(ns calva-backseat-driver.integrations.calva.features
  (:require
   ["vscode" :as vscode]
   [calva-backseat-driver.integrations.calva.api :as calva]
   [calva-backseat-driver.integrations.calva.editor :as editor]
   [calva-backseat-driver.integrations.parinfer :as parinfer]
   [clojure.string :as string]
   [promesa.core :as p]))

(def ^:private no-ns-eval-note
  "When evaluating without providing a namespace argument the evaluation is performed in the `user` namespace. Most often this is not what you want, and instead you should be evaluating providing the namespace argument. If it is the first time you are using a namespace, evaluate its ns-form first.")

(def ^:private empty-result-note
  "Not expecting a empty string as a result? If it is the first time you are using a namespace, evaluate its ns-form in the `user` namespace first.")

(def ^:private error-result-note
  "* clj: Evaluating `*e` will give your information about the error.
   * cljs: Evaluating `(.-stack *e), gives you a stack trace")


(def ^:private reserved-whos #{"ui" "api"})

(defn validate-who
  "Returns nil if valid, error string if not. Pure function."
  [who]
  (cond
    (or (nil? who) (and (string? who) (string/blank? who)))
    "The `who` parameter is required. Provide a slug identifying your agent (e.g. \"copilot\")."

    (contains? reserved-whos who)
    (str "The `who` value \"" who "\" is reserved by Calva. Choose a different identifier.")

    :else nil))

(defn- get-eval-config []
  (let [config (vscode/workspace.getConfiguration "calva-backseat-driver.evaluation")]
    {:max-length (.get config "maxLength")
     :max-depth (.get config "maxDepth")}))

;; Session listing and validation

(def ^:private legacy-session-keys
  "Fallback session keys for older Calva versions without listSessions API"
  #{"clj" "cljs" "cljc"})

(defn exists-list-sessions?
  "Returns true if the Calva API supports listing sessions"
  []
  (boolean (get-in calva/calva-api [:repl :listSessions])))

(defn list-sessions+
  "Returns a promise that resolves to a list of available REPL sessions.
   Falls back to legacy session keys when API is not available."
  [{:ex/keys [dispatch!]}]
  (dispatch! [[:app/ax.log :debug "[Server] Listing REPL sessions"]])
  (if-let [list-sessions-fn (get-in calva/calva-api [:repl :listSessions])]
    (p/let [sessions (list-sessions-fn)]
      #js {:sessions sessions})
    (p/resolved
     #js {:sessions (to-array (map (fn [k] #js {:replSessionKey k}) legacy-session-keys))
          :note "Session listing API not available, showing legacy session keys"})))

(defn- validate-session-key+
  "Validates a session key against available sessions.
   Returns {:valid? true} or {:valid? false :error ... :available-sessions ...}"
  [session-key]
  (if-let [list-sessions-fn (get-in calva/calva-api [:repl :listSessions])]
    ;; New Calva with listSessions API
    (p/let [sessions (list-sessions-fn)
            session-keys (->> sessions
                              (map #(.-replSessionKey ^js %))
                              set)]
      (if (contains? session-keys session-key)
        {:valid? true}
        {:valid? false
         :error (str "Session '" session-key "' not found.")
         :available-sessions (mapv (fn [^js s]
                                     {:session-key (.-replSessionKey s)
                                      :project-root (.-projectRoot s)})
                                   sessions)}))
    ;; Fallback for older Calva
    (p/resolved
     (if (contains? legacy-session-keys session-key)
       {:valid? true}
       {:valid? false
        :error (str "Session '" session-key "' not recognized.")
        :available-sessions (mapv (fn [k] {:session-key k}) legacy-session-keys)}))))

(defn evaluate-code+
  "Returns a promise that resolves to the result of evaluating Clojure/ClojureScript code.
   Pre-validates session key against available sessions before evaluation."
  [{:ex/keys [dispatch!]
    :calva/keys [code repl-session-key ns who description]}]
  (let [{:keys [valid? balanced-code]
         :as validation} (parinfer/validate-brackets code)]
    (when-not valid?
      (dispatch! [[:app/ax.log :debug "[Server] Code was unbalanced:" code "balanced-code:" balanced-code]]))
    (if-not valid?
      (p/resolved (clj->js validation))
      (p/let [session-validation (validate-session-key+ repl-session-key)]
        (if-not (:valid? session-validation)
          (clj->js session-validation)
          (let [evaluate-new (get-in calva/calva-api [:repl :evaluate])
                evaluate-old (get-in calva/calva-api [:repl :evaluateCode])]
            (p/let [{:keys [max-length max-depth]} (get-eval-config)
                    enabled? (or max-length max-depth)
                    nrepl-eval-options (when enabled?
                                         #js {:pprintOptions #js {:enabled true
                                                                  :printEngine "pprint"
                                                                  :maxLength max-length
                                                                  :maxDepth max-depth}})
                    result (if evaluate-new
                             ;; === NEW API PATH ===
                             (-> (p/let [options-js (clj->js
                                                     (cond-> {:sessionKey repl-session-key}
                                                       ns (assoc :ns ns)
                                                       who (assoc :who who)
                                                       description (assoc :description description)
                                                       nrepl-eval-options (assoc :nReplOptions nrepl-eval-options)))
                                         ^js evaluation+ (evaluate-new code options-js)]
                                   (dispatch! [[:app/ax.log :debug "[Server] Evaluating code (new API):" code]])
                                   (let [other-whos (some-> (.-otherWhosSinceLast evaluation+)
                                                            (js->clj))
                                         notes (cond-> ["Remember to check the output tool now and then to see what's happening in the application."]
                                                 (not ns)
                                                 (conj no-ns-eval-note)

                                                 (= "" (.-result evaluation+))
                                                 (conj empty-result-note)

                                                 (seq other-whos)
                                                 (conj (str "Other evaluators active since your last eval: "
                                                            (string/join ", " other-whos)
                                                            ". Check the output log.")))]
                                     (cond-> {:result (.-result evaluation+)
                                              :ns (.-ns evaluation+)
                                              :stdout (.-output evaluation+)
                                              :stderr (.-errorOutput evaluation+)
                                              :session-key (.-sessionKey evaluation+)
                                              :notes notes}

                                       (.-who evaluation+)
                                       (assoc :who (.-who evaluation+))

                                       (seq other-whos)
                                       (assoc :other-whos-since-last other-whos)

                                       (.-error evaluation+)
                                       (merge {:error (.-error evaluation+)
                                               :stacktrace (.-stacktrace evaluation+)}))))
                                 (p/catch (fn [err]
                                            (dispatch! [[:app/ax.log :debug "[Server] Evaluation failed:" err]])
                                            (let [msg (str (.-message err))]
                                              (if (re-find #"reserved" msg)
                                                {:result "nil"
                                                 :error msg
                                                 :notes ["The `who` value you provided is reserved. Choose a different identifier."]}
                                                {:result "nil"
                                                 :stderr (pr-str err)
                                                 :notes [error-result-note]})))))

                             ;; === FALLBACK PATH (old evaluateCode) ===
                             (-> (p/let [^js evaluation+ (if ns
                                                           (evaluate-old repl-session-key code ns nil nrepl-eval-options)
                                                           (evaluate-old repl-session-key code js/undefined nil nrepl-eval-options))]
                                   (dispatch! [[:app/ax.log :debug "[Server] Evaluating code (legacy API):" code]])
                                   (let [notes (cond-> ["Legacy Calva API detected. The who and description parameters were not applied. The user needs to update Calva to enable evaluator/who tracking."
                                                        "Remember to check the output tool now and then to see what's happening in the application."]
                                                 (not ns)
                                                 (conj no-ns-eval-note)

                                                 (= "" (.-result evaluation+))
                                                 (conj empty-result-note))]
                                     (cond-> {:result (.-result evaluation+)
                                              :ns (.-ns evaluation+)
                                              :stdout (.-output evaluation+)
                                              :stderr (.-errorOutput evaluation+)
                                              :session-key (.-sessionKey evaluation+)
                                              :notes notes}

                                       (.-error evaluation+)
                                       (merge {:error (.-error evaluation+)
                                               :stacktrace (.-stacktrace evaluation+)}))))
                                 (p/catch (fn [err]
                                            (dispatch! [[:app/ax.log :debug "[Server] Evaluation failed:" err]])
                                            {:result "nil"
                                             :stderr (pr-str err)
                                             :notes [error-result-note]}))))]
              (clj->js result))))))))


(defn get-clojuredocs+ [{:ex/keys [dispatch!]
                         :calva/keys [clojure-symbol]}]
  (dispatch! [[:app/ax.log :debug "[Server] Getting clojuredocs for:" clojure-symbol]])
  ((get-in calva/calva-api [:info :getClojureDocsDotOrg]) clojure-symbol "user"))

(defn exists-get-clojuredocs? [] (boolean (get-in calva/calva-api [:info :getClojureDocsDotOrg])))

(defn get-symbol-info+ [{:ex/keys [dispatch!]
                         :calva/keys [clojure-symbol ns repl-session-key]}]
  (dispatch! [[:app/ax.log :debug "[Server] Getting symbol info for:" clojure-symbol]])
  ((get-in calva/calva-api [:info :getSymbolInfo]) clojure-symbol repl-session-key ns))

(defn exists-get-symbol-info? [] (boolean (get-in calva/calva-api [:info :getSymbolInfo])))

(defn subscribe-to-output [{:ex/keys [dispatch!]
                            :calva/keys [on-output]}]
  ((get-in calva/calva-api [:repl :onOutputLogged])
   (fn [message]
     (dispatch! (conj on-output (js->clj message :keywordize-keys true))))))

(defn query-output [{:ex/keys [dispatch!]
                     :calva/keys [query-edn-str inputs-edn-str]}]
  (clj->js
   (dispatch! [[:app/ax.log :debug "[Server] Querying output log with:" query-edn-str]
               [:calva/ax.query-output query-edn-str inputs-edn-str]])))

(defn exists-on-output? [] (boolean (get-in calva/calva-api [:repl :onOutputLogged])))

(defn- get-editor-config []
  (let [config (vscode/workspace.getConfiguration "calva-backseat-driver.editor")]
    {:search-padding (.get config "fuzzyLineTargetingPadding")
     :context-padding (.get config "lineContextResponsePadding")}))



(defn replace-top-level-form+
  "Replace a top-level form using text targeting and Calva's ranges API"
  [{:ex/keys [dispatch!]
    :calva/keys [file-path line target-line-text new-form]}]
  (let [{:keys [search-padding context-padding]} (get-editor-config)]
    (dispatch! [[:app/ax.log :debug "[Editor] Replacing form at line" line "in" file-path]])
    (editor/apply-form-edit-by-line-with-text-targeting
     {:editor/file-path file-path
      :editor/line-number line
      :editor/target-line target-line-text
      :editor/new-form new-form
      :editor/ranges-fn-key :currentTopLevelForm
      :editor/search-padding search-padding
      :editor/context-padding context-padding})))

(defn insert-top-level-form+
  "Insert a top-level form using text targeting and Calva's ranges API"
  [{:ex/keys [dispatch!]
    :calva/keys [file-path line target-line-text new-form]}]
  (let [{:keys [search-padding context-padding]} (get-editor-config)]
    (dispatch! [[:app/ax.log :debug "[Editor] Inserting form at line" line "in" file-path]])
    (editor/apply-form-edit-by-line-with-text-targeting
     {:editor/file-path file-path
      :editor/line-number line
      :editor/target-line target-line-text
      :editor/new-form new-form
      :editor/ranges-fn-key :insertionPoint
      :editor/search-padding search-padding
      :editor/context-padding context-padding})))

(defn structural-create-file+
  "Create a new Clojure file with exact content using vscode/workspace.fs API"
  [{:ex/keys [dispatch!]
    :calva/keys [file-path content]}]
  (dispatch! [[:app/ax.log :debug "[Editor] Creating file" file-path]])
  (p/let [result (editor/structural-create-file+ file-path content)]
    (when (not (:success result))
      (dispatch! [[:app/ax.log :error "[Editor] Failed to create file" file-path (:error result)]]))
    result))

(defn append-code+
  "Append a top-level form to the end of a file at guaranteed top level"
  [{:ex/keys [dispatch!]
    :calva/keys [file-path code]}]
  (dispatch! [[:app/ax.log :debug "[Editor] Appending code to end of" file-path]])
  (p/let [result (editor/append-code+ file-path code)]
    (when (not (:success result))
      (dispatch! [[:app/ax.log :error "[Editor] Failed to append code to file" file-path (:error result)]]))
    result))


(comment
  (.-line (vscode/Position. 0))
  (p/let [info (get-symbol-info+ {:ex/dispatch! (comp pr-str println)
                                  :calva/clojure-symbol "clojure.core/reductions"
                                  :calva/repl-session-key "clj"
                                  :calva/ns "user"})]
    (def info info))
  (js->clj info :keywordize-keys true)

  (p/let [docs (get-clojuredocs+ {:ex/dispatch! (comp pr-str println)
                                  :calva/clojure-symbol "clojure.core/reductions"})]
    (def docs docs))
  (js->clj docs :keywordize-keys true)

  :rcf)

