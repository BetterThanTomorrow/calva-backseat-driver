(ns calva-backseat-driver.event-handler-test
  (:require [cljs.test :refer [deftest is]]
            [calva-backseat-driver.event-handler :as event-handler]))

;; The tests run the loop as a development build runs it, also when they are compiled
;; for release. The test of a release build sets this to false while it runs.
(set! event-handler/*development-build?* true)

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

(defn- dispatch-outcome+
  "`outcome+` of dispatching `actions` with `dispatch!`. A plain function, so that the
   dispatch's promise reaches `outcome+`: in an async test, the compiler awaits the call
   to a local function when it binds the argument first."
  [dispatch! actions]
  (outcome+ (dispatch! actions)))

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

(deftest ^:async dispatch-state-effects-and-deferred-actions
  (let [{:keys [dispatch! !state !performed]} (test-system test-actions {})]
    (await (dispatch! [[:test/ax.order] [:test/ax.now]]))
    (is (= :handled (:order @!state))
        "The state is committed")
    (is (number? (:now @!state))
        "Handlers get the time in uf-data")
    (is (= [[:test/fx.note :effect] [:test/fx.note :deferred]] @!performed)
        "Effects run before the deferred actions")))

(deftest ^:async awaited-results-are-threaded
  (let [{:keys [dispatch! !state !performed]} (test-system test-actions {})
        result (await (dispatch! [[:test/ax.recipe 21]]))]
    (is (= 42 result)
        "The dispatch resolves to the chain's last awaited result")
    (is (= [[:test/fx.double 21]
            [:test/fx.note 42]
            [:test/fx.note {:nested :uf/prev-result}]]
           @!performed)
        "The awaited result replaces :uf/prev-result as an element, and nothing deeper")
    (is (= 42 (:result @!state))
        "and in the deferred actions")))

(deftest ^:async a-dispatch-never-throws
  (let [{:keys [dispatch! !state]} (test-system test-actions {})
        outcomes (await (js/Promise.all
                         #js [(dispatch-outcome+ dispatch! [[:test/ax.throw "the action threw"]])
                              (dispatch-outcome+ dispatch! [[:test/ax.reject "the effect rejected"]])
                              (dispatch-outcome+ dispatch! [[:test/ax.nope]])
                              (dispatch-outcome+ dispatch! [[:test/ax.unknown-effect]])
                              (dispatch-outcome+ dispatch! [[:test/ax.effect-among-actions]])
                              (dispatch-outcome+ dispatch! [[:test/ax.assoc :a 1] [:test/ax.throw "the second action threw"]])]))]
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

(deftest ^:async two-chains-in-one-tick
  (let [{:keys [dispatch!]} (test-system test-actions {})
        outcomes (await (js/Promise.all
                         #js [(dispatch-outcome+ dispatch! [[:test/ax.slow 5 :first]])
                              (dispatch-outcome+ dispatch! [[:test/ax.reject "the second chain failed"]])
                              (dispatch-outcome+ dispatch! [[:test/ax.throw "the third chain threw"]])
                              (dispatch-outcome+ dispatch! [[:test/ax.recipe 4]])]))]
    (is (= [[:resolved :first]
            [:rejected "the second chain failed"]
            [:rejected "the third chain threw"]
            [:resolved 8]]
           (vec outcomes))
        "Each chain gets its own result, also when another one fails in the same tick")))

(deftest ^:async two-awaiting-actions-in-one-batch
  (let [{:keys [dispatch!]} (test-system test-actions {})
        [kind message] (await (dispatch-outcome+ dispatch! [[:test/ax.recipe 1] [:test/ax.recipe 2]]))]
    (is (= :rejected kind))
    (is (re-find #"Two actions of one batch await effects" message)
        "The loop refuses a batch whose awaited results would mix")))

(deftest ^:async state-effects
  (let [{:keys [dispatch! !performed !system]} (test-system test-actions {:a 0})]
    (swap! !system assoc :uf/state-effects
           (fn [old-state new-state]
             (when (not= (:a old-state) (:a new-state))
               [[:test/fx.note [:a-changed (:a new-state)]]])))
    (await (dispatch! [[:test/ax.assoc :a 1] [:test/ax.note :own-effect]]))
    (await (dispatch! [[:test/ax.assoc :b 1]]))
    (is (= [[:test/fx.note [:a-changed 1]]
            [:test/fx.note :own-effect]]
           @!performed)
        "The effects of a state change run before the dispatch's own, and only on a change")))

(defn- takes-two
  [state _uf-data a b]
  {:uf/db (assoc state :sum (+ a b))})

(defn- takes-options
  [state _uf-data & [options]]
  {:uf/db (assoc state :options options)})

(defn- takes-one-value
  [_dispatch! v]
  v)

(deftest ^:async argument-counts-are-checked-in-development
  (let [actions (assoc test-actions
                       :test/ax.takes-two #'takes-two
                       :test/ax.takes-options #'takes-options
                       :test/ax.two-to-an-effect (fn [_state _uf-data]
                                                   {:uf/fxs [[:test/fx.takes-one-value 1 2]]})
                       :test/ax.two-to-an-awaited-effect (fn [_state _uf-data]
                                                           {:uf/fxs [[:uf/await :test/fx.takes-one-value 1 2]]}))
        {:keys [dispatch! !state !system]} (test-system actions {})]
    (swap! !system assoc-in [:uf/effect-table :test/fx.takes-one-value] #'takes-one-value)
    (let [outcomes (await (js/Promise.all
                           #js [(dispatch-outcome+ dispatch! [[:test/ax.takes-two 1]])
                                (dispatch-outcome+ dispatch! [[:test/ax.takes-two 1 2 3]])
                                (dispatch-outcome+ dispatch! [[:test/ax.takes-two 1 2]])
                                (dispatch-outcome+ dispatch! [[:test/ax.takes-options]])
                                (dispatch-outcome+ dispatch! [[:test/ax.takes-options {:silent? true}]])
                                (dispatch-outcome+ dispatch! [[:test/ax.two-to-an-effect]])
                                (dispatch-outcome+ dispatch! [[:test/ax.two-to-an-awaited-effect]])]))]
      (is (= [[:rejected "The action :test/ax.takes-two doesn't take 1 arguments: ([state _uf-data a b])"]
              [:rejected "The action :test/ax.takes-two doesn't take 3 arguments: ([state _uf-data a b])"]
              [:resolved nil]
              [:resolved nil]
              [:resolved nil]
              [:rejected "The effect :test/fx.takes-one-value doesn't take 2 arguments: ([_dispatch! v])"]
              [:rejected "The effect :test/fx.takes-one-value doesn't take 2 arguments: ([_dispatch! v])"]]
             (vec outcomes))
          "An action or an effect vector with the wrong number of arguments fails")
      (is (= {:sum 3 :options {:silent? true}} @!state)
          "and the ones with the right number are handled"))))

(deftest ^:async an-effect-that-dispatches
  (let [actions (assoc test-actions
                       :test/ax.outer (fn [state _uf-data]
                                        {:uf/db (assoc state :outer 1)
                                         :uf/fxs [[:test/fx.dispatch [[:test/ax.assoc :nested 1]]]]
                                         :uf/dxs [[:test/ax.assoc :deferred 1]]}))
        {:keys [dispatch! !state]} (test-system actions {})]
    (await (dispatch! [[:test/ax.outer]]))
    (is (= {:outer 1 :nested 1 :deferred 1} @!state)
        "What an effect's own dispatch changed is there after the outer chain's deferred actions")))

(deftest ^:async a-release-build-logs-and-goes-on
  (let [{:keys [dispatch! !state !performed]} (test-system test-actions {})
        console-error js/console.error
        !logged (atom [])]
    (set! event-handler/*development-build?* false)
    (set! js/console.error (fn [& args]
                             (swap! !logged conj (first args))))
    (try
      (let [outcome (await (dispatch-outcome+ dispatch! [[:test/ax.nope]
                                                     [:test/ax.assoc :a 1]
                                                     [:test/ax.unknown-effect]
                                                     [:test/ax.effect-among-actions]
                                                     [:test/ax.note :ran]]))]
        (set! event-handler/*development-build?* true)
        (set! js/console.error console-error)
        (is (= [:resolved nil] outcome)
            "The dispatch resolves")
        (is (= {:a 1} @!state)
            "The actions the table has are handled")
        (is (= [[:test/fx.note :ran]] @!performed)
            "and the effects the table has are performed")
        (is (= ["Unknown action: :test/ax.nope"
                "Unknown effect: :test/fx.nope"
                "Unknown action: :test/fx.note"]
               @!logged)
            "Each unknown name is logged"))
      (finally
        (set! event-handler/*development-build?* true)
        (set! js/console.error console-error)))))
