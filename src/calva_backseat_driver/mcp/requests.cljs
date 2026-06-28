(ns calva-backseat-driver.mcp.requests
  (:require
   [calva-backseat-driver.bracket-balance :as bracket-balance]
   [calva-backseat-driver.tools :as tools]
   [calva-backseat-driver.integrations.calva.features :as calva]
   [calva-backseat-driver.mcp.skills :as skills]
   [clojure.string :as string]
   [promesa.core :as p]
   [vscode-mcp.manifest :as manifest]
   [vscode-mcp.responses :refer [text-response clj-response content-response error-response]]))

(defn- get-extension-version [options]
  (some-> ^js (:vscode/extension-context options) .-extension .-packageJSON .-version))

(defn- settings-map [options]
  (let [{:mcp/keys [provide-bd-skill? provide-edit-skill?]} options]
    {"config.calva-backseat-driver.provideBdSkill" provide-bd-skill?
     "config.calva-backseat-driver.provideEditSkill" provide-edit-skill?
     ":calva-mcp-extension/activated?" true}))


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

(defn- handle-resources-read [{:keys [id params] :as _request} options]
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

      (string/starts-with? uri "skill://")
      (let [resource (manifest/read-resource (:vscode/extension-context options) uri {:settings (settings-map options)})]
        (if resource
          {:jsonrpc "2.0"
           :id id
           :result {:contents [(dissoc resource :skill-path)]}}
          (error-response id -32602 (str "Skill not found: " uri))))

      :else
      (error-response id -32602 "Unknown resource URI"))))

(defn- handle-initialize [options id]
  (let [{:mcp/keys [repl-enabled?]} options
        skills (manifest/get-resources (:vscode/extension-context options) {:settings (settings-map options)})]
    {:jsonrpc "2.0"
     :id id
     :result {:serverInfo {:name "calva-backseat-driver"
                           :version (get-extension-version options)}
              :protocolVersion "2024-11-05"
              :capabilities {:tools {:listChanged true}
                             :resources {:listChanged true}}
              :instructions (skills/compose-instructions repl-enabled? skills)
              :description "Gives access to the Calva API, including Calva REPL output, the Clojure REPL connection (unless disabled in settings), Clojure symbol info, clojuredocs.org lookup, and structural editing tools for Clojure code. Effectively turning the AI Agent into a Clojure Interactive Programmer."}}))

(defn- handle-tools-list [options id]
  (let [{:mcp/keys [repl-enabled?]} options
        all-tools (manifest/get-tools (:vscode/extension-context options) {:settings (settings-map options)})]
    {:jsonrpc "2.0"
     :id id
     :result {:tools (cond->> all-tools
                       (not repl-enabled?)
                       (remove (comp #{"clojure_evaluate_code" "clojure_load_file"} :name)))}}))

(defn handle-request-fn [{:ex/keys [dispatch!] :as options}
                         {:keys [id method] :as request}]
  (dispatch! [[:app/ax.log :debug "[Server] handle-request " (pr-str request)]])
  (case method
    "initialize"
    (handle-initialize options id)

    "tools/list"
    (handle-tools-list options id)

    "resources/list"
    (let [skills (manifest/get-resources (:vscode/extension-context options) {:settings (settings-map options)})
          public-skills (map #(dissoc % :skill-path) skills)]
      {:jsonrpc "2.0"
       :id id
       :result {:resources public-skills}})

    "resources/templates/list"
    (let [all-tools (manifest/get-tools (:vscode/extension-context options) {:settings (settings-map options)})
          tool-desc (fn [t-name] (:description (first (filter #(= (:name %) t-name) all-tools))))]
      {:jsonrpc "2.0"
       :id id
       :result {:resourceTemplates [{:uriTemplate "/symbol-info/{symbol}@{session-key}@{namespace}"
                                     :name "symbol-info"
                                     :description (tool-desc "clojure_symbol_info")
                                     :mimeType "application/json"}
                                    {:uriTemplate "/clojuredocs/{symbol}"
                                     :name "clojuredocs"
                                     :description (tool-desc "clojuredocs_info")
                                     :mimeType "application/json"}]}})

    "tools/call"
    (handle-tools-call request options)

    "resources/read"
    (handle-resources-read request options)

    "ping"
    {:jsonrpc "2.0" :id id :result {}}

    (if id
      (error-response id -32601 "Method not found")
      nil)))

