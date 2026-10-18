(ns calva-backseat-driver.integrations.calva.editor-persist
  "Persist-after-edit helpers for structural edits (no Calva/vscode require)."
  (:require [promesa.core :as p]))

(def ^:private pre-dirty-note
  "The file had unsaved changes in the editor; the edit is in the editor buffer, not on disk. Read the open document, not the disk.")

(def ^:private await-timeout-note
  "The edit may be in the editor buffer, but it was not confirmed visible in time so the file was not saved. Read the open document, not the disk.")

(def ^:private save-failed-note
  "The edit is in the editor buffer, but the file on disk was not saved. Read the open document, not the disk.")

(defn- wait-until+
  "Poll pred until truthy or timeout-ms elapses. Returns true if pred became truthy."
  [pred timeout-ms]
  (letfn [(step [remaining]
            (cond
              (pred) (p/resolved true)
              (<= remaining 0) (p/resolved false)
              :else (p/do (p/delay 10) (step (- remaining 10)))))]
    (step timeout-ms)))

(defn await-edit-visible-on-document+
  "After applyEdit/replace resolves, wait briefly until the document is dirty or
  its text differs from text-before. Avoids saving while the buffer is still
  clean (VS Code then returns true from .save as a no-op).
  Returns true when the edit became visible, false on timeout."
  [^js vscode-document text-before]
  (wait-until+ (fn []
                 (or (.-isDirty vscode-document)
                     (not= text-before (.getText vscode-document))))
               500))

(defn persist-after-edit+
  "Shape :success/:saved after an edit that already landed (or failed).
   was-dirty? is isDirty before the edit. Only the save is error-wrapped.
   opts: :identical-replace? skips the visibility gate; :edit-visible? false
   means the visibility wait timed out on a real change — do not save."
  ([^js vscode-document was-dirty? edit-applied?]
   (persist-after-edit+ vscode-document was-dirty? edit-applied? nil))
  ([^js vscode-document was-dirty? edit-applied? {:keys [identical-replace? edit-visible?]}]
   (cond
     (not edit-applied?)
     (p/resolved {:success false :error "Failed to apply edit" :saved false})

     was-dirty?
     (p/resolved {:success true :saved false :note pre-dirty-note})

     (and (not identical-replace?) (false? edit-visible?))
     (p/resolved {:success true :saved false :note await-timeout-note})

     :else
     ;; Keystrokes typed during the visibility wait get saved with the edit.
     (-> (p/let [save-result (.save vscode-document)
                 _ (when save-result
                     (wait-until+ #(not (.-isDirty vscode-document)) 500))
                 still-dirty? (.-isDirty vscode-document)]
           (if (and save-result (not still-dirty?))
             {:success true :saved true}
             {:success true
              :saved false
              :note save-failed-note}))
         (p/catch (fn [e]
                    {:success true
                     :saved false
                     :note save-failed-note
                     :error (.-message e)}))))))

(defn persist-edit-result+
  "After an edit attempt: pre-dirty skips save; clean buffers await visibility
  (unless identical-replace?) then persist. text-before is required when the
  buffer was clean and the replace is not identical."
  [^js vscode-document was-dirty? edit-applied? {:keys [text-before identical-replace?]}]
  (cond
    was-dirty?
    (persist-after-edit+ vscode-document true edit-applied?)

    (or (not edit-applied?) identical-replace?)
    (persist-after-edit+ vscode-document false edit-applied?
                         {:identical-replace? (boolean identical-replace?)})

    :else
    (p/let [edit-visible? (await-edit-visible-on-document+ vscode-document text-before)]
      (persist-after-edit+ vscode-document false true
                           {:edit-visible? edit-visible?}))))
