(ns fulcro.client.impl.data-targeting
  (:require [clojure.set :as set]
            [fulcro.util :as util]))

(defn multiple-targets [& targets]
  (with-meta (vec targets) {::multiple-targets true}))

(defn prepend-to [target]
  (with-meta target {::prepend-target true}))

(defn append-to [target]
  (with-meta target {::append-target true}))

(defn replace-at [target]
  (with-meta target {::replace-target true}))

(defn replacement-target? [t] (-> t meta ::replace-target boolean))
(defn prepend-target? [t] (-> t meta ::prepend-target boolean))
(defn append-target? [t] (-> t meta ::append-target boolean))
(defn multiple-targets? [t] (-> t meta ::multiple-targets boolean))

(defn special-target? [target]
  (boolean (seq (set/intersection (-> target meta keys) #{::replace-target ::append-target ::prepend-target ::multiple-targets}))))

(defn integrate-ident
  "Integrate an ident into any number of places in the app state. This function is safe to use within mutation
  implementations as a general helper function.

  The named parameters can be specified any number of times. They are:

  - append:  A vector (path) to a list in your app state where this new object's ident should be appended. Will not append
  the ident if that ident is already in the list.
  - prepend: A vector (path) to a list in your app state where this new object's ident should be prepended. Will not append
  the ident if that ident is already in the list.
  - replace: A vector (path) to a specific location in app-state where this object's ident should be placed. Can target a to-one or to-many.
   If the target is a vector element then that element must already exist in the vector."
  [state ident & named-parameters]
  {:pre [(map? state)]}
  (let [actions (partition 2 named-parameters)]
    (reduce (fn [state [command data-path]]
              (let [already-has-ident-at-path? (fn [data-path] (some #(= % ident) (get-in state data-path)))]
                (case command
                  :prepend (if (already-has-ident-at-path? data-path)
                             state
                             (do
                               (assert (vector? (get-in state data-path)) (str "Path " data-path " for prepend must target an app-state vector."))
                               (update-in state data-path #(into [ident] %))))
                  :append (if (already-has-ident-at-path? data-path)
                            state
                            (do
                              (assert (vector? (get-in state data-path)) (str "Path " data-path " for append must target an app-state vector."))
                              (update-in state data-path conj ident)))
                  :replace (let [path-to-vector (butlast data-path)
                                 to-many?       (and (seq path-to-vector) (vector? (get-in state path-to-vector)))
                                 index          (last data-path)
                                 vector         (get-in state path-to-vector)]
                             (assert (vector? data-path) (str "Replacement path must be a vector. You passed: " data-path))
                             (when to-many?
                               (do
                                 (assert (vector? vector) "Path for replacement must be a vector")
                                 (assert (number? index) "Path for replacement must end in a vector index")
                                 (assert (contains? vector index) (str "Target vector for replacement does not have an item at index " index))))
                             (assoc-in state data-path ident))
                  (throw (ex-info "Unknown post-op to merge-state!: " {:command command :arg data-path})))))
            state actions)))

(defn process-target
  ([state source-path target] (process-target state source-path target true))
  ([state source-path target remove-ok?]
   {:pre [(vector? target)]}
   (let [item-to-place (cond (util/ident? source-path) source-path
                             (keyword? source-path) (get state source-path)
                             :else (get-in state source-path))
         many-idents?  (and (vector? item-to-place)
                            (every? util/ident? item-to-place))]
     (cond
       (and (util/ident? source-path)
            (not (special-target? target))) (-> state
                                                (assoc-in target item-to-place))
       (not (special-target? target)) (cond->
                                        (assoc-in state target item-to-place)
                                        remove-ok? (dissoc source-path))
       (multiple-targets? target) (cond-> (reduce (fn [s t] (process-target s source-path t false)) state target)
                                    (and (not (util/ident? source-path)) remove-ok?) (dissoc source-path))
       (and many-idents? (special-target? target)) (let [state            (if remove-ok?
                                                                            (dissoc state source-path)
                                                                            state)
                                                         target-has-many? (vector? (get-in state target))]
                                                     (if target-has-many?
                                                       (cond
                                                         (prepend-target? target) (update-in state target (fn [v] (vec (concat item-to-place v))))
                                                         (append-target? target) (update-in state target (fn [v] (vec (concat v item-to-place))))
                                                         :else state)
                                                       (assoc-in state target item-to-place)))
       (special-target? target) (cond-> state
                                  remove-ok? (dissoc source-path)
                                  (prepend-target? target) (integrate-ident item-to-place :prepend target)
                                  (append-target? target) (integrate-ident item-to-place :append target)
                                  (replacement-target? target) (integrate-ident item-to-place :replace target))
       :else state))))
