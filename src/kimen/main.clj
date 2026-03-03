(ns kimen.main
  (:require [kimen.cli :as cli]))

(set! *warn-on-reflection* true)

(defn runtime-context
  []
  {:read-file slurp
   :stdin System/in})

(defn main!
  [argv]
  (let [{:keys [exit-code stdout stderr]} (cli/run (runtime-context) argv)]
    (when (seq stdout)
      (print stdout)
      (flush))
    (when (seq stderr)
      (binding [*out* *err*]
        (print stderr)
        (flush)))
    exit-code))

(defn -main
  [& argv]
  (System/exit (int (main! argv))))
