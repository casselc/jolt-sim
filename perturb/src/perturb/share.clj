(ns perturb.share
  "THE TRANSITIVE SHAREABILITY RULE — the classifier for a freely duplicable
  value, and the exact boundary of what it decides.

  WHY THIS IS CAPABILITY-TIER WORK. PERTURB-DESIGN E37, correction 1 and the
  reframe that follows it. `STRUCTURAL-TIER-BRIEF.md` said perturb had \"a
  vocabulary for constrained things and no story for the unconstrained
  majority\" and proposed a second, structural tier to hold that story. Two
  independent research passes refuted the premise and replaced it with something
  sharper, which E37 quotes in full:

    > contraction and weakening are sound when aliases cannot observe mutation,
    > copying cannot duplicate a unique finalization obligation, discarding
    > cannot leak a program-owned obligation, and EVERY TRANSITIVELY REACHABLE
    > EXPOSED COMPONENT IS ITSELF UNRESTRICTED — or safely sealed behind an
    > observationally immutable interface.

  The classifier is an INTERFACE property, not an allocation property. That last
  clause is §4.6's root cause and not a separate tier: a persistent vector can
  hold a live socket and a closure can capture one; the shell is immutable, and
  duplicating it duplicates ROUTES TO THE CAPABILITY. E34 left exactly this
  standing — 4 `capture` diagnostics on jolt-tcp, 57% of everything
  `-M:tcpcheck` still rejects.

  So this namespace is not the structural tier. It is the transitivity rule the
  CAPABILITY tier needs, and `perturb.check` consumes it at the two sites where
  a value is duplicated or stored: a closure capture, and a composite the
  checker cannot follow a capability out of.

  THE RULE, EXACTLY.

    primitives                         SHAREABLE
    immutable products / sums /
      collections / closures           SHAREABLE iff EVERY field, element,
                                       alternative and capture is
    a declared capability              MIXED — authority-bearing, never promoted
    opaque / FFI                       REFUSED — promotion denied unless a
                                       TRUSTED DECLARATION justifies it
    a sealed interface                 SHAREABLE iff its three interface clauses
                                       are all false AND the seal does not
                                       reach a capability

  THREE VERDICTS AND ONLY THREE, and the third is the one to read first.

    :shareable  contraction and weakening are sound at the declared interface.
    :mixed      they are not. The value is authority-bearing and every
                duplication, storage and discard of it stays a CAPABILITY-TIER
                event that must remain visible.
    :refused    this procedure will not say. An opaque value with no trusted
                declaration, a malformed profile, a seal that contradicts
                itself. REFUSED IS NEVER AN ACCEPT — it is the same posture
                `perturb.refine` takes on :unknown, and for the same reason: a
                classifier that promoted on \"don't know\" would be a machine for
                laundering authority that looks like a classifier.

  DOMINANCE, WHICH IS A SOUNDNESS PROPERTY AND NOT A CONVENIENCE. Combining the
  components of a composite takes the WORST, ordered

      :mixed  >  :refused  >  :shareable

  `:mixed` beats `:refused` because `:mixed` is a DECISION — one component is
  known authority-bearing, so the composite is known non-shareable, and an
  undecided sibling cannot make that go away. `:refused` beats `:shareable` for
  the ordinary reason. Getting this order backwards would let one unclassifiable
  element mask a socket sitting beside it.

  THE QUALIFICATION, AND IT IS LOAD-BEARING. The survey states it and a naive
  implementation gets it wrong: HIDDEN MUTATION DOES NOT AUTOMATICALLY DEFEAT
  UNRESTRICTED USE. Cached hashes, reference counts and path-copying internals
  are fine when they cannot change public value semantics or expose a unique
  program obligation. So:

    `classify` NEVER READS `:perturb.share/internal-mutation`.

  It is not an input to the decision and there is a case in the table below that
  pins it — two interfaces identical but for a declared mutating internal, same
  verdict. What decides is the three INTERFACE clauses:

    :perturb.share/aliases-observe-mutation?         can a second holder see it
    :perturb.share/duplication-duplicates-authority? does copying copy a route
    :perturb.share/discard-leaks-obligation?         does dropping leak a debt

  \"Does it mutate\" is not one of them, on purpose. What `:internal-mutation`
  IS for is the declaration rules below: it must name a kind whose observability
  at the interface is vacuous BY CONSTRUCTION, from a closed set, with a reason.
  A kind outside that set is refused where it is written rather than argued
  about, exactly as `perturb.check/check-cancellation-declarations!` refuses a
  cancelled state with an ordinary outgoing edge. `:transient-builder` is
  deliberately NOT in the set: a transient is an AFFINE builder whose legal
  endpoint is an unrestricted persistent value (E37, \"transients, from the
  survey only\"), which is a linear -> unrestricted freeze boundary and not
  hidden mutation.

  A SEAL MAY HIDE MUTATION. IT MAY NOT HIDE AUTHORITY. That is the one rule that
  makes the qualification safe to have at all, and it is the analogue of the
  cancelled-state side condition: a declaration must not be able to lie. An
  interface that seals a payload which transitively reaches a declared
  capability, while claiming duplication does not duplicate authority, is
  REFUSED where it is written — because every program holding the sealed value
  would otherwise be accepted on a false premise.

  NOTHING HERE CHECKS A PROGRAM. This namespace decides a PROFILE against
  DECLARATIONS. `perturb.check` builds the profile from its own abstract value
  at a capture or a composite site; `perturb.sharecheck` runs the boundary table
  and the three-way control. Same split as `perturb.refine`/`perturb.check`, and
  for the same reason: what the procedure decides can then be read, and run, on
  its own.

  WHAT THIS IS NOT. It is not a published result. Both research passes searched
  for a study that ran a substructural or typestate checker over ordinary
  persistent immutable collections and found none, and both give the same
  architectural reason: mature mixed systems assign such values an unrestricted
  kind BEFORE typestate analysis, so the experiment is never run. What
  `perturb.sharecheck` runs is an INTERNAL CONTROL on this corpus. It is
  evidence about these rules on this code and it is nothing else.")

(require '[clojure.string :as str])

;; ---------------------------------------------------------------------------
;; PROFILES — the shape of a value, as the rule sees it
;; ---------------------------------------------------------------------------
;;
;;   :prim                      an integer, a keyword, a string, a boolean
;;   [:product p ...]           an immutable product: tuple, record, pair
;;   [:sum p ...]               an immutable sum: one of the alternatives
;;   [:coll p]                  a persistent collection whose elements are p
;;   [:closure p ...]           a closure over the captures p ...
;;   [:cap C]                   a declared capability, C its declared name
;;   [:opaque tag]              an FFI / host / unannotated result
;;   [:sealed tag p]            p, behind the interface named `tag`
;;
;; A PROFILE IS NOT A TYPE. It carries no field names, no arity, no element
;; count, and `[:coll p]` says every element has profile p rather than which
;; ones do. That is enough for transitive reachability and it is deliberately
;; not enough for anything else — see `perturb.sharecheck`'s nonclaims.

(def profile-heads
  #{:prim :product :sum :coll :closure :cap :opaque :sealed})

(defn- head [p]
  (cond
    (= :prim p) :prim
    (and (vector? p) (contains? profile-heads (first p))) (first p)
    :else nil))

(defn- children
  "The sub-profiles of a composite, in declaration order."
  [p]
  (let [h (head p)]
    (cond
      (or (= :product h) (= :sum h) (= :closure h)) (vec (rest p))
      (= :coll h)   [(second p)]
      (= :sealed h) [(nth p 2)]
      :else [])))

(defn- component-word [h]
  (cond
    (= :product h) "field"
    (= :sum h)     "alternative"
    (= :coll h)    "element"
    (= :closure h) "capture"
    :else          "component"))

(defn render-profile
  [p]
  (let [h (head p)]
    (cond
      (nil? h)       (str "<malformed " (pr-str p) ">")
      (= :prim h)    "prim"
      (= :cap h)     (str "cap " (second p))
      (= :opaque h)  (str "opaque " (second p))
      (= :sealed h)  (str "sealed[" (second p) "] " (render-profile (nth p 2)))
      (= :coll h)    (str "coll<" (render-profile (second p)) ">")
      :else (str (name h) "(" (str/join ", " (map render-profile (rest p))) ")"))))

;; ---------------------------------------------------------------------------
;; INTERFACES — the declaration, as data. NOTHING HERE CHECKS A PROGRAM.
;; ---------------------------------------------------------------------------

(def clauses
  "The three interface facts the rule reads, named so a declaration missing one
  is visible. `perturb.cap/axes` is the pattern.

  NOT ON THIS LIST, ON PURPOSE: anything about whether the implementation
  mutates. See the namespace docstring."
  [:perturb.share/aliases-observe-mutation?
   :perturb.share/duplication-duplicates-authority?
   :perturb.share/discard-leaks-obligation?])

(def internal-mutation-kinds
  "The CLOSED SET of hidden-mutation kinds whose observability at a declared
  interface is vacuous BY CONSTRUCTION. A declaration naming anything else is
  refused where it is written — not because the argument would be wrong, but
  because an argument is not a declaration and this procedure cannot check one.

  `:transient-builder` is deliberately absent. E37: a transient shares structure
  with a persistent value, uses an owner token for controlled in-place update,
  and is INVALID after `persistent!`. That is an affine builder with a
  linear -> unrestricted freeze boundary, which is a capability-tier shape
  perturb already has machinery for, and calling it hidden mutation would hide
  the one edge that matters."
  #{:cached-hash :refcount :path-copying :memoized-count})

(defn interface
  "Build an interface declaration. Validates only that all three clauses are
  present — this is shape, not semantics. `interface-faults` is where the
  semantic rules live, because a declaration that is well-shaped can still lie."
  [m]
  (let [missing (remove (fn [c] (contains? m c)) clauses)]
    (when (seq missing)
      (throw (ex-info "perturb.share: interface is missing a clause"
                      {:perturb.share/missing (vec missing)})))
    m))

(def registry
  "tag -> interface declaration."
  (atom {}))

(defn declare-interface!
  [decl]
  (swap! registry assoc (:perturb.share/tag decl) decl)
  decl)

(defn reset-registry! [] (reset! registry {}))

(defn interfaces [] @registry)

;; ---------------------------------------------------------------------------
;; THE DECLARATION'S OWN RULES — a declaration must not be able to lie
;; ---------------------------------------------------------------------------

(declare reaches-capability)

(defn interface-faults
  "Every way one interface declaration is refused, as data. Empty means the
  declaration is well formed AND does not claim something this procedure can
  see is false. It does not mean the declaration is true.

  The rules, in the order they matter:

  1. `seal-hides-authority`. THE ONE THAT MAKES THE QUALIFICATION SAFE. An
     interface that seals a payload transitively reaching a declared capability
     may not also claim that duplicating it does not duplicate authority. A seal
     is an observational claim about MUTATION; there is no observational claim
     that makes a second route to a socket stop being a second route.

  2. `mutation-kind-unrecognised` / `mutation-unjustified`. `:internal-mutation`
     must name a kind from the closed set and say why. It is never read by
     `classify`; it is read here.

  3. `untrusted-promotion`. An interface whose three clauses are all false
     PROMOTES its subject to unrestricted. That is a trust statement and it must
     name who makes it, exactly as `perturb.cap`'s annotations are hand-written
     and say so."
  [decl]
  (let [tag  (:perturb.share/tag decl)
        seal (:perturb.share/seals decl)
        dup  (:perturb.share/duplication-duplicates-authority? decl)
        obs  (:perturb.share/aliases-observe-mutation? decl)
        disc (:perturb.share/discard-leaks-obligation? decl)
        im   (:perturb.share/internal-mutation decl)
        reach (when seal (reaches-capability seal []))]
    (vec
      (concat
        (when (and reach (not (true? dup)))
          [{:kind :seal-hides-authority :tag tag
            :detail [(str "interface     " tag " seals " (render-profile seal))
                     (str "which reaches " (:cap reach) " at " (:where reach))
                     "and declares :duplication-duplicates-authority? false"
                     "A SEAL MAY HIDE MUTATION. IT MAY NOT HIDE AUTHORITY: no"
                     "observational claim makes a second route to a capability"
                     "stop being a second route. Refused where it is WRITTEN."]}])
        (when (and im (not (contains? internal-mutation-kinds (:kind im))))
          [{:kind :mutation-kind-unrecognised :tag tag
            :detail [(str "interface     " tag " declares internal mutation of kind "
                          (pr-str (:kind im)))
                     (str "the closed set is "
                          (pr-str (vec (sort-by str internal-mutation-kinds))))
                     "a kind outside it may well be harmless, but an argument is"
                     "not a declaration and this procedure cannot check one"]}])
        (when (and im (contains? internal-mutation-kinds (:kind im))
                   (empty? (str (:why im))))
          [{:kind :mutation-unjustified :tag tag
            :detail [(str "interface     " tag " declares :internal-mutation with no :why")
                     "the kind is in the closed set; the reason it is vacuous"
                     "AT THIS INTERFACE still has to be written down"]}])
        (when (and (false? dup) (false? obs) (false? disc)
                   (nil? (:perturb.share/trusted-by decl)))
          [{:kind :untrusted-promotion :tag tag
            :detail [(str "interface     " tag " declares all three clauses false")
                     "which PROMOTES its subject to unrestricted. That is a trust"
                     "statement; :perturb.share/trusted-by must name who makes it"]}])))))

(defn check-interface-declarations!
  "Every fault over every declared interface, in tag order."
  [ifaces]
  (vec (mapcat (fn [k] (interface-faults (get ifaces k)))
               (sort-by str (keys ifaces)))))

;; ---------------------------------------------------------------------------
;; REACHABILITY — the transitive half, on its own
;; ---------------------------------------------------------------------------

(defn reaches-capability
  "The first declared capability transitively reachable in `p`, with the path to
  it, or nil. A SEAL IS NOT A BARRIER HERE: this is the function the seal rule
  uses to catch a seal claiming to hide one, so descending through `:sealed` is
  the whole point."
  [p path]
  (let [h (head p)]
    (cond
      (nil? h) nil
      (= :cap h) {:cap (second p) :path path
                  :where (if (empty? path) "the value itself"
                           (str "position " (str/join "." (map str path))))}
      :else
      (reduce (fn [acc i]
                (or acc (reaches-capability (nth (children p) i) (conj path i))))
              nil (range (count (children p)))))))

;; ---------------------------------------------------------------------------
;; THE CLASSIFIER
;; ---------------------------------------------------------------------------

(def ^:private rank {:shareable 0 :refused 1 :mixed 2})

(defn worse
  "The dominance order. :mixed > :refused > :shareable — see the docstring."
  [a b]
  (if (>= (get rank (:class a)) (get rank (:class b))) a b))

(defn- path-str [path]
  (if (empty? path) "" (str " at " (str/join "." (map str path)))))

(defn classify
  "Classify one profile against a set of interface declarations.

  Returns {:class :shareable|:mixed|:refused :witness path :why [lines]} and
  never throws. `ifaces` is tag -> declaration, as `interfaces` returns."
  ([ifaces p] (classify ifaces p []))
  ([ifaces p path]
   (let [h (head p)]
     (cond
       (nil? h)
       {:class :refused :witness path
        :why [(str "`" (pr-str p) "` is not a profile this rule can read"
                   (path-str path))]}

       (= :prim h)
       {:class :shareable :witness path :why ["a primitive is unrestricted"]}

       (= :cap h)
       {:class :mixed :witness path :cap (second p)
        :why [(str "a declared capability (" (second p) ")" (path-str path)
                   " is authority-bearing")
              "duplicating a route to it duplicates the authority; discarding"
              "the last route leaks the obligation to run its destructor"]}

       (= :opaque h)
       (let [d (get ifaces (second p))]
         (cond
           (nil? d)
           {:class :refused :witness path :tag (second p)
            :why [(str "opaque/FFI value `" (second p) "`" (path-str path)
                       " has no trusted declaration")
                  "promotion to unrestricted is DENIED rather than assumed"]}
           (and (false? (:perturb.share/aliases-observe-mutation? d))
                (false? (:perturb.share/duplication-duplicates-authority? d))
                (false? (:perturb.share/discard-leaks-obligation? d)))
           {:class :shareable :witness path :tag (second p)
            :why [(str "opaque `" (second p) "` is promoted by a trusted declaration")
                  (str "trusted by " (:perturb.share/trusted-by d))]}
           :else
           {:class :mixed :witness path :tag (second p)
            :why (vec (concat
                        [(str "opaque `" (second p) "`" (path-str path)
                              " is declared authority-bearing:")]
                        (remove nil?
                                [(when (true? (:perturb.share/aliases-observe-mutation? d))
                                   "  aliases CAN observe mutation")
                                 (when (true? (:perturb.share/duplication-duplicates-authority? d))
                                   "  duplication DOES duplicate authority")
                                 (when (true? (:perturb.share/discard-leaks-obligation? d))
                                   "  discard DOES leak an obligation")])))}))

       (= :sealed h)
       (let [tag (second p)
             d   (get ifaces tag)
             inner (nth p 2)]
         (cond
           (nil? d)
           {:class :refused :witness path :tag tag
            :why [(str "seal `" tag "`" (path-str path) " has no declaration")]}
           (seq (interface-faults d))
           {:class :refused :witness path :tag tag
            :why (vec (concat [(str "seal `" tag "` is REFUSED where it is written:")]
                              (map (fn [f] (str "  " (name (:kind f)))) (interface-faults d))))}
           (and (false? (:perturb.share/aliases-observe-mutation? d))
                (false? (:perturb.share/duplication-duplicates-authority? d))
                (false? (:perturb.share/discard-leaks-obligation? d)))
           {:class :shareable :witness path :tag tag
            :why [(str "sealed behind `" tag "`, an observationally immutable interface")
                  (str "trusted by " (:perturb.share/trusted-by d))
                  "the payload is NOT descended into: that is what the seal buys,"
                  "and the seal rule above is what makes it safe to buy"]}
           :else
           (let [in (classify ifaces inner (conj path 0))]
             {:class (:class in) :witness (:witness in) :cap (:cap in)
              :why (vec (concat [(str "seal `" tag "` does not promote, so the payload decides:")]
                                (:why in)))})))

       :else
       (let [cs (children p)
             ds (map (fn [i] (classify ifaces (nth cs i) (conj path i)))
                     (range (count cs)))
             w  (reduce worse
                        {:class :shareable :witness path
                         :why [(str "every " (component-word h)
                                    " of this " (name h) " is unrestricted")]}
                        ds)]
         (if (= :shareable (:class w))
           {:class :shareable :witness path
            :why [(str "every " (component-word h) " of this " (name h)
                       " is unrestricted, so it is")]}
           {:class (:class w) :witness (:witness w) :cap (:cap w) :tag (:tag w)
            :why (vec (concat
                        [(str "this " (name h) " is " (name (:class w))
                              " because one " (component-word h) " is:")]
                        (:why w)))}))))))

(defn decision-lines
  "A classification, as a diagnostic prints it."
  [p d]
  (vec (concat [(str "profile       " (render-profile p))
                (str "verdict       " (name (:class d))
                     (if (empty? (:witness d)) ""
                       (str "   witness " (pr-str (:witness d)))))]
               (map (fn [l] (str "  " l)) (:why d)))))

;; ---------------------------------------------------------------------------
;; THE HIGHER-ORDER QUESTION, WHICH THIS RULE DOES NOT ANSWER
;; ---------------------------------------------------------------------------
;;
;; A closure over a capability is MIXED. That is a classification, and it is all
;; the rule gives: non-shareable means "every duplication of this value is a
;; capability-tier event", not "this expression is illegal". Whether one
;; particular capture is a violation depends on what the closure's CONSUMER does
;; with it — call it once and drop it, or retain it, or run it on another thread.
;;
;; perturb has no notation for that. §4.6: "Higher-order capability passing has
;; no notation at all... `(f c)` cannot be annotated because the callee is a
;; parameter." So there is exactly one case this can decide without inventing
;; one, and it is decided from a declaration perturb ALREADY has:
;;
;;   the consumer introduces CONCURRENCY, and the capability's declared
;;   `:perturb.cap/contention` is `:thread-confined`.
;;
;; Then the closure carries the route across a thread boundary while the
;; original name is still live on this one, and the declaration itself says that
;; is not allowed. Decided, on the declaration, with no model of the body.
;;
;; Every other consumer is REFUSED — the diagnostic stands, and the reason it
;; stands is that the callee's retention and arity contract is undeclared. That
;; is a refusal with a named missing declaration, not a guess in either
;; direction, and it is the honest answer for `(thrown-by #(... conn ...))`.

(def concurrency-introducers
  "Consumers that run a closure somewhere other than here. Named, not inferred:
  this is a list, and a list is exactly as complete as it is written."
  '#{clojure.core/future clojure.core/future-call clojure.core/pmap
     clojure.core/pcalls clojure.core/pvalues clojure.core/send
     clojure.core/send-off clojure.core/agent})

(defn capture-disposition
  "Given the consumer a closure is handed to and the capability's declared
  contention, say whether the rule DECIDES this capture or REFUSES it.

  `m` is {:consumer sym-or-nil :contention kw :cap sym}."
  [m]
  (let [consumer (:consumer m)
        cont     (:contention m)]
    (cond
      (and consumer (contains? concurrency-introducers consumer)
           (= :thread-confined cont))
      {:decided? true :ground :concurrency-introducer
       :why [(str "the closure is handed to " consumer ", which runs it elsewhere,")
             (str "and " (:cap m) " declares :perturb.cap/contention "
                  ":thread-confined.")
             "The route crosses a thread boundary while the original name is"
             "still live here. DECIDED on the declaration, with no model of"
             "what the closure body does."]}

      (and consumer (contains? concurrency-introducers consumer))
      {:decided? false :ground :contention-undeclared
       :why [(str "the closure is handed to " consumer ", which runs it elsewhere,")
             (str "but " (:cap m) " declares :perturb.cap/contention " (pr-str cont))
             "and this rule has no account of a capability that survives a fork."]
       :needs "a contention axis with more than one inhabited value (I20)"}

      (nil? consumer)
      {:decided? false :ground :consumer-unknown
       :why ["the closure is not in argument position of a named call, so there"
             "is no consumer to ask about at all."]
       :needs "the shape of the expression the closure occurs in"}

      :else
      {:decided? false :ground :higher-order-undeclared
       :why [(str "the closure is handed to " consumer ", which declares no")
             "higher-order contract: whether it calls the closure once and drops"
             "it, retains it, or copies it is not written down anywhere."
             "The classification stands (the closure is MIXED) and the"
             "diagnostic stands; what is REFUSED is the question of whether"
             "this particular capture is a violation."]
       :needs "§4.6's missing notation for higher-order capability passing"})))
