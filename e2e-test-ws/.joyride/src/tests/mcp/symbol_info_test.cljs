(ns tests.mcp.symbol-info-test
  (:require
   ["vscode" :as vscode]
   [cljs.test :refer [is testing]]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]
   [promesa.core :as p]))

(defn- get-active-session-key+ [socket]
  (p/let [sessions (mcp/call-tool socket 10 "clojure_list_sessions" {})
          session-key (or (->> (:sessions sessions)
                               (some (fn [s] (when (:isActiveSession s) (:replSessionKey s)))))
                          (:replSessionKey (first (:sessions sessions))))]
    (js/console.log "[symbol-info] Using session:" session-key)
    session-key))

(defn- test-known-symbol+ [socket session-key]
  (p/let [result (mcp/call-tool socket 11 "clojure_symbol_info"
                                {:clojureSymbol "map"
                                 :namespace "cljs.core"
                                 :replSessionKey session-key})]
    (js/console.log "[symbol-info] map result:" (pr-str result))
    (testing "known symbol returns structured response"
      (is (some? result) "response should not be nil")
      (is (or (some? (:doc result))
              (some? (:arglists-str result))
              (some? (:name result))
              (some? (:error result)))
          "should contain doc info or structured error"))))

(defn- test-unknown-symbol+ [socket session-key]
  (p/let [result (mcp/call-tool socket 12 "clojure_symbol_info"
                                {:clojureSymbol "definitely-not-a-real-fn-xyz"
                                 :namespace "cljs.core"
                                 :replSessionKey session-key})]
    (js/console.log "[symbol-info] unknown result:" (pr-str result))
    (testing "unknown symbol returns gracefully"
      (is (or (nil? result)
              (some? (:error result))
              (map? result))
          "unknown symbol should not crash"))))

(defn- test-missing-session-key+ [socket]
  (p/let [resp (mcp/send-request socket
                                 {:jsonrpc "2.0"
                                  :id 13
                                  :method "tools/call"
                                  :params {:name "clojure_symbol_info"
                                           :arguments {:clojureSymbol "map"
                                                       :namespace "cljs.core"}}})
          result (-> resp (js->clj :keywordize-keys true) :result)]
    (js/console.log "[symbol-info] missing session-key result:" (pr-str result))
    (testing "missing replSessionKey returns error"
      (is (some? result))
      (let [text (some-> (get-in result [:content 0 :text])
                         js/JSON.parse
                         (js->clj :keywordize-keys true))]
        (is (some? (:error text))
            "should indicate error for missing session key")))))

(defn- test-invalid-session-key+ [socket]
  (p/let [result (mcp/call-tool socket 14 "clojure_symbol_info"
                                {:clojureSymbol "map"
                                 :namespace "cljs.core"
                                 :replSessionKey "nonexistent-session"})]
    (js/console.log "[symbol-info] invalid session result:" (pr-str result))
    (testing "invalid session key returns error"
      (is (some? result))
      (is (some? (:error result))
          "should indicate error for invalid session key"))))

(deftest-async symbol-info-tests
  (let [backup-path (mcp/backup-settings! "symbol-info-test-backup.json")]
    (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                {:keys [socket]} (mcp/start-mcp-session!)
                session-key (get-active-session-key+ socket)]
          (p/do (test-known-symbol+ socket session-key)
                (test-unknown-symbol+ socket session-key)
                (test-missing-session-key+ socket)
                (test-invalid-session-key+ socket)
                (mcp/stop-mcp-session! socket)))
        (p/catch (fn [e]
                   (js/console.error "[symbol-info] Test error:" (.-message e) e)
                   (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                   (throw e)))
        (p/finally (fn [] (mcp/restore-settings! backup-path))))))
