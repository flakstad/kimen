(ns build
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :as sh]
    [clojure.string :as str]
    [clojure.tools.build.api :as b]))

(set! *warn-on-reflection* true)

(def class-dir "target/classes")
(def uber-file "target/kimen.jar")
(def native-out "target/kimen")

(defn- windows?
  []
  (str/starts-with? (str/lower-case (System/getProperty "os.name" "")) "windows"))

(defn- file->ns
  [^java.io.File f]
  (let [s (slurp f)
        [_ ns-name] (re-find #"(?m)^\(ns\s+([^\s\)]+)" s)]
    (when (seq ns-name)
      (symbol ns-name))))

(defn- src-namespaces
  []
  (->> (file-seq (io/file "src"))
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".clj"))
       (keep file->ns)
       (distinct)
       (sort)
       (vec)))

(defn clean
  [_]
  (b/delete {:path "target"}))

(defn uber
  [_]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"})
        nses (src-namespaces)]
    (b/copy-dir {:src-dirs ["resources"] :target-dir class-dir})
    (b/compile-clj {:basis basis
                    :class-dir class-dir
                    :compile-opts {:warn-on-reflection true}
                    :ns-compile nses})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main 'kimen.native-main})))

(defn- native-image-at-home
  [home]
  (when-let [home (some-> home str/trim not-empty)]
    (let [bin-dir (io/file home "bin")
          candidates (if (windows?)
                       [(io/file bin-dir "native-image.exe")
                        (io/file bin-dir "native-image.cmd")
                        (io/file bin-dir "native-image")]
                       [(io/file bin-dir "native-image")])]
      (some (fn [^java.io.File f]
              (when (.exists f)
                (.getPath f)))
            candidates))))

(defn- macos-java-home
  [version]
  (try
    (let [{:keys [exit out]} (sh/sh "/usr/libexec/java_home" "-v" (str version))]
      (when (zero? exit)
        (some-> out str/trim not-empty)))
    (catch Exception _
      nil)))

(defn- macos-graal-homes
  []
  (try
    (->> (file-seq (io/file "/Library/Java/JavaVirtualMachines"))
         (filter #(.isDirectory ^java.io.File %))
         (map #(.getPath ^java.io.File %))
         (filter #(re-find #"(?i)graalvm" %))
         (map #(str % "/Contents/Home"))
         (filter #(some? (native-image-at-home %)))
         (sort)
         (vec))
    (catch Exception _
      [])))

(defn- resolve-native-image
  []
  (let [candidates (concat
                     [(System/getenv "GRAALVM_HOME")
                      (System/getenv "JAVA_HOME")
                      (macos-java-home 25)
                      (macos-java-home 24)
                      (macos-java-home 23)]
                     (macos-graal-homes))
        from-home (some native-image-at-home candidates)]
    (or from-home
        (if (windows?) "native-image.cmd" "native-image"))))

(defn- native-image-major-version
  [native-image]
  (try
    (let [{:keys [exit out err]} (sh/sh native-image "--version")
          text (str (or out "") "\n" (or err ""))
          m (re-find #"native-image\s+(\d+)" text)]
      (when (and (zero? exit) m)
        (Long/parseLong (second m))))
    (catch Exception _
      nil)))

(defn native
  [_]
  (uber nil)
  (let [native-image (resolve-native-image)
        native-image-major (native-image-major-version native-image)
        unlock-experimental? (and native-image-major (>= native-image-major 25))
        out (or (some-> (System/getenv "KIMEN_NATIVE_OUT") str/trim not-empty)
                native-out)
        _ (when-let [parent (.getParentFile (io/file out))]
            (.mkdirs parent))
        cmd (cond-> [native-image
                     "--no-fallback"
                     "--features=clj_easy.graal_build_time.InitClojureClasses"]
              unlock-experimental? (conj "-H:+UnlockExperimentalVMOptions")
              true (conj "-Os" "-o" out "-jar" uber-file))
        res (apply sh/sh (concat cmd [:out :string :err :string]))]
    (when-not (zero? (:exit res))
      (throw (ex-info "native-image failed"
                      {:exit (:exit res)
                       :out (:out res)
                       :err (:err res)
                       :cmd cmd})))
    out))
