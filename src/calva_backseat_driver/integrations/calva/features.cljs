(ns calva-backseat-driver.integrations.calva.features
  (:require
   ["vscode" :as vscode]
   [calva-backseat-driver.integrations.calva.api :as calva]
   [calva-backseat-driver.integrations.calva.editor :as editor]
   [promesa.core :as p]))

(def ^:private no-ns-eval-note
  "When evaluating without providing a namespace argument the evaluation is performed in the `user` namespace. Most often this is not what you want, and instead you should be evaluating providing the namespace argument. If it is the first time you are using a namespace, evaluate its ns-form first.")

(def ^:private empty-result-note
  "Not expecting a empty string as a result? If it is the first time you are using a namespace, evaluate its ns-form in the `user` namespace first.")


(def ^:private error-result-note
  "* clj: Evaluating `*e` will give your information about the error.
   * cljs: Evaluating `(.-stack *e), gives you a stack trace")

(defn evaluate-code+
  "Returns a promise that resolves to the result of evaluating Clojure/ClojureScript code.
   Takes a string of code to evaluate and a session key (clj/cljs/cljc), js/undefined means current session."
  [{:ex/keys [dispatch!]
    :calva/keys [code repl-session-key ns]}]
  (p/let [evaluate (get-in calva/calva-api [:repl :evaluateCode])
          result (-> (p/let [^js evaluation+ (if ns
                                               (evaluate repl-session-key code ns)
                                               (evaluate repl-session-key code))]
                       (dispatch! [[:app/ax.log :debug "[Server] Evaluating code:" code]])
                       (cond-> {:result (.-result evaluation+)
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

                         (= "" (.-result evaluation+))
                         (merge {:note empty-result-note})))
                     (p/catch (fn [err]
                                (dispatch! [[:app/ax.log :debug "[Server] Evaluation failed:"
                                             err]])
                                {:result "nil"
                                 :stderr (pr-str err)
                                 :note error-result-note})))]
    (clj->js result)))

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

(defn replace-top-level-form+
  "Replace a top-level form using text targeting and Calva's ranges API"
  [{:ex/keys [dispatch!]
    :calva/keys [file-path line target-line-text new-form]}]
  (dispatch! [[:app/ax.log :debug "[Editor] Replacing form at line" line "in" file-path]])
  (editor/apply-form-edit-by-line-with-text-targeting
   file-path line target-line-text new-form :currentTopLevelForm))

(defn insert-top-level-form+
  "Insert a top-level form using text targeting and Calva's ranges API"
  [{:ex/keys [dispatch!]
    :calva/keys [file-path line target-line-text new-form]}]
  (dispatch! [[:app/ax.log :debug "[Editor] Inserting form at line" line "in" file-path]])
  (editor/apply-form-edit-by-line-with-text-targeting
   file-path line target-line-text new-form :insertionPoint))


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

