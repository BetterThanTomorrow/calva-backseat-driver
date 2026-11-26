(ns calva-backseat-driver.app.db
  "The app state: one atom that holds values. The loop in `app.dispatch` is the only
   writer. The extension context, the disposables and the timers are live objects, in
   `app.live-objects`.")

(def init-db
  "The state before activation. The output log and the eval history are sorted maps
   from an entity's line to the entity."
  {:calva/output-line-counter 0
   :calva/output-log (sorted-map)
   :calva/history (sorted-map)})

(defonce !app-db (atom init-db))

(comment
  (keys @!app-db)
  (count (:calva/output-log @!app-db))
  (count (:calva/history @!app-db))
  (binding [*print-length* 5]
    (pr-str @!app-db))
  :rcf)
