(ns e2e.utils)

(defn delay+
  "Resolves after ms milliseconds."
  [ms]
  (js/Promise. (fn [resolve]
                 (js/setTimeout resolve ms))))

(defn ^:async wait-for+
  "Polls pred-fn until it returns truthy, then resolves.
   pred-fn may return a promise. Rejects with timeout error if deadline is exceeded."
  [pred-fn & {:keys [interval timeout message]
              :or {interval 50 timeout 5000 message "wait-for timed out"}}]
  (let [start (.now js/Date)]
    (loop []
      (let [result (await (pred-fn))]
        (if result
          result
          (if (> (- (.now js/Date) start) timeout)
            (throw (js/Error. message))
            (do (await (delay+ interval))
                (recur))))))))
