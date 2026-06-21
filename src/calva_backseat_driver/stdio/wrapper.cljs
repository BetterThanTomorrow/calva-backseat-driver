(ns calva-backseat-driver.stdio.wrapper
  (:require
   ["fs" :as fs]
   ["net" :as net]
   ["process" :as process]
   [clojure.string :as string]))

(def log-levels {:error 0
                 :warn 1
                 :info 2
                 :debug 3})

(def startup-retry-delay-ms 500)

(def startup-timeout-ms 30000)

(def min-log-level
  (let [arg-level (some #(when (.startsWith % "--min-log-level=")
                           (subs % (count "--min-log-level=")))
                        (js->clj (.-argv process)))
        level-kw (when arg-level (keyword arg-level))]
    (get log-levels level-kw :debug)))

(defn log-stderr
  ([args] (log-stderr :debug args))
  ([level & args]
   (when (>= (get log-levels :debug) (get log-levels level min-log-level))
     (.write (.-stderr process) (str "[Wrapper] " (string/join " " args) "\n")))))

;; Redirect console output to stderr, defaulting to debug level
(set! js/console.log (partial log-stderr :debug))
(set! js/console.error (partial log-stderr :error))

(def original-stdout (.-stdout process))

(defn read-port-from-file [port-file-path]
  (js/Promise.
   (fn [resolve _reject]
     (.readFile fs port-file-path #js {:encoding "utf8"}
                (fn [err data]
                  (if err
                    (do (log-stderr :warn "Port file read error:" err)
                        (resolve nil))
                    (let [port-num (js/parseInt data 10)]
                      (if (js/isNaN port-num)
                        (do (log-stderr :error "Invalid port number in file:" data)
                            (resolve nil))
                        (resolve port-num)))))))))

(defn- sleep+ [ms]
  (js/Promise.
   (fn [resolve _reject]
     (js/setTimeout resolve ms))))

(defn- resolve-port+ [port-or-port-file]
  (if-let [parsed-port (parse-long port-or-port-file)]
    (do
      (log-stderr :info "Connecting to Backseat Driver on port." parsed-port)
      (js/Promise.resolve parsed-port))
    (let [started-at (js/Date.now)]
      (log-stderr :info "Connecting to Backseat Driver using port file." port-or-port-file)
      (letfn [(try-read+ []
                (-> (read-port-from-file port-or-port-file)
                    (.then (fn [port]
                             (if port
                               port
                               (if (< (- (js/Date.now) started-at) startup-timeout-ms)
                                 (-> (sleep+ startup-retry-delay-ms)
                                     (.then try-read+))
                                 nil))))))]
        (try-read+)))))

(defn- connect-socket+ [port]
  (js/Promise.
   (fn [resolve reject]
     (let [socket (net/connect #js {:port port})
           connected? (volatile! false)]
       (.once socket "connect"
              (fn []
                (vreset! connected? true)
                (resolve socket)))
       (.once socket "error"
              (fn [err]
                (when-not @connected?
                  (.destroy socket)
                  (reject err))))))))

(defn- connect-socket-with-retry+ [port]
  (let [started-at (js/Date.now)
        !last-error (volatile! nil)]
    (letfn [(try-connect+ []
              (-> (connect-socket+ port)
                  (.catch (fn [err]
                            (vreset! !last-error err)
                            (if (< (- (js/Date.now) started-at) startup-timeout-ms)
                              (do
                                (log-stderr :warn "Could not connect to MCP server, retrying:" (.-message err))
                                (-> (sleep+ startup-retry-delay-ms)
                                    (.then try-connect+)))
                              (js/Promise.reject (or @!last-error err)))))))]
      (try-connect+))))

(defn- process-newline-buffer! [buffer on-line]
  (loop []
    (let [buffer-val @buffer
          newline-pos (.indexOf buffer-val "\n")]
      (if (>= newline-pos 0)
        (let [message-part (subs buffer-val 0 newline-pos)
              _ (vreset! buffer (subs buffer-val (inc newline-pos)))
              message (string/trim message-part)]
          (when-not (string/blank? message)
            (on-line message))
          (recur))
        nil))))

(defn- flush-newline-buffer!
  "On graceful stream end, emit any remaining buffered content as a final
   message. A well-behaved peer terminates messages with a newline, but the
   last message may arrive without one; this avoids silently dropping it."
  [buffer on-line]
  (let [remaining (string/trim @buffer)]
    (vreset! buffer "")
    (when-not (string/blank? remaining)
      (on-line remaining))))

(defn handle-stdin [^js stdin ^js socket]
  (let [stdin-buffer (volatile! "")
        forward! (fn [message]
                   (log-stderr :info "Complete message segment from stdin, sending to socket:" message)
                   (.write socket (str message "\n")))]
    (.setEncoding stdin "utf8")

    ;; Handle stdin data
    (.on stdin "data"
         (fn [chunk]
           (log-stderr :debug "Raw stdin chunk received:" chunk)
           (vswap! stdin-buffer str chunk)
           (process-newline-buffer! stdin-buffer forward!)))

    ;; Handle stdin errors
    (.on stdin "error" (fn [err] (log-stderr :error "stdin error:" err)))

    ;; Flush a trailing newline-less message on graceful end
    (.on stdin "end" (fn [] (flush-newline-buffer! stdin-buffer forward!)))

    ;; Handle stdin close
    (.on stdin "close" (fn [] (log-stderr :info "stdin closed.")))))

(defn- json-message? [s]
  (and (string? s)
       (not (string/blank? s))
       (or (.startsWith s "{")
           (.startsWith s "["))))

(defn handle-socket [^js socket]
  (.setEncoding socket "utf8")

  (let [socket-buffer (volatile! "")
        forward! (fn [message]
                   (if (json-message? message)
                     (do
                       (log-stderr :info "Sending to stdout:" message)
                       (.write original-stdout (str message "\n")))
                     (log-stderr :warn "Filtered potential non-JSON output from socket:" message)))]
    ;; Forward socket server responses to stdout
    (.on socket "data"
         (fn [data]
           (log-stderr :debug "Received from socket:" data)
           (vswap! socket-buffer str data)
           (process-newline-buffer! socket-buffer forward!)))

    ;; Flush a trailing newline-less message on graceful end
    (.on socket "end" (fn [] (flush-newline-buffer! socket-buffer forward!)))

    ;; Handle socket errors
    (.on socket "error"
         (fn [err]
           (log-stderr :error "Socket error:" err)
           (.write original-stdout
                   (str (js/JSON.stringify
                         #js {:jsonrpc "2.0"
                              :error #js {:code -32000
                                          :message (str "Server connection error: "
                                                        (.-message err))}})
                        "\n"))
           (.exit process 1)))

    ;; Handle socket close
    (.on socket "close"
         (fn [had-error?]
           (log-stderr :info (if had-error?
                               "Socket closed due to transmission error."
                               "Socket connection closed cleanly."))
           (.exit process (if had-error? 1 0))))))

(defn ^:export main [port-or-port-file & _]

  (log-stderr :info "Running in node version: " (.-version process))

  (if-not port-or-port-file
    (do
      (log-stderr :error "Usage: calva-mcp-server.js <port or port-file>")
      (.write original-stdout
              (str (js/JSON.stringify
                    #js {:jsonrpc "2.0"
                         :error #js {:code -32002
                                     :message "Configuration error: Port or port file path not provided."}})
                   "\n"))
      (.exit process 1))
    (-> (resolve-port+ port-or-port-file)
        (.then (fn [port]
                 (if port
                   (-> (connect-socket-with-retry+ port)
                       (.then (fn [socket]
                                (let [stdin (.-stdin process)]
                                  (handle-stdin stdin socket)
                                  (handle-socket socket)
                                  (log-stderr :info "Connected to MCP server on port" port)))))
                   (do
                     (log-stderr :error "Error: Port file not found:" port-or-port-file)
                     (.write original-stdout
                             (str (js/JSON.stringify
                                   #js {:jsonrpc "2.0"
                                        :error #js {:code -32001
                                                    :message "MCP server not running or port file missing."}})
                                  "\n"))
                     (.exit process 1)))))
        (.catch (fn [err]
                  (log-stderr :error "Could not connect to MCP server:" err)
                  (.write original-stdout
                          (str (js/JSON.stringify
                                #js {:jsonrpc "2.0"
                                     :error #js {:code -32000
                                                 :message (str "Server connection error: "
                                                               (.-message err))}})
                               "\n"))
                  (.exit process 1))))))