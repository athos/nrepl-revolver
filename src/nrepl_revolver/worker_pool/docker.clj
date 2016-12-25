(ns nrepl-revolver.worker-pool.docker
  (:require [clojure.tools.nrepl :as nrepl]
            [nrepl-revolver.docker :as docker]
            [nrepl-revolver.worker-pool :as pool])
  (:import clojure.lang.PersistentQueue))

(def ^:const NREPL_IMAGE_NAME "nrepl-revolver")

(defrecord DockerContainerWorker [container port]
  pool/IWorker
  (transport [this]
    (nrepl/connect :port port)))

(def ^:private fresh-port
  (let [next-port (atom 5556)]
    (fn []
      (let [port @next-port]
        (swap! next-port inc)
        port))))

(defn- load-worker [docker]
  (let [port (fresh-port)
        container (docker/create-container docker NREPL_IMAGE_NAME
                                           :bindings {port 5555})]
    (docker/start-container docker container)
    (->DockerContainerWorker container port)))

(defn- charge-workers [docker num workers]
  (into workers
        (map (fn [_] (load-worker docker)))
        (range num)))

(defn- destroy-worker [pool worker]
  (docker/stop-container (:docker pool) (:container worker))
  (docker/remove-container (:docker pool) (:container worker))
  nil)

(defrecord DockerContainerPool [docker max workers]
  pool/IWorkerPool
  (adopt-worker [this]
    (let [ret (volatile! nil)]
      (swap! (:workers this)
             (fn [workers]
               (if-let [[worker] (seq workers)]
                 (do (vreset! ret worker)
                     (pop workers))
                 workers)))
      @ret))

  (dismiss-worker [this worker]
    (destroy-worker this worker)
    (future
      (swap! (:workers this) #(charge-workers (:docker this) 1 %)))
    nil)

  (shutdown [this]
    #_(doseq [[id {:keys [container nrepl]}] (:sessions @manager)
            :when (not= id :default)]
      (pool/destroy-container pool container))
    (doseq [worker @(:workers this)]
      (destroy-worker this worker))))

(defn container-pool [docker max]
  (let [workers (charge-workers docker max PersistentQueue/EMPTY)]
    (->DockerContainerPool docker max (atom workers))))
