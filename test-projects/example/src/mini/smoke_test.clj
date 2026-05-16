(ns mini.smoke-test)

(defn greet
  [name]
  (str "Hello, " name "!"))

(defn add
  [a b]
  (+ a b))

(defn multiply
  "Multiplies two numbers."
  [a b]
  (* a b))
