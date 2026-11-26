(ns calva-backseat-driver.tools.axs
  (:require
   [calva-backseat-driver.integrations.vscode.settings :as settings]
   [calva-backseat-driver.tools :as tools]))

(defn- call
  "Runs the tool of `tool-call`, `{:call/tool … :call/arguments …}`, with what its
   row in the tool table reads from the state, after writing the call's log line. The
   dispatch resolves to the tool's result, or to `{:result/refused reason}` when the
   manifest and the settings don't allow the call."
  [state _uf-data {:call/keys [tool arguments] :as tool-call}]
  {:uf/fxs [(when-let [line (tools/log-line tool arguments)]
              [:app/fx.log (select-keys state [:app/min-log-level :app/log-file-uri]) :debug line])
            [:uf/await :tool/fx.run
             (assoc tool-call
                    :call/state (select-keys state (get-in tools/tools [tool :tool/reads]))
                    :call/when-clause-settings (settings/when-clause-settings state))]]})

(def actions
  {:tool/ax.call #'call})
