(ns com.rpl.specter.macros
  (:require [com.rpl.specter.impl :as i]
            [clojure.walk :as walk]
            [clojure.tools.macro :as m])
  )

(defn gensyms [amt]
  (vec (repeatedly amt gensym)))

(defn determine-params-impls [[name1 & impl1] [name2 & impl2]]
  (if-not (= #{name1 name2} #{'select* 'transform*})
    (i/throw-illegal "defpath must implement select* and transform*, instead got "
      name1 " and " name2))
  (if (= name1 'select*)
    [impl1 impl2]
    [impl2 impl1]))


(def PARAMS-SYM (vary-meta (gensym "params") assoc :tag 'objects))
(def PARAMS-IDX-SYM (gensym "params-idx"))

(defn paramsnav* [bindings num-params [impl1 impl2]]
  (let [[[[_ s-structure-sym s-next-fn-sym] & select-body]
         [[_ t-structure-sym t-next-fn-sym] & transform-body]]
         (determine-params-impls impl1 impl2)]
    (if (= 0 num-params)
      `(i/no-params-compiled-path
         (i/->TransformFunctions
           i/LeanPathExecutor
           (fn [~s-structure-sym ~s-next-fn-sym]
             ~@select-body)
           (fn [~t-structure-sym ~t-next-fn-sym]
             ~@transform-body)
           ))
      `(i/->ParamsNeededPath
         (i/->TransformFunctions
           i/RichPathExecutor
           (fn [~PARAMS-SYM ~PARAMS-IDX-SYM vals# ~s-structure-sym next-fn#]
             (let [~s-next-fn-sym (fn [structure#]
                                    (next-fn#
                                      ~PARAMS-SYM
                                      (+ ~PARAMS-IDX-SYM ~num-params)
                                      vals#
                                      structure#))
                   ~@bindings]
               ~@select-body
               ))
           (fn [~PARAMS-SYM ~PARAMS-IDX-SYM vals# ~t-structure-sym next-fn#]
             (let [~t-next-fn-sym (fn [structure#]
                                    (next-fn#
                                      ~PARAMS-SYM
                                      (+ ~PARAMS-IDX-SYM ~num-params)
                                      vals#
                                      structure#))
                   ~@bindings]
               ~@transform-body
               )))
         ~num-params
         ))))

(defn paramscollector* [post-bindings num-params [_ [_ structure-sym] & body]]
  `(let [collector# (fn [~PARAMS-SYM ~PARAMS-IDX-SYM vals# ~structure-sym next-fn#]
                      (let [~@post-bindings ~@[] ; to avoid syntax highlighting issues
                            c# (do ~@body)]
                        (next-fn#                                    
                          ~PARAMS-SYM
                          (+ ~PARAMS-IDX-SYM ~num-params)
                          (conj vals# c#)
                          ~structure-sym)                     
                        ))]
     (i/->ParamsNeededPath
       (i/->TransformFunctions
         i/RichPathExecutor
         collector#
         collector# )
       ~num-params
       )))

(defn pathed-nav* [builder paths-seq latefns-sym pre-bindings post-bindings impls]
  (let [num-params-sym (gensym "num-params")]
    `(let [paths# (map i/comp-paths* ~paths-seq)
           needed-params# (map i/num-needed-params paths#)
           offsets# (cons 0 (reductions + needed-params#))
           any-params-needed?# (->> paths#
                                    (filter i/params-needed-path?)
                                    empty?
                                    not)
           ~num-params-sym (last offsets#)
           ~latefns-sym (map
                          (fn [o# p#]
                            (if (i/compiled-path? p#)
                              (fn [params# params-idx#]
                                p# )
                              (fn [params# params-idx#]
                                (i/bind-params* p# params# (+ params-idx# o#))
                                )))
                          offsets#
                          paths#)
           ~@pre-bindings
           ret# ~(builder post-bindings num-params-sym impls)
           ]
    (if (not any-params-needed?#)
      (i/bind-params* ret# nil 0)
      ret#
      ))))

(defn make-param-retrievers [params]
  (->> params
       (map-indexed
         (fn [i p]
           [p `(aget ~PARAMS-SYM
                     (+ ~PARAMS-IDX-SYM ~i))]
           ))
       (apply concat)))


(defmacro nav
  "Defines a navigator with late bound parameters. This navigator can be precompiled
  with other navigators without knowing the parameters. When precompiled with other
  navigators, the resulting path takes in parameters for all navigators in the path
  that needed parameters (in the order in which they were declared)."
  [params impl1 impl2]
  (let [num-params (count params)
        retrieve-params (make-param-retrievers params)]
    (paramsnav* retrieve-params num-params [impl1 impl2])
    ))

(defmacro paramsfn [params [structure-sym] & impl]
  `(nav ~params
     (~'select* [this# structure# next-fn#]
       (let [afn# (fn [~structure-sym] ~@impl)]
         (i/filter-select afn# structure# next-fn#)
         ))
     (~'transform* [this# structure# next-fn#]
       (let [afn# (fn [~structure-sym] ~@impl)]
         (i/filter-transform afn# structure# next-fn#)
         ))))

(defmacro paramscollector
  "Defines a Collector with late bound parameters. This collector can be precompiled
  with other selectors without knowing the parameters. When precompiled with other
  selectors, the resulting selector takes in parameters for all selectors in the path
  that needed parameters (in the order in which they were declared).
   "
  [params impl]
  (let [num-params (count params)
        retrieve-params (make-param-retrievers params)]
    (paramscollector* retrieve-params num-params impl)
    ))

(defmacro defnav [name & body]
  `(def ~name (nav ~@body)))

(defmacro defcollector [name & body]
  `(def ~name (paramscollector ~@body)))

(defmacro fixed-pathed-nav
  "This helper is used to define navigators that take in a fixed number of other
   paths as input. Those paths may require late-bound params, so this helper
   will create a parameterized navigator if that is the case. If no late-bound params
   are required, then the result is executable."
  [bindings impl1 impl2]
  (let [bindings (partition 2 bindings)
        paths (mapv second bindings)
        names (mapv first bindings)
        latefns-sym (gensym "latefns")
        latefn-syms (vec (gensyms (count paths)))]
    (pathed-nav*
      paramsnav*
      paths
      latefns-sym
      [latefn-syms latefns-sym]
      (mapcat (fn [n l] [n `(~l ~PARAMS-SYM ~PARAMS-IDX-SYM)]) names latefn-syms)
      [impl1 impl2])))

(defmacro variable-pathed-nav
  "This helper is used to define navigators that take in a variable number of other
   paths as input. Those paths may require late-bound params, so this helper
   will create a parameterized navigator if that is the case. If no late-bound params
   are required, then the result is executable."
  [[latepaths-seq-sym paths-seq] impl1 impl2]
  (let [latefns-sym (gensym "latefns")]
    (pathed-nav*
      paramsnav*
      paths-seq
      latefns-sym
      []
      [latepaths-seq-sym `(map (fn [l#] (l# ~PARAMS-SYM ~PARAMS-IDX-SYM))
                               ~latefns-sym)]
      [impl1 impl2]
      )))

(defmacro pathed-collector
  "This helper is used to define collectors that take in a single selector
   paths as input. That path may require late-bound params, so this helper
   will create a parameterized selector if that is the case. If no late-bound params
   are required, then the result is executable."
  [[name path] impl]
  (let [latefns-sym (gensym "latefns")
        latefn (gensym "latefn")]
    (pathed-nav*
      paramscollector*
      [path]
      latefns-sym
      [[latefn] latefns-sym]
      [name `(~latefn ~PARAMS-SYM ~PARAMS-IDX-SYM)]
      impl
      )
    ))

(defn- protpath-sym [name]
  (-> name (str "-prot") symbol))

(defmacro defprotocolpath
  ([name]
    `(defprotocolpath ~name []))
  ([name params]
    (let [prot-name (protpath-sym name)
          m (-> name (str "-retrieve") symbol)
          num-params (count params)
          ssym (gensym "structure")
          rargs [(gensym "params") (gensym "pidx") (gensym "vals") ssym (gensym "next-fn")]
          retrieve `(~m ~ssym)
          ]
      `(do
          (defprotocol ~prot-name (~m [structure#]))
          (def ~name
            (if (= ~num-params 0)
              (i/no-params-compiled-path
                (i/->TransformFunctions
                  i/RichPathExecutor
                  (fn ~rargs
                    (let [path# ~retrieve
                          selector# (i/compiled-selector path#)]
                      (selector# ~@rargs)
                      ))
                  (fn ~rargs
                    (let [path# ~retrieve
                          transformer# (i/compiled-transformer path#)]
                      (transformer# ~@rargs)
                      ))))
              (i/->ParamsNeededPath
                (i/->TransformFunctions
                  i/RichPathExecutor
                  (fn ~rargs
                    (let [path# ~retrieve
                          selector# (i/params-needed-selector path#)]
                      (selector# ~@rargs)
                      ))
                  (fn ~rargs
                    (let [path# ~retrieve
                          transformer# (i/params-needed-transformer path#)]
                      (transformer# ~@rargs)
                      )))
                ~num-params
                )
              ))))))


(defn declared-name [name]
  (symbol (str name "-declared")))

(defmacro declarepath
  ([name]
    `(declarepath ~name []))
  ([name params]
    (let [num-params (count params)
          declared (declared-name name)
          rargs [(gensym "params") (gensym "pidx") (gensym "vals")
                 (gensym "structure") (gensym "next-fn")]]
      `(do
         (declare ~declared)
         (def ~name
           (if (= ~num-params 0)
             (i/no-params-compiled-path
               (i/->TransformFunctions
                i/RichPathExecutor
                (fn ~rargs
                  (let [selector# (i/compiled-selector ~declared)]
                    (selector# ~@rargs)
                    ))
                (fn ~rargs
                  (let [transformer# (i/compiled-transformer ~declared)]
                    (transformer# ~@rargs)
                    ))))
             (i/->ParamsNeededPath
               (i/->TransformFunctions
                 i/RichPathExecutor
                 (fn ~rargs
                   (let [selector# (i/params-needed-selector ~declared)]
                     (selector# ~@rargs)
                     ))
                 (fn ~rargs
                   (let [transformer# (i/params-needed-transformer ~declared)]
                     (transformer# ~@rargs)
                     )))
               ~num-params
               )
           ))))))

(defmacro providepath [name apath]
  `(let [comped# (i/comp-paths* ~apath)
         expected-params# (i/num-needed-params ~name)
         needed-params# (i/num-needed-params comped#)]
     (if-not (= needed-params# expected-params#)
       (i/throw-illegal "Invalid number of params in provided path, expected "
           expected-params# " but got " needed-params#))
     (def ~(declared-name name)
       (update-in comped#
                  [:transform-fns]
                  i/coerce-tfns-rich)
       )))

(defmacro extend-protocolpath [protpath & extensions]
  `(i/extend-protocolpath* ~protpath ~(protpath-sym protpath) ~(vec extensions)))

(defmacro defpathedfn [name & args]
  (let [[name args] (m/name-with-attributes name args)]
    `(def ~name (vary-meta (fn ~@args) assoc :pathedfn true))))
  

(defn ic-prepare-path [locals-set path]
  (cond
    (vector? path)
    (mapv #(ic-prepare-path locals-set %) path)

    (symbol? path)
    (if (contains? locals-set path)
      `(com.rpl.specter.impl/->LocalSym ~path (quote ~path))
      `(com.rpl.specter.impl/->VarUse (var ~path) (quote ~path))
      )

    (i/fn-invocation? path)
    (let [[op & params] path]
      (if (special-symbol? op)
        `(com.rpl.specter.impl/->SpecialFormUse ~path (quote ~path))
        `(com.rpl.specter.impl/->FnInvocation
           ~(ic-prepare-path locals-set op)
           ~(mapv #(ic-prepare-path locals-set %) params)
           (quote ~path)))
      )

    :else
    path
    ))

;; still possible to mess this up with alter-var-root
(defmacro ic! [& path] ; "inline cache"
  (let [local-syms (-> &env keys set)
        used-locals (vec (i/walk-select local-syms vector path))
        prepared-path (ic-prepare-path local-syms (walk/macroexpand-all (vec path)))
        ;; TODO: unclear if using long here versus string makes
        ;; a significant difference
        ;; - but using random longs creates possibility of collisions
        ;; (birthday problem)
        ;; - ideally could have a real inline cache that wouldn't
        ;; have to do any hashing/equality checking at all
        ;; - with invokedynamic here, could go directly to the code
        ;; to invoke and/or parameterize the precompiled path without
        ;; a bunch of checks beforehand
        cache-id (str (java.util.UUID/randomUUID))
        ]
    `(let [info# (i/get-path-cache ~cache-id)
           
           ^com.rpl.specter.impl.CachedPathInfo info#
            (if (some? info#)
              info#
              (let [info# (i/magic-precompilation
                           ~prepared-path
                           ~(mapv (fn [e] `(quote ~e)) used-locals)
                           )]
                (i/add-path-cache! ~cache-id info#)
                info#
                ))

           precompiled# (.-precompiled info#)
           params-maker# (.-params-maker info#)]
       (if (some? precompiled#)
         (if (nil? params-maker#)
           precompiled#
           (i/bind-params* precompiled# (params-maker# ~@used-locals) 0)
           )
         (i/comp-paths* ~(vec path))
         ))
  ))
