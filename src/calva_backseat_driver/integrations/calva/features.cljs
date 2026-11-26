(ns calva-backseat-driver.integrations.calva.features
  "A batch of structural edits: validated as a whole, then applied file by file."
  (:require
   [calva-backseat-driver.integrations.calva.batch-edit :as batch-edit]
   [calva-backseat-driver.integrations.calva.editor :as editor]))

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
    (js/Promise.resolve {:success false :error (str "Unknown edit type: " (:type edit))})))

(defn- ^:async apply-file-batch+
  "Apply all edits for a single file. Sorts edits, applies sequentially,
   continues on failure. Polls diagnostics once at end."
  [file-path edits search-padding context-padding]
  (let [sorted-edits (batch-edit/sort-edits-for-file edits)
        diagnostics-before-edit (await (editor/get-diagnostics-for-file file-path))
        results (loop [remaining sorted-edits
                       acc []]
                  (if (empty? remaining)
                    acc
                    (let [edit (first remaining)
                          result (await (apply-single-edit+ edit search-padding context-padding))]
                      (recur (rest remaining)
                             (conj acc (-> result
                                           (assoc :index (:index edit))
                                           (dissoc :diagnostics-before-edit :diagnostics-after-edit)))))))
        diagnostics-after-edit (await (editor/poll-diagnostics+ file-path diagnostics-before-edit))
        disk-matches-buffer? (await (editor/disk-matches-document? file-path))]
    {:file-path file-path
     :edits results
     :disk-matches-buffer? disk-matches-buffer?
     :diagnostics-before-edit diagnostics-before-edit
     :diagnostics-after-edit diagnostics-after-edit}))

(defn- ^:async apply-file-batches+
  "Apply the edits in `grouped`, a map from file path to its edits, one file after the
   other. Returns a map from file path to the file's result."
  [grouped search-padding context-padding]
  (loop [remaining (keys grouped)
         acc {}]
    (if (empty? remaining)
      acc
      (let [file-path (first remaining)
            result (await (apply-file-batch+ file-path (get grouped file-path) search-padding context-padding))]
        (recur (rest remaining)
               (assoc acc file-path result))))))

(defn ^:async edit-files+
  "Execute a batch of structural edits across one or more files.
   Pre-validates schema, groups by file, sorts within each file,
   applies sequentially (continues on failure), and reports per-file diagnostics."
  [edits {:editor/keys [search-padding context-padding]}]
  (let [validation-errors (batch-edit/validate-edit-schema edits)]
    (if validation-errors
      {:error "Schema validation failed — no edits were applied"
       :validation-errors validation-errors}
      (let [indexed-edits (map-indexed (fn [idx edit] (assoc edit :index idx)) edits)
            grouped (group-by :filePath indexed-edits)
            file-results (await (apply-file-batches+ grouped search-padding context-padding))
            total-edits (count edits)
            applied-count (reduce (fn [n [_ file-result]]
                                    (+ n (count (filter :success (:edits file-result)))))
                                  0
                                  file-results)
            file-count (count file-results)]
        {:summary (str applied-count "/" total-edits " edits applied across " file-count " file" (when (not= 1 file-count) "s"))
         :files file-results}))))
