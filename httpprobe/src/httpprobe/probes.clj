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
        (println "Sending request to " host path)
        (send pending-requests conj this-request-promise)
        (try
            (http/get (make-url host path) *http-options*
                      (fn [response]
                          (println "Received response for " host path)
                          
                          (try
                              (let [receiveddate (java.util.Date.)]
                                  (put! permissions-channel receiveddate)
                                  (put! responses-channel response)
                                  (deliver this-request-promise receiveddate))
                              (catch Exception e (println "Exception sending permission and response" (.getMessage e))))))
            (catch Exception e (do
                                   (println "Exception creating a request for" host path (.getMessage e))
                                   (let [receiveddate (java.util.Date.)]
                                       (put! permissions-channel receiveddate)
                                       (deliver this-request-promise receiveddate)))))))

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
    (println "Sending probes:" (.toString (java.util.Date.)))
    (binding [*permissions-channel* (chan)
              *responses-channel* (chan)
              *pending-requests* (agent [])]
        (let [requests (for [h hosts p paths] {:host h :path p})
              first-batch (take batch-size requests)
              rest-batch (drop batch-size requests)
              requests-finished (agent false)]
            (doseq [r first-batch]
                (create-request r))
            (go-loop [unprocessed-requests rest-batch] 
                     (if (not-empty unprocessed-requests) 
                         (let [[req & reqs] unprocessed-requests]
                             (do
                                 (println "Waiting permission to send request to" (:host req) (:path req))
                                 (let [permission (<! *permissions-channel*)]
                                     (create-request req))
                                 (recur reqs)))
                         (do
                             (println "All requests enqued" (.toString (java.util.Date.)))
                             (send requests-finished (fn [state] true)))))
            (go-loop [] (when-let [permission (<! *permissions-channel*)]
                            (recur)))
            (loop []
                (when-let [response (<!! *responses-channel*)]
                    ;(display-response response)
                    (when (or (not @requests-finished) (not-every? realized? @*pending-requests*))
                        (recur)))) 
            (println "Done!" (.toString (java.util.Date.)))
            (close! *permissions-channel*)
            (close! *responses-channel*)
            (shutdown-agents))))

