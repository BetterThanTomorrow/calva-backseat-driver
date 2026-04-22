---
name: backseat-driver
description: 'Effective use of the Backseat Driver extension and its tools for Clojure interactive programming. Use when: working in Clojure (including all dialects and runtimes) project, be it reading, planning, developing, or evaluating code in the REPL, looking up function documentation or ClojureDocs examples, choosing REPL sessions, editing Clojure files structurally, checking REPL output, planning implementations, reviewing code, or developing solutions incrementally. Whenever you consider any of these tools: clojure_evaluate_code, clojuredocs_info, clojure_list_sessions, clojure_symbol_info, clojure_repl_output_log, replace_top_level_form, insert_top_level_form, clojure_append_code, clojure_create_file, clojure_balance_brackets. Also use this skill when PLANNING or DISCUSSING Clojure development approaches — not only at the moment of REPL evaluation.'
---

# Backseat Driver — Effective Tool Usage

**"Use the REPL"** means: call the `clojure_evaluate_code` tool. This is the Backseat Driver tool that evaluates code in Calva's connected REPL — the same REPL the user is connected to. Always use this tool rather than launching a terminal REPL. If there is no REPL connection, ask the user to connect one.

## Step 0: Add Clojure skills

Scan your available skills for Clojure-related ones. If you see a skill named Clojure, or a skill being about Clojure, read it in full and internalize its content.

### In lack of Clojure skills

If you don't see such a skill available, tell the user to install the `clojure` plugin from the Awesome Backseat Driver marketplace. Give these instructions:

```markdown
1. From the VS Code command palette run: **Chat: Install Plugin from Source**
1. Paste: https://github.com/BetterThanTomorrow/awesome-backseat-driver
1. Select **clojure**
````

## Core Principles

1. **ClojureDocs before guessing** — Before using any core function you the very least unsure about how to use effectively, call `clojuredocs_info`. You will get docstrings, argument lists, community examples, and gotchas. What's not to love?

2. **Structural editing for structural code** — Clojure is a structural language. Use the structural editing tools, not generic text replacement. See `dev/tool-instructions/editing-clojure-files.md` for detailed workflows.

## The Workflow

Every task — implementing, debugging, planning, reviewing — follows this loop:

1. ORIENT    → clojure_list_sessions (know your REPLs)
2. RESEARCH  → clojuredocs_info / clojure_symbol_info / clojure_evaluate_code (understand the functions)
3. EXPLORE   → clojure_evaluate_code (test ideas in the REPL)
4. BUILD UP  → clojure_evaluate_code (compose verified pieces)
5. APPLY     → structural editing tools (edit files with verified code — check returned linter diagnostics)
6. VERIFY    → clojure_evaluate_code (load namespace, call functions) + get_errors (Problems report)
7. MONITOR   → clojure_repl_output_log (side effects, errors) + get_errors (new warnings/errors)

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

### Code blocks shown to the user

When showing Clojure code in chat, prepend with `(in-ns 'relevant.namespace)` so the user can evaluate it directly from the code block. The relevant namespace is typically where the code belongs. Include `(in-ns)` in every code block — not just the first one.

### Chat vs files: REPL evaluation vs Rich Comment Forms

In chat, show direct REPL evaluation the user can run. In files, document validated work with Rich Comment Forms.

```clojure
;; In chat - show direct REPL evaluation:
(in-ns 'my.namespace)
(let [test-data {:user/name "example"}]
  (process-user-data test-data))

;; In files - document with RCF:
(comment
  (process-user-data {:user/name "example"})
  :rcf)
```

### Evaluate subexpressions, not println

When you need to understand what a value is, **evaluate the expression directly**. Do not wrap it in `println`. Direct evaluation gives you the actual data; println gives you a string representation that's harder to work with.

### Visual results

Any `data:image/...` URLs found anywhere in a result — whether the result is a plain string, a map, a vector, or deeply nested data — are automatically extracted and returned as viewable images alongside the text. This applies to both evaluation results and output log queries. You can visually inspect rendered output through the REPL.

### Check the output log

`clojure_repl_output_log` queries the REPL output log via Datascript Datalog. Use it to stay aware of what's happening in the REPL without pulling the entire log into your context.

**When to query:**
- After evaluating code with side effects
- When something unexpected happens
- To check for errors you might have missed
- To retrieve earlier evaluations that scrolled out of your context
- Periodically during long tasks
- To search for evaluations from this and previous sessions

**Log persistence:** Only `evaluatedCode` entries are retained across VS Code restarts and window reloads. Other categories (`evaluationResults`, `evaluationOutput`, `evaluationErrorOutput`, `otherOutput`) exist only for the current session. This means the log serves as a long-term searchable history of what was evaluated — useful when an agent or the human developer needs to find something from a past session.

**Common queries** (use with the `query` parameter, `inputs` is a JSON array for `:in` params):

Check for errors since a line you've already seen:
```
query:   [:find [(pull ?e [*]) ...]
          :in $ ?since
          :where [?e :output/category "evaluationErrorOutput"]
                 [?e :output/line ?l]
                 [(> ?l ?since)]]
inputs:  [42]
```

Retrieve your own evaluations since a checkpoint:
```
query:   [:find [(pull ?e [*]) ...]
          :in $ ?who ?since
          :where [?e :output/who ?who]
                 [?e :output/line ?l]
                 [(> ?l ?since)]]
inputs:  ["coder", 100]
```

See what another agent has been doing:
```
query:   [:find [(pull ?e [*]) ...]
          :in $ ?who
          :where [?e :output/who ?who]]
inputs:  ["reviewer"]
```

`(pull ?e [*])` returns all attributes. Since the `:where` clauses already narrow the result set, pulling everything is usually the right default. When you need a lightweight scan first — e.g. checking which evaluators are present before pulling full messages — use `(pull ?e [:output/line :output/who])` to keep the response compact, then follow up with `[*]` on the subset you care about.

Search text with truncated previews (context-window friendly):
```
query:   [:find ?l ?cat ?snippet
          :where [?e :output/text ?t]
                 [(re-find #"(?i)error" ?t)]
                 [?e :output/line ?l]
                 [?e :output/category ?cat]
                 [(count ?t) ?len]
                 [(min ?len 50) ?end]
                 [(subs ?t 0 ?end) ?snippet]]
```

More use cases — time-windowed queries, aggregation by category, overview scans — are covered in the [output log query reference](references/output-log-queries.md).

### JSON Escaping in Tool Calls

- The `code` parameter is a JSON string — use standard JSON escaping only
- `\"` inside code → `\"` in JSON (single escape) — never `\\\"` (double escape)
- Double-escaping produces garbled code in the REPL — the tool receives the literal backslashes

## Structural Editing

Use Backseat Driver's structural editing tools for all Clojure file modifications — not generic text replacement. The `editing-clojure-files` skill covers tool selection, targeting, multi-edit sequencing, indentation, error recovery, and subagent delegation in detail.

## Boundaries

### Joyride projects

When working in a Joyride context **"use the REPL" means `joyride_evaluate_code`**, not Backseat Driver's `clojure_evaluate_code`. Joyride's REPL runs in the VS Code Extension Host and is promise-aware in ways that Backseat Driver's REPL is not — using Backseat Driver's eval for Joyride code leads to async problems. Other tools of Backseat Driver (ClojureDocs, symbol info, structural editing, output log) remain useful.


## Invariants

- Verify core function semantics via `clojuredocs_info` before use
- Evaluate expressions directly — return values over print side effects
- Build up from verified subexpressions, composing into the solution
- Structural editing tools for all Clojure files (see `editing-clojure-files` skill)
- `clojure_list_sessions` at task start — know your REPLs
- Descriptions state intent: `"Testing filter with empty input"`
- `clojure_repl_output_log` after side effects — stay aware
- Multi-edit order: bottom-to-top (highest line first)
- Bracket balancer output is authoritative — accept as-is
- Joyride context → `joyride_evaluate_code`, not Backseat Driver eval
