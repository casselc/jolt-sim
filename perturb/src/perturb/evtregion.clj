(ns perturb.evtregion
  "DRIVER B', which is `perturb.evt`'s driver B with the connection table
  replaced by a `perturb.region/Region` and NOTHING ELSE CHANGED.

  THE EXPERIMENT. `PROGRESSIVE-FORMALISM-DESIGN.md` §3.4(d) claims that the
  cell architecture did not fail — that its RELOCATION TARGET was
  unexpressible, and that regions are the expression. Driver B is rejected
  function by function because a map from a runtime id to a ServerConn, that
  grows, dispatched by a value the application chose, is E24's four shapes at
  once. This namespace is the same driver over a region, and the prediction
  written into the design was:

    driver B's rejected functions move from REJECTED to ACCEPTED at
    :monitored, the emitted octets stay identical to driver B's, and a
    leaked connection is reported as a non-empty region exit WITH A COUNT
    rather than as a name the checker cannot track.

  `perturb.regioncheck` runs it and prints what actually happened. Driver B is
  NOT modified and NOT deleted: house style keeps the refuted thing visible, and
  the two drivers are only comparable if both are still here.

  WHAT IS DELIBERATELY THE SAME. The application (`perturb.evtapp`), the event
  and effect vocabulary, the round structure, the fixtures, and the helper
  functions — this namespace calls `perturb.evt`'s own `request-event`,
  `open-events`, `effect-octets` and `step-events` rather than copying them, so
  that no difference in the octets can come from a helper.

  WHAT IS DELIBERATELY DIFFERENT. One thing: `(assoc t i c)` becomes
  `(region/put-* r i c)` and `(get t i)` becomes `(region/take-* r i)`.

  THE ONE PLACE THE SHAPE FORCED A CHANGE, RECORDED BECAUSE IT IS A COST.
  `take` needs to name the state it expects, and `:produces` is static, so
  `apply-effect` has to ASK (`region/state-of`) and branch. Driver B does not
  ask, because driver B does not know there is a state. The branch is the
  notational cost of the region and it is written out rather than hidden."
  (:require [perturb.http :as h]
            [perturb.octet :as o]
            [perturb.cap :as cap]
            [perturb.region :as region]
            [perturb.evt :as evt]))

(defn accept-into-region
  "Accept `n` connections into a region. -> [listener region].

  DRIVER B's `accept-into-table` DREW TWO DIAGNOSTICS HERE — `no-signature`,
  because `clojure.core/assoc` declares no capability signature, and `dangling`,
  because the connection was then never consumed by anything the checker could
  see. `region/put-reading!` declares `:consumes` for both the region and the
  connection, so there is no unannotated callee and nothing goes out of scope
  still holding a capability."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/Listener  :state :listening :arg 0}
                               {:cap 'perturb.region/Region  :state :open      :arg 1}]
                    :produces [{:cap 'perturb.http/Listener :state :listening :at [0]}
                               {:cap 'perturb.region/Region :state :open      :at [1]}]}}
  [l0 r0 n]
  (loop [l l0 r r0 i 0]
    (if (>= i n)
      [l r]
      (let [a  (h/accept l)
            l1 (first a)
            c  (second a)
            r1 (region/put-reading! r i c)]
        (recur l1 r1 (inc i))))))

(cap/annotate-op! (var accept-into-region)
                  (:perturb.cap/op (meta (var accept-into-region))))

(defn read-round
  "Read ONE request from every member still in the region, in id order.
  -> [region' events].

  DRIVER B's `read-round` DREW `untracked-consume`: `(get t i)` is opaque, so
  `read-request` was handed something that was not a tracked capability of any
  type. `region/take-reading` PRODUCES a ServerConn@:reading, so the argument
  arrives tracked and in a state, and the typestate axis is switched back on."
  {:perturb.cap/op {:consumes [{:cap 'perturb.region/Region :state :open :arg 0}]
                    :produces [{:cap 'perturb.region/Region :state :open :at [0]}]}}
  [r0 n]
  (loop [i 0 r r0 evs []]
    (if (>= i n)
      [r evs]
      (if (not (region/member? r i))
        (recur (inc i) r evs)
        (let [tk (region/take-reading r i)
              r1 (first tk)
              c  (second tk)
              rr (h/read-request c)
              c1 (first rr)
              req (second rr)
              r2 (region/put-responding! r1 i c1)]
          (recur (inc i) r2 (conj evs (evt/request-event i req))))))))

(cap/annotate-op! (var read-round) (:perturb.cap/op (meta (var read-round))))

(defn respond-effect
  "Perform one `[:respond id …]` against the region. -> region'.

  Split out of `apply-effect` so that each operation consumes a member at ONE
  statically named state. Driver B did not have to split, because driver B did
  not have to name a state at all."
  {:perturb.cap/op {:consumes [{:cap 'perturb.region/Region :state :open :arg 0}]
                    :produces [{:cap 'perturb.region/Region :state :open}]}}
  [r e]
  (let [id (nth e 1)
        tk (region/take-responding r id)
        r1 (first tk)
        c  (second tk)
        c1 (h/respond! c (evt/effect-octets e))]
    (region/put-reading! r1 id c1)))

(cap/annotate-op! (var respond-effect) (:perturb.cap/op (meta (var respond-effect))))

(defn close-effect-reading
  "Perform one `[:close id]` on a member the region holds at :reading. -> region'.
  The member is consumed to `:closed` and NOT put back, which is how a member
  leaves a region for good."
  {:perturb.cap/op {:consumes [{:cap 'perturb.region/Region :state :open :arg 0}]
                    :produces [{:cap 'perturb.region/Region :state :open}]}}
  [r id]
  (let [tk (region/take-reading r id)
        r1 (first tk)
        c  (second tk)
        c1 (h/close-conn! c)]
    r1))

(cap/annotate-op! (var close-effect-reading)
                  (:perturb.cap/op (meta (var close-effect-reading))))

(defn close-effect-responding
  "Perform one `[:close id]` on a member the region holds at :responding."
  {:perturb.cap/op {:consumes [{:cap 'perturb.region/Region :state :open :arg 0}]
                    :produces [{:cap 'perturb.region/Region :state :open}]}}
  [r id]
  (let [tk (region/take-responding r id)
        r1 (first tk)
        c  (second tk)
        c1 (h/close-conn! c)]
    r1))

(cap/annotate-op! (var close-effect-responding)
                  (:perturb.cap/op (meta (var close-effect-responding))))

(defn apply-effect
  "Perform one effect against the region. -> region'.

  THIS IS THE FUNCTION THE HYPOTHESIS NEEDS. It is still the only place where
  application data selects a capability — `id` comes from the app — and that has
  not changed. What has changed is that selecting a member is an operation with
  a signature instead of a map lookup.

  THE BRANCH ON `state-of` IS THE COST. `:produces` is static, so a close has to
  know which state its member is in before it takes it. Driver B closes from
  whatever state without asking, which is cheaper to write and is exactly the
  information the checker was missing."
  {:perturb.cap/op {:consumes [{:cap 'perturb.region/Region :state :open :arg 0}]
                    :produces [{:cap 'perturb.region/Region :state :open}]}}
  [r e]
  (let [tag (nth e 0)
        id  (nth e 1)]
    (if (not (region/member? r id))
      (region/skip r)
      (if (= :respond tag)
        (respond-effect r e)
        (if (= :close tag)
          (if (= :responding (region/state-of r id))
            (close-effect-responding r id)
            (close-effect-reading r id))
          (region/skip r))))))

(cap/annotate-op! (var apply-effect) (:perturb.cap/op (meta (var apply-effect))))

(defn apply-effects
  "Perform every effect in order. -> region'."
  {:perturb.cap/op {:consumes [{:cap 'perturb.region/Region :state :open :arg 0}]
                    :produces [{:cap 'perturb.region/Region :state :open}]}}
  [r0 fxs]
  (loop [i 0 r r0]
    (if (>= i (count fxs))
      r
      (recur (inc i) (apply-effect r (nth fxs i))))))

(cap/annotate-op! (var apply-effects) (:perturb.cap/op (meta (var apply-effects))))

(defn close-region-members
  "Close every member still in the region. -> region'."
  {:perturb.cap/op {:consumes [{:cap 'perturb.region/Region :state :open :arg 0}]
                    :produces [{:cap 'perturb.region/Region :state :open}]}}
  [r0 n]
  (loop [i 0 r r0]
    (if (>= i n)
      r
      (if (not (region/member? r i))
        (recur (inc i) (region/skip r))
        (if (= :responding (region/state-of r i))
          (recur (inc i) (close-effect-responding r i))
          (recur (inc i) (close-effect-reading r i)))))))

(cap/annotate-op! (var close-region-members)
                  (:perturb.cap/op (meta (var close-region-members))))

(defn serve-region-with-listener
  "Driver B''s body. Same shape as `perturb.evt/serve-table-with-listener`:
  accept, then `rounds` of read / step / apply, then close what is left."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/Listener :state :listening :arg 0}]
                    :produces [{:cap 'perturb.http/Listener :state :closed}]}}
  [l0 nconn rounds state0 step on-violation]
  (let [r0 (region/open :perturb.evtregion/conns nconn on-violation)
        ar (accept-into-region l0 r0 nconn)
        l1 (first ar)
        ra (second ar)
        s0 (first (evt/step-events state0 (evt/open-events nconn) step))
        rr (loop [r ra s s0 i rounds]
             (if (<= i 0)
               [r s]
               (let [rd  (read-round r nconn)
                     r1  (first rd)
                     evs (second rd)
                     sr  (evt/step-events s evs step)
                     s1  (first sr)
                     fxs (second sr)
                     r2  (apply-effects r1 fxs)]
                 (recur r2 s1 (dec i)))))
        r9 (first rr)
        rz (close-region-members r9 nconn)
        rc (region/close! rz)]
    (h/shutdown! l1)))

(cap/annotate-op! (var serve-region-with-listener)
                  (:perturb.cap/op (meta (var serve-region-with-listener))))

(defn serve-region
  "Driver B', whole."
  [host port nconn rounds state0 step on-violation]
  (let [l  (h/listen host port)
        l2 (serve-region-with-listener l nconn rounds state0 step on-violation)]
    :served))

;; ===========================================================================
;; THE NEGATIVE CONTROL
;; ===========================================================================

(defn leaks-one-connection
  "MUST BE CAUGHT, AND THE POINT IS *HOW*. Accepts two connections, closes one,
  and closes the region with the other still in it.

  Driver B's map cannot say anything about this: the connection became opaque
  inside `accept-into-table`, and the checker's last word on it was
  `dangling` at a source location that has nothing to do with the leak. The
  region's last word is a COUNT AND A KEY at `close!`, which is less than an
  identity and more than nothing — §3.2's stated trade, executed."
  {:perturb.cap/op {:consumes [{:cap 'perturb.http/Listener :state :listening :arg 0}]
                    :produces [{:cap 'perturb.http/Listener :state :closed}]}}
  [l0 on-violation]
  (let [r0 (region/open :perturb.evtregion/leaky 2 on-violation)
        ar (accept-into-region l0 r0 2)
        l1 (first ar)
        ra (second ar)
        r1 (close-effect-reading ra 0)
        r2 (region/close! r1)]
    (h/shutdown! l1)))

(cap/annotate-op! (var leaks-one-connection)
                  (:perturb.cap/op (meta (var leaks-one-connection))))
