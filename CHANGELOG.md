# Change Log

Changes to Calva Backseat Driver

## [Unreleased]

## [v0.0.14] - 2025-06-22

- [Add human intelligence tool, for the agent to ask the human for guidance](https://github.com/BetterThanTomorrow/calva-backseat-driver/issues/17)
- [Place MCP stdio wrapper script in user home Calva config directory](https://github.com/BetterThanTomorrow/calva-backseat-driver/issues/15)

## [v0.0.13] - 2025-05-28

- [Add replace-top-level-form structural editing tool](https://github.com/BetterThanTomorrow/calva-backseat-driver/issues/12)
- [Add insert-top-level-form structural editing tool](https://github.com/BetterThanTomorrow/calva-backseat-driver/issues/12)
- [Enable REPL Tool for CoPilot by default (keep disabled default for MCP)](https://github.com/BetterThanTomorrow/calva-backseat-driver/issues/14)

## [v0.0.12] - 2025-05-22

- [Add `requests/list` MCP op](https://github.com/BetterThanTomorrow/calva-backseat-driver/issues/9)

## [v0.0.11] - 2025-05-12

- Clarify instructions to AI of how to use the Bracket Balancer tool

## [v0.0.10] - 2025-05-10

- Use preconfigured port for the MCP server
- Make the stdio wrapper script accept either a port or a port file

## [v0.0.9] - 2025-05-09

- [The MCP stdio script crashes when Claude Desktop uses it](https://github.com/BetterThanTomorrow/calva-backseat-driver/issues/7)
- Remove `parenTrails` from Bracket Balancer result

## [v0.0.8] - 2025-05-08

- Try improve tool usage descriptions for the AI.

## [v0.0.7] - 2025-05-08

- [Add tool for balancing brackets](https://github.com/BetterThanTomorrow/calva-backseat-driver/issues/6)

## [v0.0.6] - 2025-05-08

- Fix: [Enable for Cursor install and use](https://github.com/BetterThanTomorrow/calva-backseat-driver/issues/5)

## [v0.0.5] - 2025-05-07

- Fix: [The MCP server doesn't work with the released VSIX](https://github.com/BetterThanTomorrow/calva-backseat-driver/issues/2)
- [Help the MCP user figure out the stdio server start command](https://github.com/BetterThanTomorrow/calva-backseat-driver/issues/3)
- [Add configuration for auto-starting the MCP server](https://github.com/BetterThanTomorrow/calva-backseat-driver/issues/4)

## [v0.0.4] - 2025-05-06

- Fix: Missing user title for Output log tool
- Downbump VS Code version dependency to `1.98.0`, because Windsurf uses that

## [v0.0.3] - 2025-05-04

- Fix misconfiguration of get_symbol_info replSessionKey (for realz)
- Auto-activate in Clojure projects

## [v0.0.2] - 2025-05-03

- Fix misconfiguration of get_symbol_info replSessionKey

## [v0.0.1] - 2025-05-03

- **Initial Release**, WIP af
- **Tool**: Clojure Code Evaluation
- **Tool**: Symbol info lookup
- **Tool**: Clojuredocs lookup
- **Tool**: REPL Output log
- **Resource** (experimental): Symbol info lookup
- **Resource** (experimental): Clojuredocs lookup
