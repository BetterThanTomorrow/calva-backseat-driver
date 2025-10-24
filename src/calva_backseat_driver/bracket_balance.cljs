(ns calva-backseat-driver.bracket-balance
  (:require [calva-backseat-driver.integrations.parinfer :as parinfer]))

(defn infer-parens-response
  "Infer parens from the indentation"
  [{:ex/keys [dispatch!]
    :calva/keys [text]}]
  (dispatch! [[:app/ax.log :debug "[Server] Infering brackets for:" text]])
  (try
    (let [result (parinfer/infer-brackets text)]
      (clj->js
       (if (:success result)
         (let [new-text (:text result)]
           (if (= text new-text)
             {:note "The text was already properly balanced."}
             {:balanced-text (:text result)
              :note "This is the complete, bracket-balanced version of the code. REPLACE THE ENTIRE previous text (the input to the tool) with this output. The changes/fixes require NO analysis or comments from you, WHATSOEVER."}))
         result)))
    (catch :default e
      #js {:error (.-message e)})))

(comment
  (infer-parens-response {:ex/dispatch! println
                          :calva/text "(def foo [a b"}))