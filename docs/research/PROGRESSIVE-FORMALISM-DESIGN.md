# Progressive formalism: a design for one system

**Status: a design, not a decision and not a plan of record.** Nothing here is
built. It is written to be *falsified cheaply* — §11 states the smallest build
that would decide it, and §13 collects what it would take to be wrong.

**On the name.** `perturb` is used throughout as the name of the unified thing.
That is a naming convenience and **not a claim of primacy**. On line count and
on finishedness the two halves are comparable and jolt-sim is arguably ahead:
7,549 lines of working simulator, scheduler, replay kernel and monitor runner,
against 21,656 lines of checker with named holes. §7 is deliberately written so
that the dynamic half is the *spine* and the static half attaches to it, not the
reverse.

---

## 1. The one idea

Every stack that offers optional rigour makes it a **mode** you switch into:
typed vs untyped, verified vs not, tested vs monitored. The boundary between
modes is where obligations get dropped, and this record's most valuable findings
are all instances of that:

- **E18 finding 3** — `short-body-still-type-checks` declares `Content-Length: 6`
  and writes 3. The checker accepts it and the gate runs it to completion,
  because the capability reached `:finished`, which is all a state can be asked
  for. The static layer exported nothing because it did not know it had a
  residual.
- **E38 part 2** — `cross!`'s comment said a lying handler is stopped at the
  boundary. False as written; ten forgeries crossed, two silently.
- **E40** — `check-error-mapping` folded over refusals inside a declared layer's
  extent. The known-good trace has none, so the clause emitted nothing and its
  silence read as a pass, in shipped code, for the entire life of the gate.

So the thesis:

> **Formalism is not a mode. It is a value attached to every name, and it
> composes by a lattice meet.** No casts, no blame calculus. What you know about
> a composite is bounded by the weakest thing you know about its parts, and that
> falls out mechanically rather than being asserted.

And the corollary that makes it survivable:

> **"Nobody looked" is a first-class value that never silently becomes "looked
> and it was fine."** E40, generalised until you cannot build a checker without
> it.

### 1.1 The falsification criterion, stated first

The design is wrong if the ladder is not free. Concretely:

> Write one property. Move it from `:sampled` to `:exhausted` to a live
> production counter **by changing a domain declaration and nothing else.** If
> that takes three rewrites in three notations, the thesis is false and no
> amount of architecture repairs it.

> **CORRECTED — the criterion as written is false, and the correction is not
> cosmetic.** A review pointed out that a common predicate still needs an
> **explicit observation adapter**: for the exhaustive consumer you *construct*
> the arguments; for the monitor you must *find* them in a trace. That adapter
> is not `:domain` and is not free. Verified by inspection of §3.1's own
> example. The honest criterion is:
>
> > **`:holds` is unchanged.** What changes is the domain declaration and a
> > **declared observation adapter** per consumer. **A trace lacking the
> > observations an adapter requires yields `:inconclusive`, never a pass.**
>
> That is weaker than the sentence it replaces and still much stronger than four
> rewrites in four notations — but the original was a marketing claim and is
> struck.

§11 is the build that tests exactly this.

---

## 2. Evidence as the universal currency

E40's arm structure, promoted from one gate to the whole system:

```clojure
{:strength :proved | :exhausted | :sampled | :monitored | :asserted | :unknown
 :scope    {:covered <what was actually reached> :of <the intended domain>}
 :source   #{:symbolic :simulated :native}
 :basis    "one sentence: what ONE unit of coverage is"
 :units    {:checked 132672 :expected 132672}
 :residual [{:owed … :next-layer …}]
 :as-of    <content hash of everything this depends on>}
```

Four rules, each traceable to a measured failure in this record:

1. **Strength, scope and source are separate axes.** Three documents
   independently found the current lattice conflates strength with scope;
   `PROGRESSIVE-ASSURANCE-ARCHITECTURE.md` proposed `source` and then
   immediately regressed by listing *"finite/exhausted, sampled, simulated,
   runtime, monitored, or omitted"* in one flat list. Strength × scope × source,
   or nothing.
2. **`:basis` is mandatory prose, checked at definition time.** A claim whose
   unit cannot be said in one sentence does not compile. E39's `bounded-complete`
   *"within its stated corpus"* names no bound; this rule rejects that phrase
   before it can be published.
3. **Vacuity is a type error.** `:units {:checked 0}` with `:strength` above
   `:unknown` is ill-formed. So is `:checked 0` while holding violations — E40's
   `:vacuity-accounting`, because a denominator that may understate can convert
   a *failure* into a shrug.
4. **`:as-of` is a content hash**, which is what makes §5's invalidation graph
   possible and what makes a stale claim visible instead of quietly wrong.

### 2.1 The meet

- `strength` — lattice order, weakest wins.
- `scope` — intersection, **and the shortfall is recorded as a residual** rather
  than dropped.
- `source` — does not order. A composite carries the *set*. `#{:simulated}` and
  `#{:native}` are different facts, not ranked ones.

The meet is depressing by construction: a real system's top-line number reads
`:unknown` for a long time. Mitigation, and it is a design commitment rather
than an afterthought: **evidence is reported per path, never per program**, and
floors are set per path. A single program-wide number becomes decoration within
a week.

---

## 3. The language

Clojure-like, hosted on Jolt: homoiconic, immutable by default, **dynamic by
default**. Four additions, and nothing else.

### 3.1 `defspec` — one artifact, six consumers

```clojure
(defspec response-well-formed
  {:over   (fn/of ::request -> ::response)
   :domain {::request (finite/product {:method   (enum :get :head :post)
                                       :body-len (range 0 17)
                                       :encoding (enum :identity :chunked)})}
   :oracle reference/http-response          ; declared total + independent
   :holds  (fn [req resp] (= (content-length resp) (count (body resp))))
   :basis  "one (method, body-length, encoding) triple"})
```

From one form the system derives a static check where the fragment is decidable,
a bounded-exhaustive run where the domain is finite, a property test where it is
not, a simulation scenario, a runtime monitor, and a production sampler.

`:domain` finite ⇒ 102 cases, `:strength :exhausted, :units {:checked 102
:expected 102}`, uniqueness equality-confirmed. Swap `:domain` for a generator
and the **same `:holds`** yields `:sampled`. Deploy and the **same `:holds`**
becomes a fold over the production trace at `:monitored`, `:source #{:native}`.

**You never rewrite the property to change its evidence tier.** In every stack I
know, unit test → property → bounded model → monitor is four rewrites in four
notations, and the drift between them is where the real defects live.

`:oracle` independence cannot be machine-verified. It is *declared*, recorded as
an assumption in `:residual`, and `(audit-oracles)` lists every site where it
was asserted rather than shown. E39 checked oracle independence specifically
because it had just sunk a claim in a neighbouring document; that check should
be a standing report, not an act of vigilance.

### 3.2 Regions — how §4.6 stops being a wall

§4.6's root cause: **a capability may only live in a binding of statically known
shape.** E24 named four shapes that rules out — collection, growth, runtime
selection, dynamic sharing — and E34 measured that this is the sole blocker on
3 of the 7 remaining tcpcheck rejections, 57% of the substantive ones. Adding
more static shapes loses; that is *why* the four keep recurring.

Instead: **a capability lives in a region — a first-class runtime value with a
static identity.**

```clojure
(with-region conns {:discipline :unique :exit :must-be-empty}
  (loop [...]
    (let [c (region/admit! conns (accept! listener))]
      (serve c)
      (region/release! conns c))))
```

The checker reasons about *the set of capabilities in `conns`* without knowing
its size, contents, or dispatch. Membership operations are the only way in or
out. The exit rule is a static obligation, discharged statically when the loop
shape permits and by a monitor at the region's dynamic extent otherwise.

**The concession is the point, and it should be made loudly: the checker gives
up identity and keeps the obligation.** It cannot say *which* connection leaked.
It can say *this region exited holding 3*, at that source location, with a
residual naming what it could not track. All four E24 shapes become expressible
at `:monitored` with a named residual, instead of inexpressible at every tier.

Regions also close the higher-order gap — which today "has no notation at all"
and is the sole blocker on three rejections. A function taking a capability
takes a **region parameter**; region polymorphism is well-trodden
(Tofte–Talpin, Cyclone, Rust lifetimes) and inferable in the common case. This
is the one place to borrow wholesale rather than invent.

**`perturb.dbtx` is the existing proof that the declaration side is ready.** It
already carries one operation with three run-time-chosen destinations, a
discriminator, and `:poisoned` as a declared cancelled state that discharges
nothing on entry. A connection *pool* is the region that same lane is missing.

### 3.3 Effects as the only I/O, and one trace type everywhere

The rung discipline exists (`perturb.effect`, `perturb.layer`). The new
commitment is §7's: **one trace type, in the REPL, the simulator, the test
suite, and production.** A production incident loads into the REPL and replays
under the simulator. A simulator run diffs against a production one. The CI
monitor is the same code as the prod monitor; only the budget differs.

### 3.4 Cells, flows, interceptors and subscriptions

**Revision note.** §§1–3 as first drafted dropped the cell/flow line entirely —
Mycelium, `core.async.flow`, re-frame — which `PROGRESSIVE-ASSURANCE-ARCHITECTURE.md`
§"borrows two ideas" had already put on the table. Some of it *is* present under
other names and should have been labelled; some was genuinely lost and is real;
one part is refuted and must not return uncritically. All three, separated.

Sources read 2026-08-04: `mycelium-clj/mycelium` README @ `main`;
`PROGRESSIVE-ASSURANCE-ARCHITECTURE.md` §§ on `core.async.flow`. re-frame is
cited from general knowledge, not from anything in this record.

#### (a) Present, but unnamed — my error

| idea | where it already is |
| --- | --- |
| cell step `(state, event) -> [state', effects]` | §3.3, effects as data; §6.1's projected endpoints |
| Mycelium's explicit input/output schemas, graph-owned routing | `defspec`'s `:over`/`:domain`; `defsession`'s projection |
| Mycelium's *"schema/graph validation is evidence of declared shape/path compatibility, not a proof of handler semantics"* | **an evidence value with capped `:strength`.** The architecture document phrased this correctly before this design existed |
| re-frame's effects and coeffects as data | effect requests as data; `defforeign` |
| `core.async.flow`'s **static `datafy` view vs. dynamic process inspection**, which must not be conflated | §5.5's three fact sources, and the rule that a query must name which it used |
| **re-frame's subscription graph** | **§5's evidence invalidation graph is the same mechanism** — a deduplicating reactive graph keyed on content, with `:as-of` hashes where re-frame has ratom equality. Different payload, identical shape. I did not notice this until asked |

#### (b) Genuinely dropped, and each is a real gap

1. **Interceptors.** re-frame's bidirectional before/after chain around a handler
   is *structurally the same thing* as a rung, and B6 is the invariant about
   stacking them. perturb has the mechanism and **no notation** — interceptors
   are the ergonomic form, and adopting them costs nothing conceptually.
2. **Subscriptions as materialized views with dedup.** This is the brief's
   work-queue item 5 (`view = V(cursor)`), and **E35 finding 3 is precisely the
   bug the discipline prevents** — the credit fold had to follow reply order
   rather than request order, "the kind of bug that makes a checker look correct
   while measuring the wrong thing", found by hand. Rebuild at a boundary, not
   per event (row 38's cost line). Dropped from the design entirely; restored
   here.
3. **Lifecycle and supervision.** `core.async.flow` separates step logic from
   *lifecycle, monitoring and error handling* — `:describe`/`:init`/`:transition`/
   `:transform`, pause, resume, ping, an admin channel. **This design has no
   story at all for controlling a running process graph**: start, pause, drain,
   resume, stop. That is a serious omission for something whose pitch includes
   production monitoring and an attachable prod REPL, and the architecture
   document already recorded the matching gap on the other side — jolt-sim's
   process explorer "has no live-process ping surface."
4. **Topology as a separate data artifact.** Mycelium's manifest, with
   `enumerate-paths` and `compile-workflow` validating *before any code runs*:
   cell existence, edge targets, reachability from `:start`, dispatch coverage
   both ways, schema chain, path constraints. This is **the concrete answer to
   §13 nonclaim 9** — the complaint that perturb's declarations are scattered
   across five namespaces. A manifest is where they go.
5. **Mycelium's `:constraints` vocabulary** — `:must-follow`, `:must-precede`,
   `:never-together`, `:always-reachable` — *checked against all enumerated
   paths*. That is a declaration-sourced `:exhausted` query with a real
   denominator, and it is a better starting vocabulary than §5.7 invented.
6. **Resilience policies as declared data** (retry, backoff, timeout-ms). §9's
   CTMC needs exactly these as parameters; Mycelium already declares them, so
   the metastability lane's inputs are a manifest read rather than a
   hand-written model.
7. **Halt & resume with a persistent store.** Human-in-the-loop, operator-gated,
   long-running flows — the shape the brief named as the honest UI target (an
   a1s mutation lifecycle: confirm → request → poll → terminal). Absent here.
8. **Join nodes with output-key conflict detection.** Fork-join at the
   application level, with disjointness checked at compile time. §6.1 handles
   concurrency at the protocol level and says nothing about this.

**And one point of genuine kinship worth recording.** Mycelium's README
documents that `:always-reachable` *"passes vacuously if no paths reach
`:end`."* The vacuity is **known and written down in prose** — which is exactly
one step short of E40. The contribution this design makes is not noticing the
problem; it is that **the vacuity becomes a value the tool emits** rather than a
caveat the reader must remember to apply.

#### (c) Refuted — and this part must not come back uncritically

The load-bearing claim was that if user code holds no capability, *"the whole
cost of the capability discipline collapses into ONE component."* **It was
built, measured, and it failed.** Tally row 35: the shape **relocates**
obligations rather than removing them — 2 of 4 wrong applications accepted, and
the working app's `/wait` route broke the declared machine and reached the wire.

`perturb.evt` is the measurement, and it was written to make the cost visible
rather than small. Two drivers, on purpose:

- **Driver A, the register file** — the connection table is a *literal 2-tuple*,
  every position written down in the annotation. It holds a Listener and two
  ServerConns live in one scope. **It checks.**
- **Driver B, the table** — a map from id to ServerConn, *"which is what anyone
  would write"*, servicing N connections and honouring `[:close id]`. **It is
  rejected, function by function, and the rejections are the measurement.**

`CELLULAR-UI`'s containment rule — *"capability state remains outside cell
input, output, state, trace, export, and effect-request data"* — excludes the
one shape perturb can check and routes authority into all four E24 shapes at
once.

#### (d) The synthesis: regions are the notation driver B was missing

Driver B is rejected because a map keyed by runtime id, that grows, dispatched
by runtime value, behind a function-valued parameter, is E24's four unexpressible
shapes in one data structure. **That is the exact shape `with-region` exists to
express** (§3.2).

> The cell architecture did not fail. Its **relocation target** was
> unexpressible, and regions are the expression. Driver B becomes a region with
> `:exit :must-be-empty`: identity given up, obligation kept, checked at
> `:monitored` with a named residual — instead of rejected function by function.

**This is a decidable experiment and it is cheaper than §11.** `perturb.evt`
already exists, both drivers already run under `perturb.script` and over a real
loopback socket, and `perturb.evtcheck` already compares the octets.

> **RUN — E41.** Driver B′ is **0 of 11 rejected**; driver B is 7. Octets
> identical on both connections; the `perturb.evtapp` containment control still
> rejects its two escapes; every other gate still exits 0. The leak control
> reports `exit-not-empty {:size 1 :keys [1]}`.
>
> **But the cost moved rather than vanished.** `perturb.region` itself is 2 of 15
> rejected, and the reason is a notation gap this design did not name: perturb
> can say *consumed and gone* and *consumed and returned*, and has **no notation
> for consumed-and-held-by-something-else**. So the checker **accepts the
> region's unsound half** (`take-*` is declared `nil -> :state`, a mint) and
> **rejects its sound half** (`put-*`, whose implied edge is `:reading -> nil`).
> §3.2 needs `:absorbs` / `:emits` naming the holder's argument position.
>
> Three costs were unpredicted: an **identity edge** (`skip`) for branches that
> consume in one arm only; the **member's own machine must declare the region's
> operations**, so a region is not a bolt-on; and **one operation per
> (capability × state)**, because `:produces` is static.
>
> And one result nobody asked for: under `:report` the region emits two
> `state-mismatch` violations on the `/wait` connection — **tally row 35's
> architecture defect, caught at the moment it happens** instead of after the
> fact in a ledger that does not join up. Under `:refuse` it stops the run.
>
> The original prediction, kept visible:

> **Prediction, falsifiable:** under regions, driver B's `accept-into-table`,
> `read-round`, `apply-effect`, `apply-effects` and `close-table` move from
> *rejected* to *accepted at `:monitored`*, the emitted octets remain identical
> to driver A's, and a leaked connection is reported as a **non-empty region
> exit with a count** rather than as a name the checker cannot track. If the
> rejections survive, regions do not solve §4.6 and §3.2 is wrong.

### 3.5 Lifecycle: the gap this revision opened

§3.4(b)3 is not repairable by citation. Naming it as work rather than papering
over it: a running flow needs **declared lifecycle states of its own** — an
obligation-bearing typestate over the *process graph*, not over one capability —
with `:draining` and `:paused` as real states, `:resume` as an edge that may be
refused, and supervision (ping, report, admin) as an **effect** so it is
budgeted, traced and exportable like everything else. It is the natural home for
`core.async.flow`'s admin channel and for the live-process ping surface
jolt-sim's process explorer is recorded as lacking. Nothing in this design does
it, and §9.3(d)'s adaptive controller is a special case of it.

### 3.6 `unknown` as a value

Every monitor predicate returns `true | false | (unknown "why")`, and
aggregation propagates it. `unknown` at top level is not a pass.
`jolt.sim.monitor` already has this posture; `perturb.layer` did not until E40.
Making it a property of the *type* is what stops the next instance.

---

## 4. Budgets

Contracts are not free. E36 measured the byte-window arithmetic at 1220× and
4.2 GB of garbage for one 4 KB request; E38 measured a 2.5× faster inner loop
that had to be rejected because it moved a scripted run from 0 to 2 attributable
syscalls. So every check, tap, span and monitor site carries a **budget**, and
the budget is a property of the **deployment**, not the source:

`:always` · `:sampled <rate>` · `:dev-only` · `:on-suspicion`

`:on-suspicion` arms automatically when a correlated monitor is already unhappy.
It is the mechanism that makes §9's fidelity ladder and §5's capture affordance
the same feature.

---

## 5. The REPL surface

Not "a REPL with type hints." The primary object is a claim and its provenance.

### 5.1 `datafy` / `nav`, specified

**Every datafied node carries its own evidence value.** Navigating a system *is*
navigating a claim graph; the object and what you know about it arrive together.

```clojure
(datafy conn)
;=> {:cap/id 4471 :cap/state :established :cap/region 'conns
;    :cap/obligations [{:owed :close! :since 71}]
;    :cap/machine #ref :tcp/socket-machine
;    ^{:evidence {:strength :monitored :source #{:native} …} :as-of #hash "9c3a…"}}
```

**Capabilities datafy to redacted correlators, never tokens.** That single rule
is what makes §5.4 safe.

**`nav` edges are typed and budgeted, not banned.** The earlier sketches said
nav must never force work or perform effects, which makes it useless exactly
where it is wanted. Classify instead:

| class | meaning |
| --- | --- |
| `:free` | in-memory projection |
| `:deferred` | forces a realized value you already own |
| `:io` | a round trip; declares the foreign system; yields `:native` evidence |
| `:expensive` | unbounded scan; the REPL confirms before running |

The REPL enforces the budget and **every `:io` nav appends to the trace** — so
exploring production is itself recorded and replayable. That is "datafy your
database" without the version where an idle inspector melts a replica.

`(nav c :cap/machine)` walks the typestate: current state, legal edges from
here, obligations owed, and which edges would discharge them. Asking a live
object what it is allowed to do next is the affordance nothing else offers, and
`perturb.cap` already holds the data to answer it.

### 5.2 `tap>` promoted from firehose to instrument

```clojure
(deftap ::decode-loop
  {:schema {:offset nat? :state keyword? :window ::window}
   :budget :sampled-1/1000
   :retain 512
   :basis  "one decoder step"})
```

1. **Channels, not a global bus** — addressed, schema'd, bounded retention.
2. **Taps land in the trace**, in causal position, beside effects. Replay a sim
   run and your taps fire where they fired.
3. **Taps are budgeted exactly like checks** (§4).
4. **Therefore printf-debugging graduates into production telemetry with no
   rewrite.** The tap you dropped in at 2am is, unchanged, a sampled prod signal
   with a schema and a retention policy.

`(tap->fixture ::decode-loop 3)` promotes a tapped value **plus its causal
prefix** into a permanent scenario.

### 5.3 Capture, rewind, gaps

`(capture!)` grabs the lexical environment **and the trace prefix** — a
replayable scenario, not just locals. `(rewind 412)` and `(step)` are free
because `jolt.sim.kernel` already provides virtual time and exact replay; the
debugger is a consequence of the runtime rather than a separate tool.

`(gaps)` lists every `unknown`, every residual with no named next layer, every
`:asserted` never discharged, ranked by how much reachable code sits behind it.
**The work queue derives itself from the artifact.** A hand-written residual
list inherits its author's blind spots; a derived one inherits only the
artifact's. This is the direct answer to the objection that residual routing
presupposes the residual is *named*.

`(doc 'orders/submit!)` shows the docstring, the specs over it, its current
evidence, and its open residuals. Documentation and assurance are one page.

### 5.4 A production REPL you are allowed to attach

Capabilities never datafy to tokens; effects require a rung you were not given;
navs are budgeted and recorded. **A prod REPL is read-only structurally, not by
convention.** Every existing system makes this a career risk; the capability
discipline is what buys it back, and it is one of the strongest practical
arguments for the whole capability tier.

### 5.5 Three fact sources, three evidence characters

The REPL must answer relational and structural questions about the program —
reachability, impact, dead code, cycles, layer violations, "can this input reach
that sink", "is this declared state ever reachable". The reference point is
**`yogthos/chiasmus`** (read 2026-08-04, `main` @ README): tree-sitter extracts
`defines`/`calls`/`imports`/`exports`/`contains` plus derived `calls_qn` and
`imports_resolved`, and O(V+E) algorithms answer reachability, reverse-transitive
impact, dead code from entry points, cycles, Louvain communities, betweenness
hubs and bridges, and snapshot diffs; Z3 answers "for all inputs" with unsat
cores over named assertions; Prolog does transitive reasoning over exported
facts.

The design difference is not the algorithms. It is that **perturb has three
independent fact sources with three different evidence characters, and a query
must say which it used.**

| source | closed? | honest verdict | good for |
| --- | --- | --- | --- |
| **declarations** — `cap` machines, `dbtx` sums, `defsession` projections, region bounds, operation classes, B6 edges | **closed and enumerable** | `:exhausted` with counts | the declared model: unreachable states, orphan obligations, overlapping edges, layer violations |
| **IR / source** — `perturb.ir` | **open by construction** | `:asserted` | nothing load-bearing until §7.3's quarantine lifts |
| **trace** — the unified `sim.trace` document | sound, **incomplete** | `:monitored`, carrying the sample rate | "this path executed"; **never** "this path is unreachable" |

**`perturb.ir` cannot ground a dead-code answer.** Its own docstring records that
a namespace loaded before `install!` is never re-analysed and that the result is
load-order dependent. A reachability denominator drawn from it is
`report-limits` 14(f)'s failure mode — an unchecked declaration feeding a
completeness verdict. Declaration-sourced and trace-sourced queries do not have
that defect, and they are the two that matter most.

**The query no static analyser can ask.** Because both halves exist, the
interesting question is the **difference** between two sources:

```clojure
(diff/declared-not-observed)   ; declared edges no trace ever took
(diff/observed-not-declared)   ; edges taken that the model does not contain
```

The first is untested surface or dead declaration. **The second is a finding
about the model itself** — the same shape as §9.3(b)'s model error, and the same
shape as E38, where `cross!`'s comment described a boundary the code did not
enforce. A tool with only a parser cannot ask it; a tool with only a monitor
cannot ask it either.

### 5.6 Which engine — and the answer is neither kanren nor Prolog

Three query classes, three different mechanisms, and only one of them is a logic
engine:

**(a) Graph structure — plain algorithms over an indexed fact store.**
Reachability, reverse-transitive impact, cycles, dead code, hubs, bridges,
snapshot diff. These are O(V+E) graph algorithms, not logic programming.
Chiasmus implements them directly and *exports* to Prolog for the general case;
that is the right split. **No engine required, and this is most of the value.**

**(b) Relational and recursive queries — Datalog, not Prolog, not miniKanren.**
For ad-hoc joins and recursion over the fact store, the choice is forced by this
record's own discipline rather than by taste:

- **Datalog terminates** on a finite fact base, and reaching the fixpoint *is* a
  completeness result. The answer set can be **counted**, so a query result can
  honestly carry `:strength :exhausted` with `:units {:checked n :expected n}`.
- **Prolog's SLD resolution is Turing-complete**, order-dependent, and
  non-terminating on left-recursive rules without tabling. **You cannot attach an
  evidence value above `:asserted` to a Prolog answer** — you do not know whether
  the search was complete or merely stopped.
- **miniKanren / core.logic** is elegant and can run relations backwards, which
  is genuinely attractive for "generate an invalid state". But it inherits the
  same completeness problem, performs poorly at fact-store scale, and core.logic
  is JVM Clojure that would need porting to Jolt. For *generation*, §5.7's
  bounded state search over `sim.explore` is both cheaper and better-denominated.

So: **a small Datalog — semi-naive bottom-up evaluation, stratified negation** —
in the low hundreds of lines, over the same indexed fact store as (a).
Termination and countability are not incidental properties here; they are the
reason it can participate in the evidence lattice at all.

**(c) "For all inputs" — SMT, behind a `defforeign` boundary.**
`perturb.refine` decides a ground linear fragment with no case split, no
Fourier–Motzkin, no simplex, and syntactic atom comparison (tally row 57). Q2
already identified the fit: liquid types over QF-LIA with Z3, which is already a
toolchain dependency via `bin/verify-models`. Contradiction detection and
**unsat cores over named assertions** are exactly chiasmus's Z3 use, and the
core maps directly onto a residual: *these named assumptions cannot hold
together*. Per §13 nonclaim 5, this is an honest `:asserted` external
dependency, declared as one.

### 5.7 The query surface

```clojure
;; structure — declaration-sourced unless :from says otherwise
(reach 'http/respond! 'posix/write!)        ; => path or false, + evidence
(paths 'a 'b {:via :forward-only})          ; edge kinds are semantic here:
                                            ; :call :forward :perform :transition
(impact 'lintSpec)                          ; reverse transitive
(cycles) (hubs) (bridges)
(dead {:from :declarations :entry #{...}})  ; :exhausted; :from :ir would be :asserted

;; capability flow — sharper than a call graph, because the edges are typed
(cap/flow :from 'accept! :to 'close!)       ; can this capability reach that op
(cap/escapes 'conns)                        ; can a member leave the region

;; the declared model
(machine/unreachable-states 'tcp/socket)
(machine/orphan-obligations)                ; obligations no edge discharges
(machine/overlapping-edges)                 ; already `check-edge-overlap!`
(model/invalid-states {:bound 3})           ; bounded product search over
                                            ; machines × regions × sessions,
                                            ; enumerated by sim.explore

;; the two-source difference
(diff/declared-not-observed) (diff/observed-not-declared)

;; proof obligations
(prove 'response-well-formed)               ; refine's fragment, else Z3
(unsat-core)                                ; named assertions → residuals
```

**Two rules make these answers trustworthy:**

1. **Every query result carries an evidence value** naming its fact source, its
   denominator, and its residuals. `(dead …)` over declarations is `:exhausted`
   with a count; over IR it is `:asserted` with the open-universe residual
   attached; over traces it is **not offered at all**, because absence from a
   sampled trace is not unreachability.
2. **An empty result and an empty fact base are different values.** A query whose
   source contributed **zero facts** returns `:inconclusive` with its `:basis`,
   never `[]`. This is E40 verbatim, in the place it would do the most damage:
   `(dead)` returning `[]` after a failed extraction reads exactly like "no dead
   code", and is the single most likely way a query surface would lie.

**The agent surface is the REPL, and it already has a transport.**
`perturb.nrepl` is a working client over the socket effect. An agent attached to
a *running* perturb system gets these queries against live declarations, live
regions, and the actual trace — not a static parse of the source, and not a
separate server holding its own stale copy of the codebase. §5.4's redaction
discipline is what makes that safe to expose.

---

## 6. Reach

### 6.1 Distributed protocols — `defsession`

```clojure
(defsession lease-renewal
  {:roles   {:client :local :coordinator :local :store :foreign}
   :failure {:loss :possible :reorder :possible :dup :possible
             :crash #{:coordinator} :clock :unsynchronized}
   :global  [[:client -> :coordinator ::acquire]
             [:coordinator -> :store ::cas]
             (alt [[:store -> :coordinator ::ok]      [:coordinator -> :client ::granted]]
                  [[:store -> :coordinator ::conflict] [:coordinator -> :client ::denied]])]})
```

Multiparty session types, projected per role. One declaration yields a static
check of local endpoint code, a wire monitor, and an adversarial peer for the
simulator.

Three commitments:

- **Failure is inside the protocol.** `:failure` is a value — the same schedule
  object `jolt.sim.strategy` and `jolt.sim.explore` already consume.
- **Foreign roles are monitored, not checked.** A peer that violates the session
  becomes an attributed named event instead of a stack trace in your parser.
- **The trace becomes a partial order.** No global clock means causality *is*
  the ordering: trace entries carry causal ids, the trace is a DAG, sim explores
  linearisations, production records what it observed. This is a real cost, paid
  deliberately, and it is the honest option.

This is also where `:exhausted` is genuinely reachable — bounded exploration of
small configurations, with scope reading *"all interleavings of 3 nodes with ≤2
crashes, 12,400 schedules, 12,400 checked."* `jolt.sim.explore/schedule-plans`
is already that enumerator.

### 6.2 Foreign and uncontrolled systems

You cannot verify someone else's system. You can **continuously falsify your
belief about it.**

```clojure
(defforeign stripe/charge
  {:believed {:idempotent-on :idempotency-key
              :responses     #{::ok ::declined ::rate-limited ::timeout}
              :timeout-means :unknown-outcome}
   :citation {:doc "https://…/charges" :rev "2026-03-11"}
   :failures {:partition :possible :duplicate-delivery :possible}
   :evidence :asserted})
```

- **Every foreign call site produces `:asserted` at best**, with the belief
  recorded as a cited assumption — and **a citation without a revision is
  rejected**, the rule this record already adopted after the `:tx-effect`
  incident.
- **The believed contract compiles to a conformance monitor.** Divergence
  becomes a named violation attributed to the belief that failed.
- **Recorded interactions become the sim's foreign model**, in the same trace
  type — and the sim *also* runs the adversarial model from `:failures`,
  covering paths recordings never took. "We replay what happened, never what
  could" is where most contract-testing setups quietly fail.
- `:timeout-means :unknown-outcome` is first-class and the checker makes you
  handle the third case.
- `(gaps)` reports **how much reachable code sits behind each foreign belief.**
  That number is the most actionable thing the system produces.

---

## 7. The unification — how the two halves become one

This section is the point of the document. The two codebases were written
independently and **share almost nothing today, while needing precisely each
other's outputs.**

### 7.1 What each half already is

| jolt-sim (7,549 lines) — the dynamic half | perturb (21,656 lines) — the static half |
| --- | --- |
| `sim.trace` — canonical EDN projection, strict value domain, collision-free, never relies on a runtime hash | `cap` — typestate machines, obligations, discriminators, cancelled state |
| `sim.kernel` — immutable cooperative scheduler, **virtual time and exact replay**, `validate-trace!` | `check` — the checker (3,783 lines) |
| `sim.monitor` — versioned trace documents, **pure fold-based monitor runner** | `effect` — rungs, `forward!`, outward instances, latched refusals |
| `sim.explore` — deterministic schedule-plan enumeration, lexicographic, O(N)/plan, never materialises N! | `layer` + `layercheck` — B6, with E40's vacuity accounting |
| `sim.strategy` — deterministic seeded selection | `share`, `refine`, `octet`, `stream` — decision procedures |
| `sim.completion` — pure-data completion registry | `dbtx` — three-destination `begin`, `:poisoned` cancelled state |
| `sim.future-schedule`, `sim.process-explorer` — schedule control over ordinary code; **fresh child per schedule**, timeouts reported as `:timeout`, not mislabelled deadlock | `http`, `tcpcap`, `tlsish`, `wire`, `posix`, `noio` — the effect surface |
| `sim.runtime` — hermetic / observe / hybrid | `nrepl` — a real client over the socket effect |
| `sim.hegel` — generator/shrinker adapter | `ir` — IR capture (93 lines, open by construction) |

Read that table twice. **jolt-sim is a working deterministic runtime with
replay, scheduling and monitoring. perturb is a working declaration language and
checker with no runtime of its own.** They are the two halves of the design in
§1–§6, built separately, and the unification is mostly *deletion*.

### 7.2 The merge, concretely

> **STRUCK — Merge 1 as written is factually wrong.** Verified against source
> on 2026-08-04, after a review challenged it:
>
> - `kernel/validate-trace!` checks **every event's fixed-position shape** and
>   requires a non-empty vector beginning with exactly one `:run/initial`. It
>   would **reject** perturb's events, not absorb them.
> - `kernel/replay` compares the **full recorded sequence**
>   (`first-difference-index expected-trace (:trace result)`) and throws
>   `replay-diverged` on the first difference. Adding events **breaks exact
>   replay of every existing trace.**
> - `README.md:416` already says it: *"A unified causal trace remains later
>   work… lifecycle events remain a separate ordered log; correlating a future's
>   task id across both logs is the caller's responsibility today."*
>
> The kernel trace is a **scheduler artifact**, not a semantic log. So:
>
> > **one trace must begin as a NEW, separately versioned semantic-event
> > document that REFERENCES the replay trace — not by widening it.** The
> > kernel trace stays byte-identical.
>
> This is exactly the error §13 nonclaim 2 predicted: the exists-map was written
> from docstrings, and the one merge everything else depended on was the one it
> got wrong. The corrected version follows.

**Merge 1 — one SEMANTIC EVENT DOCUMENT, versioned separately; the kernel trace
is untouched.**
`perturb.effect`'s log and `perturb.cap/transition!`'s ledger project into a new
document with canonical payloads only and explicit run / task / operation /
source-site / causal-correlation fields. **Missing correlation is
`:inconclusive`, never a pass.** Cost: a projection layer and a new schema.
Buys, immediately:
- **exact replay of a B6 check**, which today has none;
- **schedule exploration over B6** via `sim.explore` — E35 nonclaim 2's "no
  third rung" and nonclaim 1's "one thread, one connection" become *sweeps*
  rather than permanent caveats;
- **process isolation for crash arms** via `sim.process-explorer`, which the
  temporal-ledger review already identified as the one reachable new arm under
  I20;
- one place to attach the OTel exporter (§8).

**Merge 2 — B6 clauses become `sim.monitor` folds.**
`perturb.layer/check` is already a fold over an event log returning a total map
over six clauses with exercised-counts. `jolt.sim.monitor/run-monitor` is
already a pure fold over a validated document. These are the same function with
different vocabularies. After the merge, **every B6 clause is replayable,
schedulable, and exportable**, and `sim.monitor`'s tri-state and `perturb.layer`'s
`:inconclusive` are one mechanism instead of two.
*Watch item:* E36 measured `subvec` as a copying loop. A fold that re-slices per
event is O(n²); the merged runner must fold with a cursor, not a slice.

**Merge 3 — `:failure` models, schedules and strategies are one vocabulary.**
`defsession`'s `:failure`, `defforeign`'s `:failures`, `sim.strategy`'s seeds and
`sim.explore`'s plans are four spellings of "the environment's choices." One
schedule object, consumed by the kernel, the explorer, the metastability rungs
(§9) and the Maelstrom lane (§10).

**Merge 4 — regions subsume the pool shapes on both sides.**
`sim.completion`'s pending/published registry and `sim.runtime`'s outstanding
operations are E24's *collection* and *growth* shapes, already implemented
dynamically. `with-region` is the declaration for what those already do.

**Merge 5 — evidence replaces four separate verdict vocabularies.**
`gatecheck`, `layercheck`, `sharecheck`, `octetcheck` and the sim's monitor
outcomes each print their own verdict shape. One evidence value, one renderer,
one `(gaps)`.

### 7.2a Ownership boundary, and the scope of the merge

**Conditional go for a narrow shared-assurance seam; no-go for a wholesale
merge.** Adopted from the same review, and it is a correction to §7's framing:
five merges were proposed and only the seam is warranted now.

| owner | owns |
| --- | --- |
| **jolt-sim** | semantic projection, simulation, replay witnesses, the monitor runner, evidence rendering |
| **perturb** | declarations, the static checker, capability/region research |
| **Jolt core** | only minimal, **disabled-by-default** hooks, and stable causal/site identities *when proven necessary* |
| extensions | optional handler packs and scenario harnesses |

**No production API may depend on the simulator.** §8's "the trace is the
telemetry" therefore binds production to the **semantic-event document schema**
only — never to `jolt.sim.kernel`. That is a real constraint on §8 and it was
not stated there.

The shared name (`perturb`, per §"On the name") is a naming convenience and does
**not** imply one runtime, one trace schema, or one language project. Those
remain separate, and §7.2's other four merges are deferred behind the seam.

### 7.2b The convergence slice, revised

Replaces §11's first slice, which was scoped to all six B6 clauses and to a
merge that does not work.

1. **`evidence-v1` as a value** — `:strength :scope :source :basis :units
   :residual :as-of`. Reject nonzero strength with zero checked units unless
   explicitly `:inconclusive`. **Plus the converse, which the review's
   formulation omits:** zero checked units **while holding violations** is
   itself a violation (E40's `:vacuity-accounting`) — otherwise a checker may
   report findings with an empty denominator.
2. **A separate versioned semantic-event document** (§7.2 Merge 1, corrected).
   The kernel trace stays unchanged.
3. **ONE B6 clause** — not six. Project its event log into semantic events,
   re-express it as a `jolt.sim.monitor` fold, and **differential-test the old
   checker against the new monitor on pass, violation, and vacuity controls.**
   E40 supplies the `:inconclusive` controls already: B6.3 on the known-good,
   C/B6.5, D/B6.2.
4. **Run that property under one bounded schedule / process-explorer case**,
   keeping the schedule witness and canonical result **separate from host
   supervision facts**. A timeout is not a deadlock proof; native execution is
   not replay.

**Acceptance gate.** Proceed only if the monitor logic is unchanged across the
old log and the semantic projection, the evidence is **byte-stable**, known
failures are preserved, and the monitor returns `:inconclusive` when its
required observations are absent.

**Deferred behind the gate:** regions and static capability enforcement (but see
E41 — already run as a falsification experiment, not as a solution),
`defspec`/`defsession` syntax, the full causal DAG, the production REPL and OTel
export, and CTMC/stability claims. One constraint on the deferred OTel item: the
semantic-event field set should be chosen so that export is a **projection**
rather than a rework.

### 7.3 What does *not* merge, and why

- **`perturb.ir` stays quarantined.** 93 lines of `alter-var-root` tap whose own
  docstring says a namespace loaded before `install!` is never re-analysed. The
  universe is open **by construction** and load-order dependent. It must never
  be the input to a completeness denominator; that is `report-limits` 14(f)'s
  failure mode exactly.
- **`dev/verify-noio.sh` stays a process-level check.** §A3 warns specifically
  against letting a trace-level checker stand in for the fail-closed FFI seam.
  Nothing in the merge may swap them.
- **I20 stays true until it is not.** `:contention :thread-confined` everywhere;
  perturb has never run on two real threads. Schedule exploration over *logical*
  time is the honest answer and covers a great deal; **physical contention —
  cache lines, GC, kernel scheduling — remains `:native` only**, permanently.

---

## 8. Telemetry

### 8.1 The trace already is the telemetry

Hand-instrumented OTel has one chronic weakness: span boundaries land wherever
someone remembered a `try`. Here the runtime already knows the boundaries the
checker reasons about.

| span | from |
| --- | --- |
| a request served by a declared layer | B6's request extent |
| one rung handling one operation | rung instance + outward instance |
| a foreign call | `defforeign` boundary |
| a transaction | `dbtx` typestate |
| a region's lifetime | `with-region` |
| a session exchange | `defsession` projection |

**Zero user instrumentation produces a better trace than most instrumented
systems**, because the spans are semantic rather than lexical.

### 8.2 Mapping

- **Span** = an extent. Parent/child from the effect frame stack — no manual
  context threading, because `*frame*`/`*outward*` already *are* the context.
- **Links** = causal edges that are not parent-child: fan-out (one `send` → eight
  forwards), demand-driven answers served from a buffer an earlier forward
  filled, stream correlation, cross-process session causality. This is where
  OTel links finally earn their keep.
- **Events** = capability transitions, taps, monitor verdicts, refusals, region
  admissions/releases.
- **Metrics** = evidence counts, monitor pass/violation/**inconclusive** counts,
  residual counts, credit balances, region occupancy, budget rates.
- **Baggage** = evidence context and capability **correlators**. Never tokens.
- **Resource attributes** = *the deployed artifact's evidence summary*, so a
  backend can answer **"error rate on endpoints whose handlers are `:asserted`
  behind a foreign belief."** Assurance tier becomes a dimension you slice
  production by — nearly free once evidence is a value.

### 8.3 Where OTel does not fit, stated rather than papered over

**OTel span status is `Ok | Error | Unset`. There is no third truth value**, and
`Unset` already means "nobody set it." So violations → `Error`, exercised-and-held
→ `Ok`, and **`inconclusive` is an explicit attribute plus its own metric**
(`assurance.monitor.inconclusive`), never `Unset`. Any dashboard built on status
alone reads our vacuity as noise. That is a known lossy edge of the export and
belongs in the residual list.

### 8.4 Conventions

Reuse `http.*`, `db.*`, `messaging.*`, `rpc.*` so this drops into existing
dashboards on day one. Invent only for what has no analogue:

```
assurance.evidence.strength / .scope.covered / .scope.of / .source
assurance.spec.basis · assurance.monitor.verdict · assurance.residual.next_layer
capability.id / .state / .region / .obligation.owed
effect.operation_class · effect.credit.balance
session.role / .projection_state
foreign.belief / .belief_rev
```

### 8.5 Sampling — head and tail, tail by default

- **Head** decides at trace start; needed for cost-critical paths and
  cross-process propagation.
- **Tail** decides at completion, after the span tree is buffered. **Default.**

**Always-keep (tail).** Span status `Error`; any monitor violation; any latched
refusal; **any `inconclusive` on a clause that is normally exercised** — vacuity
is signal; foreign-belief conformance failure; region exiting non-empty; unmet
obligation; illegal typestate transition; credit-rule breach; latency outliers;
**novelty** — an unseen span-tree shape or (route × error) pair.

**Cross-process, which is where tail sampling usually breaks.** W3C
`traceparent` carries one bit and downstreams act immediately. Resolution:
**head decides "keep at least", tail decides "keep more."** Head propagates
`sampled=1` only for definitely-keep; when undecided it propagates a *defer*
hint in baggage so downstreams buffer too. A peer that ignores the hint
truncates the trace — recorded as a **causality residual**, never stitched by
timestamp guessing.

**Two honesty rules**, because sampling is the easiest place to launder scope:

1. **The sampling decision is a value in the trace** — which policy, why, what
   effective rate — and any `:monitored` evidence derived from sampled traces
   carries that rate in `:scope`. A 1/1000 monitor reporting as "monitored"
   without its rate is E40's defect in a new costume.
2. **Buffer overflow is a measured scope shortfall**, never a silent drop.

In sim, sampling decisions are a function of the schedule, not the clock, so
telemetry is reproducible.

### 8.6 Three traps

1. **Telemetry is an effect.** It goes through the effect boundary, is
   schedule-controlled, and in hermetic mode the exporter *collects* rather than
   sends — so emission cannot perturb determinism and `verify-noio` still sees
   an empty window.
2. **Cost.** Static attributes interned at definition; span records preallocated
   per frame; no map construction on the hot path. Acceptance criterion,
   measured not assumed: **framework spans at baseline sampling cost less than
   the credit fold they sit beside.**
3. **Cardinality.** Capability and request ids are unbounded — span attributes
   only, **never metric labels**. Regions, sites, operation classes, roles and
   evidence tiers are bounded and are the label dimensions.

---

## 9. Metastability and the fidelity ladder

**Source:** Alvaro, Isaacs, Majumdar, Muniswamy-Reddy, Salamati, Soudjani,
*Formal Analysis of Metastable Failures in Software Systems*,
**arXiv:2510.03551v2**. Read 2026-08-04 via the ar5iv HTML rendering converted
to text — `arxiv.org` is blocked by this environment's egress policy.
**The figures were not seen**, and the qualitative visualisation is a
figure-heavy contribution; that reading is text-only.

### 9.1 What the paper does

1. A **DSL** (Python-embedded) for request-response systems — servers with a
   queue and worker pool, clients with arrival distribution, timeout and retry
   policy; acyclic client/server graphs. Semantics is a **discrete-event
   simulation** (~100 lines of asyncio) and **the DES is treated as ground
   truth**.
2. Abstraction to a **CTMC** with two-dimensional per-server state `(u, v)`:
   requests in the system, and — from retrial queueing theory — the **orbit**,
   requests waiting to be retried. The second axis *is* work amplification made
   into a state variable.
3. **Data-driven calibration** with **CMA-ES** against short DES trajectories,
   because the ab initio CTMC deviates badly. Their example moves λ, τ from
   `[9.5, 9]` to `[9.43, 10.54]` in 30 iterations / 978 s. Their finding is
   blunt: *calibration is crucial*, and uncalibrated models "deviate from reality
   already for simple systems."
4. **Qualitative**: a 2-D vector field over (queue, orbit) showing dominant flow
   direction, computed **in milliseconds**.
5. **Quantitative**: Definition 5.1 defines ρ-metastability via **escape
   probabilities** relative to a chosen set `D`; Theorem 5.2 relates *k*
   metastable states to a **cluster of k eigenvalues of −Q near zero**, with an
   `O(ρ/|S|)` error term. Mixing and hitting times follow from sparse linear
   algebra.

Two framing points are the paper's sharpest. **Metastability is not expressible
in CSL or probabilistic LTL** — those capture transient or stationary behaviour,
not two separated time scales. And **classical queueing's stable/unstable
dichotomy misses it**: M/M/c does not exhibit metastability at all, and a prior
definition conflated metastable with unstable. Operationally: ≤3 servers sufficed
to reproduce many real hyperscaler incidents, and reproduction went from **weeks
to hours**.

### 9.2 A correction to an earlier sketch in this line of work

An earlier draft of this design proposed **hysteresis load sweeps** as the
detection method. That is worse and is withdrawn as the primary. A sweep costs
hours; escape probability and the spectral gap on a calibrated CTMC cost
milliseconds. **The sweep becomes the check on the model, not the analysis.**

### 9.3 What this design adds

**(a) The model is generated, not hand-written.** The paper's DSL is a separate
artifact from the system — the classic model-drift problem. Here every parameter
is already declared or observable:

| CTMC needs | already exists as |
| --- | --- |
| timeout τ, retry policy ρ | `defforeign` / `defsession` failure model |
| queue capacity, worker pool | a **region** with a declared bound |
| topology | the effect/session graph |
| arrival λ, service μ | OTel spans |
| **orbit v** | **the retry region's occupancy** |

That last row matters most. In the paper the orbit is an *unobservable* model
variable that calibration must infer. Here **retries-in-flight are a region, so
the orbit is directly measured** — calibration gets real data on both axes
instead of one, which should shrink the very fit error the paper identifies as
make-or-break. Model drift becomes a stale-evidence event like any other.

**(b) The model carries an evidence value, and model error is a finding.**

```clojure
{:strength :sampled :source #{:simulated}
 :scope    {:covered "queue ≤100 × orbit ≤20, 1 server"
            :of      "the deployed topology (7 services, cycles present)"}
 :basis    "one calibrated CTMC over the (queue, orbit) grid"
 :residual [{:owed :calibration-fit :fitted [:λ :τ] :fixed [:μ :ρ]
             :method :cma-es :hyper {:Z 2 :M 100 :L 1800 :Ts 0.5}}
            {:owed :spectral-approximation :term "O(ρ/|S|)"}
            {:owed :acyclic-assumption :next-layer :none}]}
```

Two consequences follow from the paper's own results:
**an uncalibrated CTMC verdict is `:asserted`, never higher**; and **when a
higher rung disagrees with a lower rung's prediction beyond declared tolerance,
that is a violation of the model, reported.** The paper checks CTMC against DES;
this design closes the chain — **production checks the DES.**

**(c) `D` is a denominator.** Metastability in Definition 5.1 is defined
*relative to a chosen `D`* (their experiments use `{Low, High}`). **Choose `D`
badly and the analysis returns a confident answer about nothing.** So `D` needs
a mandatory one-sentence `:basis` and a vacuity check: if no state outside `D`
is attracted to `D` on the short time scale, the result is `:inconclusive`, not
"no metastability found." E40 in a new domain.

**(d) The mitigation that becomes the amplifier.** The paper sweeps fixed
mitigations (throttle to 8 RPS moves λ₂ away from zero). Because mitigations here
are declared values, that sweep is automatic. But an **adaptive** controller —
autoscaler, adaptive concurrency limiter, dynamic shedder — *changes the chain*,
and a feedback loop with lag is exactly the shape that sustains metastability.
**A controller ships with its own spectral check on the closed-loop CTMC** or it
does not go to production.

### 9.4 The ladder

| rung | model | cost | evidence | checks |
| ---: | --- | --- | --- | --- |
| 0 | calibrated CTMC: vector field, escape probability, λ₂ | **ms** | `:sampled :simulated` + calibration residual | — |
| 1 | CTMC sweep over a declared grid | minutes | `:exhausted` over *that grid* | — |
| 2 | DES of the topology | min–hrs | `:sampled :simulated` | rung 0 |
| 3 | real code, virtual clock, trace-driven arrivals | hours | `:sampled :simulated` | rung 2 |
| 4 | production / canary, same monitors | live | `:monitored :native` | rung 3 |

**Rung 2 is the cheapest rung to reach**, because `jolt.sim.kernel` is already a
virtual-time discrete-event scheduler with exact replay, and `sim.explore` is
already the enumerator. Rung 1 is where `:exhausted` is honestly reachable — a
finite declared grid, fully enumerated, counts reported — and the paper's "≤3
servers suffices" is what makes that grid small enough to mean something.

### 9.5 The production loop

1. Tail-sampled traces yield arrival process, fan-out, service-time
   distributions **and region occupancies** — λ, μ and the orbit, continuously.
2. The CTMC recalibrates on that window and **λ₂ becomes a live metric**:
   distance to the metastable boundary. That is a *pre-failure* signal, which
   ordinary dashboards structurally cannot provide.
3. **λ₂ approaching zero arms `:on-suspicion`** — full-fidelity capture on those
   paths, which is exactly the trajectory data calibration wants. **Fidelity
   rises where the model says the system is fragile, automatically and only
   there.**
4. Mitigation search runs at rung 0 in milliseconds and verifies at rungs 2–3.
5. Every captured collapse minimises into a permanent sim scenario.

---

## 10. Validation: the Maelstrom lane

`https://fly.io/dist-sys/` (Gossip Glomers) runs on **Maelstrom**, a Jepsen
harness: a node is a process speaking newline-delimited JSON on stdin/stdout;
Maelstrom drives clients, injects partitions, latency and crashes, and checks
the resulting history with Jepsen's checkers.

**Why this is the right validation target, in this record's own terms:**

- **The oracle is independent and total, and we did not write it.** E39 killed a
  `bounded-complete` claim on precisely this condition and found one blemish
  where an implementation was compared against an implementation. Jepsen's
  checkers cannot have that defect here.
- **The configurations are small** (3–5 nodes), so rung-1 exhaustive claims over
  a declared grid are reachable, with Maelstrom supplying `:native` confirmation.
- **The faults are real and adversarial**, supplied by someone with no interest
  in our passing.
- **It exercises the parts of the design with no evidence today**: sessions,
  foreign roles, partial-order traces, region obligations, and — in the
  efficiency challenges — work amplification.

### 10.1 Challenge → design feature

| challenge | exercises | honest target |
| --- | --- | --- |
| echo | the process/JSON effect boundary; one trace type across a process | `:monitored` |
| unique-ids | obligation: uniqueness under partition | rung-1 `:exhausted` on ≤4 nodes; `:native` confirm |
| broadcast a–c | `defsession` projection; in-flight = region; "every message eventually delivered" = region exit obligation | `:exhausted` over a declared schedule grid |
| **broadcast d–e (efficiency)** | **msgs-per-op budget = work amplification = §9's orbit.** The first place the metastability lane and the correctness lane meet on one artifact | rung-0 λ₂ + rung-4 measurement |
| g-counter | CRDT convergence as a monitor fold; `unknown` under partition | `:monitored` |
| kafka-style log | §6's streaming: offsets as obligations, ack-or-redeliver as region exit | `:monitored` + `:exhausted` decoder |
| txn-list-append | `dbtx` typestate at a distance; isolation as a failure model | `:monitored`; anomalies enumerated at rung 1 |

### 10.2 The two-lane discipline

Run **our** simulator and **Maelstrom** on the same node code, and report them
separately:

- our lane: `:exhausted` / `:sampled`, `:source #{:simulated}`, schedules
  enumerated by `sim.explore`, counts stated;
- Maelstrom: `:sampled`, `:source #{:native}`, oracle independent, seeds stated.

**Disagreement between the lanes is the finding.** If Maelstrom finds a history
our exhaustive lane declared impossible, our schedule model is wrong — and
learning *that* is worth more than passing.

**Dependency risk, named:** Maelstrom needs a JVM, graphviz and gnuplot, and the
`clojure` CLI is not installed in this environment (E39 nonclaim 1 hit the same
wall). The Maelstrom lane may be `:native`-unreachable here and runnable only
elsewhere. That is a scheduling fact, not a reason to redesign around it.

---

## 11. First slice: the replay debugger, in perturb

Two candidates were considered — the database lane (§3.2) and the Maelstrom lane
(§10). **The replay debugger is first**, because it is the only one that forces
Merge 1, and Merges 2–5 and §8–§10 all depend on Merge 1.

**Build:** `perturb.replay` — a REPL surface over a `jolt.sim.monitor` trace
document containing perturb's effect log and capability ledger as event kinds.

1. Project `perturb.effect`'s log and `perturb.cap`'s ledger into
   `jolt.sim.trace` events; accept `kernel/validate-trace!`.
2. `datafy`/`nav` over: run → trace → event → capability → machine → obligations,
   with the four nav classes and redacted correlators (§5.1).
3. `(rewind n)`, `(step)`, `(capture!)` on `sim.kernel`'s existing exact replay.
4. Run **B6's six clauses unchanged** as a `sim.monitor` fold over the replayed
   document.
5. `(gaps)` over the residuals the run emits — including `:inconclusive`, which
   E40 made an artifact actually emit.

**Acceptance criteria, decidable:**

- **(a)** `-M:layer`'s six clause verdicts and their exercised-counts are
  **byte-identical** whether computed by `perturb.layer` directly or by the
  monitor fold over the replayed trace. Any difference is a projection defect.
- **(b)** A replayed run is **schedule-explorable**: `sim.explore` produces ≥2
  distinct plans for a fixture and B6 verdicts are reported per plan. This is
  the first evidence in the record that a B6 clause holds under more than one
  schedule.
- **(c)** `(gaps)` lists E40's two unexercised arms, by name, without anyone
  writing them down.
- **(d)** `verify-noio.sh` still reports an empty attributable window with the
  replay surface loaded.

**Then and only then**, the thesis test from §1.1 on one real property, and the
Maelstrom `echo` + `broadcast-a` nodes as the first `:native` lane.

---

## 12. Exists vs. new

| piece | status |
| --- | --- |
| canonical trace, exact replay, virtual time | **exists** — `sim.trace`, `sim.kernel` |
| pure fold monitors over validated documents | **exists** — `sim.monitor` |
| schedule enumeration, seeded strategies, process isolation | **exists** — `sim.explore`, `sim.strategy`, `sim.process-explorer` |
| generator/shrinker adapter | **exists** — `sim.hegel` |
| typestate, obligations, discriminators, cancelled state, sum destinations | **exists** — `cap`, `dbtx` |
| effect rungs, latched refusals, outward instances | **exists** — `effect` |
| layering invariant with vacuity accounting | **exists** — `layer`, E40 |
| decision procedures (share, refine, octet, stream) | **exists** |
| a real client over an effect boundary | **exists** — `nrepl` |
| **evidence as a value; the meet; `:basis` at definition time** | **new**, small |
| **`defspec` with more than one consumer** | **new**, the thesis |
| **regions** | **new**, and the answer to §4.6 |
| **`defsession` / `defforeign`** | **new** |
| **`datafy`/`nav`/`tap`/`capture` surface** | **new**, mostly assembly (§11) |
| **OTel exporter, budgets, tail sampling** | **new**, mechanical once Merge 1 lands |
| **CTMC generation + calibration + spectral analysis** | **new**, and the only piece needing numerics we do not have |
| **indexed fact store + graph algorithms** | **new**, small; facts come from declarations and the trace, both of which exist |
| **a manifest: topology as one data artifact, with path enumeration** | **new** here, but **not new work** — Mycelium's manifest + `enumerate-paths` + `compile-workflow` is a working design to adopt |
| **interceptors as the notation for rungs** | **new** notation over an **existing** mechanism (`perturb.effect`) |
| **subscriptions / materialized views with dedup** | **new**; brief work-queue item 5, and E35 finding 3 is the bug it prevents |
| **flow lifecycle and supervision (pause/drain/resume/ping)** | **new**, and §3.5 records it as an outright gap on both sides |
| **cells over regions (driver B)** | **existing code, new primitive** — `perturb.evt` + `evtcheck` already run both drivers and compare octets |
| **a small Datalog (semi-naive, stratified negation)** | **new**, low hundreds of lines |
| **SMT escalation past `refine`'s fragment** | **new**; Z3 already a toolchain dependency via `bin/verify-models` |

---

## 13. Nonclaims

1. **Nothing here is built.** Every line is a design claim, which by this
   record's standing rule should not be trusted ahead of the artifact that tests
   it. §11 exists to produce that artifact early.
2. **The exists column is from docstrings and line counts**, not from reading
   7,549 + 21,656 lines. §7.2's merges are proposals about interfaces, and the
   record on source-reading inferences in this project is poor.
3. **`:proved` appears in the lattice and nothing in this design produces it.**
   Region inference through higher-order code is known-hard; the honest expected
   ceiling for a long time is `:exhausted` on small declared grids and
   `:monitored` everywhere else.
4. **The meet may make the system unusable before it makes it honest.** Per-path
   reporting is the proposed mitigation and it is untested.
5. **The metastability lane needs numerics this codebase does not have** —
   sparse eigenvalue computation and CMA-ES. Rungs 0–1 may be an external tool
   behind a `defforeign` boundary, which would be an amusing but genuine
   `:asserted` dependency at the heart of the analysis.
6. **The paper was read text-only, figures unseen** (§9), and its most-praised
   contribution is a visualisation.
7. **Maelstrom may not run in this environment** (§10.2), so the independent-
   oracle lane — the strongest validation on offer — may be unreachable where
   the work happens.
8. **I20 is not resolved by anything here.** Logical-time schedule exploration
   covers a great deal; physical contention stays `:native` only, permanently.
9. **The query surface's best fact source is the one that does not exist yet.**
   §5.5's declaration-sourced queries need the declarations to be *reachable as
   data in one place*, and today they are spread across `cap`, `dbtx`, `http`,
   `tcpcap` and the checker's own tables. Collecting them is not hard, but it is
   unbuilt, and until then the honest source is the trace — which can never
   answer an unreachability question.
10. **Datalog's termination guarantee is about the query, not the model.** A
   fixpoint over the declared facts is complete *over those facts*. It says
   nothing about whether the declarations describe the program, which is exactly
   what `(diff/observed-not-declared)` exists to measure and exactly what the
   record has repeatedly found to be false.
11. **§11's acceptance criterion (a) may fail for a boring reason** — the two
   event models may not be projectable onto each other without loss. If so, that
   is the finding, and it invalidates Merge 1 rather than the thesis.
