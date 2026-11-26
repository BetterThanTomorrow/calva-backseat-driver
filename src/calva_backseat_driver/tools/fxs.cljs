(ns calva-backseat-driver.tools.fxs
  (:require
   [calva-backseat-driver.app.live-objects :as live-objects]
   [calva-backseat-driver.integrations.calva.fxs :as calva-fxs]
   [calva-backseat-driver.tools :as tools]
   [vscode-mcp.manifest :as manifest]))

(defn- performer
  "Returns the function a tool performs an effect with. It takes an effect vector, and
   returns a promise of what the effect gave. The effects a tool can perform are Calva's."
  [dispatch!]
  (fn [[effect-name & args]]
    (if-let [effect (get calva-fxs/effects effect-name)]
      (.then (js/Promise.resolve nil)
             (fn [_]
               (apply effect dispatch! args)))
      (js/Promise.reject (js/Error. (str "Unknown effect: " effect-name))))))

(defn- write-log!
  "Writes the log lines of a tool call's `result`, and returns the result without them."
  [dispatch! {:result/keys [log] :as result}]
  (doseq [[level & messages] log]
    (dispatch! [(into [:app/ax.log level] messages)]))
  (dissoc result :result/log))

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
      (-> (tools/call-tool+ (performer dispatch!) tool-call)
          (.then (fn [result]
                   (write-log! dispatch! result))
                 (fn [e]
                   {:result/thrown (or (ex-message e) (str e))})))
      {:result/refused allowed})))

(def effects
  {:tool/fx.run #'run})
