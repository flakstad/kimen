(ns kimen.commands.version
  (:require [kimen.build-info :as build-info]))

(defn payload
  []
  (let [{:keys [version raw-version commit date]} (build-info/current)]
    {:ok true
     :action "version"
     :exit_code 0
     :version version
     :raw_version raw-version
     :commit commit
     :date date}))
