(ns kimen.mapfile
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]))

(def env-var-re #"^[A-Za-z_][A-Za-z0-9_]*$")
(def profile-name-re #"^[A-Za-z0-9_.-]+$")
(def env-profile-dir "KIMEN_PROFILE_DIR")

(defn- invalid!
  ([msg]
   (throw (ex-info msg {:type :invalid-map})))
  ([msg data]
   (throw (ex-info msg (merge {:type :invalid-map} data)))))

(defn valid-profile-name?
  [s]
  (boolean (and (string? s) (re-matches profile-name-re s))))

(defn- normalize-relative-path
  [raw]
  (let [input (some-> raw str str/trim)]
    (when (str/blank? input)
      (invalid! "empty relative path"))
    (let [path (.normalize (java.nio.file.Paths/get input (make-array String 0)))
          clean (.toString path)]
      (when (.isAbsolute path)
        (invalid! (format "invalid relative path %s" (pr-str raw)) {:reason :invalid-relative-path}))
      (when (or (= clean ".")
                (= clean "..")
                (str/starts-with? clean "../")
                (str/starts-with? clean "..\\")
                (str/starts-with? clean "/")
                (str/starts-with? clean "\\"))
        (invalid! (format "invalid relative path %s" (pr-str raw)) {:reason :invalid-relative-path}))
      (str/replace clean "\\" "/"))))

(defn parse-request
  [env-mappings file-mappings stdin-spec]
  (let [envs
        (mapv (fn [m]
                (let [[var value] (str/split (str m) #"=" 2)
                      var (some-> var str/trim)
                      value (some-> value str/trim)]
                  (when-not (and var value)
                    (invalid! (format "invalid --env mapping %s (expected VAR=<value>)" (pr-str m))
                              {:reason :invalid-mapping}))
                  (when-not (re-matches env-var-re var)
                    (invalid! (format "invalid env var name %s" (pr-str var)) {:reason :invalid-env-var}))
                  (when (str/blank? value)
                    (invalid! "empty value in --env mapping" {:reason :invalid-mapping}))
                  {:var var :name value}))
              env-mappings)
        files
        (mapv (fn [m]
                (let [[rel value] (str/split (str m) #"=" 2)
                      rel (some-> rel str/trim)
                      value (some-> value str/trim)]
                  (when-not (and rel value)
                    (invalid! (format "invalid --file mapping %s (expected relpath=<value>)" (pr-str m))
                              {:reason :invalid-mapping}))
                  (when (str/blank? value)
                    (invalid! "empty value in --file mapping" {:reason :invalid-mapping}))
                  {:rel-path (normalize-relative-path rel)
                   :name value}))
              file-mappings)
        stdin-spec (some-> stdin-spec str str/trim)]
    {:envs envs
     :files files
     :stdin (when-not (str/blank? stdin-spec) stdin-spec)}))

(defn parse-envpath-mappings
  [envpath-mappings]
  (mapv (fn [m]
          (let [[var rel] (str/split (str m) #"=" 2)
                var (some-> var str/trim)
                rel (some-> rel str/trim)]
            (when-not (and var rel)
              (invalid! (format "invalid envpath mapping %s (expected VAR=relpath)" (pr-str m))
                        {:reason :invalid-mapping}))
            (when-not (re-matches env-var-re var)
              (invalid! (format "invalid env var name %s" (pr-str var)) {:reason :invalid-env-var}))
            {:var var
             :rel-path (normalize-relative-path rel)}))
        envpath-mappings))

(defn- split-kind-and-rest
  [line]
  (let [[_ kind rest] (re-matches #"^(\S+)\s+(.+)$" line)]
    [kind (some-> rest str/trim)]))

(defn- strip-inline-comment
  [line]
  (let [idx (.indexOf ^String line "#")]
    (if (<= idx 0)
      line
      (let [prefix (subs line 0 idx)
            prev (nth line (dec idx))]
        (if (and (not (str/blank? prefix)) (or (= prev \space) (= prev \tab)))
          (str/trim prefix)
          line)))))

(defn parse-string
  [s]
  (let [lines (str/split-lines (or s ""))]
    (loop [idx 0
           envs []
           files []
           envpaths []
           stdin nil]
      (if (>= idx (count lines))
        (let [request (parse-request envs files stdin)
              envpaths (parse-envpath-mappings envpaths)]
          {:request request
           :env-paths envpaths})
        (let [line-no (inc idx)
              raw (nth lines idx)
              line (-> raw str/trim strip-inline-comment str/trim)]
          (if (or (str/blank? line) (str/starts-with? line "#"))
            (recur (inc idx) envs files envpaths stdin)
            (let [[kind rest] (split-kind-and-rest line)]
              (when (or (str/blank? kind) (str/blank? rest))
                (invalid! (format "invalid map line %d: expected '<kind> <mapping>'" line-no)))
              (case kind
                "env" (recur (inc idx) (conj envs rest) files envpaths stdin)
                "file" (recur (inc idx) envs (conj files rest) envpaths stdin)
                "stdin"
                (do
                  (when stdin
                    (invalid! (format "invalid map line %d: multiple stdin entries" line-no)))
                  (recur (inc idx) envs files envpaths rest))
                "envpath" (recur (inc idx) envs files (conj envpaths rest) stdin)
                (invalid! (format "invalid map line %d: unknown kind %s" line-no (pr-str kind)))))))))))

(defn parse-file
  [path]
  (parse-string (slurp (io/file path))))

(defn resolve-profile
  [name]
  (let [name (some-> name str str/trim)]
    (when-not (valid-profile-name? name)
      (throw (ex-info (format "invalid profile name %s (allowed: letters, digits, ., _, -)" (pr-str name))
                      {:reason "invalid_profile_name"})))
    (let [filename (str name ".kmap")
          candidates (->> [(some-> (System/getenv env-profile-dir) str/trim not-empty)
                           (str (io/file ".kimen/profiles"))
                           (let [cfg-home (or (some-> (System/getenv "XDG_CONFIG_HOME") str/trim not-empty)
                                              (some-> (System/getProperty "user.home") str/trim not-empty (str "/.config")))]
                             (when cfg-home
                               (str (io/file cfg-home "kimen/profiles"))))]
                          (remove nil?)
                          (map #(str (io/file % filename)))
                          vec)
          hit (some (fn [p]
                      (when (.exists (io/file p))
                        p))
                    candidates)]
      (if hit
        hit
        (throw (ex-info (format "profile %s not found (set %s or create .kimen/profiles/%s)"
                                (pr-str name)
                                env-profile-dir
                                filename)
                        {:reason "invalid_profile_name"}))))))
