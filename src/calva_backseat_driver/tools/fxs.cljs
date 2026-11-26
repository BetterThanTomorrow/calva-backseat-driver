(ns calva-backseat-driver.tools.fxs
  (:require
   [calva-backseat-driver.app.live-objects :as live-objects]
   [calva-backseat-driver.tools :as tools]
   [vscode-mcp.manifest :as manifest]))

(defn- run
  "Runs the tool of `tool-call` when the manifest has the tool and its when clause holds.
   Returns a promise of the tool's result. A tool that throws gives `{:result/thrown message}`,
   and a call that isn't allowed gives `{:result/refused reason}`, with `:disabled` or
   `:unknown` for the reason."
  [dispatch! {:call/keys [tool when-clause-settings] :as tool-call}]
  (let [allowed (manifest/tool-call-allowed? (live-objects/extension-context)
                                             tool
                                             {:settings when-clause-settings})]
    (if (= :allowed allowed)
      (.catch (tools/call-tool+ dispatch! tool-call)
              (fn [e]
                {:result/thrown (or (ex-message e) (str e))}))
      {:result/refused allowed})))

(def effects
  {:tool/fx.run #'run})
