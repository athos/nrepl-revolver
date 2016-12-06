(ns nrepl-revolver.docker
  (:import [com.github.dockerjava.api DockerClient]
           [com.github.dockerjava.api.command CreateContainerResponse]
           [com.github.dockerjava.api.model ExposedPort Ports Ports$Binding]
           [com.github.dockerjava.core DockerClientBuilder]))

(defn make-client [^String server-url]
  (. (DockerClientBuilder/getInstance server-url) build))

(defn info [client]
  (.. ^DockerClient client (infoCmd) (exec)))

(defn create-container [client image & {:keys [command expose bindings]}]
  (let [expose (some->> (or expose (and bindings (vals bindings)))
                        (map #(ExposedPort/tcp (long %)))
                        (into-array ExposedPort))
        bindings (and bindings
                      (let [ports (Ports.)]
                        (doseq [[from to] bindings]
                          (.bind ports
                                 (ExposedPort/tcp (long to))
                                 (Ports$Binding/bindPort (long from))))
                        ports))]
    (cond-> (.createContainerCmd ^DockerClient client ^String image)
      command (.withCmd (into-array String command))
      expose (.withExposedPorts expose)
      bindings (.withPortBindings bindings)
      true (.exec))))

(defn ^String ->container-id [container]
  (if (string? container)
    container
    (.getId ^CreateContainerResponse container)))

(defn inspect-container [client container]
  (.. client
      (inspectContainerCmd (->container-id container))
      (exec)))

(defn start-container [client container]
  (.. client
      (startContainerCmd (->container-id container))
      (exec)))

(defn stop-container [client container]
  (.. client
      (stopContainerCmd (->container-id container))
      (exec)))

(defn remove-container [^DockerClient client container & {:keys [force?]}]
  (.. client
      (removeContainerCmd (->container-id container))
      (withForce (boolean force?))
      (exec)))
