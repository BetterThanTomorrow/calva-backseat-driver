(ns tests.mcp.stdio-wrapper-test
  (:require
   [cljs.test :refer [is testing]]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]
   [promesa.core :as p]))

(def ^:private marker "E2E-STDIO-WRAPPER-CHUNK-MARKER")

(defn- large-chunked-response [payload-size]
  {:jsonrpc "2.0"
   :id 42
   :result {:payload (apply str (repeat payload-size marker))}})

(deftest-async stdio-wrapper-reassembles-chunked-tcp-response
  (testing "Stdio wrapper forwards a large JSON-RPC response split across TCP chunks"
    ;; Uses a fake TCP server that splits its reply at a fixed byte boundary,
    ;; simulating what happens when a real MCP response (e.g. output log) exceeds
    ;; one TCP read. The wrapper's socket→stdout path lacks newline buffering;
    ;; when this test fails, stderr shows "Filtered potential non-JSON output".
    (-> (p/let [payload-size 2000
                fake-server (mcp/start-chunked-response-server!
                             (large-chunked-response payload-size)
                             :chunk-size 512)
                _ (js/console.log "[stdio-wrapper-test] Fake server on port"
                                  (:port fake-server)
                                  "response bytes:" (:response-length fake-server))
                wrapper (mcp/spawn-stdio-wrapper! (:port fake-server))
                resp (mcp/send-stdio-request!
                      wrapper
                      {:jsonrpc "2.0" :id 42 :method "initialize"}
                      :timeout 10000)
                outer (js->clj resp :keywordize-keys true)
                payload (get-in outer [:result :payload])]
          (is (= 42 (:id outer)) "Should receive the matching JSON-RPC response id")
          (is (string? payload) "Response payload should be a string")
          (is (>= (count payload) payload-size)
              "Response payload should survive the chunked TCP relay intact")
          (is (re-find (re-pattern marker) payload)
              "Response payload should contain the expected marker text")
          (mcp/stop-stdio-wrapper! wrapper)
          (mcp/stop-chunked-response-server! fake-server))
        (p/catch (fn [e]
                   (js/console.error "[stdio-wrapper-test] Error:" (.-message e) e)
                   (throw e))))))
