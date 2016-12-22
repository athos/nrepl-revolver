(ns nrepl-revolver.middleware.session
  (:require [clojure.tools.nrepl
             [misc :as misc :refer [uuid response-for]]
             [transport :as t]]
            [nrepl-revolver.container-pool :as pool]
            [clojure.tools.nrepl :as nrepl]))

(defrecord SessionManager [sessions transport->sessions pool])

(defn create-session [pool]
  (let [{:keys [container port]} (pool/use-container pool)]
    {:id (uuid)
     :container container
     :nrepl (delay {:client (-> (nrepl/connect :port port)
                                (nrepl/client 1000))})}))

(defn session-nrepl [session]
  @(:nrepl session))

(defn- register-session [manager {:keys [transport] :as msg}]
  (let [{:keys [id] :as session} (create-session (:pool @manager))
        msg (dissoc msg :session)
        update-nrepl-id (fn [{c :client :as nrepl}]
                          (let [id (->> (nrepl/message c {:op :clone})
                                        (some :new-session))]
                            (assoc nrepl :id id)))
        ss (update session :nrepl #(delay (update-nrepl-id @%)))]
    (swap! manager
           #(-> %
                (update :sessions assoc id ss)
                (update-in [:transport->sessions transport]
                           (fnil conj #{}) id)))
    (t/send transport (response-for msg :status :done :new-session id))))

(defn- close-session [manager {:keys [session transport] :as msg}]
  (let [id (:id session)]
    (swap! manager
           #(-> %
                (update :sessions dissoc id)
                (update-in [:transport->sessions transport] disj id))))
  (pool/dispose-container (:pool @manager) (:container session))
  (t/send transport (response-for msg :status #{:done :session-closed})))

(defn close-sessions-for [manager transport]
  (let [ids (get-in @manager [:transport->sessions transport])]
    (doseq [id ids
            :let [session (get (:sessions @manager) id)]]
      (pool/dispose-container (:pool @manager) (:container session)))
    (swap! manager
           (fn [manager]
             (let [sessions' (into {} (remove #(contains? ids (key %)))
                                   (:sessions manager))]
               (-> manager
                   (assoc :sessions sessions')
                   (update :transport->sessions dissoc transport)))))))

(defn session-manager [pool]
  (let [{:keys [id] :as session} (create-session pool)]
    (atom (->SessionManager {:default session, id session}
                            {}
                            pool))))

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
      (pool/destroy-container pool container))
    (pool/shutdown pool))
  (swap! manager assoc :sessions {} :transport->sessions {}))
