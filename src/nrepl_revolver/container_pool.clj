(ns nrepl-revolver.container-pool
  (:require [nrepl-revolver.docker :as docker])
  (:import clojure.lang.PersistentQueue))

(def ^:const NREPL_IMAGE_NAME "nrepl-revolver")

(defrecord ContainerPool [docker max containers])

(def ^:private fresh-port
  (let [next-port (atom 5556)]
    (fn []
      (let [port @next-port]
        (swap! next-port inc)
        port))))

(defn- load-container [docker]
  (let [port (fresh-port)
        container (docker/create-container docker NREPL_IMAGE_NAME
                                           :bindings {port 5555})]
    (docker/start-container docker container)
    {:container container :port port}))

(defn- charge-containers [docker num containers]
  (into containers
        (map (fn [_] (load-container docker)))
        (range num)))

(defn make-pool [docker max]
  (let [containers (charge-containers docker max PersistentQueue/EMPTY)]
    (->ContainerPool docker max (atom containers))))

(defn use-container [pool]
  (let [ret (volatile! nil)]
    (swap! (:containers pool)
           (fn [containers]
             (if-let [[container] (seq containers)]
               (do (vreset! ret container)
                   (pop containers))
               containers)))
    @ret))

(defn destroy-container [pool container]
  (docker/stop-container (:docker pool) container)
  (docker/remove-container (:docker pool) container)
  nil)

(defn dispose-container [pool container]
  (destroy-container pool container)
  (future
    (swap! (:containers pool) #(charge-containers (:docker pool) 1 %)))
  nil)

(defn shutdown [pool]
  (doseq [{:keys [container]} @(:containers pool)]
    (destroy-container pool container)))
