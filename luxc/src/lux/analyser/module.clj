(ns lux.analyser.module
  (:refer-clojure :exclude [alias])
  (:require (clojure [string :as string]
                     [template :refer [do-template]])
            clojure.core.match
            clojure.core.match.array
            (lux [base :as & :refer [defvariant deftuple |let |do return return* |case]]
                 [type :as &type]
                 [host :as &host])
            [lux.host.generics :as &host-generics]))

;; [Utils]
;; ModuleState
(defvariant
  ("Active" 0)
  ("Compiled" 0)
  ("Cached" 0))

;; Module
(deftuple
  ["module-hash"
   "module-aliases"
   "defs"
   "imports"
   "tags"
   "types"
   "module-annotations"
   "module-state"])

(defn ^:private new-module [hash]
  (&/T [;; lux;module-hash
        hash
        ;; "lux;module-aliases"
        (&/|table)
        ;; "lux;defs"
        (&/|table)
        ;; "lux;imports"
        &/$Nil
        ;; "lux;tags"
        (&/|table)
        ;; "lux;types"
        (&/|table)
        ;; module-annotations
        &/$None
        ;; "module-state"
        $Active]
       ))

(do-template [<flagger> <asker> <tag>]
  (do (defn <flagger>
        "(-> Text (Lux Any))"
        [module-name]
        (fn [state]
          (let [state* (&/update$ &/$modules
                                  (fn [modules]
                                    (&/|update module-name
                                               (fn [=module]
                                                 (&/set$ $module-state <tag> =module))
                                               modules))
                                  state)]
            (&/$Right (&/T [state* &/unit-tag])))))
    (defn <asker>
      "(-> Text (Lux Bit))"
      [module-name]
      (fn [state]
        (if-let [=module (->> state (&/get$ &/$modules) (&/|get module-name))]
          (&/$Right (&/T [state (|case (&/get$ $module-state =module)
                                  (<tag>) true
                                  _       false)]))
          (&/$Right (&/T [state false])))
        )))

  flag-active-module   active-module?   $Active
  flag-compiled-module compiled-module? $Compiled
  flag-cached-module   cached-module?   $Cached
  )

;; [Exports]
(defn add-import
  "(-> Text (Lux Null))"
  [module]
  (|do [current-module &/get-module-name]
    (fn [state]
      (if (&/|member? module (->> state (&/get$ &/$modules) (&/|get current-module) (&/get$ $imports)))
        ((&/fail-with-loc (str "[Analyser Error] Cannot import module " (pr-str module) " twice @ " current-module))
         state)
        (return* (&/update$ &/$modules
                            (fn [ms]
                              (&/|update current-module
                                         (fn [m] (&/update$ $imports (partial &/$Cons module) m))
                                         ms))
                            state)
                 nil)))))

(defn set-imports
  "(-> (List Text) (Lux Null))"
  [imports]
  (|do [current-module &/get-module-name]
    (fn [state]
      (return* (&/update$ &/$modules
                          (fn [ms]
                            (&/|update current-module
                                       (fn [m] (&/set$ $imports imports m))
                                       ms))
                          state)
               nil))))

(defn define-alias [module name de-aliased]
  (fn [state]
    (|case (&/get$ &/$scopes state)
      (&/$Cons ?env (&/$Nil))
      (return* (->> state
                    (&/update$ &/$modules
                               (fn [ms]
                                 (&/|update module
                                            (fn [m]
                                              (&/update$ $defs
                                                         #(&/|put name (&/$Left de-aliased) %)
                                                         m))
                                            ms))))
               nil)
      
      _
      ((&/fail-with-loc (str "[Analyser Error] Cannot create a new global definition outside of a global environment: " (str module &/+name-separator+ name)))
       state))))

(defn define [module name exported? def-type def-meta def-value]
  (fn [state]
    (when (and (= "Macro'" name) (= "lux" module))
      (&type/set-macro*-type! def-value))
    (|case (&/get$ &/$scopes state)
      (&/$Cons ?env (&/$Nil))
      (return* (->> state
                    (&/update$ &/$modules
                               (fn [ms]
                                 (&/|update module
                                            (fn [m]
                                              (&/update$ $defs
                                                         #(&/|put name (&/$Right (&/T [exported? def-type def-meta def-value])) %)
                                                         m))
                                            ms))))
               nil)
      
      _
      ((&/fail-with-loc (str "[Analyser Error] Cannot create a new global definition outside of a global environment: " (str module &/+name-separator+ name)))
       state))))

(defn type-def
  "(-> Text Text (Lux [Bit Type]))"
  [module name]
  (fn [state]
    (if-let [$module (->> state (&/get$ &/$modules) (&/|get module))]
      (if-let [$def (->> $module (&/get$ $defs) (&/|get name))]
        (|case $def
          (&/$Left [o-module o-name])
          ((type-def o-module o-name) state)
          
          (&/$Right [exported? ?type ?meta ?value])
          (if (&type/type= &type/Type ?type)
            (return* state (&/T [exported? ?value]))
            ((&/fail-with-loc (str "[Analyser Error] Not a type: " (&/ident->text (&/T [module name]))
                                   "\nMETA: " (&/show-ast ?meta)))
             state)))
        ((&/fail-with-loc (str "[Analyser Error] Unknown definition: " (&/ident->text (&/T [module name]))))
         state))
      ((&/fail-with-loc (str "[Analyser Error] Unknown module: " module))
       state))))

(defn exists?
  "(-> Text (Lux Bit))"
  [name]
  (fn [state]
    (return* state
             (->> state (&/get$ &/$modules) (&/|contains? name)))))

(defn dealias [name]
  (|do [current-module &/get-module-name]
    (fn [state]
      (if-let [real-name (->> state (&/get$ &/$modules) (&/|get current-module) (&/get$ $module-aliases) (&/|get name))]
        (return* state real-name)
        ((&/fail-with-loc (str "[Analyser Error] Unknown alias: " name))
         state)))))

(defn alias [module alias reference]
  (fn [state]
    (let [_module_ (->> state (&/get$ &/$modules) (&/|get module))]
      (if (&/|member? module (->> _module_ (&/get$ $imports)))
        ((&/fail-with-loc (str "[Analyser Error] Cannot create alias that is the same as a module nameL " (pr-str alias) " for " reference))
         state)
        (if-let [real-name (->> _module_ (&/get$ $module-aliases) (&/|get alias))]
          ((&/fail-with-loc (str "[Analyser Error] Cannot re-use alias \"" alias "\" @ " module))
           state)
          (return* (->> state
                        (&/update$ &/$modules
                                   (fn [ms]
                                     (&/|update module
                                                #(&/update$ $module-aliases
                                                            (fn [aliases]
                                                              (&/|put alias reference aliases))
                                                            %)
                                                ms))))
                   nil))))
    ))

(defn ^:private imports? [state imported-module-name source-module-name]
  (->> state
       (&/get$ &/$modules)
       (&/|get source-module-name)
       (&/get$ $imports)
       (&/|any? (partial = imported-module-name))))

(defn get-anns [module-name]
  (fn [state]
    (if-let [module (->> state
                         (&/get$ &/$modules)
                         (&/|get module-name))]
      (return* state (&/get$ $module-annotations module))
      ((&/fail-with-loc (str "[Analyser Error] Module does not exist: " module-name))
       state))))

(defn set-anns [anns module-name]
  (fn [state]
    (return* (->> state
                  (&/update$ &/$modules
                             (fn [ms]
                               (&/|update module-name
                                          #(&/set$ $module-annotations (&/$Some anns) %)
                                          ms))))
             nil)))

(defn find-def! [module name]
  (|do [current-module &/get-module-name]
    (fn [state]
      (if-let [$module (->> state (&/get$ &/$modules) (&/|get module))]
        (if-let [$def (->> $module (&/get$ $defs) (&/|get name))]
          (|case $def
            (&/$Left [?r-module ?r-name])
            ((find-def! ?r-module ?r-name)
             state)

            (&/$Right $def*)
            (return* state (&/T [(&/T [module name]) $def*])))
          ((&/fail-with-loc (str "[Analyser Error @ find-def!] Definition does not exist: " (str module &/+name-separator+ name)
                                 " at module: " current-module))
           state))
        ((&/fail-with-loc (str "[Analyser Error @ find-def!] Module does not exist: " module
                               " at module: " current-module))
         state)))))

(defn find-def [module name]
  (|do [current-module &/get-module-name]
    (fn [state]
      (if-let [$module (->> state (&/get$ &/$modules) (&/|get module))]
        (if-let [$def (->> $module (&/get$ $defs) (&/|get name))]
          (|case $def
            (&/$Left [?r-module ?r-name])
            (if (.equals ^Object current-module module)
              ((find-def! ?r-module ?r-name)
               state)
              ((&/fail-with-loc (str "[Analyser Error @ find-def] Cannot use (private) alias: " (str module &/+name-separator+ name)
                                     " at module: " current-module))
               state))
            
            (&/$Right [exported? ?type ?meta ?value])
            (if (or (.equals ^Object current-module module)
                    (and exported?
                         (or (.equals ^Object module "lux")
                             (imports? state module current-module))))
              (return* state (&/T [(&/T [module name])
                                   (&/T [exported? ?type ?meta ?value])]))
              ((&/fail-with-loc (str "[Analyser Error @ find-def] Cannot use private definition: " (str module &/+name-separator+ name)
                                     " at module: " current-module))
               state)))
          ((&/fail-with-loc (str "[Analyser Error @ find-def] Definition does not exist: " (str module &/+name-separator+ name)
                                 " at module: " current-module))
           state))
        ((&/fail-with-loc (str "[Analyser Error @ find-def] Module does not exist: " module
                               " at module: " current-module))
         state)))))

(defn defined? [module name]
  (&/try-all% (&/|list (|do [_ (find-def! module name)]
                         (return true))
                       (return false))))

(defn create-module
  "(-> Text Hash-Code (Lux Null))"
  [name hash]
  (fn [state]
    (return* (->> state
                  (&/update$ &/$modules #(&/|put name (new-module hash) %))
                  (&/set$ &/$scopes (&/|list (&/env name &/$Nil)))
                  (&/set$ &/$current-module (&/$Some name)))
             nil)))

(do-template [<name> <tag> <type>]
  (defn <name>
    <type>
    [module]
    (fn [state]
      (if-let [=module (->> state (&/get$ &/$modules) (&/|get module))]
        (return* state (&/get$ <tag> =module))
        ((&/fail-with-loc (str "[Lux Error] Unknown module: " module))
         state))
      ))

  tags-by-module  $tags        "(-> Text (Lux (List (, Text (, Int (List Text) Type)))))"
  types-by-module $types       "(-> Text (Lux (List (, Text (, (List Text) Type)))))"
  module-hash     $module-hash "(-> Text (Lux Int))"
  )

(def imports
  (|do [module &/get-module-name
        _imports (fn [state]
                   (return* state (->> state (&/get$ &/$modules) (&/|get module) (&/get$ $imports))))]
    (&/map% (fn [_module]
              (|do [_hash (module-hash _module)]
                (return (&/T [_module _hash]))))
            _imports)))

(defn ensure-undeclared-tags [module tags]
  (|do [tags-table (tags-by-module module)
        _ (&/map% (fn [tag]
                    (if (&/|get tag tags-table)
                      (&/fail-with-loc (str "[Analyser Error] Cannot re-declare tag: " (&/ident->text (&/T [module tag]))))
                      (return nil)))
                  tags)]
    (return nil)))

(defn ensure-undeclared-type [module name]
  (|do [types-table (types-by-module module)
        _ (&/assert! (nil? (&/|get name types-table))
                     (str "[Analyser Error] Cannot re-declare type: " (&/ident->text (&/T [module name]))))]
    (return nil)))

(defn declare-tags
  "(-> Text (List Text) Bit Type (Lux Null))"
  [module tag-names was-exported? type]
  (|do [_ (ensure-undeclared-tags module tag-names)
        type-name (&type/type-name type)
        :let [[_module _name] type-name]
        _ (&/assert! (= module _module)
                     (str "[Module Error] Cannot define tags for a type belonging to a foreign module: " (&/ident->text type-name)))
        _ (ensure-undeclared-type _module _name)]
    (fn [state]
      (if-let [=module (->> state (&/get$ &/$modules) (&/|get module))]
        (let [tags (&/|map (fn [tag-name] (&/T [module tag-name])) tag-names)]
          (return* (&/update$ &/$modules
                              (fn [=modules]
                                (&/|update module
                                           #(->> %
                                                 (&/set$ $tags (&/fold (fn [table idx+tag-name]
                                                                         (|let [[idx tag-name] idx+tag-name]
                                                                           (&/|put tag-name (&/T [idx tags was-exported? type]) table)))
                                                                       (&/get$ $tags %)
                                                                       (&/enumerate tag-names)))
                                                 (&/update$ $types (partial &/|put _name (&/T [tags was-exported? type]))))
                                           =modules))
                              state)
                   nil))
        ((&/fail-with-loc (str "[Lux Error] Unknown module: " module))
         state)))))

(defn ensure-can-see-tag
  "(-> Text Text (Lux Any))"
  [module tag-name]
  (|do [current-module &/get-module-name]
    (fn [state]
      (if-let [=module (->> state (&/get$ &/$modules) (&/|get module))]
        (if-let [^objects idx+tags+exported+type (&/|get tag-name (&/get$ $tags =module))]
          (|let [[?idx ?tags ?exported ?type] idx+tags+exported+type]
            (if (or ?exported
                    (= module current-module))
              (return* state &/unit-tag)
              ((&/fail-with-loc (str "[Analyser Error] Cannot access tag #" (&/ident->text (&/T [module tag-name])) " from module " current-module))
               state)))
          ((&/fail-with-loc (str "[Module Error] Unknown tag: " (&/ident->text (&/T [module tag-name]))))
           state))
        ((&/fail-with-loc (str "[Module Error] Unknown module: " module))
         state)))))

(do-template [<name> <part> <doc>]
  (defn <name>
    <doc>
    [module tag-name]
    (fn [state]
      (if-let [=module (->> state (&/get$ &/$modules) (&/|get module))]
        (if-let [^objects idx+tags+exported+type (&/|get tag-name (&/get$ $tags =module))]
          (|let [[?idx ?tags ?exported ?type] idx+tags+exported+type]
            (return* state <part>))
          ((&/fail-with-loc (str "[Module Error] Unknown tag: " (&/ident->text (&/T [module tag-name]))))
           state))
        ((&/fail-with-loc (str "[Module Error] Unknown module: " module))
         state))))

  tag-index ?idx  "(-> Text Text (Lux Int))"
  tag-group ?tags "(-> Text Text (Lux (List Ident)))"
  tag-type  ?type "(-> Text Text (Lux Type))"
  )

(def defs
  (|do [module &/get-module-name]
    (fn [state]
      (return* state (->> state (&/get$ &/$modules) (&/|get module) (&/get$ $defs))))))

(defn fetch-imports [imports]
  (|case imports
    [_ (&/$Tuple _parts)]
    (&/map% (fn [_part]
              (|case _part
                [_ (&/$Tuple (&/$Cons [[_ (&/$Text _module)]
                                       (&/$Cons [[_ (&/$Text _alias)]
                                                 (&/$Nil)])]))]
                (return (&/T [_module _alias]))

                _
                (&/fail-with-loc "[Analyser Error] Incorrect import syntax.")))
            _parts)

    _
    (&/fail-with-loc "[Analyser Error] Incorrect import syntax.")))

(def ^{:doc "(Lux (List [Text (List Text)]))"}
  tag-groups
  (|do [module &/get-current-module]
    (return (&/|map (fn [pair]
                      (|case pair
                        [name [tags exported? _]]
                        (&/T [name (&/|map (fn [tag]
                                             (|let [[t-prefix t-name] tag]
                                               t-name))
                                           tags)])))
                    (&/get$ $types module)))))
