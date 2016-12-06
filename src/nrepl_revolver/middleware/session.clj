(ns nrepl-revolver.middleware.session
  (:require [clojure.tools.nrepl
             [misc :as misc :refer [uuid]]
             [transport :as t]]
            [nrepl-revolver.container-pool :as pool]))

(defn- response-for [msg & args]
  (apply misc/response-for (dissoc msg :session) args))

(defn create-session [pool]
  (let [{:keys [container port]} (pool/use-container pool)]
    {:id (uuid)
     :port port
     :container container}))

(defn- register-session [sessions {:keys [transport pool] :as msg}]
  (let [{:keys [id] :as session} (create-session pool)]
    (swap! sessions assoc id session)
    (t/send transport (response-for msg :status :done :new-session id))))

(defn- close-session [sessions {:keys [session transport pool] :as msg}]
  (swap! sessions dissoc (:id session))
  (pool/dispose-container pool (:container session))
  (t/send transport (response-for msg :status #{:done :session-closed})))

(defn initial-sessions [pool]
  (let [{:keys [id] :as session} (create-session pool)]
    (atom {:default session, id session})))

(defn session [handler sessions pool]
  (fn [{:keys [op session transport] :as msg}]
    (let [the-session (if session
                        (get @sessions session)
                        (:default @sessions))]
      (if-not the-session
        (t/send transport (response-for msg :status #{:error :unknown-session}))
        (let [msg (assoc msg :session the-session :pool pool)]
          (case op
            "clone" (register-session sessions msg)
            "close" (close-session sessions msg)
            (handler msg)))))))
