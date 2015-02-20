(ns httpprobe.timer)

(def ^:dynamic *start-time* (System/nanoTime))

(defn ms
  "Returns the string representing the time in ms from
  the *start-time*"
  []
  (str (/ (- (System/nanoTime) *start-time*) 1000000) "ms"))
