(ns cascalog.in-memory.platform
  (:require [cascalog.logic.predicate]
            [cascalog.logic.platform :refer
             (compile-query IPlatform platform-generator? generator to-generator)]
            [cascalog.logic.parse :as parse]
            [jackknife.core :as u]
            [jackknife.seq :as s]
            [cascalog.logic.def :as d]
            [cascalog.logic.vars :as v]
            [flatland.ordered.map :as o])
  (:import [cascalog.logic.parse TailStruct Projection Application
            FilterApplication Unique Join Grouping Rename]
           [cascalog.logic.predicate Generator RawSubquery]
           [cascalog.logic.def ParallelAggregator ParallelBuffer]
           [jcascalog Subquery]
           [java.util LinkedHashMap Map$Entry]
           [clojure.lang IFn IPersistentMap ITransientMap IPersistentCollection]))


(defn linked-map []
  (proxy [LinkedHashMap IFn ITransientMap IPersistentCollection] []
    (invoke
      ([key] (get this key))
      ([key not-found] (get this key not-found)))
    (valAt
      ([key] (.valAt this key nil))
      ([key not-found] (if-let [v (.get this key)]
                         v
                         not-found)))
    (cons [obj]
      (condp instance? obj
        Map$Entry (.put this (.getKey obj) (.getValue obj))
        (reduce (fn [m e]
                  (.put m (.getKey e) (.getValue e)))
                this
              obj)))
    (conj [e]
        (let [[k v] e]
          (.put this k v)))
    (seq []
      (keep identity (.entrySet this)))))

(defn to-tuple
  [names v]
  (if (= (count names) (count v))
    (let [kv-pairs (map (fn [v1 v2] [v1 v2]) names v)]
      (into (o/ordered-map) kv-pairs))
    (u/throw-illegal "Output variables arity and function output arity do not match")))

(defn to-tuples
  "turns [\"n\"] and [[1] [2]] into [{\"n\" 1} {\"n\" 2}]"
  [names coll-of-seqs]
  ;;(prn "to-tuples")
  (map #(to-tuple names %) coll-of-seqs))

(defn valid-tuple?
  "Verifies that non-nullable vars aren't null."
  [tuple]
  ;;(prn "vali-tuple?")
  (not-any?
   (fn [[k v]]
     (and (v/non-nullable-var? k)
          (nil? v)))
   tuple))

(defn to-tuples-filter-nullable
  "turns [\"n\"] and [[1] [2]] into [{\"n\" 1} {\"n\" 2}]"
  [names coll-of-seqs]
  (->> coll-of-seqs
       (map
        (fn [s]
          (let [tuple (to-tuple names s)]
            (if (valid-tuple? tuple)
              tuple))))
       (remove nil?)))

(defn select-fields
  "Creates a list of the values of the tuples you want and if the field isn't
   found it's nil.
   For examples: (select-fields [:b :a :d] {:a 1 :b 2 :c 3}) => (2 1 nil)"
  [fields tuple]
  (map #(tuple %) fields))

(defn select-fields-w-default
  "Just like select-fields but if a value isn't found, its value is
   the name of the field.
   For examples: (select-fields [:b :a 100] {:a 1 :b 2 :c 3}) => (2 1 100)"
  [fields tuple]
  (map #(get tuple % %) fields))

;; Projection
(defn extract-values
  "Creates a collection of vectors for the values of the fields
   you have selected"
  [fields tuples]
  (map #(vec (select-fields fields %))  tuples))

(defn inner-join
  "Inner joins two maps that both have been grouped by the same function.
   This is an inner join, so nils are discarded."
  [l-grouped r-grouped]
  (letfn [(join-fn [l-group]
            (let [key (first l-group)
                  l-tuples (second l-group)]
              (if-let [r-tuples (get r-grouped key)]
                (for [x l-tuples y r-tuples] (merge x y)))))]
    (flatten ;; the for returned a collection which we need to flatten
     (remove nil? ;; nils are discarded
             (map join-fn l-grouped)))))

(defn left-join
  [l-grouped r-grouped r-fields]
  (letfn [(join-fn [l-group]
            (let [key (first l-group)
                  l-tuples (second l-group)
                  r-empty-tuples [(to-tuple r-fields (repeat (count r-fields) nil))]
                  r-tuples (get r-grouped key r-empty-tuples)]
              (for [x l-tuples y r-tuples]
                ;; merge is specifically ordered, because the left
                ;; tuple takes precedence over the right one (which
                ;; could be nil)
                (merge y x))))]
    (flatten ;; the for returned a collection which we need to flatten
     (remove nil? ;; nils are discarded
             (map join-fn l-grouped)))))

(defn left-excluding-join
  "A left join that only returns values where the right side is nil"
  [l-grouped r-grouped r-fields]
  (letfn [(join-fn [l-group]
            (let [key (first l-group)
                  l-tuples (second l-group)
                  r-empty-tuples [(to-tuple r-fields (repeat (count r-fields) nil))]]
              (if (not (find r-grouped key))
                (for [x l-tuples y r-empty-tuples]
                  ;; merge is specifically ordered, because the left
                  ;; tuple takes precedence over the right one (which
                  ;; could be nil)
                  (merge y x)))))]
    (flatten ;; the for returned a collection which we need to flatten
     (remove nil? ;; nils are discarded
             (map join-fn l-grouped)))))

(defn outer-join
  [l-grouped r-grouped l-fields r-fields]
  (let [inner (inner-join l-grouped r-grouped)
        left (left-excluding-join l-grouped r-grouped r-fields)
        right (left-excluding-join r-grouped l-grouped l-fields)]
    (concat inner left right)))

(defn join
  "Dispatches to all the different join types and returns a vector
   with the collection of joined tuples and the type of join"
  [l-grouped r-grouped l-type r-type l-fields r-fields]
  (cond
   (and (= :inner l-type) (= :inner r-type))
   [(inner-join l-grouped r-grouped) :inner]
   (and (= :inner l-type) (= :outer r-type))
   [(left-join l-grouped r-grouped r-fields) :outer]
   (and (= :outer l-type) (= :inner r-type))
   [(left-join r-grouped l-grouped l-fields) :outer]
   :else [(outer-join l-grouped r-grouped l-fields r-fields) :outer]))

(defn smallest-arity [fun]
  "Returns the smallest number of arguments the function takes"
  (->> fun meta :arglists first count))

(defn tuple-sort
  [tuples sort-fields reverse?]
  (if sort-fields
    (let [sorted (sort-by #(vec (map % sort-fields)) tuples)]
      (if reverse?
        (reverse sorted)
        sorted))
    tuples))

(defmulti op-clojure
  (fn [coll op input output]
    (type op)))

(defmethod op-clojure ::d/map
  [coll op input output]
  (map
   (fn [tuple]
     (let [v (s/collectify (apply op (select-fields-w-default input tuple)))
           new-tuple (to-tuple output v)]
       (merge tuple new-tuple)))
   coll))

(defmethod op-clojure ::d/mapcat
  [coll op input output]
  (mapcat
   (fn [tuple]
     (let [v (apply op (select-fields-w-default input tuple))
           new-tuples (map #(to-tuple output (s/collectify %)) v)]
       (map #(merge tuple %) new-tuples)))
   coll))

(defmulti agg-clojure
  (fn [coll op]
    (type op)))

(defmethod agg-clojure ::d/buffer
  [coll op]
  (if (d/prepared? op)
    (let [
          ;; Allows Cascading to pass in a
          ;; FlowProcess and an OperationCall,
          ;; but we'll just use nils
          fun (op nil nil)
          operation (:operate fun)]
      (operation coll))
    (op coll)))

(defmethod agg-clojure ::d/bufferiter
  [coll op]
  ;; coll is a lazy-seq but the operatation expects an iterator
  ;; so we need to convert it to one
  (op (.iterator coll)))

(defmethod agg-clojure ::d/aggregate
  [coll op]
  (op (reduce (fn [s v] (op s (first v))) (op) coll)))

(defmethod agg-clojure ParallelAggregator
  [coll op]
  (let [{:keys [combine-var init-var present-var]} op
        mapped-coll (map
                     #(->> %
                           (take (smallest-arity init-var))
                           (apply init-var))
                     coll)
        reduced-coll (reduce
                      (fn [state s]
                        (->> s
                             (s/collectify)
                             (apply conj (s/collectify state))
                             (apply combine-var)))
                      (first mapped-coll)
                      (rest mapped-coll))]
    reduced-coll))

(defmethod agg-clojure ParallelBuffer
  [coll {:keys [init-var combine-var present-var buffer-var]}]
  (buffer-var coll))

(defrecord InMemoryPlatform []
  IPlatform
  (generator? [_ x]
    (platform-generator? x))

  (generator-builder [_ gen output options]
    (to-tuples-filter-nullable output (generator gen)))

  (run! [p _ _]
    (u/throw-illegal (str p " doesn't have an implementation for run!")))

  (run-to-memory! [_ _ queries]
    (map compile-query queries)))

(defmethod generator [InMemoryPlatform clojure.lang.IPersistentVector]
  [v]
  (generator (or (seq v) ())))

(defmethod generator [InMemoryPlatform clojure.lang.ISeq]
  [v] v) 

(defmethod generator [InMemoryPlatform java.util.ArrayList]
  [coll]
  (generator (into [] coll)))

;; These generators act differently than the ones above
(defmethod generator [InMemoryPlatform TailStruct]
  [sq]
  ;;(prn "sq is " sq)
  (compile-query sq))

(defmethod generator [InMemoryPlatform RawSubquery]
  [sq]
  (generator (parse/build-rule sq)))

(defmethod to-generator [InMemoryPlatform Subquery]
  [sq]
  (generator (.getCompiledSubquery sq)))

(defmethod to-generator [InMemoryPlatform Projection]
  [{:keys [source fields]}]
  ;;(prn "to-generator projection " source fields)
  ;; TODO: this is a hacky way of filtering the tuple to just the
  ;; fields we want
  (->> (extract-values fields source)
       (remove nil?)
       (to-tuples fields)))

(defmethod to-generator [InMemoryPlatform Generator]
  [{:keys [gen]}]
;;  (prn "to-generator " gen)
  gen)

(defmethod to-generator [InMemoryPlatform Rename]
  [{:keys [source fields] :as a}]
;;  (prn "to-generator rename all is " a)
;;  (prn "to-generator rename " source fields)
  (let [results  (map
                  (fn [tuple]
                    ;;(prn "tuple is " tuple " and fileds are " fields)
                    ;; TODO: extracting the vals from a map and assuming they have
                    ;; a specific order is dangerous since the order could be
                    ;; different than the expected tuple order
                    (let [vals (map (fn [[k v]] v) tuple)]
                      (to-tuple fields vals)))
                  source)]
;;    (prn "results are " results)
    results))

(defmethod to-generator [InMemoryPlatform Application]
  [{:keys [source operation]}]
  (let [{:keys [op input output]} operation]
    (op-clojure source op input output)))

(defmethod to-generator [InMemoryPlatform FilterApplication]
  [{:keys [source filter]}]
  (let [{:keys [op input]} filter]
    (clojure.core/filter
     #(apply op (select-fields-w-default input %))
     source)))

(defmethod to-generator [InMemoryPlatform Unique]
  [{:keys [source fields options]}]
  (let [{:keys [sort reverse]} options
        coll (map #(select-fields fields %) source)
        distinct-coll (distinct coll)
        tuples (to-tuples fields distinct-coll)]
    (tuple-sort tuples sort reverse)))

(defmethod to-generator [InMemoryPlatform Join]
  [{:keys [sources join-fields type-seq options]}]
  (loop [loop-sources sources
         loop-join-fields join-fields
         loop-type-seq type-seq]
    (let [
          ;; extract the vars we want
          [l-source r-source & rest-sources] loop-sources
          [l-type-seq r-type-seq & rest-type-seqs] loop-type-seq
          [l-fields l-type] l-type-seq
          [r-fields r-type] r-type-seq

          ;; setup the data for joining
          l-grouped (group-by #(vec (map % join-fields)) l-source)
          r-grouped (group-by #(vec (map % join-fields)) r-source)

          ;; join the data and setup the vars for the next loop
          j-fields (distinct (concat l-fields r-fields))
          [j-source j-type] (join l-grouped r-grouped
                                  l-type r-type
                                  l-fields r-fields)]
      ;; only recur if there are more sources to join
      (if (empty? rest-sources)
        j-source
        (recur
         (cons j-source rest-sources)
         loop-join-fields
         (cons [j-fields j-type] rest-type-seqs))))))

(defmethod to-generator [InMemoryPlatform Grouping]
  [{:keys [source aggregators grouping-fields options]}]
  (let [{:keys [sort reverse]} options
        grouped (group-by #(vec (map % grouping-fields)) source)]
    (mapcat
     (fn [[grouping-vals tuples]]
       (let [sorted-tuples (tuple-sort tuples sort reverse)
             agg-tuples (map
                         (fn [{:keys [op input output]}]
                           (let [coll (extract-values input sorted-tuples)
                                 r (s/collectify (agg-clojure coll op))
                                 r-seq (if (sequential? (first r)) r [r])]
                             (map #(to-tuple output %) r-seq)))
                         aggregators)
             merged-tuples (loop [[s1 s2 & s-rest] agg-tuples]
                             (if (or (empty? s1) (empty? s2))
                               (concat s1 s2)
                               (let [s-merge (for [x s1 y s2] (merge x y))]
                                 (if (empty? s-rest)
                                   s-merge
                                   (recur (cons s-merge s-rest))))))]
         (map #(merge (to-tuple grouping-fields grouping-vals) %) merged-tuples)))
     grouped)))

(defmethod to-generator [InMemoryPlatform TailStruct]
  [{:keys [node available-fields]}]
;;  (prn "to-generator Tailstruct " node available-fields)
  ;; TODO: if we want the fields on the structure, we don't need to
  ;; extract the values
  (extract-values available-fields node))
