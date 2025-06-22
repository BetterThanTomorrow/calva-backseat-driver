(ns calva-backseat-driver.app.axs
  (:require
   [cljs.core.match :refer [match]]))


(defn handle-action [state _context action]
  (match action
    [:app/ax.activate initial-state]
    (let [new-state (merge state initial-state)]
      {:ex/db new-state
       :ex/dxs [[:app/ax.init :vscode/config.autoStartMCPServer]] ;; Give the init-logging a chance to save the promise
       :ex/fxs [[:app/fx.init-logging (assoc new-state :ex/uri-action [:db/ax.assoc-in [:app/log-dir-initialized+]])]
                [:mcp/fx.copy-wrapper-script-to-config-dir (:mcp/wrapper-config-path new-state)]]})

    [:app/ax.init autostart-mcp-server?]
    {:ex/dxs [[:app/ax.register-command "calva-backseat-driver.startMcpServer"
               [[:mcp/ax.start-server]]]
              [:app/ax.register-command "calva-backseat-driver.stopMcpServer"
               [[:mcp/ax.stop-server]]]
              [:app/ax.register-command "calva-backseat-driver.openLogFile"
               [[:mcp/ax.open-server-log]]]
              [:app/ax.register-language-model-tools]
              [:calva/ax.when-activated [[:app/ax.init-output-listener]]]
              [:app/ax.set-when-context :calva-mcp-extension/activated?
               true]
              (when autostart-mcp-server?
                [:mcp/ax.start-server])]}

    [:app/ax.init-output-listener]
    {:ex/dxs [[:calva/ax.subscribe-to-output]]}

    [:app/ax.set-when-context k v]
    {:ex/db (assoc-in state [:extension/when-contexts k] v)
     :ex/fxs [[:vscode/fx.set-context k v]]}

    [:app/ax.set-min-log-level level]
    {:ex/db (assoc state :app/min-log-level level)}

    [:app/ax.log level & messages]
    {:ex/fxs [[:app/fx.log
               (select-keys state [:app/min-log-level :app/log-file-uri :app/log-dir-initialized+])
               level
               messages]]}

    [:app/ax.register-command command-id actions]
    {:ex/fxs [[:app/fx.register-command command-id actions]]}

    [:app/ax.clear-disposables]
    {:ex/db (assoc state :extension/disposables [])
     :ex/fxs [[:app/fx.clear-disposables (:extension/disposables state)]]}

    [:app/ax.cleanup]
    {:ex/dxs [[:app/ax.set-when-context :calva-mcp-extension/activated? false]
              [:app/ax.clear-disposables]]}

    [:app/ax.register-language-model-tools]
    {:ex/fxs [[:app/fx.register-language-model-tools]]}

    [:app/ax.deactivate]
    {:ex/dxs [[:mcp/ax.stop-server]
              [:app/ax.cleanup]]}

    :else nil))

