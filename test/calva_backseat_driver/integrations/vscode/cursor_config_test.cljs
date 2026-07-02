(ns calva-backseat-driver.integrations.vscode.cursor-config-test
  (:require [cljs.test :refer [deftest testing is]]
            [calva-backseat-driver.integrations.vscode.cursor-config :as config]))

(deftest port-file-fs-path-test
  (testing "reads fsPath from port-file-uri"
    (is (= "/proj/.calva/mcp-server/port"
           (config/port-file-fs-path {:server/port-file-uri #js {:fsPath "/proj/.calva/mcp-server/port"}}))))

  (testing "missing uri yields nil"
    (is (nil? (config/port-file-fs-path {})))))

(deftest should-auto-start-mcp-server?-test
  (testing "autoStartMCPServer alone"
    (is (config/should-auto-start-mcp-server? true false false)))

  (testing "Cursor auto-register when API available"
    (is (config/should-auto-start-mcp-server? false true true)))

  (testing "Cursor setting without API does not auto-start"
    (is (not (config/should-auto-start-mcp-server? false true false))))

  (testing "both off"
    (is (not (config/should-auto-start-mcp-server? false false false)))))

(deftest cursor-mcp-server-name-test
  (testing "registerServer name is the base name suffixed with the instance slug"
    (is (= "backseat-driver-my-project-a3f2"
           (config/cursor-mcp-server-name "my-project-a3f2")))))

(deftest cursor-mcp-settings-display-name-test
  (testing "Cursor Settings label prefixes extension- to the registerServer name"
    (is (= "extension-backseat-driver-my-project-a3f2"
           (config/cursor-mcp-settings-display-name "backseat-driver-my-project-a3f2")))))

(deftest should-register-cursor-mcp?-test
  (let [server-info {:server/port-file-uri #js {:fsPath "/ws/port"}}]
    (testing "all conditions met"
      (is (config/should-register-cursor-mcp? true true server-info)))

    (testing "setting off"
      (is (not (config/should-register-cursor-mcp? false true server-info))))

    (testing "API unavailable"
      (is (not (config/should-register-cursor-mcp? true false server-info))))

    (testing "missing port file"
      (is (not (config/should-register-cursor-mcp? true true {}))))))
