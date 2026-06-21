(ns calva-backseat-driver.integrations.vscode.cursor-config-test
  (:require [cljs.test :refer [deftest testing is]]
            [calva-backseat-driver.integrations.vscode.cursor-config :as config]))

(deftest build-stdio-server-config-test
  (testing "valid paths produce expected stdio config"
    (let [{:keys [ok config]} (config/build-stdio-server-config
                               "/ext/dist/calva-mcp-server.js"
                               "/proj/.calva/mcp-server/port")]
      (is ok)
      (is (= "backseat-driver" (:name config)))
      (is (= "node" (get-in config [:server :command])))
      (is (= ["/ext/dist/calva-mcp-server.js" "/proj/.calva/mcp-server/port"]
             (get-in config [:server :args])))
      (is (= {} (get-in config [:server :env])))))

  (testing "missing wrapper path"
    (is (= {:ok false :reason :missing-wrapper-path}
           (config/build-stdio-server-config nil "/proj/port"))))

  (testing "missing port file path"
    (is (= {:ok false :reason :missing-port-file-path}
           (config/build-stdio-server-config "/ext/wrapper.js" nil)))))

(deftest wrapper-script-path-test
  (testing "joins extensionPath with dist wrapper"
    (is (= "/Users/dev/backseat-driver/dist/calva-mcp-server.js"
           (config/wrapper-script-path #js {:extensionPath "/Users/dev/backseat-driver"}))))

  (testing "nil context yields nil"
    (is (nil? (config/wrapper-script-path nil)))))

(deftest port-file-fs-path-test
  (testing "reads fsPath from port-file-uri"
    (is (= "/proj/.calva/mcp-server/port"
           (config/port-file-fs-path {:server/port-file-uri #js {:fsPath "/proj/.calva/mcp-server/port"}}))))

  (testing "missing uri yields nil"
    (is (nil? (config/port-file-fs-path {})))))

(deftest build-cursor-mcp-registration-config-test
  (testing "combines extension wrapper path and server port file"
    (let [ctx #js {:extensionPath "/ext/root"}
          server-info {:server/port-file-uri #js {:fsPath "/ws/.calva/mcp-server/port"}}
          {:keys [ok config]} (config/build-cursor-mcp-registration-config ctx server-info)]
      (is ok)
      (is (= ["/ext/root/dist/calva-mcp-server.js" "/ws/.calva/mcp-server/port"]
             (get-in config [:server :args]))))))

(deftest should-auto-start-mcp-server?-test
  (testing "autoStartMCPServer alone"
    (is (config/should-auto-start-mcp-server? true false false)))

  (testing "Cursor auto-register when API available"
    (is (config/should-auto-start-mcp-server? false true true)))

  (testing "Cursor setting without API does not auto-start"
    (is (not (config/should-auto-start-mcp-server? false true false))))

  (testing "both off"
    (is (not (config/should-auto-start-mcp-server? false false false)))))

(deftest mcp-client-identifier-test
  (testing "builds Cursor MCP service identifier from extension id and server name"
    (is (= "user-betterthantomorrow.calva-backseat-driver-extension-backseat-driver"
           (config/mcp-client-identifier
            #js {:extension #js {:id "betterthantomorrow.calva-backseat-driver"}}))))

  (testing "nil context yields nil"
    (is (nil? (config/mcp-client-identifier nil))))

  (testing "missing extension id yields nil"
    (is (nil? (config/mcp-client-identifier #js {:extension #js {}}))))

(deftest cursor-mcp-settings-display-name-test
  (testing "Cursor Settings label prefixes extension- to registerServer name"
    (is (= "extension-backseat-driver" (config/cursor-mcp-settings-display-name))))))

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
