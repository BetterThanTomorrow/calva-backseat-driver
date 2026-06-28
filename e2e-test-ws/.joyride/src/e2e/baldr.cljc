(ns e2e.baldr
  (:require #?(:cljs [cljs.test :as t]
               :clj [clojure.test :as t])
            [clojure.string :as string]))

(defn- default [text]
  (str "\033[39m" text "\033[22m"))

(defn- gray [text]
  (str "\033[90m" text "\033[39m"))

(defn- green [text]
  (str "\033[1;32m" text "\033[22m"))

(defn- red [text]
  (str "\033[31m" text "\033[39m"))

(def ^:private initial-state {:seen-contexts nil
                              :failure-prints []})

(defonce ^:private !state (atom initial-state))

(defn- indent [level]
  (apply str (repeat (* 2 level) " ")))

(defn- get-report [m contexts seen-contexts {:keys [color bullet bullet-color context-color]}]
  (let [common-contexts (take-while true? (map = (reverse seen-contexts) (reverse contexts)))
        common-prefix-length (count common-contexts)
        new-contexts (reverse (take (- (count contexts) common-prefix-length) contexts))
        context-color (or context-color default)
        message (or (:message m) (pr-str (:expected m)))]
    (cond-> []
      (seq new-contexts) (into (map-indexed (fn [idx ctx]
                                              (str (indent (+ 2 common-prefix-length idx))
                                                   (context-color ctx)))
                                            new-contexts))
      :always (conj (str (indent (+ 2 (count contexts)))
                         (bullet-color bullet) " " (color message))))))

(defn- report! [m config]
  (let [contexts #?(:cljs (:testing-contexts (t/get-current-env))
                    :clj t/*testing-contexts*)
        printouts (get-report m contexts (:seen-contexts @!state) config)]
    (swap! !state assoc :seen-contexts contexts)
    (doseq [printout printouts]
      (println printout))))

(defn- dispatch-value [type]
  [::t/default type])

(defmethod t/report (dispatch-value :begin-test-var) [_m]
  (swap! !state merge (select-keys initial-state [:seen-contexts])))

(def ^:private original-summary (get-method t/report (dispatch-value :summary)))
(defmethod t/report (dispatch-value :summary) [m]
  (when (seq (:failure-prints @!state))
    (println))
  (doseq [[i failure-print] (map-indexed vector (:failure-prints @!state))]
    (println (red (str (inc i) ") " (string/trim failure-print)))))
  (reset! !state initial-state)
  (original-summary m))

(def ^:private original-pass (get-method t/report (dispatch-value :pass)))
(defmethod t/report (dispatch-value :pass) [m]
  (report! m {:color gray
              :bullet "✓"
              :bullet-color green})
  (original-pass m))

(def ^:private original-fail (get-method t/report (dispatch-value :fail)))
(defmethod t/report (dispatch-value :fail) [m]
  (let [failure-printout (with-out-str (original-fail m))]
    (swap! !state update :failure-prints conj failure-printout))
  (report! m {:color red
              :bullet (str (count (:failure-prints @!state)) ")")
              :bullet-color red}))

(def ^:private original-error (get-method t/report (dispatch-value :error)))
(defmethod t/report (dispatch-value :error) [m]
  (let [error-printout (with-out-str (original-error m))]
    (swap! !state update :failure-prints conj error-printout))
  (report! m {:color red
              :bullet (str (count (:failure-prints @!state)) ")")
              :bullet-color red}))

(defmethod t/report (dispatch-value :begin-test-var) [m]
  (println (str (indent 1) (default (:var m)))))
