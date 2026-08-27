(ns calva-backseat-driver.integrations.calva.session-runtimes
  (:require
   ["url" :as url]
   [clojure.string :as string]))

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

(defn- file-url->fs-path
  [file-url]
  (try
    (url/fileURLToPath file-url)
    (catch :default _
      (-> file-url
          (string/replace #"^file://" "")
          js/decodeURIComponent))))

(defn project-root-fs-path
  "Absolute filesystem path for a Calva `projectRoot` (file URI or path)."
  [project-root]
  (cond
    (nil? project-root) nil
    (string? project-root)
    (if (string/starts-with? project-root "file:")
      (file-url->fs-path project-root)
      project-root)
    (map? project-root)
    (or (:fsPath project-root) (:path project-root))
    :else (str project-root)))

(defn project-session
  "Project a session map; omit :builds when :supportsRuntimes is false."
  [session include-all-runtimes?]
  (let [projected (if (:supportsRuntimes session)
                    (-> session
                        (dissoc :builds)
                        (assoc :builds (mapv #(project-build % include-all-runtimes?)
                                             (or (:builds session) []))))
                    (dissoc session :builds))]
    (if-let [root (project-root-fs-path (:projectRoot session))]
      (assoc projected :projectRoot root)
      projected)))

(defn- registry-build
  "Shard build fields: Calva `isCurrentlyConnected` as `isHumansActiveRuntime`."
  [build]
  (cond-> build
    (not (:isHumansActiveRuntime build))
    (assoc :isHumansActiveRuntime (:isCurrentlyConnected build))
    
    :always 
    (dissoc :isCurrentlyConnected)))

(defn compact-registry-session
  "Session fields for a window-shard `sessions` entry."
  [session]
  (let [projected (project-session session false)
        base (select-keys projected [:replSessionKey :projectRoot :globs :supportsRuntimes])]
    (cond-> base
      (:supportsRuntimes projected) (assoc :builds (mapv registry-build (:builds projected))))))
