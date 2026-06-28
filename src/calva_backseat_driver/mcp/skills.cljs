(ns calva-backseat-driver.mcp.skills)

(defn compose-instructions [repl-enabled? skills]
  (str "You have access to the `clojure_edit_files` structural editing tool (replace, insert, append, create) with automatic bracket balancing."
       (when repl-enabled?
         " You can evaluate Clojure/ClojureScript code via the `clojure_evaluate_code` tool, load entire files into the REPL with `clojure_load_file`, check REPL output with `clojure_repl_output_log`, look up symbol info, and query clojuredocs.org.")
       (when (seq skills)
         (str "\n\nSpecialized skills are available as resources. Use `resources/list` to discover them and `resources/read` to load their full instructions before starting work in their domain:"
              (apply str (map (fn [{:keys [description] skill-name :name}]
                                (str "\n- **" skill-name "**: " description))
                              skills))))))
