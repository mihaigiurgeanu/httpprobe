(defproject httpprobe "0.1.2"
  :description "Sends many http GET request"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [http-kit "2.1.16"]
                 [org.clojure/math.combinatorics "0.0.8"]
                 [enlive "1.1.5"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]]
  :main ^:skip-aot httpprobe.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
