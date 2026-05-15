(ns tests.mcp.balance-brackets-test
  (:require
   ["vscode" :as vscode]
   [cljs.test :refer [is testing]]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]
   [promesa.core :as p]))

(deftest-async balance-brackets-tests
  (-> (p/let [{:keys [socket]} (mcp/start-mcp-session!)

              ;; === 1. Already balanced input ===

              balanced-input "(defn foo [x]\n  (+ x 1))"
              balanced-result (mcp/call-tool socket 10 "clojure_balance_brackets"
                                            {:text balanced-input})

              _ (js/console.log "[balance-brackets] balanced result:" (pr-str balanced-result))

              ;; === 2. Unbalanced input (missing closing paren) ===

              unbalanced-input "(defn foo [x]\n  (+ x 1)"
              unbalanced-result (mcp/call-tool socket 11 "clojure_balance_brackets"
                                              {:text unbalanced-input})

              _ (js/console.log "[balance-brackets] unbalanced result:" (pr-str unbalanced-result))

              ;; === 3. Seriously broken brackets ===

              broken-input "(defn foo [x\n  (+ x 1"
              broken-result (mcp/call-tool socket 12 "clojure_balance_brackets"
                                          {:text broken-input})

              _ (js/console.log "[balance-brackets] broken result:" (pr-str broken-result))

              ;; === 4. Missing required text parameter ===

              missing-param-resp (mcp/send-request socket
                                                   {:jsonrpc "2.0"
                                                    :id 13
                                                    :method "tools/call"
                                                    :params {:name "clojure_balance_brackets"
                                                             :arguments {}}})
              missing-param-result (-> missing-param-resp
                                       (js->clj :keywordize-keys true)
                                       :result)

              _ (js/console.log "[balance-brackets] missing param result:" (pr-str missing-param-result))

              _ (mcp/stop-mcp-session! socket)]

        ;; 1. Already balanced
        (testing "already balanced input returns note"
          (is (some? (:note balanced-result)))
          (is (nil? (:balanced-text balanced-result))))

        ;; 2. Unbalanced input gets fixed
        (testing "unbalanced input returns balanced text"
          (is (some? (:balanced-text unbalanced-result)))
          (is (some? (:note unbalanced-result))))

        ;; 3. Seriously broken gets fixed
        (testing "seriously broken brackets get balanced"
          (is (some? (:balanced-text broken-result))))

        ;; 4. Missing param
        (testing "missing text parameter returns error"
          (is (some? missing-param-result))
          (let [text (some-> (get-in missing-param-result [:content 0 :text])
                             js/JSON.parse
                             (js->clj :keywordize-keys true))]
            (is (or (:error text)
                    (:isError missing-param-result))))))

      (p/catch (fn [e]
                 (js/console.error "[balance-brackets] Test error:" (.-message e) e)
                 (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                 (throw e)))))
