(ns perturb.tcpcap
  "THE SECOND MEASUREMENT, AND THE FIRST AGAINST A LIBRARY PERTURB DOES NOT OWN.

  PERTURB-DESIGN §5 ladder step 1a: the `casselc/jolt-tcp` CLIENT connection.
  E26 finding 4 split §5's step 1 in two because the original step cited the
  jolt-tcp README's *server handler* contract, and `teensyp.server`'s connection
  table is all four E24-forbidden shapes at once. The client is the reachable
  half: `send-all!`, `receive-into!`, `close!` and `shutdown-write!` are real
  vars taking the connection at argument 0, and the lifecycle atom at
  `client.clj:516` is already a typestate product.

  This namespace declares a capability for that connection FROM OUTSIDE.
  `perturb.streamcap` is the pattern and the difference is the point:
  `perturb.stream` was a port living in perturb's own tree, written by an agent
  who had not read the rules. `teensyp.client` is a library on a branch of
  somebody else's repository, written for reasons that have nothing to do with
  this project, and NOTHING HERE MAY CHANGE IT. The library is the artifact.

  FIVE DECLARATIONS, NOT ONE. §5 step 1a exists to force one collision:
  `jolt-net`'s `close!` is idempotent by design — `handle.clj:8-13` justifies it
  as the fix for aggressive descriptor reuse, and jolt-tcp's own loopback test
  asserts `(is (false? (client/close! connection)))`. Under `:linearity :once`
  with a terminal state, perturb must statically reject the library's own
  documented contract. One declaration cannot settle who is right, so this
  namespace ships four and `perturb.tcpcheck` runs all four:

    1  THE FAITHFUL MACHINE. Five states multiplied out of the phase atom,
       every state-changing operation as :consumes + :produces. It rejects
       heavily, and its rejections MASK the collision: the first in-place
       transition kills the name six lines before the double close.
    2  PERTURB'S POSITION, ISOLATED. The largest sub-machine with no in-place
       transition — two states, and the destructor is the only operation that
       advances it. This is where the collision appears alone.
    3  THE LIBRARY'S POSITION. `close!` only borrows, so calling it twice is
       unremarkable — and nothing reaches a terminal state, so every program
       leaks.
    4  THE ENCODING NEITHER CAN WRITE. `close!` consumes at argument 0 and
       produces BACK AT argument 0. `:arg` on a `:produces` entry WAS not part
       of the language — it was accepted and silently ignored — and what the
       checker did with it was the measurement, not a bug report.
    5  THE ANSWER, ADDED AFTER E33. The same machine as variant 1, said under
       the three-notion model: `:result` labels on the transitions, `:arg 0` on
       the produced entries so the NAME survives, `:state` omitted so the
       DESTINATION is read off the machine per source, and `:closed` as an
       ABSORBING terminal state so the obligation is discharged while the
       observers stay legal. Variants 1-4 are preserved unchanged, because they
       are the measurement E30 recorded; this one is what the measurement was
       for.

  THIS IS NOT A GATE. It records no expectations. Whatever the verdicts are is
  what they are, and they are evidence about THE RULES, not about jolt-tcp."
  (:require [perturb.cap :as cap]))

;; ---------------------------------------------------------------------------
;; THE TYPESTATE, DERIVED FROM THE PHASE ATOM — NOT INVENTED
;; ---------------------------------------------------------------------------
;;
;; `teensyp/client.clj:516` (in `build-connection!`):
;;
;;     (atom {:phase :open :read-eof? false :write-shutdown? false})
;;
;; and three writers, all of them in the same file:
;;
;;     begin-close!      :phase :open -> :closing         (compare-and-set!)
;;     close-resources!  :phase       -> :closed          (in a `finally`)
;;     receive-fn        :read-eof?       false -> true   only while :phase :open
;;     shutdown-write-fn :write-shutdown? false -> true   only while :phase :open
;;
;; That is a PRODUCT of three components: a 3-valued phase and two monotone
;; bits. Both bits are guarded by `(if (= :open (:phase %)) … %)`, so they
;; freeze when close begins; the reachable space is therefore
;;
;;     {:open} x {r?} x {w?}   4 states
;;   + {:closing,:closed} x (whatever the bits were)
;;
;; PERTURB HAS NO PRODUCT TYPESTATE. `:perturb.cap/typestate` is a flat list of
;; keywords with no orthogonal regions, so the product has to be multiplied out
;; by hand. Four of the five states below ARE that multiplication.
;;
;; WHAT COULD NOT BE EXPRESSED, IN ORDER OF HOW MUCH IT COSTS:
;;
;;  1. `:closing` vs `:closed`. The library really has both — `begin-close!`
;;     sets `:closing`, and `close-resources!` sets `:closed` in a `finally`
;;     after the pollers and the socket are closed. Nothing a single thread can
;;     observe tells them apart (`closed?` is `(not= :open phase)` for both).
;;     They differ only to a CONCURRENT thread, which is the exact window the
;;     idempotent close exists to arbitrate. Perturb has no vocabulary for a
;;     state distinguishable only by another thread, so the two are merged into
;;     `:closed` here. That is the contention axis (INHERITED I20) deleting a
;;     state, not a modelling choice.
;;
;;  2. The two per-direction gates, `{:held? :waiters}`, one for read and one
;;     for write. They are a second and third machine per connection and they
;;     are what makes "one reader and one writer may run concurrently" true.
;;     Nothing here describes them at all.
;;
;;  3. `:read-eof?` is set BY THE PEER, not by the caller. `receive-fn` flips it
;;     when the transport reports EOF. Every edge in perturb's language is
;;     labelled by an `:op` — a thing the program does — so an environment-caused
;;     transition has to be smuggled in as a run-time-chosen destination of the
;;     operation that observed it. E26 finding 7's sum `:to` is what makes that
;;     sayable at all, and it is being used here for a purpose it was not
;;     designed for: not "this operation has three outcomes" but "the peer
;;     decided and the operation is where the program finds out".

(def cap-name 'teensyp.client/Connection)

(def open-states
  "Every state in which the connection's phase is still `:open`."
  [:open :read-eof :write-shut :half-closed])

;; ---------------------------------------------------------------------------
;; THE MACHINE
;; ---------------------------------------------------------------------------
;;
;; Written the way the library's code actually behaves, before any concession to
;; what the checker can read. `perturb.tcpcheck` reports what the concessions
;; would have to be.

(def transitions
  [;; --- minting -------------------------------------------------------------
   ;; `connect-endpoint` is where `build-connection!` is reached; `connect`
   ;; delegates to it. E19: a creating edge declares `:from nil`.
   {:op 'teensyp.client/connect-endpoint :from nil :to :open}
   {:op 'teensyp.client/connect          :from nil :to :open}

   ;; --- the peer half-closing under a read ----------------------------------
   ;; `receive-fn` sets `:read-eof?` when `receive-loop!` returns nil. Which of
   ;; the two destinations a call takes is decided by the PEER.
   {:op 'teensyp.client/receive-into!    :from :open       :to [:open :read-eof]}
   {:op 'teensyp.client/receive-into!    :from :write-shut :to [:write-shut :half-closed]}
   {:op 'teensyp.client/receive-at-most! :from :open       :to [:open :read-eof]}
   {:op 'teensyp.client/receive-at-most! :from :write-shut :to [:write-shut :half-closed]}

   ;; --- half-close, WHICH IS ALSO IDEMPOTENT --------------------------------
   ;; `shutdown-write!` "returns true for the first successful half-close and
   ;; false when already half-closed" — the same contract as `close!`, one level
   ;; down. It is expressible, and `close!` is not, and the difference between
   ;; them is the whole finding: a self-loop on a NON-TERMINAL state is an
   ;; ordinary edge. Idempotence is not what `:linearity :once` rejects.
   ;; Idempotence AT A TERMINAL STATE is.
   {:op 'teensyp.client/shutdown-write!  :from :open        :to :write-shut}
   {:op 'teensyp.client/shutdown-write!  :from :read-eof    :to :half-closed}
   {:op 'teensyp.client/shutdown-write!  :from :write-shut  :to :write-shut}
   {:op 'teensyp.client/shutdown-write!  :from :half-closed :to :half-closed}

   ;; --- close ---------------------------------------------------------------
   ;; From any live state, to the terminal one …
   {:op 'teensyp.client/close! :from open-states :to :closed}
   ;; … AND FROM THE TERMINAL ONE BACK TO ITSELF, which is the library's
   ;; documented contract: "Idempotent: returns true only for the call that
   ;; began close." This edge is a legal thing to WRITE — `perturb.cap`
   ;; validates only that the four axes are present — and `perturb.tcpcheck`
   ;; measures what happens to it downstream.
   {:op 'teensyp.client/close! :from :closed :to :closed}])

(def discriminators
  ;; E26 finding 7's `:perturb.cap/discriminator`: a predicate applied to the
  ;; capability that narrows a sum at an `if`. `closed?` is a real one and it is
  ;; declared here because it exists — but note what it CANNOT do. The sums this
  ;; machine actually produces are `[:open :read-eof]` and
  ;; `[:write-shut :half-closed]`, and the library's own way of resolving them is
  ;; `(:read-eof? (client/connection-info c))` — a key read on the map RETURNED
  ;; by an operation, not a predicate applied to the capability. A discriminator
  ;; must be `{:op … :arg 0 :true … :false …}`, so the one discriminator that
  ;; can be declared partitions the one axis that never needs discriminating.
  [{:op 'teensyp.client/closed? :arg 0
    :true :closed
    :false [:open :read-eof :write-shut :half-closed]}])

(def representation
  "The names inside the capability's implementation, whose bodies stop being
  checked. E18: the metric is LINES BELOW THE BOUNDARY, not names in a list.

  E26 finding 4 predicted `the closure-map indirection would put those four
  names on :perturb.cap/representation — E18's gameable list, but only four
  names`. Measured, it is SEVEN names here (the four transition operations are
  already axioms by being primitives, so they do not need to appear), and
  `build-connection!` alone is 136 of `teensyp.client`'s 773 lines. Every one of
  them is here for the same reason: it reaches into the connection's concrete
  map — `(get connection send-fn-key)`, `(get x connection-marker)` — which is
  below the abstraction the modes describe."
  ['teensyp.client/build-connection!    ; constructs the map AND the phase atom
   'teensyp.client/connection?          ; reads the marker key
   'teensyp.client/require-connection!  ; calls connection? and hands it back
   'teensyp.client/send-all!            ; (get connection ::send-fn)
   'teensyp.client/closed?              ; (get connection ::closed-fn)
   'teensyp.client/connection-info      ; (get connection ::info-fn)
   ;; receive-at-most! is a declared transition too, so it is an axiom twice
   ;; over; it is named here because the REASON its body is unchecked is the
   ;; representation, not the transition.
   'teensyp.client/receive-at-most!])

(def ^:private C cap-name)

(defn- axes
  "The four axes, identical in every variant below. Only the MACHINE and the
  annotations change from round to round; §1.2's axes are a property of the
  resource and the resource does not change."
  []
  {:perturb.cap/name       cap-name
   :perturb.cap/uniqueness :unique
   ;; `build-connection!` mints one map per established socket and the map is
   ;; not copyable in any way the library exposes.
   :perturb.cap/linearity  :once
   ;; KNOWN TO BE FALSE OF THE ARTIFACT, AND WRITTEN ANYWAY.
   ;; `teensyp.client`'s own docstring: "`close!` may run from any thread", "One
   ;; reader and one writer may run concurrently" — and the library's loopback
   ;; test really does run `receive-into!` on a `future` while `send-all!` runs
   ;; on the test thread. §1.2's contention axis has no other value to write
   ;; (INHERITED I20; §4.6, "the contention axis has never been exercised").
   ;; Omitting it is not an option: `cap/capability` rejects a declaration
   ;; missing an axis, so the axis would vanish from the report rather than be
   ;; recorded as unsatisfiable. It is written as a falsehood, in the open.
   :perturb.cap/contention :thread-confined
   ;; The connection owns two pollers and a socket, and `close-resources!`
   ;; closes all three. Nothing in the language can say that.
   :perturb.cap/locality   :dropped-by-design
   :perturb.cap/representation representation})

;; ---------------------------------------------------------------------------
;; VARIANT 1 — THE FAITHFUL MACHINE
;; ---------------------------------------------------------------------------

(def faithful-declaration
  (cap/capability
    (assoc (axes)
           :perturb.cap/doc
           "The lifecycle as teensyp.client actually implements it, multiplied out
            of the phase atom. Nothing here is a concession to the checker."
           :perturb.cap/typestate
           {:states   [:open :read-eof :write-shut :half-closed :closed]
            :initial  :open
            :terminal [:closed]
            :transitions transitions}
           :perturb.cap/discriminator discriminators)))

(def faithful-ops
  "Every state-changing operation as `:consumes` + `:produces`, which is the
  only way §1.2 has of advancing a machine — and which asserts, falsely, that
  each of them RETURNS the connection. `receive-into!` returns a byte count,
  `shutdown-write!` and `close!` return booleans. Nothing catches the lie: a
  declared transition's body is an axiom, so the annotation is read only at CALL
  SITES, where the checker believes the count is a connection."
  {'teensyp.client/connect-endpoint {:produces [{:cap C :state :open}]}
   'teensyp.client/connect          {:produces [{:cap C :state :open}]}

   'teensyp.client/receive-into!
   {:consumes [{:cap C :state :open :arg 0}]
    :produces [{:cap C :state [:open :read-eof]}]}

   'teensyp.client/receive-at-most!
   {:consumes [{:cap C :state :open :arg 0}]
    :produces [{:cap C :state [:open :read-eof]}]}

   'teensyp.client/shutdown-write!
   {:consumes [{:cap C :state :open :arg 0}]
    :produces [{:cap C :state :write-shut}]}

   'teensyp.client/close!
   {:consumes [{:cap C :state open-states :arg 0}]
    :produces []}

   ;; THE GUARD THAT IS EXPRESSIBLE, AND IS A REAL PROTOCOL PROPERTY.
   ;; `send-fn` throws `write-shutdown-ex` once `:write-shutdown?` is set and
   ;; `closed-ex` once the phase leaves `:open`. As a borrow with a state set
   ;; that is checked statically and costs nothing.
   'teensyp.client/send-all!
   {:borrows [{:cap C :state [:open :read-eof] :arg 0}]}

   ;; Pure observers, legal in every state including the terminal one.
   'teensyp.client/connection-info {:borrows [{:cap C :state :any :arg 0}]}
   'teensyp.client/closed?         {:borrows [{:cap C :state :any :arg 0}]}
   'teensyp.client/connection?     {:borrows [{:cap C :state :any :arg 0}]}})

;; ---------------------------------------------------------------------------
;; VARIANTS 2-4 — THE LARGEST SUB-MACHINE THE LANGUAGE CAN STATE
;; ---------------------------------------------------------------------------
;;
;; Variant 1's rejections are dominated by ONE cause, and it masks the collision
;; §5 step 1a was built to force: the first in-place transition kills the name,
;; so by the time the program reaches `close!` the connection has been dead for
;; six lines and the double close never gets a diagnostic of its own.
;;
;; So variants 2-4 declare the largest sub-machine that can be stated without
;; that cause firing: TWO states, and the only operation that advances the
;; machine is the destructor — which is the one operation whose return value the
;; caller does not need, so `:consumes` + `:produces []` is not a lie about it.
;;
;; WHAT THAT COSTS, EXACTLY: `:read-eof`, `:write-shut` and `:half-closed` are
;; gone, so HALF-CLOSE — the thing `shutdown-write!` exists for, and the same
;; feature E23 recorded `perturb.wire` as not having — is outside the model, and
;; with it the statically-checkable guard "no `send-all!` after
;; `shutdown-write!`". Three of the library's five real states are the price of
;; getting the destructor into the machine at all.

(def reduced-states
  {:states   [:open :closed]
   :initial  :open
   :terminal [:closed]})

(def ^:private mint-edges
  [{:op 'teensyp.client/connect-endpoint :from nil :to :open}
   {:op 'teensyp.client/connect          :from nil :to :open}])

(defn- reduced-declaration
  [close-edges]
  (cap/capability
    (assoc (axes)
           :perturb.cap/doc
           "The largest sub-machine of teensyp.client's lifecycle the declaration
            language can state without an in-place transition."
           :perturb.cap/typestate
           (assoc reduced-states :transitions (into mint-edges close-edges))
           :perturb.cap/discriminator discriminators)))

(defn- reduced-ops
  "Everything but `close!` becomes a borrow at `:open`. Nothing lies about a
  return value any more, and nothing advances the machine either."
  [close-ann]
  {'teensyp.client/connect-endpoint {:produces [{:cap C :state :open}]}
   'teensyp.client/connect          {:produces [{:cap C :state :open}]}
   'teensyp.client/receive-into!    {:borrows [{:cap C :state :open :arg 0}]}
   'teensyp.client/receive-at-most! {:borrows [{:cap C :state :open :arg 0}]}
   'teensyp.client/shutdown-write!  {:borrows [{:cap C :state :open :arg 0}]}
   'teensyp.client/send-all!        {:borrows [{:cap C :state :open :arg 0}]}
   'teensyp.client/connection-info  {:borrows [{:cap C :state :any :arg 0}]}
   'teensyp.client/closed?          {:borrows [{:cap C :state :any :arg 0}]}
   'teensyp.client/connection?      {:borrows [{:cap C :state :any :arg 0}]}
   'teensyp.client/close!           close-ann})

;; --- variant 2 — PERTURB'S POSITION, ISOLATED ------------------------------

(def linear-close-declaration
  (reduced-declaration [{:op 'teensyp.client/close! :from :open :to :closed}]))

(def linear-close-ops
  (reduced-ops {:consumes [{:cap C :state :open :arg 0}]
                :produces []}))

;; --- variant 3 — THE LIBRARY'S POSITION ------------------------------------
;;
;; "Idempotent: returns true only for the call that began close." To say that,
;; `close!` must stay legal after it has run, and the only annotation that
;; leaves a name alive is `:borrows`. A borrowed operation cannot be a declared
;; transition (its `:consumes` state would have to equal the edge's `:from` and
;; there is no `:consumes`), so the destructor leaves the machine entirely and
;; the machine has no edge into `:closed` at all.

(def idempotent-close-declaration (reduced-declaration []))

(def idempotent-close-ops
  (reduced-ops {:borrows [{:cap C :state :any :arg 0}]}))

;; --- variant 4 — THE ENCODING NEITHER POSITION CAN WRITE --------------------
;;
;; `:consumes` at argument 0 and `:produces` BACK AT ARGUMENT 0: the connection
;; does not move, it changes state where it stands. `:produces` entries are
;; positioned with `:at`, a path into the RESULT; `:arg` on a produced entry was
;; not part of the language. This variant exists to find out what the checker
;; does with a key it does not implement, which is a question about the rules.
;;
;; ITS ANSWER HAS CHANGED, DELIBERATELY, AND IT IS THE ONE VERDICT THIS WORK
;; MOVED. E30 recorded that the key drew no diagnostic, was silently ignored,
;; left the four `use-after-move` in place, and added a NEW `no-signature`
;; because the produced capability landed on the return value and
;; `clojure.core/true?` received a `Connection@:closed`. That is tally row 50 —
;; a declaration language accepting a key it does not implement — and E33's
;; instruction was to implement it or refuse it, because silently ignoring a
;; declared key is not an option. It is IMPLEMENTED, so this variant now reports
;;
;;   4 use-after-move -> 0        the name survives the close, which is what
;;                                `:arg 0` on the produced entry says
;;   1 no-signature   -> 0        nothing lands in the result, so nothing
;;                                downstream receives a Connection
;;   0 typestate      -> 1        `receive-at-most!` after the close, which
;;                                variant 4's TWO-STATE machine has no edge for
;;                                and the library's own test asserts throws
;;
;; The declaration below is unchanged from the one E30 ran; only what the
;; checker does with it has changed, which is the measurement being answered
;; rather than repeated.

;; The edge is declared `:from :any` so that the annotation and the declaration
;; agree at the DECLARATION level and the only thing this variant measures is
;; what the checker does with `:arg` on a produced entry. (`:from :any` covers
;; the idempotent self-loop without needing a second edge: `state-ok?` admits
;; `:any` from every state including `:closed`.)
(def in-place-close-declaration
  (reduced-declaration [{:op 'teensyp.client/close! :from :any :to :closed}]))

(def in-place-close-ops
  (reduced-ops {:consumes [{:cap C :state :any :arg 0}]
                :produces [{:cap C :state :closed :arg 0}]}))

;; ---------------------------------------------------------------------------
;; VARIANT 5 — THE DECLARATION THE THREE-NOTION MODEL CAN WRITE
;; ---------------------------------------------------------------------------
;;
;; Variants 1-4 are preserved EXACTLY as E30 ran them, because they are the
;; measurement. This one is the answer to it, and it is written against
;; PERTURB-DESIGN E33's correction rather than against `:linearity :once`:
;;
;;   > A stable shared handle, its current protocol state, and the obligation to
;;   > discharge the underlying resource are three different things.
;;
;; So it says all three separately, and it says nothing else that variant 1 did
;; not say. The MACHINE is variant 1's machine — five states, multiplied out of
;; `client.clj:516`'s phase atom, nothing deleted and nothing invented — with two
;; additions:
;;
;;   * `:result` LABELS. E33's primitive relation is
;;     `(capability, operation, source-state, result-label) -> destination +
;;     obligation delta`, and both round-2 surveys name it independently as the
;;     established syntax (Mungo/StMungo `Status open(): <OK: Open, ERROR: end>`).
;;     `close!` is `:won` from a live state and `:lost` from `:closed`, and BOTH
;;     land in `:closed` and BOTH discharge — so the two labels collapse, there
;;     is no sum, and the boolean is a RACE WITNESS rather than a second close.
;;     That is exactly what `handle.clj:8-13` documents and what jolt-tcp's own
;;     loopback test asserts.
;;   * THE `:closed` SELF-LOOP, which variant 1 also wrote. What has changed is
;;     downstream: `:closed` is now an ABSORBING TERMINAL STATE — obligation
;;     discharged, NAME STILL ALIVE, only the destructor and the observers legal
;;     — so the second `close!`, `closed?` and `connection-info` are all reached.
;;
;; And the ANNOTATIONS stop repeating the machine. Every state-changing operation
;; is `:consumes … :arg 0` + `:produces … :arg 0` with NO `:state` on either
;; side, which says three things at once and each of them separately:
;;
;;   the NAME survives   — `:produces … :arg 0` is an IN-PLACE produce; the
;;                         connection is a stable name over a mutable atom and
;;                         does not travel in the result. E30 finding 2's
;;                         12-of-23 bucket is this line;
;;   the STATE moves     — read off the machine at each call site from the state
;;                         the connection is actually in, so `shutdown-write!`'s
;;                         four sources with four destinations need ONE
;;                         annotation instead of contradicting three groups
;;                         (E30 finding 3's 6 diagnostics);
;;   the OBLIGATION      — derived from the destination, and therefore discharged
;;                         by `close!` from either source and by nothing else.
;;
;; IT ALSO STOPS LYING, WHICH E30 LISTED AS A FINDING OF ITS OWN. Variant 1 had
;; to declare that `receive-into!` and `close!` RETURN the connection, because
;; `:consumes`+`:produces` was the only way to advance a machine; they return an
;; integer and a boolean. `:arg 0` on the produced entry says the connection
;; stays where it is, so nothing here claims anything about a return value.
;;
;; WHAT IS STILL NOT SAYABLE, AND IS REPORTED RATHER THAN PAPERED OVER: the
;; `[:open :read-eof]` and `[:write-shut :half-closed]` sums are decided BY THE
;; PEER, and the library resolves them with `(:read-eof? (connection-info c))` —
;; a key read on a map returned by an operation, which no `:perturb.cap/
;; discriminator` can express. E30 finding 5 said so and it is still true.

(def correct-transitions
  [{:op 'teensyp.client/connect-endpoint :from nil :to :open}
   {:op 'teensyp.client/connect          :from nil :to :open}

   ;; The peer half-closing under a read. TWO RESULT LABELS PER SOURCE, and here
   ;; they do NOT collapse — `:data` and `:eof` have different destinations — so
   ;; this is a genuine sum and the fallback machinery applies to it, which is
   ;; E33's ordering: result labels first, sum + discriminator as the fallback.
   {:op 'teensyp.client/receive-into!    :from :open        :result :data :to :open}
   {:op 'teensyp.client/receive-into!    :from :open        :result :eof  :to :read-eof}
   {:op 'teensyp.client/receive-into!    :from :read-eof    :result :eof  :to :read-eof}
   {:op 'teensyp.client/receive-into!    :from :write-shut  :result :data :to :write-shut}
   {:op 'teensyp.client/receive-into!    :from :write-shut  :result :eof  :to :half-closed}
   {:op 'teensyp.client/receive-into!    :from :half-closed :result :eof  :to :half-closed}
   {:op 'teensyp.client/receive-at-most! :from :open        :result :data :to :open}
   {:op 'teensyp.client/receive-at-most! :from :open        :result :eof  :to :read-eof}
   {:op 'teensyp.client/receive-at-most! :from :read-eof    :result :eof  :to :read-eof}
   {:op 'teensyp.client/receive-at-most! :from :write-shut  :result :data :to :write-shut}
   {:op 'teensyp.client/receive-at-most! :from :write-shut  :result :eof  :to :half-closed}
   {:op 'teensyp.client/receive-at-most! :from :half-closed :result :eof  :to :half-closed}

   ;; Half-close, idempotent, with a SOURCE-DEPENDENT destination. Four sources,
   ;; four destinations, two labels. This is the shape E30 finding 3 measured as
   ;; six `annotation-inconsistent` diagnostics under one annotation that had to
   ;; name one pair.
   {:op 'teensyp.client/shutdown-write!  :from :open        :result :first :to :write-shut}
   {:op 'teensyp.client/shutdown-write!  :from :read-eof    :result :first :to :half-closed}
   {:op 'teensyp.client/shutdown-write!  :from :write-shut  :result :again :to :write-shut}
   {:op 'teensyp.client/shutdown-write!  :from :half-closed :result :again :to :half-closed}

   ;; THE COMPARE-AND-SET CLOSE. `:won` is the caller whose CAS succeeded and
   ;; `:lost` is the caller who found another had begun the close. One
   ;; destination, one obligation delta, so the labels collapse and the second
   ;; call is an ordinary self-loop on an absorbing terminal state.
   {:op 'teensyp.client/close! :from open-states :result :won  :to :closed}
   {:op 'teensyp.client/close! :from :closed     :result :lost :to :closed}])

(def correct-declaration
  (cap/capability
    (assoc (axes)
           :perturb.cap/doc
           "teensyp.client's lifecycle under E33's three-notion model: the name,
            the protocol state and the discharge obligation said separately."
           :perturb.cap/typestate
           {:states   [:open :read-eof :write-shut :half-closed :closed]
            :initial  :open
            :terminal [:closed]
            :transitions correct-transitions}
           :perturb.cap/discriminator discriminators)))

(def correct-ops
  "Nothing here writes a `:state` for an operation that is a transition, and
  nothing here claims a return value it does not have."
  {'teensyp.client/connect-endpoint {:produces [{:cap C :state :open}]}
   'teensyp.client/connect          {:produces [{:cap C :state :open}]}

   ;; IN PLACE, and the state read off the machine at each call site.
   'teensyp.client/receive-into!
   {:consumes [{:cap C :arg 0}] :produces [{:cap C :arg 0}]}
   'teensyp.client/receive-at-most!
   {:consumes [{:cap C :arg 0}] :produces [{:cap C :arg 0}]}
   'teensyp.client/shutdown-write!
   {:consumes [{:cap C :arg 0}] :produces [{:cap C :arg 0}]}
   'teensyp.client/close!
   {:consumes [{:cap C :arg 0}] :produces [{:cap C :arg 0}]}

   ;; THE GUARD THAT IS EXPRESSIBLE AND IS KEPT. `send-fn` throws once
   ;; `:write-shutdown?` is set and once the phase leaves `:open`, so a borrow
   ;; with a state set enforces `no send-all! after shutdown-write!` statically
   ;; and costs nothing. Keeping it is a decision: it is what makes the library's
   ;; own NEGATIVE test — `send-all!` after half-close, asserted to throw — draw
   ;; a `typestate`, which is the checker agreeing with the library about the
   ;; protocol and disagreeing about whether a test may provoke a violation.
   'teensyp.client/send-all!
   {:borrows [{:cap C :state [:open :read-eof] :arg 0}]}

   ;; Pure OBSERVERS, legal in every state including the absorbing terminal one.
   ;; They borrow, so they move nothing and — E33's side condition — re-acquire
   ;; nothing.
   'teensyp.client/connection-info {:borrows [{:cap C :state :any :arg 0}]}
   'teensyp.client/closed?         {:borrows [{:cap C :state :any :arg 0}]}
   'teensyp.client/connection?     {:borrows [{:cap C :state :any :arg 0}]}})

;; ---------------------------------------------------------------------------

(defn checker-input
  "A `cap/checker-input`-shaped map, assembled directly rather than through
  `cap/registry`.

  WHY NOT THE REGISTRY. `perturb.streamcap` calls `cap/declare-capability!` and
  `cap/annotate-op!` at load time because `perturb.stream` is on perturb's own
  classpath and its vars exist. `teensyp.client` is not and does not: it is
  compiled by `perturb.tcpcheck` at run time out of a working tree that is not a
  dependency of this project. `perturb.check/spec-from` takes exactly this map —
  it was split out of `spec` so the declaration-level rules could be run against
  fixtures that name no code — so nothing in the checker is bypassed, and the
  process-global registry is left untouched, which is what keeps running this
  from changing any other gate's specification."
  [decl ops]
  {:perturb.cap/declarations {cap-name decl}
   :perturb.cap/operations   ops
   :perturb.cap/ledger       []})

(def variants
  "The four declarations tcpcheck runs, in order."
  [{:label "1"
    :title "THE FAITHFUL MACHINE — five states, every transition in place"
    :decl faithful-declaration :ops faithful-ops}
   {:label "2"
    :title "PERTURB'S POSITION, ISOLATED — two states, `close!` CONSUMES"
    :decl linear-close-declaration :ops linear-close-ops}
   {:label "3"
    :title "THE LIBRARY'S POSITION — `close!` only BORROWS, and is idempotent"
    :decl idempotent-close-declaration :ops idempotent-close-ops}
   {:label "4"
    :title "THE ENCODING NEITHER CAN WRITE — `:produces` back at `:arg 0`"
    :decl in-place-close-declaration :ops in-place-close-ops}
   {:label "5"
    :title "THE THREE NOTIONS SEPARATED — result labels, in place, absorbing"
    :decl correct-declaration :ops correct-ops}])
