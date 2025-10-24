(ns tests.ws-ready.example-test
  (:require [cljs.test :refer [deftest is]]
            ["vscode" :as vscode]
            workspace-activate))

; No tests starts before the workspace is activated
(deftest ws-activated
  (is (= 42
         workspace-activate/question)
      "We can read the question from the namespace")
  (is (= (first vscode/workspace.workspaceFolders)
         workspace-activate/ws-root)
      "The root folder is also the only folder"))