(ns calva-backseat-driver.integrations.calva.editor-persist
  "Persist-after-edit helpers for structural edits (no Calva/vscode require). What an
   applied edit's result is, before and after the save, is decided in `edit-plan`."
  (:require
   [calva-backseat-driver.integrations.calva.edit-plan :as edit-plan]))

(defn- ^:async wait-until+
  "Poll pred until truthy or timeout-ms elapses. Returns true if pred became truthy."
  [pred timeout-ms]
  (loop [remaining timeout-ms]
    (cond
      (pred) true
      (<= remaining 0) false
      :else (do (await (js/Promise. (fn [resolve] (js/setTimeout resolve 10))))
                (recur (- remaining 10))))))

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

(defn ^:async persist-after-edit+
  "Shape :success/:saved after an edit that already landed (or failed).
   was-dirty? is isDirty before the edit. Only the save is error-wrapped.
   opts: :identical-replace? skips the visibility gate; :edit-visible? false
   means the visibility wait timed out on a real change — do not save."
  ([^js vscode-document was-dirty? edit-applied?]
   (persist-after-edit+ vscode-document was-dirty? edit-applied? nil))
  ([^js vscode-document was-dirty? edit-applied? {:keys [identical-replace? edit-visible?]}]
   (or (edit-plan/unsaved-result {:editor/edit-applied? edit-applied?
                                  :editor/was-dirty? was-dirty?
                                  :editor/identical-replace? identical-replace?
                                  :editor/edit-visible? edit-visible?})
       ;; Keystrokes typed during the visibility wait get saved with the edit.
       (try
         (let [save-result (await (.save vscode-document))
               _ (when save-result
                   (await (wait-until+ #(not (.-isDirty vscode-document)) 500)))
               still-dirty? (.-isDirty vscode-document)]
           (edit-plan/saved-result {:editor/save-succeeded? save-result
                                    :editor/still-dirty? still-dirty?}))
         (catch :default e
           (edit-plan/saved-result {:editor/save-error (.-message e)}))))))

(defn ^:async persist-edit-result+
  "After an edit attempt: pre-dirty skips save; clean buffers await visibility
  (unless identical-replace?) then persist. text-before is required when the
  buffer was clean and the replace is not identical."
  [^js vscode-document was-dirty? edit-applied? {:keys [text-before identical-replace?]}]
  (if (edit-plan/awaits-visibility? {:editor/edit-applied? edit-applied?
                                     :editor/was-dirty? was-dirty?
                                     :editor/identical-replace? identical-replace?})
    (let [edit-visible? (await (await-edit-visible-on-document+ vscode-document text-before))]
      (await (persist-after-edit+ vscode-document false true
                                  {:edit-visible? edit-visible?})))
    (await (persist-after-edit+ vscode-document was-dirty? edit-applied?
                                {:identical-replace? (boolean identical-replace?)}))))
