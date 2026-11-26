---
description: 'Backseat Driver extension source code conventions — the Uniflow loop (action and effect tables, awaited effects), async, state management, live objects, namespace separation, and naming. Supplements universal Clojure patterns with Backseat Driver-specific rules.'
applyTo: 'src/calva_backseat_driver/**,test/calva_backseat_driver/**'
---

# Backseat Driver Source Conventions

## Naming Conventions

- `function!` — side effects
- `function+` — returns promise
- `function!+` — side effects and promise
- `function?` — predicate
- `!atom-name` — atom (e.g., `!app-db`, `!dispatch-log`)
- `ax.` prefix — pure action handler
- `fx.` prefix — impure effect handler
- `uf/` namespace — the loop's own keys and effects, not domain logic

## The Loop

The loop is `event_handler.cljs`, following the Uniflow idea. `app/dispatch.cljs` merges the domain tables and holds `dispatch!`.

### Actions (axs.cljs)

An action is a vector, `[action-name & args]`. Its handler is a named pure function `(handler state uf-data & args)`, where `uf-data` is `{:system/now ms}`. It returns a map with any of:

```clojure
{:uf/db  new-state                ;; the next state
 :uf/fxs [[:ns/fx.name ...]]      ;; effects, performed in order
 :uf/dxs [[:ns/ax.name ...]]}     ;; actions dispatched after the effects
```

Actions never touch atoms, VS Code APIs or live objects. What they need is in the state: the settings under `:vscode/config.<name>` (read with a plain lookup, since a dotted key can't be destructured), the manifest under `:extension/manifest`. What comes from outside arrives as an action argument.

### Effects (fxs.cljs)

An effect is `(effect dispatch! & args)`. It performs the side effect, and may dispatch new actions or return a value (or a promise of one):

```clojure
(defn- list-sessions-and-runtimes
  [_dispatch!]
  (.then (js/Promise.resolve ((get-in calva/calva-api [:repl :listSessionsAndRuntimes])))
         (fn [sessions]
           (or (js->clj sessions :keywordize-keys true)
               []))))
```

### Async Continuation

An effect written as `[:uf/await :ns/fx.name & args]` is awaited before the next one runs. Its result replaces `:uf/prev-result` where that is an element of a later effect vector or of a deferred action:

```clojure
{:uf/fxs [[:uf/await :calva/fx.read-history (:calva/history-storage-uri state)]]
 :uf/dxs [[:calva/ax.history-read :uf/prev-result]]}
```

A dispatch resolves to the chain's last awaited result; `[:uf/await :app/fx.return v]` gives it a value. Two awaiting actions in one batch fail, so dispatch each on its own. Native promises throughout the loop.

### Async Functions

Promise code is an async function, `(defn ^:async f+ [...] (await ...))`, with no promise library. `await` takes any thenable, such as what `document.save()` and `workspace.applyEdit` return. A function that only hands a promise on stays a plain function. A test of async code is `(deftest ^:async …)`. What the compiler awaits on its own, so that a promise can't be handed on or chained with `.then`, is in `AGENTS.md` under Async Functions.

### Tables

Each namespace ends with its table: `(def actions {:calva/ax.add-output #'add-output …})` in `*/axs.cljs`, `(def effects {…})` in `*/fxs.cljs`. A new namespace's table goes into `actions` or `effects` in `app/dispatch.cljs`. A name that is in no table, a handler result with an unknown key, and a vector with the wrong number of arguments each fail: they throw in a development build and are logged in a release build, as `event-handler/*development-build?*` says. A test sets that var, and never assigns `goog.DEBUG`, which a release compile of the tests refuses. vscode-mcp has a copy of `event_handler.cljs`, so a change to one goes to the other.

## State Management

- All state in `app/db.cljs` — the `!app-db` atom, plain values only, so it prints
- State changes only through `dispatch!` — never direct `swap!` or `reset!` on `!app-db`
- Live objects (the extension context, the disposables, the timers) are in `app/live_objects.cljs`, beside the state. Only effects and entry points touch them
- When-contexts are computed from the state by `app/when_contexts.cljs`
- Pass data explicitly to helper functions — helpers must not access atoms directly
- Print the state with a `*print-length*`: the output log and the history are long

## Output Log and Eval History

- `:calva/output-log` — sorted map from line to entity, cap 1000, all output categories
- `:calva/history` — sorted map from line to entity, cap 10000, `evaluatedCode` only, saved to `eval-history.transit.json`
- Pure functions in `integrations/calva/output.cljs`; an output log query builds a Datascript db from the log when it runs
- Entity attributes: `:output/line`, `:output/category`, `:output/text`, `:output/who`, `:output/timestamp`, `:output/ns`, `:output/repl-session-key`, `:output/shadow-build`, `:output/shadow-runtime-id`

## File Organization

- `event_handler.cljs` — the loop
- `app/dispatch.cljs` — the merged tables, `dispatch!`, the dispatch log
- `app/axs.cljs` + `app/fxs.cljs` — application lifecycle (activation, commands, cleanup, `:uf/fx.dispatch` and the deferred dispatch effects)
- `mcp/axs.cljs` + `mcp/fxs.cljs` — the MCP server's lifecycle, through vscode-mcp's config
- `mcp/requests.cljs` — MCP protocol handling (`tools/call` here, the rest through `vscode-mcp.requests`)
- `tools.cljs` + `tools/` — the tool table, the tool call, content parts, confirmations, the evaluation tool's decisions
- `integrations/calva/` — Calva API integration, the output log, structural editing (`edit_plan.cljs` decides, `editor.cljs` and `editor_persist.cljs` gather and apply)
- `integrations/vscode/` — settings, when-contexts and messages

## Tool Implementation Pattern

Tool manifests live once in `package.json` (`languageModelTools`). Both entry points dispatch `[:tool/ax.call {:call/tool … :call/arguments …}]`, which checks the call against the manifest and the settings.

1. Manifest: `package.json` → `languageModelTools` entry
2. Row in `tools/tools` (`tools.cljs`): `:tool/function`, `:tool/reads`, `:tool/log-line`, `:tool/invocation-message`, `:tool/confirmation`
3. Confirmation: a function in `tools/confirmation.cljs`
4. Unit tests; a test checks the tool table against the manifest

## Skill Authoring

1. Create `assets/skills/{name}/SKILL.md` with YAML frontmatter (`name`, `description`)
2. Register in `package.json` → `contributes.chatSkills`
3. MCP server picks it up automatically via `vscode-mcp.manifest`

## Error Handling

- Errors are data, not exceptions — validation failures return structured maps with context
- Evaluation results include coaching notes embedded in the response protocol
- Tool failures return diagnostic context (21-line window with line numbers and arrow marker)
- Tools that reject an edit redirect the agent to the correct tool — runtime instruction delivery
