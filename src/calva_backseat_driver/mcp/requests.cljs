(ns calva-backseat-driver.mcp.requests
  (:require
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

(defn- call-result-response
  [id {:result/keys [content error?]}]
  (responses/success-response id (cond-> {:content content}
                                   error? (assoc :isError true))))

(defn- handle-tools-call [{:keys [id params] :as _request} options]
  (let [{:keys [arguments] tool :name} params
        allowed (manifest/tool-call-allowed? (:vscode/extension-context options) tool {:settings (settings-map options)})]
    (if (or (= :disabled allowed) (= :unknown allowed))
      (responses/error-response id -32601 "Unknown tool")
      ;; vscode-mcp's socket code recognizes promesa promises, so the response is one
      (p/create
       (fn [resolve _reject]
         (-> (tools/call-tool+ (:ex/dispatch! options) tool arguments)
             (.then (fn [result]
                      (resolve (call-result-response id result))))
             (.catch (fn [e]
                       (resolve (responses/error-response id -32603 (exception-message e)))))))))))

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
    (str  "Always load the backseat-driver skill. "
          "You have access to the `clojure_edit_files` structural editing tool (replace, insert, append, create) with automatic bracket balancing."
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

