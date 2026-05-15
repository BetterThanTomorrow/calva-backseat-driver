(ns tests.mcp.balance-brackets-test
  (:require
   ["vscode" :as vscode]
   [cljs.test :refer [is testing]]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]
   [promesa.core :as p]))

(defn- test-balanced-input+ [socket]
  (p/let [result (mcp/call-tool socket 10 "clojure_balance_brackets"
                                {:text "(defn foo [x]\n  (+ x 1))"})]
    (js/console.log "[balance-brackets] balanced result:" (pr-str result))
    (testing "already balanced input returns note"
      (is (some? (:note result)))
      (is (nil? (:balanced-text result))))))

(defn- test-unbalanced-input+ [socket]
  (p/let [result (mcp/call-tool socket 11 "clojure_balance_brackets"
                                {:text "(defn foo [x]\n  (+ x 1)"})]
    (js/console.log "[balance-brackets] unbalanced result:" (pr-str result))
    (testing "unbalanced input returns balanced text"
      (is (some? (:balanced-text result)))
      (is (some? (:note result))))))

(defn- test-broken-brackets+ [socket]
  (p/let [result (mcp/call-tool socket 12 "clojure_balance_brackets"
                                {:text "(defn foo [x\n  (+ x 1"})]
    (js/console.log "[balance-brackets] broken result:" (pr-str result))
    (testing "seriously broken brackets get balanced"
      (is (some? (:balanced-text result))))))

(defn- test-missing-text-param+ [socket]
  (p/let [resp (mcp/send-request socket
                                 {:jsonrpc "2.0"
                                  :id 13
                                  :method "tools/call"
                                  :params {:name "clojure_balance_brackets"
                                           :arguments {}}})
          result (-> resp (js->clj :keywordize-keys true) :result)]
    (js/console.log "[balance-brackets] missing param result:" (pr-str result))
    (testing "missing text parameter returns error"
      (is (some? result))
      (let [text (some-> (get-in result [:content 0 :text])
                         js/JSON.parse
                         (js->clj :keywordize-keys true))]
        (is (or (:error text)
                (:isError result)))))))

(deftest-async balance-brackets-tests
  (-> (p/let [{:keys [socket]} (mcp/start-mcp-session!)
              _ (test-balanced-input+ socket)
              _ (test-unbalanced-input+ socket)
              _ (test-broken-brackets+ socket)
              _ (test-missing-text-param+ socket)
              _ (mcp/stop-mcp-session! socket)])
      (p/catch (fn [e]
                 (js/console.error "[balance-brackets] Test error:" (.-message e) e)
                 (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                 (throw e)))))
