(ns workspace-activate
  (:require ["vscode" :as vscode]
            [e2e.db :as db]
            [promesa.core :as p]))

(println "e2e-test-ws workspace activating...")

;; If there is anything you want to happen in the workspace before
;; the tests run, you can set things up here

(def ws-root (first vscode/workspace.workspaceFolders))

(def question 42)

;; See e2e-test-ws/.joyride/src/tests/ws_ready/example_test.cljs
;; for how we test that the workspace has been properly prepared

;; The test runner will be waiting for this to appear in the state
;; before it starts running tests
(swap! db/!state assoc :ws-activated? true)

(println "e2e-test-ws workspace activated.")

;; E.g. when running the tests locally, you may have created files
;; that you don't want to litter the git repo with.
(defn clean-up!+ []
  (p/do
    (println "Cleaning up e2e-tests workspace")
    :nothing-to-clean-up))