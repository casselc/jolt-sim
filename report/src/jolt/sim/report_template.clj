(ns jolt.sim.report-template
  "Compile-time loading for the maintainable static-report template."
  (:require [clojure.java.io :as io]))

(defmacro embedded-html
  "Expands to the complete report template as a string literal.

  The resource is required only while the consuming namespace is analyzed.
  This deliberately avoids relying on transitive `:jolt/build :embed`
  metadata, which Jolt correctly takes only from the standalone build root."
  []
  (let [resource (io/resource "jolt/sim/report.html")]
    (when-not resource
      (throw
       (ex-info
        "Static report template is missing during analysis"
        {:type ::missing-template
         :resource "jolt/sim/report.html"})))
    (slurp resource)))
