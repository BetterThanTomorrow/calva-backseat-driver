# Persistent Evaluation History — Synthesized Plan

## Goal

`clojureCode` REPL log output survives Extension Host restarts. On activation, history loads from a workspace-scoped Transit file into both Datascript conns, restoring the monotonic line counter. On each `clojureCode` message, a debounced write persists `!history-conn` to disk. On deactivation, any pending write flushes synchronously.

## Design Decisions (consensus from 3 plans + 3 reviews)

| Concern | Decision | Source |
|---|---|---|
| Load strategy | **Synchronous** `fs/readFileSync` | A wins — eliminates race conditions, <50ms for 1-5MB |
| Activation sequence | **Insert in existing dxs**, not restructure | A wins — minimal change, no blast radius |
| Deactivation | **Sync flush** via `fs/writeFileSync` | C wins — mirrors `delete-port-file!+` pattern |
| Serialization | **Explicit attributes**, strip `:db/id` | B wins — `:db/id` is conn-local, not portable |
| Format versioning | `{:format-version 1 :entities [...]}` | B wins — cheap insurance |
| Corrupt file handling | Try/catch on load, log + delete, start fresh | B+Z compromise — `.corrupt` rename is overkill |
| Schema | `:db.unique/identity` on `!history-conn` **only** | X's insight — leave `!output-conn` schemaless |
| Dual-write | **Effect inspects category**, single effect | A wins — no new action surface, action stays pure |
| Debounce timer | `defonce` atom in `calva/fxs.cljs` | A+Z — effect implementation detail |
| Storage URI | **Pre-computed** full file URI in initial state | A wins — compute once, use everywhere |
| Module organization | Serialize/deserialize in `db.cljs` | A — co-located with conns, YAGNI for separate module |
| Cap extraction | Shared `cap-conn!` helper, Phase 0 | X+Z — reduces diff noise in later phases |
| Phase 0 | Extract `cap-conn!` first as pure refactoring | Z's recommendation |

## Phases

### Phase 0: Extract `cap-conn!` helper

Pure refactoring. No behavior change.

- [ ] Extract inline cap logic from `:calva/fx.transact-output` into a `cap-conn!` function (takes `conn` and `max-size`)
- [ ] Place in `calva/fxs.cljs` as private helper (or `db.cljs` if cleaner)
- [ ] Replace inline logic with `(cap-conn! db/!output-conn 1000)`
- [ ] Tests pass, no new warnings

### Phase 1: `!history-conn`, schema, serialization

- [ ] Add `!history-conn` with schema `{:output/line {:db/unique :db.unique/identity}}` in `db.cljs`
- [ ] Add `serialize-history` — explicit pull of 5 attributes, wrapped in `{:format-version 1 :entities [...]}`
- [ ] Add `deserialize-history` — checks `:format-version`, returns entities or nil
- [ ] Require `cognitect.transit` in `db.cljs`
- [ ] REPL verify: round-trip empty conn, round-trip with entities
- [ ] Tests pass, no new warnings

### Phase 2: Dual-write `clojureCode` to `!history-conn`

- [ ] In `:calva/fx.transact-output`, after existing transact: `(when (= "clojureCode" (:output/category entity)) (d/transact! db/!history-conn [entity]) (cap-conn! db/!history-conn 10000))`
- [ ] REPL verify: clojureCode appears in both conns, other categories only in `!output-conn`
- [ ] Tests pass, no new warnings

### Phase 3: Debounced persist + deactivation flush

- [ ] Add `(defonce !persist-timer (atom nil))` in `calva/fxs.cljs`
- [ ] After dual-write, schedule debounced persist: clear previous timer, set new 500ms timeout
- [ ] Persist function: `(db/serialize-history db/!history-conn)` → `fs/writeFileSync` to pre-computed URI
- [ ] Add `:calva/history-storage-uri` to initial state in `extension.cljs` — `(some-> (.-storageUri context) (vscode/Uri.joinPath "eval-history.transit.json"))`, store `nil` if no storageUri
- [ ] Guard all persist on `(when storage-uri ...)`
- [ ] New effect `:app/fx.flush-history` — cancel timer, sync write
- [ ] Add `[:app/ax.flush-history]` to deactivation chain before cleanup
- [ ] Ensure storage directory exists (create on first write)
- [ ] REPL verify: evaluate code, check file appears after 500ms
- [ ] Tests pass, no new warnings

### Phase 4: Load history on startup

- [ ] New action `[:calva/ax.init-history]` dispatched from `[:app/ax.init]` dxs, **before** `[:calva/ax.when-activated]`
- [ ] New effect `[:calva/fx.load-history-from-disk storage-uri]`: sync read via `fs/readFileSync`, deserialize, transact into both conns, dispatch `[:calva/ax.history-loaded max-line]`
- [ ] Wrap in try/catch: on failure, log warning, delete corrupt file, dispatch `[:calva/ax.history-loaded 0]`
- [ ] New action `[:calva/ax.history-loaded max-line]`: sets `:calva/output-line-counter` to max-line
- [ ] REPL verify: restart Extension Host, confirm counter resumes, history visible in queries
- [ ] Tests pass, no new warnings

### Phase 5: Tests + Documentation

- [ ] Unit tests for serialize/deserialize round-trip (including format version)
- [ ] Unit test for `cap-conn!`
- [ ] Unit test for `:calva/ax.history-loaded` action return value
- [ ] Update AGENTS.md: add `!history-conn`, history file location
- [ ] Update CHANGELOG.md

## Key Risks

- `storageUri` nil (untitled workspace) → all persist silently disabled, no error
- `writeFileSync` during deactivation for 5MB ≈ 5-10ms — well within VS Code shutdown grace period
- Schema on `!history-conn` only — `!output-conn` stays schemaless to avoid breaking existing behavior

## Original Plan-producing Prompt

> Persist evaluation history across Extension Host restarts in Calva Backseat Driver. Two Datascript conns: `!output-conn` (session, all categories, 10K cap) and `!history-conn` (persistent, `clojureCode` only, 10K cap). Transit JSON file under `context.storageUri`. Debounced writes (~500ms). Sync load on startup before output subscription. Sync flush on deactivation. Line counter monotonic across sessions.
>
> Process: 3 parallel planners (Ex architecture focus, data integrity focus, VS Code lifecycle focus) → 3 parallel cross-reviewers (each reviewing 2 plans) → synthesized plan taking the strongest consensus on each dimension.
