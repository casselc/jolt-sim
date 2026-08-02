(ns jolt.sim.fixtures.http-sqlite-scenarios
  "Narrow scenario wrappers that run the UNCHANGED
  jolt.sim.fixtures.http-sqlite application under the shared hermetic POSIX
  loopback plus SQLite handler packs, parameterized by one canonical scenario
  input.

  This namespace knows nothing about Hegel or the process-explorer parent. A
  fresh worker resolves one scenario here by its namespaced symbol and drives it
  with one canonical input value naming the stream capacity, pipe capacity, and
  an optional captured poll EINTR activation ordinal. The application, HTTP,
  TCP, net, DB, SQLite, and POSIX implementations are all reused unchanged from
  their existing namespaces; only the deterministic world-building glue that the
  in-process integration test already performs is re-parameterized here.

  Each scenario is a plain function carrying the same :jolt.sim/scenario and
  :jolt.sim/accepts-input markers defsim produces, so it satisfies the
  jolt.sim.explore-worker single-run protocol v2 without depending on the defsim
  macro: defsim's declared-config form cannot both build the input-dependent
  handler packs and publish world-owned cleanup evidence from one body, so this
  wrapper calls jolt.sim.runtime/run-controlled directly and returns one
  canonical evidence map (HTTP response projection plus route, cleanup,
  capacity, and fault evidence) sized to catch a model bypass. A regression
  failure carries bounded case data because the caller's drawn input is the only
  per-case variable."
  (:require [jolt.net :as net]
            [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.http-sqlite :as fixture]
            [jolt.sim.handler-pack :as hp]
            [jolt.sim.net.posix-fault :as posix-fault]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.runtime :as rt]
            [jolt.sim.sqlite :as sqlite]))

(def ^:private expected-blob-octets [0 65 127 128 255])

(defn- foreign-symbols [effect-trace]
  (->> effect-trace
       (keep (fn [entry]
               (let [descriptor (:descriptor entry)]
                 (when (= :foreign-function (:kind descriptor))
                   (:symbol descriptor)))))
       set
       sort
       vec))

(defn- statement-plans
  "The four exact SQLite plans the unchanged fixture drives over an in-memory
  connection: PRAGMA foreign_keys=1 (run by db.sqlite connection init), create,
  insert, and select. Reused unchanged from the in-process integration test."
  []
  [{:sql "PRAGMA foreign_keys=1;"
    :params {}
    :columns []
    :rows []
    :changes 0
    :last-row-id 0}
   {:sql "create table sim_blob (id integer primary key, payload blob)"
    :params {}
    :columns []
    :rows []
    :changes 0
    :last-row-id 0}
   {:sql "insert into sim_blob (id, payload) values (?, ?)"
    :params {1 {:type :integer :value 1}
             2 {:type :blob
                :value (byte-array expected-blob-octets)}}
    :columns []
    :rows []
    :changes 1
    :last-row-id 1}
   {:sql "select payload from sim_blob where id = ?"
    :params {1 {:type :integer :value 1}}
    :columns ["payload"]
    :rows [[{:type :blob
             :value (byte-array expected-blob-octets)}]]
    :changes 0
    :last-row-id 1}])

(defn- interrupt-poll-plan
  "One captured-EINTR fault plan that fires exactly once on the poll call whose
  frontend-owned per-poll ordinal equals `ordinal`. Mirrors the in-process
  integration test's interrupt-first-poll plan with a parameterized
  :on-match activation."
  [ordinal]
  [{:id :http-sqlite/interrupt-poll
    :match {:boundary :posix :operation :poll}
    :activation {:on-match ordinal :times 1}
    :outcome {:kind :captured-error :errno :eintr}}])

(defn- evidence-for
  "Builds the hermetic POSIX + SQLite handler packs from `input`, runs the
  unchanged fixture once under run-controlled, and returns one canonical
  evidence map: the ordinary HTTP response projection plus route, cleanup,
  capacity, and fault evidence sufficient to catch a model bypass.

  `overrides` is the process-explorer worker's runtime-overrides map (empty for
  a no-schedule case; :future-schedule when supplied). It is merged over this
  wrapper's :ffi-handlers/:drain-timeout-ms config so the worker protocol's
  override path is retained."
  [overrides input]
  (let [{:keys [stream-capacity pipe-capacity poll-eintr-ordinal]} input
        mem (memory/world)
        sqlite-world (sqlite/world mem (statement-plans))
        ;; The finite per-socket receive FIFO and self-pipe capacities are the
        ;; per-case variables; every HTTP octet crosses the capacity model.
        posix-world (posix/world mem (net/target-descriptor)
                                 {:stream-capacity stream-capacity
                                  :pipe-capacity pipe-capacity})
        fault? (some? poll-eintr-ordinal)
        fault-frontend (when fault?
                         (posix-fault/frontend posix-world
                                               (interrupt-poll-plan
                                                poll-eintr-ordinal)))
        ;; Three named packs over one shared memory world: memory + SQLite
        ;; foreign handlers, plus the POSIX foreign set (fault-interposed at
        ;; poll when an EINTR ordinal was drawn, plain otherwise). No plain
        ;; merge across complete packs.
        posix-foreign (if fault?
                        (posix-fault/foreign-handlers fault-frontend)
                        (posix/foreign-handlers posix-world))
        handlers (hp/compose
                  (hp/pack :jolt.sim/memory (memory/handlers mem))
                  (hp/pack :jolt.sim/sqlite (sqlite/foreign-handlers sqlite-world))
                  (hp/pack :jolt.sim/posix posix-foreign))
        controlled (rt/run-controlled
                    (merge {:ffi-handlers handlers
                            :drain-timeout-ms 10000}
                           overrides)
                    fixture/exercise-http-sqlite)
        parsed (get-in controlled [:result :parsed])
        effect-trace (:effect-trace controlled)]
    {:http {:status (:status parsed)
            :content-type (get (:headers parsed) "content-type")
            :content-length (get (:headers parsed) "content-length")
            :raw-length (get-in controlled [:result :raw-length])
            :server-errors (get-in controlled [:result :server-errors])
            ;; vec over the byte-array body matches the in-process integration
            ;; test's exact-octets projection and keeps the result canonical.
            :body-octets (vec (:body parsed))}
     :routes {:count (count effect-trace)
              :all-handled? (every? #(= :handler (:route %)) effect-trace)
              :foreign-symbols (foreign-symbols effect-trace)}
     :sqlite (sqlite/summary sqlite-world)
     :capacity {:stream (posix/capacity-summary posix-world)
                :pipe (posix/pipe-capacity-summary posix-world)}
     :fault (if fault?
              (let [history (posix-fault/evidence-history fault-frontend)]
                {:attempts (posix-fault/attempts fault-frontend)
                 :firings (:firings (posix-fault/snapshot fault-frontend))
                 :fired-attempts
                 (mapv #(select-keys % [:attempt-id :firing])
                       (filter :firing history))})
              {:attempts 0
               :firings 0
               :fired-attempts []})
     :clean? {:memory (memory/clean? mem)
              :sqlite (sqlite/clean? sqlite-world)
              :posix (posix/clean? posix-world)}}))

(defn ^{:jolt.sim/scenario true
        :jolt.sim/accepts-input true} exercise-with-capacities
  "Runs the unchanged jolt-http + db.sqlite fixture once under the shared
  hermetic POSIX loopback plus SQLite handler packs, parameterized by one
  canonical input map:

    {:stream-capacity   pos-int
     :pipe-capacity     pos-int
     :poll-eintr-ordinal nil | pos-int}

  Returns one canonical evidence map. A nil :poll-eintr-ordinal drives no fault
  frontend; a positive ordinal fires one captured EINTR on that per-poll
  attempt ordinal. Accepts the standard jolt.sim.explore-worker protocol-v2
  (runtime-overrides, input) arity used by jolt.sim.process-explorer/run-case."
  ([input]
   (exercise-with-capacities {} input))
  ([overrides input]
   (evidence-for overrides input)))
