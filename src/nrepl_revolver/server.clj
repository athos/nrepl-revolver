(ns nrepl-revolver.server
  (:require [clojure.set :as set]
            [clojure.tools.nrepl :as nrepl]
            [clojure.tools.nrepl
             [server :as server]
             [transport :as t]]
            [nrepl-revolver.container-pool :as pool]
            [nrepl-revolver.docker :as docker]
            [nrepl-revolver.middleware.session :as session]))

(defn redirecting-handler [{:keys [session transport] :as msg}]
  (let [{:keys [id client]} (session/session-nrepl session)
        msg (-> msg
                (dissoc :transport)
                (assoc :session id))]
    (doseq [res (nrepl/message client msg)
            :let [res' (cond-> res
                         (= id (:session res))
                         (assoc :session (:id session)))]]
      (t/send transport res'))))

(defrecord RevolverServer [server sessions])

(defn start-server [& {:keys [port] :or {port 5555}}]
  (let [docker (docker/make-client "tcp://localhost:2376")
        pool (pool/make-pool docker 3)
        manager (session/session-manager pool)
        handler (-> redirecting-handler
                    (session/session manager))
        server (server/start-server :port port :handler handler)]
    (add-watch (:open-transports server) ::close
               (fn [_ _ old new]
                 (doseq [transport (set/difference old new)]
                   (session/close-sessions-for manager transport))))
    (->RevolverServer server manager)))

(defn stop-server [server]
  (session/shutdown-sessions (:sessions server))
  (server/stop-server (:server server)))
