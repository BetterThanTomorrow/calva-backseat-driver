(ns calva-backseat-driver.app.dispatch-test
  (:require ["fs" :as fs]
            ["path" :as path]
            [cljs.test :refer [deftest is use-fixtures]]
            [calva-backseat-driver.app.db :as db]
            [calva-backseat-driver.app.dispatch :as dispatch]
            [vscode-mcp.manifest :as manifest]))

(def ^:private state
  {:extension/manifest (manifest/manifest-data
                        #js {:extension #js {:packageJSON (js/JSON.parse (fs/readFileSync (path/resolve "package.json") "utf8"))}})
   :app/min-log-level :error
   :vscode/config.enableMcpReplEvaluation false
   :calva/output-log (sorted-map 3 {:output/line 3 :output/category "evaluatedCode" :output/text "(+ 1 2)"})})

(use-fixtures :each
  {:before (fn []
             (reset! db/!app-db state))
   :after (fn []
            (reset! db/!app-db db/init-db))})

(deftest tables
  (is (every? var? (vals (dispatch/actions)))
      "The action tables merge, and every action has a var")
  (is (every? var? (vals (dispatch/effects)))
      "The effect tables merge, and every effect has a var")
  (is (every? #{"app" "calva" "mcp" "tool"}
              (map namespace (keys (dispatch/actions))))
      "Every action is named for its domain"))

(deftest ^:async a-tool-call-through-the-loop
  (try
    (let [[balanced disabled unknown found thrown]
          (await (js/Promise.all
                  #js [(dispatch/dispatch! [[:tool/ax.call {:call/tool "clojure_balance_brackets"
                                                            :call/arguments {:text "(def foo [a b"}}]])
                       (dispatch/dispatch! [[:tool/ax.call {:call/tool "clojure_evaluate_code"
                                                            :call/arguments {:code "1"}}]])
                       (dispatch/dispatch! [[:tool/ax.call {:call/tool "no_such_tool"
                                                            :call/arguments {}}]])
                       (dispatch/dispatch! [[:tool/ax.call {:call/tool "clojure_repl_output_log"
                                                            :call/arguments {:query "[:find [?text ...] :in $ ?category :where [?e :output/category ?category] [?e :output/text ?text]]"
                                                                             :inputs ["evaluatedCode"]}}]])
                       (dispatch/dispatch! [[:tool/ax.call {:call/tool "clojure_repl_output_log"
                                                            :call/arguments {:query "[:find ?e :where [(nope ?x)]]"}}]])]))]
      (is (= "(def foo [a b])"
             (-> balanced :result/content first :text js/JSON.parse (js->clj :keywordize-keys true) :balanced-text))
          "A call resolves to the tool's result as content parts")
      (is (= {:result/refused :disabled} disabled)
          "A tool whose setting is off is refused")
      (is (= {:result/refused :unknown} unknown)
          "A tool that the manifest doesn't have is refused")
      (is (= [{:type "text" :text "[\"(+ 1 2)\"]"}]
             (:result/content found))
          "An output log query runs on the log in the state")
      (is (= {:result/thrown "Query for unknown vars: [?e]"} thrown)
          "A query that fails is a tool that threw, with the message"))
    (catch :default e
      (is (nil? e) "The calls resolve"))))
