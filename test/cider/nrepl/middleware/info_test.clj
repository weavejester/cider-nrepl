(ns cider.nrepl.middleware.info-test
  (:use clojure.test)
  (:require [clojure.java.io :as io]
            [clojure.repl :as repl]
            [cider.nrepl.middleware.info :as info]))

(defn file
  [x]
  (:file (info/file-info x)))

(defn relative
  [x]
  (:resource (info/file-info x)))

(deftest test-resource-path
  (is (= (class (file (subs (str (clojure.java.io/resource "clojure/core.clj")) 4)))
         java.net.URL))
  (is (= (class (file "clojure/core.clj"))
         java.net.URL))
  (is (= (class (file "clojure-1.5.1.jar:clojure/core.clj"))
         java.net.URL))
  (is (= (class (file "test/cider/nrepl/middleware/info_test.clj"))
         java.io.File))
  (is (relative "clojure/core.clj"))
  (is (nil? (relative "notclojure/core.clj"))))

(deftest test-info
  (is (info/info-clj 'cider.nrepl.middleware.info 'io))

  (is (info/info-clj 'cider.nrepl.middleware.info 'info-clj))

  (is (info/info-clj 'cider.nrepl.middleware.info 'java.lang.Class))
  (is (info/info-clj 'cider.nrepl.middleware.info 'Class/forName))
  (is (info/info-clj 'cider.nrepl.middleware.info '.toString))

  ;; special forms are marked as such and nothing else is (for all syms in ns)
  (let [ns 'cider.nrepl.middleware.info
        spec-forms (into '#{letfn fn let loop} (keys @#'repl/special-doc-map))
        infos (->> (into spec-forms (keys (ns-map ns)))
                   (map (partial info/info-clj ns)))]
    (is (= spec-forms (->> (-> (group-by :special-form infos)
                               (get true))
                           (map :name)
                           (set)))))

  (is (info/info-java 'clojure.lang.Atom 'swap))

  (is (re-find #"^(http|file|jar|zip):" ; resolved either locally or online
               (-> (info/info-java 'java.lang.Object 'toString)
                   (info/format-response)
                   (get "javadoc"))))

  (is (info/format-response (info/info-clj 'cider.nrepl.middleware.info 'clojure.core)))

  (is (-> (info/info-clj 'cider.nrepl.middleware.info 'clojure.core)
          (dissoc :file)
          (info/format-response)))

  (is (info/format-response (info/info-clj 'cider.nrepl.middleware.info 'clojure.core/+)))
  ;; used to crash, sym is parsed as a class name
  (is (nil? (info/format-response (info/info-clj 'cider.nrepl.middleware.info 'notincanter.core))))
  ;; unfound nses should fall through
  (is (nil? (info/format-response (info/info-clj 'cider.nrepl.middleware.nonexistent-namespace 'a-var))))

  ;; handle zero-lenth input
  (is (nil? (info/info {:ns (ns-name *ns*) :symbol ""})))
  (is (nil? (info/info {:ns "" :symbol ""})))
  )

(deftest test-response
  (is (= (dissoc (info/format-response (info/info-clj 'cider.nrepl.middleware.info 'assoc)) "file" "column")
         '{"ns" "clojure.core",
           "name" "assoc",
           "arglists-str" "([map key val] [map key val & kvs])",
           "added" "1.0",
           "static" "true",
           "doc" "assoc[iate]. When applied to a map, returns a new map of the\n    same (hashed/sorted) type, that contains the mapping of key(s) to\n    val(s). When applied to a vector, returns a new vector that\n    contains val at index. Note - index must be <= (count vector).",
           "line" 177,
           "resource" "clojure/core.clj"
           })))
