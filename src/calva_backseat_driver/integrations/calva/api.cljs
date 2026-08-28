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

(defn on-sessions-changed
  "Subscribe to Calva session/runtime change notifications. Returns a Disposable, or nil."
  [listener]
  (when-let [subscribe (get-in calva-api [:repl :onSessionsChanged])]
    (subscribe listener)))

(defn when-calva-activated [{:ex/keys [dispatch! then]}]
  (let [!interval-id (atom nil)]
    (reset! !interval-id (js/setInterval (fn []
                                           (when (.-isActive calvaExt)
                                             (js/clearInterval @!interval-id)
                                             (dispatch! then)))
                                         100))))
