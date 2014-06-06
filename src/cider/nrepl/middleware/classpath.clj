(ns cider.nrepl.middleware.classpath
  (:use [clojure.tools.nrepl.middleware :only [set-descriptor!]]
        [clojure.tools.nrepl.misc :only [response-for]])
  (:require [clojure.java.classpath :as cp]
            [clojure.tools.nrepl.transport :as transport]))

(defn classpath []
  (map str (cp/classpath)))

(defn classpath-reply
  [{:keys [transport] :as msg}]
  (transport/send transport (response-for msg :value (classpath)))
  (transport/send transport (response-for msg :status :done)))

(defn wrap-classpath
  "Middleware that provides the java classpath."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "classpath" op)
      (classpath-reply msg)
      (handler msg))))

(set-descriptor!
  #'wrap-classpath
  {:handles
   {"classpath"
    {:doc "Return a list of entries in the java classpath"
     :returns {"status" "done"}}}})
