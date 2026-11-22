(ns tests.mcp.stdio-wrapper-test
  (:require
   [cljs.test :refer [is testing]]
   [e2e.macros :refer [deftest-async]]
   [e2e.mcp-helpers :as mcp]
   [clojure.string :as string]
   [promesa.core :as p]
   ["child_process" :as child-process]
   ["vscode" :as vscode]))

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

(defn- spawn-wrapper-without-port!
  "Spawn the stdio wrapper with no port argument, so it finds the window it runs in.
   It runs under this window's extension host, like a client an extension starts."
  []
  (let [proc (child-process/spawn "node" #js [(mcp/wrapper-script-path)]
                                  #js {:stdio #js ["pipe" "pipe" "pipe"]})]
    (.setEncoding (.-stdout proc) "utf8")
    (.setEncoding (.-stderr proc) "utf8")
    (.on (.-stderr proc) "data" #(js/console.error "[stdio wrapper stderr]" %))
    {:proc proc :stdin (.-stdin proc) :stdout (.-stdout proc) :buffer (atom "")}))

(defn- get-session-key
  [socket]
  (p/let [{:keys [sessions]} (mcp/call-tool socket 60 "clojure_list_sessions" {})]
    (or (some #(when (:currentRoutedTarget %) (:replSessionKey %)) sessions)
        (:replSessionKey (first sessions)))))

(deftest-async stdio-wrapper-without-port-reaches-this-window
  (testing "Stdio wrapper with no port file connects to the window it runs in"
    (let [backup-path (mcp/backup-settings! "stdio-wrapper-caller-window-backup.json")
          token (str "caller-window-" (random-uuid))
          !wrapper (atom nil)]
      (set! (.-backseatDriverCallerWindowToken js/globalThis) token)
      (-> (p/let [_ (mcp/ensure-repl-and-eval-enabled!)
                  {:keys [socket]} (mcp/start-mcp-session!)
                  _ (mcp/wait-for-tool! socket "clojure_evaluate_code")
                  session-key (get-session-key socket)
                  wrapper (reset! !wrapper (spawn-wrapper-without-port!))
                  init (mcp/send-stdio-request!
                        wrapper
                        {:jsonrpc "2.0" :id 61 :method "initialize" :params {}}
                        :timeout 20000)
                  call (mcp/send-stdio-request!
                        wrapper
                        {:jsonrpc "2.0" :id 62 :method "tools/call"
                         :params {:name "clojure_evaluate_code"
                                  :arguments {:code "js/globalThis.backseatDriverCallerWindowToken"
                                              :namespace "user"
                                              :replSessionKey session-key
                                              :who "e2e-caller-window"}}}
                        :timeout 20000)
                  call-text (->> (get-in (js->clj call :keywordize-keys true) [:result :content])
                                 (map :text)
                                 (string/join " "))]
            (is (= 61 (.-id init)) "The wrapper answers initialize")
            (is (some? (.. init -result -serverInfo)) "initialize has a result from the server")
            (is (string/includes? call-text token)
                (str "The evaluation ran in this window, got: " call-text))
            (mcp/stop-mcp-session! socket))
          (p/catch (fn [e]
                     (js/console.error "[stdio-wrapper-test] Error:" (.-message e) e)
                     (vscode/commands.executeCommand "calva-backseat-driver.stopMcpServer")
                     (throw e)))
          (p/finally (fn []
                       (mcp/stop-stdio-wrapper! @!wrapper)
                       (mcp/restore-settings! backup-path)))))))
