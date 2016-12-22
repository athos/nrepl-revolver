(ns nrepl-revolver.middleware.session
  (:require [clojure.tools.nrepl
             [misc :as misc :refer [uuid response-for]]
             [transport :as t]]
            [nrepl-revolver.container-pool :as pool]
            [clojure.tools.nrepl :as nrepl]))

(defrecord SessionManager [sessions pool])

(defn create-session [pool]
  (let [{:keys [container port]} (pool/use-container pool)]
    {:id (uuid)
     :port port
     :container container
     :nrepl (atom nil)}))

(defn with-session-nrepl-client [session f]
  (swap! (:nrepl session)
         (fn [nrepl]
           (or nrepl
               (let [conn (nrepl/connect :port (:port session))]
                 {:conn conn
                  :client (nrepl/client conn 1000)}))))
  (f (:client @(:nrepl session))))

(defn- register-session [manager {:keys [transport] :as msg}]
  (let [{:keys [id] :as session} (create-session (:pool @manager))
        msg (dissoc msg :session)]
    (with-session-nrepl-client session
      (fn [client]
        (when-let [id' (->> (nrepl/message client {:op :clone})
                            (some :new-session))]
          (swap! (:nrepl session) assoc :id id'))))
    (swap! manager update :sessions assoc id session)
    (t/send transport (response-for msg :status :done :new-session id))))

(defn- close-session [manager {:keys [session transport] :as msg}]
  (swap! manager update :sessions dissoc (:id session))
  (pool/dispose-container (:pool @manager) (:container session))
  (t/send transport (response-for msg :status #{:done :session-closed})))

(defn session-manager [pool]
  (let [{:keys [id] :as session} (create-session pool)]
    (atom (->SessionManager {:default session, id session} pool))))

(defn session [handler manager]
  (fn [{:keys [op session transport] :as msg}]
    (let [the-session (get (:sessions @manager) (or session :default))]
      (if-not the-session
        (t/send transport (response-for msg :status #{:error :unknown-session}))
        (let [msg (assoc msg :session the-session)]
          (case op
            "clone" (register-session manager msg)
            "close" (close-session manager msg)
            (handler msg)))))))

(defn shutdown-sessions [manager]
  (let [pool (:pool @manager)]
    (doseq [[id {:keys [container nrepl]}] (:sessions @manager)
            :when (not= id :default)]
      (when-let [{:keys [^java.io.Closeable conn]} @nrepl]
        (.close conn))
      (pool/destroy-container pool container))
    (pool/shutdown pool))
  (swap! manager assoc :sessions {}))
