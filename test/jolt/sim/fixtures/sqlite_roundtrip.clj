(ns jolt.sim.fixtures.sqlite-roundtrip
  "Ordinary db code used unchanged against real and simulated SQLite.

  This namespace deliberately depends only on jdbc.core. Its caller chooses
  whether db.sqlite reaches the system SQLite library or an installed
  jolt-sim ABI v2 handler pack."
  (:require [jdbc.core :as jdbc]))

(defn exercise-sqlite
  "Creates, inserts, and reads binary rows through the public jdbc.core API."
  []
  (with-open [connection (jdbc/connection "sqlite::memory:")]
    (let [ddl
          (jdbc/execute!
           connection
           "create table sim_probe (id integer primary key, payload blob)")
          inserted
          (jdbc/execute!
           connection
           ["insert into sim_probe (id, payload) values (?, ?)"
            1
            (byte-array [0 127 128 255])])
          empty-inserted
          (jdbc/execute!
           connection
           ["insert into sim_probe (id, payload) values (?, ?)"
            2
            (byte-array 0)])
          row
          (jdbc/fetch-one
           connection
           ["select id, payload from sim_probe where id = ?" 1])
          empty-row
          (jdbc/fetch-one
           connection
           ["select id, payload from sim_probe where id = ?" 2])]
      {:ddl ddl
       :inserted inserted
       :empty-inserted empty-inserted
       :row {:id (:id row)
             :payload (vec (:payload row))}
       :empty-row {:id (:id empty-row)
                   :payload (vec (:payload empty-row))}})))
