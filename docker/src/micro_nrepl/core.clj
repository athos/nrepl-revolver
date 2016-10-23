(ns micro-nrepl.core
  (:gen-class)
  (:require [clojure.tools.nrepl.server :as nrepl]))

(defn -main [& [address port]]
  (let [address (or address "localhost")
        port (Long/parseLong (or port "5555"))]
    (println "Waiting for connection at" (str address ":" port) "....")
    (nrepl/start-server :bind address :port port)))
