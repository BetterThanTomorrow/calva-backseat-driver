(ns calva-backseat-driver.mcp.axs
  (:require
   [calva-backseat-driver.integrations.vscode.settings :as settings]))

(defn- lifecycle-options
  "What the lifecycle effects need from the state."
  [state]
  {:mcp/wrapper-config-path (:mcp/wrapper-config-path state)
   :mcp/settings (select-keys state [:vscode/config.autoStartMCPServer
                                     :vscode/config.autoRegisterCursorMcp
                                     :vscode/config.autoRegisterEcaMcp
                                     :vscode/config.mcpHost
                                     :vscode/config.mcpSocketServerPort
                                     :vscode/config.enableMcpRegistry])})

(defn- log-options [state]
  (select-keys state [:app/min-log-level :app/log-file-uri]))

(defn- server-changed
  "Takes `server-state`, what changed about the MCP server: whether it runs, starts or
   stops, where it listens, and the name it is registered with in Cursor."
  [state _uf-data server-state]
  {:uf/db (merge state server-state)})

(defn- start-server
  "Starts the MCP server. With `:silent?` it starts only when the settings ask for it,
   and shows no message. The dispatch resolves to the server info."
  [state _uf-data & [{:keys [silent?]}]]
  {:uf/fxs [[:uf/await :mcp/fx.lifecycle-start (assoc (lifecycle-options state)
                                                      :lifecycle/silent? silent?)]]
   :uf/dxs [[:mcp/ax.lifecycle-updated :uf/prev-result]]})

(defn- lifecycle-updated
  "Takes the `server-state` that a start or a registration resolved to. The dispatch
   resolves to the server info, nil when no server runs."
  [state _uf-data server-state]
  {:uf/db (merge state server-state)
   :uf/fxs [[:uf/await :mcp/fx.server-info]]})

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
  "Takes what registering with Cursor on request resolved to: `:ok`, a `:reason` it
   didn't register, or the `:error` it threw, and the `:server/state` after it. A
   registration starts the server when it isn't running, so the state takes the
   server's state also when the registration failed."
  [state _uf-data {:keys [ok reason error] server-state :server/state :as result}]
  (cond
    ok
    {:uf/dxs [[:mcp/ax.lifecycle-updated server-state]]}

    error
    {:uf/dxs [[:app/ax.log :error "[Cursor MCP] register-with-cursor error:" error]
              [:mcp/ax.cursor-mcp-registration-failed error]]}

    :else
    {:uf/db (merge state server-state)
     :uf/fxs [[:vscode/fx.show-information-message (registration-failure-message reason)]]
     :uf/dxs [[:mcp/ax.cursor-mcp-registration-failed (dissoc result :server/state)]]}))

(defn- cursor-mcp-registered
  [state _uf-data result]
  {:uf/fxs [[:app/fx.log (log-options state) :info ["Cursor MCP server registered:" result]]]})

(defn- cursor-mcp-registration-failed
  [state _uf-data failure]
  {:uf/fxs [[:app/fx.log (log-options state) :warn ["Cursor MCP auto-registration failed:" failure]]]})

(defn- open-server-log
  "Opens the log file in an editor. The dispatch resolves when it is shown."
  [state _uf-data]
  {:uf/fxs [[:uf/await :vscode/fx.show-text-document-at (:app/log-file-uri state)]]})

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
  {:mcp/ax.server-changed #'server-changed
   :mcp/ax.start-server #'start-server
   :mcp/ax.lifecycle-updated #'lifecycle-updated
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
