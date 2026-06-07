(ns tests.mcp.z-bad-output-log-query-test
  (:require
   ["vscode" :as vscode]
   [cljs.test :refer [is testing]]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]
   [promesa.core :as p]))

(def ^:private bad-query
  "[:find [(pull ?e [:output/line :output/text :output/who :output/category]) ...] :where [?e :output/category \"otherOutput\"] [(clojure.string/includes? ?text \"smoke\")]]")

;; Bad Datalog (e.g. unbound ?text) must return JSON-RPC error, not silence the client.
(deftest-async bad-output-log-query-returns-error-not-silence
  (let [backup-path (mcp/backup-settings! "bad-output-log-query-test-backup.json")]
    (-> (p/let [{:keys [socket]} (mcp/start-mcp-session!)
                _ (mcp/wait-for-tool! socket "clojure_repl_output_log")
                [bad-result ping-result] (p/all
                                          [(-> (mcp/send-request-with-timeout
                                                socket
                                                {:jsonrpc "2.0"
                                                 :id 900
                                                 :method "tools/call"
                                                 :params {:name "clojure_repl_output_log"
                                                          :arguments {:query bad-query}}}
                                                3000)
                                              (p/catch (fn [e] {:request-error e})))
                                           (-> (mcp/send-request-with-timeout
                                                socket
                                                {:jsonrpc "2.0"
                                                 :id 901
                                                 :method "ping"}
                                                2000)
                                              (p/catch (fn [e] {:request-error e})))])
                ping-outer (if (:request-error ping-result)
                             ping-result
                             (js->clj ping-result :keywordize-keys true))
                bad-outer (if (:request-error bad-result)
                            bad-result
                            (js->clj bad-result :keywordize-keys true))
                _ (mcp/stop-mcp-session! socket)]
          (testing "Ping succeeds while bad query is in flight (EH not fully wedged)"
            (is (nil? (:request-error ping-outer)) "Ping should not time out")
            (is (= 901 (:id ping-outer)) "Ping should return matching id")
            (is (nil? (:error ping-outer)) "Ping should succeed without error"))
          (testing "Bad output-log query returns JSON-RPC error, not silence"
            (is (nil? (:request-error bad-outer))
                "Bad query should respond within timeout")
            (is (some? (:error bad-outer))
                "Unbound ?text in includes? filter should return JSON-RPC error")
            (is (re-find #"Insufficient bindings" (get-in bad-outer [:error :message]))
                "Error message should describe the Datascript binding failure")))
        (p/catch (fn [e]
                   (js/console.error "[bad-output-log-query] Error:" (.-message e) e)
                   (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                   (throw e)))
        (p/finally (fn []
                     (mcp/restore-settings! backup-path))))))
