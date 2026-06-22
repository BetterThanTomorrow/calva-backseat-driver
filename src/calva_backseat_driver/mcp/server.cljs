(ns calva-backseat-driver.mcp.server
  (:require
   ["fs" :as fs]
   ["net" :as net]
   ["path" :as path]
   ["vscode" :as vscode]
   [clojure.string :as string]
   [promesa.core :as p]))

(defn- get-workspace-root-uri-or-nil []
  (some-> vscode/workspace.workspaceFolders
          first
          .-uri))

(defn- get-server-dir+ [ctx-or-base-uri]
  (let [base (cond
               (instance? vscode/Uri ctx-or-base-uri) ctx-or-base-uri
               (get-workspace-root-uri-or-nil) (get-workspace-root-uri-or-nil)
               :else (.-globalStorageUri ^js ctx-or-base-uri))]
    (vscode/Uri.joinPath base ".calva" "mcp-server")))

(defn- get-port-file-uri+ [ctx-or-base-uri]
  (vscode/Uri.joinPath (get-server-dir+ ctx-or-base-uri) "port"))

(defn- delete-port-file!+ [{:ex/keys [dispatch!]} ^js port-file-uri]
  (p/create
   (fn [resolve-fn _reject]
     (if-not port-file-uri
       (resolve-fn true)
       (-> (vscode/workspace.fs.delete port-file-uri #js {:recursive false, :useTrash false})
           (p/then (fn [_]
                     (dispatch! [[:app/ax.log :info "[Server] Deleted port file:" (.-fsPath port-file-uri)]])
                     (resolve-fn true)))
           (p/catch (fn [err]
                      (dispatch! [[:app/ax.log :error "[Server] Could not delete port file with VS Code API:"
                                   err (.-message err)]])
                      ;; Probably VS Code API unavailable during shutdown - try Node fs fallback
                      (try
                        (let [fs-path (.-fsPath port-file-uri)]
                          (if (.existsSync fs fs-path)
                            (do
                              (.unlinkSync fs fs-path)
                              (dispatch! [[:app/ax.log :info "[Server] Deleted port file with fs fallback:" fs-path]])
                              (resolve-fn true))
                            (do
                              (dispatch! [[:app/ax.log :debug "[Server] Port file already gone (fs fallback check)"]])
                              (resolve-fn true))))
                        (catch js/Error fs-err
                          (dispatch! [[:app/ax.log :warn "[Server] Could not delete port file with fallback either:" fs-err (.-message fs-err)]])
                          (resolve-fn true))))))))))

(defn- split-buffer-on-newline [buffer]
  (let [lines (string/split buffer #"\n")]
    (cond
      (empty? lines)
      [[] ""]

      ;; Buffer ends with newline - all segments are complete
      (string/ends-with? buffer "\n")
      [(filter (comp not string/blank?) lines) ""]

      :else
      ;; Last line is incomplete
      [(filter (comp not string/blank?) (butlast lines)) (last lines)])))

(defn- parse-request-json [json-str]
  (try
    (let [request-js (js/JSON.parse json-str)]
      (js->clj request-js :keywordize-keys true))
    (catch js/Error e
      {:error :parse-error :message (.-message e) :json json-str})))

(defn- format-response-json [response]
  (str (js/JSON.stringify (clj->js response)) "\n"))

(defn- create-error-response [id code message]
  {:jsonrpc "2.0" :id id :error {:code code :message message}})

(defn- process-segment [{:ex/keys [dispatch!]} segment]
  (let [request-json (string/trim segment)]
    (if (string/blank? request-json)
      (do
        (dispatch! [[:app/ax.log :debug "[Server] Blank line segment received, ignoring."]])
        nil)
      (let [parsed (parse-request-json request-json)]
        (if (:error parsed)
          (do
            (dispatch! [[:app/ax.log :error "[Server] Error parsing request JSON segment:" (:message parsed) {:json (:json parsed)}]])
            (create-error-response nil -32700 "Parse error"))
          (dispatch! [[:app/ax.log :debug "[Server] Processing request for method:" (:method parsed)]
                      [:mcp/ax.handle-request parsed]]))))))

(defn- process-segments [options segments]
  (keep (partial process-segment options) segments))

(defn- handle-socket-data! [{:ex/keys [dispatch!] :as options}
                            buffer-atom data-chunk]
  (let [_ (dispatch! [[:app/ax.log :debug "[Server] Socket received chunk:" data-chunk]])
        _ (vswap! buffer-atom str data-chunk)
        [segments remainder] (split-buffer-on-newline @buffer-atom)
        _ (dispatch! [[:app/ax.log :debug "[Server] Split segments:" (pr-str segments) "Remainder:" (pr-str remainder)]])
        _ (vreset! buffer-atom remainder)
        responses (process-segments options segments)
        _ (dispatch! [[:app/ax.log :debug "[Server] Generated responses:" (pr-str responses)]])]
    responses))

(def ^:private active-sockets (atom #{}))
(def ^:private socket-id-counter (atom 0))

(defn- next-socket-id! []
  (swap! socket-id-counter inc))

(defn- socket-peer-label [^js socket]
  (try
    (let [remote-address (.-remoteAddress socket)
          remote-port (.-remotePort socket)]
      (when (and remote-address remote-port)
        (str remote-address ":" remote-port)))
    (catch :default _ nil)))

(defn- setup-socket-handlers! [{:ex/keys [dispatch!] :as options} ^js socket]
  (let [socket-id (next-socket-id!)
        peer-label (or (socket-peer-label socket) "unknown")]
    (.setEncoding socket "utf8")
    (swap! active-sockets conj socket)
    (dispatch! [[:app/ax.log :info "[Server] Socket connected:" socket-id peer-label]])
    (.on socket "close"
         (fn []
           (dispatch! [[:app/ax.log :info "[Server] Socket closed:" socket-id peer-label]])
           (swap! active-sockets disj socket)))

    (let [buffer (volatile! "")]
      (.on socket "data"
           (fn [data-chunk]
             (let [responses (handle-socket-data! options buffer data-chunk)]
               (doseq [response responses]
                 (when response
                   (if (p/promise? response)
                     (-> response
                         (p/then (fn [resolved-response]
                                   (dispatch! [[:app/ax.log :debug
                                                "[Server] Sending resolved response:"
                                                (pr-str resolved-response)]])
                                   (.write socket (format-response-json resolved-response))))
                         (p/catch (fn [err]
                                    (dispatch! [[:app/ax.log :error
                                                 "[Server] Error resolving response:" err]])
                                    (let [error-response (create-error-response nil -32603 (str "Internal error: " err))]
                                      (.write socket (format-response-json error-response))))))
                     ;; Handle non-promise responses
                     (do
                       (dispatch! [[:app/ax.log :debug
                                    "[Server] Sending response:"
                                    (pr-str response)]])
                       (.write socket (format-response-json response))))))))
           (.on socket "error"
                (fn [err]
                  (dispatch! [[:app/ax.log :error "[Server] Socket error:" socket-id peer-label err]])))))))

(defn- start-socket-server!+ [{:ex/keys [dispatch!]
                               :server/keys [port] :as options}]
  (p/create
   (fn [resolve-fn reject]
     (try
       (let [server (.createServer
                     net
                     (fn [^js socket]
                       (setup-socket-handlers! options socket)))
             listen-port (or port 0)]
         (.listen server listen-port
                  (fn []
                    (let [address (.address server)
                          assigned-port (.-port address)
                          falling-back? (and (not= 0 listen-port)
                                             (not= listen-port assigned-port))]
                      (dispatch! [[:app/ax.log :info "[Server] Socket server listening on port" assigned-port]])
                      (resolve-fn (merge {:server/instance server :server/port assigned-port}
                                         (when falling-back?
                                           {:server/port-note (str "NOTE: Port " port " was already in use.")}))))))
         (.on server "error"
              (fn [err]
                (if (and (= (.-code err) "EADDRINUSE")
                         (not= listen-port 0))
                  (do
                    (dispatch! [[:app/ax.log :warn (str "[Server] Port " listen-port " already in use, falling back to an available port")]])
                    ;; Try again with port 0 (available port)
                    (.listen server 0))
                  (do
                    (dispatch! [[:app/ax.log :error "[Server] Server creation error:" err]])
                    (reject err))))))
       (catch js/Error e
         (dispatch! [[:app/ax.log :error "[Server] Error creating server:" (.-message e)]])
         (reject e))))))

(defn send-notification-params [{:ex/keys [dispatch!]} notification]
  (let [sockets @active-sockets]
    (when (seq sockets)
      (doseq [socket sockets]
        (try
          (.write socket (str (js/JSON.stringify (clj->js notification)) "\n"))
          (catch js/Error e
            (dispatch! [:app/ax.log :error "[Server] Error sending notification:" (.-message e)])))))))

(defn- cursor-unique-id [workspace-root-path-or-nil]
  (if workspace-root-path-or-nil
    (str "ws-" (hash workspace-root-path-or-nil))
    "no-workspace"))

(defn- get-cursor-port-file-uri [wrapper-config-path workspace-root-uri]
  (let [config-parent (.dirname path wrapper-config-path)
        unique-id (cursor-unique-id (some-> workspace-root-uri .-fsPath))
        port-file-path (.join path config-parent "mcp-server" unique-id "port")]
    (vscode/Uri.file port-file-path)))

(defn start-server!+
  "Creates a socket server and writes the port to a file.
   Returns a promise that resolves to a map with server info when the MCP server starts successfully."
  [{:ex/keys [dispatch!] :keys [vscode/extension-context mcp/use-global-port-file? mcp/wrapper-config-path] :as options}]
  (p/let [server-info+ (start-socket-server!+ options)
          port (:server/port server-info+)
          ^js port-file-uri (if use-global-port-file?
                              (get-cursor-port-file-uri wrapper-config-path (get-workspace-root-uri-or-nil))
                              (get-port-file-uri+ extension-context))
          port-file-dir (if use-global-port-file?
                          (vscode/Uri.joinPath port-file-uri "..")
                          (get-server-dir+ extension-context))]
    (p/do!
     (vscode/workspace.fs.createDirectory port-file-dir)
     (.writeFile vscode/workspace.fs port-file-uri (js/Buffer.from (str port)))
     (dispatch! [[:app/ax.log :info "Wrote port file:" (.-fsPath port-file-uri)]])
     (assoc server-info+ :server/port-file-uri port-file-uri))))

(defn- close-server!+ [{:ex/keys [dispatch!]
                        :server/keys [instance]}]
  (dispatch! [[:app/ax.log :info "Stopping socket server..."]])
  (-> (p/create
       (fn [resolve-fn reject]
         (when (seq @active-sockets)
           (dispatch! [[:app/ax.log :info "Closing all active socket connections (" (count @active-sockets) ")..."]])
           (doseq [^js socket @active-sockets]
             (try
               (.end socket)
               (.destroy socket)
               (catch js/Error e
                 (dispatch! [[:app/ax.log :warn "[Server] Error closing socket:" (.-message e)]])))))
         (reset! active-sockets #{})
         (.close instance
                 (fn [err]
                   (if err
                     (do
                       (dispatch! [[:app/ax.log :error "[Server] Error stopping socket server:" err]])
                       (reject err))
                     (do
                       (dispatch! [[:app/ax.log :info "Socket server stopped."]])
                       (resolve-fn true)))))))
      (p/catch (fn [err2]
                 (dispatch! [[:app/ax.log :error "[Server] Error stopping socket server:" err2]])))))

(defn stop-server!+
  "Stops the MCP server and removes the port file.
   Returns a promise that resolves to a boolean indicating success."
  [{:keys [server/instance ex/dispatch! vscode/extension-context] :as options}]
  (if-not instance
    (do
      (dispatch! [[:app/ax.log :info "No server instance provided to stop."]])
      (p/resolved false))
    (-> (close-server!+ options)
        (p/then (fn [_]
                  (let [port-file-uri (get-port-file-uri+ extension-context)]
                    (delete-port-file!+ options port-file-uri))))
        (p/then (fn [_] true))
        (p/catch (fn [err]
                   (dispatch! [[:app/ax.log :error "[Server] Error during server shutdown:" err]])
                   false)))))

(comment
  ;; Todo fix working RCF
  ;; (start-server!+)
  ;; (stop-server!+)
  :rcf)

