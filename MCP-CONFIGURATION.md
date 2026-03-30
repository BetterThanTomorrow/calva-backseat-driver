# Configur Backseat Driver as an MCP Server

Backseat Driver is both a Copilot native plugin (no MCP needed) and an MCP server (for other AI/Agent harnesses).

> [!NOTE]
> I use Copilot, and can truly recommend it. I don't know much about MCP configuration. The information here was mostly written by me, after some quick testing (often long ago.)
>
> If you use Backseat Driver as an MCP server, please help in maintaining this document. 🙏

## Configuration

> [!NOTE]
> The MCP server defaults to evaluation being disabled. Search for *Backseat Driver* in VS Code Settings to enable it.
>
> Note that there are several layers to the security model here. Compliant MCP servers will default to low trust mode and ask for your confirmation every time the LLM wants to use the tool. Full YOLO mode is enabled if you enable the tool in the Calva MCP settings, and configure your AI client to be allowed to use it without asking.

The MCP server is running as a plain socket server in the VS Code Extension Host, writing out a port file when it starts. Then the MCP client needs to start a `stdio` relay/proxy/wrapper. The wrapper script takes the port or a port file as an argument. Because of these and other reasons, there will be one Calva Backseat Driver per workspace, and the port file will be written to the `.calva` directory in the workspace root.
* The default port for the socket server is `1664`. If that is not available, a random, high, available port number will be used.
* You can configure the try-first port to something else via the setting `calva-backseat-driver.mcpSocketServerPort`. Use `0` to use a random, high, available port number.

1. Open your project
1. Start the Calva MCP socket server
   * This will create a port file: `${workspaceFolder}/.calva/mcp-server/port`
   * When the server is started, a confirmation dialog will be shown. This dialog has a button which lets you copy the command for starting the stdio wrapper to the clipboard.

     ![MCP Server Started message with Copy Command button](assets/howto/mcp-copy-stdio-command.png)
1. Add the MCP server config (will vary depending on MCP Client)

### Workspace/project level config

Backseat Driver is a per-project MCP server, so should be configured on the project level if the assistant you are using allows it.

* **If you can use project/workspace level configuration**, then it is best to use the **port file** as the argument to the stdio script. If your MCP client doesn't support project level configuration.
* **If your MCP client does not support project/workspace configuration**, then you can still configure Backseat Driver to use different socket server ports for different projects. And then configure your MCP client with the appropriate port number for your session.

I am sorry that this is a bit messy. It is obvious that MCP is a bit new, and that project level MCP servers may not have been considered when creating MCP. I have tried to understand and navigate the limitations and to provide configurability/Ux to the best of my understanding and ability.

### Cursor configuration

[Cursor](https://www.cursor.com/) supports project level config.

In your project's `.cursor/mcp.json` add a `"backseat-driver"` entry like so:
```json
{
  "mcpServers": {
    "backseat-driver": {
      "command": "node",
      "args": [
  "<absolute path to calva-mcp-server.js in user-home-config directory>",
        "<absolute path to port file (which points to your project's .calva/mcp-server/port)"
      ]
    }
  }
}
```
Both absolute paths needed above can be conveniently determined by clicking on the **Copy command** button (shown when starting the MCP server) and then pasting into `mcp.json` file, as described above: [Configuration](#configuration-if-using-mcp-server).


Cursor will detect the server config and offer to start it.

You may want to check the [Cursor MCP docs](https://docs.cursor.com/chat/tools#mcp-servers).

### Windsurf configuration

[Windsurf](https://windsurf.com/) can use the Backseat Driver via its MCP server. However, it is a bit clunky, to say the least. Windsurf doesn't support workspace configurations for MCP servers, so they are only global. This means:

* You can in practice only have one Backseat Driver backed project
* It's probably best to use the port number as the argument for the stdio command
* If you use the port file as the argument, it must be an absolute path (afaik)

Windsurf's configuration file has the same shape as Cursor's, located at: `~/.codeium/windsurf/mcp_config.json` (at least on my machine).

The Windsurf AI assistant doesn't know about its MCP configurations and will keep trying to create MCP configs for Copilot. Which is silly, because it won't work for Windsurf, and Copilot doesn't need it.

**Clunk**: At startup, even with the MCP server set to auto-start, Windsurf often refreshes its MCP servers quicker than the MCP server starts. You may need to refresh the tools in Windsurf. However, Windsurf doesn't seem to handle refreshing more than once well. It just keeps spinning the refresh button.

**IMPORTANT**: Windsurf uses MCP tools without checking with the user by default. This is fine for 3 out of 4 of the Backseat Driver tools, but for the REPL tool it is less ideal. I think some Windsurf user should report this non-compliance with MCP as an issue.


### Claude desktop

Claude Desktop doesn't run in VS Code, and doesn't have any other project/workspace concept, so it is the global config that you will use. The app has a button for finding its configuration file. Configuring to use the port for the stdio command is probably easiest.

```json
{
  "mcpServers": {
    "backseat-driver": {
      "command": "node",
      "args": [
  "<absolute path to calva-mcp-server.js in user-home-config directory>",
        "1664"
      ]
    }
  }
}
```

There doesn't seem to be a way to refresh/reload the server info, so if you started the Backseat Driver MCP server after Claude, you probably need to restart Claude for the refresh to happen.

### Antigravity

- open command palette
- enter: MCP Add Server
- select "Command: (stdio)"
- enter the full command "/path/to/node /path/to/calva-mcp-server.js port-number"

a config file will be created in `.vscode/mcp_config.json`, but for some reason it won't be available to the agents.

since this per workspace approach failed, we go for the global config approach, which works:

- open the agents tab
- click at the `...` at the top right
- click on "MCP Servers"
- click on "Manage MCP Servers"
- click on "View Raw Config"
- copy and paste the content of `.vscode/mcp_config.json`
- at the "Manage MCP Servers" window click on "Refresh" and it should be set.

### Other MCP client?

Please add configuration for other AI clients! 🙏