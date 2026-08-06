(ns jolt.sim.journal-file-test
  "Executable companions for the process-crash diagnostic file adapter:
  existing-target refusal under the narrowed trusted-parent contract,
  platform-honest replacement-after-open behavior, valid header+records
  recovery, idempotent close, append-after-close/failure behavior,
  incomplete final frame preserving the valid prefix, bounded read before
  recover with hard-cap rejection, bounded path-free diagnostics, and
  first-failure retention -- all against a real java.io.FileOutputStream."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [jolt.sim.journal :as jrn]
            [jolt.sim.journal-file :as jf]))

;; ---- helpers ---------------------------------------------------------------

(defn- ba
  "Builds a byte-array from unsigned byte values (0..255)."
  [& vs]
  (byte-array (map unchecked-byte vs)))

(defn- bytes=
  "Content equality for byte arrays. Ordinary = on byte arrays is reference
  equality and is never used for contents. Every byte is compared with
  (bit-and b 0xff): raw aget sign depends on how the array was filled
  (aset-built arrays read back signed, FileInputStream-filled arrays read
  back unsigned), so normalization is required for cross-provenance
  equality, matching the journal codec's own per-byte normalization."
  [a b]
  (and (bytes? a)
       (bytes? b)
       (= (alength a) (alength b))
       (loop [i 0]
         (cond
           (= i (alength a)) true
           (not= (bit-and (aget a i) 0xff)
                 (bit-and (aget b i) 0xff)) false
           :else (recur (inc i))))))

(defn- slice [src off end]
  (java.util.Arrays/copyOfRange src off end))

(defn- pure-data?
  "Recursive structural check: only scalars and keyword-keyed maps, so no
  functions, byte arrays, throwables, host objects, or mutable aliases."
  [x]
  (cond
    (nil? x) true
    (keyword? x) true
    (string? x) true
    (number? x) true
    (boolean? x) true
    (char? x) true
    (map? x) (and (every? keyword? (keys x))
                  (every? pure-data? (vals x)))
    :else false))

;; ---- fixtures ---------------------------------------------------------------

(def ^:private run-id
  ;; Deliberately includes 0x80 and 0xff high bytes.
  (ba 0xde 0xad 0xbe 0xef 0x80 0xff 0x00 0x01
      0x7f 0x81 0xfe 0x02 0x03 0x04 0x05 0x06))

(def ^:private path-counter (atom 0))

(defn- fresh-path
  "A fresh absolute path that does not exist yet."
  []
  (str (System/getProperty "java.io.tmpdir")
       "/jolt-jf-" (swap! path-counter inc) "-" (System/nanoTime) ".bin"))

(defn- open-opts
  "Minimal valid open opts over a path."
  ([path] (open-opts path {}))
  ([path overrides]
   (merge {:path path
           :run-id run-id
           :max-payload 8
           :max-image-bytes 4096}
          overrides)))

(defn- file-bytes
  "Reads the whole file at path into a fresh byte-array (test helper)."
  [path]
  (let [fis (java.io.FileInputStream. path)
        buffer (byte-array (.length (java.io.File. path)))]
    (try
      (loop [offset 0]
        (when (< offset (alength buffer))
          (let [n (.read fis buffer offset (- (alength buffer) offset))]
            (when (pos? n)
              (recur (+ offset n))))))
      (finally (.close fis)))
    buffer))

(defn- contains-token?
  "True when the printed form of x contains the distinctive path token."
  [x token]
  (str/includes? (pr-str x) token))

;; ---- 1. existing-target refusal (narrowed trusted-parent contract) ----------------

(deftest existing-target-refusal-preserves-forensic-data
  ;; Under the trusted-parent precondition (no concurrent deletion or
  ;; replacement), the sequential exists/createNewFile refusal never
  ;; overwrites existing forensic data.
  (let [path (fresh-path)
        w (jf/open-process-crash-writer! (open-opts path))
        s0 (jf/writer-status w)]
    (is (= :healthy (:health s0)))
    (is (nil? (:failure s0)))
    (is (false? (:closed? s0)))
    (is (.exists (java.io.File. path)))
    (testing "a second open on the same path is refused"
      (let [w2 (jf/open-process-crash-writer! (open-opts path))
            s2 (jf/writer-status w2)]
        (is (= :failed (:health s2)))
        (is (= {:phase :open :reason :target-exists} (:failure s2)))
        (is (false? (:closed? s2)))
        (testing "append!/close! on the refused adapter are no-ops"
          (is (= (:failure s2) (:failure (jf/append! w2 {:payload (ba 1)}))))
          (is (= (:failure s2) (:failure (jf/close! w2)))))))
    (testing "the refused open never touched the first writer's data"
      (jf/append! w {:kind 1 :payload (ba 1 2 3)})
      (jf/append! w {:kind 2 :payload (ba 4 5)})
      (jf/close! w)
      (let [result (jrn/recover (:image (jf/read-bounded-image w)))]
        (is (nil? (:failure-reason result)))
        (is (= [0 1] (mapv :sequence (:records result))))
        (is (bytes= (ba 1 2 3) (:payload (first (:records result)))))
        (is (bytes= (ba 4 5) (:payload (second (:records result))))))))
  (testing "a path whose parent directory is missing is refused, never throws"
    (let [path (str "/tmp/jolt-no-such-dir-" (System/nanoTime) "/child.bin")
          w (jf/open-process-crash-writer! (open-opts path))
          s (jf/writer-status w)]
      (is (= :failed (:health s)))
      (is (= :create-failed (:reason (:failure s))))
      (is (false? (.exists (java.io.File. path)))))))

;; ---- 1b. replacement after open: platform-honest, path-based reads -----------------

(deftest replacement-after-open-is-platform-honest-and-path-stays-refused
  ;; Replacement is outside the trusted-parent contract. POSIX commonly
  ;; detaches the open descriptor from a recreated path; Windows may keep
  ;; the open handle associated with that path even after File.delete
  ;; reports success. Prove the portable guarantees and retain the stronger
  ;; detached-descriptor witness only when the host actually provides it.
  (let [path (fresh-path)
        w (jf/open-process-crash-writer! (open-opts path))
        _ (jf/append! w {:kind 1 :payload (ba 1 2 3)})
        unlinked? (.delete (java.io.File. path))]
    (if unlinked?
      (let [replacement (java.io.FileOutputStream. path)
            _ (.write replacement (byte-array (map unchecked-byte [0x58])))
            _ (.close replacement)
            replacement-before-append (file-bytes path)]
        (is (bytes= (ba 0x58) replacement-before-append))
        (testing "the replaced path is still refused by a new open"
          (let [w2 (jf/open-process-crash-writer! (open-opts path))
                s2 (jf/writer-status w2)]
            (is (= :failed (:health s2)))
            (is (= {:phase :open :reason :target-exists} (:failure s2)))))
        (testing "the original adapter remains healthy after out-of-contract replacement"
          (let [s (jf/append! w {:kind 2 :payload (ba 4 5)})]
            (is (= :healthy (:health s)))
            (is (= 2 (:sequence s)))))
        (jf/close! w)
        (let [path-image (file-bytes path)]
          (when (bytes= (ba 0x58) path-image)
            (testing "a detached descriptor does not clobber the replacement"
              (is (bytes= (ba 0x58) path-image))))
          (testing "read-bounded-image reflects the host's current path bytes"
            (let [read (jf/read-bounded-image w)]
              (is (= :ok (:status read)))
              (is (bytes= path-image (:image read)))))))
      (testing "targets without unlink-open semantics retain ordinary writer behavior"
        (let [s (jf/append! w {:kind 2 :payload (ba 4 5)})]
          (is (= :healthy (:health s)))
          (is (= 2 (:sequence s))))
        (jf/close! w)
        (let [result (jrn/recover (:image (jf/read-bounded-image w)))]
          (is (nil? (:failure-reason result)))
          (is (= [0 1] (mapv :sequence (:records result)))))))))

;; ---- 2. valid header+records recovery -------------------------------------------

(deftest valid-header-and-records-recover-after-close
  (let [path (fresh-path)
        w (jf/open-process-crash-writer! (open-opts path))
        payloads [(ba) (ba 1 2 3 4 5) (ba 0xff 0x80 0x00 0x7f)]
        statuses (mapv (fn [p] (jf/append! w {:kind 7 :flags 3 :payload p}))
                       payloads)]
    (is (every? #(= :healthy (:health %)) statuses))
    (is (= 3 (:sequence (last statuses))))
    (let [closed (jf/close! w)]
      (is (true? (:closed? closed)))
      (is (= :healthy (:health closed))))
    (let [read (jf/read-bounded-image w)]
      (is (= :ok (:status read)))
      (is (false? (:truncated? read)))
      (is (= (.length (java.io.File. path)) (:bytes-read read)))
      ;; The on-disk bytes equal the pure codec's segment exactly.
      (is (bytes= (jrn/encode-segment
                   {:run-id run-id
                    :max-payload 8
                    :records (mapv (fn [p] {:kind 7 :flags 3 :payload p})
                                   payloads)})
                  (:image read)))
      (let [result (jrn/recover (:image read))]
        (is (nil? (:failure-reason result)))
        (is (= 3 (count (:records result))))
        (is (= (:bytes-read read) (:last-good-offset result)))
        (doseq [[expected record] (map vector payloads (:records result))]
          (is (bytes= expected (:payload record))))))))

;; ---- 3. idempotent close ----------------------------------------------------------

(deftest close-is-idempotent
  (let [path (fresh-path)
        w (jf/open-process-crash-writer! (open-opts path))
        s1 (jf/close! w)
        s2 (jf/close! w)
        s3 (jf/writer-status w)]
    (is (true? (:closed? s1)))
    (is (= s1 s2))
    (is (= s1 s3))
    (is (= :healthy (:health s3))))
  (testing "close! on a refused adapter is a no-op that marks it closed"
    (let [path (fresh-path)
          _ (spit path "x")
          w (jf/open-process-crash-writer! (open-opts path))
          s0 (jf/writer-status w)]
      (is (= :failed (:health s0)))
      (let [s1 (jf/close! w)
            s2 (jf/close! w)]
        (is (true? (:closed? s1)))
        (is (= s1 s2))
        (is (= (:failure s0) (:failure s1)))))))

;; ---- 4. append after close / after failure -----------------------------------------

(deftest append-after-close-and-after-failure
  (testing "append! after close! is a no-op that never throws"
    (let [path (fresh-path)
          w (jf/open-process-crash-writer! (open-opts path))
          _ (jf/append! w {:kind 1 :payload (ba 1 2 3)})
          closed (jf/close! w)
          after (jf/append! w {:kind 2 :payload (ba 9 9 9 9)})
          result (jrn/recover (:image (jf/read-bounded-image w)))]
      (is (true? (:closed? after)))
      (is (= :healthy (:health after)))
      (is (= 1 (:sequence after)))
      (is (= 1 (count (:records result))))
      (is (bytes= (ba 1 2 3) (:payload (first (:records result)))))))
  (testing "append! after a writer failure is a no-op preserving the first failure"
    (let [path (fresh-path)
          w (jf/open-process-crash-writer! (open-opts path {:max-payload 4}))
          failed (jf/append! w {:payload (ba 1 2 3 4 5)})
          first-failure (:failure failed)]
      (is (= :failed (:health failed)))
      (is (= :payload-exceeds-max-payload (:reason first-failure)))
      (let [again (jf/append! w {:payload (ba 9)})
            result (jrn/recover (:image (jf/read-bounded-image w)))]
        (is (= first-failure (:failure again)))
        (is (= 0 (:sequence again)))
        ;; No record was ever written: only the header is on disk.
        (is (= [] (:records result)))
        (is (= jrn/header-size (:last-good-offset result)))
        (testing "close! after failure preserves the first failure"
          (let [closed (jf/close! w)]
            (is (true? (:closed? closed)))
            (is (= first-failure (:failure closed)))))))))

;; ---- 5. incomplete final frame preserves the valid prefix ---------------------------

(deftest incomplete-final-frame-preserves-valid-prefix
  (let [path (fresh-path)
        w (jf/open-process-crash-writer! (open-opts path))
        _ (jf/append! w {:kind 1 :payload (ba 1 2 3)})
        _ (jf/append! w {:kind 2 :payload (ba 4 5 6 7)})
        _ (jf/close! w)
        full (:image (jf/read-bounded-image w))
        record0-end (+ jrn/header-size jrn/record-overhead 3)
        ;; Cut two bytes into the second record's payload: the 24-byte fixed
        ;; prefix is present, the declared 4 payload bytes are not.
        cut (+ record0-end (- jrn/record-overhead 4) 2)
        prefix (slice full 0 cut)
        fos (java.io.FileOutputStream. path)]
    (.write fos prefix 0 (alength prefix))
    (.close fos)
    (let [read (jf/read-bounded-image w)
          result (jrn/recover (:image read))]
      (is (= :ok (:status read)))
      (is (= cut (:bytes-read read)))
      (is (= 1 (count (:records result))))
      (is (bytes= (ba 1 2 3) (:payload (first (:records result)))))
      (is (= record0-end (:last-good-offset result)))
      (is (= :incomplete-payload (:failure-reason result)))
      (is (bytes= (slice full record0-end cut) (:raw-tail result))))))

;; ---- 6. bounded read before recover -------------------------------------------------

(deftest bounded-read-caps-image-before-recover
  (let [path (fresh-path)
        w (jf/open-process-crash-writer! (open-opts path {:max-image-bytes 40}))
        _ (jf/append! w {:kind 1 :payload (ba 1 2 3)})
        _ (jf/close! w)
        read (jf/read-bounded-image w)]
    (is (= :ok (:status read)))
    (is (= 40 (:bytes-read read)))
    (is (true? (:truncated? read)))
    (is (= 40 (alength (:image read))))
    (testing "recover over the capped image keeps the valid prefix and a bounded tail"
      (let [result (jrn/recover (:image read))]
        (is (= [] (:records result)))
        (is (= jrn/header-size (:last-good-offset result)))
        (is (= :incomplete-record-header (:failure-reason result)))
        (is (= 4 (alength (:raw-tail result))))))
    (testing "an explicit larger cap reads the whole file"
      (let [full (jf/read-bounded-image w 4096)]
        (is (= :ok (:status full)))
        (is (false? (:truncated? full)))
        (is (= (.length (java.io.File. path)) (:bytes-read full)))
        (let [result (jrn/recover (:image full))]
          (is (nil? (:failure-reason result)))
          (is (= 1 (count (:records result)))))))
    (testing "an invalid cap is refused as data, never throws"
      (is (= {:status :error :reason :invalid-max-bytes}
             (jf/read-bounded-image w 0)))
      (is (= {:status :error :reason :invalid-max-bytes}
             (jf/read-bounded-image w -1)))
      (is (= {:status :error :reason :invalid-max-bytes}
             (jf/read-bounded-image w "4096")))))
  (testing "read errors are data, never throw, and carry no path"
    (let [path (str "/tmp/jolt-no-such-dir-" (System/nanoTime) "/child.bin")
          w (jf/open-process-crash-writer! (open-opts path))
          read (jf/read-bounded-image w)]
      (is (= {:status :error :reason :file-missing} read)))))

;; ---- 6b. oversized caps are rejected before any allocation -----------------------------

(deftest oversized-image-caps-are-rejected
  (testing "configured caps above max-safe-image-bytes fail open as data"
    (doseq [bad [(inc jf/max-safe-image-bytes)
                 18446744073709551615]]
      (let [path (fresh-path)
            w (jf/open-process-crash-writer! (open-opts path {:max-image-bytes bad}))
            s (jf/writer-status w)]
        (is (= :failed (:health s)))
        (is (= {:phase :open :reason :invalid-max-image-bytes} (:failure s)))
        (is (false? (.exists (java.io.File. path)))
            "no file is created for a rejected cap"))))
  (testing "override caps above max-safe-image-bytes are refused as data"
    (let [path (fresh-path)
          w (jf/open-process-crash-writer! (open-opts path))]
      (is (= {:status :error :reason :invalid-max-bytes}
             (jf/read-bounded-image w (inc jf/max-safe-image-bytes))))
      (is (= {:status :error :reason :invalid-max-bytes}
             (jf/read-bounded-image w 18446744073709551615))))))

;; ---- 6c. read failure paths are bounded data --------------------------------------------

(deftest read-failure-paths-are-bounded-data
  (testing "a target replaced by a directory is refused as data"
    (let [path (fresh-path)
          w (jf/open-process-crash-writer! (open-opts path))
          _ (jf/close! w)
          _ (.delete (java.io.File. path))
          _ (.mkdir (java.io.File. path))
          read (jf/read-bounded-image w)]
      (is (= {:status :error :reason :target-is-directory} read)))))

(deftest read-and-close-failure-precedence
  (let [combine (deref #'jf/result-after-close)
        ok {:status :ok :image (ba 1) :bytes-read 1 :truncated? false}
        read-failure {:status :error :reason :read-threw :class "read-class"}
        close-failure {:status :error :reason :close-threw :class "close-class"}]
    (testing "a close failure replaces an apparent successful read"
      (is (= close-failure (combine ok close-failure))))
    (testing "an earlier read failure remains primary if close also fails"
      (is (= read-failure (combine read-failure close-failure))))
    (testing "a successful close leaves the read result unchanged"
      (is (= ok (combine ok nil))))))

;; ---- 6d. failing-open diagnostics are bounded and path-free --------------------------------

(deftest failing-open-diagnostics-are-bounded-and-path-free
  (testing "refusal and create failures carry no message and no path"
    (doseq [[label make-path expected]
            [["missing parent"
              (fn [token]
                (str (System/getProperty "java.io.tmpdir") "/" token "/child.bin"))
              {:phase :open :reason :create-failed}]
             ["existing target"
              (fn [token]
                (let [p (str (System/getProperty "java.io.tmpdir") "/" token ".bin")]
                  (spit p "x")
                  p))
              {:phase :open :reason :target-exists}]
             ["cap above the hard maximum"
              (fn [token]
                (str (System/getProperty "java.io.tmpdir") "/" token ".bin"))
              {:phase :open :reason :invalid-max-image-bytes}]]]
      (testing label
        (let [token (str "jolt-jf-token-" (System/nanoTime))
              ;; Embed the distinctive token in the path so any path leak
              ;; into the diagnostic is detectable.
              path (make-path token)
              opts (cond-> (open-opts path)
                     (= label "cap above the hard maximum")
                     (assoc :max-image-bytes (inc jf/max-safe-image-bytes)))
              w (jf/open-process-crash-writer! opts)
              failure (:failure (jf/writer-status w))]
          (is (= expected failure))
          (is (not (contains? failure :message)))
          (is (not (contains-token? failure token)))
          (is (pure-data? failure)))))))

;; ---- 6e. trusted path-level bounded reader -------------------------------------------

(deftest read-bounded-path-matches-the-adapter-reader
  (let [path (fresh-path)
        w (jf/open-process-crash-writer! (open-opts path))
        _ (jf/append! w {:kind 1 :payload (ba 1 2 3)})
        _ (jf/append! w {:kind 7 :flags 3 :payload (ba 0xff 0x80)})
        _ (jf/close! w)
        via-adapter (jf/read-bounded-image w)
        via-path (jf/read-bounded-path path 4096)]
    (is (= :ok (:status via-adapter)))
    (is (= :ok (:status via-path)))
    (is (= (dissoc via-adapter :image) (dissoc via-path :image)))
    (is (bytes= (:image via-adapter) (:image via-path)))
    (testing "both arities delegate with the same cap semantics"
      (let [capped-adapter (jf/read-bounded-image w 40)
            capped-path (jf/read-bounded-path path 40)]
        (is (= (dissoc capped-adapter :image) (dissoc capped-path :image)))
        (is (= 40 (:bytes-read capped-path)))
        (is (true? (:truncated? capped-path)))
        (is (bytes= (:image capped-adapter) (:image capped-path)))))
    (testing "the path read matches the on-disk bytes exactly"
      (is (bytes= (jrn/encode-segment
                   {:run-id run-id
                    :max-payload 8
                    :records [{:kind 1 :flags 0 :payload (ba 1 2 3)}
                              {:kind 7 :flags 3 :payload (ba 0xff 0x80)}]})
                  (:image via-path))))))

(deftest read-bounded-path-diagnostics-are-bounded-and-path-free
  (testing "a missing target is refused as data"
    (is (= {:status :error :reason :file-missing}
           (jf/read-bounded-path
            (str "/tmp/jolt-no-such-dir-" (System/nanoTime) "/child.bin")
            4096))))
  (testing "a directory target is refused as data"
    (is (= {:status :error :reason :target-is-directory}
           (jf/read-bounded-path (System/getProperty "java.io.tmpdir") 4096))))
  (testing "a cap below the file size reports truncation"
    (let [path (fresh-path)
          w (jf/open-process-crash-writer! (open-opts path))
          _ (jf/append! w {:kind 1 :payload (ba 1 2 3)})
          _ (jf/close! w)
          full (jf/read-bounded-path path 4096)
          capped (jf/read-bounded-path path 40)]
      (is (= :ok (:status full)))
      (is (false? (:truncated? full)))
      (is (= :ok (:status capped)))
      (is (= 40 (:bytes-read capped)))
      (is (= 40 (alength (:image capped))))
      (is (true? (:truncated? capped)))
      (is (bytes= (slice (:image full) 0 40) (:image capped)))))
  (testing "invalid caps and non-string paths are refused as data, never throw"
    (doseq [bad [0 -1 "4096" (inc jf/max-safe-image-bytes)
                 18446744073709551615]]
      (is (= {:status :error :reason :invalid-max-bytes}
             (jf/read-bounded-path (fresh-path) bad))))
    (is (= {:status :error :reason :invalid-path}
           (jf/read-bounded-path 42 4096)))
    (is (= {:status :error :reason :invalid-path}
           (jf/read-bounded-path nil 4096))))
  (testing "errors carry no path token, message, or live object"
    (let [token (str "jolt-jf-token-" (System/nanoTime))
          path (str (System/getProperty "java.io.tmpdir") "/" token ".bin")
          read (jf/read-bounded-path path 4096)]
      (is (= {:status :error :reason :file-missing} read))
      (is (not (contains-token? read token)))
      (is (pure-data? read)))))

;; ---- 7. first-failure retention ------------------------------------------------------

(deftest first-failure-retention
  (testing "the first writer failure survives later appends and close!"
    (let [path (fresh-path)
          w (jf/open-process-crash-writer! (open-opts path {:max-payload 4}))
          first-failure (:failure (jf/append! w {:payload (ba 1 2 3 4 5)}))]
      (is (some? first-failure))
      (doseq [later [(jf/append! w {:payload (ba 9)})
                     (jf/writer-status w)
                     (jf/close! w)
                     (jf/append! w {:payload (ba 9 9)})
                     (jf/writer-status w)]]
        (is (= first-failure (:failure later))))))
  (testing "a refused open failure is stable across append!/close!/status"
    (let [path (fresh-path)
          _ (spit path "x")
          w (jf/open-process-crash-writer! (open-opts path))
          first-failure (:failure (jf/writer-status w))]
      (is (= {:phase :open :reason :target-exists} first-failure))
      (doseq [later [(jf/append! w {:payload (ba 1)})
                     (jf/close! w)
                     (jf/writer-status w)]]
        (is (= first-failure (:failure later))))))
  (testing "status is pure data with exactly the public keys"
    (let [path (fresh-path)
          w (jf/open-process-crash-writer! (open-opts path))
          _ (jf/append! w {:payload (ba 1)})
          status (jf/writer-status w)]
      (is (= #{:health :failure :sequence :previous-crc32c
               :accepted-end :synced-end :durability :closed?}
             (set (keys status))))
      (is (pure-data? status))
      (is (= status (jf/writer-status w))))))
