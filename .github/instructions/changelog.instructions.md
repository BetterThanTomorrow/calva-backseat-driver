---
description: 'CHANGELOG.md conventions — audience, scope, and entry format for the Backseat Driver extension changelog.'
applyTo: 'CHANGELOG.md'
---

# Changelog Conventions

## Audience

The changelog is for **users of the extension**, not developers. Entries describe changes that affect how the extension works from a user's perspective.

## What belongs in the changelog

- New features and tools
- Bug fixes
- Breaking changes or migration notes
- Changes to default behavior or settings
- Workarounds for external issues that affect users

## What does not belong

- Internal refactoring, code quality improvements, or restructuring
- Developer tooling changes (build scripts, test infrastructure)
- Dependency updates with no user-visible effect

Git history serves developer-facing change tracking.

## Entry format

Follow the established pattern in the file — link to GitHub issues when available:

```markdown
- [Brief description of user-visible change](https://github.com/BetterThanTomorrow/calva-backseat-driver/issues/N)
- Fix: Description of what was broken and how it's fixed
```
