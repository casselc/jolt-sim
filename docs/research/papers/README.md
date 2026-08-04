# Papers redistributed here, and why each one may be

**The rule applied.** A paper is committed here **only if the PDF itself, or its
canonical hosting page, carries an explicit licence granting redistribution** —
in every case below, Creative Commons Attribution 4.0 (CC-BY 4.0), except Das &
Pfenning which is LIPIcs's CC-BY. Nothing is included on the strength of "it was
freely downloadable", "it is open access", or ACM's
`© Copyright held by the owner/author(s)` line, which is a copyright statement
and **not** a licence to redistribute.

Each file is the **verbatim published PDF** from the source URL given. None has
been modified, and the attribution required by CC-BY is the citation in the
table.

## Contents

| file | citation | licence | source | used by |
| --- | --- | --- | --- | --- |
| `PLDI22-flexible-type-system-for-fearless-concurrency.pdf` | Mae Milano, Julia Turcotti, Andrew C. Myers, *A Flexible Type System for Fearless Concurrency*, PLDI 2022. [10.1145/3519939.3523443](https://doi.org/10.1145/3519939.3523443) | CC-BY 4.0 (stated in PDF) | supplied | E25; Appendix D.8 |
| `flo-semantic-foundation-progressive-stream-processing.pdf` | Shadaj Laddad, Alvin Cheung, Joseph M. Hellerstein, Mae Milano, *Flo: A Semantic Foundation for Progressive Stream Processing*, POPL 2025. [10.1145/3704845](https://doi.org/10.1145/3704845) | CC-BY 4.0 (stated in PDF) | supplied | E25; Appendix D.8 |
| `POPL24-soundly-handling-linearity.pdf` | Wenhao Tang, Daniel Hillerström, Sam Lindley, J. Garrett Morris, *Soundly Handling Linearity*, POPL 2024. [10.1145/3632896](https://doi.org/10.1145/3632896) | CC-BY 4.0 (arXiv posting) | [arXiv:2307.09383](https://arxiv.org/abs/2307.09383) | E21 claim 2; tally row 30; D.3 |
| `PLDI23-flux-liquid-types-for-rust.pdf` | Nico Lehmann, Adam T. Geller, Niki Vazou, Ranjit Jhala, *Flux: Liquid Types for Rust*, PLDI 2023. [10.1145/3591283](https://doi.org/10.1145/3591283) | CC-BY 4.0 (stated in PDF) | [ranjitjhala.github.io](https://ranjitjhala.github.io/static/flux-pldi23.pdf) | E21 claim 10; §4.6; D.4 |
| `ICFP19-quantitative-program-reasoning-with-graded-modal-types.pdf` | Dominic Orchard, Vilem-Benjamin Liepelt, Harley Eades III, *Quantitative Program Reasoning with Graded Modal Types*, ICFP 2019. [10.1145/3341714](https://doi.org/10.1145/3341714) | CC-BY 4.0 (stated in PDF) | [kent.ac.uk](https://www.cs.kent.ac.uk/people/staff/dao7/publ/granule-icfp19.pdf) | E21 claim 4; D.4 |
| `CONCUR20-session-types-with-arithmetic-refinements.pdf` | Ankush Das, Frank Pfenning, *Session Types with Arithmetic Refinements*, CONCUR 2020. [10.4230/LIPIcs.CONCUR.2020.13](https://doi.org/10.4230/LIPIcs.CONCUR.2020.13) | CC-BY (LIPIcs) | [drops.dagstuhl.de](https://drops.dagstuhl.de/entities/document/10.4230/LIPIcs.CONCUR.2020.13) | E21 claim 8; D.2 |
| `dependent-multiplicities-in-dependent-linear-type-theory.pdf` | Maximilian Doré, *Dependent Multiplicities in Dependent Linear Type Theory*, 2026. | CC-BY 4.0 (stated in PDF) | [arXiv:2507.08759](https://arxiv.org/abs/2507.08759) | E21 claim 4′; tally row 32; §4.6 join item; D.4 |
| `grounded-conceptual-model-for-ownership-types-in-rust.pdf` | *A Grounded Conceptual Model for Ownership Types in Rust*, 2023. | CC-BY 4.0 (arXiv posting) | [arXiv:2309.04134](https://arxiv.org/abs/2309.04134) | E21 claim 11(d); §4.6 join item; D.6 |

**Note on the LIPIcs copy.** Das & Pfenning is committed in the **LIPIcs**
version, not the arXiv one. The arXiv posting (2005.05970) carries only arXiv's
non-exclusive distribution licence, which does not permit redistribution here;
the published LIPIcs version is CC-BY. E21's quotations were taken from the arXiv
version and re-checked against this one.

## Papers read for E21/E25 that are deliberately **not** here

Read in full text, quoted in the findings, and **not** redistributable on the
evidence available. Appendix D gives the URL for each; fetch them yourself.

| paper | why not |
| --- | --- |
| Fowler, Lindley, Morris, Decova, *Exceptional Asynchronous Session Types*, POPL 2019 | PDF carries `© Copyright held by the owner/author(s)` and no CC grant |
| Jung, Jourdan, Krebbers, Dreyer, *RustBelt*, POPL 2018 | same |
| Astrauskas et al., *How Do Programmers Use Unsafe Rust?*, OOPSLA 2020 | same |
| Knoth, Wang, Reynolds, Polikarpova, Hoffmann, *Liquid Resource Types*, ICFP 2020 | same; arXiv posting is non-exclusive-distrib |
| Qian, Kavvos, Birkedal, *Client-Server Sessions in Linear Logic*, ICFP 2021 | same; arXiv posting is non-exclusive-distrib |
| Tov & Pucella, *Practical Affine Types* (Alms), POPL 2011 | ACM copyright, author-hosted copy carries no licence |
| DeLine & Fähndrich, *Enforcing High-Level Protocols in Low-Level Software*, PLDI 2001 | ACM copyright; Microsoft Research hosting grants no redistribution |
| Klein et al., *seL4*, SOSP 2009 | ACM copyright |
| Bae et al., *Rudra*, SOSP 2021 | no licence statement located on the author-hosted PDF |
| Chang, Knauth, Greenman, *Type Systems as Macros*, POPL 2017 | ACM copyright |
| Vasconcelos, *Fundamentals of Session Types* (SFM 2009 notes and I&C 2012) | author/Elsevier copyright; both copies retrieved via `web.archive.org` |
| Ghica & Smith, *Bounded Linear Types in a Resource Semiring*, ESOP 2014 | the Birmingham repository copy states **"License: None: All rights reserved"** |

Two papers in Appendix D could not be obtained at all and are marked ✗ there:
Parkinson & Bierman (POPL 2005) and Mostrous & Vasconcelos (2014).
