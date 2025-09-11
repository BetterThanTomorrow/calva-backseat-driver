# Editing Clojure files

We refer to all files of reasonably Clojure-ish type as **Clojure files**, be it Clojure, ClojureScript, Babashka, nbb, Joyride, Jank, you-name-it.

Even as an interactive programmer, now and then you do edit files.

* The structural editing tools attempt to automatically balance brackets before applying edits.
* The tools return post-edit diagnostics/linting info. Make use of it!

The specific processes look different depending on if you are creating files, appending forms, inserting forms, or editing existing forms.

## Code Indentation Before REPL Evaluation

**Always ensure that Clojure code is properly indented.** Proper indentation is essential for the structural editing tools to work correctly.

### Essential Rule:
- **Indent code properly** - Align nested forms with consistent indentation before evaluation

### Pattern:
```clojure
;; ❌ Poor indentation - will cause issues
(defn my-function [x]
  {:foo 1
  :bar 2}) ; `:bar` is not properly “inside” the enclosing map

;; ✅ Proper indentation - ready for use with the structural editing tools
(defn my-function [x]
  {:foo 1
   :bar 2}) ; `:bar` is properly “inside” the enclosing map
```

## When the bracket balance is off

When you have a situation where e.g. the problem tool or Clojure compiler complains about missing brackets or anything suggesting the bracket balance is off (probably because you have used non-structural editing tools):
* Instead of going ahead trying to fix it, **use the tool for requesting human input to ask for guidance/help.**

## About top level forms

The structural editing tools appends/inserts/replaces top level forms/s-expressions, such as the `ns` form, `def`, `defn`, `def...` forms, and many Rich Comment Forms. Top level form is Calva nomenclature for referring to forms at the root/top level of the Clojure code structure.

**Rich Comment Forms/RCF**: Calva treats forms immediately enclosed within `(comment <like this>)` as top level forms, making them valid targets for the top_level_form editing tools. (The `(comment ... )` form itself is also a top level form, as far as Calva and the tools are concerned.)

## Structural editing process

Follow this process for making safe and working updates:

1. **Always edit whole top level forms** (typically `ns`, `def` and `defn` and such) using the structural editing tools (**Replace Top Level Form** or **Insert Top Level Form** )
2. Always check with the problem tool what the current linting state is
3. If you are doing multiple edits to a file: **Plan your edits**, breaking it up in one edit per complete top level form you are editing or inserting. Use the todo list.
   - **Work from bottom to top of the file** - Because editing tools use line numbers, and edits can shift line numbers of content below them, always apply your edits starting from the lowest line number (bottom of file) and work upward. This keeps your planned line numbers accurate.

     Example: If you plan to edit lines 10, 20, and 30, edit them in this order: line 30 → line 20 → line 10.

     1. For each top level form in your edit plan (starting at the bottom of the file):
        * Edit the file using the appropriate structural editing tool

    Remember that in Clojure, functions need to be defined before they are called, so during the edits this way, linter complaints about symbols not found are to be expected. When the edit plan is carried out, you should have no new such warnings.

