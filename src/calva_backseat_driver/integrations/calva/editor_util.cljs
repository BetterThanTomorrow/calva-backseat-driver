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
   target-text - optional text to match against context lines
   Returns map with formatted context string and matched line number."
  [text line-number padding target-text]
  (let [lines (string/split text #"\r?\n" -1)
        total-lines (count lines)
        target-idx (dec line-number)
         ;; Calculate range using padding (±N lines)
        start-idx (max 0 (- target-idx padding))
        end-idx (min (dec total-lines) (+ target-idx padding))
        max-line-num (inc end-idx)
        line-padding (calculate-line-padding max-line-num true)
        trimmed-target (when (and target-text (not (string/blank? target-text)))
                         (string/trim target-text))
        formatted-lines (for [i (range start-idx (inc end-idx))]
                          (let [doc-line (nth lines i)
                                trimmed-line (string/trim doc-line)
                                line-num (inc i)
                                is-target? (= line-num line-number)
                                matches-target? (and trimmed-target
                                                     (= trimmed-line trimmed-target))
                                marker (format-line-marker is-target?)
                                marker-len (count marker)
                                padded-num (format-line-number line-num line-padding marker-len)]
                            {:line-text (str marker padded-num " | " doc-line)
                             :line-number line-num
                             :matches-target? matches-target?}))
        matched-line-in-context (when-let [line (-> (filter :matches-target? formatted-lines)
                                                    first)]
                                  (:line-number line))]
    {:editor/file-context (string/join "\n" (map :line-text formatted-lines))
     :editor/matched-line-in-context matched-line-in-context}))

(defn get-remedy-for-targeting
  "Generate appropriate remedy message based on context.
   matched-line-in-context - line number matching the target text within provided context.
   Returns remedy string."
  [matched-line-in-context]
  (if matched-line-in-context
    (str "Line " matched-line-in-context " in the context below matches your target text. "
         "Consider if this is your intended target line. ")
    (str "The target text was not found in the context shown below. "
         "Read the file to understand the actual structure, then target the correct line "
         "with the first line of the existing top level form starting at that line.")))