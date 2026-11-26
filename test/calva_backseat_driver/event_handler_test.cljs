(ns calva-backseat-driver.event-handler-test
  (:require [cljs.test :refer [deftest is async]]
            [calva-backseat-driver.event-handler :as event-handler]))

(defn- test-system
  "A system on its own atom, with `performed` collecting the effects that ran."
  [action-table initial-state]
  (let [!state (atom initial-state)
        !performed (atom [])
        record! (fn [effect-name]
                  (fn [_dispatch! & args]
                    (swap! !performed conj (into [effect-name] args))
                    nil))
        !system (atom nil)
        dispatch! (fn [actions]
                    (event-handler/dispatch!+ @!system actions))]
    (reset! !system
            {:uf/!state !state
             :uf/action-table action-table
             :uf/effect-table {:test/fx.note (record! :test/fx.note)
                               :test/fx.double (fn [_dispatch! n]
                                                 (swap! !performed conj [:test/fx.double n])
                                                 (js/Promise.resolve (* 2 n)))
                               :test/fx.later (fn [_dispatch! ms v]
                                                (js/Promise. (fn [resolve _reject]
                                                               (js/setTimeout #(resolve v) ms))))
                               :test/fx.reject (fn [_dispatch! message]
                                                 (js/Promise.reject (js/Error. message)))
                               :test/fx.dispatch (fn [dispatch! actions]
                                                   (dispatch! actions))}
             :uf/dispatch! dispatch!})
    {:dispatch! dispatch!
     :!state !state
     :!performed !performed
     :!system !system}))

(def ^:private test-actions
  {:test/ax.assoc (fn [state _uf-data k v]
                    {:uf/db (assoc state k v)})
   :test/ax.note (fn [_state _uf-data v]
                   {:uf/fxs [[:test/fx.note v]]})
   :test/ax.now (fn [state {:system/keys [now]}]
                  {:uf/db (assoc state :now now)})
   :test/ax.recipe (fn [_state _uf-data n]
                     {:uf/fxs [[:uf/await :test/fx.double n]
                               [:test/fx.note :uf/prev-result]
                               [:test/fx.note {:nested :uf/prev-result}]]
                      :uf/dxs [[:test/ax.assoc :result :uf/prev-result]]})
   :test/ax.slow (fn [_state _uf-data ms v]
                   {:uf/fxs [[:uf/await :test/fx.later ms v]]})
   :test/ax.throw (fn [_state _uf-data message]
                    (throw (js/Error. message)))
   :test/ax.reject (fn [_state _uf-data message]
                     {:uf/fxs [[:uf/await :test/fx.reject message]]})
   :test/ax.unknown-effect (fn [_state _uf-data]
                             {:uf/fxs [[:test/fx.nope]]})
   :test/ax.effect-among-actions (fn [_state _uf-data]
                                   {:uf/dxs [[:test/fx.note 1]]})
   :test/ax.wrong-keys (fn [_state _uf-data]
                         {:fxs [[:test/fx.note 1]]})
   :test/ax.order (fn [state _uf-data]
                    {:uf/db (assoc state :order :handled)
                     :uf/fxs [[:test/fx.note :effect]]
                     :uf/dxs [[:test/ax.note :deferred]]})})

(defn- outcome+
  "Returns a promise of `[:resolved value]` or `[:rejected message]` for `promise`."
  [promise]
  (-> promise
      (.then (fn [v] [:resolved v]))
      (.catch (fn [e] [:rejected (ex-message e)]))))

(deftest merge-tables
  (is (= {:a 1 :b 2} (event-handler/merge-tables [{:a 1} {:b 2}]))
      "The rows of every table")
  (is (thrown-with-msg? js/Error #"The name :a is in two tables"
                        (event-handler/merge-tables [{:a 1} {:a 2}]))
      "A name in two tables throws"))

(deftest handle-actions
  (is (= {:uf/db {:a 1 :b 2}
          :uf/fxs [[:test/fx.note :x]]
          :uf/dxs []
          :uf/awaiting []}
         (event-handler/handle-actions test-actions {} {}
                                       [[:test/ax.assoc :a 1]
                                        nil
                                        [:test/ax.note :x]
                                        [:test/ax.assoc :b 2]]))
      "Each action sees the state the one before it returned, and effects accumulate")
  (is (thrown-with-msg? js/Error #"Unknown action: :test/ax.nope"
                        (event-handler/handle-actions test-actions {} {} [[:test/ax.nope]]))
      "An unknown action throws in a development build")
  (is (thrown-with-msg? js/Error #"returned unknown keys: \(:fxs\)"
                        (event-handler/handle-actions test-actions {} {} [[:test/ax.wrong-keys]]))
      "A handler that returns a key the loop doesn't know throws"))

(deftest dispatch-state-effects-and-deferred-actions
  (async done
    (let [{:keys [dispatch! !state !performed]} (test-system test-actions {})]
      (-> (dispatch! [[:test/ax.order] [:test/ax.now]])
          (.then (fn [_]
                   (is (= :handled (:order @!state))
                       "The state is committed")
                   (is (number? (:now @!state))
                       "Handlers get the time in uf-data")
                   (is (= [[:test/fx.note :effect] [:test/fx.note :deferred]] @!performed)
                       "Effects run before the deferred actions")))
          (.finally done)))))

(deftest awaited-results-are-threaded
  (async done
    (let [{:keys [dispatch! !state !performed]} (test-system test-actions {})]
      (-> (dispatch! [[:test/ax.recipe 21]])
          (.then (fn [result]
                   (is (= 42 result)
                       "The dispatch resolves to the chain's last awaited result")
                   (is (= [[:test/fx.double 21]
                           [:test/fx.note 42]
                           [:test/fx.note {:nested :uf/prev-result}]]
                          @!performed)
                       "The awaited result replaces :uf/prev-result as an element, and nothing deeper")
                   (is (= 42 (:result @!state))
                       "and in the deferred actions")))
          (.finally done)))))

(deftest a-dispatch-never-throws
  (async done
    (let [{:keys [dispatch! !state]} (test-system test-actions {})]
      (-> (js/Promise.all
           #js [(outcome+ (dispatch! [[:test/ax.throw "the action threw"]]))
                (outcome+ (dispatch! [[:test/ax.reject "the effect rejected"]]))
                (outcome+ (dispatch! [[:test/ax.nope]]))
                (outcome+ (dispatch! [[:test/ax.unknown-effect]]))
                (outcome+ (dispatch! [[:test/ax.effect-among-actions]]))
                (outcome+ (dispatch! [[:test/ax.assoc :a 1] [:test/ax.throw "the second action threw"]]))])
          (.then (fn [outcomes]
                   (is (= [[:rejected "the action threw"]
                           [:rejected "the effect rejected"]
                           [:rejected "Unknown action: :test/ax.nope"]
                           [:rejected "Unknown effect: :test/fx.nope"]
                           [:rejected "Unknown action: :test/fx.note"]
                           [:rejected "the second action threw"]]
                          (vec outcomes))
                       "Each failure rejects its own dispatch")
                   (is (= {} @!state)
                       "A batch with an action that throws changes no state")))
          (.finally done)))))

(deftest two-chains-in-one-tick
  (async done
    (let [{:keys [dispatch!]} (test-system test-actions {})]
      (-> (js/Promise.all
           #js [(outcome+ (dispatch! [[:test/ax.slow 5 :first]]))
                (outcome+ (dispatch! [[:test/ax.reject "the second chain failed"]]))
                (outcome+ (dispatch! [[:test/ax.throw "the third chain threw"]]))
                (outcome+ (dispatch! [[:test/ax.recipe 4]]))])
          (.then (fn [outcomes]
                   (is (= [[:resolved :first]
                           [:rejected "the second chain failed"]
                           [:rejected "the third chain threw"]
                           [:resolved 8]]
                          (vec outcomes))
                       "Each chain gets its own result, also when another one fails in the same tick")))
          (.finally done)))))

(deftest two-awaiting-actions-in-one-batch
  (async done
    (let [{:keys [dispatch!]} (test-system test-actions {})]
      (-> (outcome+ (dispatch! [[:test/ax.recipe 1] [:test/ax.recipe 2]]))
          (.then (fn [[kind message]]
                   (is (= :rejected kind))
                   (is (re-find #"Two actions of one batch await effects" message)
                       "The loop refuses a batch whose awaited results would mix")))
          (.finally done)))))

(deftest state-effects
  (async done
    (let [{:keys [dispatch! !performed !system]} (test-system test-actions {:a 0})]
      (swap! !system assoc :uf/state-effects
             (fn [old-state new-state]
               (when (not= (:a old-state) (:a new-state))
                 [[:test/fx.note [:a-changed (:a new-state)]]])))
      (-> (dispatch! [[:test/ax.assoc :a 1] [:test/ax.note :own-effect]])
          (.then (fn [_]
                   (dispatch! [[:test/ax.assoc :b 1]])))
          (.then (fn [_]
                   (is (= [[:test/fx.note [:a-changed 1]]
                           [:test/fx.note :own-effect]]
                          @!performed)
                       "The effects of a state change run before the dispatch's own, and only on a change")))
          (.finally done)))))
