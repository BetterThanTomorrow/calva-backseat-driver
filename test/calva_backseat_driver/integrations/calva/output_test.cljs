(ns calva-backseat-driver.integrations.calva.output-test
  (:require ["fs" :as fs]
            ["path" :as path]
            [cljs.test :refer [deftest testing is]]
            [cognitect.transit :as transit]
            [calva-backseat-driver.integrations.calva.output :as output]))

(defn- code
  "An evaluated code entity at `line`."
  [line]
  {:output/line line
   :output/category "evaluatedCode"
   :output/text (str "(inc " line ")")
   :output/timestamp (* 1000 line)})

(defn- log-of
  "A log with an evaluated code entity at each of `lines`."
  [lines]
  (reduce (fn [log line]
            (output/add log (code line) output/history-cap))
          output/empty-log
          lines))

(deftest entity
  (testing "a message from a shadow-cljs evaluation"
    (is (= {:output/line 8
            :output/category "evaluatedCode"
            :output/text "(+ 1 1)"
            :output/timestamp 1795517607133
            :output/who "tester"
            :output/ns "mini.app"
            :output/repl-session-key "shadow-cljs"
            :output/shadow-build ":app"
            :output/shadow-runtime-id 42}
           (output/entity {:category "evaluatedCode"
                           :text "(+ 1 1)"
                           :who "tester"
                           :ns "mini.app"
                           :replSessionKey "shadow-cljs"
                           :shadowBuild ":app"
                           :shadowRuntimeId 42}
                          8
                          1795517607133))))
  (testing "a message with only what every message has"
    (is (= {:output/line 9
            :output/category "otherOutput"
            :output/text "hello"
            :output/timestamp 1795517607133}
           (output/entity {:category "otherOutput" :text "hello"} 9 1795517607133))))
  (testing "shadow-cljs fields that Calva sent as undefined"
    (let [entity (output/entity {:category "evaluatedCode"
                                 :text "(+ 1 1)"
                                 :shadowBuild js/undefined
                                 :shadowRuntimeId js/undefined}
                                10
                                1795517607133)]
      (is (not (contains? entity :output/shadow-build)))
      (is (not (contains? entity :output/shadow-runtime-id)))))
  (testing "a message without a text"
    (let [entity (output/entity {:category "otherOutput" :text nil} 11 1795517607133)]
      (is (= {:output/line 11
              :output/category "otherOutput"
              :output/timestamp 1795517607133}
             entity)
          "The entity has no attribute for it")
      (is (= 1 (output/query (output/add output/empty-log entity 10)
                             "[:find (count ?e) . :where [?e :output/line]]"
                             nil))
          "and a log with it can be queried"))))

(deftest add
  (is (= {1 (code 1)}
         (output/add nil (code 1) 3))
      "A log that isn't there yet starts with the entity")
  (is (= [1 2 3]
         (keys (reduce #(output/add %1 (code %2) 3) output/empty-log [3 1 2])))
      "The log is ordered by line")
  (is (= [2 3 4]
         (keys (output/add (log-of [1 2 3]) (code 4) 3)))
      "Over the cap, the lowest line is dropped")
  (is (= (assoc (code 2) :output/text "again")
         (get (output/add (log-of [1 2 3]) (assoc (code 2) :output/text "again") 3) 2))
      "An entity with a line the log has replaces the one there"))

(deftest restored
  (testing "a saved history"
    (let [{:calva/keys [history output-log output-line-counter]} (output/restored (map code [5 3 4]))]
      (is (= [3 4 5] (keys history))
          "The history has every entity, by line")
      (is (= history output-log)
          "and so has the output log")
      (is (= 5 output-line-counter)
          "Numbering goes on from the highest line")))
  (testing "a saved history with more entities than the output log holds"
    (let [lines (range 1 (+ output/log-cap 501))
          {:calva/keys [history output-log]} (output/restored (map code lines))]
      (is (= (count lines) (count history)))
      (is (= output/log-cap (count output-log))
          "The output log is at its cap")
      (is (= [501 (+ output/log-cap 500)]
             [(key (first output-log)) (key (first (rseq output-log)))])
          "with the newest entities")
      (is (= output/log-cap
             (count (output/add output-log (code (+ output/log-cap 501)) output/log-cap)))
          "and stays there with one more message")))
  (testing "no saved history"
    (is (= {:calva/history {}
            :calva/output-log {}
            :calva/output-line-counter 0}
           (output/restored nil)))))

(deftest query
  (let [log (-> output/empty-log
                (output/add {:output/line 1 :output/text "first" :output/who "tester"} 10)
                (output/add {:output/line 2 :output/text "skip" :output/who "other"} 10)
                (output/add {:output/line 3 :output/text "third" :output/who "tester"} 10))
        rows (output/query log
                           (pr-str '[:find [(pull ?e [*]) ...]
                                     :in $ ?since ?who
                                     :where [?e :output/line ?l]
                                     [(> ?l ?since)]
                                     [?e :output/who ?who]])
                           [1 "tester"])]
    (is (= [{:output/line 3 :output/text "third" :output/who "tester"}]
           (mapv #(select-keys % [:output/line :output/text :output/who]) rows))
        "A query with inputs finds the entities of the log")
    (is (= 3 (output/query log "[:find (count ?e) . :where [?e :output/line]]" nil))
        "A query without inputs")
    (is (nil? (output/query output/empty-log "[:find (count ?e) . :where [?e :output/line]]" nil))
        "A query on an empty log finds nothing")
    (is (thrown? js/Error (output/query log "[:find ?e :where [(nope ?x)]]" nil))
        "A query that fails throws")))

(deftest serialization
  (testing "a round trip"
    (let [history (log-of [1 2])]
      (is (= [(code 1) (code 2)]
             (output/deserialized (output/serialized history)))
          "gives the entities back, by line")
      (is (= []
             (output/deserialized (output/serialized output/empty-log)))
          "and an empty history gives none")))
  (testing "content that isn't a history"
    (is (nil? (output/deserialized "not-transit")))
    (is (nil? (output/deserialized (transit/write (transit/writer :json)
                                                  {:format-version 99 :entities []})))
        "Another format version isn't read")))

(deftest a-history-file-from-the-released-version
  (let [content (fs/readFileSync (path/resolve "test/fixtures/eval-history.transit.json") "utf8")
        entities (output/deserialized content)
        {:calva/keys [history output-line-counter]} (output/restored entities)]
    (is (= [41 43 47] (keys history))
        "Its entities are restored")
    (is (= {:output/line 43
            :output/category "evaluatedCode"
            :output/text "(js/console.log \"hello\")"
            :output/who "ui"
            :output/timestamp 1795517609200
            :output/ns "mini.app"
            :output/repl-session-key "shadow-cljs"
            :output/shadow-build ":app"
            :output/shadow-runtime-id 42}
           (get history 43))
        "with every attribute")
    (is (= 47 output-line-counter))
    (is (= entities
           (output/deserialized (output/serialized history)))
        "A history saved again reads as the same entities")))
