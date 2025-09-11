**Evaluate Clojure Code** using Calva's REPL connection. Enables AI Interactive Programming. Use this to make sure your edits work as you advertise, before applying them. The REPL holds the truth!

## Code Indentation Before REPL Evaluation

**Always ensure Clojure code is properly indented before evaluating it in the REPL.** Proper indentation is essential for the bracket balancer and other tooling to work correctly.

### Essential Rule:
- **Indent code properly** - Align nested forms with consistent indentation before evaluation

### Pattern:
```clojure
;; ❌ Poor indentation - will cause issues
(defn my-function [x]
  {:foo 1
  :bar 2}) ; `:bar` is not properly “inside” the enclosing map

;; ✅ Proper indentation - ready for evaluation
(defn my-function [x]
  {:foo 1
   :bar 2}) ; `:bar` is properly “inside” the enclosing map
```

## Evaluating ClojureScript code?
* remember to use the `"cljs"` `replSessionKey`
* The user namespace is probably/often `cljs.user`
* `js-keys` is a handy function to explore JS objects with
