(ns mini.test-editing
  "A test file for exercising editing operations"
  (:require [clojure.string]))

(defn hello
  "Says hello to someone"
  [name]
  (str "Hello, " name "!"))

(defn greet-enthusiastically
  "Greets someone with great enthusiasm!"
  [name]
  (str "HELLO THERE, " (clojure.string/upper-case name) "!!!"))

(defn add-numbers
  "Adds two numbers together"
  [a b]
  (+ a b))

(defn multiply
  "Multiplies two numbers"
  [x y]
  (* x y))
