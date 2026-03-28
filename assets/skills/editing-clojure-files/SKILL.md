---
name: editing-clojure-files
description: 'Structural editing of Clojure files using Backseat Driver tools. Use when: creating .clj/.cljs/.cljc/.bb files, adding/inserting/replacing/deleting top-level forms, fixing bracket balance, planning multi-edit sequences, recovering from failed edits, or working with Rich Comment Forms. Whenever you consider any of these tools: clojure_create_file, clojure_append_code, insert_top_level_form, replace_top_level_form, clojure_balance_brackets. Also use when editing Clojure and unsure which tool to pick. IMPORTANT: Also load this skill when PLANNING or DISCUSSING Clojure file edits — not only at the moment of editing.'
---

# Editing Clojure Files — Structural Editing with Backseat Driver

Clojure code is a tree of forms (S-expressions), not lines of text. The structural editing tools understand this structure, and make the edit unit a complete top-level form — `ns`, `defn`, `def`, `(comment ...)`, etc. Auto-balance brackets via Parinfer, and return post-edit diagnostics. If your editing task can be expressed as a top-level form structural edit, then use it rather than generic text replacement (`replace_string_in_file`, `create_file`).

## Delegate edits to a subagent

Unless you have been specifically tasked with editing files yourself, you should always use a subagent for editing. This protects your context window from the details of editing and any mistakes during. Use the most appropriate subagent for the task — e.g. a `Clojure-editor` subagent may be available.

**When delegating**: Provide exact file paths, the code to write (already REPL-verified and properly indented), target line info, any context you have that helps takes decisions around the editing, and explicit edit order instructions.

## Which Tool?

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

Fixing broken brackets in a file is a special case, where structural top-level edits are not possible. The `clojure_balance_brackets` tool can help you analyse what is broken, but beyond that, you will need to use regular text editing tools. If repeated attempts of trying to fix the brackets fail, escalate to the human via the #askQuestions tool.

## Targeting Forms

The `replace_top_level_form` and `insert_top_level_form` tools locate forms using two parameters: `line` (1-based line number) and `targetLineText` (the exact first line of the target form). Be vigilant about getting this right.

**Read the file first** to get accurate line numbers and exact text.

```
File at line 23:
  (defn process-data
    [items]
    (map transform items))

Parameters:
  line: 23
  targetLineText: "(defn process-data"
```

**Rich Comment Forms**: Forms directly inside `(comment ...)` are valid top-level targets. The `(comment ...)` wrapper itself is also a top-level target.

## Indentation Is Structure

Parinfer infers closing brackets from indentation. Misaligned code produces structurally wrong results — silently.

```clojure
;; ❌ Parinfer closes the map after :foo — :bar is outside
{:foo 1
:bar 2}

;; ✅ Correct — :bar aligns with :foo, both inside the map
{:foo 1
 :bar 2}
```

Rules:
- Map values align with their keys, and MUST be indented past the opening paren
- All form children MUST be indented past their opening paren
- Elements at the same nesting level share the same indentation

**Always ensure proper indentation before passing code to structural editing tools.**

## Edit process

Verify your edits!:

1. **Edit the file** — use the structural tool with REPL-verified code
2. **Check diagnostics** — the tool returns post-edit linting info; read and act on it
3. **Reload** — `(require 'the.namespace :reload)` to confirm the file loads cleanly

In a hot-reload environment, the repl output log will show any compile errors or warnings immediately after the edit. Read and act on them before proceeding.

## Process for Multi-Edit: Bottom-to-Top

When making multiple edits to a file, always edit from **highest line number to lowest**. Each edit shifts line numbers below it. Working bottom-up keeps your planned line numbers accurate.

1. Read the file — identify all edit targets with line numbers
2. Sort edits by line number, **descending**
3. Apply each edit (highest line first)
4. Read the file afterward to verify final state

Example: editing forms at lines 10, 25, 40 → edit order: 40 → 25 → 10.

During bottom-to-top editing, expect temporary linter warnings about undefined symbols — they resolve once the full sequence completes.

## Error Recovery

### Bracket balance broken

1. Use `clojure_balance_brackets` — pass the complete file content
2. Accept the balancer's output as authoritative — do NOT analyze or modify it
3. If the balancer doesn't resolve it, ask the human for help using the #askQuestions tool.

## Quick Reference

| Don't | Do instead |
|---|---|
| `replace_string_in_file` for Clojure | Structural editing tools |
| `create_file` for .clj/.cljs/.cljc | `clojure_create_file` |
| Edit top-to-bottom in multi-edit | Bottom-to-top (highest line first) |
| Guess at indentation | Align properly — Parinfer depends on it |
| Ignore post-edit diagnostics | Read and act on warnings/errors |
| Retry failed edit 5+ times | When you get stuck, escalate to human using the #askQuestions tool |
| Provide inner line as targetLineText | Exact first line of the form |
