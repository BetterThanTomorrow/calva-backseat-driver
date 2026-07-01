(ns calva-backseat-driver.mcp.fxs-test
  (:require [cljs.test :refer [deftest testing is]]
            [calva-backseat-driver.mcp.fxs :as fxs]))

(deftest copy-command-strings-test
  (let [server-info {:server/assigned-port 1664
                     :server/host "127.0.0.1"
                     :server/port-file-uri #js {:fsPath "/ws/.calva/mcp-server/port"}}
        wrapper-config-path "/home/config"
        commands (fxs/copy-command-strings server-info wrapper-config-path)]
    (testing "port variant uses stdio-config format with host"
      (is (= "node /home/config/calva-mcp-server.js 1664 127.0.0.1"
             (:port commands))))

    (testing "port-file variant uses stdio-config format with host"
      (is (= "node /home/config/calva-mcp-server.js /ws/.calva/mcp-server/port 127.0.0.1"
             (:port-file commands))))))

(deftest copy-command-strings-custom-host-test
  (let [server-info {:server/assigned-port 1664
                     :server/host "0.0.0.0"
                     :server/port-file-uri #js {:fsPath "/ws/port"}}
        commands (fxs/copy-command-strings server-info "/home/config")]
    (testing "custom host preserved in both variants"
      (is (= "node /home/config/calva-mcp-server.js 1664 0.0.0.0"
             (:port commands)))
      (is (= "node /home/config/calva-mcp-server.js /ws/port 0.0.0.0"
             (:port-file commands))))))
