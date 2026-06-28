(ns calva-backseat-driver.mcp.skills-test
  (:require [cljs.test :refer [deftest testing is]]
            [calva-backseat-driver.mcp.skills :as skills]))

(deftest compose-instructions-test
  (let [test-skills [{:name "backseat-driver"
                      :description "REPL tools usage"}
                     {:name "editing-clojure-files"
                      :description "Structural editing"}]]

    (testing "repl enabled with skills mentions REPL tools and lists skills"
      (let [result (skills/compose-instructions true test-skills)]
        (is (string? result))
        (is (re-find #"clojure_evaluate_code" result) "mentions REPL eval tool")
        (is (re-find #"clojure_load_file" result) "mentions load file tool")
        (is (re-find #"resources/list" result) "mentions resources/list")
        (is (re-find #"resources/read" result) "mentions resources/read")
        (is (re-find #"backseat-driver" result) "lists first skill")
        (is (re-find #"editing-clojure-files" result) "lists second skill")))

    (testing "repl disabled with skills omits REPL text but lists skills"
      (let [result (skills/compose-instructions false test-skills)]
        (is (nil? (re-find #"clojure_evaluate_code" result)) "no REPL eval mention")
        (is (nil? (re-find #"clojure_load_file" result)) "no load file mention")
        (is (re-find #"resources/list" result) "still mentions resources/list")
        (is (re-find #"backseat-driver" result) "still lists skills")))

    (testing "empty skills omits skills section"
      (let [result (skills/compose-instructions true [])]
        (is (re-find #"structural editing" result) "has base content")
        (is (nil? (re-find #"resources/list" result)) "no resources mention")
        (is (nil? (re-find #"backseat-driver" result)) "no skill listed")))

    (testing "nil skills omits skills section"
      (let [result (skills/compose-instructions true nil)]
        (is (re-find #"structural editing" result) "has base content")
        (is (nil? (re-find #"resources/list" result)) "no resources mention")))))
