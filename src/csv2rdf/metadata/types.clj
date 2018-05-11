(ns csv2rdf.metadata.types
  (:require [csv2rdf.metadata.validator :refer [make-warning default-if-invalid variant invalid array-of kvps optional-key
                                                required-key invalid-key-pair any map-of one-of string invalid?
                                                chain try-parse-with where strict make-error tuple eq uri ignore-invalid
                                                type-error-message]]
            [csv2rdf.metadata.context :refer [resolve-uri append-path language-code-or-default
                                              base-key language-key id-key update-from-local-context with-document-uri]]
            [csv2rdf.json-ld :refer [expand-uri-string]]
            [csv2rdf.validation :as v]
            [csv2rdf.metadata.json :refer [array? object?] :as mjson]
            [csv2rdf.uri-template :as template]
            [clojure.string :as string]
            [csv2rdf.util :as util]
            [clojure.set :as set]
            [csv2rdf.json-ld :as json-ld]
            [csv2rdf.xml.datatype :as xml-datatype]
            [csv2rdf.logging :as logging])
  (:import [java.util Locale$Builder IllformedLocaleException]
           [java.net URI URISyntaxException]))

(def non-negative (variant {:number (where util/non-negative? "non-negative")}))

(defn valid-language-code? [^String s]
  (try
    (.setLanguageTag (Locale$Builder.) s)
    true
    (catch IllformedLocaleException _ex
      false)))

(defn language-code [context x]
  (if (string? x)
    (if (valid-language-code? x)
      x
      (make-warning context (str "Invalid language code: '" x "'") invalid))
    (make-warning context (str "Expected language code, got " (mjson/get-json-type-name x)) invalid)))

(defn ^{:metadata-spec "6.6"} normalise-string-natural-language-property [context code]
  {(language-code-or-default context) [code]})

(defn ^{:metadata-spec "6.6"} normalise-array-natural-language-property [context codes]
  {(language-code-or-default context) codes})

(def ^{:metadata-spec "5.1.6"
       :doc "Validates a value in a natual language property defined as an object. Values can
        be either strings or string arrays, and returns an array if valid."} language-code-map-value
  (variant {:string (fn [_context s] [s])
            :array (array-of string)
            :default []}))

(defn ^{:metadata-spec "5.1.6"} natural-language
  "Parses a natural language property according to the specification. Normalises the result into a map of language
   codes to an array of associated values. Uses the default language if none specified in the context."
  [context x]
  (cond
    (string? x) (normalise-string-natural-language-property context x)
    (array? x) (let [values ((array-of string) context x)]
                 (normalise-array-natural-language-property context values))
    (object? x) ((map-of language-code language-code-map-value) context x)
    :else (make-warning context "Expected string, array or object for natual language property" {(language-code-or-default context) []})))

(def default-uri (URI. ""))

(defn ^{:metadata-spec "6.3"} normalise-link-property
  "Normalises a link property URI by resolving it against the current base URI."
  [context uri]
  (resolve-uri context uri))

(def ^{:metadata-spec "5.1.2"} link-property
  (chain (default-if-invalid (variant {:string uri}) default-uri) normalise-link-property))

(defn id
  "An id is a link property whose value cannot begin with _:"
  [context x]
  (if (string? x)
    (let [^String s x]
      (if (.startsWith s "_:")
        (make-error context "Ids cannot start with _:")
        (link-property context s)))
    (link-property context x)))

(def ^{:metadata-spec "5.1.3"} default-template-property (template/parse-template ""))

(def ^{:metadata-spec "5.1.3"} template-property
  (variant {:string (fn [context s]
                      (if-let [t (template/try-parse-template s)]
                        t
                        (make-warning context (str "Invalid URI template: '" s "'") invalid)))
            :default default-template-property}))

(defn ^{:metadata-spec "5.8.1"} expand-common-property-key
  "Expands a common property key into the corresponding absolute URI. Logs a warning and returns invalid if the
   value is not a valid prefixed name or absolute URI."
  [context ^String s]
  (if (.contains s ":")
    (try
      (let [uri (URI. (expand-uri-string s))]
        (if (.isAbsolute uri)
          uri
          (make-warning context "Expected prefixed name or absolute URI" invalid)))
      (catch URISyntaxException _ex
        (make-warning context (format "Invalid common property key '%s'" s) invalid)))
    (make-warning context (format "Invalid common property key '%s'" s) invalid)))

(def common-property-key (chain string expand-common-property-key))

(defn ^{:metadata-spec "5.8.2"} common-property-type-with-value [context x]
  (if (string? x)
    (if-let [datatype-iri (xml-datatype/get-datatype-iri x)]
      datatype-iri
      (let [uri (try
                  (URI. (expand-uri-string x))
                  (catch URISyntaxException ex
                    (make-error context (format "Invalid URI '%s'" x))))]
        (if (.isAbsolute uri)
          uri
          (make-error context "Type URI must be absolute"))))
    (make-error context (type-error-message #{:string} (mjson/get-json-type x)))))

(defn validate-common-property-type-without-value [context ^String s]
  (if-let [type-uri (json-ld/expand-description-object-type-uri s)]
    type-uri
    (let [uri (try
                (URI. (expand-uri-string s))
                (catch URISyntaxException _ex
                  (make-error context (format "Invalid type - expected description object type, compact or absolute URI"))))]
      (if (.isAbsolute uri)
        uri
        (make-error context "Type URI must be absolute")))))

(defn ^{:metadata-spec "5.8.2"} common-property-type-without-value
  "Validator for the @type key for objects which do not contain a @value key"
  [context x]
  (cond
    (string? x) (validate-common-property-type-without-value context x)
    (array? x) ((array-of common-property-type-without-value) context x)
    :else (make-error context (type-error-message #{:string :array} (mjson/get-json-type x)))))

;;common propeties
;;TODO: move into separate namespace?
(defn ordinary-common-property-value-key [special-keys]
  (fn [context ^String x]
    (if (and (string? x) (.startsWith x "@"))
      (make-error context (str "Only keys " (string/join ", " special-keys) " can start with an @"))
      (common-property-key context x))))

(defn common-property-value-id [context x]
  (if (string? x)
    (let [^String s x]
      (if (.startsWith s "_:")
        (make-error context "Ids cannot start with _:")
        (let [expanded (expand-uri-string s)
              uri (try
                    (URI. expanded)
                    (catch URISyntaxException ex
                      (make-error context (format "Invalid URI '%s'" s))))]
          (resolve-uri context uri))))
    (make-error context (type-error-message #{:string} (mjson/get-json-type x)))))

(defn ^{:metadata-spec "5.8"} validate-common-property-value [context v]
  (cond
    (array? v)
    ((array-of validate-common-property-value) context v)

    (object? v)
    (cond
      (contains? v "@value")
      (let [allowed-keys ["@value" "@type" "@language"]
            [allowed remaining] (util/partition-keys v allowed-keys)]
        (cond
          (seq remaining)
          (make-error context (str "Common property values specifying @value must only contain keys " (string/join ", " allowed-keys)))

          (= 3 (count allowed))
          (make-error context "Common property values specifying @value can only contain one of @type or @language")

          :else
          (let [value (get v "@value")
                value-type (mjson/get-json-type value)
                allowed-value-types #{:string :number :boolean}
                [other-key other-value] (if (contains? v "@type")
                                          ["@type" (common-property-type-with-value (append-path context "@type") (get v "@type"))]
                                          ["@language" (let [lang (get v "@language")
                                                             context (append-path context "@language")]
                                                         (if (string? lang)
                                                           (if (valid-language-code? lang)
                                                             lang
                                                             (make-error context (format "Invalid language code '%s'" lang)))
                                                           (make-error context (type-error-message #{:string} (mjson/get-json-type lang)))))])]
            (if (contains? allowed-value-types value-type)
              {"@value" value
               other-key other-value}
              (make-error context (type-error-message allowed-value-types value-type))))))

      (contains? v "@language")
      (make-error (append-path context "@language") "@language key not permitted on objects without a @value key")

      :else
      (let [special-keys ["@id" "@type"]
            [special remaining] (util/partition-keys v special-keys)
            special-validator (kvps [(optional-key "@id" common-property-value-id)
                                     (optional-key "@type" common-property-type-without-value)])
            remaining-validator (map-of (ordinary-common-property-value-key ["@id" "@type"]) validate-common-property-value)]
        (merge (special-validator context special) (remaining-validator context remaining))))

    :else v))

(defn ^{:metadata-spec "6.1"} normalise-common-property-value [{:keys [language] :as context} v]
  (cond
    (array? v)
    (mapv #(normalise-common-property-value context %) v)

    (string? v)
    (if (some? language)
      {"@value" v "@language" language}
      {"@value" v})

    (and (object? v) (contains? v "@value"))
    v

    (object? v)
    (let [[special common-properties] (util/partition-keys v ["@id" "@type"])
          normalised-common (util/map-values (fn [v] (normalise-common-property-value context v)) common-properties)]
      (merge special normalised-common))

    :else v))

(def common-property-value (chain validate-common-property-value normalise-common-property-value))

(def ^{:metadata-spec "5.3"} note common-property-value)
(def ^{:metadata-spec "5.3"} table-direction (one-of #{"rtl" "ltr" "auto"}))

(defn column-reference-array [context arr]
  (cond (= 0 (count arr)) (make-warning context "Column references should not be empty" invalid)
        (not-every? string? arr) (make-warning context "Column references should all be strings" invalid)
        :else arr))

;;TODO: validation that each referenced column exists occurs at higher-level
(def ^{:metadata-spec "5.1.4"} column-reference
  (variant {:string (fn [_context s] [s])
            :array column-reference-array}))

(def special-keys-mapping {:id "@id" :type "@type" :language "@language" base-key "@base"})
(def special-keys (into #{} (keys special-keys-mapping)))

(defn get-declared-key-mapping [declared-keys]
  (merge (into {} (map (fn [k] [(name k) k]) (remove special-keys declared-keys)))
         (set/map-invert special-keys-mapping)))

(defn document-key [key-name]
  (or (special-keys-mapping key-name) (name key-name)))

(def common-property-map (map-of common-property-key common-property-value))

(defn validate-object-of [{:keys [required optional defaults allow-common-properties?]}]
  (let [required-doc-keys (util/map-keys document-key required)
        optional-doc-keys (util/map-keys document-key optional)
        required-keys (map (fn [[k v]] (required-key k v)) required-doc-keys)
        optional-keys (map (fn [[k v]]
                             (if (contains? defaults k)
                               (optional-key (document-key k) v (get defaults k))
                               (optional-key (document-key k) v)))
                           optional)
        declared-keys (into #{} (concat (keys required) (keys optional)))
        result-key-mappings (get-declared-key-mapping declared-keys)]
    (fn [context obj]
      (let [[required-obj opt-obj] (util/partition-keys obj (keys required-doc-keys))
            [optional-obj remaining-obj] (util/partition-keys opt-obj (keys optional-doc-keys))
            required-validator (kvps required-keys)
            optional-validator (kvps optional-keys)
            declared-doc (merge (required-validator context required-obj) (optional-validator context optional-obj))
            declared (util/map-keys result-key-mappings declared-doc)]
        (if allow-common-properties?
          (let [common-properties (common-property-map context remaining-obj)]
            (if (empty? common-properties)
              declared
              (assoc declared ::common-properties common-properties)))
          (do
            (doseq [invalid-key (keys remaining-obj)]
              (logging/log-warning (format "Invalid key '%s'" invalid-key)))
            declared))))))

(defn object-of [opts]
  (variant {:object (validate-object-of opts)}))

(def csvw-ns "http://www.w3.org/ns/csvw")

(defn validate-object-context-pair [context [_ns m]]
  (if (or (contains? m base-key) (contains? m language-key))
    (v/pure m)
    (make-error context "Top-level object must contain @base or @language keys")))

(def parse-context-pair (tuple
                          (eq csvw-ns)
                          (object-of {:optional {base-key     (strict uri)
                                                 language-key (ignore-invalid language-code)}})))

(def context-pair (chain parse-context-pair validate-object-context-pair))

(def object-context (variant {:string (eq csvw-ns) :array context-pair}))

;;TODO: move context validators into metadata.context namespace?
(defn validate-contextual-object
  "Returns a validator for an object which may contain a @context key mapped to a context definition.
   If the key exists, and contain a local context definition for the object, this is used to update the
   validation context used to parse the object and its children."
  [context-required? object-validator]
  (let [context-validator (if context-required?
                            (required-key "@context" object-context)
                            (optional-key "@context" object-context))]
    (fn [context obj]
      (v/bind (fn [context-pair]
                (cond
                  ;;if no context then validate entire object
                  (nil? context-pair) (object-validator context obj)

                  ;;context is literal containing csvw-ns so remove it and validate rest of object
                  (string? (second context-pair)) (object-validator context (dissoc obj "@context"))

                  ;;context is pair containing csvw-ns and local context definition
                  :else (let [local-context (second context-pair)
                              updated-context (update-from-local-context context local-context)]
                          (object-validator updated-context (dissoc obj "@context")))))
              (context-validator context obj)))))

(defn contextual-object [context-required? object-validator]
  (variant {:object (validate-contextual-object context-required? object-validator)}))

(defn resolve-linked-object-property-object [context object-uri]
  (try
    (v/pure (util/read-json object-uri))
    (catch Exception ex
      (make-error context (format "Failed to resolve linked object property at URI %s: %s" object-uri (.getMessage ex))))))

(defn ^{:metadata-spec "6.4"} linked-object-property [object-validator]
  (fn [context object-uri]
    (->> (resolve-linked-object-property-object context object-uri)
         (v/bind (fn [obj]
                   (let [updated-context (with-document-uri context object-uri)]
                     ((contextual-object false object-validator) updated-context obj))))
         (v/fmap (fn [obj]
                   ;;TODO: error if resolved JSON document is not an object? Specification does not mention this
                   ;;case but requires an empty object in other error cases. Add @id key as required due to normalisation
                   (cond (invalid? obj) {id-key object-uri}
                         (contains? obj id-key) obj
                         :else (assoc obj id-key object-uri)))))))

(defn ^{:metadata-spec "5.1.5"} object-property
  "Object which may be specified in line in the metadata document or referenced through a URI"
  [object-validator]
  (variant {:string (chain link-property (linked-object-property object-validator))
            :object object-validator
            :default {}}))
