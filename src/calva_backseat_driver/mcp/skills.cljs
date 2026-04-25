(ns calva-backseat-driver.mcp.skills)

(defn parse-skill-frontmatter [content]
  (when-let [[_ frontmatter] (re-find #"(?s)^---\n(.*?)\n---" content)]
    (when-let [[_ desc] (re-find #"(?m)^description:\s*['\"]?(.*?)['\"]?\s*$" frontmatter)]
      {:description desc})))

(def skill-setting-keys
  {"backseat-driver" :provide-bd-skill?
   "editing-clojure-files" :provide-edit-skill?})

(defn filter-skills [skills settings]
  (filter (fn [{:skill/keys [name]}]
            (let [setting-key (skill-setting-keys name)]
              (if setting-key
                (get settings setting-key true)
                true)))
          skills))

(defn compose-instructions [repl-enabled? skills]
  (str "You have access to Clojure structural editing tools (`replace_top_level_form`, `insert_top_level_form`, `clojure_create_file`, `clojure_append_code`) with automatic bracket balancing."
       (when repl-enabled?
         " You can evaluate Clojure/ClojureScript code via the `clojure_evaluate_code` tool, load entire files into the REPL with `clojure_load_file`, check REPL output with `get-output-log`, look up symbol info, and query clojuredocs.org.")
       (when (seq skills)
         (str "\n\nSpecialized skills are available as resources. Use `resources/list` to discover them and `resources/read` to load their full instructions before starting work in their domain:"
              (apply str (map (fn [{:skill/keys [name description]}]
                                (str "\n- **" name "**: " description))
                              skills))))))
