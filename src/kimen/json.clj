(ns kimen.json
  (:require [clojure.string :as str]))

(defn- append-escaped-char!
  [^StringBuilder sb ^Character c]
  (case c
    \" (.append sb "\\\"")
    \\ (.append sb "\\\\")
    \backspace (.append sb "\\b")
    \formfeed (.append sb "\\f")
    \newline (.append sb "\\n")
    \return (.append sb "\\r")
    \tab (.append sb "\\t")
    (let [n (int c)]
      (if (< n 32)
        (.append sb (format "\\u%04x" n))
        (.append sb c)))))

(declare write-json!)

(defn- write-string!
  [^StringBuilder sb s]
  (.append sb \")
  (doseq [c s]
    (append-escaped-char! sb c))
  (.append sb \"))

(defn- write-map!
  [^StringBuilder sb m]
  (.append sb "{")
  (loop [entries (seq m)
         first? true]
    (when entries
      (when-not first?
        (.append sb ","))
      (let [[k v] (first entries)
            key-str (cond
                      (keyword? k) (name k)
                      (string? k) k
                      :else (str k))]
        (write-string! sb key-str)
        (.append sb ":")
        (write-json! sb v)
        (recur (next entries) false))))
  (.append sb "}"))

(defn- write-coll!
  [^StringBuilder sb xs]
  (.append sb "[")
  (loop [items (seq xs)
         first? true]
    (when items
      (when-not first?
        (.append sb ","))
      (write-json! sb (first items))
      (recur (next items) false)))
  (.append sb "]"))

(defn write-json!
  [^StringBuilder sb x]
  (cond
    (nil? x) (.append sb "null")
    (string? x) (write-string! sb x)
    (keyword? x) (write-string! sb (name x))
    (number? x) (.append sb (str x))
    (true? x) (.append sb "true")
    (false? x) (.append sb "false")
    (map? x) (write-map! sb x)
    (or (vector? x) (list? x) (seq? x) (set? x)) (write-coll! sb x)
    :else (write-string! sb (str x))))

(defn write-str
  [x]
  (let [sb (StringBuilder.)]
    (write-json! sb x)
    (str sb)))

(defn pretty-write-str
  "Pretty writer placeholder. For now, keep machine output deterministic/minimal."
  [x]
  (write-str x))

(defn- parse-error!
  [msg i]
  (throw (ex-info (format "%s at offset %d" msg i) {:offset i :type :invalid-json})))

(defn- ws?
  [c]
  (or (= c \space) (= c \newline) (= c \return) (= c \tab)))

(defn- skip-ws
  [s i]
  (loop [i i]
    (if (and (< i (count s)) (ws? (nth s i)))
      (recur (inc i))
      i)))

(declare parse-value)

(defn- parse-string
  [s i]
  (when-not (and (< i (count s)) (= \" (nth s i)))
    (parse-error! "expected string" i))
  (loop [j (inc i)
         sb (StringBuilder.)]
    (when (>= j (count s))
      (parse-error! "unterminated string" j))
    (let [c (nth s j)]
      (cond
        (= c \") [(str sb) (inc j)]

        (= c \\)
        (do
          (when (>= (inc j) (count s))
            (parse-error! "unterminated escape" j))
          (let [e (nth s (inc j))]
            (case e
              \" (do (.append sb \") (recur (+ j 2) sb))
              \\ (do (.append sb \\) (recur (+ j 2) sb))
              \/ (do (.append sb \/) (recur (+ j 2) sb))
              \b (do (.append sb \backspace) (recur (+ j 2) sb))
              \f (do (.append sb \formfeed) (recur (+ j 2) sb))
              \n (do (.append sb \newline) (recur (+ j 2) sb))
              \r (do (.append sb \return) (recur (+ j 2) sb))
              \t (do (.append sb \tab) (recur (+ j 2) sb))
              \u
              (do
                (when (> (+ j 6) (count s))
                  (parse-error! "invalid unicode escape" j))
                (let [hex (subs s (+ j 2) (+ j 6))]
                  (try
                    (.append sb (char (Integer/parseInt hex 16)))
                    (catch Exception _
                      (parse-error! "invalid unicode escape" j))))
                (recur (+ j 6) sb))
              (parse-error! "invalid escape sequence" j))))

        :else
        (do
          (.append sb c)
          (recur (inc j) sb))))))

(defn- parse-literal
  [s i expected value]
  (let [end (+ i (count expected))]
    (if (and (<= end (count s)) (= expected (subs s i end)))
      [value end]
      (parse-error! (str "expected " expected) i))))

(defn- numeric-char?
  [c]
  (or (Character/isDigit c)
      (= c \-)
      (= c \+)
      (= c \.)
      (= c \e)
      (= c \E)))

(defn- parse-number
  [s i]
  (let [j (loop [j i]
            (if (and (< j (count s)) (numeric-char? (nth s j)))
              (recur (inc j))
              j))
        token (subs s i j)]
    (when (str/blank? token)
      (parse-error! "expected number" i))
    (try
      (if (re-find #"[\.eE]" token)
        [(Double/parseDouble token) j]
        [(Long/parseLong token) j])
      (catch Exception _
        (parse-error! "invalid number" i)))))

(defn- parse-array
  [s i]
  (when-not (= \[ (nth s i))
    (parse-error! "expected [" i))
  (loop [j (skip-ws s (inc i))
         out []]
    (when (>= j (count s))
      (parse-error! "unterminated array" j))
    (cond
      (= \] (nth s j)) [out (inc j)]

      :else
      (let [[v next-j] (parse-value s j)
            next-j (skip-ws s next-j)]
        (when (>= next-j (count s))
          (parse-error! "unterminated array" next-j))
        (cond
          (= \] (nth s next-j)) [(conj out v) (inc next-j)]
          (= \, (nth s next-j)) (recur (skip-ws s (inc next-j)) (conj out v))
          :else (parse-error! "expected ',' or ']'" next-j))))))

(defn- parse-object
  [s i]
  (when-not (= \{ (nth s i))
    (parse-error! "expected {" i))
  (loop [j (skip-ws s (inc i))
         out {}]
    (when (>= j (count s))
      (parse-error! "unterminated object" j))
    (cond
      (= \} (nth s j)) [out (inc j)]

      :else
      (let [[k key-end] (parse-string s j)
            key-end (skip-ws s key-end)]
        (when (or (>= key-end (count s)) (not= \: (nth s key-end)))
          (parse-error! "expected ':'" key-end))
        (let [[v value-end] (parse-value s (skip-ws s (inc key-end)))
              value-end (skip-ws s value-end)]
          (when (>= value-end (count s))
            (parse-error! "unterminated object" value-end))
          (cond
            (= \} (nth s value-end)) [(assoc out k v) (inc value-end)]
            (= \, (nth s value-end)) (recur (skip-ws s (inc value-end)) (assoc out k v))
            :else (parse-error! "expected ',' or '}'" value-end)))))))

(defn parse-value
  [s i]
  (let [i (skip-ws s i)]
    (when (>= i (count s))
      (parse-error! "unexpected EOF" i))
    (let [c (nth s i)]
      (cond
        (= c \") (parse-string s i)
        (= c \{) (parse-object s i)
        (= c \[) (parse-array s i)
        (= c \t) (parse-literal s i "true" true)
        (= c \f) (parse-literal s i "false" false)
        (= c \n) (parse-literal s i "null" nil)
        :else (parse-number s i)))))

(defn read-str
  [s]
  (let [s (str s)
        [v i] (parse-value s 0)
        i (skip-ws s i)]
    (when-not (= i (count s))
      (parse-error! "trailing content" i))
    v))
