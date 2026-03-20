(ns kimen.commands.plan
  (:require
    [clojure.string :as str]
    [kimen.mapfile :as mapfile]
    [kimen.reason-codes :as reasons]))

(set! *warn-on-reflection* true)

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

(defn- diff-by-key
  [current previous key-fn value-fn added-fn removed-fn changed-fn]
  (let [curr-map (into {} (map (fn [x] [(key-fn x) (value-fn x)]) current))
        prev-map (into {} (map (fn [x] [(key-fn x) (value-fn x)]) previous))
        keys (sort (set (concat (keys curr-map) (keys prev-map))))
        added (->> keys
                   (filter #(and (contains? curr-map %) (not (contains? prev-map %))))
                   (mapv #(added-fn % (get curr-map %))))
        removed (->> keys
                     (filter #(and (contains? prev-map %) (not (contains? curr-map %))))
                     (mapv #(removed-fn % (get prev-map %))))
        changed (->> keys
                     (filter #(and (contains? curr-map %) (contains? prev-map %) (not= (get curr-map %) (get prev-map %))))
                     (mapv #(changed-fn % (get prev-map %) (get curr-map %))))]
    {:added added
     :removed removed
     :changed changed}))

(defn- build-plan-diff
  [request env-paths against-request against-env-paths]
  (let [env-diff (diff-by-key (:envs request)
                              (:envs against-request)
                              :var
                              :name
                              (fn [k v] {:var k :source v})
                              (fn [k v] {:var k :source v})
                              (fn [k before after] {:var k :from before :to after}))
        file-diff (diff-by-key (:files request)
                               (:files against-request)
                               :rel-path
                               :name
                               (fn [k v] {:path k :source v})
                               (fn [k v] {:path k :source v})
                               (fn [k before after] {:path k :from before :to after}))
        envpath-diff (diff-by-key env-paths
                                  against-env-paths
                                  :var
                                  :rel-path
                                  (fn [k v] {:var k :path v})
                                  (fn [k v] {:var k :path v})
                                  (fn [k before after] {:var k :from before :to after}))
        before-stdin (or (:stdin against-request) "")
        after-stdin (or (:stdin request) "")
        stdin-changed? (not= before-stdin after-stdin)]
    (cond-> {}
      (seq (:added env-diff)) (assoc :env_added (:added env-diff))
      (seq (:removed env-diff)) (assoc :env_removed (:removed env-diff))
      (seq (:changed env-diff)) (assoc :env_changed (:changed env-diff))
      (seq (:added file-diff)) (assoc :files_added (:added file-diff))
      (seq (:removed file-diff)) (assoc :files_removed (:removed file-diff))
      (seq (:changed file-diff)) (assoc :files_changed (:changed file-diff))
      (seq (:added envpath-diff)) (assoc :envpaths_added (:added envpath-diff))
      (seq (:removed envpath-diff)) (assoc :envpaths_removed (:removed envpath-diff))
      (seq (:changed envpath-diff)) (assoc :envpaths_changed (:changed envpath-diff))
      stdin-changed? (assoc :stdin_changed true
                            :stdin_before before-stdin
                            :stdin_after after-stdin))))

(defn plan-from-mappings
  [{:keys [request env-paths mode command against-request against-env-paths against-label]}]
  (let [mode (validate-mode! mode)
        _ (validate-envpaths! {:request request
                               :env-paths env-paths})
        _ (when against-request
            (validate-envpaths! {:request against-request
                                 :env-paths against-env-paths}))
        {:keys [envs files stdin]} request
        payload
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
         :cleanup {:projected_files_dir (if (seq files) "temp_dir" "none")}}]
    (if against-request
      (assoc payload
             :against against-label
             :diff (build-plan-diff request
                                    env-paths
                                    against-request
                                    against-env-paths))
      payload)))

(defn plan-from-source
  [{:keys [source mode command]}]
  (let [{:keys [request env-paths]}
        (try
          (mapfile/parse-string source)
          (catch Exception e
            (fail! reasons/reason-plan-failed (ex-message e))))]
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
