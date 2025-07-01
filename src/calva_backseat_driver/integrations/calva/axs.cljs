(ns calva-backseat-driver.integrations.calva.axs
  (:require [clojure.core.match :refer [match]]))

(defn handle-action [state _context action]
  (match action
    [:calva/ax.when-activated actions]
    {:ex/fxs [[:calva/fx.when-activated actions]]}

    [:calva/ax.subscribe-to-output]
    {:ex/fxs [[:calva/fx.subscribe-to-output [:calva/ax.add-output]]]}

    [:calva/ax.add-output message]
    (let [message-count (inc (:calva/output-message-count state))
          max-buffer-size (:calva/max-output-buffer-size state 1000)
          output-buffer (conj (:calva/output-buffer state [])
                              (assoc message :line message-count))
          capped-buffer (subvec output-buffer (max 0 (- (count output-buffer) max-buffer-size)))]
      {:ex/db (assoc state
                     :calva/output-buffer capped-buffer
                     :calva/output-message-count message-count)})

    [:calva/ax.get-output since-line limit]
    {:ex/fxs [[:app/fx.return (->> (:calva/output-buffer state)
                                   (filter (fn [message]
                                             (> (:line message 0) since-line)))
                                   (take-last limit))]]}

    :else
    {:ex/fxs [[:node/fx.log-error "Unknown action:" (pr-str action)]]}))
