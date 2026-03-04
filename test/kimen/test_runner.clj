(ns kimen.test-runner
  (:require [clojure.test :as t]))

(set! *warn-on-reflection* true)

(def test-namespaces
  '[kimen.bundle-test
    kimen.cli-test
    kimen.json-test
    kimen.mapfile-test
    kimen.reason-codes-test
    kimen.sync-state-test])

(defn main!
  []
  (doseq [ns-sym test-namespaces]
    (require ns-sym))
  (let [{:keys [fail error]} (apply t/run-tests test-namespaces)]
    (if (zero? (+ fail error))
      0
      1)))

(defn -main
  [& _]
  (System/exit (int (main!))))
