(ns calva-backseat-driver.mcp.server
  (:require
   [calva-backseat-driver.integrations.calva.api :as calva]
   [calva-backseat-driver.integrations.calva.session-runtimes :as session-runtimes]
   [promesa.core :as p]
   [vscode-mcp.core :as vscode-mcp]))

(defn- registry-custom-data+
  [_state]
  (-> (p/let [list-fn (get-in calva/calva-api [:repl :listSessionsAndRuntimes])
              sessions-js (when list-fn (list-fn))
              sessions (or (js->clj sessions-js :keywordize-keys true) [])]
        {:sessions (mapv session-runtimes/compact-registry-session sessions)})
      (p/catch (fn [_] {:sessions []}))))

(defn- respond-to-request+
  "Dispatches the MCP `request`. Returns a promise of the response for a request with
   an id, and nil for a notification."
  [dispatch! request]
  (let [response+ (dispatch! [[:mcp/ax.handle-request request]])]
    (when (some? (:id request))
      ;; vscode-mcp's socket code recognizes promesa promises, so the response is one
      (p/create (fn [resolve reject]
                  (.then response+ resolve reject))))))

(defn build-lifecycle-config
  "Returns the `vscode-mcp.core` config for the extension `context`, with the wrapper
   installed in `wrapper-config-path` and the settings from `settings`."
  [dispatch! ^js context wrapper-config-path settings]
  (vscode-mcp/create-config
   {:vscode/extension-context context
    :cursor/server-name "calva-backseat-driver"
    :cursor/script-relative-path "dist/calva-mcp-server.js"
    :mcp/auto-start? (:vscode/config.autoStartMCPServer settings)
    :mcp/auto-register? (:vscode/config.autoRegisterCursorMcp settings)
    :mcp/auto-register-eca? (:vscode/config.autoRegisterEcaMcp settings)
    :manual-setup/extension-name "Backseat Driver"
    :server/host (:vscode/config.mcpHost settings)
    :mcp/on-request (fn [request]
                      (respond-to-request+ dispatch! request))
    :mcp/on-log (fn [level & args]
                  (dispatch! [[:app/ax.log level (apply str (interpose " " args))]]))
    :lifecycle/request-port (fn [_ctx {:lifecycle/keys [cursor-mode?]}]
                              (if cursor-mode? 0 (:vscode/config.mcpSocketServerPort settings)))
    :lifecycle/wrapper-install-dir wrapper-config-path
    :registry/enabled? (not (false? (:vscode/config.enableMcpRegistry settings)))
    :registry/custom-data+ registry-custom-data+
    :lifecycle/on-starting-changed (fn [starting?]
                                     (dispatch! [[:app/ax.set-when-context :calva-backseat-driver/starting? starting?]]))
    :lifecycle/on-stopping-changed (fn [stopping?]
                                     (dispatch! [[:app/ax.set-when-context :calva-backseat-driver/stopping? stopping?]]))
    :lifecycle/on-running-changed (fn [running? _server-info]
                                    (dispatch! [[:app/ax.set-when-context :calva-backseat-driver/started? running?]
                                                [:mcp/ax.sync-cursor-mcp-when-contexts]]))
    :lifecycle/on-cursor-registered (fn [result]
                                      (dispatch! [[:mcp/ax.cursor-mcp-registered result]]))
    :lifecycle/on-cursor-registration-failed (fn [failure]
                                               (dispatch! [[:mcp/ax.cursor-mcp-registration-failed failure]]))
    :mcp/on-error (fn [err]
                    (dispatch! [[:mcp/ax.server-error (str err)]]))}))
