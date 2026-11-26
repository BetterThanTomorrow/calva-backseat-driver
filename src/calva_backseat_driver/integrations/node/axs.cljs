(ns calva-backseat-driver.integrations.node.axs
  (:require [clojure.core.match :refer [match]]))

(defn handle-action [_state _context action]
  (match action
    [:node/ax.log & args]
    {:ex/fxs [(into [:node/fx.log] args)]}

    :else
    {:ex/fxs [[:node/fx.log-error "Unknown action:" (pr-str action)]]}))