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

(def ^:private search-window 2)

(defn- find-target-line-by-text
  "Find the actual line number by searching for target text within a window around the initial line.
   Returns the line number (1-indexed) where the target text is found, or nil if not found."
  [^js vscode-document initial-line-number target-text]
  (when-let [found-line (util/find-target-line-by-text (.getText vscode-document) target-text (dec initial-line-number) search-window)]
    (inc found-line)))

(def remedy "Read the whole file again, in one go. Then perform the edit, targeting the correct line and the first line of the existing top level form starting at that line.")

(defn- validate-top-level-form-targeting
  "Validate that target text matches the start of the top-level form at the given position.
   Returns validation result map."
  [file-path line-number target-text]
  (p/let [form-data (get-ranges-form-data-by-line file-path line-number :currentTopLevelForm)
          top-level-form-text (second (:ranges-object form-data))]
    (if (util/target-text-is-first-line? target-text top-level-form-text)
      {:valid? true}
      {:valid? false
       :validation-error "The target text does not match the first line of a top level form in the vincinty of the target line."
       :remedy remedy})))

(defn apply-form-edit-by-line-with-text-targeting
  "Apply a form edit by line number with text-based targeting for better accuracy.
   Searches for target-line text within a 2-line window around the specified line number.
   For insertions, the new form is inserted before the targeted form."
  [file-path line-number target-line new-form ranges-fn-key]
  (let [validation (validate-edit-inputs target-line new-form)]
    (if (:valid? validation)
      (-> (p/let [vscode-document (get-document-from-path file-path)
                  actual-line-number (find-target-line-by-text vscode-document line-number target-line)]
            (if-not actual-line-number
              {:success false
               :error (str "Target line text not found. Expected: '" target-line "' near line " line-number)
               :remedy remedy}
              (p/let [final-line-number actual-line-number
                      text-validation (validate-top-level-form-targeting file-path final-line-number target-line)]
                (if (not (:valid? text-validation))
                  {:success false
                   :error (str "Target text validation failed near line: " line-number)
                   :validation-error (:validation-error text-validation)
                   :remedy (:remedy text-validation)}

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

  (p/let [edit-result (apply-form-edit-by-line-with-text-targeting "/Users/pez/Projects/calva-mcp-server/test-projects/example/src/mini/playground.clj"
                                                                   214
                                                                   ";foo"
                                                                   "(foo"
                                                                   :currentTopLevelForm)]
    (def edit-result edit-result))

  ;; Test validation - these should fail with proper error messages
  (apply-form-edit-by-line-with-text-targeting
   "/some/file.clj"
   10
   "; This is a comment line"  ; ← This should fail
   "(defn new-fn [])"
   :currentTopLevelForm)

  (apply-form-edit-by-line-with-text-targeting
   "/some/file.clj"
   10
   "(defn old-fn [])"
   "; This is a comment replacement"
   :currentTopLevelForm)  ; ← This should fail

  ;; This should succeed
  (p/let [the-result (apply-form-edit-by-line-with-text-targeting
                      "/some/file.clj"
                      10
                      "(defn old-fn [])"
                      "(defn new-fn [])"
                      :currentTopLevelForm)]
    (def the-result the-result))

  :rcf)
