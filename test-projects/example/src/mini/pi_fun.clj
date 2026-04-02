(ns mini.pi-fun
  (:require [mini.pi-decimals :as decimals]))

(def pi-decimals
  "The decimal digits of pi as a string (no leading '3.')."
  (subs decimals/pi-10k 2))

(defn pi-decimal
  "Returns the nth decimal digit of pi (1-indexed)."
  [n]
  (Character/digit (nth pi-decimals (dec n)) 10))

(defn rand-pi-index
  "Returns a random 1-indexed position in pi's decimals where the digit equals d (0-9)."
  [d]
  (let [indices (keep-indexed (fn [i c]
                                (when (= (Character/digit c 10) d) (inc i)))
                              pi-decimals)]
    (rand-nth (vec indices))))

(comment
  (pi-decimal 1)       ;=> 1
  (pi-decimal 3)       ;=> 5
  (mapv pi-decimal (range 1 6))
  (let [i (rand-pi-index 5)]
    {:index i :digit (pi-decimal i)})  ;=> {:index <random>, :digit 5}
  (rand-pi-index 4)
  (pi-decimal 3)    ;;=> 1
  (pi-decimal 5336) ;;=> 4
  (pi-decimal 7)    ;;=> 6
  (pi-decimal 42)   ;;=> 9
  (mapv pi-decimal [3 5336 7 42]) ;;=> [1 4 6 9]
  )




