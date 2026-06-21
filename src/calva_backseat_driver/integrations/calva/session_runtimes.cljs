(ns calva-backseat-driver.integrations.calva.session-runtimes)

(def ^:private runtime-fields
  [:runtimeId :description :buildId :host :lastActivity])

(defn select-runtime-fields
  "Keep only actionable runtime fields; return nil when runtime is nil."
  [runtime]
  (when runtime
    (select-keys runtime runtime-fields)))

(defn- project-runtimes
  [runtimes]
  (mapv select-runtime-fields runtimes))

(defn project-build-compact
  "Given a build map with pre-sorted :runtimes, emit compact build fields only."
  [build]
  (let [projected (project-runtimes (:runtimes build []))]
    {:buildId (:buildId build)
     :isActive (:isActive build)
     :isCurrentlyConnected (:isCurrentlyConnected build)
     :runtimeCount (count projected)
     :mostRecentRuntime (first projected)}))

(defn project-build-full
  "Compact build fields plus projected :runtimes (same sort order as input)."
  [build]
  (let [projected (project-runtimes (:runtimes build []))]
    (assoc (project-build-compact build) :runtimes projected)))

(defn project-build
  "Project a build map; compact by default, full when include-all-runtimes? is true."
  [build include-all-runtimes?]
  (if include-all-runtimes?
    (project-build-full build)
    (project-build-compact build)))

(defn project-session
  "Project a session map; omit :builds when :supportsRuntimes is false."
  [session include-all-runtimes?]
  (if (:supportsRuntimes session)
    (-> session
        (dissoc :builds)
        (assoc :builds (mapv #(project-build % include-all-runtimes?)
                             (or (:builds session) []))))
    (dissoc session :builds)))
