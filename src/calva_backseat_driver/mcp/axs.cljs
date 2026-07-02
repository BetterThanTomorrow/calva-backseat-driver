(ns calva-backseat-driver.mcp.axs
  (:require
   [calva-backseat-driver.integrations.vscode.cursor-config :as cursor-config]
   [cljs.core.match :refer [match]]
   [vscode-mcp.lifecycle :as lifecycle]))

(defn- handle-start-server [state action]
  (let [silent? (some-> action second :silent?)]
    {:ex/fxs [[:mcp/fx.lifecycle-start
               {:lifecycle/silent? silent?
                :mcp/wrapper-config-path (:mcp/wrapper-config-path state)
                :mcp/lifecycle-state (:mcp/lifecycle-state state)
                :ex/on-success [[:mcp/ax.lifecycle-updated :ex/action-args]]}]]}))

(defn- handle-lifecycle-updated [state lifecycle-state]
  {:ex/db (assoc state :mcp/lifecycle-state lifecycle-state)
   :ex/fxs [[:app/fx.return (clj->js (lifecycle/server-info lifecycle-state))]]})

(defn- handle-stop-server [state]
  {:ex/fxs [[:mcp/fx.lifecycle-stop
             {:mcp/wrapper-config-path (:mcp/wrapper-config-path state)
              :mcp/lifecycle-state (:mcp/lifecycle-state state)
              :ex/on-success [[:mcp/ax.lifecycle-stopped :ex/action-args]]}]]})

(defn- handle-lifecycle-stopped [state lifecycle-state]
  {:ex/db (assoc state :mcp/lifecycle-state lifecycle-state)
   :ex/fxs [[:app/fx.return true]]})

(defn- handle-ensure-cursor-mcp-registered [state]
  (let [server-info (lifecycle/server-info (:mcp/lifecycle-state state))]
    (when server-info
      {:ex/fxs [[:mcp/fx.register-cursor-mcp-server server-info
                 {:ex/on-success [[:mcp/ax.cursor-mcp-registered :ex/action-args]]
                  :ex/on-error [[:mcp/ax.cursor-mcp-registration-failed :ex/action-args]]}]]})))

(defn- handle-cursor-mcp-registered [state result]
  {:ex/db (update state :mcp/lifecycle-state assoc
                  :lifecycle/cursor-registered? true
                  :lifecycle/cursor-register-called? true)
   :ex/fxs [[:app/fx.log
             (select-keys state [:app/min-log-level :app/log-file-uri :app/log-dir-initialized+])
             :info
             ["Cursor MCP server registered:" (cursor-config/cursor-mcp-settings-display-name) result]]]})

(defn- handle-cursor-mcp-registration-failed [state failure]
  {:ex/db (update state :mcp/lifecycle-state assoc :lifecycle/cursor-register-called? true)
   :ex/fxs [[:app/fx.log
             (select-keys state [:app/min-log-level :app/log-file-uri :app/log-dir-initialized+])
             :warn
             ["Cursor MCP auto-registration failed:" failure]]]})

(defn- handle-open-server-log [_state log-file-uri]
  {:ex/fxs [[:vscode/fx.workspace.open-text-document
             {:open/uri log-file-uri
              :ex/then [[:vscode/ax.show-text-document :ex/action-args]]}]]})

(defn- handle-server-error [err]
  (do (js/console.error err)
      {:ex/fxs [[:vscode/fx.show-error-message (str "MCP server error: " err)]]}))

(defn- handle-mcp-request [request]
  {:ex/fxs [[:mcp/fx.handle-request {:mcp/repl-enabled? :vscode/config.enableMcpReplEvaluation
                                      :mcp/provide-bd-skill? :vscode/config.provideBdSkill
                                      :mcp/provide-edit-skill? :vscode/config.provideEditSkill} request]]})

(defn handle-action [state _context action]
  (match action
    [:mcp/ax.start-server & _]
    (handle-start-server state action)

    [:mcp/ax.lifecycle-updated lifecycle-state]
    (handle-lifecycle-updated state lifecycle-state)

    [:mcp/ax.ensure-cursor-mcp-registered]
    (handle-ensure-cursor-mcp-registered state)

    [:mcp/ax.cursor-mcp-registered result]
    (handle-cursor-mcp-registered state result)

    [:mcp/ax.cursor-mcp-registration-failed failure]
    (handle-cursor-mcp-registration-failed state failure)

    [:mcp/ax.stop-server]
    (handle-stop-server state)

    [:mcp/ax.lifecycle-stopped lifecycle-state]
    (handle-lifecycle-stopped state lifecycle-state)

    [:mcp/ax.open-server-log]
    (handle-open-server-log state (:app/log-file-uri state))

    [:mcp/ax.server-error err]
    (handle-server-error err)

    [:mcp/ax.handle-request request]
    (handle-mcp-request request)

    :else
    {:ex/fxs [[:node/fx.log-error "Unknown action:" (pr-str action)]]}))
