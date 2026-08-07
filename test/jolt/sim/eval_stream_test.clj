(ns jolt.sim.eval-stream-test
  "Discriminating clojure.test coverage for jolt.sim.eval-stream/evaluate!.

  These tests stub jolt.eval/evaluate and jolt.eval/record-history! with
  with-redefs, so they exercise the adapter's validation, emission order,
  terminal :ret construction, history forwarding, empty-stream omission, and
  emitter-failure propagation without depending on a live jolt.eval engine.
  They require the jolt.eval namespace merged into the fork at d040d502; CI
  pins that exact core commit. An upstream release without that namespace
  cannot load this suite."
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.eval :as eval]
            [jolt.sim.eval-stream :as stream]))

(defn- run-eval
  "Runs stream/evaluate! with a stubbed jolt.eval/evaluate returning
  evaluate-result and a stubbed jolt.eval/record-history! that records each
  [mode result] pair. Returns {:ret .. :events .. :history ..}."
  [request evaluate-result]
  (let [events (atom [])
        history (atom [])
        evaluations (atom [])
        emit! (fn [event] (swap! events conj event))]
    (with-redefs [eval/evaluate (fn [options]
                                  (swap! evaluations conj options)
                                  evaluate-result)
                  eval/record-history! (fn [mode result]
                                         (swap! history conj [mode result]))]
      (let [ret (stream/evaluate! request emit!)]
        {:ret ret
         :events @events
         :history @history
         :evaluations @evaluations}))))

(deftest success-emits-out-then-ret-and-returns-ret
  (testing "raw success value, exact metadata, and UI-neutral nil history"
    (let [result {:status :ok
                  :value 42
                  :exception nil
                  :backtrace nil
                  :form "(+ 40 2)"
                  :ns "user"
                  :ms 7
                  :out "hello\n"
                  :err nil}
          {:keys [ret events history evaluations]}
          (run-eval {:form "(+ 40 2)"} result)]
      (is (= {:tag :ret
              :val 42
              :ns "user"
              :ms 7
              :form "(+ 40 2)"}
             ret))
      (is (= [{:tag :out :val "hello\n"}
              {:tag :ret :val 42 :ns "user" :ms 7 :form "(+ 40 2)"}]
             events))
      (is (= [[nil result]] history))
      (is (= [{:code "(+ 40 2)"
               :ns nil
               :allow-unresolved-vars? false
               :capture-out? true
               :capture-err? true}]
             evaluations))
      (is (not (contains? ret :exception))))))

(deftest explicit-namespace-and-thread-history-are-forwarded
  (let [result {:status :ok
                :value :ok
                :exception nil
                :backtrace nil
                :form ":ok"
                :ns "example.target"
                :ms 0
                :out nil
                :err nil}
        {:keys [history evaluations]}
        (run-eval {:form ":ok"
                   :ns "example.target"
                   :history :thread
                   :allow-unresolved-vars? true}
                  result)]
    (is (= [[:thread result]] history))
    (is (= [{:code ":ok"
             :ns "example.target"
             :allow-unresolved-vars? true
             :capture-out? true
             :capture-err? true}]
           evaluations))))

(deftest out-then-err-then-ret-order
  (testing "nonempty stdout precedes nonempty stderr precedes the terminal :ret"
    (let [result {:status :ok
                  :value 1
                  :exception nil
                  :backtrace nil
                  :form "x"
                  :ns "user"
                  :ms 1
                  :out "o"
                  :err "e"}
          {:keys [events]} (run-eval {:form "x"} result)]
      (is (= [:out :err :ret] (mapv :tag events)))
      (is (= "o" (:val (nth events 0))))
      (is (= "e" (:val (nth events 1)))))))

(deftest error-emits-structured-ret-with-exception-marker
  (testing "error :val is a structured Throwable->map, :exception true, history forwarded"
    (let [ex (ex-info "boom" {:k 1})
          result {:status :error
                  :value nil
                  :exception ex
                  :backtrace "bt"
                  :form "(throw (ex-info \"boom\" {:k 1}))"
                  :ns "user"
                  :ms 3
                  :out nil
                  :err "err\n"}
          {:keys [ret events history]} (run-eval {:form "(throw ...)" :history :root} result)]
      (is (= true (:exception ret)))
      (is (= :ret (:tag ret)))
      (is (= "boom" (:cause (:val ret))))
      (is (= {:k 1} (:data (:val ret))))
      (is (= 'clojure.lang.ExceptionInfo
             (-> ret :val :via first :type)))
      (is (= "bt" (:jolt/backtrace (:val ret))))
      (is (= "user" (:ns ret)))
      (is (= 3 (:ms ret)))
      (is (= [[:root result]] history))
      (is (= [:err :ret] (mapv :tag events))))))

(deftest empty-streams-are-omitted
  (testing "empty or nil stdout/stderr produce no :out/:err events"
    (let [result {:status :ok
                  :value 1
                  :exception nil
                  :backtrace nil
                  :form "x"
                  :ns "user"
                  :ms 1
                  :out ""
                  :err nil}
          {:keys [events]} (run-eval {:form "x"} result)]
      (is (= [:ret] (mapv :tag events))))))

(deftest validation-happens-before-evaluation
  (testing "malformed requests throw typed ex-info and never call jolt.eval"
    (let [called (atom 0)]
      (with-redefs [eval/evaluate (fn [_] (swap! called inc)
                                    {:status :ok :value 1 :form "x" :ns "user" :ms 1})
                    eval/record-history! (fn [_ _])]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rejected"
                              (stream/evaluate! {:form 42} (fn [_]))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rejected"
                              (stream/evaluate! {:form "x" :history :bogus} (fn [_]))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rejected"
                              (stream/evaluate! {:form "x" :ns 7} (fn [_]))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rejected"
                              (stream/evaluate! {:form "x" :allow-unresolved-vars? "yes"} (fn [_]))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rejected"
                              (stream/evaluate! {:form "x" :allow-unresolved-vars? nil} (fn [_]))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rejected"
                              (stream/evaluate! {:form "x"} nil)))
        (doseq [callable-but-not-emitter [:keyword {} #{} []]]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"rejected"
               (stream/evaluate! {:form "x"} callable-but-not-emitter))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rejected"
                              (stream/evaluate! "not-a-map" (fn [_]))))
        (is (zero? @called))))))

(deftest invalid-request-errors-are-typed-and-bounded
  (let [large-value (vec (range 1000))
        data (try
               (stream/evaluate! {:form "x" :history large-value} (fn [_]))
               nil
               (catch :default error (ex-data error)))]
    (is (= :jolt.sim.eval-stream/invalid-request (:type data)))
    (is (= :history-not-supported (:reason data)))
    (is (= #{:type :reason :value-class} (set (keys data))))
    (is (not-any? #(= large-value %) (vals data)))))

(deftest real-engine-evaluation-round-trip
  (testing "the default nil history works without REPL thread bindings"
    (let [events (atom [])
          ret (stream/evaluate! {:form "(* 6 7)"}
                                #(swap! events conj %))]
      (is (= 42 (:val ret)))
      (is (= [:ret] (mapv :tag @events)))))
  (testing "the adapter and real jolt.eval agree on values, streams, and history"
    (binding [*1 :before *2 :older *3 :oldest *e nil]
      (let [events (atom [])
            form (str "(do (print \"real-out\") "
                      "(binding [*out* *err*] (print \"real-err\")) "
                      "{:answer 42})")
            ret (stream/evaluate! {:form form :history :thread}
                                  #(swap! events conj %))]
        (is (= [:out :err :ret] (mapv :tag @events)))
        (is (= "real-out" (:val (nth @events 0))))
        (is (= "real-err" (:val (nth @events 1))))
        (is (= {:answer 42} (:val ret)))
        (is (= form (:form ret)))
        (is (= [{:answer 42} :before :older] [*1 *2 *3])))))
  (testing "a real thrown value becomes structured data and updates *e"
    (binding [*e nil]
      (let [events (atom [])
            ret (stream/evaluate!
                 {:form "(throw (ex-info \"real-boom\" {:real true}))"
                  :history :thread}
                 #(swap! events conj %))]
        (is (= [:ret] (mapv :tag @events)))
        (is (= true (:exception ret)))
        (is (= "real-boom" (-> ret :val :cause)))
        (is (= {:real true} (-> ret :val :data)))
        (is (= {:real true} (ex-data *e)))))))

(deftest emitter-failure-propagates-without-duplicate-emission
  (testing "a throwing emit! propagates and no further event is emitted"
    (let [result {:status :ok
                  :value 1
                  :exception nil
                  :backtrace nil
                  :form "x"
                  :ns "user"
                  :ms 1
                  :out "o"
                  :err "e"}
          emitted (atom [])
          emit! (fn [event]
                  (swap! emitted conj event)
                  (when (= :out (:tag event))
                    (throw (ex-info "emitter down" {}))))]
      (with-redefs [eval/evaluate (fn [_] result)
                    eval/record-history! (fn [_ _])]
        (is (thrown? clojure.lang.ExceptionInfo
                     (stream/evaluate! {:form "x"} emit!)))
        (is (= [{:tag :out :val "o"}] @emitted))))))
