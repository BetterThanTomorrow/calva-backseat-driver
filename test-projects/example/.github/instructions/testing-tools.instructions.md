---
description: 'Testing strategies and patterns for Calva Backseat Driver Agents/MCP toolset development and validation'
applyTo: '**'
---

# Testing Calva Backseat Driver Agent Tools

Essential patterns and strategies for developing and testing the Backseat Driver MCP server toolset.

## Testing Backseat Driver Tools After Updates

After renaming tools or updating descriptions, validate all tool categories systematically to ensure changes don't break functionality.

### General Testing Approach

Organize tests by tool category using a todo list to track progress. Create dedicated test files rather than modifying production code:
- Use descriptive names like `toolset_test.clj`, `round2_test.clj`
- Include proper namespaces and requires
- Keep test code simple and focused

### Tool Categories to Test

1. **Clojure Structural Editing Tools** - Test the complete file editing workflow:
   - **Create Clojure File** tool: Create new file with namespace and initial content
   - **Append Code** tool: Append new forms to end of file
   - **Insert Top Level Form** tool: Insert forms before existing top-level forms
   - **Replace Top Level Form** tool: Replace existing forms with updated versions
   - Verify diagnostics after each operation

2. **REPL and Evaluation Tools** - Test interactive development cycle:
   - **Evaluate Clojure Code** tool: Load/reload namespaces after file changes, evaluate functions with test data
   - **Clojure Symbol Info** tool: Lookup symbol documentation from REPL
   - **ClojureDocs Info** tool: Query ClojureDocs for examples and community patterns
   - Verify results match expected behavior

3. **Utility Clojure Tools** - Test specialized functionality:
   - **Bracket Balancer** tool: Balance brackets on intentionally malformed code
   - **REPL Output Log** tool: Query REPL output log at different points
   - Verify auto-correction and error recovery

4. **General Purpose Tools** - Test cross-cutting functionality:
   - **Human Intelligence** tool: Request human input via VS Code input box
   - Verify bidirectional communication works

### Verification Strategies

**Verify end-to-end workflows** (especially important for structural editing tools):
- File creation → editing → REPL evaluation → verification
- Each step should produce expected diagnostics
- REPL should successfully load and execute edited code

**Test error handling** (applies to all tools):
- For structural editing: Intentionally provide mismatched `targetLineText` to verify error messages
- For bracket balancer: Verify correct fixing of malformed code
- Check that all error messages are clear and actionable

**Success Indicators for Any Tool Update**:
- ✅ All tool IDs resolve correctly
- ✅ Tool descriptions accurately reflect functionality
- ✅ Expected behavior matches actual results
- ✅ Error messages are clear and actionable

**Success Indicators Specific to Structural Editing Tools**:
- ✅ Proper Clojure syntax maintained
- ✅ No bracket balance issues introduced
- ✅ REPL integration remains seamless
- ✅ Diagnostics provide useful feedback

### Testing Structural Editing Tools

**Test the complete editing lifecycle in a single workflow**:
1. Create a new file with initial namespace and one simple function
2. Append multiple functions to demonstrate accumulation
3. Insert a data definition before an existing function to test positioning
4. Replace an existing function to test modification
5. Load the namespace in the REPL and evaluate functions with test data

**Key validation points for editing tools**:
- After creation: File exists with proper namespace form and snake_case filename
- After append: New forms appear at end, diagnostics show all definitions
- After insert: New form appears before target, line numbers shift correctly for subsequent forms
- After replace: Updated form replaces old one completely, preserving surrounding code
- After REPL reload: All functions evaluate correctly with expected results

**Error scenario testing for structural editing**:
- Deliberately provide wrong `targetLineText` to verify error message clarity
- Verify error suggests reading the file and trying again
- Confirm no partial edits are applied on error

### Verifying Tool Responses with REPL

When testing tool outputs (especially error messages and formatted responses), use REPL evaluation to verify the actual string formatting:

**Pattern for testing formatted output**:
1. Trigger the tool operation (e.g., intentional error with wrong `targetLineText`)
2. Capture the response string (e.g., `file-context` field from error response)
3. Use **Evaluate Clojure Code** tool to print the string and verify formatting

```clojure
(in-ns 'test.namespace)

;; Define the response string exactly as received from tool
(def response-context
  "  95 | (defn fn-91 [] 91)\n→108 | (defn fn-104 [] 104)\n 109 | (defn fn-105 [] 105)")

;; Print to see actual formatting
(println response-context)
```

**Why this matters**:
- JSON serialization may show escaped characters (`\n`) rather than actual formatting
- What you see in tool response JSON may differ from how strings are actually formatted
- Printing reveals the true visual alignment, indentation, and spacing
- Essential for verifying adaptive formatting (e.g., line number width adjusting for file size)

**Use cases**:
- Verify error message `file-context` displays correctly aligned code
- Check that line number formatting adapts to file size (single-digit vs triple-digit lines)
- Confirm arrow markers (→) don't disrupt code indentation alignment
- Validate any formatted output string before considering it production-ready

This systematic approach ensures tool updates are production-ready and maintains confidence in the toolset's reliability.
