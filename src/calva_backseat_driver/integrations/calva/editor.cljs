(ns calva-backseat-driver.integrations.calva.editor
  (:require
   ["vscode" :as vscode]
   [calva-backseat-driver.integrations.calva.api :as calva]
   [calva-backseat-driver.integrations.parinfer :as parinfer]
   [calva-backseat-driver.integrations.calva.editor-util :as util]
   [clojure.string :as string]
   [promesa.core :as p]))

(defn- get-document-from-path [path]
  (let [uri (vscode/Uri.file path)]
    (.openTextDocument vscode/workspace uri)))


(defn- get-ranges-form-data-by-line
  "Returns the raw Calva API `ranges` object for `ranged-fn-key` at `line-number` (1-indexed),
   in the document at the absolute `file-path`."
  [file-path line-number ranges-fn-key]
  (p/let [^js vscode-document (get-document-from-path file-path)
          vscode-position (vscode/Position. (dec line-number) 0)]
    {:vscode-document vscode-document
     :ranges-object (if (= :insertionPoint ranges-fn-key)
                      [(vscode/Range. vscode-position vscode-position), ""]
                      ((get-in calva/calva-api [:ranges ranges-fn-key]) vscode-document vscode-position))}))

(defn- edit-replace-range [file-path vscode-range new-text]
  (p/let [^js vscode-document (get-document-from-path file-path)]
    ((get-in calva/calva-api [:editor :replace]) vscode-document vscode-range new-text)))

(def ^:private severity-map
  "Map VS Code diagnostic severity levels to keywords"
  {0 :error
   1 :warning
   2 :info
   3 :hint})

(defn- diagnostic->clj
  "Convert a VS Code diagnostic object to a pure Clojure data structure"
  [^js diag]
  (when diag
    {:source (.-source diag)
     :message (.-message diag)
     :severity (severity-map (.-severity diag) :unknown)
     :range (let [range (.-range diag)]
              {:start {:line (.. range -start -line)
                       :character (.. range -start -character)}
               :end {:line (.. range -end -line)
                     :character (.. range -end -character)}})
     :code (.-code diag)}))

(defn- diagnostics->clj
  "Convert a collection of VS Code diagnostic objects to a vector of Clojure data structures"
  [diagnostics]
  (when diagnostics
    (mapv diagnostic->clj diagnostics)))

(defn- get-diagnostics-for-file
  "Get clj-kondo diagnostics for a file and convert to Clojure data structures"
  [file-path]
  (p/let [uri (vscode/Uri.file file-path)
          diagnostics-raw (vscode/languages.getDiagnostics uri)
          diagnostics (diagnostics->clj diagnostics-raw)]
    (filter (fn [d]
              (#{"clj-kondo" "clojure-lsp"} (:source d)))
            diagnostics)))

(comment
  (get-diagnostics-for-file "/Users/pez/Projects/calva-mcp-server/test-projects/example/src/mini/playground.clj")
  :rcf)

(defn- poll-diagnostics+ [file-path diagnostics-before-edit]
  (p/loop [attempts 0]
    (p/let [_ (p/delay 10)
            diags (get-diagnostics-for-file file-path)]
      (if (or (not= diags diagnostics-before-edit)
              (>= attempts 100))
        diags
        (p/recur (inc attempts))))))

(defn- starts-with-comment?
  "Check if text starts with a comment character after trimming whitespace"
  [text]
  (when text
    (-> text str string/trim (string/starts-with? ";"))))

(defn- validate-edit-inputs
  "Validate that neither target-line nor new-form start with comments"
  [target-line new-form]
  (cond
    (not target-line)
    {:valid? false
     :error "No target line text provided. Provide an empty string to target an empty line."}

    (not new-form)
    {:valid? false
     :error "No insert/replace text provided."}

    (starts-with-comment? target-line)
    {:valid? false
     :error "Target line text cannot start with a comment (;). You can only target forms/sexpressions. (To edit line comments, use your line based editing tools.)"}

    (starts-with-comment? new-form)
    {:valid? false
     :error "Replacement form cannot start with a comment (;). You can only insert forms/sexpressions with this tool. (To edit line comments, use your line based editing tools.)"}

    :else
    {:valid? true}))

(defn- find-target-line-by-text
  "Find the actual line number by searching for target text within a window around the initial line.
   Returns the line number (1-indexed) where the target text is found, or nil if not found."
  [{:editor/keys [^js vscode-document line-number target-text search-padding]}]
  (when-let [found-line (util/find-target-line-by-text (.getText vscode-document) target-text (dec line-number) search-padding)]
    (inc found-line)))

(defn- validate-top-level-form-targeting
  "Validate that target text matches the start of the top-level form at the given position.
   Returns validation result map."
  [{:editor/keys [file-path line-number target-text]}]
  (p/let [form-data (get-ranges-form-data-by-line file-path line-number :currentTopLevelForm)
          top-level-form-text (second (:ranges-object form-data))]
    (if (util/form-first-line-starts-target-text? target-text top-level-form-text)
      {:valid? true}
      {:valid? false
       :validation-error "The target text does not match the first line of a top level form in the vicinity of the target line."})))

(defn- targeting-error-result [{:keys [doc-text line-number context-padding target-line error-msg extra-keys]}]
  (let [{:editor/keys [file-context matched-line-in-context]}
        (util/get-context-lines doc-text line-number context-padding target-line)
        remedy (util/get-remedy-for-targeting matched-line-in-context)]
    (merge {:success false
            :error error-msg
            :remedy remedy
            :file-context file-context}
           extra-keys)))

(defn- resolve-edit-target+
  "Find target line by text and validate it matches a top-level form.
   Returns {:resolved? true :line-number N} or {:resolved? false :result error-map}"
  [{:editor/keys [file-path line-number target-line search-padding context-padding]}]
  (p/let [^js vscode-document (get-document-from-path file-path)
          doc-text (.getText vscode-document)
          actual-line-number (find-target-line-by-text {:editor/vscode-document vscode-document
                                                        :editor/line-number line-number
                                                        :editor/target-text target-line
                                                        :editor/search-padding search-padding})]
    (if-not actual-line-number
      {:resolved? false
       :result (targeting-error-result
                {:doc-text doc-text
                 :line-number line-number
                 :context-padding context-padding
                 :target-line target-line
                 :error-msg (str "Target line text not found. Expected: '" target-line "' near line " line-number)})}
      (p/let [text-validation (validate-top-level-form-targeting
                               {:editor/file-path file-path
                                :editor/line-number actual-line-number
                                :editor/target-text target-line})]
        (if-not (:valid? text-validation)
          {:resolved? false
           :result (targeting-error-result
                    {:doc-text doc-text
                     :line-number line-number
                     :context-padding context-padding
                     :target-line target-line
                     :error-msg (str "Target text validation failed near line: " line-number)
                     :extra-keys {:validation-error (:validation-error text-validation)}})}
          {:resolved? true
           :line-number actual-line-number})))))

(defn- compute-effective-range [^js vscode-document form-range ranges-fn-key trimmed-form]
  (if (and (string/blank? trimmed-form)
           (not= :insertionPoint ranges-fn-key))
    (let [start-line (.. form-range -start -line)
          end-line (.. form-range -end -line)
          total-lines (.-lineCount vscode-document)
          next-line (inc end-line)
          effective-start (if (and (pos? start-line)
                                   (string/blank? (.-text (.lineAt vscode-document (dec start-line)))))
                            (vscode/Position. (dec start-line) 0)
                            (.-start form-range))
          effective-end (if (< next-line total-lines)
                          (vscode/Position. next-line 0)
                          (.-end form-range))]
      (vscode/Range. effective-start effective-end))
    form-range))

(defn- apply-edit-and-report+
  "Apply a form edit and report diagnostics. Assumes targeting is already validated."
  [{:editor/keys [file-path line-number original-line-number new-form ranges-fn-key]}]
  (let [validation-result (parinfer/validate-brackets new-form)]
    (if-not (:valid? validation-result)
      (p/resolved (assoc validation-result :success false))
      (p/let [form-data (get-ranges-form-data-by-line file-path line-number ranges-fn-key)
              diagnostics-before-edit (get-diagnostics-for-file file-path)
              ^js vscode-document (get-document-from-path file-path)
              trimmed-form (string/trim new-form)
              text (if (= :insertionPoint ranges-fn-key) (str trimmed-form "\n\n") trimmed-form)
              form-range (first (:ranges-object form-data))
              effective-range (compute-effective-range vscode-document form-range ranges-fn-key trimmed-form)
              edit-result (edit-replace-range file-path effective-range text)
              _ (.save vscode-document)
              diagnostics-after-edit (poll-diagnostics+ file-path diagnostics-before-edit)]
        (if edit-result
          (cond-> {:success true
                   :diagnostics-before-edit diagnostics-before-edit
                   :diagnostics-after-edit diagnostics-after-edit}
            (not= line-number original-line-number)
            (assoc :actual-line-used line-number))
          {:success false
           :diagnostics-before-edit diagnostics-before-edit})))))

(defn apply-form-edit-by-line-with-text-targeting
  "Apply a form edit by line number with text-based targeting for better accuracy.
   Searches for target-line text within a configurable window around the specified line number.
   For insertions, the new form is inserted before the targeted form."
  [opts]
  (let [validation (validate-edit-inputs (:editor/target-line opts) (:editor/new-form opts))]
    (if-not (:valid? validation)
      {:success false :error (:error validation)}
      (-> (p/let [targeting (resolve-edit-target+ opts)]
            (if-not (:resolved? targeting)
              (:result targeting)
              (apply-edit-and-report+
               (assoc opts
                      :editor/line-number (:line-number targeting)
                      :editor/original-line-number (:editor/line-number opts)))))
          (p/catch (fn [e] {:success false :error (.-message e)}))))))

(comment

  (p/let [edit-result (apply-form-edit-by-line-with-text-targeting
                       {:editor/file-path "/Users/pez/Projects/calva-mcp-server/test-projects/example/src/mini/playground.clj"
                        :editor/line-number 214
                        :editor/target-line ";foo"
                        :editor/new-form "(foo"
                        :editor/ranges-fn-key :currentTopLevelForm
                        :editor/search-padding 2
                        :editor/context-padding 10})]
    (def edit-result edit-result))

  ;; Test validation - these should fail with proper error messages
  (apply-form-edit-by-line-with-text-targeting
   {:editor/file-path "/some/file.clj"
    :editor/line-number 10
    :editor/target-line "; This is a comment line"  ; ← This should fail
    :editor/new-form "(defn new-fn [])"
    :editor/ranges-fn-key :currentTopLevelForm
    :editor/search-padding 2
    :editor/context-padding 10})

  (apply-form-edit-by-line-with-text-targeting
   {:editor/file-path "/some/file.clj"
    :editor/line-number 10
    :editor/target-line "(defn old-fn [])"
    :editor/new-form "; This is a comment replacement"  ; ← This should fail
    :editor/ranges-fn-key :currentTopLevelForm
    :editor/search-padding 2
    :editor/context-padding 10})

  ;; This should succeed
  (p/let [the-result (apply-form-edit-by-line-with-text-targeting
                      {:editor/file-path "/some/file.clj"
                       :editor/line-number 10
                       :editor/target-line "(defn old-fn [])"
                       :editor/new-form "(defn new-fn [])"
                       :editor/ranges-fn-key :currentTopLevelForm
                       :editor/search-padding 2
                       :editor/context-padding 10})]
    (def the-result the-result))

  :rcf)

(defn- normalize-file-content [content]
  (-> content str string/trim (str "\n")))

(defn- get-directory-from-path [file-path]
  (let [path-parts (string/split file-path #"/")]
    (string/join "/" (butlast path-parts))))

(defn structural-create-file+
  "Create a new Clojure file with exact content using vscode/workspace.fs API"
  [file-path content]
  (let [validation-result (parinfer/validate-brackets content)]
    (if-not (:valid? validation-result)
      ;; REFUSE to create - return validation failure
      (p/resolved (assoc validation-result
                         :file-path file-path))
      ;; Valid brackets - proceed with file creation
      (p/let [uri (vscode/Uri.file file-path)
              normalized-content (normalize-file-content content)
              content-bytes (.encode (js/TextEncoder.) normalized-content)
              directory-path (get-directory-from-path file-path)
              directory-uri (vscode/Uri.file directory-path)]
        (p/-> (vscode/workspace.fs.createDirectory directory-uri)
              (p/catch (fn [_] nil))
              (p/then (fn [_]
                        (vscode/workspace.fs.writeFile uri content-bytes)))
              (p/then (fn [_]
                        (p/let [_ (p/delay 1000)
                                diagnostics-after-edit (get-diagnostics-for-file file-path)]
                          {:success true
                           :file-path file-path
                           :message "File created successfully"
                           :diagnostics-after-edit diagnostics-after-edit})))
              (p/catch (fn [error]
                         {:success false
                          :error (.-message error)
                          :file-path file-path})))))))

(defn append-code+
  "Append a top-level form to the end of a file at guaranteed top level"
  [file-path code]
  (let [validation-result (parinfer/validate-brackets code)]
    (if-not (:valid? validation-result)
      ;; REFUSE to append - return validation failure
      (p/resolved validation-result)
      ;; Valid brackets - proceed with append
      (-> (p/let [uri (vscode/Uri.file file-path)
                  ^js vscode-document (vscode/workspace.openTextDocument uri)
                  diagnostics-before-edit (get-diagnostics-for-file file-path)
                  last-line-number (.-lineCount vscode-document)
                  end-position (vscode/Position. last-line-number 0)
                  last-line-text (if (pos? last-line-number)
                                   (.-text (.lineAt vscode-document (dec last-line-number)))
                                   "")
                  needs-spacing? (and (pos? last-line-number)
                                      (not (string/blank? last-line-text)))
                  spacing (if needs-spacing? "\n\n" "\n")
                  append-text (str spacing (string/trim code) "\n")
                  edit (vscode/TextEdit.insert end-position append-text)
                  workspace-edit (vscode/WorkspaceEdit.)
                  _ (.set workspace-edit uri #js [edit])
                  edit-result (vscode/workspace.applyEdit workspace-edit)
                  _ (.save vscode-document)
                  diagnostics-after-edit (poll-diagnostics+ file-path diagnostics-before-edit)]
            (if edit-result
              {:success true
               :appended-at-end true
               :diagnostics-before-edit diagnostics-before-edit
               :diagnostics-after-edit diagnostics-after-edit}
              {:success false
               :diagnostics-before-edit diagnostics-before-edit
               :error "Failed to apply workspace edit"}))
          (p/catch (fn [error]
                     {:success false
                      :error (.-message error)}))))))
