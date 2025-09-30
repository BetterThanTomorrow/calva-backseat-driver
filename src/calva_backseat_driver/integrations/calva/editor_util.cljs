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

(defn target-text-is-first-line?
  "Check if target text matches the first line of a form.
   Returns true if the trimmed target text equals the trimmed first line of the form."
  [target-text form-text]
  (let [trimmed-target (string/trim target-text)
        first-line (-> form-text
                       string/split-lines
                       first
                       string/trim)]
    (= trimmed-target first-line)))

(defn format-line-number
  "Format a line number with padding to 3 digits.
   Returns a string like '  5' or ' 42' or '123'"
  [n]
  (let [s (str n)
        padding (apply str (repeat (- 3 (count s)) " "))]
    (str padding s)))

(defn get-context-lines
  "Extract lines around a target line with line numbers.
   text - the full document text
   line-number - the 1-indexed line to focus on
   context-size - total number of lines to show (target will be centered when possible)
   Returns formatted string with line numbers and marker for target line."
  [text line-number context-size]
  (let [lines (string/split text #"\r?\n" -1)
        total-lines (count lines)
        target-idx (dec line-number)
        half-context (quot context-size 2)
        ;; Try to center target, but clamp to file boundaries
        ideal-start (- target-idx half-context)
        ideal-end (+ target-idx half-context)
        ;; Adjust if we hit boundaries
        start-idx (cond
                    (< ideal-start 0)
                    0
                    (>= ideal-end total-lines)
                    (max 0 (- total-lines context-size))
                    :else
                    ideal-start)
        end-idx (min (dec total-lines) (+ start-idx context-size -1))
        formatted-lines (for [i (range start-idx (inc end-idx))]
                          (let [line-text (nth lines i)
                                line-num (inc i)
                                marker (if (= line-num line-number) " → " "   ")]
                            (str marker (format-line-number line-num) " | " line-text)))]
    (string/join "\n" formatted-lines)))

(defn target-in-context?
  "Check if the target text appears anywhere in the provided file context.
   Returns the line number if found, nil otherwise.
   Trims both target and line content for comparison."
  [file-context target-text]
  (let [trimmed-target (string/trim target-text)
        lines (string/split-lines file-context)]
    (some (fn [line]
            ;; Extract content after the pipe separator
            (when-let [content-match (re-find #"\|\s*(.+)$" line)]
              (let [line-content (string/trim (second content-match))]
                (when (= line-content trimmed-target)
                  ;; Extract line number
                  (when-let [num-match (re-find #"^\s*[→\s]\s*(\d+)\s*\|" line)]
                    (js/parseInt (second num-match)))))))
          lines)))

(defn get-remedy-for-targeting
  "Generate appropriate remedy message based on context.
   file-context - the formatted context string with line numbers
   target-text - the text that was being searched for
   Returns remedy string."
  [file-context target-text]
  (if-let [found-line (target-in-context? file-context target-text)]
    (str "Line " found-line " in the context below matches your target text. "
         "Consider if this is your intended target line. "
         "Verify the line contains the first line of the top-level form you want to edit.")
    (str "The target text was not found in the context shown below. "
         "Read the file to understand the actual structure, then target the correct line "
         "with the first line of the existing top level form starting at that line.")))