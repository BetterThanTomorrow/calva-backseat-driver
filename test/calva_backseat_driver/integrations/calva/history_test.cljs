(ns calva-backseat-driver.integrations.calva.history-test
  (:require [cljs.test :refer [deftest testing is use-fixtures]]
            [cognitect.transit :as transit]
            [datascript.core :as d]
            [calva-backseat-driver.app.db :as db]
            [calva-backseat-driver.integrations.calva.axs :as calva-axs]))

(use-fixtures :each
  {:before (fn []
             (d/reset-conn! db/!history-conn (d/empty-db (:schema @db/!history-conn))))
   :after  (fn []
             (d/reset-conn! db/!history-conn (d/empty-db (:schema @db/!history-conn))))})

(deftest serialize-deserialize-round-trip
  (testing "Round-trip with entities"
    (let [entities [{:output/line 1
                     :output/category "evaluatedCode"
                     :output/text "(+ 1 2)"
                     :output/who "tester"
                     :output/ns "user"
                     :output/repl-session-key "cljs"
                     :output/timestamp 1000}
                    {:output/line 2
                     :output/category "evaluatedCode"
                     :output/text "(+ 3 4)"
                     :output/timestamp 2000}]]
      (d/transact! db/!history-conn entities)
      (let [transit-str (db/serialize-history db/!history-conn)
            result (db/deserialize-history transit-str)]
        (is (= 2 (count result)))
        (is (every? #(contains? % :output/line) result))
        (is (every? #(contains? % :output/text) result))
        (is (not-any? #(contains? % :db/id) result)
            "Serialized entities should not contain :db/id"))))
  (testing "Round-trip with empty conn"
    (d/reset-conn! db/!history-conn (d/empty-db (:schema @db/!history-conn)))
    (let [transit-str (db/serialize-history db/!history-conn)
          result (db/deserialize-history transit-str)]
      (is (empty? result))))
  (testing "Deserialize invalid string returns nil"
    (is (nil? (db/deserialize-history "not-transit"))))
  (testing "Deserialize wrong format version returns nil"
    (let [w (transit/writer :json)
          bad-data (transit/write w {:format-version 99 :entities []})]
      (is (nil? (db/deserialize-history bad-data))))))

(deftest history-loaded-action
  (testing "Sets output-line-counter from loaded max-line"
    (let [state {:calva/output-line-counter 0}
          result (calva-axs/handle-action state nil [:calva/ax.history-loaded 42])]
      (is (= 42 (:calva/output-line-counter (:ex/db result))))))
  (testing "Sets counter to 0 when no history"
    (let [state {:calva/output-line-counter 5}
          result (calva-axs/handle-action state nil [:calva/ax.history-loaded 0])]
      (is (= 0 (:calva/output-line-counter (:ex/db result)))))))
