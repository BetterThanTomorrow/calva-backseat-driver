(ns calva-backseat-driver.mcp.axs
  (:require
   [calva-backseat-driver.integrations.vscode.cursor-config :as cursor-config]
   [calva-backseat-driver.mcp.cursor-registration :as cursor-reg]
   [cljs.core.match :refer [match]]))

(defn handle-action [state _context action]
  (match action
    [:mcp/ax.start-server & _]
    (let [silent? (some-> action second :silent?)
          use-global-port-file? (cursor-reg/should-use-random-port-for-cursor? state)
          server-port (if use-global-port-file?
                        0
                        :vscode/config.mcpSocketServerPort)]
      {:ex/db (assoc state
                     :app/server-starting? true
                     :app/server-start-silent? silent?)
       :ex/dxs [[:app/ax.set-when-context :calva-backseat-driver/starting? true]]
       :ex/fxs [[:mcp/fx.start-server {:app/log-dir-initialized+ (:app/log-dir-initialized+ state)
                                       :mcp/repl-enabled? :vscode/config.enableMcpReplEvaluation
                                       :mcp/use-global-port-file? use-global-port-file?
                                       :mcp/wrapper-config-path (:mcp/wrapper-config-path state)
                                       :server/port server-port
                                       :ex/on-success [[:mcp/ax.server-started :ex/action-args]]
                                       :ex/on-error [[:mcp/ax.server-error :ex/action-args]]}]]})

    [:mcp/ax.server-started server-info]
    (let [silent? (:app/server-start-silent? state)
          effective-state (if silent?
                            state
                            (dissoc state :mcp/cursor-register-server-called?))
          register? (cursor-reg/should-call-register-server? effective-state server-info)]
      {:ex/db (cond-> (assoc state
                             :app/server-info server-info
                             :app/server-starting? false
                             :app/server-start-silent? false)
                register? (assoc :mcp/cursor-register-server-called? true))
       :ex/dxs [[:app/ax.set-when-context :calva-backseat-driver/starting? false]
                [:app/ax.set-when-context :calva-backseat-driver/started? true]]
       :ex/fxs (cursor-reg/server-started-fxs server-info silent?
                                              (:mcp/wrapper-config-path state)
                                              register?)})

    [:mcp/ax.ensure-cursor-mcp-registered]
    (let [server-info (:app/server-info state)]
      (when (and server-info
                 (cursor-reg/should-call-register-server? state server-info))
        {:ex/db (assoc state :mcp/cursor-register-server-called? true)
         :ex/fxs [(cursor-reg/register-cursor-mcp-server-effect server-info)]}))

    [:mcp/ax.cursor-mcp-registered result]
    {:ex/db (assoc state :mcp/cursor-registered? true)
     :ex/fxs [[:app/fx.log
               (select-keys state [:app/min-log-level :app/log-file-uri :app/log-dir-initialized+])
               :info
               ["Cursor MCP server registered:" (cursor-config/cursor-mcp-settings-display-name) result]]]}

    [:mcp/ax.cursor-mcp-registration-failed failure]
    {:ex/fxs [[:app/fx.log
               (select-keys state [:app/min-log-level :app/log-file-uri :app/log-dir-initialized+])
               :warn
               ["Cursor MCP auto-registration failed:" failure]]]}

    [:mcp/ax.cursor-mcp-unregistered _result]
    {:ex/db (dissoc state
                    :mcp/cursor-registered?
                    :mcp/cursor-register-server-called?)}

    [:mcp/ax.stop-server]
    {:ex/db (assoc state :app/server-stopping? true)
     :ex/dxs [[:app/ax.set-when-context :calva-backseat-driver/stopping? true]]
     :ex/fxs [[:mcp/fx.stop-server (merge {:ex/on-success [[:mcp/ax.server-stopped :ex/action-args]]
                                           :ex/on-error [[:mcp/ax.server-error :ex/action-args]]
                                           :mcp/cursor-registered? (:mcp/cursor-registered? state)}
                                          (:app/server-info state))]]}

    [:mcp/ax.server-stopped success?]
    {:ex/db (dissoc state
                    :app/server-info
                    :app/server-stopping?
                    :mcp/cursor-registered?
                    :mcp/cursor-register-server-called?)
     :ex/dxs [[:app/ax.set-when-context :calva-backseat-driver/stopping? false]
              [:app/ax.set-when-context :calva-backseat-driver/started? false]]
     :ex/fxs [[:vscode/fx.show-information-message "MCP server stopped"]
              [:app/fx.return success?]]}

    [:mcp/ax.open-server-log]
    {:ex/fxs [[:vscode/fx.workspace.open-text-document
               {:open/uri (:app/log-file-uri state)
                :ex/then [[:vscode/ax.show-text-document :ex/action-args]]}]]}

    [:mcp/ax.server-error err]
    (do (js/console.error err)
        {:ex/fxs [[:vscode/fx.show-error-message (str "MCP server error: " err)]]})

    [:mcp/ax.handle-request request]
    {:ex/fxs [[:mcp/fx.handle-request {:mcp/repl-enabled? :vscode/config.enableMcpReplEvaluation
                                       :mcp/provide-bd-skill? :vscode/config.provideBdSkill
                                       :mcp/provide-edit-skill? :vscode/config.provideEditSkill} request]]}

    :else
    {:ex/fxs [[:node/fx.log-error "Unknown action:" (pr-str action)]]}))
