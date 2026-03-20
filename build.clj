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
(def default-install-bin "kimen")

(defn- windows?
  []
  (str/starts-with? (str/lower-case (System/getProperty "os.name" "")) "windows"))

(defn- ensure-parent-dir!
  [path]
  (when-let [parent (some-> path io/file .getParentFile)]
    (when-not (.exists ^java.io.File parent)
      (.mkdirs ^java.io.File parent))))

(defn- default-install-bin-name
  []
  (if (windows?)
    (str default-install-bin ".cmd")
    default-install-bin))

(defn- default-install-bin-path
  []
  (if (windows?)
    (let [local-app-data (some-> (System/getenv "LOCALAPPDATA") str/trim not-empty)
          user-home (some-> (System/getProperty "user.home") str/trim not-empty)
          base (or local-app-data user-home ".")]
      (str (io/file base "bin" (default-install-bin-name))))
    (let [user-home (or (some-> (System/getProperty "user.home") str/trim not-empty) ".")]
      (str (io/file user-home ".local" "bin" (default-install-bin-name))))))

(defn- legacy-install-bin-path
  []
  (when-let [install-dir (some-> (System/getenv "INSTALL_BIN_DIR") str/trim not-empty)]
    (str (io/file install-dir (default-install-bin-name)))))

(defn- resolve-install-bin-path
  []
  (let [raw (some-> (System/getenv "KIMEN_INSTALL_BIN") str/trim not-empty)]
    (cond
      (nil? raw) (or (legacy-install-bin-path) (default-install-bin-path))
      (#{"0" "false" "off" "no" "none"} (str/lower-case raw)) nil
      :else raw)))

(defn- shell-escape-double-quotes
  [s]
  (str/replace (str s) "\"" "\\\""))

(defn- install-native-launcher!
  [native-bin]
  (when-let [install-path (resolve-install-bin-path)]
    (let [native-bin-path (.getCanonicalPath (io/file (str native-bin)))
          install-file (io/file install-path)]
      (ensure-parent-dir! install-path)
      (if (windows?)
        (spit install-file
              (str "@echo off\r\n"
                   "\"" (str/replace native-bin-path "\"" "\\\"") "\" %*\r\n"))
        (spit install-file
              (str "#!/usr/bin/env bash\n"
                   "set -euo pipefail\n"
                   "exec \"" (shell-escape-double-quotes native-bin-path) "\" \"$@\"\n")))
      (.setExecutable ^java.io.File install-file true false)
      (println (str "installed launcher " (.getPath install-file)
                    " -> " native-bin-path))
      (.getPath install-file))))

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

(defn- uber-with-basis
  [basis]
  (clean nil)
  (let [nses (src-namespaces)]
    (b/copy-dir {:src-dirs ["resources"] :target-dir class-dir})
    (b/compile-clj {:basis basis
                    :class-dir class-dir
                    :compile-opts {:warn-on-reflection true}
                    :ns-compile nses})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis
             :main 'kimen.native-main})))

(defn uber
  [_]
  (uber-with-basis (b/create-basis {:project "deps.edn"})))

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
  "Build an uberjar and then a GraalVM native-image executable.

  Optional env overrides:
  - KIMEN_NATIVE_OUT (default: target/kimen)
  - KIMEN_INSTALL_BIN (default: ~/.local/bin/kimen; set to 'off' to skip launcher install)
  - INSTALL_BIN_DIR (legacy directory override for the installed launcher)"
  [_]
  (uber-with-basis (b/create-basis {:project "deps.edn"
                                    :aliases [:native-image]}))
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
    (install-native-launcher! out)
    out))
