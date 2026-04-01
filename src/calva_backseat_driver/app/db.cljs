(ns calva-backseat-driver.app.db
  (:require [datascript.core :as d]))

(def init-db {:vscode/extension-context nil
              :extension/disposables []
              :extension/when-contexts {:calva-mcp-extension/activated? false}
              :calva/output-line-counter 0})

(defonce !app-db (atom init-db))

(defonce !output-conn (d/create-conn))

(comment
  (d/q '[:find (count ?e) . :where [?e :output/line]] @!output-conn)
  :rcf)