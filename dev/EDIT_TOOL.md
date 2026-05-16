# Batch Structural Edit Tool - Calva Backseat Driver

## Overview

`clojure_edit_files` is a batch structural editing tool that handles create, replace, insert, and append operations across multiple files in a single call. It leverages Calva's ranges API for semantic Clojure editing, with automatic bracket balancing via Parinfer.

## Design Evolution

The current batch approach consolidates four individual tools:
- `replace_top_level_form` → `type: "replace"`
- `insert_top_level_form` → `type: "insert"`
- `clojure_create_file` → `type: "create"`
- `clojure_append_code` → `type: "append"`

Text targeting (scan ±2 lines around target) remains the core positioning mechanism for replace/insert.

## Architecture

```
Input: [{type, filePath, ...params}]
       ↓
Schema Validation (blocks all on error)
       ↓
Index edits (preserve original order)
       ↓
Group by filePath
       ↓
Per file: sort → apply sequentially → poll diagnostics once
       ↓
Output: {summary, files: {path: {edits, diagnostics}}}
```

### Sort Order Within a File
1. `create` first
2. `replace`/`insert` by line descending (highest first)
3. `append` last

### Failure Handling
- Schema errors: block entire batch
- Edit failures: continue within file, report per-edit success/failure
- Cross-file: files processed sequentially, all attempted regardless of failures

## Tool API

### Input Schema
```json
{
  "edits": [
    {"type": "replace", "filePath": "/abs/path.clj", "line": 23, "targetLineText": "(defn foo", "newForm": "(defn foo [x] x)"},
    {"type": "insert", "filePath": "/abs/path.clj", "line": 10, "targetLineText": "(defn bar", "newForm": "(defn helper [] nil)"},
    {"type": "append", "filePath": "/abs/path.clj", "code": "(defn new-fn [] :ok)"},
    {"type": "create", "filePath": "/abs/new.clj", "content": "(ns my.new)\n\n(defn init [] :ok)"}
  ]
}
```

### Constraints
- At most one `create` per filePath
- At most one `append` per filePath
- `filePath` must be absolute (starts with `/`)
- `replace`/`insert` require: `line` (integer), `targetLineText` (string), `newForm` (string)

### Response Shape
```clojure
{:summary "3/4 edits applied across 2 files"
 :files {"/path/a.clj" {:file-path "/path/a.clj"
                         :edits [{:success true :index 0} {:success false :error "..." :index 1}]
                         :diagnostics-before-edit [...]
                         :diagnostics-after-edit [...]}
         "/path/b.clj" {:file-path "/path/b.clj"
                         :edits [{:success true :index 2}]
                         :diagnostics-before-edit [...]
                         :diagnostics-after-edit [...]}}}
```

## Implementation

### Key Files
- `src/calva_backseat_driver/integrations/calva/batch_edit.cljs` — pure validation and sorting
- `src/calva_backseat_driver/integrations/calva/features.cljs` — orchestration (`edit-files+`)
- `src/calva_backseat_driver/integrations/calva/editor.cljs` — core edit primitives
- `src/calva_backseat_driver/mcp/requests.cljs` — MCP handler
- `src/calva_backseat_driver/tools.cljs` — VS Code Language Model tool registration

### Core Functions
- `batch-edit/validate-edit-schema` — pure pre-validation
- `batch-edit/sort-edits-for-file` — pure sort for safe application order
- `features/edit-files+` — orchestration entry point
- `editor/apply-form-edit+` — single form edit without diagnostics
- `editor/create-file-core+` — file creation without diagnostics
- `editor/append-code-core+` — append without diagnostics

## Known Issues & Workarounds

- **Large line offsets**: May exceed scan window (±2 lines). Consider expanding window or using absolute positioning
- **Non-structural edits**: AI agents are instructed via error messages to use built-in line-oriented tools for top-level comments and other non-structural content
- **Ignored diagnostics**: AI agents frequently ignore post-edit lint feedback. Considering lint diff format for clearer communication


## Security & Testing

TODO!

**Security**: Validate file paths, check permissions, sanitize input, respect REPL security model.

**Testing**: Unit tests for accuracy/error handling, integration tests for workflows, interactive testing with real codebases.

---

This toolset enables AI agents to edit Clojure code effectively by respecting the language's form-based nature while using error messages and validation to guide agents toward appropriate tools for different editing tasks.
