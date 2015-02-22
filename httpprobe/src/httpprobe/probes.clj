(ns httpprobe.probes
  (:require [org.httpkit.client :as http]
            [clojure.core.async :refer [chan go go-loop <! close! put! <!!]]
            [httpprobe.timer :as timer])
  (:use [httpprobe.parser :only [extract-title]]))

(def ^:dynamic *permissions-channel*)
(def ^:dynamic *http-options* {})
(def ^:dynamic *pending-requests*)
(def ^:dynamic *responses-count*)
(def ^:dynamic *probes-finished*)

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

(defn- decrement-pending-requests
  "Decrement the number of pending requests. If this number is
  0 and the probes finished in the input probes list, then
  shuts down the current probes sending session."
  []
  (swap!
   *pending-requests*
   (fn [req-cnt]
     (let [req-left (- req-cnt 1)]
       (when (and @*probes-finished* (<= req-left 0))
         (close! *permissions-channel*))
       req-left))))

(defn- create-request [{:keys [host path] :as req-pair}]
  (let [permissions-channel *permissions-channel*
        pending-requests *pending-requests*
        responses-count *responses-count*
        probes-finished *probes-finished*
        out *out*
        err *err*]
    (swap! pending-requests + 1)
    (try
      (http/get
       (make-url host path)
       *http-options*
       (fn [response]
         (binding [*permissions-channel* permissions-channel
                   *responses-count* responses-count
                   *probes-finished* probes-finished
                   *pending-requests* pending-requests
                   *out* out
                   *err* err]
           (put! permissions-channel req-pair)
           (send responses-count
                 (fn [crt-rsp-no]
                   (let [this-rsp-no (+ crt-rsp-no 1)]
                     (display-response this-rsp-no response)
                     (decrement-pending-requests)
                     this-rsp-no))))))
      (catch Exception e
        (println "Exception creating a request for" host path (.getMessage e))
        (.printStackTrace e *err*)
        (put! permissions-channel req-pair)
        (send responses-count
              (fn [crt-rsp-no]
                (decrement-pending-requests)
                (+ 1 crt-rsp-no)))))))

(defn send-probes
  "Takes a list of hosts and a list of paths. Sends
  GET requests to all the paths for each and every
  hosts in the list. Retrieves info if the path si valid
  link and, if yes, gets the title of the page"
  [hosts paths batch-size http-options]
  (binding [*permissions-channel* (chan batch-size)
            *pending-requests* (atom 0)
            *http-options* http-options
            *responses-count* (agent 0)
            *probes-finished* (atom false)]
    (let [probes (for [h hosts p paths] {:host h :path p})
          first-batch (take batch-size probes)
          rest-batch (drop batch-size probes)]

      (println (timer/ms) "Start")
      (doseq [r first-batch]
        (create-request r))

      ;;send the rest of the request
      (loop [unprocessed-probes rest-batch]
        (let [permission (<!! *permissions-channel*)]
          (if (not-empty unprocessed-probes)
            (let [[p & ps] unprocessed-probes]
              (create-request p)
              (recur ps))
            (reset! *probes-finished* true))))

      ;;consume the end of permissions and wait for channel to close
      (loop []
        (when (<!! *permissions-channel*) (recur))))

    (println (timer/ms) "Done!" @*responses-count* "http probes processed")))
