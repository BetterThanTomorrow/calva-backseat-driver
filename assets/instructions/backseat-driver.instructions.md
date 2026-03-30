---
description: 'Clojure Interactive Programming tools from Backseat Driver — load the backseat-driver skill when working with Clojure code, evaluating in the REPL, looking up documentation, or checking REPL output.'
---

# Backseat Driver — Clojure Interactive Programming Tools

The term Clojure is used to cover all Clojure dialects and runtimes — Clojure, ClojureScript, ClojureCLR, Babashka, Jank, etc, etc, etc.

"Use the REPL" means `clojure_evaluate_code`. This is the primary tool for Clojure Interactive Programming.

Backseat Driver provides 10 tools in two groups:

**REPL Exploration & Understanding**: `clojure_evaluate_code`, `clojure_list_sessions`, `clojure_repl_output_log`, `clojure_symbol_info`, `clojuredocs_info`

**Structural Editing**: `clojure_create_file`, `clojure_append_code`, `replace_top_level_form`, `insert_top_level_form`, `clojure_balance_brackets`

## Joyride Boundary

In a Joyride context, "Use the REPL" means `joyride_evaluate_code`, not `clojure_evaluate_code`. All other Backseat Driver tools remain useful in Joyride contexts.

## Load the Skills

Always load the `backseat-driver` skill when using Backseat Driver tools.
Always load the `editing-clojure-files` skill when editing Clojure files.
