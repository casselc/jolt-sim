# Affine Sessions

> **Machine-generated Markdown conversion — this is a MODIFIED version.**
> Converted from `COORDINATION14-affine-sessions.pdf` with PyMuPDF. Layout, mathematics,
> figures and tables are lossy; **quote from the PDF, not from this file.**
> Page anchors below correspond to PDF pages.

- **Citation:** Dimitris Mostrous, Vasco Thudichum Vasconcelos. COORDINATION/DisCoTec 2014. doi:10.1007/978-3-662-43376-8_8
- **Licence:** CC-BY 4.0 (HAL deposit)
- **Source:** https://inria.hal.science/hal-01290071

---


<!-- page 1 -->

## To cite this version:

## HAL Id: hal-01290071

## https://inria.hal.science/hal-01290071

Submitted on 17 Mar 2016

HAL is a multi-disciplinary open access archive for the deposit and dissemination of scientific research documents, whether they are published or not. The documents may come from teaching and research institutions in France or abroad, or from public or private research centers.

Distributed under a Creative Commons Attribution 4.0 International License

## Affine Sessions

## Dimitris Mostrous, Vasco Thudichum Vasconcelos

Dimitris Mostrous, Vasco Thudichum Vasconcelos. Affine Sessions. 16th International Conference on Coordination Models and Languages (COORDINATION), Jun 2014, Berlin, Germany. pp.115-130, ￿10.1007/978-3-662-43376-8_8￿. ￿hal-01290071￿

L'archive ouverte pluridisciplinaire HAL, est destinée au dépôt et à la diffusion de documents scientifiques de niveau recherche, publiés ou non, émanant des établissements d'enseignement et de recherche français ou étrangers, des laboratoires publics ou privés.


<!-- page 2 -->

## Affine Sessions

Dimitris Mostrous and Vasco Thudichum Vasconcelos

University of Lisbon, Faculty of Sciences and LaSIGE

Lisbon, Portugal

Abstract. Session types describe the structure of protocols from the point of view of each participating channel. In particular, the types describe the type of communicated values, and also the dynamic alternation of input and output actions on the same channel, by which a protocol can be statically verified. Crucial to any term language with session types is the notion of linearity, which guarantees that channels exhibit exactly the behaviour prescribed by their type. We relax the condition of linearity to that of affinity, by which channels exhibit at most the behaviour prescribed by their types. This more liberal setting allows us to incorporate an elegant error handling mechanism which simplifies and improves related works on exceptions. Moreover, our treatment does not affect the progress properties of the language: sessions never get stuck.

## 1 Introduction

A session is "a semantically atomic chain of communication actions which can interleave with other such chains freely, for high-level abstraction of interactionbased computing" [21]. Session types capture this intuition as a description of the structure of a protocol, in the simplest case between two programs (binary sessions). This description consists of types that indicate whether a communication channel will next perform an output or input action, the type of the value to send or receive, and what to do next, inductively. For example, !nat.!string.?bool.end is the type of a channel that will first send a value of type nat, then one of type string, then wait for a value of type bool, and nothing more. This type can be materialised by the π-calculus [19] process a5.a "hello".a(x).0. To compose two processes that communicate over a channel, we require that each has a complementary (or dual) type, so that an input will match an output, and vice versa. The dual of the previous type is ?nat.?string.!bool.end, and can be implemented by a(x).a(y).a(x + 1 < 2).0. To ensure that the actions take place in the prescribed order, session typing relies crucially on the notion of linearity [12], which means that a causal chain can be assumed. To see why, imagine that we write the first process as a5.0 | a "hello".a(x).0. Now we cannot determine which output can react first, and the second process can receive a "hello" first, which would clearly be unsound and would most likely raise an error.

Beyond the basic input/output types, sessions typically provide constructors for alternative sub-protocols, which are very useful for structured interaction. For example, & {go: T1, cancel: T2} can be assigned to an (external) choice a ▷


<!-- page 3 -->

go: T1, cancel: T2

## 2 Affine Sessions by Example

{go.P1 8 cancel.P2}. The dual type, where T denotes T with an alternation of all constructors, is ⊕

, and corresponds to a process that will make a (internal) choice, either a ◁go.Q1 or a ◁cancel.Q2. In the first case the two processes will continue as P1 and Q1, respectively.

As can be seen, sessions are very suitable as a static verification mechanism for interacting programs. However, they are also quite rigid, since everything in the description of a session type must be implemented by a program with that type. Indeed, in many real world situations, interactions are structured but can be aborted at any time, for example an online store should be prepared for clients that get disconnected, that close their web browsers, or for general errors that abruptly severe the expected pattern of interaction.

In this work, we address the above issues. In technical terms, we relax the condition of linearity to that of affinity, inspired by Affine Logic (which is the variation of Linear Logic with unrestricted weakening; see [2] for an introduction), and this allows processes to terminate their sessions prematurely. However, a naive introduction of affinity can leave programs in a stuck state: let us re-write the first process into a5.a "hello".0, i.e., without the final input; then, after two communications the dual process will be stuck trying to perform a(5 + 1 < 2).0. One of the basic tenets of sessions, progress, is now lost. Actually, the study of Proof Nets for Affine Logic [2] reveals that weakening is not, and should not be, invisible. In particular, there exists a device that will perform the weakening step by step, progressing through the dependencies of a proof, and removing all that must be removed. This is exactly what we need in order to handle an abrupt termination of an interaction in an explicit way, and we denote it by a , which reads cancel a. Now we can write a5.a "hello".a , and after two steps against the dual process we obtain a(5 + 1 < 2).0 | a , which results in the cancellation of the output (and in general, of any subsequent actions on a). We take this idea a step further: if cancellation of a session is explicit, we can treat it as an exception, and for this we introduce a do-catch construct that can provide an alternative behaviour activated when a cancellation is encountered. For example, we can write do a(5 + 1 < 2).0 catch P, and a composition with a will replace a(5 + 1 < 2).0 with the exception handler P. A do-catch is not the same as the try-catch commonly found in sequential languages: it does not define a persistent scope that captures exceptions from the inside, but rather it applies to the first communication and is activated by exceptions from the outside (as in the previous example). Thus, do a(5 + 1 < 2).0 catch P in parallel to a(x).0 becomes 0, because the communication was successful.

We describe a simple interaction that implements a book purchase taking place between three processes, Buyer, Seller, and Bank. The buyer sends the title of a book, receives the price, and makes a choice to either buy it or cancel. If the buyer chooses to buy the book, the credit card is sent over the session, and the


<!-- page 4 -->

b : "Proofs and Types"

b : e 178

b : select buy

b : select accepted

Buyer .= (νb)



Seller .= acc seller(s).

  

k(amount).k(r).r(card).kr.

Bank .= acc bank(k).

Buyer Seller Bank

c : e 178

c : session on b

b : ccard

c : session on b

c : select accepted

Fig. 1. Sequence Diagram for Succesful Book Purchase

buyer is informed whether or not the transaction was successful. The diagram in Figure 1 shows the interactions of a specific purchase.

We now show how this scenario can be implemented using sessions, and how our treatment of affinity can be used to enable a more concise and natural handling of exceptional outcomes. Our language is an almost standard π-calculus where replication is written acc a(x).P and plays the role of "accept" in sessions terminology [15]. Dually, an output that activates a replication is written req ab.P, and is called a "request". We will use some standard contructs that are encodable in π-calculus, like ae for an expression e. Also, we use if t then P else Q that can be implemented by a new session, specifically a ▷{true.P 8 false.Q} against some process representing the test t that communicates the result by a selection of one of the labels, a ◁true or a ◁false.

An implementation of the protocol we described in Figure 1 is shown below:

req seller b | b "Proofs and Types".b(price).if (price < 200) then b ◁buy.b ccard.b ▷{accepted.P 8 rejected.Q} else b ◁cancel



s(prod).s price(prod). s ▷{buy.(νc)(req bank c | c price(prod).cs.c(r).

  

c ▷{accepted.r ◁accepted 8 rejected.r ◁rejected}) 8 cancel.0}

if charge(amount, card) then k ◁accepted else k ◁rejected


<!-- page 5 -->



    

BuyerMsg .= (νb)

First we note how sessions are established. For example, in Buyer the fresh name b is sent to Seller via the request req seller b, where it will substitute s in a copy of the replication, and it appears also locally. These are the two endpoints of the session, and it is easy to check that the interactions match perfectly. Another point is the "borrowing" of the session b (which becomes identified with s) from Seller to Bank, with cs.c(r) and k(r).r(card).kr (again, c and k are identified), respectively, so that the credit card is received directly by Bank; see also Figure 1.

A more robust variation of Seller could utilise the do-catch mechanism to account for the possibility of the Bank not being available (or being crashed), by providing an alternative payment provider. This can be achieved if we substitute req bank c with do req bank c catch req paymate c, so that a failure to use the bank service (bank ) will activate req paymate c (which must have the same type) and the protocol has a chance to complete successfully.

The Buyer might also benefit from our notion of exception handling. For example, we show an adaptation that catches a cancellation at the last communication of the buy branch and prints an informative message:



req seller b | b "Proofs and Types".b(price). if (price < 200) then b ◁buy.b ccard.

    

do b ▷{accepted.P 8 rejected.Q} catch req print "Error 42" else b ◁cancel

As mentioned in the Introduction, a do-catch on some communication does not catch subsequent cancellations. For instance, if in the above example the do-catch was placed on b "Proofs and Types", then any b generated after this output was performed would be uncaught, since reqprint "Error 42" would have been already discarded. However, a do-catch does catch cancellations emitted before the point of definition, so placing it near the end of a protocol is very useful if we just want a single exception handler that catches everything.

Indeed, exception handlers that persist for the lifetime of the whole session are definable. Specifically, we can write tryP catch(b: Q) to mean that one endpoint of b is implemented in P and that Q should be activated if b is canceled at any point from the outside of P. This try-catch notation translates to a do-catch on the last prefix on b in P, in multiple branches as required, assuming that b is not delegated (i.e., sent over another channel), as is the case in BuyerMsg.1 Then we have, for example, that try b5.P ′ catch (b: Q) | b(x).0 becomes try P ′ catch (b: Q) and try b5.P ′ catch (b: Q) | b becomes P ′′ | Q where P ′′ is P ′ with b and all its dependencies canceled. In general, however, our mechanism is very finegrained, and a single session can have multiple, nested do-catch on crucial points of communication.

1 We assume that in BuyerMsg P and Q do not use b. The restriction to non-delegated sessions is for simplicity: if the last action on b was to send it over some channel k, e.g., using kb, the encoding would be more complicated, because we would not have access to the end of the session and anything after that output would not be caught.


<!-- page 6 -->

CheckPriceA .= (νb)

CheckPriceB .= (νb)

BuyerCancel .= (νb)

go : & {go : !T1.JT2K, cancel : end} ,

J!T1.T2K .= ⊕

cancel : & {go : end, cancel : end}

## 3 The Process Calculus of Affine Sessions

Note also that a can be very useful in itself, even without the do-catch mechanism. Here are two ways to implement a process that starts a protocol with Seller only to check the price of a book:

req seller b | b "The Prince".b(price).(b ◁cancel | R)

req seller b | b "Beyond Good and Evil".b(price).(b | R)

Both the above processes can be typed. However, the first requires a knowledge of the protocol, which in that case includes an exit point (branch cancel), while the second is completely transparent. For example, imagine a buyer that selects buy by accident and then wishes to cancel the purchase: without cancellation it is impossible because it is not predicted by the session type; with cancellation it is extremely simple, as shown below.

req seller b | b "Gödel, Escher, Bach".b(price).b ◁buy.b

We now make a small digression to discuss how our affine sessions can be encoded in standard sessions. The purpose is to shed light on the complexity that is required, which motivates even more our development. First of all, it is possible that both endpoints of a session emit a cancellation, possibly at different moments. Therefore, if we are to encode this behaviour in a standard session system, we must allow a protocol to end at any point and by the request of either of the participants. This can be achieved by an exchange of a decision, to go or to cancel, by both endpoints, before all communications. We show the translation for output and input; the rest is similar.

J?T1.T2K .= J!T1.T2K JendK .= end

The notable point is that by an alternation of constructors we obtain a translation that preserves duality, and it is easy to check that it preserves soundness. Moreover, the only way to proceed is if both ends agree to go. The term-level translation follows the structure of the types, and a becomes a ◁cancel. All free sessions in the term must be canceled in the branches that do not result in normal execution so as to obtain the same typing environment, but this is always possible.

The above translation only handles cancellation. Our do-catch mechanism can also be encoded within the branches of the previous translation, but it becomes quite complicated due to the typing contraints that must be respected. In any case, we think it is obvious that the burden is heavy if one wishes to obtain a functionality as general as the one available in the affine system, and types would become completely illegible from the multiplication of constructors.

Syntax Our language is a small extension of standard π-calculus [19]. With respect to standard sessions systems [11,22], we avoid the need for polarities


<!-- page 7 -->

and double binders by carefully introducing a logically-founded typing principle, detailed later. For technical convenience we shall consider all indexing sets I to be totally ordered, so that we can speak, e.g., of the maximum element. Also for technical convenience, we separate the prefixes denoted by ρ, i.e., all communication actions except for accept (replication). We only added two nonstandard constructs: the cancellation a and the do-catch construct that captures a cancellation, denoted by do ρ catch P. Notice that we restricted the action to a prefix in ρ, but this is not so limiting. In the case of replication, it does not make sense to catch an event that never occurs, since as we shall see we never explicitly cancel a persistent service. For parallel composition, it would be ambiguous to allow do (P | Q) catch R since more than one action can be active in (P | Q), and moreover we do not think it would really add any benefit since we can add a separate do-catch for each session. Similarly, do a catch P would be strange: it would allow to trigger some behaviour when the other end is canceled, but while at the same time the protected session is canceled too. It can be added if a good use is discovered, but we preferred to keep the semantics simpler.

ρ ::= a(x: T).P (input)

| ab.P (output)

| a ▷{li.Pi}i∈I (branching)

| a ◁lk.P (selection)

| req ab.P (request)

P ::= ρ (prefix)

| acc a(x: T).P (replicated accept)

| 0 (nil)

| P | Q (parallel)

| (νa: T)P (restriction)

| a (cancel)

| do ρ catch P (catch)

Structural Congruence With ≡we denote the least congruence on processes that is an equivalence relation, equates processes up to α-conversion, satisfies the abelian monoid laws for parallel composition, the usual laws for scope extrusion, and satisfies the axiom:

(νa: T)(a | · · · | a ) ≡0

We added this axiom mainly for the left-to-right direction which allows "leftover" cancellations to disappear; this is convenient for technical reasons.


<!-- page 8 -->

The cancellation reductions follow:

We discuss some notable points.

2 The language remains confluent, as expected in a logically founded system.

Reduction We use two kinds of contexts, C[ ] which are standard, and H[ ] for (possible) exception handling, defined below.

Standard Contexts : C[ · ] ::= · | ( C[ · ] | P ) | (νa: T) C[ · ] Do-Catch Contexts : H[ · ] ::= · | do · catch P

Reduction is defined in two parts, first the standard rules, and then the cancellation rules. The standard reductions are defined below:

H1[ ab.P ] | H2[ a(x: T).Q ] −→P | Q{b/x} (R-Com)

H1[ a ◁lk.P ] | H2[ a ▷{li.Qi}i∈I ] −→P | Qk (k ∈I) (R-Bra)

H[ req ab.P ] | acc a(x: T).Q −→P | Q{b/x} | acc a(x: T).Q (R-Ses)

P ≡P ′ −→Q′ ≡Q ⇒P −→Q (R-Str)

P −→Q ⇒C[ P ] −→C[ Q ] (R-Ctx)

The only notable point is that we discard any do-catch handlers, since there is no cancellation, which explains why the H-contexts disappear.

req ab.P | a −→a | b | P (C-Req)

ab.P | a −→a | b | P (C-Out)

a(x: T).P | a −→(νx: T)(a | x | P) (C-Inp)

a ◁lk.P | a −→P | a (C-Sel)

a ▷{li.Pi}i∈I | a −→Pk | a max(I) = k (C-Bra)

do ρ catch P | a −→P | a subject(ρ) = a (C-Cat)

Only what is strictly needed will be deleted, in particular one might have expected a(x: T).P | a to result in the annihilation of P, which can be done by generating b for each b in the free names of P. However, this has several drawbacks: first, it is too absolute, since some interactions in P may not depend on a or x, and we prefer to preserve them; second, it is technically simpler, since in this setting we can use typing restrictions to avoid the creation of any b for a replication acc b(x).Q inside P, which follows our decision to never delete services; finally, it is what happens in Proof Nets for Affine Logic (see [2]).

In the cancellation of branching, (C-Bra), we choose the maximum index k which exists by our assumption that index sets are totally ordered. This is a simple way to avoid non-determinism solely by cancellation.2 Notice that it follows the pattern of activating a continuation, motivated above.

In the rule (C-Cat), we use a function subject(ρ) which returns the subject in the prefix of ρ. This is defined in the obvious way, e.g., subject(ab.P) = subject(req ab.P) = a, and similarly for the other cases. The typing system will ensure that a does not appear in P, so it is ok to leave a in the result; this is


<!-- page 9 -->

needed for canceled requests, where the a should remain until it reacts with all of them.

We clarify some of the main points:

i) Consider a(x).( Q | acc b(z).R ) | a . The replication provided on b may or

may not depend on x. A cancellation of a does not necessarily mean that b will be affected, but if x appears in R it is possible that subsequent sessions will be canceled. ii) Consider a(x).( acc x(z).Q | bx.R ) | a . As can be seen, the replicated

channel x is delegated on b, and it should not be deleted just because a is canceled. Indeed, this situation is not allowed by the restrictions in our type system. In other words, some sessions cannot be canceled. iii) Consider a(x).( Q | be.R ) | a . The session output be.R will not be canceled,

but since it is possible that e = x and in general e could appear in R, other cancellations may eventually be generated. iv) A communication discards any handlers: do ab.P catch Q | a(x: T).R −→

P | R{b/x}. The type system ensures that it is sound to discard Q, since it contains the same sessions as ab.P, except for a. v) A cancellation activates a handler, which may provide some default values

to a session, completing it or eventually re-throwing a cancellation, as in: do ab.P catch (b5.b ) | a −→b5.b | a .

## 4 Typing Affine Sessions

Types The session types we use are standard [15] with two exceptions. First, following [22] we allow a session type to evolve into a shared type. Second, we decompose shared types into accept types acc T and request types req T, following the logical principles of Affine Logic. Technically, acc T corresponds to !T ("of course T ") and req T to ?T ("why not T ") [12]. This has several technical advantages that simplify our development, for example acc T retains information on the persistence of a term with that type, since it must be replicated, which is useful for typing. Moreover, req T is the only type allowed in the context of a resource that can be used zero or more times.

T ::= end (nothing)

| !T.T (output)

| ?T.T (input)

| ⊕{li : Ti}i∈I (selection)

| & {li : Ti}i∈I (branching)

| req T (request)

| acc T (accept)

Duality The two ends of a session are composed when their types are dual, which is defined as an involution over the type constructors, similarly to Linear Logic's


<!-- page 10 -->

negation except that end is self-dual.3

.= &

⊕{li : Ti}i∈I

li : Ti

Typing Rules Typing judgements take the form:

∅= ∅◦∅

Γ = Γ1 ◦Γ2 Γ, a: T = (Γ1, a: T) ◦Γ2

!T1.T2 .= ?T1.T2 ?T1.T2 .= !T1.T2

.= ⊕

i∈I & {li : Ti}i∈I

li : Ti

i∈I

req T .= acc T acc T .= req T end .= end

P ▷Γ where Γ ::= ∅| Γ, a: T

meaning that term P has interface Γ. We shall also use Γ, ∆, Θ for interfaces.

We restrict replications to be unique, but allow multiple requests to take place against them. This means that processes can have multiple uses of a: req T, which corresponds to the logical principle of contraction. For this, we make use of the splitting relation from [22]:

Γ = Γ1 ◦Γ2 Γ, a: req T = (Γ1, a: req T) ◦(Γ2, a: req T)

Γ = Γ1 ◦Γ2 Γ, a: T = Γ1 ◦(Γ2, a: T)

In the typing rules, reqΓ stands for an interface of the shape a1 : req T1, . . . , an : req Tn. Similarly, end Γ stands for an interface a1 : end, . . . , an : end.

We also define a predicate no-requests(T), used to forbid any request type from appearing in the type of a , since this maps by duality to the deletion of a persistent accept on the other side, which we do not allow.4

no-requests(req T) = false

no-requests(acc T) = no-requests(T) no-requests(end) = true

no-requests(!T1.T2) = no-requests(T1) ∧no-requests(T2)

no-requests(?T1.T2) = no-requests(T1) ∧no-requests(T2)

no-requests(⊕{li : Si}i∈I) = no-requests(& {li : Si}i∈I) = ∧i∈Ino-requests(Si)

The typing rules are presented in Figure 2. We focus on some key points. First, we type modulo structural equivalence, a possibility suggested by [18] and used in [4]. This is because associativity of " | " does not preserve typability, i.e., a composition between P and (Q | R) may be untypable as (P | Q) | R; see

3 The expert might notice that logical negation suggests a dualisation of all components, e.g., !T.T ′ .= ?T.T

′ In fact the output type !T.T ′ and the request req T hide a duality on T, effected by the type system, so everything is compatible. 4 This method works fine until one adds a second-order fragment: then type substitutions must be carefully controlled, or some results will become slightly weaker.


<!-- page 11 -->

(Out)

P ▷Γ, a: T2 ab.P ▷(Γ, a: !T1.T2) ◦b: T1

(Sel)

P ▷Γ, a: Tk k ∈I

a ◁lk.P ▷Γ, a: ⊕{li : Ti}i∈I

(Req)

P ▷Γ

req ab.P ▷Γ ◦a: req T ◦b: T

(Par)

(ParSes)

P ▷Γ1 Q ▷Γ2 P | Q ▷Γ1 ◦Γ2

(Res)

P ▷Γ1, a: T Q ▷Γ2, a: T

(νa: T)(P | Q) ▷Γ1 ◦Γ2

(Catch)

ρ ▷Γ, a: T P ▷Γ subject(ρ) = a

do ρ catch P ▷Γ, a: T

(In)

P ▷Γ, x: T1, a: T2 a(x: T1).P ▷Γ, a: ?T1.T2

(Bra)

∀i ∈I . Pi ▷Γ, a: Ti I̸ = ∅

a ▷{li.Pi}i∈I ▷Γ, a: & {li : Ti}i∈I

(Acc)

P ▷req Γ, x: T

acc a(x: T).P ▷req Γ, a: acc T

P ▷Γ1, a: acc T Q ▷Γ2, a: req T

P | Q ▷(Γ1 ◦Γ2), a: acc T

(Str)

(Nil)

Q ▷Γ Q ≡P

P ▷Γ

0 ▷req Γ, end ∆

(Cancel)

no-requests(T)

a ▷a: T

Fig. 2. Affine Session Typing

(ParSes), (Res). This applies also to (νa) that causes similar problems. In fact, the splitting of the terms in (Res) is inspired by the work [4] which interprets sessions as propositions in a form of Intuitionistic Linear Logic. It is because of this separation of terms, which applies also to (ParSes), that we can avoid channel polarities: the two ends of a session can never become causally dependent or intermixed. The purpose of (ParSes) is to type multiple requests against a persistent accept, which explains why a: acc T remains in the conclusion. An output ab.P records a conclusion b: T1, so in fact it will compose against b: T1. Therefore !T1.T2 really means "send T1," which matches with the dual input. A cancellation a can be given any type that does not contain a request, as explained previously.

A do-catch is typed as follows: if ρ is an action on a and has an interface Γ, a: T, then the handler P will implement Γ, i.e., all sessions of ρ except for a: T which has been canceled. Of course, inside P these other sessions can be canceled anyway, which corresponds to "re-throwing" the cancellation, but they can also be implemented in whole or in part. The rule is sound, since no session is damaged, irrespectively of which term we execute, ρ or P.

Motivating the "no requests" restriction on the type of a There are pragmatic motivations behind our decision to not allow cancellation of replicated terms,


<!-- page 12 -->

(νa: T)(Q

i∈I req abi.Pi | a ) −→(νa: T)(Q

Typing the Book Purchase Example

with T1 = !string.?double.⊕

cancel: end

The type T1 is the behaviour of b inside Buyer.

For the Seller we obtain:

namely that we do not wish a request to cancel a service possibly shared by many processes. However, there are also technical challenges, stemming from the fact that multiple actions of type a: req T can appear in a well-typed term, which as we explain below can create ambiguity in cancellation reductions.

Let us assume that the no-requests(T) restriction was lifted. Now, as an example consider the composition req ab.P | a | ca.Q | c(x).acc x(y).R. First, let us look at the underlined term: it is impossible to know what is the type of a , as it could be either acc T or req T. If the type is acc T, which means that the (dual) accept is canceled, we should apply cancellation to req ab.P; if the type is req T, i.e., if a is in fact the cancellation of another request, then we should not touch reqab.P. In our example, it is easy to check that the replication will appear after a communication on c so the type of a must be req T, but in general it is not possible to determine this information (again, consider just the underlined term). Our restriction on the type of a ensures that, in a case like the underlined term above, we can be sure that the type cannot be req T, so it must be acc T and we can proceed to cancel req ab.P using (C-Req). Indeed, the full composition is not typable since in that case the type of a must be req T, and no-requests(req T) is not true. In fact, without our restriction, (C-Req) does not work any more, since it assumes a to be of type acc T, so we would need to replace it with:

i∈I(bi | Pi) | a ) (C-Req')

This is the special case in which we know the type of a must be acc T, since it is bound and all other elements are requests. This variation is more complex, and we would also have to forego the ability to use do-catch on req ab.P, so we chose not to introduce it. Even if we did use this seemingly more liberal system, we would anyway not want replications to be deleted, so it would be of limited value. The only advantage of this alternative solution is that it does not put restrictions on the shape of types assigned to a , and therefore it works also with polymorphism (i.e., a second-order setting).

It is easy to verify that the examples from Section 2 are well-typed. For the Buyer we obtain the following (for some req Γ1 and end ∆1):

Buyer ▷req Γ1, end ∆1, seller: req T1

buy: !string.& {accepted: end, rejected: end} ,

Seller ▷req Γ2, bank: req T2, seller: acc T1

with T2 = ?double.?(?string.T3).!T3.T3) and T3 = ⊕{accepted: end, rejected: end} .


<!-- page 13 -->

For the Bank we obtain:

## 5 Properties

Typed terms enjoy the expected soundness properties. In particular we have:

Theorem 1 (Subject Reduction). If P ▷Γ and P −→Q then Q ▷Γ.

Progress

Bank ▷req Γ3, bank: acc T2

Interestingly, no type structure is needed for the affine adaptations: cancellation is completely transparent. The variation of Seller with an added do-catch, doreqbank ccatchreqpaymate c, will simply need paymate: req T2 in its interface, i.e., with a type matching that of bank, but the original Seller can also be typed in the same way by weakening. Similarly, BuyerMsg has the same interface as Buyer, except that it must include print: req string, and again the two processes can be assigned the same interface by weakening, if needed. The processes CheckPriceA, CheckPriceB, and BuyerCancel can be assigned the same type for seller, namely req T1, exactly like Buyer.

Finally, as we shall see next affinity does not destroy any of the good properties we expect to obtain with session typing.

Lemma 1 (Substitution). If P ▷Γ and a̸ ∈dom(Γ) then Q{a/b} ▷Γ{a/b}.

Proof. The proof relies on several results including Lemma 1. The non-standard case is for cancellations and in particular for doρcatchP | Q. However, it can be easily checked that the substitution of ρ by P is sound, because both terms offer the same interface except for the canceled session (and dually P can be thrown away in standard communication).

Theorem 2 (Diamond property). If P ▷Γ and Q1 ←−P −→Q2 then either Q1 ≡Q2 or Q1 −→R ←−Q2.

Proof. The result is actually easy to establish, since the only critical pairs arise from multiple requests to the same replication or to the same cancellation. However, even in that case the theorem holds because: a) replications are immediately available and functional (uniform availability); b) cancellations are persistent. The fact that a can never be assigned req T simplifies the proof.

The above strong confluence property indicates that our sessions are completely deterministic, even considering the possible orderings of requests.

Our contribution to the theory of session types is well-behaved affinity, in the sense that we can guarantee that any session that ends prematurely will not affect the quality of a program. Indeed, if we simply allowed unrestricted weakening,


<!-- page 14 -->

This is proved very easily by induction on typing derivations.

Definition 1 (Blocked process). A process P is blocked if P̸ −→and:

We give some examples to clarify the definition:

H-contexts in the definition.

We can now present the main result:

Theorem 3 (Progress). If P ▷Γ then:

a) for all Q, C[ ] such that P ≡C[ Q ], Q is not blocked.

such that (νea)(P | Q) ▷Θ and (νea)(P | Q) −→.

for example by a type rule Γ ⊢0 as done in [13], but without any apparatus at the language level, it would be easy to type a process such as (νa)ab.P | b(x).Q and clearly not only a but also b would be stuck for ever. In this section we prove that this never happens to a well-typed term.

Let us write λ for a prefix ρ that is not a request, i.e., such that ρ̸ = reqab.P.

Proposition 1. If P ▷Γ and P ≡(νea)(H[ λ ] | Q) and subject(λ) = b and b ∈fn(Q) then b ∈ea.

We now define a notion of permanently blocked process, which intuitively is a process that cannot proceed in any context, either because of deadlock or because of (restricted) sessions without a dual. We will use the fact that linear communications are always under the corresponding bound name, from Proposition 1. As usual P̸ −→means that P cannot reduce.

P ≡(νea: eT) (H1[ ρ1 ] | · · · | Hn[ ρn ]) n ≥1 ∀i ∈{1, . . . , n} . subject(ρi) ∈{ea}

i) reqax.reqby.P | reqbr.reqak.Q is not considered blocked since it can reduce

properly if we add the appropriate replications. ii) (νa)(req ax.req by.P | req br.req ak.Q) is not considered blocked because

we can add a replication acc b(x).R and it will perform one step (before becoming blocked). iii) both (νa)(req ax.req by.P | req ak.req br.Q) and (νa, b)(req ax.req by.P |

req br.req ak.Q) are blocked, and indeed they have no chance of reducing. iv) a(x).by.P | b(z).ar.Q is not considered blocked, even if it can never reduce,

but from Proposition 1 we don't need to consider this case. v) (νa, b, c)(a(x).by.P | b(z).cr.Q | c(s).ae.R) is blocked; there is a cycle span-

ning all three sub-processes. vi) (νa)ab.P and (νa, b)(ab.P | b(x).Q) are blocked; this is "bad" affinity. vii) The above examples can be extended with do-catch, which explains the

b) if P̸ −→, then either P ≡0 or there exists Q, ea, ∆, Θ with Q ▷∆and Q̸ −→,

Part a) is shown by Theorem 1 and with the help of a lemma: if P ▷Γ then P is not blocked. Part b), which is similar to the formulation in [9], is shown by induction on the type derivation.

The theorem is actually very strong, since it holds also for requests, contrary to related works such as [9] where terms enjoy progress only for the linear part


<!-- page 15 -->

of sessions, i.e., where a request (that may never be activated) can permanently disable any sessions that depend on it.

Moreover, notice that b) in itself is not enough: we could have a blocked subterm in parallel to a request req a1b.P ′, then we could iterate compositions with forwarders acc ai(x).req ai+1x and there would always be a reduction. In general, the existence of a "good" Q does not exclude a "bad" one that leads to deadlock. However, from a) we know that there is no subterm that is blocked.

Also, we have not considered circular dependencies for replications; it is easy to check that they cannot lead to deadlock, and actually they cannot be typed.

Finally, we have checked that typed processes are strongly normalising, which is not so surprising since we followed closely the logical principles of Affine Logic. We leave the complete proof of this result, which uses the technique of Reducibility Candidates [12] in conjunction with Theorem 2, to a longer version. Note that Progress (Theorem 3) is in a sense more important, for two reasons: first, a system without progress can still be strongly normalising, since blocked terms are by definition irreducible; second, practical systems typically allow divergence, and in that case the progress property (which we believe can be transferred without surprises to this setting) becomes much more relevant.

## 6 Related work and future plans

We divide our discussion on the related work in three parts: relaxing linearity in session types, dealing with exceptional behaviour, and logical foundations.

The study of language constructs for exceptional behavior (including exceptional handling and compensation handling) has received significant attention; we refer the reader to a recent overview [10], while concentrating on those works more closely related to ours. Carbone at al. are probably the first to introduce exceptional behaviour in session types [7]. They do so by extending the programming language (the π-calculus) to include a throw primitive and a try-catch process. The language of types is also extended with an abstraction for a try-catch block: essentially a pair of types describing the normal and the exceptional behaviour. The extensions allow communication peers to escape, in a coordinated manner, from a dialogue and reach another point from where the protocol may progress. Carbone [6] and Capecchi et al. [5] port these ideas to the multi-party setting. Hu et al. present an extension of multi-party session types that allow to specify conversations that may be interrupted [16]. Towards this end, an interruptible type constructor is added to the type language, requiring types that govern conversations to be designed with the possible interrupt points in mind. In contrast, we propose a model where programs with and without exceptional behaviour are governed by the same (conventional) types, as it is the norm in functional and object-oriented programming languages.

Caires et al. proposed the conversation calculus [23]. The model introduces the idea of conversation context, providing for a simple mechanism to locally handle exceptional conditions. The language supports error recovery via throw and try-catch primitives. No type abstraction is proposed.


<!-- page 16 -->

Contracts take a different approach by using process-algebra languages [8] or labeled transition systems [3] for describing the communication behaviour of processes. In contrast to session types, where client-service compliance is given by a symmetric duality relation, contracts come equipped with an asymmetric notion of compliance usually requiring that a client and a service reach a successful state. In these works it is possible to end a session (usually on the client side only) prematurely, but there is no mechanism equivalent to our cancellation, no relationship with exception handling, and no clear logical foundations.

Caires and Pfenning gave a Curry-Howard correspondence relating Intuitionistic Linear Logic and session types in a synchronous π-calculus [4]. Although we do not use their types, there is a clear correspondence between !T1.T2 and JT1K ⊗JT2K, and similarly for input. The splitting of the term when composing session endpoints, in our case with (Res), which is standard from [1], was never used in sessions before the work [4]. For output (and request) we followed a different but equivalent approach in which b in ab.P is free, when in [4] it would be restricted to appear strictly under P. In fact, we did not change anything compared to the usual output rule [15], which shows that a logical system can be obtained from a standard session system simply by an adaptation of (Res) so that it plays the role of a logical "cut."

Indeed, the system we presented can be mildly adapted to obtain an embedding of typed processes to proofs of Affine Logic. In any case, our formulation allows to type more processes than Linear Logic interpretations, and to our knowledge it is the first logical account of exceptions in sessions, based on an original interpretation of weakening. Moreover, Propositional Affine Logic is decidable, a result by Kopylov [17], so there are better prospects for type inference.

As part of future work, we would like to develop an algorithmic typing system, along the lines of [22]. We also believe it would be interesting to apply our technique to multiparty sessions [14] based on Proof Nets [20].

Acknowledgments This work was supported by FCT through funding of MULTICORE project, ref. PTDC/EIA-CCO/122547/2010, and LaSIGE Strategic Project, ref. PEst-OE/EEI/UI0408/2014. We would like to thank the anonymous reviewers and also Nobuko Yoshida, Hugo Torres Vieira, Francisco Martins, and the members of the Gloss group in the University of Lisbon, for their detailed and insightful comments.

## References

1. Samson Abramsky. Computational interpretations of linear logic. Theoretical Computer Science, 111:3-57, 1993. 2. Andrea Asperti and Luca Roversi. Intuitionistic light affine logic. ACM Transactions on Compututational Logic, 3(1), 2002. 3. Mario Bravetti and Gianluigi Zavattaro. Contract-based discovery and composition of web services. In SFM, volume 5569 of LNCS, pages 261-295. Springer, 2009. 4. Luís Caires and Frank Pfenning. Session types as intuitionistic linear propositions. In CONCUR, volume 6269 of LNCS, pages 222-236. Springer, 2010.


<!-- page 17 -->

5. Sara Capecchi, Elena Giachino, and Nobuko Yoshida. Global escape in multiparty sessions. In FSTTCS, LIPIcs, pages 338-351. Schloss Dagstuhl, 2010. 6. Marco Carbone. Session-based choreography with exceptions. In PLACES, volume 241 of ENTCS, pages 35-55. Elsevier, 2009. 7. Marco Carbone, Kohei Honda, and Nobuko Yoshida. Structured interactional exceptions in session types. In CONCUR, volume 5201 of LNCS, pages 402-417. Springer, 2008. 8. Giuseppe Castagna, Nils Gesbert, and Luca Padovani. A theory of contracts for web services. ACM Transactions on Programming Languages and Systems, 31(5):1- 61, 2009. 9. Mariangiola Dezani-Ciancaglini, Ugo de' Liguoro, and Nobuko Yoshida. On progress for structured communications. In TGC'07, volume 4912 of LNCS, pages 257-275. Springer, 2008. 10. Carla Ferreira, Ivan Lanese, António Ravara, Hugo Torres Vieira, and Gianluigi Zavattaro. Results of the SENSORIA Project 2011, volume 6582 of LNCS, chapter Advanced Mechanisms for Service Combination and Transactions, pages 302-325. Springer, 2011. 11. Simon J. Gay and Malcolm J. Hole. Subtyping for session types in the pi calculus. Acta Informatica, 42(2/3):191-225, 2005. 12. Jean-Yves Girard. Linear logic. Theoretical Computer Science, 50:1-102, 1987. 13. Marco Giunti. Algorithmic type checking for a pi-calculus with name matching and session types. The Journal of Logic and Algebraic Programming, 82(8):263-281, 2013. 14. K. Honda, N. Yoshida, and M. Carbone. Multiparty asynchronous session types. In POPL, pages 273-284. ACM, 2008. 15. Kohei Honda, Vasco T. Vasconcelos, and Makoto Kubo. Language primitives and type disciplines for structured communication-based programming. In ESOP, volume 1381 of LNCS, pages 22-138. Springer, 1998. 16. Raymond Hu, Rumyana Neykova, Nobuko Yoshida, and Romain Demangeon. Practical interruptible conversations: Distributed dynamic verification with session types and Python. In RV, volume 8174 of LNCS, pages 148-130. Springer, 2013. 17. A.P Kopylov. Decidability of linear affine logic. Information and Computation, 164(1):173 - 198, 2001. 18. Robin Milner. Functions as processes. Mathematical Structures in Computer Science, 2(2):119-141, 1992. 19. Robin Milner, Joachim Parrow, and David Walker. A calculus of mobile processes, parts I and II. Information and Computation, 100(1), 1992. 20. Dimitris Mostrous. Multiparty sessions based on proof nets. In Programming Language Approaches to Concurrency and Communication-cEntric Software (PLACES), 2014. 21. Kaku Takeuchi, Kohei Honda, and Makoto Kubo. An interaction-based language and its typing system. In PARLE '94, pages 398-413. Springer, 1994. 22. Vasco T. Vasconcelos. Fundamentals of session types. Information and Computation, 217:52-70, 2012. 23. Hugo T. Vieira, Luís Caires, and João C. Seco. The conversation calculus: A model of service-oriented computation. In ESOP, volume 4960 of LNCS, pages 269-283. Springer, 2008.
