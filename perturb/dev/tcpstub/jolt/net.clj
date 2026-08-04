(ns jolt.net
  "A NAME-ONLY STUB OF `jolt.net`, FOR ANALYSIS. NOT A MODEL, NOT AN ORACLE.

  `perturb.tcpcheck` checks the REAL IR of `casselc/jolt-tcp`'s
  `teensyp.client`. To analyse that source the compiler has to resolve the 33
  `jolt.net` names it references, and the real `jolt-net` cannot be loaded by
  the jolt in `/home/user/jolt`: `jolt/net/ffi.clj:49` uses the `:varargs-after`
  option of `jolt.ffi`, which this (upstream, read-only) compiler does not have.

  So this file exists to supply NAMES AND NOTHING ELSE. Every function here
  throws. That is deliberate and it is the honest position:

    - the IR that gets checked is `teensyp.client`'s own, unmodified, compiled
      from the library's own source file;
    - `jolt.net` is never checked, never declared, and never called — and if
      anything ever does call it, it fails loudly instead of returning a
      plausible answer;
    - the four `def`s that are VALUES rather than functions (`would-block`,
      `eof`, `connected`, `in-progress`) are compared with `=` in the library
      and never inspected, so their identity is all that is used.

  WHAT THIS COSTS, STATED PLAINLY. `teensyp.client`'s IR contains `:var`
  references into this namespace. The checker treats an unannotated var call as
  opaque, so nothing downstream depends on what these bodies would have done —
  but the arities here are `[& _]` rather than the library's real ones, so this
  stub cannot detect an arity error the real `jolt-net` would have caught. It is
  a compile-time scaffold for a static check, and it must never be on the
  classpath of anything that runs.")

;; --- values compared with `=` ------------------------------------------------
(def would-block ::would-block)
(def eof ::eof)
(def connected ::connected)
(def in-progress ::in-progress)

(defn- nope [] (throw (ex-info "jolt.net stub: this is a name, not an implementation" {})))

;; --- the surface `teensyp.client`, `teensyp.server` and `teensyp.buffer` use --
(defn await-ready [& _] (nope))
(defn close! [& _] (nope))
(defn endpoint [& _] (nope))
(defn eof? [& _] (nope))
(defn family [& _] (nope))
(defn finish-connect! [& _] (nope))
(defn generation [& _] (nope))
(defn host [& _] (nope))
(defn listen [& _] (nope))
(defn local-endpoint [& _] (nope))
(defn native-handle [& _] (nope))
(defn op [& _] (nope))
(defn open-poller [& _] (nope))
(defn peer-endpoint [& _] (nope))
(defn port [& _] (nope))
(defn register! [& _] (nope))
(defn remove-registration! [& _] (nope))
(defn resolve [& _] (nope))
(defn shutdown! [& _] (nope))
(defn socket [& _] (nope))
(defn status [& _] (nope))
(defn try-accept [& _] (nope))
(defn try-connect [& _] (nope))
(defn try-read-bytes! [& _] (nope))
(defn try-write-bytes! [& _] (nope))
(defn update-registration! [& _] (nope))
(defn wake! [& _] (nope))
(defn would-block? [& _] (nope))
