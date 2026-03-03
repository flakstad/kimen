(ns kimen.native-main
  (:gen-class)
  (:require [kimen.main :as main]))

(set! *warn-on-reflection* true)

(defn -main
  [& args]
  (System/exit (int (main/main! args))))
