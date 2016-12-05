(ns nrepl-revolver.server
  (:require [clojure.tools.nrepl :as nrepl]
            [clojure.tools.nrepl
             [misc :refer [uuid response-for log]]
             [server :as server]
             [transport :as t]]
            [nrepl-revolver.docker :as docker]
            [nrepl-revolver.middleware.session :as session]))

(def ^:const NREPL_IMAGE_NAME "nrepl-revolver")

(defn create-session [docker]
  (let [port 5556
        container (docker/create-container docker NREPL_IMAGE_NAME
                                           :bindings {port 5556})]
    (docker/start-container docker container)
    {:id (uuid)
     :port port
     :container container}))

(defn- register-session [sessions {:keys [session transport docker] :as msg}]
  (let [{:keys [id] :as session} (create-session docker transport)]
    (swap! sessions assoc id session)
    (t/send transport (response-for msg :status :done :new-session id))))

(defn- close-session [sessions {:keys [session transport docker] :as msg}]
  (swap! sessions dissoc (:id session))
  (docker/stop-container docker (:container session))
  (t/send transport (response-for msg :status #{:done :session-closed})))

(defn- with-nrepl-client [session f]
  (with-open [conn (nrepl/connect :port (:port session))]
    (f (nrepl/client conn 1000))))

(defn- redirect-message [{:keys [session transport] :as msg}]
  (with-nrepl-client session
    (fn [client]
      (let [msg (dissoc msg :session :transport :docker)]
        (doseq [res (nrepl/message client msg)]
          (t/send transport res))))))

(defn session-handler [sessions docker]
  (fn [{:keys [op session transport] :as msg}]
    (let [the-session (if session
                        (get @sessions session)
                        (:default @sessions))]
      (if-not the-session
        (t/send transport (response-for msg :status #{:error :unknown-session}))
        (let [msg (assoc msg :session the-session :docker docker)]
          (case op
            "clone" (register-session sessions msg)
            "close" (close-session sessions msg)
            (redirect-message msg)))))))

(defrecord RevolverServer [server docker sessions])

(defn- initial-sessions [docker]
  (let [{:keys [id] :as session} (create-session docker)]
    (atom {:default session, id session})))

(defn start-server [& {:keys [port] :or {port 5555}}]
  (let [docker (docker/make-client "tcp://localhost:2376")
        sessions (initial-sessions docker)
        handler (session-handler sessions docker)]
    (->RevolverServer (server/start-server :port port :handler handler)
                      docker
                      sessions)))

(defn stop-server [server]
  (doseq [[id {:keys [container]}] @(:sessions server)
          :when (not= id :default)]
    (docker/stop-container (:docker server) container))
  (reset! (:sessions server) {})
  (server/stop-server (:server server)))
