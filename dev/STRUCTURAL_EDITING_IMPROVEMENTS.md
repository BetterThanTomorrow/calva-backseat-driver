# Structural Editing Tools Improvement Plan

## Problem Statement

The current structural editing tools (`insert_top_level_form`, `replace_top_level_form`) have issues that can lead to:
1. **Nested forms**: Tools can accidentally insert forms inside existing forms instead of at the true top level
2. **Append difficulties**: Models struggle with appending forms to the end of files
3. **Ambiguous targeting**: "Top level" concept is not clearly defined, leading to incorrect line targeting
4. **Error propagation**: Once nesting occurs, it cascades into more structural problems

## Proposed Solutions

### Phase 1: Immediate Fixes

#### 1.1 Add Structural Validation

**Pre-insertion validation:**
1. Verify that the form is a top level form (use Calva API for this)
2. `insert_top_level_form` should be changed to require that the target line is the start of an existing top level form
3. Reject insertion if target line is not the start of a top level form


#### 1.2 Strengthen Tool Instructions

Establish a common “human language name” for each tool. Use the Joyride GitHub repo for reference of how this should be done.

* Review the current tool instructions together with the human developer.

The current main instructions for the structural editing tools are [here](tool-instructions/editing-clojure-files.md), copy pasted into package.json where the instructions for the parameters are also hosted.

#### 1.3 Create `structural_create_file` Tool

* Uses VS Code `vscode/workspace.fs` API to write exact content
* Trims and adds trailing empty line at end of file
* Avoids auto-namespace insertion that confuses models

**Benefits:**
- Models get predictable starting state for new files
- Eliminates confusion from auto-generated namespace declarations
- Provides exact control over initial file structure
- Uses `vscode/workspace.fs` API directly for reliable file creation

#### 1.4 Create `append_top_level_form` Tool

* Append a form to the end of the file at the true top level
* Always appends after last top-level form with proper spacing

**Benefits:**
- Eliminates line number guessing for common append case
- Always inserts at guaranteed top level
- Simpler model interface for common pattern

## Implementation Priority

### High Priority (Phase 1)
- [ ] **Top level validation** - Stops nesting at the gate
- [ ] **Enhanced tool instructions** - Prevents basic targeting errors
- [ ] **`structural_create_file` tool** - Eliminates auto-namespace confusion
- [ ] **`append_code` tool** - Solves immediate append struggles

### Out of scope, but cool ideas
- [ ] **Rollback capability** - Safety net for complex edits

## Discussion Points

### 1. Backward Compatibility
**Question**: How to handle existing model patterns?
- Answer: we can change tool signatures for better design, if need be

## Open Questions

1. How should `structural_create_file` handle directory creation (auto-create vs. explicit)?
   * Answer: It should create directories as needed

## Next Steps

1. Make a todo list for top level validation
   1. Discuss with human developer about the requirements/implementation
   1. Implement top level validation
2. Make a todo list for better instructions,
   1. Discuss with human developer about the requirements/implementation
   1. Implement better instructions, to `dev/tool-instructions/<tool>.md`
3. Make a todo list for `structural_create_file` tool (addresses auto-namespace confusion)
   1. Discuss with human developer about the requirements/implementation
   1. Implement `structural_create_file` tool
4. Make a todo list for `append_top_level_form` tool (addresses auto-namespace confusion)
   1. Discuss with human developer about the requirements/implementation
   1. Implement `append_code` tool
