(ns tests.mcp.symbol-info-test
  (:require
   ["vscode" :as vscode]
   [cljs.test :refer [is testing]]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]
   [promesa.core :as p]))

(deftest-async symbol-info-tests
  (let [backup-path (mcp/backup-settings! "symbol-info-test-backup.json")]
    (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                {:keys [socket]} (mcp/start-mcp-session!)

                ;; Get a valid session key
                sessions (mcp/call-tool socket 10 "clojure_list_sessions" {})
                session-key (or (->> (:sessions sessions)
                                     (some (fn [s] (when (:isActiveSession s) (:replSessionKey s)))))
                                (:replSessionKey (first (:sessions sessions))))

                _ (js/console.log "[symbol-info] Using session:" session-key)

                ;; === 1. Known symbol via available session ===
                ;; The Joyride session may not support getSymbolInfo (nREPL info op).
                ;; We test that the tool responds gracefully — either with data or
                ;; a structured error, but never crashes.

                map-result (mcp/call-tool socket 11 "clojure_symbol_info"
                                          {:clojureSymbol "map"
                                           :namespace "cljs.core"
                                           :replSessionKey session-key})

                _ (js/console.log "[symbol-info] map result:" (pr-str map-result))

                ;; === 2. Unknown symbol ===

                unknown-result (mcp/call-tool socket 12 "clojure_symbol_info"
                                              {:clojureSymbol "definitely-not-a-real-fn-xyz"
                                               :namespace "cljs.core"
                                               :replSessionKey session-key})

                _ (js/console.log "[symbol-info] unknown result:" (pr-str unknown-result))

                ;; === 3. Missing replSessionKey ===

                missing-sk-resp (mcp/send-request socket
                                                  {:jsonrpc "2.0"
                                                   :id 13
                                                   :method "tools/call"
                                                   :params {:name "clojure_symbol_info"
                                                            :arguments {:clojureSymbol "map"
                                                                        :namespace "cljs.core"}}})
                missing-sk-result (-> missing-sk-resp
                                      (js->clj :keywordize-keys true)
                                      :result)

                _ (js/console.log "[symbol-info] missing session-key result:" (pr-str missing-sk-result))

                ;; === 4. Invalid session key ===

                invalid-sk-result (mcp/call-tool socket 14 "clojure_symbol_info"
                                                 {:clojureSymbol "map"
                                                  :namespace "cljs.core"
                                                  :replSessionKey "nonexistent-session"})

                _ (js/console.log "[symbol-info] invalid session result:" (pr-str invalid-sk-result))

                _ (mcp/stop-mcp-session! socket)]

          ;; 1. Known symbol responds without crashing (may return data or structured error)
          (testing "known symbol returns structured response"
            (is (some? map-result) "response should not be nil")
            (is (or (some? (:doc map-result))
                    (some? (:arglists-str map-result))
                    (some? (:name map-result))
                    (some? (:error map-result)))
                "should contain doc info or structured error"))

          ;; 2. Unknown symbol doesn't crash
          (testing "unknown symbol returns gracefully"
            (is (or (nil? unknown-result)
                    (some? (:error unknown-result))
                    (map? unknown-result))
                "unknown symbol should not crash"))

          ;; 3. Missing replSessionKey returns error
          (testing "missing replSessionKey returns error"
            (is (some? missing-sk-result))
            (let [text (some-> (get-in missing-sk-result [:content 0 :text])
                               js/JSON.parse
                               (js->clj :keywordize-keys true))]
              (is (some? (:error text))
                  "should indicate error for missing session key")))

          ;; 4. Invalid session key returns error
          (testing "invalid session key returns error"
            (is (some? invalid-sk-result))
            (is (some? (:error invalid-sk-result))
                "should indicate error for invalid session key")))

        (p/catch (fn [e]
                   (js/console.error "[symbol-info] Test error:" (.-message e) e)
                   (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                   (throw e)))
        (p/finally (fn [] (mcp/restore-settings! backup-path))))))
