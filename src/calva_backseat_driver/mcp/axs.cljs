(ns calva-backseat-driver.mcp.axs
  (:require
   [cljs.core.match :refer [match]]
   [vscode-mcp.core :as vscode-mcp]))

(defn- handle-start-server [state action]
  (let [silent? (some-> action second :silent?)]
    {:ex/fxs [[:mcp/fx.lifecycle-start
               {:lifecycle/silent? silent?
                :mcp/wrapper-config-path (:mcp/wrapper-config-path state)
                :mcp/lifecycle-state (:mcp/lifecycle-state state)
                :ex/on-success [[:mcp/ax.lifecycle-updated :ex/action-args]]}]]}))

(defn- handle-sync-cursor-mcp-when-contexts [state]
  (let [lifecycle (:mcp/lifecycle-state state)
        cursor-available? (:mcp/cursor-mcp-available? state)
        server-running? (boolean (vscode-mcp/server-info lifecycle))
        cursor-registered? (and server-running?
                                (vscode-mcp/cursor-registered? lifecycle))]
    {:ex/dxs [[:app/ax.set-when-context :calva-backseat-driver/cursor-mcp-available? cursor-available?]
              [:app/ax.set-when-context :calva-backseat-driver/mcp-server-registered-with-cursor? cursor-registered?]]}))

(defn- handle-lifecycle-updated [state lifecycle-state]
  {:ex/db (assoc state :mcp/lifecycle-state lifecycle-state)
   :ex/dxs [[:mcp/ax.sync-cursor-mcp-when-contexts]]
   :ex/fxs [[:app/fx.return (clj->js (vscode-mcp/server-info lifecycle-state))]]})

(defn- handle-stop-server [state action]
  (let [opts (or (second action) {})]
    {:ex/fxs [[:mcp/fx.lifecycle-stop
               (merge {:mcp/wrapper-config-path (:mcp/wrapper-config-path state)
                       :mcp/lifecycle-state (:mcp/lifecycle-state state)
                       :ex/on-success [[:mcp/ax.lifecycle-stopped :ex/action-args]]}
                      opts)]]}))

(defn- handle-lifecycle-stopped [state lifecycle-state]
  {:ex/db (assoc state :mcp/lifecycle-state lifecycle-state)
   :ex/dxs [[:mcp/ax.sync-cursor-mcp-when-contexts]]
   :ex/fxs [[:app/fx.return true]]})

(defn- handle-register-with-cursor [state]
  {:ex/fxs [[:mcp/fx.register-with-cursor
             {:mcp/wrapper-config-path (:mcp/wrapper-config-path state)
              :mcp/lifecycle-state (:mcp/lifecycle-state state)
              :ex/on-success [[:mcp/ax.lifecycle-updated :ex/action-args]]
              :ex/on-error [[:mcp/ax.cursor-mcp-registration-failed :ex/action-args]]}]]})

(defn- handle-cursor-mcp-registered [state result]
  {:ex/dxs [[:mcp/ax.sync-cursor-mcp-when-contexts]
            [:app/fx.log
             (select-keys state [:app/min-log-level :app/log-file-uri :app/log-dir-initialized+])
             :info
             ["Cursor MCP server registered:" result]]]})

(defn- handle-cursor-mcp-registration-failed [state failure]
  {:ex/dxs [[:mcp/ax.sync-cursor-mcp-when-contexts]
            [:app/fx.log
             (select-keys state [:app/min-log-level :app/log-file-uri :app/log-dir-initialized+])
             :warn
             ["Cursor MCP auto-registration failed:" failure]]]})

(defn- handle-open-server-log [_state log-file-uri]
  {:ex/fxs [[:vscode/fx.workspace.open-text-document
             {:open/uri log-file-uri
              :ex/then [[:vscode/ax.show-text-document :ex/action-args]]}]]})

(defn- handle-server-error [err]
  (js/console.error err)
  {:ex/fxs [[:vscode/fx.show-error-message (str "MCP server error: " err)]]})

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

    [:mcp/ax.sync-cursor-mcp-when-contexts]
    (handle-sync-cursor-mcp-when-contexts state)

    [:mcp/ax.cursor-mcp-registered result]
    (handle-cursor-mcp-registered state result)

    [:mcp/ax.cursor-mcp-registration-failed failure]
    (handle-cursor-mcp-registration-failed state failure)

    [:mcp/ax.register-with-cursor]
    (handle-register-with-cursor state)

    [:mcp/ax.stop-server & _]
    (handle-stop-server state action)

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
