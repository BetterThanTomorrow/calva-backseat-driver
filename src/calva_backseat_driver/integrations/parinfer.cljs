(ns calva-backseat-driver.integrations.parinfer
  (:require [calva-backseat-driver.integrations.parinfer-glue :as glue]))

(defn infer-brackets [code]
  (glue/indent-mode code))

(defn validate-brackets
  "Validates that code has balanced brackets without semantic changes.
   Returns a map with :valid? boolean, optional :error string, optional :balanced-code,
   and optional :parinfer-error map with structured error details."
  [code]
  (let [inferred (infer-brackets code)
        balanced-code (:text inferred)
        balancing-occurred? (not= code balanced-code)]
    (if balancing-occurred?
      (if (:success inferred)
        ;; Balancer succeeded but changed the code (ambiguous indentation)
        {:valid? false
         :error (str "Code has unbalanced brackets. The bracket balancer would change "
                     "the code structure, which could alter its meaning. "
                     "See the 'balanced-code' field for what the balancer would produce. "
                     "If that looks correct, please re-run this tool using that code. "
                     "Otherwise, fix the indentation and bracket balance before re-running")
         :balanced-code balanced-code}
        ;; Balancer failed (malformed brackets)
        (let [error-info (:error inferred)]
          {:valid? false
           :error (str "Code has malformed brackets: " (:message error-info)
                       " Please fix the bracket structure before evaluation.")
           :parinfer-error {:message (:message error-info)
                            :line (inc (:lineNo error-info))
                            :column (:x error-info)}}))
      {:valid? true})))

(comment
  (validate-brackets "foo")
  (validate-brackets "(foo)")
  (validate-brackets "(foo")
  :rcf)

