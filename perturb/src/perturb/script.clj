(ns perturb.script
  "Handler (b): scripted, in memory. No socket, no FFI, no syscall.

  Two flavours, both satisfying the same effect declaration as `perturb.posix`:

  MODEL — a tiny in-memory nREPL server. It decodes what the session sent with
  `perturb.bencode/decode`, looks the op up in a script, encodes the replies
  with `perturb.bencode/encode`, and delivers them ONE OCTET AT A TIME. The
  one-octet delivery is the point: it forces `perturb.nrepl/read-frame` through
  the `:need-more` arm on almost every byte, which is what E4's exact-original
  -cursor contract exists to make safe.

  REPLAY — takes a transcript recorded by `perturb.posix/handler` and plays the
  recorded receives back, rechunked. Same session code, same codec, different
  chunk boundaries from the ones the network happened to produce.

  Neither flavour reimplements anything the session uses. The session's encoder,
  decoder, driver and capability threading are the same vars in both."
  (:require [perturb.bencode :as b]
            [perturb.octet :as o]))

;; --- shared queue mechanics -------------------------------------------------

(defn- push-chunks! [st ov chunk-size]
  (swap! st update :pending (fn [q] (into (or q []) (o/ochunks ov chunk-size)))))

(defn- pop-chunk! [st]
  (let [s @st
        q (:pending s)]
    (if (empty? q)
      o/empty-octets
      (do (swap! st assoc :pending (subvec q 1)) (nth q 0)))))

;; --- the model server -------------------------------------------------------

(def default-script
  "op -> (fn [request-frame] -> vector of reply maps). Values may be host
  strings; `perturb.bencode/encode` turns them into octets."
  {"clone" (fn [_] [{"new-session" "scripted-session-1" "status" ["done"]}])
   "describe" (fn [_] [{"ops" {"clone" {} "eval" {} "describe" {}}
                        "versions" {"perturb-script" {"major" 0 "minor" 1}}
                        "status" ["done"]}])
   "eval"  (fn [req]
             (let [code (b/dtext req "code")]
               [{"value" (str "scripted<" code ">") "ns" "user" "status" ["done"]}]))})

(defn- handle-request [st req]
  (let [op   (b/dtext req "op")
        mid  (b/dtext req "id")
        sid  (b/dtext req "session")
        f    (get (:script @st) op)
        reps (if f (f req) [{"status" ["done" "unknown-op"]}])]
    (doseq [r reps]
      (let [r' (cond-> r
                 mid (assoc "id" mid)
                 sid (assoc "session" sid))]
        (push-chunks! st (b/encode r') (:chunk-size @st))))))

(defn- feed! [st ov]
  ;; Decode every complete frame the session just sent, using the SAME decoder
  ;; the session uses to read replies.
  (swap! st update :inbuf (fn [b] (o/oconcat (or b o/empty-octets) ov)))
  (loop []
    (let [buf (:inbuf @st)
          r   (b/decode buf 0)]
      (when (= :ok (first r))
        (handle-request st (second r))
        (swap! st assoc :inbuf (o/odrop buf (nth r 2)))
        (recur)))))

(defn model-handler
  "Scripted in-memory nREPL. `opts`: :script, :chunk-size (default 1)."
  ([] (model-handler {}))
  ([opts]
   (let [st (atom {:script     (or (:script opts) default-script)
                   :chunk-size (or (:chunk-size opts) 1)
                   :pending    []
                   :inbuf      o/empty-octets
                   :open       false})]
     (fn [op site args]
       (cond
         (= op :connect) (do (swap! st assoc :open true) [:ok [:scripted (first args) (second args)]])
         (= op :send)    (let [ov (second args)]
                           (if-not (:open @st)
                             [:abort {:reason :not-open}]
                             (do (feed! st ov) [:ok (o/ocount ov)])))
         (= op :recv)    (if-not (:open @st)
                           [:abort {:reason :not-open}]
                           [:ok (pop-chunk! st)])
         (= op :close)   (do (swap! st assoc :open false) [:ok :closed])
         :else [:abort {:reason :unsupported-op :op op}])))))

;; --- the scripted network, server side --------------------------------------
;;
;; `model-handler` above is a scripted PEER for a client. This is a scripted
;; NETWORK for a server: it answers `:listen` and `:accept`, and each accepted
;; connection delivers a fixed octet stream one chunk at a time and records
;; everything written to it.
;;
;; It knows nothing about HTTP. The request octets are supplied by the caller,
;; which is what keeps `perturb.script` free of `perturb.http` — the same
;; property `model-handler` has with respect to `perturb.nrepl`'s protocol layer
;; (it does use the bencode codec, which is the weaker half of that claim).

(defn server-session
  "A scripted server-side network.

  `opts`:
    :conns      vector of octet views — one complete inbound stream per
                connection, delivered in accept order
    :chunk-size octets per `recv` (DEFAULT 1)

  Returns {:handler f :state atom}. `:sent` in the state is conn-index -> vector
  of octet views written to that connection, in order, so a caller can compare
  what the server put on the wire against what a real socket saw.

  ONE OCTET PER RECV IS THE DEFAULT ON PURPOSE. It forces
  `perturb.http/read-request` through the `:need-more` arm on nearly every octet
  of every request, which is the only thing that makes the exact-original-cursor
  half of the trichotomy load-bearing rather than decorative."
  ([] (server-session {}))
  ([opts]
   (let [conns (or (:conns opts) [])
         csz   (or (:chunk-size opts) 1)
         st    (atom {:conns conns :next 0 :queues {} :sent {} :listening false})
         qpop  (fn [i]
                 (let [q (get (:queues @st) i)]
                   (if (or (nil? q) (empty? q))
                     o/empty-octets
                     (do (swap! st (fn [s] (assoc s :queues (assoc (:queues s) i (subvec q 1)))))
                         (nth q 0)))))
         h (fn [op site args]
             (cond
               (= op :listen)
               (do (swap! st assoc :listening true)
                   [:ok [:scripted-listener (first args) (second args)]])

               (= op :accept)
               (let [i (:next @st)]
                 (if (>= i (count conns))
                   [:abort {:reason :no-more-connections :accepted i}]
                   (do (swap! st (fn [s]
                                   (assoc s
                                          :next   (inc i)
                                          :queues (assoc (:queues s) i
                                                         (vec (o/ochunks (nth conns i) csz)))
                                          :sent   (assoc (:sent s) i []))))
                       [:ok [:scripted-conn i]])))

               (= op :recv)
               (let [tok (first args)]
                 (if (= :scripted-conn (first tok))
                   [:ok (qpop (second tok))]
                   [:abort {:reason :recv-on-a-listener}]))

               (= op :send)
               (let [tok (first args) ov (second args)]
                 (if (= :scripted-conn (first tok))
                   (let [i (second tok)]
                     (swap! st (fn [s]
                                 (assoc s :sent
                                        (assoc (:sent s) i
                                               (conj (or (get (:sent s) i) []) ov)))))
                     [:ok (o/ocount ov)])
                   [:abort {:reason :send-on-a-listener}]))

               (= op :close) [:ok :closed]
               :else [:abort {:reason :unsupported-op :op op}]))]
     {:handler h :state st})))

(defn server-handler
  "`server-session`'s handler alone, for callers that do not need the state."
  ([] (:handler (server-session {})))
  ([opts] (:handler (server-session opts))))

(defn sent-octets
  "All octets written to scripted connection `i`, concatenated."
  [state i]
  (reduce (fn [a ov] (o/oconcat a ov)) o/empty-octets (or (get (:sent @state) i) [])))

;; --- the replay handler -----------------------------------------------------

(defn replay-handler
  "Replay a `perturb.posix` transcript. `recorded` is the vector that handler
  appended to. Recv payloads are re-chunked to `chunk-size` (default 1), so the
  frame boundaries the session sees are deliberately not the ones the network
  produced."
  ([recorded] (replay-handler recorded 1 nil))
  ([recorded chunk-size] (replay-handler recorded chunk-size nil))
  ([recorded chunk-size record]
   (let [recvs (mapv :octets (filter (fn [e] (= :recv (:op e))) recorded))
         st    (atom {:pending (vec (mapcat (fn [ov] (o/ochunks ov chunk-size)) recvs))
                      :sent    []})]
     (fn [op site args]
       (cond
         (= op :connect) [:ok [:replay (first args) (second args)]]
         (= op :send)    (do (swap! st update :sent conj (second args))
                             (when record (swap! record conj {:op :send :site site :octets (second args)}))
                             [:ok (o/ocount (second args))])
         (= op :recv)    [:ok (pop-chunk! st)]
         (= op :close)   [:ok :closed]
         :else [:abort {:reason :unsupported-op :op op}])))))
