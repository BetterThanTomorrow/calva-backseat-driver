(ns calva-backseat-driver.tools.fxs
  (:require
   [calva-backseat-driver.integrations.calva.fxs :as calva-fxs]
   [calva-backseat-driver.tools :as tools]))

(defn- performer
  "Returns the function a tool performs an effect with. It takes an effect vector, and
   returns a promise of what the effect gave, for the effects a tool can perform."
  [dispatch!]
  (fn [[effect-name & args]]
    (if-let [effect (get calva-fxs/tool-effects effect-name)]
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
  "Runs the tool of `tool-call`. Returns a promise of the tool's result. A tool that
   throws gives `{:result/thrown message}`."
  [dispatch! tool-call]
  (-> (tools/call-tool+ (performer dispatch!) tool-call)
      (.then (fn [result]
               (write-log! dispatch! result))
             (fn [e]
               {:result/thrown (or (ex-message e) (str e))}))))

(def effects
  {:tool/fx.run #'run})
