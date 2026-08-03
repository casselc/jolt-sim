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
