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

### 3. Output Log Queries

The `clojure_repl_output_log` tool takes a single `query` parameter — a Datalog query as an EDN string. The output log is a DataScript database where each message is an entity with these attributes:

- `:output/line` — monotonic integer (message sequence number, useful as a cursor)
- `:output/category` — `"evaluationResults"`, `"clojureCode"`, `"evaluationOutput"`, `"evaluationErrorOutput"`, `"otherOutput"`, or `"otherErrorOutput"`
- `:output/text` — the message content
- `:output/who` — evaluator slug (e.g. `"copilot"`, `"joyride-test"`, `"ui"`) or absent
- `:output/timestamp` — milliseconds since epoch

Use `pull` to select only the attributes you need — this protects the context window.

**Test protocol**:

1. **Overview** — count entries per category:
   ```edn
   [:find ?cat (count ?e) :where [?e :output/category ?cat]]
   ```
   Verify multiple categories present.

2. **Recent entries** — find the max line, then fetch entries after a threshold:
   ```edn
   [:find (max ?l) . :where [?e :output/line ?l]]
   ```
   ```edn
   [:find [(pull ?e [:output/line :output/category :output/text :output/who]) ...]
    :where [?e :output/line ?l] [(> ?l 20)]]
   ```
   Verify entries returned with expected attributes.

3. **Who filtering (include)** — inline the who value:
   ```edn
   [:find [(pull ?e [:output/line :output/text :output/who]) ...]
    :where [?e :output/who "smoke-tester"]
           [?e :output/category "evaluationResults"]]
   ```
   Verify only entries from that evaluator are returned.

4. **Who filtering (exclude)** — use a predicate:
   ```edn
   [:find [(pull ?e [:output/line :output/text :output/who]) ...]
    :where [?e :output/category "evaluationResults"]
           [?e :output/who ?w] [(not= ?w "smoke-tester")]]
   ```
   Verify the excluded evaluator is absent, others present.

5. **Aggregation** — count entries per evaluator:
   ```edn
   [:find ?who (count ?e) :where [?e :output/who ?who]]
   ```
   Verify counts match expectations from prior evals.

**Known limitation**: Parameterized `:in` clauses beyond `$` (e.g. `:in $ ?who`) currently fail with "Too few inputs passed". Inline values directly in `:where` clauses instead. See tool-smith notes below.

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

## Skills Opt-Out

Backseat Driver provides skills conditionally. The `backseat-driver` and `editing-clojure-files` skills referenced in AGENTS.md are injected by BD into the agent's skills list only when enabled. They can be opted out, in which case they will not appear in the agent's `<skills>` section at all.

**VS Code settings**:
- `calva-backseat-driver.provideBdSkill` — controls the `backseat-driver` skill
- `calva-backseat-driver.provideEditSkill` — controls the `editing-clojure-files` skill

**Test protocol**:
1. Ask the agent to list all skills it sees in its context
2. When a setting is disabled, the corresponding skill should be absent from the agent's `<skills>` section
3. When a setting is enabled, the corresponding skill should appear as a loadable skill with a file path

This verifies that BD's conditional skill injection works correctly.

## Success Criteria

- All tool IDs resolve correctly
- `who` parameter accepted and echoed in responses
- `other-whos-since-last` correctly tracks API-to-API interleaving
- Output log Datalog queries return correct results (pull, aggregation, predicates)
- Structural editing maintains valid Clojure syntax
- Error messages are clear and actionable
- Bracket balance validation catches malformed code
- Legacy Calva degrades gracefully
- BD-provided skills appear/disappear based on opt-in/opt-out setting
