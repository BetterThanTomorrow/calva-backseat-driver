(ns calva-backseat-driver.integrations.calva.version
  (:require [clojure.string :as string]))

(defn parse-version
  "Parses a version string like \"2.0.576\" or \"2.0.576-3182-foo\" into [major minor patch].
   Extracts leading digits from each segment to handle prerelease suffixes."
  [v]
  (when v
    (let [parse-segment (fn [s] (some-> (re-find #"^\d+" s) js/parseInt))
          parts (string/split v #"\.")]
      (mapv parse-segment (take 3 parts)))))

(defn version>=
  "Returns true if version-a >= version-b, given parsed [major minor patch] vectors."
  [version-a version-b]
  (and (some? version-a)
       (some? version-b)
       (let [[a-major a-minor a-patch] version-a
             [b-major b-minor b-patch] version-b]
         (or (> a-major b-major)
             (and (= a-major b-major)
                  (or (> a-minor b-minor)
                      (and (= a-minor b-minor)
                           (>= a-patch b-patch))))))))
