(ns calva-backseat-driver.integrations.calva.features
  (:require
   ["vscode" :as vscode]
   [calva-backseat-driver.bracket-balance :as balance]
   [calva-backseat-driver.integrations.calva.api :as calva]
   [calva-backseat-driver.integrations.calva.editor :as editor]
   [clojure.string :as string]
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
  (let [inferred (balance/infer-parens code)
        balancded-code (if (:success inferred)
                         (:text inferred)
                         code)
        balancing-ocurred? (not= code balancded-code)]
    (when balancing-ocurred?
      (dispatch! [[:app/ax.log :debug "[Server] Code was unbalanced:" code "balancded-code:" balancded-code]]))
    (p/let [evaluate (get-in calva/calva-api [:repl :evaluateCode])
            result (-> (p/let [^js evaluation+ (if ns
                                                 (evaluate repl-session-key balancded-code ns)
                                                 (evaluate repl-session-key balancded-code))]
                         (dispatch! [[:app/ax.log :debug "[Server] Evaluating code:" balancded-code]])
                         (cond-> {:result (.-result evaluation+)
                                  :ns (.-ns evaluation+)
                                  :stdout (.-output evaluation+)
                                  :stderr (.-errorOutput evaluation+)
                                  :session-key (.-replSessionKey evaluation+)
                                  :note "Remember to check the output tool now and then to see what's happening in the application."}

                           balancing-ocurred?
                           (merge
                            {:balancing-note "The code provided for evaluation had unbalanced brackets. The code was automatically balanced before evaluation. Use the code in the `balanced-code` to correct your code on record."
                             :balanced-code balancded-code})

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
      (clj->js result))))

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

(defn- normalize-file-content [content]
  (-> content str string/trim (str "\n")))

(defn- get-directory-from-path [file-path]
  (let [path-parts (string/split file-path #"/")]
    (string/join "/" (butlast path-parts))))

(defn structural-create-file+
  "Create a new Clojure file with exact content using vscode/workspace.fs API"
  [{:ex/keys [dispatch!]
    :calva/keys [file-path content]}]
  (dispatch! [[:app/ax.log :debug "[Editor] Creating file" file-path]])
  (let [inferred (balance/infer-parens content)
        balanced-content (if (:success inferred)
                           (:text inferred)
                           content)
        balancing-occurred? (not= content balanced-content)]
    (when balancing-occurred?
      (dispatch! [[:app/ax.log :debug "[Editor] Content was unbalanced, balanced before creation"]]))
    (p/let [uri (vscode/Uri.file file-path)
            normalized-content (normalize-file-content balanced-content)
            content-bytes (.encode (js/TextEncoder.) normalized-content)
            directory-path (get-directory-from-path file-path)
            directory-uri (vscode/Uri.file directory-path)]
      (p/-> (vscode/workspace.fs.createDirectory directory-uri)
            (p/catch (fn [_] nil)) ;; Ignore if directory already exists
            (p/then (fn [_]
                      (vscode/workspace.fs.writeFile uri content-bytes)))
            (p/then (fn [_]
                      (cond-> {:success true
                               :file-path file-path
                               :message "File created successfully"}
                        balancing-occurred?
                        (merge {:balancing-note "The code provided had unbalanced brackets. The code was automatically balanced before creating file."
                                :balanced-code balanced-content}))))
            (p/catch (fn [error]
                       (dispatch! [[:app/ax.log :error "[Editor] Failed to create file" file-path error]])
                       {:success false
                        :error (.-message error)
                        :file-path file-path}))))))

(defn append-code+
  "Append a top-level form to the end of a file at guaranteed top level"
  [{:ex/keys [dispatch!]
    :calva/keys [file-path code]}]
  (dispatch! [[:app/ax.log :debug "[Editor] Appending code to end of" file-path]])
  (let [inferred (balance/infer-parens code)
        balanced-form (if (:success inferred)
                       (:text inferred)
                       code)
        balancing-occurred? (not= code balanced-form)]
    (when balancing-occurred?
      (dispatch! [[:app/ax.log :debug "[Editor] Form was unbalanced, balanced before appending"]]))
    (p/let [uri (vscode/Uri.file file-path)
            ^js vscode-document (vscode/workspace.openTextDocument uri)
            ;; Get the end position of the document
            last-line-number (.-lineCount vscode-document)
            end-position (vscode/Position. last-line-number 0)
            ;; Check if we need to add spacing based on the last line
            last-line-text (if (pos? last-line-number)
                             (.-text (.lineAt vscode-document (dec last-line-number)))
                             "")
            needs-spacing? (and (pos? last-line-number)
                                (not (string/blank? last-line-text)))
            ;; Create the text to append with proper spacing
            spacing (if needs-spacing? "\n\n" "\n")
            append-text (str spacing (string/trim balanced-form) "\n")
            ;; Create edit operation
            edit (vscode/TextEdit.insert end-position append-text)
            workspace-edit (vscode/WorkspaceEdit.)]
      ;; Apply the edit
      (.set workspace-edit uri #js [edit])
      (p/-> (vscode/workspace.applyEdit workspace-edit)
            (p/then (fn [success]
                      (if success
                        (do
                          ;; Save the document synchronously like the existing editor code
                          (.save vscode-document)
                          (cond-> {:success true
                                   :appended-at-end true}
                            balancing-occurred?
                            (merge {:balancing-note "The code provided had unbalanced brackets. The code was automatically balanced before appending."
                                    :balanced-code balanced-form})))
                        {:success false
                         :error "Failed to apply workspace edit"})))
            (p/catch (fn [error]
                       (dispatch! [[:app/ax.log :error "[Editor] Failed to append code to file" file-path error]])
                       {:success false
                        :error (.-message error)}))))))


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

