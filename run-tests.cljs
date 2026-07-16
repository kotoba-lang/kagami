#!/usr/bin/env nbb
;; nbb --classpath src:test run-tests.cljs
(ns run-tests
  (:require [cljs.test :refer [run-tests]]
            [fleet.core-test]))

(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (when-not (cljs.test/successful? m)
    (js/process.exit 1)))

(run-tests 'fleet.core-test)
