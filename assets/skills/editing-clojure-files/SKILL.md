---
name: editing-clojure-files
description: 'Structural editing of Clojure files using Backseat Driver tools. Use when: creating .clj/.cljs/.cljc/.bb files, adding/inserting/replacing/deleting top-level forms, fixing bracket balance, resolving indentation issues, planning multi-edit sequences, recovering from failed edits, or working with Rich Comment Forms. Use whenever you consider any of these tools: clojure_create_file, clojure_append_code, insert_top_level_form, replace_top_level_form, clojure_balance_brackets. Use when editing Clojure and unsure which tool to pick. Use this skill when PLANNING or DISCUSSING Clojure file edits — not only at the moment of editing.'
---

# Editing Clojure Files

Clojure code is a tree of forms, not lines of text. The unit of editing is the top-level form: `ns`, `defn`, `def`, `(comment ...)`, etc. The structural editing tools understand this structure, auto-balance brackets via Parinfer, and return post-edit diagnostics. Whenever a structural edit is possible, prefer these tools over generic text editing (`replace_string_in_file`, `create_file`).

## Delegation

Unless you have been specifically tasked with editing files yourself, always delegate file editing to a subagent. This protects the context window and isolates errors. Prefer the `Clojure-editor` subagent when available.

**When delegating**: Provide exact file paths, REPL-verified code with proper indentation, target line info, decision context, and explicit edit order instructions.

## Code Shape for Tool Success

Smaller, simpler forms mean less code to get right in a single edit, fewer indentation levels to manage, lower chance of Parinfer misinterpreting structure, and easier error recovery when edits fail.

- Keep functions focused — one responsibility, appropriate abstraction level
- Prefer shallow nesting over deeply nested `let`/`if`/`when`/`loop` chains — extract named helpers
- Keep `case`/`cond`/`condp` branches lean — complex bodies belong in named functions

When a function you need to modify is already long or deeply nested, improve its structure first, then make the requested change. Structural edits on bloated forms are error-prone.

## Tool Selection

```
What are you doing?
├── Creating a new file?
│   → clojure_create_file
│     Include all known content at creation time.
│     Namespace kebab-case → filename snake_case.
│
├── Adding forms to end of existing file?
│   → clojure_append_code
│
├── Inserting a form before an existing form?
│   → insert_top_level_form
│
├── Modifying an existing form?
│   → replace_top_level_form
│
├── Deleting a form?
│   → replace_top_level_form with empty newForm
│
├── Fixing broken brackets?
│   → clojure_balance_brackets
│     Accept its output as authoritative. NEVER modify it.
│
└── Editing line comments (; ...)?
    → Use built-in text editing tools (not structural tools)
```

When brackets are broken, structural top-level edits are not possible. Use `clojure_balance_brackets` to analyse the breakage, then fall back to regular text editing tools if needed. If repeated attempts fail, escalate to the human via the #askQuestions tool.

## Targeting Forms

The `replace_top_level_form` and `insert_top_level_form` tools locate forms using two parameters: `line` (1-based line number) and `targetLineText` (the exact first line of the target form). Accuracy here is critical.

Read the file immediately before editing to get accurate line numbers and exact text.

```
File at line 23:
  (defn process-data
    [items]
    (map transform items))

Parameters:
  line: 23
  targetLineText: "(defn process-data"
```

**Scan window**: The tool searches for `targetLineText` within ±2 lines of the given `line` number. If the offset is greater than 2, the tool fails even if the text exists elsewhere in the file. Previous edits shift line numbers — always re-read before editing.

**Rich Comment Forms**: Forms directly inside `(comment ...)` are valid top-level targets. The `(comment ...)` wrapper itself is also a valid top-level target.

## Indentation Is Structure

Parinfer infers closing brackets from indentation. Misaligned code produces structurally wrong results — silently.

```clojure
;; Wrong — Parinfer closes the map after :foo, :bar is outside
{:foo 1
:bar 2}

;; Correct — :bar aligns with :foo, both inside the map
{:foo 1
 :bar 2}
```

Rules:
- Map values align with their keys and must be indented past the opening brace
- All form children must be indented past their opening paren
- Elements at the same nesting level share the same indentation

Always ensure proper indentation before passing code to structural tools.

## Definition Order

Definitions must precede their call sites in the file. When edits would create a call-before-define situation, rearrange the code. For circular dependencies, factor into separate namespaces, unless in an explicit one-namespace context. `declare` is a rare last resort.

## Edit Process

Verification is mandatory after every edit:

1. **Check problems first** — review current diagnostics; fix existing compilation problems before introducing new edits
2. **Edit the file** — use the structural tool with REPL-verified code
3. **Check diagnostics** — read post-edit linting info; act on unexpected problems before the next edit
4. **Reload** — `(require 'the.namespace :reload)` to confirm the file loads cleanly

In a hot-reload environment, the REPL output log shows compile errors and warnings immediately after the edit. Read and act on them before proceeding.

## Multiple Edits: Bottom-to-Top

Always edit from highest line number to lowest. Each edit shifts line numbers below it — working bottom-up keeps planned numbers accurate.

1. Read the file — identify all edit targets with line numbers
2. Sort edits by line number, **descending**
3. Apply each edit (highest line first)
4. Read the file afterward to verify final state

Example: editing forms at lines 10, 25, 40 → edit order: 40 → 25 → 10.

Expect temporary linter warnings about undefined symbols during a multi-edit sequence — they resolve once the full sequence completes.

## Error Recovery

### Broken Bracket Balance

1. Pass the complete file content to `clojure_balance_brackets`
2. Accept the output as authoritative — do not analyze or modify it
3. If unresolved, ask the human for help using the #askQuestions tool

### Target Text Not Found

The `targetLineText` does not match any line in the ±2 scan window:
```
Target line text not found. Expected: '(defn wrong-function [x]' near line 23
```
Fix: Use `grep_search` scoped to the file to find the target text's current line number — cheaper than reading the whole file. Plain text mode for exact matches; in regex mode, escape parens as `\(`.

### Comment Targeting

Structural tools operate on forms, not line comments:
```
Target line text cannot start with a comment (;). You can only target forms/sexpressions.
```
Fix: Use `replace_string_in_file` or equivalent text tool for line comments.

### Scan Window Miss

The text exists but outside the ±2 window — most commonly because previous edits shifted line numbers. Search the file for the target text to get its current line number.

## Extensibility

This skill provides the shared baseline for structural editing mechanics. User-level and workspace-level skills extend it with editing workflow preferences, delegation patterns, and project conventions.

When this skill is loaded, also load any corresponding `clojure-coding` or `clojure-editor` skills from the user profile or workspace — they carry workflow preferences that complement this baseline.

## Invariants

- Clojure file edits use structural tools; line comments use text editing tools
- New `.clj`/`.cljs`/`.cljc`/`.bb` files are created with `clojure_create_file`
- Multi-edit sequences proceed bottom-to-top (highest line number first)
- Indentation is verified before every structural edit — Parinfer depends on it
- Post-edit diagnostics are read and acted on before proceeding
- `targetLineText` is always the exact first line of the target form
- After 5 failed retries on the same edit, escalate to the human via #askQuestions
