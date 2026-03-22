---
name: backseat-driver-testing
description: 'Testing strategies for Calva Backseat Driver MCP tools. Use when: validating BD tool updates, testing structural editing workflows, verifying REPL evaluation with who-tracking, testing output log filtering, smoke testing after dep bumps, or debugging tool behavior. Covers all BD tool categories: structural editing, REPL eval, symbol info, bracket balancing, output log, and human intelligence.'
---

# Backseat Driver Testing Skill

Strategies and patterns for testing the Backseat Driver MCP server toolset. Use this skill when validating tool updates, testing new features, or debugging tool behavior.

## When to Use This Skill

- After bumping Backseat Driver dependencies and rebuilding
- When testing new or modified tool parameters
- When validating structural editing, REPL evaluation, or output log behavior
- When debugging who-tracking or cross-evaluator awareness

## Tool Categories

### 1. Structural Editing Tools

Test the complete file editing lifecycle:

**Create Clojure File** → **Append Code** → **Insert Top Level Form** → **Replace Top Level Form**

**Workflow**:
1. Create a new file with namespace and one simple function
2. Append multiple functions to demonstrate accumulation
3. Insert a data definition before an existing function to test positioning
4. Replace an existing function to test modification
5. Load the namespace in the REPL and evaluate functions with test data

**Validation points**:
- After creation: File exists with proper namespace form and snake_case filename
- After append: New forms appear at end, diagnostics show all definitions
- After insert: New form appears before target, line numbers shift correctly
- After replace: Updated form replaces old one, preserving surrounding code
- After REPL reload: All functions evaluate correctly

**Error scenario testing**:
- Provide wrong `targetLineText` — verify clear error message with file context
- Target line beyond fuzzy window — verify failure with helpful context
- Test on tiny files (3-4 lines) smaller than context window — verify clean display
- Test with similar/duplicate code patterns — verify correct line matching

### 2. REPL Evaluation with Who-Tracking

The `clojure_evaluate_code` tool supports evaluator identity tracking.

**Required parameters**:
- `code`: Clojure code to evaluate
- `who`: Evaluator identity slug (e.g., `"copilot"`) — **required**
- `namespace`: Target namespace
- `replSessionKey`: REPL session key (e.g., `"clj"`)

**Optional parameters**:
- `description`: Human-readable description of the evaluation purpose

**Response fields to verify**:
- `result`: Evaluation result
- `ns`: Namespace after evaluation
- `who`: Echo of the evaluator identity
- `other-whos-since-last`: Array of other evaluator slugs that evaluated since this evaluator's last eval
- `notes`: Array of informational messages (includes other-evaluator alerts)
- `stdout`, `stderr`: Captured output
- `session-key`: Echo of the session key

#### Who-Tracking Test Protocol

Test pure API-to-API tracking (UI eval tracking is a known Calva limitation):

**Step 1 — Baseline**: Evaluate as `"copilot"` via BD tool. Expect empty `other-whos-since-last`.

**Step 2 — Interleave via Joyride**: Use Joyride to evaluate via the Calva API with a different `who`:
```clojure
(require '["ext://betterthantomorrow.calva$v1" :as calva])
(require '[promesa.core :as p])

(p/let [r (calva/repl.evaluate "(+ 1 1)"
            #js {:who "joyride-test" :ns "user" :sessionKey "clj"})]
  {:who (.-who r) :others (js->clj (.-otherWhosSinceLast r))})
```

**Step 3 — Verify cross-tracking**: Evaluate again as `"copilot"` via BD tool. Expect `other-whos-since-last: ["joyride-test"]` and a note mentioning the interleaved evaluator.

**Step 4 — Reverse direction**: Evaluate via Joyride API again. Expect `otherWhosSinceLast: ["copilot"]`.

**Known limitation**: Human UI evaluations (editor eval commands, load file) do not participate in `who` tracking. This is a Calva-side issue, not a BD issue.

### 3. Output Log with Who-Filtering

The `clojure_repl_output_log` tool supports filtering by evaluator identity.

**Parameters**:
- `sinceLine` (optional): Return entries after this line number
- `includeWho` (optional): Array of `who` slugs to include (whitelist)
- `excludeWho` (optional): Array of `who` slugs to exclude (blacklist)

**Test protocol**:

1. **Unfiltered**: Call with just `sinceLine` — verify all entries returned with `who` field on each
2. **Include filter**: `includeWho: ["copilot"]` — verify only BD evals returned
3. **Include different who**: `includeWho: ["joyride-test"]` — verify only Joyride API evals returned
4. **Exclude filter**: `excludeWho: ["copilot"]` — verify BD evals excluded, everything else included

**Output entry shape**:
```json
{"category": "evaluationResults", "text": "6", "who": "copilot", "line": 42}
```

Categories: `"evaluationResults"`, `"otherOutput"`. The `who` field is `null` for UI-triggered evaluations and description output messages.

### 4. Symbol Info and ClojureDocs

**Clojure Symbol Info**: Look up REPL-connected documentation.
- Test with core functions (`map`, `reduce`)
- Verify: docstring, arglists, source file location

**ClojureDocs Info**: Query community examples.
- Test with core functions
- Verify: examples, notes, see-also references

### 5. Bracket Balancer

Test with three code scenarios:
- **Balanced**: `(defn add [a b] (+ a b))` — expect success
- **Unbalanced**: `(+ 1 2` — expect `valid?: false` with `balanced-code`
- **Malformed**: `({]][((broken))` — expect `valid?: false` with `parinfer-error`

All structural editing tools also validate bracket balance before applying changes.

### 6. REPL Session Listing

**List Sessions**: Verify available REPL sessions.
- Expect at least one session with key `"clj"` for JVM Clojure
- Response includes session type and connection info

### 7. Human Intelligence

**Human Intelligence tool**: Request human input via VS Code input box.
- Verify bidirectional communication works
- Verify the response contains the human's input

## Diagnosing Calva vs Backseat Driver Issues

When a BD tool behaves unexpectedly, use Joyride to call the Calva API directly — bypassing BD — to isolate whether the issue is in Calva or BD.

**Direct API evaluation via Joyride**:
```clojure
(require '["ext://betterthantomorrow.calva$v1" :as calva])
(require '[promesa.core :as p])

(p/let [r (calva/repl.evaluate "(+ 1 1)"
            #js {:who "joyride-test" :ns "user" :sessionKey "clj"})]
  {:result (.-result r)
   :who (.-who r)
   :others (js->clj (.-otherWhosSinceLast r))})
```

**Output log subscription via Joyride** (for checking `who` on output messages):
```clojure
(require '["ext://betterthantomorrow.calva$v1" :as calva])

(def !disposable (atom nil))
(reset! !disposable
  (calva/repl.onOutputLogged
    (fn [entry]
      (js/console.log "Output:" (pr-str {:who (.-who entry)
                                          :category (.-category entry)
                                          :text (.-text entry)})))))
;; Trigger evals, then check Joyride Output terminal
;; Clean up: (.dispose @!disposable)
```

**Decision tree**:
- Same behavior via Joyride direct API → **Calva issue**
- Different behavior (BD breaks, Joyride works) → **BD issue**
- Neither works → **nREPL/environment issue**

## Legacy Calva Compatibility

When testing with older Calva versions that lack the new `evaluate()` API:

- `clojure_evaluate_code`: Should still work but returns informational notes about missing API features. The `who` and `other-whos-since-last` fields will be absent.
- `clojure_repl_output_log`: Returns `null` when the output log API is unavailable.

Verify graceful degradation — tools should never crash, just provide informative feedback about reduced capabilities.

## Test File Management

- Create dedicated test files: `src/mini/tool_smoke_test_<n>.clj`
- Clean up test files after validation
- Never modify production source files for testing

## Success Criteria

- All tool IDs resolve correctly
- `who` parameter accepted and echoed in responses
- `other-whos-since-last` correctly tracks API-to-API interleaving
- Output log filtering returns correct subsets
- Structural editing maintains valid Clojure syntax
- Error messages are clear and actionable
- Bracket balance validation catches malformed code
- Legacy Calva degrades gracefully
