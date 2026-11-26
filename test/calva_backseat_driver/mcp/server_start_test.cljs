(ns calva-backseat-driver.mcp.server-start-test
  "vscode-mcp's server start. It is tested here, since it needs the `vscode` stub, which
   the library's own test build doesn't have."
  (:require
   ["net" :as net]
   ["vscode" :as vscode]
   [cljs.test :refer [deftest is]]
   [vscode-mcp.server :as server]))

(defn- connects?+
  "Returns a promise of whether something takes a connection on `port`."
  [port]
  (js/Promise. (fn [resolve _reject]
                 (let [socket (.connect net #js {:port port :host "127.0.0.1"})]
                   (.on socket "connect" (fn []
                                           (.destroy socket)
                                           (resolve true)))
                   (.on socket "error" (fn [_]
                                         (resolve false)))))))

(deftest ^:async a-start-whose-port-file-cannot-be-written
  (let [workspace-fs (.-fs vscode/workspace)
        write-file (.-writeFile workspace-fs)
        !port (atom nil)
        on-log (fn [_level & args]
                 (when (= "[Server] Socket server listening on port" (first args))
                   (reset! !port (second args))))]
    (set! (.-writeFile workspace-fs) (fn [& _]
                                       (js/Promise.reject (js/Error. "EACCES: permission denied"))))
    (try
      (let [outcome (try
                      (await (server/start-server!+ {:server/host "127.0.0.1"
                                                     :server/request-port 0
                                                     :server/port-file-uri #js {:fsPath "/nowhere/my-server.port"}
                                                     :mcp/on-request (fn [_] nil)
                                                     :mcp/on-log on-log}))
                      :started
                      (catch :default e
                        (.-message e)))]
        (is (= "EACCES: permission denied" outcome)
            "The start rejects with the error of the write")
        (is (number? @!port)
            "The server listened before the write")
        (is (false? (await (connects?+ @!port)))
            "and it is closed, so a failed start leaves nothing that listens"))
      (finally
        (set! (.-writeFile workspace-fs) write-file)))))
