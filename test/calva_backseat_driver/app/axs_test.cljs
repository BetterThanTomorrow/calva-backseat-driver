(ns calva-backseat-driver.app.axs-test
  (:require ["fs" :as fs]
            ["path" :as path]
            [cljs.test :refer [deftest testing is]]
            [calva-backseat-driver.app.axs :as app-axs]
            [calva-backseat-driver.event-handler :as event-handler]))

(defn- handle
  "Returns what the app handlers give for `action` on `state`."
  [state action]
  (event-handler/handle-actions app-axs/actions state {:system/now 0} [action]))

(defn- auto-start
  "The effect that starts the MCP server by itself, when `state` calls for it."
  [state]
  (some #{[:uf/fx.dispatch [[:mcp/ax.start-server {:silent? true}]]]}
        (:uf/fxs (handle state [:app/ax.start-background-work]))))

(deftest activate
  (let [result (handle {:calva/output-line-counter 0}
                       [:app/ax.activate {:vscode/config.autoStartMCPServer true
                                          :mcp/cursor-mcp-available? false}])]
    (is (= {:calva/output-line-counter 0
            :vscode/config.autoStartMCPServer true
            :mcp/cursor-mcp-available? false}
           (:uf/db result))
        "The state takes what activation found, the settings among it")
    (is (= [[:app/ax.init]]
           (:uf/dxs result))
        "and then the extension initializes")))

(deftest init
  (let [{:uf/keys [db fxs dxs]} (handle {} [:app/ax.init])
        manifest (js->clj (js/JSON.parse (fs/readFileSync (path/resolve "package.json") "utf8"))
                          :keywordize-keys true)]
    (is (= (set (map :command (get-in manifest [:contributes :commands])))
           (set (keep (fn [[effect-name command-id]]
                        (when (= :app/fx.register-command effect-name)
                          command-id))
                      fxs)))
        "Registers every command in package.json, and no others")
    (is (some #{[:app/fx.register-language-model-tools]} fxs)
        "Registers the tools with the Language Model API")
    (is (some #{[:vscode/fx.subscribe-to-settings]} fxs)
        "Subscribes to changes of the settings")
    (is (= {:app/activated? true} db)
        "The extension is activated, which its when-context follows")
    (is (= [[:calva/ax.init-history]
            [:app/ax.start-background-work]]
           dxs)
        "Loads the history and starts the background work")))

(deftest start-background-work
  (testing "Calva"
    (is (= [[:uf/fx.dispatch [[:calva/ax.when-activated [[:calva/ax.subscribe-to-output]
                                                         [:calva/ax.subscribe-to-sessions-changed]]]]]]
           (:uf/fxs (handle {} [:app/ax.start-background-work])))
        "Subscribes to Calva's output and session changes once Calva is active"))
  (testing "the MCP server starting by itself"
    (is (auto-start {:vscode/config.autoStartMCPServer true})
        "starts when the auto start setting is on")
    (is (auto-start {:vscode/config.autoRegisterCursorMcp true
                     :mcp/cursor-mcp-available? true})
        "starts when Cursor registration is on, in Cursor")
    (is (auto-start {:vscode/config.autoRegisterEcaMcp true
                     :mcp/eca-available? true
                     :mcp/workspace-root-present? true})
        "starts when ECA registration is on, with ECA installed and a folder open")
    (is (not (auto-start {:vscode/config.autoRegisterEcaMcp false
                          :mcp/eca-available? true
                          :mcp/workspace-root-present? true}))
        "doesn't start with ECA installed and its setting off")
    (is (not (auto-start {:vscode/config.autoRegisterCursorMcp true
                          :mcp/cursor-mcp-available? false}))
        "doesn't start for Cursor registration outside Cursor")))

(deftest settings-changed
  (is (= {:vscode/config.mcpHost "0.0.0.0"
          :vscode/config.provideBdSkill false
          :calva/output-line-counter 7}
         (:uf/db (handle {:vscode/config.mcpHost "127.0.0.1"
                          :calva/output-line-counter 7}
                         [:app/ax.settings-changed {:vscode/config.mcpHost "0.0.0.0"
                                                    :vscode/config.provideBdSkill false}])))
      "The state takes the new settings and keeps the rest"))

(deftest hot-reloaded
  (testing "a reload after the extension initialized"
    (let [state {:app/activated? true}
          result (handle state [:app/ax.hot-reloaded {:mcp/eca-available? true}])]
      (is (= (assoc state :mcp/eca-available? true)
             (:uf/db result))
          "takes what the reload found")
      (is (empty? (:uf/dxs result))
          "and doesn't initialize again")))
  (testing "a reload before the extension initialized"
    (is (= [[:app/ax.init]]
           (:uf/dxs (handle {} [:app/ax.hot-reloaded {:mcp/eca-available? true}])))
        "initializes")))

(deftest log
  (is (= [[:app/fx.log
           {:app/min-log-level :info
            :app/log-file-uri :the-log-file}
           :warn
           ["a message" {:with "data"}]]]
         (:uf/fxs (handle {:app/min-log-level :info
                           :app/log-file-uri :the-log-file
                           :calva/output-line-counter 7}
                          [:app/ax.log :warn "a message" {:with "data"}])))
      "The log effect gets the level, the messages and where the log is"))

(deftest cleanup
  (let [result (handle {:app/activated? true} [:app/ax.cleanup])]
    (is (= {:app/activated? false} (:uf/db result)))
    (is (= [[:app/fx.clear-disposables]] (:uf/fxs result)))))

(deftest deactivate
  (let [history (sorted-map 7 {:output/line 7 :output/category "evaluatedCode" :output/text "(+ 1 2)"})
        result (handle {:app/activated? true
                        :calva/history-storage-uri "file:///storage/eval-history.transit.json"
                        :calva/history history}
                       [:app/ax.deactivate])]
    (is (= [[:uf/fx.cancel-deferred-dispatch :calva/history-save]
            [:calva/fx.save-history "file:///storage/eval-history.transit.json" history]
            [:app/fx.clear-disposables]]
           (:uf/fxs result))
        "Saves the history at once, in place of the save that waited, and disposes what the extension registered")
    (is (false? (:app/activated? (:uf/db result)))
        "The extension is no longer activated")
    (is (= [[:mcp/ax.stop-server {:lifecycle/silent? true}]]
           (:uf/dxs result))
        "Then stops the MCP server without a message")))
