(ns kimen.json
  #?(:bb (:require [cheshire.core :as cheshire])
     :clj (:require [clojure.data.json :as data-json])))

(set! *warn-on-reflection* true)

(defn write-str
  [x]
  #?(:bb (cheshire/generate-string x)
     :clj (data-json/write-str x :escape-slash false)))

(defn pretty-write-str
  [x]
  (write-str x))

(defn read-str
  [s]
  #?(:bb (cheshire/parse-string (str s) false)
     :clj (data-json/read-str (str s))))
