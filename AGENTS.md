# Calva Backseat Driver - AI Agent Guide

ClojureScript VS Code extension providing REPL access to AI agents via Language Model API and MCP. Enables Interactive Programming where AI evaluates code in the user's running environment rather than guessing.

## Essential Architecture

### Action/Effect System ("Ex")
Functional core with unidirectional flow:
```
dispatch! → handle-actions → [enrich → route] → {:ex/db, :ex/fxs, :ex/dxs} → update state → execute effects
```

**Actions** (`*/axs.cljs`): Pure functions returning state changes and effects
**Effects** (`*/fxs.cljs`): Side effect handlers with dispatch access
**State**: Single `app-db` atom in `src/calva_backseat_driver/app/db.cljs`

### Runtime State Structure
The `app-db` atom contains:
```clojure
{:vscode/extension-context    ; VS Code extension API access
 :app/log-file-uri            ; Logging location
 :app/min-log-level           ; :debug/:info/:warn/:error
 :mcp/wrapper-config-path     ; ~/.config/calva/backseat-driver
 :calva/output-buffer         ; REPL output messages (ring buffer)
 :calva/output-message-count  ; For tracking output
 :extension/disposables       ; VS Code subscriptions
 :extension/when-contexts}    ; Context keys for enablement
```

### Calva API Integration
Available at `calva-backseat-driver.integrations.calva.api/calva-api`:
```clojure
{:repl     {:evaluateCode, :currentSessionKey, :onOutputLogged}
 :ranges   {:currentTopLevelForm, :currentEnclosingForm, ...}
 :editor   {:replace}
 :document {:getNamespace, :getNamespaceAndNsForm}
 :info     {:getSymbolInfo, :getClojureDocsDotOrg}}
```

## Development Workflow

### REPL Setup (ClojureScript)
```bash
npm run watch           # shadow-cljs + nREPL, auto-runs tests
# Connect Calva: Ctrl+Alt+C Ctrl+Alt+C
# Launch Extension Host: F5
```

**Critical**: This is a ClojureScript project running in VS Code Extension Host (Node.js). Use `cljs` REPL session, not `clj`.

### Interactive Development Pattern
```clojure
;; Explore runtime state
(in-ns 'calva-backseat-driver.app.db)
@!app-db

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
bb run-e2e-tests-ws     # Full E2E (VS Code Insiders must NOT be running)
bb run-mcp-inspector    # Test MCP tools interactively
bb package-pre-release  # Package pre-release VSIX
```

## Key Implementation Patterns

### Enrichment System
Actions use keyword placeholders enriched at runtime:
```clojure
;; In action:
[:vscode/fx.show-input-box {:title "Name" :ex/then [[:handler :ex/action-args]]}]

;; Effect enriches and dispatches:
(dispatch! context (ax/enrich-with-args then-actions result))

;; Placeholders: :ex/action-args (entire result), :ex/action-args%1 (first element), etc.
```

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
Tools use `targetLineText` (first line of target form) + line number ±2:
```clojure
;; replace_top_level_form or insert_top_level_form
{:filePath "/absolute/path.clj"
 :line 23
 :targetLineText "(defn multiply-numbers"
 :newForm "(defn multiply-numbers\n  [x y]\n  (* x y))"}
```

### Automatic Features
- **Bracket balancing**: Parinfer-powered (`integrations/parinfer.cljs`)
- **Rich Comment support**: Forms inside `(comment ...)` treated as top-level
- **Diagnostic context**: On failure, returns 21-line context + remedy message

### Critical: Bottom-to-Top Editing
When making multiple edits, work from highest line number to lowest (line numbers shift down as you edit above).

## MCP Server Architecture

### Socket Server Pattern
- Backend server runs in Extension Host (TCP socket)
- Port file: `${workspaceFolder}/.calva/mcp-server/port`
- stdio wrapper (`dist/calva-mcp-server.js`) relays stdio ↔ socket
- One server per workspace folder

### Configuration Access
Settings read via enrichment:
```clojure
;; In action/effect context:
:vscode/config.enableMcpReplEvaluation  ; boolean
:vscode/config.mcpSocketServerPort      ; number (default 1664, 0=random)
:vscode/config.autoStartMCPServer       ; boolean
```

## Adding New Features

### New Tool Implementation
1. **Tool manifest**: `package.json` → `languageModelTools`
2. **MCP handler**: `src/calva_backseat_driver/mcp/requests.cljs`
3. **VS Code tool**: `src/calva_backseat_driver/integrations/vscode/tools.cljs`
4. **Test**: `bb run-mcp-inspector`

### Action/Effect Example
```clojure
;; Action (pure, in */axs.cljs)
[:app/ax.register-command command-id actions]
{:ex/fxs [[:app/fx.register-command command-id actions]]}

;; Effect (side effect, in */fxs.cljs)
[:app/fx.register-command command-id actions]
(vscode/commands.registerCommand command-id
  (fn [] (dispatch! context actions)))
```

## Common Pitfalls

- **DON'T** access `@!app-db` directly from helpers (pass data explicitly)
- **DON'T** perform side effects in action handlers (return effects instead)
- **DON'T** forget namespace reloads after file edits: `(require 'ns :reload)`
- **DO** evaluate in REPL before file edits
- **DO** use `cljs` session (this is ClojureScript, not Clojure)
- **DO** check `:calva/output-buffer` in app-db to see REPL activity
- **DO** work bottom-to-top when multiple structural edits

## Quick Reference

**Key Namespaces**:
- `calva-backseat-driver.ex.ex` - Dispatcher
- `calva-backseat-driver.app.{axs,fxs,db}` - Application layer
- `calva-backseat-driver.mcp.{server,requests,axs,fxs}` - MCP server
- `calva-backseat-driver.integrations.calva.{api,editor,editor-util}` - Calva integration
- `calva-backseat-driver.integrations.vscode.tools` - VS Code Language Model tools

**REPL Inspection Commands**:
```clojure
@calva-backseat-driver.app.db/!app-db                    ; See full state
(keys calva-backseat-driver.integrations.calva.api/calva-api)  ; Available Calva APIs
(:calva/output-buffer @calva-backseat-driver.app.db/!app-db)   ; Recent REPL output
```

**Build Artifacts**:
- `out/extension.js` - Main extension bundle
- `dist/calva-mcp-server.js` - MCP stdio wrapper
- `out/extension-tests.js` - Unit tests

---

For deeper architecture details, see:
- `dev/EX_ARCHITECTURE.md` - Action/effect system
- `dev/MCP_OVERVIEW.md` - MCP protocol specifics
- `PROJECT_SUMMARY.md` - Complete project overview
