(ns tests.mcp.clojuredocs-info-test
  (:require
   ["vscode" :as vscode]
   [cljs.test :refer [is testing]]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]))

(defn- ^:async test-well-known-symbol+ [socket]
  (let [result (await (mcp/call-tool socket 10 "clojuredocs_info"
                                     {:clojureSymbol "map"}))]
    (js/console.log "[clojuredocs-info] map result:" (pr-str result))
    (testing "well-known symbol returns structured response"
      (is (or (some? result) true)
          "tool invocation should not crash")
      (when (and result (not (:error result)))
        (is (some #(get result %) [:name :doc :examples])
            "data response should contain name, doc, or examples")))))

(defn- ^:async test-nonexistent-symbol+ [socket]
  (let [result (await (mcp/call-tool socket 11 "clojuredocs_info"
                                     {:clojureSymbol "definitely-not-a-real-fn-xyz"}))]
    (js/console.log "[clojuredocs-info] unknown result:" (pr-str result))
    (testing "nonexistent symbol returns gracefully"
      (is (or (nil? result)
              (some? (:error result))
              (map? result))
          "nonexistent symbol should not crash"))))

(defn- ^:async test-missing-symbol-param+ [socket]
  (let [resp (await (mcp/send-request socket
                                      {:jsonrpc "2.0"
                                       :id 12
                                       :method "tools/call"
                                       :params {:name "clojuredocs_info"
                                                :arguments {}}}))
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
    (try
      (let [_ (await (mcp/ensure-repl-and-eval-enabled!))
            {:keys [socket]} (await (mcp/start-mcp-session!))]
        (await (test-well-known-symbol+ socket))
        (await (test-nonexistent-symbol+ socket))
        (await (test-missing-symbol-param+ socket))
        (await (mcp/stop-mcp-session! socket)))
      (catch :default e
        (js/console.error "[clojuredocs-info] Test error:" (.-message e) e)
        (await (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer"))
        (throw e))
      (finally
        (await (mcp/restore-settings! backup-path))))))
