(ns kimen.main
  (:require [kimen.api :as api]))

(set! *warn-on-reflection* true)

(defn runtime-context
  []
  (api/runtime-context))

(defn main!
  [argv]
  (api/main! argv))

(defn -main
  [& argv]
  (System/exit (int (main! argv))))
