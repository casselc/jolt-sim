# Brief: verify E20 against the actual papers

**Run this where the network is not restricted.** E20 (`PERTURB-DESIGN.md` §3)
is a literature survey in which **no paper was read in full text** — every
scholarly host was blocked by an egress policy, so its citations were verified
for existence, venue, authors and year, and its claims were drawn from
abstracts, search-result extractions, and whatever implementation artifacts were
reachable. E20 nonetheless **refuted four claims the design record had made**,
and those refutations are currently the weakest-evidenced statements in the
document. This brief exists to replace them with read sources or to overturn
them.

---

## The prompt

> You are verifying a literature survey against its sources. The survey is §3's
> finding **E20** in `docs/research/PERTURB-DESIGN.md`, on branch
> `claude/ocaml-effect-based-language-gsg316` of `casselc/jolt-sim`. Its
> bibliography is **Appendix D** of the same file, organised into D.1–D.7 with
> DOIs and URLs.
>
> **Context.** `perturb` is an effect-based Clojure-like under design. It has a
> capability tier (uniqueness, linearity, typestate, contention, plus
> refinements) enforced by a static checker that reads real compiler IR and
> rejects programs. Findings E15–E19 record what that checker got wrong and how
> it was repaired; E20 asked what the literature says about the remaining gaps.
> Read §1.2, §1.3, §1.4, §1.7, §4.6 and findings E15–E20 before starting. Read
> `perturb/src/perturb/{cap,check,refine,nrepl,http}.clj` if a claim turns on
> what the artifact actually does.
>
> **Your job is not to summarise the literature.** It is to take each numbered
> claim below, obtain the paper, read the part that bears on it, and return a
> verdict: **CONFIRMED**, **REFUTED**, **OVERSTATED** (directionally right,
> stronger than the source supports), **UNDERSTATED**, or **NOT FOUND** (the
> source does not address it). Every verdict must carry a quotation with a
> section or page number. A verdict with no quotation is not a verdict.
>
> Where a claim is CONFIRMED, say whether the paper's actual scope is narrower
> than E20's use of it. That is the most likely failure mode here: a correct
> sentence from an abstract, applied to a case the paper does not cover.
>
> **The claims, in priority order. The first three are load-bearing on decisions
> already taken.**
>
> 1. **The linear conditional rule.** E20 claims that in a linear/session-typed
>    language a conditional types **both branches in the same linear context**,
>    so a branch that consumes a capability and one that does not are not both
>    typable — and therefore that perturb's join rule *is* the linear rule, and
>    that internal/external choice (⊕/&) does **not** dissolve it. Source:
>    Vasconcelos, *Fundamentals of Session Types*, Information and Computation
>    217, 2012 (and the SFM 2009 lecture notes). Find the typing rule for the
>    conditional. Does it split the context and type both branches against the
>    same residual? Does the paper say anything about what happens when the
>    branches disagree? **This refuted a claim in the design record; if it is
>    wrong, §4.6's join-rule item and E20's tally row 27 must be reverted.**
>
> 2. **Effect handlers break linearity, and no-resumption avoids it.** E20
>    claims: linear type systems assume continuations are used linearly, which
>    handlers violate by discarding or multi-invoking them; Links carried a
>    soundness bug for years because of this; the fix is a second discipline
>    called control-flow linearity. And it claims perturb's §1.4 rule — a
>    handler substitutes a validated result or aborts, with no resumption and no
>    continuation capture — places perturb **outside** that tension. Source:
>    Tang, Hillerström, Lindley, Morris, *Soundly Handling Linearity*, POPL 2024
>    (arXiv 2307.09383). Verify the first three parts from the paper. Then
>    assess the fourth **as an argument, not a citation** — the paper is not
>    about perturb. State precisely what restriction on handlers suffices, and
>    whether "tail-resumptive or abort" is that restriction or merely close to
>    it. **§1.4's D4 decision now leans on this.**
>
> 3. **Exceptions require affinity plus explicit cancellation.** E20 calls this
>    the highest-value item perturb's queue did not contain: to have exceptions
>    at all in a linear setting, linearity must weaken to affinity **plus** an
>    explicit cancellation obligation, so a resource abandoned by a non-local
>    exit is cancelled rather than leaked. Source: Fowler, Lindley, Morris,
>    Decova, *Exceptional Asynchronous Session Types: Session Types without
>    Tiers*, POPL 2019. What exactly is the cancellation obligation, what
>    discharges it, and what does it cost? perturb's `abort!` is a non-local
>    exit thrown past live capabilities (E15 blind spot 4), and its capabilities
>    already bind affinely (E6). **What would perturb have to add?** Be
>    concrete — this is the one item likely to become work.
>
> 4. **Grades do not decide a value-dependent counting obligation without a
>    solver.** E20 claims Granule requires Z3; that graded systems are
>    "parameterized by the decision procedure of the semiring equational theory"
>    (Ghica & Smith, ESOP 2014); and that the AARA authors concluded
>    value-dependent bounds need refinements (Knoth, Wang, Reynolds,
>    Polikarpova, Hoffmann, *Liquid Resource Types*, ICFP 2020). The concrete
>    question this must answer: **can any graded/quantitative system express "this
>    handle owes the wire exactly N more octets", where N is a run-time integer
>    committed to in an already-sent header, and decide it without SMT?** Check
>    Granule, QTT/Idris 2, Linear Haskell, bounded linear logic and dℓPCF. Also
>    check the recent work on value-dependent multiplicities (arXiv 2507.08759)
>    which E20 could not fetch. **This refuted a hypothesis; tally row 28.**
>
> 5. **Sealing relocates trust rather than discharging it.** E20 claims abstract
>    types (Mitchell & Plotkin 1988; F-ing Modules) give a *scope* rather than a
>    *guarantee about the implementation*, and that the apparatus which does
>    give the latter is abstract predicates (Parkinson & Bierman, POPL 2005) or
>    a semantic model (RustBelt, POPL 2018, where each library carries a
>    verification condition rather than an exemption). Confirm the RustBelt
>    framing verbatim — E20 rests a recommendation on one sentence about
>    per-library verification conditions. **Tally row 29.**
>
> 6. **Sealing creates linearity rather than merely preserving it.** E20 claims
>    Alms (Tov & Pucella, *Practical Affine Types*, POPL 2011) lets an interface
>    impose **stiffer** usage restrictions than its implementation's principal
>    ones, so an unrestricted concrete representation becomes an affine
>    capability at the seal. If true this is the best available account of what
>    perturb's `:perturb.cap/representation` boundary is *for*. Verify, and find
>    what Alms requires for it to be sound.
>
> 7. **One operation may advance several machines.** E20 claims Vault (DeLine &
>    Fähndrich, PLDI 2001) gives functions pre/post conditions over the **whole
>    key set**, making a two-machine operation ordinary rather than
>    inexpressible — and that perturb's E18 finding 1(a) was a rediscovery of a
>    problem solved in 2001. E19 has since fixed it by keying `[capability
>    operation]`. Check whether Vault's form is more general than what E19
>    built, and in what way.
>
> 8. **Refinements inside recursive session types cost decidability.** E20 cites
>    Das & Pfenning, *Session Types with Arithmetic Refinements*, CONCUR 2020
>    for: type equality, subtyping and type checking become **undecidable**
>    despite Presburger being decidable, and Rast ships a practical incomplete
>    algorithm. E19 attached its refinement to a *transition* and discharges it
>    by normalisation, which E20 says avoids this. Confirm the undecidability
>    result and its cause, and say whether E19's placement genuinely avoids it.
>
> 9. **Empirical numbers.** Verify: Rudra (SOSP 2021) found 264 memory-safety
>    bugs in one scan of ~43,000 crates, representing 51.6% of RustSec's
>    memory-safety history; Astrauskas et al. (OOPSLA 2020) found 92.3% of
>    crates have an unsafe-statement ratio ≤10%; seL4's proof-to-code ratio is
>    ~20:1. E20 uses the first to argue that trusted cores are small and often
>    wrong. Check the numbers and their denominators.
>
> 10. **Flux.** E20 recommends Lehmann, Geller, Vazou, Jhala, *Flux*, PLDI 2023
>     as the nearest existing system to perturb's shape, on the grounds that it
>     combines affine ownership, exclusive borrows, refinements on owned data,
>     strong updates on indexed locations (= typestate carrying a refinement),
>     and **inferred** loop invariants via Horn constraints. Verify each
>     conjunct. If it holds, extract what perturb's `perturb.refine` would need
>     to reach the same class — E19's procedure refuses every loop.
>
> 11. **Things E20 says do not exist.** Each is a negative claim and negative
>     claims are the ones a blocked search gets wrong. Search properly for:
>     (a) anyone typing a **sans-io** parser/driver split — a pure step function
>     returning ok / need-more-with-the-*exact original* cursor / invalid;
>     (b) an implementation of client-server sessions in linear logic (Qian,
>     Kavvos, Birkedal, ICFP 2021);
>     (c) a graded system used to enforce a **wire-format length** obligation;
>     (d) any empirical measurement of how often a conditional-move/join rule
>     rejects real code;
>     (e) a metric for trusted surface that survives adversarial refactoring
>     (perturb found operation-counts gameable; `cargo-geiger` has the mirror
>     defect).
>     For each: found or not found, with what you searched.
>
> **Rules of engagement.**
>
> - **Do not repair E20 by softening it.** If a claim is wrong, say so plainly
>   and say what the design record must change — including reverting a tally row
>   or reopening a §4.6 item.
> - **Quote, with a locator.** Section or page. An abstract is not a source for
>   a claim about a paper's content; say so when that is all you could get.
> - **Distinguish paywalled-and-unread from read.** If you cannot obtain a
>   paper, say which and why. ACM's `dl.acm.org` sits behind Cloudflare and will
>   challenge automated fetches even when reachable; prefer author-hosted PDFs,
>   arXiv, DBLP's links, and LIPIcs (`drops.dagstuhl.de`), which are open.
> - **No claim stronger than its artifact**, which here means: no claim stronger
>   than the text you actually read.
>
> **What to produce.**
>
> 1. Edit `docs/research/PERTURB-DESIGN.md`:
>    - a new finding **E21** in §3, in the style of E15–E20, giving the verdict
>      table and, for every REFUTED or OVERSTATED item, what changes elsewhere;
>    - update the tally table at the top for any claim E21 overturns, including
>      E20's own rows 27–29 if they do not survive;
>    - update **Appendix D**: mark every reference you actually read, and add a
>      column or note for ones that proved unobtainable;
>    - update E20's method note so it no longer says "no paper was read in full
>      text" without qualification.
> 2. Update §4.6 for any open item that closes, opens, or changes shape —
>    especially the `abort!` cancellation item (claim 3) and the module-boundary
>    item (claims 5 and 6).
> 3. Commit to `claude/ocaml-effect-based-language-gsg316` with a message that
>    states what was overturned. Do not open a pull request.
>
> **House style.** Findings are named after the evidence that produced them.
> Docstrings and sections state what a thing does **not** establish. Corrections
> are recorded rather than silently applied — this document keeps a running
> tally of its own refuted claims and that tally is the point. If this brief
> contains a factual error, say so in your report; the last five briefs each
> contained one that the agent caught.

---

## Notes for whoever runs it

- **The four E20 refutations are the priority.** Claims 1, 4 and 5 are tally
  rows 27–29; claim 2 is the basis on which §1.4's D4 decision is now defended.
  If only part of this brief gets done, do those.
- **Claim 3 is the one most likely to become work**, not just a record change.
- **ACM is a dead end for automated fetching** even when allowlisted — it
  returns a Cloudflare interstitial. Route around it via arXiv, DBLP, LIPIcs,
  Semantic Scholar, and author pages.
- Hosts worth having reachable, in rough priority: `arxiv.org`,
  `drops.dagstuhl.de`, `dblp.org`, `www.semanticscholar.org`,
  `homepages.inf.ed.ac.uk` (Links group — claims 2 and 3), `www.di.fc.ul.pt`
  (Vasconcelos — claim 1), `plv.mpi-sws.org` (RustBelt — claim 5),
  `ranjitjhala.github.io` (Flux — claim 10), `granule-project.github.io`
  (claim 4), `www.cs.cmu.edu` (AARA — claim 4).
