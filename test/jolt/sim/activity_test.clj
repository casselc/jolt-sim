(ns jolt.sim.activity-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.sim.activity :as act]
            [jolt.sim.journal :as journal]
            [jolt.sim.journal-file :as jf]
            [jolt.sim.trace :as trace]))

(defn- ba [& values] (byte-array (map unchecked-byte values)))

(def ^:private run-id
  (ba 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16))

(def ^:private path-counter (atom 0))

(defn- fresh-path []
  (str (System/getProperty "java.io.tmpdir")
       "/jolt-activity-" (swap! path-counter inc) "-"
       (System/nanoTime) ".bin"))

(defn- open-observer []
  (let [path (fresh-path)]
    {:path path
     :observer (act/open-observer! {:path path :run-id run-id})}))

(defn- recovered-records [observer]
  (let [read-result (jf/read-bounded-image (:adapter observer))]
    (is (= :ok (:status read-result)))
    (is (false? (:truncated? read-result)))
    (:records (journal/recover (:image read-result)))))

(deftest disabled-emission-is-a-no-op
  (is (nil? (act/current-observer)))
  (is (nil? (act/emit! :deliberately-invalid)))
  (is (nil? (act/current-observer))))

(deftest closed-event-shape-is-enforced-and-absorbing
  (doseq [invalid [[:not-namespaced nil nil {}]
                   [::event :reserved nil {}]
                   [::event nil :reserved {}]
                   [::event nil nil []]
                   [::event nil nil {} :extra]
                   {::event {}}]]
    (let [{:keys [observer]} (open-observer)]
      (act/call-with-observer observer #(act/emit! invalid))
      (let [first-status (act/observer-status observer)]
        (is (= :failed (:health first-status)))
        (is (= {:phase :schema :reason :invalid-event}
               (:failure first-status)))
        (act/call-with-observer
         observer
         #(act/emit! [::later nil nil {:must-not-write true}]))
        (is (= first-status (act/observer-status observer))))
      (act/close-observer! observer))))

(deftest canonical-edn-is-stable-for-compound-keys
  (let [{left :observer} (open-observer)
        {right :observer} (open-observer)
        left-data {{:z 2 :a 1} {:nested {:y 2 :b 1}}}
        right-data {{:a 1 :z 2} {:nested {:b 1 :y 2}}}]
    (act/call-with-observer left #(act/emit! [::event nil nil left-data]))
    (act/call-with-observer right #(act/emit! [::event nil nil right-data]))
    (act/close-observer! left)
    (act/close-observer! right)
    (let [left-record (first (recovered-records left))
          right-record (first (recovered-records right))]
      (is (= (seq (:payload left-record)) (seq (:payload right-record))))
      (is (= [::event nil nil left-data]
             (read-string (String. (:payload left-record) "UTF-8")))))))

(deftest unsupported-and-sensitive-values-fail-closed
  (doseq [data [{:host (Object.)}
                {:throwable (ex-info "secret-path-/tmp/credential" {})}
                {:function (fn [] :no)}
                {(ba 1 2) :mutable-key}
                {:bytes (ba 3 4)}
                {:metadata (with-meta {:a 1} {:secret true})}]]
    (let [{:keys [observer]} (open-observer)]
      (act/call-with-observer observer #(act/emit! [::event nil nil data]))
      (let [status (act/observer-status observer)]
        (is (= :failed (:health status)))
        (is (= {:phase :encode :reason :unsupported-event-data}
               (:failure status)))
        (is (not (.contains (pr-str status) "secret-path"))))
      (act/close-observer! observer))))

(deftest oversized-payload-fails-before-journal-append
  (let [{:keys [observer]} (open-observer)
        oversized (apply str (repeat 20000 \x))]
    (act/call-with-observer
     observer
     #(act/emit! [::event nil nil {:value oversized}]))
    (let [status (act/observer-status observer)]
      (is (= :failed (:health status)))
      (is (= :payload-exceeds-max-payload (:reason (:failure status))))
      (is (= 0 (:sequence status)))
      (is (= 0 (:accepted status))))
    (act/close-observer! observer)))

(deftest journal-failure-is-absorbing
  (let [path (fresh-path)
        _ (spit path "existing")
        observer (act/open-observer! {:path path :run-id run-id})
        before (act/observer-status observer)]
    (is (= :failed (:health before)))
    (act/call-with-observer
     observer
     #(act/emit! [::event nil nil {:ignored true}]))
    (is (= before (act/observer-status observer)))
    (act/close-observer! observer)))

(deftest concurrent-emission-cannot-exceed-record-cap
  (let [{:keys [observer]} (open-observer)]
    (act/call-with-observer
     observer
     (fn []
       (let [workers (mapv (fn [worker]
                             (future
                               (is (identical? observer (act/current-observer)))
                               (dotimes [index 48]
                                 (act/emit! [::tick nil nil
                                             {:worker worker :index index}]))))
                           (range 8))]
         (doseq [worker workers] @worker))))
    (let [status (act/observer-status observer)]
      (is (= :healthy (:health status)))
      (is (= act/max-records (:accepted status)))
      (is (= act/max-records (:sequence status)))
      (is (true? (:capped? status))))
    (act/close-observer! observer)))

(deftest maximum-valid-run-is-readable-without-truncation
  (let [{:keys [observer]} (open-observer)
        value (apply str (repeat 15000 \x))]
    (act/call-with-observer
     observer
     (fn []
       (dotimes [index act/max-records]
         (act/emit! [::large nil nil {:index index :value value}]))))
    (let [closed (act/close-observer! observer)
          read-result (jf/read-bounded-image (:adapter observer))]
      (is (true? (:closed? closed)))
      (is (= :healthy (:health closed)))
      (is (= act/max-records (:sequence closed)))
      (is (= :ok (:status read-result)))
      (is (false? (:truncated? read-result)))
      (is (= act/max-records
             (count (:records (journal/recover (:image read-result)))))))))

(deftest binding-restores-and-body-exception-remains-primary
  (let [{:keys [observer]} (open-observer)
        boom (ex-info "primary" {::primary true})]
    (is (nil? (act/current-observer)))
    (is (= :value
           (act/call-with-observer
            observer
            (fn []
              (is (identical? observer (act/current-observer)))
              :value))))
    (is (nil? (act/current-observer)))
    (try
      (act/call-with-observer observer #(throw boom))
      (is false "body exception must propagate")
      (catch Throwable caught
        (is (identical? boom caught))))
    (is (nil? (act/current-observer)))
    (is (true? (:closed? (act/close-observer! observer))))))

;; ---- recovery paging ----------------------------------------------------------------

(defn- event-payload
  "Canonical EDN payload bytes for one closed v1 event."
  [event]
  (.getBytes (trace/canonical-edn event) "UTF-8"))

(defn- activity-image
  "A complete physically valid activity journal image for events."
  [events]
  (journal/encode-segment
   {:run-id run-id
    :max-payload act/max-payload-bytes
    :records (mapv (fn [event]
                     {:kind 1 :flags 0 :payload (event-payload event)})
                   events)}))

(defn- ok-read
  "Synthesizes a bounded read-result over image without file I/O."
  ([image] (ok-read image false))
  ([image truncated?]
   {:status :ok
    :image image
    :bytes-read (alength image)
    :truncated? truncated?}))

(defn- slice [src off end]
  (java.util.Arrays/copyOfRange src off end))

(defn- page-pure?
  "Recursive structural check: page output holds only immutable EDN-domain
  values -- never byte arrays, throwables, or host objects."
  [x]
  (cond
    (nil? x) true
    (keyword? x) true
    (symbol? x) true
    (string? x) true
    (number? x) true
    (boolean? x) true
    (char? x) true
    (bytes? x) false
    (map? x) (and (every? page-pure? (keys x))
                  (every? page-pure? (vals x)))
    (or (vector? x) (sequential? x) (set? x)) (every? page-pure? x)
    :else false))

(deftest canonical-events-paginate-in-bounded-pages
  (let [events (mapv (fn [i] [::tick nil nil {:index i}]) (range 70))
        image (activity-image events)
        read (ok-read image)
        page1 (act/recover-page read 0)
        page2 (act/recover-page read 32)
        page3 (act/recover-page read 64)]
    (is (= #{:version :cursor :next-cursor :accepted-count :remaining?
             :events :recovery}
           (set (keys page1))))
    (is (= 1 (:version page1)))
    (is (= #{:status :reason :sequence :last-good-offset :raw-tail-bytes
             :image-truncated? :class}
           (set (keys (:recovery page1)))))
    (doseq [page [page1 page2 page3]]
      (is (page-pure? page)))
    (is (= {:status :complete
            :reason nil
            :sequence 70
            :last-good-offset (alength image)
            :raw-tail-bytes 0
            :image-truncated? false
            :class nil}
           (:recovery page1)))
    (is (= 70 (:accepted-count page1)))
    (is (= 0 (:cursor page1)))
    (is (= 32 (:next-cursor page1)))
    (is (true? (:remaining? page1)))
    (is (= (range 32) (map :sequence (:events page1))))
    (is (= (subvec events 0 32) (mapv :event (:events page1))))
    (is (= 70 (:accepted-count page2)))
    (is (= 32 (:cursor page2)))
    (is (= 64 (:next-cursor page2)))
    (is (true? (:remaining? page2)))
    (is (= (subvec events 32 64) (mapv :event (:events page2))))
    (is (= 70 (:accepted-count page3)))
    (is (= 64 (:cursor page3)))
    (is (= 70 (:next-cursor page3)))
    (is (false? (:remaining? page3)))
    (is (= (subvec events 64 70) (mapv :event (:events page3))))
    (is (= (:recovery page1) (:recovery page3)))))

(deftest page-byte-budget-binds-before-event-count
  ;; 10 events of ~14970 canonical bytes each: 8 fit the 131072-byte
  ;; budget (119760), a 9th would exceed it (134730) while the 32-event
  ;; count limit would still allow 23 more.
  (let [value (apply str (repeat 14900 \x))
        events (mapv (fn [i] [::large nil nil {:index i :value value}])
                     (range 10))
        image (activity-image events)
        read (ok-read image)
        page1 (act/recover-page read 0)
        page2 (act/recover-page read (:next-cursor page1))]
    (is (< (alength (event-payload (first events))) act/max-payload-bytes))
    (is (= 10 (:accepted-count page1)))
    (is (= 8 (:next-cursor page1)))
    (is (true? (:remaining? page1)))
    (is (= (subvec events 0 8) (mapv :event (:events page1))))
    (is (= 10 (:accepted-count page2)))
    (is (= 10 (:next-cursor page2)))
    (is (false? (:remaining? page2)))
    (is (= :complete (:status (:recovery page2))))))

(deftest recovery-enforces-the-writer-record-quota
  (let [events (mapv (fn [i] [::tick nil nil {:index i}])
                     (range (inc act/max-records)))
        image (activity-image events)
        read (ok-read image)
        first-page (act/recover-page read 0)
        end-page (act/recover-page read act/max-records)]
    (is (= act/max-records (:accepted-count first-page)))
    (is (= :partial (get-in first-page [:recovery :status])))
    (is (= :record-quota-exceeded
           (get-in first-page [:recovery :reason])))
    (is (= act/max-records (get-in first-page [:recovery :sequence])))
    (is (= act/max-records (:accepted-count end-page)))
    (is (= [] (:events end-page)))
    (is (false? (:remaining? end-page)))
    (let [caught (try
                   (act/recover-page read (inc act/max-records))
                   nil
                   (catch :default t t))]
      (is (= :jolt.sim.activity/invalid-cursor
             (:type (ex-data caught)))))))

(deftest cursor-validation-is-fail-closed
  (let [image (activity-image (mapv (fn [i] [::tick nil nil {:index i}])
                                    (range 3)))
        read (ok-read image)]
    (testing "the end cursor returns an empty complete page"
      (let [page (act/recover-page read 3)]
        (is (= 3 (:cursor page)))
        (is (= 3 (:next-cursor page)))
        (is (= 3 (:accepted-count page)))
        (is (= [] (:events page)))
        (is (false? (:remaining? page)))
        (is (= :complete (:status (:recovery page))))))
    (testing "a cursor beyond the recovered prefix throws typed ex-info"
      (let [caught (try
                     (act/recover-page read 4)
                     nil
                     (catch :default t t))]
        (is (some? caught))
        (is (= :jolt.sim.activity/invalid-cursor (:type (ex-data caught))))
        (is (= :cursor-beyond-recovery (:reason (ex-data caught))))
        (is (= {:cursor 4 :accepted 3}
               (select-keys (ex-data caught) [:cursor :accepted])))))
    (testing "negative and non-integer cursors throw typed ex-info"
      (doseq [[label cursor reason]
              [["negative" -1 :negative-cursor]
               ["string" "0" :non-integer-cursor]
               ["nil" nil :non-integer-cursor]]]
        (testing label
          (let [caught (try
                         (act/recover-page read cursor)
                         nil
                         (catch :default t t))]
            (is (= :jolt.sim.activity/invalid-cursor
                   (:type (ex-data caught))))
            (is (= reason (:reason (ex-data caught))))))))))

(deftest torn-and-corrupt-physical-suffix-preserves-valid-prefix
  (let [events (mapv (fn [i] [::tick nil nil {:index i}]) (range 3))
        image (activity-image events)
        full (alength image)
        last-payload (alength (event-payload (last events)))
        last-record-offset (- full journal/record-overhead last-payload)]
    (testing "a torn final trailer keeps the valid prefix"
      (let [torn (slice image 0 (- full 3))
            page (act/recover-page (ok-read torn) 0)]
        (is (= 2 (:accepted-count page)))
        (is (= (subvec events 0 2) (mapv :event (:events page))))
        (is (= {:status :partial
                :reason :incomplete-trailer
                :sequence 2
                :last-good-offset last-record-offset
                :raw-tail-bytes (- (alength torn) last-record-offset)
                :image-truncated? false
                :class nil}
               (:recovery page)))
        (is (page-pure? page))))
    (testing "a corrupt final record crc keeps the valid prefix"
      (let [corrupt (java.util.Arrays/copyOf image full)
            _ (aset corrupt (+ last-record-offset 24) (unchecked-byte 0x7e))
            page (act/recover-page (ok-read corrupt) 0)]
        (is (= 2 (:accepted-count page)))
        (is (= {:status :partial
                :reason :bad-record-crc
                :sequence 2
                :last-good-offset last-record-offset
                :raw-tail-bytes (- full last-record-offset)
                :image-truncated? false
                :class nil}
               (:recovery page)))))
    (testing "a rejected header fails with no valid prefix"
      (let [corrupt (java.util.Arrays/copyOf image full)
            _ (aset corrupt 0 (unchecked-byte 0x00))
            page (act/recover-page (ok-read corrupt) 0)]
        (is (= [] (:events page)))
        (is (= {:status :failed
                :reason :bad-header-magic
                :sequence 0
                :last-good-offset 0
                :raw-tail-bytes full
                :image-truncated? false
                :class nil}
               (:recovery page)))))
    (testing "a shorter-than-header image fails with no valid prefix"
      (let [page (act/recover-page (ok-read (slice image 0 20)) 0)]
        (is (= :failed (:status (:recovery page))))
        (is (= :incomplete-header (:reason (:recovery page))))
        (is (= 0 (:last-good-offset (:recovery page))))
        (is (= 20 (:raw-tail-bytes (:recovery page))))))))

(deftest wrong-kind-or-flags-stop-acceptance-without-resynchronizing
  (let [good (mapv (fn [i] (event-payload [::tick nil nil {:index i}]))
                   (range 3))
        base {:run-id run-id :max-payload act/max-payload-bytes}]
    (testing "a kind other than 1 is semantic corruption"
      (let [image (journal/encode-segment
                   (assoc base :records
                          [{:kind 1 :flags 0 :payload (nth good 0)}
                           {:kind 2 :flags 0 :payload (nth good 1)}
                           {:kind 1 :flags 0 :payload (nth good 2)}]))
            page (act/recover-page (ok-read image) 0)]
        (is (= 1 (:accepted-count page)))
        (is (= [{:sequence 0 :event [::tick nil nil {:index 0}]}]
               (:events page)))
        (is (= {:status :partial
                :reason :unexpected-kind
                :sequence 1
                :last-good-offset (+ journal/header-size
                                     journal/record-overhead
                                     (alength (nth good 0)))
                :raw-tail-bytes (- (alength image)
                                   journal/header-size
                                   journal/record-overhead
                                   (alength (nth good 0)))
                :image-truncated? false
                :class nil}
               (:recovery page)))
        (is (false? (:remaining? page)))))
    (testing "flags other than 0 are semantic corruption"
      (let [image (journal/encode-segment
                   (assoc base :records
                          [{:kind 1 :flags 0 :payload (nth good 0)}
                           {:kind 1 :flags 1 :payload (nth good 1)}
                           {:kind 1 :flags 0 :payload (nth good 2)}]))
            page (act/recover-page (ok-read image) 0)]
        (is (= 1 (:accepted-count page)))
        (is (= :unexpected-flags (:reason (:recovery page))))
        (is (= 1 (:sequence (:recovery page))))))))

(deftest payload-edn-is-strictly-one-canonical-event
  (let [payload-image (fn [payload]
                        (journal/encode-segment
                         {:run-id run-id
                          :max-payload act/max-payload-bytes
                          :records [{:kind 1 :flags 0 :payload payload}]}))
        recovery-of (fn [payload]
                      (:recovery
                       (act/recover-page (ok-read (payload-image payload)) 0)))]
    (doseq [[label payload reason]
            [["malformed EDN"
              (.getBytes "[:a/b nil nil {" "UTF-8")
              :invalid-event-encoding]
             ["empty payload"
              (byte-array 0)
              :invalid-event-encoding]
             ["invalid UTF-8"
              (byte-array [(unchecked-byte 0xc3) (unchecked-byte 0x28)])
              :invalid-event-encoding]
             ["multiple forms"
              (.getBytes "[:a/b nil nil {}] [:c/d nil nil {}]" "UTF-8")
              :non-canonical-event]
             ["noncanonical map key order"
              (.getBytes "[:a/b nil nil {:z 1 :a 2}]" "UTF-8")
              :non-canonical-event]
             ["trailing whitespace"
              (.getBytes "[:a/b nil nil {:a 1}] " "UTF-8")
              :non-canonical-event]
             ["wrong shape: data is not a map"
              (.getBytes "[:a/b nil nil []]" "UTF-8")
              :invalid-event-shape]
             ["wrong shape: tag is not namespaced"
              (.getBytes "[:a nil nil {}]" "UTF-8")
              :invalid-event-shape]
             ["wrong shape: not a vector"
              (.getBytes "{:a 1}" "UTF-8")
              :invalid-event-shape]]]
      (testing label
        (let [recovery (recovery-of payload)]
          (is (= :partial (:status recovery)))
          (is (= reason (:reason recovery)))
          (is (= 0 (:sequence recovery)))
          (is (= journal/header-size (:last-good-offset recovery)))
          (is (nil? (:class recovery))))))
    (testing "the exact canonical encoding is accepted"
      (let [event [::tick nil nil {:z 1 :a 2}]
            payload (event-payload event)
            page (act/recover-page (ok-read (payload-image payload)) 0)]
        (is (= :complete (:status (:recovery page))))
        (is (= [{:sequence 0 :event event}] (:events page)))))))

(deftest cap-truncated-reads-never-look-complete
  (let [events (mapv (fn [i] [::tick nil nil {:index i}]) (range 3))
        image (activity-image events)
        full (alength image)
        payload0 (alength (event-payload (first events)))
        record0-end (+ journal/header-size journal/record-overhead payload0)]
    (testing "a cap exactly on a frame boundary is partial, never complete"
      (let [page (act/recover-page (ok-read (slice image 0 record0-end) true) 0)]
        (is (= 1 (:accepted-count page)))
        (is (false? (:remaining? page)))
        (is (= {:status :partial
                :reason :input-truncated
                :sequence 1
                :last-good-offset record0-end
                :raw-tail-bytes 0
                :image-truncated? true
                :class nil}
               (:recovery page)))))
    (testing "a real truncated path read stays partial end to end"
      (let [path (str (System/getProperty "java.io.tmpdir")
                      "/jolt-activity-read-" (System/nanoTime) ".bin")
            fos (java.io.FileOutputStream. path)]
        (.write fos image 0 full)
        (.close fos)
        (let [read (jf/read-bounded-path path record0-end)
              page (act/recover-page read 0)]
          (is (= :ok (:status read)))
          (is (= record0-end (:bytes-read read)))
          (is (true? (:truncated? read)))
          (is (= 1 (:accepted-count page)))
          (is (= {:status :partial
                  :reason :input-truncated
                  :sequence 1
                  :last-good-offset record0-end
                  :raw-tail-bytes 0
                  :image-truncated? true
                  :class nil}
                 (:recovery page))))))
    (testing "a cap mid-frame reports the physical halt and stays partial"
      (let [page (act/recover-page
                  (ok-read (slice image 0 (+ record0-end 10)) true) 0)]
        (is (= 1 (:accepted-count page)))
        (is (= {:status :partial
                :reason :incomplete-record-header
                :sequence 1
                :last-good-offset record0-end
                :raw-tail-bytes 10
                :image-truncated? true
                :class nil}
               (:recovery page)))))))

(deftest read-failure-and-malformed-read-results-fail-closed
  (testing "a read error produces a failed page, never throws"
    (let [page (act/recover-page {:status :error :reason :file-missing} 0)]
      (is (= 0 (:accepted-count page)))
      (is (= [] (:events page)))
      (is (false? (:remaining? page)))
      (is (= {:status :failed
              :reason :file-missing
              :sequence 0
              :last-good-offset 0
              :raw-tail-bytes 0
              :image-truncated? false
              :class nil}
             (:recovery page)))
      (is (page-pure? page))))
  (testing "a read error propagates the bounded class string, never a Throwable"
    (let [page (act/recover-page
                {:status :error :reason :open-threw :class "bounded-class"} 0)]
      (is (= :failed (:status (:recovery page))))
      (is (= "bounded-class" (:class (:recovery page))))
      (is (page-pure? page))))
  (testing "a nonzero cursor on a failed read is beyond recovery"
    (let [caught (try
                   (act/recover-page {:status :error :reason :file-missing} 1)
                   nil
                   (catch :default t t))]
      (is (= :jolt.sim.activity/invalid-cursor (:type (ex-data caught))))
      (is (= :cursor-beyond-recovery (:reason (ex-data caught))))))
  (testing "malformed read-results throw typed ex-info"
    (doseq [[label read-result]
            [["not a map" 42]
             ["unknown status" {:status :mystery}]
             ["ok without a byte image"
              {:status :ok :image "not-bytes" :bytes-read 1 :truncated? false}]
             ["ok with mismatched byte count"
              {:status :ok :image (byte-array 1)
               :bytes-read 0 :truncated? false}]
             ["ok beyond the activity image bound"
              {:status :ok :image (byte-array (inc act/max-image-bytes))
               :bytes-read (inc act/max-image-bytes) :truncated? false}]
             ["ok with non-boolean truncation"
              {:status :ok :image (byte-array 0)
               :bytes-read 0 :truncated? :yes}]
             ["error without a keyword reason" {:status :error :reason 42}]]]
      (testing label
        (let [caught (try
                       (act/recover-page read-result 0)
                       nil
                       (catch :default t t))]
          (is (= :jolt.sim.activity/invalid-read-result
                 (:type (ex-data caught)))))))))
