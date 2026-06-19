(ns calva-backseat-driver.integrations.calva.session-runtimes-test
  (:require [cljs.test :refer [deftest testing is]]
            [calva-backseat-driver.integrations.calva.session-runtimes :as session-runtimes]))

(def ^:private sample-runtime-raw
  {:runtimeId 1
   :description "browser tab"
   :buildId "app"
   :host "localhost:9630"
   :lastActivity 100
   :workerId "worker-1"
   :sinceInst 50
   :sinceDescription "connected"})

(def ^:private sample-runtime-projected
  {:runtimeId 1
   :description "browser tab"
   :buildId "app"
   :host "localhost:9630"
   :lastActivity 100})

(def ^:private sample-runtime-null-activity-raw
  (assoc sample-runtime-raw
         :runtimeId 2
         :description "node worker"
         :lastActivity nil))

(def ^:private sample-runtime-null-activity-projected
  (assoc sample-runtime-projected
         :runtimeId 2
         :description "node worker"
         :lastActivity nil))

(def ^:private sample-build-base
  {:buildId "app"
   :isActive true
   :isCurrentlyConnected true})

(deftest select-runtime-fields-test
  (testing "nil runtime returns nil"
    (is (nil? (session-runtimes/select-runtime-fields nil))))
  (testing "drops connection-metadata fields"
    (is (= sample-runtime-projected
           (session-runtimes/select-runtime-fields sample-runtime-raw))))
  (testing "preserves lastActivity null"
    (is (= sample-runtime-null-activity-projected
           (session-runtimes/select-runtime-fields sample-runtime-null-activity-raw)))))

(deftest project-build-compact-test
  (testing "empty runtimes"
    (is (= {:buildId "app"
            :isActive true
            :isCurrentlyConnected true
            :runtimeCount 0
            :mostRecentRuntime nil}
           (session-runtimes/project-build-compact
            (assoc sample-build-base :runtimes [])))))
  (testing "single runtime"
    (is (= {:buildId "app"
            :isActive true
            :isCurrentlyConnected true
            :runtimeCount 1
            :mostRecentRuntime sample-runtime-projected}
           (session-runtimes/project-build-compact
            (assoc sample-build-base :runtimes [sample-runtime-raw])))))
  (testing "multiple runtimes — mostRecentRuntime is first element"
    (let [second-runtime (assoc sample-runtime-raw :runtimeId 3 :description "second tab")
          build (assoc sample-build-base
                       :runtimes [sample-runtime-raw second-runtime])
          result (session-runtimes/project-build-compact build)]
      (is (= 2 (:runtimeCount result)))
      (is (= sample-runtime-projected (:mostRecentRuntime result)))))
  (testing "does not include :runtimes"
    (is (nil? (:runtimes (session-runtimes/project-build-compact
                          (assoc sample-build-base :runtimes [sample-runtime-raw])))))))

(deftest project-build-full-test
  (testing "includes projected runtimes in input order"
    (let [second-runtime (assoc sample-runtime-raw :runtimeId 3 :description "second tab")
          build (assoc sample-build-base
                       :runtimes [sample-runtime-raw second-runtime])
          result (session-runtimes/project-build-full build)]
      (is (= 2 (:runtimeCount result)))
      (is (= sample-runtime-projected (:mostRecentRuntime result)))
      (is (= [sample-runtime-projected
              (assoc sample-runtime-projected :runtimeId 3 :description "second tab")]
             (:runtimes result))))))

(deftest project-build-test
  (testing "includeAllRuntimes false — compact"
    (let [build (assoc sample-build-base :runtimes [sample-runtime-raw])
          result (session-runtimes/project-build build false)]
      (is (= 1 (:runtimeCount result)))
      (is (nil? (:runtimes result)))))
  (testing "includeAllRuntimes true — full"
    (let [build (assoc sample-build-base :runtimes [sample-runtime-raw])
          result (session-runtimes/project-build build true)]
      (is (= 1 (:runtimeCount result)))
      (is (= [sample-runtime-projected] (:runtimes result))))))

(deftest project-session-test
  (testing "supportsRuntimes true — projects builds"
    (let [session {:replSessionKey "cljs"
                   :supportsRuntimes true
                   :builds [(assoc sample-build-base :runtimes [sample-runtime-raw])]}
          result (session-runtimes/project-session session false)]
      (is (= "cljs" (:replSessionKey result)))
      (is (= 1 (count (:builds result))))
      (is (= 1 (:runtimeCount (first (:builds result)))))
      (is (nil? (:runtimes (first (:builds result)))))))
  (testing "supportsRuntimes true — full runtimes when requested"
    (let [session {:supportsRuntimes true
                   :builds [(assoc sample-build-base :runtimes [sample-runtime-raw])]}
          result (session-runtimes/project-session session true)]
      (is (= [sample-runtime-projected]
             (:runtimes (first (:builds result)))))))
  (testing "supportsRuntimes false — omits builds"
    (let [session {:replSessionKey "clj"
                   :supportsRuntimes false
                   :builds [(assoc sample-build-base :runtimes [sample-runtime-raw])]}
          result (session-runtimes/project-session session false)]
      (is (= "clj" (:replSessionKey result)))
      (is (nil? (:builds result)))))
  (testing "supportsRuntimes true with nil builds — empty vector"
    (let [session {:supportsRuntimes true :builds nil}
          result (session-runtimes/project-session session false)]
      (is (= [] (:builds result)))))
  (testing "currentRoutedTarget maps to isActiveSession when absent"
    (let [session {:replSessionKey "cljs" :supportsRuntimes false :currentRoutedTarget true}
          result (session-runtimes/project-session session false)]
      (is (= true (:isActiveSession result)))))
  (testing "missing isActiveSession and currentRoutedTarget defaults to false"
    (let [session {:replSessionKey "clj" :supportsRuntimes false}
          result (session-runtimes/project-session session false)]
      (is (= false (:isActiveSession result))))))
