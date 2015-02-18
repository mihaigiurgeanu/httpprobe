(ns httpprobe.probes
  (:require [org.httpkit.client :as http]
            [clojure.core.async :refer [chan go go-loop <! >! close! >!! <!!]])
  (:use [httpprobe.parser :only [extract-title]]))

(def ^:dynamic *channel*)
(def ^:dynamic *control-channel*)

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

(defn send-probes
  "Takes a list of hosts and a list of paths. Sends
  GET requests to all the paths for each and every
  hosts in the list. Retrieves info if the path si valid
  link and, if yes, gets the title of the page"
  [hosts paths batch-size]
  (println "Start:" (.toString (java.util.Date.)))
  (binding [*channel* (chan batch-size)
            *control-channel* (chan)]
    (let [requests (mapcat #(send-probes-to-host % paths) hosts)]
      (go-loop [unprocessed-requests requests]
               (if (empty? unprocessed-requests)
                 (>!! *control-channel* "Done")
                 (let [[next-req & rest-of-reqs] unprocessed-requests]
                   (>! *channel* next-req)
                   (recur rest-of-reqs))))
      (go (try (let [req (<! *channel*)]
                 (display-response req))
            (catch java.lang.Exception e (println (.getMessage e)))))
      (<!! *control-channel*)
      (close! *channel*)
      (close! *control-channel*)
      (println "Done: " (.toString(java.util.Date.))))))


