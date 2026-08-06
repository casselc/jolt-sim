(ns jolt.sim.fixtures.outbox-experiment-packs
  "Trusted experiment-workbench packs and one fixed closed manifest for the
   canonical cancel-before-ack outbox lane.

   This namespace is the experiment-compiler face of the ordinary
   HTTP command -> SQLite outbox -> framed TCP/bencode delivery application
   whose unchanged code already runs in jolt.sim.fixtures.outbox-delivery and
   its integration gates. It adds no executor, no ffi-schedule coordinator,
   no Hegel adapter, and no virtual clock: it only declares the three logical
   connections (HTTP command, SQLite store, TCP/bencode delivery) as trusted
   versioned pack descriptors, plus the fixed manifest selecting them, so
   jolt.sim.experiment/compile-plan
   can assemble one hermetic plan per case. Nothing here runs the
   application.

   Per case the trusted side builds exactly one shared native bundle: one
   jolt.sim.ffi-memory world backing one jolt.sim.sqlite world (the exact
   18-statement cancel-before-ack plan vector shared with the integration
   lane) and one jolt.sim.net.posix-loopback world (the inline Linux target
   facts and the canonical finite capacities of that lane, with no virtual
   clock). Physical handler ownership is declared exactly once, in sorted
   connection-id order:

     :command  -> :outbox.experiment/memory   (native-memory substrate)
     :delivery -> :outbox.experiment/posix    (socket/poll boundary)
     :store    -> :outbox.experiment/sqlite   (SQLite library boundary)

   The partition is deliberately a bijection. jolt.sim.handler-pack/compose
   rejects any handler key claimed by two packs, so the sqlite and posix
   foreign-only builders plus exactly one memory pack compose without
   collision; a connection that double-claimed the shared memory substrate
   would fail compilation rather than silently overwrite a handler. The
   partition is a compile-time ownership discipline, not a causal claim about
   which connection's subject traffic served which shared-substrate effect:
   the HTTP request cycle and the framed delivery both traverse the one
   posix boundary, and both modeled worlds allocate in the one memory world.
   Causal semantic projection belongs to the later executor slice, which is
   intentionally absent here."
  (:require [jolt.sim.ffi-memory :as memory]
            [jolt.sim.fixtures.outbox-sqlite-plans :as plans]
            [jolt.sim.handler-pack :as hp]
            [jolt.sim.net.posix-loopback :as posix]
            [jolt.sim.pack-registry :as packs]
            [jolt.sim.runtime :as runtime]
            [jolt.sim.sqlite :as sqlite]))

;; ---- fixed physical facts --------------------------------------------------

;; The dependency-free inline Linux x86_64 POSIX target facts, byte-identical
;; to jolt.sim.posix-loopback-model-test's linux-descriptor. Keeping the facts
;; inline lets this fixture load in the ordinary -M:test source suite with no
;; jolt.net dependency, exactly like the model and fault tests.
(def ^:private linux-descriptor
  {:platform :posix
   :socklen-type :uint
   :nfds-type :size_t
   :sin-len? false
   :const {:af-inet 2 :sock-stream 1
           :sol-socket 1 :so-error 4
           :shut-rd 0 :shut-wr 1 :shut-rdwr 2
           :f-getfl 3 :f-setfl 4 :o-nonblock 2048
           :pollin 1 :pollout 4 :pollerr 8 :pollhup 16 :pollnval 32}
   :errno {:eagain 11 :einprogress 115 :eaddrinuse 98
           :econnrefused 111 :econnreset 104 :epipe 32}
   :layout {:sockaddr-in {:size 16 :family 0 :port 2 :addr 4}
            :pollfd {:size 8 :fd 0 :events 4 :revents 6}
            :addrinfo {:size 48 :flags 0 :family 4 :socktype 8
                       :protocol 12 :addrlen 16 :addr 24
                       :canonname 32 :next 40 :addrlen-type :uint}}})

;; The canonical finite capacities of the cancel-before-ack integration lane
;; (jolt.sim.outbox-delivery-integration-test): bounded stream/pipe progress
;; without any virtual clock or ffi-schedule coordinator.
(def ^:private posix-config
  {:progress-limit 64
   :stream-capacity 8
   :pipe-capacity 1})

;; ---- physical handler ownership --------------------------------------------

(def physical-ownership
  "The exact physical handler-pack ownership partition for the three logical
   connections, in sorted connection-id order. Each physical pack is built
   once per case bundle and claimed by exactly one connection binding, so
   handler-pack/compose can never see a handler key twice. :physical indexes
   the bundle's :handlers map; :pack-id is the composed pack identity."
  {:command {:physical :memory :pack-id :outbox.experiment/memory}
   :delivery {:physical :posix :pack-id :outbox.experiment/posix}
   :store {:physical :sqlite :pack-id :outbox.experiment/sqlite}})

;; ---- per-case shared bundle --------------------------------------------------

(defn new-case-bundle
  "Builds the per-case shared native bundle for one cancel-before-ack case.

   Exactly one memory world is shared by one SQLite world (the exact
   18-statement cancel-before-ack plan vector, with no mark-delivered
   transaction) and one POSIX loopback world (fixed Linux facts and
   capacities, no virtual clock). The three physical handler maps are built
   once from those worlds and stored under :handlers so compiled bindings and
   tests reference the identical handler functions rather than reconstructions.
   :callbacks carries the trusted compile counters proving each connection
   callback runs exactly once per case. A bundle is never shared
   across cases: fresh worlds, fresh handlers, fresh counters."
  []
  (let [mem (memory/world)
        sqlite-world (sqlite/world mem
                                   (plans/cancel-before-ack-statement-plans))
        posix-world (posix/world mem linux-descriptor posix-config)]
    {:memory mem
     :sqlite sqlite-world
     :posix posix-world
     :handlers {:memory (memory/handlers mem)
                :sqlite (sqlite/foreign-handlers sqlite-world)
                :posix (posix/foreign-handlers posix-world)}
     :callbacks {:compile {:command (atom 0)
                           :delivery (atom 0)
                           :store (atom 0)}}}))

;; ---- mechanism probes and history projectors --------------------------------

(defn- mechanism-probe
  "Builds the connection's mandatory mechanism probe. The probe reads the
   shared bundle's live world evidence at call time. It deliberately reports
   physical handler-substrate activity, never logical connection traversal:
   HTTP and TCP share POSIX and every modeled world shares memory, so logical
   attribution requires the later executor's semantic evidence adapter. The
   single argument is that future execution evidence and is ignored here."
  [connection-id bundle]
  (case connection-id
    :command
    (fn [_execution]
      {:connection-id :command
       :scope :physical-handler-substrate
       :physical-pack-id :outbox.experiment/memory
       :activity? (boolean (seq (memory/snapshot (:memory bundle))))
       :evidence {:allocations (count (memory/snapshot (:memory bundle)))
                  :live-allocations (count (memory/leaks (:memory bundle)))}})
    :delivery
    (fn [_execution]
      (let [state (posix/state (:posix bundle))]
        {:connection-id :delivery
         :scope :physical-handler-substrate
         :physical-pack-id :outbox.experiment/posix
         :activity? (boolean (or (seq (:sockets state))
                                 (seq (:listeners state))
                                 (seq (:closed-fds state))))
         :evidence {:sockets (count (:sockets state))
                    :listeners (count (:listeners state))
                    :closed-fds (count (:closed-fds state))}}))
    :store
    (fn [_execution]
      (let [summary (sqlite/summary (:sqlite bundle))]
        {:connection-id :store
         :scope :physical-handler-substrate
         :physical-pack-id :outbox.experiment/sqlite
         :activity? (pos? (:plan-index summary))
         :evidence summary}))))

(defn- history-projector
  "Builds the connection's history projector. The projector selects exactly
   the effect-trace entries whose canonical handler key belongs to the
   physical handler pack this connection owns. This is an ownership-partition
   projection for plan assembly and tests, not a causal attribution of
   shared-substrate effects; causal semantic projection is the executor
   slice's responsibility."
  [connection-id bundle]
  (let [owned-keys
        (set (keys (runtime/normalize-ffi-handlers
                    (get-in bundle
                            [:handlers
                             (get-in physical-ownership
                                     [connection-id :physical])]))))]
    (fn [effect-trace]
      {:connection-id connection-id
       :events (filterv #(contains? owned-keys (:handler-key %))
                        effect-trace)})))

;; ---- connection pack descriptors ---------------------------------------------

(defn- compile-connection
  "Builds the trusted compiler closure for one logical connection. The
   binding declares exactly the descriptor's capabilities, owns exactly one
   physical handler pack, installs no clock and no native fallback, and
   carries the connection's physical-activity probe, ownership projector,
   and empty monitor/presentation contributions."
  [bundle connection-id capabilities]
  (fn [request]
    (swap! (get-in bundle [:callbacks :compile connection-id]) inc)
    (packs/connection-binding
     (:id request)
     (:mode request)
     {:consumes capabilities
      :handler-packs
      [(hp/pack (get-in physical-ownership [connection-id :pack-id])
                (get-in bundle
                        [:handlers
                         (get-in physical-ownership
                                 [connection-id :physical])]))]
      :clock nil
      :mechanism-probe (mechanism-probe connection-id bundle)
      :history-projector (history-projector connection-id bundle)
      :monitor-specs []
      :presentation-registry {}
      :native-fallback #{}})))

(defn connection-packs
  "Returns the three trusted connection pack descriptors for one case bundle,
   in sorted connection-id order: :outbox.http/command-v1,
   :outbox.tcp/delivery-v1, :outbox.sqlite/store-v1. Each descriptor's
   discoverable data is recursively data-only; its :compile is an in-process
   closure over the bundle. This fixed lane declares no fault vocabulary:
   cancel-before-ack is ordinary application behavior, and fault activation
   coordinates belong to the later Hegel/coordinator slices."
  [bundle]
  (let [command-capabilities
        {:from #{:outbox.http/client-v1} :to #{:outbox.http/server-v1}}
        delivery-capabilities
        {:from #{:outbox.tcp/client-v1} :to #{:outbox.tcp/server-v1}}
        store-capabilities
        {:from #{:outbox.sqlite/client-v1} :to #{:outbox.sqlite/server-v1}}]
    [(packs/connection-pack
      {:id :outbox.http/command-v1
       :doc "HTTP command connection: one POST of the canonical command to the
            unchanged application handler, returning after COMMIT."
       :capabilities command-capabilities
       :config-schema [:map {:closed true} [:contract [:= :outbox/command-v1]]]
       :template {:contract :outbox/command-v1}
       :modes {:simulate {:params-schema [:map {:closed true}]}}
       :faults {}
       :compile (compile-connection bundle :command command-capabilities)})
     (packs/connection-pack
      {:id :outbox.tcp/delivery-v1
       :doc "TCP/bencode delivery connection: one framed delivery attempt to
            the unchanged receiver, cancelled before its acknowledgement."
       :capabilities delivery-capabilities
       :config-schema [:map {:closed true} [:contract [:= :outbox/delivery-v1]]]
       :template {:contract :outbox/delivery-v1}
       :modes {:simulate {:params-schema [:map {:closed true}]}}
       :faults {}
       :compile (compile-connection bundle :delivery delivery-capabilities)})
     (packs/connection-pack
      {:id :outbox.sqlite/store-v1
       :doc "SQLite store connection: the unchanged outbox adapter's exact
            18-statement cancel-before-ack transcript with no marking."
       :capabilities store-capabilities
       :config-schema [:map {:closed true} [:contract [:= :outbox/store-v1]]]
       :template {:contract :outbox/store-v1}
       :modes {:simulate {:params-schema [:map {:closed true}]}}
       :faults {}
       :compile (compile-connection bundle :store store-capabilities)})]))

(defn case-registry
  "Composes the trusted immutable registry for one case bundle. This first
   slice intentionally registers only connection packs. The ack/mark check is
   deferred until an executor produces its closed semantic observations; a
   disconnected monitor would be false evidence."
  [bundle]
  (packs/registry (connection-packs bundle)))

;; ---- fixed closed manifest ---------------------------------------------------

(def cancel-before-ack-manifest
  "The one fixed closed manifest for the ordinary cancel-before-ack outbox
   lane. Closed data only: four nodes (client, app, sqlite, receiver), the
   three logical connections wired app-out/sqlite-and-receiver-in, and the
   single :hermetic profile simulating every connection. It declares no
   semantic check yet: the executor must first produce closed observations.
   It also declares no workload, seed, schedule, fault
   plan, ffi-schedule coordinator, or clock; the pack-owned contract values
   are the only per-connection configuration."
  {:jolt.sim.experiment/version 1
   :id :outbox.experiment/cancel-before-ack-v1
   :nodes
   {:client {:ports {:commands {:direction :out
                                :capabilities #{:outbox.http/client-v1}}}}
    :app {:ports {:commands {:direction :in
                             :capabilities #{:outbox.http/server-v1}}
                  :store {:direction :out
                          :capabilities #{:outbox.sqlite/client-v1}}
                  :deliveries {:direction :out
                               :capabilities #{:outbox.tcp/client-v1}}}}
    :sqlite {:ports {:database {:direction :in
                                :capabilities #{:outbox.sqlite/server-v1}}}}
    :receiver {:ports {:deliveries {:direction :in
                                    :capabilities #{:outbox.tcp/server-v1}}}}}
   :connections
   {:command {:from [:client :commands]
              :to [:app :commands]
              :pack :outbox.http/command-v1
              :config {:contract :outbox/command-v1}}
    :delivery {:from [:app :deliveries]
               :to [:receiver :deliveries]
               :pack :outbox.tcp/delivery-v1
               :config {:contract :outbox/delivery-v1}}
    :store {:from [:app :store]
            :to [:sqlite :database]
            :pack :outbox.sqlite/store-v1
            :config {:contract :outbox/store-v1}}}
   :profiles
   {:hermetic {:connections {:command {:mode :simulate :params {}}
                             :delivery {:mode :simulate :params {}}
                             :store {:mode :simulate :params {}}}}}
   :checks []})
