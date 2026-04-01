(ns calva-backseat-driver.integrations.calva.axs
  (:require [clojure.core.match :refer [match]]
            [cljs.reader :as reader]
            [datascript.core :as d]
            [calva-backseat-driver.app.db :as db]))

(defn handle-action [_state _context action]
  (match action
    [:calva/ax.when-activated actions]
    {:ex/fxs [[:calva/fx.when-activated actions]]}

    [:calva/ax.subscribe-to-output]
    {:ex/fxs [[:calva/fx.subscribe-to-output [:calva/ax.add-output]]]}

    [:calva/ax.add-output message]
    {:ex/fxs [[:calva/fx.add-output message]]}

    [:calva/ax.query-output query-edn-str]
    (let [query (reader/read-string query-edn-str)
          result (d/q query @db/!output-conn)]
      {:ex/fxs [[:app/fx.return result]]})

    :else
    {:ex/fxs [[:node/fx.log-error "Unknown action:" (pr-str action)]]}))
