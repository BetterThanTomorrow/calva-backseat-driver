(ns calva-backseat-driver.integrations.calva.editor
  (:require
   ["vscode" :as vscode]
   [calva-backseat-driver.integrations.calva.api :as calva]
   [calva-backseat-driver.integrations.calva.edit-plan :as edit-plan]
   [calva-backseat-driver.integrations.calva.editor-persist :as editor-persist]
   [calva-backseat-driver.integrations.parinfer :as parinfer]
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

(defn- edit-replace-range
  "Apply Calva editor.replace on the given document (same object that will be saved)."
  [^js vscode-document vscode-range new-text]
  ((get-in calva/calva-api [:editor :replace]) vscode-document vscode-range new-text))

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

(defn get-diagnostics-for-file
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

(defn poll-diagnostics+ [file-path diagnostics-before-edit]
  (p/loop [attempts 0]
    (p/let [_ (p/delay 10)
            diags (get-diagnostics-for-file file-path)]
      (if (or (not= diags diagnostics-before-edit)
              (>= attempts 100))
        diags
        (p/recur (inc attempts))))))

(defn- resolve-edit-target+
  "Finds the edit's target line by its text, and checks that it starts a top level form.
   Returns {:resolved? true :line-number N} or {:resolved? false :result error-map}"
  [{:editor/keys [file-path] :as edit}]
  (p/let [^js vscode-document (get-document-from-path file-path)
          document {:document/text (.getText vscode-document)}
          actual-line-number (edit-plan/target-line-number document edit)]
    (if-not actual-line-number
      {:resolved? false
       :result (edit-plan/target-not-found document edit)}
      (p/let [form-data (get-ranges-form-data-by-line file-path actual-line-number :currentTopLevelForm)
              mismatch (edit-plan/form-mismatch document edit (second (:ranges-object form-data)))]
        (if mismatch
          {:resolved? false
           :result mismatch}
          {:resolved? true
           :line-number actual-line-number})))))

(defn- range-as-data
  "Returns the VS Code range `vscode-range` as `[[line character] [line character]]`."
  [^js vscode-range]
  [[(.. vscode-range -start -line) (.. vscode-range -start -character)]
   [(.. vscode-range -end -line) (.. vscode-range -end -character)]])

(defn- vscode-range
  "Returns the VS Code range for `[[line character] [line character]]`."
  [[[start-line start-character] [end-line end-character]]]
  (vscode/Range. start-line start-character end-line end-character))

(defn apply-form-edit+
  "Apply a form edit without diagnostics polling. Assumes targeting is already validated."
  [{:editor/keys [file-path line-number original-line-number new-form ranges-fn-key] :as edit}]
  (let [validation-result (parinfer/validate-brackets new-form)]
    (if-not (:valid? validation-result)
      (p/resolved (assoc validation-result :success false :saved false))
      (p/let [form-data (get-ranges-form-data-by-line file-path line-number ranges-fn-key)
              ^js vscode-document (:vscode-document form-data)
              was-dirty? (.-isDirty vscode-document)
              text-before (.getText vscode-document)
              {:editor/keys [text] plan-range :editor/range} (edit-plan/edit-plan {:document/text text-before}
                                                                                 (range-as-data (first (:ranges-object form-data)))
                                                                                 edit)
              effective-range (vscode-range plan-range)
              range-text (.getText vscode-document effective-range)
              edit-result (edit-replace-range vscode-document effective-range text)
              persist (editor-persist/persist-edit-result+
                       vscode-document was-dirty? edit-result
                       {:text-before text-before
                        :identical-replace? (= range-text text)})]
        (cond-> persist
          (and (:success persist) (not= line-number original-line-number))
          (assoc :actual-line-used line-number))))))

(defn- apply-edit-and-report+
  "Apply a form edit and report diagnostics. Assumes targeting is already validated."
  [{:editor/keys [file-path] :as opts}]
  (p/let [diagnostics-before-edit (get-diagnostics-for-file file-path)
          result (apply-form-edit+ opts)]
    (if (:success result)
      (p/let [diagnostics-after-edit (poll-diagnostics+ file-path diagnostics-before-edit)]
        (assoc result
               :diagnostics-before-edit diagnostics-before-edit
               :diagnostics-after-edit diagnostics-after-edit))
      result)))

(defn apply-form-edit-by-line-with-text-targeting
  "Apply a form edit by line number with text-based targeting for better accuracy.
   Searches for target-line text within a configurable window around the specified line number.
   For insertions, the new form is inserted before the targeted form."
  [opts]
  (or (edit-plan/input-error opts)
      (-> (p/let [targeting (resolve-edit-target+ opts)]
            (if-not (:resolved? targeting)
              (:result targeting)
              (apply-edit-and-report+
               (assoc opts
                      :editor/line-number (:line-number targeting)
                      :editor/original-line-number (:editor/line-number opts)))))
          (p/catch (fn [e] {:success false :saved false :error (.-message e)})))))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
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

(defn create-file-core+
  "Create a new Clojure file with exact content. Returns {:success true :file-path ...} or {:success false ...}"
  [file-path content]
  (let [validation-result (parinfer/validate-brackets content)]
    (if-not (:valid? validation-result)
      (p/resolved (assoc validation-result
                         :file-path file-path))
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
                        {:success true
                         :saved true
                         :file-path file-path}))
              (p/catch (fn [error]
                         {:success false
                          :saved false
                          :error (.-message error)
                          :file-path file-path})))))))

(defn append-code-core+
  "Append a top-level form to the end of a file. Returns {:success true :appended-at-end true} or {:success false ...}"
  [file-path code]
  (let [validation-result (parinfer/validate-brackets code)]
    (if-not (:valid? validation-result)
      (p/resolved (assoc validation-result :saved false))
      (-> (p/let [uri (vscode/Uri.file file-path)
                  ^js vscode-document (vscode/workspace.openTextDocument uri)
                  was-dirty? (.-isDirty vscode-document)
                  text-before (.getText vscode-document)
                  {:editor/keys [text] [[end-line end-character]] :editor/range} (edit-plan/append-plan {:document/text text-before} code)
                  edit (vscode/TextEdit.insert (vscode/Position. end-line end-character) text)
                  workspace-edit (vscode/WorkspaceEdit.)
                  _ (.set workspace-edit uri #js [edit])
                  edit-result (vscode/workspace.applyEdit workspace-edit)
                  persist (editor-persist/persist-edit-result+
                           vscode-document was-dirty? edit-result
                           {:text-before text-before})]
            (cond-> persist
              (:success persist) (assoc :appended-at-end true)))
          (p/catch (fn [error]
                     {:success false
                      :saved false
                      :error (.-message error)}))))))

(defn disk-matches-document?
  "True when on-disk file content equals the open document buffer for file-path.
   Returns nil when the document or disk cannot be read."
  [file-path]
  (-> (p/let [^js vscode-document (get-document-from-path file-path)
              disk-bytes (vscode/workspace.fs.readFile (.-uri vscode-document))
              disk (.decode (js/TextDecoder.) disk-bytes)]
        (= disk (.getText vscode-document)))
      (p/catch (fn [_] nil))))
