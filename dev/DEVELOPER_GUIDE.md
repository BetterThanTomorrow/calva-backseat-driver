# Calva Backseat Driver - Developer Guide

## Overview

Development-focused guide for contributing to Calva Backseat Driver.

**Prerequisites**: Read [`PROJECT_SUMMARY.md`](../PROJECT_SUMMARY.md) first for project architecture, dependencies, and available tools.

This guide covers development workflows, testing strategies, and contribution processes specific to working with the codebase.

## Interactive Development Workflow

### Setup
1. **Start REPL Server**: `npm run watch` (shadow-cljs with nREPL)
2. **Connect Calva**: `Ctrl+Alt+C Ctrl+Alt+C`
3. **Launch Dev Host**: `F5` in VS Code
5. **Hot Reload**: Changes automatically applied to running extension either by saving files (shadow-cljs hot reload, or by evaluating code in the REPL)

### REPL-Driven Development
```clojure
;; Test functions interactively
(comment
  (require '[calva-backseat-driver.integrations.calva.api :as calva])
  ;; => Promise resolving to symbol documentation
  (let [info (calva/symbol-info+ "map" "clojure.core" "clj")]
    (def info info) ; inline def used so that the unwrapped promise can be examined in the repl
    )
  :rcf)
```

### Human/AI Collaboration
- **Phase 1**: AI initializes the repl connection by starting the REPL server
  - Human handles completes the REPL promotion and connection
- **Phase 2**: AI leverages interactive environment for development
- **Phase 3**: AI uses the MCP server being developed (bootstrap complete!)

## Build & Test

### Development Commands
```bash
# Development build with hot reload and auto-running tests
npm run watch

# Production build
npm run compile
npm run release

# Package pre-release version of the extension
bb package-pre-release

# End-to-end testing with workspace files
bb run-e2e-tests-ws
```

### Testing Strategy
- **Unit Tests**: Shadow-cljs auto-discovers `*_test.cljs` files and runs tests when files are saved, after reloading
- **Integration Tests**: Test API endpoints and component interactions
- **E2E Tests**: Full VS Code environment with Joyride + Seatbelt

### E2E Testing Requirements
- Uses VS Code Insiders (auto-downloaded)
- **Critical**: VS Code Insiders must NOT be running when starting tests
- Tests located in `e2e-test-ws/.joyride/src/tests/`

## MCP Development

### MCP Inspector Tool
```bash
# Test MCP server interactively
bb run-mcp-inspector
```

**Use Cases**:
- Test tool implementations
- Validate schema compliance
- Debug connection issues
- Monitor request/response cycles

### Schema Compliance
- **Current Version**: 2024-11-05 MCP schema
- **Validation**: All responses must conform to official schema
- **Common Issues**: Response formats, tool definitions, initialize response structure

### Server Testing
```clojure
;; Test MCP server directly through REPL
(comment
  (require '[calva-backseat-driver.app.dispatch :as dispatch])
  (dispatch/dispatch! [[:mcp/ax.start-server]])
  ;; A tool call, as both entry points dispatch it
  (dispatch/dispatch! [[:tool/ax.call {:call/tool "clojure_list_sessions"
                                       :call/arguments {}}]])
  )
```

## Contributing Workflow

> **Architecture Reference**: See [`PROJECT_SUMMARY.md`](../PROJECT_SUMMARY.md) for Action/Effect patterns, file structure, and implementation conventions.

### New Feature Development
1. **Define Interface**: Document expected behavior and API
2. **Write Tests**: Unit tests for core logic, integration tests for APIs
3. **Implement Actions**: Pure functions that return effects
4. **Implement Effects**: Side effect handlers with error handling
5. **Test Integration**: E2E testing in real VS Code environment
6. **Update Documentation**: Keep all docs in sync with changes

### Adding New Tools
> **Reference**: See [`PROJECT_SUMMARY.md - Extension Points`](../PROJECT_SUMMARY.md#extension-points) for detailed tool addition steps.

**Quick Checklist**:
- [ ] Define tool manifest in `package.json`
- [ ] Add a row to the tool table, `tools/tools` in `tools.cljs`
- [ ] Add a confirmation function in `tools/confirmation.cljs`
- [ ] Add unit tests (one checks the tool table against the manifest)
- [ ] Test with MCP Inspector
- [ ] Validate schema compliance

### Commit Practices
- **Small, Focused Commits**: Each commit represents coherent unit of work
- **Descriptive Messages**: Tell the story of the development process
- **Documentation Sync**: Keep code and docs updated together
- **Decision Documentation**: Log architectural choices and rationale

## Deployment

### Automated Pipeline
- **Linting & Formatting**: Automated checks
- **Testing**: Unit and E2E tests must pass
- **Publishing**: Automated marketplace deployment
- **Version Management**: Semantic versioning with changelog generation

### Task Management (Babashka)
```bash
bb publish              # Publish to marketplace
bb package-pre-release  # Create pre-release VSIX
bb ci:release-notes     # Generate release notes
bb run-e2e-tests-ws     # Full E2E test suite
```

## Troubleshooting

### Common Issues
- **REPL Connection**: Ensure shadow-cljs is running before Calva connection
- **Hot Reload**: Refresh extension host if changes aren't applied
- **E2E Tests**: Close VS Code Insiders before running tests
- **MCP Inspector**: Verify server is running and port file exists

### Debugging Tips
```clojure
;; Check app state (the output log and the history are long)
(comment
  (binding [*print-length* 5]
    (pr-str @calva-backseat-driver.app.db/!app-db))
  ;; The last 200 dispatches (development build)
  @calva-backseat-driver.app.dispatch/!dispatch-log
  )

;; Monitor MCP server logs
(comment
  (calva-backseat-driver.app.dispatch/dispatch! [[:app/ax.log :debug "Custom debug message"]])
  )
```

## Architecture References

- **Project Summary**: [`PROJECT_SUMMARY.md`](../PROJECT_SUMMARY.md) - Complete project overview, architecture, and patterns
- **Action/Effect System**: `dev/EX_ARCHITECTURE.md` - What is particular to Backseat Driver's loop
- **MCP Protocol**: `dev/MCP_OVERVIEW.md` - Model Context Protocol specifics

## AI Development Notes

> **Reference**: See [`PROJECT_SUMMARY.md - AI Collaboration Notes`](../PROJECT_SUMMARY.md#ai-collaboration-notes) for complete guidance.

**Development-Specific Tips**:
- Start REPL server with `npm run watch` before any development session
- Use `(comment ...)` forms for interactive experimentation
- Test MCP tools with `bb run-mcp-inspector` before committing
- Monitor extension logs and REPL output during development
