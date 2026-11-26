(ns calva-backseat-driver.mcp.axs
  (:require
   [calva-backseat-driver.integrations.vscode.settings :as settings]
   [vscode-mcp.core :as vscode-mcp]))

(defn- lifecycle-options
  "What the lifecycle effects need from the state."
  [state]
  {:mcp/wrapper-config-path (:mcp/wrapper-config-path state)
   :mcp/lifecycle-state (:mcp/lifecycle-state state)
   :mcp/settings (select-keys state [:vscode/config.autoStartMCPServer
                                     :vscode/config.autoRegisterCursorMcp
                                     :vscode/config.autoRegisterEcaMcp
                                     :vscode/config.mcpHost
                                     :vscode/config.mcpSocketServerPort
                                     :vscode/config.enableMcpRegistry])})

(defn- log-options [state]
  (select-keys state [:app/min-log-level :app/log-file-uri]))

(defn- start-server
  "Starts the MCP server. With `:silent?` it starts only when the settings ask for it,
   and shows no message. The dispatch resolves to the server info."
  [state _uf-data & [{:keys [silent?]}]]
  {:uf/fxs [[:uf/await :mcp/fx.lifecycle-start (assoc (lifecycle-options state)
                                                      :lifecycle/silent? silent?)]]
   :uf/dxs [[:mcp/ax.lifecycle-updated :uf/prev-result]]})

(defn- sync-cursor-mcp-when-contexts
  [state _uf-data]
  (let [lifecycle (:mcp/lifecycle-state state)
        server-running? (boolean (vscode-mcp/server-info lifecycle))]
    {:uf/dxs [[:app/ax.set-when-context :calva-backseat-driver/cursor-mcp-available?
               (:mcp/cursor-mcp-available? state)]
              [:app/ax.set-when-context :calva-backseat-driver/mcp-server-registered-with-cursor?
               (and server-running?
                    (vscode-mcp/cursor-registered? lifecycle))]]}))

(defn- lifecycle-updated
  [state _uf-data lifecycle-state]
  {:uf/db (assoc state :mcp/lifecycle-state lifecycle-state)
   :uf/fxs [[:uf/await :app/fx.return (clj->js (vscode-mcp/server-info lifecycle-state))]]
   :uf/dxs [[:mcp/ax.sync-cursor-mcp-when-contexts]]})

(defn- stop-server
  "Stops the MCP server. The dispatch resolves to true."
  [state _uf-data & [options]]
  {:uf/fxs [[:uf/await :mcp/fx.lifecycle-stop (merge (lifecycle-options state) options)]]
   :uf/dxs [[:mcp/ax.lifecycle-stopped :uf/prev-result]]})

(defn- lifecycle-stopped
  [state _uf-data lifecycle-state]
  {:uf/db (assoc state :mcp/lifecycle-state lifecycle-state)
   :uf/fxs [[:uf/await :app/fx.return true]]
   :uf/dxs [[:mcp/ax.sync-cursor-mcp-when-contexts]]})

(defn- register-with-cursor
  [state _uf-data]
  {:uf/fxs [[:uf/await :mcp/fx.register-with-cursor (lifecycle-options state)]]
   :uf/dxs [[:mcp/ax.cursor-registration-finished :uf/prev-result]]})

(defn- registration-failure-message [reason]
  (case reason
    :cursor-api-unavailable "Cursor MCP registration API is not available in this editor."
    "Could not register Backseat Driver MCP server with Cursor."))

(defn- cursor-registration-finished
  "Takes what registering with Cursor on request resolved to: `:ok` with the new
   lifecycle `:state`, a `:reason` it didn't register, or the `:error` it threw."
  [_state _uf-data {:keys [ok reason error] lifecycle-state :state :as result}]
  (cond
    ok
    {:uf/dxs [[:mcp/ax.lifecycle-updated lifecycle-state]]}

    error
    {:uf/dxs [[:app/ax.log :error "[Cursor MCP] register-with-cursor error:" error]
              [:mcp/ax.cursor-mcp-registration-failed error]]}

    :else
    {:uf/fxs [[:vscode/fx.show-information-message (registration-failure-message reason)]]
     :uf/dxs [[:mcp/ax.cursor-mcp-registration-failed result]]}))

(defn- cursor-mcp-registered
  [state _uf-data result]
  {:uf/fxs [[:app/fx.log (log-options state) :info ["Cursor MCP server registered:" result]]]
   :uf/dxs [[:mcp/ax.sync-cursor-mcp-when-contexts]]})

(defn- cursor-mcp-registration-failed
  [state _uf-data failure]
  {:uf/fxs [[:app/fx.log (log-options state) :warn ["Cursor MCP auto-registration failed:" failure]]]
   :uf/dxs [[:mcp/ax.sync-cursor-mcp-when-contexts]]})

(defn- open-server-log
  [state _uf-data]
  {:uf/fxs [[:vscode/fx.show-text-document-at (:app/log-file-uri state)]]})

(defn- server-error
  "Tells the person that the MCP server failed, with `message`."
  [_state _uf-data message]
  {:uf/fxs [[:node/fx.log-error "MCP server error:" message]
            [:vscode/fx.show-error-message (str "MCP server error: " message)]]})

(defn- handle-request
  "Answers the MCP `request`. The dispatch resolves to the response."
  [state _uf-data request]
  {:uf/fxs [[:uf/await :mcp/fx.handle-request
             {:mcp/repl-enabled? (:vscode/config.enableMcpReplEvaluation state)
              :mcp/when-clause-settings (settings/when-clause-settings state)}
             request]]})

(defn- update-registry
  [state _uf-data]
  {:uf/fxs [[:mcp/fx.update-registry (lifecycle-options state)]]})

(def actions
  {:mcp/ax.start-server #'start-server
   :mcp/ax.lifecycle-updated #'lifecycle-updated
   :mcp/ax.sync-cursor-mcp-when-contexts #'sync-cursor-mcp-when-contexts
   :mcp/ax.stop-server #'stop-server
   :mcp/ax.lifecycle-stopped #'lifecycle-stopped
   :mcp/ax.register-with-cursor #'register-with-cursor
   :mcp/ax.cursor-registration-finished #'cursor-registration-finished
   :mcp/ax.cursor-mcp-registered #'cursor-mcp-registered
   :mcp/ax.cursor-mcp-registration-failed #'cursor-mcp-registration-failed
   :mcp/ax.open-server-log #'open-server-log
   :mcp/ax.server-error #'server-error
   :mcp/ax.handle-request #'handle-request
   :mcp/ax.update-registry #'update-registry})
