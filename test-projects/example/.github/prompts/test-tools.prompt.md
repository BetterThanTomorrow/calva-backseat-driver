---
description: 'Systematic smoke tests for Backseat Driver tools - validates core functionality and basic error handling after tool updates.'
mode: clojure-interactive-programming
---

# Backseat Driver Tool Smoke Tests

Run basic validation tests for all Backseat Driver tools to ensure core functionality works after updates. Create a dedicated test file to avoid modifying production code.

## Test Workflow

Create a fresh test file: `src/mini/tool_smoke_test_<round_number>.clj`

Then test each category systematically:

### 1. Structural Editing Tools

Test the complete file editing lifecycle:

**Test: Create Clojure File**
- Create new file with namespace and one simple function
- ✓ Verify: File created with valid syntax, no diagnostics errors

**Test: Append Code**
- Add 1-2 functions to end of file
- ✓ Verify: Forms appended correctly, syntax remains valid

**Test: Insert Top Level Form**
- Insert a data definition before an existing function
- ✓ Verify: Form inserted at correct position, line numbers shift properly

**Test: Replace Top Level Form**
- Modify an existing function (e.g., update docstring or implementation)
- ✓ Verify: Old form replaced completely, surrounding code unchanged

### 2. REPL Tools

Test interactive development cycle:

**Test: Evaluate Clojure Code**
- Load the namespace and call functions with test data
- ✓ Verify: Namespace loads, functions return expected results

**Test: Clojure Symbol Info**
- Look up documentation for a core function (e.g., `map`, `reduce`)
- ✓ Verify: Returns docstring, arglists, and file location

**Test: ClojureDocs Info**
- Query ClojureDocs for examples of a core function
- ✓ Verify: Returns examples, notes, and see-also references

### 3. Utility Tools

Test specialized functionality:

**Test: Bracket Balancer**
- Balance intentionally malformed code with missing closing brackets
- ✓ Verify: Returns properly balanced code

**Test: REPL Output Log**
- Query recent REPL output with `sinceLine` parameter
- ✓ Verify: Returns evaluation history from specified line

## Success Criteria

- ✅ All tools respond without errors
- ✅ File edits maintain valid Clojure syntax
- ✅ REPL integration works seamlessly
- ✅ Documentation lookups return relevant information
- ✅ Diagnostics provide useful feedback

## One Basic Edge Case

Test one error scenario for structural editing tools:

- Provide **intentionally wrong `targetLineText`** to a structural editing tool
- Verify the error message is clear and actionable

This validates that error handling provides helpful guidance when operations fail.

---

Keep tests simple and focused. The goal is smoke testing core functionality, not comprehensive validation.
