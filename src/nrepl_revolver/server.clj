(ns nrepl-revolver.server
  (:require [clojure.tools.nrepl :as nrepl]
            [clojure.tools.nrepl.middleware.session :as session]
            [clojure.tools.nrepl.server :as server]
            [clojure.tools.nrepl.transport :as transport]
            [nrepl-revolver.docker :as docker]))

(defn make-client [port]
  {:port port :nrepl (atom nil)})

(defn nrepl-client [c]
  (or @(:nrepl c)
      (let [conn (nrepl/connect :port (:port c))
            client (nrepl/client conn 1000)]
        (swap! (:nrepl c) client)
        client)))

(defn proxy-handler [client]
  (fn [{:keys [transport] :as msg}]
    (print "receive message: ")
    (prn msg)
    (let [nrepl (nrepl-client client)]
      (doseq [res (nrepl/message nrepl (dissoc msg :transport))]
        (print "sending response: ")
        (prn res)
        (transport/send transport res)))))

(defn start-server [& {:keys [port] :or {port 5555}}]
  (let [client (docker/make-client "tcp://localhost:2376")
        container (docker/create-container client "nrepl-revolver"
                                           :bindings {5556 5556})
        _ (docker/start-container client container)
        handler (-> (make-client 5556)
                    proxy-handler
                    session/session)]
    (server/start-server :port port :handler handler)))
