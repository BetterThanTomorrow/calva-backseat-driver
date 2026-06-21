(ns calva-backseat-driver.mcp.requests
  (:require
   ["fs" :as fs]
   ["path" :as path]
   ["vscode" :as vscode]
   [calva-backseat-driver.bracket-balance :as bracket-balance]
   [calva-backseat-driver.tools :as tools]
   [calva-backseat-driver.integrations.calva.features :as calva]
   [calva-backseat-driver.mcp.skills :as skills]
   [clojure.string :as string]
   [promesa.core :as p]))

(defn- get-extension-version []
  (some-> (vscode/extensions.getExtension "betterthantomorrow.calva-backseat-driver")
          .-packageJSON
          .-version))

(defn- ^js tool-manifest [tool-name]
  (try
    (let [extension (vscode/extensions.getExtension "betterthantomorrow.calva-backseat-driver")]
      (if extension
        (let [^js contributes (some-> extension
                                      .-packageJSON
                                      .-contributes)]
          (some->> contributes
                   .-languageModelTools
                   (filter (fn [^js tool]
                             (= tool-name (.-name tool))))
                   first))
        (do
          (js/console.warn "[Server] Extension not found when looking for tool manifest for:" tool-name)
          nil)))
    (catch :default err
      (js/console.error "[Server] Error getting tool manifest for:" tool-name "error:" (.-message err))
      nil)))

(defn- tool-description [tool-name]
  (some-> tool-name
          tool-manifest
          .-modelDescription))

(defn- param-description [tool-name param]
  (some-> (tool-manifest tool-name)
          .-inputSchema
          .-properties
          (unchecked-get param)
          .-description))

(comment
  (tool-description  "clojure_evaluate_code")
  (param-description "clojure_evaluate_code" "code")
  :rcf)

(defn- tool-listing [{:keys [tool-name properties required priority]}]
  {:name tool-name
   :description (tool-description tool-name)
   :inputSchema {:type "object"
                 :properties (into {}
                                   (map (fn [[k v]]
                                          [k (merge v {:description (param-description tool-name k)})]))
                                   properties)
                 :required required
                 :audience ["user" "assistant"]
                 :priority priority}})

(def evaluate-code-tool-listing
  (tool-listing {:tool-name "clojure_evaluate_code"
                 :properties {"code" {:type "string"}
                              "namespace" {:type "string"}
                              "replSessionKey" {:type "string"}
                              "who" {:type "string"}
                              "description" {:type "string"}
                              "maxImages" {:type "number"}
                              "targetRuntimeId" {:type "number"}}
                 :required ["code" "namespace" "replSessionKey" "who"]
                 :priority 9}))

(def list-sessions-tool-listing
  (tool-listing {:tool-name "clojure_list_sessions"
                 :properties {"includeAllRuntimes" {:type "boolean"}}
                 :required []
                 :priority 9}))

(def symbol-info-tool-listing
  (tool-listing {:tool-name "clojure_symbol_info"
                 :properties {"clojureSymbol" {:type "string"}
                              "namespace" {:type "string"}
                              "replSessionKey" {:type "string"}}
                 :required ["clojureSymbol" "replSessionKey" "namespace"]
                 :priority 8}))

(def output-log-tool-info
  (tool-listing {:tool-name "clojure_repl_output_log"
                 :properties {"query" {:type "string"}
                              "inputs" {:type "array" :items {}}
                              "maxImages" {:type "number"}}
                 :required ["query"]
                 :priority 10}))

(def clojuredocs-tool-listing
  (tool-listing {:tool-name "clojuredocs_info"
                 :properties {"clojureSymbol" {:type "string"}}
                 :required ["clojureSymbol"]
                 :priority 8}))

(def bracket-balance-tool-listing
  (tool-listing {:tool-name "clojure_balance_brackets"
                 :properties {"text" {:type "string"}}
                 :required ["text"]
                 :priority 10}))

(def edit-files-tool-listing
  (tool-listing {:tool-name "clojure_edit_files"
                 :properties {"edits" {:type "array"
                                       :items {:type "object"
                                               :properties {"type" {:type "string"}
                                                            "filePath" {:type "string"}
                                                            "line" {:type "integer"}
                                                            "targetLineText" {:type "string"}
                                                            "newForm" {:type "string"}
                                                            "code" {:type "string"}
                                                            "content" {:type "string"}}
                                               :required ["type" "filePath"]}}}
                 :required ["edits"]
                 :priority 7}))

(def load-file-tool-listing
  (tool-listing {:tool-name "clojure_load_file"
                 :properties {"filePath" {:type "string"}
                              "replSessionKey" {:type "string"}
                              "who" {:type "string"}}
                 :required ["filePath" "replSessionKey" "who"]
                 :priority 8}))

(defn- skill-manifests []
  (try
    (let [extension (vscode/extensions.getExtension "betterthantomorrow.calva-backseat-driver")]
      (if extension
        (let [^js skills (some-> extension
                                 .-packageJSON
                                 .-contributes
                                 .-chatSkills)]
          (when skills
            (->> skills
                 (keep (fn [^js entry]
                         (when-let [[_ skill-name] (re-find #"\./assets/skills/([^/]+)/SKILL\.md" (.-path entry))]
                           {:skill/name skill-name
                            :skill/path (.-path entry)}))))))
        (do
          (js/console.warn "[Server] Extension not found when looking for skill manifests")
          [])))
    (catch :default err
      (js/console.error "[Server] Error getting skill manifests:" (.-message err))
      [])))

(defn- get-skills []
  (let [manifests (skill-manifests)
        extension (vscode/extensions.getExtension "betterthantomorrow.calva-backseat-driver")
        ext-path (when extension (.. extension -extensionUri -fsPath))]
    (when ext-path
      (->> manifests
           (keep (fn [{:skill/keys [name] :as manifest}]
                   (try
                     (let [rel-path (string/replace-first (:skill/path manifest) "./" "")
                           abs-path (path/join ext-path rel-path)
                           content (str (fs/readFileSync abs-path "utf8"))
                           {:keys [description]} (skills/parse-skill-frontmatter content)]
                       {:skill/name name
                        :skill/description (or description "")
                        :skill/uri (str "/skills/" name "/SKILL.md")
                        :skill/content content})
                     (catch :default err
                       (js/console.warn "[Server] Error reading skill" name ":" (.-message err))
                       nil))))
           vec))))



(defn- text-response
  "JSON-RPC success response with JSON-stringified text content."
  [id data]
  {:jsonrpc "2.0"
   :id id
   :result {:content [{:type "text"
                       :text (js/JSON.stringify data)}]}})

(defn- clj-response
  "JSON-RPC success response with clj->js JSON-stringified text content."
  [id data]
  {:jsonrpc "2.0"
   :id id
   :result {:content [{:type "text"
                       :text (js/JSON.stringify (clj->js data))}]}})

(defn- content-response
  "JSON-RPC success response with pre-built content array."
  [id content]
  {:jsonrpc "2.0"
   :id id
   :result {:content content}})

(defn- error-response
  "JSON-RPC error response."
  [id code message]
  {:jsonrpc "2.0"
   :id id
   :error {:code code :message message}})

(defn- exception-message
  "Best-effort message for any thrown/rejected value, not just js/Error."
  [e]
  (or (ex-message e) (str e)))

(defn- handle-evaluate-code [options id arguments]
  (let [{:keys [code replSessionKey who description maxImages targetRuntimeId]
         ns :namespace} arguments
        who-error (calva/validate-who who)]
    (if who-error
      (clj-response id {:error who-error})
      (p/let [result (calva/evaluate-code+ (merge options
                                                  {:calva/code code
                                                   :calva/repl-session-key replSessionKey
                                                   :calva/who who
                                                   :calva/description description}
                                                  (when ns {:calva/ns ns})
                                                  (when targetRuntimeId {:calva/target-runtime-id targetRuntimeId})))]
        (content-response id (tools/mcp-content-with-images result :max-images (if (some? maxImages) maxImages 10)))))))

(defn- handle-list-sessions [options id arguments]
  (let [{:keys [includeAllRuntimes]} arguments]
    (p/let [result (calva/list-sessions+ (merge options
                                                {:calva/include-all-runtimes? (true? includeAllRuntimes)}))]
      (clj-response id result))))

(defn- handle-symbol-info [options id arguments]
  (p/let [{:keys [clojureSymbol replSessionKey]
           ns :namespace} arguments
          info (calva/get-symbol-info+ (merge options
                                              {:calva/clojure-symbol clojureSymbol
                                               :calva/repl-session-key replSessionKey
                                               :calva/ns ns}))]
    (text-response id info)))

(defn- handle-clojuredocs [options id arguments]
  (p/let [{:keys [clojureSymbol]} arguments
          info (calva/get-clojuredocs+ (merge options
                                              {:calva/clojure-symbol clojureSymbol}))]
    (text-response id info)))

(defn- handle-output-log [options id arguments]
  (let [{:keys [query inputs maxImages]} arguments
        output (calva/query-output (merge options
                                          {:calva/query-edn-str query
                                           :calva/inputs inputs}))]
    (content-response id (tools/mcp-content-with-images output :max-images (if (some? maxImages) maxImages 0)))))

(defn- handle-balance-brackets [options id arguments]
  (let [{:keys [text]} arguments
        result (bracket-balance/infer-parens-response (merge options {:calva/text text}))]
    (text-response id result)))

(defn- blank-session-key? [k]
  (or (nil? k) (and (string? k) (string/blank? k))))

(defn- handle-load-file [options id arguments]
  (let [{:keys [filePath replSessionKey who]} arguments
        who-error (calva/validate-who who)]
    (cond
      (blank-session-key? replSessionKey)
      (clj-response id {:error "The `replSessionKey` parameter is required. Use `clojure_list_sessions` to discover available sessions."})

      who-error
      (clj-response id {:error who-error})

      :else
      (p/let [result (calva/load-file+ (merge options
                                              {:calva/file-path filePath
                                               :calva/repl-session-key replSessionKey
                                               :calva/who who}))]
        (if (:error result)
          {:jsonrpc "2.0"
           :id id
           :result {:content [{:type "text"
                               :text (js/JSON.stringify (clj->js {:error (:error result)}))}]
                    :isError true}}
          (clj-response id result))))))

(defn- handle-edit-files [options id arguments]
  (p/let [{:keys [edits]} arguments
          result (calva/edit-files+ (merge options {:calva/edits edits}))]
    (clj-response id result)))

(def ^:private tool-handlers
  {"clojure_evaluate_code"    {:handler handle-evaluate-code :repl-required? true}
   "clojure_list_sessions"    {:handler handle-list-sessions}
   "clojure_symbol_info"      {:handler handle-symbol-info}
   "clojuredocs_info"         {:handler handle-clojuredocs}
   "clojure_repl_output_log"  {:handler handle-output-log}
   "clojure_balance_brackets" {:handler handle-balance-brackets}
   "clojure_edit_files"       {:handler handle-edit-files}
   "clojure_load_file"        {:handler handle-load-file :repl-required? true}})

(defn- handle-tools-call [{:keys [id params] :as _request}
                          {:mcp/keys [repl-enabled?] :as options}]
  (let [{:keys [arguments] tool :name} params
        {:keys [handler repl-required?]} (get tool-handlers tool)]
    (cond
      (nil? handler)
      (error-response id -32601 "Unknown tool")

      (and repl-required? (not (true? repl-enabled?)))
      (error-response id -32601 "Unknown tool")

      :else
      (try
        (let [result (handler options id arguments)]
          (if (p/promise? result)
            ;; Async handlers reject asynchronously, so the synchronous catch
            ;; below won't see it; recover here to keep the request's id.
            (p/catch result
                     (fn [e]
                       (error-response id -32603 (exception-message e))))
            result))
        (catch :default e
          (error-response id -32603 (exception-message e)))))))

(defn- handle-resources-read [{:keys [id params] :as _request}
                              {:mcp/keys [provide-bd-skill? provide-edit-skill?] :as options}]
  (let [{:keys [uri]} params]
    (cond
      (string/starts-with? uri "/symbol-info/")
      (p/let [[_ clojureSymbol session-key ns] (re-find #"^/symbol-info/([^@]+)@([^@]+)@(.+)$" uri)
              info (calva/get-symbol-info+ (merge options
                                                  {:calva/clojure-symbol clojureSymbol
                                                   :calva/repl-session-key session-key
                                                   :calva/ns ns}))]
        {:jsonrpc "2.0"
         :id id
         :result {:contents [{:uri uri
                              :text (js/JSON.stringify info)}]}})

      (string/starts-with? uri "/clojuredocs/")
      (p/let [[_ clojureSymbol] (re-find #"^/clojuredocs/(.+)$" uri)
              info (calva/get-clojuredocs+ (merge options
                                                  {:calva/clojure-symbol clojureSymbol}))]
        {:jsonrpc "2.0"
         :id id
         :result {:contents [{:uri uri
                              :text (js/JSON.stringify info)}]}})

      (string/starts-with? uri "/skills/")
      (let [[_ skill-name] (re-find #"^/skills/([^/]+)/SKILL\.md$" uri)
            filtered-skills (skills/filter-skills (get-skills)
                                                  {:provide-bd-skill? provide-bd-skill?
                                                   :provide-edit-skill? provide-edit-skill?})
            skill (some #(when (= (:skill/name %) skill-name) %) filtered-skills)]
        (if skill
          {:jsonrpc "2.0"
           :id id
           :result {:contents [{:uri uri
                                :text (:skill/content skill)
                                :mimeType "text/markdown"}]}}
          (error-response id -32602 (str "Skill not found: " uri))))

      :else
      (error-response id -32602 "Unknown resource URI"))))

(defn- handle-initialize [options id]
  (let [{:mcp/keys [repl-enabled? provide-bd-skill? provide-edit-skill?]} options]
    {:jsonrpc "2.0"
     :id id
     :result {:serverInfo {:name "calva-backseat-driver"
                           :version (get-extension-version)}
              :protocolVersion "2024-11-05"
              :capabilities {:tools {:listChanged true}
                             :resources {:listChanged true}}
              :instructions (skills/compose-instructions repl-enabled?
                                                         (skills/filter-skills (get-skills)
                                                                               {:provide-bd-skill? provide-bd-skill?
                                                                                :provide-edit-skill? provide-edit-skill?}))
              :description "Gives access to the Calva API, including Calva REPL output, the Clojure REPL connection (unless disabled in settings), Clojure symbol info, clojuredocs.org lookup, and structural editing tools for Clojure code. Effectively turning the AI Agent into a Clojure Interactive Programmer."}}))

(defn- handle-tools-list [options id]
  (let [{:mcp/keys [repl-enabled?]} options]
    {:jsonrpc "2.0"
     :id id
     :result {:tools (cond-> [bracket-balance-tool-listing
                              list-sessions-tool-listing
                              symbol-info-tool-listing
                              clojuredocs-tool-listing
                              output-log-tool-info
                              edit-files-tool-listing]
                       (true? repl-enabled?)
                       (conj evaluate-code-tool-listing
                             load-file-tool-listing))}}))

(defn handle-request-fn [{:ex/keys [dispatch!] :as options
                          :mcp/keys [provide-bd-skill? provide-edit-skill?]}
                         {:keys [id method] :as request}]
  (dispatch! [[:app/ax.log :debug "[Server] handle-request " (pr-str request)]])
  (case method
    "initialize"
    (handle-initialize options id)

    "tools/list"
    (handle-tools-list options id)

    "resources/list"
    (let [filtered-skills (skills/filter-skills (get-skills)
                                                {:provide-bd-skill? provide-bd-skill?
                                                 :provide-edit-skill? provide-edit-skill?})]
      {:jsonrpc "2.0"
       :id id
       :result {:resources (mapv (fn [{:skill/keys [name description uri]}]
                                   {:uri uri
                                    :name name
                                    :description description
                                    :mimeType "text/markdown"})
                                 filtered-skills)}})

    "resources/templates/list"
    {:jsonrpc "2.0"
     :id id
     :result {:resourceTemplates [{:uriTemplate "/symbol-info/{symbol}@{session-key}@{namespace}"
                                   :name "symbol-info"
                                   :description (tool-description "clojure_symbol_info")
                                   :mimeType "application/json"}
                                  {:uriTemplate "/clojuredocs/{symbol}"
                                   :name "clojuredocs"
                                   :description (tool-description "clojuredocs_info")
                                   :mimeType "application/json"}]}}

    "tools/call"
    (handle-tools-call request options)

    "resources/read"
    (handle-resources-read request options)

    "ping"
    {:jsonrpc "2.0" :id id :result {}}

    (if id
      (error-response id -32601 "Method not found")
      nil)))

