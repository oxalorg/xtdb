(ns ^:no-doc xtdb.cache
  (:require [xtdb.cache.second-chance :as sc]
            [xtdb.system :as sys]
            [xtdb.cache.lru :as lru]
            [clojure.tools.logging :as log])
  (:import xtdb.cache.ICache
           xtdb.cache.second_chance.ConcurrentHashMapTableAccess))

(defn compute-if-absent [^ICache cache k stored-key-fn f]
  (.computeIfAbsent cache k stored-key-fn f))

(defn evict [^ICache cache k]
  (.evict cache k))

(defn ->cache
  {::sys/args {:cache-size {:doc "Cache size"
                            :default (* 128 1024)
                            :spec ::sys/nat-int}}}
  ^xtdb.cache.ICache [opts]
  (if (ConcurrentHashMapTableAccess/canAccessTable)
    (sc/->second-chance-cache opts)
    (do
      (defonce scc-warning
        (log/warn "Could not open ConcurrentHashMap.table field - falling back to LRU caching. Use `--add-opens java.base/java.util.concurrent=ALL-UNNAMED` to use the second-chance cache.")) ;

      (lru/->lru-cache opts))))
