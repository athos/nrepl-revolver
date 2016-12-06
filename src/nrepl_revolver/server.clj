(ns nrepl-revolver.server
  (:require [clojure.tools.nrepl :as nrepl]
            [clojure.tools.nrepl
             [server :as server]
             [transport :as t]]
            [nrepl-revolver.docker :as docker]
            [nrepl-revolver.middleware.session :as session]))

(defn- with-nrepl-client [session f]
  (with-open [conn (nrepl/connect :port (:port session))]
    (f (nrepl/client conn 1000))))

(defn redirecting-handler [{:keys [session transport] :as msg}]
  (with-nrepl-client session
    (fn [client]
      (let [msg (dissoc msg :session :transport :docker)]
        (doseq [res (nrepl/message client msg)]
          (t/send transport res))))))

(defrecord RevolverServer [server docker sessions])

(defn start-server [& {:keys [port] :or {port 5555}}]
  (let [docker (docker/make-client "tcp://localhost:2376")
        sessions (session/initial-sessions docker)
        handler (-> redirecting-handler
                    (session/session sessions docker))]
    (->RevolverServer (server/start-server :port port :handler handler)
                      docker
                      sessions)))

(defn stop-server [server]
  (doseq [[id {:keys [container]}] @(:sessions server)
          :when (not= id :default)]
    (docker/stop-container (:docker server) container))
  (reset! (:sessions server) {})
  (server/stop-server (:server server)))
