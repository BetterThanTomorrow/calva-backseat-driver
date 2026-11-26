(ns calva-backseat-driver.tools.content
  "A tool's result as content parts: text and image parts, before an entry point
   puts them in its protocol's form."
  (:require
   [clojure.string :as string]
   [clojure.walk :as walk]))

(def ^:private image-url-pattern
  #"data:(image/[^;]+);base64,([A-Za-z0-9+/=\s]+)")

(defn- reduce-images-in-string
  "Replaces the image data URLs in `s` with `<<image-N>>` references, numbering on from
   `image-count`. Returns `{:text … :images […] :image-count …}`. Images past `max-images`
   get an `<<image-N-capped>>` marker and are left out of `:images`."
  [s image-count max-images]
  (reduce (fn [{:keys [text image-count] :as acc} [full-match mime base64]]
            (let [n (inc image-count)]
              (if (<= n max-images)
                (-> acc
                    (assoc :text (string/replace-first text full-match (str "<<image-" n ">>"))
                           :image-count n)
                    (update :images conj {:mime mime
                                          :base64 (string/replace base64 #"\s" "")}))
                (assoc acc
                       :text (string/replace-first text full-match (str "<<image-" n "-capped>>"))
                       :image-count n))))
          {:text s :images [] :image-count image-count}
          (re-seq image-url-pattern s)))

(defn reduce-images
  "Replaces the image data URLs in the strings of `data` with `<<image-N>>` references.
   Returns `{:data … :images [{:mime … :base64 …}]}`, or nil when `data` holds no image.
   Images past `max-images` get an `<<image-N-capped>>` marker and are left out of `:images`."
  [data max-images]
  (let [!images (volatile! [])
        !image-count (volatile! 0)
        reduced (walk/postwalk
                 (fn [x]
                   (if (and (string? x) (string/includes? x "data:image/"))
                     (let [{:keys [text images image-count]} (reduce-images-in-string x @!image-count max-images)]
                       (vswap! !images into images)
                       (vreset! !image-count image-count)
                       text)
                     x))
                 data)]
    (when (pos? @!image-count)
      {:data reduced
       :images @!images})))

(defn- text-part
  "Returns the text part for `data` as JSON. A tool's JavaScript result that is undefined
   gives a part without text."
  [data]
  (cond-> {:type "text"}
    (not (undefined? data)) (assoc :text (js/JSON.stringify (clj->js data)))))

(defn parts
  "Returns the content parts for a tool's result `data`: one text part with the data as
   JSON. With `max-images`, the images in the data follow as image parts, and the text
   refers to them."
  ([data]
   [(text-part data)])
  ([data max-images]
   (if-let [{:keys [data images]} (reduce-images data max-images)]
     (into [(text-part data)]
           (map (fn [{:keys [mime base64]}]
                  {:type "image" :mimeType mime :data base64}))
           images)
     [(text-part data)])))
