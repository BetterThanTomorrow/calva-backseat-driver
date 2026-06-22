---
description: 'Clojure Interactive Programming tools from Backseat Driver — load the backseat-driver skill when working with Clojure code, evaluating in the REPL, looking up documentation, or checking REPL output.'
---

# Backseat Driver — Clojure Interactive Programming Tools

The term Clojure is used to cover all Clojure dialects and runtimes — Clojure, ClojureScript, ClojureCLR, Babashka, Jank, etc, etc, etc.

"Use the REPL" means `clojure_evaluate_code`. This is the primary tool for Clojure Interactive Programming.

Backseat Driver provides 8 tools in two groups:

**REPL Exploration & Understanding**: `clojure_evaluate_code`, `clojure_load_file`, `clojure_list_sessions`, `clojure_repl_output_log`, `clojure_symbol_info`, `clojuredocs_info`

Shadow-cljs sessions may expose multiple JavaScript runtimes via `clojure_list_sessions`; optional `targetRuntimeId` on `clojure_evaluate_code` targets a specific runtime without changing the editor selection.

**Structural Editing**: `clojure_edit_files`, `clojure_balance_brackets`

## Joyride Boundary

In a Joyride context, if the `joyride_evaluate_code` tool is available, "Use the REPL" means `joyride_evaluate_code`. All other Backseat Driver tools remain useful in Joyride contexts. In lieu of the `joyride_evaluate_code` tool, use the `clojure_evaluate_tool`. 

## Load the Skills

Always load the `backseat-driver` skill when using Backseat Driver tools.
Always load the `editing-clojure-files` skill when editing Clojure files.
