(ns com.walmartlabs.lacinia.pedestal.interceptors
  "Utilities for building interceptor chains that are ordered based on dependencies."
  {:added "0.3.0"}
  (:require
    [com.stuartsierra.dependency :as d]))

(defn ordered-after
  "Adds metadata to an interceptor to identify its dependencies.
  The interceptor will be ordered after all of its dependencies.

  Dependencies are a seq of keywords.

  ordered-after is additive, invoking it multiple times will
  accumulate dependencies.

  Returns the interceptor with updated metadata."
  [interceptor dependencies]
  {:pre [(some? interceptor)
         (seq dependencies)]}
  (vary-meta interceptor
             (fn [m]
               (update m ::dependencies
                       #(into (or % #{}) dependencies)))))

(defn dependencies
  "Returns the set of dependencies for an interceptor, as provided by [[ordered-after]]."
  [interceptor]
  (-> interceptor meta ::dependencies))

(defn order-by-dependency
  "Orders an interceptor *map* by dependencies.
  The keys of the map are arbitrary keywords (generally the same as the :name
  key of the interceptor map), and each value is an interceptor that has been
  augmented via [[ordered-after]].

  The result is an ordered list of just the non-empty interceptors.

  By intention, it is possible to remove an interceptor before ordering by
  `assoc`-ing nil, or an empty map, into the dependency map. The distinction
  between nil and empty exists because an empty map can still have dependencies
  on other interceptors, but nil can not, which may affect the ordering of
  intereceptors that depend on the removed interceptor."
  [dependency-map]
  (let [reducer (fn [g dep-name interceptor]
                  (reduce #(d/depend %1 dep-name %2)
                          g
                          (dependencies interceptor)))]
    (->> dependency-map
         (reduce-kv reducer (d/graph))
         ;; Note: quietly ignore dependencies to unknown nodes, which is a feature
         ;; (you can remove an interceptor entirely even if other interceptors depend
         ;; on it) ... though probably better to use splice now.
         d/topo-sort
         (map #(get dependency-map %))
         ;; When dealing with dependencies, you might replace a dependency with
         ;; an empty map that, perhaps, has dependencies on some new dependency.
         (remove empty?)
         vec)))

(defn as-dependency-map
  "Given a seq of interceptors, returns a map of the interceptors keyed on the :name
  of each interceptor.  This can then be passed to [[order-by-dependency]]."
  [interceptors]
  (zipmap (map :name interceptors) interceptors))

(defn splice
  "Overwrites an existing interceptor in the map with a replacement interceptor.

  Throws an exception if the interceptor does not already exist.

  The replacement's dependencies are merged with the dependency it is replacing: this
  ensures that interceptor ordering is not affected, even if it was based on transitive
  dependencies through the replaced interceptor."
  {:added "0.4.0"}
  [dependency-map interceptor-name replacement]
  (update dependency-map interceptor-name
          (fn [existing]
            (when (nil? existing)
              (throw (ex-info "Unknown interceptor for splice."
                              {:dependency-map dependency-map
                               :interceptor-name interceptor-name})))

            (ordered-after replacement (dependencies existing)))))
