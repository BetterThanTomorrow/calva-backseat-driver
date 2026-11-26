(ns calva-backseat-driver.tools.axs
  (:require
   [calva-backseat-driver.integrations.vscode.settings :as settings]
   [calva-backseat-driver.tools :as tools]))

(defn- call
  "Runs the tool of `tool-call`, `{:call/tool … :call/arguments …}`, with the settings
   its row in the tool table reads. The dispatch resolves to the tool's result, or to
   `{:result/refused reason}` when the manifest and the settings don't allow the call."
  [state _uf-data {:call/keys [tool] :as tool-call}]
  {:uf/fxs [[:uf/await :tool/fx.run
             (assoc tool-call
                    :call/settings (select-keys state (get-in tools/tools [tool :tool/reads]))
                    :call/when-clause-settings (settings/when-clause-settings state))]]})

(def actions
  {:tool/ax.call #'call})
