(ns calva-backseat-driver.app.db)

(def init-db {:vscode/extension-context nil
              :extension/disposables []
              :extension/when-contexts {:calva-mcp-extension/activated? false}
              :output/limit 150})

(defonce !app-db (atom init-db))

(comment
  (:calva/output-buffer @!app-db)
  :rcf)