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

(deftest-async resources-list-validation
  (testing "MCP server returns skill resources"
    (-> (p/let [_ (js/console.log "[resources-list] Starting MCP server...")
                server-info+ (vscode/commands.executeCommand "calva-backseat-driver.startMcpServer")
                server-info (js->clj server-info+ :keywordize-keys true)
                port (:port server-info)

                _ (js/console.log "[resources-list] Connecting to MCP server...")
                socket (connect-to-mcp-server port)

                _ (send-request socket {:jsonrpc "2.0" :id 1 :method "initialize"})

                _ (js/console.log "[resources-list] Sending resources/list request...")
                resources-response (send-request socket {:jsonrpc "2.0"
                                                         :id 2
                                                         :method "resources/list"})
                resources (-> resources-response
                              (js->clj :keywordize-keys true)
                              (get-in [:result :resources]))

                _ (js/console.log "[resources-list] Resources:" (pr-str resources))
                _ (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                _ (.end socket)]

          (is (sequential? resources)
              "Response includes a resources array")
          (is (>= (count resources) 2)
              "At least 2 skill resources are returned")

          (doseq [resource resources]
            (is (string? (:uri resource))
                (str "Resource has a URI: " (:name resource)))
            (is (string? (:name resource))
                (str "Resource has a name: " (:uri resource)))
            (is (string? (:description resource))
                (str "Resource has a description: " (:name resource)))
            (is (= "text/markdown" (:mimeType resource))
                (str "Resource mimeType is text/markdown: " (:name resource)))
            (is (re-find #"^/skills/[^/]+/SKILL\.md$" (:uri resource))
                (str "URI matches /skills/{name}/SKILL.md pattern: " (:uri resource)))))

        (p/catch (fn [e]
                   (js/console.error "[resources-list] Error:" (.-message e) e)
                   (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                   (throw e))))))

(deftest-async resources-read-skill
  (testing "MCP server returns skill content via resources/read"
    (-> (p/let [_ (js/console.log "[resources-read] Starting MCP server...")
                server-info+ (vscode/commands.executeCommand "calva-backseat-driver.startMcpServer")
                server-info (js->clj server-info+ :keywordize-keys true)
                port (:port server-info)

                socket (connect-to-mcp-server port)

                _ (send-request socket {:jsonrpc "2.0" :id 1 :method "initialize"})

                resources-response (send-request socket {:jsonrpc "2.0"
                                                         :id 2
                                                         :method "resources/list"})
                resources (-> resources-response
                              (js->clj :keywordize-keys true)
                              (get-in [:result :resources]))

                first-uri (:uri (first resources))
                _ (js/console.log "[resources-read] Reading resource:" first-uri)
                read-response (send-request socket {:jsonrpc "2.0"
                                                    :id 3
                                                    :method "resources/read"
                                                    :params {:uri first-uri}})
                read-result (-> read-response
                                (js->clj :keywordize-keys true)
                                :result)
                contents (:contents read-result)

                _ (js/console.log "[resources-read] Got" (count contents) "content entries")
                _ (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                _ (.end socket)]

          (is (sequential? contents)
              "Response has contents array")
          (is (= 1 (count contents))
              "Exactly one content entry")

          (let [content (first contents)]
            (is (= first-uri (:uri content))
                "Content URI matches requested URI")
            (is (string? (:text content))
                "Content has text")
            (is (seq (:text content))
                "Content text is not empty")
            (is (= "text/markdown" (:mimeType content))
                "Content mimeType is text/markdown")
            (is (re-find #"---" (:text content))
                "Content contains frontmatter markers")))

        (p/catch (fn [e]
                   (js/console.error "[resources-read] Error:" (.-message e) e)
                   (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                   (throw e))))))

(deftest-async resources-read-unknown-skill
  (testing "MCP server returns error for unknown skill URI"
    (-> (p/let [_ (js/console.log "[resources-read-unknown] Starting MCP server...")
                server-info+ (vscode/commands.executeCommand "calva-backseat-driver.startMcpServer")
                server-info (js->clj server-info+ :keywordize-keys true)
                port (:port server-info)

                socket (connect-to-mcp-server port)

                _ (send-request socket {:jsonrpc "2.0" :id 1 :method "initialize"})

                _ (js/console.log "[resources-read-unknown] Reading nonexistent skill...")
                read-response (send-request socket {:jsonrpc "2.0"
                                                    :id 2
                                                    :method "resources/read"
                                                    :params {:uri "/skills/nonexistent/SKILL.md"}})
                result (js->clj read-response :keywordize-keys true)

                _ (js/console.log "[resources-read-unknown] Response:" (pr-str result))
                _ (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                _ (.end socket)]

          (is (some? (:error result))
              "Response has error")
          (is (= -32602 (get-in result [:error :code]))
              "Error code is -32602 (invalid params)"))

        (p/catch (fn [e]
                   (js/console.error "[resources-read-unknown] Error:" (.-message e) e)
                   (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                   (throw e))))))

(deftest-async initialize-dynamic-instructions
  (testing "MCP server initialize response contains dynamic instructions"
    (-> (p/let [_ (js/console.log "[init-instructions] Starting MCP server...")
                server-info+ (vscode/commands.executeCommand "calva-backseat-driver.startMcpServer")
                server-info (js->clj server-info+ :keywordize-keys true)
                port (:port server-info)

                socket (connect-to-mcp-server port)

                _ (js/console.log "[init-instructions] Sending initialize request...")
                init-response (send-request socket {:jsonrpc "2.0"
                                                    :id 1
                                                    :method "initialize"})
                init-result (-> init-response
                                (js->clj :keywordize-keys true)
                                :result)
                instructions (:instructions init-result)

                _ (js/console.log "[init-instructions] Instructions length:" (count instructions))
                _ (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                _ (.end socket)]

          (is (string? instructions)
              "Initialize response has instructions string")
          (is (seq instructions)
              "Instructions are not empty")
          (is (re-find #"resources/list" instructions)
              "Instructions mention resources/list")
          (is (re-find #"resources/read" instructions)
              "Instructions mention resources/read")
          (is (re-find #"backseat-driver" instructions)
              "Instructions mention backseat-driver skill")
          (is (re-find #"editing-clojure-files" instructions)
              "Instructions mention editing-clojure-files skill"))

        (p/catch (fn [e]
                   (js/console.error "[init-instructions] Error:" (.-message e) e)
                   (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                   (throw e))))))









(deftest-async conditional-skills-filtering
  (testing "Disabled skills are filtered from MCP responses"
    (-> (p/let [config (vscode/workspace.getConfiguration "calva-backseat-driver")

                ;; Disable the backseat-driver skill
                _ (.update config "provideBdSkill" false vscode/ConfigurationTarget.Workspace)

                _ (js/console.log "[conditional-skills] Starting MCP server with BD skill disabled...")
                server-info+ (vscode/commands.executeCommand "calva-backseat-driver.startMcpServer")
                server-info (js->clj server-info+ :keywordize-keys true)
                port (:port server-info)

                socket (connect-to-mcp-server port)

                _ (send-request socket {:jsonrpc "2.0" :id 1 :method "initialize"})

                ;; Test resources/list — BD skill should be absent
                resources-response (send-request socket {:jsonrpc "2.0"
                                                         :id 2
                                                         :method "resources/list"})
                resources (-> resources-response
                              (js->clj :keywordize-keys true)
                              (get-in [:result :resources]))
                resource-names (set (map :name resources))
                _ (js/console.log "[conditional-skills] Resource names:" (pr-str resource-names))]

          (is (not (contains? resource-names "backseat-driver"))
              "Disabled BD skill should not appear in resources/list")
          (is (contains? resource-names "editing-clojure-files")
              "Enabled edit skill should appear in resources/list")

          ;; Test resources/read — reading disabled skill should return error
          (p/let [read-response (send-request socket {:jsonrpc "2.0"
                                                      :id 3
                                                      :method "resources/read"
                                                      :params {:uri "/skills/backseat-driver/SKILL.md"}})
                  read-result (js->clj read-response :keywordize-keys true)
                  _ (js/console.log "[conditional-skills] Read disabled skill response:" (pr-str read-result))]

            (is (some? (:error read-result))
                "Reading disabled skill should return error")
            (is (= -32602 (get-in read-result [:error :code]))
                "Error code should be -32602 for disabled skill")

            ;; Test initialize — instructions should not mention disabled skill
            (p/let [init-response (send-request socket {:jsonrpc "2.0"
                                                        :id 4
                                                        :method "initialize"})
                    instructions (-> init-response
                                     (js->clj :keywordize-keys true)
                                     (get-in [:result :instructions]))
                    _ (js/console.log "[conditional-skills] Instructions length:" (count instructions))]

              (is (not (re-find #"backseat-driver" instructions))
                  "Instructions should not mention disabled backseat-driver skill")
              (is (re-find #"editing-clojure-files" instructions)
                  "Instructions should mention enabled editing-clojure-files skill")

              ;; Cleanup
              (p/let [_ (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                      _ (.end socket)
                      _ (.update config "provideBdSkill" true vscode/ConfigurationTarget.Workspace)]))))

        (p/catch (fn [e]
                   (js/console.error "[conditional-skills] Error:" (.-message e) e)
                   (-> (p/let [config (vscode/workspace.getConfiguration "calva-backseat-driver")
                               _ (.update config "provideBdSkill" true vscode/ConfigurationTarget.Workspace)]
                         (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer"))
                       (p/catch (fn [_])))
                   (throw e))))))
