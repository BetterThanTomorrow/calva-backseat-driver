(ns calva-backseat-driver.mcp.cursor-registration-test
  (:require [cljs.test :refer [deftest testing is]]
            [calva-backseat-driver.mcp.cursor-registration :as reg]))

(defn- mock-state [auto-register?]
  {:app/getConfiguration (fn [_]
                           #js {:get (fn [key]
                                       (when (= key "autoRegisterCursorMcp") auto-register?))})})

(deftest should-register-on-server-started?-test
  (let [server-info {:server/port-file-uri #js {:fsPath "/ws/port"}}
        state-on (assoc (mock-state true) :mcp/cursor-mcp-available? true)
        state-off (assoc (mock-state false) :mcp/cursor-mcp-available? true)]
    (testing "registers when setting on and API available"
      (is (reg/should-register-on-server-started? state-on server-info)))

    (testing "skips when setting off"
      (is (not (reg/should-register-on-server-started? state-off server-info))))

    (testing "skips without port file"
      (is (not (reg/should-register-on-server-started? state-on {}))))

    (testing "skips when Cursor API unavailable"
      (is (not (reg/should-register-on-server-started?
                (assoc state-on :mcp/cursor-mcp-available? false)
                server-info))))))

(deftest should-call-register-server?-test
  (let [server-info {:server/port-file-uri #js {:fsPath "/ws/port"}}
        state-on (assoc (mock-state true) :mcp/cursor-mcp-available? true)]
    (testing "calls register when eligible"
      (is (reg/should-call-register-server? state-on server-info)))

    (testing "skips when already registered"
      (is (not (reg/should-call-register-server?
                (assoc state-on :mcp/cursor-registered? true)
                server-info))))

    (testing "skips when registerServer already called this activation"
      (is (not (reg/should-call-register-server?
                (assoc state-on :mcp/cursor-register-server-called? true)
                server-info))))))

(deftest should-use-random-port-for-cursor?-test
  (testing "uses random port when Cursor auto-register is enabled"
    (is (reg/should-use-random-port-for-cursor?
         (assoc (mock-state true) :mcp/cursor-mcp-available? true))))

  (testing "skips when setting off"
    (is (not (reg/should-use-random-port-for-cursor?
              (assoc (mock-state false) :mcp/cursor-mcp-available? true)))))

  (testing "skips when Cursor API unavailable"
    (is (not (reg/should-use-random-port-for-cursor?
              (assoc (mock-state true) :mcp/cursor-mcp-available? false))))))

(deftest server-started-fxs-test
  (let [server-info {:server/assigned-port 1664
                     :server/port-file-uri #js {:fsPath "/ws/port"}}]
    (testing "silent Cursor start registers without copy-command effect"
      (let [fxs (reg/server-started-fxs server-info true "/home/config" true)]
        (is (not (some #(= :mcp/fx.show-server-started-message (first %)) fxs)))
        (is (some #(= :mcp/fx.register-cursor-mcp-server (first %)) fxs))))

    (testing "manual start shows copy-command when not silent"
      (let [fxs (reg/server-started-fxs server-info false "/home/config" false)]
        (is (some #(= :mcp/fx.show-server-started-message (first %)) fxs))
        (is (not (some #(= :mcp/fx.register-cursor-mcp-server (first %)) fxs)))))))
