# Session Types with Arithmetic Refinements

> **Machine-generated Markdown conversion — this is a MODIFIED version.**
> Converted from `CONCUR20-session-types-with-arithmetic-refinements.pdf` with PyMuPDF. Layout, mathematics,
> figures and tables are lossy; **quote from the PDF, not from this file.**
> Page anchors below correspond to PDF pages.

- **Citation:** Ankush Das, Frank Pfenning. CONCUR 2020. doi:10.4230/LIPIcs.CONCUR.2020.13
- **Licence:** CC-BY (LIPIcs)
- **Source:** https://drops.dagstuhl.de/entities/document/10.4230/LIPIcs.CONCUR.2020.13

---


<!-- page 1 -->

## Session Types with Arithmetic Refinements

## Ankush Das

Carnegie Mellon University, Pittsburgh, PA, USA http://www.cs.cmu.edu/~ankushd ankushd@cs.cmu.edu Frank Pfenning Carnegie Mellon University, Pittsburgh, PA, USA http://www.cs.cmu.edu/~fp fp@cs.cmu.edu

## Abstract

Session types statically prescribe bidirectional communication protocols for message-passing processes. However, simple session types cannot specify properties beyond the type of exchanged messages. In this paper we extend the type system by using index refinements from linear arithmetic capturing intrinsic attributes of data structures and algorithms. We show that, despite the decidability of Presburger arithmetic, type equality and therefore also subtyping and type checking are now undecidable, which stands in contrast to analogous dependent refinement type systems from functional languages. We also present a practical, but incomplete algorithm for type equality, which we have used in our implementation of Rast, a concurrent session-typed language with arithmetic index refinements as well as ergometric and temporal types. Moreover, if necessary, the programmer can propose additional type bisimulations that are smoothly integrated into the type equality algorithm.

## 2012 ACM

Subject Classification Theory of computation →Process calculi; Theory of computation →Linear logic; Theory of computation →Logic and verification; Computing methodologies → Concurrent programming languages; Theory of computation →Type theory

## Keywords and phrases Session Types, Refinement Types, Type Equality

## Digital Object Identifier 10.4230/LIPIcs.CONCUR.2020.13

Funding Ankush Das: funded by the National Science Foundation under SaTC Award 1801369 and CAREER Award 1845514. Frank Pfenning: funded by the National Science Foundation under Grant No. 1718276.

## 1 Introduction

Session types [24, 42] provide a structured way of prescribing communication protocols in message-passing systems. This paper focuses on binary session types governing the interactions along channels with two endpoints. They arise either directly as part of a program notation [25], or as the result of endpoint projection of multi-party session types [26] and are thus of central importance in the study of message-passing concurrency. Moreover, a Curry-Howard correspondence relates propositions of linear logic to session types [8, 43, 9], further evidence for their fundamental nature.

Once recursion is introduced for session types as well as processes, we are confronted with the question as to what is the correct notion of type equality since its use in type checking is inescapable. Gay and Hole [17] convincingly answer this question and also provide a practical algorithm for subtyping (which implies an algorithm for type equality). First, since the endpoints of channels need to agree on a type (or possibly two dual types) for communication, recursive types should be a priori structural rather than nominal. Second, types should be equal if their observable communication behaviors are indistinguishable. This means that two types should be equal if there is a bisimulation between them. This is particularly elegant since the definition is independent of any particular programming

© Ankush Das and Frank Pfenning; licensed under Creative Commons License CC-BY 31st International Conference on Concurrency Theory (CONCUR 2020). Editors: Igor Konnov and Laura Kovács; Article No. 13; pp. 13:1-13:18

Leibniz International Proceedings in Informatics Schloss Dagstuhl - Leibniz-Zentrum für Informatik, Dagstuhl Publishing, Germany


<!-- page 2 -->

## 13:2

Session Types with Arithmetic Refinements

language in which session types are embedded, or whether they are checked statically or dynamically. The algorithm for type equality then constructs a bisimulation. It terminates because the number of pairs of types that might be related by the bisimulation is finite.

Like any type system, basic session types are limited in the kind of properties they can express, which has led to some generalizations such as polymorphic [43, 7, 21] and context-free [38] session types, each with its own questions for type equality. In this paper we propose a natural linear arithmetic refinement of session types, which allows us to capture a number of significant properties of message-passing communication such as size or value of data structures, number of messages exchanged or delay in those messages. In conception, this refinement is closely related to indexed types or value-dependent types familiar from functional languages [47, 46, 37], where the indices are arithmetic expressions.

To our surprise, despite an eminently decidable index domain, the type equality problem becomes undecidable. We show this via a reduction from the non-halting problem for two-counter machines [30]. Analyzing this reduction in detail shows that the problem is already undecidable for a single type constructor (pick either internal (⊕) or external (&) choice, in addition to arithmetic refinements). While our type system is equirecursive to aid in the simplicity of programming, even retreating to isorecursive types leaves the problem undecidable. Finally, one may be tempted to blame the quantifiers in Presburger arithmetic, but our reduction shows that even if we restrict ourselves to linear arithmetic with universal prefix quantification only, type equality remains undecidable.

A retrenchment to a nominal interpretation of recursive types would rule out too many programs and complicate communications, so we develop a sound but incomplete algorithm. Our experience with the Rast implementation [14] to date shows that it is effective in practice (see Section 6 for further discussion).

Most closely related is the design of LiquidPi [22], but it refines only basic data types such as int rather than equirecursively defined session types. The resulting system has a decidable subtyping problem and even type inference (under reasonable assumptions on the constraint domain), but it cannot express many of our motivating examples. Along similar lines, refinements of basic data types together with subtyping have been proposed for runtime monitoring of binary session-typed communication [20, 19]. Label-dependent session

types [39] also support types indexed by natural numbers using a fixed schema of iteration with a particular unfolding equality, rather than arbitrary recursion and bisimulation. Zhou et al. [49, 48] refine base types with arithmetic expressions in the context of multiparty session types without recursive types. In this simpler setting, they obtain a decidable notion of type equality. Further related work can be found in Section 7.

## 2 Basic Session Types

We review the basic language of binary session types. We take the intuitionistic point of view [8, 9], since our experiments and motivating examples have been carried out in Rast [14]. Changes for a classical view [43] are minimal and do not affect our results or algorithms. We would add a type ⊥dual to 1, and replace the ⊸operator with ` with only minor changes to the remainder of the development.

A, B, C ::= ⊕{ℓ: Aℓ}ℓ∈L send label k ∈L continue at type Ak | &{ℓ: Aℓ}ℓ∈L receive label k ∈L continue at type Ak | A ⊗B send channel a : A continue at type B | A ⊸B receive channel a : A continue at type B | 1 send close message no continuation | V defined type variable


<!-- page 3 -->

## A. Das and F. Pfenning

13:3

We provide a brief description of the operational behavior of the types from the provider's

point of view. The provider of ⊕{ℓ: Aℓ}ℓ∈L sends a label k ∈L and continues to provide Ak. Dually, the provider of &{ℓ: Aℓ}ℓ∈L receives one of the labels in L. The provider of A ⊗B sends a channel of type A and continues to provide B, whereas the process providing A ⊸B receives a channel of type A and provides B. Finally, the provider of 1 sends a close message and terminates.

We assume that labels ℓ∈L (for a finite, nonempty set L) and close messages can be observed, but the identity of channels can not. Instead any communication along channels that are sent and received can be observed in turn. Based on this notion, we adopt type bisimulations from Gay and Hole [17]. Rather than an explicit recursive type constructor µ we postulate a signature Σ with definitions of type variables V .

Signature Σ ::= · | Σ, V = A

In a valid signature all definitions V = A are contractive, that is, A is not itself a type variable. This allows us to take an equirecursive view of type definitions, which means that unfolding a type definition does not require communication. We can easily adapt our definitions to an isorecursive view [28, 15] with explicit unfold messages (see the remark at the end of Section 4). All type variables V occurring in a valid signature may refer to each other and must be defined, and all type variables defined in a signature must be distinct.

▶Definition 1. We define unfoldΣ(V ) = A if V = A ∈Σ and unfoldΣ(A) = A otherwise.

▶Definition 2. A relation R on types is a type bisimulation if (A, B) ∈R implies that for S = unfoldΣ(A), T = unfoldΣ(B) we have

If S = ⊕{ℓ: Aℓ}ℓ∈L then T = ⊕{ℓ: Bℓ}ℓ∈L and (Aℓ, Bℓ) ∈R for all ℓ∈L. If S = &{ℓ: Aℓ}ℓ∈L then T = &{ℓ: Bℓ}ℓ∈L and (Aℓ, Bℓ) ∈R for all ℓ∈L. If S = A1 ⊗A2, then T = B1 ⊗B2 and (A1, B1) ∈R and (A2, B2) ∈R. If S = A1 ⊸A2, then T = B1 ⊸B2 and (A1, B1) ∈R and (A2, B2) ∈R. If S = 1 then T = 1.

▶Definition 3. We say that A is equal to B, written A ≡B, if there is a type bisimulation R such that (A, B) ∈R.

As two simple running examples we use an interface to a queue and the representation of binary numbers as sequences of bits.

▶Example 4 (Queues, v1). A queue provider offers a choice (indicated by &) of either receiving an ins label followed by a channel of type A (denoted by ⊸) to insert into the queue, or a del label to delete an element from the queue. In the latter case, the queue provider has a choice (indicated by ⊕) of either responding with the label none (if there is no element in the queue) and closes the channel (indicated by 1), or the label some followed by an element of type A (denoted by ⊗) and recurses to await the next round of interactions. We view queueA as a family of types, one for each A, to avoid introducing explicit polymorphic type constructors.

## queueA = &{ins : A ⊸queueA,

## del : ⊕{none : 1,

## some : A ⊗queueA}}

▶Example 5 (Binary Numbers, v1). A process representing a binary number either sends a label e representing the number 0 and closes the channel, or one of the labels b0 (bit 0) or b1 (bit 1) followed by remaining bits (by recursing). We assume a "little endian" form, that is, the least significant bit is sent first.

## CONCUR 2020


<!-- page 4 -->

## 13:4

Session Types with Arithmetic Refinements

## bin = ⊕{b0 : bin, b1 : bin, e : 1}

As examples of message sequences along a fixed channel, we would have

e ; close representing 0 b0 ; e ; close also representing 0 b0 ; b1 ; e ; close representing 2 b1 ; b0 ; b1 ; b1 ; e ; close representing 13

## 3 Arithmetic Refinements

Before we extend our language of types formally, we revisit the examples in order to motivate the specific constructs available. We write V [e] for a type indexed by a sequence of arithmetic expressions e. Since it has been appropriate for most of our examples, we restrict ourselves to natural numbers rather than arbitrary integers.

▶Example 6 (Queues, v2). The provider of a queue should be constrained to answer none exactly if the queue contains no elements and some if it is nonempty. The queue type from Example 4 does not express this. This means a client may need to have some redundant branches to account for responses that should be impossible. We now define the type queueA[n] to stand for a queue with exactly n elements.

## queueA[n] = &{ins : A ⊸queueA[n + 1],

## del : ⊕{none : ?{n = 0}. 1,

## some : ?{n > 0}. A ⊗queueA[n −1]}}

The first branch is easy to understand: if we add an element to a queue of length n, it subsequently contains n + 1 elements. In the second branch we constrain the arithmetic variable n to be equal to 0 if the provider sends none and positive if the provider sends some. In the latter case, we subtract one from the length after an element has been dequeued.

Conceptually, the type ?{φ}. A means that the provider must send a proof p of φ, so it corresponds to ∃p : φ. A. A characteristic of type refinement, in contrast to fully dependent types, is that the computation of A and thus, the execution of processes can only depend on the existence of a proof, but not on its form (known in type theory as proof irrelevance). More concretely, the process types and terms cannot refer to the proof p. This irrelevance property combined with the decidability of our index domain means that no actual proof needs to be sent (since one can be constructed from φ automatically, if needed), just a token asserting its existence. There is also a dual constructor !{φ}. A that licenses the assumption

of φ, which, conceptually, corresponds to receiving a proof of φ.

▶Example 7 (Binary Numbers, v2). The indexed type bin[n] should represent a binary number with value n. Because the least significant bit comes first, we expect, for example, that bin[n] = ⊕{b0 : ?{2 | n}. bin[n/2], . . .} (a | b denotes a divides b). However, while divisibility is available in Presburger arithmetic, division itself is not; instead, we can express the constraint and the index of the recursive occurrence using quantification.

## bin[n] = ⊕{b0 : ∃k. ?{n = 2 ∗k}. bin[k],

## b1 : ∃k. ?{n = 2 ∗k + 1}. bin[k],

e : ?{n = 0}. 1}

As a further refinement, we could rule out leading zeros by adding the constraint n > 0 in the branch for b0 (in branch b1, n = 2k + 1 implies n > 0 so the constraint implicitly holds).


<!-- page 5 -->

The type ∃n. A means that the provider must send a natural number i and proceed at type A[i/n], corresponding to existential quantification in arithmetic. The dual universal quantifier ∀n. A requires the provider to receive a number i and proceed at type A[i/n].

We now extend our definitions to account for these new constructs. Below, i represents a constant, n is a natural number variable and (i | e) means i divides e.

Types A ::= . . . | ?{φ}. A assert φ continue at type A | !{φ}. A assume φ continue at type A | ∃n. A send number i continue at type A[i/n] | ∀n. A receive number i continue at type A[i/n] | V [e] variable instantiation

Arith. Expressions e ::= i | e + e | e −e | i × e | (i | e) | n

Arith. Propositions φ ::= e = e | e > e | ⊤| ⊥| φ ∧φ | φ ∨φ | ¬φ | ∃n. φ | ∀n. φ

Signature Σ ::= · | Σ, V [n | φ] = A

An indexed type definition V [n | φ] = A containing an optional proposition φ requires every instantiation e (in V [e]) of the sequence of variables n to satisfy φ[e/n]. This is verified statically when a type signature is checked for validity, as defined below. We use V for a collection of arithmetic variables and C (to signify constraints) for an arithmetic proposition occurring among the antecedents of a judgment. We then have the following rules defining the validity of signatures (⊢Σ signature), declarations (⊢Σ Σ′ valid), and types (V ; C ⊢Σ A valid) where V is a collection of arithmetic variables including all free variables

in constraint C and type A. We silently rename variables so that n does not already occur in V in the ∃V and ∀V rules. We also call upon the semantic entailment judgment V ; C ⊨φ which means that ∀V. C ⊃φ holds in arithmetic and ⊨φ abbreviates · ; ⊤⊨φ.

⊢Σ Σ valid ⊢Σ signature ⊢Σ (·) valid

V ; C ∧φ ⊢Σ A valid V ; C ⊢Σ ?{φ}. A valid ?V V ; C ∧φ ⊢Σ A valid V ; C ⊢Σ !{φ}. A valid !V

V, n ; C ⊢Σ A valid V ; C ⊢Σ ∃n. A valid ∃V n V, n ; C ⊢Σ A valid V ; C ⊢Σ ∀n. A valid ∀V n

V [n | φ] = A ∈Σ V ; C ⊨φ[e/n] V ; C ⊢Σ V [e] valid tdef

We elide the compositional rules for all the other type constructors. Since we like to work

over natural numbers rather than integers, it is convenient to assume that every definition V [n] = A abbreviates V [n | n ≥0] = A. This means that in valid signatures every occurrence V [e] is such that e ≥0 follows from the known constraints.

## ▶Example 8. The declaration

## queueA[n] = &{ins : A ⊸queueA[n + 1],

## del : ⊕{none : ?{n = 0}. 1,

is valid: in the ins branch, we verify (n ; n ≥0 ⊨n + 1 ≥0) while checking validity of queueA[n + 1] with rule tdef; in the some branch, we add n > 0 to our constraint C (due to rule ?V ) and verify (n ; n ≥0 ∧n > 0 ⊨n −1 ≥0) while checking validity of queueA[n −1].

## A. Das and F. Pfenning

13:5

⊢Σ Σ′ valid n ; φ ⊢Σ A valid A̸ = V ′[e′] ⊢Σ Σ′, V [n | φ] = A valid

## some : ?{n > 0}. A ⊗queueA[n −1]}}

## CONCUR 2020


<!-- page 6 -->

## 13:6

Session Types with Arithmetic Refinements

Unfolding a definition must now substitute for the arithmetic variables we abstract over.

▶Definition 9. unfoldΣ(V [e]) = A[e/n] if V [n | φ] = A ∈Σ and unfoldΣ(A) = A otherwise.

We say that a type is closed if it contains no free arithmetic variables n.

▶Definition 10. A relation R on closed valid types is a type bisimulation if (A, B) ∈R implies that for S = unfoldΣ(A), T = unfoldΣ(B) we have the following conditions (in addition to those of Definition 2):

If S = ?{φ}. A′ then T = ?{ψ}. B′ and either (i) ⊨φ, ⊨ψ, and (A′, B′) ∈R, or (ii) ⊨¬φ and ⊨¬ψ.

If S = !{φ}. A′ then T = !{ψ}. B′ and either (i) ⊨φ, ⊨ψ, and (A′, B′) ∈R, or (ii) ⊨¬φ and ⊨¬ψ

If S = ∃m. A′ then T = ∃n. B′ and for all i ∈N, (A′[i/m], B′[i/n]) ∈R. If S = ∀m. A′ then T = ∀n. B′ and for all i ∈N, (A′[i/m], B′[i/n]) ∈R. We also extend the notation A ≡B to this richer set of types.

An interesting point here is provided by the cases (ii) in the first two clauses. Because the type must be closed, we know that φ and ψ will be either true or false. If both are false, no messages can be sent along a channel of either type and therefore the continuation types A′ and B′ are irrelevant when considering type equality.

Fundamentally, due to the presence of arbitrary recursion and therefore non-termination, we always view a type as a restriction of what a process might send or receive along some channel, but it is neither required to send a message nor guaranteed to receive one. This is similar to functional programming with unrestricted recursion where an expression may not return a value. The definition based on observability of messages is then somewhat strict, as exemplified by the next example.

## ▶Example 11. Consider the following two types

bin[n] = ⊕{b0 : ∃k. ?{n = 2 ∗k}. bin[k], zero = ⊕{b0 : ∃k. ?{k = 0}. zero, b1 : ∃k. ?{n = 2 ∗k + 1}. bin[k], e : ?{0 = 0}. 1} e : ?{n = 0}. 1}

We might expect bin[0] ≡zero, but this is not so. A process of type bin[0] could send the

label b1 and an arbitrary value for k and then just loop forever (because there is no proof of 0 = 2k + 1). The type zero cannot exhibit this behavior so the types are not equivalent.

In our implementation, missing branches for a choice in process definitions are reconstructed with a continuation that marks it as impossible, which is then verified by the type checker. We found this simple technique significantly limited the need for subtyping or explicit definition of types such as zero - instead, we just work with bin[0].

The following properties of type equality are straightforward.

▶Lemma 12 (Properties of Type Equality). The relation ≡is reflexive, symmetric, transitive and a congruence on closed valid types.

## 4 Undecidability of Type Equality

We prove the undecidability of type equality by exhibiting a reduction from an undecidable

problem about two counter machines.

The type system allows us to simulate two counter machines [30]. Intuitively, arithmetic constraints allow us to model branching zero-tests available in the machine. This, coupled with recursion in the language of types, establishes undecidability. Remarkably, a small fragment


<!-- page 7 -->

of our language containing only type definitions, internal choice (⊕) and assertions (?{φ}. A) where φ just contains constraints n = 0 and n > 0 is sufficient to prove undecidability. Moreover, the proof still applies if we treat types isorecursively. In the remainder of this section we provide some details of the undecidability proof.

▶Definition 13 (Two Counter Machine). A two counter machine M is defined as a sequence of instructions ι1, ι2, . . . , ιm and cj (j ∈{1, 2}) as the two counters. Each instruction has one of the following forms.

"inc(cj); goto k" (increment counter j by 1 and go to instruction k) "zero(cj)? goto k : dec(cj); goto l" (if the value of counter j is 0, go to instruction k, else decrement the counter by 1 and go to instruction l)

"halt" (stop computation)

A configuration C of the machine M is defined as a triple (i, c1, c2), where i denotes the number of the current instruction and cj's denote the value of the counters. A configuration

C′ is defined as the successor configuration of C, written as C 7→C′ if C′ is the result of executing the i-th instruction on C. If ιi = halt, then C = (i, c1, c2) has no successor configuration. The computation of M is the unique maximal sequence ρ = ρ(0)ρ(1) . . . such that ρ(i) 7→ρ(i + 1) and ρ(0) = (1, 0, 0). Either ρ is infinite, or ends in (i, c1, c2) such that

ιi = halt and c1, c2 ∈N.

The halting problem refers to determining whether the computation of a two counter machine M with given initial values c1, c2 ∈N is finite. Both the halting problem and its dual, the non-halting problem, are undecidable.

▶Theorem 14. Given a valid signature Σ, two natural number variables m and n, and two types A and B such that m, n ; ⊤⊢Σ A, B valid. Then it is undecidable whether for concrete

i, j ∈N we have A[i/m, j/n] ≡B[i/m, j/n].

Proof. Given a two counter machine, we construct a signature Σ and two types A and B with free arithmetic variables m and n such that the computation of the machine starting with initial counter values i and j is infinite iffA[i/m, j/n] ≡B[i/m, j/n] in Σ.

inf} for distinct labels ℓand ℓ′. Next, for every instruction ιi, we define types Ti and T ′

We define types Tinf = ⊕{ℓ: Tinf} and T ′

i based on the form of the instruction. Case (ιi = inc(c1); goto k): We define

Ti[c1, c2] = ⊕{inc1 : Tk[c1 + 1, c2]} T ′

i[c1, c2] = ⊕{inc1 : T ′

k[c1 + 1, c2]}

Case (ιi = inc(c2); goto k): We define

Ti[c1, c2] = ⊕{inc2 : Tk[c1, c2 + 1]} T ′

i[c1, c2] = ⊕{inc2 : T ′

k[c1, c2 + 1]}

Case (ιi = zero(c1)? goto k : dec(c1); goto l): We define

Ti[c1, c2] = ⊕{zero1 : ?{c1 = 0}. Tk[c1, c2], dec1 : ?{c1 > 0}. Tl[c1 −1, c2]} T ′

i[c1, c2] = ⊕{zero1 : ?{c1 = 0}. T ′

Case (ιi = zero(c2)? goto k : dec(c2); goto l): We define

Ti[c1, c2] = ⊕{zero2 : ?{c2 = 0}. Tk[c1, c2], dec2 : ?{c2 > 0}. Tl[c1, c2 −1]} T ′

i[c1, c2] = ⊕{zero2 : ?{c2 = 0}. T ′

## A. Das and F. Pfenning

13:7

inf = ⊕{ℓ′ : T ′

k[c1, c2], dec1 : ?{c1 > 0}. T ′

l [c1 −1, c2]}

k[c1, c2], dec2 : ?{c2 > 0}. T ′

l [c1, c2 −1]}

## CONCUR 2020


<!-- page 8 -->

## 13:8

Session Types with Arithmetic Refinements

Case (ιi = halt): We define

Ti[c1, c2] = Tinf T ′

i[c1, c2] = T ′

inf

We set type A = T1[m, n] and B = T ′

1[m, n]. Now suppose, the counter machine M is initialized in the state (1, i, j). The type equality question we ask is whether T1[i, j] ≡T ′

1[i, j]. The two types only differ at the halting instruction. If M does not halt, the two types capture exactly the same communication behavior, since the halting instruction is never reached and they agree on all other instructions. If M halts, the first type proceeds with label ℓand the second with ℓ′ and they are therefore not equal. Hence, the two types are equal iffM does not halt. ◀

We can easily modify this reduction for an isorecursive interpretation of types, by wrapping ⊕{unfold : } around the right-hand side of each type definition to simulate the unfold message. We also see that a host of other problems are undecidable, such as determining whether two types with free arithmetic variables are equal for all instances. This is the problem that arises while type-checking parametric process definitions.

## 5 A Practical Algorithm for Type Equality

Despite its undecidability, we have designed a coinductive algorithm for soundly approximating type equality. Similar to Gay and Hole's algorithm, it proceeds by attempting to construct a bisimulation. Due to the undecidability of the problem, our algorithm can terminate in three different states: (1) we have succeeded in constructing a bisimulation, (2) we have found a counterexample to type equality by finding a place where the types may exhibit different behavior, or (3) we have terminated the search without a definitive answer. From the point of view of type-checking, both (2) and (3) are interpreted as a failure to type-check (but there is a recourse; see Subsection 5.2). Our algorithm is expressed as a set of inference rules where the execution of the algorithm corresponds to the bottom-up construction of a deduction. The algorithm is deterministic (no backtracking) and the implementation is quite efficient in practice (see Section 6).

One of the basic operations in Gay and Hole's algorithm is loop detection, that is, we have to determine that we have already added an equation A ≡B to the bisimulation we are constructing. Since we must treat open types, that is, types with free arithmetic variables subject to some constraints, determining if we have considered an equation already becomes a difficult operation. To that purpose we make an initial pass over the given type and introduce fresh internal names abstracted over their free type variables and known constraints. In the resulting signature defined type variables and type constructors alternate and we can perform loop detection entirely on type definitions (whether internal or external).

▶Example 15 (Queues, v3). After creating internal names %i for the type of queue we obtain the following signature (here parametric in A).

queueA[n] = &{ins : %0[n], del : %1[n]} %0[n] = A ⊸queueA[n + 1] %3 = 1 %1[n] = ⊕{none : %2[n], some : %4[n]} %4[n] = ?{n > 0}. %5[n] %2[n] = ?{n = 0}. %3 %5[n | n > 0] = A ⊗queueA[n −1]

Based on the invariants established by internal names, the algorithm only needs to compare two type variables or two structural types. The rules are shown in Figure 1. The judgment has the form V ; C ; Γ ⊢A ≡B where V contains the free arithmetic


<!-- page 9 -->

V ; C ; Γ ⊢Aℓ≡Bℓ (∀ℓ∈L) V ; C ; Γ ⊢⊕{ℓ: Aℓ}ℓ∈L ≡⊕{ℓ: Bℓ}ℓ∈L

V ; C ; Γ ⊢A1 ≡B1 V ; C ; Γ ⊢A2 ≡B2 V ; C ; Γ ⊢A1 ⊗A2 ≡B1 ⊗B2 ⊗

V ; C ; Γ ⊢A1 ≡B1 V ; C ; Γ ⊢A2 ≡B2 V ; C ; Γ ⊢A1 ⊸A2 ≡B1 ⊸B2 ⊸ V ; C ; Γ ⊢1 ≡1 1

V ; C ⊨φ ↔ψ V ; C ∧φ ; Γ ⊢A ≡B V ; C ; Γ ⊢?{φ}. A ≡?{ψ}. B ? V ; C ⊨φ ↔ψ V ; C ∧φ ; Γ ⊢A ≡B V ; C ; Γ ⊢!{φ}. A ≡!{ψ}. B !

V, k ; C ; Γ ⊢A[k/m] ≡B[k/n]

V ; C ; Γ ⊢∃m. A ≡∃n. B ∃k V, k ; C ; Γ ⊢A[k/m] ≡B[k/n]

V ; C ⊨⊥ V ; C ; Γ ⊢A ≡B ⊥

V1[v1 | φ1] = A ∈Σ V2[v2 | φ2] = B ∈Σ γ = ⟨V ; C ; V1[e1] ≡V2[e2]⟩ V ; C ; Γ, γ ⊢A[e1/v1] ≡B[e2/v2]

V ; C ; Γ ⊢V1[e1] ≡V2[e2]

′]⟩∈Γ V ; C ⊨∃V′. C′ ∧e1

⟨V′ ; C′ ; V1[e1

′] ≡V2[e2

## Figure 1 Algorithmic Rules for Type Equality.

variables in the constraints C and the types A and B, and Γ is a collection of closures ⟨V′ ; C′ ; V ′

1[e1′] ≡V ′ 2[e2′]⟩. If a derivation can be constructed, all ground instances of all closures are included in the resulting bisimulation (see the proof of Theorem 20). A ground instance V ′

1[e1′[σ′]] ≡V ′ 2[e2′[σ′]] is given by a substitution σ′ over variables in V′ such that ⊨C′[σ′].

The rules for type constructors simply compare the components. If the type constructors (or the label sets in the ⊕and & rules) do not match, then type equality fails (having

constructed a counterexample to bisimulation) unless the ⊥rule applies. This rules handles the case where the constraints are contradictory and no communication is possible.

The rule of reflexivity is needed explicitly here (but not in the version of Gay and Hole) because due to the incompleteness of the algorithm we may otherwise fail to recognize type variables with equal index expressions as equal.

Now we come to the key rules, expd and def. In the expd rule we expand the definitions of V1[e1] and V2[e2], and we also add the closure ⟨V ; C ; V1[e1] ≡V2[e2]⟩to Γ. Since the equality of V1[e1] and V2[e2] must hold for all its ground instances, the extension of Γ with the corresponding closure remembers exactly that. We can ignore the propositions φ1 and φ2 since the validity of types (rule tdef in Section 3) ensures that both ⊨φ1[e1/v1] and ⊨φ2[e2/v2] hold.

In the def rule we close offthe derivation successfully if all instances of the equation V1[e1] ≡V2[e2] are already instances of a closure in Γ. This is checked by the entailment in the second premise, V ; C ⊨∃V′. C′ ∧E1 = e1 ∧E2 = e2. This entailment is verified as a

## A. Das and F. Pfenning

13:9

⊕ V ; C ; Γ ⊢Aℓ≡Bℓ (∀ℓ∈L) V ; C ; Γ ⊢&{ℓ: Aℓ}ℓ∈L ≡&{ℓ: Bℓ}ℓ∈L &

V ; C ; Γ ⊢∀m. A ≡∀n. B ∀k

V ; C ⊨e1 = e′

1 ∧. . . ∧en = e′ n V ; C ; Γ ⊢V [e] ≡V [e′] refl

expd

′ = e2 V ; C ; Γ ⊢V1[e1] ≡V2[e2]

′ = e1 ∧e2

def

## CONCUR 2020


<!-- page 10 -->

## 13:10

Session Types with Arithmetic Refinements

closed ∀∃arithmetic formula, even if the original constraints C and C′ do not contain any quantifiers. While for Presburger arithmetic we can decide such a proposition using quantifier elimination, other constraint domains may not permit such a decision procedure.

The algorithm so far is sound, but potentially nonterminating because when encountering variable/variable equations, we can use the expd rule indefinitely. To ensure termination, we restrict the expd rule to the case where no closure with the same type variables V1 and V2 is already present in Γ. This also removes the overlap between these two rules. Note that if type variables have no parameters, our algorithm specializes to Gay and Hole's (with the small optimizations of reflexivity and internal naming), which means our algorithm is sound and complete on unindexed types.

As an extension, our algorithm also allows the programmer to specify a depth bound k. This informs the algorithm to apply the expd rule until there are at most k closures with the same type variables V1 and V2 in Γ.

▶Example 16 (Integer Counter). An integer counter with increment (inc), decrement (dec) and sign-test (sgn) operations provides type intctr[x, y], where the current value of the counter is x −y for natural numbers x and y.

## intctr[x, y] = &{inc : intctr[x + 1, y],

## dec : intctr[x, y + 1],

sgn : ⊕{neg : ?{x < y}. intctr[x, y],

## zer : ?{x = y}. intctr[x, y],

pos : ?{x > y}. intctr[x, y]}}

Under this definition our algorithm verifies, for example, that an increment followed by a decrement does not change the counter value. That is,

x, y ; ⊤; · ⊢intctr[x, y] ≡intctr[x + 1, y + 1]

where we have elided the assumptions x, y ≥0. When applying expd, we assume γ = ⟨x′, y′ ; ⊤; intctr[x′, y′] ≡intctr[x′ + 1, y′ + 1]⟩. Then, for example, in the first branch (for inc) we conclude x, y ; ⊤; γ ⊢intctr[x + 1, y] ≡intcr[x + 2, y + 1] using the def rule and the entailment x, y ; ⊤⊨∃x′. ∃y′. x′ = x + 1 ∧y′ = y ∧x′ + 1 = x + 2 ∧y′ + 1 = y + 1. The other branches are similar.

As exemplified by the above example, a distinguishing feature of our algorithm is that it goes beyond reflexivity. Essentially, V [e1] ≡V [e2] can hold even if e1̸ = e2. This is in contrast with traditional refinement languages such as DML [46] that use reflexivity as the only criterion for equality on indexed type names.

### 5.1 Soundness of the Type Equality Algorithm

We prove that the type equality algorithm is sound with respect to the definition of type

equality. The soundness is proved by constructing a type bisimulation from a derivation of the algorithmic type equality judgment. We sketch the key points of the proofs.

The first gap we have to bridge is that the type bisimulation is defined only for closed types, because observations can only arise from communication along channels which, at runtime, will be of closed type. So, if we can derive V ; C ; · ⊢A ≡B then we should interpret this as stating that for all ground substitutions σ over V such that ⊨C[σ] we have A[σ] ≡B[σ].


<!-- page 11 -->

## A. Das and F. Pfenning

13:11

▶Definition 17. Given a relation R on valid ground types and two types A and B such that V ; C ⊢A, B valid, we write ∀V. C ⇒A ≡R B if for all ground substitutions σ over V such that ⊨C[σ] we have (A[σ], B[σ]) ∈R.

Furthermore, we write ∀V. C ⇒A ≡B if there exists a type bisimulation R such that ∀V. C ⇒A ≡R B.

Note that if V ; C ⊨⊥, then ∀V. C ⇒A ≡B is vacuously true, since there does not exist a ground substitution σ such that ⊨C[σ].

A key lemma is the following, which is needed to show the soundness of the def rule.

▶Lemma 18. Suppose ∀V′.C′ ⇒V1[e1′] ≡R V2[e2′] holds. Further assume that V ; C ⊨ ∃V′.C′ ∧e1′ = e1 ∧e2′ = e2 for some V, C, e1, e2. Then, ∀V.C ⇒V1[e1] ≡R V2[e2] holds.

Proof. To prove ∀V. C ⇒V1[e1] ≡R V2[e2], it is sufficient to show that V1[e1[σ]] ≡R V2[e2[σ]] for any substitution σ over V such that ⊨C[σ]. Applying this substitution to V ; C ⊨∃V′. C′ ∧e1′ = e1 ∧e2′ = e2, we infer ∃V′. C′ ∧e1′ = e1[σ] ∧e2′ = e2[σ] since ⊨C[σ]. Thus, there exists σ′ over V′ such that ⊨C′[σ′] holds, and e1′[σ′] = e1[σ] and e2′[σ′] = e2[σ]. And since ∀V′. C′ ⇒V1[e1′] ≡R V2[e2′], we deduce that for any ground substitution (including the current one) σ′ over V′, V1[e1′[σ′]] ≡R V2[e2′[σ′]] holds. This implies that V1[e1[σ]] ≡R V2[e2[σ]] since e1′[σ′] = e1[σ] and e2′[σ′] = e2[σ]. ◀

We construct the bisimulation from a derivation of V ; C ; Γ ⊢A ≡B by (i) collecting the conclusions of all the sequents, excepting only the def rule, and (ii) forming all ground instances from them.

▶Definition 19. Given a derivation D of V ; C ; Γ ⊢A ≡B, we define the set S(D) of closures. For each sequent V′ ; C′ ; Γ′ ⊢A′ ≡B′ (except the conclusion of the def rule) we include the closure ⟨V′ ; C′ ; A′ ≡B′⟩in S(D).

## ▶Theorem 20 (Soundness). If V ; C ; · ⊢A ≡B, then ∀V. C ⇒A ≡B.

Proof. We are given a derivation D0 of V0 ; C0 ; · ⊢A0 ≡B0. Construct S(D0) and define a relation R on closed valid types as follows:

R = {(A[σ], B[σ]) | ⟨V ; C ; A ≡B⟩∈S(D0) and σ over V with ⊨C[σ]}

We prove that R is a type bisimulation. Then our theorem follows since the closure ⟨V0 ; C0 ; A0 ≡B0⟩∈S(D0).

Consider (A[σ], B[σ]) ∈R where ⟨V ; C ; A ≡B⟩∈S(D0) for some σ over V and ⊨C[σ]. First, consider the case where V ; C ⊨⊥. Under such a constraint, V ; C ; · ⊢A ≡B holds true due to the ⊥rule. Furthermore, ∀V. C ⇒A ≡B holds vacuously, and the algorithm is sound. For the remaining cases, we case analyze on the structure of A[σ] and assume that there exists a ground substitution σ such that ⊨C[σ].

Consider the case where A = ⊕{ℓ: Aℓ}ℓ∈L. Since A and B are both structural, B = ⊕{ℓ: Bℓ}ℓ∈L. Since ⟨V ; C ; A ≡B⟩∈S(D0), by definition of S(D0), we get ⟨V ; C ; Aℓ≡ Bℓ⟩∈S(D0) for all ℓ∈L. By the definition of R, we get that (Aℓ[σ], Bℓ[σ]) ∈R. Also, A[σ] = ⊕{ℓ: Aℓ[σ]}ℓ∈L and similarly, B[σ] = ⊕{ℓ: Bℓ[σ]}ℓ∈L. Hence, R satisfies the appropriate closure condition for a type bisimulation.

Next, consider the case where A = ?{φ}. A′. Since A and B are both structural, B = ?{ψ}. B′. Since ⟨V ; C ; A ≡B⟩∈S(D0), we obtain V ; C ⊨φ ↔ψ and ⟨V ; C ∧ φ ; A′ ≡B′⟩∈S(D0). Thus, for any substitution σ such that ⊨C[σ] ∧φ[σ], we get that (A′[σ], B′[σ]) ∈R with A[σ] = ?{φ[σ]}. A′[σ] and B[σ] = ?{ψ[σ]}. B′[σ]. Since ⊨φ[σ] and

and V ; C ⊨φ ↔ψ we also obtain ⊨ψ[σ] and the closure condition is satisfied.

## CONCUR 2020


<!-- page 12 -->

## 13:12

Session Types with Arithmetic Refinements

### 5.2 Type Equality Declarations

∀V. C ⇒V1[e1] ≡V2[e2]

in signatures. Let ΓΣ denote the set of all such declarations. Then we check

V ; C ; ΓΣ ⊢V1[e1] ≡V2[e2]

## ▶Example 21 (Queues, v4). Consider the two types queueA[n] and queue′

## queueA[n] = &{ins : A ⊸queueA[n + 1],

## del : ⊕{none : ?{n = 0}. 1,

## A[n] = &{ins : A ⊸queue′

queue′

## A[n + 1],

del : ⊕{none : ?{n = 1}. 1,

## some : ?{n > 1}. A ⊗queue′

Our intuition would suggest that queueA[0] ≡queue′

## 6 Implementation and Further Examples

Next, consider the case where A = ∃m. A′. Since A and B are both structural, B = ∃n. B′. Since ⟨V ; C ; A ≡B⟩∈S(D0), we get that ⟨V, k ; C ; A′[k/m] ≡B′[k/n]⟩∈S(D0). Since k was chosen fresh and does not occur in C, we obtain that for any i ∈N we have ⊨C[σ, i/k] and therefore (A′[σ, i/k], B′[σ, i/k]) ∈R for all i ∈N and the closure condition is satisfied.

The only case where a conclusion is not added to S(D0) is the def rule. In this case, adding (∀V. C ⇒V1[e1] ≡V2[e2]) is redundant: Lemma 18 states that V1[e1[σ]] ≡R V2[e2[σ]] which implies (V1[e1[σ]], V2[e2[σ]]) ∈R. ◀

Even though the type equality algorithm in Section 5 is incomplete, we have yet to find a natural example where it fails after we added reflexivity as a general rule. But since we cannot see a simple reason why this should be so, we made our type equality algorithm extensible by the programmer via an additional form of declaration

for each such declaration, seeding the construction of a bisimulation with all the given equations. Then, when type-checking has to decide the equality of two types, it starts not with the empty context Γ but with ΓΣ. Our soundness proof can easily accommodate this more general algorithm.

A[n], both representing queue data structures, but queue′

A[n] is rooted at 1.

## some : ?{n > 0}. A ⊗queueA[n −1]}}

A[n −1]}}

A[1]. But this cannot be directly proved by our equality algorithm. While checking this equality, our algorithm would add ⟨· ; ⊤; queueA[0] ≡queue′

A[1]⟩to Γ and would continue to check queueA[1] ≡queue′

## A[2]

(the ins branch). However, our closure in Γ is not sufficient to prove this goal (the def rule

fails), and our algorithm reports the types may not be equal. However, we can add a general equality declaration ∀n. queueA[n] ≡queue′

A[n + 1] to the signature. This can be verified by our algorithm since it would add ⟨n ; ⊤; queueA[n] ≡queue′

A[n + 1]⟩to Γ and use it to prove queueA[n + 1] ≡queue′

## A[n + 2] in the ins branch. Then, we will use the same equality

declaration from the signature to verify queueA[0] ≡queue′

A[1] by instantiating n = 0.

We have implemented the algorithm presented in Section 5 as part of the Rast programming

language [14], whose name derives from "Resource-Aware Session Types". Rast is based on intuitionistic linear sessions [8, 9] extended with general equirecursive types and recursively


<!-- page 13 -->

## A. Das and F. Pfenning

13:13

## Table 1 Case Studies.

Module LOC #Defs T (ms)

arithmetic 143 8 1.325 integers 114 8 1.074 linlam 67 6 4.003 list 441 29 3.419 primes 118 8 1.646 segments 65 9 0.195 ternary 235 16 1.967 theorems 141 16 0.894 tries 308 9 5.283

Total 1632 109 19.806

defined processes. We do not explicitly dualize types [43] but distinguish providers and clients that are connected by a private channel. In parallel work we have proved type safety for Rast, which includes type preservation (session fidelity) and global progress (deadlock freedom). The open-source implementation is written in Standard ML and currently comprises about 7500 lines of source code [36]. Rast supports indexed types, quantifiers, and arithmetic constraints, following the presentation in this paper with minor syntactic differences. In addition, Rast has temporal [12] and ergometric [13] types that capture parallel and sequential complexity of programs. These bounds often depend on intrinsic properties of the data structures (such as the length of a queue or the value of a binary number) which are expressed as arithmetic indices.

Rast's linear type checker is bidirectional, which means that only process definitions need to be annotated with their types. In the so-called explicit syntax type checking is then straightforward, breaking down the structure of the type and unfolding definitions, except for calls to type equality (which are necessary for forwarding, process invocations, and sending of channels). The implementation also supports an implicit syntax in which some parts of the program, specifically those concerning missing branches that can be proved to be impossible using refinements, can be omitted from the source and are reconstructed. The reconstructed code is then passed through the type checker as ultimate arbiter.

We use a straightforward implementation of Cooper's algorithm [10] to decide Presburger arithmetic with two small but significant optimizations. One takes advantage of the fact that we are working over natural numbers rather than integers, the other is to eliminate constraints of the form x = e by substituting e for x in order to reduce the number of variables. We also extend our solver to handle non-linear constraints. Since non-linear arithmetic is undecidable, in general, we use a normalizer which collects coefficients of each term in the multinomial expression. To check e1 = e2, we normalize e1 −e2 and check that each coefficient of the normal form is 0. To check e1 ≥e2, we normalize e1 −e2 and check that each coefficient is non-negative.

We have a variety of 21 examples implemented, totaling about 3700 lines of code, for which complete code can be found in our open source repository [36]. Table 1 describes the results for nine representative case studies: LOC describes the lines of code, #Defs shows the number of process definitions, and T (ms) shows the type-checking time in milliseconds respectively. The experiments were run on an Intel Core i5 2.7 GHz processor with 16 GB 1867 MHz DDR3 memory. We briefly describe each case study.

## CONCUR 2020


<!-- page 14 -->

## 13:14

Session Types with Arithmetic Refinements

1. arithmetic: natural numbers in unary and binary representation indexed by their value and processes implementing standard arithmetic operations. 2. integers: an integer counter represented using two indices x and y with value x −y. 3. linlam: expressions in the linear λ-calculus indexed by their size with an eval process to evaluate them (see below for an excerpt). 4. list: lists indexed by their size with standard operations (e.g., append, reverse, map). 5. primes: implementation of the sieve of Eratosthenes. 6. segments: type seg[n] = ∀k.list[k] ⊸list[n + k] representing partial lists with constantwork append operation. 7. ternary: natural numbers represented in balanced ternary form with digits 0, 1, −1, indexed by their value, and some standard operations on them. 8. theorems: processes representing (circular [15]) proofs of simple arithmetic theorems. 9. tries: a trie data structure to store multisets of binary numbers, with constant amortized work insertion and deletion, verified with ergometric types.

Linear λ-calculus. We briefly sketch the types in an implementation of the (untyped) linear λ-calculus in which the index objects track the size of the expression, because it uses multiple feature of the type system.

## exp[n] = ⊕{lam : ?{n > 0}. ∀n1. exp[n1] ⊸exp[n1 + n −1],

## app : ∃n1. ∃n2. ?{n = n1 + n2 + 1}. exp[n1] ⊗exp[n2]}

An expression is either a λ-abstraction (sending label lam) or an application (sending label app). In case of lam, the continuation receives a number n1 and an argument of size n1 and then behaves like the body of the λ-abstraction of size n1 + n −1. In case of app, it will send n1 and n2 such that n = n1 + n2 + 1 followed an expression of size n1 and then behave as an expression of size n2.

A value can only be a λ-abstraction

## val[n] = ⊕{lam : ?{n > 0}. ∀n1. exp[n1] ⊸exp[n1 + n −1]}

so the app label is not permitted. Type checking verifies that that the result of evaluating a linear λ-term is no larger than the original term. The declaration below expresses that eval [n] is client to a process sending a λ-expression of size n along channel e and provides a

value of size k, where k ≤n.

(e : exp[n]) ⊢eval [n] :: (v : ∃k. ?{k ≤n}. val[k])

## 7 Further Related Work

Traditional languages with dependent type refinements such as Zenger's [47] or DML [46] only use the rule of reflexivity as a criterion for equality of indexed types. This is justified in the context of these functional languages because data types are generative and therefore nominal in nature. This is also true for more recent languages with linearity and value-dependent types such as Granule [32].

Session type systems that allow dependencies are label-dependent session types [39] and richer linear type theories [40, 33, 41]. Toninho et al. [40, 33] allow sufficient dependencies that, in general, proofs must be sent in some circumstances. They do not provide a type equality algorithm or implementation. In a more recent paper, Toninho et al. [41] propose a dependent type theory with rich notions of value and process equality based on βη-congruences


<!-- page 15 -->

## A. Das and F. Pfenning

13:15

and certain process equalities, but they do not discuss decidability or algorithms for type checking or type equality. Wu and Xi [44] propose a dependent session type system based on ATS [45] formalizing type equality in terms of subtyping and regular constraint relations. They mention recursive session types as a possible extension, but do not develop them nor investigate properties of the required type equality.

Linearly refined session types [2, 16] extend the π-calculus with capabilities from a fragment of multiplicative linear logic. These capabilities encode an authorization logic enabling fine-grained specifications and are thus not directly comparable to arithmetic refinements. Session types with limited arithmetic refinements (only base types could be refined) have been proposed for the purpose of runtime monitoring [20, 19], which is complementary to our uses for static verification. They have also been proposed to capture work [13, 11] and parallel time [12], but parameterization over index objects was left to an informal meta-level and not part of the object language. Consequently, these languages contain neither constraints nor quantifiers, and the metatheory of type equality, type checking, and reconstruction in the presence of index variables was not developed.

Several other generalizations of session types for specification and verification have been proposed. Generalizing the idea of "Design by Contract" [29] to distributed domains, session types have been elaborated with logical predicates to obtain global assertions [4]. Actris [23] combines concurrent separation logics with session types for reasoning about message passing in the presence of other concurrency paradigms. Actris is able to prove functional correctness of a distributed merge sort, a distributed load-balancing mapper, and a variant of the mapreduce model. Context-free session types [38] are another generalization of basic session types in a different direction, essentially allowing the concatenation of sessions. This generalization has decidable type checking and type equality problems that have been shown to be efficient in practice [1].

Asynchronous session types [18] have a notion of subtyping under different assumptions regarding communication behavior [31]. The resulting subtyping relation also turns out to be undecidable [6, 27] with the development of recent practical incomplete algorithms [5]. The expressive power of asynchronous session subtyping seems incomparable to our arithmetically refined session types.

## 8 Conclusion

This paper explored the metatheory of session types with arithmetic refinements, showing the undecidability of type equality. Nevertheless, we have shown a sound, but incomplete algorithm that has performed well over a range of examples in our Rast implementation.

Natural extensions include nonlinear arithmetic and other constraint domains, balancing practicality of type checking with expressive power. We would also like to generalize from type equality to subtyping, replacing the notion of bisimulation with a simulation. Clearly, this will be undecidable as well, but the pioneering work by Gay and Hole and the characteristics of our algorithms suggest that it should extend cleanly and remain practical.

Finally, we would also like to generalize our approach to a mixed linear/nonlinear language [3] or all the way to adjoint session types [34, 35]. Since the main issues of type equality are orthogonal to the presence or absence of structural properties, we conjecture that the algorithm proposed here will extend to this more general setting.

## CONCUR 2020


<!-- page 16 -->

## 13:16

Session Types with Arithmetic Refinements

## References

## 1 Bernardo Almeida, Andreia Mordido, and Vasco T. Vasconcelos. Deciding the bisimilarity of

context-free session types. In A. Biere and D. Parker, editors, 16th International Conference on Tools and Algorithms for the Construction and Analysis of Systems (TACAS 2020), pages

39-56, Dublin, Ireland, April 2020. Springer LNCS 12079.

2 Pedro Baltazar, Dimitris Mostrous, and Vasco T. Vasconcelos. Linearly refined session types. In S. Alves and I. Mackie, editors, International Workshop on Linearity (LINEARITY 2012), pages 38-49, Tallinn, Estonia, April 2012. EPTCS 101.

3 Nick Benton. A mixed linear and non-linear logic: Proofs, terms and models. In L. Pacholski and J. Tiuryn, editors, Selected Papers from the 8th International Workshop on Computer Science Logic (CSL 1994), pages 121-135, Kazimierz, Poland, September 1994. Springer LNCS 933. An extended version appears as Technical Report UCAM-CL-TR-352, University of Cambridge.

4 Laura Bocchi, Kohei Honda, Emilio Tuosto, and Nobuko Yoshida. A theory of design-bycontract for distributed multiparty interactions. In Paul Gastin and François Laroussinie, editors, CONCUR 2010 - Concurrency Theory, pages 162-176, Berlin, Heidelberg, 2010. Springer Berlin Heidelberg.

## 5 Mario Bravetti, Marco Carbone, Julien Lange, Nobuko Yoshida, and Gianluigi Zavattaro. A

sound algorithm for asynchronous session subtyping. In W. Fokkink and R. van Glabbeek, editors, 30th International Conference on Concurrency Theory (CONCUR 2019), pages 38:1-38:16, Amsterdam, The Netherlands, August 2019. LIPIcs 140.

## 6 Mario Bravetti, Marco Carbone, and Gianluigi Zavattaro. Undecidability of asynchronous

## session subtyping. Information & Computation, 256:300-320, 2017.

7 Luís Caires, Jorge A. Pérez, Frank Pfenning, and Bernardo Toninho. Behavioral polymorphism and parametricity in session-based communication. In M.Felleisen and P.Gardner, editors, Proceedings of the European Symposium on Programming (ESOP'13), pages 330-349, Rome,

Italy, March 2013. Springer LNCS 7792.

8 Luís Caires and Frank Pfenning. Session types as intuitionistic linear propositions. In P.Gastin and F.Laroussinie, editors, Proceedings of the 21st International Conference on Concurrency Theory (CONCUR 2010), pages 222-236, Paris, France, August 2010. Springer LNCS 6269.

## 9 Luís Caires, Frank Pfenning, and Bernardo Toninho. Linear logic propositions as session

types. Mathematical Structures in Computer Science, 26(3):367-423, 2016. Special Issue on Behavioural Types.

10 David C. Cooper. Theorem proving in arithmetic without multiplication. Machine Intelligence, 7:91-99, 1972.

## 11

Ankush Das, Stephanie Balzer, Jan Hoffmann, Frank Pfenning, and Ishani Santurkar. Resourceaware session types for digital contracts, 2019. arXiv:1902.06056.

## 12 Ankush Das, Jan Hoffmann, and Frank Pfenning. Parallel complexity analysis with temporal

session types. In M. Flatt, editor, Proceedings of International Conference on Functional Programming (ICFP 2018), pages 91:1-91:30, St. Louis, Missouri, USA, September 2018. ACM.

## 13 Ankush Das, Jan Hoffmann, and Frank Pfenning. Work analysis with resource-aware session

types. In A. Dawar and E. Grädel, editors, Proceedings of 33rd Symposium on Logic in Computer Science (LICS'18), pages 305-314, Oxford, UK, July 2018.

## 14 Ankush Das and Frank Pfenning.

Rast: Resource-aware session types with arithmetic refinements. In Z. Ariola, editor, 5th International Conference on Formal Structures for Computation and Deduction (FSCD 2020), pages 4:1-4:17. LIPIcs 167, June 2020. System

description. To appear.

15 Farzaneh Derakhshan and Frank Pfenning. Circular proofs as session-typed processes: A local validity condition, August 2019. arXiv:1908.01909.


<!-- page 17 -->

## A. Das and F. Pfenning

13:17

## 16 Juliana Franco and Vasco T. Vasconcelos. A concurrent programming language with refined

session types. In S. Counsell and M. Núñez, editors, Software Engineering and Formal Methods (SEFM 2013), pages 15-28, Madrid, Spain, September 2013. Springer LNCS 8368. 17 Simon J. Gay and Malcolm Hole. Subtyping for session types in the π-calculus. Acta Informatica, 42(2-3):191-225, 2005. 18 Simon J. Gay and Vasco T. Vasconcelos. Linear type theory for asynchronous session types. Journal of Functional Programming, 20(1):19-50, January 2010. 19 Hannah Gommerstadt. Session-Typed Concurrent Contracts. PhD thesis, Carnegie Mellon University, September 2019. Available as Technical Report CMU-CS-19-119. 20 Hannah Gommerstadt, Limin Jia, and Frank Pfenning. Session-typed concurrent contracts. In A. Ahmed, editor, European Symposium on Programming (ESOP'18), pages 771-798, Thessaloniki, Greece, April 2018. Springer LNCS 10801. 21 Dennis Griffith. Polarized Substructural Session Types. PhD thesis, University of Illinois at Urbana-Champaign, April 2016. 22 Dennis Griffith and Elsa L. Gunter. LiquidPi: Inferrable dependent session types. In Proceedings of the NASA Formal Methods Symposium, pages 186-197. Springer LNCS 7871, 2013. 23 Jonas Kastberg Hinrichsen, Jesper Bengtson, and Robbert Krebbers. Actris: Session-type based reasoning in separation logic. Proc. ACM Program. Lang., 4(POPL), December 2019. doi:10.1145/3371074. 24 Kohei Honda. Types for dyadic interaction. In E. Best, editor, 4th International Conference on Concurrency Theory (CONCUR 1993), pages 509-523. Springer LNCS 715, 1993. 25 Kohei Honda, Vasco T. Vasconcelos, and Makoto Kubo. Language primitives and type discipline for structured communication-based programming. In C. Hankin, editor, 7th European Symposium on Programming Languages and Systems (ESOP 1998), pages 122-138. Springer LNCS 1381, 1998. 26 Kohei Honda, Nobuko Yoshida, and Marco Carbone. Multiparty asynchronous session types. In G. Necula and P. Wadler, editors, Proceedings of the 35th Symposium on Principles of Programming Language (POPL 2008), pages 273-284, San Francisco, California, USA, January 2008. ACM. 27 Julien Lange and Nobuko Yoshida. On the undecidability of asynchronous session subtyping. In Proceedings of the 20th International Conference on Foundations of Software Science and Computation Structures (FoSSaCS 2017), pages 441-457. Springer LNCS 10203, 2017. 28 Sam Lindley and J. Garrett Morris. Talking bananas: Structural recursion for session types. In J. Garrigue, G. Keller, and E. Sumii, editors, Proceedings of the 21st International Conference on Functional Programming, pages 434-447, Nara, Japan, September 2016. ACM. 29 Bertrand Meyer. Applying "design by contract". Computer, 25(10):40-51, October 1992. doi:10.1109/2.161279. 30 Marvin L. Minsky. Computation: Finite and Infinite Machines. Prentice-Hall, Inc., USA, 1967. 31 Dimitris Mostrous and Nobuko Yoshida. Session typing and asynchronous subtyping for the higher-order π-calculus. Information & Computation, 241:227-263, 2015. 32 Dominic Orchard, Vilem-Benjamin Liepelt, and Harley Eades, III. Quantitative program reasoning with graded modal types. In International Conference on Functional Programming (ICFP 2019), pages 110:1-110:30, Berlin, Germany, August 2019. ACM. 33 Frank Pfenning, Luís Caires, and Bernardo Toninho. Proof-carrying code in a session-typed process calculus. In 1st International Conference on Certified Programs and Proofs (CPP 2011), pages 21-36, Kenting, Taiwan, December 2011. Springer LNCS 7086. 34 Frank Pfenning and Dennis Griffith. Polarized substructural session types. In A. Pitts, editor, Proceedings of the 18th International Conference on Foundations of Software Science and Computation Structures (FoSSaCS 2015), pages 3-22, London, England, April 2015. Springer

LNCS 9034. Invited talk.

## CONCUR 2020


<!-- page 18 -->

## 13:18

Session Types with Arithmetic Refinements

## 35 Klaas Pruiksma and Frank Pfenning. A message-passing interpretation of adjoint logic. In

F. Martins and D. Orchard, editors, Workshop on Programming Language Approaches to Concurrency and Communication-Centric Software (PLACES 2019), pages 60-79, Prague,

Czech Republic, April 2019. EPTCS 291. 36 Rast language, February 2020. Version 1.01. URL: https://bitbucket.org/fpfenning/rast/ src/master/rast/. 37 Patrick Maxim Rondon, Ming Kawaguchi, and Ranjit Jhala. Liquid types. In R. Gupta and S. Amarasinghe, editors, Conference on Programming Language Design and Implementation (PLDI 2008), pages 159-169, Tuscon, Arizona, June 2008. ACM. 38 Peter Thiemann and Vasco T. Vasconcelos. Context-free session types. In Proceedings of the 21st International Conference on Functional Programming (ICFP 2016), pages 462-475, Nara, Japan, September 2016. ACM. 39 Peter Thiemann and Vasco T. Vasconcelos. Label-dependent session types. In L. Birkedal, editor, Proceedings of the Symposium on Programming Languages (POPL 2020), pages 67:1- 67:29, New Orleans, Louisiana, USA, January 2020. ACM Proceedings on Programming Languages 4. 40 Bernardo Toninho, Luís Caires, and Frank Pfenning. Dependent session types via intuitionistic linear type theory. In P.Schneider-Kamp and M.Hanus, editors, Proceedings of the 13th International Conference on Principles and Practice of Declarative Programming (PPDP 2011), pages 161-172, Odense, Denmark, July 2011. ACM. 41 Bernardo Toninho and Nobuko Yoshida. Depending on session-types processes. In C. Baier and U. Dal Lago, editors, 21st International Conference on Foundations of Software Science and Computation Structures (FoSSaCS 2018), pages 128-145, Thessaloniki, Greece, April 2018. Springer LNCS 10803. 42 Vasco T. Vasconcelos. Fundamentals of session types. Information & Computation, 217:52-70, 2012. 43 Philip Wadler. Propositions as sessions. In Proceedings of the 17th International Conference on Functional Programming (ICFP 2012), pages 273-286, Copenhagen, Denmark, September

2012. ACM Press. 44 Hanwen Wu and Hongwei Xi. Dependent session types, 2017. arXiv:1704.07004. 45 Hongwei Xi. Applied type system: Extended abstract. In S. Berardi, M. Coppo, and F. Damiani, editors, International Workshop on Types for Proofs and Programming (TYPES 2003), pages 394-408, Torino, Italy, April 2003. Springer LNCS 3085. 46 Hongwei Xi and Frank Pfenning. Dependent types in practical programming. In A. Aiken, editor, Conference Record of the 26th Symposium on Principles of Programming Languages (POPL 1999), pages 214-227, San Antonio, Texas, USA, January 1999. ACM Press. 47 Christoph Zenger. Indexed types. Theoretical Computer Science, 187:147-165, 1997. 48 Fangyi Zhou. Refinement session types. Master's thesis, Imperial College London, 2019. 49 Fangyi Zhou, Francisco Ferreira, Rumyana Neykova, and Nobuko Yoshida. Fluid Types: Statically Verified Distributed Protocols with Refinements. In 11th Workshop on Programming Language Approaches to Concurrency and Communication-Centric Software, 2019.
