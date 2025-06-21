# Human Intelligence Tool - Design Document

## Overview

This tool that allows AI agents to request human input during task execution. The AI provides context in the chat, then uses this tool to prompt the human for specific guidance via a VS Code input box.

## Core Concept

**Value Proposition**: Enable AI agents to keep the human in the loop without loosing sight on the current task. Leveraging  the chat for context.

### The Flow

```
AI Agent Working → Needs Input → Explains Context in Chat → Calls Tool → VS Code Input Box → Human Responds → AI Continues
```

## Usage Pattern

The AI agent would:

1. **Explain the situation in chat**: "I'm implementing user authentication and need to choose between JWT tokens or session-based auth. The app will have about 100 users and needs to integrate with your existing infrastructure."
2. **Call the tool** with just the question:
   ```clojure
   {:tool "request_human_input"
    :parameters {:prompt "Which authentication approach should I use?"}}
   ```
3. **Human sees VS Code input box** with:
   - Title: "AI Agent needs input"
   - Prompt: "Which authentication approach should I use?"
   - Placeholder: "Start typing to cancel auto-dismiss (60s timeout)..."
4. **Human responds**: "Use JWT with our existing auth service"
5. **AI continues**: "Great! I'll implement JWT integration with your auth service..."

## Tool Interface

### Parameters
- `prompt` (required): The question to ask the human

### Built-in UX Handling
The tool automatically:
- Uses a standard placeholder: "Start typing to cancel auto-dismiss (60s timeout)..."
- Auto-dismisses after 60 seconds if no human activity
- **Cancels the timeout** as soon as the human starts typing
- Returns `:timeout->what-would-rich-hickey-do` if timeout occurs (indicating no human response)
- AI agent can detect timeout and continue reminded about Rich Hickey's approach to problem solving

## Implementation

### VS Code Integration
- Use `vscode.window.createInputBox()` for full control with built-in smart timeout
- Built-in 60-second timeout that cancels when user starts typing
- Standard placeholder: "Start typing to cancel auto-dismiss (60s timeout)..."
- Simple title: "AI Agent needs input"
- Display the prompt as helper text below the input field
- **Prompt limitations**: Plain text only, single line (no markdown, no line breaks), but can be quite long

### MCP Tool Schema
```json
{
  "name": "request_human_input",
  "tags": ["information", "knowledge", "lookup"],
  "displayName": "Human Intelligence",
  "toolReferenceName": "human-intelligence"
  "modelDescription": "Ask the human developer for input or guidance. Need to think about a thing together with someone? Would you benefit from clarification? Is there some domain knowledge you need? Use this tool to request input from the human.\n\n## Tool flow\nAI Agent Working → Agent Needs Input → Agents Explains Context in Chat → Calls Tool → VS Code Input Box → Human Responds → AI Continues, better informed.",
  "userDescription": "The AI asks the human developer for input or guidance",
  "inputSchema": {
    "type": "object",
    "properties": {
      "prompt": {
        "type": "string",
        "description": "The question to ask the human. First provide context in the chat, this prompt is for framing the question."
      }
    },
    "required": ["prompt"]
  }
}
```

