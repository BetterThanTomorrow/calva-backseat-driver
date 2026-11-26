---
name: e2e-testing
description: 'E2E test authoring for Backseat Driver. Use when: writing, modifying, or debugging e2e tests under e2e-test-ws/, adding new MCP test scenarios, investigating test failures, understanding test infrastructure, or working with files matching *_test.cljs in the e2e workspace.'
---

# E2E Testing — Backseat Driver

E2e tests run inside a VS Code Extension Host via Joyride. They exercise the full stack: extension activation, MCP server lifecycle, TCP socket communication, tool invocation, and VS Code command registration. Together with the `bd-tester` agent (which uses the MCP protocol for its test harness) in the example/test project inside the dev Extension Host, the e2e suite provides full coverage of the MCP server and tools.

When verifying a change that affects MCP behavior, use the `bd-tester` agent in the dev Extension Host to specifically exercise the changed functionality, and often run its full protocol as a regression check.

## Running Tests

```bash
bb run-e2e-tests-ws          # in a Linux container on a virtual display
bb run-e2e-tests-ws --local  # in a VS Code on this machine
bb run-e2e-tests-ws --local --vscode-executable /Applications/Cursor.app/Contents/MacOS/Cursor  # in another editor, here Cursor
```

- The default runs in Docker (`e2e-test-ws/run-containerized.sh`), against the `out/` the build keeps current. No test window opens on the screen.
- `--local` needs `ELECTRON_RUN_AS_NODE` unset when started from a VS Code terminal.
- `--vscode-executable` takes the editor to run the suite in, with `--local`, and opens that editor on the screen. Cursor shows its login window for the fresh user data directory, and the tests run without a login.
- Output summary prints to stdout
- Detailed output: `.tmp/e2e-output.log` — read this file for diagnostics
- Do not pipe or redirect the command

### Tests That Depend on the Editor

`tests/mcp/cursor_test.cljs` runs only where the editor has Cursor's registration API. The cases in `tests/mcp/tool_results_test.cljs` that go through the Language Model API run only where `vscode.lm.tools` has tools, which Cursor doesn't. Each logs a line when it skips.

### When a Run Goes Wrong

- A run in Docker has hung a few times, with VS Code logging that the extension host is unresponsive. The e2e task has no time limit of its own, so such a run waits until it is stopped. The cause is unknown.
- The active editor tests in `structural_editing_test.cljs` each create a target file of their own, and `clojure_edit_files` reports an edit as saved when VS Code has saved the file.

## Test Infrastructure

### File Layout

```
e2e-test-ws/.joyride/
├── scripts/
│   └── workspace_activate.cljs  # Workspace setup; the runner waits for it
└── src/
    ├── e2e/
    │   ├── test_runner.cljs    # Discovery, ordering, execution
    │   ├── macros.cljs         # deftest-async macro
    │   ├── utils.cljs          # wait-for+ and delay+
    │   ├── mcp_helpers.cljs    # Shared MCP session/request helpers
    │   ├── db.cljs             # Test state atom (pass/fail/error counts)
    │   └── baldr.cljc          # ANSI color reporter
    └── tests/
        ├── smoke_test.cljs
        ├── ws_ready/
        │   └── example_test.cljs        # The workspace is set up before the tests
        └── mcp/
            ├── a_activation_test.cljs   # Pre-activation assertions (runs first)
            ├── balance_brackets_test.cljs
            ├── clojuredocs_info_test.cljs
            ├── cursor_test.cljs         # Registration with Cursor
            ├── eca_config_test.cljs     # The ECA config entry
            ├── load_file_test.cljs
            ├── output_log_test.cljs     # Datalog query tests
            ├── server_test.cljs         # Server lifecycle, tools, resources, skills
            ├── stdio_wrapper_test.cljs  # The stdio wrapper relay
            ├── structural_editing_test.cljs
            ├── symbol_info_test.cljs
            ├── tool_results_test.cljs   # Each tool's results, through MCP and the Language Model API
            └── z_bad_output_log_query_test.cljs
```

### Test Runner Mechanics

`test_runner.cljs` discovers `*_test.cljs` files, converts to namespace symbols, and **sorts alphabetically**. All namespaces run sequentially within one Extension Host session.

VS Code extension activation is irreversible within a session. Tests that must run before activation use an `a_` filename prefix to sort first (e.g., `a_activation_test.cljs`).

A minimum assertion threshold (currently 2) catches silent failures where tests appear to pass but produce no assertions.

### `deftest-async` Macro

Tests that await use `deftest-async` from `e2e.macros`. It runs the body as an async function inside `cljs.test/async`, with the body in one `do`, so a test body may have several forms. The function starts from a resolved promise, so a throw before the first `await` fails the test the same way a rejection does.

```clojure
(deftest-async my-test
  (let [result (await (some-async-op+))]
    (is (= expected result))))
```

Sync-only tests use plain `deftest`.

### Async Functions in SCI

Joyride runs the tests in SCI, and SCI's async functions are not the ClojureScript compiler's. What holds for them:

- `(defn ^:async f [] …)`, `(defn- ^:async f [] …)` and `(^:async fn [] …)` make async functions. In `(fn ^:async [] …)` the metadata is on the argument vector, and loading fails with "Unable to resolve symbol: await". The metadata goes on `fn` itself.
- `await` works inside `let`, `do`, `if`/`when`/`cond`, `case`, `loop`/`recur`, `doseq`, `->`, `testing`, and `try`/`catch`/`finally`.
- **An async function's body must be one form.** With several forms in the body, an `await` in a form that isn't the last one is not waited for, and its rejection is lost, so a test passes without testing. Wrap the body in a `do` or a `let`, as `stop-mcp-session!` does. `deftest-async` does it for a test body.
- A throw before the first `await` throws synchronously. It does not reject.
- Once the body has an `await`, `catch` ignores the exception type: the first `catch` clause catches everything. The tests write `(catch :default e …)`.
- `await` inside a nested plain `fn`, or inside `with-redefs`, fails at load. A predicate for `wait-for+` that awaits is an `(^:async fn [] …)`.

### `wait-for+` and `delay+`

`e2e.utils` has both. `wait-for+` polls a predicate every 50ms, and rejects after `:timeout` with `:message`. The predicate may return a promise.

```clojure
(await (wait-for+ #(some-condition?)
                  :timeout 5000
                  :message "Condition not met"))
```

`delay+` resolves after the given milliseconds. It is the pause in a test's own poll loop (`wait-for-output` in `output_log_test.cljs`), and the timeout raced against a request (`send-request-with-timeout`). A condition is polled with `wait-for+`, and never waited for with a fixed `delay+` or `setTimeout`.

## Shared MCP Helpers (`e2e.mcp-helpers`)

The helpers that talk to VS Code, a socket or a process return promises. Await them.

| Helper | Purpose |
|--------|---------|
| `start-mcp-session!` | Activate extension → start server → connect socket → send `initialize` → returns `{:socket :port}` |
| `stop-mcp-session!` | Stop server command → close socket. Resolves when the server has stopped |
| `connect-to-mcp-server` | Connect a socket to a port, on `default-mcp-host` unless a host is given |
| `send-request` | JSON-RPC request over TCP socket, resolves to the response with the request's id |
| `send-request-with-timeout` | `send-request` that rejects when no response comes within the timeout |
| `call-tool` | Wraps `send-request` for `tools/call`, parses JSON text content |
| `wait-for-tool!` | Poll `tools/list` until the tool is registered |
| `ensure-repl-and-eval-enabled!` | Connect Joyride REPL → wait for session → ensure `enableMcpReplEvaluation` is on (default is `true`). **Activates the extension as a side effect.** |
| `backup-settings!` | Snapshot `.vscode/settings.json` to a file beside it, returns path |
| `restore-settings!` | Restore the Backseat Driver settings from the backup path, wait until VS Code has them, delete the backup |
| `wrapper-script-path` | The extension's `dist/calva-mcp-server.js` |
| `spawn-stdio-wrapper!` | Start the stdio wrapper for a port, returns a client map |
| `send-stdio-request!` | JSON-RPC request through the stdio wrapper, with a timeout |
| `stop-stdio-wrapper!` | Kill the stdio wrapper process |
| `start-chunked-response-server!` | A fake TCP server that answers in two socket writes |
| `stop-chunked-response-server!` | Close that server |
| `workspace-uri` | The workspace folder URI |
| `settings-path` | Path to `.vscode/settings.json` |

## Patterns

### MCP Session Lifecycle

Every MCP test follows this shape:

```clojure
(deftest-async my-mcp-test
  (try
    (let [{:keys [socket]} (await (mcp/start-mcp-session!))
          result (await (mcp/call-tool socket 1 "some_tool" {}))]
      (is (= expected result))
      (await (mcp/stop-mcp-session! socket)))
    (catch :default e
      (js/console.error (.-message e) e)
      (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
      (throw e))))
```

The catch clause stops the server to prevent leaked processes, then re-throws so `deftest-async` records the failure.

### Settings Backup/Restore

Tests that modify VS Code configuration wrap in backup/restore:

```clojure
(deftest-async config-dependent-test
  (let [backup-path (mcp/backup-settings! "my-test-backup.json")]
    (try
      (let [;; ... modify settings and test ...
            ]
        ;; assertions
        )
      (catch :default e ...)
      (finally
        (await (mcp/restore-settings! backup-path))))))
```

The `finally` clause restores the settings on failure too.

### Checkpoint-Based Output Assertions

Use a monotonic line counter as a checkpoint before the action. Query for entries after the checkpoint to isolate from prior test output.

```clojure
checkpoint (await (get-max-line socket))
;; ... action that produces output ...
rows (await (wait-for-output {:socket socket :query who-since-query
                              :inputs [checkpoint "my-who-slug"] :pred seq}))
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
