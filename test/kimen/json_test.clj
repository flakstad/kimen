(ns kimen.json-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [kimen.json :as json]))

(deftest write-str-encodes-basic-values
  (is (= "{\"ok\":true,\"count\":2,\"label\":\"x\"}"
         (json/write-str (array-map :ok true :count 2 :label "x")))))

(deftest write-str-escapes-control-characters
  (let [s "line1\nline2\t\"q\"\\"
        out (json/write-str s)]
    (is (= "\"line1\\nline2\\t\\\"q\\\"\\\\\"" out))))

(deftest write-str-handles-colls
  (testing "vectors and nested maps"
    (is (= "[1,{\"a\":\"b\"},true,null]"
           (json/write-str [1 {:a "b"} true nil])))))

(deftest read-str-round-trips-basic-json
  (let [in "{\"ok\":true,\"n\":2,\"s\":\"x\",\"a\":[1,false,null],\"o\":{\"k\":\"v\"}}"
        out (json/read-str in)]
    (is (= true (get out "ok")))
    (is (= 2 (get out "n")))
    (is (= "x" (get out "s")))
    (is (= [1 false nil] (get out "a")))
    (is (= {"k" "v"} (get out "o")))))

(deftest read-str-rejects-invalid-json
  (is (thrown? Exception (json/read-str "{bad}"))))
