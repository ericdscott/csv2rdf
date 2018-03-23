(ns csv2rdf.util
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json])
  (:import [java.io BufferedReader]
           [java.net URI]
           [java.lang.reflect InvocationTargetException Method]))

(defn read-lines
  "Eagerly reads the lines from the given source."
  [source]
  (let [r (io/reader source)]
    (with-open [r (if (instance? BufferedReader r) r (BufferedReader. r))]
      (into [] (line-seq r)))))

(defmacro ignore-exceptions [& body]
  `(try
     ~@body
     (catch Exception ex# nil)))

(defn remove-fragment [uri]
  (URI. (.getScheme uri) (.getUserInfo uri) (.getHost uri) (.getPort uri) (.getPath uri) (.getQuery uri) nil))

(defn ->coll [x]
  (if (coll? x) x [x]))

;;TODO: make into spec?
(defn non-negative?
  "Whether the given value is a non-negative number."
  [x]
  (and (number? x)
       (or (zero? x)
           (pos? x))))

(defn read-json [source]
  (with-open [r (io/reader source)]
    (json/read r)))

(defn select-keys-as
  "Selects keys from the source map m, renaming them to the mapped keys in the result map e.g.

  (select-keys-as {\"foo\" 1 \"bar\" 2} {\"foo\" :a \"quux\" :b})
  => {:a 1}"
  [m key-mapping]
  (reduce (fn [acc [source-key dest-key]]
            (if (contains? m source-key)
              (assoc acc dest-key (get m source-key))
              acc))
          {}
          key-mapping))

(defn filter-values
  "Filters a map given a predicate on the values."
  [pred m]
  (into {} (filter (fn [[_k v]] (pred v)) m)))

(defn partition-keys
  "Splits a source map into two sub-maps - the first contain the keys in both source-map and ref-map,
   the second contains the keys in source-map which do not exist in ref-map."
  [source-map ref-keys]
  [(select-keys source-map ref-keys) (apply dissoc source-map ref-keys)])

(defn get-declared-method [cls method-name arg-types]
  (let [m (.getDeclaredMethod cls method-name (into-array Class arg-types))]
    (.setAccessible m true)
    m))

(defn invoke-method
  "Invokes the given Method instance with the given arguments and receiver if one is specified. Unwraps the inner
   exception if an InvocationTargetException is thrown."
  ([method args] (invoke-method method nil args))
  ([^Method method receiver args]
   (try
     (.invoke method receiver (into-array Object args))
     (catch InvocationTargetException ex
       (throw (.getCause ex))))))

(defn map-keys [f m]
  (into {} (map (fn [[k v]] [(f k) v]) m)))

(defn equals-ignore-case?
  "Whether two string are equal ignoring case differences. Nil references are considered equal."
  [s1 s2]
  (if (nil? s1)
    (nil? s2)
    (.equalsIgnoreCase s1 s2)))

(defn assoc-if
  "Associates v with the key k in the associative structure m if condition is truthy, otherwise returns m."
  [m condition k v]
  (if condition
    (assoc m k v)
    m))