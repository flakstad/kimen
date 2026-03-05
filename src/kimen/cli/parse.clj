(ns kimen.cli.parse
  (:require
   [clojure.string :as str]))

(def ^:private duration-unit-ms
  {"ms" 1
   "s" 1000
   "m" (* 60 1000)
   "h" (* 60 60 1000)
   "d" (* 24 60 60 1000)})

(defn parse-flag-value
  [args flag]
  (let [prefix (str flag "=")]
    (cond
      (empty? args) [nil args nil]
      (= flag (first args))
      (if-let [v (second args)]
        [v (nnext args) nil]
        [nil args (str "missing value for " flag)])
      (str/starts-with? (first args) prefix)
      [(subs (first args) (count prefix)) (next args) nil]
      :else
      [nil args nil])))

(defn parse-duration-ms
  [raw]
  (let [s (some-> raw str/trim str/lower-case)]
    (cond
      (str/blank? s)
      [nil "missing duration value"]

      (re-matches #"^-?\d+$" s)
      [(Long/parseLong s) nil]

      :else
      (if-let [[_ n unit] (re-matches #"^(-?\d+)(ms|s|m|h|d)$" s)]
        [(* (Long/parseLong n)
            (long (get duration-unit-ms unit 0)))
         nil]
        [nil (format "invalid duration %s (expected e.g. 30s, 5m, 1h)" (pr-str raw))]))))

(defn parse-json-only-opts
  [args]
  (loop [args args
         opts {:json? false}]
    (if (empty? args)
      [opts nil]
      (let [a (first args)]
        (cond
          (= a "--json")
          (recur (rest args) (assoc opts :json? true))

          (str/starts-with? a "-")
          [opts (str "unknown flag " a)]

          :else
          [opts (str "unexpected argument " (pr-str a))])))))

(defn split-before-double-dash
  [args]
  (loop [left []
         args args]
    (if (empty? args)
      [left []]
      (if (= "--" (first args))
        [left (rest args)]
        (recur (conj left (first args)) (rest args))))))
