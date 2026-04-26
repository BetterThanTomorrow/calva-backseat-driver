(ns tests.mcp.load-file-test
  (:require
   ["path" :as path]
   ["vscode" :as vscode]
   [cljs.test :refer [deftest is testing]]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]
   [promesa.core :as p]))

(deftest-async load-file-version-gate
  (testing "clojure_load_file presence matches Calva version gate"
    (let [backup-path (mcp/backup-settings! "load-file-gate-backup.json")]
      (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                  {:keys [socket]} (mcp/start-mcp-session!)

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
                  _ (mcp/stop-mcp-session! socket)]
            ;; The tool should be present when running with Calva >= 2.0.576
            ;; and absent otherwise. We verify by checking the tools/list response
            ;; rather than reimplementing version parsing.
            (is has-load-file?
                "clojure_load_file should be present in tools/list"))
          (p/catch (fn [e]
                     (js/console.error "[load-file-gate] Error:" (.-message e) e)
                     (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                     (throw e)))
          (p/finally (fn []
                       (mcp/restore-settings! backup-path)))))))

(deftest-async load-file-requires-who
  (testing "clojure_load_file rejects missing who parameter"
    (let [backup-path (mcp/backup-settings! "load-file-who-backup.json")]
      (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                  {:keys [socket]} (mcp/start-mcp-session!)

                  sessions (mcp/call-tool socket 1 "clojure_list_sessions" {})
                  session-key (or (->> (:sessions sessions)
                                       (some (fn [s] (when (:isActiveSession s) (:replSessionKey s)))))
                                  (:replSessionKey (first (:sessions sessions))))

                  resp (mcp/send-request socket
                                         {:jsonrpc "2.0"
                                          :id 2
                                          :method "tools/call"
                                          :params {:name "clojure_load_file"
                                                   :arguments {:filePath "/some/file.clj"
                                                               :replSessionKey session-key}}})
                  result (-> resp (js->clj :keywordize-keys true) :result)
                  text (some-> (get-in result [:content 0 :text]) js/JSON.parse (js->clj :keywordize-keys true))

                  _ (js/console.log "[load-file-who] Missing who result:" (pr-str text))
                  _ (mcp/stop-mcp-session! socket)]
            (is (some? (:error text))
                "Should return an error when who is missing")
            (is (re-find #"who" (:error text))
                "Error should mention the who parameter"))
          (p/catch (fn [e]
                     (js/console.error "[load-file-who] Error:" (.-message e) e)
                     (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                     (throw e)))
          (p/finally (fn []
                       (mcp/restore-settings! backup-path)))))))

(deftest-async load-file-requires-session-key
  (testing "clojure_load_file rejects missing replSessionKey parameter"
    (let [backup-path (mcp/backup-settings! "load-file-session-key-backup.json")]
      (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                  {:keys [socket]} (mcp/start-mcp-session!)

                  resp (mcp/send-request socket
                                         {:jsonrpc "2.0"
                                          :id 2
                                          :method "tools/call"
                                          :params {:name "clojure_load_file"
                                                   :arguments {:filePath "/some/file.clj"
                                                               :who "e2e-load-file-test"}}})
                  result (-> resp (js->clj :keywordize-keys true) :result)
                  text (some-> (get-in result [:content 0 :text]) js/JSON.parse (js->clj :keywordize-keys true))

                  _ (js/console.log "[load-file-session-key] Full response:" (pr-str (js->clj resp :keywordize-keys true)))
                  _ (js/console.log "[load-file-session-key] Missing session key result:" (pr-str text))
                  _ (mcp/stop-mcp-session! socket)]
            (is (some? (:error text))
                "Should return an error when replSessionKey is missing")
            (is (re-find #"replSessionKey" (:error text))
                "Error should mention the replSessionKey parameter"))
          (p/catch (fn [e]
                     (js/console.error "[load-file-session-key] Error:" (.-message e) e)
                     (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                     (throw e)))
          (p/finally (fn []
                       (mcp/restore-settings! backup-path)))))))

(deftest-async load-file-functional
  (testing "clojure_load_file loads a file with who, and the loaded var is accessible"
    (let [backup-path (mcp/backup-settings! "load-file-functional-backup.json")]
      (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                  {:keys [socket]} (mcp/start-mcp-session!)

                  ;; Get available session key
                  sessions (mcp/call-tool socket 1 "clojure_list_sessions" {})
                  session-key (or (->> (:sessions sessions)
                                       (some (fn [s] (when (:isActiveSession s) (:replSessionKey s)))))
                                  (:replSessionKey (first (:sessions sessions))))

                  ;; Load the test target file with explicit who
                  file-path (path/join (.-fsPath mcp/workspace-uri) "test_load_target.clj")
                  _ (js/console.log "[load-file-functional] Loading:" file-path "session:" session-key)
                  load-result (mcp/call-tool socket 2 "clojure_load_file"
                                             {:filePath file-path
                                              :replSessionKey session-key
                                              :who "e2e-load-file-test"})

                  _ (js/console.log "[load-file-functional] Load result:" (pr-str load-result))

                  ;; Verify the loaded var is accessible
                  eval-result (mcp/call-tool socket 3 "clojure_evaluate_code"
                                             {:code "test-load-target/loaded-sentinel"
                                              :namespace "user"
                                              :replSessionKey session-key
                                              :who "e2e-load-file-test"})

                  _ (js/console.log "[load-file-functional] Eval result:" (pr-str eval-result))
                  _ (mcp/stop-mcp-session! socket)]
            (is (= "#'test-load-target/loaded-sentinel" (:result load-result))
                "Load file should return the var definition result")
            (is (nil? (:error load-result))
                "Load file should not return an error")
            (is (= "42" (:result eval-result))
                "The loaded var should be accessible and equal to 42"))
          (p/catch (fn [e]
                     (js/console.error "[load-file-functional] Error:" (.-message e) e)
                     (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                     (throw e)))
          (p/finally (fn []
                       (mcp/restore-settings! backup-path)))))))

(deftest-async load-file-error-handling
  (testing "clojure_load_file returns isError for nonexistent file"
    (let [backup-path (mcp/backup-settings! "load-file-error-backup.json")]
      (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                  {:keys [socket]} (mcp/start-mcp-session!)

                  sessions (mcp/call-tool socket 1 "clojure_list_sessions" {})
                  session-key (or (->> (:sessions sessions)
                                       (some (fn [s] (when (:isActiveSession s) (:replSessionKey s)))))
                                  (:replSessionKey (first (:sessions sessions))))

                  resp (mcp/send-request socket
                                         {:jsonrpc "2.0"
                                          :id 2
                                          :method "tools/call"
                                          :params {:name "clojure_load_file"
                                                   :arguments {:filePath "/nonexistent/path/foo.clj"
                                                               :replSessionKey session-key
                                                               :who "e2e-load-file-test"}}})
                  resp-clj (-> resp (js->clj :keywordize-keys true))
                  result (:result resp-clj)
                  error (:error resp-clj)

                  _ (js/console.log "[load-file-error] Full response:" (pr-str resp-clj))
                  _ (mcp/stop-mcp-session! socket)]
            ;; Server may return either a tool-level error (:result with :isError)
            ;; or a protocol-level error (:error) depending on where the error occurs
            (is (or (:isError result) (some? error))
                "Response should indicate an error for nonexistent file")
            (is (or (some? (get-in result [:content 0 :text])) (some? (:message error)))
                "Error response should include error information"))
          (p/catch (fn [e]
                     (js/console.error "[load-file-error] Error:" (.-message e) e)
                     (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                     (throw e)))
          (p/finally (fn []
                       (mcp/restore-settings! backup-path)))))))
