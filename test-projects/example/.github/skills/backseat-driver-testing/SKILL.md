---
name: backseat-driver-testing
description: 'Testing strategies for Calva Backseat Driver MCP tools. Use when: Testing Backseat Driver, validating tool updates, testing structural editing workflows, verifying REPL evaluation with who-tracking, testing output log filtering, testing load-file tool, smoke testing after dep bumps, or debugging tool behavior. Covers all Backseat Driver tool categories: structural editing, REPL eval, load file, symbol info, bracket balancing, and output log.'
---

# Backseat Driver Testing Skill

Strategies and patterns for testing the Backseat Driver MCP server toolset. Each section is self-contained — run one section, a few, or all of them depending on what needs testing.

## When to Use This Skill

- After bumping Backseat Driver dependencies and rebuilding
- When testing new or modified tool parameters
- When validating structural editing, REPL evaluation, or output log behavior
- When debugging who-tracking or cross-evaluator awareness

## Before You Start

Call `clojure_list_sessions` immediately. Both a `clj` (JVM Clojure) and a `bb` (Babashka) session are expected. If either is missing, use `#askQuestions` to tell the user which REPL(s) are absent and ask them to start the REPL(s) before continuing. Do not proceed with REPL-dependent tests without these sessions unless the user responds that it is okay to continue without them.

## Test Scope

Match the user's request to the relevant section(s). When asked to "run a full test" or "smoke test the extension," work through all sections. When asked to test something specific ("test the bracket balancer," "check who-tracking"), go directly to that section.

**Section index** — each section lists its own prerequisites:
- **REPL Session Listing** — no prerequisites
- **REPL Evaluation** — requires session listing first
- **Load File** — requires session listing first
- **Structural Editing** — requires session listing first
- **Output Log Queries** — requires some prior REPL evaluations to have populated the log
- **Symbol Info and ClojureDocs** — requires session listing first
- **Bracket Balancer** — no prerequisites, no REPL needed

### Delegation via `bd-tester`

The `bd-tester` agent is a purpose-built subagent for Backseat Driver testing. It loads all relevant skills, executes a test task, and returns structured results. Use it for:

- **Parallel who-tracking**: Launch 3+ `bd-tester` instances simultaneously with distinct `who` slugs
- **Full-suite parallelization**: After running REPL Session Listing yourself, delegate independent sections to parallel `bd-tester` subagents. Sections with no cross-dependencies can run simultaneously:
  - Group A (independent): Bracket Balancer, Symbol Info / ClojureDocs
  - Group B (needs evals first): REPL Evaluation, Load File, then Output Log Queries
  - Group C (needs evals first): Structural Editing

When delegating, pass the specific section name, any prerequisite results (e.g., available session keys), and the success criteria from this skill. The `bd-tester` agent handles skill loading and reporting internally.

## Test Conventions

Use `"smoke-tester"` as the `who` slug for all Backseat Driver evaluations during testing. Use `"joyride-test"` for Joyride-side evaluations in cross-evaluator tests.

Clean up test artifacts (created files) at the end of every test run.

## REPL Session Listing

Prerequisites: none. Run this first when testing any REPL-dependent section.

**List Sessions**: Call `clojure_list_sessions` with no parameters.

**Response fields to verify**:
- `replSessionKey`: Session identifier (e.g., `"clj"`, `"bb"`)
- `projectRoot`: Workspace root URI
- `lastActivity`: Timestamp of last evaluation
- `globs`: File patterns the session handles
- `currentRoutedTarget`: `true` for the session serving the user's active file

**Validation**:
- JVM Clojure REPL connected → expect at least one session with key `"clj"`
- Babashka REPL connected → expect a session with key `"bb"`
- Exactly one session has `currentRoutedTarget: true`

## REPL Evaluation with Who-Tracking

Prerequisites: REPL Session Listing (to discover available sessions).

The `clojure_evaluate_code` tool supports evaluator identity tracking.

**Required parameters**:
- `code`: Clojure code to evaluate
- `who`: Evaluator identity slug (e.g., `"smoke-tester"`) — **required**
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

**Test steps**:

1. **Basic eval**: Evaluate `(+ 21 21)` in `user` namespace via `clj` session as `"smoke-tester"`. Verify `result` is `"42"` and `who` echoes back.

2. **Description parameter**: Evaluate with a `description` value. Then query the output log to verify the description appears as an `"otherOutput"` entry tagged with the same `who`.

3. **Babashka eval** (when bb REPL connected): Evaluate `(+ 1 1)` via `bb` session. Verify result and session key.

#### Basic Who-Tracking (No Joyride)

The simplest who-tracking verification — uses only Backseat Driver's eval tool and subagents, no Joyride needed.

**Step 1 — Baseline**: Evaluate as `"smoke-tester"` via Backseat Driver tool. Note the `other-whos-since-last` (likely empty).

**Step 2 — Parallel subagent evals**: Launch 2+ `bd-tester` subagents in parallel, each with a distinct `who` (e.g., `"who-test-a"`, `"who-test-b"`). Each subagent evaluates a simple expression via the REPL.

**Step 3 — Check cross-tracking**: After subagents complete, evaluate again as `"smoke-tester"`. Verify `other-whos-since-last` contains the subagent `who` handles.

**Step 4 — Output log confirmation**: Query the output log to verify all `who` handles appear with correct attribution:
```edn
[:find ?who (count ?e)
 :where [?e :output/who ?who]
        [?e :output/category "evaluationResults"]]
```
Expect `"smoke-tester"`, `"who-test-a"`, and `"who-test-b"` all present.

#### Who-Tracking Test Protocol

Test pure API-to-API tracking (UI eval tracking is a known Calva limitation):

**Step 1 — Baseline**: Evaluate as `"smoke-tester"` via Backseat Driver tool. Expect empty `other-whos-since-last`.

**Step 2 — Interleave via Joyride**: Use Joyride to evaluate via the Calva API with a different `who`:
```clojure
(require '["ext://betterthantomorrow.calva$v1" :as calva])
(require '[promesa.core :as p])

(p/let [r (calva/repl.evaluate "(+ 1 1)"
            #js {:who "joyride-test" :ns "user" :sessionKey "clj"})]
  {:who (.-who r) :others (js->clj (.-otherWhosSinceLast r))})
```

**Step 3 — Verify cross-tracking**: Evaluate again as `"smoke-tester"` via Backseat Driver tool. Expect `other-whos-since-last: ["joyride-test"]` and a note mentioning the interleaved evaluator.

**Step 4 — Reverse direction**: Evaluate via Joyride API again. Expect `otherWhosSinceLast: ["smoke-tester"]`.

**Known limitation**: Human UI evaluations (editor eval commands, load file) do not participate in `who` tracking. This is a Calva-side issue, not a Backseat Driver issue.

#### Parallel Who-Tracking via Subagents

Launch 3+ `bd-tester` subagents in parallel, each with a distinct `who` slug, all evaluating via the REPL simultaneously. This tests concurrent who-tracking under real contention.

**Procedure**: Launch `bd-tester` subagents in parallel (not sequentially). Pass each a task like:

> Evaluate 3-5 expressions in the `user` namespace via the `clj` session using `who` = `"parallel-<x>"`. For each evaluation, record the `other-whos-since-last` array from the response. Return your `who` slug and all recorded `other-whos-since-last` arrays, with brief reasoning about how the arrays changed with each eval.

Use distinct slugs: `"parallel-a"`, `"parallel-b"`, `"parallel-c"`, etc.


**Verification** (after all subagents complete): Query the output log:
```edn
[:find ?who (count ?e)
 :where [?e :output/who ?who]
        [?e :output/category "evaluationResults"]]
```
Verify all three slugs appear with expected counts. At least some subagents should have reported non-empty `other-whos-since-last` arrays — confirming the REPL saw concurrent evaluators.

## Load File

Prerequisites: REPL Session Listing (to discover available sessions).

The `clojure_load_file` tool loads and evaluates an entire Clojure file through Calva's connected REPL. It requires Calva >= 2.0.576 and is version-gated — the tool only appears when the version check passes.

**Required parameters**:
- `filePath`: Absolute or workspace-relative path to the Clojure file
- `replSessionKey`: REPL session to load the file in (e.g., `"clj"`, `"bb"`)
- `who`: Evaluator identity slug (e.g., `"smoke-tester"`)

**Response fields to verify**:
- `result`: String result of the last evaluated form in the file
- `error`: Error message (on failure)

**Test steps**:

1. **Load file in clj session**: Call `clojure_load_file` with a file containing a namespace and definitions (e.g., a file whose last form evaluates to a known value). Provide `replSessionKey: "clj"`. Verify `result` matches the expected last-form value.

2. **Load file in bb session** (when bb REPL connected): Call `clojure_load_file` with a simple file via `replSessionKey: "bb"`. Verify `result` is returned correctly.

3. **Post-load usability**: After loading, evaluate a function from the loaded namespace via `clojure_evaluate_code`. Verify the function is callable and returns the expected result.

4. **Missing file**: Call `clojure_load_file` with a non-existent file path. Verify the response contains an `error` field with a clear message about the missing file.

5. **Who attribution**: After a load-file call, check `other-whos-since-last` on the next `clojure_evaluate_code` call. Load-file participates in who-tracking via the `who` parameter — verify that load-file does **not** leak a `"ui"` evaluator into `other-whos-since-last`.

## Structural Editing Tools

Prerequisites: REPL Session Listing (for the REPL reload step).

Test the complete file editing lifecycle:

**Create Clojure File** → **Append Code** → **Insert Top Level Form** → **Replace Top Level Form** → **Delete Form** → **REPL Reload** → **Cleanup**

**Workflow**:
1. Create a new file with namespace and one simple function
2. Append multiple functions to demonstrate accumulation
3. Insert a data definition before an existing function to test positioning
4. Replace an existing function to test modification
5. Delete a form using `replace_top_level_form` with empty `newForm`
6. Fix definition order if inserts created forward references (definitions must precede their call sites in Clojure)
7. Load the namespace in the REPL and evaluate functions with test data
8. Clean up: delete the test file after verification

**Validation points**:
- After creation: File exists with proper namespace form and snake_case filename
- After append: New forms appear at end, diagnostics show all definitions
- After insert: New form appears before target, line numbers shift correctly
- After replace: Updated form replaces old one, preserving surrounding code
- After delete: Form removed, blank lines may remain
- After REPL reload: All functions evaluate correctly — no compilation errors

**Definition order**: Inserting forms can create forward references. If the REPL reload fails with "Unable to resolve symbol," reorder definitions so every symbol is defined before its first use. This is expected behavior, not a tool bug.

**Error scenario testing**:
- Provide wrong `targetLineText` — verify clear error message with file context
- Target line beyond fuzzy window — verify failure with helpful context
- Test on tiny files (3-4 lines) smaller than context window — verify clean display
- Test with similar/duplicate code patterns — verify correct line matching

## Output Log Queries

Prerequisites: some REPL evaluations must have occurred to populate the log. If testing this section in isolation, run a few evaluations first (e.g., the basic eval from REPL Evaluation).

The `clojure_repl_output_log` tool takes a `query` parameter (a Datalog query as an EDN string) and an optional `inputs` parameter (an EDN vector of values for parameterized `:in` clauses).

The output log is a DataScript database where each message is an entity with these attributes:

- `:output/line` — monotonic integer (message sequence number, useful as a cursor)
- `:output/category` — `"evaluationResults"`, `"evaluatedCode"`, `"evaluationOutput"`, `"evaluationErrorOutput"`, `"otherOutput"`, or `"otherErrorOutput"`
- `:output/text` — the message content
- `:output/who` — evaluator slug (e.g. `"smoke-tester"`, `"joyride-test"`, `"ui"`) or absent
- `:output/timestamp` — milliseconds since epoch
- `:output/ns` — namespace (present on `evaluatedCode` messages)
- `:output/repl-session-key` — REPL session key (present on `evaluatedCode` messages)

Use `pull` to select only the attributes you need — this protects the context window.

**Test protocol**:

1. **Category coverage** — before querying, generate entries for all interesting categories by evaluating:
   - A normal expression (produces `evaluatedCode` + `evaluationResults`)
   - A `println` call (produces `evaluationOutput`)
   - An expression that errors, e.g. `(/ 1 0)` (produces `evaluationErrorOutput`)
   - An evaluation with a `description` (produces `otherOutput`)

   Then count entries per category:
   ```edn
   [:find ?cat (count ?e) :where [?e :output/category ?cat]]
   ```
   Verify at least these categories are present: `evaluatedCode`, `evaluationResults`, `evaluationOutput`, `evaluationErrorOutput`, `otherOutput`. The `otherErrorOutput` category may not appear in a clean session — that's fine.

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

6. **Namespace and session key** — evaluate across different sessions and namespaces, then verify the output log records them correctly.

   **Setup** — evaluate in at least two distinct combinations:
   - Evaluate `(+ 1 1)` in `user` namespace via `clj` session
   - Evaluate `(+ 2 2)` in `user` namespace via `bb` session (when connected)
   - Evaluate `(ns mini.playground) :ok` in `mini.playground` namespace via `clj` session

   **Query** — pull `:output/ns` and `:output/repl-session-key` from `evaluatedCode` entries:
   ```edn
   [:find [(pull ?e [:output/line :output/ns :output/repl-session-key :output/text]) ...]
    :where [?e :output/category "evaluatedCode"]]
   ```

   **Verify**:
   - Each entry's `:output/ns` matches the namespace that evaluation was performed in
   - Each entry's `:output/repl-session-key` matches the session key used (`"clj"` or `"bb"`)
   - Entries from different sessions have different `:output/repl-session-key` values
   - Entries from different namespaces have different `:output/ns` values

7. **Parameterized queries** — use the `inputs` parameter with `:in` clauses:
   ```edn
   [:find [(pull ?e [:output/line :output/category :output/text]) ...]
    :in $ ?who ?cat
    :where [?e :output/who ?who] [?e :output/category ?cat]]
   ```
   With `inputs`: `["smoke-tester" "evaluationResults"]`

   Verify results match the equivalent inline-value query from step 3.

8. **Image capping (`maxImages`)** — the output log tool defaults to `maxImages: 0`, replacing all `data:image/...` URLs with `<<image-N-capped>>` markers and returning no image content. The `clojure_evaluate_code` tool defaults to `maxImages: 10`.

   **Setup** — evaluate a string containing a `data:image/png;base64,...` URL (use a real but small PNG, at least 10×10 pixels to avoid API rejection of degenerate images):
   ```clojure
   (str "test-image: data:image/png;base64,<valid-base64>")
   ```

   **⚠️ Pitfall**: Do not use 1×1 pixel images — the model API rejects them with a 400 `"Could not process image"` error, which crashes the entire request. Use at least 10×10 pixel images for testing.

   **Test steps**:

   a. **Default (no maxImages)** — query the log for the image-bearing entry. Verify:
      - Text contains `<<image-1-capped>>` marker (not the raw data URI)
      - No image content is returned alongside the text

   b. **Explicit maxImages: 0** — same query with `maxImages: 0`. Verify identical capping behavior.

   c. **maxImages: 1** — same query with `maxImages: 1`. Verify:
      - Text contains `<<image-1>>` (not `capped`)
      - Image content is returned alongside the text

   d. **Partial capping** — if the log contains an entry with multiple images, query with `maxImages` lower than the image count. Verify:
      - First N images use `<<image-N>>` markers and are returned
      - Remaining images use `<<image-N-capped>>` markers and are not returned

   e. **Plain results unaffected** — query a non-image entry. Verify text passes through unmodified with no markers.

   f. **evaluatedCode entries** — verify that source code containing data URIs also has images capped in the text.

## Symbol Info and ClojureDocs

Prerequisites: REPL Session Listing.

**Clojure Symbol Info**: Look up REPL-connected documentation.
- Test with core functions (`map`, `reduce`)
- Verify: docstring, arglists, source file location

**ClojureDocs Info**: Query community examples.
- Test with core functions
- Verify: examples, notes, see-also references

## Bracket Balancer

Prerequisites: none. This tool operates on text input — no REPL connection needed.

Test with three code scenarios:

- **Balanced**: `(defn add [a b] (+ a b))`
  Expect: `{"note":"The text was already properly balanced."}`

- **Unbalanced**: `(+ 1 2`
  Expect: `{"balanced-text":"(+ 1 2)","note":"..."}` — the tool auto-balances and returns the fixed text

- **Malformed**: `({]][((broken))`
  Expect: `{"success":false,"error":{"name":"unmatched-close-paren",...}}` — Parinfer cannot auto-fix contradictory brackets

All structural editing tools also validate bracket balance before applying changes.

## Diagnosing Calva vs Backseat Driver Issues

When a Backseat Driver tool behaves unexpectedly, use Joyride to call the Calva API directly — bypassing Backseat Driver — to isolate whether the issue is in Calva or Backseat Driver.

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
- Different behavior (Backseat Driver breaks, Joyride works) → **Backseat Driver issue**
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

When reporting results, cover only the sections that were tested.

**Session Listing**: Sessions discovered with expected keys and `currentRoutedTarget` field.

**REPL Evaluation**: `who` echoed, result correct, `description` appears in output log. Babashka eval returns correct session key (when bb REPL connected).

**Who-Tracking**: `other-whos-since-last` correctly tracks API-to-API interleaving. Parallel subagent contention produces non-empty tracking arrays.

**Structural Editing**: Full create → append → insert → replace → delete → reload lifecycle completes. Post-edit diagnostics are accurate. REPL reload succeeds after definition order is correct.

**Output Log**: Datalog queries return correct results across all query styles (pull, aggregation, predicates, parameterized `:in` clauses). Image capping: default `maxImages: 0` replaces data URIs with `<<image-N-capped>>` markers; explicit `maxImages > 0` returns images with `<<image-N>>` markers; plain results pass through unmodified.

**Symbol Info / ClojureDocs**: Docstrings, arglists, examples, and see-alsos returned.

**Bracket Balancer**: Balanced code recognized, unbalanced code auto-fixed, malformed code produces clear error.

**Load File**: File loads successfully in clj and bb sessions. Default routing works without explicit session key. Post-load functions are callable. Missing file produces clear error. Who attribution behavior is documented.

**Legacy Calva**: Graceful degradation — informative feedback, no crashes.

**Skills Opt-Out**: Skills appear/disappear based on settings.
