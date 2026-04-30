---
name: backseat-driver-internals
description: 'Backseat Driver extension internals — Ex framework contracts, app-db state architecture, MCP socket server lifecycle, enrichment system, Datascript output log, activation sequences, and namespace reference. Use when: modifying core extension code, debugging state issues, working with app-db or Ex framework, understanding activation or MCP server flow, investigating enrichment or Datascript connections, or working with tool registration.'
---

# Backseat Driver Extension Internals

Subsystem contracts, state architecture, and temporal sequences for the Backseat Driver extension.

## Key Namespaces

| Namespace | Purpose |
|---|---|
| `ex.ex` | Dispatcher — the only entry point for state changes |
| `ex.ax` | Action routing, enrichment, accumulation |
| `ex.fx` | Effect routing (context-enriched before domain dispatch) |
| `app.db` | `!app-db` atom, `!output-conn` & `!history-conn` Datascript, serialization |
| `app.axs` | App lifecycle actions (activate, init, commands, cleanup) |
| `app.fxs` | App effects (logging, command registration, Language Model tools) |
| `db.axs` | Generic state mutation actions (`assoc-in`, `update-in`) |
| `mcp.axs` | MCP server lifecycle actions (start/stop/error/handle-request) |
| `mcp.fxs` | MCP effects (start/stop server, notifications, request handling, wrapper copy) |
| `mcp.server` | TCP socket server implementation (low-level net, port file, buffering) |
| `mcp.requests` | MCP protocol handler — tool listings, initialize, tools/call dispatch |
| `mcp.skills` | Skill frontmatter parsing, filtering, instruction composition |
| `mcp.logging` | MCP server log output |
| `extension` | VS Code activate/deactivate entry points |
| `integrations.calva.api` | Calva API access (REPL eval, ranges, editor, document, info) |
| `integrations.calva.editor` | Structural editing implementation |
| `integrations.calva.editor-util` | File context formatting (line-numbered windows) |
| `integrations.vscode.tools` | VS Code Language Model tool registration |
| `integrations.parinfer` | Bracket balancing via Parinfer |
| `stdio.wrapper` | MCP stdio-to-socket relay (`dist/calva-mcp-server.js`) |

## Subsystem Contracts

```
λ app_db_contract.
  db/!app-db ≡ atom {
    :vscode/extension-context    ExtensionContext     ;; set before dispatch, circular refs
    :app/getConfiguration        fn                   ;; VS Code config reader

    ;; From init-db
    :extension/disposables       [Disposable]         ;; VS Code subscriptions
    :extension/when-contexts     {:calva-mcp-extension/activated? bool
                                  :calva-backseat-driver/started? bool
                                  :calva-backseat-driver/starting? bool
                                  :calva-backseat-driver/stopping? bool}
    :calva/output-line-counter   int                  ;; monotonic for output entities

    ;; From initial-state (extension.cljs)
    :app/log-file-uri            Uri                  ;; log location
    :app/min-log-level           keyword              ;; :debug/:info/:warn/:error
    :mcp/wrapper-config-path     string               ;; ~/.config/calva/backseat-driver
    :calva/history-storage-uri   Uri|nil              ;; eval-history.transit.json

    ;; Runtime
    :app/log-dir-initialized+    promise              ;; resolved when log dir exists
    :app/server-info             {:server/instance Server
                                  :server/port int
                                  :server/port-file-uri Uri
                                  :server/port-note string?}
  }
  | inspection: always(dissoc :vscode/extension-context) | circular_ref_guard
  | mutation: only_through_dispatch! | ¬direct_swap!

λ ex_dispatcher_contract.
  dispatch!(context, actions) ≡ the_only_state_entry_point
  | ~16 lines of code | the entire framework
  |
  | flow:
  |   ax/handle-actions(@!app-db, context, actions)
  |     reduce_over_actions:
  |       1. enrich-from-context(action, context)    → :context/x.y → js-get-in
  |       2. enrich-from-state(action, state)        → [:db/get k], :vscode/config.x
  |       3. route_by(namespace action-kw) → domain_handler
  |       4. handler_returns {:ex/db, :ex/fxs, :ex/dxs}
  |     accumulate: merge_db, into_fxs(enriched_from_state), into_dxs
  |   when db → reset!(!app-db)
  |   when dxs → recursive dispatch!(context, dxs)
  |   when fxs → for_each: enrich_from_context → route → execute

λ enrichment_contract.
  context_enrichment:
    :context/x.y.z → js-get-in(context, ["x" "y" "z"])
    | applied_to: actions ∧ effects
  state_enrichment:
    [:db/get key] → (get state key)
    :vscode/config.settingName → VS Code configuration API
    | applied_to: actions_only (effects get context enrichment only)
  args_enrichment:
    :ex/action-args → entire_result_from_effect
    :ex/action-args%N → (nth result (dec N))
    | applied_to: continuation_actions_via :ex/then

λ action_routing.
  (namespace action-kw) → handler_namespace:
    "app"      → app.axs
    "mcp"      → mcp.axs
    "db"       → db.axs
    "vscode"   → integrations.vscode (via axs)
    "node"     → integrations.node (via axs)
    "calva"    → integrations.calva (via axs)
  | new_domain → add_case_in ex/ax.cljs handle-action

λ effect_routing.
  (namespace fx-kw) → handler_namespace:
    "app"      → app.fxs
    "mcp"      → mcp.fxs
    "vscode"   → integrations.vscode (via fxs)
    "node"     → integrations.node (via fxs)
    "calva"    → integrations.calva (via fxs)
  | effects_receive(dispatch!, context, enriched-fx)
  | new_domain → add_case_in ex/fx.cljs perform-effect!

λ datascript_contract.
  !output-conn ≡ session_scoped | schemaless | cap: 1000 entities
    | all_output_categories: evaluationResults, evaluatedCode, evaluationOutput,
    |   evaluationErrorOutput, otherOutput, otherErrorOutput
  !history-conn ≡ persistent | schema: {:output/line {:db/unique :db.unique/identity}}
    | cap: 10000 | evaluatedCode_only
    | serialized_to: eval-history.transit.json | cognitect.transit JSON
    | format: {:format-version 1 :entities [...]}
  entity_attributes:
    :output/line        int (monotonic, from :calva/output-line-counter)
    :output/category    string
    :output/text        string
    :output/who         string?
    :output/timestamp   inst
    :output/ns          string?
    :output/repl-session-key string?

λ mcp_server_contract.
  socket_server: TCP | net.createServer | localhost_only
  | port: configured(default 1664) | 0 ≡ random | EADDRINUSE → fallback_to_0
  | port_file: ${workspaceFolder}/.calva/mcp-server/port
  | protocol: newline_delimited_JSON | buffered_partial_reads
  | active_sockets: atom | tracks_connected_clients
  | notifications: broadcast_to_all_active_sockets

  stdio_wrapper: dist/calva-mcp-server.js
  | reads_port_from_port_file → TCP_connect → relay(stdio ↔ socket)
  | one_server_per_workspace_folder

λ calva_api_contract.
  calva-api ≡ {
    :repl     {:evaluateCode, :currentSessionKey, :onOutputLogged}
    :ranges   {:currentTopLevelForm, :currentEnclosingForm, ...}
    :editor   {:replace}
    :document {:getNamespace, :getNamespaceAndNsForm}
    :info     {:getSymbolInfo, :getClojureDocsDotOrg}
  }
  | accessed_at: integrations.calva.api/calva-api
  | capability_gated: tools_appear_disappear_based_on_calva_support

λ structural_editing_contract.
  editing_unit ≡ top_level_form | ¬lines_of_text
  | parinfer: bracket_balancing | integrations/parinfer.cljs
  | targeting: targetLineText + line ±2 scan_window
  | rich_comments: forms_inside(comment ...) ≡ top_level
  | diagnostic_context: 21_line_window | line_numbers | → arrow_marker
  | bottom_to_top: multiple_edits_highest_line_first
```

## Temporal Sequences

```
λ activation_sequence.
  1_vscode_calls: extension.activate(context) | entry_point
  2_store_context: assoc(:vscode/extension-context) into !app-db | before_dispatch
  3_store_config_reader: assoc(:app/getConfiguration) | vscode/workspace.getConfiguration
  4_dispatch_activate: [:app/ax.activate (initial-state context)]
  5_ax.activate: merge(init-db, initial-state) | set when-contexts | dispatch ax.init
  6_ax.init: register_commands ∧ language_model_tools | init_output_listener
  7_fx.init-logging: ensure_log_directory | resolve(:app/log-dir-initialized+)
  8_fx.copy-wrapper-script: copy(calva-mcp-server.js) to(~/.config/calva/backseat-driver/)
  9_auto_start_check: if(:vscode/config.autoStartMCPServer) → dispatch(ax.start-server)
  | skip(2) → enrichment_fails | context_not_available
  | skip(8) → stdio_wrapper_missing | MCP_clients_cant_connect

λ mcp_server_lifecycle.
  start:
    1_dispatch: [:mcp/ax.start-server]
    2_set_starting: when-context(starting? → true, stopping? → false)
    3_fx.start-server → server/start-server!+
      3a_create_server: net.createServer(TCP)
      3b_listen: configured_port | EADDRINUSE → port_0
      3c_write_port_file: .calva/mcp-server/port
    4_ax.server-started: store(:app/server-info) | when-context(started? → true, starting? → false)
    5_fx.show-server-started-message: info_notification_with_port

  client_connection:
    1_socket_connects: stdio_wrapper → TCP_connect(port)
    2_data_handler: UTF-8 | buffer → split(\n) → parse_JSON
    3_dispatch: [:mcp/ax.handle-request parsed-message]
    4_fx.handle-request → requests/handle-request-fn
    5_response: write_JSON\n_to_socket

  stop:
    1_dispatch: [:mcp/ax.stop-server]
    2_set_stopping: when-context(stopping? → true)
    3_fx.stop-server → server/stop-server!+
      3a_end_sockets: end+destroy_all_active_sockets
      3b_close_server: server.close()
      3c_delete_port_file: vscode_workspace.fs | node_fs_fallback
    4_ax.server-stopped: clear(:app/server-info) | when-context(started? → false, stopping? → false)

λ mcp_request_flow.
  initialize → {:protocolVersion "2024-11-05", :capabilities, :instructions (compose-instructions)}
  tools/list → read(package.json languageModelTools) → transform_to_mcp_format | capability_filtered
  tools/call → multimethod(handle-tool-call) by(:name) → {:content [{:type :text :text result}]}
  resources/list → skill_manifests | filtered_by_settings
  resources/read → read(assets/skills/{name}/SKILL.md) | full_content

λ tool_registration_sequence.
  1_package_json: languageModelTools manifest (name, description, inputSchema, when-clause)
  2_activation: ax.register-language-model-tools → fx.register-language-model-tools
  3_vscode_api: tools.cljs registers with Language Model API
  4_mcp_server: requests.cljs reads same manifests at runtime
  5_capability_gating: tools conditionally available based on Calva version + settings
  | single_source_of_truth: package.json | change_once → applied_everywhere

λ hot_reload_sequence.
  1_edit_source: modify .cljs file | save
  2_shadow_detects: file_watcher → incremental_compile
  3_compile: cljs → js | check_for_warnings
  4_extension_host: picks_up_changes | ¬restart_needed
  5_verify: re_evaluate_in_repl | require_with_reload
  | zero_warnings_policy: new_warnings_stand_out_only_when_baseline_clean
  | package_json_changes → extension_host_restart_required
```

## Interactive Development Patterns

```clojure
;; Explore runtime state
(in-ns 'calva-backseat-driver.app.db)
(dissoc @!app-db :vscode/extension-context)

;; Test Calva API
(in-ns 'calva-backseat-driver.integrations.calva.api)
(keys (:repl calva-api))

;; Inspect MCP server state
(in-ns 'calva-backseat-driver.mcp.server)
@active-sockets

;; Query output log
(in-ns 'calva-backseat-driver.app.db)
(d/q '[:find ?text :where [?e :output/category "evaluatedCode"] [?e :output/text ?text]] @!output-conn)

;; Test enrichment
(in-ns 'calva-backseat-driver.ex.ax)
(enrich-from-context [:some/ax.test :context/subscriptions] context)
```

## Configuration Settings

| Setting | Type | Default | Purpose |
|---|---|---|---|
| `enableMcpReplEvaluation` | boolean | false | Enable REPL eval via MCP |
| `mcpSocketServerPort` | number | 1664 | Socket server port (0 = random) |
| `autoStartMCPServer` | boolean | false | Start MCP server on activation |
| `provideBdSkill` | boolean | true | Provide Backseat Driver skill via MCP |
| `provideEditSkill` | boolean | true | Provide structural editing skill via MCP |

Settings accessed via enrichment: `:vscode/config.settingName` in action handlers.
