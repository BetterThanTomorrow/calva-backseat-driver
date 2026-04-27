(ns e2e.utils
  (:require [promesa.core :as p]))

(defn wait-for+
  "Polls pred-fn until it returns truthy, then resolves.
   pred-fn may return a promise. Rejects with timeout error if deadline is exceeded."
  [pred-fn & {:keys [interval timeout message]
              :or {interval 50 timeout 5000 message "wait-for timed out"}}]
  (let [start (.now js/Date)]
    (p/loop []
      (p/let [result (pred-fn)]
        (if result
          result
          (if (> (- (.now js/Date) start) timeout)
            (throw (js/Error. message))
            (p/do (p/delay interval)
                  (p/recur))))))))
