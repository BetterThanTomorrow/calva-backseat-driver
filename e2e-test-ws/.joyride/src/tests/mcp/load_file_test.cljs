(ns tests.mcp.load-file-test
  (:require
   ["path" :as path]
   ["vscode" :as vscode]
   [cljs.test :refer [is testing]]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]))

;; --- Domain-specific helpers ---

(defn- ^:async get-session-key+ [socket]
  (let [sessions (await (mcp/call-tool socket 10 "clojure_list_sessions" {}))]
    (or (->> (:sessions sessions)
             (some (fn [s] (when (:currentRoutedTarget s) (:replSessionKey s)))))
        (:replSessionKey (first (:sessions sessions))))))

(defn- ^:async test-tool-presence+ [socket]
  (let [_ (await (mcp/wait-for-tool! socket "clojure_load_file"))]
    (testing "clojure_load_file presence matches Calva version gate"
      (is true "clojure_load_file should be present in tools/list"))))

(defn- ^:async assert-missing-param-error+
  "Call clojure_load_file with given args and assert error mentions param-name."
  [socket id arguments param-name]
  (let [resp (await (mcp/send-request socket
                                      {:jsonrpc "2.0"
                                       :id id
                                       :method "tools/call"
                                       :params {:name "clojure_load_file"
                                                :arguments arguments}}))
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

(defn- ^:async test-functional-load+ [socket session-key]
  (let [file-path (path/join (.-fsPath mcp/workspace-uri) "test_load_target.clj")
        load-result (await (mcp/call-tool socket 13 "clojure_load_file"
                                          {:filePath file-path
                                           :replSessionKey session-key
                                           :who "e2e-load-file-test"}))
        eval-result (await (mcp/call-tool socket 14 "clojure_evaluate_code"
                                          {:code "test-load-target/loaded-sentinel"
                                           :namespace "user"
                                           :replSessionKey session-key
                                           :who "e2e-load-file-test"}))]
    (testing "clojure_load_file loads a file and the loaded var is accessible"
      (is (= "#'test-load-target/loaded-sentinel" (:result load-result))
          "Load file should return the var definition result")
      (is (nil? (:error load-result))
          "Load file should not return an error")
      (is (= "42" (:result eval-result))
          "The loaded var should be accessible and equal to 42"))))

(defn- ^:async test-error-nonexistent-file+ [socket session-key]
  (let [err-resp (await (mcp/send-request socket
                                          {:jsonrpc "2.0"
                                           :id 15
                                           :method "tools/call"
                                           :params {:name "clojure_load_file"
                                                    :arguments {:filePath "/nonexistent/path/foo.clj"
                                                                :replSessionKey session-key
                                                                :who "e2e-load-file-test"}}}))
        err-result (-> err-resp (js->clj :keywordize-keys true) :result)]
    (testing "clojure_load_file returns isError for nonexistent file"
      (is (= true (:isError err-result))
          "Response should have isError true for nonexistent file")
      (is (some? (get-in err-result [:content 0 :text]))
          "Error response should include error text"))))

;; --- Test orchestrator ---

(deftest-async load-file-tests
  (let [backup-path (mcp/backup-settings! "load-file-test-backup.json")]
    (try
      (let [_ (await (mcp/ensure-repl-and-eval-enabled!))
            {:keys [socket]} (await (mcp/start-mcp-session!))
            session-key (await (get-session-key+ socket))]
        (await (test-tool-presence+ socket))
        (await (test-requires-who+ socket session-key))
        (await (test-requires-session-key+ socket))
        (await (test-functional-load+ socket session-key))
        (await (test-error-nonexistent-file+ socket session-key))
        (await (mcp/stop-mcp-session! socket)))
      (catch :default e
        (js/console.error "[load-file-tests] Error:" (.-message e) e)
        (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
        (throw e))
      (finally
        (await (mcp/restore-settings! backup-path))))))
