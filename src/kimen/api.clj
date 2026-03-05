(ns kimen.api
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [kimen.cli :as cli]
            [kimen.json :as json]))

(set! *warn-on-reflection* true)

(defn runtime-context
  "Default runtime context for CLI execution."
  []
  {:read-file slurp
   :stdin System/in})

(defn- split-before-double-dash
  [argv]
  (let [args (vec argv)
        idx (first (keep-indexed (fn [i v] (when (= "--" v) i)) args))]
    (if (some? idx)
      [(subvec args 0 idx) (subvec args idx)]
      [args []])))

(defn- normalize-output-format
  [argv]
  (let [raw (vec argv)
        args (if (and (seq raw) (= "--" (first raw)))
               (subvec raw 1)
               raw)
        [head tail] (split-before-double-dash args)
        json? (boolean (some #{"--json"} head))
        edn? (boolean (some #{"--edn"} head))]
    (cond
      (and json? edn?)
      {:error "cannot use both --json and --edn"}

      edn?
      {:format :edn
       :argv (vec (concat (mapv (fn [a] (if (= a "--edn") "--json" a)) head)
                          tail))}

      json?
      {:format :json
       :argv args}

      :else
      {:format :text
       :argv args})))

(defn- json-text->edn-text
  [s]
  (if (nil? s)
    nil
    (let [trimmed (str/trim s)]
      (if (str/blank? trimmed)
        s
        (try
          (let [data (json/read-str trimmed)
                keywordized (walk/postwalk
                              (fn [x]
                                (if (map? x)
                                  (into {}
                                        (map (fn [[k v]]
                                               [(if (string? k) (keyword k) k) v]))
                                        x)
                                  x))
                              data)]
            (str (pr-str keywordized) "\n"))
          (catch Exception _
            s))))))

(defn- apply-output-format
  [format result]
  (if (= format :edn)
    (-> result
        (update :stdout json-text->edn-text)
        (update :stderr json-text->edn-text))
    result))

(defn- conflict-format-result
  []
  {:exit-code 1
   :stdout nil
   :stderr "cannot use both --json and --edn\n"})

(defn run
  "Run Kimen argv and return {:exit-code :stdout :stderr}.
   Optional `ctx` overrides or extends runtime behavior."
  ([argv]
   (run {} argv))
  ([ctx argv]
   (let [{:keys [argv format error]} (normalize-output-format argv)]
     (if (some? error)
       (conflict-format-result)
       (->> (cli/run (merge (runtime-context) ctx) argv)
            (apply-output-format format))))))

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
