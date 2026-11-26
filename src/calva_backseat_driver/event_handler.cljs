(ns calva-backseat-driver.event-handler
  "The loop. Actions are vectors, `[action-name & args]`, handled by the vars in an
   action table: `(handler state uf-data & args)` returns a map with any of `:uf/db`
   (the next state), `:uf/fxs` (effects to perform, in order) and `:uf/dxs` (actions to
   dispatch after the effects). Effects are vectors too, performed by the vars in an
   effect table: `(effect dispatch! & args)`.

   An effect written as `[:uf/await effect-name & args]` is awaited before the next one
   runs, and its result replaces `:uf/prev-result` where that is an element of a later
   effect vector or of a deferred action.")

(defn merge-tables
  "Returns one table with the rows of `tables`. Throws when a name is in two of them."
  [tables]
  (reduce (fn [merged table]
            (when-let [duplicate (some (set (keys merged)) (keys table))]
              (throw (ex-info (str "The name " duplicate " is in two tables")
                              {:uf/name duplicate})))
            (merge merged table))
          {}
          tables))

(defn- fail!
  "Throws in a development build. Logs the error in a release build, and returns nil."
  [message data]
  (if js/goog.DEBUG
    (throw (ex-info message data))
    (js/console.error message (pr-str data))))

(defn- await-fx? [fx]
  (= :uf/await (first fx)))

(defn- effect-name [fx]
  (if (await-fx? fx)
    (second fx)
    (first fx)))

(defn- known
  "Returns the `vectors` whose name, by `name-of`, is in `table`. Fails on the others."
  [table kind name-of vectors]
  (filterv (fn [v]
             (or (contains? table (name-of v))
                 (fail! (str "Unknown " kind ": " (pr-str (name-of v)))
                        {:uf/name (name-of v)})))
           vectors))

(def ^:private result-keys #{:uf/db :uf/fxs :uf/dxs})

(defn- handle-action
  [action-table state uf-data [action-name & args]]
  (if-let [handler (get action-table action-name)]
    (let [result (apply handler state uf-data args)]
      (when-let [unknown-keys (seq (remove result-keys (keys result)))]
        (fail! (str "The handler of " action-name " returned unknown keys: " (pr-str unknown-keys))
               {:uf/name action-name}))
      result)
    (fail! (str "Unknown action: " (pr-str action-name))
           {:uf/name action-name})))

(defn handle-actions
  "Handles `actions` in order with the handlers in `action-table`, each seeing the state
   the one before it returned. Returns `{:uf/db state :uf/fxs […] :uf/dxs […]}`."
  [action-table state uf-data actions]
  (reduce (fn [{state :uf/db :as acc} action]
            (let [{:uf/keys [db fxs dxs]} (handle-action action-table state uf-data action)
                  fxs (remove nil? fxs)]
              (cond-> acc
                db (assoc :uf/db db)
                (seq fxs) (update :uf/fxs into fxs)
                (some await-fx? fxs) (update :uf/awaiting conj (first action))
                dxs (update :uf/dxs into (remove nil?) dxs))))
          {:uf/db state
           :uf/fxs []
           :uf/dxs []
           :uf/awaiting []}
          (remove nil? actions)))

(defn- replace-prev-result
  "Returns `v` with `prev` in the place of each `:uf/prev-result` element."
  [v prev]
  (mapv (fn [x]
          (if (= :uf/prev-result x)
            prev
            x))
        v))

(defn- thenable? [x]
  (and (some? x)
       (fn? (unchecked-get x "then"))))

(defn- then
  "Calls `f` with `x`, or with what `x` resolves to when it is a promise."
  [x f]
  (if (instance? js/Promise x)
    (.then x f)
    (f x)))

(defn- perform-effect!
  [effect-table dispatch! [effect-name & args]]
  (apply (get effect-table effect-name) dispatch! args))

(defn- log-effect-failure [fx e]
  (js/console.error "Effect failed:" (pr-str (first fx)) e))

(defn- perform-and-forget!
  "Performs `fx` without waiting for it. A throw or a rejection is logged."
  [effect-table dispatch! fx]
  (try
    (let [result (perform-effect! effect-table dispatch! fx)]
      (when (thenable? result)
        (.catch (js/Promise.resolve result)
                (fn [e]
                  (log-effect-failure fx e)))))
    (catch :default e
      (log-effect-failure fx e))))

(defn- execute-effects
  "Performs `fxs` in order. Returns the last awaited result, starting from `prev`, as a
   value when no effect was awaited and as a promise otherwise."
  [effect-table dispatch! fxs prev]
  (let [[not-awaited [awaited & remaining]] (split-with (complement await-fx?) fxs)]
    (doseq [fx not-awaited]
      (perform-and-forget! effect-table dispatch! (replace-prev-result fx prev)))
    (if awaited
      (.then (js/Promise.resolve
              (perform-effect! effect-table dispatch! (replace-prev-result (vec (rest awaited)) prev)))
             (fn [result]
               (execute-effects effect-table dispatch! remaining result)))
      prev)))

(defn- check-awaiting!
  "Fails when two actions of one batch returned awaited effects, since the awaited
   results of a batch are threaded as one sequence."
  [awaiting]
  (when (< 1 (count awaiting))
    (fail! (str "Two actions of one batch await effects: " (pr-str awaiting)
                ". Dispatch each on its own.")
           {:uf/awaiting awaiting})))

(defn- dispatch-chain
  [{:uf/keys [!state action-table effect-table dispatch! state-effects log!] :as system} actions prev]
  (let [old-state @!state
        uf-data {:system/now (js/Date.now)}
        {:uf/keys [db fxs dxs awaiting]} (handle-actions action-table old-state uf-data actions)
        _ (check-awaiting! awaiting)
        fxs (known effect-table "effect" effect-name fxs)
        dxs (known action-table "action" first dxs)]
    (when-not (identical? old-state db)
      (reset! !state db))
    (when log!
      (log! {:uf/actions actions
             :uf/fxs fxs
             :uf/dxs dxs
             :uf/state-before old-state
             :uf/state-after db
             :system/now (:system/now uf-data)}))
    (when state-effects
      (doseq [fx (known effect-table "effect" effect-name (state-effects old-state db))]
        (perform-and-forget! effect-table dispatch! fx)))
    (then (execute-effects effect-table dispatch! fxs prev)
          (fn [prev]
            (if (seq dxs)
              (dispatch-chain system (mapv #(replace-prev-result % prev) dxs) prev)
              prev)))))

(defn dispatch!+
  "Dispatches `actions` through `system`, a map with:

   - `:uf/!state`, the state atom
   - `:uf/action-table` and `:uf/effect-table`
   - `:uf/dispatch!`, the function effects get for dispatching
   - `:uf/state-effects`, optional: `(fn [old-state new-state])` returning effects to
     perform right after a state change, before the dispatch's own effects
   - `:uf/log!`, optional: called with what each dispatch handled and returned

   Effects run in order, and then the deferred actions are dispatched. Returns a promise
   of the chain's last awaited result. It never throws: an action or an awaited effect
   that throws rejects the promise."
  [system actions]
  (let [result+ (try
                  (js/Promise.resolve (dispatch-chain system actions nil))
                  (catch :default e
                    (js/Promise.reject e)))]
    (.catch result+ (fn [e]
                      (js/console.error "Dispatch failed:" (pr-str (mapv first actions)) e)))
    result+))
