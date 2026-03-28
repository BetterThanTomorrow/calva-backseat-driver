---
description: 'Test instruction (no applyTo) — verifying whether extension-contributed chatInstructions without applyTo are always injected. If you can see this, it works!'
---

# Test Universal Instruction (from Calva Backseat Driver)

This is a test instruction contributed via `contributes.chatInstructions` in CBD's `package.json`.

This one has NO `applyTo` frontmatter — testing whether it gets injected into all chat requests regardless of file context.

## Test Content

- Contributed by Calva Backseat Driver extension
- No applyTo restriction — should appear universally
- Compare with the Clojure-scoped instruction that has `applyTo: '**/*.{clj,cljs,cljc,bb,edn}'`
