# clojure_load_file

Loads and evaluates an entire Clojure file through Calva's connected REPL.

## Requirements

- Connected REPL
- Calva >= 2.0.576 (the `path` argument to `calva.loadFile` is new in this version)
- For MCP: `enableMcpReplEvaluation` must be `true`

## Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `filePath` | string | yes | Absolute or workspace-relative path to the Clojure file |
| `replSessionKey` | string | no | Target REPL session (e.g., `"clj"`, `"cljs"`). Without this, Calva uses default session routing. |

## Response

**Success**: `{:result "42"}` — the string representation of the last form's evaluation result.

**Error**: `{:error "..."}` with `isError: true` — human-readable error message from Calva.

## Implementation

Wraps `calva.loadFile` VS Code command with `{:path filePath :silent true :sessionKey replSessionKey}`. The `silent: true` flag causes errors to reject the promise instead of showing UI dialogs.

Feature-gated via `exists-load-file?` in `features.cljs`, which checks `calva-version-at-least? "2.0.576"` against the installed Calva extension's `packageJSON.version`.
