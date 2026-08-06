(ns jolt.sim.activity-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.sim.activity :as act]
            [jolt.sim.journal :as journal]
            [jolt.sim.journal-file :as jf]))

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
