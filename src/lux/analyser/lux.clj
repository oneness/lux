(ns lux.analyser.lux
  (:require (clojure [template :refer [do-template]])
            [clojure.core.match :as M :refer [matchv]]
            clojure.core.match.array
            (lux [base :as & :refer [|do return return* fail fail* |let |list]]
                 [parser :as &parser]
                 [type :as &type]
                 [macro :as &macro]
                 [host :as &host])
            (lux.analyser [base :as &&]
                          [lambda :as &&lambda]
                          [case :as &&case]
                          [env :as &&env]
                          [def :as &&def])))

(defn ^:private analyse-1+ [analyse]
  (fn [?token]
    (&&/with-var #(&&/analyse-1 analyse % ?token))))

;; [Exports]
(defn analyse-tuple [analyse exo-type ?elems]
  (|do [=elems (&/map% (analyse-1+ analyse) ?elems)
        =elems-types (&/map% &&/expr-type =elems)
        ;; :let [_ (prn 'analyse-tuple =elems)]
        :let [endo-type (&/V "lux;TupleT" =elems-types)]
        _ (&type/check exo-type endo-type)
        ;; :let [_ (prn 'analyse-tuple 'DONE)]
        ]
    (return (&/|list (&/V "Expression" (&/T (&/V "tuple" =elems)
                                            exo-type))))))

(defn analyse-variant [analyse exo-type ident ?value]
  (|let [[?module ?name] ident]
    (|do [module (if (= "" ?module)
                   &/get-module-name
                   (return ?module))
          :let [?tag (str module ";" ?name)]
          =value ((analyse-1+ analyse) ?value)
          =value-type (&&/expr-type =value)
          :let [endo-type (&/V "lux;VariantT" (|list (&/T ?tag =value-type)))]
          _ (&type/check exo-type endo-type)
          ;; :let [_ (prn 'analyse-variant 'DONE)]
          ]
      (return (&/|list (&/V "Expression" (&/T (&/V "variant" (&/T ?tag =value))
                                              exo-type)))))))

(defn analyse-record [analyse exo-type ?elems]
  (|do [=elems (&/map% (fn [kv]
                         (matchv ::M/objects [kv]
                           [[k v]]
                           (|do [=v (&&/analyse-1 analyse v)]
                             (return (to-array [k =v])))))
                       ?elems)
        =elems-types (&/map% (fn [kv]
                               (matchv ::M/objects [kv]
                                 [[k v]]
                                 (|do [module (if (= "" k)
                                                &/get-module-name
                                                (return k))
                                       =v (&&/expr-type v)]
                                   (return (to-array [module =v])))))
                             =elems)
        ;; :let [_ (prn 'analyse-tuple =elems)]
        ]
    (return (&/|list (&/V "Expression" (&/T (&/V "lux;record" =elems) (&/V "lux;RecordT" =elems-types)))))))

(defn ^:private show-frame [frame]
  (str "{{" (->> frame (&/get$ "lux;locals") (&/get$ "lux;mappings")
                 &/|keys &/->seq (interpose " ") (reduce str ""))
       "}}"))

(defn analyse-symbol [analyse exo-type ident]
  (|do [module-name &/get-module-name]
    (fn [state]
      (|let [[?module ?name] ident
             local-ident (str ?module ";" ?name)
             global-ident (str (if (= "" ?module) module-name ?module) ";" ?name)
             stack (&/get$ "lux;local-envs" state)
             no-binding? #(and (->> % (&/get$ "lux;locals")  (&/get$ "lux;mappings") (&/|contains? local-ident) not)
                               (->> % (&/get$ "lux;closure") (&/get$ "lux;mappings") (&/|contains? local-ident) not))
             [inner outer] (&/|split-with no-binding? stack)]
        (matchv ::M/objects [outer]
          [["lux;Nil" _]]
          (if-let [global (->> state (&/get$ "lux;global-env") &/from-some (&/get$ "lux;locals") (&/get$ "lux;mappings") (&/|get global-ident))]
            (&/run-state (|do [=global-type (&&/expr-type global)
                               _ (&type/check exo-type =global-type)]
                           (return (&/|list global)))
                         state)
            (do (prn (str "((" (->> stack (&/|map show-frame) &/->seq (interpose " ") (reduce str "")) "))"))
             (fail* (str "[Analyser Error] Unrecognized identifier: " local-ident))))

          [["lux;Cons" [top-outer _]]]
          (|let [scopes (&/|tail (&/folds #(&/|cons (&/get$ "lux;name" %2) %1)
                                          (&/|map #(&/get$ "lux;name" %) outer)
                                          (&/|reverse inner)))
                 [=local inner*] (&/fold (fn [register+new-inner frame+in-scope]
                                           (|let [[register new-inner] register+new-inner
                                                  [frame in-scope] frame+in-scope
                                                  [register* frame*] (&&lambda/close-over (&/|cons module-name (&/|reverse in-scope)) ?name register frame)]
                                             (&/T register* (&/|cons frame* new-inner))))
                                         (&/T (or (->> top-outer (&/get$ "lux;locals")  (&/get$ "lux;mappings") (&/|get local-ident))
                                                  (->> top-outer (&/get$ "lux;closure") (&/get$ "lux;mappings") (&/|get local-ident)))
                                              (&/|list))
                                         (&/zip2 (&/|reverse inner) scopes))]
            (return* (&/set$ "lux;local-envs" (&/|++ inner* outer) state) (&/|list =local)))
          )))
    ))

(defn ^:private analyse-apply* [analyse exo-type =fn ?args]
  (|do [=args (&/map% (fn [arg] (&&/with-var #(&&/analyse-1 analyse % arg)))
                      ?args)
        =fn-type (&&/expr-type =fn)
        [=apply =output-type] (&/fold% (fn [[=fn =fn-type] =input]
                                         (|do [;; :let [_ (prn "#2")]
                                               =input-type (&&/expr-type =input)
                                               ;; :let [_ (prn "#3")]
                                               =output-type (&type/apply-lambda =fn-type =input-type)
                                               ;; :let [_ (prn "#4")]
                                               ]
                                           (return [(&/V "Expression" (&/T (&/V "apply" (&/T =fn =input))
                                                                           =output-type))
                                                    =output-type])))
                                       [=fn =fn-type]
                                       =args)
        _ (&type/check exo-type =output-type)]
    (matchv ::M/objects [=apply]
      [["Expression" [?expr _]]]
      (return (&/|list (&/V "Expression" (&/T ?expr exo-type)))))))

(defn analyse-apply [analyse exo-type =fn ?args]
  ;; (prn 'analyse-apply1 (aget =fn 0))
  (|do [loader &/loader]
    (matchv ::M/objects [=fn]
      [["Expression" [=fn-form =fn-type]]]
      (do ;; (prn 'analyse-apply2 (aget =fn-form 0))
          (matchv ::M/objects [=fn-form]
            [["global" [?module ?name]]]
            (|do [macro? (&&def/macro? ?module ?name)]
              (if macro?
                (let [macro-class (&host/location (&/|list ?module ?name))]
                  (|do [macro-expansion (&macro/expand loader macro-class ?args)
                        output (&/flat-map% analyse macro-expansion)]
                    (return output)))
                (analyse-apply* analyse exo-type =fn ?args)))
            
            [_]
            (analyse-apply* analyse =fn ?args)))

      [_]
      (fail "[Analyser Error] Can't call a statement!"))
    ))

(defn analyse-case [analyse exo-type ?value ?branches]
  (|do [:let [num-branches (&/|length ?branches)]
        _ (&/assert! (> num-branches 0) "[Analyser Error] Can't have empty branches in \"case'\" expression.")
        _ (&/assert! (even? num-branches) "[Analyser Error] Unbalanced branches in \"case'\" expression.")
        =value ((analyse-1+ analyse) ?value)
        =value-type (&&/expr-type =value)
        =match (&&case/analyse-branches analyse exo-type =value-type (&/|as-pairs ?branches))]
    (return (&/|list (&/V "Expression" (&/T (&/V "case" (&/T =value =match))
                                            exo-type))))))

(defn analyse-lambda [analyse exo-type ?self ?arg ?body]
  ;; (prn 'analyse-lambda ?self ?arg ?body)
  (|do [=lambda-type* &type/fresh-lambda]
    (matchv ::M/objects [=lambda-type*]
      [["lux;LambdaT" [=arg =return]]]
      (|do [[=scope =captured =body] (&&lambda/with-lambda ?self =lambda-type*
                                       ?arg =arg
                                       (&&/analyse-1 analyse =return ?body))
            =lambda-type** (&type/clean =return =lambda-type*)
            =bound-arg (&type/lookup =arg)
            =lambda-type (matchv ::M/objects [=arg =bound-arg]
                           [["lux;VarT" id] ["lux;Some" bound]]
                           (&type/clean =arg =lambda-type**)

                           [["lux;VarT" id] ["lux;None" _]]
                           (let [var-name (str (gensym ""))
                                 bound (&/V "lux;BoundT" var-name)]
                             (|do [_ (&type/reset id bound)
                                   lambda-type (&type/clean =arg =lambda-type**)]
                               (return (&/V "lux;AllT" (&/T (&/|list) "" var-name lambda-type))))))]
        (return (&/|list (&/V "Expression" (&/T (&/V "lambda" (&/T =scope =captured =body)) =lambda-type))))))))

(defn analyse-def [analyse exo-type ?name ?value]
  ;; (prn 'analyse-def ?name ?value)
  (|do [_ (&type/check &type/Nothing exo-type)
        module-name &/get-module-name]
    (&/if% (&&def/defined? module-name ?name)
           (fail (str "[Analyser Error] Can't redefine " ?name))
           (|do [=value (&/with-scope ?name
                          (&&/with-var
                            #(&&/analyse-1 analyse % ?value)))
                 =value-type (&&/expr-type =value)
                 :let [_ (prn 'analyse-def ?name (&type/show-type =value-type))]
                 _ (&&def/define module-name ?name =value-type)]
             (return (&/|list (&/V "Statement" (&/V "def" (&/T ?name =value)))))))))

(defn analyse-declare-macro [exo-type ident]
  (|let [[?module ?name] ident]
    (|do [module-name &/get-module-name]
      (if (= ?module module-name)
        (|do [_ (&&def/declare-macro ?module ?name)]
          (return (&/|list)))
        (fail "Can't declare macros from foreign modules.")))))

(defn analyse-import [analyse exo-type ?path]
  (assert false)
  (return (&/|list)))

(defn analyse-check [analyse eval! exo-type ?type ?value]
  (println "analyse-check#0")
  (|do [=type (&&/analyse-1 analyse &type/Type ?type)
         :let [_ (println "analyse-check#1")]
         ==type (eval! =type)
         _ (&type/check exo-type ==type)
         :let [_ (println "analyse-check#4" (&type/show-type ==type))]
         =value (&&/analyse-1 analyse ==type ?value)
         :let [_ (println "analyse-check#5")]]
    (matchv ::M/objects [=value]
      [["Expression" [?expr ?expr-type]]]
      (|do [:let [_ (println "analyse-check#6" (&type/show-type ?expr-type))]
             _ (&type/check ==type ?expr-type)
             :let [_ (println "analyse-check#7")]]
        (return (&/|list (&/V "Expression" (&/T ?expr ==type))))))))

(defn analyse-coerce [analyse eval! exo-type ?type ?value]
  (|do [=type (&&/analyse-1 analyse &type/Type ?type)
         ==type (eval! =type)
         =value (&&/analyse-1 analyse ==type ?value)]
    (matchv ::M/objects [=value]
      [["Expression" [?expr ?expr-type]]]
      (return (&/|list (&/V "Expression" (&/T ?expr ==type)))))))
