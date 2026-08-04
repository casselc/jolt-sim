# Application sketches under the progressive-formalism design

**Status: sketches. Nothing here compiles, and no compiler exists.** Every form
below is invented notation for `PROGRESSIVE-FORMALISM-DESIGN.md`'s primitives.
The syntax is illustrative and would not survive contact with an implementation
unchanged. Read them as *"what would development feel like"*, which is the
question they were written to answer, and not as a specification.

**What is grounded and what is not.** The evidence numbers below are
**arithmetic on declared domains** (a 3×17×2 product really is 102 cases), not
measurements — nothing was run. Where a sketch leans on something that exists,
it is named: `perturb.dbtx`, `perturb.evt`, `jolt.sim.explore`,
`jolt.sim.kernel`, `perturb.layer`. Where it leans on something that does not,
§8 says so.

---

## 1. The common shape

Every application in this design is the same five things. The domains differ in
which of them carry the weight, not in what they are.

| | |
| --- | --- |
| **manifest** | topology as one data artifact — cells, edges, constraints. Enumerable, so path questions have a denominator |
| **cells** | ordinary code: `(ctx state msg) -> [state' effects]`. No capability lives here |
| **regions** | where capabilities live. Bounded, with an exit rule |
| **specs** | one `:holds`, six consumers, tier set by the domain declaration |
| **budgets** | which checks, taps and spans run where |

### 1.1 The ladder, which is the whole thesis in nine lines

The same property, three tiers. **Only the `:domain` key changes; `:holds` never
moves.** This is §1.1's falsification criterion made concrete — if any sketch
below needs to rewrite `:holds` to change tier, the design is wrong.

```clojure
;; tier 1 — stated, unchecked                        :asserted
{:holds (fn [req resp] (= (content-length resp) (count (body resp))))}

;; tier 2 — add a generator                          :sampled, 1000 cases
{:domain {::request (gen/request)}                 :holds …}

;; tier 3 — make the domain finite     ← ONE KEY     :exhausted, 102/102
{:domain {::request (finite/product
                      {:method   (enum :get :head :post)
                       :body-len (range 0 17)
                       :encoding (enum :identity :chunked)})}
 :holds …}

;; tier 4 — deploy                     ← NOTHING     :monitored :native, 1/1000
```

---

## 2. A web API

The interesting part is not routing. It is that **authorization is a region**,
so "no query in this handler can reach a row outside the caller's scope" is a
checkable sentence rather than a code-review convention.

```clojure
(defmanifest orders/api
  {:cells    {:parse   :http/parse-request
              :authz   :auth/scope-request
              :plan    :orders/plan-submit
              :persist :orders/persist
              :render  :http/render}
   :pipeline [:parse :authz :plan :persist :render]

   :constraints [{:type :must-precede    :cell :authz :before :persist}
                 {:type :never-together  :cells [:authz/bypass :orders/persist]}
                 {:type :always-reachable :cell :render}]

   :routes (finite/product {:method (enum :get :post :delete)
                            :path   (enum "/orders" "/orders/:id")})})
```

`:routes` is finite, so **route exhaustiveness is `:exhausted`, not a hope**:
every route reachable, every method handled, every declared response shape
produced by some cell — 6 of 6, counted.

`:constraints` is Mycelium's vocabulary, checked against all enumerated paths.
Note the vacuity rule applies: `:always-reachable` passing because *no path
reaches `:render`* is reported `:inconclusive`, not `pass`.

### 2.1 Authority as a region, not a token

```clojure
(defcell :auth/scope-request
  {:input   ::http-request
   :output  {:authorized ::scoped-request :denied ::problem}
   :effects {}
   :grants  (fn [req] (region/of-scope (:claims req)))}  ; mints a REGION
  [ctx state req]
  (if-let [scope (verify (:token req))]
    [state [[:authorized (assoc req :scope scope)]]]
    [state [[:denied {:status 403}]]]))
```

The cell never holds a credential. It names a **region**, and the region is what
the persist cell's effects are checked against:

```clojure
(defspec no-query-escapes-caller-scope
  {:over   (cell/of :orders/persist)
   :domain {::scoped-request (finite/product {:tenant (enum :t1 :t2)
                                              :role   (enum :reader :writer :admin)
                                              :op     (enum :insert :update :select)})}
   :holds  (fn [req effects]
             (every? #(region/covers? (:scope req) (:target %))
                     (filter effect/db? effects)))
   :basis  "one (tenant, role, op) triple"})
```

**18 cases, `:exhausted`.** The same `:holds`, deployed, is the monitor that
catches the first handler someone writes with a hand-built SQL string.

### 2.2 What the REPL says

```clojure
perturb=> (why 'orders/persist)
{:strength :exhausted
 :units    {:checked 18 :expected 18}
 :scope    {:covered "tenant × role × op" :of "tenant × role × op"}
 :source   #{:symbolic}
 :basis    "one (tenant, role, op) triple"
 :residual [{:owed :db-semantics :next-layer 'postgres/primary
             :evidence :asserted :belief-rev "16.2"}]}
```

The residual is the honest part. The *authorization* claim is exhausted; what
the database does with an authorized query is a belief about someone else's
software, and it says so, with a revision.

### 2.3 Evidence earned

| claim | tier | why not higher |
| --- | --- | --- |
| every route handled, every response shape produced | `:exhausted` 6/6 | — |
| no query escapes caller scope | `:exhausted` 18/18 | over the *declared* scope model |
| request/response wire conformance | `:monitored` | production shapes are not enumerable |
| the database honours the scope | **`:asserted`** | it is not our code |

---

## 3. A TUI

A view is a pure projection; purity is *statically* checkable because the effect
system already knows what a projection may not do. The two things worth showing
are **subscriptions rebuilt at a boundary** and **input scripts as schedules**.

```clojure
(defsub visible-rows
  {:over    [::rows ::cursor ::filter]
   :rebuild :at-boundary                ; NOT per event — row 38's cost line
   :basis   "one recomputation per (rows, cursor, filter) change"}
  [rows cursor filt]
  (->> rows (filter (pred filt)) (window cursor 40)))

(defview transfer-screen [state ui]
  [:screen
   [:header {:title (:title ui)}]
   [:table  {:rows (visible-rows state ui) :cursor (:cursor ui)}]
   [:footer (help-view state ui)]])
```

`:rebuild :at-boundary` is re-frame's subscription discipline with the cost line
attached. **E35 finding 3 is the bug it prevents** — a fold that followed the
wrong order and made a checker look correct while measuring the wrong thing.

### 3.1 The UI property that is actually exhaustible

```clojure
(defspec submit-disabled-while-submitting
  {:over   (view/of transfer-screen)
   :domain {::state (finite/product {:phase     (enum :idle :confirming :submitting
                                                      :done :failed)
                                     :selection (range 0 3)})}
   :holds  (fn [state tree]
             (implies (= :submitting (:phase state))
                      (not (enabled? (find-node tree [:footer :submit])))))
   :basis  "one (phase, selection) pair"})
```

**15 cases, `:exhausted`** — over the *view tree*, which is a value. Double
submits are a real bug class and this is the whole test.

### 3.2 Input is a schedule, so UI testing is simulation

```clojure
(defscenario confirm-then-cancel
  {:schedule (input/script [[:key :down] [:key :enter] [:key :esc]])
   :clock    (virtual/from 0)
   :expect   {:terminal-phase :idle :effects-performed []}})
```

Same schedule object `jolt.sim.strategy` and `jolt.sim.explore` already consume.
`(rewind 12) (step)` works on it because `jolt.sim.kernel` already has exact
replay.

### 3.3 The honest ceiling

```clojure
perturb=> (why 'transfer-screen)
{:strength :exhausted :units {:checked 15 :expected 15}
 :scope {:covered "the view tree" :of "what the user sees"}
 :residual [{:owed :terminal-rendering :next-layer 'vhs/golden
             :evidence :sampled :source #{:native}}]}
```

`:covered "the view tree"` vs `:of "what the user sees"` is the scope axis doing
its job. Nothing about ANSI, terminal width, or a font is established by a
property of a tree, and the residual names where that lives.

---

## 4. A stream consumer

Two capabilities of this design meet here: **in-flight messages are a region**,
and **delivery semantics are a failure model the simulator enumerates.**

```clojure
(with-region in-flight
  {:discipline :unique
   :bound      1000
   :exit       :must-be-empty
   :obligation {:owed :ack-or-redeliver}}

  (loop [cursor (offset/start)]
    (let [batch (region/admit-all! in-flight (poll! consumer cursor))]
      (doseq [m batch]
        (process! m)
        (region/release! in-flight m {:via :ack!}))
      (recur (offset/advance cursor batch)))))
```

*"Every message is acked or redelivered"* is now **region exit**, not a
hand-written audit that someone remembers to run.

```clojure
(defspec every-message-acked-or-redelivered
  {:over    (region/of in-flight)
   :domain  (schedule/product {:dup     (enum 0 1 2)
                               :reorder (enum :none :swap)
                               :crash   (enum :none :after-process :before-ack)})
   :holds   (fn [trace] (region/exits-empty? trace in-flight))
   :basis   "one (duplicate-count, reorder, crash-point) schedule"})
```

**18 schedules, `:exhausted` over that grid.** The `:crash` arm is reachable
because `jolt.sim.process-explorer` already runs each schedule in a fresh child
and reports a non-exit as `:timeout` rather than mislabelling it a deadlock.

### 4.1 The decoder, where E4's rule lives

```clojure
(defspec frame-trichotomy
  {:domain (finite/frames {:max-len 16})
   :holds  (fn [octets r]
             (case (:tag r)
               :need-more (= (:cursor r) (:original-cursor r))  ; the exact rule
               :ok        (= octets (encode (:value r)))
               :invalid   true))                                ; ← see below
   :basis  "one frame of ≤16 octets"})
```

`:invalid true` is **deliberately vacuous and must be labelled so.** E39 found
exactly this: the `:invalid` arm is one third of the trichotomy and is
enumerated nowhere, because *what is the finite domain of invalid inputs?* has
no canonical answer. Under §2's rules this arm reports `:inconclusive` with its
basis rather than contributing a pass.

### 4.2 Where the streaming lane meets §9

Consumer lag and redelivery are **work amplification** — the metastability
paper's orbit, directly measured rather than inferred:

```clojure
perturb=> (stability/probe 'orders/consumer)
{:orbit      (region/occupancy 'in-flight)   ; measured, not fitted
 :lambda-2   0.31                            ; distance to the metastable boundary
 :evidence   {:strength :sampled :source #{:simulated}
              :residual [{:owed :calibration-fit :fitted [:λ :τ] :fixed [:μ :ρ]}]}}
```

---

## 5. A database lane

**The most built-out sketch, because `perturb.dbtx` already exists** and already
carries the hard part: one `begin` with three run-time-chosen destinations,
resolved by a discriminator, with `:poisoned` as a declared cancelled state that
discharges nothing on entry.

```clojure
(defcap orders/tx
  {:states      #{:idle :open :poisoned :closed}
   :transitions [{:op :begin    :from :idle     :to :open     :when :r0}
                 {:op :begin    :from :idle     :to :idle     :when [:r1 :r2]}
                 {:op :begin    :from :idle     :to :poisoned :when [:r3 :r4 :r5 :r6 :r7]}
                 {:op :commit   :from :open     :to :closed   :discharges [:tx-open]}
                 {:op :rollback :from :open     :to :closed   :discharges [:tx-open]}
                 {:op :close    :from :poisoned :to :closed}]
   :discriminator {:pred `autocommit-probe
                   :arms {:clean [:r1 :r2] :dirty [:r3 :r4 :r5 :r6 :r7]}}
   :cancelled   :poisoned})
```

**The pool is the region, and it is the piece `dbtx` is missing:**

```clojure
(with-region pool {:discipline :unique :bound 8 :exit :must-be-empty})
```

That is E24's *collection* and *growth* shapes, which is why the pool has never
been expressible. A leaked connection now reports as **"region exited holding
2"** at a source location — not as an identity the checker cannot track.

### 5.1 Isolation as a failure model — the sweep worth having

```clojure
(defspec no-lost-update-under-read-committed
  {:over    (fn/of ::checkout)
   :failure {:isolation :read-committed}   ; the anomalies the level PERMITS
   :domain  (schedule/interleavings {:txns 2 :ops 3})
   :holds   (fn [h] (not (anomaly/lost-update? h)))
   :basis   "one interleaving of 2 transactions × 3 operations"})
```

```clojure
perturb=> (check 'no-lost-update-under-read-committed)
{:verdict  :violation
 :units    {:checked 90 :expected 90}
 :witness  {:schedule [[:t1 :select] [:t2 :select] [:t1 :update] [:t2 :update]]
            :sites    ["orders/checkout:41" "orders/checkout:47"]}
 :evidence {:strength :exhausted :source #{:simulated}}}
```

**That is the demo.** *"Under READ COMMITTED this code has a lost update between
line 41 and line 47"*, found by bounded enumeration in seconds, over a stated
90-interleaving domain — the class of bug nobody finds until it is revenue.

### 5.2 What stays `:asserted`, and why that is fine

```clojure
(defforeign postgres/execute
  {:believed {:read-committed :no-dirty-reads
              :serialization-failure :retryable
              :timeout-means :unknown-outcome}
   :citation {:doc "postgresql.org/docs/16/transaction-iso.html" :rev "16.2"}
   :evidence :asserted})
```

The belief compiles to a conformance monitor. When the engine does something the
belief forbids, you get a **named violation attributed to the belief**, not a
mystery at 3am.

---

## 6. Gossip Glomers

Chosen in the design because **the oracle is independent, total, and we did not
write it** — the condition that has already sunk two claims in this record.

### 6.1 Echo — the smallest thing that exercises the whole spine

```clojure
(defsession maelstrom/echo
  {:roles  {:client :foreign :node :local}
   :global [[:client -> :node ::echo]
           [:node   -> :client ::echo_ok]]})

(defcell :maelstrom/echo
  {:input ::echo :output {:reply ::echo_ok} :effects {}}
  [ctx state msg]
  [state [[:reply {:type "echo_ok" :echo (:echo msg)}]]])
```

`:client :foreign` is doing real work: Maelstrom is not our code, so its half of
the session is **monitored, not checked**, and a malformed client message
becomes an attributed session violation rather than a parse error six frames
down.

### 6.2 Unique IDs

```clojure
(defspec ids-unique-under-partition
  {:over   (cluster/of maelstrom/unique-ids {:nodes 3})
   :domain (schedule/product {:partition (enum :none :minority :majority)
                              :calls     (range 1 4)})
   :holds  (fn [h] (apply distinct? (ids h)))
   :basis  "one (partition-shape, calls-per-node) schedule on exactly 3 nodes"})
```

**9 schedules, `:exhausted` — on exactly 3 nodes.** The `:basis` carries the
`3` because that is the honest denominator, and §2 rule 2 will not let the
sentence be written without it.

### 6.3 Broadcast — obligations become the specification

```clojure
(with-region unacked
  {:discipline :shareable                      ; many peers may hold the same msg
   :exit       :must-be-empty
   :obligation {:owed :delivered-to-every-peer}})

(defspec eventual-delivery
  {:over   (region/of unacked)
   :domain (schedule/product {:partition (enum :none :split :heal-late)
                              :msgs      (range 1 4)})
   :holds  (fn [h] (region/exits-empty? h 'unacked))
   :basis  "one (partition-shape, message-count) schedule on 5 nodes"})
```

`:discipline :shareable` is `perturb.share`'s existing classifier, and the
transitive shareability rule from E37/E38 is what decides whether a broadcast
payload may be held by several peers at once.

### 6.4 Efficient broadcast — where the two lanes meet on one artifact

Challenge 3d/3e scores **messages-per-operation**. That number *is* work
amplification, which *is* the metastability paper's orbit:

```clojure
(defstability broadcast-amplification
  {:control  {:offered (ramp 10 200 :ops-per-s)}
   :observe  {:msgs-per-op (/ (msgs h) (ops h))
              :orbit       (region/occupancy 'unacked)}   ; MEASURED, not fitted
   :model    (ctmc/from 'maelstrom/broadcast)             ; GENERATED from the
                                                          ; manifest + telemetry
   :holds    (fn [m] (> (:lambda-2 m) 0.05))
   :basis    "one calibrated CTMC over (unacked, retry-orbit), 5 nodes"})
```

This is the payoff of §9.3(a): the challenge's own scoring metric and the
metastability observable **are the same measurement**, and the orbit is a region
occupancy rather than a variable CMA-ES has to infer.

### 6.5 The two-lane report, which is the point of the exercise

```clojure
perturb=> (report 'maelstrom/broadcast)
{:ours      {:strength :exhausted :units {:checked 12 :expected 12}
             :source #{:simulated}
             :basis "one (partition-shape, message-count) schedule on 5 nodes"}
 :maelstrom {:strength :sampled :source #{:native}
             :oracle :jepsen/checker :independent true :seeds [7 8 9]}
 :agreement :consistent}
```

**`:agreement` is the finding.** If Maelstrom produces a history our exhaustive
lane declared impossible, our schedule model is wrong — and learning that is
worth more than passing.

---

## 7. One REPL session, across all of it

```clojure
perturb=> (gaps)                       ; ranked by reachable code behind each
[{:kind :foreign-belief   :at 'postgres/execute :rev "16.2" :behind "62% of handlers"}
 {:kind :asserted         :at 'stripe/charge    :rev "2026-03-11" :behind "9%"}
 {:kind :inconclusive     :at [:b6.3 :error-mapping]
                          :basis "one refusal inside a declared layer's extent"}
 {:kind :inconclusive     :at [:frame-trichotomy :invalid]
                          :basis "one malformed frame from a declared finite domain"}
 {:kind :unrouted-residual :at 'orders/persist :owed :db-semantics :next-layer nil}]
```

Nobody wrote that list. It is derived from what the artifacts emit — which is
the answer to the objection that residual routing presupposes the residual is
already named.

```clojure
perturb=> (cap/flow :from 'pool/acquire! :to 'wire/write!)
{:paths 41 :violating 0
 :evidence {:strength :exhausted :units {:checked 41 :expected 41}
            :source #{:symbolic} :from :declarations}}

perturb=> (dead {:from :ir})
{:verdict :inconclusive
 :why "perturb.ir's universe is open by construction and load-order dependent"
 :basis "one namespace analysed after install!"}
```

**That second one is the sketch I care most about.** A dead-code query over an
open universe returns `:inconclusive`, **not `[]`** — because `[]` reads exactly
like "no dead code" and would be the single most likely way a query surface
lies.

```clojure
perturb=> (diff/observed-not-declared)
[{:edge [:orders/persist :retry/schedule!] :seen 1204 :declared false}]
```

The retry path exists in production and not in the model. **That is a finding
about the model**, the same shape as §9.3(b)'s model error and the same shape as
E38 — and it is the one query neither a parser nor a monitor could ask alone.

```clojure
perturb=> (rewind 4412)
perturb=> (step)
perturb=> (datafy *conn)
{:cap/id 4471 :cap/state :open :cap/region 'pool
 :cap/obligations [{:owed :tx-open :since 4390}]}

perturb=> (nav *conn :cap/machine)
{:now :open
 :legal [{:op :commit :to :closed :discharges [:tx-open]}
         {:op :rollback :to :closed :discharges [:tx-open]}]
 :illegal-from-here [{:op :begin :why "no :begin edge from :open"}]}
```

Asking a live object what it is allowed to do next, at a rewound point in a
replayed trace. `perturb.cap` already holds that data; `jolt.sim.kernel` already
has the exact replay.

---

## 8. What these sketches lean on that does not exist

Named rather than assumed, so no reader mistakes a sketch for a plan:

- **`finite/product`, `schedule/product`, `schedule/interleavings`** — domain
  constructors that yield enumerable domains *and their counts*. Nothing like
  them exists; `jolt.sim.explore` enumerates schedule plans and is the closest.
- **`region/*` in its entirety.** §3.2 of the design; unbuilt.
- **`ctmc/from`** — model generation from a manifest plus telemetry. §9.3(a),
  and it needs numerics this codebase does not have.
- **`defsub` with `:rebuild :at-boundary`** — the design's §3.4(b)2, restored on
  paper only.
- **The manifest itself.** Perturb's declarations are real but spread across
  `cap`, `dbtx`, `http`, `tcpcap` and the checker's tables. §5's queries assume
  one place; there isn't one.
- **Anything about Maelstrom running here.** It needs a JVM and the `clojure`
  CLI, which is not installed in this environment.

## 9. Nonclaims

1. **No sketch was executed.** The counts are arithmetic on declared domains.
   A 3×17×2 product is 102 cases whether or not any of this works.
2. **The syntax is invented and will not survive implementation.** Its purpose
   is to show *where the obligations land*, not to be a grammar.
3. **`:exhausted` here always means "over the declared grid."** Every one of
   §§2–6's exhausted claims is exhausted over a hand-drawn domain, and the
   domains are drawn by the same hand that wrote the properties — E40's
   hand-drawn-denominators residual, in a new place.
4. **The web, TUI and streaming sketches have no existing counterpart** in
   either codebase. Only §5 (`perturb.dbtx`) and §6.3's shareability
   (`perturb.share`) rest on something built.
5. **The isolation-anomaly result in §5.1 is fabricated.** No such check has
   been run; it is written as a violation because that is the interesting case
   to show, not because a lost update was found.
6. **The two-lane agreement in §6.5 is the design's most load-bearing untested
   claim.** If our simulated lane and Maelstrom cannot be made to disagree
   *detectably*, the two-lane discipline is decoration.
