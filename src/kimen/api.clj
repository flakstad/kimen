(ns kimen.api
  (:refer-clojure :exclude [run!])
  (:require [kimen.cli :as cli]))

(set! *warn-on-reflection* true)

(defn runtime-context
  "Default runtime context for CLI execution."
  []
  {:read-file slurp
   :stdin System/in})

(defn run
  "Run Kimen argv and return {:exit-code :stdout :stderr}.
   Optional `ctx` overrides or extends runtime behavior."
  ([argv]
   (run {} argv))
  ([ctx argv]
   (cli/run (merge (runtime-context) ctx) (vec argv))))

(defn emit-result!
  "Print a run result to stdout/stderr using CLI semantics."
  [{:keys [stdout stderr]}]
  (when (seq stdout)
    (print stdout)
    (flush))
  (when (seq stderr)
    (binding [*out* *err*]
      (print stderr)
      (flush))))

(defn run!
  "Run Kimen argv, emit stdout/stderr, and return exit code."
  ([argv]
   (run! {} argv))
  ([ctx argv]
   (let [res (run ctx argv)]
     (emit-result! res)
     (:exit-code res))))

(defn main!
  "CLI-compatible main entrypoint that returns an exit code."
  [argv]
  (run! argv))
