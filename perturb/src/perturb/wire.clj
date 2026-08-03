(ns perturb.wire
  "The socket effect, declared once. This is the only place perturb's session and
  codec code can reach I/O from.

  CLAIM 2 lives here: `perturb.nrepl` and `perturb.bencode` name nothing below
  this namespace. Swapping `perturb.posix/handler` for `perturb.script/handler`
  changes which handler is installed and nothing else — no conditional in the
  session code, no second code path, no parameterisation of the codec.

  Result predicates are the `validated` half of §1.4's substitute-a-validated-
  result-or-abort: a handler that returns something of the wrong shape is
  stopped at the effect boundary."
  (:require [perturb.effect :as fx]
            [perturb.octet :as o]))

(defn- conn-token? [x] (some? x))
(defn- count? [x] (and (integer? x) (>= x 0)))
(defn- closed? [x] (= :closed x))

(def socket
  (fx/effect
    'perturb.wire/socket
    {:connect {:arity 2 :result-pred conn-token?
               :doc "(host port) -> an opaque handler-owned token. The session code
                     must never inspect it; that opacity is what lets the scripted
                     handler use a vector where the posix handler uses an fd."}
     :send    {:arity 2 :result-pred count?
               :doc "(token octets) -> octets accepted. Argument is an octet view,
                     never a string and never a byte array."}
     :recv    {:arity 2 :result-pred o/octets?
               :doc "(token max) -> octet view. An empty view means end of stream;
                     the session code treats that as an abort, not as `wait`."}
     :close   {:arity 1 :result-pred closed?
               :doc "(token) -> :closed."}}))

;; Thin wrappers. `site` is passed by the caller as a literal keyword so the
;; boundary has durable identity (perturb.effect's docstring; §1.1).

(defn connect [host port site] (fx/perform socket :connect site [host port]))
(defn send!   [tok ov site]    (fx/perform socket :send    site [tok ov]))
(defn recv    [tok max-n site] (fx/perform socket :recv    site [tok max-n]))
(defn close!  [tok site]       (fx/perform socket :close   site [tok]))
