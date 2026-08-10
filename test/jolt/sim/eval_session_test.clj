(ns jolt.sim.eval-session-test
  (:require [clojure.core.protocols :as protocols]
            [clojure.datafy :as datafy]
            [clojure.test :refer [deftest is]]
            [jolt.example.outbox]
            [jolt.sim.eval-session :as eval-session]
            [jolt.sim.eval-stream :as eval-stream]))

(defn- caught-data [f]
  (try (f) nil (catch :default error (ex-data error))))

(defn- terminal [envelope]
  (peek (:events envelope)))

(deftest real-engine-persists-namespace-history-streams-and-errors
  (let [s (eval-session/start)]
    (is (= 10 (:val (terminal (eval-session/evaluate! s {:form "10"})))))
    (is (= 20 (:val (terminal (eval-session/evaluate! s {:form "20"})))))
    (is (= 30 (:val (terminal (eval-session/evaluate! s {:form "30"})))))
    (is (= [30 20 10]
           (:val (terminal
                  (eval-session/evaluate! s {:form "[*1 *2 *3]"})))))
    (eval-session/evaluate! s {:form "(def answer 42)"})
    (is (= 42
           (:val (terminal
                  (eval-session/evaluate! s {:form "answer"})))))
    (is (= "user" (:namespace (eval-session/snapshot s))))
    (let [changed
          (eval-session/evaluate!
           s {:form "(ns jolt.sim.eval-session-ns-target)"})]
      (is (= "jolt.sim.eval-session-ns-target" (:ns (terminal changed))))
      (is (= "jolt.sim.eval-session-ns-target"
             (:namespace (eval-session/snapshot s)))))
    (eval-session/evaluate! s {:form "(def namespaced-answer 43)"})
    (is (= 43
           (:val (terminal
                  (eval-session/evaluate! s {:form "namespaced-answer"})))))
    (let [changed
          (eval-session/evaluate!
           s {:form "(in-ns 'jolt.sim.eval-session-in-ns-target)"})]
      (is (= "jolt.sim.eval-session-in-ns-target" (:ns (terminal changed))))
      (is (= "jolt.sim.eval-session-in-ns-target"
             (:namespace (eval-session/snapshot s)))))
    (eval-session/evaluate! s {:form "(def in-ns-answer 44)"})
    (is (= 44
           (:val (terminal
                  (eval-session/evaluate! s {:form "in-ns-answer"})))))
    (let [output
          (eval-session/evaluate!
           s {:form (str "(do (print \"real-out\") "
                              "(binding [*out* *err*] (print \"real-err\")) "
                              ":done)")})]
      (is (= [:out :err :ret] (mapv :tag (:events output))))
      (is (= ["real-out" "real-err"]
             (mapv :val (butlast (:events output))))))
    (let [failed
          (eval-session/evaluate!
           s {:form "(throw (ex-info \"session-boom\" {:session true}))"})]
      (is (= true (:exception (terminal failed))))
      (is (= {:session true} (get-in (terminal failed) [:val :data]))))
    (is (= [true {:session true}]
           (:val (terminal
                  (eval-session/evaluate!
                   s {:form "[(some? *e) (ex-data *e)]"})))))
    (eval-session/close! s)))

(deftest ordinary-outbox-code-runs-unchanged-through-the-session
  (let [s (eval-session/start)]
    (eval-session/evaluate!
     s {:form "(require '[jolt.example.outbox :as outbox] :reload)"})
    (eval-session/evaluate!
     s {:form "(def app-state (outbox/initial-state))"})
    (eval-session/evaluate!
     s {:form (str "(def transition "
                   "(outbox/apply-command "
                   "app-state "
                   "{:request-id \"ripple-1\" "
                   ":entity-id \"account-1\" "
                   ":payload [0 127 128 255]}))")})
    (let [result
          (:val (terminal
                 (eval-session/evaluate! s {:form "(:result transition)"})))
          history
          (:val (terminal
                 (eval-session/evaluate! s {:form "*1"})))]
      (is (= {:status :committed
              :request-id "ripple-1"
              :entity-id "account-1"
              :version 1
              :outbox-id 1}
             result))
      (is (= result history)))
    (eval-session/close! s)))

(deftest accepted-request-delegates-exactly-once-with-session-policy
  (let [calls (atom [])
        s (eval-session/start {:allow-unresolved-vars? true
                               :retained-evaluations 2})]
    (with-redefs [eval-stream/evaluate!
                  (fn [request emit!]
                    (swap! calls conj request)
                    (let [event {:tag :ret :val :ok :ns (:ns request)
                                 :ms 0 :form (:form request)}]
                      (emit! event)
                      event))]
      (let [result (eval-session/evaluate! s {:form "unresolved-name"})]
        (is (= [{:form "unresolved-name"
                 :ns "user"
                 :history :thread
                 :allow-unresolved-vars? true}]
               @calls))
        (is (= :jolt.sim.kind/evaluation (:kind result)))
        (is (= 0 (:sequence result)))
        (is (= [:ret] (mapv :tag (:events result))))))))

(deftest retention-keeps-whole-monotonic-evaluations
  (let [s (eval-session/start {:retained-evaluations 2})]
    (with-redefs [eval-stream/evaluate!
                  (fn [request emit!]
                    (emit! {:tag :out :val (str "out-" (:form request))})
                    (let [ret {:tag :ret :val (:form request) :ns "user"
                               :ms 0 :form (:form request)}]
                      (emit! ret)
                      ret))]
      (doseq [form ["a" "b" "c"]]
        (eval-session/evaluate! s {:form form}))
      (let [retained (eval-session/recent s)]
        (is (= [1 2] (mapv :sequence retained)))
        (is (= ["b" "c"] (mapv #(get-in % [:request :form]) retained)))
        (is (= [[:out :ret] [:out :ret]]
               (mapv #(mapv :tag (:events %)) retained)))
        (is (= {:from 1 :through 2 :count 2}
               (:evaluations (eval-session/snapshot s))))))))

(deftest datafy-is-cheap-and-nav-returns-the-captured-retained-prefix
  (let [s (eval-session/start {:retained-evaluations 3})]
    (doseq [form ["1" "2"]]
      (eval-session/evaluate! s {:form form}))
    (let [summary (datafy/datafy s)
          original (::datafy/obj (meta summary))
          token (:evaluations summary)]
      (is (= (eval-session/snapshot s) summary))
      (is (identical? s original))
      (eval-session/evaluate! s {:form "3"})
      (is (= [0 1]
             (mapv :sequence
                   (datafy/nav original :evaluations token))))
      (is (= :unchanged (protocols/nav s :other :unchanged))))))

(deftest navigation-fails-closed-after-retention-evicts-the-capture
  (let [s (eval-session/start {:retained-evaluations 1})]
    (eval-session/evaluate! s {:form "1"})
    (let [token (:evaluations (eval-session/snapshot s))]
      (eval-session/evaluate! s {:form "2"})
      (is (= :stale-navigation
             (:reason
              (caught-data #(protocols/nav s :evaluations token)))))))
  (let [s (eval-session/start)]
    (doseq [token [{:from nil :through nil :count 1}
                   {:from 0 :through 2 :count 2}
                   {:from 0 :through 0 :count 1 :extra true}]]
      (is (= :invalid-navigation
             (:reason
              (caught-data #(protocols/nav s :evaluations token))))))))

(deftest concurrent-evaluations-serialize-through-one-session-lock
  (let [entered (promise)
        release (promise)
        second-attempted (promise)
        calls (atom [])
        s (eval-session/start)]
    (with-redefs [eval-stream/evaluate!
                  (fn [request emit!]
                    (swap! calls conj (:form request))
                    (when (= "first" (:form request))
                      (deliver entered true)
                      @release)
                    (let [ret {:tag :ret :val (:form request) :ns "user"
                               :ms 0 :form (:form request)}]
                      (emit! ret)
                      ret))]
      (let [first-result
            (future (eval-session/evaluate! s {:form "first"}))]
        (try
          (is (= true (deref entered 1000 ::timeout)))
          (let [second-result
                (future
                  (deliver second-attempted true)
                  (eval-session/evaluate! s {:form "second"}))]
            (is (= true (deref second-attempted 1000 ::timeout)))
            (is (= ::blocked (deref second-result 50 ::blocked)))
            (is (= ["first"] @calls))
            (deliver release true)
            (is (not= ::timeout (deref first-result 1000 ::timeout)))
            (is (not= ::timeout (deref second-result 1000 ::timeout)))
            (is (= ["first" "second"] @calls))
            (is (= [0 1] (mapv :sequence (eval-session/recent s)))))
          (finally
            (deliver release true)))))))

(deftest tap-publishes-once-after-commit-and-never-controls-evaluation
  (let [s (eval-session/start)
        observed-state (atom nil)
        tapped (atom [])]
    (with-redefs [clojure.core/tap>
                  (fn [value]
                    (reset! observed-state (eval-session/snapshot s))
                    (swap! tapped conj value)
                    (throw (ex-info "observational tap failed" {})))]
      (let [result (eval-session/evaluate! s {:form "42"})]
        (is (= 42 (:val (terminal result))))
        (is (= [result] @tapped))
        (is (= 1 (:next-sequence @observed-state)))
        (is (= {:from 0 :through 0 :count 1}
               (:evaluations @observed-state)))
        (is (= [result] (eval-session/recent s)))))))

(deftest close-is-idempotent-and-post-close-rejects-before-delegation
  (let [calls (atom 0)
        s (eval-session/start)]
    (eval-session/evaluate! s {:form "1"})
    (let [first-close (eval-session/close! s)
          second-close (eval-session/close! s)]
      (is (= first-close second-close))
      (is (= :closed (:status first-close)))
      (is (= 1 (count (eval-session/recent s)))))
    (with-redefs [eval-stream/evaluate!
                  (fn [_ _] (swap! calls inc))]
      (is (= :closed
             (:reason
              (caught-data
               #(eval-session/evaluate! s {:form "2"})))))
      (is (zero? @calls)))))

(deftest malformed-configs-and-requests-are-bounded-and-fail-before-eval
  (doseq [config [nil
                  {:unknown true}
                  {:allow-unresolved-vars? nil}
                  {:retained-evaluations 0}
                  {:retained-evaluations 4097}]]
    (is (= :jolt.sim.eval-session/rejected
           (:type (caught-data #(eval-session/start config))))))
  (let [calls (atom 0)
        s (eval-session/start)]
    (with-redefs [eval-stream/evaluate! (fn [_ _] (swap! calls inc))]
      (doseq [request [nil
                       {}
                       {:form 1}
                       {:form "1" :ns "user"}]]
        (is (= :jolt.sim.eval-session/rejected
               (:type
                (caught-data #(eval-session/evaluate! s request)))))
        (is (zero? @calls))))))

(deftest malformed-input-errors-do-not-retain-large-caller-values
  (let [large (vec (range 1000))
        config-data (caught-data
                     #(eval-session/start
                       {:retained-evaluations large}))
        s (eval-session/start)
        request-data (caught-data
                      #(eval-session/evaluate! s
                        {:form "1" :large large}))
        nav-data (caught-data
                  #(protocols/nav s :evaluations
                                  {:from 0 :through 0
                                   :count 1 :large large}))]
    (doseq [data [config-data request-data nav-data]]
      (is (= :jolt.sim.eval-session/rejected (:type data)))
      (is (not-any? #(= large %) (vals data))))))

(deftest session-capability-does-not-expose-state-or-generated-constructors
  (let [s (eval-session/start)
        publics (set (keys (ns-publics 'jolt.sim.eval-session)))]
    (is (not (map? s)))
    (is (nil? (get s :state)))
    (is (not (contains? publics '->EvalSession)))
    (is (not (contains? publics 'map->EvalSession)))
    (is (= :invalid-operation
           (:reason (caught-data #(s :forged nil)))))))
