(ns calva-backseat-driver.integrations.calva.batch-edit
  (:require [clojure.string :as string]))

(defn validate-edit-schema
  "Pre-validate all edits for schema correctness. Returns nil if valid,
   or a vector of {:index N :error \"message\"} for invalid edits.
   Checks: type exists, required fields per type, filePath is absolute,
   at most one create and one append per file."
  [edits]
  (let [valid-types #{"replace" "insert" "append" "create"}
        per-edit-errors (keep-indexed
                         (fn [idx edit]
                           (let [type (:type edit)]
                             (cond
                               (not type)
                               {:index idx :error "Missing required field: type"}

                               (not (valid-types type))
                               {:index idx :error (str "Invalid type: " type ". Must be one of: replace, insert, append, create")}

                               (not (:filePath edit))
                               {:index idx :error "Missing required field: filePath"}

                               (not (string/starts-with? (:filePath edit) "/"))
                               {:index idx :error "filePath must be an absolute path (starting with /)"}

                               (and (#{"replace" "insert"} type)
                                    (not (integer? (:line edit))))
                               {:index idx :error (str "Type '" type "' requires integer field: line")}

                               (and (#{"replace" "insert"} type)
                                    (not (string? (:targetLineText edit))))
                               {:index idx :error (str "Type '" type "' requires string field: targetLineText")}

                               (and (#{"replace" "insert"} type)
                                    (not (string? (:newForm edit))))
                               {:index idx :error (str "Type '" type "' requires string field: newForm")}

                               (and (= "append" type)
                                    (not (string? (:code edit))))
                               {:index idx :error "Type 'append' requires string field: code"}

                               (and (= "create" type)
                                    (not (string? (:content edit))))
                               {:index idx :error "Type 'create' requires string field: content"})))
                         edits)
        valid-path-edits (filter #(string/starts-with? (str (:filePath %)) "/") edits)
        per-file-errors (mapcat
                         (fn [[file-path file-edits]]
                           (let [create-count (count (filter #(= "create" (:type %)) file-edits))
                                 append-count (count (filter #(= "append" (:type %)) file-edits))]
                             (cond-> []
                               (> create-count 1)
                               (conj {:index -1 :error (str "Multiple create edits for file: " file-path)})

                               (> append-count 1)
                               (conj {:index -1 :error (str "Multiple append edits for file: " file-path)}))))
                         (group-by :filePath valid-path-edits))
        all-errors (concat per-edit-errors per-file-errors)]
    (when (seq all-errors) (vec all-errors))))

(defn sort-edits-for-file
  "Sort edits for safe application order within a single file:
   creates first, replace/insert by line descending, appends last."
  [edits]
  (sort-by (fn [edit]
             (case (:type edit)
               "create"  [0 0]
               "replace" [1 (- (:line edit 0))]
               "insert"  [1 (- (:line edit 0))]
               "append"  [2 0]))
           edits))
