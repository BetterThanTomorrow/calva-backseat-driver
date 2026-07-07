(ns calva-backseat-driver.mcp.requests
  (:require
   [calva-backseat-driver.bracket-balance :as bracket-balance]
   [calva-backseat-driver.tools :as tools]
   [calva-backseat-driver.integrations.calva.features :as calva]
   [clojure.string :as string]
   [promesa.core :as p]
   [vscode-mcp.manifest :as manifest]
   [vscode-mcp.requests :as mcp-requests]
   [vscode-mcp.responses :as responses]))

(defn- settings-map [options]
  (let [{:mcp/keys [provide-bd-skill? provide-edit-skill? repl-enabled?]} options]
    {"config.calva-backseat-driver.provideBdSkill" provide-bd-skill?
     "config.calva-backseat-driver.provideEditSkill" provide-edit-skill?
     "config.calva-backseat-driver.enableMcpReplEvaluation" (true? repl-enabled?)
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
      (responses/clj-response id {:error who-error})
      (p/let [result (calva/evaluate-code+ (merge options
                                                  {:calva/code code
                                                   :calva/repl-session-key replSessionKey
                                                   :calva/who who
                                                   :calva/description description}
                                                  (when ns {:calva/ns ns})
                                                  (when targetRuntimeId {:calva/target-runtime-id targetRuntimeId})))]
        (responses/content-response id (tools/mcp-content-with-images result :max-images (if (some? maxImages) maxImages 10)))))))

(defn- handle-list-sessions [options id arguments]
  (let [{:keys [includeAllRuntimes]} arguments]
    (p/let [result (calva/list-sessions+ (merge options
                                                {:calva/include-all-runtimes? (true? includeAllRuntimes)}))]
      (responses/clj-response id result))))

(defn- handle-symbol-info [options id arguments]
  (p/let [{:keys [clojureSymbol replSessionKey]
           ns :namespace} arguments
          info (calva/get-symbol-info+ (merge options
                                              {:calva/clojure-symbol clojureSymbol
                                               :calva/repl-session-key replSessionKey
                                               :calva/ns ns}))]
    (responses/text-response id info)))

(defn- handle-clojuredocs [options id arguments]
  (p/let [{:keys [clojureSymbol]} arguments
          info (calva/get-clojuredocs+ (merge options
                                              {:calva/clojure-symbol clojureSymbol}))]
    (responses/text-response id info)))

(defn- handle-output-log [options id arguments]
  (let [{:keys [query inputs maxImages]} arguments
        output (calva/query-output (merge options
                                          {:calva/query-edn-str query
                                           :calva/inputs inputs}))]
    (responses/content-response id (tools/mcp-content-with-images output :max-images (if (some? maxImages) maxImages 0)))))

(defn- handle-balance-brackets [options id arguments]
  (let [{:keys [text]} arguments
        result (bracket-balance/infer-parens-response (merge options {:calva/text text}))]
    (responses/text-response id result)))

(defn- blank-session-key? [k]
  (or (nil? k) (and (string? k) (string/blank? k))))

(defn- handle-load-file [options id arguments]
  (let [{:keys [filePath replSessionKey who]} arguments
        who-error (calva/validate-who who)]
    (cond
      (blank-session-key? replSessionKey)
      (responses/clj-response id {:error "The `replSessionKey` parameter is required. Use `clojure_list_sessions` to discover available sessions."})

      who-error
      (responses/clj-response id {:error who-error})

      :else
      (p/let [result (calva/load-file+ (merge options
                                              {:calva/file-path filePath
                                               :calva/repl-session-key replSessionKey
                                               :calva/who who}))]
        (if (:error result)
          (responses/success-response id {:content [{:type "text"
                                                     :text (js/JSON.stringify (clj->js {:error (:error result)}))}]
                                          :isError true})
          (responses/clj-response id result))))))

(defn- handle-edit-files [options id arguments]
  (p/let [{:keys [edits]} arguments
          result (calva/edit-files+ (merge options {:calva/edits edits}))]
    (responses/clj-response id result)))

(def ^:private tool-handlers
  {"clojure_evaluate_code"    {:handler handle-evaluate-code}
   "clojure_list_sessions"    {:handler handle-list-sessions}
   "clojure_symbol_info"      {:handler handle-symbol-info}
   "clojuredocs_info"         {:handler handle-clojuredocs}
   "clojure_repl_output_log"  {:handler handle-output-log}
   "clojure_balance_brackets" {:handler handle-balance-brackets}
   "clojure_edit_files"       {:handler handle-edit-files}
   "clojure_load_file"        {:handler handle-load-file}})

(defn- handle-tools-call [{:keys [id params] :as _request} options]
  (let [{:keys [arguments] tool :name} params
        allowed (manifest/tool-call-allowed? (:vscode/extension-context options) tool {:settings (settings-map options)})
        {:keys [handler]} (tool-handlers tool)]
    (cond
      (or (= :disabled allowed) (= :unknown allowed))
      (responses/error-response id -32601 "Unknown tool")

      :else
      (try
        (let [result (handler options id arguments)]
          (if (p/promise? result)
            (p/catch result
                     (fn [e]
                       (responses/error-response id -32603 (exception-message e))))
            result))
        (catch :default e
          (responses/error-response id -32603 (exception-message e)))))))

(defn- bd-read-dynamic-resource [uri options]
  (cond
    (string/starts-with? uri "/symbol-info/")
    (p/let [[_ clojureSymbol session-key ns] (re-find #"^/symbol-info/([^@]+)@([^@]+)@(.+)$" uri)
            info (calva/get-symbol-info+ (merge options
                                                {:calva/clojure-symbol clojureSymbol
                                                 :calva/repl-session-key session-key
                                                 :calva/ns ns}))]
      {:contents [{:uri uri
                   :text (js/JSON.stringify info)}]})

    (string/starts-with? uri "/clojuredocs/")
    (p/let [[_ clojureSymbol] (re-find #"^/clojuredocs/(.+)$" uri)
            info (calva/get-clojuredocs+ (merge options
                                                {:calva/clojure-symbol clojureSymbol}))]
      {:contents [{:uri uri
                   :text (js/JSON.stringify info)}]})

    :else
    nil))

(defn- bd-resource-templates [options]
  (let [all-tools (manifest/get-tools (:vscode/extension-context options) {:settings (settings-map options)})
        tool-desc (fn [t-name] (:description (first (filter #(= (:name %) t-name) all-tools))))]
    [{:uriTemplate "/symbol-info/{symbol}@{session-key}@{namespace}"
      :name "symbol-info"
      :description (tool-desc "clojure_symbol_info")
      :mimeType "application/json"}
     {:uriTemplate "/clojuredocs/{symbol}"
      :name "clojuredocs"
      :description (tool-desc "clojuredocs_info")
      :mimeType "application/json"}]))

(defn- initialize-base-text [options]
  (let [{:mcp/keys [repl-enabled?]} options]
    (str "You have access to the `clojure_edit_files` structural editing tool (replace, insert, append, create) with automatic bracket balancing."
         (when repl-enabled?
           " You can evaluate Clojure/ClojureScript code via the `clojure_evaluate_code` tool, load entire files into the REPL with `clojure_load_file`, check REPL output with `clojure_repl_output_log`, look up symbol info, and query clojuredocs.org."))))

(defn- request-opts [options]
  {:settings (settings-map options)
   :initialize-opts {:base-text (initialize-base-text options)
                     :name "calva-backseat-driver"
                     :settings (settings-map options)}
   :initialize-merge {:capabilities {:tools {:listChanged true}
                                     :resources {:listChanged true}}
                      :description "Gives access to the Calva API, including Calva REPL output, the Clojure REPL connection (unless disabled in settings), Clojure symbol info, clojuredocs.org lookup, and structural editing tools for Clojure code. Effectively turning the AI Agent into a Clojure Interactive Programmer."}
   :resource-templates+ (fn [_ctx _opts] (bd-resource-templates options))
   :read-resource+ (fn [_ctx uri _opts] (bd-read-dynamic-resource uri options))})

(defn handle-request-fn [{:ex/keys [dispatch!] :as options}
                         {:keys [method] :as request}]
  (dispatch! [[:app/ax.log :debug "[Server] handle-request " (pr-str request)]])
  (let [opts (request-opts options)
        ctx (:vscode/extension-context options)]
    (case method
      "tools/call" (handle-tools-call request options)
      (mcp-requests/handle-manifest-request ctx request opts))))

