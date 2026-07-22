(ns calva-backseat-driver.app.axs
  (:require
   [vscode-mcp.policy :as mcp-policy]
   [cljs.core.match :refer [match]]))

(defn should-auto-start-mcp-server?
  [auto-start-mcp? auto-register-cursor-mcp? cursor-mcp-available?
   auto-register-eca? eca-available? workspace-root-present?]
  (mcp-policy/should-auto-start? {:mcp/auto-start? auto-start-mcp?
                                  :mcp/auto-register? auto-register-cursor-mcp?
                                  :mcp/cursor-available? cursor-mcp-available?
                                  :mcp/auto-register-eca? auto-register-eca?
                                  :mcp/eca-available? eca-available?
                                  :mcp/workspace-root-present? workspace-root-present?}))

(defn handle-action [state _context action]
  (match action
    [:app/ax.activate initial-state]
    (let [new-state (merge state initial-state)]
      {:ex/db new-state
       :ex/fxs [[:app/fx.init-logging {:app/log-file-uri (:app/log-file-uri new-state)
                                       :ex/uri-action [:db/ax.assoc-in [:app/log-dir-initialized+]]
                                       :ex/then [[:app/ax.init {:auto-start-mcp? :vscode/config.autoStartMCPServer
                                                                :auto-register-cursor-mcp? :vscode/config.autoRegisterCursorMcp
                                                                :auto-register-eca? :vscode/config.autoRegisterEcaMcp}]]}]
                [:mcp/fx.copy-wrapper-script-to-config-dir (:mcp/wrapper-config-path new-state)]]})

    [:app/ax.init {:auto-start-mcp? auto-start-mcp?
                   :auto-register-cursor-mcp? auto-register-cursor-mcp?
                   :auto-register-eca? auto-register-eca?}]
    {:ex/dxs [[:app/ax.register-command "calva-backseat-driver.startMcpServer"
               [[:mcp/ax.start-server]]]
              [:app/ax.register-command "calva-backseat-driver.stopMcpServer"
               [[:mcp/ax.stop-server]]]
              [:app/ax.register-command "calva-backseat-driver.registerMcpServerWithCursor"
               [[:mcp/ax.register-with-cursor]]]
              [:app/ax.register-command "calva-backseat-driver.openLogFile"
               [[:mcp/ax.open-server-log]]]
              [:app/ax.register-language-model-tools]
              [:calva/ax.init-history]
              [:calva/ax.when-activated [[:app/ax.init-output-listener]]]
              [:app/ax.set-when-context :calva-mcp-extension/activated?
               true]
              [:mcp/ax.sync-cursor-mcp-when-contexts]
              (when (should-auto-start-mcp-server? auto-start-mcp?
                                                   auto-register-cursor-mcp?
                                                   (:mcp/cursor-mcp-available? state)
                                                   auto-register-eca?
                                                   (:mcp/eca-available? state)
                                                   (:mcp/workspace-root-present? state))
                [:mcp/ax.start-server {:silent? true}])]}

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
    {:ex/fxs [[:calva/fx.flush-history]]
     :ex/dxs [[:mcp/ax.stop-server {:lifecycle/silent? true}]
              [:app/ax.cleanup]]}

    :else nil))

