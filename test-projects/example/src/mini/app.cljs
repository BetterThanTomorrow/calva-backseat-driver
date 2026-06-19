(ns mini.app
  (:require [replicant.dom :as r]))

(defonce !state
  (atom {:app/count 0}))

(defn ui [{:app/keys [count]}]
  [:main
   [:h1 "Backseat Driver CLJS Test"]
   [:p "A minimal Replicant + shadow-cljs app for testing BD tooling."]
   [:p "Count: " (str count)]
   [:button {:on {:click [[:app/inc]]}}
    "Increment"]])

(defn handle-action [state [action & _args]]
  (case action
    :app/inc (update state :app/count inc)
    state))

(defn dispatch-actions! [actions]
  (swap! !state
         (fn [state]
           (reduce handle-action state actions))))

(defn event-handler [_event-data actions]
  (dispatch-actions! actions))

(defn render! []
  (r/render (js/document.getElementById "app")
            (ui @!state)))

(defn ^:export init []
  (r/set-dispatch! event-handler)
  (add-watch !state ::render
             (fn [_ _ old new]
               (when (not= old new)
                 (render!))))
  (render!))

(defn ^:export reload []
  (render!))
