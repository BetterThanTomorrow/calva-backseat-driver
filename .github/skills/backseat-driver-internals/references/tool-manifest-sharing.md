# Tool Manifest Sharing (LM API + MCP)

## One Manifest, Two Surfaces

All Language Model tools are declared once in `package.json` under:

```json
"contributes": {
  "languageModelTools": [
    {
      "name": "clojure_evaluate_code",
      "modelDescription": "Evaluates Clojure code...",
      "inputSchema": {
        "type": "object",
        "properties": {
          "code": { "type": "string", "description": "..." },
          ...
        },
        "required": ["code", "namespace", "replSessionKey", "who"]
      }
    }
  ]
}
```

This single entry feeds **both**:

- VS Code Language Model API (Copilot, etc.)
- MCP `tools/list` response

## How MCP Reuses the Manifest

The manifest is read once at activation, as data, into the state under `:extension/manifest`. `mcp/requests.cljs` hands it to `vscode-mcp.requests/handle-manifest-request`, which answers `tools/list` with each tool's `name`, `modelDescription` and `inputSchema`, gated by the `when` clauses and the settings.

Result: MCP tools stay in sync automatically. No duplicated schemas.

## Adding or Extending a Tool

1. Add or modify the entry in `package.json` → `contributes.languageModelTools`.
2. Add a row to the tool table, `tools/tools` in `tools.cljs`, with the tool's function, `(f perform-effect! tool-call)`.
3. Add its confirmation function in `tools/confirmation.cljs`.
4. Run the unit tests: one checks the tool table against the manifest.
5. Both LM API and MCP surfaces receive the change on next activation. Both dispatch `[:tool/ax.call {:call/tool … :call/arguments …}]`.

## Naming & Description Guidelines

- Use the same `name` for both surfaces.
- `modelDescription` should be concise and action-oriented (what the tool does).
- Every property should have a `description` — these become the parameter docs in both LM prompts and MCP tool listings.
- For optional parameters that change behavior significantly (e.g., `targetRuntimeId`), document the fallback behavior clearly.

## When the Pattern Does Not Apply

- Skills (MCP resources + injected instructions) are declared separately under `contributes.chatSkills`.
- The loop's actions and effects are not exposed as tools.

## Runtime targeting example

Shadow-cljs runtime parameters follow the same manifest-first pattern. Example `package.json` property entries:

```json
"includeAllRuntimes": {
  "type": "boolean",
  "description": "Shadow-cljs only. List every connected runtime per build instead of just the count and most-recently-active one."
},
"targetRuntimeId": {
  "type": "number",
  "description": "Shadow-cljs only. Evaluate on a specific runtime (browser tab / Node process) without changing the editor's connected runtime. Get IDs from `clojure_list_sessions`."
}
```

Handlers read these optional parameters from MCP/LM tool arguments. The response shape for `clojure_list_sessions` is shaped in `integrations/calva/session_runtimes.cljs` — a projection layer that complements manifest-only params: compact `runtimeCount`/`mostRecentRuntime` by default, full `runtimes[]` when `includeAllRuntimes` is true.

## Related Code

- `package.json` — the single source of truth
- `tools.cljs` — the tool table, `call-tool+`, the Language Model API's entry point
- `tools/axs.cljs` — `:tool/ax.call`, which checks a call against the manifest and the settings
- `mcp/requests.cljs` — `tools/call`, and the rest through `vscode-mcp.requests`
- `app.fxs` — LM tool registration during activation

## References

- Calva API docs (runtime targeting fields and `listSessionsAndRuntimes`/`evaluate` with `targetRuntimeId`)
- `backseat-driver-internals/SKILL.md` (MCP server lifecycle, tool registration)