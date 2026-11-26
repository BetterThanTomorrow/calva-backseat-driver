(ns calva-backseat-driver.integrations.calva.editor-test
  (:require ["vscode" :as vscode]
            [cljs.test :refer [deftest testing is]]
            [calva-backseat-driver.integrations.calva.editor :as editor]
            [calva-backseat-driver.integrations.calva.editor-persist :as editor-persist]))

(defn- stub-document
  "Minimal TextDocument stand-in: mutable isDirty and a save fn."
  [{:keys [dirty? save-result save-error on-save]}]
  (let [!dirty (atom (boolean dirty?))
        doc #js {}]
    (js/Object.defineProperty
     doc "isDirty"
     #js {:configurable true
          :get (fn [] @!dirty)})
    (set! (.-save doc)
          (fn []
            (when on-save (on-save))
            (if save-error
              (js/Promise.reject (js/Error. save-error))
              (js/Promise.resolve
               (do (when save-result (reset! !dirty false))
                   save-result)))))
    doc))

(deftest ^:async persist-after-edit-timeout-does-not-save
  (testing "timed-out visibility wait on a real change returns :saved false without calling save"
    (let [save-calls (atom 0)
          doc (stub-document {:dirty? false
                              :save-result true
                              :on-save #(swap! save-calls inc)})]
      (try
        (let [result (await (editor-persist/persist-after-edit+
                             doc false true {:edit-visible? false}))]
          (is (true? (:success result)))
          (is (false? (:saved result)))
          (is (string? (:note result)))
          (is (zero? @save-calls)
              "save must not run when visibility wait timed out"))
        (catch :default e
          (is false (str e)))))))

(deftest ^:async persist-after-edit-identical-replace-skips-visibility-gate
  (testing "identical replace saves even when edit-visible? is false"
    (let [save-calls (atom 0)
          doc (stub-document {:dirty? true
                              :save-result true
                              :on-save #(swap! save-calls inc)})]
      (try
        (let [result (await (editor-persist/persist-after-edit+
                             doc false true {:identical-replace? true
                                             :edit-visible? false}))]
          (is (true? (:success result)))
          (is (true? (:saved result)))
          (is (= 1 @save-calls)))
        (catch :default e
          (is false (str e)))))))

(defn- lazy-thenable
  "A thenable that isn't a native promise, as VS Code returns from a document's `save`.
   Calls `settle` with the resolve and reject functions after `ms`."
  [ms settle]
  #js {:then (fn [resolve reject]
               (js/setTimeout #(settle resolve reject) ms))})

(defn- document-saving-later
  "A TextDocument stand-in whose `save` returns a thenable that settles after 30 ms.
   The document reads as without unsaved changes when `save` is called, as the extension
   host does for a moment after an edit, and as unsaved from then until the save resolves."
  [!events settle]
  (let [!dirty (atom false)
        doc #js {}]
    (js/Object.defineProperty doc "isDirty" #js {:configurable true
                                                 :get (fn [] @!dirty)})
    (set! (.-save doc)
          (fn []
            (js/setTimeout #(reset! !dirty true) 2)
            (lazy-thenable 30 (fn [resolve reject]
                                (swap! !events conj :save-settled)
                                (settle {:resolve (fn [v]
                                                    (reset! !dirty false)
                                                    (resolve v))
                                         :reject reject})))))
    doc))

(deftest ^:async persist-after-edit-awaits-the-save
  (testing "a save that VS Code resolves later, through a thenable that isn't a native promise"
    (let [!events (atom [])
          doc (document-saving-later !events (fn [{:keys [resolve]}] (resolve true)))]
      (try
        (let [result (await (editor-persist/persist-after-edit+ doc false true {:edit-visible? true}))]
          (is (= [:save-settled] @!events)
              "The save has resolved when the edit is reported")
          (is (= {:success true :saved true} (select-keys result [:success :saved]))
              "and the edit is reported as saved")
          (is (false? (.-isDirty doc))))
        (catch :default e
          (is false (str e)))))))

(deftest ^:async persist-after-edit-save-that-fails
  (testing "a save that VS Code resolves to false"
    (let [doc (document-saving-later (atom []) (fn [{:keys [resolve]}] (resolve false)))]
      (try
        (let [result (await (editor-persist/persist-after-edit+ doc false true {:edit-visible? true}))]
          (is (= {:success true :saved false} (select-keys result [:success :saved]))
              "The edit is reported as in the buffer only"))
        (catch :default e
          (is false (str e)))))))

(deftest ^:async persist-after-edit-save-that-rejects
  (testing "a save that rejects"
    (let [doc (document-saving-later (atom []) (fn [{:keys [reject]}] (reject (js/Error. "disk full"))))]
      (try
        (let [result (await (editor-persist/persist-after-edit+ doc false true {:edit-visible? true}))]
          (is (= {:success true :saved false :error "disk full"}
                 (select-keys result [:success :saved :error]))
              "The edit is reported as in the buffer only, with the error's message"))
        (catch :default e
          (is false (str e)))))))

(defn- document-and-apply-edit
  "A TextDocument stand-in without unsaved changes, and an `applyEdit` for it that returns
   a thenable that isn't a native promise, as VS Code's does, and settles after 30 ms
   through `settle`. With `edits?`, `applyEdit` changes the document's text at once, as
   VS Code does before its thenable resolves. The save resolves to true and leaves the
   document saved."
  [!events edits? settle]
  (let [!text (atom "(ns a)\n")
        !dirty (atom false)
        doc #js {}]
    (js/Object.defineProperty doc "isDirty" #js {:configurable true
                                                 :get (fn [] @!dirty)})
    (set! (.-getText doc) (fn [] @!text))
    (set! (.-save doc) (fn []
                         (swap! !events conj :save)
                         (reset! !dirty false)
                         (js/Promise.resolve true)))
    {:document doc
     :apply-edit (fn [_workspace-edit]
                   (when edits?
                     (reset! !text "(ns a)\n\n(foo)\n")
                     (reset! !dirty true))
                   (lazy-thenable 30 (fn [resolve reject]
                                       (swap! !events conj :edit-settled)
                                       (settle {:resolve resolve
                                                :reject reject}))))}))

(defn- ^:async append-with-apply-edit
  "Appends a form with `append-code-core+`, with `document` as the document VS Code opens
   and `apply-edit` as `workspace.applyEdit`."
  [{:keys [document apply-edit]}]
  (let [open-text-document (.-openTextDocument vscode/workspace)
        workspace-apply-edit (.-applyEdit vscode/workspace)]
    (set! (.-openTextDocument vscode/workspace) (fn [_uri] (js/Promise.resolve document)))
    (set! (.-applyEdit vscode/workspace) apply-edit)
    (try
      (await (editor/append-code-core+ "/project/src/a.clj" "(foo)"))
      (finally
        (set! (.-openTextDocument vscode/workspace) open-text-document)
        (set! (.-applyEdit vscode/workspace) workspace-apply-edit)))))

(deftest ^:async append-code-awaits-the-edit
  (testing "an applyEdit that VS Code resolves to true later, through a thenable that isn't a native promise"
    (let [!events (atom [])]
      (try
        (let [result (await (append-with-apply-edit
                             (document-and-apply-edit !events true (fn [{:keys [resolve]}] (resolve true)))))]
          (is (= [:edit-settled :save] @!events)
              "The edit has resolved when the document is saved")
          (is (= {:success true :saved true :appended-at-end true}
                 (select-keys result [:success :saved :appended-at-end :error]))
              "and the append is reported as saved"))
        (catch :default e
          (is false (str e)))))))

(deftest ^:async append-code-edit-that-is-not-applied
  (testing "an applyEdit that VS Code resolves to false"
    (let [!events (atom [])]
      (try
        (let [result (await (append-with-apply-edit
                             (document-and-apply-edit !events false (fn [{:keys [resolve]}] (resolve false)))))]
          (is (= {:success false :error "Failed to apply edit" :saved false} result)
              "The append is reported as not applied")
          (is (= [:edit-settled] @!events)
              "and the document isn't saved"))
        (catch :default e
          (is false (str e)))))))

(deftest ^:async append-code-edit-that-rejects
  (testing "an applyEdit that rejects"
    (let [!events (atom [])]
      (try
        (let [result (await (append-with-apply-edit
                             (document-and-apply-edit !events false (fn [{:keys [reject]}] (reject (js/Error. "edit refused"))))))]
          (is (= {:success false :saved false :error "edit refused"} result)
              "The append is reported as not successful, with the error's message")
          (is (= [:edit-settled] @!events)
              "and the document isn't saved"))
        (catch :default e
          (is false (str e)))))))
