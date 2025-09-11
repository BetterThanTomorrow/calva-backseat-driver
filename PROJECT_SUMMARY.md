# Calva Backseat Driver - Project Summary

## Overview

Calva Backseat Driver (v0.0.14) is a VS Code extension that transforms AI coding assistants into Interactive Programmers by giving them access to a live Clojure/ClojureScript REPL. It exposes Calva's powerful REPL-driven development capabilities to AI assistants through both VS Code's Language Model API and the Model Context Protocol (MCP).

**Core Value Proposition**: Turn "plausible-looking code that might work" into "tested code that actually works" by enabling AI to evaluate and iterate on solutions in real-time.

## Key Features

- **REPL Evaluation Tool** (opt-in): AI can execute Clojure/ClojureScript code in your connected REPL
- **Form-Aware Editing Tools**: Two structural editing tools (`replace_top_level_form` and `insert_top_level_form`) that respect form boundaries with automatic bracket balancing and text targeting for precise edits
- **Bracket Balancer**: Powered by Parinfer to help AI generate properly balanced Clojure code
- **Symbol Info Lookup**: Access to Clojure symbol documentation, argument lists, and metadata
- **ClojureDocs.org Integration**: Examples, usage patterns, and "see also" references for Clojure core symbols
- **REPL Output Log Access**: AI can monitor application output for debugging and understanding
- **MCP Inspector Testing**: Built-in Babashka tasks for testing MCP server functionality with the MCP inspector
- **Dual Interface**: Works with VS Code CoPilot (Language Model API) and any MCP-compliant AI client

## Architecture Overview

```
User ↔ AI Agent ↔ [VS Code LM API | MCP Server] ↔ Calva ↔ REPL ↔ Running Application
```

### Core Components

1. **Extension Entry Point** (`src/calva_backseat_driver/extension.cljs`)
   - Main activation/deactivation lifecycle
   - Exposes `activate` and `deactivate` functions to VS Code

2. **Action/Effect System** (`src/calva_backseat_driver/ex/`)
   - **Actions** (`ax.cljs`): Pure state transformations that return effects and new state
   - **Effects** (`fx.cljs`): Side effect handlers that perform async operations
   - **Dispatcher** (`ex.cljs`): Coordinates action processing and effect execution

3. **MCP Server Implementation** (`src/calva_backseat_driver/mcp/`)
   - **Server** (`server.cljs`): Socket server implementation with port file management
   - **Request Handlers** (`requests.cljs`): MCP protocol message processing
   - **Tool Definitions**: Expose evaluation, symbol lookup, and utility functions

4. **VS Code Integration** (`src/calva_backseat_driver/integrations/vscode/`)
   - **Language Model Tools** (`tools.cljs`): CoPilot tool implementations
   - **VS Code API Wrappers**: Commands, configuration, and UI integration

5. **Calva Integration** (`src/calva_backseat_driver/integrations/calva/`)
   - REPL evaluation API calls
   - Symbol information retrieval
   - Output log monitoring

## Key File Paths

### Core Implementation
- `src/calva_backseat_driver/extension.cljs` - Main extension entry point
- `src/calva_backseat_driver/ex/ex.cljs` - Action/effect dispatcher
- `src/calva_backseat_driver/app/axs.cljs` - Application action handlers
- `src/calva_backseat_driver/app/fxs.cljs` - Application effect handlers

### MCP Server
- `src/calva_backseat_driver/mcp/server.cljs` - Socket server and connection management
- `src/calva_backseat_driver/mcp/requests.cljs` - MCP protocol request/response handling
- `src/calva_backseat_driver/mcp/axs.cljs` - MCP-specific actions
- `src/calva_backseat_driver/mcp/fxs.cljs` - MCP-specific effects

### Integrations
- `src/calva_backseat_driver/integrations/calva/api.cljs` - Calva API integration
- `src/calva_backseat_driver/integrations/calva/editor.cljs` - Editor integration utilities
- `src/calva_backseat_driver/integrations/calva/features.cljs` - Feature detection and capabilities
- `src/calva_backseat_driver/integrations/vscode/tools.cljs` - VS Code Language Model tools
- `src/calva_backseat_driver/stdio/wrapper.cljs` - MCP stdio wrapper for external clients

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
- **Core.match 1.1.0** - Pattern matching for action/effect dispatch
- **Baldr 1.0.9** - Updates test output to do positive reporting

### VS Code Integration
- **VS Code API ^1.96.2** - Extension host environment
- **Parinfer 3.13.1** - Bracket balancing for Clojure code

### Development Dependencies
- **Babashka** - Task automation and scripting
- **@vscode/test-electron** - End-to-end testing framework
- **Joyride + Seatbelt** - VS Code scripting environment with test runner for E2E testing

## Available Tools/APIs

### AI Agent Tools

The tools are exposed as VS Code Language ModelAPI (for CoPilot) and MCP (for external AI clients).

1. **evaluate_clojure_code** (if enabled)
   - Execute Clojure/ClojureScript code in connected REPL
   - Parameters: `code`, `namespace`, `replSessionKey`

2. **get_symbol_info**
   - Retrieve symbol documentation and metadata
   - Parameters: `clojureSymbol`, `namespace`, `replSessionKey`

3. **get_clojuredocs_info**
   - Fetch examples and documentation from clojuredocs.org
   - Parameters: `clojureSymbol`

4. **get_repl_output_log**
   - Access REPL output for monitoring and debugging
   - Parameters: `sinceLine`

5. **clojure_balance_brackets**
   - Fix bracket imbalances in Clojure code using Parinfer
   - Parameters: `text`

6. **replace_top_level_form**
   - Form-aware editing of Clojure code with semantic awareness
   - Parameters: `filePath`, `line`, `targetLineText` (optional), `newForm`
   - Features: Text targeting, automatic bracket balancing, rich comment support

7. **insert_top_level_form**
   - Form-aware insertion of new Clojure forms
   - Parameters: `filePath`, `line`, `targetLineText` (optional), `newForm`
   - Features: Text targeting, automatic bracket balancing, rich comment support

## Implementation Patterns

### Action/Effect Pattern
```clojure
;; Actions are pure functions that return effects
[:app/ax.register-command command-id actions]
{:ex/fxs [[:app/fx.register-command command-id actions]]}

;; Effects perform side effects and can dispatch new actions
[:app/fx.register-command command-id actions]
(vscode/commands.registerCommand command-id handler)
```

### Configuration Access
```clojure
;; Access VS Code configuration through explicit context passing
:vscode/config.enableMcpReplEvaluation  ; -> boolean value from settings
:vscode/config.mcpSocketServerPort   ; -> port number from settings
```

### Async Coordination
```clojure
;; Promises with explicit success/error handling
{:ex/on-success [[:mcp/ax.server-started :ex/action-args]]
 :ex/on-error [[:mcp/ax.server-error :ex/action-args]]}
```

### State Management
- Central app-db atom for all application state
- State changes flow through action/effect cycle
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
- **Disabled by default** - Requires explicit user opt-in
- **Multi-layer security**:
  1. Extension setting must be enabled
  2. MCP clients default to low-trust mode
  3. Confirmation prompts for evaluation actions
- **Workspace-scoped** - Settings per workspace for granular control

### MCP Server
- **Socket-based communication** - Runs on localhost with port file
- **Process isolation** - Separate stdio wrapper for external clients
- **Graceful shutdown** - Proper cleanup of connections and resources

## Extension Points

### Adding New Tools
1. **Define tool manifest** in `package.json` under `languageModelTools`
2. **Implement MCP handler** in `src/calva_backseat_driver/mcp/requests.cljs`
3. **Add VS Code tool** in `src/calva_backseat_driver/integrations/vscode/tools.cljs`
4. **Register tool** in both registration functions

### Integration Points
- **Calva API Extensions**: Add new Calva integration points in `integrations/calva/api.cljs`
- **Custom Effects**: Extend effect system in namespace-specific effect handlers
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
