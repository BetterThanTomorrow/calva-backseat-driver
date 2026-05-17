---
name: editing-clojure-files
description: 'Structural editing of Clojure files using Backseat Driver tools. Use when editing or planning edits: creating/adding/inserting/replacing/deleting top-level forms, fixing bracket balance, resolving indentation issues, planning multi-edit sequences, recovering from failed edits, or working with Rich Comment Forms in Clojure files, regardless of dialect or runtime. Use whenever you consider any of these tools: clojure_edit_files, clojure_balance_brackets. Use when editing Clojure and unsure which tool to pick. Use this skill when PLANNING or DISCUSSING Clojure file edits — not only at the moment of editing.'
---

# Editing Clojure Files

Clojure code is a tree of forms, not lines of text. The unit of editing is the top-level form: `ns`, `defn`, `def`, `(comment ...)`, etc. The structural editing tool `clojure_edit_files` understands this structure, auto-balances brackets via Parinfer, and returns post-edit diagnostics. Use `clojure_edit_files` whenever a structural edit is possible.

## Delegation

Unless you have been specifically tasked with editing files yourself, always delegate file editing to a subagent. This protects the context window and isolates errors. Prefer the `Clojure-editor` subagent when available.

**When delegating**: Provide exact file paths, REPL-verified code with proper indentation, target line info, decision context, and explicit edit order instructions.

## Code Shape for Tool Success

Structural editing tools operate on whole top-level forms — they read, transform, and rewrite the entire form in one pass. Smaller, simpler forms have higher success rates.

### Thresholds

| Metric | Target |
|--------|--------|
| Function length |
| Nesting depth | **Under 4 levels** |
| Cyclomatic complexity | **Under 9 branches** |

### Code smells that break structural edits

1. **Large Method** — Over ~25 lines. Split at responsibility boundaries; use `defn-` helpers so each is an independently editable top-level form.
2. **Deep Nested Complexity** — 3+ levels of `let`/`when`/`if`/`cond` inside each other. Use threading macros (`->`, `->>`) to flatten transformations. Extract inner bodies into named functions.
3. **Bumpy Road** — Alternating simple and complex sections in one function. Each bump is a misalignment point. One responsibility per function.
4. **Complex Method** — Many conditional branches in one function. Keep `case`/`cond`/`condp` branch bodies to one-liners or calls. Prefer data-driven dispatch (`defmethod`, maps of keyword → handler) over deep conditional trees.
5. **Code Duplication** — Repeated structure across functions. Extract once; each duplicate doubles the chance of structural mismatch during edits.

### The refactor-first rule

When a function you need to modify already violates these thresholds, improve its structure first in a separate edit, verify the refactoring, then make the requested change. Structural edits on bloated forms are error-prone — the tool is fighting the shape of the code instead of focusing on the change.

## The Tool: `clojure_edit_files`

One tool handles all structural editing operations. It accepts a batch of edits, validates them up front, groups by file, sorts for safe application order, and applies them sequentially.

### Edit Types

| Type | Purpose | Required fields |
|------|---------|-----------------|
| `create` | Create a new file with content | `filePath`, `content` |
| `append` | Append forms to end of file | `filePath`, `code` |
| `replace` | Replace an existing top-level form | `filePath`, `line`, `targetLineText`, `newForm` |
| `insert` | Insert a form before an existing form | `filePath`, `line`, `targetLineText`, `newForm` |

### Constraints

- At most one `create` per file per call
- At most one `append` per file per call
- All edits are schema-validated before any are applied — one invalid edit blocks the entire batch
- Within a file: creates run first, then replace/insert (highest line first), then appends
- Across files: files are processed sequentially

### Usage Pattern

```json
{
  "edits": [
    {"type": "replace", "filePath": "/path/to/file.clj", "line": 23, "targetLineText": "(defn process-data", "newForm": "(defn process-data\n  [items]\n  (map transform items))"},
    {"type": "insert", "filePath": "/path/to/file.clj", "line": 10, "targetLineText": "(defn helper", "newForm": "(defn new-fn\n  [x]\n  (inc x))"},
    {"type": "append", "filePath": "/path/to/other.clj", "code": "(defn added-fn\n  []\n  :done)"},
    {"type": "create", "filePath": "/path/to/new_file.clj", "content": "(ns my.new-file)\n\n(defn init []\n  :ok)"}
  ]
}
```

### Deleting a Form

Use `replace` with an empty `newForm`:
```json
{"type": "replace", "filePath": "/path/file.clj", "line": 15, "targetLineText": "(defn obsolete-fn", "newForm": ""}
```

### When Brackets Are Broken

Structural top-level edits are not possible when brackets are unbalanced. Use `clojure_balance_brackets` to fix brackets — it runs Parinfer and returns corrected text. Fall back to regular text editing tools if needed. If repeated attempts fail, ask the human for help.

### Line Comments

Structural tools operate on forms, not line comments (`;`). Use built-in text editing tools for line comments.

## Targeting Forms

The `replace` and `insert` edit types locate forms using two parameters: `line` (1-based line number) and `targetLineText` (the exact first line of the target form). Accuracy here is critical.

Read the file immediately before editing to get accurate line numbers and exact text. Example — if line 23 starts with `(defn process-data`, use `line: 23` and `targetLineText: "(defn process-data"`.

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

Verification is mandatory after every batch:

1. **Check problems first** — review current diagnostics (`get_errors` or REPL output); fix existing compilation problems before introducing new edits
2. **Batch edits** — assemble all edits for the call with REPL-verified code
3. **Check diagnostics** — read post-edit diagnostics from the response; in a hot-reload environment, also check the REPL output log for compile errors
4. **Reload** — `clojure_load_file` to confirm the file loads cleanly

## Error Recovery

- **Broken brackets** — Pass the complete file content to `clojure_balance_brackets`. Accept the output as authoritative. If unresolved, ask the human for help.
- **Target text not found** — Use `grep_search` scoped to the file to find the target text's current line number (cheaper than reading the whole file). Plain text mode for exact matches; in regex mode, escape parens as `\(`.
- **Partial batch failure** — The tool continues on failure. The response shows which edits succeeded and which failed. Re-read the file, get updated line numbers, retry failed edits in a new batch.
- **Scan window miss** — The text exists but outside the ±2 window, most commonly because a prior edit shifted line numbers. Re-read the file and retry with updated targeting.

## Invariants

- Clojure file edits use `clojure_edit_files`; line comments use text editing tools
- New `.clj`/`.cljs`/`.cljc`/`.bb` files are created with `clojure_edit_files` type `create`
- Indentation is verified before every structural edit — Parinfer depends on it
- Post-edit diagnostics are read and acted on before proceeding
- `targetLineText` is always the exact first line of the target form
- After 5 failed retries on the same edit, ask the human for help
