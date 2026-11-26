(ns calva-backseat-driver.tools.axs
  (:require
   [calva-backseat-driver.integrations.vscode.settings :as settings]
   [calva-backseat-driver.tools :as tools]
   [vscode-mcp.manifest :as manifest]))

(defn- call
  "Runs the tool of `tool-call`, `{:call/tool … :call/arguments …}`, with what its
   row in the tool table reads from the state, after writing the call's log line. The
   dispatch resolves to the tool's result. A call that the manifest and the settings
   don't allow isn't run, and resolves to `{:result/refused reason}`, with `:disabled`
   or `:unknown` for the reason."
  [state _uf-data {:call/keys [tool arguments] :as tool-call}]
  (let [allowed (manifest/tool-call-allowed? (:extension/manifest state)
                                             tool
                                             {:settings (settings/when-clause-settings state)})]
    (if (= :allowed allowed)
      {:uf/fxs [(when-let [line (tools/log-line tool arguments)]
                  [:app/fx.log (select-keys state [:app/min-log-level :app/log-file-uri]) :debug line])
                [:uf/await :tool/fx.run
                 (assoc tool-call :call/state (select-keys state (get-in tools/tools [tool :tool/reads])))]]}
      {:uf/fxs [[:uf/await :app/fx.return {:result/refused allowed}]]})))

(def actions
  {:tool/ax.call #'call})
