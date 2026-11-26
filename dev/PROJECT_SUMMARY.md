# Calva Backseat Driver - Project Summary

## Overview

Calva Backseat Driver (v0.0.27) is a VS Code extension that transforms AI coding assistants into Interactive Programmers by giving them access to a live Clojure/ClojureScript REPL. It exposes Calva's powerful REPL-driven development capabilities to AI assistants through both VS Code's Language Model API and the Model Context Protocol (MCP).

**Core Value Proposition**: Turn "plausible-looking code that might work" into "tested code that actually works" by enabling AI to evaluate and iterate on solutions in real-time.

## Key Features

### 8 Tools (VS Code Language Model API + MCP)

**REPL Exploration & Understanding**: `clojure_evaluate_code`, `clojure_load_file` (requires Calva >= 2.0.576), `clojure_list_sessions`, `clojure_repl_output_log`, `clojure_symbol_info`, `clojuredocs_info`

**Structural Editing**: `clojure_edit_files`, `clojure_balance_brackets`

### 2 Skills, 2 Instructions

- **Backseat Driver skill**: REPL workflow, tool usage patterns, evaluation strategy
- **Editing Clojure Files skill**: Structural editing tool selection, targeting, indentation rules
- **Backseat Driver instruction**: Directs agents to load the backseat-driver skill
- **Editing Clojure Files instruction**: Directs agents to load the editing skill when editing Clojure

Skills are also exposed as MCP resources for non-Copilot clients.

### Other

- **Dual Interface**: Works with VS Code Copilot (Language Model API) and any MCP-compliant AI client
- **MCP Inspector Testing**: Built-in Babashka tasks for testing MCP server functionality
- **Clojure Copilot Plugins**: See [Awesome Backseat Driver](https://github.com/BetterThanTomorrow/awesome-backseat-driver) for community plugins providing Clojure knowledge, agents, and workflows

## Architecture Overview

```
User ↔ AI Agent ↔ [VS Code LM API | MCP Server] ↔ Calva ↔ REPL ↔ Running Application
```

### Core Components

1. **Extension Entry Point** (`src/calva_backseat_driver/extension.cljs`)
   - Main activation/deactivation lifecycle
   - Exposes `activate` and `deactivate` functions to VS Code

2. **The Loop** (`src/calva_backseat_driver/event_handler.cljs`, following the Uniflow idea; see [EX_ARCHITECTURE.md](EX_ARCHITECTURE.md))
   - **Actions** (`*/axs.cljs`): Pure handlers that return the next state, effects and deferred actions, listed in each namespace's `actions` table
   - **Effects** (`*/fxs.cljs`): Side effect handlers, listed in each namespace's `effects` table
   - **Dispatch** (`app/dispatch.cljs`): Merges the tables and holds `dispatch!`
   - **State** (`app/db.cljs`): Plain values; live objects are beside it in `app/live_objects.cljs`

3. **MCP Server Wiring** (`src/calva_backseat_driver/mcp/`)
   - **Lifecycle** (`axs.cljs`, `fxs.cljs`): The config vscode-mcp runs the server from, and the server's state it reports
   - **Request Handlers** (`requests.cljs`): `tools/call`, and the rest through `vscode-mcp.requests`

4. **Tools** (`src/calva_backseat_driver/tools.cljs`, `src/calva_backseat_driver/tools/`)
   - **Tool table** (`tools/tools`): One row per tool, for both the Language Model API and MCP
   - **Tool call** (`tools/axs.cljs`, `tools/fxs.cljs`): Checks a call against the manifest and the settings, and runs it
   - **VS Code Integration** (`integrations/vscode/`): Settings, when-contexts and messages

5. **Calva Integration** (`src/calva_backseat_driver/integrations/calva/`)
   - REPL evaluation API calls
   - Symbol information retrieval
   - Output log monitoring

## Key File Paths

### Core Implementation
- `src/calva_backseat_driver/extension.cljs` - Main extension entry point
- `src/calva_backseat_driver/event_handler.cljs` - The loop
- `src/calva_backseat_driver/app/dispatch.cljs` - The action and effect tables, `dispatch!`
- `src/calva_backseat_driver/app/db.cljs` - The state
- `src/calva_backseat_driver/app/axs.cljs` - Application action handlers
- `src/calva_backseat_driver/app/fxs.cljs` - Application effect handlers

### MCP Server
- `src/calva_backseat_driver/mcp/requests.cljs` - MCP protocol request/response handling
- `src/calva_backseat_driver/mcp/axs.cljs` - MCP-specific actions
- `src/calva_backseat_driver/mcp/fxs.cljs` - MCP-specific effects

### Integrations
- `src/calva_backseat_driver/integrations/calva/api.cljs` - Calva API integration
- `src/calva_backseat_driver/integrations/calva/editor.cljs` - Editor integration utilities
- `src/calva_backseat_driver/integrations/calva/features.cljs` - Feature detection and capabilities
- `src/calva_backseat_driver/tools.cljs` - The tool table and the Language Model API's entry point

### Supporting Files
- `src/calva_backseat_driver/bracket_balance.cljs` - Parinfer integration for bracket balancing
- `src/calva_backseat_driver/mcp/logging.cljs` - Server logging utilities

### Configuration & Build
- `shadow-cljs.edn` - ClojureScript build configuration
- `package.json` - VS Code extension manifest and dependencies
- `deps.edn` - Clojure dependencies

### Development & Testing
- `dev/DEVELOPER_GUIDE.md` - Comprehensive development instructions
- `dev/EDIT_TOOL.md` - Form-aware editing tool documentation
- `dev/MCP_OVERVIEW.md` - MCP protocol documentation
- `e2e-test-ws/` - End-to-end testing environment
- `test-projects/example/` - Example project for testing and development

## Dependencies

### Core Dependencies
- **Clojure 1.12.0** - Core language
- **ClojureScript 1.11.132** - Client-side compilation
- **Shadow-CLJS 2.28.23** - Build tooling and hot reload
- **Promesa 11.0.678** - Promise handling and async coordination
- **Baldr 1.0.9** - Updates test output to do positive reporting

### VS Code Integration
- **VS Code API ^1.96.2** - Extension host environment
- **Parinfer 3.13.1** - Bracket balancing for Clojure code

### Development Dependencies
- **Babashka** - Task automation and scripting
- **@vscode/test-electron** - End-to-end testing framework
- **Joyride + Seatbelt** - VS Code scripting environment with test runner for E2E testing

## Available Tools/APIs

### AI Agent Tools (10 tools)

The tools are exposed as VS Code Language Model API (for Copilot) and MCP (for external AI clients).

**REPL Exploration & Understanding:**

1. **clojure_evaluate_code**
   - Execute Clojure/ClojureScript code in connected REPL
   - Parameters: `code`, `namespace`, `replSessionKey`, `who`, `description`, optional `targetRuntimeId` (shadow-cljs)

2. **clojure_list_sessions**
   - Discover available REPL sessions and their details
   - Returns session keys, project roots, file glob patterns, active session; shadow-cljs sessions include compact per-build runtime info (`runtimeCount`, `mostRecentRuntime`); optional `includeAllRuntimes` lists every runtime

3. **clojure_symbol_info**
   - Retrieve symbol documentation and metadata
   - Parameters: `clojureSymbol`, `namespace`, `replSessionKey`

4. **clojuredocs_info**
   - Fetch examples and documentation from clojuredocs.org
   - Parameters: `clojureSymbol`

5. **clojure_repl_output_log**
   - Access REPL output for monitoring and debugging
   - Parameters: `sinceLine`, `includeWho`, `excludeWho`

**Structural Editing:**

6. **clojure_edit_files**
   - Batch structural editing: replace, insert, append, create in one call
   - Parameters: `edits` (array of edit objects with `type`, `filePath`, and type-specific fields)
   - Auto-sorts within each file, continues on failure, per-file diagnostics

7. **clojure_balance_brackets**
    - Fix bracket imbalances in Clojure code using Parinfer
    - Parameters: `text`

## Implementation Patterns

### Action/Effect Pattern
```clojure
;; Actions are pure functions that return effects
(defn- init [state _uf-data]
  {:uf/db (assoc state :app/activated? true)
   :uf/fxs [[:app/fx.register-command command-id actions] …]})

;; Effects perform side effects and can dispatch new actions
(defn- register-command [dispatch! command-id actions]
  (live-objects/add-disposables!
   [(vscode/commands.registerCommand command-id
                                     (fn [& _args]
                                       (.then (dispatch! actions) clj->js)))]))
```

### Configuration Access
```clojure
;; The settings are in the state; read one with a plain lookup
(:vscode/config.enableMcpReplEvaluation state)  ; -> boolean value from settings
(:vscode/config.mcpSocketServerPort state)      ; -> port number from settings
```

### Async Coordination
```clojure
;; An awaited effect's result replaces :uf/prev-result in what follows
{:uf/fxs [[:uf/await :mcp/fx.lifecycle-start options]]
 :uf/dxs [[:mcp/ax.lifecycle-updated :uf/prev-result]]}
```

### State Management
- Central app-db atom for all application state, as plain values
- State changes flow through the loop
- No direct state mutation from business logic

## Development Workflow

### Interactive Development
1. **Start REPL**: `npm run watch` (shadow-cljs with nREPL middleware)
2. **Connect Calva**: `Ctrl+Alt+C Ctrl+Alt+C` in VS Code
3. **Launch Extension Host**: `F5` to start development instance
4. **Hot Reload**: Changes automatically applied to running extension

### Building & Testing
- **Development Build**: `npm run compile`
- **Production Build**: `npm run release`
- **E2E Testing**: `bb run-e2e-tests-ws` (uses VS Code Insiders with Joyride + Seatbelt test runner)
- **Package Extension**: `npm run package`

### Task Management (Babashka)
- `bb publish` - Publish to marketplace
- `bb package-pre-release` - Create pre-release VSIX
- `bb ci:release-notes` - Generate release notes

## Security Model

### REPL Evaluation
- **Enabled by default** — set `enableMcpReplEvaluation` to `false` to disable
- **Workspace-scoped** — per-workspace control when you want evaluation off
- **Client-side permissions** — MCP clients may still ask before invoking tools

### MCP Server
- **Socket-based communication** - Runs on localhost with port file
- **Process isolation** - Separate stdio wrapper for external clients
- **Graceful shutdown** - Proper cleanup of connections and resources

## Extension Points

### Adding New Tools
1. **Define tool manifest** in `package.json` under `languageModelTools`
2. **Add a row** to the tool table, `tools/tools` in `src/calva_backseat_driver/tools.cljs`
3. **Add a confirmation** function in `src/calva_backseat_driver/tools/confirmation.cljs`
4. **Add unit tests**; a test checks the tool table against the manifest

### Integration Points
- **Calva API Extensions**: Add new Calva integration points in `integrations/calva/api.cljs`
- **Custom Effects**: Add an effect to a namespace's `effects` table
- **Protocol Extensions**: Add new MCP capabilities in `mcp/requests.cljs`

### Testing Extensions
- **Unit Tests**: Add to `test/` directory following existing patterns
- **E2E Tests**: Add to `e2e-test-ws/.joyride/src/tests/` (uses Seatbelt test runner within Joyride scripting environment)
- **Integration Tests**: Use test projects in `test-projects/`

## AI Collaboration Notes

When working with this codebase:

1. **Leverage the REPL** - Always evaluate code interactively during development
2. **Use Rich Comment Forms** - Document experimentation and examples
3. **Follow Function Naming** - Respect `!` (side effects) and `+` (promises) conventions
4. **Keep Actions Pure** - Never perform side effects in action handlers
5. **Pass Context Explicitly** - Avoid global state access from helper functions
6. **Test Incrementally** - Build and validate each step through REPL evaluation

The project is designed to bootstrap its own development - the AI tools being built can be used to enhance the development of those very tools, creating a powerful feedback loop for Interactive Programming.
