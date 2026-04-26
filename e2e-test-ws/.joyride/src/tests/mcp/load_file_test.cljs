(ns tests.mcp.load-file-test
  (:require
   ["path" :as path]
   ["vscode" :as vscode]
   [cljs.test :refer [is testing]]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]
   [promesa.core :as p]))

;; All load-file tests share a single MCP server session to avoid
;; settings/server lifecycle race conditions between async tests.

(deftest-async load-file-tests
  (let [backup-path (mcp/backup-settings! "load-file-test-backup.json")]
    (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                {:keys [socket]} (mcp/start-mcp-session!)

                ;; === 1. Version gate: tool should be present ===

                tools-response (mcp/send-request socket
                                                 {:jsonrpc "2.0"
                                                  :id 2
                                                  :method "tools/list"})
                tools (-> tools-response
                          (js->clj :keywordize-keys true)
                          (get-in [:result :tools]))
                tool-names (set (map :name tools))
                has-load-file? (contains? tool-names "clojure_load_file")

                _ (js/console.log "[load-file-gate] tool present?" has-load-file?)

                ;; === 2. Requires who parameter ===

                sessions (mcp/call-tool socket 10 "clojure_list_sessions" {})
                session-key (or (->> (:sessions sessions)
                                     (some (fn [s] (when (:isActiveSession s) (:replSessionKey s)))))
                                (:replSessionKey (first (:sessions sessions))))

                who-resp (mcp/send-request socket
                                           {:jsonrpc "2.0"
                                            :id 11
                                            :method "tools/call"
                                            :params {:name "clojure_load_file"
                                                     :arguments {:filePath "/some/file.clj"
                                                                 :replSessionKey session-key}}})
                who-result (-> who-resp (js->clj :keywordize-keys true) :result)
                who-text (some-> (get-in who-result [:content 0 :text]) js/JSON.parse (js->clj :keywordize-keys true))

                _ (js/console.log "[load-file-who] Missing who result:" (pr-str who-text))

                ;; === 3. Requires replSessionKey parameter ===

                sk-resp (mcp/send-request socket
                                          {:jsonrpc "2.0"
                                           :id 12
                                           :method "tools/call"
                                           :params {:name "clojure_load_file"
                                                    :arguments {:filePath "/some/file.clj"
                                                                :who "e2e-load-file-test"}}})
                sk-resp-clj (-> sk-resp (js->clj :keywordize-keys true))
                sk-result (:result sk-resp-clj)
                sk-text (some-> (get-in sk-result [:content 0 :text]) js/JSON.parse (js->clj :keywordize-keys true))

                _ (js/console.log "[load-file-session-key] Full response:" (pr-str sk-resp-clj))

                ;; === 4. Functional: load file and verify var ===

                file-path (path/join (.-fsPath mcp/workspace-uri) "test_load_target.clj")
                _ (js/console.log "[load-file-functional] Loading:" file-path "session:" session-key)
                load-result (mcp/call-tool socket 13 "clojure_load_file"
                                           {:filePath file-path
                                            :replSessionKey session-key
                                            :who "e2e-load-file-test"})

                _ (js/console.log "[load-file-functional] Load result:" (pr-str load-result))

                eval-result (mcp/call-tool socket 14 "clojure_evaluate_code"
                                           {:code "test-load-target/loaded-sentinel"
                                            :namespace "user"
                                            :replSessionKey session-key
                                            :who "e2e-load-file-test"})

                _ (js/console.log "[load-file-functional] Eval result:" (pr-str eval-result))

                ;; === 5. Error handling: nonexistent file ===

                err-resp (mcp/send-request socket
                                           {:jsonrpc "2.0"
                                            :id 15
                                            :method "tools/call"
                                            :params {:name "clojure_load_file"
                                                     :arguments {:filePath "/nonexistent/path/foo.clj"
                                                                 :replSessionKey session-key
                                                                 :who "e2e-load-file-test"}}})
                err-result (-> err-resp (js->clj :keywordize-keys true) :result)

                _ (js/console.log "[load-file-error] Error result:" (pr-str err-result))
                _ (mcp/stop-mcp-session! socket)]

          ;; 1. Version gate
          (testing "clojure_load_file presence matches Calva version gate"
            (is has-load-file?
                "clojure_load_file should be present in tools/list"))

          ;; 2. Requires who
          (testing "clojure_load_file rejects missing who parameter"
            (is (some? (:error who-text))
                "Should return an error when who is missing")
            (is (re-find #"who" (:error who-text))
                "Error should mention the who parameter"))

          ;; 3. Requires replSessionKey
          (testing "clojure_load_file rejects missing replSessionKey parameter"
            (is (some? (:error sk-text))
                "Should return an error when replSessionKey is missing")
            (is (re-find #"replSessionKey" (:error sk-text))
                "Error should mention the replSessionKey parameter"))

          ;; 4. Functional
          (testing "clojure_load_file loads a file and the loaded var is accessible"
            (is (= "#'test-load-target/loaded-sentinel" (:result load-result))
                "Load file should return the var definition result")
            (is (nil? (:error load-result))
                "Load file should not return an error")
            (is (= "42" (:result eval-result))
                "The loaded var should be accessible and equal to 42"))

          ;; 5. Error handling
          (testing "clojure_load_file returns isError for nonexistent file"
            (is (= true (:isError err-result))
                "Response should have isError true for nonexistent file")
            (is (some? (get-in err-result [:content 0 :text]))
                "Error response should include error text")))
        (p/catch (fn [e]
                   (js/console.error "[load-file-tests] Error:" (.-message e) e)
                   (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                   (throw e)))
        (p/finally (fn []
                     (mcp/restore-settings! backup-path))))))
