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

(defn format-line-marker
  "Returns marker string for a line. Just '→' for target, empty string otherwise."
  [is-target?]
  (if is-target? "→" ""))

(defn format-line-number
  "Format a line number with padding.
   n - the line number
   padding - total width for marker + padding + number
   marker-len - length of marker (0 or 1)
   Returns padded string like '  5' or ' 42' or '123'"
  [n padding marker-len]
  (let [num-str (str n)
        needed-padding (- padding marker-len (count num-str))
        pad-str (apply str (repeat needed-padding " "))]
    (str pad-str num-str)))

(defn calculate-line-padding
  "Calculate padding width needed for line numbers.
   max-line-number - the largest line number that will be displayed
   has-marker? - whether any line will have a marker
   Returns total width needed for [marker][padding][number]"
  [max-line-number has-marker?]
  (let [number-width (count (str max-line-number))
        marker-width (if has-marker? 1 0)]
    (+ marker-width number-width)))

(defn get-context-lines
  "Extract lines around a target line with line numbers.
   text - the full document text
   line-number - the 1-indexed line to focus on
   padding - number of lines to show above and below the target line
   Returns formatted string with line numbers and marker for target line."
  [text line-number padding]
  (let [lines (string/split text #"\r?\n" -1)
        total-lines (count lines)
        target-idx (dec line-number)
        ;; Calculate range using padding (±N lines)
        start-idx (max 0 (- target-idx padding))
        end-idx (min (dec total-lines) (+ target-idx padding))
        max-line-num (inc end-idx)
        line-padding (calculate-line-padding max-line-num true)
        formatted-lines (for [i (range start-idx (inc end-idx))]
                          (let [line-text (nth lines i)
                                line-num (inc i)
                                is-target? (= line-num line-number)
                                marker (format-line-marker is-target?)
                                marker-len (count marker)
                                padded-num (format-line-number line-num line-padding marker-len)]
                            (str marker padded-num " | " line-text)))]
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