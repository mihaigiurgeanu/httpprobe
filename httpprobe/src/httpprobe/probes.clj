(ns httpprobe.probes
  (:require [org.httpkit.client :as http]
            [clojure.core.async :refer [chan go go-loop <! close! put! <!!]]
            [httpprobe.timer :as timer])
  (:use [httpprobe.parser :only [extract-title]]))

(def ^:dynamic *permissions-channel*)
(def ^:dynamic *http-options* {})
(def ^:dynamic *pending-requests*)
(def ^:dynamic *responses-count*)
(def ^:dynamic *responses-channel*)

(defn- make-url
  "Create an http URL from a host address and a path."
  [host path]
  (str "http://" host path))

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

(defn- create-request [{:keys [host path] :as req-pair}]
  (let [permissions-channel *permissions-channel*
        responses-channel *responses-channel*
        pending-requests *pending-requests*
        this-request-promise (promise)
        responses-count *responses-count*]
    (send pending-requests conj this-request-promise)
    (try
      (http/get (make-url host path) *http-options*
                (fn [response]
                  (try
                    (put! permissions-channel req-pair)
                    (send responses-count
                          (fn [crt-rsp-no]
                            (let [this-rsp-no (+ crt-rsp-no 1)]
                              (display-response this-rsp-no response)
                              (deliver this-request-promise 1)
                              (put! responses-channel this-rsp-no)
                              this-rsp-no)))
                    (catch Exception e
                      (println "Exception sending permission and response" (.getMessage e))))))
      (catch Exception e
        (println "Exception creating a request for" host path (.getMessage e))
          (put! permissions-channel req-pair)
          (deliver this-request-promise 1)))))

(defn send-probes
  "Takes a list of hosts and a list of paths. Sends
  GET requests to all the paths for each and every
  hosts in the list. Retrieves info if the path si valid
  link and, if yes, gets the title of the page"
  [hosts paths batch-size http-options]
  (binding [*permissions-channel* (chan batch-size)
            *responses-channel* (chan batch-size)
            *pending-requests* (agent [])
            *http-options* http-options
            *responses-count* (agent 0)]
    (let [requests (for [h hosts p paths] {:host h :path p})
          first-batch (take batch-size requests)
          rest-batch (drop batch-size requests)
          requests-finished (agent false)]

      ;; Setting up the control loop
      (go-loop []
        (if-let [response-no (<! *responses-channel*)]
          (do
            (send *pending-requests* (fn [state] (filter #(not (realized? %)) state)))
            (when (and @requests-finished (every? realized? @*pending-requests*))
              (close! *responses-channel*))
            (recur))
          (do
            (println (timer/ms) "Done!")
            (close! *permissions-channel*)
            (close! *responses-channel*)
            (shutdown-agents))))


      (println (timer/ms) "Start")
      (doseq [r first-batch]
        (create-request r))

      (loop [unprocessed-requests rest-batch process-list true]
        (let [permission (<!! *permissions-channel*)]
          (if process-list
            (if (not-empty unprocessed-requests)
              (let [[req & reqs] unprocessed-requests]
                (create-request req)
                (recur reqs true))
              (do
                (send requests-finished (fn [state] true))
                (recur nil false)))
            (when permission (recur nil false))))))))
