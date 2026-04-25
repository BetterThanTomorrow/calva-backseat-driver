(ns tests.mcp.load-file-test
  (:require
   ["path" :as path]
   ["vscode" :as vscode]
   [cljs.test :refer [deftest is testing]]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]
   [promesa.core :as p]))

(defn- calva-version []
  (some-> (vscode/extensions.getExtension "betterthantomorrow.calva")
          .-packageJSON .-version))

(defn- load-file-available? []
  (let [v (calva-version)]
    (when v
      (let [parts (.split v ".")
            patch (js/parseInt (re-find #"^\d+" (aget parts 2)))]
        (and (>= (js/parseInt (aget parts 0)) 2)
             (>= patch 576))))))

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
                  expected-present? (load-file-available?)

                  _ (js/console.log "[load-file-gate] Calva version:" (calva-version)
                                    "load-file expected?" expected-present?
                                    "actual?" (contains? tool-names "clojure_load_file"))
                  _ (mcp/stop-mcp-session! socket)]
            (if expected-present?
              (is (contains? tool-names "clojure_load_file")
                  "clojure_load_file should be present with Calva >= 2.0.576")
              (is (not (contains? tool-names "clojure_load_file"))
                  "clojure_load_file should be absent with Calva < 2.0.576")))
          (p/catch (fn [e]
                     (js/console.error "[load-file-gate] Error:" (.-message e) e)
                     (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                     (throw e)))
          (p/finally (fn []
                       (mcp/restore-settings! backup-path)))))))

(deftest-async load-file-functional
  (testing "clojure_load_file loads a file and the loaded var is accessible (when available)"
    (if-not (load-file-available?)
      (p/resolved
       (do (js/console.log "[load-file-functional] Skipping: Calva" (calva-version) "< 2.0.576")
           (is true "Skipped — Calva version too old for load-file")))
      (let [backup-path (mcp/backup-settings! "load-file-functional-backup.json")]
        (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                    {:keys [socket]} (mcp/start-mcp-session!)

                    ;; Get available session key
                    sessions (mcp/call-tool socket 1 "clojure_list_sessions" {})
                    session-key (or (->> (:sessions sessions)
                                        (some (fn [s] (when (:isActiveSession s) (:replSessionKey s)))))
                                   (:replSessionKey (first (:sessions sessions))))

                    ;; Load the test target file
                    file-path (path/join (.-fsPath mcp/workspace-uri) "test_load_target.clj")
                    _ (js/console.log "[load-file-functional] Loading:" file-path "session:" session-key)
                    load-result (mcp/call-tool socket 2 "clojure_load_file"
                                               {:filePath file-path
                                                :replSessionKey session-key})

                    _ (js/console.log "[load-file-functional] Load result:" (pr-str load-result))

                    ;; Verify the loaded var is accessible
                    eval-result (mcp/call-tool socket 3 "clojure_evaluate_code"
                                               {:code "test-load-target/loaded-sentinel"
                                                :namespace "user"
                                                :replSessionKey session-key
                                                :who "e2e-load-file-test"})

                    _ (js/console.log "[load-file-functional] Eval result:" (pr-str eval-result))
                    _ (mcp/stop-mcp-session! socket)]
              (is (some? load-result)
                  "Load file should return a result")
              (is (nil? (:error load-result))
                  "Load file should not return an error")
              (is (= "42" (:result eval-result))
                  "The loaded var should be accessible and equal to 42"))
            (p/catch (fn [e]
                       (js/console.error "[load-file-functional] Error:" (.-message e) e)
                       (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                       (throw e)))
            (p/finally (fn []
                         (mcp/restore-settings! backup-path))))))))

(deftest-async load-file-error-handling
  (testing "clojure_load_file returns isError for nonexistent file (when available)"
    (if-not (load-file-available?)
      (p/resolved
       (do (js/console.log "[load-file-error] Skipping: Calva" (calva-version) "< 2.0.576")
           (is true "Skipped — Calva version too old for load-file")))
      (let [backup-path (mcp/backup-settings! "load-file-error-backup.json")]
        (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                    {:keys [socket]} (mcp/start-mcp-session!)

                    resp (mcp/send-request socket
                                           {:jsonrpc "2.0"
                                            :id 2
                                            :method "tools/call"
                                            :params {:name "clojure_load_file"
                                                     :arguments {:filePath "/nonexistent/path/foo.clj"}}})
                    result (-> resp (js->clj :keywordize-keys true) :result)

                    _ (js/console.log "[load-file-error] Error result:" (pr-str result))
                    _ (mcp/stop-mcp-session! socket)]
              (is (= true (:isError result))
                  "Response should have isError true for nonexistent file")
              (is (some? (get-in result [:content 0 :text]))
                  "Error response should include error text"))
            (p/catch (fn [e]
                       (js/console.error "[load-file-error] Error:" (.-message e) e)
                       (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                       (throw e)))
            (p/finally (fn []
                         (mcp/restore-settings! backup-path))))))))