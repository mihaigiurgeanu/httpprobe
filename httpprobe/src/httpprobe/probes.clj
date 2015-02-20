(ns httpprobe.probes
  (:require [org.httpkit.client :as http]
            [clojure.core.async :refer [chan go go-loop <! close! put! <!!]]
            [httpprobe.timer :as timer])
  (:use [httpprobe.parser :only [extract-title]]))

(def ^:dynamic *permissions-channel*)
(def ^:dynamic *responses-channel*)
(def ^:dynamic *http-options* {})
(def ^:dynamic *pending-requests*)

(defn- make-url
  "Create an http URL from a host address and a path."
  [host path]
  (str "http://" host path))

(defn- create-request [{:keys [host path] :as req-pair}]
  (let [permissions-channel *permissions-channel*
        responses-channel *responses-channel*
        pending-requests *pending-requests*
        this-request-promise (promise)]
    (send pending-requests conj this-request-promise)
    (try
      (http/get (make-url host path) *http-options*
                (fn [response]
                  (try
                    (put! permissions-channel req-pair)
                    (put! responses-channel response)
                    (deliver this-request-promise 1)
                    (catch Exception e
                      (println "Exception sending permission and response" (.getMessage e))))))
      (catch Exception e
        (println "Exception creating a request for" host path (.getMessage e))
          (put! permissions-channel req-pair)
          (deliver this-request-promise 1)))))

(defn- display-response
  [response-number {:keys [opts body status headers error]}]
  (let [{:keys [url trace-redirects]} opts]
    (println
     (timer/ms)
     response-number
     (if trace-redirects (str trace-redirects "->" url) url)
     status
     error
     (extract-title body))))

(defn send-probes
  "Takes a list of hosts and a list of paths. Sends
  GET requests to all the paths for each and every
  hosts in the list. Retrieves info if the path si valid
  link and, if yes, gets the title of the page"
  [hosts paths batch-size http-options]
  (binding [*permissions-channel* (chan batch-size)
            *responses-channel* (chan batch-size)
            *pending-requests* (agent [])
            *http-options* http-options]
    (let [requests (for [h hosts p paths] {:host h :path p})
          first-batch (take batch-size requests)
          rest-batch (drop batch-size requests)
          requests-finished (agent false)]

      ;; Setting up the display loop
      (go-loop [i 0]
        (when-let [response (<!! *responses-channel*)]
          (display-response i response)
          (if (or (not @requests-finished) (not-every? realized? @*pending-requests*))
            (recur (+ i 1))
            (do
              (println (timer/ms) "Done!")
              (close! *permissions-channel*)
              (close! *responses-channel*)
              (shutdown-agents)))))


      (println (timer/ms) "Start")
      (doseq [r first-batch]
        (create-request r))

      (loop [unprocessed-requests rest-batch process-list true]
        (let [permission (<! *permissions-channel*)]
          (if process-list
            (if (not-empty unprocessed-requests)
              (let [[req & reqs] unprocessed-requests]
                (create-request req)
                (recur reqs true))
              (do
                (send requests-finished (fn [state] true))
                (recur nil false)))
            (when permission (recur nil false))))))))
