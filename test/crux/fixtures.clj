(ns crux.fixtures
  (:require [clojure.test :as t]
            [clojure.test.check.clojure-test :as tcct]
            [crux.api :as api]
            [crux.fixtures.kafka :refer [*kafka-bootstrap-servers*]]
            [crux.fixtures.kv :refer [*kv* *kv-backend*]]
            [crux.http-server :as srv]
            [crux.io :as cio]
            [crux.kafka.embedded :as ek]
            [crux.tx :as tx])
  (:import [crux.api Crux ICruxAPI]
           java.util.UUID))

(def ^:dynamic *api-url*)
(def ^:dynamic ^ICruxAPI *api*)
(def ^:dynamic ^ICruxAPI *cluster-node*)

(defn with-cluster-node [f]
  (assert (bound? #'*kafka-bootstrap-servers*))
  (assert (not (bound? #'*kv*)))
  (let [server-port (cio/free-port)
        db-dir (str (cio/create-tmpdir "kv-store"))
        test-id (UUID/randomUUID)
        tx-topic (str "tx-topic-" test-id)
        doc-topic (str "doc-topic-" test-id)
        options {:server-port server-port
                 :db-dir db-dir
                 :tx-topic tx-topic
                 :doc-topic doc-topic
                 :kv-backend *kv-backend*
                 :bootstrap-servers *kafka-bootstrap-servers*}]
    (try
      (with-open [cluster-node (Crux/startClusterNode options)
                  http-server (srv/start-http-server cluster-node options)]
        (binding [*cluster-node* cluster-node
                  *api* cluster-node
                  *api-url* (str "http://" ek/*host* ":" server-port)]
          (f)))
      (finally
        (cio/delete-dir db-dir)))))

(defn with-standalone-system [f]
  (assert (not (bound? #'*kv*)))
  (let [db-dir (str (cio/create-tmpdir "kv-store"))
        event-log-dir (str (cio/create-tmpdir "event-log-dir"))
        options {:db-dir db-dir
                 :event-log-dir event-log-dir
                 :kv-backend *kv-backend*}]
    (try
      (with-open [standalone-system (Crux/startStandaloneSystem options)]
        (binding [*api* standalone-system]
          (f)))
      (finally
        (cio/delete-dir db-dir)
        (cio/delete-dir event-log-dir)))))

(defn with-api-client [f]
  (assert (bound? #'*api-url*))
  (with-open [api-client (Crux/newApiClient *api-url*)]
    (binding [*api* api-client]
      (f))))

(defn with-each-api-implementation [f]
  (t/testing "Local API ClusterNode"
    (with-cluster-node f))
  (t/testing "Local API StandaloneSystem"
    (with-standalone-system f))
  (t/testing "Remote API"
    (with-cluster-node
      #(with-api-client f))))

(defn with-silent-test-check [f]
  (binding [tcct/*report-completion* false]
    (f)))

(defn maps->tx-ops
  ([maps]
   (vec (for [m maps]
          [:crux.tx/put m])))
  ([maps ts]
   (vec (for [m maps]
          [:crux.tx/put m ts]))))

(defn transact!
  "Helper fn for transacting entities"
  ([api entities]
   (transact! api entities (cio/next-monotonic-date)))
  ([^ICruxAPI api entities ts]
   (let [submitted-tx (api/submit-tx api (maps->tx-ops entities ts))]
     (api/sync api (:crux.tx/tx-time submitted-tx) nil))
   entities))

(defn entities->delete-tx-ops [entities ts]
  (vec (for [e entities]
         [:crux.tx/delete e ts])))

(defn delete-entities!
  ([api entities]
   (delete-entities! api entities (cio/next-monotonic-date)))
  ([api entities ts]
   (let [submitted-tx (api/submit-tx api (entities->delete-tx-ops entities ts))]
     (api/sync api (:crux.tx/tx-time submitted-tx) nil))
   entities))

(defn random-person [] {:crux.db/id (UUID/randomUUID)
                        :name      (rand-nth ["Ivan" "Petr" "Sergei" "Oleg" "Yuri" "Dmitry" "Fedor" "Denis"])
                        :last-name (rand-nth ["Ivanov" "Petrov" "Sidorov" "Kovalev" "Kuznetsov" "Voronoi"])
                        :sex       (rand-nth [:male :female])
                        :age       (rand-int 100)
                        :salary    (rand-int 100000)})

(defn people [people-mixins]
  (->> people-mixins (map merge (repeatedly random-person))))
