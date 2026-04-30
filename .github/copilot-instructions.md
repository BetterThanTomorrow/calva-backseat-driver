For comprehensive development guidance, invoke the `@backseat-dev` agent.

## What Backseat Driver Is

Backseat Driver bridges AI coding assistants into Interactive Programming — the REPL-driven development practice central to Clojure culture. A ClojureScript VS Code extension running in the Extension Host (Node.js), built from scratch with zero npm dependencies for the protocol layer. Provides MCP server and VS Code Language Model tools for REPL evaluation, structural editing, and output inspection.

## REPL Environment

- **Primary REPL**: Backseat Driver CLJS (`clojure_evaluate_code`, replSessionKey: `"cljs"`) — for developing the extension itself
- **Shadow-cljs build tooling**: CLJ session (replSessionKey: `"clj"`) — build system only

This is ClojureScript on Node.js. Use the `cljs` session, not `clj`.

## State Inspection Safety

- `@db/!app-db` — always `dissoc :vscode/extension-context` before inspecting (circular references)
- VS Code API objects — use `select-keys` or `dissoc`, never print raw
- Datascript connections — `@db/!output-conn` (session output), `@db/!history-conn` (persistent history)

## Bundled AI Context (chatSkills / chatInstructions)

Skills are dual-registered in `package.json` as both `chatSkills` (MCP resources) and `chatInstructions` (auto-injected). Skills are conditional on user settings (`provideBdSkill`, `provideEditSkill`); instructions are always injected.

## Key Development Commands

```bash
npm run watch           # shadow-cljs + nREPL, auto-runs tests
bb run-e2e-tests-ws     # Full E2E (output → .tmp/e2e-output.log)
bb run-mcp-inspector    # Test MCP tools interactively
bb package-pre-release  # Package pre-release VSIX
```

## Reference Documents

- `AGENTS.md` — comprehensive agent onboarding (architecture, patterns, adding features)
- `dev/EX_ARCHITECTURE.md` — action/effect system details
- `dev/MCP_OVERVIEW.md` — MCP protocol specifics
- `dev/agent-docs/soul-of-backseat-driver.md` — guiding philosophy and invariants
