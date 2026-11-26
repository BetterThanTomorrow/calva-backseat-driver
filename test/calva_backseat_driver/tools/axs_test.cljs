(ns calva-backseat-driver.tools.axs-test
  (:require ["fs" :as fs]
            ["path" :as path]
            [cljs.test :refer [deftest is]]
            [calva-backseat-driver.event-handler :as event-handler]
            [calva-backseat-driver.tools.axs :as tools-axs]))

(def ^:private manifest
  "The manifest as data, with the tools that package.json has."
  {:manifest/tools (js->clj (.. (js/JSON.parse (fs/readFileSync (path/resolve "package.json") "utf8"))
                                -contributes
                                -languageModelTools))})

(def ^:private state
  {:extension/manifest manifest
   :vscode/config.evaluation.maxLength 25
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

(defn- effects
  "The effects that the handlers give for a call of `tool` with `arguments`, in
   `state`, or in the state above when none is given."
  ([tool arguments]
   (effects state tool arguments))
  ([state tool arguments]
   (:uf/fxs (event-handler/handle-actions tools-axs/actions state {:system/now 0}
                                          [[:tool/ax.call {:call/tool tool
                                                           :call/arguments arguments}]]))))

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
                        :vscode/config.evaluation.maxDepth 7}}]
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
  (is (= [[:uf/await :app/fx.return {:result/refused :unknown}]]
         (effects "no_such_tool" {}))
      "A tool that the manifest doesn't have isn't run, and the call is refused")
  (is (= [[:uf/await :app/fx.return {:result/refused :disabled}]]
         (effects (assoc state :vscode/config.enableMcpReplEvaluation false)
                  "clojure_evaluate_code" {:code "(+ 1 2)"}))
      "A tool whose setting is off isn't run, and writes no log line"))
