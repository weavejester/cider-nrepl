(ns cider-nrepl.plugin
  (:require [clojure.java.io :as io]))

;; Keep in sync with VERSION-FORM in project.clj
(defn- version
  []
  (let [v (-> (io/resource "META-INF/leiningen/weavejester/cider-nrepl/project.clj")
              slurp
              read-string
              (nth 2))]
    (assert (string? v)
        (str "Something went wrong, version is not a string: "
             v))
    v))

(defn middleware
  [project]
  (-> project
      (update-in [:dependencies]
                 (fnil into [])
                 [['weavejester/cider-nrepl (version)]])
      (update-in [:repl-options :nrepl-middleware]
                 (fnil into [])
                 '[cider.nrepl.middleware.classpath/wrap-classpath
                   cider.nrepl.middleware.complete/wrap-complete
                   cider.nrepl.middleware.info/wrap-info
                   cider.nrepl.middleware.inspect/wrap-inspect
                   cider.nrepl.middleware.stacktrace/wrap-stacktrace
                   cider.nrepl.middleware.trace/wrap-trace])))
