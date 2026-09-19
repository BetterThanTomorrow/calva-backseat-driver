(ns calva-backseat-driver.mcp.server
  (:require
   ["vscode" :as vscode]
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

(defn build-lifecycle-config
  "Builds a `vscode-mcp.core` config from current settings and BD's
   wrapper-install-dir/when-context conventions. Cheap to rebuild — callers
   don't need to cache it (see plan Decision Q6: settings are read fresh on
   each start/stop, same as the rest of BD's Ex config-keyword enrichment)."
  [dispatch! ^js context wrapper-config-path]
  (let [settings (vscode/workspace.getConfiguration "calva-backseat-driver")]
    (vscode-mcp/create-config
     {:vscode/extension-context context
      :cursor/server-name "calva-backseat-driver"
      :cursor/script-relative-path "dist/calva-mcp-server.js"
      :mcp/auto-start? (.get settings "autoStartMCPServer")
      :mcp/auto-register? (.get settings "autoRegisterCursorMcp")
      :mcp/auto-register-eca? (.get settings "autoRegisterEcaMcp")
      :manual-setup/extension-name "Backseat Driver"
      :server/host (.get settings "mcpHost")
      :mcp/on-request (fn [request]
                        (dispatch! context [[:mcp/ax.handle-request request]]))
      :mcp/on-log (fn [level & args]
                    (dispatch! context [[:app/ax.log level (apply str (interpose " " args))]]))
      :lifecycle/request-port (fn [_ctx {:lifecycle/keys [cursor-mode?]}]
                                (if cursor-mode? 0 (.get settings "mcpSocketServerPort")))
      :lifecycle/wrapper-install-dir wrapper-config-path
      :registry/enabled? (not (false? (.get settings "enableMcpRegistry")))
      :registry/custom-data+ registry-custom-data+
      :lifecycle/on-starting-changed (fn [starting?]
                                       (dispatch! context [[:app/ax.set-when-context :calva-backseat-driver/starting? starting?]]))
      :lifecycle/on-stopping-changed (fn [stopping?]
                                       (dispatch! context [[:app/ax.set-when-context :calva-backseat-driver/stopping? stopping?]]))
      :lifecycle/on-running-changed (fn [running? _server-info]
                                      (dispatch! context [[:app/ax.set-when-context :calva-backseat-driver/started? running?]
                                                          [:mcp/ax.sync-cursor-mcp-when-contexts]]))
      :lifecycle/on-cursor-registered (fn [result]
                                        (dispatch! context [[:mcp/ax.cursor-mcp-registered result]]))
      :lifecycle/on-cursor-registration-failed (fn [failure]
                                                 (dispatch! context [[:mcp/ax.cursor-mcp-registration-failed failure]]))
      :lifecycle/on-error (fn [err]
                            (dispatch! context [[:mcp/ax.server-error err]]))})))
