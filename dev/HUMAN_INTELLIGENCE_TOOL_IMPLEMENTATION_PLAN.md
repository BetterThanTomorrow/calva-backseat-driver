# Human Intelligence Tool - Implementation Plan

## Overview

This document provides a detailed implementation plan for the Human Intelligence Tool that allows AI agents to request human input during task execution. The tool leverages VS Code's input box API with intelligent timeout handling and seamless integration with the existing Ex architecture.

## Architecture Analysis

The Calva Backseat Driver uses an Ex architecture (functional core/imperative shell) with:
- **Actions (`:ex/axs`)**: Pure data structures representing operations
- **Effects (`:ex/fxs`)**: Data structures representing controlled side effects
- **Action Enrichment**: Runtime transformation of data structures with context
- **Unidirectional Flow**: Actions → Enrichment → Handlers → Effects → State Updates

The MCP server processes tool calls through `tools/call` requests, where each tool is handled as a conditional branch in the request handler.

## Technical Approach

### 1. VS Code Input Box Integration
- Use `vscode.window.createInputBox()` for full control over the input experience
- Implement smart timeout (60 seconds) that cancels when user starts typing
- Standard UX with consistent placeholder text and styling

### 2. Ex Architecture Integration
- Create new VS Code effect for input box creation: `[:vscode/fx.create-input-box options]`
- Leverage existing `:ex/then` pattern for promise-based async handling
- Use action enrichment with `:ex/action-args` for result passing

### 3. MCP Tool Registration
- Add tool to `package.json` languageModelTools configuration
- Implement tool listing in `mcp/requests.cljs`
- Add tool execution handler in the `tools/call` conditional chain

## Implementation Steps

### Step 1: VS Code Effect Handler
**File**: `src/calva_backseat_driver/integrations/vscode/fxs.cljs`

Add new effect handler to the `match` statement:

```clojure
[:vscode/fx.request-human-input options]
(request-human-input! dispatch! context options)
```

Add helper function (private defn):

```clojure
(defn- request-human-input! [dispatch! context {:keys [prompt callback]}]
  (let [input-box (vscode/window.createInputBox)
        timeout-id (volatile! nil)]
    (setup-input-box! input-box prompt)
    (setup-timeout! input-box timeout-id callback)
    (setup-event-handlers! input-box timeout-id dispatch! context callback)
    (.show input-box)))

(defn- setup-input-box! [input-box prompt]
  (set! (.-title input-box) "AI Agent needs input")
  (set! (.-prompt input-box) prompt)
  (set! (.-placeholder input-box) "Start typing to cancel auto-dismiss (60s timeout)..."))

(defn- setup-timeout! [input-box timeout-id callback]
  (vreset! timeout-id
           (js/setTimeout #(do (.hide input-box)
                               (callback ":timeout->what-would-rich-hickey-do"))
                          60000)))

(defn- setup-event-handlers! [input-box timeout-id _dispatch! _context callback]
  (.onDidChangeValue input-box (fn [_] (when @timeout-id (js/clearTimeout @timeout-id))))
  (.onDidAccept input-box (fn []
                            (let [value (.-value input-box)]
                              (.hide input-box)
                              (callback (if (string/blank? value)
                                         ":timeout->what-would-rich-hickey-do"
                                         value)))))
  (.onDidHide input-box (fn []
                          (when (string/blank? (.-value input-box))
                            (callback ":timeout->what-would-rich-hickey-do")))))
```

### Step 2: Package.json Tool Registration
**File**: `package.json`

Add to the `languageModelTools` array:

```json
{
  "name": "request_human_input",
  "tags": ["information", "knowledge", "lookup"],
  "toolReferenceName": "human-intelligence",
  "displayName": "Human Intelligence",
  "modelDescription": "Ask the human developer for input or guidance. Need to think about a thing together with someone? Would you benefit from clarification? Is there some domain knowledge you need? Use this tool to request input from the human.\n\n## Tool flow\nAI Agent Working → Agent Needs Input → Agents Explains Context in Chat → Calls Tool → VS Code Input Box → Human Responds → AI Continues, better informed.",
  "userDescription": "The AI asks the human developer for input or guidance",
  "canBeReferencedInPrompt": true,
  "icon": "$(person)",
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

### Step 3: MCP Tool Listing
**File**: `src/calva_backseat_driver/mcp/requests.cljs`

Add tool listing definition:

```clojure
(def human-input-tool-listing
  (let [tool-name "request_human_input"]
    {:name tool-name
     :description (tool-description tool-name)
     :inputSchema {:type "object"
                   :properties {"prompt" {:type "string"
                                          :description (param-description tool-name "prompt")}}
                   :required ["prompt"]
                   :audience ["user" "assistant"]
                   :priority 7}}))
```

Update the `tools/list` response to include the new tool:

```clojure
;; In the tools/list handler, add to the tools vector:
human-input-tool-listing
```

### Step 4: MCP Tool Execution
**File**: `src/calva_backseat_driver/mcp/requests.cljs`

Add tool execution handler in the `tools/call` conditional chain:

```clojure
(= tool "request_human_input")
(p/create
  (fn [resolve-fn _reject-fn]
    (let [{:keys [prompt]} arguments]
      (dispatch! [[:vscode/ax.request-human-input
                   {:prompt prompt
                    :callback (fn [result]
                               (resolve-fn {:jsonrpc "2.0"
                                           :id id
                                           :result {:content [{:type "text"
                                                              :text result}]}}))}]]))))
```

### Step 5: VS Code Action Handler
**File**: `src/calva_backseat_driver/integrations/vscode/axs.cljs`

Add action handler that triggers the effect:

```clojure
[:vscode/ax.request-human-input options]
{:ex/fxs [[:vscode/fx.request-human-input options]]}
```

## Alternative Implementation Approaches

### Approach A: Pure Ex Architecture Integration
- Create actions for human input request: `[:human-input/ax.request prompt]`
- Create effects for input box display: `[:vscode/fx.create-input-box options]`
- Use standard `:ex/then` patterns for response handling
- **Pros**: Consistent with Ex architecture, testable, pure data flow
- **Cons**: More complex, requires state management for pending requests

### Approach B: Direct MCP Integration (Recommended)
- Handle input box creation directly in MCP request handler
- Use promise-based approach with callback functions
- Minimal state management, direct VS Code API usage
- **Pros**: Simpler implementation, direct integration, fewer moving parts
- **Cons**: Less consistent with Ex patterns, harder to test

## Testing Strategy

### Unit Tests
- Test timeout behavior with mock VS Code API
- Test input validation and response formatting
- Test error handling for edge cases

### Integration Tests
- Test full MCP tool flow from request to response
- Test VS Code command registration and execution
- Test user interaction flows with actual input boxes

### REPL Testing
Use REPL-driven development to test individual components:

```clojure
;; Test input box creation
(evaluate-clojure-code
  "(vscode/commands.executeCommand \"calva-backseat-driver.requestHumanInput\"
                                   #js {:prompt \"Test question?\"
                                        :callback (fn [result] (js/console.log \"Result:\" result))})"
  "user"
  "cljs")

;; Test MCP tool listing
(evaluate-clojure-code
  "(require '[calva-backseat-driver.mcp.requests :as req])
   (req/human-input-tool-listing)"
  "calva-backseat-driver.mcp.requests"
  "cljs")
```

## Error Handling

### Timeout Scenarios
- Return `:timeout->what-would-rich-hickey-do` for timeouts
- AI agents can detect this response and continue with Rich Hickey's problem-solving approach
- Log timeout events for debugging and usage analytics

### Input Validation
- Handle empty/blank inputs as cancellation
- Sanitize input for JSON serialization
- Validate prompt parameter exists and is non-empty

### VS Code API Errors
- Graceful fallback if input box creation fails
- Error responses following MCP protocol
- Logging for debugging integration issues

## Data Flow Specification

```
1. AI Agent → MCP Request: tools/call "request_human_input" {prompt: "..."}
2. MCP Handler → VS Code Command: calva-backseat-driver.requestHumanInput
3. VS Code Command → Input Box: createInputBox() with timeout logic
4. Human Interaction → Input Box: typing cancels timeout
5. Input Box → Callback: user input or timeout sentinel
6. Callback → MCP Response: JSON-RPC result with text content
7. MCP Response → AI Agent: continues with human input
```

## Performance Considerations

### Memory Management
- Properly dispose of input box event listeners
- Clear timeouts to prevent memory leaks
- Limit concurrent human input requests

### User Experience
- Non-blocking input collection
- Clear visual feedback during timeout countdown
- Responsive cancellation when user starts typing

## Future Enhancements

### Multi-Choice Support
- Extend schema to support multiple choice options
- Use `vscode.window.showQuickPick` for selection UIs
- Maintain backwards compatibility with text input

### Rich Context Display
- Show context in a dedicated panel or notification
- Support markdown formatting in prompts
- Link to relevant files or code locations

### Analytics and Learning
- Track usage patterns and common questions
- Identify opportunities for automation
- Improve timeout defaults based on usage data

## Conclusion

This implementation plan provides a pragmatic approach to adding human intelligence capabilities to the Calva Backseat Driver. By leveraging existing VS Code APIs and integrating with the MCP protocol, we create a seamless experience for AI agents to request human guidance while maintaining the project's functional programming principles and data-oriented design.

The implementation prioritizes simplicity and reliability while providing clear extension points for future enhancements. The REPL-driven development approach ensures each component can be tested and refined incrementally.
