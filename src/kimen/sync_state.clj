(ns kimen.sync-state
  (:require
    [clojure.string :as str]
    [kimen.reason-codes :as reasons]))

(defn detect-conflict
  [last-seen remote-rev has-remote]
  (let [last-seen (some-> last-seen str str/trim)
        last-seen (when-not (str/blank? last-seen) last-seen)
        remote-rev (some-> remote-rev str str/trim)
        remote-rev (when-not (str/blank? remote-rev) remote-rev)]
    (cond
      (and (not has-remote) (nil? last-seen))
      {:has-conflict false}

      (and (not has-remote) (some? last-seen))
      {:has-conflict true
       :reason reasons/reason-remote-disappeared
       :message (format "remote bundle disappeared since last sync (expected rev %s)" last-seen)
       :expected-rev last-seen}

      (and has-remote (nil? last-seen))
      {:has-conflict true
       :reason reasons/reason-no-local-baseline
       :message (format "remote has data (rev %s) but no local baseline; run `kimen sync pull` first"
                        (or remote-rev "unknown"))
       :actual-rev remote-rev}

      (and has-remote (not= last-seen remote-rev))
      {:has-conflict true
       :reason reasons/reason-remote-changed
       :message (format "remote changed (expected rev %s, found %s); run `kimen sync pull`, re-apply changes, then push"
                        last-seen
                        (or remote-rev "unknown"))
       :expected-rev last-seen
       :actual-rev remote-rev}

      :else
      {:has-conflict false})))
