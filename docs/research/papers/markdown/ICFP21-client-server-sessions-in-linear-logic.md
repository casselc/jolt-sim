# Client-Server Sessions in Linear Logic

> **Machine-generated Markdown conversion — this is a MODIFIED version.**
> Converted from `ICFP21-client-server-sessions-in-linear-logic.pdf` with PyMuPDF. Layout, mathematics,
> figures and tables are lossy; **quote from the PDF, not from this file.**
> Page anchors below correspond to PDF pages.

- **Citation:** Zesen Qian, G. A. Kavvos, Lars Birkedal. ICFP 2021. doi:10.1145/3473567
- **Licence:** CC-BY 4.0 (Crossref; also in PDF)
- **Source:** https://pure.au.dk/ws/files/285414161/3473567.pdf

---


<!-- page 1 -->

## Client-Server Sessions in Linear Logic

ZESEN QIAN, Aarhus University, Denmark G. A. KAVVOS, University of Bristol, United Kingdom LARS BIRKEDAL, Aarhus University, Denmark

We introduce coexponentials, a new set of modalities for Classical Linear Logic. As duals to exponentials, the coexponentials codify a distributed form of the structural rules of weakening and contraction. This makes them a suitable logical device for encapsulating the pattern of a server receiving requests from an arbitrary number of clients on a single channel. Guided by this intuition we formulate a system of session types based on Classical Linear Logic with coexponentials, which is suited to modelling client-server interactions. We also present a session-typed functional programming language for client-server programming, which we translate to our system of coexponentials.

CCS Concepts: · Theory of computation →Linear logic; · Computing methodologies →Concurrent programming languages; Parallel programming languages.

Additional Key Words and Phrases: session types, linear logic, propositions as sessions, Curry-Howard, 𝜋-calculus, coexponential modality, client-server architecture

ACM Reference Format: Zesen Qian, G. A. Kavvos, and Lars Birkedal. 2021. Client-Server Sessions in Linear Logic. Proc. ACM Program. Lang. 5, ICFP, Article 62 (August 2021), 31 pages. https://doi.org/10.1145/3473567

## 1 INTRODUCTION

The programme of session types [Honda et al. 1998; Vasconcelos 2012] aims to formulate behavioural type systems that capture the notion of a sessionÐa structured, concurrent interaction between communicating agents. Very little is usually assumed about these agents: their only shared resource is usually a set of channels through which they can send and receive messages. On the other hand, ever since its inception it has been clear that linear logic [Girard 1987] has a deep and mystifying relationship with concurrency. Abramsky [1994] argued that 𝜋-calculi [Milner et al. 1992] and linear logic should be in a Curry-Howard correspondence [Bellin and Scott 1994]. Consequently, one should be able to use formulas of linear logic as types that specify concurrent interactions, thereby constructing a system of session types that is logically motivated. Session types and linear types have recently undergone a swift rapprochement beginning with the work of Caires and Pfenning [Caires and Pfenning 2010; Caires et al. 2016].

Despite these advances, the 𝜋-calculi that have been developed for Linear Logic suffer from dire expressive poverty. The typable processes are free of deadlock and nondeterminism, at the price of being unable to model even benign forms of race. One striking omission is that it is difficult to write down a well-typed process that represents two distinct clients being served by a server listening on a single channel. The goal of the present paper is to introduce a logical device, namely the strong

Authors' addresses: Zesen Qian, Aarhus University, Denmark, zesen.qian@cs.au.dk; G. A. Kavvos, University of Bristol, United Kingdom, alex.kavvos@bristol.ac.uk; Lars Birkedal, Aarhus University, Denmark, birkedal@cs.au.dk.

This work is licensed under a Creative Commons Attribution 4.0 International License.

© 2021 Copyright held by the owner/author(s).

2475-1421/2021/8-ART62 https://doi.org/10.1145/3473567

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.


<!-- page 2 -->

⊗ output O input N offer a choice ⊕ make a choice ! server ? client We follow a convention by which the multiplicative connectives ⊗, O associate to the right. Thus a type like 𝐴⊗𝐵O 𝐶is 𝐴⊗(𝐵O 𝐶) and can be read as: output a (channel of type) 𝐴, then input a (channel of type) 𝐵, and proceed as 𝐶.

(i) There is a server process, which repeatedly provides a service. (ii) There is a pool of client processes, each of which requests the said service. (iii) There is a unique end point at which the clients may issue their requests to the server.

nondeterministic manner. While Wadler's interpretation faithfully captures (i) and (iii), it does not immediately enable the representation of (ii). Because of its deterministic behaviour, CP is incapable of modelling (iv).

𝑄⊢Γ

𝑄⊢Γ,𝑦: 𝐴

?𝑤

𝑄⊢Γ,𝑥: ?𝐴

?𝑥[𝑦].𝑄⊢Γ,𝑥: ?𝐴

Wadler interprets these rules as client formation. Weakening stands for the empty case of a pool of no clients. Dereliction represents a single client following session 𝐴. Given that 𝑄[𝑥/𝑦] denotes the term obtained by renaming all free occurrences of 𝑦in 𝑄to 𝑥, contraction enables the aggregation of two client pools: two sessions of type ?𝐴can be collapsed into one.

We argue that, of those interpretations, only the one for dereliction is tenable. In the case of weakening, we see that at least one process is involved in the premise. Hence, the 'pool' formed has at least one client in it, albeit one that does not communicate with the server. Likewise, contraction does not combine different clients, but different sessions owned by the same client. Beginning with a single process 𝑃⊢𝑥: 𝐴,𝑦: 𝐴we can use dereliction twice followed by contraction to obtain ?𝑤[𝑥]. ?𝑤[𝑦]. 𝑃⊢𝑤: ?𝐴. This process will ask for two channels that communicate with session

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.

62:2 Zesen Qian, G. A. Kavvos, and Lars Birkedal

coexponential modalities, that will allow us to give a linear type to this extremely common pattern of concurrent interaction.

### 1.1 The Problem

Caires and Pfenning [2010] proposed a Curry-Howard correspondence in which Intuitionistic Linear Logic is used as a type system for the 𝜋-calculus [Milner et al. 1992]. This correspondence allows one to interpret formulas of linear logic as session types, i.e., as specifications of disciplined communication over a named channel. A few years later Wadler [2014] extended this interpretation to Classical Linear Logic (CLL). Wadler's system, which is called Classical Processes (CP), perfectly corresponds to Girard's original one-sided sequent system for CLL [1987]. Its typing judgments are of the form 𝑃⊢Γ, where 𝑃is a 𝜋-calculus process, and Γ is a list 𝑥1 : 𝐴1, . . . ,𝑥𝑛: 𝐴𝑛of name-session type pairs, with 𝐴𝑖a formula of Classical Linear Logic. The operational semantics of CP led Wadler to the following interpretation of the connectives.

While the interpretation of the first four connectives is intuitive, something seems to have gone awry with the exponentials [Wadler 2014, ğ3.4]. We claim that the computational behaviour of exponentials in CP does not in fact accommodate what we would think of as client-server interaction. To begin, we consider the following aspects to be the main characteristics of a clientserver architecture [van Steen and Tanenbaum 2017, ğğ2.3, 3.4]:

(iv) The underlying network is inherently unreliable: clients may be served out-of-order, i.e., in a

A CP term 𝑆⊢𝑥: !𝐴can indeed 'serve' sessions of type 𝐴over the channel 𝑥. However, the reading of a term 𝐶⊢𝑦: ?𝐴as a process which behaves as a pool of clients along channel 𝑦is not so crisp. Recall the three rules of ?, namely weakening, dereliction, and contraction. In CP:

𝑄⊢Γ,𝑥: ?𝐴,𝑦: ?𝐴

?𝑑

?𝑐

𝑄[𝑥/𝑦] ⊢Γ,𝑥: ?𝐴


<!-- page 3 -->

𝐴. Nevertheless, the result is still a single process, and not a pool of clients. Dually, the type !𝐴 merely connotes a shared channel: a non-linearized, non-session channel which is used to spawn an arbitrary number of new sessions, each one of type 𝐴[Caires and Pfenning 2010, ğ3].

Mix

𝑃⊢Γ 𝑄⊢Δ

𝑃| 𝑄⊢Γ, Δ

𝑃⊢𝑧: 𝐴

?𝑑

?𝑥[𝑧]. 𝑃⊢𝑥: ?𝐴

?𝑥[𝑧]. 𝑃| ?𝑦[𝑤].𝑄⊢𝑥: ?𝐴,𝑦: ?𝐴

?𝑥[𝑧]. 𝑃| ?𝑥[𝑤].𝑄⊢𝑥: ?𝐴

The operational semantics of the Mix rule in CP are studied by Atkey et al. [2016]. To formulate them correctly one needs also to add the rule

Mix0

stop ⊢·

stop ⊢·

stop ⊢𝑥: ?𝐴

where 𝐶⊸𝐷

Client-Server Sessions in Linear Logic 62:3

More alarmingly, there is no way to combine two distinct processes 𝑃⊢𝑧: 𝐴and 𝑄⊢𝑤: 𝐴into a single process pool(𝑥;𝑧. 𝑃,𝑤.𝑄) ⊢𝑥: ?𝐴communicating along a shared channel. As a remedy, Wadler introduces the Mix rule:

Mix was carefully considered for inclusion in Linear Logic, but was rejected [Girard 1987, ğV.4]. Informally, it allows two completely independent, non-intercommunicating processes to run 'in parallel.' We may then use contraction to merge them into a single client pool:

𝑄⊢𝑤: 𝐴

?𝑑

?𝑦[𝑤]. 𝑃⊢𝑦: ?𝐴

Mix

?𝑐

Mix0 has a flavour of inconsistency to it, but it is otherwise useful. On the technical level, it lets us show that the operational semantics, which adds a reaction 𝑃| 𝑄−→𝑃′ | 𝑄whenever 𝑃−→𝑃′, is well-behaved (terminating, deadlock-free, and deterministic). In terms of computational interpretation, Mix0 represents a stopped process. This solves the second problem we pointed out above, viz. the formation of a vacuously empty client pool:

Mix0

?𝑤

Nevertheless, Mix and Mix0 are unbecoming rules. To begin, they are respectively equivalent to ⊥⊸1 and 1 ⊸⊥, and thereby conflate the two units. Moreover, it is well-known [Abramsky et al. 1996; Atkey et al. 2016; Bellin 1997; Girard 1987; Wadler 2014] that Mix is equivalent to

𝐴⊗𝐵⊸𝐴O 𝐵 (∗)

def= 𝐶⊥O 𝐷. Admitting this implication is unwise. At first glance, (∗) merely weakens the separation between these connectives, and hence damages the interpretation of O as input, and ⊗as output. However, we argue that deeper problems lurk just beneath the surface. Abramsky et al. [1996, ğ3.4.2] describe a perspective on CLL which reads 𝐴O 𝐵as connected concurrency (information necessarily flows between 𝐴and 𝐵[Girard 1987, ğV.4]) and 𝐴⊗𝐵as disjoint concurrency (no information flow between 𝐴and 𝐵whatsoever). The implication (∗) makes ⊗a special case of O. Hence, flow between the

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.


<!-- page 4 -->

62:4 Zesen Qian, G. A. Kavvos, and Lars Birkedal

components of 𝐴⊗𝐵is permitted, but not obligatory [Abramsky and Jagadeesan 1994, ğ3.2]. Thus, (∗) allows us to pretend that there is flow of information between two clients.1

Nevertheless, generating the actual flow of information is seemingly impossible. Using Mix we can put together two clients 𝐶𝑖⊢𝑐𝑖: 𝐴, and get a single process 𝐶0 | 𝐶1 ⊢𝑐0 : 𝐴,𝑐1 : 𝐴. As the comma stands for O, we can only cut this with a server 𝑆⊢𝑠: 𝐴⊥⊗𝐴⊥. But, by the interpretation of ⊗as disjoint concurrency, we see that the two client sessions will be served by disjoint server components. In other words, the server will not allow information to flow between clients, which does not conform to our usual conception of a stateful server! To enable this kind of flow, a server must use O. As we cannot cut a O (in the server) with another O (in the client pool), we are compelled to also accept the converse implication 𝐴O 𝐵⊸𝐴⊗𝐵in order to convert one of the two O's to ⊗. This forces ⊗= O, which inescapably leads to deadlock [Atkey et al. 2016, ğ4.2].

Requiring ⊗= O, a.k.a. compact closure [Abramsky et al. 1996; Barr 1991], is often deemed necessary for concurrency. In fact, Atkey et al. [2016] argue that this conflation of dual connectives (1 = ⊥, ⊗= O, and so on) is the source of all concurrency in Linear Logic. The objective of this paper is to argue that there is another way: we aim to augment the Caires-Pfenning interpretation of propositions-as-sessions with a certain degree of concurrency without adding Mix. We also wish to introduce just enough nondeterminism to convincingly model client-server interactions in a style that satisfies points (i)ś(iv).

We shall achieve both of these goals with the introduction of coexponentials.2

### 1.2 Roadmap

First, in ğ2 we discuss the expression of the usual exponential modalities of linear logic (!?) as least and greatest fixed points. This leads us to a different definition of !, which we call the strong exponential. By taking a 'multiplicative dual' of these fixed point expressions, we reach two novel modalities, the strong coexponentials, for which we write ¡ and £. We refine coexponentials back into a weak form that is similar to the usual exponentials, and show that they coincide with weak exponentials in the presence of Mix and the Binary Cut rule.

Following that, in ğ3 we introduce a process calculus with strong coexponentials, which we call CSLL (Client-Server Linear Logic). This new system is in the style of Kokke et al. [2019a], which replaces the one-sided sequents with hypersequents. It is argued that coexponentials enable the collection of an arbitrary number of clients following session 𝐴into a client pool, which communicates on a channel that follows session £𝐴. Conversely, the rules for ¡ express the formation of a server, which can be cut with a client pool to serve its requests.

In ğ4 we present an extended example that illustrates the computational behaviour of coexponentials, namely an implementation of the Compare-and-Set (CAS) synchronization primitive. Our system neatly encapsulates the racy yet atomic behaviour implicit in such operations.

In ğ5 we explore the implications of coexponentials in a session-typed functional language. We extend Wadler's GV with constructs for client-server interaction, and translate them to coexponentials in CSLL. We take advantage of the higher-level notation to give several examples that would be tedious to program directly in CSLL.

We survey related work in ğ6, and make some concluding remarks in ğ7.

1This is evident in the Abramsky-Jagadeesan game semantics for MLL+MIX: a play in 𝐴⊗𝐵projects to plays for 𝐴and 𝐵, but the Opponent can switch components at will. The fully complete model consists of history-free strategies, so there can only be non-stateful Opponent-mediated flow of information between 𝐴and 𝐵. 2The word 'coexponential' was used in Lafont and Streicher [1991, ğ6.4] to refer the ? connective.

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.


<!-- page 5 -->

## 2 EXPONENTIALS, FIXED POINTS, AND COEXPONENTIALS

### 2.1 Exponentials as Fixed Points

The exponential ('of course') modality of linear logic ! is used to mark a replicable formula. While describing a combinatory presentation of linear logic, Girard and Lafont [1987, ğ3.2] noticed that !𝐴can potentially be expressed as the fixed point

!𝐴 1 N 𝐴N (!𝐴⊗!𝐴)

The three additive conjuncts on the RHS correspond to the three rules of the dual connective ?, namely weakening, dereliction, and contraction. As N is a negative connective, the choice of conjunct rests on the 'user' of the formula,3 who may pick one of the three conjuncts at will.

def= 1 N 𝐴N (X ⊗X) 𝐺𝐴(X)

𝐹𝐴(X)

one defines

def= 𝜈𝐹𝐴 ?𝐴

!𝐴

StrongExp

⊢Γ, 𝐵 ⊢𝐵⊥, 1 ⊢𝐵⊥,𝐴 ⊢𝐵⊥, 𝐵⊗𝐵

⊢Γ, !𝐴

As foreshadowed by the use of a greatest fixed point, this rule is coinductive. To prove !𝐴from context Γ one must use it to construct a 'seed' value (or 'invariant') of type 𝐵. Moreover, this value must be discardable (⊢𝐵⊥, 1), derelictable (⊢𝐵⊥,𝐴), and copyable (⊢𝐵⊥, 𝐵⊗𝐵). This is eerily reminiscent of the free commutative comonoids used to build certain categorical models of Linear Logic [Melliès 2009, ğ7.2]. Because of the arbitrary choice of 'seed' type 𝐵, the system using this rule does not produce good behaviour under cut elimination: the normal forms do not satisfy the subformula property [Baelde 2012, ğ3]: not all detours are eliminated. We call the modality introduced by StrongExp the strong exponential.

## def= N

!𝐴

𝑛∈N

3Also known as external choice. In the language of game semantics, the opponent.

Client-Server Sessions in Linear Logic 62:5

One may thus be led to believe that, were we to allow fixed points for all functors, we could obtain !𝐴as the fixed point of a functor. Baelde [2012, ğ2.3] discusses this in the context of a system of higher-order CLL with least and greatest fixed points. Using the functors

def= ⊥⊕𝐴⊕(X O X)

def= 𝜇𝐺𝐴 where 𝜇and 𝜈stand for the least and greatest fixed point respectively. Just by expanding the fixed point rules, one then obtains certain derivable rules. While those for ? are the usual onesÐweakening, dereliction, and contractionÐthe rule for ! is radically different:

Baelde shows that the standard ! rule can be derived from StrongExp. But while the strong exponential can simulate the standard exponential, it also enables a host of other computational behaviours under cut elimination. Put simply, the standard exponential ensures uniformity: each dereliction of !𝐴into an 𝐴must be reduced to the very same proof of 𝐴every time. This makes sense in at least two ways. First, when we embed intuitionistic logic into linear logic through the Girard translation, we expect that in a proof of (𝐴→𝐵)𝑜def= !𝐴𝑜⊸𝐵𝑜each use of the antecedent !𝐴produces the same proof of 𝐴. Second, we know that one way to construct the exponential in many 'degenerate' models of linear logic [Barr 1991; Melliès et al. 2018] is through the formula

𝐴⊗𝑛/∼𝑛

where 𝐴⊗𝑛def= 𝐴⊗· · · ⊗𝐴, and 𝐴⊗𝑛/∼𝑛stands for the equalizer of 𝐴⊗𝑛under its 𝑛! symmetries. Decoding the categorical language, this means that we take one N component for each multiplicity 𝑛, and each component consists of exactly 𝑛copies of the same proof of 𝐴.

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.


<!-- page 6 -->

### 2.2 Deriving Coexponentials

def= ⊥N 𝐴N (X O X) 𝐾𝐴(X)

𝐻𝐴(X)

The strong coexponentials are then defined by

def= 𝜈𝐻𝐴 £𝐴

¡𝐴

We define (£𝐴)⊥def= ¡𝐴⊥, and vice versa. This gives the following derived rules.

⊢Γ,𝐴

£𝑤

£𝑑

⊢Γ, £𝐴

⊢£𝐴

⊢Γ, 𝐵 ⊢𝐵⊥, ⊥ ⊢𝐵⊥,𝐴 ⊢𝐵⊥, 𝐵O 𝐵

⊢Γ, ¡𝐴

Ì

⊢

Ì

⊢

£Γ stands for the context obtained by applying £ to every formula in Γ, and Ë folds this context with a tensor. Unfortunately, the presence of this folding operation means that this rule is not well-behaved in proof-theoretic terms.

The requisite rules are Mix, and one of the binary cut or multicut rules:

BiCut

⊢Γ,𝐴, 𝐵 ⊢Δ,𝐴⊥, 𝐵⊥

⊢Γ, Δ

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.

62:6 Zesen Qian, G. A. Kavvos, and Lars Birkedal

In contrast, the ! rules derived from their fixed point presentation merely create an infinite tree of occurrences of 𝐴, and not all of them need be proven in the same way.

Both exponentials (qua fixed points) are given by a tree where each fork is marked with a connective (⊗for !, O for ?). The leaves of the tree are either marked with 𝐴, or with the corresponding unit. Turning this process on its head leads to two dual modalities, which we call the coexponentials.

More concretely, we define two functors by dualising the connective that adorns forks. We must not forget to change the units accordingly: we swap 1 (the unit for ⊗) with ⊥(the unit for O). Let

def= 1 ⊕𝐴⊕(X ⊗X)

def= 𝜇𝐾𝐴

⊢Γ, £𝐴 ⊢Δ, £𝐴

£𝑐

⊢Γ, Δ, £𝐴

¡

The rules for £ are distributed forms of the structural rules, while the ¡ rule gives a strong coexponential, analogous to the strong version of ! described in the previous section. The corresponding 'weak' coexponential is given by replacing the above ¡ rule with

£Γ,𝐴

¡

£Γ, ¡𝐴

### 2.3 Exponentials vs. Coexponentials under Mix and Binary Cuts

In fact, we can show that, in the presence of additional rules, (weak) exponentials and (weak) coexponentials are interderivable up to provability. This is not merely a theoretical result: it demonstrates that, under the bonnet, Wadler's use of Mix for the formation of a client pool (which we sketched in ğ1.1) secretly introduces the coexponential modalities proposed here.

MultiCut

⊢Γ,𝐴1, . . . ,𝐴𝑛 ⊢Δ,𝐴1⊥, . . . ,𝐴𝑛⊥

⊢Γ, Δ

BiCut cuts two formulas at once, and MultiCut an arbitrary number. These rules were first proposed in the context of Linear Logic by Abramsky [1993b] in the compact setting (⊗= O). They are logically equivalent, but only the second one satisfies cut elimination [Atkey et al. 2016, ğ4.2]. We recall some folklore facts regarding the interderivability of certain formulas and Mix-like inference rules. Recall that 𝐶⊸𝐷

def= 𝐶⊥O 𝐷. Some form of the following lemma may be found across the relevant literature [Abramsky et al. 1996; Atkey et al. 2016; Bellin 1997; Girard 1987; Wadler 2014].


<!-- page 7 -->

Lemma 2.1. The following rules are logically interderivable.

(i) The axiom 1 ⊸⊥and the Mix0 rule. (ii) The axiom ⊥⊸1 and the Mix rule. (iii) The axiom 𝐴⊗𝐵⊸𝐴O 𝐵and the Mix rule.

(iv) The axiom 𝐴O 𝐵⊸𝐴⊗𝐵and the BiCut rule.

(v) BiCut and MultiCut.

Moreover, Mix0 is derivable from the axiom rule ⊢𝐴⊥,𝐴and BiCut.

Armed with this, we can prove that:

## 3 PROCESSES

3.1 £ Means Client, ¡ Means Server Recall the three rules for £, namely

⊢Γ,𝐴

£𝑤

£𝑑

⊢Γ, £𝐴

⊢£𝐴

We can read £𝐴as the session type of a channel shared by a pool of clients.

• £𝑤allows the vacuous formation of a empty client pool. • £𝑑allows the formation of a client pool consisting of exactly one client. • £𝑐can be used to aggregate two client pools together.

Finally, the 'weak' ¡ rule, i.e.,

Ì

⊢

Ì

⊢

Client-Server Sessions in Linear Logic 62:7

Theorem 2.2. In CLL with Mix and BiCut, exponentials and coexponentials coincide up to provability. That is: if we replace ? and ! in the rules for the exponentials with £ and ¡ respectively, the resultant rule is provable using the coexponential rules, and vice versa.

This theorem confirms that exponentials and coexponentials are indeed symmetric with respect to multiplicativity. It also explains why exponentials can represent client-server interactions after introducing Mix [Kokke et al. 2019a; Wadler 2014]. Finally, the theorem extends to strong exponentials vs. strong coexponentials; the proof there is even simpler: under Mix and BiCut we have ⊗= O, so 𝐹𝐴, 𝐻𝐴and 𝐺𝐴, 𝐾𝐴are pairwise logically equivalent.

In the rest of the paper we will argue that the logical observations we made in ğ2 have a computational interpretation as client-server interaction. To this end we will introduce a process calculus for CLL equipped with a bespoke form of strong coexponentials. Our system shall introduce a certain amount of nondeterminism, yet it will remain Mix-free.

We first explain how the coexponentials capture the intuitive shape of client pool formation (ğ3.1). Following that, we briefly discuss three technical design decisions that pertain to the coexponentials used in our system (ğğ3.2ś3.4). Finally, we introduce the system in ğ3.5, and its metatheory in ğ3.6.

⊢Γ, £𝐴 ⊢Δ, £𝐴

£𝑐

⊢Γ, Δ, £𝐴

The last point requires some elaboration. Each premise of £𝑐can be seen as a client pool with an external interface (Γ and Δ respectively). The rule allows us to combine these into a single process. This new process still behaves as a client pool, but it also retains both external interfaces. In contrast, the ?𝑐rule only allowed us to collapse two shared channels that belonged to a single process. Moreover, it did not allow us to mix two external interfacesÐone had to use Mix for that.

£Γ,𝐴

£Γ, ¡𝐴

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.


<!-- page 8 -->

### 3.2 Design Decision #1: Server State and The Strong Rules

The first change with respect to the above is the switch to the strong rule, namely

⊢Γ, 𝐵 ⊢𝐵⊥, ⊥ ⊢𝐵⊥,𝐴 ⊢𝐵⊥, 𝐵O 𝐵

⊢Γ, ¡𝐴

We use this strong rule in order to avoid the uniformity property that was discussed in ğ2.1: the weak coexponential rule gives trivial servers providing identical 𝐴's to all clients. In contrast, this rule will allow a server to provide a different 𝐴each time it is called upon to do so.

### 3.3 Design Decision #2: Replacing Trees with Lists

The strong coexponential rule arose by taking the greatest fixed point of

𝐻𝐴(X)

As discussed in ğğ2.1 and 2.2, this rule represents a tree-like structure. Nothing stops us from replacing it with a list-like structure.4 We use the functors

def= ⊥N (𝐴O X) 𝐾′

𝐻′

𝐴(X)

and acquire the strong server rule derived from 𝐻′

⊢Γ, 𝐵 ⊢𝐵⊥, ⊥ ⊢𝐵⊥,𝐴O 𝐵

⊢Γ, ¡𝐴

Server

⊢Γ, 𝐵 ⊢𝐵⊥, Δ ⊢𝐵⊥,𝐴, 𝐵

⊢Γ, Δ, ¡𝐴

4It is worth noting that Girard considered list-like exponentials [1987, ğV.5(ii)], but rejected them as they were not able to reproduce contraction. This is not a requirement for modelling client-server interaction.

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.

62:8 Zesen Qian, G. A. Kavvos, and Lars Birkedal

can be read as the introduction rule for a dual server session type. It states that a process serving 𝐴, and all of whose other interactions have a client role (£) with respect to a set of non-interacting (⊗) services, can itself be 'co-promoted' to a server ¡𝐴.

Note that our intuitive explanations are almost identical to those of Wadler [2014]; the difference is that our rules have the right branching structure to support the underlying intuition.

This rule evokes the structure of a 'stateful' server serving 𝐴's, with external interface Γ. Within the server there exists an internal server protocol 𝐵. This comes with four ingredients: a process that provides a 𝐵, interacting along Γ (initialization); a way to silently consume 𝐵(finalization); a way to 'convert' a 𝐵to an 𝐴(serving a client); and a way to fork one 𝐵into two connected 𝐵's (forking two subservers).

def= ⊥N 𝐴N (X O X)

def= 1 ⊕(𝐴⊗X)

𝐴(X)

𝐴, viz.

The main benefit is that the resulting system more closely reflects the pattern of client-server interaction: clients form a queue rather than a tree, and servers no longer have to fork subprocesses. This rule also requires fewer ingredients: an initialization of the internal protocol, a finalization, and a component that spawns a session to serve one additional client.

To optimize this further, we make the O implicit, and replace ⊥with a general Δ in the finalization:


<!-- page 9 -->

This second rule can be immediately derived from the first one:

⊢Γ, 𝐵 ⊢𝐵⊥, Δ

⊢𝐵⊥, 𝐵

⊢Γ, Δ, 𝐵⊗𝐵⊥

⊢𝐵⊥O 𝐵, ⊥

⊢Γ, Δ, ¡𝐴

def= ⊥in Server gives back the preceding rule. Hence, these two rules are logically equivalent.

and 'finalized' into a Δ. The ⊥rule is invertible, so instantiating Δ

### 3.4 Design Decision #3: Nondeterminism through Permutation

⊢£𝐴

⊢Γ, £𝐴 ⊢Δ,𝐴

⊢Γ, Δ, £𝐴 ⊢Σ,𝐴

⊢Γ, Δ, Σ, £𝐴 ≡

This amounts to quotienting lists up to permutation. Thus, when a client pool interacts with a server, the cut elimination procedure may silently choose to serve any of the constituent clients.

Trees and nondeterminism. The careful reader might notice that the original, tree-like 'distributed contraction' rule £𝑐inherently supported a certain amount of nondeterminism: if we were to quotient derivations up to permutation of the premises of £𝑐, then the cut elimination procedure would have some choice of whether to serve the left or right subtree first. Switching to list-like functors forbids this move, and seemingly imposes a much stricter discipline.

Nevertheless, the tree structure is awkward and rigid in another way. For example, consider a client pool whose tree structure is informally [[𝑐0,𝑐1], [𝑐2,𝑐3]]. As nondetermistic choices are only made at each node, the clients cannot be served in any order. For example, if 𝑐0 is served first then 𝑐1 must be served nextÐas it is in the same subtree. From a conventional client-server perspective this is arguably not a sufficient amount of nondeterminism. In contrast, our formulation allows full permutations of the client pool.

### 3.5 Introducing CSLL

Based on the above considerations, we introduce the system CSLL of Client-Server Linear Logic.

Client-Server Sessions in Linear Logic 62:9

⊢𝐵⊥,𝐴, 𝐵 ⊢𝐵⊥, 𝐵

⊢𝐵⊥, 𝐵, 𝐵⊗𝐵⊥,𝐴

⊢𝐵⊥O 𝐵,𝐴O (𝐵⊗𝐵⊥)

There is a surreptitious twist here: the 'new' internal server protocol is not 𝐵, but 𝐵⊗𝐵⊥. This leads to internal back-and-forth communication in the server. Γ is consumed to produce a 𝐵. This is 'passed' to each process serving each client. Finally, it is reflected back to the initilization process,

Using list-shaped rules for ¡ forces us to revise the rules for £. To define a cut elimination procedure the rules must now match the dual functor 𝐾′

𝐴, and hence become

⊢Γ, £𝐴 ⊢Δ,𝐴

⊢Γ, Δ, £𝐴

The cut elimination procedure for these rules leads to a confluent dynamics. This is unsatisfactory from the perspective of client-server interaction: a proper model requires some nondeterminism in the order in which clients are served. There are many ways to introduce this kind of behaviour. We choose the simplest one: we identify derivations up to permutation of client formation in pools. That is, we quotient them under the least congruence ≡generated from

⊢Γ, £𝐴 ⊢Σ,𝐴

⊢Γ, Σ, £𝐴 ⊢Δ,𝐴

⊢Γ, Δ, Σ, £𝐴

Following recent presentation of CLL-based systems of session types [Kokke et al. 2019a], CSLL is structured around hyperenvironments. Thus the logical system underlying CSLL is not one-sided

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.


<!-- page 10 -->

62:10 Zesen Qian, G. A. Kavvos, and Lars Birkedal

sequent calculus like CP, but a hypersequent system [Avron 1991]. In this kind of presentation process constructors are more finely decoupled. For example, the original CP output/⊗constructor 𝑥[𝑦]. (𝑃| 𝑄) is a combination of a parallel composition with an output prefix. Hypersequent systems allow us to separately type these two constructs, and bring the language closer to 𝜋-calculus.

One-sided sequent systems for CLLÐsuch as Girard's original presentation [1987]Ðuse sequents of the form ⊢Γ where Γ is an environment, i.e., an unordered list of formulas. We assign distinct names to each formula. The environment Γ = 𝑥1 : 𝐴1, . . . ,𝑥𝑛: 𝐴𝑛stands for 𝐴1 O . . . O𝐴𝑛. Hence, a comma stands for O. Environments are identical up to permutation. We write · for the empty one.

A hyperenvironment adds another layer: it is an unordered list of environments. We separate environments by vertical lines. If each environment Γ𝑖stands for the formula 𝐴𝑖, the hyperenvironment G = Γ1 | · · · | Γ𝑛stands for the formula 𝐴1 ⊗· · · ⊗𝐴𝑛. Hence, | stands for ⊗. Hyperenvironments are identical up to permutation, and we write ∅for the empty one. We also stipulate that variable names be distinct within and across environments.

The syntax and the type system of CSLL are defined in Fig. 1. The types are the formulas of CLL. Note that the choice between curly braces, parantheses and brackets in the syntax of processes is merely typographical, and does not bear formal meaning. However, curly braces are meant to evoke parameters, whereas parentheses and brackets evoke bindings in continuations. A generic judgment of the type system has the shape 𝑃⊢G where 𝑃is a process, and G is a hyperenvironment.

Most typing rules are identical to HCP, and in the interest of brevity we only discuss the important ones. Hyperenvironment components are introduced by the nullary and binary hypermix rules, HMix0 and HMix2. These are 'Mix' rules only in name. HMix2 forms the disjoint parallel composition of two processes: their environments are joined with |, which stands for ⊗.5 HMix0 is the stopped process; its hyperenvironment is the empty one, which stands for the unit of ⊗, namely 1.6

The Cut and Tensor rules eliminate hyperenvironment components. The premises of Cut ensure that the two variables that are being connectedÐviz. 𝑥and 𝑦Ðare in different 'parallel components' of 𝑃. Notice that the external environments of these two components, namely Γ and Δ, are then brought together in the conclusion. A similar pattern permeates the Tensor and M-True rules. It is instructive to follow the derivation of the original CP rules for ⊗and 1, which we will silently use:

𝑃⊢Γ,𝑦: 𝐴 𝑄⊢Δ,𝑥: 𝐵

𝑃| 𝑄⊢Γ,𝑦: 𝐴| Δ,𝑥: 𝐵

stop ⊢∅

𝑥[𝑦]. (𝑃| 𝑄) ⊢Γ, Δ,𝑥: 𝐴⊗𝐵

𝑥[]. stop ⊢𝑥: 1

The exponential rules WhyNotW, WhyNotD, WhyNotC and OfCourse are formulated in the style of Kokke et al. [2019a]. In OfCourse we use vector notation (®−) as a shorthand for lists of names and types. Note thatÐin contrast to all previous systemsÐwe notate 𝑃as a parameter rather than as the continuation in the process !𝑥{®𝑦; 𝑃}. This because 𝑃does not behave like a continuation. For example, it has its own distinct commuting conversion.

The coexponential rules QueW, QueA and Claro follow the patterns described in ğğ3.1ś3.4. The rule QueW (W stands for 'weaken') constructs an empty client pool. The rule QueA (A stands for 'absorb') combines a client and a pool into a slightly larger pool. The interfaces of the client pool

and the client are necessarily disjoint, as they are separated by a | in the premise. All the processes in the resultant pool race to communicate with a server at the single endpoint 𝑥.

Correspondingly, Claro constructs a process that offers a service at the single endpoint 𝑦. Its continuation 𝑃functions as both the initialization and the finalization of the server, over channels 𝑖 and 𝑓respectively. This rule is similar to the Server rule of ğ3.3, but in the interest of brevity it

5Mix would join them with a comma, which would stand for a O. 6Mix0 would stand for the unit of O, namely ⊥.

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.


<!-- page 11 -->

𝐴, 𝐵, . . . F 1 | ⊥| 𝐴O 𝐵| 𝐴⊗𝐵| 𝐴⊕𝐵| 𝐴N 𝐵| £𝐴| ¡𝐴| ?𝐴| !𝐴

HMix2

HMix0

𝑃⊢G 𝑄⊢H

stop ⊢∅

𝑃| 𝑄⊢G | H

Par

Ax

𝑃⊢G | Γ,𝑥: 𝐴,𝑦: 𝐵

𝑦(𝑥). 𝑃⊢G | Γ,𝑦: 𝐴O 𝐵

𝑥↔𝑦⊢𝑥: 𝐴⊥,𝑦: 𝐴

PlusL

PlusR

𝑃⊢G | Γ,𝑥: 𝐴

𝑄⊢G | Γ,𝑦: 𝐵

𝑥[inl]. 𝑃⊢G | Γ,𝑥: 𝐴⊕𝐵

𝑦[inr].𝑄⊢G | Γ,𝑦: 𝐴⊕𝐵

M-False

M-True

𝑃⊢G | Γ

𝑃⊢G

𝑥(). 𝑃⊢G | Γ,𝑥: ⊥

𝑥[]. 𝑃⊢G | 𝑥: 1

OfCourse

WhyNotC

𝑃⊢G | Γ,𝑦0 : ?𝐴,𝑦1 : ?𝐴

?𝑥[𝑦0,𝑦1]. 𝑃⊢G | Γ,𝑥: ?𝐴

QueA

Claro

𝑃⊢G | Γ,𝑥: £𝐴| Δ,𝑥′ : 𝐴

£𝑥[𝑥′]. 𝑃⊢G | Γ, Δ,𝑥: £𝐴

Client-Server Sessions in Linear Logic 62:11

Γ, Δ, . . . F · | Γ,𝑥: 𝐴 (environments)

G, H, . . . F ∅| G | Γ (hyperenvironments)

𝑃,𝑄, . . . F stop (terminated process)

| 𝑥↔𝑦 (link between 𝑥and 𝑦)

| 𝜈𝑥𝑦. 𝑃 (connect 𝑥and 𝑦)

| 𝑃| 𝑄 (parallel composition)

| 𝑦.case{𝑃;𝑄} (receive choice over 𝑦)

| 𝑦[inl]. 𝑃| 𝑦[inr]. 𝑃 (send choice over 𝑦)

| 𝑦(𝑥). 𝑃| 𝑦[𝑥]. 𝑃 (receive/send 𝑥over 𝑦)

| 𝑦(). 𝑃| 𝑦[]. 𝑃 (receive/send end-of-session at 𝑦)

| £𝑥[]. 𝑃 (create new client interface 𝑥)

| £𝑥[𝑦]. 𝑃 (send client interface 𝑦over 𝑥)

| ¡𝑦{𝑧′,𝑤′,𝑦′.𝑄}(𝑧,𝑤). 𝑃 (serve over 𝑦)

| ?𝑥[]. 𝑃| ?𝑥[𝑦]. 𝑃| ?𝑥[𝑦0,𝑦1]. 𝑃 (weakening, dereliction and contraction)

| !𝑥{®𝑦; 𝑃} (promotion)

Cut

𝑃⊢G | Γ,𝑥: 𝐴| Δ,𝑦: 𝐴⊥

𝜈𝑥𝑦. 𝑃⊢G | Γ, Δ

Tensor

𝑃⊢G | Γ,𝑥: 𝐴| Δ,𝑦: 𝐵

𝑦[𝑥]. 𝑃⊢G | Γ, Δ,𝑦: 𝐴⊗𝐵

With

𝑃⊢Γ,𝑥: 𝐴 𝑄⊢Γ,𝑥: 𝐵

𝑥.case{𝑃;𝑄} ⊢Γ,𝑥: 𝐴N 𝐵

WhyNotW

WhyNotD

𝑃⊢G | Γ

𝑃⊢G | Γ,𝑦: 𝐴

?𝑥[]. 𝑃⊢G | Γ,𝑥: ?𝐴

?𝑥[𝑦]. 𝑃⊢G | Γ,𝑥: ?𝐴

QueW

𝑃⊢®𝑦: ?®𝐵,𝑥: 𝐴

𝑃⊢G

!𝑥{®𝑦; 𝑃} ⊢®𝑦: ?®𝐵,𝑥: !𝐴

£𝑥[]. 𝑃⊢G | 𝑥: £𝐴

𝑃⊢G | Γ,𝑖: 𝐵| Δ, 𝑓: 𝐵⊥ 𝑄⊢𝑧: 𝐵⊥,𝑧′ : 𝐵,𝑦′ : 𝐴

¡𝑦{𝑧,𝑧′,𝑦′.𝑄}(𝑖, 𝑓). 𝑃⊢G | Γ, Δ,𝑦: ¡𝐴

Fig. 1. The syntax and type system of CSLL.

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.


<!-- page 12 -->

62:12 Zesen Qian, G. A. Kavvos, and Lars Birkedal

combines the premises ⊢Γ, 𝐵and ⊢𝐵⊥, Δ into one process. However, these functionalities continue to be logically disjoint components of 𝑃, as their interfaces are separated by a | in the premise. The process 𝑄is a 'worker' process which is spawned every time a client is to be served.

In all process constructs that involve a dot that is not within curly braces, e.g. 𝑦(𝑥). 𝑃, we call the part that precedes it the prefix of the process (𝑦(𝑥) in this case), and the part that succeeds it the continuation (𝑃in this case).

The bound names Bn(𝑃) of a process 𝑃are defined as follows:

• 𝑥and 𝑦are bound in 𝑃within 𝜈𝑥𝑦. 𝑃. • 𝑥is bound in 𝑃within 𝑦(𝑥). 𝑃and 𝑦[𝑥]. 𝑃. • Within ¡𝑦{𝑧,𝑧′,𝑦′.𝑄}(𝑖, 𝑓). 𝑃we have that 𝑖and 𝑓are bound in 𝑃, while 𝑧, 𝑧′, and 𝑦′ are bound in 𝑄. Note that 𝑦is not bound, but rather 'exported.' • 𝑥is bound in 𝑃within £𝑦[𝑥]. 𝑃. • 𝑥0 and 𝑥1 are bound in 𝑃within ?𝑥[𝑥0,𝑥1]. 𝑃. • 𝑥is bound in 𝑃within ?𝑥′[𝑥]. 𝑃. In all other cases the set of bound names is empty. We define the free names Fn(𝑃) of a process 𝑃to be the set of sets corresponding to the names occurring in the typing judgment of 𝑃. For example, the hyperenvironment G

def= 𝑥: 𝐴,𝑦: 𝐵| 𝑧: 𝐶,𝑤: 𝐷determines the set of sets ⌊G⌋= 𝑥,𝑦| 𝑧,𝑤

def= {{𝑥,𝑦}, {𝑧,𝑤}}. Kokke et al. [2019a] call this the name partition corresponding to a hyperenvironment. Thus, if 𝑃⊢G we define Fn(𝑃)

def= ⌊G⌋. We will sometimes abusively write Fn(𝑃) to mean the union of the name partition, i.e. the complete set of free names that occur in it. As is usual, processes are identified up to 𝛼-equivalence.

We write 𝜋𝑦for an arbitrary prefix communicating on channel 𝑦, and Bn(𝜋𝑦) for the variables that it binds in its continuation. For example, 𝜋𝑦could be 𝑦(𝑥), and in this case Bn(𝜋𝑦) = {𝑥}.

Finally, notice that the typing cannot be inferred from the terms alone. For example, in M-False the term 𝑥(). 𝑃does not specify in which environment Γ within its hyperenvironment the unit ⊥ should be introduced. This has an impact on the name partition Fn(𝑃) of a process 𝑃.

### 3.6 Operational Semantics and Metatheory

Definition 3.1. Canonical terms are defined by the following clauses.

• 𝜋𝑥. 𝑃is canonical whenever 𝑃is. • 𝑃| 𝑄is canonical if both 𝑃and 𝑄are canonical. • stop and 𝑥↔𝑦are canonical. • 𝑦.case{𝑃;𝑄} and !𝑥{®𝑦; 𝑃} are canonical.

In particular, 𝜈𝑥𝑦. 𝑃is not canonical; it is a cut.

The above notion of canonicity is not definitive. For example, 𝜋𝑥. 𝑃could have been considered canonical regardless of the canonicity of 𝑃(similar to weak head normal form for 𝜆-calculus). However, we choose to react 𝑃further to make the 'final result' of an interaction visible in later examples. In addition, we could require terms such as 𝑃and 𝑄in 𝑦.case{𝑃;𝑄} be canonical for the whole term to be canonical, but we choose not to so as to reduce the number of reaction rules.

We define the notion of structural equivalence 𝑃≡𝑄to be the least congruence between processes induced by the clauses in Fig. 2. Furthermore, we define the reaction relation 𝑃−→𝑄between processes to be the least relation induced by the clauses in Fig. 3.

The structural equivalence and the reaction semantics largely mirror the notions of the same name in the 𝜋-calculus [Milner 1992, 1999]. Those that differ are justified via linear logic. Res-Pre and Pre-Pre can be seen as identifications arising from proof nets, in which the corresponding proofs would be graphically identical. Note that the commuting prefixes are requried to not 'cross'

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.


<!-- page 13 -->

ParL

Res

𝑃−→𝑃′

𝑃−→𝑃′

𝑃| 𝑄−→𝑃′ | 𝑄

𝜈𝑥𝑦. 𝑃−→𝜈𝑥𝑦. 𝑃′

𝜈𝑥𝑦. (£𝑥[𝑥′].𝐶| ¡𝑦{𝑧,𝑧′,𝑦′.𝑄}(𝑖, 𝑓). 𝑃) −→𝜈𝑥𝑦.𝜈𝑥′𝑦′. (𝐶| ¡𝑦{𝑧,𝑧′,𝑦′.𝑄}(𝑧′, 𝑓). (𝜈𝑖𝑧. (𝑃| 𝑄)))

𝜈𝑥𝑦. (?𝑥[𝑥0,𝑥1]. 𝑃| !𝑦{®𝑧; 𝑄}) −→?®𝑧[ ®𝑧0, ®𝑧1].𝜈𝑥0𝑦0.𝜈𝑥1𝑦1. (𝑃| 𝑅)

where 𝑅

Client-Server Sessions in Linear Logic 62:13

𝑃| stop ≡𝑃 (Par-Unit)

𝑃| 𝑄≡𝑄| 𝑃 (Par-Comm)

𝑃| (𝑄| 𝑅) ≡(𝑃| 𝑄) | 𝑅 (Par-Assoc)

𝑥↔𝑦≡𝑦↔𝑥 (Link-Comm)

𝜈𝑥𝑦. (𝑃| 𝑄) ≡𝑃| 𝜈𝑥𝑦.𝑄(𝑥,𝑦∉Fn(𝑃)) (Res-Par)

𝜈𝑥𝑦.𝜈𝑧𝑤. 𝑃≡𝜈𝑧𝑤.𝜈𝑥𝑦. 𝑃 (Res-Res)

𝜋𝑥. (𝑃| 𝑄) ≡𝑃| 𝜋𝑥.𝑄(Bn(𝜋𝑥) ∩Fn(𝑃) = ∅) (Pre-Par)

𝜈𝑥𝑦. 𝜋𝑧. 𝑃≡𝜋𝑧.𝜈𝑥𝑦. 𝑃(𝑧≠𝑥,𝑦and 𝜋𝑧and 𝜈𝑥𝑦. not cross Fn(𝑃)) (Res-Pre)

𝜋𝑥. 𝜋𝑦. 𝑃≡𝜋𝑦. 𝜋𝑥. 𝑃(𝑥≠𝑦,𝑦∉Bn(𝜋𝑥),𝑥∉Bn(𝜋𝑦), 𝜋𝑥and 𝜋𝑦not cross Fn(𝑃)) (Pre-Pre)

extended with

£𝑥[𝑥0]. £𝑥[𝑥1]. 𝑃≡£𝑥[𝑥1]. £𝑥[𝑥0]. 𝑃 (Que-Que)

Fig. 2. The structural equivalence of CSLL processes.

Eq

Pre

𝑃−→𝑃′

𝑃≡𝑃′ 𝑃−→𝑄 𝑄≡𝑄′

𝑃′ −→𝑄′

𝜋𝑦. 𝑃−→𝜋𝑦. 𝑃′

𝜈𝑥𝑦. (𝑧.case{𝑃0; 𝑃1} | 𝑄) −→𝑧.case{𝜈𝑥𝑦. (𝑃0 | 𝑄);𝜈𝑥𝑦. (𝑃1 | 𝑄)} (With-Comm)

𝜈𝑥𝑦. (!𝑧{𝑥®𝑤; 𝑃} | !𝑦{®𝑣; 𝑄}) −→!𝑧{®𝑣®𝑤; 𝜈𝑥𝑦. (𝑃| !𝑦{®𝑣; 𝑄})} (OfCourse-Comm)

𝜈𝑥𝑦. (𝑧↔𝑥| 𝑄) −→𝑄[𝑧/𝑦] (Link)

𝜈𝑥𝑦. (𝑥[]. 𝑃| 𝑦().𝑄) −→𝑃| 𝑄 (One-Bot)

𝜈𝑥𝑦. (𝑥[𝑧]. 𝑃| 𝑦(𝑤).𝑄) −→𝜈𝑥𝑦.𝜈𝑧𝑤. (𝑃| 𝑄) (Tensor-Par)

𝜈𝑥𝑦. (𝑥[inl]. 𝑃| 𝑦.case{𝑄0;𝑄1}) −→𝜈𝑥𝑦. (𝑃| 𝑄0) (PlusL-With)

𝜈𝑥𝑦. (𝑥[inr]. 𝑃| 𝑦.case{𝑄0;𝑄1}) −→𝜈𝑥𝑦. (𝑃| 𝑄1) (PlusR-With)

𝜈𝑥𝑦. (£𝑥[].𝐶| ¡𝑦{𝑧,𝑧′,𝑦′.𝑄}(𝑖, 𝑓). 𝑃) −→𝐶| 𝜈𝑖𝑓. 𝑃 (Claro-QueW)

(Claro-QueA)

𝜈𝑥𝑦. (?𝑥[]. 𝑃| !𝑦{®𝑧; 𝑄}) −→?®𝑧[]. 𝑃 (ExpW)

𝜈𝑥𝑦. (?𝑥[𝑥′]. 𝑃| !𝑦{®𝑧; 𝑄}) −→𝜈𝑥′𝑦. (𝑃| 𝑄) (ExpD)

def= !𝑦1{ ®𝑧1; 𝑄[ ®𝑧1𝑦1/®𝑧𝑦]} | !𝑦0{ ®𝑧0; 𝑄[ ®𝑧0𝑦0/®𝑧𝑦]} (ExpC)

Fig. 3. The operational semantics of CSLL processes.

the name partition in order to preserve typing. As a counterexample, if 𝑃⊢𝑥: 𝐴,𝑦: 𝐵| 𝑧: 𝐶,𝑤: 𝐷, then 𝑥(𝑤).𝑦[𝑧]. 𝑃⊢𝑥: 𝐴O 𝐷,𝑦: 𝐵⊗𝐶while 𝑦[𝑧]. 𝑥(𝑤). 𝑃is ill-typed. To avoid this, we say that 𝑥(𝑤). and 𝑦[𝑧]. cross the name partition Fn(𝑃) = 𝑥,𝑦| 𝑧,𝑤, and hence that this commutation is forbidden. Formally, 'crossing' is defined as follows.

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.


<!-- page 14 -->

𝑋𝑥(𝑥′).

𝑌𝑦[𝑦′].

def= {𝑧,𝑤}

𝑌¡𝑦{𝑧′,𝑤′,𝑦′.𝑄}(𝑧,𝑤).

Now, 𝜋𝑥and 𝜋𝑦cross the name partition ⌊G⌋just if any of the following cases apply.

The overwhelming majority of these commuting conversions is used in previous works on the relationship between linear logic and 𝜋-calculus to obtain cut elimination [Wadler 2014, ğ3.6] [Bellin and Scott 1994, ğ3]. Perhaps the only exception is Pre-Pre, which allows us to swap any two noninterfering prefixes. It can be justified computationally as an observational equivalence arising from the semantics of Atkey [2017, ğ5]. Finally, Kokke et al. [2019a] view it as a session-theoretic version of delayed actions [Merro and Sangiorgi 2004].

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.

62:14 Zesen Qian, G. A. Kavvos, and Lars Birkedal

Definition 3.2. We first define two sets of names 𝑋𝜋𝑥and 𝑌𝜋𝑦indexed by prefixes. Note they are defined for only some prefixes.

def= {𝑥,𝑥′} 𝑋?𝑥[𝑥0,𝑥1].

def= {𝑥0,𝑥1}

def= {𝑦,𝑦′} 𝑌£𝑦[𝑦′].

def= {𝑦,𝑦′}

• In the binary case, we require the following: 𝑋𝜋𝑥and 𝑌𝜋𝑦is defined for 𝜋𝑥and 𝜋𝑦respectively; write 𝑋𝜋𝑥= {𝑥0,𝑥1} and 𝑌𝜋𝑦= {𝑦0,𝑦1}; there are Γ, Δ ∈⌊G⌋such that 𝑥0,𝑦0 ∈Γ and 𝑥1,𝑦1 ∈Δ. • In the nullary case, we require all the following to hold: ś 𝜋𝑥is 𝑥(). or ?𝑥[]. ś 𝜋𝑦is 𝑦[]. or £𝑥[]. ś ⌊G⌋is ∅ Moreover, 𝜋𝑥and 𝜈𝑦0𝑦1. cross the name partition ⌊G⌋if the following holds: 𝑋𝜋𝑥is defined for 𝜋𝑥; write 𝑋= {𝑥0,𝑥1} and there is Γ, Δ ∈⌊G⌋such that 𝑥0,𝑦0 ∈Γ and 𝑥1,𝑦1 ∈Δ.

The structural equivalence Que-Que allows us to commute the position of two clients in the pool, thereby imitating racingÐas discussed in ğ3.4. In order fully exploit the nondeterminism induced by Que-Que the other structural equivalences are necessary. For example, the two clients in £𝑥[𝑥0].𝑦(𝑦′). £𝑥[𝑥1]. 𝑃cannot be permuted without using Pre-Pre first. Indeed, this is the major motivation for Pre-Pre, as the latter is not needed for our metatheoretic results. Note that Que-Que is the one and only source of nondeterminism in the system.

Some commuting conversions appear as structural equivalences, and some as reaction rules. OfCourse-Comm and With-Comm are commuting conversions for OfCourse and With respectively. Pre-Par with Res-Pre combine into a kind of commuting conversion for prefixes. We take the former as reaction rules, and the latter as structural equivalences. This choice makes structural equivalence preserve canonicity. For example, in With-Comm the LHS is not canonical, but the RHS is.

Pre corresponds to eliminating non-top-level cuts in Linear Logic; it is not standard in either 𝜋calculus or CP. Nevertheless, we choose to include it in order to strengthen our notion of canonical form, which in turn elucidates the examples in ğ4. In contrast, the reaction rules for the exponentials are standard; see Kokke et al. [2019a].

Finally, we have a number of novel reaction rules for coexponentials. The rule Claro-QueW corresponds to serving an empty client pool. In this case we simply connect the initialization and finalization channels of 𝑃. Likewise, the rule Claro-QueA is the reaction caused by a nonempty pool of clients. The pool offers a fresh channel 𝑥′ on which the new client expects to be served. The server then spawns a worker process 𝑄, and the channel 𝑦′ on which it will serve the new client which is connected to 𝑥′, as expected. The initialization channel 𝑖of the server continuation is connected to the 𝑧channel, on which the worker process expects to receive the 'current state' of the server. Once 𝑄serves the client, it will send the 'next state' of the server on 𝑧′. Thus, we re-instantiate the server with 𝑧′ as the new initialization channel. Note that the 'server state' we


<!-- page 15 -->

discuss here does not conform to the usual intuition of an immutable value; it could be a session type itself, as demonstrated by the example in ğ5.5.

We have the following metatheoretic results.

Lemma 3.3. If 𝑃≡𝑄, then 𝑃⊢G if and only if 𝑄⊢G.

Theorem 3.4 (Preservation). If 𝑃⊢G and 𝑃−→𝑄, then 𝑄⊢G.

## 4 AN EXAMPLE: COMPARE-AND-SET

A register that supports compare-and-set comes with an operation Cas(𝑒,𝑑) which takes two values: the expected value 𝑒, and the desirable value 𝑑. The function compares the expected value 𝑒with the register. If the two differ, the value of the register remains put, and Cas(𝑒,𝑑) returns false. But if they are found equal, the register is updated with the desirable value 𝑑, and Cas(𝑒,𝑑) returns true. When multiple clients are trying to perform CAS operations on the same register they must be performed atomically. The CAS operation is very powerful: an asynchronous machine that supports it can implement all concurrent objects in a wait-free manner.

def= 𝑧[inl].𝑧[]. stop ⊢𝑧: 2 ff𝑧

tt𝑧

Moreover, we obtain the following derivable 'elimination' rule (we write derivable rules in blue):

𝑃⊢Γ

𝑧(). 𝑃⊢𝑧: ⊥, Γ

if(𝑧; 𝑃; 𝑄)

Hence, we can eliminate a Boolean channel in any environment Γ. The induced reactions are

𝜈𝑥𝑦. (tt𝑥| if(𝑦; 𝑃; 𝑄)) −→∗stop | 𝑃≡𝑃 𝜈𝑥𝑦. (ff𝑥| if(𝑦; 𝑃; 𝑄)) −→∗stop | 𝑄≡𝑃

We can now implement a register with a CAS operation. To begin, each client communicates with the register along a channel of type

𝐴

def= 𝑥0[𝑥𝑒]. 𝑥0[𝑥𝑑]. (ff𝑥𝑒| tt𝑥𝑑| 𝑥0 ↔𝑟0) ⊢𝑥0 : 2 ⊗2 ⊗2⊥O 1,𝑟0 : 2 ⊗⊥

𝐶0

def= 𝑥1[𝑥𝑒]. 𝑥1[𝑥𝑑]. (tt𝑥𝑒| ff𝑥𝑑| 𝑥1 ↔𝑟1) ⊢𝑥1 : 2 ⊗2 ⊗2⊥O 1,𝑟1 : 2 ⊗⊥

𝐶1

clients

Client-Server Sessions in Linear Logic 62:15

Theorem 3.5 (Progress). If 𝑅⊢G then either 𝑅is canonical, or there exists 𝑅′ such that 𝑅−→𝑅′.

We now wish to demonstrate the client-server features of CSLL. To do so we produce an implementation of the quintessential example of a synchronization primitive, the Compare-and-Set operation (CAS) [Herlihy and Shavit 2012, ğ5.8]. Higher-level examples are given in ğ5.

We follow previous work [Abramsky 1993a; Atkey et al. 2016; Girard 1987; Kokke et al. 2019a] and define the type of Boolean sessions to be 2

def= 1 ⊕1. We have the following derivable constants:

def= 𝑧[inr].𝑧[]. stop ⊢𝑧: 2

𝑄⊢Γ

𝑧().𝑄⊢𝑧: ⊥, Γ

def= 𝑧.case{𝑧(). 𝑃;𝑧().𝑄} ⊢𝑧: 2⊥, Γ

def= 2 ⊗2 ⊗2⊥O 1

Thus, a client outputs three channels. On the first two it shall send the expected and desirable values. On the third it will input a boolean, namely the success flag of the CAS operation. Following that, it will accept an end-of-session signal. Curiously, this last step is necessary for our implementation to type-check.

As a minimal example we will construct a pool of two racing clients, one performing Cas(ff, tt), and the other one Cas(tt, ff). Initially 𝑥1 is ahead in the client pool.

def= £𝑥[𝑥1]. £𝑥[𝑥0]. £𝑥[]. (𝐶0 | 𝐶1) ⊢𝑥: £ 2 ⊗2 ⊗2⊥O 1 ,𝑟0 : 2 ⊗⊥,𝑟1 : 2 ⊗⊥

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.


<!-- page 16 -->

def= 2. We initialize the register to false, and forward the final state of the register to 𝑢.

𝑃

𝑄

We have carefully named the channels so that 𝑦𝑒: 2⊥and 𝑦𝑑: 2⊥carry the expected and desirable values. 𝑧′ and 𝑤′ carry the internal register, before and after the operation. The continuations 𝑅0 and 𝑅1 do a case analysis on the expected and desired value:

𝑅1

𝑅0

Two further case analyses lead to an exhaustive eight cases, each of which is handled by a separate process 𝑆𝑖𝑗𝑘. We only give 𝑆110 here, the rest being analogous:

𝑆110

def= ¡𝑦{𝑧,𝑧′,𝑦′.𝑄}(𝑖, 𝑓). 𝑃⊢𝑦: ¡(2⊥O 2⊥O 2 ⊗⊥),𝑢: 2, and cut:

𝜈𝑥𝑦. (clients | server)

= 𝜈𝑥𝑦. (£𝑥[𝑥1]. £𝑥[𝑥0]. £𝑥[]. (𝐶0 | 𝐶1) | server)

−→∗𝑟0[𝑦𝑟]. (tt𝑦𝑟| 𝑟0().𝑟1[𝑦𝑟]. (tt𝑦𝑟| 𝑟1().𝜈𝑥𝑦. (£𝑥[]. stop | ¡𝑦{𝑧,𝑧′,𝑦′.𝑄}(𝑧′′′, 𝑓). 𝑃′′)))

The coexponentials play a central rôle here: ¡ is used to represent the fact that this register provides a server session at a unique end point, and £ is used to collect requests for a CAS operation to this single end point. We see that every feature of client-server interaction, as described in points (i)ś(iv) of ğ1.1, is modelled.

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.

62:16 Zesen Qian, G. A. Kavvos, and Lars Birkedal

Note that each client forwards the result it receives to an individual channel 𝑟𝑖. By the QueA rule these two channels are preserved in the final interface of the pool.

Next we define the CAS register process, for which we use the ¡ connective. This requires two components: the initialization and finalization process 𝑃, and the worker process 𝑄that serves one client. To begin, we pick the internal server state to be 𝐵

def= (ff𝑖| 𝑓↔𝑢) ⊢𝑖: 2 | 𝑓: 2⊥,𝑢: 2

Finally, we define 𝑄. We begin by receiving the input and output channels from a client, and do a case analysis on the current state of the register:

def= 𝑦′(𝑦𝑒).𝑦′(𝑦𝑑). if(𝑧; 𝑅1; 𝑅0) ⊢𝑧: 2⊥,𝑦′ : 2⊥O 2⊥O 2 ⊗⊥,𝑧′ : 2

def= if(𝑦𝑒; if(𝑦𝑑; 𝑆111; 𝑆110); if(𝑦𝑑; 𝑆101; 𝑆100)) ⊢𝑦𝑒: 2⊥,𝑦𝑑: 2⊥,𝑦′ : 2 ⊗⊥,𝑧′ : 2

def= if(𝑦𝑒; if(𝑦𝑑; 𝑆011; 𝑆010); if(𝑦𝑑; 𝑆001; 𝑆000)) ⊢𝑦𝑒: 2⊥,𝑦𝑑: 2⊥,𝑦′ : 2 ⊗⊥,𝑧′ : 2

def= 𝑦′[𝑦𝑟]. (tt𝑦𝑟| 𝑦′(). ff𝑧′) ⊢𝑦′ : 2 ⊗⊥,𝑧′ : 2

In this case, the expected value (true) matches the register state (true), so the process outputs true to the result channel 𝑦𝑟(the CAS operation succeeds), and the register is set to the desired value (false). We must not forget to receive an end-of-session signal on 𝑦, as required by the session type. We let server

≡𝜈𝑥𝑦. (£𝑥[𝑥0]. £𝑥[𝑥1]. £𝑥[]. (𝐶0 | 𝐶1) | server) (𝑥0 preempts 𝑥1 using Que-Que)

−→𝜈𝑥𝑦.𝜈𝑥0𝑦′. (𝐶0 | £𝑥[𝑥1]. £𝑥[].𝐶1 | ¡𝑦{𝑧,𝑧′,𝑦′.𝑄}(𝑧′, 𝑓). (𝜈𝑖𝑧. (𝑃| 𝑄))) (𝐶0 is accepted)

−→∗𝑟0[𝑦𝑟]. (tt𝑦𝑟| 𝑟0().𝜈𝑥𝑦. (£𝑥[𝑥1]. £𝑥[].𝐶1 | ¡𝑦{𝑧,𝑧′,𝑦′.𝑄}(𝑧′′, 𝑓). 𝑃′)) (𝐶0 performs CAS)

−→𝑟0[𝑦𝑟]. (tt𝑦𝑟| 𝑟0().𝜈𝑥𝑦.𝜈𝑥1𝑦′. (𝐶1 | £𝑥[]. stop | ¡𝑦{𝑧,𝑧′,𝑦′.𝑄}(𝑧′, 𝑓). (𝜈𝑧′′𝑧. (𝑃′ | 𝑄))))

(𝐶1 is accepted)

(𝐶1 performs CAS)

−→𝑟0[𝑦𝑟]. (tt𝑦𝑟| 𝑟0().𝑟1[𝑦𝑟]. (tt𝑦𝑟| 𝑟1(). (stop | 𝜈𝑧′′′𝑓. 𝑃′′))) (server starts to finalize)

−→∗𝑟0[𝑦𝑟]. (tt𝑦𝑟| 𝑟0().𝑟1[𝑦𝑟]. (tt𝑦𝑟| 𝑟1(). ff𝑢)) ⊢𝑟0 : 2 ⊗⊥,𝑟1 : 2 ⊗⊥,𝑢: 2 (server finalizes)

where 𝑃′ = tt𝑧′′ | 𝑓↔𝑢and 𝑃′′ = ff𝑧′′′ | 𝑓↔𝑢. This corresponds to the scenario where 𝐶0 wins the first race, and hence the CAS operation of both clients suceeds. There is another reaction sequence: if 𝐶1 wins the first race, we end up with 𝑟1[𝑦𝑟]. (ff𝑦𝑟| 𝑟1().𝑟0[𝑦𝑟]. (tt𝑦𝑟| 𝑟0(). tt𝑢)).


<!-- page 17 -->

Client-Server Sessions in Linear Logic 62:17

The fact we are able to implement a synchronization primitive like CAS shows that the client-server rules also provide an additional safeguard, namely that server acceptance is atomic. While the actual CAS is not an atomic operationÐas many things are happening in parallelÐthe causal flow of information ensures that the state implicitly remains atomic.

𝑟0 2 ⊗⊥

𝑖 𝑧 𝐵

𝑦′ 𝑥0 𝐴

𝑄

𝐶0

ff𝑖

𝑧′

𝐵

𝑧

𝑟1 2 ⊗⊥

𝑦′ 𝑥1 𝐴 𝑢 2

𝑧′ 𝑓 𝐵

↔

𝑄

𝐶1

To illustrate the type of atomicity we have, consider an alternative reaction sequence where the two clients are immediately accepted before any other reaction. Fig. 4 shows the process topology of the scenario where 𝐶0 is accepted immediately before 𝐶1. Each client is connected to the one of the two worker processes 𝑄with client protocol 𝐴, and the worker processes are connected to each other and 𝑃with internal server protocol 𝐵. Which specific worker process a client connects to is determined by the client's position in the queue, before the coexponential reaction Claro-QueA takes place. The clients' positions in the layout also determine the final result of the reaction up to structural equivalence, even before the computation of the output takes place.

server

Fig. 4. Topology of Compare-and-Set protocol, after two server acceptances. Boxes represent processes. Cuts are represented by edges connecting two channels. The dual of each session type is omitted for simplicity.

## 5 A SESSION-TYPED LANGUAGE FOR CLIENT-SERVER PROGRAMMING

As the example of the previous section shows, CSLL is a particularly low-level language. This is a feature of essentially all variants of linear logic as used for session typing, including Kokke et al.'s HCP [2019a, Example 2.1], and Wadler's CP [Atkey 2017, ğ2.1] [Atkey et al. 2016, ğ3.1]. Consequently, the need for higher-level notation to help us write richer examples arises. These in turn will help us illustrate the degree of channel sharing allowed by CSLL. We follow the lead of Wadler [2014, ğ4] and introduce a higher-level, session-typed functional language, which we call CSGV.

CSGV is a linear 𝜆-calculus augmented with session types and communication primitives. It is based on the influential work of Gay and Vasconcelos [2010]. Over the past decade many variations of this language have been proposed; see e.g. Lindley and Morris [2015, 2016, 2017] and Fowler et al. [2019]. CSGV extends Wadler's version with primitives for client-server interaction. Like the approach in loc. cit. we do not directly endow CSGV with a semantics. Instead, we formulate a typepreserving translation into CSLL, which indirectly provides an execution mechanism. Naturally, the client-server primitives translate to the coexponential rules of CSLL.

### 5.1 Source Language and the Translation

Types. The types of CSGV consist of standard functional types and session types. While the former are used to classify values, the latter are used to describe the behaviour of channels. Compared to Wadler [2014] we have added sum types, and session types for client-server shared channels.

𝑇, . . . F 𝑇⊸𝑇| 𝑇→𝑇| 𝑇+𝑇| 𝑇⊗𝑇| Unit | 𝑇𝑆 𝑇𝑆, . . . F !𝑇.𝑇𝑆 (output value of type 𝑇, then behave as 𝑇𝑆)

| ?𝑇.𝑇𝑆 (input value of type 𝑇, then behave as 𝑇𝑆)

| 𝑇𝑆⊕𝑇𝑆 (select from options)

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.


<!-- page 18 -->

def= J𝑇K⊥O J𝑇𝑆K J𝑇𝑆N 𝑈𝐿K

J!𝑇.𝑇𝑆K

def= J𝑇K ⊗J𝑇𝑆K J𝑇𝑆⊕𝑈𝐿K

J?𝑇.𝑇𝑆K

def= ¡J𝑇𝑆K J¡𝑇𝑆K

J£𝑇𝑆K

def= ?𝑇.𝑇𝑆 !𝑇.𝑇𝑆

!𝑇.𝑇𝑆

def= £𝑇𝑆 The translation is a homomorphism of involutions:

def= 𝑇𝑆⊕𝑈𝐿 £𝑇𝑆

𝑇𝑆N 𝑈𝐿

Lemma 5.1. J𝑇𝑆K = J𝑇𝑆K⊥.

Thus, connecting channels in CSGV will be translated to cuts in linear logic.

Definition 5.2. The set of unlimited types is defined inductively as follows.

• unit and 𝑇→𝑈are unlimited. • 𝑇+ 𝑈and 𝑇⊗𝑈are unlimited whenever 𝑇and 𝑈are. All other types are linear.

𝐿, 𝑀, 𝑁F 𝑥| ★| 𝜆𝑥. 𝑁| 𝑀𝑁| (𝑀, 𝑁) | let (𝑥,𝑦) = 𝑀in 𝑁

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.

62:18 Zesen Qian, G. A. Kavvos, and Lars Birkedal

| 𝑇𝑆N𝑇𝑆 (offer choice)

| end? | end! (end-of-session)

| £𝑇𝑆 (request 𝑇𝑆session)

| ¡𝑇𝑆 (serve 𝑇𝑆session)

Both the functional types and the session types of CSGV are translated to the linear types of CSLL. The functional part closely follows Wadler in using the 'call-by-value' embedding of intuitionistic logic into linear logic [Benton and Wadler 1996; Maraist et al. 1999, 1995]. The session types are translated as follows:

def= J𝑇𝑆K ⊕J𝑈𝐿K Jend!K

def= ⊥

def= J𝑇𝑆K N J𝑈𝐿K Jend?K

def= 1

def= £J𝑇𝑆K

As noted by Wadler [2014, ğ4.1], the connectives translate to the dual of what one might expect. The reason is that channels are used in the opposite way. Consider the session type !𝑇.𝑆: sending a value in CSGV is translated as inputting a channel on which you can send it in CSLL. Similarly, ¡𝑆 does not represent a channel that the server provides, but rather a channel that the server consumes. It is therefore a channel that the client pool provides, and hence it is translated to a client in CSLL.

Duality. We define duality on session types in the standard way; it is obviously an involution.

def= ?𝑇.𝑇𝑆 𝑇𝑆⊕𝑈𝐿

def= 𝑇𝑆N 𝑈𝐿

def= ¡𝑇𝑆 ¡𝑇𝑆

Values of unlimited types can be discarded and duplicated, because they are translated to CSLL types that admit weakening and contraction. Categorical considerations [Melliès 2009, ğ6.5] lead us to consider 𝑇⊗𝑈unlimited whenever 𝑇and 𝑈are, which is finer-grained than loc. cit.

Terms. CSGV is a linear 𝜆-calculus, extended with constructs for sending and receiving messages.

| inl 𝑀| inr 𝑀| match 𝐿with 𝑥.{𝑀, 𝑁} (functional fragment)

| send 𝑀𝑁| recv 𝑀 (send and receive)

| select𝐿𝑀| select𝑅𝑀| case 𝐿of 𝑥.{𝑀, 𝑁} (select options)

| terminate 𝑀 (terminate 𝑀)

| connect(𝑥. 𝑀;𝑦. 𝑁) (connect 𝑥of 𝑀to 𝑦of 𝑁)

| eof𝑥 (end client pool)

| fork𝑥𝑥′. 𝑀 (extract client interface)


<!-- page 19 -->

u

}

Recv

Γ ⊢𝑀: ?𝑇.𝑇𝑆 Γ ⊢recv 𝑀: 𝑇⊗𝑇𝑆

wv

~

def= J𝑀K𝑧⊢JΓK⊥,𝑧: J𝑇K ⊗J𝑇𝑆K

𝑧

u

}

Send

Γ ⊢𝑀: 𝑇 Δ ⊢𝑁: !𝑇.𝑇𝑆 Γ, Δ ⊢send 𝑀𝑁: 𝑇𝑆

wv

~

def=

𝑧

J𝑀K𝑦⊢JΓK⊥,𝑦: J𝑇K 𝑥′ ↔𝑧⊢𝑥′ : J𝑇𝑆K⊥,𝑧: J𝑇𝑆K

𝑥′[𝑦]. (J𝑀K𝑦| 𝑥′ ↔𝑧) ⊢JΓK⊥,𝑥′ : J𝑇K ⊗J𝑇𝑆K⊥,𝑧: J𝑇𝑆K

𝜈𝑥𝑥′. (𝑥′[𝑦]. (J𝑀K𝑦| 𝑥′ ↔𝑧) | J𝑁K𝑥) ⊢JΓK⊥, JΔK⊥,𝑧: J𝑇𝑆K u

}

Conn

Γ,𝑥: 𝑇𝑆⊢𝑀: end! Δ,𝑦: 𝑇𝑆⊢𝑁: 𝑇

wwv

~

def=

Γ, Δ ⊢connect(𝑥. 𝑀;𝑦. 𝑁) : 𝑇

𝑧

J𝑀K𝑦⊢JΓK⊥,𝑥: J𝑇𝑆K⊥,𝑦: ⊥ 𝑧[]. stop ⊢𝑧: 1

𝜈𝑦𝑧. (J𝑀K𝑦| 𝑧[]. stop) ⊢JΓK⊥,𝑥: J𝑇𝑆K⊥ Cut

𝜈𝑥𝑦. (𝜈𝑦𝑧. (J𝑀K𝑦| 𝑧[]. stop) | J𝑁K𝑧) ⊢JΓK⊥, JΔK⊥,𝑧: J𝑇K

Typing rules. The environments of CSGV are given by Γ, · · · F • | Γ,𝑥: 𝑇. The translation of types is extended to environments pointwise.

Client-Server Sessions in Linear Logic 62:19

⊗

J𝑁K𝑥⊢JΔK⊥,𝑥: J𝑇K⊥O J𝑇𝑆K

J𝑁K𝑧⊢JΔK⊥,𝑦: J𝑇𝑆K,𝑧: J𝑇K

Cut

Fig. 5. CSGV Typing Rules and Translation to CSLL: linear session part

| serve 𝑦{𝐿,𝑧. 𝑀, 𝑓. 𝑁} (server construction)

Selected typing rules of CSGV are given in Figs. 5 and 6. Most rules follow Wadler [2014, ğ4.1] to the letter, and are therefore omitted. In the interest of economy we also give the translation to CSLL at the same time. The translation is defined by induction on the typing derivations of CSGV. As the purpose of a CSGV program is the computation of a value of a distinguished type, the translation must privilege a single name over which this value will be returned. Thus, given a choice of name 𝑧and a typing derivation Γ ⊢𝑀: 𝑇, we write JΓ ⊢𝑀: 𝑇K𝑧for its translation into CSLL. Somewhat abusively we will sometimes also write J𝑀K𝑧⊢JΓK⊥,𝑧: J𝑇K for the translated term. This slight abuse of notation also reveals the intended typing.

The novelty here is in the CSGV rules for client-server interaction, and their translation into CSLL. A name of shared client type £𝑇𝑆can be seen as a form of 'capability' for talking to the server. ReqW discards this capability, signalling the end of the client pool. ReqA uses it to spawn a fresh channel 𝑥′ on which a client 𝑀will talk to a server, and returns the capability back to the caller. The client 𝑀itself has type end!: it does not return valuable information, but uses values and channels found in Γ.

Dually, ¡𝑇𝑆is the type of a server channel. Serv constructs a server from three components. 𝐿 computes the initial state of the server. Given the current state in 𝑧, and a client channel 𝑦, 𝑀 serves the client listening on 𝑦, and then returns the next state of the server. 𝑁finalizes the server. Note that the so-called server 'state' here could well be a channel itself, enabling bidirectional interleaving communicationÐa design we will explore in ğ5.5.

The Serv typing rule is quite restrictive, in that it does not allow anything from the environments Δ and Σ to be used in the term 𝑀which computes the next state of the server. Fortunately, the

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.


<!-- page 20 -->

u

}

stop ⊢∅

ReqW

£𝑥[]. stop ⊢𝑥: £J𝑇𝑆K⊥

wwv

~

def=

𝑧(). £𝑥[]. stop ⊢𝑥: £J𝑇𝑆K⊥,𝑧: ⊥

𝑥: £𝑇𝑆⊢eof𝑥: end!

𝑧

u

}

ReqA

Γ,𝑥′ : 𝑇𝑆⊢𝑀: end! Γ,𝑥: £𝑇𝑆⊢fork𝑥𝑥′. 𝑀: £𝑇𝑆

wwv

~

def=

𝑧

J𝑀K𝑢⊢JΓK⊥,𝑥′ : J𝑇𝑆K⊥,𝑢: ⊥ 𝑣[]. stop ⊢𝑣: 1

𝜈𝑢𝑣. (J𝑀K𝑧| 𝑣[]. stop) ⊢JΓK⊥,𝑥′ : J𝑇𝑆K⊥ 𝑥↔𝑧⊢𝑥: £J𝑇𝑆K⊥,𝑧: ¡J𝑇𝑆K

£𝑥[𝑥′]. (𝜈𝑢𝑣. (𝑧[]. stop | J𝑀K𝑢) | 𝑥↔𝑧) ⊢JΓK⊥,𝑥: £J𝑇𝑆K⊥,𝑧: ¡J𝑇𝑆K

u

Serv

Δ ⊢𝐿: 𝑇 𝑧: 𝑇,𝑦: 𝑇𝑆⊢𝑀: 𝑇 Σ, 𝑓: 𝑇⊢𝑁: 𝑈

wv

Δ, Σ,𝑦: ¡𝑇𝑆⊢serve 𝑦{𝐿,𝑧. 𝑀, 𝑓. 𝑁} : 𝑈

J𝐿K𝑖⊢JΔK⊥,𝑖: J𝑇K J𝑁K𝑢⊢JΣK⊥, 𝑓: J𝑇K⊥,𝑢: J𝑈K

J𝐿K𝑖| J𝑁K𝑢⊢JΔK⊥,𝑖: J𝑇K | JΣK⊥, 𝑓: J𝑇K⊥,𝑢: J𝑈K J𝑀K𝑧′ ⊢𝑧: J𝑇K⊥,𝑦: J𝑇𝑆K⊥,𝑧′ : J𝑇K

¡𝑦{𝑧,𝑧′,𝑦. J𝑀K𝑧′}(𝑖, 𝑓). (J𝐿K𝑖| J𝑁K𝑢) ⊢JΔK⊥,𝑦: ¡J𝑇𝑆K⊥, JΣK⊥,𝑢: J𝑈K

following derivable rule allows us to weave some non-linear values of types ®𝑉in the server.

®𝑣: ®𝑉, Δ, Σ,𝑦: ¡𝑇𝑆⊢serve 𝑦{(®𝑣, 𝐿),𝑧′. let (®𝑣,𝑧) = 𝑧′ in (®𝑣, 𝑀), 𝑓′. let (®𝑣, 𝑓) = 𝑓′ in 𝑁}

We will make crucial use of this derivable rule in a couple of our examples. We also also adopt the common shorthands let 𝑥= 𝑀in 𝑁

### 5.2 Functional Data Structure Server

enq : 𝑇⊗𝐴→𝑇 deq : 𝑇→𝑇⊗(Unit + 𝐴) empty : 𝑇

In particular, deq could return Unit if the queue is empty. The server will talk to a client via a channel of type𝑇𝑆

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.

62:20 Zesen Qian, G. A. Kavvos, and Lars Birkedal

HMix2+QueA

}

~

def=

𝑢

Claro

Fig. 6. CSGV Typing Rules and Translation to CSLL: shared session part

Δ ⊢𝐿: 𝑇 ®𝑣: ®𝑉,𝑧: 𝑇,𝑦: 𝑇𝑆⊢𝑀: 𝑇 Σ, 𝑓: 𝑇⊢𝑁: 𝑈 ®𝑉unlimited

: 𝑈

| {z }

serve' 𝑦{𝐿,𝑧. 𝑀, 𝑓. 𝑁}

def= (𝜆𝑥. 𝑁)𝑀and let _ = 𝑀in 𝑁

def= (𝜆𝑧. 𝑁)𝑀for fresh 𝑧: ★.

Our primitives can be used to protect a shared functional data structure. Without loss of generality, we consider a server whose state is a purely functional queue 𝑇with operations

def= (?𝐴.end?)N(!(Unit+𝐴).end?). One client receives an 𝐴along 𝑟0, and enqueues it. The other one dequeues an element, and sends it along 𝑟1.


<!-- page 21 -->

def= empty

𝐿

def= let (𝑣,𝑦′′) = recv 𝑦′ in

𝑀𝑒𝑛𝑞

let _ = terminate 𝑦′′ in enq(𝑧, 𝑣)

def= let (𝑣,𝑧′) = deq 𝑧in

𝑀𝑑𝑒𝑞

let _ = terminate (send 𝑣𝑦′) in 𝑧′

def= case 𝑦of 𝑦′.{𝑀𝑒𝑛𝑞, 𝑀𝑑𝑒𝑞}

𝑀

let 𝑥= fork𝑥𝑥1.𝐶1 in eof𝑥 We then define server

def= serve 𝑦{𝐿,𝑧. 𝑀, 𝑓. 𝑓}, and see that

𝑟0 : ?𝐴.end?,𝑟1 : !(Unit + 𝐴).end? ⊢connect(𝑥. clients;𝑦. server) : 𝑇

def= Unit + Unit. We implement tt and ff by the obvious injections, and the conditional by

Γ ⊢𝐵: B Δ ⊢𝑀: 𝑉 Δ ⊢𝑁: 𝑉

Γ, Δ ⊢if 𝐵then 𝑀else 𝑁

(for 𝑥fresh). The clients 𝐶0,𝐶1 respectively send ff and tt over a channel. We also define a server with a pair of Booleans as internal state. The first component records whether the server has ever received a value. When a value is received it is stored in the second component, and any further values received are discarded.

def= send ff 𝑥0

𝐶0

def= send tt 𝑥1

𝐶1

def= let 𝑥= fork𝑥𝑥0.𝐶0 in

clients

let 𝑥= fork𝑥𝑥1.𝐶1 in

eof𝑥

We define a server

⊢flip

𝑃⊢Γ 𝑄⊢Γ

choose(𝑃,𝑄)

such that choose(𝑃,𝑄) −→∗𝑃and choose(𝑃,𝑄) −→∗𝑄.

Client-Server Sessions in Linear Logic 62:21

def= let (𝑣,𝑟′

0) = recv 𝑟0 in

𝐶0

let _ = terminate 𝑟′

0 in

let 𝑥′

0 = send 𝑣(select𝐿𝑥0) in 𝑥′ 0

def= let (𝑣,𝑥′

1) = recv (select𝑅𝑥0) in

𝐶1

let _ = terminate (send 𝑣𝑟1) in 𝑥′

def= let 𝑥= fork𝑥𝑥0.𝐶0 in

clients

### 5.3 Nondeterminism

Unsurprisingly, the races in our system suffice to implement nondeterministic choice. We define B

+𝐸

def= match 𝐵with 𝑥.{𝑀, 𝑁} : 𝑉

def= let (𝑧0,𝑧1) = 𝑧in

𝑀

let (𝑣,𝑦′) = recv 𝑦in

let _ = terminate 𝑦′ in

if 𝑧0 then 𝑧else (tt, 𝑣)

def= let (𝑓0, 𝑓1) = 𝑓in 𝑓1

𝑁

def= serve 𝑦{(ff, ff),𝑧. 𝑀, 𝑓. 𝑁} beginning from (ff, ff). We then have that

def= connect(𝑥. clients;𝑦. server) : B

This program is translated to JflipK𝑦⊢𝑦: 2, with reactions JflipK𝑦−→∗ff𝑦and JflipK𝑦−→∗tt𝑦. We can use this to implement a nondeterministic choice operator:

def= 𝜈𝑥𝑦. (JflipK𝑦| if(𝑥; 𝑃; 𝑄)) ⊢Γ

### 5.4 Fork-Join Parallelism

Fork-join parallelism [Conway 1963] is a common model of parallelism in which child processes are forked to perform computation simultanously. Once they have finished, they are joined by the parent process, which collects their work and produces the final result. We assume a 'heavyweight' function ℎ: 𝐴→𝐵that will run on forked processes, and a relatively less expensive function 𝑔: 𝐵→𝐵→𝐵that will combine their answers. We also assume an initial value 𝑔0 : 𝐵, and a

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.


<!-- page 22 -->

62:22 Zesen Qian, G. A. Kavvos, and Lars Birkedal

list of 'tasks' 𝑥𝑠: [𝐴] to process. Of course, [𝐴] is the type of lists of 𝐴, and is supported by the operations:

nil : [𝐴] cons : 𝐴→[𝐴] →[𝐴] fold𝐶: 𝐶→(𝐶→𝐴→𝐶) →[𝐴] →𝐶

Let

def= let 𝑦= fold£𝑇𝑆𝑐(𝜆𝑥. 𝜆𝑣. fork𝑥𝑥′. (let 𝑣′ = ℎ𝑣in send 𝑣′ 𝑥′)) 𝑥𝑠in eof𝑦

clients

def= let (𝑣,𝑦′) = recv 𝑦in let _ = terminate 𝑦′ in 𝑔𝑧𝑣

𝑀

def= !𝐵.end!. To form the client pool, we begin with a shared client channel 𝑐: £𝑇𝑆. We fold over the list 𝑥𝑠: [𝐴], adding a forked process for each 'task' 𝑣: 𝐴to the client pool. Each one of these forked processes will compute ℎ𝑣: 𝐵, and send it over its fresh channel 𝑥′ : 𝑇𝑆. We have 𝑐: £𝑇𝑆⊢clients : end!.

The client protocol is 𝑇𝑆

def= serve' 𝑦{𝑔0,𝑧. 𝑀, 𝑓. 𝑓}. The server begins with internal state 𝑔0 : 𝐵. It nondeterministically receives the result of a computation of ℎfrom each client, and 'merges' it into its state using 𝑔. In the end, it returns the result. We have 𝑧: 𝐵,𝑦: 𝑇𝑆⊢𝑀: 𝐵, and thus 𝑦: ¡𝑇𝑆⊢server : 𝐵. We use serve' to pass unlimited parameters to the server internals.

We let server

Putting this system together, we get

def= connect(𝑥. clients;𝑦. server) : 𝐵

⊢fork-join(ℎ,𝑔0,𝑔,𝑥𝑠)

The fork-join paradigm is often used in industrial parallelization frameworks [Blumofe et al. 1995; Dagum and Menon 1998; Leijen et al. 2009; Reinders 2007]. The background languages and type systems usually do not use any logical devices for concurrency. In particular, concurrent behaviour is not controlled by the type system, as it is here. Note that fork-join requires each spawned process to be independent of each other and only communicate with the parent process, which is precisely caputured by the linearity restriction of our system.

Another parallel computation model is that of async-finish. It is more expressive than fork-join, as it allows spawned processes to spawn further processes. The whole tree is then joined at the root process, with no regard to the spawning thread of each child. Our system(s) does not support that: in the ReqA rule, the spawned process 𝑀is only given a channel 𝑥′ : 𝑇𝑆, which cannot be used to spawn further processes in the same pool. However, it is well-known is that nested parallelism is still possible, but each child has to spawn its own instance of a fork-join computation, which does not interfere with the root process.

An even more expressive model is that of futures [Halstead 1984]. A future is a first-class value that represents a computation running in parallel to the current process. At any point it can be forced to obtain its result; if it has not finished an error may be returned, or the process forcing it may block. While fork-join or async-finish spawned processes are independent of each other, futures may be passed around freely (in any reasonably expressive language) and introduce rich interactions. This seems to be in violation of the linearity restriction of our system(s), and thus cannot be expressed. Nevertheless, the Conn rule can be seen as a very restricted form of future, where the spawned process can only communicate with the parent process. More discussions about the difference between these models is given by Acar [2016].

### 5.5 Keynes' Beauty Contest

Until this point we have seen only relatively simple examples of client-server interaction. In all cases, the 'internal server protocol' we have used has consisted of an unlimited type, the values of which we can replicate or discard. This leads to the false impression that clients access the server one-by-one in a sequential manner, so that clients that connect later are unable to influence the

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.


<!-- page 23 -->

information observed by the earlier ones. In this section we present an example that shows this to be untrue. In particular, if the 'internal server protocol' consists of a session type itself, then we witness bidirectional, interleaving behaviour. This distinguishes our systems from those based on manifest sharing [Balzer and Pfenning 2017].

Γ,𝑥: 𝑇𝑆⊢𝑀: end! 𝑦: 𝑇𝑆⊢𝑦: 𝑇𝑆

Γ ⊢inv𝑥(𝑀)

def= ?(N ⊗ N).!B.end?, where N is the type of natural numbers. We assume a bunch of standard functions:

The client session type is 𝐶𝑆

zero : N succ : N →N ≤: N →N →B eq : B →B →B

where eq checks Boolean values for equality. We let

𝐿

𝑁

𝑀

𝑤′′

Client-Server Sessions in Linear Logic 62:23

We present a server implementing the umpire in a Keynesian beauty contest [Keynes 1936, ğ12]. Keynes' beauty contest works as follows. A newspaper runs a beauty contest in which readers have to pick the prettiest faces from a set of photographs. The competitors are not those pictured, but the readers themselves: if they pick the faces which are judged to be the prettiest by the majority, they will win a prize. Thus, the readers are incentivized to estimate the aesthetics of the majority.

We will implement a restricted version of this scenario, where a pool of clients votes for a Boolean value. The server then counts the votes, and awards a payoff of 0 or 1 (represented by ff and tt respectively) to each client, indicating whether they voted for the winner. This is obviously impossible if the server handles requests sequentially. In fact, the server will be implemented by spawning a network of interconnected processes, each of which will handle one vote.

We first define the following derived rule. Informally, this rule expresses that a process that uses a channel of type 𝑇𝑆is also exposing a channel of dual type 𝑇𝑆.

def= connect(𝑥. 𝑀;𝑦.𝑦) : 𝑇𝑆

def= !B.?B.end!, and the internal server protocol is 𝑇𝑆

def= let 𝑤′ = send (zero, zero) 𝑤in (send initial state)

let (_,𝑤′′) = recv 𝑤′ in 𝑤′′ (receive final value)

def= let (𝑠, 𝑓′) = recv 𝑓in (receive final count)

let (𝑛0,𝑛1) = 𝑠in (unpack state)

let 𝑓′′ = send (𝑛0 ≤𝑛1) 𝑓′ in (compute winner and notify the last worker process)

terminate 𝑓′′ (close channel)

def= let (𝑠,𝑧′) = recv 𝑧in (get state)

let (𝑛𝑡,𝑛𝑓) = 𝑠in (unpack state)

let (𝑏,𝑦′) = recv 𝑦in (receive a vote)

let 𝑠′ = if 𝑏then (succ 𝑛𝑡,𝑛𝑓) else (𝑛𝑡, succ 𝑛𝑓) in (increment the right counter)

let 𝑤′ = send 𝑠′ 𝑤in (pass new state to next worker process)

let (𝑏′,𝑤′′) = recv 𝑤′ in (receive winner from next worker process)

let _ = terminate (send (eq 𝑏𝑏′) 𝑦′) in (tell competitor if they won, close channel)

let _ = terminate (send 𝑏′ 𝑧′) in (forward winner on, close channel)

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.


<!-- page 24 -->

We define server

𝑤: 𝑇𝑆⊢𝐿: end!

𝑤: 𝑇𝑆,𝑧: 𝑇𝑆,𝑦: 𝐶𝑆⊢𝑀: end!

The details of this protocol are subtle. The construct inv𝑥(−) allows us to use programs which only have side-effects as internal server state, by inverting the polarity of one of the channels. The server is initialized by 𝐿, which sets the state to be (0, 0). It then listens on the same channel to receive the winner, which it promptly discards. The server finalization 𝑁receives the final tally of the votes, computes the winner, sends back the result, and closes the channel.

If we have two such competitors 𝐶0 and 𝐶1 merged in a pool, and we connect them to server, we will obtain a process topology of the form illustrated in the schematic diagram of Fig. 7. Compared with Fig. 4 this diagram is intuitive but loose on accuracy. Details such as end? and end! are left out. We have also spelled out the protocols internals. For example, the server internal protocol 𝑇𝑆is indicated by a forward arrow N ⊗N and a backward arrow B.

## 6 RELATED WORK

Hypersequents and Session Types. Hypersequents were introduced to process calculi and Classical Linear Logic by Montesi and Peressotti [2018]. Another version of that system was studied in detail by Kokke et al. [2019a]. A reaction semantics similar to the one used here was given in [Kokke et al. 2018].

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.

62:24 Zesen Qian, G. A. Kavvos, and Lars Birkedal

def= serve 𝑦{inv𝑤(𝐿),𝑧. inv𝑤(𝑀), 𝑓. 𝑁}. The components are typed as

𝑓: 𝑇𝑆⊢𝑁: unit

𝑦: ¡𝑇𝑆⊢server : unit

The component 𝑀is used to communicate with each competitor. It receives the state of the server, the competitor's vote, and increments the appropriate tally. It then passes on this new state to the next worker process 𝑀, which will communicate with the next competitor. This sets up an entire network of worker processes 𝑀, one to serve each competitor. When the competitors have all cast their votes, 𝑁computes the winner, and sends it back to the last worker process. This process then tells the competitor whether they won, closes the channel to the competitor, and passes on the result to the worker process serving the previous competitor, and so on. At the very end, the winner is passed to the initialization process 𝐿.

We can then define a number of competitors 𝑥𝑖: !B.?B.end! ⊢𝐶𝑖: end! who will cast their votes by sending a Boolean value and receive a payoff along 𝑥𝑖. These can be combined into a client pool, much in the same way as in previous examples.

N ⊗N

𝑦B 𝑥0 𝑦

𝑥0

𝑧 B 𝑧′

𝐶0

𝑖

𝐿

𝑀

B

𝑧′

N ⊗N 𝑧

B

𝑧 𝑧′ N ⊗N 𝑓 𝑧′

𝑦B 𝑥1 𝑦

𝑥1

B 𝑓

𝐶1

𝑁

𝑀

B

server

Fig. 7. Layout of the Keynesian beauty contest, after coexponential reactions but before other reactions. Boxes represents processes whose names are at the center of the boxes. Arrows represents directed mesages between processes with types of the data annotated. Labels on edges of boxes are the names of the channels to the processes.

Differential Linear Logic. The rules for £ given in ğ2.2 are almost the same as the coweakening, codereliction and cocontraction rules for ! in Differential Linear Logic (DiLL) [Ehrhard 2018]. DiLL is equipped with nondeterministic reduction and formal sums, and is believed to have something to do with concurrency. Ehrhard and Laurent [2010] have produced an embedding of the finitary 𝜋-calculus into DiLL, though that encoding has been criticized [Mazza 2018]. A type of client-server interactionsÐnamely the encoding of ML-style reference cells into session typesÐhas been encoded by Castellan et al. [2020] in a system based on the rules of DiLL. This work relies on both the costructural rules and Mix, so it is not clear which device primarily augments expressive power.


<!-- page 25 -->

Client-Server Sessions in Linear Logic 62:25

Our work shows that something akin to the costructural rules of DiLL arises from the wish to form client pools. The exact relationship between coexponentials and DiLL remains to be determined.

Clients, Servers, and Races in Linear Logic. Typing client-server interaction has been a thorn in the side of session types and Linear Logic. All previous attempts rely on some version of the Mix rule. Both Wadler [2014, ğ3.4] and Caires and Pérez [2017, Ex. 2.4] use Mix to combine clients into client pools. Kokke et al. implicitly use Mix to type an otherwise untypable client pool in HCP [Kokke et al. 2019a, Ex. 3.7].7 Remarkably, none of these calculi demonstrate stateful server behaviour, as we predicted using a semantic argument in ğ1.1.

Atkey et al. [2016] explore the additional power bestowed upon CP by conflating dual connectives. The conflation of ? and ! leads to the notion of access point, a dynamic match-making communication service on a single end point. In fact, the rules look eerily close to the list-like formulation of our servers and generators. Access points prove too powerful: they introduce stateful nondeterminism, racy communication, and general recursion. This impairs the safety of CP by introducing deadlock and livelock. Our work shows that we can still safely obtain the former two features without introducing the third.

Adding nondeterminism to CLL in a controlled fashion is complex. Atkey et al. [2016] express a form of nondeterministic local choice in CP by conflating N and ⊕. The resultant form of nondeterministic choice cannot induce the racy behaviour normally exhibited in the 𝜋-calculus [Kokke et al. 2019b, ğ2]. Caires and Pérez [2017] present a dual-context system based on CLL+Mix in which the same kind of nondeterministic local choice is expressed through a new set of modalities, ⊕ and N.8 These bear a similarity to the coexponential modalities presented here, but they are used for nondeterminism instead. Their N modality has a monadic flavour, and hence can be used to encapsulate nondeterminism 'in the monad' in the usual manner in which we isolate effects.

Kokke et al. [2019b] drew inspiration from Bounded Linear Logic [Girard et al. 1992] to formulate a system for nondeterministic client-server interaction. They use types of the form ?𝑛𝐴(standing for 𝑛copies of 𝐴delimited by O) and !𝑛𝐴(standing for 𝑛copies of 𝐴delimited by ⊗). !𝑛𝐴represents a pool of 𝑛disjoint clients with protocol 𝐴, and ?𝑛𝐴a server that can serve exactly 𝑛clients with protocol 𝐴. While this is consistent with disjoint-vs.-connected concurrency, their system is limited to serving a specific number of clients in each session. Thus, it fails to satisfy criterion (i) in ğ1.1, and does not form a satisfactory model.

Carbone et al. [2017] approach multiparty session types through coherence proofs. In op. cit. the authors develop Multiparty Classical Processes, a version of CP with role annotations and the MCut rule. The latter is a version of the MultiCut rule annotated with a coherence judgment derived from Honda et al. [2016], which generalises duality and ensures that roles match appropriately. MCP does not allow dynamic sessions with arbitrary numbers of participants, and hence cannot model client-server interactions. MCP was later refined into the system of Globally-governed Classical Processes (GCP) by Carbone et al. [2016]. Unlike these calculi, our work does not require any consideration of coherence or local vs. global types.

The work of Rocha and Caires [2021], which also appears at ICFP 2021, introduces shared state to CLL. This is accomplished by a rule akin to the co-contraction of DiLL [Ehrhard 2018], along with rules that manipulate shared memory cells (allocation, deallocation, read, write). Races are resolved on a case-by-case basis, using locks to protect critical sections, and collecting all the possible outcomes into a formal sum, again in the style of DiLL. This system also includes the Mix rule, but it is not clear if that is an essential feature of the approach. Moreover, the system enjoys subject reduction, progress, weak normalization, andÐas nondeterminism is captured by formal sumsÐit

7This has been confirmed to us by the authors. 8This is an intentional clash with external and internal choice in Linear Logic.

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.


<!-- page 26 -->

62:26 Zesen Qian, G. A. Kavvos, and Lars Birkedal

is also confluent. Compared to that work the approach through the coexponential modalities is more parsimonious and follows Linear Logic more closely. The difference in expressivity is unclear.

Fixed Points in Linear Logic. Inductive and coinductive typesÐpresented proof-theoretically as least and greatest fixed pointsÐwere introduced in the context of higher-order Classical Linear Logic by Baelde [2012]. Baelde formulates a weakly normalization cut elimination procedure, which albeit does not satisfy the subformula property. Ehrhard and Jafarrahmani [2021] study categorical models for a slight extension of the propositional fragment of Baelde's system, which allow them to infer certain facts about the behaviour of (co)inductive linear data types.

The structure of Baelde's system has been used to extend CP with inductive and coinductive types by Lindley and Morris [2016]. This is our starting point in ğ2.2, but with significant alterations along the way. First, our system is based on HCP. Second, the server rule has been reformulated to support hyperenvironments, and server finalization (ğ3.3). Third, our client pools allow permutation in order to enable nondeterminism (ğ3.4). Finally, our reaction semantics are tailored to the specific setting, and are consequently simpler. In a separate strand of work, Toninho et al. [2014] introduce coinduction in a system of session types based on Intuitionistic Linear Logic (ILL); see Lindley and Morris [2016, ğğ1, 7] for a comparison. Derakhshan and Pfenning [2020] give a linear metalogic with least and greatest fixed point and prove strong progress for binary session-typed processes in the metalogic.

Manifest sharing. Closely related to our work is the notion of manifest sharing [Balzer and Pfenning 2017]. This work starts from a very different premise: a channel is either linear (as the usual channels in session types), or shared between processes. This leads to an ILL-based system, SILL𝑆, with two modes and two modalities shifting between them [Reed 2009]. The switch to a shared channel is punctuated by the modalities. Thus, sharing manifests in the types. In some ways, SILL𝑆is a much stronger system, as it features equi-synchronizing recursive session types. The price to pay is the introduction of deadlock. Balzer et al. [2019] develop an additional layer of the type system that protects from it.

Our work attempts to solve the expressivity problem of LL-based session types beginning from Curry-Howard: we seek the minimal extension to Linear Logic that will enable us to write server and client processes. Unlike manifest sharing, we remain committed to CLL and its duality. The result is that our system has simpler rules, avoiding the notions of linear and shared channels (as (co)exponentials internalise them), as well as the lock-like primitives used to introduce modalities by Balzer and Pfenning [2017]. Moreover, we have remained committed to the goal of retaining the good properties ensured by cut elimination in CLL (e.g. deadlock freedom). A drawback of this approach is that our system inherits the linearity constraint from linear logic, and is thus unable to express circular structures (such as Dijkstra's dining philosophers).

Both systems provide atomicity, but in radically different ways. In SILL𝑆users access the service in a mutually exclusive manner. This is not compatible with the usual view of typical client-server interaction, where mutliple clients need to access the server simultanously in order to exchange information. A common workaround is to decompose an interleaved session into a 'stateless' protocol consisting of several mini-sessions (such as the voting examples in Das et al. [2021] and Sano et al. [2021]). Every client is then required to send a 'cookie' to identify themselves across mini-sessions. In our system accesses to the shared service are concurrent, but causally atomic (ğ4). As a result, interleaved sessions can be expressed natively (ğ5.5).

Type systems for the 𝜋-calculus. There are many ways to equip the 𝜋-calculus with a type system. A large class of such systems is based on Kobayashi's notion of channel usage [Kobayashi 2002, 2003, 2006]. That work proceeds in the opposite direction: it begins with the 𝜋-calculus, and tries

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.


<!-- page 27 -->

Client-Server Sessions in Linear Logic 62:27

to tame its expressive power through types that control the use of channels, thereby guaranteeing deadlock-freedom, lock-freedom, and so on. These systems are able to express some of the expected properties of client-server interaction, see e.g. Kobayashi [2003, Example 8]. Comparing these families of type systems for concurrent behaviour is a difficult task which has been undertaken by Dardha and Pérez [2015]. The main difference seems to be that our work tries to stick as closely as possible to the foundations of session types in linear logic. In addition, the usage-based type systems take a 'channel-first' approach, where all channels may be shared between processes; this is in sharp contrast to session types [Kobayashi 2003, ğ10]. Dardha and Gay [2018] have attempted to merge these two approaches through the formulation of Priority-based CP, a new calculus based on CLL which allows a controlled form of cyclic dependencies.

Session Types. There is a nontrivial connection between our work and Multiparty Session Types [Coppo et al. 2016; Honda et al. 2008, 2016], which comprise a 𝜋-calculus and a behavioural type system specifying interaction between multiple agents. The kinds of protocols expressed by multiparty session types are 'fully' choreographed, and involve a fixed number of participants. As such, they cannot model interactions with an arbitrary number of clients; nor can they introduce a controlled amount of nondeterminism. Some of these expressive limitations have been remedied in systems of Dynamic Multirole Session Types [Deniélou and Yoshida 2011], which come at the price of introducing roles that parties can dynamically join or leave, and a notion of quantification over participants with a role. Our system captures certain use-cases of roles using only tools from linear logic, with little additional complexity.

## 7 CONCLUSIONS AND FURTHER WORK

We presented the system of Client-Server Linear Logic, which features a novel form of modality, the coexponentials. We then showed how CSLL can be used to model client-server interactions without falling down the slippery slope of introducing Mix. We comment on some directions for future work.

Termination. It would be interesting to establish a termination result for CSLL. This would prove that the resulting calculus does not generate livelock. We expect this proof to be somewhat involved, which is why most work on Linear Logic and session types either fails to produce a proof, or defers to Girard's proof for CLL [Aschieri and Genco 2019; Wadler 2014].

Syntax. The weak ¡ rule listed in ğ2.2 is expressed by folding ⊗over the set of formulas. This obstructs a particular commuting conversion in cut elimination. Similarly, presentation of the strong exponential and its computational interpretation is omitted due to its unsatisfactory rules. We believe these issues are due to the limitation of sequent calculus, and alternative techniques are necessary to solve them.

ACKNOWLEDGEMENTS

Alex Kavvos was supported in part by a research grant (no. 12386, Guarded Homotopy Type Theory) from the VILLUM Foundation. This work was also supported in part by a Villum Investigator grant (no. 25804, Center for Basic Research in Program Verification (CPV)). We gratefully acknowledge discussions with Martin Berger, Amar Hadzihasanovic, Vladimir Zamdzhiev, Wen Kokke, Fabrizio Montesi and Marco Peressotti.

REFERENCES

Samson Abramsky. 1993a. Computational interpretations of linear logic. Theoretical Computer Science 111, 1-2 (1993), 3ś57.

https://doi.org/10.1016/0304-3975(93)90181-R ISBN: 0304-3975.

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.


<!-- page 28 -->

62:28 Zesen Qian, G. A. Kavvos, and Lars Birkedal

Samson Abramsky. 1993b. Interaction categories. In Theory and Formal Methods 1993. Springer, 57ś69. https://doi.org/10.

1007/978-1-4471-3503-6_5 Samson Abramsky. 1994. Proofs as processes. Theoretical Computer Science 135, 1 (1994), 5ś9. https://doi.org/10.1016/0304-

3975(94)00103-0 Samson Abramsky, Simon J Gay, and Rajagopal Nagarajan. 1996. Interaction categories and the foundations of typed

concurrent programming. In Deductive Program Design (Nato ASI Subseries F), Manfred Broy (Ed.). Springer-Verlag Berlin Heidelberg, 35ś113. http://www.springer.com/us/book/9783540609476 Samson Abramsky and Radha Jagadeesan. 1994. Games and Full Completeness for Multiplicative Linear Logic. The Journal

of Symbolic Logic 59, 2 (1994), 543. https://doi.org/10.2307/2275407 arXiv:1311.6057 Umut A Acar. 2016. Parallel Computing: Theory and Practice. http://www.cs.cmu.edu/afs/cs/academic/class/15210-f15/

www/tapp.html Federico Aschieri and Francesco A. Genco. 2019. Par Means Parallel: Multiplicative Linear Logic Proofs as Concurrent

Functional Programs. Proc. ACM Program. Lang. 4, POPL, Article 18 (Dec. 2019), 28 pages. https://doi.org/10.1145/3371086 Robert Atkey. 2017. Observed communication semantics for classical processes. In European Symposium on Programming.

Springer, 56ś82. https://doi.org/10.1007/978-3-662-54434-1_3 Robert Atkey, Sam Lindley, and J. Garrett Morris. 2016. Conflation Confers Concurrency. In A List of Successes That Can

Change the World, Sam Lindley, Conor McBride, Phil Trinder, and Don Sannella (Eds.). Lecture Notes in Computer Science, Vol. 9600. Springer International Publishing, 32ś55. https://doi.org/10.1007/978-3-319-30936-1_2 Arnon Avron. 1991. Hypersequents, logical consequence and intermediate logics for concurrency. Annals of Mathematics

and Artificial Intelligence 4, 3-4 (1991), 225ś248. https://doi.org/10.1007/BF01531058 David Baelde. 2012. Least and greatest fixed points in linear logic. ACM Transactions on Computational Logic 13, 1 (2012),

1ś44. https://doi.org/10.1145/2071368.2071370 Stephanie Balzer and Frank Pfenning. 2017. Manifest sharing with session types. Proceedings of the ACM on Programming

Languages 1, ICFP (2017), 1ś29. https://doi.org/10.1145/3110281 Stephanie Balzer, Bernardo Toninho, and Frank Pfenning. 2019. Manifest Deadlock-Freedom for Shared Session Types. In

Programming Languages and Systems, Luís Caires (Ed.), Vol. 11423. Springer International Publishing, Cham, 611ś639. https://doi.org/10.1007/978-3-030-17184-1_22 Michael Barr. 1991. ∗-Autonomous categories and linear logic. Mathematical Structures in Computer Science 1, 2 (1991),

159ś178. https://doi.org/10.1017/S0960129500001274 Gianluigi Bellin. 1997. Subnets of proof-nets in multiplicative linear logic with MIX. Mathematical Structures in Computer

Science 7, 6 (1997), 663ś669. https://doi.org/10.1017/S0960129597002326 G. Bellin and P. J. Scott. 1994. On the 𝜋-calculus and linear logic. Theoretical Computer Science 135, 1 (1994), 11ś65.

https://doi.org/10.1016/0304-3975(94)00104-9 N Benton and P Wadler. 1996. Linear logic, monads and the lambda calculus. In Proceedings 11th Annual IEEE Symposium on

Logic in Computer Science. IEEE. https://doi.org/10.1109/LICS.1996.561458 Robert D. Blumofe, Christopher F. Joerg, Bradley C. Kuszmaul, Charles E. Leiserson, Keith H. Randall, and Yuli Zhou. 1995.

Cilk: An Efficient Multithreaded Runtime System. In Proceedings of the Fifth ACM SIGPLAN Symposium on Principles and Practice of Parallel Programming (Santa Barbara, California, USA) (PPOPP '95). Association for Computing Machinery, New York, NY, USA, 207ś216. https://doi.org/10.1145/209936.209958 Luís Caires and Jorge A. Pérez. 2017. Linearity, Control Effects, and Behavioral Types. In Programming Languages and Systems.

ESOP 2017, Hongseok Yang (Ed.). Springer Berlin Heidelberg, Berlin, Heidelberg, 229ś259. https://doi.org/10.1007/978-3662-54434-1_9 Luís Caires and Frank Pfenning. 2010. Session Types as Intuitionistic Linear Propositions. In Proceedings of the 21st

International Conference on Concurrency Theory (Paris, France) (CONCUR'10). Springer-Verlag, Berlin, Heidelberg, 222ś236. https://doi.org/10.5555/1887654.1887670 Luís Caires, Frank Pfenning, and Bernardo Toninho. 2016. Linear logic propositions as session types. Mathematical Structures

in Computer Science 26, 3 (2016), 367ś423. https://doi.org/10.1017/S0960129514000218 Marco Carbone, Sam Lindley, Fabrizio Montesi, Carsten Schürmann, and Philip Wadler. 2016. Coherence Generalises

Duality: A Logical Explanation of Multiparty Session Types. In 27th International Conference on Concurrency Theory (CONCUR 2016) (Leibniz International Proceedings in Informatics (LIPIcs), Vol. 59), Josée Desharnais and Radha Jagadeesan (Eds.). Schloss DagstuhlśLeibniz-Zentrum fuer Informatik, Dagstuhl, Germany, 33:1ś33:15. https://doi.org/10.4230/ LIPIcs.CONCUR.2016.33 Marco Carbone, Fabrizio Montesi, Carsten Schürmann, and Nobuko Yoshida. 2017. Multiparty session types as coherence

proofs. Acta Informatica 54 (2017), 243ś269. https://doi.org/10.1007/s00236-016-0285-y Simon Castellan, Léo Stefanesco, and Nobuko Yoshida. 2020. Game Semantics: Easy as Pi. CoRR abs/2011.05248 (2020).

arXiv:2011.05248

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.


<!-- page 29 -->

Client-Server Sessions in Linear Logic 62:29

Melvin E. Conway. 1963. A Multiprocessor System Design (AFIPS '63 (Fall)). Association for Computing Machinery, New

York, NY, USA, 139ś146. https://doi.org/10.1145/1463822.1463838 Mario Coppo, Mariangiola Dezani-Ciancaglini, Nobuko Yoshida, and Luca Padovani. 2016. Global progress for dynamically

interleaved multiparty sessions. Mathematical Structures in Computer Science 26, 2 (2016), 238ś302. https://doi.org/10. 1017/S0960129514000188 L. Dagum and R. Menon. 1998. OpenMP: an industry standard API for shared-memory programming. IEEE Computational

Science and Engineering 5, 1 (1998), 46ś55. https://doi.org/10.1109/99.660313 Ornela Dardha and Simon J. Gay. 2018. A New Linear Logic for Deadlock-Free Session-Typed Processes. In Foundations

of Software Science and Computation Structures (Lecture Notes in Computer Science, Vol. 10803), Christel Baier and Ugo Dal Lago (Eds.). Springer International Publishing, Cham, 91ś109. https://doi.org/10.1007/978-3-319-89366-2{_}5 Ornela Dardha and Jorge A. Pérez. 2015. Comparing Deadlock-Free Session Typed Processes. Electronic Proceedings in

Theoretical Computer Science 190 (2015). https://doi.org/10.4204/EPTCS.190.1 A. Das, S. Balzer, J. Hoffmann, F. Pfenning, and I. Santurkar. 2021. Resource-Aware Session Types for Digital Contracts.

In 2021 IEEE 34th Computer Security Foundations Symposium (CSF). IEEE Computer Society, Los Alamitos, CA, USA, 111ś126. https://doi.org/10.1109/CSF51468.2021.00004 Pierre-Malo Deniélou and Nobuko Yoshida. 2011. Dynamic Multirole Session Types. In Proceedings of the 38th Annual ACM

SIGPLAN-SIGACT Symposium on Principles of Programming Languages - POPL '11. ACM Press, 435. https://doi.org/10. 1145/1926385.1926435 Farzaneh Derakhshan and Frank Pfenning. 2020. Circular Proofs in First-Order Linear Logic with Least and Greatest Fixed

Points. (2020). arXiv:2001.05132 Thomas Ehrhard. 2018. An introduction to differential linear logic: proof-nets, models and antiderivatives. Mathematical

Structures in Computer Science 28, 7 (2018), 995ś1060. https://doi.org/10.1017/S0960129516000372 Thomas Ehrhard and Farzad Jafarrahmani. 2021. Categorical models of Linear Logic with fixed points of formulas.

arXiv:2011.10209 To appear in the proceedings of LICS 2021. Thomas Ehrhard and Olivier Laurent. 2010. Interpreting a finitary pi-calculus in differential interaction nets. Information

and Computation 208, 6 (2010), 606ś633. https://doi.org/10.1016/j.ic.2009.06.005 Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova. 2019. Exceptional asynchronous session types: session

types without tiers. Proceedings of the ACM on Programming Languages 3, POPL (2019). https://doi.org/10.1145/3290341 Simon J Gay and Vasco T Vasconcelos. 2010. Linear type theory for asynchronous session types. Journal of Functional

Programming 20, 1 (2010), 19. https://doi.org/10.1017/S0956796809990268 Jean-Yves Girard. 1987. Linear logic. Theoretical Computer Science 50, 1 (1987), 1ś101. https://doi.org/10.1016/03043975(87)90045-4 J. Y. Girard and Y. Lafont. 1987. Linear logic and lazy computation. In TAPSOFT '87 (Lecture Notes in Computer Science,

Vol. 250), Hartmut Ehrig, Robert Kowalski, Giorgio Levi, and Ugo Montanari (Eds.). Springer-Verlag, Berlin/Heidelberg, 52ś66. https://doi.org/10.1007/BFb0014972 Jean-Yves Girard, Andre Scedrov, and Philip J. Scott. 1992. Bounded linear logic: a modular approach to polynomial-time

computability. Theoretical Computer Science 97, 1 (1992), 1ś66. https://doi.org/10.1016/0304-3975(92)90386-T Robert H. Halstead. 1984. Implementation of Multilisp: Lisp on a Multiprocessor. In Proceedings of the 1984 ACM Symposium

on LISP and Functional Programming (Austin, Texas, USA) (LFP '84). Association for Computing Machinery, New York, NY, USA, 9ś17. https://doi.org/10.1145/800055.802017 Maurice Herlihy and Nir Shavit. 2012. The Art of Multiprocessor Programming (revised first ed.). Morgan Kaufmann.

https://doi.org/10.5555/2385452 Kohei Honda, Vasco T Vasconcelos, and Makoto Kubo. 1998. Language primitives and type discipline for structured

communication-based programming. In Programming Languages and Systems: Proceedings of the 7th European Symposium on Programming (ESOP'98) (Lecture Notes in Computer Science, Vol. 1381). Springer, Berlin, Heidelberg, 122ś138. https: //doi.org/10.1007/BFb0053567 Kohei Honda, Nobuko Yoshida, and Marco Carbone. 2008. Multiparty Asynchronous Session Types. In Proceedings of

the 35th Annual ACM SIGPLAN-SIGACT Symposium on Principles of Programming Languages. ACM Press. https: //doi.org/10.1145/1328438.1328472 Kohei Honda, Nobuko Yoshida, and Marco Carbone. 2016. Multiparty Asynchronous Session Types. J. ACM 63, 1 (2016),

1ś67. https://doi.org/10.1145/2827695 John Maynard Keynes. 1936. The General Theory of Employment, Interest and Money. Macmillan & Co. Ltd., London. Naoki Kobayashi. 2002. A Type System for Lock-Free Processes. Information and Computation 177, 2 (2002), 122ś159.

https://doi.org/10.1006/inco.2002.3171 Naoki Kobayashi. 2003. Type Systems for Concurrent Programs. In Formal Methods at the Crossroads. From Panacea to

Foundational Support (Lecture Notes in Computer Science, Vol. 2757), Bernhard K. Aichernig and Tom Maibaum (Eds.). Springer Berlin Heidelberg, Berlin, Heidelberg, 439ś453. https://doi.org/10.1007/978-3-540-40007-3_26 Extended version.

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.


<!-- page 30 -->

62:30 Zesen Qian, G. A. Kavvos, and Lars Birkedal

Naoki Kobayashi. 2006. A new type system for deadlock-free processes. In International Conference on Concurrency Theory.

Springer, 233ś247. https://doi.org/10.1007/11817949_16 Wen Kokke, Fabrizio Montesi, and Marco Peressotti. 2018. Taking Linear Logic Apart. In Proceedings Joint International

Workshop on Linearity & Trends in Linear Logic and Applications, Linearity-TLLA@FLoC 2018, Oxford, UK, 7-8 July 2018 (EPTCS, Vol. 292), Thomas Ehrhard, Maribel Fernández, Valeria de Paiva, and Lorenzo Tortora de Falco (Eds.). 90ś103. https://doi.org/10.4204/EPTCS.292.5 Wen Kokke, Fabrizio Montesi, and Marco Peressotti. 2019a. Better late than never: a fully-abstract semantics for classical

processes. Proceedings of the ACM on Programming Languages 3, POPL (2019), 1ś29. https://doi.org/10.1145/3290337 Wen Kokke, J Garrett Morris, and Philip Wadler. 2019b. Towards races in linear logic. In International Conference on

Coordination Languages and Models. Springer, 37ś53. https://doi.org/10.1007/978-3-030-22397-7_3 Yves Lafont and Thomas Streicher. 1991. Games semantics for linear logic. In Proceedings 1991 Sixth Annual IEEE Symposium

on Logic in Computer Science. IEEE Computer Society, 43ś44. https://doi.org/10.1109/LICS.1991.151629 Daan Leijen, Wolfram Schulte, and Sebastian Burckhardt. 2009. The design of a task parallel library. Acm Sigplan Notices 44,

10 (2009), 227ś242. https://doi.org/10.1145/1639949.1640106 Sam Lindley and J Garrett Morris. 2015. A semantics for propositions as sessions. In European Symposium on Programming

Languages and Systems. Springer, 560ś584. https://doi.org/10.1007/978-3-662-46669-8_23 Sam Lindley and J. Garrett Morris. 2016. Talking bananas: structural recursion for session types. In Proceedings of the

21st ACM SIGPLAN International Conference on Functional Programming - ICFP 2016. ACM Press, Nara, Japan, 434ś447. https://doi.org/10.1145/2951913.2951921 Sam Lindley and J. Garrett Morris. 2017. Lightweight Functional Session Types. In Behavioural Types: from Theory to Tools,

Simon Gay and Antonio Ravara (Eds.). River Publishers. https://doi.org/10.13052/rp-9788793519817 J. Maraist, M. Odersky, D.N. Turner, and P. Wadler. 1999. Call-by-name, call-by-value, call-by-need and the linear lambda

calculus. Theoretical Computer Science 228, 1-2 (1999), 175ś210. https://doi.org/10.1016/S0304-3975(98)00358-2 John Maraist, Martin Odersky, David N. Turner, and Philip Wadler. 1995. Call-by-name, Call-by-value, Call-by-need, and the

Linear Lambda Calculus. Electronic Notes in Theoretical Computer Science 1 (1995), 370ś392. https://doi.org/10.1016/S15710661(04)00022-2 Damiano Mazza. 2018. The true concurrency of differential interaction nets. Mathematical Structures in Computer Science

28, 7 (2018), 1097ś1125. https://doi.org/10.1017/S0960129516000402 Paul-André Melliès. 2009. Categorical Semantics of Linear Logic. In Panoramas et synthèses 27: Interactive models of

computation and program behaviour, Pierre-Louis Curien, Hugo Herbelin, Jean-Louis Krivine, and Paul-André Melliès (Eds.). Société Mathématique de France. http://www.pps.univ-paris-diderot.fr/~mellies/papers/panorama.pdf Paul-André Melliès, Nicolas Tabareau, and Christine Tasson. 2018. An explicit formula for the free exponential modality of

linear logic. Mathematical Structures in Computer Science 28, 7 (2018). https://doi.org/10.1017/S0960129516000426 Massimo Merro and Davide Sangiorgi. 2004. On asynchrony in name-passing calculi. Mathematical Structures in Computer

Science 14, 5 (2004), 715ś767. https://doi.org/10.1017/S0960129504004323 Robin Milner. 1992. Functions as processes. Mathematical Structures in Computer Science 2, 2 (1992), 119ś141. https: //doi.org/10.1017/S0960129500001407 Robin Milner. 1999. Communicating and Mobile Systems: The 𝜋-calculus. Cambridge University Press, New York, NY, USA.

https://doi.org/10.5555/329902 Robin Milner, Joachim Parrow, and David Walker. 1992. A calculus of mobile processes, i. Information and computation 100,

1 (1992), 1ś40. https://doi.org/10.1016/0890-5401(92)90008-4 Fabrizio Montesi and Marco Peressotti. 2018. Classical Transitions. (2018). arXiv:1803.01049 Jason Reed. 2009. A Judgmental Deconstruction of Modal Logic. (2009). http://www.cs.cmu.edu/~jcreed/papers/jdml.pdf James Reinders. 2007. Intel threading building blocks: outfitting C++ for multi-core processor parallelism. " O'Reilly Media,

Inc.". Pedro Rocha and Luís Caires. 2021. Propositions-as-Types and Shared State. Proceedings of the ACM on Programming

Languages ICFP (2021). https://doi.org/10.1145/3473584 Chuta Sano, Stephanie Balzer, and Frank Pfenning. 2021. Manifestly Phased Communication via Shared Session Types.

CoRR abs/2101.06249 (2021). arXiv:2101.06249 https://arxiv.org/abs/2101.06249 Bernardo Toninho, Luís Caires, and Frank Pfenning. 2014. Corecursion and non-divergence in session-typed processes. In

International Symposium on Trustworthy Global Computing. Springer, 159ś175. https://doi.org/10.1007/978-3-662-459171_11 Maarten van Steen and Andrew S. Tanenbaum. 2017. Distributed Systems (3 ed.). distributed-systems.net. https://www.

distributed-systems.net/ Vasco T. Vasconcelos. 2012. Fundamentals of session types. Information and Computation 217 (2012), 52ś70. https: //doi.org/10.1016/j.ic.2012.05.002

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.


<!-- page 31 -->

Client-Server Sessions in Linear Logic 62:31

Philip Wadler. 2014. Propositions as sessions. Journal of Functional Programming 24, 2-3 (2014), 384ś418. https://doi.org/10.

1017/S095679681400001X

Proc. ACM Program. Lang., Vol. 5, No. ICFP, Article 62. Publication date: August 2021.
