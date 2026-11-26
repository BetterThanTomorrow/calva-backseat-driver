(ns calva-backseat-driver.app.live-objects
  "The live objects: the JavaScript objects with identity that stay in one map beside the
   app state. Only effects and entry points touch them.")

(def ^:private kinds
  "The kinds of key. A key is a kind, or a vector that starts with one."
  #{:vscode/extension-context
    :extension/disposables
    :timer})

(defonce ^:private !live-objects (atom {}))

(defn- checked
  "Returns `k`. In a development build, throws when `k` isn't of a known kind."
  [k]
  (when (and js/goog.DEBUG
             (not (contains? kinds (if (vector? k) (first k) k))))
    (throw (ex-info (str "Unknown kind of live object: " (pr-str k)) {:live-object/key k})))
  k)

(defn put!
  "Keeps `object` under `k`, and returns it."
  [k object]
  (swap! !live-objects assoc (checked k) object)
  object)

(defn object
  "Returns the live object under `k`, or nil when there is none."
  [k]
  (get @!live-objects (checked k)))

(defn remove!
  "Forgets the live object under `k`, and returns it."
  [k]
  (let [found (object k)]
    (swap! !live-objects dissoc k)
    found))

(defn extension-context
  "Returns the extension context."
  ^js []
  (object :vscode/extension-context))

(defn add-disposables!
  "Keeps `disposables` for disposal when the extension cleans up, and registers them
   with the extension context."
  [disposables]
  (let [^js context (extension-context)]
    (doseq [disposable disposables]
      (.push (.-subscriptions context) disposable))
    (swap! !live-objects update :extension/disposables (fnil into []) disposables)))

(defn dispose-all!
  "Disposes the kept disposables."
  []
  (doseq [^js disposable (remove! :extension/disposables)]
    (.dispose disposable)))
