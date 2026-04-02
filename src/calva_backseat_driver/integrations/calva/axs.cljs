(ns calva-backseat-driver.integrations.calva.axs
  (:require [clojure.core.match :refer [match]]
            [cljs.reader :as reader]
            [datascript.core :as d]
            [calva-backseat-driver.app.db :as db]))

(defn handle-action [state _context action]
  (match action
    [:calva/ax.when-activated actions]
    {:ex/fxs [[:calva/fx.when-activated actions]]}

    [:calva/ax.subscribe-to-output]
    {:ex/fxs [[:calva/fx.subscribe-to-output [:calva/ax.add-output]]]}

    [:calva/ax.add-output message]
    (let [line (inc (:calva/output-line-counter state 0))
          entity (cond-> {:output/line line
                          :output/category (:category message)
                          :output/text (:text message)
                          :output/timestamp (js/Date.now)}
                   (:who message) (assoc :output/who (:who message)))]
      {:ex/db (assoc state :calva/output-line-counter line)
       :ex/fxs [[:calva/fx.transact-output entity]]})

    [:calva/ax.query-output query-edn-str inputs]
    (let [query (reader/read-string query-edn-str)
          result (apply d/q query @db/!output-conn inputs)]
      {:ex/fxs [[:app/fx.return result]]})

    :else
    {:ex/fxs [[:node/fx.log-error "Unknown action:" (pr-str action)]]}))
