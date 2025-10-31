(ns calva-backseat-driver.integrations.calva.features
  (:require
   ["vscode" :as vscode]
   [calva-backseat-driver.integrations.calva.api :as calva]
   [calva-backseat-driver.integrations.calva.editor :as editor]
   [promesa.core :as p]
   [calva-backseat-driver.integrations.parinfer :as parinfer]))

(def ^:private no-ns-eval-note
  "When evaluating without providing a namespace argument the evaluation is performed in the `user` namespace. Most often this is not what you want, and instead you should be evaluating providing the namespace argument. If it is the first time you are using a namespace, evaluate its ns-form first.")

(def ^:private empty-result-note
  "Not expecting a empty string as a result? If it is the first time you are using a namespace, evaluate its ns-form in the `user` namespace first.")

(def ^:private error-result-note
  "* clj: Evaluating `*e` will give your information about the error.
   * cljs: Evaluating `(.-stack *e), gives you a stack trace")


(defn- create-pprint-options
  "Create a JavaScript object with pretty printing options"
  [maxLength maxDepth width]
  (let [opts #js {}]
    (when maxLength (set! (.-maxLength opts) maxLength))
    (when maxDepth (set! (.-maxDepth opts) maxDepth))
    (when width (set! (.-width opts) width))
    opts))

(defn evaluate-code+
  "Returns a promise that resolves to the result of evaluating Clojure/ClojureScript code.
   Takes a string of code to evaluate and a session key (clj/cljs/cljc), js/undefined means current session.

   Optional pretty printing options to limit result size:
   - :pprint/maxLength - Maximum number of items in collections before truncation
   - :pprint/maxDepth - Maximum nesting depth before truncation
   - :pprint/width - Maximum line width for formatting"
  [{:ex/keys [dispatch!]
    :calva/keys [code repl-session-key ns]
    :pprint/keys [maxLength maxDepth width]
    :as args}]
  (let [{:keys [valid? balanced-code]
         :as validation} (parinfer/validate-brackets code)]
    (when-not valid?
      (dispatch! [[:app/ax.log :debug "[Server] Code was unbalanced:" code "balanced-code:" balanced-code]]))
    (if-not valid?
      (p/resolved validation)
      (p/let [evaluate (get-in calva/calva-api [:repl :evaluateCode])
              result (-> (p/let [^js evaluation+ (if ns
                                                   (evaluate repl-session-key code ns)
                                                   (evaluate repl-session-key code))]
                           (dispatch! [[:app/ax.log :debug "[Server] Evaluating code:" code]])

                           ;; Apply size limiting if options are provided and result is not empty
                           (let [raw-result (.-result evaluation+)
                                 should-limit? (and (or maxLength maxDepth width)
                                                    (not= "" raw-result))
                                 limited-result (if should-limit?
                                                  (let [prettyPrint-fn (get-in calva/calva-api [:pprint :prettyPrint])
                                                        pprint-opts (create-pprint-options maxLength maxDepth width)]
                                                    (.-value (prettyPrint-fn raw-result pprint-opts)))
                                                  raw-result)]

                             (cond-> {:result limited-result
                                      :ns (.-ns evaluation+)
                                      :stdout (.-output evaluation+)
                                      :stderr (.-errorOutput evaluation+)
                                      :session-key (.-replSessionKey evaluation+)
                                      :note "Remember to check the output tool now and then to see what's happening in the application."}

                               (.-error evaluation+)
                               (merge {:error (.-error evaluation+)
                                       :stacktrace (.-stacktrace evaluation+)})

                               (not ns)
                               (merge {:note no-ns-eval-note})

                               (= "" raw-result)
                               (merge {:note empty-result-note})

                               should-limit?
                               (merge {:note (str "Result was limited using pretty printing options. "
                                                  "Original note: Remember to check the output tool now and then to see what's happening in the application.")}))))
                         (p/catch (fn [err]
                                    (dispatch! [[:app/ax.log :debug "[Server] Evaluation failed:" err]])
                                    {:result "nil"
                                     :stderr (pr-str err)
                                     :note error-result-note})))]
        (clj->js result)))))

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

(defn get-output [{:ex/keys [dispatch!]
                   :calva/keys [since-line]}]
  (clj->js
   (dispatch! [[:app/ax.log :debug "[Server] Getting getting output since line:" since-line]
               [:calva/ax.get-output since-line [:db/get :output/limit]]])))

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

  ;; Example usage of size-limited evaluation
  (def mock-dispatch! (fn [actions] (println "Would dispatch:" (pr-str actions))))

  ;; Test large result with maxLength limiting
  (p/let [result (evaluate-code+
                  {:ex/dispatch! mock-dispatch!
                   :calva/code "(vec (repeat 100 {:id 1 :data \"item-1\"}))"
                   :calva/repl-session-key "cljs"
                   :calva/ns "user"
                   :pprint/maxLength 3})]
    (js->clj result))

  ;; Test nested data with maxDepth limiting
  (p/let [result (evaluate-code+
                  {:ex/dispatch! mock-dispatch!
                   :calva/code "{:level1 {:level2 {:level3 {:level4 \"deep value\"}}}}"
                   :calva/repl-session-key "cljs"
                   :calva/ns "user"
                   :pprint/maxDepth 2})]
    (js->clj result))

  :rcf)

