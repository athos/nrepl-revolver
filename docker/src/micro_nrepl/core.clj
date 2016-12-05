(ns micro-nrepl.core
  (:gen-class)
  (:require [clojure.tools.nrepl.server :as nrepl])
  (:import [java.net InetAddress]))

(defn -main []
  (let [address (.getHostAddress (InetAddress/getLocalHost))
        port 5555]
    (println "Waiting for connection at" (str address ":" port) "....")
    (nrepl/start-server :bind address :port port)))
