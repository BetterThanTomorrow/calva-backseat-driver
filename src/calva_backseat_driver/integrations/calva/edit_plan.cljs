(ns calva-backseat-driver.integrations.calva.edit-plan
  "The decisions of a structural edit, as pure functions from what was gathered about
   the document to the edit plan, or to the result the agent gets when the edit can't
   be made. A position is `[line character]`, both from 0, and a range is `[start end]`."
  (:require
   [calva-backseat-driver.integrations.calva.editor-util :as util]
   [clojure.string :as string]))

(def ^:private pre-dirty-note
  "The file had unsaved changes in the editor; the edit is in the editor buffer, not on disk. Read the open document, not the disk.")

(def ^:private await-timeout-note
  "The edit may be in the editor buffer, but it was not confirmed visible in time so the file was not saved. Read the open document, not the disk.")

(def ^:private save-failed-note
  "The edit is in the editor buffer, but the file on disk was not saved. Read the open document, not the disk.")

;; What the agent asked for

(defn- starts-with-comment? [text]
  (-> text str string/trim (string/starts-with? ";")))

(defn input-error
  "Returns the result for a replace or an insert that can't be made because of its
   `target-line` or its `new-form`, or nil when it can."
  [{:editor/keys [target-line new-form]}]
  (when-let [error (cond
                     (not target-line)
                     "No target line text provided. Provide an empty string to target an empty line."

                     (not new-form)
                     "No insert/replace text provided."

                     (starts-with-comment? target-line)
                     "Target line text cannot start with a comment (;). You can only target forms/sexpressions. (To edit line comments, use your line based editing tools.)"

                     (starts-with-comment? new-form)
                     "Replacement form cannot start with a comment (;). You can only insert forms/sexpressions with this tool. (To edit line comments, use your line based editing tools.)")]
    {:success false
     :saved false
     :error error}))

;; Targeting

(defn target-line-number
  "Returns the line, from 1, where the edit's target line text is in the document's
   `text`, within the search padding around the edit's line. Returns nil when it isn't there."
  [{:document/keys [text]} {:editor/keys [line-number target-line search-padding]}]
  (some-> (util/find-target-line-by-text text target-line (dec line-number) search-padding)
          inc))

(defn- targeting-error
  "The result for an edit whose target wasn't found, with the lines around where it was
   looked for and what to do about it."
  [{:document/keys [text]} {:editor/keys [line-number target-line context-padding]} error]
  (let [{:editor/keys [file-context matched-line-in-context]}
        (util/get-context-lines text line-number context-padding target-line)]
    {:success false
     :error error
     :remedy (util/get-remedy-for-targeting matched-line-in-context)
     :file-context file-context}))

(defn target-not-found
  "Returns the result for an edit whose target line text isn't near its line in `document`."
  [document {:editor/keys [line-number target-line] :as edit}]
  (targeting-error document edit
                   (str "Target line text not found. Expected: '" target-line "' near line " line-number)))

(defn form-mismatch
  "Returns the result for an edit whose target line text was found, when `form-text`,
   the top level form at that line, doesn't start with it. Returns nil when it does."
  [document {:editor/keys [line-number target-line] :as edit} form-text]
  (when-not (util/form-first-line-starts-target-text? target-line form-text)
    (assoc (targeting-error document edit
                            (str "Target text validation failed near line: " line-number))
           :validation-error "The target text does not match the first line of a top level form in the vicinity of the target line.")))

;; The edit plan

(defn- lines-of [text]
  (string/split text #"\r?\n" -1))

(defn- insertion? [{:editor/keys [ranges-fn-key]}]
  (= :insertionPoint ranges-fn-key))

(defn- removal-range
  "The range that removing the form at `form-range` replaces: the form, a blank line
   before it when there is one, and the rest through the start of the next line when
   there is a next line."
  [text [[start-line _ :as start] [end-line _ :as end]]]
  (let [lines (lines-of text)
        blank-line-before? (and (pos? start-line)
                                (string/blank? (nth lines (dec start-line))))
        next-line (inc end-line)]
    [(if blank-line-before?
       [(dec start-line) 0]
       start)
     (if (< next-line (count lines))
       [next-line 0]
       end)]))

(defn edit-plan
  "Returns the edit plan for `edit` on `document`: the range to replace, and the text
   to put there. `form-range` is the range of the form at the edit's line, or the
   insertion point for an insert. A replace with a blank new form removes the form and
   the blank line around it."
  [{:document/keys [text]} form-range {:editor/keys [new-form] :as edit}]
  (let [trimmed-form (string/trim new-form)
        insert? (insertion? edit)]
    {:editor/range (if (and (string/blank? trimmed-form)
                            (not insert?))
                     (removal-range text form-range)
                     form-range)
     :editor/text (if insert?
                    (str trimmed-form "\n\n")
                    trimmed-form)}))

(defn append-plan
  "Returns the edit plan for appending `code` to a document with `text`: the position
   after its last line, and the code with a blank line before it unless the document
   ends with one."
  [{:document/keys [text]} code]
  (let [lines (lines-of text)
        line-count (count lines)
        needs-spacing? (not (string/blank? (last lines)))]
    {:editor/range [[line-count 0] [line-count 0]]
     :editor/text (str (if needs-spacing? "\n\n" "\n")
                       (string/trim code)
                       "\n")}))

;; After the edit was applied

(defn awaits-visibility?
  "True when the edit that was applied must show in the document before the document
   is saved: the document was clean, the edit was applied, and it changed the text."
  [{:editor/keys [edit-applied? was-dirty? identical-replace?]}]
  (boolean (and edit-applied?
                (not was-dirty?)
                (not identical-replace?))))

(defn unsaved-result
  "Returns the result for an applied edit that isn't saved, or nil when the document is
   to be saved: the edit wasn't applied, the document had unsaved changes before it, or
   the edit didn't show in the document in time."
  [{:editor/keys [edit-applied? was-dirty? identical-replace? edit-visible?]}]
  (cond
    (not edit-applied?)
    {:success false :error "Failed to apply edit" :saved false}

    was-dirty?
    {:success true :saved false :note pre-dirty-note}

    (and (not identical-replace?)
         (false? edit-visible?))
    {:success true :saved false :note await-timeout-note}))

(defn saved-result
  "Returns the result for an applied edit after the save: saved when the save
   succeeded and the document is clean, and otherwise in the editor buffer only."
  [{:editor/keys [save-succeeded? still-dirty? save-error] :as saved}]
  (cond
    ;; A save that threw has the key, also when the error had no message
    (contains? saved :editor/save-error)
    {:success true :saved false :note save-failed-note :error save-error}

    (and save-succeeded?
         (not still-dirty?))
    {:success true :saved true}

    :else
    {:success true :saved false :note save-failed-note}))
