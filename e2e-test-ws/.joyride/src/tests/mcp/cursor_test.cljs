(ns tests.mcp.cursor-test
  "The MCP server's registration with Cursor. The tests run where the editor has Cursor's
   registration API, and are skipped elsewhere. Run them with
   `bb run-e2e-tests-ws --local --vscode-executable <Cursor's executable>`."
  (:require
   ["vscode" :as vscode]
   [cljs.test :refer [is testing]]
   [e2e.macros :refer [deftest-async]]
   [e2e.utils :refer [wait-for+]]
   [promesa.core :as p]))

(defn- cursor-registration-api? []
  (fn? (some-> vscode/cursor .-mcp .-registerServer)))

(defn- skipped []
  (js/console.log "[cursor] This editor has no Cursor registration API: skipped")
  (p/resolved nil))

(defn- command!+
  "Runs the Backseat Driver command `command-name`, and returns what it resolves to as data."
  [command-name]
  (p/let [result (vscode/commands.executeCommand (str "calva-backseat-driver." command-name))]
    (js->clj result :keywordize-keys true)))

(defn- start-command-registered!+ []
  (wait-for+ #(p/let [commands (vscode/commands.getCommands true)]
                (.includes commands "calva-backseat-driver.startMcpServer"))
             :timeout 15000
             :message "[cursor] startMcpServer command not registered within 15s"))

(defn- stopping-on-failure
  "Returns `promise` with the server stopped when it rejects, so a failed test leaves no server."
  [promise]
  (p/catch promise
           (fn [e]
             (p/do (command!+ "stopMcpServer")
                   (throw e)))))

(defn- set-auto-register!+
  "Sets the workspace's `autoRegisterCursorMcp` to `value`, where `js/undefined` removes
   the setting, and waits until the setting reads as `effective-value`."
  [value effective-value]
  (let [config (vscode/workspace.getConfiguration "calva-backseat-driver")]
    (p/let [_ (.update config "autoRegisterCursorMcp" value vscode/ConfigurationTarget.Workspace)]
      (wait-for+ #(= effective-value
                     (.get (vscode/workspace.getConfiguration "calva-backseat-driver") "autoRegisterCursorMcp"))
                 :timeout 15000
                 :message "[cursor] autoRegisterCursorMcp did not take the value within 15s"))))

(deftest-async a-start-registers-the-server-with-cursor
  (if-not (cursor-registration-api?)
    (skipped)
    (stopping-on-failure
     (p/let [_ (start-command-registered!+)
             started (command!+ "startMcpServer")
             _ (command!+ "stopMcpServer")]
       (testing "the state the start command resolves to"
         (is (string? (:registered-name started))
             "has the server's name in Cursor")
         (is (nil? (:registration-error started))
             "and no registration error"))))))

(deftest-async a-stop-and-a-start-gives-the-server-a-new-name-in-cursor
  (if-not (cursor-registration-api?)
    (skipped)
    (stopping-on-failure
     (p/let [_ (start-command-registered!+)
             first-start (command!+ "startMcpServer")
             _ (command!+ "stopMcpServer")
             second-start (command!+ "startMcpServer")
             _ (command!+ "stopMcpServer")]
       (is (string? (:registered-name first-start))
           "The first start registers")
       (is (string? (:registered-name second-start))
           "The second start registers")
       (is (not= (:registered-name first-start) (:registered-name second-start))
           "under a name that Cursor hasn't seen, so it reads the server's tools anew")))))

(deftest-async the-register-command-registers-a-server-that-is-not-registered
  (if-not (cursor-registration-api?)
    (skipped)
    (-> (stopping-on-failure
         (p/let [_ (start-command-registered!+)
                 _ (set-auto-register!+ false false)
                 started (command!+ "startMcpServer")
                 registered (command!+ "registerMcpServerWithCursor")
                 _ (command!+ "stopMcpServer")]
           (is (nil? (:registered-name started))
               "With auto-registration off, a start doesn't register")
           (is (some? (:assigned-port started))
               "and the server runs")
           (is (string? (:registered-name registered))
               "The register command resolves to the server's state with its name in Cursor")
           (is (= (:assigned-port started) (:assigned-port registered))
               "for the server that was running")))
        (p/finally (fn []
                     (set-auto-register!+ js/undefined true))))))
