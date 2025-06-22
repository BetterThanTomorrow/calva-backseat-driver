# Make CoPilot an Interactive Programmer

Clojure Tools for CoPilot

> It is also an MCP Server for Calva

(Parts of this README is written by Claude Sonnet. Pardon any marketing language. I will clean up.)

[![VS Code Extension](https://img.shields.io/visual-studio-marketplace/v/betterthantomorrow.calva-backseat-driver)](https://marketplace.visualstudio.com/items?itemName=betterthantomorrow.calva-backseat-driver)
[![Issues](https://img.shields.io/github/issues/BetterThanTomorrow/calva-backseat-driver)](https://github.com/BetterThanTomorrow/calva-backseat-driver/issues)
[![License](https://img.shields.io/github/license/BetterThanTomorrow/calva-backseat-driver)](https://github.com/BetterThanTomorrow/calva-backseat-driver/blob/master/LICENSE.txt)

An VS Code Language model extension for [Calva](https://calva.io), the Clojure/ClojureScript extension for VS Code, enabling AI assistants to harness the power of the REPL.

This extension exposes the AI tools both to CoPilot directly, using the VS Code Language Model API, and via an optional MCP server for any AI assistants/agents.

## Features

* Tool: **Evaluate Code** Access to the Clojure REPL to evaluate code at will
* Tool: **Human Intelligence** The agent can ask the human for guidance, using an VS Code input box
* Tool: **Replace top level form** Structural editing, including formatting, bracket balancing and linting
* Tool: **Insert top level form** Structural editing, including formatting, bracket balancing and linting
* Tool: **Bracket Balancer** Helps the model get the bracket balance right (powered by [Parinfer](https://github.com/parinfer/parinfer.js))
* Tool: **Symbol info lookup**, the AI can look up symbols it is interested in, and will get doc strings, argument info etcetera
* Tool: **clojuredocs.org lookup**, docs, examples, and *see also* information on Clojure core-ish symbols
* Resource: **Symbol info lookup**, (a bit experimental) same as the tool
* Resource: **clojuredocs.org lookup**, (a bit experimental) same as the tool

Please note that for the editing tools there is no UI for reviewing the edits. I suggest using the source control tools for reviewing AI editing activity.

## Why Calva Backseat Driver?

"I wish Copilot could actually run my Clojure code instead of just guessing what the code may do."

The Calva Backseat Driver transforms AI coding assistants from static code generators into interactive programming partners by giving them access to your REPL. (Please be mindful about the implications of that before you start using it.)

### Turn your AI Agent into an Interactive Programming partner

Tired of AI tools that write plausible-looking Clojure that falls apart at runtime? Calva Backseat Driver lets your AI assistant:

- **Evaluate code in your actual environment** - No more "this might work" guesses
- **See real data structures**, not just predict their shape
- **Test functions with real inputs** before suggesting them
- **Debug alongside you** with access to runtime errors
- **Learn from your codebase's actual behavior**

### For Clojurians who value Interactive Programming

As Clojure developers, we know the REPL isn't just a console - it's the center of our workflow. Now your AI assistant can join that workflow, understanding your data and functions as they actually exist, not just as they appear in static code.

In [test-projects/example/AI_INTERACTIVE_PROGRAMMING.md](test-projects/example/AI_INTERACTIVE_PROGRAMMING.md) you'll find an attempt to prompt the AI to leverage the REPL for interactive programming. (With varying success, help with this is much appreciated!)

## Getting Started

### Prerequisites

- [VS Code](https://code.visualstudio.com/)
- [Calva](https://marketplace.visualstudio.com/items?itemName=betterthantomorrow.calva)
- [Calva Backseat Driver](https://marketplace.visualstudio.com/items?itemName=betterthantomorrow.calva-backseat-driver)
- GitHub CoPilot (or some MCP compliant assistant)

### Code generation instructions

This is something we will have to figure out and discover together.  Here's a system prompt you can try. A lot of it is ripped from [Clojure MCP](https://github.com/bhauman/clojure-mcp).

For CoPilot with default settings, system prompts can be provided in the workspace file `.github/copilot-instructions.md`

```markdown
# AI Interactive Programming with Clojure and Calva Backseat Driver

You are an AI Agent with access to Calva's REPL connection via the `evaluate-clojure-code` tool. THis makes you an Interactive Programmer. You love the REPL. You love Clojure. You also love lisp structural editing, so when you edit files you prefer to do so with structural tools such as replacing or inserting top level forms. Good thing Backseat Driver has these tool!

You use your REPL power to evaluate and iterate on the code changes you propose. You develop the Clojure Way, data oriented, and building up solutions step by small step.

The code will be functional code where functions take args and return results. This will be preferred over side effects. But we can use side effects as a last resort to service the larger goal.

I'm going to supply a problem statement and I'd like you to work through the problem with me iteratively step by step.

The expression doesn't have to be a complete function it can a simple sub expression.

Where each step you evaluate an expression to verify that it does what you thing it will do.

Println use id HIGHLY discouraged. Prefer evaluating subexpressions to test them vs using println.

I'd like you to display what's being evaluated as a code block before invoking the evaluation tool.

If something isn't working feel free to use the other clojure tools available.

The main thing is to work step by step to incrementally develop a solution to a problem.  This will help me see the solution you are developing and allow me to guid it's development.

When you update files:

1. You first have used the REPL tool to develop and test the code that you edit into the files
1. You use the structural editing tool to do the actual updates
```

The Backset Driver extension provides these as defailts instructions, in the `github.copilot.chat.codeGeneration.instructions` array. But only in case you don't have any yet.

* Afaik, there is no way for the extension to describe itself to CoPilot.
* For MCP clients, the server provides a description of itself.

Even with this system prompt, to my experience the AI needs to be reminded to use the structural editing tools, and the REPL. When you give the agent a task, it can be good to end with something like:

> Please use interactive programming and structural editing.

This repository has **Discussions** active. Please use it to share experience and tips with prompting.

Right now I include this in the `github.copilot.chat.codeGeneration.instructions` array, and it seems to work pretty well.

```
      {
        "text": "You are a Senior Clojure developer who know how to leverage the Calva Backseat Driver tools to improve your assistance. Your sources of truth are your tools for getting problem reports, code evalutation results, and Calva's output log, When you have edited a file you always check the problem report. Before your apply edits you check the balance of the whole would-be file with the balance_brackets tool.",
        "language": "clojure"
      },
```

### Configuration (if using MCP Server)

> Since evaluating Clojure code could be a bit risky, the MCP server defaults to evaluation being disabled, so you can use the server for other things. Search for *Backseat Driver* in VS Code Settings to enable it.
>
> Note that there are several layers to the security model here. This server starts with evaluation powers disables, and compliant MCP servers will default to low trust mode and ask for your confirmation every time the LLM wants to use the tool. Full YOLO mode is enabled if you enable the tool in the Calva MCP settings, and configure your AI client to be allowed to use it without asking.

The MCP server is running as a plain socket server in the VS Code Extension Host, writing out a port file when it starts. Then the MCP client needs to start a `stdio` relay/proxy/wrapper. The wrapper script takes the port or a port file as an argument. Because of these and other reasons, there will be one Calva Backseat Driver per workspace, and the port file will be written to the `.calva` directory in the workspace root.
* The default port for the socket server is `1664`. If that is not available, a random, high, available port number will be used.
* You can configure the try-first port to something else via the setting `calva-backseat-driver.mcpSocketServerPort`. Use `0` to use a random, high, available port number.

1. Open your project
1. Start the Calva MCP socket server
   * This will create a port file: `${workspaceFolder}/.calva/mcp-server/port`
   * When the server is started, a confirmation dialog will be shown. This dialog has a button which lets you copy the port number, or the command for starting the stdio wrapper, to the clipboard.

     ![MCP Server Started message with Copy Command button](assets/howto/mcp-copy-stdio-command.png)
1. Add the MCP server config (will vary depending on MCP Client)

#### Workspace/project level config

Backseat Driver is a per-project MCP server, so should be configured on the project level if the assistant you are using allows it.

* **If you can use project/workspace level configuration**, then it is best to use the **port file** as the argument to the stdio script. If your MCP client doesn't support project level
* **If your MCP client does not support project/workspace configuration**, then you can still configure Backseat Driver to use different socket server ports for different projects. And then configure your MCP client with the appropriate port number for your session.

I am sorry that this is a bit messy. It is obvious that MCP is a bit new, and that project level MCP servers may not have been considered when creating MCP. I have tried to understand and navigate the limitiations and to provide configurability/Ux to the best of my understanding and ability.

#### Cursor configuration

[Cursor](https://www.cursor.com/) supports project level config.

In you project's `.cursor/mcp.json` add a `"backseat-driver"` entry like so:
```json
{
  "mcpServers": {
    "backseat-driver": {
      "command": "node",
      "args": [
        "<absolute path to calva-mcp-server.js in the extension folder>",
        "<absolute path to port file (which points to your project's .calva/mcp-server/port)"
      ]
    }
  }
}
```
Both absolute paths needed above can be conveniently determined by clicking on the **Copy command** button (shown when starting the MCP server) and then pasting into `mcp.json` file, as described above: [Configuration](#configuration-if-using-mcp-server).


Cursor will detect the server config and offer to start it.

You may want to check the [Cursor MCP docs](https://docs.cursor.com/chat/tools#mcp-servers).

#### Windsurf configuration

[Windsurf](https://windsurf.com/) can use the Backseat Driver via its MCP server. However, it is a bit clunky, to say the least. Windsurf doesn't support workspace configurations for MCP servers, so they are only global. This means:

* You can in practice only have one Backseat Driver backed project
* It's probably best to use the port number as the argument for the stdio command
* If you use the port file as the argument, it must be an absolute path (afaik)

Windsurf's configuration file has the same shape as Cursor's, located at: `~/.codeium/windsurf/mcp_config.json` (at least on my machine).

The Windsurf AI assistant doesn't know about its MCP configurations and will keep trying to create MCP configs for CoPilot. Which is silly, because it won't work for Windsurf, and CoPilot doesn't need it.

**Clunk**: At startup, even with the MCP server set to auto-start, Windsurf often refreshes its MCP servers quicker than the MCP server starts. You may need to refresh the tools in Windsurf. However, Windsurf doesn't seem to handle refreshing more than once well. It just keeps spinning the refresh button.

**IMPORTANT**: Windsurf uses MCP tools without checking with the user by default. This is fine for 3 out of 4 of the Backseat Driver tools, but for the REPL tool it is less ideal. I think some Windsurf user should report this non-compliance with MCP as an issue.


#### Claude desktop

Claude Desktop doesn't run in VS Code, and doesn't have any other project/workspace concept, so it is the global config that you will use. The app has a button for finding its configuration file. Configuring to use the port for the stdio command is probably easiest.

```json
{
  "mcpServers": {
    "backseat-driver": {
      "command": "node",
      "args": [
        "<absolute path to calva-mcp-server.js in the extension folder>",
        "1664"
      ]
    }
  }
}
```

There doesn't seem to be a way to refresh/reload the server info, so if you started the Backseat Driver MCP server after Claude, you probably neeed to restart Claude to for the refresh to happen.

#### Other MCP client?

Please add configuration for other AI clients! 🙏

### Using

1. Connect Calva to your Clojure/ClojureScript project
1. If you want the AI to have full REPL powers, enable this in settings.

All tools can be referenced in the chat:

* `#eval-clojure`
* `#replace-top-level-form`
* `#insert-top-level-form`
* `#clojure-symbol`
* `#clojuredocs`
* `#calva-output`

## How It Works (evaluating code)

1. When your AI assistant needs to understand your code better, it can execute it in your REPL
2. The results flow back to the AI, giving it insight into actual data shapes and function behavior
3. This creates a powerful feedback loop where suggestions improve based on runtime information
4. You remain in control of this process, benefiting from an AI partner that truly understands your running code

```mermaid
flowchart TD
    subgraph InteractiveProgrammers["Interactive Programmers"]
        User([You])
        AIAgent([AI Agent])
        User <--> AIAgent
    end

    subgraph VSCode["VS Code"]

        MCP["Calva Backseat Driver"]

        subgraph Calva["Calva"]
            REPLClient["REPL Client"]
        end

        subgraph Project["Clojure Project"]

            subgraph RunningApp["Running Application"]
                SourceCode["Source Code"]
                REPL["REPL"]
            end
        end
    end

    User --> SourceCode
    User --> Calva
    REPLClient --> REPL
    AIAgent --> SourceCode
    AIAgent --> MCP
    MCP --> Calva

    classDef users fill:#ffffff,stroke:#63b132,stroke-width:1px,color:#63b132;
    classDef programmers fill:#63b132,stroke:#000000,stroke-width:2px,color:#ffffff;
    classDef vscode fill:#0078d7,stroke:#000000,stroke-width:1px,color:#ffffff;
    classDef calva fill:#df793b,stroke:#ffffff,stroke-width:1px,color:#ffffff;
    classDef highlight fill:#ffffff,stroke:#000000,stroke-width:1px,color:#000000;
    classDef dark fill:#333333,stroke:#ffffff,stroke-width:1px,color:#ffffff;
    classDef repl fill:#5881d8,stroke:#ffffff,stroke-width:1px,color:#ffffff;
    classDef running fill:#63b132,stroke:#ffffff,stroke-width:1px,color:#ffffff;
    classDef project fill:#888888,stroke:#ffffff,stroke-width:1px,color:#ffffff;

    class User,AIAgent users;
    class VSCode vscode;
    class Calva,MCP calva;
    class REPLClient repl;
    class Project project;
    class SourceCode dark;
    class RunningApp running;
    class REPL repl;
    class InteractiveProgrammers programmers;
```

### MCP

Calva Backseat Driver implements the [Model Context Protocol](https://modelcontextprotocol.io) (MCP), creating a bridge between AI assistants and your REPL:

## Alternatives

Some projects/tools to look to complement Backseat Driver, or use instead of it:

* [Clojure MCP](https://github.com/bhauman/clojure-mcp) - [Bruce Hauman](https://github.com/bhauman)'s take. A very comprehensive set of tools, resources, prompts and agents to use AI for generating more maintainable code than we could do without AI.
* [nREPL MCP Server](https://github.com/JohanCodinha/nrepl-mcp-server), gives the AI tools to connect to a running nREPL server and evaluate code (and more)
* [Babashka AI Coding Tools](https://github.com/nextdoc/ai-tools), Clojure test runner for AI agents.

## WIP

This is all super early, and bare bones and experimental.

Please let us now what features you would like to see.

## Contributing

Contributions are welcome! Issues, PRs, whatever. Before a PR, we appreciate an issue stating the problem being solved. You may also want to reach out discussing the issue before starting to work on it.

## License 🍻🗽

[MIT](LICENSE.txt)

## Sponsor my open source work ♥️

You are welcome to show me you like my work using this link:

* https://github.com/sponsors/PEZ
