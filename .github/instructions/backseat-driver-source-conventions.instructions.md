---
description: 'Backseat Driver extension source code conventions — Ex framework patterns, async, state management, namespace separation, and naming. Supplements universal Clojure patterns with Backseat Driver-specific rules.'
applyTo: 'src/calva_backseat_driver/**,test/calva_backseat_driver/**'
---

# Backseat Driver Source Conventions

## Naming Conventions

- `function!` — side effects
- `function+` — returns promise
- `function!+` — side effects and promise
- `function?` — predicate
- `!atom-name` — atom (e.g., `!app-db`, `!output-conn`)
- `ax.` prefix — pure action handler
- `fx.` prefix — impure effect handler
- `ex/` namespace — framework plumbing, not domain logic

## Ex Framework Patterns

### Actions (axs.cljs)

Actions are pure functions. They receive immutable state and arguments, return a map of changes:

```clojure
{:ex/db  new-state        ;; state update (merged at end)
 :ex/dxs [[:ns/ax.name ...]]  ;; deferred actions (dispatched after state update)
 :ex/fxs [[:ns/fx.name ...]]} ;; effect declarations (executed after dxs)
```

Actions never access atoms, VS Code APIs, or external state directly. Use enrichment placeholders:
- `:context/some.path` — resolved from VS Code extension context
- `[:db/get :key]` — resolved from current app-db state
- `:vscode/config.settingName` — resolved from VS Code configuration

### Effects (fxs.cljs)

Effects are impure handlers that receive `dispatch!`, `context`, and the enriched effect vector. They perform side effects and may dispatch new actions:

```clojure
(defn some-effect! [dispatch! context [_ args]]
  (-> (some-async-operation!+ args)
      (.then (fn [result]
               (dispatch! context [[:ns/ax.handle-result result]])))))
```

### Async Continuation

Effects return promises. Continuation actions use `:ex/then`, `:ex/on-success`, `:ex/on-error` with placeholder enrichment:

```clojure
;; In action — declare effect with continuation
{:ex/fxs [[:ns/fx.do-thing {:data data
                             :ex/then [[:ns/ax.thing-done :ex/action-args]]}]]}

;; In effect — enrich continuation and dispatch
(.then promise
  (fn [result]
    (dispatch! context (ax/enrich-with-args then-actions result))))
```

### Routing

Actions route by `(namespace action-keyword)`: `"app"`, `"mcp"`, `"db"`, `"vscode"`, `"node"`, `"calva"`.
Effects route similarly. Add new routes in `ex/ax.cljs` (`handle-action`) and `ex/fx.cljs` (`perform-effect!`).

## State Management

- All state in `app/db.cljs` — `!app-db` atom, `!output-conn` and `!history-conn` Datascript connections
- State changes only through `dispatch!` — never direct `swap!` or `reset!` on `!app-db`
- Pass data explicitly to helper functions — helpers must not access atoms directly
- Inspect `!app-db` safely: always `(dissoc @db/!app-db :vscode/extension-context)` — circular references

## Datascript Connections

- `!output-conn` — session-scoped, schemaless, cap 1000 entities, all output categories
- `!history-conn` — persistent, `:output/line` unique identity, cap 10000, `evaluatedCode` only
- Entity attributes: `:output/line`, `:output/category`, `:output/text`, `:output/who`, `:output/timestamp`, `:output/ns`, `:output/repl-session-key`

## File Organization

- `app/axs.cljs` + `app/fxs.cljs` — application lifecycle (activation, commands, cleanup)
- `mcp/axs.cljs` + `mcp/fxs.cljs` — MCP server lifecycle
- `mcp/server.cljs` — TCP socket server implementation
- `mcp/requests.cljs` — MCP protocol handling (tools/list, tools/call, resources)
- `mcp/skills.cljs` — skill discovery, filtering, instruction composition
- `db/axs.cljs` — generic state mutation actions
- `integrations/calva/` — Calva API integration
- `integrations/vscode/` — VS Code Language Model tools
- `ex/` — dispatcher, action routing, effect routing (~50 LOC total framework)

## Tool Implementation Pattern

Tool manifests live once in `package.json` (`languageModelTools`). Both the MCP server and VS Code Language Model API read them at runtime.

1. Manifest: `package.json` → `languageModelTools` entry
2. MCP handler: `requests.cljs` → `handle-tool-call` multimethod
3. VS Code tool: `tools.cljs` → Language Model API registration
4. Test: `bb run-mcp-inspector` → invoke tool

## Skill Authoring

1. Create `assets/skills/{name}/SKILL.md` with YAML frontmatter (`name`, `description`)
2. Register in `package.json` → `contributes.chatSkills` and `chatInstructions`
3. MCP server picks it up automatically via `skills.cljs`

## Error Handling

- Errors are data, not exceptions — validation failures return structured maps with context
- Evaluation results include coaching notes embedded in the response protocol
- Tool failures return diagnostic context (21-line window with line numbers and arrow marker)
- Tools that reject an edit redirect the agent to the correct tool — runtime instruction delivery
