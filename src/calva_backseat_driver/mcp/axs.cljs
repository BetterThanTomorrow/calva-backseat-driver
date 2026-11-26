(ns calva-backseat-driver.mcp.axs
  (:require
   [calva-backseat-driver.integrations.calva.session-runtimes :as session-runtimes]
   [calva-backseat-driver.integrations.vscode.settings :as settings]))

(defn- lifecycle-options
  "What vscode-mcp's config is built from: the wrapper's directory, the MCP settings,
   and Calva's sessions for the window's registry entry."
  [state]
  {:mcp/wrapper-config-path (:mcp/wrapper-config-path state)
   :mcp/settings (select-keys state [:vscode/config.autoStartMCPServer
                                     :vscode/config.autoRegisterCursorMcp
                                     :vscode/config.autoRegisterEcaMcp
                                     :vscode/config.mcpHost
                                     :vscode/config.mcpSocketServerPort
                                     :vscode/config.enableMcpRegistry])
   :mcp/registry-data {:sessions (mapv session-runtimes/compact-registry-session
                                       (:calva/sessions state))}})

(defn- log-options [state]
  (select-keys state [:app/min-log-level :app/log-file-uri]))

(defn- news
  "Returns the value of `k` in `server-state` when it has one that `state` doesn't have."
  [state server-state k]
  (let [v (get server-state k)]
    (when (and v
               (not= v (get state k)))
      v)))

(defn- server-changed
  "Takes the MCP server's state, as vscode-mcp reports it when it changes. A start that
   failed is shown to the person. A registration with Cursor is logged, and so is one
   that failed."
  [state _uf-data server-state]
  (let [error (news state server-state :server/error)
        registered-name (news state server-state :lifecycle/registered-name)
        registration-error (news state server-state :lifecycle/registration-error)]
    {:uf/db (merge state server-state)
     :uf/fxs [(when error
                [:node/fx.log-error "MCP server error:" error])
              (when error
                [:vscode/fx.show-error-message (str "MCP server error: " error)])
              (when registered-name
                [:app/fx.log (log-options state) :info ["Cursor MCP server registered:" registered-name]])
              (when registration-error
                [:app/fx.log (log-options state) :warn ["Cursor MCP auto-registration failed:" registration-error]])]}))

(defn- start-server
  "Starts the MCP server. With `:silent?` it starts only when the settings ask for it,
   and shows no message. The dispatch resolves to the server's state, or to nil when no
   server runs after it."
  [state _uf-data & [{:keys [silent?]}]]
  {:uf/fxs [[:uf/await :mcp/fx.lifecycle-start (assoc (lifecycle-options state)
                                                      :lifecycle/silent? silent?)]]
   :uf/dxs [[:mcp/ax.lifecycle-updated :uf/prev-result]]})

(defn- lifecycle-updated
  "Takes the `server-state` that a start or a registration resolved to. The dispatch
   resolves to it, or to nil when no server runs. A server that runs gets Calva's
   sessions for its registry entry once more, since they may have changed while it started."
  [state _uf-data {:server/keys [running?] :as server-state}]
  {:uf/db (merge state server-state)
   :uf/fxs [[:uf/await :app/fx.return (when running?
                                        server-state)]]
   :uf/dxs (when running?
             [[:mcp/ax.update-registry]])})

(defn- stop-server
  "Stops the MCP server. The dispatch resolves to true."
  [state _uf-data & [options]]
  {:uf/fxs [[:uf/await :mcp/fx.lifecycle-stop (merge (lifecycle-options state) options)]]
   :uf/dxs [[:mcp/ax.lifecycle-stopped :uf/prev-result]]})

(defn- lifecycle-stopped
  "Takes the `server-state` that a stop resolved to. The dispatch resolves to true."
  [state _uf-data server-state]
  {:uf/db (merge state server-state)
   :uf/fxs [[:uf/await :app/fx.return true]]})

(defn- register-with-cursor
  [state _uf-data]
  {:uf/fxs [[:uf/await :mcp/fx.register-with-cursor (lifecycle-options state)]]
   :uf/dxs [[:mcp/ax.cursor-registration-finished :uf/prev-result]]})

(defn- registration-failure-message [reason]
  (case reason
    :cursor-api-unavailable "Cursor MCP registration API is not available in this editor."
    "Could not register Backseat Driver MCP server with Cursor."))

(defn- cursor-registration-finished
  "Takes the `server-state` that registering with Cursor on request resolved to. A
   registration that failed is shown to the person. The dispatch resolves as a start does
   when the server is registered."
  [state _uf-data server-state]
  (if-let [{:keys [reason]} (:lifecycle/registration-error server-state)]
    {:uf/db (merge state server-state)
     :uf/fxs [[:vscode/fx.show-information-message (registration-failure-message reason)]]}
    {:uf/dxs [[:mcp/ax.lifecycle-updated server-state]]}))

(defn- open-server-log
  "Opens the log file in an editor. The dispatch resolves when it is shown."
  [state _uf-data]
  {:uf/fxs [[:uf/await :vscode/fx.show-text-document-at (:app/log-file-uri state)]]})

(defn- handle-request
  "Answers the MCP `request`. The dispatch resolves to the response."
  [state _uf-data request]
  {:uf/fxs [[:uf/await :mcp/fx.handle-request
             {:mcp/manifest (:extension/manifest state)
              :mcp/repl-enabled? (:vscode/config.enableMcpReplEvaluation state)
              :mcp/when-clause-settings (settings/when-clause-settings state)}
             request]]})

(defn- update-registry
  "Hands the window's registry entry its data again, after Calva's sessions changed."
  [state _uf-data]
  {:uf/fxs [[:mcp/fx.update-registry (lifecycle-options state)]]})

(def actions
  {:mcp/ax.server-changed #'server-changed
   :mcp/ax.start-server #'start-server
   :mcp/ax.lifecycle-updated #'lifecycle-updated
   :mcp/ax.stop-server #'stop-server
   :mcp/ax.lifecycle-stopped #'lifecycle-stopped
   :mcp/ax.register-with-cursor #'register-with-cursor
   :mcp/ax.cursor-registration-finished #'cursor-registration-finished
   :mcp/ax.open-server-log #'open-server-log
   :mcp/ax.handle-request #'handle-request
   :mcp/ax.update-registry #'update-registry})
