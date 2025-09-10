## Replacing a Top Level Form in Clojure files

This is the **Replace Top Level Form** tool. Use it to modify an existing top level form. To delete a top level form, replace it with an empty string.

**Important**: This tool is **only** for replacing Clojure forms/s-expressions, **one at a time**
* For editing line comments (`; ...` which are not structural), use the built in edit tool

Process:

1. Find the top level form you want to replace.
2. Read the first line of the form and use the text for the `targetLineText` param
3. Use the 1-based line number for the `line` param