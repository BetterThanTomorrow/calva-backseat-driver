# Editing Clojure files

We refer to all files of reasonably Clojure-ish type as **Clojure files**, be it Clojure, ClojureScript, Babashka, nbb, Joyride, Jank, you-name-it.

Even as an interactive programmer, now and then you do edit files. The most important things:

1. **Before any editing: First always read the whole file you are about to edit.** Instead of chunked reading, **read it in one go**.
2. Always check with the problem tool what the current linting state is
3. Check with the problem tool after each edit
4. Always be alert to when the bracket balance is off, and see [When the bracket balance is off](#when-the-bracket-balance-is-off) if it is.

Also:
* The structural editing tools attempt to automatically balance brackets before applying edits.
* The tools return post-edit diagnostics/linting ifno. Make use of it!

The specific process look different depending on if you are creating files, appending forms, inserting forms, or editing existing forms.

## About top level forms

The structural editing tools appends/inserts/replaces top level forms/s-expressions, such as the `ns` form, `def`, `defn`, `def...` forms, and many Rich Comment Forms. Top level form is Calva nomenclature for referring to forms at the root/top level of the Clojure code structure.

* **Rich Comment Forms/RCF**: Calva treats forms immediately enclosed within `(comment <like this>)` as top level forms, making them valid targets for the top_level_form editing tools. (The `(comment ... )` form itself is also a top level form, as far as Calva is concerned.)

## Important about the Structural Editing Tools

Use the structural editing tools for Clojure top level forms/s-expressions.

* Use the **Append Top Level Form** tool to append forms to the end of a Clojure file
* Use the **Create Clojure File** tool to create a Clojure file. Create the file with all the contents you know should go there at the time of creation.

## Structural editing process

Follow this process for making safe and working updates:

1. **Always edit whole top level forms** (typically `def` and `defn` and such) using the structural editing tools (`replace_top_level_form` or `insert_top_level_form`)
2. Always check with the problem tool what the current linting state is
3. Plan your edits, breaking it up in one edit per complete top level form you are editing or inserting
4. **Work from bottom to top of the file** - Because editing tools use line numbers, and edits can shift line numbers of content below them, always apply your edits starting from the lowest line number (bottom of file) and work upward. This keeps your planned line numbers accurate.

   Example: If you plan to edit lines 10, 20, and 30, edit them in this order: line 30 → line 20 → line 10.

   1. For each top level form in your edit plan (starting at the bottom of the file):
      1. Edit the file using the appropriate structural editing tool
      2. Check with the problem tool that no new problems are reported

# When the bracket balance is off

When you have a situation where e.g. the problem tool or Clojure compiler complains about missing brackets or anything suggesting the bracket balance is off:
* Instead of going ahead trying to fix it, **use the tool for requesting human input to ask for guidance/help.**
