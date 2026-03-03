(ns kimen.commands.map-lint
  (:require
    [clojure.string :as str]
    [kimen.exit-code :as exit-code]
    [kimen.mapfile :as mapfile]))

(defn- issue
  [code severity message]
  {:code code
   :severity severity
   :message message})

(defn- duplicate-issues
  [items key-fn code message-fn]
  (->> items
       (group-by key-fn)
       (filter (fn [[_ xs]] (> (count xs) 1)))
       (sort-by key)
       (mapv (fn [[k _]]
               (issue code "error" (message-fn k))))))

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

(defn lint-request
  [{:keys [request env-paths]} mode]
  (let [{:keys [envs files stdin]} request
        envpaths (or env-paths [])
        mode (or mode "all")
        issues (vec (concat
                      (when (and (empty? envs) (empty? files) (empty? envpaths) (str/blank? stdin))
                        [(issue "empty_map" "warning" "map has no projections")])
                      (duplicate-issues envs :var "duplicate_env_var"
                                        (fn [k] (format "duplicate env var %s" k)))
                      (duplicate-issues files :rel-path "duplicate_file_path"
                                        (fn [k] (format "duplicate file path %s" (pr-str k))))
                      (duplicate-issues envpaths :var "duplicate_envpath_var"
                                        (fn [k] (format "duplicate envpath var %s" k)))
                      (envpath-overrides-env-issues envs envpaths)
                      (envpath-missing-file-issues files envpaths)
                      (file-path-conflict-issues files)
                      (when (and (not= mode "run") (not (str/blank? stdin)))
                        [(issue "stdin_run_only" "warning" "stdin mapping is only used in run mode")])
                      (when (and (= mode "envfile") (empty? envs))
                        [(issue "envfile_has_no_env_mappings" "warning" "envfile mode has no env mappings")])))]
    (let [issues (sort-by (juxt :severity :code :message) issues)
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
       :issues [(issue "invalid_map" "error" (.getMessage e))]
       :exit_code exit-code/code-map-lint-failed})))

(defn invalid-input-report
  [mode message]
  {:ok false
   :mode (or mode "all")
   :error_count 1
   :warning_count 0
   :issues [(issue "invalid_input" "error" message)]
   :exit_code exit-code/code-map-lint-failed})

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
