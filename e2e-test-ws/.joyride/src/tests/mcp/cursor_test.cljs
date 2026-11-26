(ns tests.mcp.cursor-test
  "The MCP server's registration with Cursor. The tests run where the editor has Cursor's
   registration API, and are skipped elsewhere. Run them with
   `bb run-e2e-tests-ws --local --vscode-executable <Cursor's executable>`."
  (:require
   ["vscode" :as vscode]
   [cljs.test :refer [is testing]]
   [e2e.macros :refer [deftest-async]]
   [e2e.utils :refer [wait-for+]]))

(defn- cursor-registration-api? []
  (fn? (some-> vscode/cursor .-mcp .-registerServer)))

(defn- skipped []
  (js/console.log "[cursor] This editor has no Cursor registration API: skipped"))

(defn- ^:async command!+
  "Runs the Backseat Driver command `command-name`, and returns what it resolves to as data."
  [command-name]
  (let [result (await (vscode/commands.executeCommand (str "calva-backseat-driver." command-name)))]
    (js->clj result :keywordize-keys true)))

(defn- start-command-registered!+ []
  (wait-for+ (^:async fn []
               (let [commands (await (vscode/commands.getCommands true))]
                 (.includes commands "calva-backseat-driver.startMcpServer")))
             :timeout 15000
             :message "[cursor] startMcpServer command not registered within 15s"))

(defn- ^:async set-auto-register!+
  "Sets the workspace's `autoRegisterCursorMcp` to `value`, where `js/undefined` removes
   the setting, and waits until the setting reads as `effective-value`."
  [value effective-value]
  (let [config (vscode/workspace.getConfiguration "calva-backseat-driver")
        _ (await (.update config "autoRegisterCursorMcp" value vscode/ConfigurationTarget.Workspace))]
    (await (wait-for+ #(= effective-value
                          (.get (vscode/workspace.getConfiguration "calva-backseat-driver") "autoRegisterCursorMcp"))
                      :timeout 15000
                      :message "[cursor] autoRegisterCursorMcp did not take the value within 15s"))))

(deftest-async a-start-registers-the-server-with-cursor
  (if-not (cursor-registration-api?)
    (skipped)
    (try
      (let [_ (await (start-command-registered!+))
            started (await (command!+ "startMcpServer"))
            _ (await (command!+ "stopMcpServer"))]
        (testing "the state the start command resolves to"
          (is (string? (:registered-name started))
              "has the server's name in Cursor")
          (is (nil? (:registration-error started))
              "and no registration error")))
      (catch :default e
        (await (command!+ "stopMcpServer"))
        (throw e)))))

(deftest-async a-stop-and-a-start-gives-the-server-a-new-name-in-cursor
  (if-not (cursor-registration-api?)
    (skipped)
    (try
      (let [_ (await (start-command-registered!+))
            first-start (await (command!+ "startMcpServer"))
            _ (await (command!+ "stopMcpServer"))
            second-start (await (command!+ "startMcpServer"))
            _ (await (command!+ "stopMcpServer"))]
        (is (string? (:registered-name first-start))
            "The first start registers")
        (is (string? (:registered-name second-start))
            "The second start registers")
        (is (not= (:registered-name first-start) (:registered-name second-start))
            "under a name that Cursor hasn't seen, so it reads the server's tools anew"))
      (catch :default e
        (await (command!+ "stopMcpServer"))
        (throw e)))))

(deftest-async the-register-command-registers-a-server-that-is-not-registered
  (if-not (cursor-registration-api?)
    (skipped)
    (try
      (let [_ (await (start-command-registered!+))
            _ (await (set-auto-register!+ false false))
            started (await (command!+ "startMcpServer"))
            registered (await (command!+ "registerMcpServerWithCursor"))
            _ (await (command!+ "stopMcpServer"))]
        (is (nil? (:registered-name started))
            "With auto-registration off, a start doesn't register")
        (is (some? (:assigned-port started))
            "and the server runs")
        (is (string? (:registered-name registered))
            "The register command resolves to the server's state with its name in Cursor")
        (is (= (:assigned-port started) (:assigned-port registered))
            "for the server that was running"))
      (catch :default e
        (await (command!+ "stopMcpServer"))
        (throw e))
      (finally
        (await (set-auto-register!+ js/undefined true))))))
