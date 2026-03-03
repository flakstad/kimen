(ns kimen.commands.init
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [kimen.reason-codes :as reasons]))

(def default-ci-sync-gate-workflow-path ".github/workflows/kimen-sync-gate.yml")
(def default-ci-pr-safety-workflow-path ".github/workflows/kimen-pr-safety.yml")
(def default-ci-deploy-workflow-path ".github/workflows/kimen-deploy.yml")

(def ci-sync-gate-workflow-name "kimen-sync-gate")
(def ci-pr-safety-workflow-name "kimen-pr-safety")
(def ci-deploy-workflow-name "kimen-deploy")

(defn default-ci-sync-gate-options
  []
  {:remote-name "team"
   :remote-type "git"
   :remote-path ""
   :remote-branch "main"
   :remote-bundle-path "vault.age"
   :local-bundle "vault.age"
   :profile ""
   :stale-threshold "30m"})

(defn default-ci-pr-safety-options
  []
  {:profile "ci"
   :command "echo ci-check"})

(defn default-ci-deploy-options
  []
  {:profile "prod"
   :deploy-command "./scripts/deploy.sh"})

(defn- load-template!
  [name]
  (if-let [r (io/resource (str "kimen/scaffold_templates/" name))]
    (slurp r)
    (throw (ex-info (format "template not found: %s" name)
                    {:reason reasons/reason-init-failed}))))

(defn- yaml-quoted
  [s]
  (pr-str (str/trim (str (or s "")))))

(defn render-ci-pr-safety-workflow
  [opts workflow-name]
  (-> (load-template! "kimen-pr-safety.yml.tmpl")
      (str/replace "__WORKFLOW_NAME__" (str/trim (str workflow-name)))
      (str/replace "__PROFILE_DEFAULT__" (yaml-quoted (:profile opts)))
      (str/replace "__COMMAND_DEFAULT__" (yaml-quoted (:command opts)))))

(defn render-ci-deploy-workflow
  [opts workflow-name]
  (-> (load-template! "kimen-deploy.yml.tmpl")
      (str/replace "__WORKFLOW_NAME__" (str/trim (str workflow-name)))
      (str/replace "__PROFILE_DEFAULT__" (yaml-quoted (:profile opts)))
      (str/replace "__DEPLOY_COMMAND_DEFAULT__" (yaml-quoted (:deploy-command opts)))))

(defn render-ci-sync-gate-workflow
  [opts workflow-name]
  (-> (load-template! "kimen-sync-gate.yml.tmpl")
      (str/replace "__WORKFLOW_NAME__" (str/trim (str workflow-name)))
      (str/replace "__REMOTE_NAME_DEFAULT__" (yaml-quoted (:remote-name opts)))
      (str/replace "__REMOTE_TYPE_DEFAULT__" (yaml-quoted (:remote-type opts)))
      (str/replace "__REMOTE_PATH_DEFAULT__" (yaml-quoted (:remote-path opts)))
      (str/replace "__REMOTE_BRANCH_DEFAULT__" (yaml-quoted (:remote-branch opts)))
      (str/replace "__REMOTE_BUNDLE_PATH_DEFAULT__" (yaml-quoted (:remote-bundle-path opts)))
      (str/replace "__LOCAL_BUNDLE_DEFAULT__" (yaml-quoted (:local-bundle opts)))
      (str/replace "__PROFILE_DEFAULT__" (yaml-quoted (:profile opts)))
      (str/replace "__STALE_THRESHOLD_DEFAULT__" (yaml-quoted (:stale-threshold opts)))))

(defn- write-scaffold-file!
  [path content force?]
  (let [f (io/file path)]
    (when (.exists f)
      (when (.isDirectory f)
        (throw (ex-info (format "output path is a directory: %s" path)
                        {:reason reasons/reason-output-is-directory})))
      (when-not force?
        (throw (ex-info (format "output file already exists: %s (use --force to overwrite)" path)
                        {:reason reasons/reason-output-exists}))))
    (io/make-parents f)
    (spit f content)
    path))

(defn init-ci-pr-safety!
  [{:keys [out force? profile command]}]
  (let [out (some-> out str/trim)
        _ (when (str/blank? out)
            (throw (ex-info "--out cannot be empty"
                            {:reason reasons/reason-missing-out})))
        content (render-ci-pr-safety-workflow {:profile profile
                                               :command command}
                                              ci-pr-safety-workflow-name)
        clean-out (str (.normalize (.toPath (io/file out))))]
    (write-scaffold-file! clean-out content force?)
    {:ok true
     :action "init_ci_pr_safety"
     :exit_code 0
     :out clean-out}))

(defn init-ci-deploy!
  [{:keys [out force? profile deploy-command]}]
  (let [out (some-> out str/trim)
        _ (when (str/blank? out)
            (throw (ex-info "--out cannot be empty"
                            {:reason reasons/reason-missing-out})))
        content (render-ci-deploy-workflow {:profile profile
                                            :deploy-command deploy-command}
                                           ci-deploy-workflow-name)
        clean-out (str (.normalize (.toPath (io/file out))))]
    (write-scaffold-file! clean-out content force?)
    {:ok true
     :action "init_ci_deploy"
     :exit_code 0
     :out clean-out}))

(defn init-ci-sync-gate!
  [{:keys [out force? remote-name remote-type remote-path remote-branch remote-bundle-path local-bundle profile stale-threshold]}]
  (let [remote-type (some-> remote-type str/lower-case str/trim)
        _ (when-not (#{"git" "fs"} remote-type)
            (throw (ex-info "--remote-type must be git or fs"
                            {:reason reasons/reason-invalid-remote-type})))
        out (some-> out str/trim)
        _ (when (str/blank? out)
            (throw (ex-info "--out cannot be empty"
                            {:reason reasons/reason-missing-out})))
        content (render-ci-sync-gate-workflow {:remote-name remote-name
                                               :remote-type remote-type
                                               :remote-path remote-path
                                               :remote-branch remote-branch
                                               :remote-bundle-path remote-bundle-path
                                               :local-bundle local-bundle
                                               :profile profile
                                               :stale-threshold stale-threshold}
                                              ci-sync-gate-workflow-name)
        clean-out (str (.normalize (.toPath (io/file out))))]
    (write-scaffold-file! clean-out content force?)
    {:ok true
     :action "init_ci_sync_gate"
     :exit_code 0
     :out clean-out}))
