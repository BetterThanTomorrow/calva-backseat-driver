## Inserting Top Level Forms in Clojure Files

This is the **Insert Top Level Form** tool. Use it to insert new forms to a file, the forms will be inserted before an existing, targeted, top level form.

**Important**: This tool is **only** for inserting Clojure forms/s-expressions
* For inserting line comments (`; ...` which are not structural), use your built in edit tool

Process:

1. Find the top level form you want to insert code before.
2. Read the first line of the form and use the text for the `targetLineText` param
3. Use the 1-based line number for the `line` param
