(ns kimen.commands.map-lint
  (:require
    [clojure.string :as str]
    [kimen.exit-code :as exit-code]
    [kimen.mapfile :as mapfile]))

(set! *warn-on-reflection* true)

(def exec-prefix "exec:")

(def shell-sensitive-exec-chars
  #{\' \" \| \& \; \< \> \$ \( \) \`})

(defn- issue
  [code severity message]
  {:code code
   :severity severity
   :message message})

(defn- sorted-issues
  [issues]
  (sort-by (juxt :severity :code :message) issues))

(defn- duplicate-keys
  [items key-fn value-fn]
  (let [seen (atom {})
        conflicts (atom #{})
        redundant (atom #{})]
    (doseq [item items]
      (let [k (key-fn item)
            v (value-fn item)
            prev (get @seen k ::missing)]
        (if (= prev ::missing)
          (swap! seen assoc k v)
          (if (= prev v)
            (swap! redundant conj k)
            (swap! conflicts conj k)))))
    {:conflicts (sort @conflicts)
     :redundant (sort @redundant)}))

(defn- map-has-no-mappings?
  [{:keys [envs files stdin]} envpaths]
  (and (empty? envs)
       (empty? files)
       (empty? envpaths)
       (str/blank? stdin)))

(defn- map-has-only-file-mappings?
  [{:keys [envs files stdin]} envpaths]
  (and (seq files)
       (empty? envs)
       (empty? envpaths)
       (str/blank? stdin)))

(defn- envpath-missing-file-issues
  [files envpaths]
  (let [file-paths (set (map :rel-path files))]
    (->> envpaths
         (remove #(contains? file-paths (:rel-path %)))
         (sort-by (juxt :var :rel-path))
         (mapv (fn [{:keys [var rel-path]}]
                 (issue "envpath_missing_file"
                        "error"
                        (format "envpath %s points to missing file mapping %s"
                                var
                                (pr-str rel-path))))))))

(defn- envpath-overrides-env-issues
  [envs envpaths]
  (let [env-vars (set (map :var envs))]
    (->> envpaths
         (filter #(contains? env-vars (:var %)))
         (sort-by :var)
         (mapv (fn [{:keys [var]}]
                 (issue "envpath_overrides_env_var"
                        "warning"
                        (format "envpath overrides env var %s" var)))))))

(defn- file-path-conflict-issues
  [files]
  (let [paths (->> files (map :rel-path) distinct sort)
        path-set (set paths)
        conflicts
        (for [p paths
              :let [parts (str/split p #"/")]
              i (range 1 (count parts))
              :let [prefix (str/join "/" (take i parts))]
              :when (contains? path-set prefix)]
          [prefix p])]
    (->> conflicts
         (distinct)
         (sort)
         (mapv (fn [[prefix full]]
                 (issue "file_path_conflicts_with_directory"
                        "error"
                        (format "file path %s conflicts with directory path %s"
                                (pr-str prefix)
                                 (pr-str full))))))))

(defn- exec-source-empty-warning
  [where spec]
  (let [s (some-> spec str str/trim)]
    (when (and (str/starts-with? s exec-prefix)
               (str/blank? (subs s (count exec-prefix))))
      (format "%s uses an empty exec source command: %s" where spec))))

(defn- shell-sensitive-exec?
  [cmd]
  (boolean (some shell-sensitive-exec-chars cmd)))

(defn- exec-source-warning
  [where spec]
  (let [s (some-> spec str str/trim)]
    (when (str/starts-with? s exec-prefix)
      (let [cmd (some-> (subs s (count exec-prefix)) str/trim)]
        (when (and (not (str/blank? cmd))
                   (shell-sensitive-exec? cmd))
          (format "%s uses shell-sensitive exec source; `exec:` splits on whitespace and does not parse quotes (use a wrapper script): %s"
                  where
                  spec))))))

(defn- exec-source-empty-issues
  [{:keys [envs files stdin]}]
  (let [messages
        (->> (concat
               (for [{:keys [var name]} envs]
                 (exec-source-empty-warning (str "env " var) name))
               (for [{:keys [rel-path name]} files]
                 (exec-source-empty-warning (str "file " rel-path) name))
               [(when-not (str/blank? stdin)
                  (exec-source-empty-warning "stdin" stdin))])
             (remove nil?)
             distinct
             sort)]
    (mapv #(issue "exec_source_empty_command" "error" %) messages)))

(defn- exec-source-warning-issues
  [{:keys [envs files stdin]}]
  (let [messages
        (->> (concat
               (for [{:keys [var name]} envs]
                 (exec-source-warning (str "env " var) name))
               (for [{:keys [rel-path name]} files]
                 (exec-source-warning (str "file " rel-path) name))
               [(when-not (str/blank? stdin)
                  (exec-source-warning "stdin" stdin))])
             (remove nil?)
             distinct
             sort)]
    (mapv #(issue "exec_source_may_require_wrapper" "warning" %) messages)))

(defn lint-request
  [{:keys [request env-paths]} mode]
  (let [{:keys [envs files stdin]} request
        envpaths (or env-paths [])
        mode (or mode "all")
        env-dups (duplicate-keys envs :var :name)
        file-dups (duplicate-keys files :rel-path :name)
        envpath-dups (duplicate-keys envpaths :var :rel-path)
        issues
        (vec (concat
               (when (map-has-no-mappings? request envpaths)
                 [(issue "empty_map" "error" "map has no mappings (add at least one env/file/stdin/envpath entry)")])
               (mapv (fn [k]
                       (issue "duplicate_env_var" "error"
                              (format "duplicate env var mapping with conflicting values: %s" k)))
                     (:conflicts env-dups))
               (mapv (fn [k]
                       (issue "redundant_env_var" "warning"
                              (format "redundant env var mapping (same value repeated): %s" k)))
                     (:redundant env-dups))
               (mapv (fn [k]
                       (issue "duplicate_file_path" "error"
                              (format "duplicate file mapping path with conflicting values: %s" (pr-str k))))
                     (:conflicts file-dups))
               (mapv (fn [k]
                       (issue "redundant_file_path" "warning"
                              (format "redundant file mapping path (same value repeated): %s" (pr-str k))))
                     (:redundant file-dups))
               (mapv (fn [k]
                       (issue "duplicate_envpath_var" "error"
                              (format "duplicate envpath var mapping with conflicting values: %s" k)))
                     (:conflicts envpath-dups))
               (mapv (fn [k]
                       (issue "redundant_envpath_var" "warning"
                              (format "redundant envpath var mapping (same value repeated): %s" k)))
                     (:redundant envpath-dups))
               (envpath-overrides-env-issues envs envpaths)
               (envpath-missing-file-issues files envpaths)
               (file-path-conflict-issues files)
               (exec-source-empty-issues request)
               (when (and (seq envpaths) (or (= mode "all") (= mode "envfile")))
                 [(issue "envpath_requires_files_dir_for_envfile" "warning" "envpath mappings require --files-dir when using `kimen envfile`")])
               (when (and (not= mode "run") (not (str/blank? stdin)))
                 [(issue "stdin_run_only" "warning" "stdin mapping is only used in run mode")])
               (when (and (or (= mode "all") (= mode "envfile"))
                          (map-has-only-file-mappings? request envpaths))
                 [(issue "envfile_has_no_env_mappings" "warning" "map has file mappings but no env/envpath entries; `kimen envfile` with this map will fail")])
               (exec-source-warning-issues request)))]
    (let [issues (sorted-issues issues)
          error-count (count (filter #(= "error" (:severity %)) issues))
          warning-count (count (filter #(= "warning" (:severity %)) issues))]
      {:ok (zero? error-count)
       :mode mode
       :error_count error-count
       :warning_count warning-count
       :issues issues
       :exit_code (if (zero? error-count) 0 exit-code/code-map-lint-failed)})))

(defn lint-source
  [{:keys [source mode]}]
  (try
    (let [parsed (mapfile/parse-string source)]
      (lint-request parsed mode))
    (catch Exception e
      {:ok false
       :mode (or mode "all")
       :error_count 1
       :warning_count 0
       :issues [(issue "invalid_map" "error" (ex-message e))]
       :exit_code exit-code/code-map-lint-failed})))

(defn invalid-input-report
  [mode message]
  {:ok false
   :mode (or mode "all")
   :error_count 1
   :warning_count 0
   :issues [(issue "invalid_input" "error" message)]
   :exit_code exit-code/code-map-lint-failed})

(defn apply-strict
  [report strict?]
  (if (and strict? (pos? (:warning_count report)))
    (assoc report
           :ok false
           :exit_code exit-code/code-map-lint-failed)
    report))

(defn render-report-text
  [{:keys [ok mode error_count warning_count issues]}]
  (let [header (format "map lint: %s (mode=%s errors=%d warnings=%d)"
                       (if ok "ok" "failed")
                       mode
                       error_count
                       warning_count)
        issue-lines (map (fn [{:keys [severity code message]}]
                           (format "- [%s:%s] %s" severity code message))
                         issues)]
    (str/join "\n" (concat [header] issue-lines))))
