(ns perturb.corpus
  "The acceptance corpus for `perturb.check`. REAL perturb source, compiled by
  Jolt, checked from the IR the back end was handed — not a model, not a list of
  operation names.

  Every function here type-checks as ordinary Clojure and would run. None is
  called. The point is what the CHECKER says about each one, and `expectations`
  below records what it must say. A corpus entry that flips verdict is a
  regression in the checker or a change in the rule set; either way the gate
  fails.

  Intended rejections and intended acceptances are interleaved deliberately: the
  interesting pairs differ by one line."
  (:require [perturb.nrepl :as n]
            [perturb.cap :as cap]))

;; ===========================================================================
;; ACCEPT
;; ===========================================================================

(defn open-request-close
  "The straight-line correct path. Each operation consumes the connection and
  binds its successor; the last state is terminal."
  [host port code]
  (let [c  (n/open host port)
        c1 (n/request c {"op" "eval" "code" code})
        c2 (n/close! c1)]
    :done))

(defn shadowed-rebind
  "The same correct path written the way people actually write it — one name,
  rebound. Each binding occurrence is a DIFFERENT capability instance; `:local`
  cannot tell them apart, and the checker's own binding ids must."
  [host port code]
  (let [c (n/open host port)
        c (n/request c {"op" "eval" "code" code})
        c (n/close! c)]
    :done))

(defn both-arms-close
  "controlflow.py's `same-move-in-both-arms`: an agreed join carries the state
  forward soundly."
  [host port flag code]
  (let [c  (n/open host port)
        c1 (if flag (n/close! c) (n/close! c))]
    :done))

(defn loop-of-requests
  "controlflow.py's `loop-of-region-reads`, on a real driver shape: the body is
  environment-preserving at the back edge (active in, active out), so it
  iterates."
  [host port codes]
  (let [c0 (n/open host port)
        c1 (loop [c c0 fs codes]
             (if (empty? fs)
               c
               (recur (n/request c {"op" "eval" "code" (first fs)}) (rest fs))))
        c2 (n/close! c1)]
    :done))

(defn ping
  "A DERIVED operation: annotated, but not a transition of the declared machine,
  so the checker checks its body against its own annotation instead of believing
  it. It returns the connection BARE, which is the only result shape §1.2's
  unpositioned :produces can describe."
  {:perturb.cap/op {:consumes [{:cap 'perturb.nrepl/Connection :state :active}]
                    :produces [{:cap 'perturb.nrepl/Connection :state :active}]}}
  [c]
  (n/request c {"op" "eval" "code" "(+ 1 1)"}))

(cap/annotate-op! (var ping) (:perturb.cap/op (meta (var ping))))

(defn uses-ping
  "Composition works — when the helper returns the capability bare."
  [host port]
  (let [c  (n/open host port)
        c1 (ping c)
        c2 (n/close! c1)]
    :done))

;; ===========================================================================
;; REJECT
;; ===========================================================================

(defn use-after-close
  "INHERITED I16, verbatim: `(let [c (open …)] (close! c) (request c …))`
  compiles and runs on Jolt today. This is the program the checker exists to
  refuse."
  [host port]
  (let [c (n/open host port)]
    (n/close! c)
    (n/request c {"op" "eval" "code" "(+ 1 2)"})
    :done))

(defn double-close
  "Second close! from :closed — a typestate violation, not a linearity one,
  because the successor was bound."
  [host port]
  (let [c  (n/open host port)
        c1 (n/close! c)
        c2 (n/close! c1)]
    :done))

(defn use-after-move
  "E6 probe 2: capabilities bind affinely. `(let [d c] …)` is a MOVE, so `c` is
  dead afterwards even though nothing consumed it."
  [host port]
  (let [c  (n/open host port)
        d  c
        c1 (n/close! d)
        c2 (n/request c {"op" "eval" "code" "1"})]
    :done))

(defn dangling-connection
  "linearity :once plus a declared terminal state: a connection that never
  reaches :closed leaks at scope exit."
  [host port]
  (let [c (n/open host port)]
    :opened))

(defn shadowing-hides-a-leak
  "THE BINDING-IDENTITY CASE. The inner `c` shadows the outer one and is the one
  that gets closed. A checker keyed on the NAME `c` sees a close and accepts;
  the outer connection leaks. §2.1 predicted this from reading ir.clj — here it
  is on running code."
  [host port]
  (let [c (n/open host port)]
    (let [c (n/open host port)]
      (n/close! c)
      :done)))

(defn conditional-close
  "E6 probe 1's known usability risk, on a real connection: `if (flag) close(c)`
  leaves the capability maybe-moved, and \"maybe moved\" is not a mode."
  [host port flag]
  (let [c (n/open host port)]
    (if flag (n/close! c) nil)
    :done))

(defn conditional-close-then-use
  "The same shape §1.2 names verbatim — `if (c) { b = detach_result(b) };
  use(b)` — written with a connection. Rejected at the join, before the use."
  [host port flag]
  (let [c (n/open host port)]
    (if flag (n/close! c) nil)
    (n/request c {"op" "eval" "code" "1"})
    :done))

(defn loop-that-closes
  "controlflow.py's `loop-that-moves`: the back edge re-enters at :closed, so the
  body cannot run twice."
  [host port k]
  (let [c0 (n/open host port)]
    (loop [c c0 i k]
      (if (zero? i)
        :done
        (recur (n/close! c) (dec i))))))

(defn helper-without-a-signature
  "The shape `perturb.nrepl/clone-session` and `/eval-code` are written in: an
  ordinary function that takes a connection. Interprocedural flow is by
  annotation only, so the parameter is not a tracked capability and the
  operation has nothing legal to consume."
  [c]
  (n/request c {"op" "eval" "code" "1"}))

(defn ping-tuple
  "E13, on real code. Annotated exactly like `ping`, but it returns the pair the
  real client returns. §1.2's :produces cannot say WHICH position holds the
  capability, so the annotation cannot describe this function at all."
  {:perturb.cap/op {:consumes [{:cap 'perturb.nrepl/Connection :state :active}]
                    :produces [{:cap 'perturb.nrepl/Connection :state :active}]}}
  [c]
  (let [c1 (n/request c {"op" "eval" "code" "1"})]
    [c1 :pinged]))

(cap/annotate-op! (var ping-tuple) (:perturb.cap/op (meta (var ping-tuple))))

(defn capture-in-closure
  "A `linearity :once` capability closed over by a fn that may run any number of
  times."
  [host port codes]
  (let [c (n/open host port)]
    (map (fn [f] (n/request c {"op" "eval" "code" f})) codes)))

;; ===========================================================================
;; what the checker must say
;; ===========================================================================

(def expectations
  "var -> the verdict the gate requires, and for a rejection at least one
  diagnostic kind that must appear."
  [{:var 'perturb.corpus/open-request-close     :expect :accept}
   {:var 'perturb.corpus/shadowed-rebind        :expect :accept}
   {:var 'perturb.corpus/both-arms-close        :expect :accept}
   {:var 'perturb.corpus/loop-of-requests       :expect :accept}
   {:var 'perturb.corpus/ping                   :expect :accept}
   {:var 'perturb.corpus/uses-ping              :expect :accept}

   {:var 'perturb.corpus/use-after-close        :expect :reject :kind :use-after-move}
   {:var 'perturb.corpus/double-close           :expect :reject :kind :typestate}
   {:var 'perturb.corpus/use-after-move         :expect :reject :kind :use-after-move}
   {:var 'perturb.corpus/dangling-connection    :expect :reject :kind :dangling}
   {:var 'perturb.corpus/shadowing-hides-a-leak :expect :reject :kind :dangling}
   {:var 'perturb.corpus/conditional-close      :expect :reject :kind :join}
   {:var 'perturb.corpus/conditional-close-then-use :expect :reject :kind :join}
   {:var 'perturb.corpus/loop-that-closes       :expect :reject :kind :loop-not-preserving}
   {:var 'perturb.corpus/helper-without-a-signature :expect :reject :kind :untracked-consume}
   {:var 'perturb.corpus/ping-tuple             :expect :reject :kind :escape}
   {:var 'perturb.corpus/capture-in-closure     :expect :reject :kind :capture}])
