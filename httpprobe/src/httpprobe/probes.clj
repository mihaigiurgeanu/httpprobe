(ns httpprobe.probes
  (:require [org.httpkit.client :as http]
            [clojure.core.async :refer [chan go go-loop <! >! close! >!! <!!]])
  (:use [httpprobe.parser :only [extract-title]]))

(def ^:dynamic *permissions-channel*)
(def ^:dynamic *responses-channel*)

(defn- make-url
 "Create an http URL from a host address and a path."
 [host path]
 (str "http://" host path))

(defn- send-probes-to-host [host paths]
  (println "Sending probes to host" host)
  (let [urls (map #(make-url host %) paths)]
    (map http/get urls)))

(defn- display-response
  [request]
  (let [{:keys [opts body status headers error]} @request
           {:keys [url trace-redirects]} opts]
       (println (if trace-redirects (str trace-redirects "->" url) url)
                status
                error
                (extract-title body))))

(defn- process-request 
    "Creates a future that waits for the request to finish, writes the response to the responses
    channel and a permission to the permissions channel."
    [r]
    (future
        (try
            (let [response @r]
                (>!! *permissions-channel* response)
                (>!! *responses-channel* response))
            (catch Exception e (do (>!! *permissions-channel* e)
                                   (println (.getMessage e)))))))

(defn send-probes
    "Takes a list of hosts and a list of paths. Sends
    GET requests to all the paths for each and every
    hosts in the list. Retrieves info if the path si valid
    link and, if yes, gets the title of the page"
    [hosts paths batch-size]
    (println "Sending probes:" (.toString (java.util.Date.)))
    (binding [*permissions-channel* (chan)
              *responses-channel* (chan)]
        (let [requests (mapcat #(send-probes-to-host % paths) hosts)
              first-batch (take batch-size requests)
              rest-batch (drop batch-size requests)]
            (doseq [r first-batch]
                (process-request r))
            (go-loop [unprocessed-requests rest-batch] 
                     (if-let [[req & reqs] unprocessed-requests]
                         (do
                             (let [permission (<! *permissions-channel*)]
                                 (process-request req))
                             (recur reqs))
                         (do
                             (println "All requests enqued" (.toString (java.util.Date.)))
                             (close! *permissions-channel*)
                             (close! *responses-channel*))))
            (loop []
                (when-let [response (<!! *responses-channel*)]
                    (display-response response)))
            (println "Done!" (.toString (java.util.Date.))))))

