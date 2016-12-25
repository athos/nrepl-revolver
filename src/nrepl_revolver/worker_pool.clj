(ns nrepl-revolver.worker-pool)

(defprotocol IWorker
  (transport [this]))

(defprotocol IWorkerPool
  (adopt-worker [this])
  (dismiss-worker [this worker])
  (shutdown [this]))
