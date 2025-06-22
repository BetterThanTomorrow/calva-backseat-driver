(ns calva-backseat-driver.mcp.axs
  (:require
   [cljs.core.match :refer [match]]))

(defn handle-action [state _context action]
  (match action
    [:mcp/ax.start-server]
    {:ex/db (assoc state :app/server-starting? true)
     :ex/dxs [[:app/ax.set-when-context :calva-backseat-driver/starting? true]]
     :ex/fxs [[:mcp/fx.start-server {:app/log-dir-initialized+ (:app/log-dir-initialized+ state)
                                     :mcp/repl-enabled? :vscode/config.enableMcpReplEvaluation
                                     :server/port :vscode/config.mcpSocketServerPort
                                     :ex/on-success [[:mcp/ax.server-started :ex/action-args]]
                                     :ex/on-error [[:mcp/ax.server-error :ex/action-args]]}]]}

    [:mcp/ax.server-started server-info]
    {:ex/db (assoc state
                   :app/server-info server-info
                   :app/server-starting? false)
     :ex/dxs [[:app/ax.set-when-context :calva-backseat-driver/starting? false]
              [:app/ax.set-when-context :calva-backseat-driver/started? true]]
     :ex/fxs [[:mcp/fx.show-server-started-message server-info (:mcp/wrapper-config-path state)]
              [:app/fx.return (clj->js server-info)]]}

    [:mcp/ax.stop-server]
    {:ex/db (assoc state :app/server-stopping? true)
     :ex/dxs [[:app/ax.set-when-context :calva-backseat-driver/stopping? true]]
     :ex/fxs [[:mcp/fx.stop-server (merge {:ex/on-success [[:mcp/ax.server-stopped :ex/action-args]]
                                           :ex/on-error [[:mcp/ax.server-error :ex/action-args]]}
                                          (:app/server-info state))]]}

    [:mcp/ax.server-stopped success?]
    {:ex/db (dissoc state
                    :app/server-info
                    :app/server-stopping?)
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
    {:ex/fxs [[:mcp/fx.handle-request {:mcp/repl-enabled? :vscode/config.enableMcpReplEvaluation} request]]}

    :else
    {:ex/fxs [[:node/fx.log-error "Unknown action:" (pr-str action)]]}))