# Model Context Protocol (MCP) Overview

## What is Model Context Protocol?

MCP is a protocol that enables AI language models (like Claude, GPT, etc.) to interact with external tools and resources. It creates a standardized way for AI assistants to:

1. **Discover capabilities** provided by servers
2. **Call functions** (tools) that execute on those servers
3. **Access resources** like files or API responses
4. **Use templates** (prompts) for specialized tasks

Instead of reiterating (badly) what MCP is here, let's take it from the horse's mouth:

* https://modelcontextprotocol.io/
* Calva Backseat Driver follows version `2024-11-05` of the protocol. There's a schema here: https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/refs/heads/main/schema/2024-11-05/schema.ts

## Calva Backseat Driver Implementation

For our Calva Backseat Driver project, we're building an MCP server that will expose Calva's capabilities (particularly REPL evaluation) to AI assistants. This means an AI will be able to:

1. Evaluate Clojure/ClojureScript code in a REPL
2. Get results back in a structured format
3. Use those results to help solve programming problems

See the project [README](../README.md) for the rationale.

## Key Components for Implementation

Based on the MCP specification, our server will need:

1. **Tool Definitions**: We'll define tools like `evaluate-code` that expose Calva's REPL functionality
2. **Tool Execution Logic**: The actual implementation that connects to Calva's API
3. **Resources**: Skills declared in `package.json` under `contributes.chatSkills` are exposed as MCP resources via `resources/list` and `resources/read` at `/skills/{name}/SKILL.md` URIs
4. **Dynamic Instructions**: The `initialize` response includes dynamically composed instructions reflecting available tools and skills
5. **Server Setup**: Code to initialize and run the MCP server

### Server Setup

Considerations:

* VS Code CoPilot only supports the **stdio** [Transport](https://modelcontextprotocol.io/docs/concepts/transports).
* There is no way to start the server inside the extension via a shell command (afaik).
* The user needs full control of wether the REPL is exposed via the MCP protocol or not.
* The Calva Backseat Driver needs API access to the Calva extension

We solve this by implementing the server running inside the Calva MCP Extension, using TCP sockets (a backend server). Then in front of that a node script witch which the AI Agent system can start an MCP **stdio** Transport “relay”/wrapper that the AI Agent uses.

* Backend server: [mcp/server.cljs](../src/calva_mcp_server/mcp/server.cljs)
* Relay/wrapper: [stdio/wrapper.cljs](../src/calva_mcp_server/stdio/wrapper.cljs)

### ClojureScript Implementation Approach

Note that for now we are trying to build an MCP server implementation from scratch, in ClojureScript, with as few npm dependencies (none so far) as we can get away with.

We build both the extension, it's MCP server, and the wrapper with ClojureScript, in Interactive Programming mode.

See also the [DEVELOPER_GUIDE](DEVELOPER_GUIDE.md), and the [PROJECT_REQUIREMENTS](PROJECT_REQUIREMENTS.md).