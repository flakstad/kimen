(ns kimen.build-info)

(set! *warn-on-reflection* true)

(defn current
  []
  {:version (or (some-> (System/getProperty "kimen.version") str)
                "dev")
   :raw-version (or (some-> (System/getProperty "kimen.version") str)
                    "dev")
   :commit (or (some-> (System/getProperty "kimen.commit") str)
               "none")
   :date (or (some-> (System/getProperty "kimen.date") str)
             "unknown")})
