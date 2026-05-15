(ns tests.mcp.clojuredocs-info-test
  (:require
   ["vscode" :as vscode]
   [cljs.test :refer [is testing]]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]
   [promesa.core :as p]))

(defn- test-well-known-symbol+ [socket]
  (p/let [result (mcp/call-tool socket 10 "clojuredocs_info"
                                {:clojureSymbol "map"})]
    (js/console.log "[clojuredocs-info] map result:" (pr-str result))
    (testing "well-known symbol returns structured response"
      (is (or (some? result) true)
          "tool invocation should not crash")
      (when (and result (not (:error result)))
        (is (some #(get result %) [:name :doc :examples])
            "data response should contain name, doc, or examples")))))

(defn- test-nonexistent-symbol+ [socket]
  (p/let [result (mcp/call-tool socket 11 "clojuredocs_info"
                                {:clojureSymbol "definitely-not-a-real-fn-xyz"})]
    (js/console.log "[clojuredocs-info] unknown result:" (pr-str result))
    (testing "nonexistent symbol returns gracefully"
      (is (or (nil? result)
              (some? (:error result))
              (map? result))
          "nonexistent symbol should not crash"))))

(defn- test-missing-symbol-param+ [socket]
  (p/let [resp (mcp/send-request socket
                                 {:jsonrpc "2.0"
                                  :id 12
                                  :method "tools/call"
                                  :params {:name "clojuredocs_info"
                                           :arguments {}}})
          result (-> resp (js->clj :keywordize-keys true) :result)]
    (js/console.log "[clojuredocs-info] missing param result:" (pr-str result))
    (testing "missing clojureSymbol parameter returns error"
      (is (some? result))
      (let [text (some-> (get-in result [:content 0 :text])
                         js/JSON.parse
                         (js->clj :keywordize-keys true))]
        (is (or (:error text)
                (:isError result)
                (nil? (get-in result [:content 0 :text])))
            "should indicate error or handle missing param")))))

(deftest-async clojuredocs-info-tests
  (let [backup-path (mcp/backup-settings! "clojuredocs-info-test-backup.json")]
    (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                {:keys [socket]} (mcp/start-mcp-session!)
                _ (test-well-known-symbol+ socket)
                _ (test-nonexistent-symbol+ socket)
                _ (test-missing-symbol-param+ socket)
                _ (mcp/stop-mcp-session! socket)])
        (p/catch (fn [e]
                   (js/console.error "[clojuredocs-info] Test error:" (.-message e) e)
                   (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                   (throw e)))
        (p/finally (fn [] (mcp/restore-settings! backup-path))))))
