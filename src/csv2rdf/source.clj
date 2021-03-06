(ns csv2rdf.source
  "A source is an instance of clojure.java.io/IOFactory which has an associated URI"
  (:require [clojure.java.io :as io]
            [csv2rdf.util :as util]
            [clojure.spec.alpha :as s]
            [csv2rdf.http :as http]
            [clojure.data.json :as json])
  (:import [java.net URI]
           [java.io File InputStream ByteArrayInputStream]))

(defprotocol URIable
  "Represents an object with an associated URI."
  (->uri ^URI [this]))

(extend-protocol URIable
  File
  (->uri [file] (.toURI file))

  URI
  (->uri [uri] uri))

(defprotocol JSONSource
  "Protocol for loading a JSON map from a given source"
  (get-json [this]))

(defn- read-json
  "Reads a JSON map from an implementation of io/IOFactory."
  [source]
  (with-open [r (io/reader source)]
    (json/read r)))

(extend-protocol JSONSource
  URI
  (get-json [uri]
    (let [{:keys [body]} (http/get-uri uri)]
      (get-json body)))

  File
  (get-json [f] (read-json f))

  String
  (get-json [s] (json/read-str s)))

(defrecord MapMetadataSource [uri json]
  URIable
  (->uri [_this] uri)

  JSONSource
  (get-json [_this] json))

(defprotocol IntoInputStream
  "Represents an object which can be coerced into a java.io.InputStream."
  (into-input-stream [this]))

(extend-protocol IntoInputStream
  InputStream
  (into-input-stream [is] is)

  String
  (into-input-stream [^String s] (ByteArrayInputStream. (.getBytes s)))

  File
  (into-input-stream [f] (io/input-stream f)))

(defprotocol InputStreamRequestable
  "Represents a source which can be de-referenced to return an input stream along with a map
   of associated header values."
  (request-input-stream [this]))

(defmulti request-uri-input-stream (fn [^URI uri] (keyword (.getScheme uri))))

(defn- get-uri-input-stream
  "Accesses a HTTP(S) URI through a GET request and returns the header
  map and body as an input stream."
  [uri]
  (let [{:keys [status headers body] :as response} (http/get-uri uri)]
    (if (http/is-not-found-response? response)
      (throw (ex-info
               (format "Error resolving URI %s: not found" uri)
               {:type ::resolve-uri-error
                :uri uri
                :status status})))
    {:headers headers
     :stream (into-input-stream body)}))

(defmethod request-uri-input-stream :http [uri]
  (get-uri-input-stream uri))

(defmethod request-uri-input-stream :https [uri]
  (get-uri-input-stream uri))

(defmethod request-uri-input-stream :file [uri]
  {:headers {} :stream (io/input-stream uri)})

(extend-protocol InputStreamRequestable
  File
  (request-input-stream [f] {:headers {}
                             :stream (io/input-stream f)})

  URI
  (request-input-stream [uri] (request-uri-input-stream uri)))

(s/def ::uriable #(satisfies? URIable %))
(s/def ::json-source #(satisfies? JSONSource %))
