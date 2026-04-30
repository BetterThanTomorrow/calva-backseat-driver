---
name: e2e-testing
description: 'E2E test authoring for Backseat Driver. Use when: writing, modifying, or debugging e2e tests under e2e-test-ws/, adding new MCP test scenarios, investigating test failures, understanding test infrastructure, or working with files matching *_test.cljs in the e2e workspace.'
---

# E2E Testing — Backseat Driver

E2e tests run inside a VS Code Extension Host via Joyride. They exercise the full stack: extension activation, MCP server lifecycle, TCP socket communication, tool invocation, and VS Code command registration.

## Running Tests

```bash
bb run-e2e-tests-ws
```

- Output summary prints to stdout
- Detailed output: `.tmp/e2e-output.log` — read this file for diagnostics
- Do not pipe or redirect the command

## Test Infrastructure

### File Layout

```
e2e-test-ws/.joyride/src/
├── e2e/
│   ├── test_runner.cljs    # Discovery, ordering, execution
│   ├── macros.cljs         # deftest-async macro
│   ├── utils.cljs          # wait-for+ polling utility
│   ├── mcp_helpers.cljs    # Shared MCP session/request helpers
│   ├── db.cljs             # Test state atom (pass/fail/error counts)
│   └── baldr.cljs          # ANSI color reporter
└── tests/
    └── mcp/
        ├── a_activation_test.cljs   # Pre-activation assertions (runs first)
        ├── output_log_test.cljs     # Datalog query tests
        └── server_test.cljs         # Server lifecycle, tools, resources, skills
```

### Test Runner Mechanics

`test_runner.cljs` discovers `*_test.cljs` files, converts to namespace symbols, and **sorts alphabetically**. All namespaces run sequentially within one Extension Host session.

VS Code extension activation is irreversible within a session. Tests that must run before activation use an `a_` filename prefix to sort first (e.g., `a_activation_test.cljs`).

A minimum assertion threshold (currently 2) catches silent failures where tests appear to pass but produce no assertions.

### `deftest-async` Macro

All promise-based tests use `deftest-async` from `e2e.macros`. It wraps the body in `cljs.test/async` with automatic error-to-failure conversion.

```clojure
(deftest-async my-test
  (p/let [result (some-async-op)]
    (is (= expected result))))
```

Sync-only tests use plain `deftest`.

### `wait-for+` — The Only Timing Primitive

Poll a predicate every 50ms. Rejects after configurable timeout.

```clojure
(wait-for+ #(some-condition?)
           :timeout 5000
           :message "Condition not met")
```

Never use `p/delay` or `setTimeout` for waiting on conditions.

## Shared MCP Helpers (`e2e.mcp-helpers`)

| Helper | Purpose |
|--------|---------|
| `start-mcp-session!` | Activate extension → start server → connect socket → send `initialize` → returns `{:socket :port}` |
| `stop-mcp-session!` | Stop server command → close socket |
| `send-request` | JSON-RPC request over TCP socket, resolves first `data` event |
| `call-tool` | Wraps `send-request` for `tools/call`, parses JSON text content |
| `ensure-repl-and-eval-enabled!` | Connect Joyride REPL → wait for session → enable `enableMcpReplEvaluation` setting. **Activates the extension as a side effect.** |
| `backup-settings!` | Snapshot `.vscode/settings.json` to a temp file, returns path |
| `restore-settings!` | Restore settings from backup path |
| `workspace-uri` | The workspace folder URI |
| `settings-path` | Path to `.vscode/settings.json` |

## Patterns

### MCP Session Lifecycle

Every MCP test follows this shape:

```clojure
(deftest-async my-mcp-test
  (-> (p/let [{:keys [socket]} (mcp/start-mcp-session!)
              ;; ... test body using socket ...
              _ (mcp/stop-mcp-session! socket)]
        ;; assertions here
        )
      (p/catch (fn [e]
                 (js/console.error (.-message e) e)
                 (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                 (throw e)))))
```

The catch clause stops the server to prevent leaked processes, then re-throws so `deftest-async` records the failure.

### Settings Backup/Restore

Tests that modify VS Code configuration wrap in backup/restore:

```clojure
(deftest-async config-dependent-test
  (let [backup-path (mcp/backup-settings! "my-test-backup.json")]
    (-> (p/let [;; ... modify settings and test ...
                ]
          ;; assertions
          )
        (p/catch (fn [e] ...))
        (p/finally (fn [] (mcp/restore-settings! backup-path))))))
```

`p/finally` ensures settings restore even on failure.

### Checkpoint-Based Output Assertions

Use a monotonic line counter as a checkpoint before the action. Query for entries after the checkpoint to isolate from prior test output.

```clojure
checkpoint (get-max-line socket)
;; ... action that produces output ...
rows (wait-for-output socket query [checkpoint "my-who-slug"] seq)
;; All rows guaranteed to be from this test's action
(is (every? #(> (:line %) checkpoint) rows))
```

### Domain-Specific Helpers

Keep test-domain helpers (e.g., `query-output-log`, `evaluate-code`, `wait-for-output`) as private functions in the test namespace. Shared helpers in `mcp_helpers.cljs` are for cross-cutting MCP concerns (session lifecycle, request plumbing).

Promote to `mcp_helpers.cljs` only when multiple test namespaces need the same helper.

## Data Shape Through MCP

JSON serialization through the MCP socket strips Clojure namespace qualifiers from keywords:

| Clojure side | After JSON roundtrip |
|---|---|
| `:output/category` | `:category` |
| `:output/line` | `:line` |
| `:output/who` | `:who` |

Assertions in e2e tests must use the unqualified key names.

## Adding a New Test File

1. Create `e2e-test-ws/.joyride/src/tests/mcp/<name>_test.cljs`
2. The ns must follow `tests.mcp.<name>-test` (kebab-case, matching the snake_case filename)
3. Require `[e2e.macros :refer [deftest-async]]` and `[e2e.mcp-helpers :as mcp]`
4. The test runner discovers it automatically via glob
5. Consider alphabetical sort position — if the test activates the extension or has ordering dependencies, choose the filename accordingly
