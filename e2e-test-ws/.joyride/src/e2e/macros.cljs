(ns e2e.macros)

(defmacro deftest-async [name opts & body]
  (let [[opts body]
        (if (map? opts)
          [opts body]
          [nil (cons opts body)])
        async-fn (with-meta 'fn {:async true})]
    `(cljs.test/deftest ~name
       ~@(when-let [pre (:before opts)]
           [pre])
       (cljs.test/async
        ~'done
        (-> (js/Promise.resolve nil)
            (.then (fn [_#] ((~async-fn [] ~@body))))
            (.catch (fn [err#]
                      (cljs.test/is (= 1 0) (str err# (.-stack err#)))))
            (.finally
             (fn []
               ~@(when-let [post (:after opts)]
                   [post])
               (~'done))))))))
