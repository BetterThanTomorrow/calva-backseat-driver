(ns calva-backseat-driver.integrations.calva.fxs
  (:require
   [clojure.core.match :refer [match]]
   [datascript.core :as d]
   [calva-backseat-driver.app.db :as db]
   [calva-backseat-driver.integrations.calva.api :as calva]
   [calva-backseat-driver.integrations.calva.features :as calva-features]))

(defn perform-effect! [dispatch! ^js context effect]
  (match effect
    [:calva/fx.when-activated actions]
    (calva/when-calva-activated {:ex/dispatch! (partial dispatch! context)
                                 :ex/then actions})

    [:calva/fx.subscribe-to-output on-output]
    (let [disposable (calva-features/subscribe-to-output {:ex/dispatch! #(dispatch! context [%])
                                                          :calva/on-output on-output})]
      (.push (.-subscriptions context) disposable))

    [:calva/fx.add-output message]
    (let [line (swap! db/!output-line-counter inc)
          entity (cond-> {:output/line line
                          :output/category (:category message)
                          :output/text (:text message)
                          :output/timestamp (js/Date.now)}
                   (:who message) (assoc :output/who (:who message)))]
      (d/transact! db/!output-conn [entity])
      (let [max-size 1000
            total (d/q '[:find (count ?e) . :where [?e :output/line]] @db/!output-conn)]
        (when (and total (> total max-size))
          (let [excess (- total max-size)
                oldest-lines (take excess (sort (d/q '[:find [?l ...] :where [?e :output/line ?l]] @db/!output-conn)))
                retractions (mapv (fn [line]
                                    [:db.fn/retractEntity
                                     (d/q '[:find ?e . :in $ ?l :where [?e :output/line ?l]] @db/!output-conn line)])
                                  oldest-lines)]
            (d/transact! db/!output-conn retractions)))))

    :else
    (js/console.error "Unknown effect:" (pr-str effect))))
