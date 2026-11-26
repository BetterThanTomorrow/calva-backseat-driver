---
name: backseat-driver-internals
description: 'Backseat Driver extension internals — the Uniflow action/effect loop and its tables, app-db state as plain data, live objects, when-contexts, the tool table and tool calls, the MCP server lifecycle through vscode-mcp, the output log and eval history, activation sequences, and namespace reference. Use when: modifying core extension code, debugging state issues, working with app-db, actions, effects or dispatch, understanding activation or MCP server flow, investigating the output log or the history, or adding or registering tools.'
---

# Backseat Driver Extension Internals

Subsystem contracts, state architecture, and temporal sequences for the Backseat Driver extension. For the loop's idea, load the Uniflow skill; [dev/EX_ARCHITECTURE.md](../../../dev/EX_ARCHITECTURE.md) has what is particular to this one.

## Key Namespaces

| Namespace | Purpose |
|---|---|
| `event-handler` | The loop: handles actions, performs effects, threads awaited results |
| `app.dispatch` | Merges the action and effect tables, holds `dispatch!` and `!dispatch-log` |
| `app.db` | `!app-db`, the state as plain values |
| `app.live-objects` | The extension context, the disposables and the timers, beside the state |
| `app.when-contexts` | The when-contexts as a function of the state |
| `app.axs` | App lifecycle actions (activate, init, settings, hot reload, cleanup, deactivate) |
| `app.fxs` | App effects (logging, command and tool registration, `:uf/fx.dispatch`, deferred dispatch) |
| `mcp.axs` | MCP server lifecycle actions (start, stop, server changed, register with Cursor, handle request, registry) |
| `mcp.fxs` | vscode-mcp's config, and the effects that call vscode-mcp |
| `mcp.requests` | MCP request handler — `tools/call` here, the rest through `vscode-mcp.requests` |
| `mcp.logging` | MCP server log output |
| `tools` | The tool table, `call-tool+`, the Language Model API's entry point |
| `tools.axs` / `tools.fxs` | `[:tool/ax.call …]` and `:tool/fx.run` |
| `tools.content` | A tool's result as content parts |
| `tools.confirmation` | What a person is shown before a tool runs |
| `tools.evaluation` | The evaluation tool's pure decisions |
| `extension` | VS Code activate/deactivate entry points, hot reload hooks |
| `integrations.calva.api` | Calva API access (REPL eval, ranges, editor, document, info) |
| `integrations.calva.output` | The output log and the eval history as values |
| `integrations.calva.edit-plan` | A structural edit's pure decisions (the edit plan) |
| `integrations.calva.editor` / `editor-persist` | Gathering for, and applying, a structural edit |
| `integrations.calva.editor-util` | File context formatting (line-numbered windows) |
| `integrations.vscode.settings` | Reads the settings into `:vscode/config.<name>` keys |
| `integrations.parinfer` | Bracket balancing via Parinfer |

The stdio wrapper and the skill resources are in vscode-mcp (`vscode-mcp.stdio.wrapper`, `vscode-mcp.manifest`).

## Subsystem Contracts

```
λ app_db_contract.
  db/!app-db ≡ atom(plain_values) | prints | keys: (keys @db/!app-db)
  | settings: :vscode/config.<name> | read_at(activation ∧ onDidChangeConfiguration)
  |   read_with_plain_lookup | dotted_key ¬destructurable
  | :extension/manifest              manifest_as_data
  | :calva/output-log                sorted_map(line → entity) | cap 1000
  | :calva/history                   sorted_map(line → entity) | cap 10000 | evaluatedCode_only
  | :calva/output-line-counter       int | monotonic
  | :calva/sessions                  Calva's sessions
  | :server/running? :server/starting? :server/stopping? :server/host
  |   :server/assigned-port :server/instance-slug :server/port-file-path :server/error
  |   :lifecycle/registered-name :lifecycle/registration-error   ;; as vscode-mcp reports them
  | :app/activated? | :app/log-file-uri ∧ :calva/history-storage-uri ≡ URI_strings
  | inspection: (binding [*print-length* 5] (pr-str @db/!app-db)) | output_log ∧ history ≡ long
  | mutation: only_through_dispatch! | ¬direct_swap!

λ live_objects_contract.
  app.live-objects ≡ one_map_beside_state
  | kinds: :vscode/extension-context ∧ :extension/disposables ∧ [:timer k]
  | touched_by: effects ∧ entry_points | ¬actions

λ loop_contract.
  action ≡ [action-name & args] | handler ≡ (handler state uf-data & args) | uf-data ≡ {:system/now ms}
  | result: any_of(:uf/db next_state, :uf/fxs effects_in_order, :uf/dxs actions_after_effects)
  | effect ≡ [effect-name & args] | performed_by (effect dispatch! & args)
  | [:uf/await :ns/fx.name & args] → awaited | result replaces :uf/prev-result
  |   where(element_of later_effect_vector ∨ deferred_action)
  | dispatch! [actions] → native_promise(last_awaited_result) | ¬throws
  | [:uf/await :app/fx.return v] → gives_dispatch_its_value
  | state_effects_hook: app.when-contexts/effects → [:vscode/fx.set-contexts …] when(values_changed)
  | fails(unknown_name ∨ unknown_result_key ∨ wrong_arity ∨ two_awaiting_actions_in_batch)
  |   → throw in(development_build) | logged in(release_build)
  | native_promises_throughout | ¬promesa_in_loop

λ tables.
  ∀namespace: ends_with (def actions {…}) in */axs.cljs ∨ (def effects {…}) in */fxs.cljs
  | app.dispatch merges: (dispatch/actions) ∧ (dispatch/effects) | duplicate_name → throws
  | listing: (keys (dispatch/actions)) ∧ (keys (dispatch/effects))
  | new_namespace → add_its_table in app/dispatch.cljs
  | deferred_dispatch: :uf/fx.dispatch ∧ :uf/fx.defer-dispatch(key) ∧ :uf/fx.cancel-deferred-dispatch
  |   same_key → later_replaces_waiting

λ tool_contract.
  tools/tools ≡ one_row_per_tool | listing: (keys tools/tools)
  | row: :tool/function(var) ∧ :tool/reads(state_keys) ∧ :tool/log-line(data, keyword ≡ argument)
  |   ∧ :tool/default-max-images ∧ :tool/invocation-message ∧ :tool/confirmation
  | entry_points: MCP tools/call(mcp.requests) ∧ Language_Model_API(tools)
  |   → dispatch [:tool/ax.call {:call/tool … :call/arguments …}]
  | :tool/ax.call: check(manifest ∧ settings) → log_line → [:uf/await :tool/fx.run …]
  | function: (f perform-effect! tool-call) → promise{:result/data, :result/error?, :result/log}
  | perform-effect!: effect_vector from integrations.calva.fxs/tool-effects → promise
  | dispatch_resolves: {:result/content parts} ∨ :result/refused ∨ :result/thrown
  | unit_test: table ≡ manifest

λ output_log_contract.
  pure_functions: integrations.calva.output
  | :calva/output-log: all_output_categories: evaluationResults, evaluatedCode, evaluationOutput,
  |   evaluationErrorOutput, otherOutput, otherErrorOutput
  | :calva/history: saved_to eval-history.transit.json | cognitect.transit JSON
  |   format: {:format-version 1 :entities [...]} | save deferred 500 ms after_change
  | query: builds Datascript db from log when_it_runs | ¬connections
  entity_attributes:
    :output/line        int (monotonic, from :calva/output-line-counter)
    :output/category    string
    :output/text        string
    :output/who         string?
    :output/timestamp   ms
    :output/ns          string?
    :output/repl-session-key string?
    :output/shadow-build ∧ :output/shadow-runtime-id   when(shadow-cljs)

λ mcp_server_contract.
  vscode-mcp keeps the server | contract: ../vscode-mcp/AGENTS.md
  | config (mcp/fxs.cljs lifecycle-config): :server/name ∧ :wrapper/script-relative-path
  |   ∧ :server/request-port ∧ :registry/custom-data(sessions from state)
  |   ∧ :mcp/on-request ∧ :mcp/on-log ∧ :mcp/on-server-changed
  | start!+ ∧ maybe-start!+ ∧ stop!+ ∧ register-with-cursor!+ ∧ update-registry!+
  |   take(config) → promise(server_state)
  | [:mcp/ax.server-changed server-state] stores_it | shows(:server/error) ∧ logs(registration)
  | port: configured(default 0) | 0 ≡ random | host: mcpHost
  | port_file: ~/.config/vscode-mcp/port-files/<serverName>-<windowId>.port
  | windowId: vscode-mcp instance-slug | computed_once_at_start
  |   ws-<hash([uriScheme (or workspace-file first-folder)])> | win-<hash([uriScheme ext-host-pid])> when(¬folder)
  |   same_folder → different_id per(editor ∧ workspace_file) | written_down_paths(.eca ∧ .mcp.json) go_stale

  stdio_wrapper: dist/calva-mcp-server.js | built from vscode-mcp.stdio.wrapper
  | decisions: vscode-mcp.stdio.wrapper-axs/handle-action | shell: vscode-mcp.stdio.wrapper
  | relay(stdio ↔ socket) | one_server_per_window

λ calva_api_contract.
  calva-api ≡ {
    :repl     {:evaluateCode, :currentSessionKey, :onOutputLogged, :listSessionsAndRuntimes, :onSessionsChanged}
    :ranges   {:currentTopLevelForm, :currentEnclosingForm, ...}
    :editor   {:replace}
    :document {:getNamespace, :getNamespaceAndNsForm}
    :info     {:getSymbolInfo, :getClojureDocsDotOrg}
  }
  | accessed_at: integrations.calva.api/calva-api
  | capability_gated: tools_appear_disappear_based_on_calva_support

λ structural_editing_contract.
  editing_unit ≡ top_level_form | ¬lines_of_text
  | decisions: integrations.calva.edit-plan(the edit plan) | gather ∧ apply: editor ∧ editor-persist
  | parinfer: bracket_balancing | integrations/parinfer.cljs
  | targeting: targetLineText + line ±2 scan_window
  | rich_comments: forms_inside(comment ...) ≡ top_level
  | diagnostic_context: 21_line_window | line_numbers | → arrow_marker
  | bottom_to_top: multiple_edits_highest_line_first
```

## Temporal Sequences

```
λ activation_sequence.
  1_vscode_calls: extension.activate(context) | entry_point | checks Calva version
  2_store_context: live-objects/put! :vscode/extension-context | before_dispatch
  3_dispatch_activate: [:app/ax.activate (initial-state context)]
  |   initial-state: settings ∧ manifest_data ∧ mcp_availability ∧ log_and_history_URIs
  4_ax.activate: merge(state, initial-state) | dxs [:app/ax.init]
  5_ax.init: :app/activated? true | register_commands ∧ language_model_tools ∧ subscribe_to_settings
  |   dxs [:calva/ax.init-history] ∧ [:app/ax.start-background-work]
  6_background: when_Calva_active → subscribe(output ∧ sessions_changed)
  |   should-auto-start-mcp-server? → [:mcp/ax.start-server {:silent? true}]
  7_vscode-mcp: installs stdio_wrapper into ~/.config/calva/backseat-driver on_start
  | skip(2) → effects_without_context

λ mcp_server_lifecycle.
  start:
    1_dispatch: [:mcp/ax.start-server] | {:silent? true} → maybe-start!+
    2_await: :mcp/fx.lifecycle-start → vscode-mcp start!+ ∨ maybe-start!+
    3_server_changes: :mcp/on-server-changed → [:mcp/ax.server-changed server-state]
    4_ax.lifecycle-updated: merge(server_state) | dispatch resolves to it, nil when ¬running
    5_when_contexts: follow_from_state | started? ∧ starting? ∧ stopping? ∧ registered_with_cursor?

  request:
    1_vscode-mcp: socket_line → :mcp/on-request
    2_dispatch: [:mcp/ax.handle-request request]
    3_await: :mcp/fx.handle-request → requests/handle-request-fn
    4_response: dispatch resolves to it | vscode-mcp writes it

  stop:
    1_dispatch: [:mcp/ax.stop-server] | deactivate: {:lifecycle/silent? true}
    2_await: :mcp/fx.lifecycle-stop → vscode-mcp stop!+ | unregisters_Cursor ∧ stops_socket
    3_ax.lifecycle-stopped: merge(server_state) | dispatch resolves to true

  sessions:
    [:calva/ax.sessions-changed] → :calva/fx.read-sessions → [:calva/ax.sessions-read sessions]
    → [:mcp/ax.update-registry] → vscode-mcp update-registry!+

λ mcp_request_flow.
  tools/call → [:tool/ax.call …] → {:content parts} ∨ error_response(refused ∨ thrown)
  ∀other(initialize ∧ tools/list ∧ resources/* ∧ ping) → vscode-mcp.requests/handle-manifest-request
  |   manifest from :extension/manifest | when_clauses from settings
  resources/read: skill://… ∨ /symbol-info/… ∨ /clojuredocs/… (dispatched as tool calls)

λ tool_registration_sequence.
  1_package_json: languageModelTools manifest (name, description, inputSchema, when-clause)
  2_activation: :app/fx.register-language-model-tools → registers ∀(keys tools/tools)
  3_mcp_server: tools/list from the manifest data in the state
  4_capability_gating: manifest/tool-call-allowed? with when-clause settings | per_call
  | single_source_of_truth: package.json ∧ tools/tools | unit_test keeps them in step

λ hot_reload_sequence.
  1_edit_source: modify .cljs file | save
  2_shadow_detects: file_watcher → incremental_compile
  3_compile: cljs → js | check_for_warnings
  4_after-load: dispatch [:app/ax.hot-reloaded (mcp-availability)] | init when ¬activated
  5_verify: re_evaluate_in_repl | require_with_reload
  | zero_warnings_policy: new_warnings_stand_out_only_when_baseline_clean
  | package_json_changes → extension_host_restart_required
```

## Interactive Development Patterns

```clojure
;; Explore runtime state
(in-ns 'calva-backseat-driver.app.db)
(binding [*print-length* 5]
  (pr-str @!app-db))

;; List what the extension can decide, do, and offer
(keys (calva-backseat-driver.app.dispatch/actions))
(keys (calva-backseat-driver.app.dispatch/effects))
(keys calva-backseat-driver.tools/tools)

;; See what was dispatched, and dispatch
@calva-backseat-driver.app.dispatch/!dispatch-log
(calva-backseat-driver.app.dispatch/dispatch! [[:app/ax.cleanup]])

;; Test Calva API
(in-ns 'calva-backseat-driver.integrations.calva.api)
(keys (:repl calva-api))

;; Query output log
(calva-backseat-driver.integrations.calva.output/query
 (:calva/output-log @calva-backseat-driver.app.db/!app-db)
 "[:find ?text :where [?e :output/category \"evaluatedCode\"] [?e :output/text ?text]]"
 [])
```

## Configuration Settings

| Setting | Type | Default | Purpose |
|---|---|---|---|
| `enableMcpReplEvaluation` | boolean | true | Expose REPL eval via MCP (set `false` to disable) |
| `mcpSocketServerPort` | number | 0 | Socket server port (0 = random) |
| `autoStartMCPServer` | boolean | false | Start MCP server on activation |
| `provideBdSkill` | boolean | true | Provide Backseat Driver skill via MCP |
| `provideEditSkill` | boolean | true | Provide structural editing skill via MCP |

Settings are in the state under `:vscode/config.settingName`; a handler reads one with a plain lookup.
