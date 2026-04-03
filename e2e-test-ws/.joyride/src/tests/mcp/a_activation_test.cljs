(ns tests.mcp.a-activation-test
  (:require
   ["vscode" :as vscode]
   [cljs.test :refer [deftest is testing]]
   [e2e.macros :refer [deftest-async]]
   [promesa.core :as p]))

(deftest-async command-registration
  (testing "MCP server commands are registered"
    (try
      (p/let [extension (vscode/extensions.getExtension "betterthantomorrow.calva-backseat-driver")
              already-active? (.-isActive extension)
              pre-activation (vscode/commands.getCommands true)]
        (when-not already-active?
          (is (= false
                 (.includes pre-activation "calva-backseat-driver.startMcpServer"))
              "there is no start server command before activation")
          (is (= false
                 (.includes pre-activation "calva-backseat-driver.stopMcpServer"))
              "there is no stop server command before activation"))
        (p/let [_ (.activate extension)
                post-activation (vscode/commands.getCommands true)]
          (is (= true
                 (.includes post-activation "calva-backseat-driver.startMcpServer"))
              "there is a start server command after activation")
          (is (= true
                 (.includes post-activation "calva-backseat-driver.stopMcpServer"))
              "there is a stop server command after activation")))
      (catch :default e
        (js/console.error (.-message e) e)))))
