(ns calva-backseat-driver.mcp.logging
  (:require
   ["fs" :as fs]
   ["path" :as path]
   ["vscode" :as vscode]
   [clojure.string :as string]))

(defonce ^:private !ensured-directories (atom #{}))

(defn- ensure-directory!
  "Creates `directory` when this is the first log line for it."
  [directory]
  (when-not (contains? @!ensured-directories directory)
    (fs/mkdirSync directory #js {:recursive true})
    (swap! !ensured-directories conj directory)))

(defn- append-file+ [file-path data]
  (js/Promise.
   (fn [resolve-fn reject]
     (fs/appendFile file-path data
                    (fn [err]
                      (if err
                        (reject err)
                        (resolve-fn)))))))

(defn log!
  "Appends a line with `level` and `messages` to the log file at the URI string
   `log-file-uri`."
  [{:app/keys [log-file-uri]} level & messages]
  (let [timestamp (.toISOString (js/Date.))
        log-entry (str timestamp " [" (name level) "] "
                       (string/join " " (map pr-str messages))
                       "\n")]
    (-> (js/Promise.resolve nil)
        (.then (fn [_]
                 (let [file-path (.-fsPath (vscode/Uri.parse log-file-uri))]
                   (ensure-directory! (path/dirname file-path))
                   (append-file+ file-path log-entry))))
        (.catch (fn [err]
                  (js/console.error "Failed to write to MCP server log:" err))))))
