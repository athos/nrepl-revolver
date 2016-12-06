(ns nrepl-revolver.middleware.session
  (:require [clojure.tools.nrepl
             [misc :as misc :refer [uuid]]
             [transport :as t]]
   [nrepl-revolver.docker :as docker]))

(def ^:const NREPL_IMAGE_NAME "nrepl-revolver")

(defn- response-for [msg & args]
  (apply misc/response-for (dissoc msg :session) args))

(def ^:private fresh-port
  (let [next-port (atom 5556)]
    (fn []
      (let [port @next-port]
        (swap! next-port inc)
        port))))

(defn create-session [docker]
  (let [port (fresh-port)
        container (docker/create-container docker NREPL_IMAGE_NAME
                                           :bindings {port 5555})]
    (docker/start-container docker container)
    {:id (uuid)
     :port port
     :container container}))

(defn- register-session [sessions {:keys [transport docker] :as msg}]
  (let [{:keys [id] :as session} (create-session docker)]
    (swap! sessions assoc id session)
    (t/send transport (response-for msg :status :done :new-session id))))

(defn- close-session [sessions {:keys [session transport docker] :as msg}]
  (swap! sessions dissoc (:id session))
  (docker/stop-container docker (:container session))
  (t/send transport (response-for msg :status #{:done :session-closed})))

(defn initial-sessions [docker]
  (let [{:keys [id] :as session} (create-session docker)]
    (atom {:default session, id session})))

(defn session [handler sessions docker]
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
            (handler msg)))))))
