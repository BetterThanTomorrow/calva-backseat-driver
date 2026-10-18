(ns calva-backseat-driver.integrations.calva.editor-test
  (:require [cljs.test :refer [deftest testing is async]]
            [promesa.core :as p]
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

(deftest persist-after-edit-timeout-does-not-save
  (testing "timed-out visibility wait on a real change returns :saved false without calling save"
    (async done
           (let [save-calls (atom 0)
                 doc (stub-document {:dirty? false
                                     :save-result true
                                     :on-save #(swap! save-calls inc)})]
             (-> (p/let [result (editor-persist/persist-after-edit+
                                 doc false true {:edit-visible? false})]
                   (is (true? (:success result)))
                   (is (false? (:saved result)))
                   (is (string? (:note result)))
                   (is (zero? @save-calls)
                       "save must not run when visibility wait timed out")
                   (done))
                 (p/catch (fn [e]
                            (is false (str e))
                            (done))))))))

(deftest persist-after-edit-identical-replace-skips-visibility-gate
  (testing "identical replace saves even when edit-visible? is false"
    (async done
           (let [save-calls (atom 0)
                 doc (stub-document {:dirty? true
                                     :save-result true
                                     :on-save #(swap! save-calls inc)})]
             (-> (p/let [result (editor-persist/persist-after-edit+
                                 doc false true {:identical-replace? true
                                                 :edit-visible? false})]
                   (is (true? (:success result)))
                   (is (true? (:saved result)))
                   (is (= 1 @save-calls))
                   (done))
                 (p/catch (fn [e]
                            (is false (str e))
                            (done))))))))
