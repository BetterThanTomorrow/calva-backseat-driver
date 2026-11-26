(ns calva-backseat-driver.integrations.calva.history-test
  (:require [cljs.test :refer [deftest testing is use-fixtures]]
            [cognitect.transit :as transit]
            [datascript.core :as d]
            [calva-backseat-driver.app.db :as db]
            [calva-backseat-driver.event-handler :as event-handler]
            [calva-backseat-driver.integrations.calva.axs :as calva-axs]))

(def ^:private now 1795517607133)

(defn- handle
  "Returns what the Calva handlers give for `action` on `state`."
  [state action]
  (event-handler/handle-actions calva-axs/actions state {:system/now now} [action]))

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
                     :output/shadow-build ":app"
                     :output/shadow-runtime-id 7
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
        (is (= ":app" (:output/shadow-build (first result))))
        (is (= 7 (:output/shadow-runtime-id (first result))))
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

(deftest add-output-shadow-fields
  (testing "Persists shadow build and runtime from Calva output messages"
    (let [result (handle
                  {:calva/output-line-counter 0}
                  [:calva/ax.add-output
                   {:category "evaluatedCode"
                    :text "(+ 1 1)"
                    :who "tester"
                    :ns "mini.app"
                    :replSessionKey "shadow-cljs"
                    :shadowBuild ":app"
                    :shadowRuntimeId 42}])
          entity (get-in result [:uf/fxs 0 1])]
      (is (= ":app" (:output/shadow-build entity)))
      (is (= 42 (:output/shadow-runtime-id entity))))))

(deftest add-output-omits-absent-shadow-fields
  (testing "No shadow keys on entity when Calva message omits them"
    (let [result (handle
                  {:calva/output-line-counter 0}
                  [:calva/ax.add-output
                   {:category "evaluatedCode"
                    :text "(+ 1 1)"
                    :who "tester"
                    :ns "user"
                    :replSessionKey "clj"}])
          entity (get-in result [:uf/fxs 0 1])]
      (is (not (contains? entity :output/shadow-build)))
      (is (not (contains? entity :output/shadow-runtime-id))))))

(deftest add-output-omits-undefined-shadow-fields
  (testing "No shadow keys when Calva sent undefined values"
    (let [result (handle
                  {:calva/output-line-counter 0}
                  [:calva/ax.add-output
                   {:category "evaluatedCode"
                    :text "(+ 1 1)"
                    :who "tester"
                    :replSessionKey "clj"
                    :shadowBuild js/undefined
                    :shadowRuntimeId js/undefined}])
          entity (get-in result [:uf/fxs 0 1])]
      (is (not (contains? entity :output/shadow-build)))
      (is (not (contains? entity :output/shadow-runtime-id))))))

(deftest history-loaded-action
  (testing "Sets output-line-counter from loaded max-line"
    (let [state {:calva/output-line-counter 0}
          result (handle state [:calva/ax.history-loaded 42])]
      (is (= 42 (:calva/output-line-counter (:uf/db result))))))
  (testing "Sets counter to 0 when no history"
    (let [state {:calva/output-line-counter 5}
          result (handle state [:calva/ax.history-loaded 0])]
      (is (= 0 (:calva/output-line-counter (:uf/db result)))))))

(deftest query-output-returns-pulled-rows-filtered-by-since-and-who
  (testing "Supported output-log query via :calva/ax.query-output"
    (let [local-conn (d/create-conn {:output/line {:db/unique :db.unique/identity}})
          query '[:find [(pull ?e [*]) ...]
                  :in $ ?since ?who
                  :where [?e :output/line ?l]
                  [(> ?l ?since)]
                  [?e :output/who ?who]]
          entities [{:output/line 1
                     :output/text "first"
                     :output/who "tester"}
                    {:output/line 2
                     :output/text "skip"
                     :output/who "other"}
                    {:output/line 3
                     :output/text "third"
                     :output/who "tester"}]]
      (d/transact! local-conn entities)
      (with-redefs [db/!output-conn local-conn]
        (let [returned-rows (-> (handle
                                 {}
                                 [:calva/ax.query-output (pr-str query) [1 "tester"]])
                                :uf/fxs
                                first
                                (nth 2))]
          (is (= [{:output/line 3
                   :output/text "third"
                   :output/who "tester"}]
                 (mapv #(select-keys % [:output/line :output/text :output/who]) returned-rows)))
          (is (every? #(> (:output/line %) 1) returned-rows))
          (is (every? #(= "tester" (:output/who %)) returned-rows)))))))
