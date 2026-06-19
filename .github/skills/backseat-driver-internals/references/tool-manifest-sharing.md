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

In `src/calva_backseat_driver/mcp/requests.cljs`:

- `tool-manifest` — reads `packageJSON.contributes.languageModelTools` at runtime and finds the entry by name.
- `tool-description` / `param-description` — extract `modelDescription` and per-property `description` values.
- `tool-listing` — builds the MCP tool definition by merging static property shapes (defined in Clojure) with the descriptions pulled from the manifest.

Result: MCP tools stay in sync automatically. No duplicated schemas.

## Adding or Extending a Tool

1. Add or modify the entry in `package.json` → `contributes.languageModelTools`.
2. Add the corresponding handler in `mcp/requests.cljs` (e.g., `handle-list-runtimes`).
3. Wire the handler into the `tools/call` dispatch.
4. Both LM API and MCP surfaces receive the change on next activation.

## Naming & Description Guidelines

- Use the same `name` for both surfaces.
- `modelDescription` should be concise and action-oriented (what the tool does).
- Every property should have a `description` — these become the parameter docs in both LM prompts and MCP tool listings.
- For optional parameters that change behavior significantly (e.g., `targetRuntimeId`), document the fallback behavior clearly.

## When the Pattern Does Not Apply

- Skills (MCP resources + injected instructions) are declared separately under `contributes.chatSkills`.
- Internal actions/effects (Ex framework) are not exposed as tools.

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
- `mcp/requests.cljs` — `tool-manifest`, `tool-listing`, handler dispatch
- `app.fxs` — LM tool registration during activation

## References

- Calva API docs (runtime targeting fields and `listSessionsAndRuntimes`/`evaluate` with `targetRuntimeId`)
- `backseat-driver-internals/SKILL.md` (MCP server lifecycle, tool registration)