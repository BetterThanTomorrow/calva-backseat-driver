(ns tests.mcp.server-test
  (:require
   ["net" :as net]
   ["path" :as path]
   ["vscode" :as vscode]
   [cljs.test :refer [deftest is testing]]
   [e2e.macros :refer [deftest-async]]
   [promesa.core :as p]))

(def workspace-uri (.-uri (first vscode/workspace.workspaceFolders)))
(def settings-path (.-fsPath (vscode/Uri.joinPath workspace-uri ".vscode" "settings.json")))
(def settings-backup-path (path/join (path/dirname settings-path) "integration-test-backup-settings.json"))

(deftest minimal-test
  (testing "Basic test to verify test discovery"
    (is (= true true) "True should be true")))

(deftest-async command-registration
  (testing "MCP server commands are registered"
    (try
      (p/let [#_#__ (vscode/commands.executeCommand "calva-backseat-driver.startMcpServer")
              pre-activation (vscode/commands.getCommands true)]
        (is (= false
               (.includes pre-activation "calva-backseat-driver.startMcpServer"))
            "there is no start server command before activation")
        (is (= false
               (.includes pre-activation "calva-backseat-driver.stopMcpServer"))
            "there is no stop server command before activation")
        (p/let [extension (vscode/extensions.getExtension "betterthantomorrow.calva-backseat-driver")
                _ (.activate extension)
                post-activation (vscode/commands.getCommands true)]
          (is (= true
                 (.includes post-activation "calva-backseat-driver.startMcpServer"))
              "there is a start server command after activation")
          (is (= true
                 (.includes post-activation "calva-backseat-driver.stopMcpServer"))
              "there is a stop server command before server start")))
      (catch :default e
        (js/console.error (.-message e) e)))))

(defn- connect-to-mcp-server [port]
  (p/create
   (fn [resolve reject]
     (let [socket (.connect net #js {:port port})]
       (.on socket "error" (fn [err]
                            (js/console.error "[MCP client] Socket error:" err)
                            (reject err)))
       (.on socket "connect" (fn []
                              (js/console.log "[MCP client] Connected to server port:" port)
                              (resolve socket)))))))

(defn- send-request [socket request-obj]
  (p/create
   (fn [resolve reject]
     (let [buffer (atom "")
           request-str (str (.stringify js/JSON (clj->js request-obj)) "\n")]
       (.once socket "data" (fn [data]
                           (swap! buffer str data)
                           (try
                             (resolve (.parse js/JSON @buffer))
                             (catch :default e
                               (reject e)))))
       (.write socket request-str)))))

(deftest-async server-lifecycle
  (testing "MCP server can be started and stopped via commands"
    (-> (p/let [_ (p/delay 500) ; Allow log file to have been created
                _ (js/console.log "[server-lifecycle] Attempting to start MCP server...")
                server-info+ (vscode/commands.executeCommand "calva-backseat-driver.startMcpServer")
                {:keys [instance port]} (js->clj server-info+ :keywordize-keys true)

                _ (js/console.log "[server-lifecycle] Attempting to stop MCP server...")
                success?+ (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")]
          (is (not= nil
                    instance)
              "Server instance is something")
          (is (number? port)
              "Server started on a port")
          (is (= true
                 success?+)
              "Server stopped successfully"))
        (p/catch (fn [e]
                   (js/console.error (.-message e) e))))))

(deftest-async tools-validation
  (testing "MCP server tools have valid descriptions"
    (-> (p/let [_ (js/console.log "[tools-validation] Starting MCP server...")
                server-info+ (vscode/commands.executeCommand "calva-backseat-driver.startMcpServer")
                server-info (js->clj server-info+ :keywordize-keys true)
                port (:port server-info)

                _ (js/console.log "[tools-validation] Connecting to MCP server...")
                socket (connect-to-mcp-server port)

                _ (js/console.log "[tools-validation] Sending initialize request...")
                _ (send-request socket {:jsonrpc "2.0"
                                       :id 1
                                       :method "initialize"})

                _ (js/console.log "[tools-validation] Sending tools/list request...")
                tools-response (send-request socket {:jsonrpc "2.0"
                                                    :id 2
                                                    :method "tools/list"})

                tools (-> tools-response
                          (js->clj :keywordize-keys true)
                          (get-in [:result :tools]))

                _ (js/console.log "[tools-validation] Tools:" (pr-str tools))
                _ (js/console.log "[tools-validation] Stopping MCP server...")
                _ (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                _ (.end socket)]

          (is (sequential? tools)
              "Response includes a tools array")
          (is (pos? (count tools))
              "At least one tool is returned")

          (doseq [tool tools]
            (is (string? (:name tool))
                (str "Tool has a valid name: " (:name tool)))
            (is (string? (:description tool))
                (str "Tool has a valid description: " (:name tool)))
            (is (not (nil? (:description tool)))
                (str "Tool description is not null: " (:name tool)))
            (is (seq (:description tool))
                (str "Tool description is not empty: " (:name tool)))))

        (p/catch (fn [e]
                   (js/console.error "[tools-validation] Error:" (.-message e) e)
                   (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                   (throw e))))))

(deftest-async tools-validation-with-specific-tools
  (testing "MCP server registers expected tools including evaluation"
    (-> (p/let [_ (js/console.log "[tools-validation] Starting MCP server...")
                server-info+ (vscode/commands.executeCommand "calva-backseat-driver.startMcpServer")
                server-info (js->clj server-info+ :keywordize-keys true)
                port (:port server-info)

                _ (js/console.log "[tools-validation] Connecting to MCP server...")
                socket (connect-to-mcp-server port)

                _ (send-request socket {:jsonrpc "2.0" :id 1 :method "initialize"})

                tools-response (send-request socket {:jsonrpc "2.0" :id 2 :method "tools/list"})
                tools (-> tools-response
                          (js->clj :keywordize-keys true)
                          (get-in [:result :tools]))
                tool-names (set (map :name tools))

                _ (js/console.log "[tools-validation] Tool names:" (pr-str tool-names))
                _ (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                _ (.end socket)]

          ;; Always-registered tools
          (is (contains? tool-names "clojure_balance_brackets")
              "Bracket balancer should always be registered")
          (is (contains? tool-names "replace_top_level_form")
              "Replace form tool should always be registered")
          (is (contains? tool-names "insert_top_level_form")
              "Insert form tool should always be registered")
          (is (contains? tool-names "clojure_create_file")
              "Create file tool should always be registered")
          (is (contains? tool-names "clojure_append_code")
              "Append code tool should always be registered")

          ;; Conditional tools - would be registered IF Calva APIs are available
          ;; For now, just log whether they're present
          (js/console.log "Symbol info present?" (contains? tool-names "clojure_symbol_info"))
          (js/console.log "ClojureDocs present?" (contains? tool-names "clojuredocs_info"))
          (js/console.log "Output log present?" (contains? tool-names "clojure_repl_output_log"))

          ;; Evaluation tool - controlled by setting but defaults to DISABLED for MCP
          (js/console.log "Evaluation tool present?" (contains? tool-names "clojure_evaluate_code")))

        (p/catch (fn [e]
                   (js/console.error "[tools-validation] Error:" (.-message e) e)
                   (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                   (throw e))))))

#_(deftest-async tools-validation-with-repl-eval-enabled
  (testing "MCP server includes evaluation tool when enabled and REPL is connected"
    (fs/copyFileSync settings-path settings-backup-path)
    (-> (p/let [;; First ensure Joyride REPL is connected
                _ (vscode/commands.executeCommand "calva.startJoyrideReplAndConnect")
                _ (p/delay 1000) ; Give REPL time to connect

                ;; Enable the evaluation tool setting
                config (vscode/workspace.getConfiguration "calva-backseat-driver")
                _ (.update config "enableMcpReplEvaluation" true vscode/ConfigurationTarget.Workspace)

                _ (js/console.log "[eval-tool-test] Starting MCP server with eval enabled...")
                server-info+ (vscode/commands.executeCommand "calva-backseat-driver.startMcpServer")
                server-info (js->clj server-info+ :keywordize-keys true)
                port (:port server-info)

                _ (js/console.log "[eval-tool-test] Connecting to MCP server...")
                socket (connect-to-mcp-server port)

                _ (send-request socket {:jsonrpc "2.0" :id 1 :method "initialize"})

                tools-response (send-request socket {:jsonrpc "2.0" :id 2 :method "tools/list"})
                tools (-> tools-response
                          (js->clj :keywordize-keys true)
                          (get-in [:result :tools]))
                tool-names (set (map :name tools))

                _ (js/console.log "[eval-tool-test] Tool names with eval enabled:" (pr-str tool-names))

                ;; Cleanup
                _ (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                _ (.end socket)

                ;; Restore setting to default
                _ (.update config "enableMcpReplEvaluation" false vscode/ConfigurationTarget.Workspace)]

          ;; The evaluation tool should now be present
          (is (contains? tool-names "clojure_evaluate_code")
              "Evaluation tool should be present when setting is enabled and REPL is connected"))

        (p/catch (fn [e]
                   (js/console.error "[eval-tool-test] Error:" (.-message e) e)
                   (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                   (throw e)))
        (p/finally (fn []
                     (fs/renameSync settings-backup-path settings-path))))))
