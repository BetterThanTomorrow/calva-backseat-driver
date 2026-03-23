(ns e2e.utils
  (:require [promesa.core :as p]))

(defn wait-for+
  "Polls pred-fn until it returns truthy, then resolves.
   Rejects with timeout error if deadline is exceeded."
  [pred-fn & {:keys [interval timeout message]
              :or {interval 50 timeout 5000 message "wait-for timed out"}}]
  (p/create
   (fn [resolve reject]
     (let [start (.now js/Date)
           check (fn check []
                   (if (pred-fn)
                     (resolve true)
                     (if (> (- (.now js/Date) start) timeout)
                       (reject (js/Error. message))
                       (js/setTimeout check interval))))]
       (check)))))
