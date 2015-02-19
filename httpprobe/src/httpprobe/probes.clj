(ns httpprobe.probes
  (:require [org.httpkit.client :as http]
            [clojure.core.async :refer [chan go go-loop <! close! put! <!!]])
  (:use [httpprobe.parser :only [extract-title]]))

(def ^:dynamic *permissions-channel*)
(def ^:dynamic *responses-channel*)
(def ^:dynamic *http-options* {})
(def ^:dynamic *pending-requests*)

(defn- make-url
  "Create an http URL from a host address and a path."
  [host path]
  (str "http://" host path))

(defn- create-request [{:keys [host path]}]
  (let [permissions-channel *permissions-channel*
        responses-channel *responses-channel*
        pending-requests *pending-requests*
        this-request-promise (promise)]
    (println "Sending request to " host path (System/nanoTime))
    (send pending-requests conj this-request-promise)
    (try
      (http/get (make-url host path) *http-options*
                (fn [response]
                  (println "Received response for " host path)
                  (try
                    (let [receiveddate (System/nanoTime)]
                      (println "Sending permission" receiveddate)
                      (put! permissions-channel receiveddate)
                      (put! responses-channel response)
                      (deliver this-request-promise receiveddate))
                    (catch Exception e
                      (println "Exception sending permission and response" (.getMessage e))))))
      (catch Exception e
        (println "Exception creating a request for" host path (.getMessage e))
        (let [receiveddate (System/nanoTime)]
          (put! permissions-channel receiveddate)
          (deliver this-request-promise receiveddate))))))

(defn- display-response
  [{:keys [opts body status headers error]}]
  (let [{:keys [url trace-redirects]} opts]
    (println (if trace-redirects (str trace-redirects "->" url) url)
             status
             error
             (extract-title body))))

(defn send-probes
  "Takes a list of hosts and a list of paths. Sends
  GET requests to all the paths for each and every
  hosts in the list. Retrieves info if the path si valid
  link and, if yes, gets the title of the page"
  [hosts paths batch-size]
  (println "Sending probes:" (System/nanoTime))
  (binding [*permissions-channel* (chan batch-size)
            *responses-channel* (chan batch-size)
            *pending-requests* (agent [])]
    (let [requests (for [h hosts p paths] {:host h :path p})
          first-batch (take batch-size requests)
          rest-batch (drop batch-size requests)
          requests-finished (agent false)]
      (doseq [r first-batch]
        (create-request r))
      (go-loop [unprocessed-requests rest-batch process-list true]
               (println "Waiting permission to send request")
               (let [permission (<! *permissions-channel*)]
                 (if process-list
                   (if (not-empty unprocessed-requests)
                     (let [[req & reqs] unprocessed-requests]
                       (println "Received persmission"
                                (:host req)
                                (:path req)
                                permission)
                       (create-request req)
                       (recur reqs true))
                     (do
                       (println "All requests enqued" (System/nanoTime))
                       (send requests-finished (fn [state] true))
                       (recur nil false)))
                   (when permission (recur nil false)))))

      (loop []
        (when-let [response (<!! *responses-channel*)]
          ;(display-response response)
          (when (or (not @requests-finished) (not-every? realized? @*pending-requests*))
            (recur))))
      (println "Done!" (System/nanoTime))
      (close! *permissions-channel*)
      (close! *responses-channel*)
      (shutdown-agents))))
