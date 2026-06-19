(ns mini.tui
  (:require
   ["process" :as node-process]
   ["readline" :as readline]
   [clojure.string :as string]))

(defonce !state
  (atom {:tui/count 0}))

(declare prompt!)

(defn- print-menu! [count]
  (println)
  (println "Backseat Driver CLJS node test")
  (println "Count:" count)
  (println)
  (println "  1  Increment")
  (println "  2  Quit")
  (println))

(defn- handle-choice [^js rl choice]
  (case (string/trim choice)
    "1" (do
          (swap! !state update :tui/count inc)
          (prompt! rl))
    "2" (do
          (println "Bye.")
          (.close rl))
    (do
      (println "Choose 1 or 2.")
      (prompt! rl))))

(defn- prompt! [^js rl]
  (print-menu! (:tui/count @!state))
  (.question rl "Choice: "
             (fn [answer]
               (handle-choice rl answer))))

(defn ^:export main [& _args]
  (let [rl (.createInterface readline
                             #js {:input (.-stdin node-process)
                                  :output (.-stdout node-process)})]
    (.on rl "close" #(.exit node-process 0))
    (prompt! rl)))

(defn ^:export reload []
  (println "[mini.tui] Reloaded. Count:" (:tui/count @!state)))
