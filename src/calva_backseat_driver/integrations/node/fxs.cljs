(ns calva-backseat-driver.integrations.node.fxs)

(defn- log
  "Writes `args` to the console."
  [_dispatch! & args]
  (apply js/console.log args))

(defn- log-error
  "Writes `args` to the console as an error."
  [_dispatch! & args]
  (apply js/console.error args))

(def effects
  {:node/fx.log #'log
   :node/fx.log-error #'log-error})
