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
resources/public/      - Static assets and shadow-cljs output
shadow-cljs.edn        - Shadow CLJS build config (`:app` browser build)
test/mini/             - Test namespace (future use)
.github/               - Instructions, prompts, and skills
```

## Shadow CLJS

Minimal browser app using Replicant. The user Jacks in with the **Shadow CLJS + Replicant** Calva connect sequence. The app is served at  http://localhost:8780

## Resources

- [Backseat Driver Documentation](https://github.com/BetterThanTomorrow/calva-backseat-driver)
