(ns calva-backseat-driver.app.axs
  (:require
   [vscode-mcp.policy :as mcp-policy]))

(def ^:private commands
  "The actions each command dispatches."
  {"calva-backseat-driver.startMcpServer" [[:mcp/ax.start-server]]
   "calva-backseat-driver.stopMcpServer" [[:mcp/ax.stop-server]]
   "calva-backseat-driver.registerMcpServerWithCursor" [[:mcp/ax.register-with-cursor]]
   "calva-backseat-driver.openLogFile" [[:mcp/ax.open-server-log]]})

(defn should-auto-start-mcp-server?
  "True when the settings and what is installed call for starting the MCP server at activation."
  [{:mcp/keys [cursor-mcp-available? eca-available? workspace-root-present?]
    :as state}]
  (mcp-policy/should-auto-start? {:mcp/auto-start? (:vscode/config.autoStartMCPServer state)
                                  :mcp/auto-register? (:vscode/config.autoRegisterCursorMcp state)
                                  :mcp/cursor-available? cursor-mcp-available?
                                  :mcp/auto-register-eca? (:vscode/config.autoRegisterEcaMcp state)
                                  :mcp/eca-available? eca-available?
                                  :mcp/workspace-root-present? workspace-root-present?}))

(defn- activate
  "Takes the state the extension starts with: the settings, and what `activate` found installed."
  [state _uf-data initial-state]
  {:uf/db (merge state initial-state)
   :uf/dxs [[:app/ax.init]]})

(defn- init
  "Registers the commands and the tools, and starts what the extension runs."
  [state _uf-data]
  {:uf/db (assoc state :app/activated? true)
   :uf/fxs (-> (mapv (fn [[command-id actions]]
                       [:app/fx.register-command command-id actions])
                     commands)
               (conj [:app/fx.register-language-model-tools]
                     [:vscode/fx.subscribe-to-settings]))
   :uf/dxs [[:calva/ax.init-history]
            [:app/ax.start-background-work]]})

(defn- start-background-work
  "Starts the chains that wait for something: Calva being active, and the MCP server starting."
  [state _uf-data]
  {:uf/fxs [[:uf/fx.dispatch [[:calva/ax.when-activated [[:calva/ax.subscribe-to-output]
                                                         [:calva/ax.subscribe-to-sessions-changed]]]]]
            (when (should-auto-start-mcp-server? state)
              [:uf/fx.dispatch [[:mcp/ax.start-server {:silent? true}]]])]})

(defn- settings-changed
  "Takes the extension's `settings` after one of them changed."
  [state _uf-data settings]
  {:uf/db (merge state settings)})

(defn- hot-reloaded
  "Takes what the reload hook found installed, and initializes when a reload came before the first init."
  [state _uf-data facts]
  {:uf/db (merge state facts)
   :uf/dxs (when-not (:app/activated? state)
             [[:app/ax.init]])})

(defn- log
  "Writes `messages` to the log file at `level`."
  [state _uf-data level & messages]
  {:uf/fxs [[:app/fx.log
             (select-keys state [:app/min-log-level :app/log-file-uri])
             level
             messages]]})

(defn- cleanup
  "Disposes what the extension registered."
  [state _uf-data]
  {:uf/db (assoc state :app/activated? false)
   :uf/fxs [[:app/fx.clear-disposables]]})

(defn- deactivate
  "Saves the eval history at once, and stops the MCP server without a message."
  [state _uf-data]
  {:uf/db (assoc state :app/activated? false)
   :uf/fxs [[:uf/fx.cancel-deferred-dispatch :calva/history-save]
            [:calva/fx.save-history (:calva/history-storage-uri state) (:calva/history state)]
            [:app/fx.clear-disposables]]
   :uf/dxs [[:mcp/ax.stop-server {:lifecycle/silent? true}]]})

(def actions
  {:app/ax.activate #'activate
   :app/ax.init #'init
   :app/ax.start-background-work #'start-background-work
   :app/ax.settings-changed #'settings-changed
   :app/ax.hot-reloaded #'hot-reloaded
   :app/ax.log #'log
   :app/ax.cleanup #'cleanup
   :app/ax.deactivate #'deactivate})
