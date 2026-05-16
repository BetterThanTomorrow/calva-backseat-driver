(ns calva-backseat-driver.integrations.calva.batch-edit
  (:require [clojure.string :as string]))

(def ^:private type-required-fields
  "Required fields per edit type: [field-key predicate type-label]."
  {"replace" [[:line integer? "integer"] [:targetLineText string? "string"] [:newForm string? "string"]]
   "insert"  [[:line integer? "integer"] [:targetLineText string? "string"] [:newForm string? "string"]]
   "append"  [[:code string? "string"]]
   "create"  [[:content string? "string"]]})

(defn- validate-single-edit
  "Validate a single edit for required fields based on its type.
   Returns an error message string, or nil if valid."
  [edit]
  (let [type (:type edit)]
    (cond
      (not type)
      "Missing required field: type"

      (not (type-required-fields type))
      (str "Invalid type: " type ". Must be one of: replace, insert, append, create")

      (not (:filePath edit))
      "Missing required field: filePath"

      (not (string/starts-with? (:filePath edit) "/"))
      "filePath must be an absolute path (starting with /)"

      :else
      (some (fn [[field pred type-name]]
              (when-not (pred (get edit field))
                (str "Type '" type "' requires " type-name " field: " (name field))))
            (type-required-fields type)))))

(defn validate-edit-schema
  "Pre-validate all edits for schema correctness. Returns nil if valid,
   or a vector of {:index N :error \"message\"} for invalid edits.
   Checks: type exists, required fields per type, filePath is absolute,
   at most one create and one append per file."
  [edits]
  (let [per-edit-errors (keep-indexed
                         (fn [idx edit]
                           (when-let [error (validate-single-edit edit)]
                             {:index idx :error error}))
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
