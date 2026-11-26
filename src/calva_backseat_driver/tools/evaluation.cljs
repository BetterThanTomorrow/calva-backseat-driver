(ns calva-backseat-driver.tools.evaluation
  "The evaluation tool. Its decisions are pure functions: whether a call can run, what
   Calva is asked, and what the agent gets back. `evaluate-code+` runs a call with them."
  (:require
   [calva-backseat-driver.integrations.parinfer :as parinfer]
   [clojure.string :as string]))

(def ^:private no-ns-eval-note
  "When evaluating without providing a namespace argument the evaluation is performed in the `user` namespace. Most often this is not what you want, and instead you should be evaluating providing the namespace argument. If it is the first time you are using a namespace, evaluate its ns-form first.")

(def ^:private empty-result-note
  "Not expecting a empty string as a result? If it is the first time you are using a namespace, evaluate its ns-form in the `user` namespace first.")

(def ^:private error-result-note
  "* clj: Evaluating `*e` will give your information about the error.
   * cljs: Evaluating `(.-stack *e), gives you a stack trace")

(def ^:private reserved-whos #{"ui" "api"})

(defn who-error
  "Returns the error text for a `who` that a call can't use, or nil."
  [who]
  (cond
    (or (nil? who)
        (and (string? who)
             (string/blank? who)))
    "The `who` parameter is required. Provide a slug identifying your agent (e.g. \"copilot\")."

    (contains? reserved-whos who)
    (str "The `who` value \"" who "\" is reserved by Calva. Choose a different identifier.")))

(defn input-error
  "Returns the result of an evaluation that can't run because of its `who` or its
   code's brackets, or nil when it can."
  [{:keys [code who]}]
  (if-let [error (who-error who)]
    {:result/data {:error error}}
    (let [{:keys [valid? balanced-code] :as validation} (parinfer/validate-brackets code)]
      (when-not valid?
        {:result/data validation
         :result/log [[:debug "[Server] Code was unbalanced:" code "balanced-code:" balanced-code]]}))))

(defn session-error
  "Returns the result of an evaluation whose session isn't among Calva's `sessions`, or
   nil when it is."
  [{:keys [replSessionKey]} sessions]
  (when-not (some #(= replSessionKey (:replSessionKey %)) sessions)
    {:result/data {:valid? false
                   :error (str "Session '" replSessionKey "' not found.")
                   :available-sessions (mapv (fn [session]
                                               {:session-key (:replSessionKey session)
                                                :project-root (:projectRoot session)})
                                             sessions)}}))

(defn calva-options
  "Returns the options Calva evaluates the call's code with, from the call's
   `arguments` and the print limits in `state`."
  [{:keys [replSessionKey who description targetRuntimeId]
    ns :namespace}
   state]
  (let [max-length (:vscode/config.evaluation.maxLength state)
        max-depth (:vscode/config.evaluation.maxDepth state)]
    (cond-> {:sessionKey replSessionKey}
      ns (assoc :ns ns)
      who (assoc :who who)
      description (assoc :description description)
      targetRuntimeId (assoc :targetRuntimeId targetRuntimeId)
      (or max-length max-depth) (assoc :nReplOptions {:pprintOptions {:enabled true
                                                                      :printEngine "pprint"
                                                                      :maxLength max-length
                                                                      :maxDepth max-depth}}))))

(defn- notes
  "The notes an agent gets with what an evaluation gave."
  [{ns :namespace} {:evaluation/keys [result other-whos]}]
  (cond-> []
    (not ns)
    (conj no-ns-eval-note)

    (= "" result)
    (conj empty-result-note)

    (seq other-whos)
    (conj (str "Other evaluators active since your last eval: "
               (string/join ", " other-whos)
               ". Check the output log."))))

(defn- evaluated
  "What the agent gets for an `evaluation` that Calva performed."
  [arguments {:evaluation/keys [result ns output error-output session-key who other-whos
                                shadow-build shadow-runtime-id error stacktrace]
              :as evaluation}]
  (cond-> {:result result
           :ns ns
           :stdout output
           :stderr error-output
           :session-key session-key
           :notes (notes arguments evaluation)}
    who (assoc :who who)
    (seq other-whos) (assoc :other-whos-since-last other-whos)
    shadow-build (assoc :shadow-build shadow-build)
    shadow-runtime-id (assoc :shadow-runtime-id shadow-runtime-id)
    error (merge {:error error
                  :stacktrace stacktrace})))

(defn- rejected
  "What the agent gets for an evaluation that Calva rejected."
  [{:evaluation/keys [rejected-message rejected-printed]}]
  (if (re-find #"reserved" rejected-message)
    {:result "nil"
     :error rejected-message
     :notes ["The `who` value you provided is reserved. Choose a different identifier."]}
    {:result "nil"
     :stderr rejected-printed
     :notes [error-result-note]}))

(defn result
  "Returns the result of the call with `arguments` for `evaluation`: what Calva's
   evaluation gave, or how Calva rejected it."
  [arguments {:evaluation/keys [rejected-message] :as evaluation}]
  (if rejected-message
    {:result/data (rejected evaluation)
     :result/log [[:debug "[Server] Evaluation failed:" rejected-message]]}
    {:result/data (evaluated arguments evaluation)}))

(defn evaluate-code+
  "Evaluates the call's code through Calva. Returns a promise of the result."
  [perform-effect! {:call/keys [arguments state]}]
  (if-let [error (input-error arguments)]
    (js/Promise.resolve error)
    (.then (perform-effect! [:calva/fx.list-sessions])
           (fn [sessions]
             (or (session-error arguments sessions)
                 (.then (perform-effect! [:calva/fx.evaluate (:code arguments) (calva-options arguments state)])
                        (fn [evaluation]
                          (result arguments evaluation))))))))
