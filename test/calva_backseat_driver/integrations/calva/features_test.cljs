(ns calva-backseat-driver.integrations.calva.features-test
  (:require [cljs.test :refer [deftest testing is]]
            [calva-backseat-driver.integrations.calva.version :as version]))

(deftest parse-version-test
  (testing "standard version"
    (is (= [2 0 576] (version/parse-version "2.0.576"))))
  (testing "prerelease suffix"
    (is (= [2 0 576] (version/parse-version "2.0.576-3182-load-file-foo"))))
  (testing "single segment"
    (is (= [2] (version/parse-version "2"))))
  (testing "two segments"
    (is (= [2 0] (version/parse-version "2.0"))))
  (testing "nil input"
    (is (nil? (version/parse-version nil))))
  (testing "zero version"
    (is (= [0 0 0] (version/parse-version "0.0.0")))))

(deftest version>=-test
  (testing "equal versions"
    (is (true? (version/version>= [2 0 576] [2 0 576]))))
  (testing "greater patch"
    (is (true? (version/version>= [2 0 577] [2 0 576]))))
  (testing "lesser patch"
    (is (false? (version/version>= [2 0 575] [2 0 576]))))
  (testing "greater minor"
    (is (true? (version/version>= [2 1 0] [2 0 999]))))
  (testing "lesser minor"
    (is (false? (version/version>= [2 0 999] [2 1 0]))))
  (testing "greater major"
    (is (true? (version/version>= [3 0 0] [2 99 999]))))
  (testing "lesser major"
    (is (false? (version/version>= [1 99 999] [2 0 0]))))
  (testing "nil version-a"
    (is (false? (version/version>= nil [2 0 576]))))
  (testing "nil version-b"
    (is (false? (version/version>= [2 0 576] nil))))
  (testing "both nil"
    (is (false? (version/version>= nil nil)))))
