(ns calva-backseat-driver.mcp.axs-test
  (:require [cljs.test :refer [deftest testing is]]
            [calva-backseat-driver.event-handler :as event-handler]
            [calva-backseat-driver.mcp.axs :as mcp-axs]))

(defn- handle
  "Returns what the MCP handlers give for `action` on `state`."
  [state action]
  (event-handler/handle-actions mcp-axs/actions state {:system/now 0} [action]))

(def ^:private state
  {:mcp/wrapper-config-path "/tmp/w"
   :mcp/lifecycle-state :the-lifecycle-state
   :vscode/config.mcpHost "127.0.0.1"
   :vscode/config.mcpSocketServerPort 1664
   :vscode/config.provideBdSkill true
   :app/min-log-level :debug
   :app/log-file-uri :the-log-file})

(def ^:private lifecycle-options
  {:mcp/wrapper-config-path "/tmp/w"
   :mcp/lifecycle-state :the-lifecycle-state
   :mcp/settings {:vscode/config.mcpHost "127.0.0.1"
                  :vscode/config.mcpSocketServerPort 1664}})

(def ^:private log-options
  {:app/min-log-level :debug
   :app/log-file-uri :the-log-file})

(deftest sync-cursor-mcp-when-context-keys-test
  (testing "when-context keys match package.json enablement strings"
    (is (= ":calva-backseat-driver/mcp-server-registered-with-cursor?"
           (str :calva-backseat-driver/mcp-server-registered-with-cursor?)))
    (is (= ":calva-backseat-driver/cursor-mcp-available?"
           (str :calva-backseat-driver/cursor-mcp-available?)))))

(deftest start-server
  (testing "started by a person"
    (let [result (handle state [:mcp/ax.start-server])]
      (is (= [[:uf/await :mcp/fx.lifecycle-start (assoc lifecycle-options :lifecycle/silent? nil)]]
             (:uf/fxs result))
          "The start gets the lifecycle state, the wrapper's directory and the MCP settings")
      (is (= [[:mcp/ax.lifecycle-updated :uf/prev-result]]
             (:uf/dxs result))
          "and the state takes the lifecycle state the start resolves to")))
  (testing "started by the extension"
    (is (= [[:uf/await :mcp/fx.lifecycle-start (assoc lifecycle-options :lifecycle/silent? true)]]
           (:uf/fxs (handle state [:mcp/ax.start-server {:silent? true}])))
        "The start is silent")))

(deftest stop-server
  (let [result (handle state [:mcp/ax.stop-server {:lifecycle/silent? true}])]
    (is (= [[:uf/await :mcp/fx.lifecycle-stop (assoc lifecycle-options :lifecycle/silent? true)]]
           (:uf/fxs result)))
    (is (= [[:mcp/ax.lifecycle-stopped :uf/prev-result]]
           (:uf/dxs result)))))

(deftest lifecycle-stopped
  (let [result (handle state [:mcp/ax.lifecycle-stopped :the-stopped-state])]
    (is (= :the-stopped-state (:mcp/lifecycle-state (:uf/db result))))
    (is (= [[:uf/await :app/fx.return true]]
           (:uf/fxs result))
        "The stop command resolves to true")
    (is (= [[:mcp/ax.sync-cursor-mcp-when-contexts]]
           (:uf/dxs result)))))

(deftest update-registry
  (is (= [[:mcp/fx.update-registry lifecycle-options]]
         (:uf/fxs (handle state [:mcp/ax.update-registry])))))

(deftest cursor-registration-is-logged
  (testing "a registration writes a log line"
    (let [result (handle state [:mcp/ax.cursor-mcp-registered {:ok true}])]
      (is (= [[:app/fx.log log-options :info ["Cursor MCP server registered:" {:ok true}]]]
             (:uf/fxs result)))
      (is (= [[:mcp/ax.sync-cursor-mcp-when-contexts]]
             (:uf/dxs result)))))
  (testing "a failed registration writes a log line"
    (let [result (handle state [:mcp/ax.cursor-mcp-registration-failed {:ok false}])]
      (is (= [[:app/fx.log log-options :warn ["Cursor MCP auto-registration failed:" {:ok false}]]]
             (:uf/fxs result)))
      (is (= [[:mcp/ax.sync-cursor-mcp-when-contexts]]
             (:uf/dxs result))))))

(deftest cursor-registration-finished
  (testing "a registration"
    (let [result (handle state [:mcp/ax.cursor-registration-finished {:ok true :state :the-new-state}])]
      (is (= [[:mcp/ax.lifecycle-updated :the-new-state]]
             (:uf/dxs result))
          "The state takes the new lifecycle state")
      (is (empty? (:uf/fxs result)))))
  (testing "outside Cursor"
    (let [failure {:ok false :reason :cursor-api-unavailable}
          result (handle state [:mcp/ax.cursor-registration-finished failure])]
      (is (= [[:vscode/fx.show-information-message "Cursor MCP registration API is not available in this editor."]]
             (:uf/fxs result))
          "The person is told that the editor has no registration API")
      (is (= [[:mcp/ax.cursor-mcp-registration-failed failure]]
             (:uf/dxs result)))))
  (testing "a registration that didn't take"
    (let [failure {:ok false :reason :registration-failed}
          result (handle state [:mcp/ax.cursor-registration-finished failure])]
      (is (= [[:vscode/fx.show-information-message "Could not register Backseat Driver MCP server with Cursor."]]
             (:uf/fxs result))
          "The person is told that it could not register")
      (is (= [[:mcp/ax.cursor-mcp-registration-failed failure]]
             (:uf/dxs result)))))
  (testing "a registration that threw"
    (let [result (handle state [:mcp/ax.cursor-registration-finished {:ok false :error "boom"}])]
      (is (= [[:app/ax.log :error "[Cursor MCP] register-with-cursor error:" "boom"]
              [:mcp/ax.cursor-mcp-registration-failed "boom"]]
             (:uf/dxs result))
          "The error is logged")
      (is (empty? (:uf/fxs result))))))

(deftest server-error
  (is (= [[:node/fx.log-error "MCP server error:" "Error: listen EADDRINUSE"]
          [:vscode/fx.show-error-message "MCP server error: Error: listen EADDRINUSE"]]
         (:uf/fxs (handle state [:mcp/ax.server-error "Error: listen EADDRINUSE"])))
      "The person is shown the error, and it goes to the console"))

(deftest open-server-log
  (is (= [[:uf/await :vscode/fx.show-text-document-at :the-log-file]]
         (:uf/fxs (handle state [:mcp/ax.open-server-log])))
      "The command resolves when the log file is shown"))

(deftest handle-request
  (let [request {:jsonrpc "2.0" :id 1 :method "tools/list"}]
    (is (= [[:uf/await :mcp/fx.handle-request
             {:mcp/repl-enabled? false
              :mcp/when-clause-settings {"config.calva-backseat-driver.provideBdSkill" true
                                         "config.calva-backseat-driver.provideEditSkill" nil
                                         "config.calva-backseat-driver.enableMcpReplEvaluation" false
                                         ":calva-mcp-extension/activated?" true}}
             request]]
           (:uf/fxs (handle (assoc state :vscode/config.enableMcpReplEvaluation false)
                            [:mcp/ax.handle-request request])))
        "The request is answered with the settings that gate tools and skills")))
