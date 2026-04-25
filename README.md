# Make Copilot an Interactive Programmer

Clojure Tools for Copilot

> (It is also an MCP Server, for users of other AI harnesses)

See also [Awesome Backseat Driver](https://github.com/BetterThanTomorrow/awesome-backseat-driver) for a repository hosting Clojure related Copilot plugins, skills, agents, instructions, hooks, prompts, etcetera.

[![VS Code Extension](https://img.shields.io/visual-studio-marketplace/v/betterthantomorrow.calva-backseat-driver)](https://marketplace.visualstudio.com/items?itemName=betterthantomorrow.calva-backseat-driver)
[![Issues](https://img.shields.io/github/issues/BetterThanTomorrow/calva-backseat-driver)](https://github.com/BetterThanTomorrow/calva-backseat-driver/issues)
[![License](https://img.shields.io/github/license/BetterThanTomorrow/calva-backseat-driver)](https://github.com/BetterThanTomorrow/calva-backseat-driver/blob/master/LICENSE.txt)

A VS Code language model extension for [Calva](https://calva.io), the Clojure/ClojureScript extension for VS Code, enabling AI assistants to harness the power of the REPL.

This extension exposes the AI tools both to Copilot directly, using the VS Code Language Model API, and via an optional MCP server for any AI assistants/agents.

## Features

* Tool: **Evaluate Code** Access to the Clojure REPL to evaluate code at will
* Tool: **Create Clojure File** Creates Clojure files with automatic bracket balancing
* Tool: **Append Code** Appends code to Clojure files with automatic bracket balancing
* Tool: **Replace Top Level Form** Structural editing, including formatting, bracket balancing and linting
* Tool: **Insert Top Level Form** Structural editing, including formatting, bracket balancing and linting
* Tool: **Bracket Balancer** Helps the model get the bracket balance right (powered by [Parinfer](https://github.com/parinfer/parinfer.js))
* Tool: **Load File** Load/evaluate an entire Clojure file through Calva's connected REPL
* Tool: **Symbol info lookup**, the AI can look up symbols it is interested in, and will get doc strings, argument info etcetera
* Tool: **clojuredocs.org lookup**, docs, examples, and *see also* information on Clojure core-ish symbols
* Resource: **Symbol info lookup**, (a bit experimental) same as the tool
* Resource: **clojuredocs.org lookup**, (a bit experimental) same as the tool
* Resource: **Skills**, specialized instructions discoverable via `resources/list` and readable via `resources/read`

Please note that for the editing tools there is no UI for reviewing the edits. I suggest using the source
control tools for reviewing AI editing activity.

## Copilot Instructions: Leveraging Backseat Driver

Backseat Driver gives Copilot the tools for Clojure Interactive Programming and the skills for using the tools. To allow you to keep the control of how Copilot holds Clojure and the REPL, the extension does not provide much in the way of Clojure knowledge, philosophy, nor for REPL methodology.

For Copilot to be truly effective it needs to know how you prefer Clojure to be written and how to use the REPL effectively.

To avoid starting with a blank slate, where bad training data and hallucinations about Clojure ruin the day, consider installing the **clojure** Copilot plugin from the [Awesome Backseat Driver](https://github.com/BetterThanTomorrow/awesome-backseat-driver) repository. There are some instructions in the README for that repository.

## Configuring Backseat Driver

### Evaluation result size limiting

To prevent large REPL evaluation results from overwhelming the agent's context window, Backseat Driver automatically limits the size of returned results:

- `calva-backseat-driver.evaluation.maxLength` (default `25`) — maximum number of items to display in collections. Use `0` to disable length limiting.
- `calva-backseat-driver.evaluation.maxDepth` (default `7`) — maximum nesting depth; deeper structures are replaced with `##`. Use `0` to disable depth limiting.

### Skills

Backseat Driver provides two skills to AI agents:

- `calva-backseat-driver.provideBdSkill` (default `true`) — provide the Backseat Driver skill to agents
- `calva-backseat-driver.provideEditSkill` (default `true`) — provide the Clojure structural editing skill to agents

### Editor configuration

The structural editing tools for inserting and replacing top level forms respect two Backseat Driver editor settings:

- `calva-backseat-driver.editor.fuzzyLineTargetingPadding` (default `2`) — number of lines on each side of the requested line that the AI is allowed to scan when matching target text. Increase this if forms move around during larger refactorings; set to `0` for exact line targeting. _Trade-off_: higher values tolerate line shifts but raise the risk of matching a nearby, similar form when the agent's copy of the buffer is stale.
- `calva-backseat-driver.editor.lineContextResponsePadding` (default `10`) — number of lines on each side of the requested line included in the troubleshooting snippet returned when targeting fails. Reduce this to keep responses shorter, or increase it for more surrounding context. _Trade-off_: larger values give the agent more cues for a retry, but can cost extra tokens (or time) compared with sending a focused snippet.
### MCP

See: [Configure Backseat Driver as an MCP server](MCP-CONFIGURATION.md)

## Getting Started

### Prerequisites

- [VS Code](https://code.visualstudio.com/)
- [Calva](https://marketplace.visualstudio.com/items?itemName=betterthantomorrow.calva)
- [Calva Backseat Driver](https://marketplace.visualstudio.com/items?itemName=betterthantomorrow.calva-backseat-driver)
- GitHub Copilot (or some MCP compliant assistant)

### Using

0. Teach the AI Clojure and REPL discipline, e.g. See: [Copilot Instructions](#copilot-instructions-leveraging-backseat-driver)
1. Connect Calva to your Clojure/ClojureScript project
1. Ask Copilot to help you with things. It will know what you mean when you say: "Please use the REPL to investigate ...".
  * It will know how to find the right REPL session and how to use it.
  * Configure Calva to use the terminal for REPL output and monitor the output terminal to see what it tries at the REPL

It works very well will subagents, also parallel subagents. Try something like:

1. "Please task three parallel subagents to use the REPL to investigate how to implement ...”
  * The REPL output will show you, and the agents, who is trying what at the REPL.
  * You can participate in the REPL party.

> [!NOTE]
> The stronger the model you use, the better result. As of this writing, Claude Opus 4.6 is the best to understand how to use Backseat Driver and Clojure.

All tools can be referenced in the chat by prepending the tool name with a `#`, e.g.`#clojure-eval`.

### MCP

Copilot doesn't need MCP, but for other AI harnesses Calva Backseat Driver implements the [Model Context Protocol](https://modelcontextprotocol.io) (MCP), creating a bridge between AI assistants and your REPL.

## Alternatives

Some projects/tools to look to complement Backseat Driver, or use instead of it:

* [Clojure MCP](https://github.com/bhauman/clojure-mcp) - [Bruce Hauman](https://github.com/bhauman)'s take. A very comprehensive set of tools, resources, prompts and agents to use AI for generating more maintainable code than we could do without AI.
* [nREPL MCP Server](https://github.com/JohanCodinha/nrepl-mcp-server), gives the AI tools to connect to a running nREPL server and evaluate code (and more)
* [Babashka AI Coding Tools](https://github.com/nextdoc/ai-tools), Clojure test runner for AI agents.

## WIP

As we all are, I am learning to use AI and figuring out one thing at a time. All while the whole space is moving faster than I can learn. Backseat Driver is my very best effort to provide Clojure developers with powerful AI tools that can be used with zero configuration.

The basic design of Backseat Driver has proven to work and be useful over time. But I have also been improving it incrementally as I have learnt new things. A lot of these things I have learnt from users.

Please, please let me know how you fare with Backseat Driver, and what features you would like to see. 🙏

## Contributing

Contributions are welcome! Issues, PRs, whatever. Before a PR, I appreciate an issue stating the problem being solved. You may also want to reach out discussing the issue before starting to work on it.

## License 🍻🗽

[MIT](LICENSE.txt)

## Please sponsor my open source work ♥️

You are welcome to encourage my work, using this link:

* https://github.com/sponsors/PEZ
