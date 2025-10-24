;; Something with loading "parinfer" in dev causes troubles, so we have this glue to only do the indirection
(ns calva-backseat-driver.integrations.parinfer-glue
  (:require ["parinfer" :as parinfer]))

(defn indent-mode
  [code]
  (some-> (parinfer/indentMode code #js {:partialResult true})
          (js->clj :keywordize-keys true)))