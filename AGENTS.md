# Calva Backseat Driver - AI Agent Guide

ClojureScript VS Code extension providing REPL access to AI agents via Language Model API and MCP. Enables Interactive Programming where AI evaluates code in the user's running environment rather than guessing.

When authoring code and test, ask yourself: Will this and the tests work on Windows too?

## Essential Architecture

### The Loop (Uniflow)
Functional core with unidirectional flow, following the Uniflow idea (see [dev/EX_ARCHITECTURE.md](dev/EX_ARCHITECTURE.md)):
```
dispatch! [[action & args] …] → handlers → {:uf/db, :uf/fxs, :uf/dxs} → update state → perform effects → dispatch :uf/dxs
```

**Actions** (`*/axs.cljs`): named pure functions `(handler state uf-data & args)`, where `uf-data` is `{:system/now ms}`. Each namespace ends with its table: `(def actions {:calva/ax.add-output #'add-output …})`
**Effects** (`*/fxs.cljs`): `(effect dispatch! & args)`, listed the same way in `(def effects {…})`
**Dispatch**: `src/calva_backseat_driver/app/dispatch.cljs` merges the tables and holds `dispatch!`, which takes a vector of actions and returns a native promise
**State**: one `!app-db` atom in `src/calva_backseat_driver/app/db.cljs`, written only by the loop

### Runtime State Structure
The state holds plain values only, so it prints. List its keys with `(keys @calva-backseat-driver.app.db/!app-db)`. Among them:
- `:vscode/config.<name>`: every setting the manifest declares, read at activation and on `onDidChangeConfiguration` (`integrations/vscode/settings.cljs`)
- `:extension/manifest`: the manifest as data
- `:calva/output-log` and `:calva/history`: sorted maps from line to entity (see below)
- `:calva/sessions`: Calva's sessions, for the window's registry entry
- `:server/running?`, `:server/assigned-port`, `:server/port-file-path`, `:server/error`, `:lifecycle/registered-name` and the rest of the MCP server's state, as vscode-mcp reports it
- `:app/log-file-uri` and `:calva/history-storage-uri`, as URI strings

The extension context, the disposables and the timers are live objects, kept in one map beside the state by `app/live_objects.cljs`. Only effects and entry points touch them. The when-contexts are computed from the state by `app/when_contexts.cljs`, and set when a state change gives them other values.

### Output Log and Eval History
- `:calva/output-log`: all REPL output categories. Cap: 1000 entities. An output log query builds a Datascript db from it when it runs.
- `:calva/history`: `evaluatedCode` only. Cap: 10000 entities. Saved to `eval-history.transit.json` under `context.storageUri`.
- The pure functions for both are in `integrations/calva/output.cljs`.

### Calva API Integration
Available at `calva-backseat-driver.integrations.calva.api/calva-api`:
```clojure
{:repl     {:evaluateCode, :currentSessionKey, :onOutputLogged
            :listSessionsAndRuntimes, :onSessionsChanged}
 :ranges   {:currentTopLevelForm, :currentEnclosingForm, ...}
 :editor   {:replace}
 :document {:getNamespace, :getNamespaceAndNsForm}
 :info     {:getSymbolInfo, :getClojureDocsDotOrg}}
```

## Development Workflow

### REPL Setup (ClojureScript)

Co-dev tower (vscode-mcp sibling): (1) git pin in `deps.edn`, (2) `:local-dev` overlay, (3) npm scripts — `watch`/`compile` = pin, `watch:local` = local, (4) VS Code tasks — default **Watch** → `watch:local`, pinned → `watch`. Task `"script"` names must exist in `package.json`.

```bash
npm run watch           # git SHA pin from deps.edn
npm run watch:local     # shadow-cljs + nREPL via :local-dev (sibling ../vscode-mcp)
# Connect Calva: Ctrl+Alt+C Ctrl+Alt+C
# Launch Extension Host: F5
```

In the `backseat-driver-dev` workspace, the window does all of this when it opens, and agents restart the watch and the dev Extension Host themselves: see the `dev-environment` skill in `../backseat-driver-dev/.github/skills/dev-environment/SKILL.md`.

Day-to-day co-development: run the default **Watch** task (`watch:local`), not `npm run watch`. When the library stint is done: commit vscode-mcp, then bump and commit the `:git/sha` pin in `deps.edn` here; the human pushes vscode-mcp first, then Backseat Driver. Use **Watch (pinned vscode-mcp)** or `npm run watch` to verify the pin. Full process: vscode-mcp `AGENTS.md` Co-development.

**Critical**: This is a ClojureScript project running in VS Code Extension Host (Node.js). Use `cljs` REPL session, not `clj`.

### Interactive Development Pattern
```clojure
;; Explore runtime state (the output log and the history are long)
(in-ns 'calva-backseat-driver.app.db)
(binding [*print-length* 5]
  (pr-str @!app-db))

;; Test Calva API
(in-ns 'calva-backseat-driver.integrations.calva.api)
(keys (:repl calva-api))

;; Test utilities
(in-ns 'calva-backseat-driver.integrations.calva.editor)
(require '[calva-backseat-driver.integrations.calva.editor-util :as util])
(util/get-context-lines sample-text 3 10)
```

### Testing Commands
```bash
bb run-e2e-tests-ws     # Full E2E (exercises MCP server for many tests), in Docker
bb run-e2e-tests-ws --local  # The same, in a VS Code on this machine
bb run-e2e-tests-ws --local --vscode-executable /Applications/Cursor.app/Contents/MacOS/Cursor  # The same, in another editor, such as Cursor
bb package-pre-release  # Package pre-release VSIX
```

`bb run-mcp-inspector` remains available for interactive/ad-hoc exploration of MCP tools when needed, but is not part of the daily verification routine.

**Verification after changes**:
- Full MCP coverage comes from the e2e tests + the `bd-tester` agent in the example/test project used inside the dev Extension Host.
- For any change affecting the MCP server, tools, port file, or Cursor/ECA auto-registration: after the change, launch the dev Extension Host (F5), use `bd-tester` to specifically test the modified behavior, and frequently run its full protocol as a regression check.

**Note**: `bb run-e2e-tests-ws` runs the tests in a Linux container on a virtual display (`e2e-test-ws/run-containerized.sh`), against the `out/` your build keeps current, so no test window opens on your screen. It needs Docker. With `--local` they run in a VS Code on this machine; from a VS Code terminal, unset `ELECTRON_RUN_AS_NODE` first. `--vscode-executable` takes the editor to run them in, with `--local`. `e2e-test-ws/.joyride/src/tests/mcp/cursor_test.cljs` tests the Cursor registration, and its tests are skipped in an editor without Cursor's registration API. Detailed output goes to .tmp/e2e-output.log — read that file for details, if needed. The command outputs a very brief summary. Don't pipe or redirect.

### E2E Testing
Load the `e2e-testing` skill (`.github/skills/e2e-testing/SKILL.md`) when writing, modifying, or debugging e2e tests. It covers test runner mechanics, SCI's async functions, shared helpers, session lifecycle patterns, and data shape through MCP. The e2e suite (plus the `bd-tester` agent in the dev extension host's test project) provides full coverage of the MCP server/tools protocol.

Key rule: a condition is polled with `wait-for+`, never waited for with a fixed `delay+` or `setTimeout`. `delay+` in `e2e.utils` is for the tests' own waits, such as the pause in a poll loop.

## Key Implementation Patterns

### Awaited Effects
An effect written as `[:uf/await :some/fx.name & args]` is awaited before the next one runs. Its result replaces `:uf/prev-result` where that is an element of a later effect vector or of a deferred action:
```clojure
;; In an action handler:
{:uf/fxs [[:uf/await :calva/fx.read-sessions]]
 :uf/dxs [[:calva/ax.sessions-read :uf/prev-result]]}

;; A dispatch resolves to the chain's last awaited result. To give it a value:
{:uf/fxs [[:uf/await :app/fx.return v]]}
```

A name that is in no table, a handler result with an unknown key, a vector with the wrong number of arguments, and two awaiting actions in one batch each fail: they throw in a development build and are logged in a release build. The var `*development-build?*` in `event_handler.cljs` says which, and tests set it. vscode-mcp has a copy of `event_handler.cljs`, the same text apart from the namespace name, so a change to one goes to the other. `:uf/fx.dispatch`, `:uf/fx.defer-dispatch` (with a key; a later one with the same key replaces the one waiting) and `:uf/fx.cancel-deferred-dispatch` are in `app/fxs.cljs`.

### Async Functions
Promise code in `src` and `test` is written as async functions, with no promise library. ClojureScript 1.12.145 and shadow-cljs 3.5.14 compile them:
```clojure
(defn ^:async save-document+ [^js document]
  (let [saved? (await (.save document))]
    {:saved saved?}))
```
- Inside an async function a promise is awaited. The compiler awaits the expressions it wraps in a function of their own: a `do`, `let` or `try` used as a value, and a call of a local that isn't a `fn` literal when its arguments aren't plain values. A promise from such an expression can't be handed on, or chained with `.then`. A helper that takes the function and its arguments, and makes the call itself, gets the promise.
- A function that only hands a promise on stays a plain function, as `dispatch!` does.
- `await` takes any thenable. VS Code's `document.save()`, `workspace.applyEdit` and `window.showInformationMessage` return thenables that aren't native promises.
- A test of async code is `(deftest ^:async …)` that awaits what it checks, one async test per `deftest`.

### File Context Feature
When structural editing fails, returns diagnostic context:
```clojure
;; 21 lines around target with line numbers and marker
" 13 | (defn subtract-numbers
  14 |   \"Subtracts b from a\"
  15 |   [a b]
  16 |   (- a b))
→ 17 |
  18 | (defn add-numbers
  ..."
```

Implemented in `integrations/calva/editor-util.cljs`:
```clojure
(util/get-context-lines doc-text line-number context-size)
;; Returns formatted string with line numbers and → marker at target
```

### Naming Conventions
- **Side effects**: `function-name!`
- **Promises**: `function-name+`
- **Inline debugging**: `(def var var)` inside REPL comment blocks
- **Test names**: kebab-case describing area/thing (`file-context-formatting`)

## Structural Editing Tools

### Text Targeting
The `clojure_edit_files` tool uses `targetLineText` (first line of target form) + line number ±2:
```clojure
;; clojure_edit_files batch with replace/insert edits
{:edits [{:type "replace"
          :filePath "/absolute/path.clj"
          :line 23
          :targetLineText "(defn multiply-numbers"
          :newForm "(defn multiply-numbers\n  [x y]\n  (* x y))"}]}
```

### Automatic Features
- **Bracket balancing**: Parinfer-powered (`integrations/parinfer.cljs`)
- **Rich Comment support**: Forms inside `(comment ...)` treated as top-level
- **Diagnostic context**: On failure, returns 21-line context + remedy message
- **Saving**: An edit to a file without unsaved changes is saved. The edit flow (`integrations/calva/editor.cljs`, `editor_persist.cljs`, `features.cljs`) awaits `applyEdit` and the document's save, so an edit is reported with `:saved true` when VS Code has saved it, with whatever takes part in the save, such as format on save. A file with unsaved changes keeps the edit in its buffer, with `:saved false`

### Critical: Bottom-to-Top Editing
When making multiple edits, work from highest line number to lowest (line numbers shift down as you edit above).

## MCP Server Architecture

### Socket Server Pattern
- Backend server runs in Extension Host (TCP socket)
- stdio wrapper (`dist/calva-mcp-server.js`) relays stdio ↔ socket — built from `vscode-mcp` via shadow-cljs `:stdio-wrapper` (`vscode-mcp.stdio.wrapper/main`). Connect-retry: waits up to 60 s for port file + TCP connect, re-reading the port file each attempt; stdin is buffered during the wait and flushed on connect. The wrapper's decisions are in `vscode-mcp.stdio.wrapper-axs/handle-action`, with `vscode-mcp.stdio.wrapper` as its shell
- vscode-mcp keeps the server. `mcp/fxs.cljs` builds the config it runs from: `:server/name`, `:wrapper/script-relative-path`, `:server/request-port`, `:registry/custom-data` (Calva's sessions from the state), and three functions, `:mcp/on-request`, `:mcp/on-log` and `:mcp/on-server-changed`. `start!+`, `maybe-start!+`, `stop!+`, `register-with-cursor!+` and `update-registry!+` take the config and resolve to the server's state, and `[:mcp/ax.server-changed server-state]` stores it. vscode-mcp's own contract is in `../vscode-mcp/AGENTS.md`
- One server per window
- Port file: `~/.config/vscode-mcp/port-files/calva-backseat-driver-<window-id>.port`. The window id comes from vscode-mcp `instance-slug`, computed once at server start: `ws-<hash>` of the editor `uriScheme` plus the `.code-workspace` file, or plus the first folder when there is no workspace file. A window with nothing open gets `win-<hash>` of `uriScheme` plus the Extension Host pid. So the same folder gets a different id in Cursor and in VS Code, and a different one again when it's opened through another workspace file. A port-file path written down once (a project `.mcp.json`, `~/.claude.json`, `.eca/config.json` with the registry off) only works for the window that wrote it
- Window registry: `:registry/enabled?` from setting `enableMcpRegistry` (default true) in `mcp/fxs.cljs` `lifecycle-config`; `:registry/custom-data` carries the compact sessions made from `:calva/sessions`. Entry path: `~/.config/vscode-mcp/registry/windows/calva-backseat-driver-<window-id>.json`. Session changes go through `[:calva/ax.sessions-changed]` → `:calva/fx.read-sessions` → `[:calva/ax.sessions-read sessions]` → `[:mcp/ax.update-registry]` → `(vscode-mcp.core/update-registry!+ config)`

### Cursor registration lifecycle
- Stop closes the socket and removes the port file, then unregisters from Cursor (best-effort), then removes the registry entry. The next registration gets a new name (vscode-mcp's Cursor registration). A server that is still starting is left to start
- Deactivate dispatches `[:mcp/ax.stop-server {:lifecycle/silent? true}]`, the same teardown without a message
- Register command: `calva-backseat-driver.registerMcpServerWithCursor` dispatches `[:mcp/ax.register-with-cursor]`, which awaits `register-with-cursor!+` (starts the server when it doesn't run, then registers). Enablement: `:calva-backseat-driver/cursor-mcp-available? && !:calva-backseat-driver/mcp-server-registered-with-cursor?`
- When-contexts: `app/when_contexts.cljs` computes `:calva-backseat-driver/cursor-mcp-available?` and `:calva-backseat-driver/mcp-server-registered-with-cursor?` (registered only while the server is running) from the state

### ECA registration
- Project-local `.eca/config.json` only
- Setting `autoRegisterEcaMcp` (default true); library key `:mcp/auto-register-eca?`
- Gates: ECA extension `editor-code-assistant.eca` installed (activated before write), workspace folder, port file from `server-info`
- Managed fields only (`command`, `args`); independent of Cursor; no deregister on stop; no command or when-contexts
- ECA entry: `node -e "require(require('path').join(require('os').homedir(),'.config','calva','backseat-driver','calva-mcp-server.js'))" calva-mcp-server.js` — the same on every machine; the wrapper (installed into `~/.config/calva/backseat-driver` by vscode-mcp on MCP start — DEBUG symlink / release copy) gets no port file and connects to the caller's window via the registry. With `enableMcpRegistry` off, the args end with the port file's absolute path and the host from settings

### Skills and Instructions as Bundled Assets
- `assets/skills/` and `assets/instructions/` are the canonical source for content bundled with the extension. When updating skill or instruction content, edit these files — not the installed extension copies under `~/.vscode*/extensions/`.
- Skills declared in `package.json` under `contributes.chatSkills` are exposed as MCP resources
- Discovery: `resources/list` returns one entry per enabled skill (name, description, canonical URI `skill://{name}/SKILL.md`). For the full catalog, read `skill://index.json` via `resources/read` (not listed in `resources/list`; gated like list entries)
- Reading: `resources/read` at `skill://{name}/SKILL.md` returns the skill entry point; bare `skill://{name}` is a read alias. Sibling files under the skill directory (e.g. `skill://{name}/references/…`) are readable but not listed
- Dynamic instructions: the `initialize` response includes instructions composed from available tools and skills (full skill URIs; may mention `skill://index.json`)
- Implementation: `vscode-mcp.manifest` (`get-resources`, `build-server-instructions`, index builder, sibling reads, skills extension capability) — wired from BD via `mcp/requests.cljs` and the vscode-mcp dependency pin

### Configuration Access
The settings are in the state under `:vscode/config.<name>` keys. A handler reads one with a plain lookup, `(:vscode/config.mcpHost state)`, since a dotted key can't be destructured:
```clojure
:vscode/config.enableMcpReplEvaluation  ; boolean (default true — set false to disable MCP eval)
:vscode/config.mcpSocketServerPort      ; number (default 0, the server picks a port)
:vscode/config.autoStartMCPServer       ; boolean
:vscode/config.autoRegisterCursorMcp    ; boolean (default true)
:vscode/config.autoRegisterEcaMcp       ; boolean (default true)
:vscode/config.enableMcpRegistry        ; boolean (default true) — write window registry entries
:vscode/config.provideBdSkill           ; boolean (default true) — enable/disable Backseat Driver skill
:vscode/config.provideEditSkill         ; boolean (default true) — enable/disable structural editing skill
```

## Adding New Features

### New Skill
1. Create `assets/skills/{name}/SKILL.md` with YAML frontmatter (`name`, `description`)
2. Add entry to `package.json` → `contributes.chatSkills`: `{"path": "./assets/skills/{name}/SKILL.md"}`
3. The MCP server picks it up automatically — no code changes needed

### New Tool Implementation
Both entry points, MCP `tools/call` in `mcp/requests.cljs` and the Language Model API in `tools.cljs`, dispatch `[:tool/ax.call {:call/tool … :call/arguments …}]` (`tools/axs.cljs`). It checks the call against the manifest and the settings, writes the log line, and awaits `:tool/fx.run`. A tool's function is `(f perform-effect! tool-call)` and returns a promise of `{:result/data …}`; `perform-effect!` takes an effect vector from `integrations.calva.fxs/tool-effects`.
1. **Tool manifest**: `package.json` → `languageModelTools`
2. **Tool table row**: `tools/tools` in `src/calva_backseat_driver/tools.cljs` (`:tool/function`, `:tool/reads`, `:tool/log-line`, `:tool/invocation-message`, `:tool/confirmation`)
3. **Confirmation**: a function in `src/calva_backseat_driver/tools/confirmation.cljs`
4. **Unit tests**: a test checks the tool table against the manifest

### Action/Effect Example
```clojure
;; Action (pure, in mcp/axs.cljs, listed in its `actions` table)
(defn- open-server-log [state _uf-data]
  {:uf/fxs [[:uf/await :vscode/fx.show-text-document-at (:app/log-file-uri state)]]})

;; Effect (side effect, in app/fxs.cljs, listed in its `effects` table)
(defn- register-command [dispatch! command-id actions]
  (live-objects/add-disposables!
   [(vscode/commands.registerCommand command-id
                                     (fn [& _args]
                                       (.then (dispatch! actions) clj->js)))]))
```

## Common Pitfalls

- **DON'T** access `@!app-db` directly from helpers (pass data explicitly)
- **DON'T** perform side effects in action handlers (return effects instead)
- **DON'T** put a live object (a VS Code or Node object) in the state (keep it in `app/live_objects.cljs`)
- **DON'T** forget namespace reloads after file edits: `(require 'ns :reload)`
- **DO** evaluate in REPL before file edits
- **DO** use `cljs` session (this is ClojureScript, not Clojure)
- **DO** check `:calva/output-log` in app-db, or `@calva-backseat-driver.app.dispatch/!dispatch-log`, to see what happened
- **DO** work bottom-to-top when multiple structural edits

## Quick Reference

**Key Namespaces**:
- `calva-backseat-driver.event-handler` - The loop
- `calva-backseat-driver.app.dispatch` - The action and effect tables, `dispatch!`, the dispatch log
- `calva-backseat-driver.app.{axs,fxs,db,live-objects,when-contexts}` - Application layer
- `calva-backseat-driver.mcp.{requests,axs,fxs}` - MCP server wiring
- `calva-backseat-driver.tools` and `calva-backseat-driver.tools.*` - The tool table and the tool call
- `calva-backseat-driver.integrations.calva.{api,editor,edit-plan,editor-util,output}` - Calva integration

**REPL Inspection Commands**:
```clojure
(binding [*print-length* 5] (pr-str @calva-backseat-driver.app.db/!app-db))  ; The state
(keys (calva-backseat-driver.app.dispatch/actions))    ; Every action
(keys (calva-backseat-driver.app.dispatch/effects))    ; Every effect
(keys calva-backseat-driver.tools/tools)               ; Every tool
@calva-backseat-driver.app.dispatch/!dispatch-log      ; The last 200 dispatches (development build)
(calva-backseat-driver.app.dispatch/dispatch! [[:app/ax.cleanup]])  ; Dispatch from the REPL
(keys calva-backseat-driver.integrations.calva.api/calva-api)  ; Available Calva APIs
```

**Build Artifacts**:
- `out/extension.js` - Main extension bundle
- `dist/calva-mcp-server.js` - MCP stdio wrapper
- `out/extension-tests.js` - Unit tests (they load every namespace; `test/vscode_stub.js` stubs `vscode` with named exports, with enough of `workspace` for them to drive the edit flow). `npm run release` compiles them too, and a release compile refuses an assignment to `goog.DEBUG`, so a test of what a release build does sets `event-handler/*development-build?*`

---

For deeper architecture details, see:
- `dev/EX_ARCHITECTURE.md` - The loop: what is particular to Backseat Driver's
- `dev/MCP_OVERVIEW.md` - MCP protocol specifics
- `dev/PROJECT_SUMMARY.md` - Complete project overview
