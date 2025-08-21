# Editing CLojure files

Even as an interactive programmer, now and then you do edit files. The most important things:

1. **Before any editing: First always read the whole file you are about to edit.** Instead of chunked reading, **read it in one go**.
2. Always check with the problem tool what the current linting state is
3. Check with the problem tool after each edit
4. Always be alert to when the bracket balance is off, and see [When the bracket balance is off](#when-the-bracket-balance-is-off) if it is.

The specific process look different depending on if you are creating files, adding functions, or editing existing functions.

## Creating Clojure files

Use the `create_file` tool to create files with empty content `""`.

### Clojure Namespace and Filename Convention:

**Important**: In Clojure,  namespace names use kebab-case while filenames use snake_case. For example:
- Namespace: `my.project.multi-word-namespace`
- Filename: `my/project/multi_word_namespace.clj(s|c)`

Always convert dashes in namespace names to underscores in the corresponding filename.

### Create empty files, then add content

For you to create files and add content safely/predictably, follow this process:

1. **Always create empty files first** - Use `create_file` with empty content `""`
2. Read the content of the file created (default content may have been added)
3. **Use structural editing tools** to edit the file

## Important about the Structural Editing Tools

Use the structural editing tools for Clojure forms/s-expressions.

* Use `insert_top_level_form` to add new forms to a file
* Use `replace_top_level_form` to modify existing forms
* Make use of the diagnostics info returned
* **Rich Comment Forms/RCF**: Calva treats forms immediately enclosed within `(comment <like this>)` as top level forms, making them valid targets for the top_level_form editing tools.

The structural editing tools attempt to automatically balance brackets before applying edits.

### `replace_top_level_form`
* Target top level forms by their starting, 1-based, line number
* **Important**: This tool is **only** for replacing top level Clojure one form/s-expression with another form/s-expression
  * For editing line comments (`; ...` which are not structural), use your built in edit tools

### `insert_top_level_form`
* Always target the top level of the code
* Use 1-based, line numbers
* **Important**: This tool is **only** for inserting Clojure forms/s-expressions, one at a time
  * For inserting line comments (`; ...` which are not structural), use your built in edit tool

## Structural editing process

Follow this process for making safe and working updates:

1. **Always edit whole top level forms** (typically `def` and `defn` and such) using the structural editing tools (`replace_top_level_form` or `insert_top_level_form`)
2. Always check with the problem tool what the current linting state is
3. Plan your edits, breaking it up in one edit per complete top level form you are editing or inserting
4. With your list of edits, work backwards from the edit furthest down/nearest the end the file to the edit nearest the start of the file
5. For each top level form in your edit plan (starting at the bottom of the file):
   1. Edit the file using the appropriate structural editing tool
   2. Check with the problem tool that no new problems are reported

# When the bracket balance is off

When you have a situation where e.g. the problem tool or Clojure compiler complains about missing brackets or anything suggesting the bracket balance is off:
* Instead of going ahead trying to fix it, **use the tool for requesting human input to ask for guidance/help.**
