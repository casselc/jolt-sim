# Papers redistributed here, and why each one may be

**The rule.** A paper is committed here **only if there is an explicit licence
grant permitting redistribution** — in every case below, Creative Commons
Attribution 4.0, except Das & Pfenning (LIPIcs CC-BY). Each file is the
**verbatim published PDF** from the source given; **no PDF here has been
modified**, and the attribution CC-BY requires is the citation in the table.
(The Markdown renderings under `markdown/` *are* modified derivatives, and are
labelled as such — see below.)

**A correction to how that rule was first applied.** The first eight files here
were selected by reading the licence line **printed on the PDF**. That test is
too conservative and it excluded five papers wrongly. ACM's
`© Copyright held by the owner/author(s)` line is a copyright statement, not a
licence — that part was right — but for PACMPL (POPL/ICFP/OOPSLA/PLDI proceedings
of the ACM) the **licence grant lives in the publisher's Crossref metadata**, not
always in the PDF furniture. Querying `api.crossref.org` for each DOI returns
`https://creativecommons.org/licenses/by/4.0/` for Fowler et al., RustBelt,
Astrauskas et al., Liquid Resource Types and Qian et al. — all five of which the
first pass had listed as "not redistributable". They are now included. The
authority for a licence is the publisher's machine-readable metadata, cross-checked
against Unpaywall's OA-location records; the printed line is corroboration, not
the test.

**Markdown conversions.** `markdown/` holds a machine-generated Markdown
rendering of every PDF here, so the corpus can be grepped. They are **lossy** —
mathematics and figures do not survive — and they are *derivative works*, which
CC-BY permits provided the modification is indicated; that is done in each
file's header, in `markdown/README.md`, and by committing the converter itself.
**Quote from the PDFs, not from the conversions.**

## Contents

| file | citation | licence (source of the grant) | copy used | used by |
| --- | --- | --- | --- | --- |
| `PLDI22-flexible-type-system-for-fearless-concurrency.pdf` | Milano, Turcotti, Myers, *A Flexible Type System for Fearless Concurrency*, PLDI 2022. [10.1145/3519939.3523443](https://doi.org/10.1145/3519939.3523443) | CC-BY 4.0 (in PDF) | supplied | E25; D.8 |
| `flo-semantic-foundation-progressive-stream-processing.pdf` | Laddad, Cheung, Hellerstein, Milano, *Flo: A Semantic Foundation for Progressive Stream Processing*, POPL 2025. [10.1145/3704845](https://doi.org/10.1145/3704845) | CC-BY 4.0 (in PDF) | supplied | E25; D.8 |
| `POPL24-soundly-handling-linearity.pdf` | Tang, Hillerström, Lindley, Morris, *Soundly Handling Linearity*, POPL 2024. [10.1145/3632896](https://doi.org/10.1145/3632896) | CC-BY 4.0 (arXiv posting) | [arXiv:2307.09383](https://arxiv.org/abs/2307.09383) | E21 claim 2; row 30; D.3 |
| `POPL19-exceptional-asynchronous-session-types.pdf` | Fowler, Lindley, Morris, Decova, *Exceptional Asynchronous Session Types: Session Types without Tiers*, POPL 2019. [10.1145/3290341](https://doi.org/10.1145/3290341) | CC-BY 4.0 (Crossref) | author copy of the version of record, [slindley/papers/zap.pdf](https://homepages.inf.ed.ac.uk/slindley/papers/zap.pdf) | E21 claim 3; row 31; §1.2; §4.6; D.3 |
| `POPL18-rustbelt-securing-the-foundations-of-rust.pdf` | Jung, Jourdan, Krebbers, Dreyer, *RustBelt*, POPL 2018. [10.1145/3158154](https://doi.org/10.1145/3158154) | CC-BY 4.0 (Crossref) | [plv.mpi-sws.org](https://plv.mpi-sws.org/rustbelt/popl18/paper.pdf) | E21 claim 5; row 29; §4.6; D.5 |
| `OOPSLA20-how-do-programmers-use-unsafe-rust.pdf` | Astrauskas, Matheja, Poli, Müller, Summers, *How Do Programmers Use Unsafe Rust?*, OOPSLA 2020. [10.1145/3428204](https://doi.org/10.1145/3428204) | CC-BY 4.0 (Crossref) | [pm.inf.ethz.ch](https://pm.inf.ethz.ch/publications/AstrauskasMathejaMuellerPoliSummers20.pdf) | E21 claim 9; §6; D.6 |
| `ICFP20-liquid-resource-types.pdf` | Knoth, Wang, Reynolds, Hoffmann, Polikarpova, *Liquid Resource Types*, ICFP 2020. [10.1145/3408988](https://doi.org/10.1145/3408988) | CC-BY 4.0 (Crossref) | [cs.cmu.edu](https://www.cs.cmu.edu/~janh/assets/pdf/WangKRPH20.pdf) | E21 claim 4; row 28; D.4 |
| `ICFP21-client-server-sessions-in-linear-logic.pdf` | Qian, Kavvos, Birkedal, *Client-Server Sessions in Linear Logic*, ICFP 2021. [10.1145/3473567](https://doi.org/10.1145/3473567) | CC-BY 4.0 (Crossref; also in PDF) | [pure.au.dk](https://pure.au.dk/ws/files/285414161/3473567.pdf) | E21 claim 11(b); D.2 |
| `ICFP19-quantitative-program-reasoning-with-graded-modal-types.pdf` | Orchard, Liepelt, Eades III, *Quantitative Program Reasoning with Graded Modal Types*, ICFP 2019. [10.1145/3341714](https://doi.org/10.1145/3341714) | CC-BY 4.0 (in PDF) | [kent.ac.uk](https://www.cs.kent.ac.uk/people/staff/dao7/publ/granule-icfp19.pdf) | E21 claim 4; D.4 |
| `CONCUR20-session-types-with-arithmetic-refinements.pdf` | Das, Pfenning, *Session Types with Arithmetic Refinements*, CONCUR 2020. [10.4230/LIPIcs.CONCUR.2020.13](https://doi.org/10.4230/LIPIcs.CONCUR.2020.13) | CC-BY (LIPIcs) | [drops.dagstuhl.de](https://drops.dagstuhl.de/entities/document/10.4230/LIPIcs.CONCUR.2020.13) | E21 claim 8; D.2 |
| `COORDINATION14-affine-sessions.pdf` | Mostrous, Vasconcelos, *Affine Sessions*, COORDINATION/DisCoTec 2014. [10.1007/978-3-662-43376-8_8](https://doi.org/10.1007/978-3-662-43376-8_8) | CC-BY 4.0 (HAL deposit, [hal-01290071](https://inria.hal.science/hal-01290071)) | HAL copy, retrieved via `web.archive.org` (HAL itself serves an anti-bot interstitial) | E21 claim 3 — **was marked ✗ unobtainable**; D.3 |
| `dependent-multiplicities-in-dependent-linear-type-theory.pdf` | Doré, *Dependent Multiplicities in Dependent Linear Type Theory*, 2026. | CC-BY 4.0 (in PDF) | [arXiv:2507.08759](https://arxiv.org/abs/2507.08759) | E21 claim 4′; row 32; §4.6; D.4 |
| `grounded-conceptual-model-for-ownership-types-in-rust.pdf` | *A Grounded Conceptual Model for Ownership Types in Rust*, 2023. | CC-BY 4.0 (arXiv posting) | [arXiv:2309.04134](https://arxiv.org/abs/2309.04134) | E21 claim 11(d); §4.6; D.6 |

**Versions.** Every file above is the **version of record** except
`COORDINATION14-affine-sessions.pdf`, which is the author deposit, and
`dependent-multiplicities-…`/`grounded-conceptual-model-…`, which are the arXiv
postings that carry the CC-BY grant. Where a paper was fetched from an author or
institutional page rather than the publisher, that copy was checked to carry the
published article number and pagination.

**On the LIPIcs copy.** Das & Pfenning is committed in the **LIPIcs** version,
not arXiv: the arXiv posting (2005.05970) carries only arXiv's non-exclusive
distribution licence. E21's quotations were taken from the arXiv copy and
re-checked against this one.

## Read or cited, and deliberately **not** here

Checked against Crossref and Unpaywall. None has a redistribution grant.

| paper | status |
| --- | --- |
| Tov & Pucella, *Practical Affine Types* (Alms), POPL 2011 | Crossref: ACM copyright policy. Not OA. Read from the author's copy |
| DeLine & Fähndrich, *Enforcing High-Level Protocols in Low-Level Software*, PLDI 2001 | Crossref: ACM copyright policy. Not OA. Read from the MSR-hosted copy |
| Klein et al., *seL4*, SOSP 2009 | Crossref: ACM copyright policy. Not OA |
| Bae et al., *Rudra*, SOSP 2021 | Crossref: ACM copyright policy — **not** CC-BY, unlike the PACMPL papers above |
| Chang, Knauth, Greenman, *Type Systems as Macros*, POPL 2017 | Crossref: ACM copyright policy. Not OA |
| Vasconcelos, *Fundamentals of Session Types*, I&C 2012 (and the SFM 2009 notes) | Unpaywall: OA at Elsevier with **no licence**. Both copies read via `web.archive.org` |
| Ghica & Smith, *Bounded Linear Types in a Resource Semiring*, ESOP 2014 | the Birmingham repository copy states **"License: None: All rights reserved"** outright |
| Parkinson & Bierman, *Separation logic and abstraction*, POPL 2005 | **not open access at all** (Unpaywall: `is_oa=false`); no author or institutional copy located. Still ✗ in Appendix D and still unread |

Parkinson & Bierman is now the **only** paper E21 marked ✗ that remains
unobtained; Mostrous & Vasconcelos has been recovered and is above.
