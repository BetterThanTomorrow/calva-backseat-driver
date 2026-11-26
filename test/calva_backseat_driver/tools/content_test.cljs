(ns calva-backseat-driver.tools.content-test
  (:require [cljs.test :refer [deftest is testing]]
            [calva-backseat-driver.tools.content :as content]))

(def ^:private red-dot "data:image/png;base64,iVBORw0KGgo=")
(def ^:private blue-dot "data:image/jpeg;base64,/9j/4AAQ\nSkZJRg==")

(deftest reduce-images
  (testing "data without images"
    (is (nil? (content/reduce-images {:result "42"} 10))
        "gives nil"))
  (testing "images in nested data"
    (is (= {:data {:result "a <<image-1>>, b"
                   :more ["<<image-2>>"]}
            :images [{:mime "image/png" :base64 "iVBORw0KGgo="}
                     {:mime "image/jpeg" :base64 "/9j/4AAQSkZJRg=="}]}
           (content/reduce-images {:result (str "a " red-dot ", b")
                                   :more [blue-dot]}
                                  10))
        "are replaced by references, in order, with whitespace removed from the base64"))
  (testing "images past the cap"
    (is (= {:data ["<<image-1>>" "<<image-2-capped>>"]
            :images [{:mime "image/png" :base64 "iVBORw0KGgo="}]}
           (content/reduce-images [red-dot blue-dot] 1))
        "get a capped marker and are left out")
    (is (= {:data ["<<image-1-capped>>"]
            :images []}
           (content/reduce-images [red-dot] 0))
        "all of them, with a cap of 0")))

(deftest parts
  (testing "without a cap"
    (is (= [{:type "text" :text "{\"result\":\"data:image/png;base64,iVBORw0KGgo=\"}"}]
           (content/parts {:result red-dot}))
        "the data is one text part, images and all"))
  (testing "with a cap"
    (is (= [{:type "text" :text "{\"result\":\"<<image-1>>\"}"}
            {:type "image" :mimeType "image/png" :data "iVBORw0KGgo="}]
           (content/parts {:result red-dot} 10))
        "the images follow as image parts")
    (is (= [{:type "text" :text "{\"result\":\"42\"}"}]
           (content/parts {:result "42"} 10))
        "data without images is one text part"))
  (testing "a JavaScript value"
    (is (= [{:type "text" :text "{\"note\":\"ok\"}"}]
           (content/parts #js {:note "ok"}))
        "is serialized as it is")
    (is (= [{:type "text" :text "null"}]
           (content/parts nil))
        "nil is null")
    (is (= [{:type "text"}]
           (content/parts js/undefined))
        "undefined gives a part without text")))
