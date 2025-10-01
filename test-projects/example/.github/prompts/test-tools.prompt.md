---
description: 'Systematic smoke tests for Backseat Driver tools - validates core functionality and basic error handling after tool updates.'
mode: clojure-interactive-programming
---

# Backseat Driver Tool Smoke Tests

You are an AI agent testing the Backseat Driver MCP server tools by **using them directly**. These tools are designed for AI agents like you to perform Clojure development tasks.

Your mission is to manually exercise each Backseat Driver tool to validate:
- Core functionality works as expected when you use the tools
- Error handling provides clear, actionable feedback to you as an agent
- Edge cases related to fuzzy line matching and error context are handled gracefully

**Important**: This is NOT about writing or running automated tests. You will directly invoke each tool, observe the results, and verify they work correctly for agent-driven development workflows.

## Test Workflow

### Query Current Settings

Before testing, check the current fuzzy matching and error context settings using Joyride:

```clojure
(require '["vscode" :as vscode])

(let [config (vscode/workspace.getConfiguration "calva-backseat-driver")]
  {:fuzzy-line-targeting-padding (.get config "editor.fuzzyLineTargetingPadding")
   :line-context-response-padding (.get config "editor.lineContextResponsePadding")})
```

Note the values for:
- **fuzzyLineTargetingPadding** (default: 2) - lines to search ±target line
- **lineContextResponsePadding** (default: 10) - lines shown in error messages

### Create Test Files

Use the **Create Clojure File** tool to create these test files for different edge case scenarios:

1. **Main test file**: `src/mini/tool_smoke_test_<round_number>.clj` (standard size, 15+ lines)
2. **Tiny file**: `src/mini/tiny_edge_case.clj` (only 3-4 lines total)
3. **Similar lines file**: `src/mini/similar_lines.clj` (contains duplicate/similar code patterns)

Then test each category systematically:

### 1. Structural Editing Tools

Test the complete file editing lifecycle:

#### Create Clojure File
- Create new file with namespace and one simple function
- ✓ Verify: File created with valid syntax, no diagnostics errors

#### Append Code
- Add 1-2 functions to end of file
- ✓ Verify: Forms appended correctly, syntax remains valid

#### Insert Top Level Form
- Insert a data definition before an existing function
- ✓ Verify: Form inserted at correct position, line numbers shift properly

**Edge Case: Wrong targetLineText**
- Provide intentionally wrong `targetLineText`
- ✓ Verify: Error message is clear and actionable

**Edge Case: Line number beyond fuzzy window**
- Target line number >2 lines away from actual location (test `tiny_edge_case.clj`)
- ✓ Verify: Operation fails with helpful error context

**Edge Case: File smaller than context window**
- Use `tiny_edge_case.clj` (3-4 lines) which is smaller than the 21-line error context window
- ✓ Verify: Error context shows entire file without breaking

**Edge Case: Similar code patterns**
- Use `similar_lines.clj` with duplicate function signatures
- Provide line number within fuzzy window of multiple matches
- ✓ Verify: Matches the intended line using targetLineText

#### Replace Top Level Form
- Modify an existing function (e.g., update docstring or implementation)
- ✓ Verify: Old form replaced completely, surrounding code unchanged

**Edge Case: Wrong targetLineText**
- Provide intentionally wrong `targetLineText`
- ✓ Verify: Error message is clear and actionable

**Edge Case: Line number beyond fuzzy window**
- Target line number >2 lines away from actual location (test on `tiny_edge_case.clj`)
- ✓ Verify: Operation fails with helpful error context showing the actual matching line

**Edge Case: File smaller than context window**
- Trigger error on `tiny_edge_case.clj` (3-4 lines) which is smaller than the 21-line error context
- ✓ Verify: Error context shows entire file cleanly, with arrow marker on target line

### 2. REPL Tools

Test interactive development cycle:

#### Evaluate Clojure Code
- Load the namespace and call functions with test data
- ✓ Verify: Namespace loads, functions return expected results

#### Clojure Symbol Info
- Look up documentation for a core function (e.g., `map`, `reduce`)
- ✓ Verify: Returns docstring, arglists, and file location

#### ClojureDocs Info
- Query ClojureDocs for examples of a core function
- ✓ Verify: Returns examples, notes, and see-also references

### 3. Utility Tools

Test specialized functionality:

#### Bracket Balancer
- Balance intentionally malformed code with missing closing brackets
- ✓ Verify: Returns properly balanced code

#### REPL Output Log
- Query recent REPL output with `sinceLine` parameter
- ✓ Verify: Returns evaluation history from specified line

---

Keep tests simple and focused. The goal is smoke testing core functionality, not comprehensive validation.
