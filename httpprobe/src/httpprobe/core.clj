(ns httpprobe.core
  (:use [httpprobe.probes :only [send-probes]]
        [clojure.java.io :only [reader]])
  (:gen-class))

(defn -main
  [& args]
  (println "Start:" (.toString (java.util.Date.)))
  (with-open [config-file (java.io.PushbackReader. (reader "httpprobe.conf"))]
    (let [{:keys [hosts-file paths batch-size]} (read config-file)]
      (with-open [rdr (clojure.java.io/reader hosts-file)]
        (send-probes (line-seq rdr) paths batch-size)))))