(ns kimen.reason-codes-test
  (:require
    [clojure.test :refer [deftest is]]
    [kimen.reason-codes :as reason-codes]))

(set! *warn-on-reflection* true)

(def snake-case-re #"^[a-z0-9]+(?:_[a-z0-9]+)*$")

(deftest reason-codes-are-unique-and-snake-case
  (let [codes (reason-codes/all-reason-codes)]
    (is (= (count codes) (count (distinct codes))))
    (doseq [code codes]
      (is (re-matches snake-case-re code)))))
