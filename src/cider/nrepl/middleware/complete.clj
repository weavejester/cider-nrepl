(ns cider.nrepl.middleware.complete
  (:use [clojure.tools.nrepl.middleware :only [set-descriptor!]]
        [clojure.tools.nrepl.misc :only [response-for]])
  (:require [clojure.string :as s]
            [clojure.tools.nrepl.transport :as transport]
            [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.misc :as u]
            [compliment.core :as jvm-complete]
            [cljs-tooling.complete :as cljs-complete]))

(defn complete
  [{:keys [symbol ns context] :as msg}]
  (let [ns (u/as-sym ns)
        prefix (str symbol)]
    (if-let [cljs-env (cljs/grab-cljs-env msg)]
      (cljs-complete/completions cljs-env prefix ns)
      (jvm-complete/completions prefix ns context))))

(defn completion-doc
  [{:keys [symbol ns] :as msg}]
  (when-not (cljs/grab-cljs-env msg)
    (jvm-complete/documentation (str symbol) (u/as-sym ns))))

(defn complete-reply
  [{:keys [transport] :as msg}]
  (let [results (complete msg)]
    (try
      (transport/send transport (response-for msg :value results))
      (catch Exception e
        (transport/send
         transport (response-for msg :exception (.getMessage e)))))
    (transport/send transport (response-for msg :status :done))))

(defn doc-reply
  [{:keys [transport] :as msg}]
  (let [results (completion-doc msg)]
    (transport/send transport (response-for msg :value results))
    (transport/send transport (response-for msg :status :done))))

(defn wrap-complete
  "Middleware that looks up possible functions for the given (partial) symbol."
  [handler]
  (fn [{:keys [op] :as msg}]
    (cond
     (= "complete" op) (complete-reply msg)
     (= "complete-doc" op) (doc-reply msg)
     :else (handler msg))))

(set-descriptor!
 #'wrap-complete
 (cljs/maybe-piggieback
  {:handles
   {"complete"
    {:doc "Return a list of symbols matching the specified (partial) symbol."
     :requires {"symbol" "The symbol to lookup"
                "ns" "The current namespace"}
     :returns {"status" "done"}}}}))
