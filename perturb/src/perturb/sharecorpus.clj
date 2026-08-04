(ns perturb.sharecorpus
  "THE THREE-WAY NEGATIVE CONTROL, as real perturb source compiled by Jolt.

  PERTURB-DESIGN E37, \"Q6, the decision-relevant negative — not found, by
  both\". Neither research pass located a published study that ran a
  substructural or typestate checker over ordinary persistent immutable
  collections and concluded it was useless. Both give the same reason, and it is
  architectural rather than bibliographic: mature mixed systems assign such
  values an unrestricted kind BEFORE typestate analysis, so the experiment is
  never run.

  THIS IS THEREFORE AN INTERNAL CONTROL AND IS FRAMED AS ONE. It is evidence
  about these rules on this corpus. It is not a published result, it does not
  establish that nobody has run the experiment, and `perturb.sharecheck` says so
  where it prints.

  The survey designed the shape and it is three-way, which is the point:

    ARM A  NEGATIVE.   A corpus of ONLY unrestricted persistent values with no
                       capability payload. Capability typestate must produce NO
                       nontrivial obligations and NO useful rejections. If it
                       instead emits artificial dead-name or duplication errors,
                       THAT IS THE FINDING and it gets reported as one.

    ARM B  POSITIVE.   A live capability placed INSIDE a persistent container,
                       and then duplicated, stored and discarded. Every one of
                       those must remain VISIBLE — not laundered by the
                       immutable shell. This is the arm that would fail if the
                       transitivity clause were missing.

    ARM C  STRUCTURAL. An out-of-bounds access and a broken persistence
                       invariant. These must be caught by STRUCTURAL reasoning
                       — `perturb.refine`'s ground linear fragment, driven from
                       real IR by `perturb.sharecheck` — and NOT by capability
                       typestate, which must say nothing about them at all.

  Reporting only that \"the checker rejected some vectors\" would not validate
  anything. The three-way shape is what separates the two concerns: arm A says
  the capability tier is SILENT where it should be, arm B says it is LOUD where
  it should be, and arm C says the thing it is silent about is caught somewhere
  else rather than not caught.

  EVERY FUNCTION HERE IS REAL, COMPILED SOURCE. `perturb.sharecheck` checks the
  IR the back end was handed, exactly as `perturb.check` does for
  `perturb.corpus`. Nothing in arm A or arm C opens a socket; arm B's
  capabilities are `perturb.nrepl` connections, which the checker refuses
  statically and which are never called.")

(require '[perturb.nrepl :as n])
(require '[perturb.octet :as o])

;; ===========================================================================
;; ARM A — UNRESTRICTED PERSISTENT VALUES, NO CAPABILITY PAYLOAD
;; ===========================================================================
;;
;; Every function below duplicates, stores, discards, nests and closes over
;; persistent values. Under the rule every one of them is SHAREABLE, so
;; contraction and weakening are sound and the capability tier has nothing to
;; say. The gate requires ZERO diagnostics of ANY kind across this arm.
;;
;; They are written to hit the exact shapes that DO draw a diagnostic the moment
;; a capability is inside them — arm B is the same shapes with `n/open` in place
;; of a literal, function for function where that is possible.

(defn vector-built-and-indexed
  "Build a persistent vector, read two positions, keep both. Contraction on an
  ordinary value: `v` is named three times and nothing moves."
  [a b]
  (let [v (conj (conj [] a) b)
        x (nth v 0)
        y (nth v 1)]
    [x y (count v)]))

(defn vector-duplicated
  "CONTRACTION, explicitly. The same persistent vector reaches two places and is
  then compared with itself. If capability typestate fired here it would be
  reporting a duplication error on a value that has no authority to duplicate."
  [a]
  (let [v [a a a]
        p [v v]]
    [(count (nth p 0)) (count (nth p 1)) (= v v)]))

(defn vector-discarded
  "WEAKENING, explicitly. A persistent vector is built and dropped in statement
  position. `perturb.check` raises `dangling` for exactly this shape when the
  value owes a destructor; an unrestricted value owes nothing and must draw
  nothing."
  [a]
  (let [_ [a a]
        m {:k a}]
    :dropped))

(defn map-assoc-and-drop
  "A persistent map: the shape that draws `escape` the instant a capability is
  the value at a key."
  [k v]
  (let [m  (assoc {} k v)
        m2 (assoc m :second v)
        m3 (dissoc m2 k)]
    [(count m) (count m2) (count m3)]))

(defn nested-collections
  "A map holding a vector holding a map. The transitivity clause reads this
  whole tree and finds nothing but primitives."
  [a]
  (let [inner {:a a}
        mid   [inner inner]
        outer {:rows mid :n (count mid)}]
    (count outer)))

(defn closure-over-a-vector
  "A closure CAPTURING a persistent value. This is `capture-in-closure` from
  `perturb.corpus` with the connection replaced by a vector, and it is the pair
  that matters: same shape, opposite verdict, and the only difference is what
  the closure reaches."
  [xs]
  (let [v (vec xs)]
    (map (fn [i] (nth v i)) (range (count v)))))

(defn closure-returned
  "A closure ESCAPING its scope, still holding the value. An unrestricted
  capture may leave; the whole content of the rule is that this is decided by
  what is captured and not by the fact of capturing."
  [base]
  (let [table {:base base}]
    (fn [k] (get table k))))

(defn loop-over-a-persistent-vector
  "A loop whose binding carries a persistent value across every back edge.
  `loop-not-preserving` is what a capability draws here; a vector draws
  nothing, and the accumulator GROWS, which is the shape E24 recorded a
  capability table cannot have."
  [xs]
  (loop [i 0 acc []]
    (if (>= i (count xs))
      acc
      (recur (inc i) (conj acc (nth xs i))))))

(defn octet-window-shared
  "perturb's own persistent byte window, duplicated and sliced. `perturb.octet`
  says in its own docstring that an octet view is an ordinary immutable value
  and that uniqueness and linearity are vacuous for it. This is that claim, run
  through the capability checker."
  [xs]
  (let [w  (o/octets xs)
        a  (o/osub w 0 (o/ocount w))
        b  w]
    [(o/ocount a) (o/ocount b) (o/ocount w)]))

(defn strings-and-keywords
  "Primitives, at the leaves, through three composites."
  [s]
  (let [t  (str s "-x")
        m  {:in s :out t}
        v  [m m]]
    [(count v) (:in (nth v 0))]))

;; ===========================================================================
;; ARM B — A LIVE CAPABILITY INSIDE A PERSISTENT CONTAINER
;; ===========================================================================
;;
;; The countercase. Each of these is an arm-A function with `n/open` in place of
;; the literal. The immutable shell must NOT launder it: duplication, storage
;; and discard all have to stay visible.
;;
;; NONE OF THESE IS EVER CALLED. They are compiled, their IR is checked, and the
;; checker refuses them. `perturb.nrepl/open` would open a socket; the gate runs
;; nothing in this arm, which is why the arm can hold a leak.

(defn conn-in-a-vector
  "The capability enters a VECTOR — the one composite the abstract domain models
  — and leaves the function inside it. A vector is followable, so this is not
  `escape`-by-opacity: it is the capability being handed out with no `:produces`
  that says so."
  [host port]
  (let [c (n/open host port)]
    [c :held]))

(defn conn-in-a-map
  "The capability enters a MAP. A capability path names tuple positions only, so
  the checker cannot follow it out — the shell is immutable and the route is
  gone. `perturb.corpus/connection-into-a-map` is the same shape; it is repeated
  here so arm B is complete on its own."
  [host port]
  (let [c (n/open host port)]
    {:conn c :opened true}))

(defn conn-in-a-nested-vector
  "TRANSITIVITY, at depth. The capability is two composites down. The rule says
  the outer vector is mixed because the inner one is, and the inner one is mixed
  because its element is."
  [host port]
  (let [c (n/open host port)]
    [[c] :depth-2]))

(defn conn-duplicated-through-a-vector
  "THE LAUNDERING TEST. Put the capability in a persistent vector, then read it
  out TWICE and close both copies. If the immutable shell laundered duplication
  this would check clean and would double-close a socket at run time."
  [host port]
  (let [v  [(n/open host port)]
        c1 (nth v 0)
        c2 (nth v 0)
        _  (n/close! c1)]
    (n/close! c2)))

(defn conn-discarded-inside-a-vector
  "WEAKENING, on a mixed value. `vector-discarded` is this function with the
  capability removed and it must draw nothing; this one must leak."
  [host port]
  (let [v [(n/open host port)]]
    :dropped))

(defn conn-captured-by-a-closure
  "CONTRACTION, on a mixed value, through a closure. `closure-over-a-vector` is
  this function with the capability removed.

  The rule CLASSIFIES this (the closure is mixed, witness [0]) and REFUSES the
  separate question of whether this capture is a violation, because
  `clojure.core/map` declares no higher-order retention contract. The
  diagnostic stands either way."
  [host port codes]
  (let [c (n/open host port)]
    (map (fn [f] (n/request c {"op" "eval" "code" f})) codes)))

(defn conn-into-a-future
  "THE ONE CAPTURE SHAPE THE RULE DECIDES, rather than merely classifies.
  `future` runs the closure somewhere else while `c` is still live here, and
  `perturb.nrepl/Connection` declares `:perturb.cap/contention :thread-confined`
  — so the route crosses a thread boundary the declaration says it may not.
  Decided FROM THE DECLARATION, with no model of what the closure body does.

  This is jolt-tcp's `client_test.clj:601` in miniature: the same `future`, the
  same still-live original name, the same declared contention. It is here so
  the decided branch is exercised by a gate that needs no external library."
  [host port]
  (let [c (n/open host port)]
    (future (n/request c {"op" "eval" "code" "1"}))))

;; ===========================================================================
;; ARM C — STRUCTURAL: INDEX SAFETY AND A PERSISTENCE LENGTH LAW
;; ===========================================================================
;;
;; Pure `perturb.octet` code with no capability anywhere. The capability checker
;; must be SILENT on every function here — that is half the arm. The other half
;; is that the defects ARE caught, by `perturb.sharecheck`'s structural pass:
;; abstract lengths built from real IR and handed to `perturb.refine`'s ground
;; linear fragment.
;;
;; The obligations are declared in `structural-obligations` at the bottom, in
;; the same posture `perturb.cap` takes: written down as data, discharged
;; elsewhere, and nothing here checks anything.

(defn in-bounds-read
  "`i` is 2 and the window is 3 octets. VALID."
  []
  (let [w (o/octets [1 2 3])
        i 2]
    (o/oref w i)))

(defn out-of-bounds-read
  "`i` is 5 and the window is 3 octets. REFUTED — and the run-time failure is an
  index error inside `nth`, which no capability rule has any view of."
  []
  (let [w (o/octets [1 2 3])
        i 5]
    (o/oref w i)))

(defn boundary-read
  "`i` is 3 and the window is 3 octets. The off-by-one, REFUTED — this is the
  `i <= n` against `i = n` control the reframe's revised queue asks for."
  []
  (let [w (o/octets [1 2 3])
        i 3]
    (o/oref w i)))

(defn concat-length-law
  "The persistence invariant that IS checkable here:
  `ocount(oconcat a b) = ocount a + ocount b`. VALID by normalisation."
  []
  (let [a (o/octets [1 2])
        b (o/octets [3 4 5])
        c (o/oconcat a b)]
    (o/ocount c)))

(defn broken-length-claim
  "A BROKEN persistence invariant: `oconcat` claimed to leave the length alone,
  which is what a structure-sharing implementation that forgot to add the tail
  count would do. REFUTED."
  []
  (let [a (o/octets [1 2])
        b (o/octets [3 4 5])
        c (o/oconcat a b)]
    (o/ocount c)))

(defn ancestor-unchanged
  "The other half of persistence: building `c` from `a` must not change `a`.
  Stated as a length obligation, which is the part of it this fragment can
  decide. VALID."
  []
  (let [a (o/octets [1 2])
        b (o/octets [3 4 5])
        c (o/oconcat a b)]
    (o/ocount a)))

(defn read-at-a-runtime-index
  "THE BOUNDARY. The index arrives as an argument, so it is an opaque integer
  with no sign and no bound. UNKNOWN, which the gate requires to be REFUSED
  rather than accepted — `perturb.refine`'s posture, unchanged."
  [i]
  (let [w (o/octets [1 2 3])]
    (o/oref w i)))

;; ===========================================================================
;; WHAT THE GATE REQUIRES
;; ===========================================================================

(def arm-a
  "Every function that must draw NOTHING from capability typestate."
  '[perturb.sharecorpus/vector-built-and-indexed
    perturb.sharecorpus/vector-duplicated
    perturb.sharecorpus/vector-discarded
    perturb.sharecorpus/map-assoc-and-drop
    perturb.sharecorpus/nested-collections
    perturb.sharecorpus/closure-over-a-vector
    perturb.sharecorpus/closure-returned
    perturb.sharecorpus/loop-over-a-persistent-vector
    perturb.sharecorpus/octet-window-shared
    perturb.sharecorpus/strings-and-keywords])

(def arm-b
  "var -> the diagnostic kind that must appear, and the sentence that says what
  would have been laundered if it did not."
  [{:var 'perturb.sharecorpus/conn-in-a-vector :kind :escape
    :laundered "a connection handed out inside a pair with no :produces"}
   {:var 'perturb.sharecorpus/conn-in-a-map :kind :escape
    :laundered "a connection stored under a map key and unreachable afterwards"}
   {:var 'perturb.sharecorpus/conn-in-a-nested-vector :kind :escape
    :laundered "a connection two composites deep"}
   {:var 'perturb.sharecorpus/conn-duplicated-through-a-vector :kind :use-after-move
    :laundered "ONE socket closed TWICE, the two closes reached through a"}
   {:var 'perturb.sharecorpus/conn-discarded-inside-a-vector :kind :dangling
    :laundered "a socket dropped while still owing its destructor, inside a"}
   {:var 'perturb.sharecorpus/conn-captured-by-a-closure :kind :capture
    :decided? false :ground :higher-order-undeclared
    :laundered "a connection reachable from a closure that may run any number"}
   {:var 'perturb.sharecorpus/conn-into-a-future :kind :capture
    :decided? true :ground :concurrency-introducer
    :laundered "a connection reachable from another THREAD, through a"}])

(def arm-c
  "var -> [obligation expected-verdict why]. The obligation is a formula in
  `perturb.refine`'s language over names this corpus binds: a let-bound name `x`
  denotes its abstract integer and `x-count` its abstract octet length.

  These are DECLARED here and DISCHARGED in `perturb.sharecheck`, against the IR
  the back end was handed. Nothing in this namespace checks them."
  [{:var 'perturb.sharecorpus/in-bounds-read
    :obligation '(and (<= 0 i) (< i w-count))
    :expect :valid
    :why "0 <= 2 < 3"}
   {:var 'perturb.sharecorpus/out-of-bounds-read
    :obligation '(and (<= 0 i) (< i w-count))
    :expect :refuted
    :why "5 < 3 is refuted by normalisation over the integers"}
   {:var 'perturb.sharecorpus/boundary-read
    :obligation '(and (<= 0 i) (< i w-count))
    :expect :refuted
    :why "the off-by-one: i = n is out of bounds and `<` is not `<=`"}
   {:var 'perturb.sharecorpus/concat-length-law
    :obligation '(= c-count (+ a-count b-count))
    :expect :valid
    :why "the persistence length law, discharged by linear normalisation"}
   {:var 'perturb.sharecorpus/broken-length-claim
    :obligation '(= c-count a-count)
    :expect :refuted
    :why "a concat that forgot the tail count: 5 = 2 is a genuine counterexample"}
   {:var 'perturb.sharecorpus/ancestor-unchanged
    :obligation '(= a-count 2)
    :expect :valid
    :why "the ancestor keeps its length after a descendant is built from it"}
   {:var 'perturb.sharecorpus/read-at-a-runtime-index
    :obligation '(and (<= 0 i) (< i w-count))
    :expect :unknown
    :why "an argument is an opaque integer with no sign and no bound; REFUSED"}])
