(ns nrepl-revolver.server
  (:require [clojure.tools.nrepl :as nrepl]
            [clojure.tools.nrepl
             [server :as server]
             [transport :as t]]
            [nrepl-revolver.container-pool :as pool]
            [nrepl-revolver.docker :as docker]
            [nrepl-revolver.middleware.session :as session]))

(defn redirecting-handler [{:keys [session transport] :as msg}]
  (session/with-session-nrepl-client session
    (fn [client]
      (let [msg (dissoc msg :session :transport :pool)]
        (doseq [res (nrepl/message client msg)]
          (t/send transport res))))))

(defrecord RevolverServer [server pool sessions])

(defn start-server [& {:keys [port] :or {port 5555}}]
  (let [docker (docker/make-client "tcp://localhost:2376")
        pool (pool/make-pool docker 3)
        sessions (session/initial-sessions pool)
        handler (-> redirecting-handler
                    (session/session sessions pool))]
    (->RevolverServer (server/start-server :port port :handler handler)
                      pool
                      sessions)))

(defn stop-server [server]
  (session/shutdown-sessions (:sessions server))
  (reset! (:sessions server) {})
  (server/stop-server (:server server)))
