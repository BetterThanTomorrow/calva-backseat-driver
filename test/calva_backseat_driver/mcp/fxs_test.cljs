(ns calva-backseat-driver.mcp.fxs-test
  (:require [cljs.test :refer [deftest testing is]]
            ["path" :as path]
            [vscode-mcp.manual-setup :as manual-setup]))

;; Targets the shared, vscode-free vscode-mcp.manual-setup namespace directly,
;; using the same wrapper-path construction fxs.cljs uses, rather than
;; requiring calva-backseat-driver.mcp.fxs (which requires "vscode").

(deftest copy-command-strings-test
  (let [server-info {:server/assigned-port 1664
                     :server/host "127.0.0.1"
                     :server/port-file-uri #js {:fsPath "/ws/.calva/mcp-server/port"}}
        wrapper-path (path/join "/home/config" "calva-mcp-server.js")
        commands (manual-setup/copy-command-strings wrapper-path server-info)]
    (testing "port variant uses stdio-config format with host"
      (is (= "node /home/config/calva-mcp-server.js 1664 127.0.0.1"
             (:manual-setup/port commands))))

    (testing "port-file variant uses stdio-config format with host"
      (is (= "node /home/config/calva-mcp-server.js /ws/.calva/mcp-server/port 127.0.0.1"
             (:manual-setup/port-file commands))))))

(deftest copy-command-strings-custom-host-test
  (let [server-info {:server/assigned-port 1664
                     :server/host "0.0.0.0"
                     :server/port-file-uri #js {:fsPath "/ws/port"}}
        wrapper-path (path/join "/home/config" "calva-mcp-server.js")
        commands (manual-setup/copy-command-strings wrapper-path server-info)]
    (testing "custom host preserved in both variants"
      (is (= "node /home/config/calva-mcp-server.js 1664 0.0.0.0"
             (:manual-setup/port commands)))
      (is (= "node /home/config/calva-mcp-server.js /ws/port 0.0.0.0"
             (:manual-setup/port-file commands))))))
