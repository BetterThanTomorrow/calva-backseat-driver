(ns calva-backseat-driver.tools.axs-test
  (:require [cljs.test :refer [deftest is]]
            [calva-backseat-driver.event-handler :as event-handler]
            [calva-backseat-driver.tools.axs :as tools-axs]))

(def ^:private state
  {:vscode/config.evaluation.maxLength 25
   :vscode/config.evaluation.maxDepth 7
   :vscode/config.editor.fuzzyLineTargetingPadding 2
   :vscode/config.editor.lineContextResponsePadding 10
   :vscode/config.enableMcpReplEvaluation true
   :vscode/config.provideBdSkill true
   :vscode/config.provideEditSkill false
   :app/min-log-level :debug
   :app/log-file-uri "file:///logs/mcp-server.log"
   :calva/output-line-counter 7
   :calva/output-log (sorted-map 7 {:output/line 7 :output/text "(+ 1 2)"})})

(def ^:private when-clause-settings
  {"config.calva-backseat-driver.provideBdSkill" true
   "config.calva-backseat-driver.provideEditSkill" false
   "config.calva-backseat-driver.enableMcpReplEvaluation" true
   ":calva-mcp-extension/activated?" true})

(defn- effects
  "The effects that the handlers give for a call of `tool` with `arguments`."
  [tool arguments]
  (:uf/fxs (event-handler/handle-actions tools-axs/actions state {:system/now 0}
                                         [[:tool/ax.call {:call/tool tool
                                                          :call/arguments arguments}]])))

(defn- run-effect
  "The effect that runs a call of `tool` with `arguments`."
  [tool arguments]
  (some #(when (= :uf/await (first %)) %)
        (effects tool arguments)))

(deftest the-log-line-of-a-call
  (is (= [:app/fx.log
          {:app/min-log-level :debug :app/log-file-uri "file:///logs/mcp-server.log"}
          :debug
          ["[Server] Evaluating code:" "(+ 1 2)"]]
         (first (effects "clojure_evaluate_code" {:code "(+ 1 2)"})))
      "A call writes its tool's log line before it runs")
  (is (= [:uf/await :tool/fx.run]
         (take 2 (second (effects "clojure_evaluate_code" {:code "(+ 1 2)"})))))
  (is (= 1 (count (effects "clojure_edit_files" {:edits []})))
      "A tool without a log line writes none"))

(deftest call
  (is (= [:uf/await :tool/fx.run
          {:call/tool "clojure_evaluate_code"
           :call/arguments {:code "(+ 1 2)"}
           :call/state {:vscode/config.evaluation.maxLength 25
                        :vscode/config.evaluation.maxDepth 7}
           :call/when-clause-settings when-clause-settings}]
         (run-effect "clojure_evaluate_code" {:code "(+ 1 2)"}))
      "An evaluation runs with the print limits from the settings")
  (is (= {:vscode/config.editor.fuzzyLineTargetingPadding 2
          :vscode/config.editor.lineContextResponsePadding 10}
         (:call/state (nth (run-effect "clojure_edit_files" {:edits []}) 2)))
      "An edit runs with the paddings from the settings")
  (is (= {:calva/output-log {7 {:output/line 7 :output/text "(+ 1 2)"}}}
         (:call/state (nth (run-effect "clojure_repl_output_log" {:query "[:find ?e :where [?e :output/line]]"}) 2)))
      "An output log query runs with the output log")
  (is (= {}
         (:call/state (nth (run-effect "clojure_list_sessions" {}) 2)))
      "A tool that reads nothing from the state gets nothing")
  (is (= {:call/tool "no_such_tool"
          :call/arguments {}
          :call/state {}
          :call/when-clause-settings when-clause-settings}
         (nth (run-effect "no_such_tool" {}) 2))
      "A tool that isn't in the table is still run, for the effect to refuse"))
