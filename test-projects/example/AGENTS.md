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
```

## Shadow CLJS

Minimal browser app using Replicant. Jack in with the **Shadow CLJS (browser + tui)** Calva connect sequence — it watches both `:app` and `:tui` builds. The browser app is served at http://localhost:8780

Node prompt app (`:tui`) — a small readline menu in the terminal. Enter `1` to increment a counter, `2` to quit.

```bash
npx shadow-cljs -A :dev watch app tui
# or run the compiled script in a terminal:
node out/mini-tui.js
```

If jack-in fails with `already started`, a stale shadow-cljs server is still running. Stop it first:

```bash
npx shadow-cljs stop
```

## Resources

- [Backseat Driver Documentation](https://github.com/BetterThanTomorrow/calva-backseat-driver)
