(ns kimen.projection
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :as sh]
    [clojure.string :as str]
    [kimen.reason-codes :as reasons]))

(set! *warn-on-reflection* true)

(def exec-prefix "exec:")
(def secret-prefix "secret:")
(def const-prefix "const:")
(def simple-env-value-re #"^[A-Za-z0-9_./:@+\-]*$")

(defn- fail!
  [reason message]
  (throw (ex-info message {:reason reason})))

(defn- strip-one-trailing-newline
  [s]
  (cond
    (str/ends-with? s "\r\n") (subs s 0 (- (count s) 2))
    (str/ends-with? s "\n") (subs s 0 (dec (count s)))
    :else s))

(defn- parse-cmd
  [s]
  (->> (str/split (str/trim (str s)) #"\s+")
       (remove str/blank?)
       vec))

(defn resolve-value
  [{:keys [lookup-secret]} spec]
  (let [spec (some-> spec str str/trim)]
    (when (str/blank? spec)
      (fail! reasons/reason-invalid-mapping "empty value spec"))
    (cond
      (str/starts-with? spec exec-prefix)
      (let [cmd (some-> (subs spec (count exec-prefix)) str/trim)
            argv (parse-cmd cmd)]
        (when (empty? argv)
          (fail! reasons/reason-invalid-mapping "empty exec command in value spec"))
        (let [[prog & args] argv
              {:keys [exit out err]} (apply sh/sh (concat [prog] args [:out :string :err :string]))]
          (if (zero? exit)
            (strip-one-trailing-newline (or out ""))
            (fail! reasons/reason-projection-failed
                   (format "exec source failed: %s" (or (some-> err str/trim not-empty) "non-zero exit"))))))

      (str/starts-with? spec const-prefix)
      (subs spec (count const-prefix))

      :else
      (let [secret-name (if (str/starts-with? spec secret-prefix)
                          (some-> (subs spec (count secret-prefix)) str/trim)
                          spec)]
        (when (str/blank? secret-name)
          (fail! reasons/reason-invalid-mapping "empty secret name in value spec"))
        (lookup-secret secret-name)))))

(defn- ensure-dir!
  [path]
  (let [f (io/file path)]
    (when-not (.exists f)
      (.mkdirs f))
    (try
      (.setReadable f false false)
      (.setReadable f true true)
      (.setWritable f false false)
      (.setWritable f true true)
      (.setExecutable f false false)
      (.setExecutable f true true)
      (catch Exception _ nil))
    path))

(defn- write-file-0600!
  [path content]
  (let [tmp (str path ".tmp")]
    (spit tmp content)
    (let [f (io/file tmp)]
      (try
        (.setReadable f false false)
        (.setReadable f true true)
        (.setWritable f false false)
        (.setWritable f true true)
        (catch Exception _ nil)))
    (io/make-parents path)
    (io/copy (io/file tmp) (io/file path))
    (.delete (io/file tmp))
    path))

(defn render-files!
  [{:keys [lookup-secret]} out-dir files]
  (ensure-dir! out-dir)
  (doseq [{:keys [rel-path name]} files]
    (let [full (str (io/file out-dir rel-path))
          value (resolve-value {:lookup-secret lookup-secret} name)]
      (io/make-parents full)
      (write-file-0600! full value)))
  (count files))

(defn env-overrides
  [{:keys [lookup-secret files-dir]} request env-paths]
  (let [env-map
        (reduce (fn [acc {:keys [var name]}]
                  (assoc acc var (resolve-value {:lookup-secret lookup-secret} name)))
                {}
                (:envs request))
        env-map
        (if (seq (:files request))
          (assoc env-map "KIMEN_FILES_DIR" files-dir)
          env-map)]
    (reduce (fn [acc {:keys [var rel-path]}]
              (assoc acc var (str (io/file files-dir rel-path))))
            env-map
            env-paths)))

(defn stdin-value
  [{:keys [lookup-secret]} request]
  (when-let [stdin-spec (:stdin request)]
    (resolve-value {:lookup-secret lookup-secret} stdin-spec)))

(defn run-child!
  [command env-overrides stdin-string]
  (let [pb (ProcessBuilder. ^java.util.List (vec command))
        env (.environment pb)]
    (doseq [[k v] env-overrides]
      (.put env (str k) (str v)))
    (let [proc (.start pb)
          out-f (future (slurp (.getInputStream proc)))
          err-f (future (slurp (.getErrorStream proc)))]
      (if (some? stdin-string)
        (with-open [w (io/writer (.getOutputStream proc))]
          (.write w (str stdin-string)))
        (.close (.getOutputStream proc)))
      (let [exit (.waitFor proc)
            out @out-f
            err @err-f]
        {:exit exit
         :out out
         :err err}))))

(defn write-envfile!
  [path env-map]
  (io/make-parents path)
  (let [lines (->> env-map
                   (sort-by key)
                   (map (fn [[k v]]
                          (let [v (str v)]
                            (when (str/includes? v "\u0000")
                              (fail! reasons/reason-envfile-failed
                                     (format "%s: value contains NUL" k)))
                            (when (or (str/includes? v "\n") (str/includes? v "\r"))
                              (fail! reasons/reason-envfile-failed
                                     (format "%s: value contains newline" k)))
                            (let [encoded (cond
                                            (= "" v) "\"\""
                                            (re-matches simple-env-value-re v) v
                                            :else
                                            (str "\""
                                                 (-> v
                                                     (str/replace "\\" "\\\\")
                                                     (str/replace "\"" "\\\""))
                                                 "\""))]
                              (str k "=" encoded)))))
                   vec)
        body (str (str/join "\n" lines) "\n")]
    (write-file-0600! path body)
    path))

(defn delete-dir-recursive!
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (doseq [^java.io.File child (reverse (file-seq f))]
        (.delete child)))))
