(ns cider.nrepl.middleware.info
  (:use [clojure.tools.nrepl.middleware :only [set-descriptor!]]
        [clojure.tools.nrepl.misc :only [response-for]])
  (:require [clojure.string :as s]
            [clojure.java.io :as io]
            [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.java :as java]
            [cider.nrepl.middleware.util.misc :as u]
            [clojure.repl :as repl]
            [cljs-tooling.info :as cljs-info]
            [clojure.tools.nrepl.transport :as transport]))

(defn maybe-protocol
  [info]
  (if-let [prot-meta (meta (:protocol info))]
    (merge info {:file (:file prot-meta)
                 :line (:line prot-meta)})
    info))

(def var-meta-whitelist
  [:ns :name :doc :file :arglists :macro :protocol :line :column :static :added :deprecated :resource])

(defn- map-seq [x]
  (if (seq x)
    x
    nil))

(defn var-meta
  [v]
  (-> v meta maybe-protocol (select-keys var-meta-whitelist) map-seq))

(defn ns-meta
  [ns]
  (merge
   (meta ns)
   {:ns ns
    :file (-> (ns-publics ns)
              first
              second
              var-meta
              :file)
    :line 1}))

(defn resolve-var
  [ns sym]
  (if-let [ns (find-ns ns)]
    (try (ns-resolve ns sym)
         ;; Impl might try to resolve it as a class, which may fail
         (catch ClassNotFoundException _
           nil)
         ;; TODO: Preserve and display the exception info
         (catch Exception _
           nil))))

(defn resolve-aliases
  [ns]
  (if-let [ns (find-ns ns)]
    (ns-aliases ns)))

;; This reproduces the behavior of the canonical `clojure.repl/doc` using its
;; internals, but returns the metadata rather than just printing. Oddly, the
;; only place in the Clojure API that special form metadata is available *as
;; data* is a private function. Lame. Just call through the var.
(defn resolve-special
  "Return info for the symbol if it's a special form, or nil otherwise. Adds
  `:url` unless that value is explicitly set to `nil` -- the same behavior
  used by `clojure.repl/doc`."
  [sym]
  (try
    (let [sym (get '{& fn, catch try, finally try} sym sym)
          v   (meta (ns-resolve (find-ns 'clojure.core) sym))]
      (when-let [m (cond (special-symbol? sym) (#'repl/special-doc sym)
                         (:special-form v) v)]
        (assoc m
          :url (if (contains? m :url)
                 (when (:url m)
                   (str "http://clojure.org/" (:url m)))
                 (str "http://clojure.org/special_forms#" (:name m))))))
    (catch Exception _)))

(defn info-clj
  [ns sym]
  (cond
   ;; sym is an alias for another ns
   (get (resolve-aliases ns) sym) (ns-meta (get (resolve-aliases ns) sym))
   ;; it's simply a full ns
   (find-ns sym) (ns-meta (find-ns sym))
   ;; it's a special (special-symbol? or :special-form)
   (resolve-special sym) (resolve-special sym)
   ;; it's a var
   (var-meta (resolve-var ns sym)) (var-meta (resolve-var ns sym))
   ;; it's a Java class/member symbol...or nil
   :else (java/resolve-symbol ns sym)))

(defn info-cljs
  [env symbol ns]
  (let [x (cljs-info/info env symbol ns)]
    (select-keys x [:file :line :ns :doc :column :name :arglists])))

(defn info-java
  [class member]
  (java/member-info class member))

(defn info
  [{:keys [ns symbol class member] :as msg}]
  (let [[ns symbol] (map u/as-sym [ns symbol])]
    (if-let [cljs-env (cljs/grab-cljs-env msg)]
      (info-cljs cljs-env symbol ns)
      (if ns
        (info-clj ns symbol)
        (info-java class member)))))

(defn resource-path
  "If it's a resource, return a tuple of the relative path and the full resource path."
  [x]
  (or (if-let [full (io/resource x)]
        [x full])
      (if-let [[_ relative] (re-find #".*jar!/(.*)" x)]
        (if-let [full (io/resource relative)]
          [relative full]))
      ;; handles load-file on jar resources from a cider buffer
      (if-let [[_ relative] (re-find #".*jar:(.*)" x)]
        (if-let [full (io/resource relative)]
          [relative full]))))

(defn file-path
  [x]
  (if (seq x)
    (let [f (io/file x)]
      (if (.exists f)
        f))))

(defn file-info
  [path]
  (let [[resource-relative resource-full] (resource-path path)]
    (merge {:file (or (file-path path) resource-full path)}
           ;; Classpath-relative path if possible
           (if resource-relative
             {:resource resource-relative}))))

(defn javadoc-info
  "Resolve a relative javadoc path to a URL and return as a map. Prefer javadoc
  resources on the classpath; then use online javadoc content for core API
  classes. If no source is available, return the relative path as is."
  [path]
  {:javadoc
   (or (io/resource path)
       (when (re-find #"^(java|javax|org.omg|org.w3c.dom|org.xml.sax)/" path)
         (format "http://docs.oracle.com/javase/%s/docs/api/%s"
                 u/java-api-version path))
       path)})

(declare format-response)

(defn format-nested
  "Apply response formatting to nested `:candidates` info for Java members."
  [info]
  (if-let [candidates (:candidates info)]
    (assoc info :candidates
           (into {} (for [[k v] candidates]
                      [k (format-response v)])))
    info))

(defn blacklist
  "Remove anything that might contain arbitrary EDN, metadata can hold anything"
  [info]
  (let [blacklisted #{:arglists :forms}]
    (apply dissoc info blacklisted)))

(defn format-response
  [info]
  (when info
    (-> info
        (merge (when-let [ns (:ns info)]
                 (:ns (str ns)))
               (when-let [args (:arglists info)]
                 {:arglists-str (pr-str args)})
               (when-let [forms (:forms info)]
                 {:forms-str (->> (map #(str "  " (pr-str %)) forms)
                                  (s/join \newline))})
               (when-let [file (:file info)]
                 (file-info file))
               (when-let [path (:javadoc info)]
                 (javadoc-info path)))
        format-nested
        blacklist
        u/transform-value)))

(defn info-reply
  [{:keys [transport] :as msg}]
  (try
    (transport/send
     transport (response-for msg :value (format-response (info msg))))
    (catch Exception e
      (transport/send
       transport (response-for msg :exception (.getMessage e)))))
  (transport/send transport (response-for msg :status :done)))

(defn wrap-info
  "Middleware that looks up info for a symbol within the context of a particular namespace."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "info" op)
      (info-reply msg)
      (handler msg))))

(set-descriptor!
 #'wrap-info
 (cljs/maybe-piggieback
  {:handles
   {"info"
    {:doc "Return a map of information about the specified symbol."
     :requires {"symbol" "The symbol to lookup"
                "ns" "The current namespace"}
     :returns {"status" "done"}}}}))
