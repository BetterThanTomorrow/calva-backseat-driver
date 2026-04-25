# Load File Tool — Implementation Plan

## Goal

After this plan is complete, AI agents using the Backseat Driver extension can load/evaluate entire Clojure files via a `clojure_load_file` tool. The tool wraps Calva's `calva.loadFile` command (with the new `path` argument from Calva > 2.0.575), providing a runtime-agnostic file loading capability that works across JVM Clojure, ClojureScript, and other nREPL-compatible runtimes. The tool is available through both the VS Code Language Model API and the MCP server, with E2E test coverage.

## Key Design Decisions

### Implementation approach: `vscode.commands.executeCommand`

The tool calls `calva.loadFile` with `{ path, silent: true }`, where `path` is the absolute file path. This uses Calva's own file loading infrastructure (namespace extraction, session routing, nREPL `load-file` op) — no need for Backseat Driver to reimplement any of that.

The `silent: true` argument causes errors to reject the promise instead of showing UI dialogs, allowing structured error reporting to the agent.

### Feature gate: Calva version > 2.0.575

The `path` argument to `calva.loadFile` is new in Calva > 2.0.575. The command itself exists in older versions but ignores the argument (loading the active editor's file instead — silent wrong behavior).

**Approach**: Read Calva's `packageJSON.version` via the extensions API and do a semver comparison. Parse `major.minor.patch` by splitting on `.` and extracting leading digits (handles prerelease suffixes like `2.0.576-3182-...`). Introduce a `calva-version-at-least?` helper in `features.cljs` that checks this. If the version is insufficient, the tool does not appear in `tools/list` and is not registered as an LM tool.

This departs from the existing `exists-*?` feature-detection pattern (which checks for API functions in `calva-api` v1 exports). Version checking is necessary here because the command exists in old Calva — there's no API function to detect. If Calva later exposes `loadFile` in its v1 API exports, we can add an `exists-load-file?` check as an alternative gate, but for the initial implementation, version checking is the practical path.

### REPL gating: yes, same as evaluate_code

Loading a file evaluates it in the REPL — same security posture as `clojure_evaluate_code`. Gate on `repl-enabled?` (from `:vscode/config.enableMcpReplEvaluation`) in MCP, and register the LM tool unconditionally (LM tools rely on user confirmation UX).

### Input parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `filePath` | string | yes | Absolute or workspace-relative path to the Clojure file to load |
| `replSessionKey` | string | no | Target REPL session key (e.g., `"clj"`, `"cljs"`). Without this, Calva uses its default session routing which may pick the wrong session depending on editor context. |

Always pass `silent: true` internally (not exposed to agents).

### Response shape

Success: The promise resolves to the string representation of the last form's evaluation result (e.g., `"42"`, `"nil"`, `"#'my.ns/my-var"`). Wrap in the standard MCP content envelope:
```json
{"content": [{"type": "text", "text": "42"}]}
```

Error: Return `isError: true` with the error message from the rejected promise — these are already human-readable and descriptive (file not found, evaluation error with stack trace, etc.).

## Phases

### Phase 1: Version gate infrastructure + feature wrapper

Add the ability to check Calva's version and the `load-file+` wrapper function.

- [ ] In `integrations/calva/api.cljs`: add a `calva-version` function that reads `calvaExt.packageJSON.version` (returns string or nil)
- [ ] In `integrations/calva/features.cljs`: add `calva-version-at-least?` that takes a version string and compares semver against `calva-version`. Add `exists-load-file?` that returns `(calva-version-at-least? "2.0.576")`
- [ ] In `integrations/calva/features.cljs`: add `load-file+` function that takes `{:calva/file-path path :calva/repl-session-key session-key}` and calls `(vscode/commands.executeCommand "calva.loadFile" #js {:path path :silent true :sessionKey session-key})`, returning a promise (resolves to the string result of the last evaluated form)
- [ ] Verify in REPL: `(calva-version)` returns the expected string, `(exists-load-file?)` returns true with the dev Calva
- [ ] No new lint errors
- [ ] Unit tests pass

**What the system can do now:** Backseat Driver can detect whether the connected Calva supports file loading with path arguments, and can invoke it programmatically. No tool is exposed yet.

---

### Phase 2: Tool manifest + MCP integration

Register the tool in both LM and MCP layers.

- [ ] In `package.json`: add `clojure_load_file` entry to `languageModelTools` array:
  - `name`: `"clojure_load_file"`
  - `tags`: `["clojure", "repl", "load-file", "needs-connected-repl"]`
  - `toolReferenceName`: `"clojure-load-file"`
  - `displayName`: `"Load Clojure File"`
  - `modelDescription`: Describe the tool's purpose — loads and evaluates an entire Clojure file through Calva's connected REPL. Mention it requires a connected REPL and Calva > 2.0.575.
  - `inputSchema`: `filePath` (string, required) — "The absolute or workspace-relative path to the Clojure file to load and evaluate"; `replSessionKey` (string, optional) — "The REPL session to load the file in"
  - `when`: `":calva-backseat-driver/loadFileAvailable"` — conditional visibility in LM UI
- [ ] In `tools.cljs`: add `LoadFileTool` factory function following the `EvaluateClojureCodeTool` pattern:
  - `prepareInvocation`: confirm message showing the file path
  - `invoke`: call `calva/load-file+` with the path, return `LanguageModelToolResult`
- [ ] In `tools.cljs` `register-language-model-tools`: add `when-context` dispatch for `:calva-backseat-driver/loadFileAvailable` and `cond->` entry gated on `(calva/exists-load-file?)`
- [ ] In `mcp/requests.cljs`: add `load-file-tool-listing` def (following existing tool-listing pattern with `tool-description` helper)
- [ ] In `mcp/requests.cljs` `tools/list` handler: add `cond->` entry gated on `(and (= true repl-enabled?) (calva/exists-load-file?))`
- [ ] In `mcp/requests.cljs` `tools/call` handler: add `cond` branch for `"clojure_load_file"` — extract `filePath` from arguments, call `calva/load-file+`, wrap result in MCP response envelope. Handle errors with `isError: true`.
- [ ] Verify via MCP Inspector (`bb run-mcp-inspector`): tool appears in `tools/list`, calling it with a file path works
- [ ] No new lint errors
- [ ] Unit tests pass

**What the system can do now:** The load-file tool is fully functional through both LM and MCP. Agents can call it to load files.

---

### Phase 3: E2E tests

Add MCP E2E tests for the load-file tool.

- [ ] Create a test target file in the E2E workspace — a simple `.clj` file (e.g., `e2e-test-ws/test_load_target.clj`) that defines a namespace with a known var:
  ```clojure
  (ns test-load-target)
  (def loaded-sentinel 42)
  ```
  ASSUMPTION: `calva.loadFile` with `path` argument works in the E2E workspace's REPL environment. Verify empirically before writing assertions.
- [ ] Create `e2e-test-ws/.joyride/src/tests/mcp/load_file_test.cljs` with namespace `tests.mcp.load-file-test`
- [ ] Test: tool presence — after `ensure-repl-and-eval-enabled!` + `start-mcp-session!`, verify `"clojure_load_file"` appears in `tools/list` response
- [ ] Test: successful load — call the tool with the test target's absolute path, then verify via `clojure_evaluate_code` that `test-load-target/loaded-sentinel` evaluates to `42`
- [ ] Test: error case — call the tool with a nonexistent file path, verify the response contains `isError: true`
- [ ] Run `bb run-e2e-tests-ws` — all tests pass (including existing tests)
- [ ] No new lint errors

**What the system can do now:** Load-file tool is implemented and E2E tested.

---

### Phase 4: Dynamic instructions + compose-instructions

Update the MCP server's dynamic instructions so MCP clients know about the tool.

- [ ] In `mcp/skills.cljs` `compose-instructions`: add mention of `clojure_load_file` in the `repl-enabled?` block, e.g., "You can load entire Clojure files into the REPL with `clojure_load_file`"
- [ ] Update `test/calva_backseat_driver/mcp/skills_test.cljs`: add assertion that `compose-instructions` result mentions `clojure_load_file` when repl-enabled
- [ ] No new lint errors
- [ ] Unit tests pass

**What the system can do now:** MCP clients receive instructions mentioning the load-file tool.

---

### Phase 5: Skills, instructions, and documentation

Update all documentation surfaces that enumerate tools.

- [ ] `assets/skills/backseat-driver/SKILL.md`: add `clojure_load_file` to frontmatter description tool list, add to "REPL Exploration & Understanding" tool group, mention in the VERIFY step of the workflow as an alternative to evaluating ns-form
- [ ] `assets/instructions/backseat-driver.instructions.md`: update tool count (10 → 11), add `clojure_load_file` to "REPL Exploration & Understanding" list
- [ ] `assets/skills/editing-clojure-files/SKILL.md`: mention `clojure_load_file` as an alternative to `(require '... :reload)` in the Edit Process reload step — works across all runtimes
- [ ] `README.md`: add tool bullet: `* Tool: **Load File** Load/evaluate an entire Clojure file through Calva's connected REPL`
- [ ] `CHANGELOG.md`: add entry under `[Unreleased]`
- [ ] `PROJECT_SUMMARY.md`: update tool count and add to tool lists
- [ ] `AGENTS.md`: add `calva.loadFile` to the Calva API Integration table if applicable
- [ ] Create `dev/tool-instructions/clojure_load_file.md`: brief usage guide (requires connected REPL, takes absolute file path, equivalent to Calva's Load File command)
- [ ] No new lint errors
- [ ] Unit tests pass
- [ ] E2E tests pass

**What the system can do now:** All documentation is consistent and complete.

---

## REPL-Verified Findings

The following assumptions were verified empirically using the CLJS REPL connected to the extension host running Calva `2.0.576-3182-load-file-accept-argument-49221e11`:

### ✓ Calva version is accessible at runtime
```clojure
(-> calvaExt .-packageJSON .-version)
;; => "2.0.576-3182-load-file-accept-argument-49221e11"
```
The `calva-api` v1 exports contain `["repl" "ranges" "vscode" "editor" "document" "pprint" "info"]` — no `loadFile` key, confirming version checking (not feature detection) is the right approach.

### ✓ `silent: true` rejects the promise on error
Errors produce JS Error objects with descriptive `.message` properties. Two error types observed:

**Evaluation error** (file exists but has classpath/compilation issues):
```
"Evaluation of file bracket_balance.cljs failed: Execution error (FileNotFoundException) at calva-backseat-driver.bracket-balance/eval9310$loading (bracket_balance.cljs:1). Could not locate calva_backseat_driver/integrations/parinfer__init.class..."
```

**File not found** (nonexistent path):
```
"cannot open file:///Users/.../nonexistent.clj. Detail: Unable to read file '...' (Error: Unable to resolve nonexistent file '...')"
```

Both cases: `.catch` handler fires with an Error containing a human-readable message. The tool can relay `err.message` directly to the agent.

### ✓ File loading works without an open editor
The `path` argument loads the specified file without needing it open in the editor. Verified by loading files not open in any editor tab.

### ✓ Version parsing works with prerelease strings
Calva version strings can contain prerelease suffixes (e.g., `2.0.576-3182-load-file-...`). Parsing `major.minor.patch` by splitting on `.` and extracting leading digits from the patch segment handles this correctly:
```clojure
(js/parseInt (re-find #"^\d+" "576-3182-...")) ;; => 576
```

### ✗ Promise resolves to `nil` on success
~~`calva.loadFile` resolves with `nil` (not the file's evaluation result).~~

**UPDATED**: Fixed in Calva build `e3af2f49`+. The promise now resolves to the **string representation of the last form's evaluation result**:
- `playground.clj` ending with `42` → resolves to `"42"`
- `pi_fun.clj` (edited) → resolves to `"42"`
- `foo.clj` (just `(ns mini.foo)`) with `sessionKey: "clj"` → resolves to `"nil"`

### ✓ New `sessionKey` parameter works
The updated command accepts `sessionKey` to target a specific REPL session. Without it, Calva's default routing may pick the wrong session (observed `typeof$` errors when the wrong session was selected). With explicit `sessionKey: "clj"`, all files load correctly. The Backseat Driver tool should expose `replSessionKey` as an optional parameter.

### ✓ Workspace-relative paths work
Tested with `"src/mini/playground.clj"` (workspace-relative) — works correctly. Calva resolves relative to the workspace folder.

## Remaining Open Questions

- `OPEN:` Should the tool accept workspace-relative paths in addition to absolute paths? The existing structural editing tools use absolute paths only — recommend following that convention for consistency.
- `OPEN:` E2E test feasibility: the E2E workspace uses a Joyride REPL. Whether `calva.loadFile` with `path` works via Joyride's nREPL `load-file` op needs empirical verification in that environment. If it doesn't work, E2E tests can still verify tool listing and error handling.

---

## Original Plan-producing Prompt

Create an implementation plan for adding a `clojure_load_file` tool to the Calva Backseat Driver VS Code extension. The tool wraps Calva's `calva.loadFile` command, which in Calva > 2.0.575 accepts `{ path?: string | string[] | Uri, silent?: boolean }` (see Calva's commands.md documentation). The tool enables AI agents to load/evaluate entire Clojure files through Calva, providing a runtime-agnostic alternative to `(load-file ...)` which agents struggle with across different runtimes.

Requirements:
- Tool enablement must be guarded on Calva version > 2.0.575 (the `path` argument is new; older versions ignore it, causing silent wrong behavior)
- Must work through both VS Code Language Model API (LM tools) and MCP server
- Must be E2E tested via MCP
- Follow existing patterns: tool manifest in `package.json`, factory function in `tools.cljs`, feature detection in `features.cljs`, MCP handler in `requests.cljs`, dynamic instructions in `skills.cljs`
- Gate on `repl-enabled?` config setting (same as `clojure_evaluate_code`)
- Update all documentation surfaces: skills, instructions, README, CHANGELOG, PROJECT_SUMMARY, AGENTS.md, tool-instructions

Analysis approach: 3 parallel analysis subagents (tool registration flow, E2E testing patterns, documentation impact) followed by 3 parallel cross-review subagents, then synthesis into this plan document.
