(ns calva-backseat-driver.integrations.calva.features
  "A batch of structural edits: validated as a whole, then applied file by file."
  (:require
   [calva-backseat-driver.integrations.calva.batch-edit :as batch-edit]
   [calva-backseat-driver.integrations.calva.editor :as editor]
   [promesa.core :as p]))

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
            diagnostics-after-edit (editor/poll-diagnostics+ file-path diagnostics-before-edit)
            disk-matches-buffer? (editor/disk-matches-document? file-path)]
      {:file-path file-path
       :edits results
       :disk-matches-buffer? disk-matches-buffer?
       :diagnostics-before-edit diagnostics-before-edit
       :diagnostics-after-edit diagnostics-after-edit})))

(defn edit-files+
  "Execute a batch of structural edits across one or more files.
   Pre-validates schema, groups by file, sorts within each file,
   applies sequentially (continues on failure), and reports per-file diagnostics."
  [edits {:editor/keys [search-padding context-padding]}]
  (let [validation-errors (batch-edit/validate-edit-schema edits)]
    (if validation-errors
      (p/resolved {:error "Schema validation failed — no edits were applied"
                   :validation-errors validation-errors})
      (let [indexed-edits (map-indexed (fn [idx edit] (assoc edit :index idx)) edits)
            grouped (group-by :filePath indexed-edits)
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
