**Evaluate Clojure Code** using Calva's REPL connection. Enables AI Interactive Programming. Use this to make sure your edits work as you advertise, before applying them. The REPL holds the truth!

## Identifying Yourself

The `who` parameter is **required**. Provide a short, stable slug that identifies you as the evaluator (e.g. `"copilot"`, `"cursor-agent"`, `"aider"`). This value:
- Appears in the REPL output log, letting the user see which agent triggered which evaluation
- Is returned in the result so you can confirm your identity
- Must NOT be `"ui"` or `"api"` (reserved by Calva — will throw an error)
- Use the same slug consistently across your session

## Tracking Other Evaluators

The result may include `otherWhosSinceLast` — a list of other evaluator identifiers that have evaluated code since your previous evaluation. This tells you whether the user or another agent has been active. The list reflects only new activity since your last evaluation, so each call gives you a fresh view.

## Providing Context

The optional `description` parameter explains *why* you are evaluating code. It appears as green context text in the REPL output before your code, giving the user insight into your reasoning. Good examples:
- `"Testing add-numbers after refactoring"`
- `"Checking current state of app-db"`
- `"Verifying namespace loads correctly"`

## Code Indentation Before REPL Evaluation

**Always ensure Clojure code is properly indented before evaluating it in the REPL.** Proper indentation is essential for the bracket balancer and other tooling to work correctly.

### Essential Rule:
- **Indent code properly** - Align nested forms with consistent indentation before evaluation

### Pattern:
```clojure
;; ❌ Poor indentation - will cause issues
(defn my-function [x]
  {:foo 1
  :bar 2}) ; `:bar` is not properly "inside" the enclosing map

;; ✅ Proper indentation - ready for evaluation
(defn my-function [x]
  {:foo 1
   :bar 2}) ; `:bar` is properly "inside" the enclosing map
```

## Evaluating ClojureScript code?
* remember to use the `"cljs"` `replSessionKey`
* The user namespace is probably/often `cljs.user`
* `js-keys` is a handy function to explore JS objects with
