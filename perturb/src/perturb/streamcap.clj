(ns perturb.streamcap
  "THE MEASUREMENT. `perturb.stream` is a port of `teensyp.stream` written by an
  agent that was forbidden to read `perturb.cap`, `perturb.check`, either corpus,
  or the design record. It is the first code perturb has ever checked that was
  not written by someone who already knew the rules.

  This namespace declares a capability for it FROM OUTSIDE — the port is not
  modified, because the port is the artifact. Everything here is the most
  faithful capability declaration the existing language can express for the code
  as written. Where it cannot express something, that is the finding."
  (:require [perturb.cap :as cap]
            [perturb.stream :as st]))

(def conn-capability
  (cap/declare-capability!
    (cap/capability
      {:perturb.cap/name       'perturb.stream/Conn
       :perturb.cap/doc        "A blocking line-oriented connection, as ported."
       :perturb.cap/uniqueness :unique
       :perturb.cap/linearity  :once
       :perturb.cap/contention :thread-confined
       :perturb.cap/typestate
       {:states  [:open :closed]
        :initial :open
        :terminal :closed
        :transitions [{:op 'perturb.stream/connect    :from nil   :to :open}
                      {:op 'perturb.stream/conn-close :from :open :to :closed}]}
       ;; `conn` builds the record and `lf-index`/`recv-chunk` work on its
       ;; representation.
       :perturb.cap/representation
       ['perturb.stream/conn 'perturb.stream/lf-index 'perturb.stream/recv-chunk]
       :perturb.cap/locality :dropped-by-design})))

;; The lifecycle. `connect` mints, `conn-close` consumes to a terminal state.
;; E19's rule that a terminal `:to` need not be produced is what lets
;; `conn-close` return nil, which is what the ported code does.
(cap/annotate-op! (var st/connect)
                  {:consumes []
                   :produces [{:cap 'perturb.stream/Conn :state :open}]})

(cap/annotate-op! (var st/conn-close)
                  {:consumes [{:cap 'perturb.stream/Conn :state :open :arg 0}]
                   :produces []})

;; The three operations that USE the connection without ending it. The ported
;; code calls them repeatedly on one value and they return nil or a line, never
;; a successor — so `:borrows` is the only thing that describes them.
(cap/annotate-op! (var st/conn-recv)
                  {:borrows [{:cap 'perturb.stream/Conn :state :open :arg 0}]})

(cap/annotate-op! (var st/conn-send)
                  {:borrows [{:cap 'perturb.stream/Conn :state :open :arg 0}]})

(cap/annotate-op! (var st/conn-read-line)
                  {:borrows [{:cap 'perturb.stream/Conn :state :open :arg 0}]})

;; --- ROUND 2: annotate the helpers too --------------------------------------
;;
;; Round 1 rejected 7 of 8. The dominant diagnostic was `untracked-borrow` /
;; `untracked-consume` on ordinary helper functions that take a connection —
;; because interprocedural flow is by ANNOTATION ONLY, so an unannotated
;; function's parameters are opaque. The question round 2 answers: is that an
;; ergonomic cost (annotate every function and it passes) or a hole (it cannot
;; be said at all)?

(cap/annotate-op! (var st/drain-lines)
                  {:borrows [{:cap 'perturb.stream/Conn :state :open :arg 0}]})

(cap/annotate-op! (var st/request-lines)
                  {:borrows [{:cap 'perturb.stream/Conn :state :open :arg 0}]})

(cap/annotate-op! (var st/line-echo)
                  {:borrows [{:cap 'perturb.stream/Conn :state :open :arg 0}]})

;; `read-lines` is the interesting one: it BORROWS on the complete-reply path
;; and CONSUMES on the truncated path, and there is no way to say "borrows,
;; except on the branch that aborts".
(cap/annotate-op! (var st/read-lines)
                  {:borrows [{:cap 'perturb.stream/Conn :state :open :arg 0}]})
