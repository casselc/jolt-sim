(ns jolt.sim.explore
  "Pure, deterministic top-level future-admission plan enumeration.

  A schedule plan is an exact permutation of the ordinals 0..N-1: the admission
  order in which `jolt.sim.future-schedule` runs N spawned bodies one at a time.
  Enumerating candidate plans in a stable order supplies controlled-execution
  cases without depending on host thread interleavings. It does not traverse
  the state graph of an arbitrary Jolt program or make a completeness claim.

  `schedule-plans` returns the first `:max-schedules` plans in lexicographic
  order. It walks permutations one at a time with Narayana Pandita's
  next-permutation rule, so the cost is O(N) per plan and O(N*limit) overall; it
  never materializes the N! permutation space. A large-N / small-limit request
  therefore returns promptly where an eager `(take limit (all-perms N))` would
  never finish.

  This namespace is pure Clojure with no host, ABI, or controller dependency, so
  it runs unchanged on any image including an ordinary released one.")

(defn- invalid-config
  "Builds the canonical malformed-config ex-info. Every rejection carries the
  :type, a stable :reason keyword, and the offending key/value evidence."
  [reason data]
  (ex-info
   (str "schedule-plans rejected a malformed configuration: " (name reason))
   (merge {:type ::invalid-config :reason reason} data)))

(defn- next-permutation
  "Returns the next lexicographically greater permutation of `perm`, or nil when
  `perm` is the last permutation. `perm` must be a vector of the integers
  0..(count perm)-1. Pure; the input is never mutated."
  [perm]
  (let [n (count perm)]
    ;; 1. Largest k with perm[k] < perm[k+1]. Absent => last permutation.
    (loop [k (- n 2)]
      (cond
        (neg? k)
        nil

        (< (perm k) (perm (inc k)))
        (let [pivot (perm k)]
          ;; 2. Largest l > k with perm[k] < perm[l]; always exists here.
          (loop [l (dec n)]
            (if (< pivot (perm l))
              (let [swapped (assoc perm k (perm l) l pivot)
                    tail (subvec swapped (inc k))]
                ;; 3. Reverse the suffix perm[k+1..] and reassemble.
                (vec (concat (subvec swapped 0 (inc k)) (rseq tail))))
              (recur (dec l)))))

        :else
        (recur (dec k))))))

(defn- generate
  "Accumulates at most `limit` lexicographic permutations of 0..(dec n) into a
  vector. Bounded by `limit`: once it is reached the loop returns without
  computing another next-permutation, and it always stops at the last
  permutation regardless of `limit`."
  [n limit]
  (loop [perm (vec (range n))
         acc []]
    (let [acc (conj acc perm)]
      (if (>= (count acc) limit)
        acc
        (if-let [next (next-permutation perm)]
          (recur next acc)
          acc)))))

(defn- check-config
  "Validates the `schedule-plans` configuration map and returns the validated
  [future-count max-schedules] pair. Throws ::invalid-config for any non-map,
  unknown key, missing key, or non-positive-integer value."
  [config]
  (when-not (map? config)
    (throw (invalid-config :config-not-a-map {:value config})))
  (let [allowed #{:future-count :max-schedules}
        ;; Compare printable keys rather than the keys themselves. Mixed key
        ;; types such as a keyword and string have no common Jolt comparator,
        ;; but must still produce the typed invalid-config failure.
        unknown (seq (sort-by pr-str (remove allowed (keys config))))]
    (when unknown
      (throw (invalid-config :unknown-key {:keys (vec unknown)})))
    (when-not (contains? config :future-count)
      (throw (invalid-config :missing-key {:key :future-count})))
    (let [n (get config :future-count)]
      (when-not (and (integer? n) (pos? n))
        (throw (invalid-config :not-a-positive-integer
                               {:key :future-count :value n})))
      (when-not (contains? config :max-schedules)
        (throw (invalid-config :missing-key {:key :max-schedules})))
      (let [limit (get config :max-schedules)]
        (when-not (and (integer? limit) (pos? limit))
          (throw (invalid-config :not-a-positive-integer
                                 {:key :max-schedules :value limit})))
        [n limit]))))

(defn schedule-plans
  "Returns a vector of at most `:max-schedules` exact permutations of the
  integers 0 through one less than `:future-count`, in deterministic
  lexicographic order.

  Options (a map, exactly these keys):

    :future-count   N, a positive integer. Plans are permutations of 0..N-1.
    :max-schedules  the maximum number of plans to return, a positive integer.

  Both keys are required. Unknown or missing keys, and any value that is not a
  positive integer, are rejected with an ex-info of type
  :jolt.sim.explore/invalid-config whose ex-data carries a stable :reason
  keyword and the offending :key/:value/:keys evidence.

  Generation is bounded by :max-schedules: it never materializes the full N!
  permutation space, so a large :future-count with a small :max-schedules
  returns promptly. The result never exceeds :max-schedules entries and stops at
  the last permutation if N! is smaller than :max-schedules."
  [config]
  (let [[n limit] (check-config config)]
    (generate n limit)))
