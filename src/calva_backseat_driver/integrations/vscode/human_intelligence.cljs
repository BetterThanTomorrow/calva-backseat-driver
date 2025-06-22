(ns calva-backseat-driver.integrations.vscode.human-intelligence
  (:require
   ["vscode" :as vscode]
   [clojure.string :as string]
   [promesa.core :as p]))

(def timeout-ms 10000)
(def timeout-s (/ timeout-ms 1000))

(defn request-human-input! [{:ex/keys [dispatch!]
                             :cbd/keys [prompt]}]
  (let [!state (atom {:human-intelligence/result :human-intelligence/empty->what-would-rich-hickey-do?})]
    (p/create
     (fn [resolve-fn _reject]
       (let [input-box (vscode/window.createInputBox)]
         (set! (.-title input-box) "AI Agent needs input")
         (set! (.-prompt input-box) prompt)
         (set! (.-placeholder input-box) (str "Start typing to cancel auto-dismiss (" timeout-s "s timeout)..."))
         (swap! !state assoc :human-intelligence/timeout-id
                (js/setTimeout #(do
                                  (dispatch! [[:app/ax.log :debug "[Server] Human Intelligence timed out:"]])
                                  (.hide input-box))
                               timeout-ms))
         (.onDidChangeValue input-box (fn [_] (when-let [timeout-id (:human-intelligence/timeout-id @!state)]
                                                (swap! !state dissoc :human-intelligence/timeout-id)
                                                (js/clearTimeout timeout-id))))
         (.onDidAccept input-box (fn []
                                   (let [value (.-value input-box)]
                                     (dispatch! [[:app/ax.log :debug "[Server] Human Intelligence response:" value]])
                                     (when-not (string/blank? value)
                                       (swap! !state assoc :human-intelligence/result value))
                                     (.hide input-box))))
         (.onDidHide input-box (fn []
                                 (.dispose input-box)
                                 (resolve-fn (:human-intelligence/result @!state))))
         (.show input-box))))))

(comment
  (p/let [a (request-human-input! {:ex/dispatch! js/console.log
                                   :cbd/prompt "hello?"})]
    (def a a))
  :rcf)

