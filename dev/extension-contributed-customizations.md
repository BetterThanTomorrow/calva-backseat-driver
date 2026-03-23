# Extension-Contributed Copilot Customizations

Research into how VS Code extensions can provide instructions, skills, and prompt files to GitHub Copilot — and how Backseat Driver can leverage these mechanisms.

## Contribution Points

VS Code provides three `package.json` contribution points for extensions to ship Copilot customizations:

### `contributes.chatInstructions`

Auto-applied guidelines that Copilot includes when relevant. No manual attachment needed.

```json
"contributes": {
  "chatInstructions": [
    {
      "path": "./prompts/my-guidelines.instructions.md",
      "when": "resourceExtname == .clj"
    }
  ]
}
```

- `path` — Markdown file relative to extension root (required)
- `when` — Optional when-clause for conditional enablement
- Metadata (`name`, `description`, `applyTo`) goes in the **file's YAML frontmatter**, not in `package.json`

File format:
```markdown
---
name: 'Clojure REPL Guidelines'
description: 'How to use the REPL tools effectively'
applyTo: '**/*.clj*'
---

# Instructions content here...
```

### `contributes.chatPromptFiles`

Reusable prompts invocable as slash commands in chat.

```json
"contributes": {
  "chatPromptFiles": [
    {
      "path": "./prompts/review-and-fix.prompt.md",
      "when": "someCondition"
    }
  ]
}
```

- Same shape as `chatInstructions` — `path` + optional `when`
- Users invoke via `/` slash command menu

### `contributes.chatSkills`

Specialized capabilities with instructions, scripts, and resources. Follows the open [Agent Skills standard](https://agentskills.io/).

```json
"contributes": {
  "chatSkills": [
    {
      "path": "./skills/my-skill/SKILL.md"
    }
  ]
}
```

**Critical constraint**: The `name` field in `SKILL.md` frontmatter must match the parent directory name.

Required directory structure:
```
extension-root/
└── skills/
    └── interactive-programming/
        ├── SKILL.md          # Required
        ├── examples/         # Optional resources
        └── scripts/          # Optional scripts
```

SKILL.md format:
```markdown
---
name: interactive-programming
description: 'What it does and when to use it. Max 1024 chars.'
argument-hint: 'Optional hint for slash command input'
user-invocable: true          # Show in / menu (default: true)
disable-model-invocation: false  # Allow auto-loading (default: false)
---

# Skill instructions here...
```

## Comparison

| Aspect | `chatInstructions` | `chatPromptFiles` | `chatSkills` |
|---|---|---|---|
| Purpose | Auto-applied guidelines | Reusable slash-command prompts | Specialized capabilities |
| Scope | Always-on or file-pattern matched | On-demand via `/` menu | On-demand or auto-loaded |
| Can include resources | No (single file) | No (single file) | Yes (whole directory) |
| Conditional enablement | `when` clause + `applyTo` glob | `when` clause | `user-invocable` + `disable-model-invocation` |
| Standard | VS Code-specific | VS Code-specific | Open standard (agentskills.io) |

## How Skills Load (Three-Level System)

1. **Discovery** — Copilot reads `name` + `description` from YAML frontmatter
2. **Instructions loading** — On match or `/` invocation, loads `SKILL.md` body into context
3. **Resource access** — Loads additional files from skill directory only when referenced

This means many skills can be installed without context overhead — only relevant content loads.

## Existing Examples in the Wild

### Joyride (chatInstructions)

```json
"chatInstructions": [
  {
    "name": "JoyrideBasicsForAgents",
    "description": "Joyride agent guide...",
    "path": "./assets/llm-contexts/agent-joyride-eval.md"
  }
]
```

Note: Joyride uses the older format with `name`/`description` in `package.json`. Current docs say to put metadata in the file's YAML frontmatter instead.

### GitHub PR Extension (chatSkills — internal)

Ships skills for summarizing issues, suggesting fixes, forming search queries, and addressing PR comments. These appear to use `chatSkills` or an internal equivalent.

### Copilot Chat Extension

Ships the `get-search-view-results` skill via its extension assets.

## References

- [VS Code Contribution Points — chatInstructions](https://code.visualstudio.com/api/references/contribution-points#contributeschatinstructions)
- [VS Code Contribution Points — chatPromptFiles](https://code.visualstudio.com/api/references/contribution-points#contributeschatpromptfiles)
- [VS Code Custom Instructions docs](https://code.visualstudio.com/docs/copilot/customization/custom-instructions)
- [VS Code Agent Skills docs](https://code.visualstudio.com/docs/copilot/customization/agent-skills)
- [Agent Skills specification](https://agentskills.io/)

## MCP Server Integration

Backseat Driver bridges `chatSkills` to MCP resources, making skills accessible to any MCP client:

1. Skills declared in `package.json` `chatSkills` are read from `assets/skills/{name}/SKILL.md`
2. The MCP server exposes them via `resources/list` (name, description, URI) and `resources/read` (full content)
3. The `initialize` response includes dynamic instructions that list available skills and guide agents to discover them via resources

This allows a single skill definition to serve both VS Code Copilot (native `chatSkills`) and any MCP-compatible AI client.
