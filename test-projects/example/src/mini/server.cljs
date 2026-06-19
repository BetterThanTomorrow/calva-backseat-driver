(ns mini.server
  (:require
   ["fs" :as fs]
   ["net" :as net]
   ["path" :as path]
   ["process" :as node-process]
   [clojure.string :as string]))

(defonce !state
  (atom {:server/count 0}))

(defn- port-dir []
  (.join path (.cwd node-process) ".calva" "mini-server"))

(defn- port-file []
  (.join path (port-dir) "port"))

(defn- log [& args]
  (apply js/console.log "[mini.server]" args))

(defn- ensure-port-dir! []
  (.mkdirSync fs (port-dir) #js {:recursive true}))

(defn- write-port-file! [port]
  (ensure-port-dir!)
  (.writeFileSync fs (port-file) (str port) #js {:encoding "utf8"})
  (log "Wrote port file:" (port-file) port))

(defn- format-response [data]
  (str (js/JSON.stringify (clj->js data)) "\n"))

(defn- handle-command [command]
  (when (= "inc" command)
    (swap! !state update :server/count inc))
  (format-response {:count (:server/count @!state)
                    :command command}))

(defn- split-buffer-on-newline [buffer]
  (let [lines (string/split buffer #"\n")]
    (cond
      (empty? lines) [[] ""]
      (string/ends-with? buffer "\n")
      [(filter (comp not string/blank?) lines) ""]
      :else
      [(filter (comp not string/blank?) (butlast lines)) (last lines)])))

(defn- setup-socket! [^js socket]
  (.setEncoding socket "utf8")
  (let [buffer (volatile! "")]
    (.on socket "data"
         (fn [chunk]
           (vswap! buffer str chunk)
           (let [[segments remainder] (split-buffer-on-newline @buffer)]
             (vreset! buffer remainder)
             (doseq [segment segments]
               (let [command (string/trim segment)]
                 (when (seq command)
                   (.write socket (handle-command command))))))))
    (.on socket "error"
         (fn [err]
           (log "Socket error:" (.-message err))))))

(defn- start-server! [listen-port]
  (let [server (.createServer net setup-socket!)]
    (.listen server listen-port
             (fn []
               (let [assigned-port (.-port (.address server))]
                 (write-port-file! assigned-port)
                 (log "Listening on port" assigned-port))))
    (.on server "error"
         (fn [err]
           (log "Server error:" (.-message err))))))

(defn ^:export main [& _args]
  (log "Starting mini test server")
  (start-server! 0))

(defn ^:export reload []
  (log "Reloaded. Count:" (:server/count @!state)))
