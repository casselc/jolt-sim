(ns perturb.tlsish
  "A TLS-SHAPED RECORD LAYER THAT CONSUMES THE EFFECT IT SATISFIES.

  THIS IS NOT TLS. There is no key exchange, no cipher, no MAC, no certificate,
  no sequence number, no version negotiation and no security property of any
  kind. Nothing here should ever be used to protect anything. What it borrows
  from TLS is only the SHAPE, and only the three parts of the shape that bear on
  the question being asked:

    1. A HANDSHAKE PHASE BEFORE DATA MAY FLOW. `open-server-record` produces a
       Record in `:handshaking`; `server-handshake` is the only edge to
       `:established`; `record-recv` is declared `:from :established`.
    2. FRAMING THAT DOES NOT ALIGN WITH THE TRANSPORT'S. Records are
       `ctype:1 || len:2 (big-endian) || payload`, with `max-record-payload`
       octets of payload each. An HTTP response of 100 octets is seven records
       and seven transport sends; a request that the transport delivers one
       octet at a time is reassembled here before the layer above sees anything.
    3. A SHUTDOWN THAT MUST BE SENT BEFORE CLOSE. `close-record!` performs a
       transport `send` of an alert record and only then a transport `close`.

  WHY IT EXISTS. PERTURB-DESIGN's architectural goal is `events over an HTTP
  implementation that is events over a TCP socket that is events over a file
  descriptor`, and §5's ladder has never been climbed even one rung. The cheap
  decisive form of the question is not `can two protocols be written` — perturb
  has two — but *can one layer's HANDLER be the next layer's TRANSPORT*. So the
  thing under test is the shape of the composition, not the protocol:

      perturb.http  --performs--> perturb.wire/socket
                                        |
                                  handled by THIS
                                        |
                    --performs--> perturb.wire/socket
                                        |
                    handled by perturb.script / perturb.posix

  HANDLER-OVER-HANDLER, NOT CALL-OVER-CALL. `via` below is the entire mechanism
  and the entire experiment. A handler here does not CALL the transport; it
  PERFORMS `perturb.wire/socket` while it is handling `perturb.wire/socket`, so
  the inner crossing goes through `perturb.effect/perform` — arity check, handler
  lookup, `*handling*` binding, result predicate, trace, run accounting — exactly
  as the outer one did.

  AND THE CONTROL IS THE SAME LAYER. `*call-over-call*` switches `via` to invoke
  the rung below as an ordinary function, which is how every layered library
  composes. One flag, one implementation, both compositions — so what the report
  shows is the difference between the two seams and nothing else.

  THE ONE THING THAT HAD TO BE WRITTEN BY HAND, AND IT IS A FINDING.
  `perturb.effect/*handlers*` is a FLAT MAP keyed by effect name and `perform`
  does not pop it before calling a handler. So a handler that performs the effect
  it is handling re-enters ITSELF: there are no deep handlers here, and no
  enclosing-handler semantics. `via` supplies the missing pop by rebinding the
  effect name to the rung below for exactly the extent of the inner perform.
  `no-deep-handler-probe` is the executable demonstration that the pop is absent,
  and `via` is a convention with nothing enforcing it — the same residual shape
  `perturb.posix/defsys` closed one layer down, reopened one layer up.

  WHAT THE CHECKER MAKES OF IT is in `perturb.tlsdemo`, run as a report and not
  as a gate, in the manner of `perturb.streamcheck`."
  (:require [perturb.wire :as w]
            [perturb.octet :as o]
            [perturb.effect :as fx]
            [perturb.cap :as cap]))

;; --- the record format, as octets -------------------------------------------

(def CT-ALERT     0x15)
(def CT-HANDSHAKE 0x16)
(def CT-APPDATA   0x17)

(def max-record-payload
  "Deliberately small. TLS uses 2^14; sixteen octets guarantees that a single
  HTTP request or response spans several records and that no record boundary
  coincides with anything the layer above knows about. Misalignment is the
  property under test, so it is made unavoidable rather than likely."
  16)

(def CLIENT-HELLO "PHELLO")
(def SERVER-HELLO "PACCEPT")
(def CLOSE-NOTIFY "BYE")

(def contract
  "`parse-record`'s refinement, in the shape `perturb.http/contract` uses.
  Hand-written and UNCHECKED, exactly as that one is."
  '{:perturb.tlsish/step parse-record
    :perturb.tlsish/args
    [{:name ov  :sort Octets}
     {:name pos :sort Int :refine (and (<= 0 pos) (<= pos (ocount ov)))}]
    :perturb.tlsish/result
    (one-of
      {:tag :ok        :refine (and (< pos pos-out) (<= pos-out (ocount ov)))}
      {:tag :need-more :refine (= pos-out pos)}
      {:tag :invalid   :refine (= pos-out pos)})
    :perturb.tlsish/notes
    "The SAME trichotomy as perturb.bencode and perturb.http, for the same
     reason: the transport under this layer may deliver one octet at a time, so
     every partial record must return the EXACT cursor it was given. What is new
     is that the caller of this step function is a HANDLER rather than a driver,
     and the recv it performs on a :need-more is an effect rather than a call."})

;; --- pure framing: no capability and no effect appears below ----------------

(defn frame
  "One record: ctype, two-octet big-endian length, payload. Pure."
  [ctype payload]
  (let [n (o/ocount payload)]
    (o/octets (into [ctype (bit-and (bit-shift-right n 8) 0xff) (bit-and n 0xff)]
                    (o/ovec payload)))))

(defn records-of
  "Split plaintext into a VECTOR of framed records of at most
  `max-record-payload` payload octets each. Pure, and the reason the octet
  counts at the two layers never agree."
  [ctype ov]
  (let [chunks (o/ochunks ov max-record-payload)]
    (if (empty? chunks)
      [(frame ctype o/empty-octets)]
      (mapv (fn [c] (frame ctype c)) chunks))))

(defn parse-record
  "One record from `ov` at `pos`. See `contract`.

  -> [:ok ctype payload pos'] | [:need-more pos] | [:invalid reason offset pos]."
  [ov pos]
  (let [n (o/ocount ov)]
    (if (< (- n pos) 3)
      [:need-more pos]
      (let [ct  (o/oref ov pos)
            len (+ (* 256 (o/oref ov (+ pos 1))) (o/oref ov (+ pos 2)))
            end (+ pos 3 len)]
        (cond
          (not (or (= ct CT-ALERT) (= ct CT-HANDSHAKE) (= ct CT-APPDATA)))
          [:invalid :unknown-content-type pos pos]

          (> len max-record-payload)
          [:invalid :record-too-large pos pos]

          (> end n) [:need-more pos]

          :else [:ok ct (o/osub ov (+ pos 3) end) end])))))

(defn unframe
  "Every complete record in `ov`, as [[ctype payload] ...]. Pure. Used by the
  demo to recover the plaintext a run put on the wire, so that what the layer
  above produced can be compared across transports without trusting the layer."
  [ov]
  (loop [pos 0 acc []]
    (let [r (parse-record ov pos)]
      (if (= :ok (first r))
        (recur (nth r 3) (conj acc [(nth r 1) (nth r 2)]))
        acc))))

(defn plaintext-of
  "The application-data payload of `ov`, concatenated. Pure."
  [ov]
  (reduce (fn [a p] (if (= CT-APPDATA (first p)) (o/oconcat a (second p)) a))
          o/empty-octets (unframe ov)))

;; --- the rung below ---------------------------------------------------------

(def ^:dynamic *call-over-call*
  "Compose the two rungs by CALLING the one below instead of PERFORMING against
  it. False by default; `perturb.tlsdemo` binds it true for one control run.

  This is the alternative every layered library actually uses — the rung below
  is an object you hold and invoke — and the whole architectural claim is that
  the effect boundary is a better seam than that. Making it a flag rather than a
  second implementation means the SAME layer runs both ways, so the difference
  in the report is the difference between the two compositions and nothing
  else."
  false)

(defn rung
  "The handler INSTANCE this layer forwards through.

  `handler` builds one at construction time whose named outer instance is the
  transport, and threads it where the raw transport handler used to go — so the
  Record value carries an instance rather than a bare function. The bare-function
  arm exists only for `record-echo-round`, which is an isolation program the
  checker reads and nothing runs; it lifts the transport to a bottom-rung
  instance and wraps it in an anonymous rung so `via` has an outer to name."
  [t]
  (if (fx/instance? t)
    t
    (fx/instance! 'perturb.tlsish/anonymous-rung nil
                  (fx/as-instance 'perturb.tlsish/transport t) nil)))

(defn via
  "Reach the rung BELOW this one, from inside a handler for this one.

  THE WHOLE EXPERIMENT IS THIS FUNCTION, and it has two arms on purpose.

  FORWARD (default). `perturb.effect/forward!` is called with this layer's
  handler INSTANCE, and it crosses to that instance's NAMED OUTER INSTANCE. The
  octets leave this layer through the effect boundary: arity check, handler
  lookup, `*handling*` binding, result predicate, trace, run accounting — all of
  it, a second time, one rung down.

  WHAT THIS USED TO BE, AND WHY IT CHANGED. Until B6 was made executable this
  arm was `with-handlers` + `perform`: rebind `perturb.wire/socket` to the rung
  below for exactly the extent of the inner perform. That rebinding was E29
  finding 1 — `perturb.effect/perform` does not remove the currently-executing
  handler from `*handlers*` before calling it, so WITHOUT the rebinding an inner
  perform of `perturb.wire/socket` dispatches back to THIS layer and recurs
  forever (`no-deep-handler-probe`, still here, still true of `perform`). E29
  recorded that nothing checked a layer had supplied the pop: `a layer that
  forgot would loop rather than be refused`. `forward!` closes that at the
  mechanism instead of at the report — there is no lookup by effect name, so
  there is nothing to forget, `*handlers*` is neither read nor written, and
  forwarding to yourself is a LATCHED REFUSAL (`:self-forward`) rather than a
  stack overflow.

  CALL. The transport handler is invoked as an ordinary function. It is four
  lines shorter and it works, and what it costs is the whole of the answer to
  `is the effect boundary a real seam` — see the control in `perturb.tlsdemo`.

  THE OUTWARD-OPERATION INSTANCE. The perform arm now carries one:
  `perturb.effect/outward-for` mints a fresh instance per forward unless the
  caller supplied one, and `perturb.effect/perform` records it on the request.
  It costs one map when an event log is bound and returns nil when none is, so
  neither arm's behaviour changes. It is what makes `this layer forwarded its
  own operation twice` and `this forward escaped the request that created it`
  sayable at all — see `perturb.layer` clause 4 and
  `multishot-outward-handler` below. NOTE THAT THE CALL ARM MINTS NOTHING,
  which is not an omission: a call that never reaches the boundary has no
  forward to correlate, and that absence is exactly what control 3 is."
  [transport op site args]
  (if *call-over-call*
    ;; The rung below as an ordinary function. Since `handler` threads its RUNG
    ;; INSTANCE where the bare transport handler used to go, the control arm has
    ;; to reach through the instance to the handler its outer names — which is
    ;; the same object it always called, reached the same way every layered
    ;; library reaches it: by holding it.
    (let [h     (if (fx/instance? transport)
                  (:perturb.effect/handler (:perturb.effect/outer transport))
                  transport)
          reply (h op site args)]
      (cond
        (not (vector? reply))
        (fx/abort! :tlsish-malformed-transport-reply {:perturb.tlsish/reply reply})
        (= :abort (first reply))
        (fx/abort! :tlsish-transport-refused {:perturb.tlsish/reason (second reply)
                                              :perturb.tlsish/site site})
        :else (second reply)))
    (fx/with-outward (fx/outward-for site)
      (fx/forward! (rung transport) w/socket op site args))))

(defn as-call-over-call
  "Run `f` with the layer composed by CALLING the rung below. A function rather
  than a `binding` at the call site so that a namespace which has not required
  this one can still drive the control."
  [f]
  (binding [*call-over-call* true] (f)))

;; --- the capability ---------------------------------------------------------

(def record-capability
  (cap/declare-capability!
    (cap/capability
      {:perturb.cap/name       'perturb.tlsish/Record
       :perturb.cap/doc
       "One TLS-shaped record connection. It OWNS a transport connection it never
        names in its type: the token the rung below handed back lives inside the
        value, and the machine below advances whenever this one does."
       :perturb.cap/uniqueness :unique
       :perturb.cap/linearity  :once
       :perturb.cap/contention :thread-confined
       ;; THREE STATES AND EIGHT EDGES. `:handshaking` is not decoration: it is
       ;; the state that makes `record-recv`'s `:from :established` a real
       ;; precondition, so `read before the handshake finished` is a typestate
       ;; error rather than a comment.
       ;;
       ;; `record-send-early` is TLS 1.3 early data, and it is here for a reason
       ;; that is a finding rather than a feature — see `open-client-record`.
       :perturb.cap/typestate
       {:states  [:handshaking :established :closed]
        :initial :handshaking
        :terminal :closed
        :transitions
        [{:op 'perturb.tlsish/open-server-record :from nil :to :handshaking}
         {:op 'perturb.tlsish/open-client-record :from nil :to :handshaking}
         {:op 'perturb.tlsish/server-handshake   :from :handshaking :to :established}
         {:op 'perturb.tlsish/client-finish      :from :handshaking :to :established}
         {:op 'perturb.tlsish/record-send-early  :from :handshaking :to :handshaking}
         {:op 'perturb.tlsish/record-send        :from :established :to :established}
         {:op 'perturb.tlsish/record-recv        :from :established :to :established}
         {:op 'perturb.tlsish/close-record!      :from [:handshaking :established]
                                                 :to   :closed}]}
       ;; EMPTY, and for exactly `perturb.http/representation-note`'s reason:
       ;; every access to the concrete map is written INSIDE a declared
       ;; transition, whose body is already an axiom. The unchecked surface did
       ;; not shrink; it was repackaged. Recorded so the zero is not read as
       ;; progress.
       :perturb.cap/representation []
       :perturb.cap/obligations
       '[{:name close-notify-before-transport-close
          :formula (forall [e ledger]
                     (implies (and (= 'perturb.tlsish/Record (:capability e))
                                   (= :closed (:to e)))
                              (sent-alert-record-before e)))
          :class   :temporal
          :note    "The shutdown obligation, and NOTHING discharges it. It is a
                    property relating an event of THIS machine to an octet that
                    crossed the machine BELOW, and the machine below is inside
                    the handler where §1.2 has no name for it at all. This is
                    strictly weaker than perturb.http's
                    `body-finished-before-conn-reused`, which at least relates
                    two machines the checker can see."}
         {:name no-application-data-before-established
          :formula (forall [e ledger]
                     (implies (= 'perturb.tlsish/record-recv (:site e))
                              (= :established (:from e))))
          :class   :typestate
          :note    "This one IS on the typestate axis and IS declared, as
                    `record-recv`'s `:from`. It is written out here so the
                    contrast with the one above is visible: the handshake
                    ordering is expressible and the shutdown ordering is not."}]
       :perturb.cap/locality :dropped-by-design})))

;; --- record values ----------------------------------------------------------

(def ^:private id-counter (atom 0))
(defn- fresh-id [] (str "perturb-rec-" (swap! id-counter inc)))

(defn- rec-value
  [id role transport under]
  {:perturb.cap/capability 'perturb.tlsish/Record
   :perturb.cap/id         id
   :perturb.cap/state      :handshaking
   :perturb.tlsish/role    role
   ;; The rung below, carried in the value. It is a HANDLER, not a capability:
   ;; a function has no lifetime, so nothing on §1.2's four axes applies to it.
   :perturb.tlsish/under-handler transport
   :perturb.tlsish/under         under
   :perturb.tlsish/inbuf         o/empty-octets
   :perturb.tlsish/plain         o/empty-octets
   :perturb.tlsish/in            0
   :perturb.tlsish/out           0})

(defn- pump
  "Perform ONE transport `recv` and append it to the record buffer.
  -> [record' progressed?]. The only place this layer reads."
  [r]
  (let [chunk (via (:perturb.tlsish/under-handler r) :recv :perturb.tlsish/under-recv
                   [(:perturb.tlsish/under r) 65536])]
    (if (zero? (o/ocount chunk))
      [r false]
      [(assoc r :perturb.tlsish/inbuf
              (o/oconcat (:perturb.tlsish/inbuf r) chunk)) true])))

(defn- push-record!
  "Perform ONE transport `send` of one framed record."
  [r ctype payload]
  (via (:perturb.tlsish/under-handler r) :send :perturb.tlsish/under-send
       [(:perturb.tlsish/under r) (frame ctype payload)])
  (assoc r :perturb.tlsish/out (inc (:perturb.tlsish/out r))))

(defn- next-record
  "Reassemble until one complete record is available.
  -> [record' ctype payload] | [record' nil nil] on transport end-of-stream."
  [r0]
  (loop [r r0]
    (let [pr (parse-record (:perturb.tlsish/inbuf r) 0)]
      (cond
        (= :ok (first pr))
        [(assoc r :perturb.tlsish/inbuf (o/odrop (:perturb.tlsish/inbuf r) (nth pr 3))
                  :perturb.tlsish/in    (inc (:perturb.tlsish/in r)))
         (nth pr 1) (nth pr 2)]

        (= :invalid (first pr))
        (fx/abort! :tlsish-bad-record
                   {:perturb.tlsish/reason (second pr)
                    :perturb.tlsish/offset (nth pr 2)
                    :perturb.tlsish/record (:perturb.cap/id r)})

        :else
        (let [p (pump r)]
          (if (second p) (recur (first p)) [(first p) nil nil]))))))

;; --- the transitions. Every one of these PERFORMS while HANDLING ------------

(defn open-server-record
  "Accept one transport connection and mint a Record in `:handshaking`.

  ONE OPERATION, THREE MACHINES. This performs `perturb.wire/socket :accept`,
  which — when the rung below is `perturb.posix` — advances a listening socket
  and mints an accepted one, and it mints a Record here. E19 closed `an operation
  that advances two machines cannot be declared` by keying the primitive table
  `[capability operation]`. That fix does not reach this case: the other two
  machines are on the far side of an effect boundary, are named by no var, and
  carry no annotation. `:consumes []` below is not modesty, it is the only thing
  sayable."
  {:perturb.cap/op {:consumes []
                    :produces [{:cap 'perturb.tlsish/Record :state :handshaking}]}}
  [transport ltok]
  (let [utok (via transport :accept :perturb.tlsish/under-accept [ltok])
        id   (fresh-id)]
    (cap/transition! 'perturb.tlsish/Record id nil :handshaking
                     :perturb.tlsish/open-server-record)
    (rec-value id :server transport utok)))

(defn open-client-record
  "Connect a transport and send the ClientHello record. Produces
  Record@:handshaking — NOT `:established`.

  THE DEFERRAL IS FORCED AND IT IS A FINDING. A handshake is a ROUND TRIP, and
  perturb has no scheduler (§1.4 keeps control in an explicit cooperative kernel
  that does not exist). `perturb.httpdemo` is single-threaded because a loopback
  `connect` completes in the kernel without `accept`; that trick does not extend
  to a handshake, because the peer's reply cannot be written before the peer
  runs. So the client CANNOT finish its handshake inside `connect`, and the
  request must go out as early data. The layer above is unchanged and the
  transport is unchanged; the layering itself introduced a deferral, and the
  deferral is what `lazy-handshake-on-first-recv` is rejected for."
  {:perturb.cap/op {:consumes []
                    :produces [{:cap 'perturb.tlsish/Record :state :handshaking}]}}
  [transport host port]
  (let [utok (via transport :connect :perturb.tlsish/under-connect [host port])
        id   (fresh-id)
        r    (rec-value id :client transport utok)]
    (cap/transition! 'perturb.tlsish/Record id nil :handshaking
                     :perturb.tlsish/open-client-record)
    (push-record! r CT-HANDSHAKE (o/encode-utf8 CLIENT-HELLO))))

(defn server-handshake
  "Read the ClientHello record, answer with ServerHello, become `:established`.

  A HANDLER PERFORMING AN EFFECT IN A LOOP. `next-record` performs a transport
  `recv` per iteration and the scripted transport answers ONE OCTET at a time, so
  a three-octet record header costs three inner performs before this function can
  make a decision. Every one of them goes through `perturb.effect/perform`."
  {:perturb.cap/op {:consumes [{:cap 'perturb.tlsish/Record :state :handshaking :arg 0}]
                    :produces [{:cap 'perturb.tlsish/Record :state :established}]}}
  [r0]
  (let [n  (next-record r0)
        r  (nth n 0)
        ct (nth n 1)
        pl (nth n 2)]
    (cond
      (nil? ct)
      (fx/abort! :tlsish-eof-in-handshake {:perturb.tlsish/record (:perturb.cap/id r)})

      (not= CT-HANDSHAKE ct)
      (fx/abort! :tlsish-unexpected-record
                 {:perturb.tlsish/want CT-HANDSHAKE :perturb.tlsish/got ct})

      (not= CLIENT-HELLO (o/->str pl))
      (fx/abort! :tlsish-bad-hello {:perturb.tlsish/got (o/->str pl)})

      :else
      (let [r1 (push-record! r CT-HANDSHAKE (o/encode-utf8 SERVER-HELLO))]
        (cap/transition! 'perturb.tlsish/Record (:perturb.cap/id r1)
                         :handshaking :established :perturb.tlsish/server-handshake)
        (assoc r1 :perturb.cap/state :established)))))

(defn client-finish
  "Read the ServerHello record and become `:established`."
  {:perturb.cap/op {:consumes [{:cap 'perturb.tlsish/Record :state :handshaking :arg 0}]
                    :produces [{:cap 'perturb.tlsish/Record :state :established}]}}
  [r0]
  (let [n  (next-record r0)
        r  (nth n 0)
        ct (nth n 1)
        pl (nth n 2)]
    (cond
      (nil? ct)
      (fx/abort! :tlsish-eof-in-handshake {:perturb.tlsish/record (:perturb.cap/id r)})

      (not= CT-HANDSHAKE ct)
      (fx/abort! :tlsish-unexpected-record
                 {:perturb.tlsish/want CT-HANDSHAKE :perturb.tlsish/got ct})

      (not= SERVER-HELLO (o/->str pl))
      (fx/abort! :tlsish-bad-hello {:perturb.tlsish/got (o/->str pl)})

      :else
      (do (cap/transition! 'perturb.tlsish/Record (:perturb.cap/id r)
                           :handshaking :established :perturb.tlsish/client-finish)
          (assoc r :perturb.cap/state :established)))))

(defn record-recv
  "Deliver at most `max-n` octets of plaintext to the layer above.
  Consumes Record@:established, produces [Record@:established octets] at [0].

  An empty result means END OF STREAM to the layer above, which is
  `perturb.wire/socket`'s contract and therefore what `perturb.http/read-request`
  aborts on. TWO different facts below are folded into it — an alert record
  received, and the transport itself returning empty — and the layer above cannot
  tell them apart. That is E23's `w/recv's empty view means both end of stream
  and nothing queued` complaint, inherited one rung up and made worse: here it
  also means `the peer said goodbye politely`."
  {:perturb.cap/op {:consumes [{:cap 'perturb.tlsish/Record :state :established :arg 0}]
                    :produces [{:cap 'perturb.tlsish/Record :state :established :at [0]}]}}
  [r0 max-n]
  (loop [r r0]
    (let [plain (:perturb.tlsish/plain r)]
      (if (pos? (o/ocount plain))
        (let [k (min max-n (o/ocount plain))]
          (cap/transition! 'perturb.tlsish/Record (:perturb.cap/id r)
                           :established :established :perturb.tlsish/record-recv)
          [(assoc r :perturb.tlsish/plain (o/odrop plain k)) (o/osub plain 0 k)])
        (let [n  (next-record r)
              r1 (nth n 0)
              ct (nth n 1)
              pl (nth n 2)]
          (cond
            (nil? ct)
            (do (cap/transition! 'perturb.tlsish/Record (:perturb.cap/id r1)
                                 :established :established :perturb.tlsish/record-recv)
                [r1 o/empty-octets])

            (= CT-ALERT ct)
            (do (cap/transition! 'perturb.tlsish/Record (:perturb.cap/id r1)
                                 :established :established :perturb.tlsish/record-recv)
                [r1 o/empty-octets])

            (= CT-APPDATA ct)
            (recur (assoc r1 :perturb.tlsish/plain pl))

            :else
            (fx/abort! :tlsish-unexpected-record
                       {:perturb.tlsish/want CT-APPDATA :perturb.tlsish/got ct})))))))

(defn record-send
  "Frame `ov` into records and perform ONE transport send per record.
  Consumes Record@:established, produces Record@:established BARE.

  THE OCTET COUNTS DO NOT AGREE AND NOTHING NOTICES. `perturb.wire/socket`'s
  `:send` result predicate is `count?` and the layer above reads it as `octets
  accepted`. This returns the PLAINTEXT count while the transport accepted
  plaintext + 3 octets per record. Both satisfy the predicate; the effect
  declaration has no vocabulary for `whose octets`."
  {:perturb.cap/op {:consumes [{:cap 'perturb.tlsish/Record :state :established :arg 0}]
                    :produces [{:cap 'perturb.tlsish/Record :state :established}]}}
  [r0 ov]
  (let [recs (records-of CT-APPDATA ov)
        r    (loop [i 0 r r0]
               (if (>= i (count recs))
                 r
                 (do (via (:perturb.tlsish/under-handler r) :send
                          :perturb.tlsish/under-send
                          [(:perturb.tlsish/under r) (nth recs i)])
                     (recur (inc i) (assoc r :perturb.tlsish/out
                                           (inc (:perturb.tlsish/out r)))))))]
    (cap/transition! 'perturb.tlsish/Record (:perturb.cap/id r)
                     :established :established :perturb.tlsish/record-send)
    r))

(defn record-send-early
  "TLS 1.3 early data: send before the handshake completed. Consumes
  Record@:handshaking, produces Record@:handshaking BARE. See
  `open-client-record` for why this edge exists at all."
  {:perturb.cap/op {:consumes [{:cap 'perturb.tlsish/Record :state :handshaking :arg 0}]
                    :produces [{:cap 'perturb.tlsish/Record :state :handshaking}]}}
  [r0 ov]
  (let [recs (records-of CT-APPDATA ov)
        r    (loop [i 0 r r0]
               (if (>= i (count recs))
                 r
                 (do (via (:perturb.tlsish/under-handler r) :send
                          :perturb.tlsish/under-send
                          [(:perturb.tlsish/under r) (nth recs i)])
                     (recur (inc i) (assoc r :perturb.tlsish/out
                                           (inc (:perturb.tlsish/out r)))))))]
    (cap/transition! 'perturb.tlsish/Record (:perturb.cap/id r)
                     :handshaking :handshaking :perturb.tlsish/record-send-early)
    r))

(defn close-record!
  "Send the close-notify alert, THEN close the transport. Consumes
  Record@[:handshaking :established], produces Record@:closed.

  THE SHUTDOWN OBLIGATION, AND IT IS AN AXIOM. `:closed` is a state and `an
  alert record was written before the transport was closed` is an ordering
  between an event of this machine and an octet on the machine below. §1.2's
  typestate axis expresses the first; nothing in perturb expresses the second,
  and unlike `perturb.http`'s `wrote-exactly-content-length` it is not even
  arithmetic a refinement could carry, because the quantity is not in this
  layer's state. The two `w/send!`/`w/close!` performs below are in the right
  order because they are written in the right order."
  {:perturb.cap/op {:consumes [{:cap 'perturb.tlsish/Record
                                :state [:handshaking :established] :arg 0}]
                    :produces [{:cap 'perturb.tlsish/Record :state :closed}]}}
  [r0]
  (let [r (push-record! r0 CT-ALERT (o/encode-utf8 CLOSE-NOTIFY))]
    (via (:perturb.tlsish/under-handler r) :close :perturb.tlsish/under-close
         [(:perturb.tlsish/under r)])
    (cap/transition! 'perturb.tlsish/Record (:perturb.cap/id r)
                     (:perturb.cap/state r) :closed :perturb.tlsish/close-record!)
    (assoc r :perturb.cap/state :closed :perturb.tlsish/under nil)))

(cap/annotate-op! (var open-server-record) (:perturb.cap/op (meta (var open-server-record))))
(cap/annotate-op! (var open-client-record) (:perturb.cap/op (meta (var open-client-record))))
(cap/annotate-op! (var server-handshake)   (:perturb.cap/op (meta (var server-handshake))))
(cap/annotate-op! (var client-finish)      (:perturb.cap/op (meta (var client-finish))))
(cap/annotate-op! (var record-recv)        (:perturb.cap/op (meta (var record-recv))))
(cap/annotate-op! (var record-send)        (:perturb.cap/op (meta (var record-send))))
(cap/annotate-op! (var record-send-early)  (:perturb.cap/op (meta (var record-send-early))))
(cap/annotate-op! (var close-record!)      (:perturb.cap/op (meta (var close-record!))))

;; --- the machine, threaded the way the checker wants it ---------------------

(defn record-echo-round
  "Accept, handshake, read one plaintext chunk, write it back, close.

  THE CONTROL. Every capability is in a `let` binding of statically-known shape,
  the states line up, and this is what the record layer looks like when it is
  written the way §1.2 requires. It is also USELESS as a handler: a handler is
  called once per operation and this is one straight line through the whole
  connection lifetime. The gap between this function and `handler` below is the
  measurement."
  [transport ltok]
  (let [r0 (open-server-record transport ltok)
        r1 (server-handshake r0)
        rr (record-recv r1 65536)
        r2 (first rr)
        pl (second rr)
        r3 (record-send r2 pl)
        r4 (close-record! r3)]
    :echoed))

;; --- THE HANDLER: the same layer, shaped so it can be one -------------------

(defn handler
  "A `perturb.wire/socket` handler whose OWN implementation performs
  `perturb.wire/socket` against `transport`.

  Install it and `perturb.http` is running over a record layer without a single
  line of `perturb.http` changing, without a transport argument, and without a
  conditional. That is the compositional claim, executed.

  WHY THE ATOM IS NOT A SHORTCUT. A handler is `(fn [op site args] ...)`: it is
  entered once per operation and must carry the record's reassembly buffer,
  phase and transport token BETWEEN entries. A `let` cannot span two invocations.
  So the capability has to live in inter-invocation state, and the only carriers
  available are an atom and a closure — `perturb.check`'s report-limits item 5
  rejects the first outright and item 3 rejects the second. The isolation
  programs at the bottom of this namespace pin that down. This is not `an atom
  was convenient`; it is that a HANDLER STACK IS INTER-INVOCATION STATE BY
  CONSTRUCTION, which is a stronger statement of §4.6's `a capability may only
  live in a binding of statically-known shape` than E23 or E24 reached.

  Tokens handed UP are `[:tls-conn id]` / `[:tls-listener under]` and are opaque
  to the layer above, exactly as `perturb.script`'s vectors and
  `perturb.posix`'s file descriptors are. The layer above cannot reach the
  transport token; the layer below never sees a Record.

  THE RUNG INSTANCE. `me` is this layer's explicit identity
  (PRACTICAL-ADOPTION-AND-SIM-LEARNINGS §A3): it names the transport as its
  OUTER INSTANCE once, at construction, and every forward this layer makes goes
  through it. It is what is threaded into the Record value where the bare
  transport handler used to sit, so a Record carries the rung it belongs to
  rather than a function nobody can name.

  AND IT CARRIES A FINALIZER. §A3 wants finalisation on success, abort and
  exception; this one reports the Records this layer still holds and which state
  each is in. It DISCHARGES NOTHING — a report is all a finalizer is allowed to
  be here, and `perturb.effect/finalize!` refuses a forward from inside it, so
  it cannot write the close-notify alert it might like to. That inability is the
  honest finding: `close-record!`'s shutdown obligation is exactly the thing a
  finalizer would want to discharge and exactly the thing it may not.

  THE SECOND ARITY exists only so a driver can reach `me`: `perturb.layer`'s
  finalisation clause needs the rung instance to hand to
  `perturb.effect/with-rung`, and a closure is not reachable from outside. The
  one-arity form is what `perturb.tlsdemo` has always called and is unchanged."
  ([transport] (handler transport (atom nil)))
  ([transport rung-out]
  (let [st (atom {:conns {}})
        me (fx/instance!
             'perturb.tlsish/handler nil
             (fx/as-instance 'perturb.tlsish/transport transport)
             (fn [outcome _]
               {:perturb.tlsish/outcome outcome
                :perturb.tlsish/records
                (mapv (fn [p] [(first p) (if (second p)
                                           (:perturb.cap/state (second p))
                                           :gone)])
                      (seq (:conns @st)))}))]
    (reset! rung-out me)
    (fn [op site args]
      (cond
        (= op :listen)
        [:ok [:tls-listener (via me :listen :perturb.tlsish/under-listen
                                 [(first args) (second args)])]]

        (= op :accept)
        (let [r0 (open-server-record me (nth (first args) 1))
              r1 (server-handshake r0)
              id (:perturb.cap/id r1)]
          (swap! st assoc-in [:conns id] r1)
          [:ok [:tls-conn id]])

        (= op :connect)
        (let [r (open-client-record me (first args) (second args))]
          (swap! st assoc-in [:conns (:perturb.cap/id r)] r)
          [:ok [:tls-conn (:perturb.cap/id r)]])

        (= op :recv)
        (let [id (nth (first args) 1)
              r  (get (:conns @st) id)
              ;; THE LAZY HANDSHAKE, and the join it is. One arm consumes the
              ;; Record and the other does not; see `lazy-handshake-on-first-recv`.
              r1 (if (= :handshaking (:perturb.cap/state r)) (client-finish r) r)
              rr (record-recv r1 (second args))]
          (swap! st assoc-in [:conns id] (first rr))
          [:ok (second rr)])

        (= op :send)
        (let [id (nth (first args) 1)
              r  (get (:conns @st) id)
              ov (second args)
              r1 (if (= :handshaking (:perturb.cap/state r))
                   (record-send-early r ov)
                   (record-send r ov))]
          (swap! st assoc-in [:conns id] r1)
          ;; PLAINTEXT octets, not wire octets. See `record-send`.
          [:ok (o/ocount ov)])

        (= op :close)
        (if (= :tls-listener (first (first args)))
          (do (via me :close :perturb.tlsish/under-close
                   [(nth (first args) 1)])
              [:ok :closed])
          (let [id (nth (first args) 1)
                r  (get (:conns @st) id)]
            (if (nil? r)
              [:ok :closed]
              (let [r1 (close-record! r)]
                (swap! st assoc-in [:conns id] nil)
                [:ok :closed]))))

        :else [:abort {:reason :unsupported-op :op op}])))))

;; --- probes -----------------------------------------------------------------

(defn no-deep-handler-probe
  "EXECUTABLE PROOF THAT `perform` DOES NOT POP THE HANDLER.

  Installs a handler for `perturb.wire/socket` that performs
  `perturb.wire/socket :recv` WITHOUT `via`, i.e. without rebinding the effect
  name. If `perform` removed the executing handler from the environment before
  calling it — deep handlers, or the enclosing-handler semantics every effect
  calculus in Appendix D.3 has — the inner perform would reach whatever handler
  was installed outside and return its answer. It does not: it reaches this
  handler again. The depth counter is what stops the run instead of a stack
  overflow, and its final value is the evidence.

  Returns {:depth n :outcome ...}."
  [limit]
  (let [depth (atom 0)
        outer (fn [op site args] [:ok (o/octets [0xEE])])
        inner (fn [op site args]
                (swap! depth inc)
                (if (> @depth limit)
                  [:abort {:reason :depth-limit :depth @depth}]
                  [:ok (w/recv :token 1 :perturb.tlsish/no-deep-handler-probe)]))]
    (fx/with-handlers {'perturb.wire/socket outer}
      (try
        (fx/with-handlers {'perturb.wire/socket inner}
          {:depth @depth
           :outcome [:returned (o/hex (w/recv :token 1 :perturb.tlsish/probe-top))]})
        (catch :default e
          {:depth @depth
           :outcome [:aborted (:perturb.effect/abort (ex-data e))]})))))

(defn laundering-handler
  "A record layer that CONVERTS THE RUNG BELOW'S REFUSAL INTO ITS OWN SUCCESS.

  `perturb.effect/latching-aborts` deliberately excludes `:handler-abort`, on the
  stated ground that a handler returning `[:abort ...]` is `a declared outcome of
  the effect, catchable by the caller`. That is sound while the caller is
  application code. Under a handler stack THE CALLER IS ANOTHER HANDLER, and this
  is what one can do with the licence: catch the transport's refusal and answer
  the layer above with a clean end-of-stream. The layer above sees an orderly
  shutdown, and `perturb.effect/report` says `all-handled? true`, because nothing
  latched and every required symbol was reached.

  Not a straw man: `record-recv` folds three different facts into an empty view
  already, and returning `[:ok empty]` on a failed read is what an implementation
  that wanted to be robust would write.

  The second arity forwards `perturb.tlsish/handler`'s rung-instance atom, so a
  driver can finalise the rung this wraps."
  ([transport] (laundering-handler transport (atom nil)))
  ([transport rung-out]
  (let [inner (handler transport rung-out)]
    (fn [op site args]
      (if (= op :recv)
        (try (inner op site args)
             (catch :default e [:ok o/empty-octets]))
        (inner op site args))))))

(defn multishot-outward-handler
  "A LAYER WHOSE OUTWARD OPERATION IS CONTROL-FLOW-UNRESTRICTED. The negative
  control both round-2 surveys asked for by name.

  PERTURB-DESIGN E33 records Tang's four admissibility conditions for an
  operation clause that performs an operation outward, and the rejection that
  goes with them: **rejected when the outward operation is
  control-flow-unrestricted and might resume zero or many times**. The decision
  recorded there is `continue, and add a negative test whose outer operation is
  multi-shot or unrestricted, which must be rejected`. This is that test.

  WHAT IS AND IS NOT BEING MODELLED, SAID FIRST BECAUSE IT IS THE WEAK POINT.
  `perturb.effect` has NO RESUMPTIONS — charter D4 is `substitute a validated
  result or abort; no continuations at that layer` — so `resume zero or many
  times` cannot be reproduced literally and nothing here should be read as
  having reproduced it. What CAN be reproduced is the property the condition is
  about: the outward operation as a value whose invocation count and dynamic
  extent are unconstrained. `perturb.effect/outward!` mints an instance; this
  layer STORES one in an atom and then

    - answers its first `:recv` locally, having minted an outward instance it
      never consumes — the ZERO-shot arm, and the answer is octets it never
      received from anybody;
    - answers its second `:recv` by re-supplying that SAME stored instance to
      TWO forwards — the MANY-shot arm, and both of them happen under a
      different request from the one that minted it, so the instance also
      escaped its creating extent.

  Three facts, one program, and every one of them is a fact about the recorded
  trace rather than about the source. `perturb.layer` clause 4 names all three.

  It is otherwise an honest pass-through: `:listen`, `:accept`, `:send` and
  `:close` forward exactly once, so the program fails for the reason under test
  and not because it is broken everywhere."
  [transport]
  (let [st (atom {:k nil :n 0})
        me (fx/instance! 'perturb.tlsish/multishot nil
                         (fx/as-instance 'perturb.tlsish/transport transport)
                         (fn [outcome _] {:perturb.tlsish/outcome outcome}))]
    (fn [op site args]
      (cond
        (= op :listen)
        [:ok [:ms-listener (via me :listen :perturb.tlsish/ms-listen
                                [(first args) (second args)])]]

        (= op :accept)
        [:ok [:ms-conn (via me :accept :perturb.tlsish/ms-accept
                            [(nth (first args) 1)])]]

        (= op :send)
        [:ok (via me :send :perturb.tlsish/ms-send
                  [(nth (first args) 1) (second args)])]

        (= op :close)
        (do (via me :close :perturb.tlsish/ms-close [(nth (first args) 1)])
            [:ok :closed])

        (= op :recv)
        (let [u (nth (first args) 1)]
          (if (zero? (:n @st))
            ;; ZERO-SHOT. Mint TWO outward operations for this request: one is
            ;; dropped on the floor and never consumed at all — the arm that
            ;; `resumes zero times` — and one is stashed for later. Answer the
            ;; layer above with octets that came from nowhere.
            (do (fx/outward! :perturb.tlsish/ms-recv-dropped)
                (swap! st assoc
                       :k (fx/outward! :perturb.tlsish/ms-recv)
                       :n 1)
                [:ok (o/encode-utf8 "")])
            ;; MANY-SHOT. Re-supply the STORED instance to two forwards, under a
            ;; request that is not the one that minted it, and discard one of
            ;; the two answers.
            (let [k (:k @st)
                  a (fx/with-outward k
                      (via me :recv :perturb.tlsish/ms-recv [u 65536]))
                  b (fx/with-outward k
                      (via me :recv :perturb.tlsish/ms-recv [u 65536]))]
              (swap! st assoc :n (+ 1 (:n @st)))
              [:ok (o/oconcat a b)])))

        :else [:abort {:reason :unsupported-op :op op}]))))

(defn self-forwarding-handler
  "A LAYER THAT FORWARDS TO ITSELF. §A3's first control: `self-forward must
  fail`.

  This is E29 finding 1's failure mode with the mechanism that makes it one. In
  the old `via` — `with-handlers` + `perform` — a layer that omitted the
  rebinding did not forward to itself deliberately, it simply failed to pop, and
  the result was unbounded recursion that `no-deep-handler-probe` had to stop
  with a counter. Here the outer instance is NAMED, so naming yourself is the
  only way to do it and `perturb.effect/forward!` refuses on the spot with
  `:self-forward`, latched.

  Built by minting an instance and then patching its `:perturb.effect/outer` to
  point at itself, which is a shape a layer author would reach by copying the
  wrong variable."
  [transport]
  (let [me (fx/instance! 'perturb.tlsish/self-forwarder nil
                         (fx/as-instance 'perturb.tlsish/transport transport) nil)
        looped (assoc me :perturb.effect/outer me)]
    (fn [op site args]
      [:ok (via looped op :perturb.tlsish/self-forward [(first args) (second args)])])))

(defn finalizer-forwarding-handler
  "A RUNG WHOSE FINALIZER FORWARDS. §A3: a finalizer `may report
  transfer/discharge but MAY NOT retry native proceed`; perturb's analogue of
  retrying the outward mechanism is forwarding, so this must fail.

  It is not a straw man. `close-record!`'s obligation — write a close-notify
  alert BEFORE closing the transport — is precisely what a layer author would
  want a finalizer to discharge on the abort path, and this is what happens when
  they try. Returns the instance so a caller can drive `with-rung` around it."
  [transport]
  (let [me (fx/instance! 'perturb.tlsish/finalizer-forwarder nil
                         (fx/as-instance 'perturb.tlsish/transport transport)
                         nil)]
    (assoc me :perturb.effect/finalize
           (fn [outcome _]
             ;; The close-notify a finalizer cannot send.
             (via me :send :perturb.tlsish/finalizer-close-notify
                  [[:scripted-conn 0] (frame CT-ALERT (o/encode-utf8 CLOSE-NOTIFY))])
             {:perturb.tlsish/outcome outcome}))))

;; --- isolation programs: one rejection each ---------------------------------
;;
;; These are not the layer. Each exists to isolate ONE rule that refuses the
;; handler shape, so the report can say WHICH rule rather than that several do.
;; Every one of them is annotated and none is a declared transition, so all of
;; them are inside the checked set.

(def ^:private stash (atom nil))

(defn stash-record-in-atom
  "The move `handler` is FORCED to make. A Record arrives, is put in an atom so
  the next invocation can find it, and is never consumed on any path the checker
  can see."
  {:perturb.cap/op {:consumes [{:cap 'perturb.tlsish/Record :state :established :arg 0}]
                    :produces []}}
  [r]
  (reset! stash r)
  :stashed)

(cap/annotate-op! (var stash-record-in-atom)
                  (:perturb.cap/op (meta (var stash-record-in-atom))))

(defn record-table
  "The other shape a handler could use: a map from the token the layer above
  holds to the Record it names. E24 measured this for connections; it is the
  same map one rung up, and now it is not a design choice — a handler has
  nowhere else to put it."
  {:perturb.cap/op {:consumes [{:cap 'perturb.tlsish/Record :state :established :arg 0}]
                    :produces []}}
  [r t id]
  (assoc t id r))

(cap/annotate-op! (var record-table) (:perturb.cap/op (meta (var record-table))))

(defn record-from-table
  "And the read back out. `(get t id)` is a map lookup, so the Record arrives
  with no state, and `record-send` cannot be told it is in the wrong one — the
  checker does not know a Record is there."
  [t id ov]
  (let [r (get t id)]
    (record-send r ov)))

(defn lazy-handshake-on-first-recv
  "The client's deferred handshake, isolated. `client-finish` consumes the Record
  in one arm of the `if` and the other arm does not, which is E6 probe 1's join
  rule reached a THIRD way — E23 reached it from a truncated reply, E24 from an
  application effect, this from a layer that cannot complete its handshake when
  it is opened. `handler`'s `:recv` branch has this shape verbatim."
  {:perturb.cap/op {:consumes [{:cap 'perturb.tlsish/Record :state :handshaking :arg 0}]
                    :produces [{:cap 'perturb.tlsish/Record :state :established :at [0]}]}}
  [r]
  (let [r1 (if (= :handshaking (:perturb.cap/state r)) (client-finish r) r)]
    [r1]))

(cap/annotate-op! (var lazy-handshake-on-first-recv)
                  (:perturb.cap/op (meta (var lazy-handshake-on-first-recv))))

(defn handler-closing-over-a-record
  "The closure carrier, which is the only alternative to the atom. A Record is
  captured by the `fn` a handler must return, so the capability's lifetime is the
  closure's and nothing knows how often it runs."
  {:perturb.cap/op {:consumes [{:cap 'perturb.tlsish/Record :state :established :arg 0}]
                    :produces []}}
  [r]
  (fn [op site args] (record-recv r 16)))

(cap/annotate-op! (var handler-closing-over-a-record)
                  (:perturb.cap/op (meta (var handler-closing-over-a-record))))
