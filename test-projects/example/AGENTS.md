# Backseat Driver Test Project

This project is a testing environment for development versions of **Backseat Driver**, a Model Context Protocol (MCP) server that provides Clojure-focused development tools for AI agents.

## Purpose

Test and validate Backseat Driver's toolset:
- Structural editing tools for Clojure files
- REPL integration and evaluation
- Symbol documentation and ClojureDocs lookup
- Bracket balancing and code utilities
- Human intelligence integration

## For AI Agents Working in This Project

You have access to Backseat Driver's Clojure-focused MCP tools. See these skills for comprehensive guidance before beginning work:

- **`backseat-driver`** — Effective use of Backseat Driver tools: session awareness, ClojureDocs discipline, incremental development, output log monitoring, and the orient → research → explore → build → apply workflow.
- **`editing-clojure-files`** — Structural editing: which tool to use, targeting forms, indentation rules, multi-edit sequencing, and error recovery.
- **`backseat-driver-testing`** — Testing strategies for BD tool behavior: structural editing workflows, REPL who-tracking, output log filtering, bracket balancing, and compatibility testing. Use when validating tool updates or new features.

## Project Structure

```
src/mini/              - Test Clojure and ClojureScript files
resources/public/      - Static assets and shadow-cljs browser output
out/                   - Compiled node-script output
shadow-cljs.edn        - Shadow CLJS build config (`:app` browser, `:tui` node)
test/mini/             - Test namespace (future use)
.github/               - Instructions, prompts, and skills
.joyride/              - Starts and stops the REPLs, browser tabs and tuis
```

## Starting the REPLs, browsers and tuis

When the window opens, `.joyride/scripts/workspace_activate.cljs` runs `test-project/start!` from `.joyride/src/test_project.cljs`. It jacks in to five REPLs, opens two browser tabs on the shadow-cljs app, and starts two tuis in split terminals. It looks at what is already running first, so it only starts what is missing. The VS Code tasks **test project: start** and **test project: stop** start and stop all of it. The connect sequences are in `test_project.cljs`, not in the settings.

### REPL sessions

| Session | REPL | Files |
|---------|------|-------|
| `clj` | JVM Clojure in `sub-project/` | `sub-project/*.clj`, `sub-project/deps.edn` |
| `mini-clj` | JVM Clojure in the mini project | `src/**/*.clj` |
| `bb` | Babashka in the mini project | `**/*.bb` |
| `shadow-clj` | shadow-cljs JVM side | `deps.edn`, `shadow-cljs.edn` |
| `shadow-cljs` | shadow-cljs, builds `:app` and `:tui` | `**/app.cljs`, `**/tui.cljs` |

The browser app (`:app`, Replicant) is served at http://localhost:8780. The node prompt app (`:tui`) is a small readline menu, run by the tasks **tui 1** and **tui 2**. Enter `1` to increment a counter, `2` to quit. For `tui.cljs` evaluations, select the `:tui` runtime in Calva's shadow runtime picker (the cljs session connects to `:app`).

If a jack-in fails with `already started`, a stale shadow-cljs server is still running. Stop it first:

```bash
npx shadow-cljs stop
```

## Resources

- [Backseat Driver Documentation](https://github.com/BetterThanTomorrow/calva-backseat-driver)
