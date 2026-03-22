---
name: backseat-driver
description: 'Effective use of Backseat Driver tools for Clojure interactive programming. Use when: evaluating Clojure/ClojureScript/Babashka/SCI/Squint/Anything-Clojure-ish code in the REPL, looking up function documentation, choosing REPL sessions, editing Clojure files structurally, checking REPL output, planning implementations, reviewing code, or developing solutions incrementally. Whenever you consider any of these tools: clojure_evaluate_code, clojuredocs_info, clojure_list_sessions, clojure_symbol_info, clojure_repl_output_log, replace_top_level_form, insert_top_level_form, clojure_append_code, clojure_create_file, clojure_balance_brackets.'
---

# Backseat Driver — Effective REPL Tool Usage

You have a live REPL connected to the running system. The system is your source of truth — not your training data. Use it.

## Core Principles

1. **The REPL holds the truth** — Your training data may be wrong about function semantics, argument order, or return types. The running system is always current. Evaluate to verify.

2. **ClojureDocs before guessing** — Before using any core function you the very least unsure about how to use effectively, call `clojuredocs_info`. You will get docstrings, argument lists, community examples, and gotchas. What's not to love?

3. **Build up in small steps** — Start with the innermost subexpression. Verify each piece. Compose verified pieces into the solution. This often beats writing a complete function and hope it works.

4. **Planning is development** — The REPL is as relevant to a planner and reviewer as to an implementer. Use it to verify assumptions, test feasibility, and explore APIs during planning. Use it to verify assumptions during review.

5. **Structural editing for structural code** — Clojure is a structural language. Use the structural editing tools, not generic text replacement. See `dev/tool-instructions/editing-clojure-files.md` for detailed workflows.

## The Workflow

Every task — implementing, debugging, planning, reviewing — follows this loop:

```
1. ORIENT    → clojure_list_sessions (know your REPLs)
2. RESEARCH  → clojuredocs_info / clojure_symbol_info (understand the functions)
3. EXPLORE   → clojure_evaluate_code (test ideas in the REPL)
4. BUILD UP  → clojure_evaluate_code (compose verified pieces)
5. APPLY     → structural editing tools (edit files with verified code)
6. VERIFY    → clojure_evaluate_code (load namespace, call functions)
7. MONITOR   → clojure_repl_output_log (check for side effects, errors)
```

Not every task needs all steps. A planner might stop at step 4. A reviewer might focus on steps 1-3. But the sequence is always orient → research → explore.

## Session Awareness

### Starting a task

Call `clojure_list_sessions` first. It tells you:
- Which sessions exist (`clj`, `cljs`, `bb`, etc.)
- Which session is active for the user's current file (`isActiveSession`)

You don't need to re-check sessions for every evaluation, but do re-check when switching file types or when evaluations fail unexpectedly.

### The `who` parameter

Every evaluation requires a `who` identifier. Choose a handle that reflects your role:
- An implementer might use `coder` or `builder`
- A reviewer might use `reviewer`
- A planner might use `planner`

**For subagents**: Make subagents aware of their role so they choose appropriate `who` handles. This creates a legible audit trail in the REPL output log. If you spawn multiple subagents, give them distinct handles (<role>-A, <role>-B, ...) to differentiate their actions.

### The `description` parameter

Evaluation can include a meaningful `description`. This is what appears in the REPL output and what the human sees. You most often have a clear objective with an evaluation. Use that as the description. If the evaluation is just correcting a mistake with a previous evaluation, the description is not needed.

## ClojureDocs Discipline

`clojuredocs_info` gives you the docstring, argument lists, community examples, see-also references, and gotcha notes for any `clojure.core` function.

### When to look up

- Before using any core function you haven't verified this session
- When choosing between similar functions (`map` vs `mapv`, `assoc` vs `assoc-in`, `merge` vs `into`)
- When you want idiomatic alternatives to manual code
- When the user asks "what function does X?" — look it up, don't guess

### What you get

The community examples on ClojureDocs are often more valuable than the docstring. They show idiomatic patterns, edge cases, and common mistakes. The see-also references help you discover related functions.

### The rule

If you're about to write a function call and you're not 100% certain of its exact semantics, argument order, or return value — **look it up**.

## REPL Evaluation Strategy

### Evaluate subexpressions, not println

When you need to understand what a value is, **evaluate the expression directly**. Do not wrap it in `println`. Direct evaluation gives you the actual data; println gives you a string representation that's harder to work with.

### Check the output log

Call `clojure_repl_output_log` to see what's happened in the REPL — evaluations, stdout, stderr, errors. Use it:
- After evaluating code with side effects
- When something unexpected happens
- To see if other evaluators have been active (`otherWhosSinceLast`)
- Periodically during long tasks

Filter with `includeWho`/`excludeWho` when needed. Use `sinceLine` for incremental reads.

## Structural Editing

Use Backseat Driver's structural editing tools for all Clojure file modifications — not generic text replacement. The `editing-clojure-files` skill covers tool selection, targeting, multi-edit sequencing, indentation, error recovery, and subagent delegation in detail.

## Boundaries

### Joyride projects

When working with Joyride code, use **Joyride's own evaluation tool** instead of Backseat Driver's `clojure_evaluate_code`. Joyride's REPL is promise-aware in ways that BD's is not, and using BD's eval for Joyride code leads to async problems. BD's other tools (ClojureDocs, symbol info, structural editing, output log) remain useful.

### Workflow preferences

This skill teaches effective tool usage. It does not prescribe workflow preferences — those belong to the developer's own instructions and chat modes. Different developers will have different opinions about when to write tests first, how much REPL exploration to do, and how to structure their sessions.

## Anti-Pattern Quick Reference

| Don't | Do instead |
|---|---|
| Guess at core function semantics | `clojuredocs_info` to verify |
| `(println "debug:" x)` | Evaluate `x` directly |
| Write complete function, hope it works | Build up from subexpressions |
| Use generic text replacement for Clojure | Structural editing tools (see `editing-clojure-files` skill) |
| Skip session discovery | `clojure_list_sessions` first |
| Use `"eval"` as description | Describe intent: `"Testing filter with empty input"` |
| Ignore output log | `clojure_repl_output_log` after side effects |
| Edit top-to-bottom in multi-edit | Bottom-to-top (highest line first) |
| Modify bracket balancer output | Accept it as authoritative |
| Use BD eval for Joyride | Use Joyride's own evaluation tool |

## Quick Reference

```
ORIENT:     clojure_list_sessions → know your REPLs, pick who handle
RESEARCH:   clojuredocs_info → verify core fns before using them
EXPLORE:    clojure_evaluate_code → test ideas, verify assumptions
BUILD UP:   clojure_evaluate_code → compose verified subexpressions
EDIT:       structural tools → create, append, insert, replace
VERIFY:     clojure_evaluate_code → load ns, call functions
MONITOR:    clojure_repl_output_log → check output, errors, other whos
```
