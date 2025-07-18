# Form-Aware Edit Tool - Calva Backseat Driver

## Overview

Form-aware editing tools that leverage Calva's ranges API for semantic Clojure editing. Enables AI agents to edit Clojure code by operating on forms rather than lines, with automatic bracket balancing and structural awareness.

## Design Evolution

The current line-based approach with text targeting evolved through several iterations:

1. **Character positions**: AI agents couldn't reliably determine character indices
2. **Simple line numbers**: File metadata (e.g., `; filepath:` comments) caused offset issues
3. **Auto-offsetting**: Failed when agents had accurate line info (e.g., from selections)
4. **Text targeting** (current): Scans ±2 lines around target for validation

**Future Direction**: Move to explicit range-based tools (`read-forms`/`replace-range`) to mirror built-in AI tools and eliminate positioning ambiguity.

## Status

| Tool | VS Code | MCP | Description |
|------|---------|-----|-------------|
| `replace_top_level_form` | ✅ | ✅ | Replace structural forms with text targeting |
| `insert_top_level_form` | ✅ | ✅ | Insert structural forms with text targeting |

**Key Limitations**:
- Non-structural edits (top-level comments) handled via error messages directing AI to use built-in line-oriented tools
- Text targeting scan window may miss large line offsets
- Post-edit diagnostics often ignored by AI agents

## Tool APIs

Both tools use line-based positioning with text targeting for accuracy:

### `replace_top_level_form`
```clojure
(defn apply-form-edit-by-line-with-text-targeting
  [file-path line-number target-line-text new-form])
```

### `insert_top_level_form`
```clojure
(defn apply-form-edit-by-line-with-text-targeting
  [file-path line-number target-line-text new-form])
```

**Common Parameters:**
- `file-path`: Absolute path to Clojure file
- `line-number`: Line number (1-indexed) to identify target
- `target-line-text`: Exact text content for validation (searches ±2 lines)
- `new-form`: Form content to replace or insert

**Common Features:**
- Text targeting with fuzzy line matching (±2 lines, may need expansion for large offsets)
- Automatic Parinfer bracket balancing (forms only)
- Rich comment form support (forms inside `(comment ...)` treated as top-level)
- Post-edit diagnostics (often ignored by AI agents - considering lint diffs instead)
- Error messages guide AI agents to use built-in tools for non-structural edits (comments)


## Usage Examples

### Basic Usage
```clojure
;; Replace form
replace_top_level_form({
  filePath: "/path/to/file.clj",
  line: 23,
  targetLineText: "(defn old-function [x]",
  newForm: "(defn new-function [x y] (+ x y))"
})

;; Insert top-level form
insert_top_level_form({
  filePath: "/path/to/file.clj",
  line: 45,
  targetLineText: "(defn process-data",
  newForm: ";; Helper functions for data processing\n\n(defn helper-fn [x] x)"
})
```

### Error Handling
```clojure
// When target text is not found
{
  success: false,
  error: "Target line text not found. Expected: '(defn wrong-function [x]' near line 23"
}

// When attempting to use structural tools for comments
{
  success: false,
  error: "Target line text cannot start with a comment (;). You can only target forms/sexpressions. (To edit line comments, use your line based editing tools.)"
}

// Line offset exceeds scan window
{
  success: false,
  error: "Target text found outside scan window. Line offset: 5, Window: ±2"
}
```

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
