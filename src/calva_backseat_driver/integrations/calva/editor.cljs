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

(defn get-editor-from-document [vscode-document]
  (let [visible-editor (->> vscode/window.visibleTextEditors
                            (filter (fn [doc] (= (.-document doc) vscode-document)))
                            first)]
    (if visible-editor
      visible-editor
      (.showTextDocument vscode/window vscode-document))))

(defn- get-editor-from-file-path [file-path]
  (p/let [vscode-document (get-document-from-path file-path)]
    (get-editor-from-document vscode-document)))


(defn- get-ranges-form-data-by-line
  "Returns the raw Calva API `ranges` object for `ranged-fn-key` at `line-number` (1-indexed),
   in the document at the absolute `file-path`."
  [file-path line-number ranges-fn-key]
  (p/let [^js vscode-document (get-document-from-path file-path)
          vscode-editor (get-editor-from-document vscode-document)
          vscode-position (vscode/Position. (dec line-number) 0)]
    {:vscode-document vscode-document
     :ranges-object (if (= :insertionPoint ranges-fn-key)
                      [(vscode/Range. vscode-position vscode-position), ""]
                      ((get-in calva/calva-api [:ranges ranges-fn-key]) vscode-editor vscode-position))}))

(defn- edit-replace-range [file-path vscode-range new-text]
  (p/let [^js editor (get-editor-from-file-path file-path)]
    (.revealRange editor vscode-range)
    ((get-in calva/calva-api [:editor :replace]) editor vscode-range new-text)))

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
    (if (util/target-text-is-first-line? target-text top-level-form-text)
      {:valid? true}
      {:valid? false
       :validation-error "The target text does not match the first line of a top level form in the vicinity of the target line."})))

(defn apply-form-edit-by-line-with-text-targeting
  "Apply a form edit by line number with text-based targeting for better accuracy.
   Searches for target-line text within a configurable window around the specified line number.
   For insertions, the new form is inserted before the targeted form."
  [{:editor/keys [file-path line-number target-line new-form ranges-fn-key search-padding context-padding]}]
  (let [validation (validate-edit-inputs target-line new-form)]
    (if (:valid? validation)
      (-> (p/let [^js vscode-document (get-document-from-path file-path)
                  doc-text (.getText vscode-document)
                  actual-line-number (find-target-line-by-text {:editor/vscode-document vscode-document
                                                                :editor/line-number line-number
                                                                :editor/target-text target-line
                                                                :editor/search-padding search-padding})]
            (if-not actual-line-number
              (let [file-context (util/get-context-lines doc-text line-number context-padding)
                    remedy (util/get-remedy-for-targeting file-context target-line)]
                {:success false
                 :error (str "Target line text not found. Expected: '" target-line "' near line " line-number)
                 :remedy remedy
                 :file-context file-context})
              (p/let [final-line-number actual-line-number
                      text-validation (validate-top-level-form-targeting {:editor/file-path file-path
                                                                          :editor/line-number final-line-number
                                                                          :editor/target-text target-line})]
                (if (not (:valid? text-validation))
                  (let [file-context (util/get-context-lines doc-text line-number context-padding)
                        remedy (util/get-remedy-for-targeting file-context target-line)]
                    {:success false
                     :error (str "Target text validation failed near line: " line-number)
                     :validation-error (:validation-error text-validation)
                     :remedy remedy
                     :file-context file-context})

                  ;; Validation done. Proceed with form editing
                  (p/let [balance-result (some-> (parinfer/infer-brackets new-form)
                                                 (js->clj :keywordize-keys true))
                          form-data (get-ranges-form-data-by-line file-path final-line-number ranges-fn-key)
                          diagnostics-before-edit (get-diagnostics-for-file file-path)]
                    (if (:success balance-result)
                      (p/let [balanced-form (:text balance-result)
                              balancing-occurred? (not= new-form balanced-form)
                              text (if (= :insertionPoint ranges-fn-key)
                                     (str (string/trim balanced-form) "\n\n")
                                     balanced-form)
                              edit-result (edit-replace-range file-path
                                                              (first (:ranges-object form-data))
                                                              text)
                              _ (p/delay 1000) ;; TODO: Consider subscribing on diagnistics changes instead
                              diagnostics-after-edit (get-diagnostics-for-file file-path)]
                        (if edit-result
                          (do
                            (.save vscode-document)
                            (cond-> {:success true
                                     :actual-line-used final-line-number
                                     :diagnostics-before-edit diagnostics-before-edit
                                     :diagnostics-after-edit diagnostics-after-edit}

                              balancing-occurred?
                              (merge
                               {:balancing-note "The code provided for editing had unbalanced brackets. The code was automatically balanced before editing. Use the code in the `balanced-code` to correct your code on record."
                                :balanced-code balanced-form})))
                          {:success false
                           :diagnostics-before-edit diagnostics-before-edit}))
                      balance-result))))))
          (p/catch (fn [e]
                     {:success false
                      :error (.-message e)})))
      {:success false
       :error (:error validation)})))

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
  (let [inferred (parinfer/infer-brackets content)
        infer-result (when inferred (js->clj inferred :keywordize-keys true))
        balanced-content (if (:success infer-result)
                           (:text infer-result)
                           content)
        balancing-occurred? (not= content balanced-content)]
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
                      (p/let [_ (p/delay 1000) ;; Wait for diagnostics to update
                              diagnostics-after-edit (get-diagnostics-for-file file-path)]
                        (cond-> {:success true
                                 :file-path file-path
                                 :message "File created successfully"
                                 :diagnostics-after-edit diagnostics-after-edit}
                          balancing-occurred?
                          (merge {:balancing-note "The code provided had unbalanced brackets. The code was automatically balanced before creating file."
                                  :balanced-code balanced-content})))))
            (p/catch (fn [error]
                       {:success false
                        :error (.-message error)
                        :file-path file-path}))))))

(defn append-code+
  "Append a top-level form to the end of a file at guaranteed top level"
  [file-path code]
  (let [inferred (parinfer/infer-brackets code)
        infer-result (when inferred (js->clj inferred :keywordize-keys true))
        balanced-form (if (:success infer-result)
                        (:text infer-result)
                        code)
        balancing-occurred? (not= code balanced-form)]
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
                append-text (str spacing (string/trim balanced-form) "\n")
                edit (vscode/TextEdit.insert end-position append-text)
                workspace-edit (vscode/WorkspaceEdit.)
                _ (.set workspace-edit uri #js [edit])
                edit-result (vscode/workspace.applyEdit workspace-edit)
                _ (p/delay 1000) ;; Wait for diagnostics to update
                diagnostics-after-edit (get-diagnostics-for-file file-path)]
          (.save vscode-document)
          (if edit-result
            (cond-> {:success true
                     :appended-at-end true
                     :diagnostics-before-edit diagnostics-before-edit
                     :diagnostics-after-edit diagnostics-after-edit}
              balancing-occurred?
              (merge {:balancing-note "The code provided had unbalanced brackets. The code was automatically balanced before appending."
                      :balanced-code balanced-form}))
            {:success false
             :diagnostics-before-edit diagnostics-before-edit
             :error "Failed to apply workspace edit"}))
        (p/catch (fn [error]
                   {:success false
                    :error (.-message error)})))))
