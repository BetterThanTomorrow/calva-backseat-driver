(ns calva-backseat-driver.mcp.requests
  (:require
   [clojure.string :as string]
   [promesa.core :as p]
   [vscode-mcp.manifest :as manifest]
   [vscode-mcp.requests :as mcp-requests]
   [vscode-mcp.responses :as responses]))

(defn- exception-message
  "Best-effort message for any thrown/rejected value, not just js/Error."
  [e]
  (or (ex-message e) (str e)))

(defn- call-result-response
  "Returns the response to the `tools/call` request `id` for a tool call's `result`."
  [id {:result/keys [content error? refused thrown]}]
  (cond
    refused (responses/error-response id -32601 "Unknown tool")
    thrown (responses/error-response id -32603 thrown)
    :else (responses/success-response id (cond-> {:content content}
                                           error? (assoc :isError true)))))

(defn- handle-tools-call
  "Dispatches the tool call of a `tools/call` request. Returns a promise of the response."
  [dispatch! {:keys [id params]}]
  (let [{:keys [arguments] tool :name} params]
    (.then (dispatch! [[:tool/ax.call {:call/tool tool
                                       :call/arguments arguments}]])
           (fn [result]
             (call-result-response id result))
           (fn [e]
             (responses/error-response id -32603 (exception-message e))))))

(defn- tool-resource+
  "Returns a promise of the contents of the resource at `uri`: what a call of `tool`
   with `arguments` gives, as text."
  [dispatch! uri tool arguments]
  ;; vscode-mcp recognizes promesa promises, so the contents are one
  (p/create
   (fn [resolve reject]
     (.then (dispatch! [[:tool/ax.call {:call/tool tool
                                        :call/arguments arguments}]])
            (fn [{:result/keys [content thrown]}]
              (if thrown
                (reject (js/Error. thrown))
                (let [text (:text (first content))]
                  (resolve {:contents [(cond-> {:uri uri}
                                         text (assoc :text text))]}))))
            reject))))

(defn- bd-read-dynamic-resource
  "Returns a promise of the contents of the symbol info or ClojureDocs resource at
   `uri`, or nil when `uri` is neither."
  [dispatch! uri]
  (cond
    (string/starts-with? uri "/symbol-info/")
    (let [[_ clojure-symbol session-key ns] (re-find #"^/symbol-info/([^@]+)@([^@]+)@(.+)$" uri)]
      (tool-resource+ dispatch! uri "clojure_symbol_info" {:clojureSymbol clojure-symbol
                                                           :replSessionKey session-key
                                                           :namespace ns}))

    (string/starts-with? uri "/clojuredocs/")
    (let [[_ clojure-symbol] (re-find #"^/clojuredocs/(.+)$" uri)]
      (tool-resource+ dispatch! uri "clojuredocs_info" {:clojureSymbol clojure-symbol}))

    :else
    nil))

(defn- bd-resource-templates [options]
  (let [all-tools (manifest/get-tools (:vscode/extension-context options) {:settings (:mcp/when-clause-settings options)})
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

(defn- request-opts [{:mcp/keys [when-clause-settings]
                      :uf/keys [dispatch!]
                      :as options}]
  {:settings when-clause-settings
   :initialize-opts {:base-text (initialize-base-text options)
                     :name "calva-backseat-driver"
                     :settings when-clause-settings}
   :initialize-merge {:capabilities {:tools {:listChanged true}
                                     :resources {:listChanged true}}
                      :description "Gives access to the Calva API, including Calva REPL output, the Clojure REPL connection (unless disabled in settings), Clojure symbol info, clojuredocs.org lookup, and structural editing tools for Clojure code. Effectively turning the AI Agent into a Clojure Interactive Programmer."}
   :resource-templates+ (fn [_ctx _opts] (bd-resource-templates options))
   :read-resource+ (fn [_ctx uri _opts] (bd-read-dynamic-resource dispatch! uri))})

(defn handle-request-fn [{:uf/keys [dispatch!] :as options}
                         {:keys [method] :as request}]
  (dispatch! [[:app/ax.log :debug "[Server] handle-request " (pr-str request)]])
  (let [opts (request-opts options)
        ctx (:vscode/extension-context options)]
    (case method
      "tools/call" (handle-tools-call dispatch! request)
      (mcp-requests/handle-manifest-request ctx request opts))))

