(ns httpprobe.parser
  (:require [net.cgrand.enlive-html :as html]))

(defn parse-html [html-source]
  (-> html-source java.io.StringReader. html/html-resource))

(defn extract-title [body]
  (try
    (when-let [title (first (html/select (parse-html body) [:title]))]
      (html/text title))
    (catch java.lang.Exception e (.getMessage e))))

