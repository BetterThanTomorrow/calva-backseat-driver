(ns calva-backseat-driver.integrations.calva.features
  (:require
   ["vscode" :as vscode]
   [calva-backseat-driver.integrations.calva.api :as calva]
   [calva-backseat-driver.integrations.calva.batch-edit :as batch-edit]
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

(defn- blank-who? [who]
  (or (nil? who) (and (string? who) (string/blank? who))))

(defn validate-who
  "Returns nil if valid, error string if not. Pure function."
  [who]
  (cond
    (blank-who? who)
    "The `who` parameter is required. Provide a slug identifying your agent (e.g. \"copilot\")."

    (contains? reserved-whos who)
    (str "The `who` value \"" who "\" is reserved by Calva. Choose a different identifier.")

    :else nil))

(defn- get-eval-config []
  (let [config (vscode/workspace.getConfiguration "calva-backseat-driver.evaluation")]
    {:max-length (.get config "maxLength")
     :max-depth (.get config "maxDepth")}))

;; Session listing and validation

(defn list-sessions+
  "Returns a promise that resolves to a list of available REPL sessions."
  [{:ex/keys [dispatch!]}]
  (dispatch! [[:app/ax.log :debug "[Server] Listing REPL sessions"]])
  (p/let [sessions ((get-in calva/calva-api [:repl :listSessions]))]
    #js {:sessions sessions}))

(defn- validate-session-key+
  "Validates a session key against available sessions.
   Returns {:valid? true} or {:valid? false :error ... :available-sessions ...}"
  [session-key]
  (p/let [sessions ((get-in calva/calva-api [:repl :listSessions]))
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
                                 sessions)})))

(defn- get-nrepl-eval-options [max-length max-depth]
  (when (or max-length max-depth)
    #js {:pprintOptions #js {:enabled true
                             :printEngine "pprint"
                             :maxLength max-length
                             :maxDepth max-depth}}))

(defn- handle-eval-error+ [dispatch! err]
  (dispatch! [[:app/ax.log :debug "[Server] Evaluation failed:" err]])
  (let [msg (str (.-message err))]
    (if (re-find #"reserved" msg)
      {:result "nil"
       :error msg
       :notes ["The `who` value you provided is reserved. Choose a different identifier."]}
      {:result "nil"
       :stderr (pr-str err)
       :notes [error-result-note]})))

(defn- validate-eval-inputs+ [code repl-session-key dispatch!]
  (let [{:keys [valid? balanced-code] :as validation} (parinfer/validate-brackets code)]
    (when-not valid?
      (dispatch! [[:app/ax.log :debug "[Server] Code was unbalanced:" code "balanced-code:" balanced-code]]))
    (if-not valid?
      (p/resolved {:error validation})
      (p/let [session-validation (validate-session-key+ repl-session-key)]
        (if-not (:valid? session-validation)
          {:error session-validation}
          {:valid? true})))))

(defn evaluate-code+
  "Returns a promise that resolves to the result of evaluating Clojure/ClojureScript code.
   Pre-validates session key against available sessions before evaluation."
  [{:ex/keys [dispatch!]
    :calva/keys [code repl-session-key ns who description]}]
  (p/let [input-validation (validate-eval-inputs+ code repl-session-key dispatch!)]
    (if (:error input-validation)
      (:error input-validation)
      (let [evaluate-fn (get-in calva/calva-api [:repl :evaluate])]
        (p/let [{:keys [max-length max-depth]} (get-eval-config)
                nrepl-eval-options (get-nrepl-eval-options max-length max-depth)
                result (-> (p/let [options-js (clj->js
                                               (cond-> {:sessionKey repl-session-key}
                                                 ns (assoc :ns ns)
                                                 who (assoc :who who)
                                                 description (assoc :description description)
                                                 nrepl-eval-options (assoc :nReplOptions nrepl-eval-options)))
                                   ^js evaluation+ (evaluate-fn code options-js)]
                             (dispatch! [[:app/ax.log :debug "[Server] Evaluating code:" code]])
                             (let [other-whos (some-> (.-otherWhosSinceLast evaluation+)
                                                      (js->clj))
                                   notes (cond-> []
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
                           (p/catch (fn [err] (handle-eval-error+ dispatch! err))))]
          result)))))


(defn get-clojuredocs+ [{:ex/keys [dispatch!]
                         :calva/keys [clojure-symbol]}]
  (dispatch! [[:app/ax.log :debug "[Server] Getting clojuredocs for:" clojure-symbol]])
  ((get-in calva/calva-api [:info :getClojureDocsDotOrg]) clojure-symbol "user"))

(defn get-symbol-info+ [{:ex/keys [dispatch!]
                         :calva/keys [clojure-symbol ns repl-session-key]}]
  (dispatch! [[:app/ax.log :debug "[Server] Getting symbol info for:" clojure-symbol]])
  ((get-in calva/calva-api [:info :getSymbolInfo]) clojure-symbol repl-session-key ns))

(defn subscribe-to-output [{:ex/keys [dispatch!]
                            :calva/keys [on-output]}]
  ((get-in calva/calva-api [:repl :onOutputLogged])
   (fn [message]
     (dispatch! (conj on-output (js->clj message :keywordize-keys true))))))

(defn query-output [{:ex/keys [dispatch!]
                     :calva/keys [query-edn-str inputs]}]
  (dispatch! [[:app/ax.log :debug "[Server] Querying output log with:" query-edn-str]
              [:calva/ax.query-output query-edn-str inputs]]))

(defn load-file+
  "Loads/evaluates a Clojure file through Calva's connected REPL.
   Returns a promise resolving to the string result of the last evaluated form."
  [{:ex/keys [dispatch!]
    :calva/keys [file-path repl-session-key who]}]
  (dispatch! [[:app/ax.log :debug "[Server] Loading file:" file-path]])
  (-> (p/let [result (vscode/commands.executeCommand
                      "calva.loadFile"
                      (cond-> #js {:path file-path :silent true}
                        repl-session-key (doto (aset "sessionKey" repl-session-key))
                        who (doto (aset "who" who))))]
        {:result (or result "nil")})
      (p/catch (fn [err]
                 (dispatch! [[:app/ax.log :debug "[Server] Load file failed:" err]])
                 {:error (.-message err)}))))

(defn- get-editor-config []
  (let [config (vscode/workspace.getConfiguration "calva-backseat-driver.editor")]
    {:search-padding (.get config "fuzzyLineTargetingPadding")
     :context-padding (.get config "lineContextResponsePadding")}))



(defn- form-edit+ [{:ex/keys [dispatch!]
                    :calva/keys [file-path line target-line-text new-form]
                    :keys [ranges-fn-key log-verb]}]
  (let [{:keys [search-padding context-padding]} (get-editor-config)]
    (dispatch! [[:app/ax.log :debug "[Editor]" log-verb "form at line" line "in" file-path]])
    (editor/apply-form-edit-by-line-with-text-targeting
     {:editor/file-path file-path
      :editor/line-number line
      :editor/target-line target-line-text
      :editor/new-form new-form
      :editor/ranges-fn-key ranges-fn-key
      :editor/search-padding search-padding
      :editor/context-padding context-padding})))

(defn replace-top-level-form+
  "Replace a top-level form using text targeting and Calva's ranges API"
  [m]
  (form-edit+ (assoc m :ranges-fn-key :currentTopLevelForm :log-verb "Replacing")))

(defn insert-top-level-form+
  "Insert a top-level form using text targeting and Calva's ranges API"
  [m]
  (form-edit+ (assoc m :ranges-fn-key :insertionPoint :log-verb "Inserting")))

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


(defn- apply-single-edit+
  "Apply a single edit operation. Returns {:success true ...} or {:success false ...}"
  [edit search-padding context-padding]
  (case (:type edit)
    "create"  (editor/create-file-core+ (:filePath edit) (:content edit))
    "append"  (editor/append-code-core+ (:filePath edit) (:code edit))
    "replace" (editor/apply-form-edit-by-line-with-text-targeting
               {:editor/file-path (:filePath edit)
                :editor/line-number (:line edit)
                :editor/target-line (:targetLineText edit)
                :editor/new-form (:newForm edit)
                :editor/ranges-fn-key :currentTopLevelForm
                :editor/search-padding search-padding
                :editor/context-padding context-padding})
    "insert"  (editor/apply-form-edit-by-line-with-text-targeting
               {:editor/file-path (:filePath edit)
                :editor/line-number (:line edit)
                :editor/target-line (:targetLineText edit)
                :editor/new-form (:newForm edit)
                :editor/ranges-fn-key :insertionPoint
                :editor/search-padding search-padding
                :editor/context-padding context-padding})
    (p/resolved {:success false :error (str "Unknown edit type: " (:type edit))})))

(defn- apply-file-batch+
  "Apply all edits for a single file. Sorts edits, applies sequentially,
   continues on failure. Polls diagnostics once at end."
  [file-path edits search-padding context-padding]
  (let [sorted-edits (batch-edit/sort-edits-for-file edits)]
    (p/let [diagnostics-before-edit (editor/get-diagnostics-for-file file-path)
            results (p/loop [remaining sorted-edits
                             acc []]
                      (if (empty? remaining)
                        acc
                        (let [edit (first remaining)]
                          (p/let [result (apply-single-edit+ edit search-padding context-padding)]
                            (p/recur (rest remaining)
                                     (conj acc (-> result
                                                   (assoc :index (:index edit))
                                                   (dissoc :diagnostics-before-edit :diagnostics-after-edit))))))))
            diagnostics-after-edit (editor/poll-diagnostics+ file-path diagnostics-before-edit)]
      {:file-path file-path
       :edits results
       :diagnostics-before-edit diagnostics-before-edit
       :diagnostics-after-edit diagnostics-after-edit})))

(defn edit-files+
  "Execute a batch of structural edits across one or more files.
   Pre-validates schema, groups by file, sorts within each file,
   applies sequentially (continues on failure), and reports per-file diagnostics."
  [{:calva/keys [edits]}]
  (let [validation-errors (batch-edit/validate-edit-schema edits)]
    (if validation-errors
      (p/resolved {:error "Schema validation failed — no edits were applied"
                   :validation-errors validation-errors})
      (let [indexed-edits (map-indexed (fn [idx edit] (assoc edit :index idx)) edits)
            grouped (group-by :filePath indexed-edits)
            {:keys [search-padding context-padding]} (get-editor-config)
            file-paths (keys grouped)]
        (p/let [file-results (p/loop [remaining file-paths
                                      acc {}]
                               (if (empty? remaining)
                                 acc
                                 (let [file-path (first remaining)]
                                   (p/let [result (apply-file-batch+ file-path (get grouped file-path) search-padding context-padding)]
                                     (p/recur (rest remaining)
                                              (assoc acc file-path result))))))]
          (let [total-edits (count edits)
                applied-count (reduce (fn [n [_ file-result]]
                                        (+ n (count (filter :success (:edits file-result)))))
                                      0
                                      file-results)
                file-count (count file-results)]
            {:summary (str applied-count "/" total-edits " edits applied across " file-count " file" (when (not= 1 file-count) "s"))
             :files file-results}))))))

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

