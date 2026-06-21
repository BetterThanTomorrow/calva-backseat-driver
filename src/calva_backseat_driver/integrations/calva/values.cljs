(ns calva-backseat-driver.integrations.calva.values)

(defn present-value?
  "True when a Calva API value is actually present (not nil or js/undefined)."
  [v]
  (and (not (nil? v)) (not (undefined? v))))

(defn js-field-value
  "Returns a JS object field when Calva included it with a defined value."
  [obj field]
  (let [v (aget obj field)]
    (when (present-value? v) v)))

(defn map-field-value
  "Returns a map entry when the key is present with a defined value."
  [m k]
  (when (contains? m k)
    (let [v (get m k)]
      (when (present-value? v) v))))
