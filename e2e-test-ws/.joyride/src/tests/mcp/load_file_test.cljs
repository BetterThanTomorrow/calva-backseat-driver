(ns tests.mcp.load-file-test
  (:require
   ["path" :as path]
   ["vscode" :as vscode]
   [cljs.test :refer [is testing]]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]
   [promesa.core :as p]))

;; --- Domain-specific helpers ---

(defn- get-session-key+ [socket]
  (p/let [sessions (mcp/call-tool socket 10 "clojure_list_sessions" {})]
    (or (->> (:sessions sessions)
             (some (fn [s] (when (:isActiveSession s) (:replSessionKey s)))))
        (:replSessionKey (first (:sessions sessions))))))

(defn- test-tool-presence+ [socket]
  (p/let [tools-response (mcp/send-request socket
                                           {:jsonrpc "2.0"
                                            :id 2
                                            :method "tools/list"})
          tools (-> tools-response
                    (js->clj :keywordize-keys true)
                    (get-in [:result :tools]))
          tool-names (set (map :name tools))]
    (testing "clojure_load_file presence matches Calva version gate"
      (is (contains? tool-names "clojure_load_file")
          "clojure_load_file should be present in tools/list"))))

(defn- assert-missing-param-error+
  "Call clojure_load_file with given args and assert error mentions param-name."
  [socket id arguments param-name]
  (p/let [resp (mcp/send-request socket
                                 {:jsonrpc "2.0"
                                  :id id
                                  :method "tools/call"
                                  :params {:name "clojure_load_file"
                                           :arguments arguments}})
          result (-> resp (js->clj :keywordize-keys true) :result)
          text (some-> (get-in result [:content 0 :text]) js/JSON.parse (js->clj :keywordize-keys true))]
    (testing (str "clojure_load_file rejects missing " param-name " parameter")
      (is (some? (:error text))
          (str "Should return an error when " param-name " is missing"))
      (is (re-find (re-pattern param-name) (:error text))
          (str "Error should mention the " param-name " parameter")))))

(defn- test-requires-who+ [socket session-key]
  (assert-missing-param-error+ socket 11
    {:filePath "/some/file.clj" :replSessionKey session-key}
    "who"))

(defn- test-requires-session-key+ [socket]
  (assert-missing-param-error+ socket 12
    {:filePath "/some/file.clj" :who "e2e-load-file-test"}
    "replSessionKey"))

(defn- test-functional-load+ [socket session-key]
  (p/let [file-path (path/join (.-fsPath mcp/workspace-uri) "test_load_target.clj")
          load-result (mcp/call-tool socket 13 "clojure_load_file"
                                     {:filePath file-path
                                      :replSessionKey session-key
                                      :who "e2e-load-file-test"})
          eval-result (mcp/call-tool socket 14 "clojure_evaluate_code"
                                     {:code "test-load-target/loaded-sentinel"
                                      :namespace "user"
                                      :replSessionKey session-key
                                      :who "e2e-load-file-test"})]
    (testing "clojure_load_file loads a file and the loaded var is accessible"
      (is (= "#'test-load-target/loaded-sentinel" (:result load-result))
          "Load file should return the var definition result")
      (is (nil? (:error load-result))
          "Load file should not return an error")
      (is (= "42" (:result eval-result))
          "The loaded var should be accessible and equal to 42"))))

(defn- test-error-nonexistent-file+ [socket session-key]
  (p/let [err-resp (mcp/send-request socket
                                     {:jsonrpc "2.0"
                                      :id 15
                                      :method "tools/call"
                                      :params {:name "clojure_load_file"
                                               :arguments {:filePath "/nonexistent/path/foo.clj"
                                                           :replSessionKey session-key
                                                           :who "e2e-load-file-test"}}})
          err-result (-> err-resp (js->clj :keywordize-keys true) :result)]
    (testing "clojure_load_file returns isError for nonexistent file"
      (is (= true (:isError err-result))
          "Response should have isError true for nonexistent file")
      (is (some? (get-in err-result [:content 0 :text]))
          "Error response should include error text"))))

;; --- Test orchestrator ---

(deftest-async load-file-tests
  (let [backup-path (mcp/backup-settings! "load-file-test-backup.json")]
    (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                {:keys [socket]} (mcp/start-mcp-session!)
                session-key (get-session-key+ socket)]
          (p/do (test-tool-presence+ socket)
                (test-requires-who+ socket session-key)
                (test-requires-session-key+ socket)
                (test-functional-load+ socket session-key)
                (test-error-nonexistent-file+ socket session-key)
                (mcp/stop-mcp-session! socket)))
        (p/catch (fn [e]
                   (js/console.error "[load-file-tests] Error:" (.-message e) e)
                   (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                   (throw e)))
        (p/finally (fn []
                     (mcp/restore-settings! backup-path))))))
