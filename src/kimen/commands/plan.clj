(ns kimen.commands.plan
  (:require
    [clojure.string :as str]
    [kimen.mapfile :as mapfile]
    [kimen.reason-codes :as reasons]))

(def valid-modes #{"run" "render" "envfile"})

(defn- fail!
  [reason message]
  (throw (ex-info message {:reason reason})))

(defn- validate-mode!
  [mode]
  (let [mode (or mode "run")]
    (when-not (contains? valid-modes mode)
      (fail! reasons/reason-invalid-mode
             (format "invalid mode %s (expected run|render|envfile)" (pr-str mode))))
    mode))

(defn- validate-envpaths!
  [{:keys [request env-paths]}]
  (let [files (set (map :rel-path (:files request)))]
    (when (and (seq env-paths) (empty? files))
      (fail! reasons/reason-envpath-requires-projected-files
             "envpath mappings require file projections"))
    (doseq [{:keys [rel-path]} env-paths]
      (when-not (contains? files rel-path)
        (fail! reasons/reason-envpath-missing-projected-file
               (format "envpath mapping points to missing file %s" (pr-str rel-path)))))))

(defn plan-from-mappings
  [{:keys [request env-paths mode command]}]
  (let [mode (validate-mode! mode)
        _ (validate-envpaths! {:request request
                               :env-paths env-paths})
        {:keys [envs files stdin]} request]
    {:ok true
     :action "plan"
     :exit_code 0
     :mode mode
     :command (vec (or command []))
     :env (mapv (fn [{:keys [var name]}]
                  {:var var
                   :source name})
                envs)
     :files (mapv (fn [{:keys [rel-path name]}]
                    {:path rel-path
                     :source name})
                  files)
     :stdin (or stdin "")
     :env_paths (mapv (fn [{:keys [var rel-path]}]
                        {:var var
                         :path rel-path})
                      env-paths)
     :cleanup {:projected_files_dir (if (seq files) "temp_dir" "none")}}))

(defn plan-from-source
  [{:keys [source mode command]}]
  (let [{:keys [request env-paths]}
        (try
          (mapfile/parse-string source)
          (catch Exception e
            (fail! reasons/reason-plan-failed (.getMessage e))))]
    (plan-from-mappings {:request request
                         :env-paths env-paths
                         :mode mode
                         :command command})))

(defn render-plan-text
  [{:keys [mode env files stdin env_paths command]}]
  (str/join "\n"
            [(format "plan mode: %s" mode)
             (format "command: %s" (if (seq command) (str/join " " command) "<none>"))
             (format "env mappings: %d" (count env))
             (format "file mappings: %d" (count files))
             (format "envpath mappings: %d" (count env_paths))
             (format "stdin source: %s" (if (str/blank? stdin) "<none>" stdin))]))
