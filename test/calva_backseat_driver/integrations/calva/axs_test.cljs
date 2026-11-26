(ns calva-backseat-driver.integrations.calva.axs-test
  (:require [cljs.test :refer [deftest testing is]]
            [calva-backseat-driver.app.db :as db]
            [calva-backseat-driver.event-handler :as event-handler]
            [calva-backseat-driver.integrations.calva.axs :as calva-axs]
            [calva-backseat-driver.integrations.calva.output :as output]))

(def ^:private now 1795517607133)

(defn- handle
  "Returns what the Calva handlers give for `action` on `state`."
  [state action]
  (event-handler/handle-actions calva-axs/actions state {:system/now now} [action]))

(def ^:private state
  (assoc db/init-db
         :calva/output-line-counter 7
         :calva/history-storage-uri "file:///storage/eval-history.transit.json"))

(deftest add-output
  (testing "evaluated code"
    (let [entity {:output/line 8
                  :output/category "evaluatedCode"
                  :output/text "(+ 1 1)"
                  :output/timestamp now
                  :output/who "tester"
                  :output/ns "user"
                  :output/repl-session-key "clj"}
          result (handle state [:calva/ax.add-output {:category "evaluatedCode"
                                                      :text "(+ 1 1)"
                                                      :who "tester"
                                                      :ns "user"
                                                      :replSessionKey "clj"}])]
      (is (= (assoc state
                    :calva/output-line-counter 8
                    :calva/output-log {8 entity}
                    :calva/history {8 entity})
             (:uf/db result))
          "It gets the next line and the time of the dispatch, and is in the output log and in the history")
      (is (= [[:uf/fx.defer-dispatch [[:calva/ax.save-history]] 500 :calva/history-save]]
             (:uf/fxs result))
          "The history is saved half a second later, unless more code comes before that")))
  (testing "other output"
    (let [result (handle state [:calva/ax.add-output {:category "evaluationResults" :text "2"}])]
      (is (= {8 {:output/line 8
                 :output/category "evaluationResults"
                 :output/text "2"
                 :output/timestamp now}}
             (:calva/output-log (:uf/db result)))
          "It is in the output log")
      (is (= {} (:calva/history (:uf/db result)))
          "and not in the history")
      (is (empty? (:uf/fxs result))
          "so nothing is saved")))
  (testing "a full output log"
    (let [full (reduce (fn [log line]
                         (output/add log {:output/line line} output/log-cap))
                       output/empty-log
                       (range 1 (inc output/log-cap)))
          result (handle (assoc state
                                :calva/output-line-counter output/log-cap
                                :calva/output-log full)
                         [:calva/ax.add-output {:category "otherOutput" :text "one more"}])
          log (:calva/output-log (:uf/db result))]
      (is (= output/log-cap (count log))
          "stays at its cap")
      (is (= [2 (inc output/log-cap)] [(key (first log)) (key (first (rseq log)))])
          "and loses its oldest entity"))))

(deftest save-history
  (let [history (sorted-map 3 {:output/line 3 :output/category "evaluatedCode"})]
    (is (= [[:calva/fx.save-history "file:///storage/eval-history.transit.json" history]]
           (:uf/fxs (handle (assoc state :calva/history history) [:calva/ax.save-history]))))))

(deftest init-history
  (let [result (handle state [:calva/ax.init-history])]
    (is (= [[:uf/await :calva/fx.read-history "file:///storage/eval-history.transit.json"]]
           (:uf/fxs result)))
    (is (= [[:calva/ax.history-read :uf/prev-result]]
           (:uf/dxs result)))))

(deftest history-read
  (let [entities [{:output/line 41 :output/category "evaluatedCode" :output/text "(+ 1 2)"}
                  {:output/line 47 :output/category "evaluatedCode" :output/text "(def answer 42)"}]]
    (testing "at activation"
      (let [restored (:uf/db (handle db/init-db [:calva/ax.history-read entities]))]
        (is (= [41 47] (keys (:calva/history restored)))
            "The history has the saved entities")
        (is (= (:calva/history restored) (:calva/output-log restored))
            "and so has the output log")
        (is (= 47 (:calva/output-line-counter restored))
            "Numbering goes on from the highest line")))
    (testing "with no saved history"
      (is (= db/init-db
             (:uf/db (handle db/init-db [:calva/ax.history-read nil])))))
    (testing "in a state that has taken output"
      (is (= state
             (:uf/db (handle state [:calva/ax.history-read entities])))
          "The state keeps what it has"))))

(deftest when-activated
  (let [result (handle state [:calva/ax.when-activated [[:calva/ax.subscribe-to-output]]])]
    (is (= [[:uf/await :calva/fx.calva-activated]] (:uf/fxs result)))
    (is (= [[:calva/ax.subscribe-to-output]] (:uf/dxs result))
        "The actions are dispatched once Calva is active")))

(deftest sessions
  (is (= [[:mcp/ax.update-registry]]
         (:uf/dxs (handle state [:calva/ax.sessions-changed {:reason "connected"}])))
      "A change of Calva's sessions refreshes the registry entry")
  (let [result (handle state [:calva/ax.subscribe-to-sessions-changed])]
    (is (= [[:calva/fx.subscribe-to-sessions-changed]] (:uf/fxs result)))
    (is (= [[:mcp/ax.update-registry]] (:uf/dxs result))
        "and so does subscribing to the changes")))
