(ns calva-backseat-driver.integrations.calva.editor-util
  (:require
   [clojure.string :as string]))

(defn find-target-line-by-text
  "Find the actual line number by searching for target text within a window around the initial line.
   Returns the line number where the target text is found, or nil if not found."
  [^js document-text target-text initial-line-number search-window]
  (let [lines (string/split document-text #"\r?\n" -1)
        line-count (count lines)
        start-line (max 0 (- initial-line-number search-window))
        end-line (min (dec line-count) (+ initial-line-number search-window))
        trimmed-target-text (string/trim target-text)]
    (loop [line-idx start-line]
      (if (<= line-idx end-line)
        (let [line-text (-> lines
                            (nth line-idx)
                            (string/trim))]
          (if (= line-text trimmed-target-text)
            line-idx
            (recur (inc line-idx))))
        nil))))