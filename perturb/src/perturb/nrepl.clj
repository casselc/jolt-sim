(ns perturb.nrepl
  "nREPL session over the socket effect. This namespace is the CLAIM 2 exhibit:
  it names `perturb.wire` and never `perturb.posix` or `perturb.script`. The same
  compiled code runs against a real TCP socket and against an in-memory handler.

  It is also the CLAIM 3 exhibit. The connection is a `unique` capability with
  typestate created -> active -> closed. It is threaded affinely: every operation
  CONSUMES the connection value and RETURNS its successor, so an expression that
  reused a closed connection would have to name a value that a `close` already
  consumed. That is not enforced by anything — §1.2's checker is out of scope —
  but it is the shape a checker would accept, and the annotations below say so in
  data.

  DRIVER. E4/E13's trichotomy drives real I/O here: `:need-more` is the only
  thing that causes a `recv`, and it carries the exact original cursor, so a
  frame that arrives split across an arbitrary number of chunks decodes the same
  way. `perturb.script` delivers one octet per `recv` precisely to exercise that."
  (:require [perturb.wire :as w]
            [perturb.bencode :as b]
            [perturb.octet :as o]
            [perturb.effect :as fx]
            [perturb.cap :as cap]))

;; --- the capability declaration, as data ------------------------------------

(def connection-capability
  (cap/declare-capability!
    (cap/capability
      {:perturb.cap/name        'perturb.nrepl/Connection
       :perturb.cap/doc         "An nREPL connection: an opaque wire token plus the receive buffer."
       ;; §1.2's four axes, all four stated.
       :perturb.cap/uniqueness  :unique
       :perturb.cap/linearity   :once
       :perturb.cap/contention  :thread-confined
       :perturb.cap/typestate
       {:states      [:created :active :closed]
        :initial     :created
        :terminal    :closed
        ;; role-indexed operation legality (E5)
        :transitions [{:op 'perturb.nrepl/open    :from :created :to :active}
                      {:op 'perturb.nrepl/request :from :active  :to :active}
                      {:op 'perturb.nrepl/close!  :from :active  :to :closed}]}
       ;; §1.3: obligations outside the typestate machine, for Ansatz or a
       ;; refinement checker. Hand-written; nothing discharges them.
       :perturb.cap/obligations
       '[{:name closed-exactly-once
          :formula (= 1 (count (filter (fn [e] (= :closed (:to e))) ledger)))}
         {:name no-use-after-close
          :formula (forall [e ledger] (implies (= :closed (:from e)) false))}
         {:name buffer-cursor-well-formed
          :formula (forall [c states] (and (<= 0 (:pos c)) (<= (:pos c) (ocount (:buf c)))))}]
       ;; §1.2 dropped locality; recorded so its absence is a decision.
       :perturb.cap/locality    :dropped-by-design})))

;; --- connection values ------------------------------------------------------

(def ^:private id-counter (atom 0))

(defn- fresh-id [] (str "perturb-conn-" (swap! id-counter inc)))

(defn- conn [id state token buf pos msg-id]
  {:perturb.cap/capability 'perturb.nrepl/Connection
   :perturb.cap/id         id
   :perturb.cap/state      state
   :perturb.nrepl/token    token
   :perturb.nrepl/buf      buf
   :perturb.nrepl/pos      pos
   :perturb.nrepl/msg-id   msg-id})

(defn state [c] (:perturb.cap/state c))
(defn conn-id [c] (:perturb.cap/id c))

;; --- operations -------------------------------------------------------------

(defn open
  "Connect. Produces a Connection in state :active.

  Annotation: consumes nothing, produces a unique Connection@:active."
  {:perturb.cap/op {:consumes [] :produces [{:cap 'perturb.nrepl/Connection :state :active}]}}
  [host port]
  (let [id  (fresh-id)
        _   (cap/transition! 'perturb.nrepl/Connection id nil :created :perturb.nrepl/open)
        tok (w/connect host port :perturb.nrepl/connect)]
    (cap/transition! 'perturb.nrepl/Connection id :created :active :perturb.nrepl/open)
    (conn id :active tok o/empty-octets 0 0)))

(defn- compact
  "Drop the consumed prefix and append `chunk`. Keeps the buffer from growing
  without bound across a session, and keeps `pos` in range — the third
  obligation above."
  [c chunk]
  (assoc c
         :perturb.nrepl/buf (o/oconcat (o/odrop (:perturb.nrepl/buf c) (:perturb.nrepl/pos c)) chunk)
         :perturb.nrepl/pos 0))

(defn- read-frame
  "The sans-io driver. Returns [conn' value].

  Note what drives the I/O: nothing but a `:need-more` from a pure step
  function. The driver has no knowledge that the step function is bencode —
  E13's result that the driver types at a fixed refinement, in running form."
  [c]
  (loop [c c]
    (let [r (b/decode (:perturb.nrepl/buf c) (:perturb.nrepl/pos c))
          tag (first r)]
      (cond
        (= :ok tag)
        [(assoc c :perturb.nrepl/pos (nth r 2)) (second r)]

        (= :invalid tag)
        (fx/abort! :decode {:perturb.nrepl/reason (second r)
                            :perturb.nrepl/offset (nth r 2)
                            :perturb.nrepl/conn   (conn-id c)})

        :else ;; :need-more, cursor unchanged
        (let [chunk (w/recv (:perturb.nrepl/token c) 65536 :perturb.nrepl/recv)]
          (if (zero? (o/ocount chunk))
            (fx/abort! :eof {:perturb.nrepl/conn (conn-id c)})
            (recur (compact c chunk))))))))

(defn- done? [frame]
  (let [st (b/dget frame "status")]
    (and (vector? st)
         (some (fn [x] (= "done" (b/dstrs x))) st))))

(defn request
  "Send `msg` and read frames until one carries status \"done\".
  Returns [conn' frames], frames decoded with octet-view byte strings.

  Annotation: consumes Connection@:active, produces Connection@:active."
  {:perturb.cap/op {:consumes [{:cap 'perturb.nrepl/Connection :state :active}]
                    :produces [{:cap 'perturb.nrepl/Connection :state :active}]}}
  [c msg]
  (let [c   (assoc c :perturb.nrepl/msg-id (inc (:perturb.nrepl/msg-id c)))
        mid (str (:perturb.nrepl/msg-id c))
        ov  (b/encode (assoc msg "id" mid))]
    (cap/transition! 'perturb.nrepl/Connection (conn-id c) :active :active :perturb.nrepl/request)
    (w/send! (:perturb.nrepl/token c) ov :perturb.nrepl/send)
    (loop [c c acc []]
      (let [r     (read-frame c)
            c'    (first r)
            frame (second r)
            acc'  (conj acc frame)]
        (if (done? frame) [c' acc'] (recur c' acc'))))))

(defn close!
  "Close. Consumes Connection@:active, produces Connection@:closed.

  Called exactly once per connection in every path of this artifact. Nothing
  enforces that; the annotation states it and the ledger records what happened."
  {:perturb.cap/op {:consumes [{:cap 'perturb.nrepl/Connection :state :active}]
                    :produces [{:cap 'perturb.nrepl/Connection :state :closed}]}}
  [c]
  (w/close! (:perturb.nrepl/token c) :perturb.nrepl/close)
  (cap/transition! 'perturb.nrepl/Connection (conn-id c) :active :closed :perturb.nrepl/close)
  (assoc c :perturb.cap/state :closed :perturb.nrepl/token nil))

;; register the annotations for a future checker
(cap/annotate-op! (var open)    (:perturb.cap/op (meta (var open))))
(cap/annotate-op! (var request) (:perturb.cap/op (meta (var request))))
(cap/annotate-op! (var close!)  (:perturb.cap/op (meta (var close!))))

;; --- protocol operations ----------------------------------------------------

(defn clone-session
  "-> [conn' session-id]"
  [c]
  (let [r      (request c {"op" "clone"})
        c'     (first r)
        frames (second r)
        sid    (some (fn [f] (b/dtext f "new-session")) frames)]
    (when (nil? sid)
      (fx/abort! :no-session {:perturb.nrepl/frames (mapv b/dstrs frames)}))
    [c' sid]))

(defn eval-code
  "-> [conn' {:value s :out s :err s :ns s :frames [...]}]"
  [c session code]
  (let [r      (request c {"op" "eval" "session" session "code" code})
        c'     (first r)
        frames (second r)
        pick   (fn [k] (some (fn [f] (b/dtext f k)) frames))]
    [c' {:value  (pick "value")
         :out    (pick "out")
         :err    (pick "err")
         :ns     (pick "ns")
         :frames (mapv b/dstrs frames)}]))

;; --- the whole session, as one function -------------------------------------

(defn session
  "Open, clone, evaluate each form in `forms`, close. Returns a map.

  THIS FUNCTION IS THE CLAIM-2 EXHIBIT. `perturb.demo` calls this one var under
  two different handlers. There is no handler-conditional in it and no argument
  selecting a transport."
  [host port forms]
  (let [c0 (open host port)
        r  (clone-session c0)
        c1 (first r)
        sid (second r)]
    (loop [c c1 results [] fs forms]
      (if (empty? fs)
        (let [c' (close! c)]
          {:session sid :results results :final-state (state c') :conn-id (conn-id c')})
        (let [er (eval-code c sid (first fs))]
          (recur (first er) (conj results (second er)) (rest fs)))))))
