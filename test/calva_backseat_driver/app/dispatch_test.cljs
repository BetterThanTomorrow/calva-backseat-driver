(ns calva-backseat-driver.app.dispatch-test
  (:require ["fs" :as fs]
            ["path" :as path]
            [cljs.test :refer [deftest is async use-fixtures]]
            [calva-backseat-driver.app.db :as db]
            [calva-backseat-driver.app.dispatch :as dispatch]
            [calva-backseat-driver.app.live-objects :as live-objects]))

(def ^:private extension-context
  "An extension context with the manifest, which is what gating a tool reads from it."
  #js {:extension #js {:packageJSON (js/JSON.parse (fs/readFileSync (path/resolve "package.json") "utf8"))}})

(def ^:private state
  {:app/min-log-level :error
   :vscode/config.enableMcpReplEvaluation false
   :vscode/config.provideBdSkill true
   :vscode/config.provideEditSkill true})

(use-fixtures :each
  {:before (fn []
             (live-objects/put! :vscode/extension-context extension-context)
             (reset! db/!app-db state))
   :after (fn []
            (live-objects/remove! :vscode/extension-context)
            (reset! db/!app-db db/init-db))})

(deftest tables
  (is (every? var? (vals (dispatch/actions)))
      "The action tables merge, and every action has a var")
  (is (every? var? (vals (dispatch/effects)))
      "The effect tables merge, and every effect has a var")
  (is (every? #{"app" "calva" "mcp" "tool"}
              (map namespace (keys (dispatch/actions))))
      "Every action is named for its domain"))

(deftest a-tool-call-through-the-loop
  (async done
    (-> (js/Promise.all
         #js [(dispatch/dispatch! [[:tool/ax.call {:call/tool "clojure_balance_brackets"
                                                   :call/arguments {:text "(def foo [a b"}}]])
              (dispatch/dispatch! [[:tool/ax.call {:call/tool "clojure_evaluate_code"
                                                   :call/arguments {:code "1"}}]])
              (dispatch/dispatch! [[:tool/ax.call {:call/tool "no_such_tool"
                                                   :call/arguments {}}]])])
        (.then (fn [[balanced disabled unknown]]
                 (is (= "(def foo [a b])"
                        (-> balanced :result/content first :text js/JSON.parse (js->clj :keywordize-keys true) :balanced-text))
                     "A call resolves to the tool's result as content parts")
                 (is (= {:result/refused :disabled} disabled)
                     "A tool whose setting is off is refused")
                 (is (= {:result/refused :unknown} unknown)
                     "A tool that the manifest doesn't have is refused")))
        (.catch (fn [e]
                  (is (nil? e) "The calls resolve")))
        (.finally done))))
