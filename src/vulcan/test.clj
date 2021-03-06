(ns vulcan.test
  (:refer-clojure :exclude [ns])
  (:require
   [clojure.string :as str]
   [clojure.tools.namespace.find :as find]
   [clojure.java.io :as io]
   [clojure.test :as test]
   [vulcan.util :as u]
   [vulcan.commands :refer [defcommand] :as c]))

;; from cognitect's test-runner
;; vulvan.test simplifies the filters and proper exit code for use outside of the REPL

(defn fail? [{:keys [test fail error]}]
  (or (nil? test)
      (zero? test)
      (pos? fail)
      (pos? error)))

(defn ns-filter [{:keys [namespace]}]
  (if namespace
    #(= namespace %)
    (constantly true)))

(defn var-filter
  [{:keys [var include exclude]}]
  (let [test-specific (if var
                        (set (map #(or (resolve %)
                                       (throw (ex-info (str "Could not resolve var: " %)
                                                       {:symbol %})))))
                        (constantly true))
        test-inclusion (if include
                         #((apply some-fn include) (meta %))
                        (constantly true))
        test-exclusion (if exclude
                         #((complement (apply some-fn exclude)) (meta %))
                         (constantly true))]
    #(and (test-specific %)
          (test-inclusion %)
          (test-exclusion %))))

(defn filter-vars!
  [nses filter-fn]
  (doseq [ns nses]
    (doseq [[name var] (ns-publics ns)]
      (when (:test (meta var))
        (when (not (filter-fn var))
          (alter-meta! var #(-> %
                                (assoc ::test (:test %))
                                (dissoc :test))))))))

(defn restore-vars!
  [nses]
  (doseq [ns nses]
    (doseq [[name var] (ns-publics ns)]
      (when (::test (meta var))
        (alter-meta! var #(-> %
                              (assoc :test (::test %))
                              (dissoc ::test)))))))
(defn do-test
  [options]
  (let [dirs (or (:dir options)
                 #{"test"})
        nses (->> dirs
                  (map io/file)
                  (mapcat find/find-namespaces-in-dir))
        nses (filter (ns-filter options) nses)]
    (println (format "\nRunning tests in %s" dirs))
    (dorun (map require nses))
    (try
      (filter-vars! nses (var-filter options))
      (apply test/run-tests nses)
      (finally
        (restore-vars! nses)))))

(def selectors
  #{:api :scenario :fixme :integration-fixme
    :integration :secure :concurrent :unit})

(defn run-test [selector]
  (try
    (let [opts {:include (when selector #{selector})
                :exclude (disj selectors selector)}
          result (do-test (u/remove-nil-entries opts))]
      (println result)
      (if (fail? result)
        (System/exit 1)
        (System/exit 0)))
    (catch Exception e
      (println e)
      (System/exit 1))
    (finally
      (shutdown-agents))))
