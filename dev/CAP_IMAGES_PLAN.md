# Cap Images Returned by Tools (Issue #51)

## Goal

After this plan is complete, `reduce-images` enforces a configurable cap on extracted images (default 20). Both `clojure_evaluate_code` and `clojure_repl_output_log` accept an optional `maxImages` parameter, threaded through both VS Code LM and MCP paths. Excess images are replaced with `<<image-N-capped>>` markers so agents know images were dropped. The Backseat Driver skill documents this behavior.

## Phases

### Phase 1: Core — Parameterize `reduce-images`

Add a `max-images` parameter (default 20) to `reduce-images`. Images beyond the cap are still *replaced* in the text (to keep payloads small) but with `<<image-N-capped>>` markers instead of being added to the `:images` vector.

- [ ] Update `reduce-images` in `src/calva_backseat_driver/tools.cljs` to accept an optional `max-images` arg (default 20)
- [ ] After the cap is reached, continue replacing `data:image/` strings but use `<<image-N-capped>>` markers and skip adding to `!images`
- [ ] Update `tool-result-with-images` to accept and pass through `max-images`
- [ ] Update `mcp-content-with-images` to accept and pass through `max-images`
- [ ] Verify in REPL with test data containing >20 images
- [ ] No new shadow-cljs warnings

**What the system can do now:** Image extraction is capped at the core level. All existing callers default to 20 images.

---

### Phase 2: Thread `maxImages` Through Tool Interfaces

Add `maxImages` as an optional input parameter to both tools, in both VS Code LM and MCP surfaces.

**package.json** (source of truth for descriptions):
- [ ] Add `maxImages` property to `clojure_evaluate_code` inputSchema — type `number`, description explaining the default cap of 20
- [ ] Add `maxImages` property to `clojure_repl_output_log` inputSchema — same

**MCP schemas** in `src/calva_backseat_driver/mcp/requests.cljs`:
- [ ] Add `maxImages` to `evaluate-code-tool-listing` properties
- [ ] Add `maxImages` to `output-log-tool-info` properties

**MCP handlers** in `src/calva_backseat_driver/mcp/requests.cljs`:
- [ ] Destructure `maxImages` in the `clojure_evaluate_code` handler, pass to `mcp-content-with-images`
- [ ] Destructure `maxImages` in the `clojure_repl_output_log` handler, pass to `mcp-content-with-images`

**VS Code tool handlers** in `src/calva_backseat_driver/tools.cljs`:
- [ ] Read `maxImages` from `options.input` in `EvaluateClojureCodeTool.invoke`, pass to `tool-result-with-images`
- [ ] Read `maxImages` from `options.input` in `GetOutputLogTool.invoke`, pass to `tool-result-with-images`

- [ ] No new shadow-cljs warnings

**What the system can do now:** Agents can pass `maxImages` to control the cap; defaults protect against the uncapped case.

---

### Phase 3: Documentation — Update Backseat Driver Skill

- [ ] Update the "Visual results" section in `assets/skills/backseat-driver/SKILL.md` to mention the 20-image cap, the `<<image-N-capped>>` markers, and that `maxImages` can override the default

**What the system can do now:** Agents are informed about image limits and know how to adjust them.

---

### Phase 4: Verify

- [ ] Run E2E tests (`bb run-e2e-tests-ws`) to confirm existing image tests still pass
- [ ] Manually test with MCP inspector (`bb run-mcp-inspector`) if feasible

**What the system can do now:** Full confidence that the change works end-to-end.

---

## Open Questions / Assumptions

- `ASSUMPTION:` Default cap of 20 is appropriate. The issue says "maybe different models have different limits" — 20 is conservative enough for current models. The parameterization lets agents/users adjust.
- `ASSUMPTION:` Capped images should still have their `data:image/` content stripped from the text (replaced with `<<image-N-capped>>`) to keep payloads small.

---

## Original Plan-producing Prompt

Fix issue #51 (output log tool returns too many images, stopping the agent). Parameterize `reduce-images` in `tools.cljs` with a `max-images` default of 20. Add an optional `maxImages` input parameter to both `clojure_evaluate_code` and `clojure_repl_output_log` tools (VS Code LM API in `tools.cljs`, MCP schemas and handlers in `requests.cljs`, descriptions in `package.json`). Update the Backseat Driver skill doc to mention the cap. Verify with existing E2E tests.
