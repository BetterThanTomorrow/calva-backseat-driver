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
   :calva/output-line-counter 7})

(def ^:private when-clause-settings
  {"config.calva-backseat-driver.provideBdSkill" true
   "config.calva-backseat-driver.provideEditSkill" false
   "config.calva-backseat-driver.enableMcpReplEvaluation" true
   ":calva-mcp-extension/activated?" true})

(defn- run-effect
  "The effect that the handlers give for a call of `tool` with `arguments`."
  [tool arguments]
  (-> (event-handler/handle-actions tools-axs/actions state {:system/now 0}
                                    [[:tool/ax.call {:call/tool tool
                                                     :call/arguments arguments}]])
      :uf/fxs
      first))

(deftest call
  (is (= [:uf/await :tool/fx.run
          {:call/tool "clojure_evaluate_code"
           :call/arguments {:code "(+ 1 2)"}
           :call/settings {:vscode/config.evaluation.maxLength 25
                           :vscode/config.evaluation.maxDepth 7}
           :call/when-clause-settings when-clause-settings}]
         (run-effect "clojure_evaluate_code" {:code "(+ 1 2)"}))
      "An evaluation runs with the print limits from the settings")
  (is (= {:vscode/config.editor.fuzzyLineTargetingPadding 2
          :vscode/config.editor.lineContextResponsePadding 10}
         (:call/settings (nth (run-effect "clojure_edit_files" {:edits []}) 2)))
      "An edit runs with the paddings from the settings")
  (is (= {}
         (:call/settings (nth (run-effect "clojure_list_sessions" {}) 2)))
      "A tool that reads no settings gets none")
  (is (= {:call/tool "no_such_tool"
          :call/arguments {}
          :call/settings {}
          :call/when-clause-settings when-clause-settings}
         (nth (run-effect "no_such_tool" {}) 2))
      "A tool that isn't in the table is still run, for the effect to refuse"))
