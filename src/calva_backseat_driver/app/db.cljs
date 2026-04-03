(ns calva-backseat-driver.app.db
  (:require [cognitect.transit :as transit]
            [datascript.core :as d]))

(def init-db {:vscode/extension-context nil
              :extension/disposables []
              :extension/when-contexts {:calva-mcp-extension/activated? false}
              :calva/output-line-counter 0})

(defonce !app-db (atom init-db))

(defonce !output-conn (d/create-conn))

(defonce !history-conn (d/create-conn {:output/line {:db/unique :db.unique/identity}}))

(defn serialize-history [conn]
  (let [entities (d/q '[:find [(pull ?e [:output/line :output/category :output/text :output/who :output/timestamp :output/ns :output/repl-session-key]) ...]
                        :where [?e :output/line]]
                      @conn)
        data {:format-version 1 :entities (vec entities)}
        w (transit/writer :json)]
    (transit/write w data)))

(defn deserialize-history [transit-str]
  (try
    (let [r (transit/reader :json)
          data (transit/read r transit-str)]
      (when (= 1 (:format-version data))
        (:entities data)))
    (catch :default _e
      nil)))

(comment
  (d/q '[:find (count ?e) . :where [?e :output/line]] @!output-conn)
  (d/q '[:find (count ?e) . :where [?e :output/line]] @!history-conn)
  :rcf)