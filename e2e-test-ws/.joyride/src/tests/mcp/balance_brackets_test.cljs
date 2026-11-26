(ns tests.mcp.balance-brackets-test
  (:require
   ["vscode" :as vscode]
   [cljs.test :refer [is testing]]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]))

(defn- ^:async test-balanced-input+ [socket]
  (let [result (await (mcp/call-tool socket 10 "clojure_balance_brackets"
                                     {:text "(defn foo [x]\n  (+ x 1))"}))]
    (js/console.log "[balance-brackets] balanced result:" (pr-str result))
    (testing "already balanced input returns note"
      (is (some? (:note result)))
      (is (nil? (:balanced-text result))))))

(defn- ^:async test-unbalanced-input+ [socket]
  (let [result (await (mcp/call-tool socket 11 "clojure_balance_brackets"
                                     {:text "(defn foo [x]\n  (+ x 1)"}))]
    (js/console.log "[balance-brackets] unbalanced result:" (pr-str result))
    (testing "unbalanced input returns balanced text"
      (is (some? (:balanced-text result)))
      (is (some? (:note result))))))

(defn- ^:async test-broken-brackets+ [socket]
  (let [result (await (mcp/call-tool socket 12 "clojure_balance_brackets"
                                     {:text "(defn foo [x\n  (+ x 1"}))]
    (js/console.log "[balance-brackets] broken result:" (pr-str result))
    (testing "seriously broken brackets get balanced"
      (is (some? (:balanced-text result))))))

(defn- ^:async test-missing-text-param+ [socket]
  (let [resp (await (mcp/send-request socket
                                      {:jsonrpc "2.0"
                                       :id 13
                                       :method "tools/call"
                                       :params {:name "clojure_balance_brackets"
                                                :arguments {}}}))
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
  (try
    (let [{:keys [socket]} (await (mcp/start-mcp-session!))]
      (await (test-balanced-input+ socket))
      (await (test-unbalanced-input+ socket))
      (await (test-broken-brackets+ socket))
      (await (test-missing-text-param+ socket))
      (await (mcp/stop-mcp-session! socket)))
    (catch :default e
      (js/console.error "[balance-brackets] Test error:" (.-message e) e)
      (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
      (throw e))))
