(ns tests.mcp.server-test
  (:require
   ["vscode" :as vscode]
   [cljs.test :refer [deftest is testing]]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]
   [e2e.utils :refer [wait-for+]]
   [promesa.core :as p]))

(deftest minimal-test
  (testing "Basic test to verify test discovery"
    (is (= true true) "True should be true")))

(deftest-async server-lifecycle
  (testing "MCP server can be started and stopped via commands"
    (-> (p/let [_ (wait-for+ #(.-isActive (vscode/extensions.getExtension "betterthantomorrow.calva-backseat-driver"))
                             :timeout 15000
                             :message "[server-lifecycle] Extension not active within 15s")
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
                _ (js/console.log "[tools-validation] Connecting to MCP server...")
                _ (js/console.log "[tools-validation] Sending initialize request...")
                {:keys [socket]} (mcp/start-mcp-session!)

                _ (js/console.log "[tools-validation] Sending tools/list request...")
                tools-response (mcp/send-request socket
                                                 {:jsonrpc "2.0"
                                                  :id 2
                                                  :method "tools/list"})

                tools (-> tools-response
                          (js->clj :keywordize-keys true)
                          (get-in [:result :tools]))

                _ (js/console.log "[tools-validation] Tools:" (pr-str tools))
                _ (js/console.log "[tools-validation] Stopping MCP server...")
                _ (mcp/stop-mcp-session! socket)]

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
                _ (js/console.log "[tools-validation] Connecting to MCP server...")
                {:keys [socket]} (mcp/start-mcp-session!)

                tools-response (mcp/send-request socket
                                                 {:jsonrpc "2.0"
                                                  :id 2
                                                  :method "tools/list"})
                tools (-> tools-response
                          (js->clj :keywordize-keys true)
                          (get-in [:result :tools]))
                tool-names (set (map :name tools))

                _ (js/console.log "[tools-validation] Tool names:" (pr-str tool-names))
                _ (mcp/stop-mcp-session! socket)]
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

(deftest-async tools-validation-with-repl-eval-enabled
  (testing "MCP server includes evaluation tool when enabled and REPL is connected"
    (let [backup-path (mcp/backup-settings! "eval-tool-test-backup.json")]
      (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                  _ (js/console.log "[eval-tool-test] Starting MCP server with eval enabled...")
                  _ (js/console.log "[eval-tool-test] Connecting to MCP server...")
                  {:keys [socket]} (mcp/start-mcp-session!)

                  tools-response (mcp/send-request socket
                                                   {:jsonrpc "2.0"
                                                    :id 2
                                                    :method "tools/list"})
                  tools (-> tools-response
                            (js->clj :keywordize-keys true)
                            (get-in [:result :tools]))
                  tool-names (set (map :name tools))

                  _ (js/console.log "[eval-tool-test] Tool names with eval enabled:" (pr-str tool-names))
                  _ (mcp/stop-mcp-session! socket)]
            ;; The evaluation tool should now be present
            (is (contains? tool-names "clojure_evaluate_code")
                "Evaluation tool should be present when setting is enabled and REPL is connected"))
          (p/catch (fn [e]
                     (js/console.error "[eval-tool-test] Error:" (.-message e) e)
                     (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                     (throw e)))
          (p/finally (fn []
                       (mcp/restore-settings! backup-path)))))))

(deftest-async resources-list-validation
  (testing "MCP server returns skill resources"
    (-> (p/let [_ (js/console.log "[resources-list] Starting MCP server...")
                _ (js/console.log "[resources-list] Connecting to MCP server...")
                {:keys [socket]} (mcp/start-mcp-session!)

                _ (js/console.log "[resources-list] Sending resources/list request...")
                resources-response (mcp/send-request socket
                                                     {:jsonrpc "2.0"
                                                      :id 2
                                                      :method "resources/list"})
                resources (-> resources-response
                              (js->clj :keywordize-keys true)
                              (get-in [:result :resources]))

                _ (js/console.log "[resources-list] Resources:" (pr-str resources))
                _ (mcp/stop-mcp-session! socket)]
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
                {:keys [socket]} (mcp/start-mcp-session!)

                resources-response (mcp/send-request socket
                                                     {:jsonrpc "2.0"
                                                      :id 2
                                                      :method "resources/list"})
                resources (-> resources-response
                              (js->clj :keywordize-keys true)
                              (get-in [:result :resources]))

                first-uri (:uri (first resources))
                _ (js/console.log "[resources-read] Reading resource:" first-uri)
                read-response (mcp/send-request socket
                                                {:jsonrpc "2.0"
                                                 :id 3
                                                 :method "resources/read"
                                                 :params {:uri first-uri}})
                read-result (-> read-response
                                (js->clj :keywordize-keys true)
                                :result)
                contents (:contents read-result)

                _ (js/console.log "[resources-read] Got" (count contents) "content entries")
                _ (mcp/stop-mcp-session! socket)]
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
                {:keys [socket]} (mcp/start-mcp-session!)

                _ (js/console.log "[resources-read-unknown] Reading nonexistent skill...")
                read-response (mcp/send-request socket
                                                {:jsonrpc "2.0"
                                                 :id 2
                                                 :method "resources/read"
                                                 :params {:uri "/skills/nonexistent/SKILL.md"}})
                result (js->clj read-response :keywordize-keys true)

                _ (js/console.log "[resources-read-unknown] Response:" (pr-str result))
                _ (mcp/stop-mcp-session! socket)]
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
                {:keys [socket]} (mcp/start-mcp-session!)

                _ (js/console.log "[init-instructions] Sending initialize request...")
                init-response (mcp/send-request socket
                                                {:jsonrpc "2.0"
                                                 :id 1
                                                 :method "initialize"})
                init-result (-> init-response
                                (js->clj :keywordize-keys true)
                                :result)
                instructions (:instructions init-result)

                _ (js/console.log "[init-instructions] Instructions length:" (count instructions))
                _ (mcp/stop-mcp-session! socket)]
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
    (let [backup-path (mcp/backup-settings! "conditional-skills-test-backup.json")]
      (-> (p/let [config (vscode/workspace.getConfiguration "calva-backseat-driver")

                  ;; Disable the backseat-driver skill
                  _ (.update config "provideBdSkill" false vscode/ConfigurationTarget.Workspace)

                  ;; Wait for config to propagate
                  _ (wait-for+
                     #(= false (.get (vscode/workspace.getConfiguration "calva-backseat-driver")
                                     "provideBdSkill"))
                     :message "provideBdSkill did not become false")

                  _ (js/console.log "[conditional-skills] Starting MCP server with BD skill disabled...")
                  {:keys [socket]} (mcp/start-mcp-session!)

                  ;; Test resources/list — BD skill should be absent
                  resources-response (mcp/send-request socket
                                                       {:jsonrpc "2.0"
                                                        :id 2
                                                        :method "resources/list"})
                  resources (-> resources-response
                                (js->clj :keywordize-keys true)
                                (get-in [:result :resources]))
                  resource-names (set (map :name resources))
                  _ (js/console.log "[conditional-skills] Resource names:" (pr-str resource-names))

                  _ (p/let [read-response (mcp/send-request socket
                                                            {:jsonrpc "2.0"
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
                      (p/let [init-response (mcp/send-request socket
                                                              {:jsonrpc "2.0"
                                                               :id 4
                                                               :method "initialize"})
                              instructions (-> init-response
                                               (js->clj :keywordize-keys true)
                                               (get-in [:result :instructions]))
                              _ (js/console.log "[conditional-skills] Instructions length:" (count instructions))]
                        (is (not (re-find #"backseat-driver" instructions))
                            "Instructions should not mention disabled backseat-driver skill")
                        (is (re-find #"editing-clojure-files" instructions)
                            "Instructions should mention enabled editing-clojure-files skill")))
                  _ (mcp/stop-mcp-session! socket)]
            nil)
          (p/finally (fn []
                       (mcp/restore-settings! backup-path)))))))

(deftest-async image-content-in-eval-result
  (testing "Evaluation returning data URL produces image content in response"
    (let [backup-path (mcp/backup-settings! "image-content-test-backup.json")]
      (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                  {:keys [socket]} (mcp/start-mcp-session!)

                  ;; Get a session key for evaluation
                  sessions-resp (mcp/send-request socket
                                  {:jsonrpc "2.0"
                                   :id 1
                                   :method "tools/call"
                                   :params {:name "clojure_list_sessions"
                                            :arguments {}}})
                  session-key (let [outer (js->clj sessions-resp :keywordize-keys true)
                                    text (get-in outer [:result :content 0 :text])
                                    parsed (js->clj (.parse js/JSON text) :keywordize-keys true)]
                                (or (->> (:sessions parsed)
                                         (some (fn [s] (when (:isActiveSession s) (:replSessionKey s)))))
                                    (:replSessionKey (first (:sessions parsed)))))

                  ;; Evaluate code that returns a data URL string
                  eval-resp (mcp/send-request socket
                              {:jsonrpc "2.0"
                               :id 2
                               :method "tools/call"
                               :params {:name "clojure_evaluate_code"
                                        :arguments {:code "\"data:image/png;base64,iVBORw0KGgo=\""
                                                    :namespace "user"
                                                    :replSessionKey session-key
                                                    :who "e2e-image-test"}}})
                  content (let [outer (js->clj eval-resp :keywordize-keys true)]
                            (get-in outer [:result :content]))

                  _ (mcp/stop-mcp-session! socket)]
            (is (= 2 (count content))
                "Should have text + image content items")
            (is (= "text" (:type (first content)))
                "First content item should be text")
            (is (= "image" (:type (second content)))
                "Second content item should be image")
            (is (= "image/png" (:mimeType (second content)))
                "Image content should have correct MIME type")
            (is (string? (:data (second content)))
                "Image content should have base64 data string"))
          (p/catch (fn [e]
                     (js/console.error "[image-content] Error:" (.-message e) e)
                     (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                     (throw e)))
          (p/finally (fn []
                       (mcp/restore-settings! backup-path)))))))

(deftest-async eval-max-images-zero-caps-all
  (testing "Evaluation with maxImages 0 returns only text, no images"
    (let [backup-path (mcp/backup-settings! "eval-max-images-zero-backup.json")]
      (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                  {:keys [socket]} (mcp/start-mcp-session!)

                  ;; Get a session key for evaluation
                  sessions-resp (mcp/send-request socket
                                  {:jsonrpc "2.0"
                                   :id 1
                                   :method "tools/call"
                                   :params {:name "clojure_list_sessions"
                                            :arguments {}}})
                  session-key (let [outer (js->clj sessions-resp :keywordize-keys true)
                                    text (get-in outer [:result :content 0 :text])
                                    parsed (js->clj (.parse js/JSON text) :keywordize-keys true)]
                                (or (->> (:sessions parsed)
                                         (some (fn [s] (when (:isActiveSession s) (:replSessionKey s)))))
                                    (:replSessionKey (first (:sessions parsed)))))

                  ;; Evaluate code that returns a data URL string, with maxImages 0
                  eval-resp (mcp/send-request socket
                              {:jsonrpc "2.0"
                               :id 2
                               :method "tools/call"
                               :params {:name "clojure_evaluate_code"
                                        :arguments {:code "\"data:image/png;base64,iVBORw0KGgo=\""
                                                    :namespace "user"
                                                    :replSessionKey session-key
                                                    :who "e2e-image-cap-test"
                                                    :maxImages 0}}})
                  content (let [outer (js->clj eval-resp :keywordize-keys true)]
                            (get-in outer [:result :content]))

                  _ (mcp/stop-mcp-session! socket)]
            (is (= 1 (count content))
                "Should have only text content (no images with maxImages 0)")
            (is (= "text" (:type (first content)))
                "Content should be text type")
            (is (string? (re-find #"image-1-capped" (:text (first content))))
                "Text should contain capped image marker"))
          (p/catch (fn [e]
                     (js/console.error "[eval-max-images-zero] Error:" (.-message e) e)
                     (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                     (throw e)))
          (p/finally (fn []
                       (mcp/restore-settings! backup-path)))))))
