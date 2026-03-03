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

(defn native
  [_]
  (uber nil)
  (let [out (or (some-> (System/getenv "KIMEN_NATIVE_OUT") str/trim not-empty)
                native-out)
        cmd ["native-image"
             "--no-fallback"
             "--features=clj_easy.graal_build_time.InitClojureClasses"
             "-H:+UnlockExperimentalVMOptions"
             "-Os"
             "-o" out
             "-jar" uber-file]
        res (apply sh/sh (concat cmd [:out :string :err :string]))]
    (when-not (zero? (:exit res))
      (throw (ex-info "native-image failed"
                      {:exit (:exit res)
                       :out (:out res)
                       :err (:err res)
                       :cmd cmd})))
    out))
