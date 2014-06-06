(ns cider.nrepl.middleware.stacktrace-test
  (:use cider.nrepl.middleware.stacktrace
        clojure.test))

;; # Utils

(defn causes
  [form]
  (analyze-causes
   (try (eval form)
        (catch Exception e
          e))))

(defn stack-frames
  [form]
  (analyze-stacktrace
   (try (eval form)
        (catch Exception e
          e))))


;; ## Test fixtures

(def form1 '(throw (ex-info "oops" {:x 1} (ex-info "cause" {:y 2}))))
(def form2 '(do (defn oops [] (+ 1 "2"))
                (oops)))

(def frames1 (stack-frames form1))
(def frames2 (stack-frames form2))
(def causes1 (causes form1))
(def causes2 (causes form2))


;; ## Tests

(deftest test-stacktrace-frames
  (testing "File types"
    ;; Should be clj and java only.
    (let [ts1 (group-by :type frames1)
          ts2 (group-by :type frames2)]
      (is (= #{:clj :java} (set (keys ts1))))
      (is (= #{:clj :java} (set (keys ts2))))))
  (testing "Clojure ns, fn, and var"
    ;; All Clojure frames should have non-nil :ns :fn and :var attributes.
    (is (every? #(every? identity ((juxt :ns :fn :var) %))
                (filter #(= :clj (:type %)) frames1)))
    (is (every? #(every? identity ((juxt :ns :fn :var) %))
                (filter #(= :clj (:type %)) frames2))))
  (testing "Clojure name demunging"
    ;; Clojure fn names should be free of munging characters.
    (is (not-any? #(re-find #"[_$]|(--\d+)" (:fn %))
                  (filter :fn frames1)))
    (is (not-any? #(re-find #"[_$]|(--\d+)" (:fn %))
                  (filter :fn frames2)))))

(deftest test-stacktrace-frame-flags
  (testing "Flags"
    (testing "for file type"
      ;; Every frame should have its file type added as a flag.
      (is (every? #(contains? (:flags %) (:type %)) frames1))
      (is (every? #(contains? (:flags %) (:type %)) frames2)))
    (testing "for REPL frames"
      ;; Frames defined in the test should be flagged as from the REPL.
      ;; Here, this is only eval call.
      (is (= 1 (count (filter (comp :repl :flags) frames1))))
      (is (= 1 (count (filter (comp :repl :flags) frames2)))))
    (testing "for tooling"
      ;; Tooling frames are in classes named with 'clojure' or 'nrepl',
      ;; or are java thread runners.
      (is (every? #(re-find #"(clojure|nrepl|run)" (:name %))
                  (filter (comp :tooling :flags) frames1)))
      (is (every? #(re-find #"(clojure|nrepl|run)" (:name %))
                  (filter (comp :tooling :flags) frames2))))
    (testing "for duplicate frames"
      ;; Index frames. For all frames flagged as :dup, the frame above it in
      ;; the stack (index i - 1) should be substantially the same source info.
      (let [ixd1 (zipmap (iterate inc 0) frames1)
            ixd2 (zipmap (iterate inc 0) frames2)
            dup? #(or (= (:name %1) (:name %2))
                      (and (= (:file %1) (:file %2))
                           (= (:line %1) (:line %2))))]
        (every? (fn [[i v]] (dup? v (get ixd1 (dec i))))
                (filter (comp :dup :flags val) ixd1))
        (every? (fn [[i v]] (dup? v (get ixd2 (dec i))))
                (filter (comp :dup :flags val) ixd2))))))

(deftest test-exception-causes
  (testing "Exception cause unrolling"
    (is (= 2 (count causes1)))
    (is (= 1 (count causes2))))
  (testing "Exception data"
    ;; If ex-data is present, the cause should have a :data attribute.
    (when (find-var 'clojure.core/ex-data)
      (is (:data (first causes1)))
      (is (not (:data (first causes2)))))))
