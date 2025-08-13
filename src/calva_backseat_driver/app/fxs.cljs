(ns calva-backseat-driver.app.fxs
  (:require
   ["vscode" :as vscode]
   [calva-backseat-driver.ex.ax :as ax]
   [calva-backseat-driver.tools :as tools]
   [calva-backseat-driver.mcp.logging :as logging]
   [clojure.core.match :refer [match]]))

(defn perform-effect! [dispatch! ^js context effect]
  (match effect
    [:app/fx.init-logging options]
    (logging/init!+ (merge options
                           {:ex/dispatch! (partial dispatch! context)}))

    [:app/fx.log options level messages]
    (let [min-level (:app/min-log-level options :debug)
          levels {:error 0, :warn 1, :info 2, :debug 3}]
      (when (<= (levels level) (levels min-level))
        (apply logging/log! (assoc options :ex/dispatch! dispatch!) level messages)))

    [:app/fx.return v]
    v

    [:app/fx.register-command command-id actions]
    (let [disposable (vscode/commands.registerCommand
                      command-id
                      (fn [& args]
                        (dispatch!
                         context
                         (ax/enrich-with-args actions
                                              (js->clj args :keywordize-keys true)))))]
      (.push (.-subscriptions context) disposable)
      (dispatch! context [[:db/ax.update-in [:extension/disposables] conj disposable]]))

    [:app/fx.register-language-model-tools]
    (let [disposables (tools/register-language-model-tools (partial dispatch! context))]
      (doseq [disposable disposables]
        (.push (.-subscriptions context) disposable))
      (dispatch! context [[:db/ax.update-in [:extension/disposables] into disposables]]))


    [:app/fx.clear-disposables disposables]
    (doseq [^js disposable disposables]
      (.dispose disposable))

    :else
    (js/console.warn "Unknown extension effect:" (pr-str effect))))