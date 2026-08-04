(ns jolt.sim.fault-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.sim.fault :as fault]
            [jolt.sim.trace :as trace]))

(defn- caught [f]
  (try
    (f)
    ::no-throw
    (catch :default error
      error)))

(defn- caught-data [f]
  (ex-data (caught f)))

(defn- step-until [director attempts]
  (let [result
        (reduce (fn [{:keys [director] :as acc} attempt]
                  (let [next (fault/step director attempt)]
                    (-> acc
                        (assoc :director (:director next))
                        (update :evidence conj (:evidence next)))))
                {:director director :evidence []}
                attempts)]
    result))

(deftest plan-validation-is-closed-and-typed
  (testing "non-vector plan"
    (let [data (caught-data #(fault/director {:id :f/r}))]
      (is (= fault/invalid-plan (:type data)))
      (is (= :plan-must-be-vector (:reason data)))))
  (testing "non-map rule"
    (let [data (caught-data #(fault/director [:not-a-map]))]
      (is (= :rule-must-be-map (:reason data)))))
  (testing "nil and false rules never act as end-of-plan sentinels"
    (let [valid {:id :f/valid :match {} :activation {:on-match 1}
                 :outcome nil}]
      (doseq [[plan expected-index]
              [[[nil] 0]
               [[false] 0]
               [[valid nil valid] 1]
               [[valid false valid] 1]]]
        (let [data (caught-data #(fault/director plan))]
          (is (= fault/invalid-plan (:type data)))
          (is (= :rule-must-be-map (:reason data)))
          (is (= expected-index (:rule-index data)))))))
  (testing "missing rule keys"
    (let [data (caught-data #(fault/director [{:id :f/r :match {} :activation {:on-match 1}}]))]
      (is (= :missing-rule-keys (:reason data)))
      (is (= [:outcome] (:missing data)))))
  (testing "unknown rule keys"
    (let [data (caught-data
                #(fault/director [{:id :f/r :match {} :activation {:on-match 1}
                                   :outcome nil :extra :nope}]))]
      (is (= :unknown-rule-keys (:reason data)))
      (is (= [:extra] (:unknown data)))))
  (testing "multi-key error evidence has stable printable ordering"
    (let [data (caught-data
                #(fault/director [{:id :f/r :match {} :activation {:on-match 1}
                                   :outcome nil :z 1 "a" 2}]))]
      (is (= :unknown-rule-keys (:reason data)))
      (is (= ["a" :z] (:unknown data)))))
  (testing "id must be a namespaced keyword"
    (doseq [bad-id [:no-namespace "string-id" 7 nil]]
      (let [data (caught-data
                  #(fault/director [{:id bad-id :match {} :activation {:on-match 1}
                                     :outcome nil}]))]
        (is (= :invalid-id (:reason data)) (pr-str bad-id)))))
  (testing "duplicate ids"
    (let [data (caught-data
                #(fault/director [{:id :f/a :match {} :activation {:on-match 1} :outcome nil}
                                  {:id :f/a :match {} :activation {:on-match 1} :outcome nil}]))]
      (is (= :duplicate-id (:reason data)))
      (is (= :f/a (:id data)))))
  (testing "match must be a plain map"
    (let [data (caught-data
                #(fault/director [{:id :f/r :match [:not-a-map] :activation {:on-match 1}
                                   :outcome nil}]))]
      (is (= :match-must-be-map (:reason data)))))
  (testing "activation must be a closed map with positive counts"
    (let [non-map (caught-data
                   #(fault/director [{:id :f/r :match {} :activation :nope
                                      :outcome nil}]))
          missing (caught-data
                   #(fault/director [{:id :f/r :match {} :activation {:times 2}
                                      :outcome nil}]))
          unknown (caught-data
                   #(fault/director [{:id :f/r :match {} :activation {:on-match 1 :bonus 1}
                                      :outcome nil}]))
          bad-on (caught-data
                  #(fault/director [{:id :f/r :match {} :activation {:on-match 0}
                                     :outcome nil}]))
          bad-times (caught-data
                     #(fault/director [{:id :f/r :match {} :activation {:on-match 1 :times 0}
                                        :outcome nil}]))]
      (is (= :activation-must-be-map (:reason non-map)))
      (is (= :missing-activation-keys (:reason missing)))
      (is (= :unknown-activation-keys (:reason unknown)))
      (is (= :invalid-on-match (:reason bad-on)))
      (is (= :invalid-times (:reason bad-times)))))
  (testing "outcome outside the trace domain is rejected before a director"
    (let [data (caught-data
                #(fault/director [{:id :f/r :match {} :activation {:on-match 1}
                                   :outcome (fn [_] :nope)}]))]
      (is (= trace/unsupported-value (:type data)))))
  (testing "metadata anywhere in the plan is rejected by the trace domain"
    (let [plan (with-meta
                 [{:id :f/r :match {} :activation {:on-match 1}
                   :outcome nil}]
                 {:source :caller})
          data (caught-data #(fault/director plan))]
      (is (= trace/unsupported-value (:type data)))
      (is (= :metadata (:reason data))))))

(deftest empty-plan-and-no-match-produce-no-firing
  (testing "empty plan never fires and leaves counts at zero"
    (let [d0 (fault/director [])
          r (fault/step d0 {:attempt-id :a :op :foo})]
      (is (= #{:attempt-id :matches :firing} (set (keys (:evidence r)))))
      (is (= [] (get-in r [:evidence :matches])))
      (is (nil? (get-in r [:evidence :firing])))
      (is (= d0 (:director r)))
      (is (zero? (:firings (:director r))))))
  (testing "a non-matching rule records nothing"
    (let [d0 (fault/director [{:id :f/x :match {:op :foo}
                               :activation {:on-match 1} :outcome :o}])
          r (fault/step d0 {:attempt-id :a :op :bar})]
      (is (= 1 (get-in d0 [:rules 0 :times])))
      (is (= [] (get-in r [:evidence :matches])))
      (is (nil? (get-in r [:evidence :firing])))
      (is (= 0 (get-in (:director r) [:rules 0 :matches])))
      (is (zero? (:firings (:director r)))))))

(deftest matching-is-shallow-subset-and-nested-exact
  (testing "empty matcher is a catch-all"
    (let [d0 (fault/director [{:id :f/all :match {}
                               :activation {:on-match 1} :outcome :o}])
          r (fault/step d0 {:attempt-id :a :anything :here})]
      (is (= [:f/all] (mapv :rule-id (get-in r [:evidence :matches]))))))
  (testing "extra attempt keys are allowed (shallow subset)"
    (let [d0 (fault/director [{:id :f/op :match {:op :foo}
                               :activation {:on-match 1} :outcome :o}])
          r (fault/step d0 {:attempt-id :a :op :foo :extra :ignored})]
      (is (= [:f/op] (mapv :rule-id (get-in r [:evidence :matches]))))
      (is (= :f/op (get-in r [:evidence :firing :rule-id])))))
  (testing "nested map values are compared exactly, not recursively"
    (let [d0 (fault/director [{:id :f/n :match {:payload {:x 1}}
                               :activation {:on-match 1} :outcome :o}])
          r (fault/step d0 {:attempt-id :a :payload {:x 1 :y 2}})]
      (is (= [] (get-in r [:evidence :matches])))
      (is (nil? (get-in r [:evidence :firing])))))
  (testing "exact nested value matches"
    (let [d0 (fault/director [{:id :f/n :match {:payload {:x 1}}
                               :activation {:on-match 1} :outcome :o}])
          r (fault/step d0 {:attempt-id :a :payload {:x 1}})]
      (is (= :f/n (get-in r [:evidence :firing :rule-id]))))))

(deftest byte-array-matching-and-constructor-freezing
  (let [original (byte-array [1 2 3])
        d0 (fault/director [{:id :f/blob :match {:blob original}
                             :activation {:on-match 1} :outcome :o}])]
    (testing "byte-array content matches by canonical projection"
      (let [match (fault/step d0 {:attempt-id :a :blob (byte-array [1 2 3])})
            nomatch (fault/step d0 {:attempt-id :a :blob (byte-array [1 2 4])})]
        (is (= :f/blob (get-in match [:evidence :firing :rule-id])))
        (is (nil? (get-in nomatch [:evidence :firing])))))
    (testing "mutating the caller's matcher byte array after construction is ignored"
      ;; The director froze the matcher through a canonical projection; the
      ;; caller-owned array is now mutated, but matching must use the original.
      (aset original 0 99)
      (let [match (fault/step d0 {:attempt-id :a :blob (byte-array [1 2 3])})
            nomatch (fault/step d0 {:attempt-id :a :blob (byte-array [99 2 3])})]
        (is (= :f/blob (get-in match [:evidence :firing :rule-id])))
        (is (nil? (get-in nomatch [:evidence :firing])))))))

(deftest on-match-threshold-and-times-exhaustion
  (testing "on-match threshold gates activation"
    (let [d0 (fault/director [{:id :f/t :match {:op :x}
                               :activation {:on-match 2} :outcome :o}])
          r1 (fault/step d0 {:attempt-id :a :op :x})
          r2 (fault/step (:director r1) {:attempt-id :a :op :x})]
      (is (nil? (get-in r1 [:evidence :firing])))
      (is (= 1 (get-in r1 [:evidence :matches 0 :match-ordinal])))
      (is (false? (get-in r1 [:evidence :matches 0 :activated?])))
      (is (= :f/t (get-in r2 [:evidence :firing :rule-id])))
      (is (= 2 (get-in r2 [:evidence :firing :match-ordinal])))))
  (testing "times is total firings; exhaustion stops firing but not matching"
    (let [d0 (fault/director [{:id :f/t :match {:op :x}
                               :activation {:on-match 1 :times 2} :outcome :o}])
          r1 (fault/step d0 {:attempt-id :a :op :x})
          r2 (fault/step (:director r1) {:attempt-id :a :op :x})
          r3 (fault/step (:director r2) {:attempt-id :a :op :x})]
      (is (= :f/t (get-in r1 [:evidence :firing :rule-id])))
      (is (= 1 (get-in r1 [:evidence :firing :rule-firing-ordinal])))
      (is (= :f/t (get-in r2 [:evidence :firing :rule-id])))
      (is (= 2 (get-in r2 [:evidence :firing :rule-firing-ordinal])))
      (is (nil? (get-in r3 [:evidence :firing])))
      (is (= 3 (get-in r3 [:evidence :matches 0 :match-ordinal])))
      (is (false? (get-in r3 [:evidence :matches 0 :activated?])))
      (is (= 2 (get-in (:director r3) [:rules 0 :firings])))))
  (testing "omitted times defaults to exactly one total firing"
    (let [d0 (fault/director [{:id :f/default :match {:op :x}
                               :activation {:on-match 1} :outcome :o}])
          r1 (fault/step d0 {:attempt-id :a :op :x})
          r2 (fault/step (:director r1) {:attempt-id :b :op :x})]
      (is (= 1 (get-in d0 [:rules 0 :times])))
      (is (= :f/default (get-in r1 [:evidence :firing :rule-id])))
      (is (nil? (get-in r2 [:evidence :firing])))
      (is (= 2 (get-in r2 [:evidence :matches 0 :match-ordinal])))
      (is (false? (get-in r2 [:evidence :matches 0 :activated?]))))))

(deftest overlapping-rules-priority-and-eligible-loser
  (let [plan [{:id :f/first :match {:op :x} :activation {:on-match 1 :times 1} :outcome :a}
              {:id :f/second :match {:op :x} :activation {:on-match 1 :times 1} :outcome :b}]
        d0 (fault/director plan)
        r1 (fault/step d0 {:attempt-id :a :op :x})]
    (testing "both activate; first in plan order fires; loser is recorded"
      (let [matches (get-in r1 [:evidence :matches])]
        (is (= [:f/first :f/second] (mapv :rule-id matches)))
        (is (= [true true] (mapv :activated? matches)))
        (is (= [true false] (mapv :fired? matches)))
        (is (= :f/first (get-in r1 [:evidence :firing :rule-id])))
        (is (= :a (get-in r1 [:evidence :firing :outcome])))
        (is (= 1 (get-in r1 [:evidence :firing :firing-ordinal])))
        (is (= 1 (get-in r1 [:evidence :firing :rule-firing-ordinal])))
        (is (= [1 1] (mapv :matches (get-in r1 [:director :rules]))))))
    (testing "after the first exhausts, the activated loser fires next"
      (let [r2 (fault/step (:director r1) {:attempt-id :a :op :x})
            matches (get-in r2 [:evidence :matches])]
        (is (= [:f/first :f/second] (mapv :rule-id matches)))
        ;; first is now exhausted (not activated), second wins
        (is (= [false true] (mapv :activated? matches)))
        (is (= [false true] (mapv :fired? matches)))
        (is (= :f/second (get-in r2 [:evidence :firing :rule-id])))
        (is (= :b (get-in r2 [:evidence :firing :outcome])))
        (is (= 2 (get-in r2 [:evidence :firing :firing-ordinal])))
        (is (= 1 (get-in r2 [:evidence :firing :rule-firing-ordinal])))
        (is (= [2 2] (mapv :matches (get-in r2 [:director :rules]))))))))

(deftest global-and-per-rule-ordinals
  (let [plan [{:id :f/a :match {:op :x} :activation {:on-match 1 :times 2} :outcome :a}
              {:id :f/b :match {:op :y} :activation {:on-match 1 :times 2} :outcome :b}]
        d0 (fault/director plan)
        ;; a fires twice, then b fires twice; global ordinal spans both rules.
        r1 (fault/step d0 {:attempt-id :a :op :x})
        r2 (fault/step (:director r1) {:attempt-id :a :op :x})
        r3 (fault/step (:director r2) {:attempt-id :a :op :y})
        r4 (fault/step (:director r3) {:attempt-id :a :op :y})]
    (is (= [1 1] [(get-in r1 [:evidence :firing :firing-ordinal])
                  (get-in r1 [:evidence :firing :rule-firing-ordinal])]))
    (is (= [2 2] [(get-in r2 [:evidence :firing :firing-ordinal])
                  (get-in r2 [:evidence :firing :rule-firing-ordinal])]))
    (is (= [3 1] [(get-in r3 [:evidence :firing :firing-ordinal])
                  (get-in r3 [:evidence :firing :rule-firing-ordinal])]))
    (is (= [4 2] [(get-in r4 [:evidence :firing :firing-ordinal])
                  (get-in r4 [:evidence :firing :rule-firing-ordinal])]))
    (is (= 4 (:firings (:director r4))))
    (is (= 2 (get-in (:director r4) [:rules 0 :firings])))
    (is (= 2 (get-in (:director r4) [:rules 1 :firings])))))

(deftest duplicate-attempt-ids-are-processed-independently
  ;; Identical :attempt-id values are opaque and allowed; each attempt still
  ;; advances match counts and is reported with its own evidence.
  (let [d0 (fault/director [{:id :f/r :match {:op :x}
                             :activation {:on-match 2} :outcome :o}])
        r1 (fault/step d0 {:attempt-id :dup :op :x})
        r2 (fault/step (:director r1) {:attempt-id :dup :op :x})]
    (is (= :dup (get-in r1 [:evidence :attempt-id])))
    (is (= :dup (get-in r2 [:evidence :attempt-id])))
    (is (nil? (get-in r1 [:evidence :firing])))
    (is (= 1 (get-in r1 [:evidence :matches 0 :match-ordinal])))
    (is (= :f/r (get-in r2 [:evidence :firing :rule-id])))
    (is (= 2 (get-in r2 [:evidence :firing :match-ordinal])))))

(deftest byte-array-outcomes-are-freshly-restored-per-firing
  (let [source (byte-array [1 2 3])
        plan [{:id :f/blob :match {}
               :activation {:on-match 1 :times 2}
               :outcome {:data source}}]
        d0 (fault/director plan)
        ;; Construction froze the outcome before this caller mutation.
        _ (aset source 0 88)
        r1 (fault/step d0 {:attempt-id :a})
        o1 (get-in r1 [:evidence :firing :outcome])
        b1 (:data o1)]
    (is (some? b1))
    (is (= [1 2 3] (vec b1)))
    ;; Mutate the first firing before requesting the second. A later restoration
    ;; must still come from the frozen plan, not the caller-owned result.
    (aset b1 0 99)
    (let [r2 (fault/step (:director r1) {:attempt-id :a})
          b2 (get-in r2 [:evidence :firing :outcome :data])]
      (is (some? b2))
      (is (not (identical? b1 b2)))
      (is (= [99 2 3] (vec b1)))
      (is (= [1 2 3] (vec b2))))))

(deftest attempt-validation-fails-closed
  (testing "non-map attempt"
    (let [data (caught-data #(fault/step (fault/director []) [:not-a-map]))]
      (is (= fault/invalid-attempt (:type data)))
      (is (= :attempt-must-be-map (:reason data)))))
  (testing "missing attempt-id"
    (let [data (caught-data #(fault/step (fault/director []) {:op :x}))]
      (is (= :missing-attempt-id (:reason data)))))
  (testing "non-stable attempt value is rejected"
    (let [data (caught-data #(fault/step (fault/director [])
                                         {:attempt-id :a :bad (fn [_] :nope)}))]
      (is (= trace/unsupported-value (:type data))))))

(deftest malformed-director-state-fails-closed
  (let [valid
        (fault/director
         [{:id :f/r
           :match {:op :x}
           :activation {:on-match 1}
           :outcome :o}])]
    (testing "arbitrary and incomplete maps are rejected"
      (let [data (caught-data #(fault/step {} {:attempt-id :a}))]
        (is (= fault/invalid-director (:type data)))
        (is (= :missing-director-keys (:reason data)))))
    (testing "unknown top-level state keys are rejected"
      (let [data (caught-data #(fault/step (assoc valid :extra true)
                                           {:attempt-id :a}))]
        (is (= fault/invalid-director (:type data)))
        (is (= :unknown-director-keys (:reason data)))))
    (testing "metadata on public state is rejected before it can be preserved"
      (let [data (caught-data #(fault/step (with-meta valid {:forged true})
                                           {:attempt-id :a}))]
        (is (= fault/invalid-director (:type data)))
        (is (= :director-outside-trace-domain (:reason data)))))
    (testing "per-rule and global firing counts must remain coherent"
      (let [data (caught-data #(fault/step (assoc valid :firings 1)
                                           {:attempt-id :a}))]
        (is (= fault/invalid-director (:type data)))
        (is (= :inconsistent-firing-count (:reason data)))))
    (testing "compiled matcher values must remain canonical projections"
      (let [tampered (assoc-in valid [:rules 0 :match :op] :raw-value)
            data (caught-data #(fault/step tampered {:attempt-id :a}))]
        (is (= fault/invalid-director (:type data)))
        (is (= :invalid-rule-match (:reason data)))))
    (testing "duplicate compiled rule ids are rejected"
      (let [rule (get-in valid [:rules 0])
            tampered (assoc valid :rules [rule rule])
            data (caught-data #(fault/step tampered {:attempt-id :a}))]
        (is (= fault/invalid-director (:type data)))
        (is (= :duplicate-rule-id (:reason data)))))))

(deftest identical-sequences-produce-byte-identical-evidence-and-equal-directors
  (let [plan [{:id :f/a :match {:op :x} :activation {:on-match 1 :times 2}
               :outcome {:tag :a :bytes (byte-array [7 7])}}
              {:id :f/b :match {:op :x} :activation {:on-match 1 :times 1}
               :outcome {:tag :b}}]
        attempts (list {:attempt-id :k1 :op :x}
                       {:attempt-id :k2 :op :x}
                       {:attempt-id :k3 :op :x}
                       {:attempt-id :k4 :op :y})
        run (fn [] (step-until (fault/director plan) attempts))]
    (let [{evidence1 :evidence director1 :director} (run)
          {evidence2 :evidence director2 :director} (run)]
      (testing "evidence and director states are accepted by canonical-value"
        (is (trace/canonical-form? (trace/canonical-value director1)))
        (is (trace/canonical-form? (trace/canonical-value (first evidence1))))
        (is (trace/canonical-form? (trace/canonical-value evidence1))))
      (testing "director states are equal"
        (is (= director1 director2)))
      (testing "evidence sequences are byte-identical under canonical-edn"
        (let [s1 (trace/canonical-edn evidence1)
              s2 (trace/canonical-edn evidence2)]
          (is (string? s1))
          (is (= s1 s2)))))))

(deftest step-return-shape-is-exact
  (let [d0 (fault/director [{:id :f/r :match {} :activation {:on-match 1} :outcome :o}])
        fired (fault/step d0 {:attempt-id :a})
        nofire (fault/step (fault/director []) {:attempt-id :a})]
    (is (= #{:director :evidence} (set (keys fired))))
    (is (= #{:attempt-id :matches :firing} (set (keys (:evidence fired)))))
    (is (= #{:rule-id :match-ordinal :firing-ordinal :rule-firing-ordinal :outcome}
           (set (keys (get-in fired [:evidence :firing])))))
    (is (= #{:rule-id :match-ordinal :activated? :fired?}
           (set (keys (first (get-in fired [:evidence :matches]))))))
    (is (= #{:director :evidence} (set (keys nofire))))
    (is (= #{:attempt-id :matches :firing} (set (keys (:evidence nofire)))))
    (is (nil? (:firing (:evidence nofire))))))
