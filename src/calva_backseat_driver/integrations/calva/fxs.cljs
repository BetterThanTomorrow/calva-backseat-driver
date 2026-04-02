(ns calva-backseat-driver.integrations.calva.fxs
  (:require
   ["fs" :as fs]
   ["path" :as path]
   [clojure.core.match :refer [match]]
   [datascript.core :as d]
   [calva-backseat-driver.app.db :as db]
   [calva-backseat-driver.integrations.calva.api :as calva]
   [calva-backseat-driver.integrations.calva.features :as calva-features]))

(defn- cap-conn! [conn max-size]
  (let [total (d/q '[:find (count ?e) . :where [?e :output/line]] @conn)]
    (when (and total (> total max-size))
      (let [excess (- total max-size)
            oldest-lines (take excess (sort (d/q '[:find [?l ...] :where [?e :output/line ?l]] @conn)))
            retractions (mapv (fn [l]
                                [:db.fn/retractEntity
                                 (d/q '[:find ?e . :in $ ?l :where [?e :output/line ?l]] @conn l)])
                              oldest-lines)]
        (d/transact! conn retractions)))))

(defonce !persist-timer (atom nil))

(defn- persist-history! []
  (let [storage-uri (:calva/history-storage-uri @db/!app-db)]
    (when storage-uri
      (try
        (let [fs-path (.-fsPath storage-uri)
              dir-path (path/dirname fs-path)]
          (when-not (.existsSync fs dir-path)
            (.mkdirSync fs dir-path #js {:recursive true}))
          (.writeFileSync fs fs-path (db/serialize-history db/!history-conn) "utf8"))
        (catch :default e
          (js/console.error "[History] Failed to persist:" (.-message e)))))))

(defn- schedule-persist! []
  (when-let [timer @!persist-timer]
    (js/clearTimeout timer))
  (reset! !persist-timer
          (js/setTimeout (fn []
                           (reset! !persist-timer nil)
                           (persist-history!))
                         500)))

(defn perform-effect! [dispatch! ^js context effect]
  (match effect
    [:calva/fx.when-activated actions]
    (calva/when-calva-activated {:ex/dispatch! (partial dispatch! context)
                                 :ex/then actions})

    [:calva/fx.subscribe-to-output on-output]
    (let [disposable (calva-features/subscribe-to-output {:ex/dispatch! #(dispatch! context [%])
                                                          :calva/on-output on-output})]
      (.push (.-subscriptions context) disposable))

    [:calva/fx.transact-output entity]
    (do
      (d/transact! db/!output-conn [entity])
      (cap-conn! db/!output-conn 1000)
      (when (= "clojureCode" (:output/category entity))
        (d/transact! db/!history-conn [entity])
        (cap-conn! db/!history-conn 10000)
        (schedule-persist!)))

    [:calva/fx.load-history-from-disk storage-uri]
    (if (and storage-uri (.existsSync fs (.-fsPath storage-uri)))
      (try
        (let [transit-str (.readFileSync fs (.-fsPath storage-uri) "utf8")
              entities (db/deserialize-history transit-str)]
          (if (seq entities)
            (do
              (d/transact! db/!history-conn entities)
              (d/transact! db/!output-conn entities)
              (let [max-line (d/q '[:find (max ?l) . :where [?e :output/line ?l]] @db/!history-conn)]
                (dispatch! context [[:calva/ax.history-loaded (or max-line 0)]])))
            (dispatch! context [[:calva/ax.history-loaded 0]])))
        (catch :default e
          (js/console.warn "[History] Failed to load, starting fresh:" (.-message e))
          (try
            (.unlinkSync fs (.-fsPath storage-uri))
            (catch :default _))
          (dispatch! context [[:calva/ax.history-loaded 0]])))
      (dispatch! context [[:calva/ax.history-loaded 0]]))

    [:calva/fx.flush-history]
    (do
      (when-let [timer @!persist-timer]
        (js/clearTimeout timer)
        (reset! !persist-timer nil))
      (persist-history!))

    :else
    (js/console.error "Unknown effect:" (pr-str effect))))
