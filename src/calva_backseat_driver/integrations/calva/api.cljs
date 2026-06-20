(ns calva-backseat-driver.integrations.calva.api
  (:require
   ["vscode" :as vscode]))

(def ^:private ^js calvaExt (vscode/extensions.getExtension "betterthantomorrow.calva"))

(def calva-api (-> calvaExt
                   .-exports
                   .-v1
                   (js->clj :keywordize-keys true)))

(defn calva-version
  "Returns Calva's installed version string, or nil if unavailable."
  []
  (some-> calvaExt .-packageJSON .-version))

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

(defn when-calva-activated [{:ex/keys [dispatch! then]}]
  (let [!interval-id (atom nil)]
    (reset! !interval-id (js/setInterval (fn []
                                           (when (.-isActive calvaExt)
                                             (js/clearInterval @!interval-id)
                                             (dispatch! then)))
                                         100))))
