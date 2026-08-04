# Exceptional Asynchronous Session Types: Session Types without Tiers

> **Machine-generated Markdown conversion — this is a MODIFIED version.**
> Converted from `POPL19-exceptional-asynchronous-session-types.pdf` with PyMuPDF. Layout, mathematics,
> figures and tables are lossy; **quote from the PDF, not from this file.**
> Page anchors below correspond to PDF pages.

- **Citation:** Simon Fowler, Sam Lindley, J. Garrett Morris, Sara Decova. POPL 2019. doi:10.1145/3290341
- **Licence:** CC-BY 4.0 (Crossref)
- **Source:** https://homepages.inf.ed.ac.uk/slindley/papers/zap.pdf

---


<!-- page 1 -->

## Exceptional Asynchronous Session Types

Session Types without Tiers

SIMON FOWLER, The University of Edinburgh, UK SAM LINDLEY, The University of Edinburgh, UK J. GARRETT MORRIS, The University of Kansas, USA SÁRA DECOVA, The University of Edinburgh, UK

Session types statically guarantee that communication complies with a protocol. However, most accounts of session typing do not account for failure, which means they are of limited use in real applications--especially distributed applications--where failure is pervasive.

We present the first formal integration of asynchronous session types with exception handling in a functional programming language. We define a core calculus which satisfies preservation and progress properties, is deadlock free, confluent, and terminating.

We provide the first implementation of session types with exception handling for a fully-fledged functional programming language, by extending the Links web programming language; our implementation draws on existing work on effect handlers. We illustrate our approach through a running example of two-factor authentication, and a larger example of a session-based chat application where communication occurs over session-typed channels and disconnections are handled gracefully.

CCS Concepts: • Software and its engineering →Functional languages; Concurrent programming languages; Deadlocks;

Additional Key Words and Phrases: session types, asynchrony, exceptions, web programming

ACM Reference Format: Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova. 2019. Exceptional Asynchronous Session Types: Session Types without Tiers. Proc. ACM Program. Lang. 3, POPL, Article 28 (January 2019), 29 pages. https://doi.org/10.1145/3290341

## 1 INTRODUCTION

With the growth of the internet and mobile devices, as well as the failure of Moore's law, concurrency and distribution have become central to many applications. Writing correct concurrent and distributed code requires effective tools for reasoning about communication protocols. While data types provide an effective tool for reasoning about the shape of data communicated, protocols also require us to reason about the order in which messages are transmitted.

Session types [Honda 1993; Honda et al. 1998] are types for protocols. They describe both the shape and order of messages. If a program type-checks according to its session type, then it is statically guaranteed to comply with the corresponding protocol. Alas, most accounts of session types do not handle failure, which means they are of limited use in distributed settings where failure is pervasive. Inspired by work of Mostrous and Vasconcelos [2014], we present

Authors' addresses: Simon Fowler, The University of Edinburgh, UK, simon.fowler@ed.ac.uk; Sam Lindley, The University of Edinburgh, UK, sam.lindley@ed.ac.uk; J. Garrett Morris, The University of Kansas, USA, garrett@ittc.ku.edu; Sára Decova, The University of Edinburgh, UK, sara.decova@gmail.com.

Permission to make digital or hard copies of part or all of this work for personal or classroom use is granted without fee provided that copies are not made or distributed for profit or commercial advantage and that copies bear this notice and the full citation on the first page. Copyrights for third-party components of this work must be honored. For all other uses, contact the owner/author(s). © 2019 Copyright held by the owner/author(s).

2475-1421/2019/1-ART28 https://doi.org/10.1145/3290341

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 2 -->

28:2 S. Fowler et al.

TwoFactorServer ≜

?(Username, Password).⊕{

Authenticated : ServerBody, Challenge : !ChallengeKey.?Response.

⊕{Authenticated : ServerBody,

AccessDenied : End}, AccessDenied : End}

(a) Server Session Type

the first account of asynchronous session types in a functional programming language, which smoothly handles both distribution and failure. We present both a core calculus enjoying strong metatheoretical correctness properties and a practical implementation as an extension of the Links web programming language [Cooper et al. 2007].

### 1.1 Session Types

We illustrate session types with a basic example of two-factor authentication. A user inputs their credentials. If the login attempt is from a known device, then they are authenticated and may proceed to perform privileged actions. If the login attempt is from an unrecognised device, then the user is sent a challenge code. They enter the challenge code into a hardware key which yields a response code. If the user responds with the correct response code, then they are authenticated.

send M N :S where M has type A, and N is an endpoint with session type !A.S receive M : (A × S) where M is an endpoint with session type ?A.S close M : 1 where M is an endpoint with session type End

Let us also suppose we have constructs for selecting and offering a choice:

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.

TwoFactorClient ≜

!(Username, Password).&{

Authenticated : ClientBody, Challenge : ?ChallengeKey.!Response.

&{Authenticated : ClientBody,

AccessDenied : End}, AccessDenied : End}

(b) Client Session Type

Fig. 1. Two-factor Authentication Session Types

A session type specifies the communication behaviour of one endpoint of a communication channel participating in a dialogue (or session) with the other endpoint of the channel. Fig. 1 shows the session types of two channel endpoints connecting a client and a server. Fig. 1a shows the session type for the server which first receives (?) a pair of a username and password from the client. Next, the server selects (⊕) whether to authenticate the client, issue a challenge, or reject the credentials. If the server decides to issue a challenge, then it sends (!) the challenge string, awaits the response, and either authenticates or rejects the client. The ServerBody type abstracts over the remainder of the interactions, for example making a deposit or withdrawal.

Duality. The client implements the dual session type, shown in Fig. 1b. Whenever the server receives a value, the client sends a value, and vice versa. Whenever the server makes a selection, the client offers a choice (&), and vice versa. This duality between client and server ensures that each communication is matched by the other party. We denote duality with an overbar; thus TwoFactorClient = TwoFactorServer and TwoFactorServer = TwoFactorClient.

Implementing Two-factor Authentication. Let us suppose we have constructs for sending and receiving along, and for closing, an endpoint.

select ℓj M : Sj where M is an endpoint with session type ⊕{ℓi : Si}i ∈I, and j ∈I offer M {ℓi(xi) 7→Ni}i ∈I : A where M is an endpoint with session type &{ℓi : Si}i ∈I, each xi binds an endpoint with session type Si, and each Ni has type A


<!-- page 3 -->

Exceptional Asynchronous Session Types 28:3

We can now write a client implementation.

twoFactorClient : (Username × Password × TwoFactorClient) ⊸1 twoFactorClient(username, password,s) ≜

let s = send (username, password) s in offer s {Authenticated(s) 7→clientBody(s)

The twoFactorClient function takes the credentials and an endpoint of type TwoFactorClient as its arguments. The credentials are sent along the endpoint, then three choices are offered depending on whether the server authenticates the user, sends a two-factor challenge, or rejects the authentication attempt. If the server authenticates the user, then the program progresses to the main application (clientBody(s)). If the server sends a challenge, then the client receives the challenge key, and sends the response, calculated by generateResponse. Two choices are then offered according to whether the challenge response was successful. The rejection of an authentication attempt is part of the protocol and not exceptional behaviour. We can also write a server implementation.

twoFactorServer : TwoFactorServer ⊸1 twoFactorServer(s) ≜let ((username, password),s) = receive s in

### 1.2 Linear Types

wrongClient : TwoFactorClient ⊸1 wrongClient(s) ≜let t = send ("Alice", "hunter2") s in

Challenge(s) 7→let (key,s) = receive s in let s = send (generateResponse(key)) s in offer s {Authenticated(s) 7→clientBody(s)

AccessDenied(s) 7→close s; loginFailed} AccessDenied(s) 7→close s; loginFailed}

if checkDetails(username, password) then

let s = select Authenticated s in serverBody(s) else

let s = select AccessDenied s in close s

The twoFactorServer function takes an endpoint of type TwoFactorServer along which it receives the credentials, which are checked using checkDetails. If the check passes, then the server proceeds to the application body (serverBody(s)); if not, then the server notifies the client by selecting the AccessDenied branch. This particular server implementation opts to never send a challenge request.

Statically checking session types demands a substructural type system. We discuss three options: linear types, affine types, and linear types with explicit cancellation.

Simply providing constructs for sending and receiving values, and for selecting and offering choices, is insufficient for safely implementing session types. Consider the following client:

let t = send ("Bob", "letmein") s in . . .

Reuse of s allows a (username, password) pair to be sent along the same endpoint twice, violating the fundamental property of session fidelity, which states that in a well-typed program, communication over an endpoint matches its session type. To maintain session fidelity and ensure that all communication actions in a session type occur, session type systems typically require that each endpoint is used linearly--exactly once.

Exceptions. In practice, linear session types are unrealistic. Thus far, we have assumed checkDetails always succeeds, which may be plausible if checking against an in-memory store, but not if connecting to a remote database. One option would be for checkDetails to return false on

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 4 -->

28:4 S. Fowler et al.

exnServer1 : TwoFactorClient ⊸1 exnServer1(s) ≜let ((username, password),s) = receive s in

However, the above code does not type-check and is unsafe. Linear endpoint s is not used in the catch block and yet is still open if an exception is raised by checkDetails.

exnServer2 : TwoFactorServer ⊸1 exnServer2(s) ≜let ((username, password),s) = receive s in

case checkDetailsOpt(username, password) of

TwoFactorServerExn ≜?(Username, Password).⊕{

We could use select InternalError s in the None branch to yield a type-correct program, but doing so would be unsatisfactory as it clutters the protocol and the implementation with failure points.

Disconnection. The problem of failure is compounded by the possibility of disconnection. On a single machine it may be plausible to assume that communication always succeeds. In a distributed setting this assumption is unrealistic as parties may disconnect without warning. The problem is particularly acute in web applications as a client may close the browser at any point. In order to adequately handle failure we must incorporate some mechanism for detecting disconnection.

### 1.3 Affine Types

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.

failure, but that would lose information. Instead, suppose we have an exception handling construct. As a first attempt, we might try to write:

try if checkDetails(username, password) then

let s = select Authenticated s in serverBody(s) else

let s = select AccessDenied s in close s catch log("Database Error")

As a second attempt, we may decide to localise exception handling to the call to checkDetails. We introduce checkDetailsOpt, which returns Some(result) if the call is successful and None if not.

checkDetailsOpt : (Username × Password) ⊸Option(Bool) checkDetailsOpt(username, password) ≜try Some(checkDetails(username, password))

catch None

Some(res) 7→if res then let s = select Authenticated s in serverBody(s)

else let s = select AccessDenied s in close s None 7→log("Database Error")

Still the code is unsafe as it does not use s in the None branch of the case-split. However, we do now have more precise information about the type of s, since it is unused in the try block in checkDetailsOpt. One solution could be to adapt the protocol by adding an InternalError branch:

Authenticated : ServerBody, Challenge : !ChallengeKey.Response.⊕{Authenticated : ServerBody, AccessDenied : End}, AccessDenied : End, InternalError : End}

We began by assuming linear types--each endpoint must be used exactly once. One might consider relaxing linear types to affine types--each endpoint must be used at most once. Statically checked affine types form the basis of the existing Rust implementation of session types [Jespersen et al. 2015] and dynamically checked affine types form the basis of the OCaml FuSe [Padovani 2017] and Scala lchannels [Scalas and Yoshida 2016] session type libraries. Affine types present two


<!-- page 5 -->

Exceptional Asynchronous Session Types 28:5

### 1.4 Linear Types with Explicit Cancellation

exnServer3 : TwoFactorServer ⊸1 exnServer3(s) ≜let ((username, password),s) = receive s in

cancel s; log("Database Error")

Following Benton and Kennedy [2001], an exception handler try L asx in M otherwise N takes an explicit success continuation M as well as the usual failure continuation N. If checkDetails fails with an exception, then s is safely discarded using cancel, which takes an endpoint and returns the unit value. Disconnection is handled by cancelling all endpoints associated with a client. If a peer tries to read along a cancelled endpoint then an exception is thrown.

quandaries arising from endpoints being silently discarded. First, a developer receives no feedback if they accidentally forget to finish a protocol implementation. Second, if an exception is raised in an evaluation context that captures an open endpoint then the peer may be left waiting forever.

Mostrous and Vasconcelos [2014] address the difficulties outlined above through an explicit discard (or cancellation) operator. (They characterise their sessions as affine, but it is important not to confuse their system with affine type systems, as in §1.3, which allow variables to be discarded implicitly.) Their approach boils down to three key principles: endpoints can be explicitly discarded; an exception is thrown if a communication cannot succeed because a peer endpoint has been cancelled; and endpoint cancellations are propagated when endpoints become inaccessible due to an exception being thrown. They introduce a process calculus including the term a ("cancel a"), which indicates that endpoint a may no longer be used to perform communications. They provide an exception handling construct which attempts a communication action, running an exception handler if the action fails, and show that explicit cancellation is well-behaved: their calculus satisfies preservation and global progress (well-typed processes never get stuck), and is confluent.

Explicit cancellation neatly handles failure while ruling out accidentally incomplete implementations and providing a mechanism for notifying peers when an exception is raised. In this paper we take advantage of explicit cancellation to formalise and implement asynchronous session types with failure handling in a distributed functional programming language; this is not merely a routine adaptation of the ideas of Mostrous and Vasconcelos for the following reasons:

• They present a process calculus, but we work in a functional programming language. • Communication in their system is synchronous, depending on a rendezvous between sender and receiver. We require asynchronous communication, which is more amenable to implementation in a distributed setting. • Their exception handling construct is over a single communication action and does not allow nested exception handling. This design is difficult to reconcile with a functional language, as it is inherently non-compositional. Our exception handling construct is compositional.

We define a core concurrent λ-calculus, Exceptional GV (EGV), with asynchronous session-typed communication and exception handling. As with the calculus of Mostrous and Vasconcelos, an exception is raised when a communication action fails. But our compositional exception handling construct can be arbitrarily nested, and allows exception handling over multiple communication actions. Using EGV, we may implement the two factor authentication server as follows:

try checkDetails(username, password) as res in

if res then let s = select Authenticated s in serverBody(s) else let s = select AccessDenied s in close s otherwise

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 6 -->

28:6 S. Fowler et al.

let s =

try

let s = fork (λt.cancel t) in let (res,s) = receive s in close s; res as res in

fork (λt.

print ("Result: " + res) otherwise print "Error!"

(a) Cancellation and Exceptions

### 1.5 Contributions

This paper makes five main contributions:

tion workflow we outline the implementation of a chat server.

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.

let (res,t) = receive t in close t; res) in let u = fork (λv.cancel v) in let u = send s u in close u

let f = (λx.send x s) in raise;

f (5)

(c) Closures

(b) Delegation

Fig. 2. Failure Examples

We implement the constructs described by EGV as an extension to Links [Cooper et al. 2007], a functional programming language for the web. Our implementation is based on a minimal translation to effect handlers [Plotkin and Pretnar 2013].

(1) Exceptional GV (§2), a core linear lambda calculus extended with asynchronous session-typed

channels and exception handling. We prove (§3) that the core calculus enjoys preservation, progress, a strong form of confluence called the diamond property, and termination. (2) Extensions to EGV (§4) supporting exception payloads, unrestricted types, and access points

(which provide a more flexible means of session initiation). (3) The design and implementation of an extension of the Links web programming language to

support tierless web applications which can communicate using session-typed channels (§5). (4) Client and server backends for Links implementing session typing with exception handling

(§5.4), drawing on connections with effect handlers [Plotkin and Pretnar 2013]. (5) Example applications using the infrastructure (§6). In addition to our two-factor authentica-

Links is open-source and freely-available. The website can be found at http://www.links-lang.org and the source at http://www.github.com/links-lang/links. Users of the opam tool can install Links by invoking opam install links.

The rest of the paper is structured as follows: §2 presents Exceptional GV and §3 its metatheory; §4 discusses extensions to Exceptional GV; §5 describes the implementation; §6 presents a chat application written in Links; §7 discusses related work; and §8 concludes.

## 2 EXCEPTIONAL GV

In this section, we introduce Exceptional GV (henceforth EGV). GV is a core session-typed linear λ-calculus that has a tight correspondence with classical linear logic [Lindley and Morris 2015; Wadler 2014]. EGV is an asynchronous variant of GV with support for failure handling.

Due to GV's close correspondence with classical linear logic, EGV has a strong metatheory, enjoying preservation, global progress, the diamond property, and termination. Much like the simply-typed λ-calculus, this well-behaved core must be extended to be expressive enough to write larger applications. Nonetheless, the core calculus alone is expressive enough to support our two-factor authentication example, and to support server applications which gracefully handle disconnection. In §3, we show that cancellation is well-behaved, and does not violate any of the


<!-- page 7 -->

Exceptional Asynchronous Session Types 28:7

Types A, B,C ::= 1 | A ⊸B | A + B | A × B | S Session Types S,T ::= !A.S | ?A.S | End Variables x,y Terms L, M, N ::= x | λx.M | M N | () | let () = M in N | (M, N) | let (x,y) = M in N

| inl M | inr M | case L of {inl x 7→M; inr y 7→N } | fork M | send M N | receive M | close M | cancel M | raise | try L as x in M otherwise N Type Environments Γ ::= · | Γ,x : A

Fig. 3. Syntax

core properties of GV. In §4, following Lindley and Morris [2015, 2017], we extend EGV modularly with standard features of our implementation, some of which provide weaker guarantees. Channel cancellation and exceptions are orthogonal to these features.

### 2.1 Integrating Sessions with Exceptions, by Example

Integrating session types with failure handling into a higher-order functional language requires care. Fig. 2 illustrates three important cases: cancellation and exceptions, delegation, and closures. In order to initiate a session, we adopt the fork primitive of Lindley and Morris [2015]. Given a term M of type S ⊸1, the term fork M of type S creates a fresh channel with endpoints a of type S and b of type S, forks a child thread that executes M a, and returns endpoint b.

Cancellation and Exceptions. Fig. 2a forks a thread which immediately cancels its endpoint. The parent attempts to receive, but the message can never arrive so an exception is raised and the otherwise clause is invoked.

Delegation. A central feature of π-calculus is mobility of names. In session calculi sending an endpoint is known as session delegation. The code in Fig. 2b begins by forking a thread and returning endpoint s. The child is passed endpoint t on which it blocks receiving. Next, the parent forks a second child, yielding endpoint u. The second child is passed endpoint v, which is immediately discarded using cancel. Now the parent thread sends endpoint s along u. Endpoint s will never be received as the peer endpoint v of u has been cancelled. In turn, this renders s irretrievable and an exception is thrown in the first child thread, as it can never receive a value.

Closures. It is crucial that cancellation plays nicely with closures. The code in Fig. 2c defines a function f which sends its argument x along s. The parent thread then raises an exception. As s appears in the closure bound to f , which appears in the continuation and is thus discarded, s must be cancelled.

### 2.2 Syntax and Typing Rules for Terms

Fig. 3 gives the syntax of EGV. Types include unit (1), linear functions (A ⊸B), linear sums (A + B), linear tensor products (A × B), and session types (S).

Terms include variables (x) and the usual introduction and elimination forms for linear functions, unit, products, and sums. We write M; N as syntactic sugar for let () = M in N and let x = M in N for (λx.N) M. The standard session typing primitives [Lindley and Morris 2015] are as follows: fork M creates a fresh channel with endpoints a of type S and b of type S, forks a child thread that executes M a, and returns endpoint b; send M N sends M along endpoint N; receive M receives along endpoint M; and close M closes an endpoint when a session is complete.

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 8 -->

28:8 S. Fowler et al.

Term Typing Γ ⊢M :A

T-Abs

T-Var

Γ,x :A ⊢M : B

x :A ⊢x :A

Γ ⊢λx.M :A ⊸B

T-LetUnit

T-Pair

Γ1 ⊢M : 1 Γ2 ⊢N :A

T-Unit

· ⊢() : 1

Γ1, Γ2 ⊢let () = M in N :A

T-Inl

T-Inr

Γ ⊢M :A

Γ ⊢M : B

Γ ⊢inl M :A + B

Γ ⊢inr M :A + B

T-Fork

T-Send

Γ ⊢M :S ⊸1

Γ1 ⊢M :A Γ2 ⊢N : !A.S

Γ1, Γ2 ⊢send M N :S

Γ ⊢fork M :S

T-Cancel

T-Try

Γ ⊢M :S

Γ ⊢cancel M : 1

Duality S

!A.S = ?A.S ?A.S = !A.S End = End

We introduce three new term constructs to support session typing with failure handling: cancelM explicitly discards session endpoint M; raise raises an exception; and try L as x in M otherwise N evaluates L, on success binding the result to x in M and on failure evaluating N.

Explicit success continuations. Benton and Kennedy [2001] argue that:

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.

T-App

Γ1 ⊢M :A ⊸B Γ2 ⊢N :A

Γ1, Γ2 ⊢M N : B

T-LetPair

Γ1 ⊢M :A Γ2 ⊢N : B

Γ1 ⊢M :A × B Γ2,x :A,y : B ⊢N :C

Γ1, Γ2 ⊢(M, N) :A × B

Γ1, Γ2 ⊢let (x,y) = M in N :C

T-Case

Γ1 ⊢L :A + B Γ2,x :A ⊢M :C Γ2,y : B ⊢N :C

Γ1, Γ2 ⊢case L of {inl x 7→M; inr y 7→N } :C

T-Recv

T-Close

Γ ⊢M : ?A.S

Γ ⊢M : End

Γ ⊢receive M : (A × S)

Γ ⊢close M : 1

T-Raise

Γ1 ⊢L :A Γ2,x :A ⊢M : B Γ2 ⊢N : B

Γ1, Γ2 ⊢try L as x in M otherwise N : B

· ⊢raise :A

Fig. 4. Term Typing and Duality

From the points of view of programming pragmatics, rewriting and operational semantics, the syntactic construct used for exception handling in ML-like programming languages, and in much theoretical work on exceptions, has subtly undesirable features. Benton and Kennedy show that explicit success continuations avoid the subtly undesirable features they identify; correspondingly, we adopt their construct. Moreover, explicit success continuations align with the definition of handlers for algebraic effects [Plotkin and Pretnar 2013] that we use in our implementation (§5.4).

Branching and selection. Though our implementation supports select and offer directly, and we use them in examples, we omit them from the core calculus (following Lindley and Morris [2015, 2017]) as they can be encoded using sums and delegation [Dardha et al. 2017; Kobayashi 2002].

Typing. Fig. 4 gives the typing rules for EGV. As usual, linearity is enforced by splitting environments when typing subterms, ensuring T-Var takes a singleton environment, and leaf rules T-Unit and T-Raise take an empty environment. We write Γ1, Γ2 to mean the disjoint union of Γ1 and Γ2. The bulk of the rules are standard for a linear λ-calculus. Session types are related by duality. The T-Fork rule forks a thread connected by dual endpoints of a channel. The rules T-Send, T-Recv, and T-Close capture session-typed communication.


<!-- page 9 -->

Exceptional Asynchronous Session Types 28:9

Runtime Types R ::= S | S♯

Names a,b,c Terms M ::= · · · | a Values U ,V,W ::= a | λx.M | () | (V,W ) | inl V | inr V Configurations C, D, E ::= (νa)C | C ∥D | ϕM | halt | a | a(−→ V )↭b(−→ W ) Thread Flags ϕ ::= • | ◦ Top-level threads T ::= •M | halt Auxiliary threads A ::= ◦M | a | a(−→ V )↭b(−→ W ) Type Environments Γ ::= · · · | Γ,a : S Runtime Type Environments ∆::= · | ∆,a : R Evaluation Contexts E ::= [ ] | E M | V E

| let () = E in M | (E, M) | (V, E) | let (x,y) = E in M | inl E | inr E | case E of {inl x 7→M; inr x 7→N } | fork E | send E M | send V E | receive E | close E | cancel E | try E as x in M otherwise N Pure Contexts P ::= [ ] | P M | V P

| let () = P in M | (P, M) | (V, P) | let (x,y) = P in M | inl P | inr P | case P of {inl x 7→M; inr x 7→N } | fork P | send P M | send V P | receive P | close P | cancel P Thread Contexts F ::= ϕE Configuration Contexts G ::= [ ] | (νa)G | G ∥C

Syntactic Sugar

V ≜ a1 ∥· · · ∥ an where fn(V ) = {ai }i

P ≜ a1 ∥· · · ∥ an where fn(P) = {ai }i E ≜ a1 ∥· · · ∥ an where fn(E) = {ai }i

Fig. 5. Runtime Syntax

As exceptions do not return values, the rule T-Raise allows an exception to be given any type A. Rule T-Try embraces explicit success continuations as advocated by Benton and Kennedy [2001], binding a result in M if L evaluates successfully. The T-Cancel rule explicitly discards an endpoint. Naïvely implemented, cancellation violates progress: a thread could discard an endpoint, leaving a peer waiting forever. We avoid this pitfall by raising an exception when a communication action would wait forever due to cancellation.

### 2.3 Operational Semantics

We now give a small-step operational semantics for EGV.

Runtime Syntax. Fig. 5 shows the runtime syntax of EGV. We write S♯for the type of a channel which can be split into two endpoints of types S and S. Runtime types R are either session types or channel types. We extend the syntax of terms to include names ranged over by a,b,c. Depending on context, a name a is variously used to identify a channel of type S♯and each of its endpoints of type S and S. Values are standard. The semantics makes use of configurations, which are similar to π-calculus processes: (νa)C binds name a in configuration C, and C ∥D is the parallel composition of configurations C and D. Program threads take the form ϕM, where ϕ is a thread flag identifying whether the term is the main thread (•), which returns a top-level result, or a child thread (◦), which

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 10 -->

28:10 S. Fowler et al.

Term Reduction M −→M N

E-Lam (λx.M) V −→M M{V /x} E-Unit let () = () in M −→M M E-Pair let (x,y) = (V,W ) in M −→M M{V /x,W /y} E-Inl case inl V of {inl x 7→M; inr y 7→N } −→M M{V /x} E-Inr case inr V of {inl x 7→M; inr y 7→N } −→M N {V /y} E-Val try V as x in M otherwise N −→M M{V /x} E-Lift E[M] −→M E[M′], if M −→M M′

Configuration Equivalence C ≡D

C ∥(D ∥E) ≡(C ∥D) ∥E C ∥D ≡D ∥C (νa)(νb)C ≡(νb)(νa)C

C ∥(νa)D ≡(νa)(C ∥D), if a < fn(C)

a(−→ V )↭b(−→ W ) ≡b(−→ W )↭a(−→ V ) ◦() ∥C ≡C (νa)(νb)( a ∥ b ∥a(ϵ)↭b(ϵ)) ∥C ≡C Configuration Reduction C −→D

E-Fork F [fork (λx.M)] −→(νa)(νb)(F [a] ∥◦M{b/x} ∥a(ϵ)↭b(ϵ)), where a,b are fresh E-Send F [send U a] ∥a(−→ V )↭b(−→ W ) −→F [a] ∥a(−→ V )↭b(−→ W · U ) E-Receive F [receive a] ∥a(U · −→ V )↭b(−→ W ) −→F [(U ,a)] ∥a(−→ V )↭b(−→ W ) E-Close (νa)(νb)(F [close a] ∥F ′[close b] ∥a(ϵ)↭b(ϵ)) −→F [()] ∥F ′[()] E-Cancel F [cancel a] −→F [()] ∥ a E-Zap a ∥a(U · −→ V )↭b(−→ W ) −→ a ∥ U ∥a(−→ V )↭b(−→ W ) E-CloseZap F [close a] ∥ b ∥a(ϵ)↭b(ϵ) −→F [raise] ∥ a ∥ b ∥a(ϵ)↭b(ϵ) E-ReceiveZap F [receive a] ∥ b ∥a(ϵ)↭b(−→ W ) −→F [raise] ∥ a ∥ b ∥a(ϵ)↭b(−→ W ) E-Raise F [try P[raise] as x in M otherwise N] −→F [N] ∥ P E-RaiseChild ◦P[raise] −→ P E-RaiseMain •P[raise] −→halt ∥ P E-LiftC G[C] −→G[D], if C −→D E-LiftM ϕM −→ϕM′, if M −→M M′

Fig. 6. Reduction and Equivalence for Terms and Configurations

does not, and must return the unit value. A configuration has at most one main thread. As well as program threads, configurations include three special forms of thread. A zapper thread ( a) manages an endpoint a that has been cancelled, and is used to propagate failure. A halted thread (halt) arises when the main thread has crashed due to an uncaught exception. A buffer thread (a(−→ V )↭b(−→ W )) models asynchrony: −→ V and −→ W are sequences of values ready to be received along endpoints a and b respectively. We find it useful to distinguish top-level threads T (main threads and halted threads) from auxiliary threads A (child threads, zapper threads, and buffer threads).

Environments. We extend type environments Γ to include runtime names of session type and introduce runtime type environments ∆, which type both buffer endpoints of session type and channels of type S♯for some S, but not object variables.

Contexts. Evaluation contexts E are set up for standard left-to-right call-by-value evaluation. Pure contexts P are those evaluation contexts that include no exception handling frames. Thread contexts F support reduction in program threads. Configuration contexts G support reduction under ν-binders and parallel composition.

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 11 -->

Exceptional Asynchronous Session Types 28:11

Free Names. We let the meta operation fn(−) denote the set of free names in a term, type environment, buffer environment, value, configuration, pure context, or evaluation context.

Syntactic Sugar. We follow the standard convention that parallel composition of configurations associates to the right. We write V , P, and E, as shorthand for the parallel composition of zapper threads for each free name in values V , pure contexts P, and evaluation contexts E, respectively.

Following prior work on linear functional languages with session types [Gay and Vasconcelos 2010; Lindley and Morris 2015, 2016, 2017], we present the semantics of EGV via a deterministic reduction relation on terms (−→M), an equivalence relation on configurations (≡), and a nondeterministic reduction relation on configurations (−→). We write =⇒for the relation ≡−→≡. Fig. 6 presents reduction and equivalence rules for terms and configurations.

Term Reduction. Reduction on terms is standard call-by-value β-reduction.

Configuration Equivalence. A running program can make use of the standard structural π-calculus equivalence rules [Milner 1999] of associativity and commutativity of parallel composition, name restriction reordering, and scope extrusion. Formally, equivalence is defined as the smallest congruence relation satisfying the equivalence axioms in Figure 6. We incorporate a further rule to allow buffers to be treated symmetrically and two garbage collection rules, allowing completed child threads and cancelled empty buffers to be discarded.

Communication and Concurrency. The E-Fork rule creates two fresh names for each endpoint of a channel, returning one name and substituting the other in the body of the spawned thread, as well as creating a channel with two empty buffers. The E-Send and E-Receive rules send to and receive from a buffer. The E-Close rule discards an empty buffer once a session is complete.

Cancellation. The E-Cancel rule cancels an endpoint by creating a zapper thread. The E-Zap rule ensures that when an endpoint is cancelled, all endpoints in the buffer of the cancelled endpoint are also cancelled: it dequeues a value from the head of the buffer and cancels any endpoints contained within the dequeued value ( U ). It is applied repeatedly until the buffer is empty.

Raising Exceptions. Following Mostrous and Vasconcelos [2014], an exception is raised when it would be otherwise impossible for a communication action to succeed. The E-ReceiveZap rule raises an exception if an attempt is made to receive along an endpoint whose buffer is empty and whose peer endpoint has been cancelled. Similarly, E-CloseZap raises an exception if an attempt is made to close a channel where the peer endpoint has been cancelled. There is no rule for the case where a thread tries to send a value along a cancelled endpoint; the free names in the communicated value must eventually be cancelled, but this is achieved through E-Zap. We choose not to raise an exception in this case since to do so would violate confluence, which we discuss in more detail in §3.4. Not raising exceptions on sends to dead peers is standard in languages such as Erlang.

Handling Exceptions. The E-Raise rule invokes the otherwise clause if an exception is raised, while also cancelling all endpoints in the enclosing pure context. If an unhandled exception occurs in a child thread, then all free endpoints in the evaluation context are cancelled and the thread is terminated (E-RaiseChild). If the exception is in the main thread then all free endpoints are cancelled and the main thread reduces to halt (E-RaiseMain).

### 2.4 Synchrony

As we are interested in writing distributed applications, we consider asynchronous session types. However, our semantics adapts straightforwardly to the synchronous setting, where a send to a

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 12 -->

28:12 S. Fowler et al.

cancelled peer must also raise an exception:

E-SyncComm F [send V a] ∥F ′[receive a] −→F [a] ∥F ′[(V,a)] E-SyncSendZap F [send V a] ∥ a −→F [raise] ∥ V ∥ a ∥ a E-SyncRecvZap F [receive a] ∥ a −→F [raise] ∥ a ∥ a

(νa)( a ∥ a) ∥C ≡C

## 3 METATHEORY

Even in the presence of channel cancellation and exceptions, EGV retains GV's strong metatheory [Lindley and Morris 2015]. The central property of session-typed systems is session fidelity: all communication follows the prescribed session types. Session fidelity follows as a corollary of preservation of configuration typing under reduction.

Session calculi with roots in linear logic are deadlock-free as interpreting the logical cut rule as a combination of name restriction and parallel composition necessarily ensures acyclicity [Caires and Pfenning 2010]. It is also possible to use deadlock-freedom to derive a global progress result. We prove that global progress holds even in the presence of channel cancellation. (Our proof is direct, not requiring catalyser processes [Carbone et al. 2014; Mostrous and Vasconcelos 2014].) We also prove that EGV is confluent and terminating. Full proofs of the results can be found in the online appendix [Fowler et al. 2018].

### 3.1 Runtime Typing

To state our main results we require typing rules for names and configurations. These are given in Fig. 7. As names a must be substituted for variables at runtime, we extend the term typing rules with T-Name. The configuration typing judgement has the shape Γ; ∆⊢ϕ C, which states that under type environment Γ, runtime environment ∆, and thread flag ϕ, configuration C is well-typed. We additionally require that fn(Γ) ∩fn(∆) = ∅. Thread flags ensure that there can be at most one top-level thread which can return a value: • denotes a configuration with a top-level thread and ◦denotes a configuration without. The main thread returns the result of running a program. Any configuration C such that Γ; ∆⊢• C has exactly one main thread or halted thread as a subconfiguration. We write Γ; ∆⊢• C : A whenever the derivation of Γ; ∆⊢• C contains a subderivation of the form

Γ′ ⊢M : A

Γ′; . ⊢• •M or ·; · ⊢• halt

We say that a C is a ground configuration if there exists A such that ·; · ⊢• C : A and A contains no session types or function types.

The T-Nu rule introduces a channel name; T-Connect1 and T-Connect2 connect two configurations over a channel; and T-Mix composes two configurations that share no channels. The latter three rules use the + operator to combine the flags from subconfigurations. The T-Main and T-Child rules introduce main and child threads. Child threads always return the unit value. The T-Halt rule types the halt configuration, which signifies that an unhandled exception has occurred in the main thread. The T-Zap rule types a zapper thread, given a single name in the type environment. The T-Buffer rule ensures that buffers contain values corresponding to the session types of their endpoints. This is the only rule that consumes names from the runtime environment. Buffers rely on two auxiliary judgements. The queue typing judgement Γ ⊢−→ V : −→A states that under type environment Γ, the sequence of values −→ V have types −→A. The session slicing operator S/−→A captures reasoning about session types discounting values contained in the buffer. The session

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 13 -->

Exceptional Asynchronous Session Types 28:13

Session Slicing S/−→A

Term Typing Γ ⊢M : A

T-Name

S/ϵ = S !A.S/A · −→A = S/−→A

a :S ⊢a :S

Configuration Typing Γ; ∆⊢ϕ C

T-Nu

Γ; ∆,a : S♯⊢ϕ C

Γ; ∆⊢ϕ (νa)C

T-Connect1

Γ1,a : S; ∆1 ⊢ϕ1 C Γ2; ∆2,a : S ⊢ϕ2 D

Γ1, Γ2; ∆1, ∆2,a : S♯⊢ϕ1+ϕ2 C ∥D

T-Main

T-Child

T-Halt

Γ ⊢M : A

Γ ⊢M : 1

Γ; · ⊢• •M

·; · ⊢• halt

Γ; · ⊢◦◦M

Flag Combination ϕ1 + ϕ2 = ϕ3

• + ◦= • ◦+ • = • ◦+ ◦= ◦ • + • undefined

Environment Reduction Γ; ∆−→Γ′; ∆′

S −→S′

S −→S′

Γ,a : S; ∆−→Γ,a : S′; ∆

Γ; ∆,a : S −→Γ; ∆,a : S′

### 3.2 Preservation

Preservation for the functional fragment of EGV is standard.

Lemma 3.1 (Preservation (Terms)). If Γ ⊢M : A and M −→M M′, then Γ ⊢M′ : A.

Ψ ::= · | Ψ,a : S

Preservation of typing by configuration reduction holds only for closed configurations.

Queue Typing Γ ⊢−→ V : −→A

Γ1 ⊢V : A Γ2 ⊢−→ V : −→A

Γ1, Γ2 ⊢V · −→ V : A · −→A

· ⊢ϵ : ϵ

T-Mix

Γ1; ∆1 ⊢ϕ1 C Γ2; ∆2 ⊢ϕ2 D

Γ1, Γ2; ∆1, ∆2 ⊢ϕ1+ϕ2 C ∥D

T-Connect2

Γ1; ∆1,a : S ⊢ϕ1 C Γ2,a : S; ∆2 ⊢ϕ2 D

Γ1, Γ2; ∆1, ∆2,a : S♯⊢ϕ1+ϕ2 C ∥D

T-Buffer

S/−→A = S′/−→B Γ1 ⊢−→ V : −→A Γ2 ⊢−→ W : −→B

T-Zap

Γ1, Γ2;a : S,b : S′ ⊢◦a(−→ V )↭b(−→ W )

a : S; · ⊢◦ a

Session Type Reduction S −→S′

?A.S −→S !A.S −→S

S −→S′

Γ; ∆,a : S♯−→Γ; ∆,a : S′♯

Fig. 7. Runtime Typing

types of two buffer endpoints are compatible if they are dual up to values contained in the buffer. The partiality of the slicing operator coupled with the duality constraint ensures that at least one queue in a buffer is always empty.

Given a relation R, we write R? for its reflexive closure. We write Ψ for the restriction of type environments Γ to contain runtime names but no variables:

Theorem 3.2 (Preservation). If Ψ; ∆⊢ϕ C and C −→C′, then there exist Ψ′, ∆′ such that Ψ; ∆−→? Ψ′; ∆′ and Ψ′; ∆′ ⊢ϕ C′.

Proof. By induction on the derivation of C −→C′, making use of Lemma 3.1, and lemmas for subconfiguration typeability and replacement. □

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 14 -->

28:14 S. Fowler et al.

Typing and Configuration Equivalence. As is common in logically-inspired session-typed functional languages [Lindley and Morris 2015, 2017], typeability of configurations is not preserved by equivalence. Consider Γ; ∆⊢ϕ (νa)(νb)(C ∥(D ∥E)) with a ∈fn(C), b ∈fn(D), and a,b ∈fn(E). But Γ; ∆⊬ϕ (νa)(νb)((C ∥D) ∥E). Fortunately this looseness of the equivalence relation is unproblematic: we may always safely re-associate parallel composition (for example, Γ; ∆⊢ϕ (νa)(νb)((C ∥E) ∥D); see the online appendix), and any reduction sequence which uses ill-typed equivalences may be replaced by one that does not.

Theorem 3.3 (Preservation Modulo Eqivalence). If Ψ; ∆⊢ϕ C, C ≡D, and D −→D′, then: (1) There exists some E ≡D and some E′ such that Ψ; ∆⊢ϕ E and E −→E′

(2) There exist Ψ′, ∆′ such that Ψ; ∆−→? Ψ′; ∆′ and Ψ′; ∆′ ⊢ϕ E′

(3) D′ ≡E′

Proof. The only non-trivial reductions are those involving a synchronisation with a buffer (E-Send, E-Receive, E-Close, E-Zap, E-CloseZap, E-ReceiveZap). The only equivalence rule that can lead to an ill-typed configuration is associativity of parallel composition

C ∥(D ∥E) ≡(C ∥D) ∥E

where both compositions arise from the T-Connect1 and T-Connect2 rules. The only reason to apply the associativity rule from left-to-right is to enable threads inside C and D to synchronise. But for synchronisation to be possible there must exist a name a such that a ∈fn(C) and a ∈fn(D). Because the left-hand-side of the equation is well-typed, we know that C and E have no names in common, that D and E share a name, and that the right-hand-side must be well-typed as there is still exactly one channel connecting each of the parallel compositions. The argument for applying the rule from right-to-left is symmetric. In summary, any ill-typed use of equivalence is useless. □

### 3.3 Progress

To prove that EGV enjoys a strong notion of progress we identify a canonical form for configurations. We prove that every well-typed configuration is equivalent to a well-typed configuration in canonical form, and that ground configurations can always either reduce, or are equivalent to either a value or halt.

The functional fragment of EGV enjoys progress.

Lemma 3.4 (Progress: Open Terms). If Ψ ⊢M : A, then either:

• M is a value; • there exists some M′ such that M −→M M′; or • M has the form E[M′], where M′ is a session typing primitive of the form: forkV , send V W , receive V , close V , or cancel V .

Proof. By induction on the derivation of Ψ ⊢M : A. □

To reason about progress of configurations, we characterise canonical forms, which make explicit the property that at most one name is shared between threads. Recall that A ranges over auxiliary threads and T over top-level threads (Fig. 5). Let M range over configurations of the form:

A1 ∥· · · ∥Am ∥T

Definition 3.5 (Canonical Form). A configuration C is in canonical form if there is a sequence of names a1, . . . ,an, a sequence of configurations A1, . . . , An, and a configuration M, such that:

C = (νa1)(A1 ∥(νa2)(A2 ∥· · · ∥(νan)(An ∥M) . . .))

where ai ∈fn(Ai) for each i ∈1..n.

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 15 -->

Exceptional Asynchronous Session Types 28:15

The following lemma implies that communication topologies are always acyclic.

Lemma 3.6. If Γ; ∆⊢ϕ C and C = G[D ∥E], then fn(D) ∩fn(E) is either ∅or {a} for some a.

Proof. By induction on the derivation of Γ; ∆⊢ϕ C; the only interesting rules are those for parallel composition. As the environments are well-formed, fn(Γ) ∩fn(∆) = ∅. Thus, T-Connect1 and T-Connect2 allow exactly one name to be shared, whereas T-Mix forbids sharing of names. □

All well-typed configurations can be written in canonical form.

Theorem 3.7 (Canonical Forms). Given C such that Γ; ∆⊢• C, there exists some D ≡C such that Γ; ∆⊢• D and D is in canonical form.

Proof. By induction on the count of ν-bound variables, following Lindley and Morris [2015] and making use of Lemma 3.6. The additional features of EGV do not change the essential argument. The full proof can be found in the online appendix. □

Next, we characterise threads which are ready to perform a communication action on an endpoint.

Definition 3.8. We say that term M is ready to perform an action on name a if M is about to send on, receive on, close, or cancel a. Formally:

ready(a, M) ≜∃E.(M = E[send V a]) ∨(M = E[receive a]) ∨(M = E[close a]) ∨(M = E[cancel a])

Using the notion of a ready thread, we may classify a notion of progress for open configurations.

Theorem 3.9 (Progress: Open). Suppose Ψ; ∆⊢• C, where C is in canonical form. Let C = (νa1)(A1 ∥(νa2)(A2 ∥· · · ∥(νan)(An ∥M) . . .)). Either there exists some C′ such that C =⇒C′, or: (1) For 1 ≤i ≤n, each auxiliary thread Ai is either:

(a) a child thread ◦M for which there exists a ∈{aj | 1 ≤j ≤i} ∪fn(Ψ) such that ready(a, M); (b) a zapper thread ai; or (c) a buffer. (2) M = A′

1 ∥· · · ∥A′ m ∥T such that for 1 ≤j ≤m: (a) A′

j is either: (i) a child thread ◦N with N = () or ready(a, N) for some a ∈{ai | 1 ≤i ≤n}∪fn(Ψ)∪fn(∆); (ii) a zapper thread a for some a ∈{ai | 1 ≤i ≤n} ∪fn(Ψ) ∪fn(∆); or (iii) a buffer. (b) Either T = •N, where N is either a value or ready(a, N) for some a ∈{ai | 1 ≤i ≤

n} ∪fn(Ψ) ∪fn(∆); or T= halt.

Proof. The result follows from a more verbose, but finer-grained, property which we prove by induction on the derivation of Ψ; ∆⊢• C. Full details are in the online appendix. □

This theorem tells us that open reduction cannot "go wrong". A progress theorem states that either reduction is possible or the configuration is a value. Conditions 1(a)(b)(c) and 2(a)(b) constitute a suitable generalisation of 'value'.

By restricting attention to closed environments, we obtain a tighter progress property.

Theorem 3.10 (Progress: Closed). Suppose ·; · ⊢• C where C is in canonical form. Let C = (νa1)(A1 ∥(νa2)(A2 ∥· · · ∥(νan)(An ∥M) . . .)). Either there exists some C′ such that C =⇒C′, or: (1) For 1 ≤i ≤n, each auxiliary thread Ai is either:

(a) a child thread ◦M for some M such that ready(ai, M); or

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 16 -->

28:16 S. Fowler et al.

(b) a zapper thread ai; or (c) a buffer. (2) Either M = •W for some value W , or M = halt.

The above progress results do not specifically mention deadlock. However, Lemma 3.6 ensures deadlock-freedom. Nevertheless, communication can still be blocked if an endpoint appears in the value returned by the main thread. A conservative way of disallowing endpoints in the result is to insist that the return type of the program be free of session types and function types (closures may capture endpoints). All configurations of such a programs are ground configurations.

Theorem 3.11 (Global Progress). Suppose C is a ground configuration. Either there exists some C′ such that C =⇒C′; or C ≡•V ; or C ≡halt.

Proof. As a consequence of Theorem 3.10, either there exists some C′ such that C =⇒C′, or C Y=⇒and each thread Ai must be a zapper, a buffer, or ready to perform an action. If C Y=⇒, since C is ground, by Lemma 3.6, we have that no thread can be ready to perform an action. Thus, each Ai must be either ◦(), a zapper, or an empty buffer. The result then follows by the garbage collection congruences of Fig. 6. □

### 3.4 Confluence

EGV enjoys a strong form of confluence known as the diamond property [Barendregt 1984].

Theorem 3.12 (Diamond Property). If Ψ; ∆⊢ϕ C, and C =⇒D1, and C =⇒D2, then either D1 ≡D2, or there exists some D3 such that D2 =⇒D3 and D2 =⇒D3.

Proof. First, note that −→M is entirely deterministic and hence confluent due to the call-byvalue, left-to-right ordering imposed by evaluation contexts. By linearity, we know that endpoints to different buffers may not be shared, so it follows that communication actions on different channels may be performed in any order. Asynchrony and cancellation introduce two critical pairs which may be resolved in a single step; full details can be found in the online appendix. □

Remark. The system becomes non-confluent if we choose to raise an exception when sending to a cancelled buffer. Suppose that instead of the current semantics, we were to replace E-Send with the following two rules:

(νb)(F [send U a] ∥a(−→ V )↭b(−→ W ) ∥ϕM) −→ (νb)(F [a] ∥a(−→ V )↭b(−→ W · U ) ∥ϕM) F [send U a] ∥ b ∥a(−→ V )↭b(−→ W ) −→ F [raise] ∥ b ∥ U ∥a(−→ V )↭b(−→ W )

Then, sending and cancelling peer endpoints of a buffer results in a non-convergent critical pair:

(νb)(F [send U a] ∥F ′[cancel b] ∥a(−→ V )↭b(−→ W ))

(νb)(F [a] ∥F ′[cancel b] ∥a(−→ V )↭b(−→ W · U )) (νb)(F [send U a] ∥F ′[()] ∥ b ∥a(−→ V )↭b(−→ W ))

(νb)(F [a] ∥F ′[()] ∥ b ∥a(−→ V )↭b(−→ W · U )) (νb)(F [raise] ∥F ′[()] ∥ b ∥ U ∥a(−→ V )↭b(−→ W ))

In either case, the endpoints contained in U will still eventually be cancelled, thus preservation and global progress still hold. However, the lack of confluence affects exactly when the exception is raised in context F . This decision has practical significance, in that it characterises the race between sending a message and propagating a cancellation notification.

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 17 -->

Exceptional Asynchronous Session Types 28:17

### 3.5 Termination

As EGV is linear, it has an elementary strong normalisation proof.

Theorem 3.13 (Strong Normalisation). If Ψ; ∆⊢ϕ C, then there are no infinite =⇒reduction sequences from C.

Proof. Let the size of a configuration be the sum of the sizes of the abstract syntax trees of all of the terms contained in its main threads, child threads, and buffers, modulo exhaustively applying the garbage collection equivalences from left-to-right. The size of a configuration is invariant under ≡and strictly decreases under −→, hence =⇒reduction must always terminate. □

We conjecture that the strong normalisation result continues to hold in the presence of unrestricted types or shared channels for session initiation, but the proof technique is necessarily more involved. We believe that a logical relations argument along the lines of Pérez et al. [2012] or a CPS translation along the lines of Lindley and Morris [2016] would suffice.

## 4 EXTENSIONS

### 4.1 User-defined Exceptions with Payloads

In order to focus on the interplay between exceptions and session types we have thus far considered handling a single kind of exception. In practice it can be useful to distinguish between multiple kinds of user-defined exception, each of which may carry a payload.

Consider again handling the exception in checkDetails. An exception may arise if the database is corrupt, or if there are too many connections. We might like to handle each case separately:

exnServer4(s) ≜

let ((username, password),s) = receive s in try checkDetails(username, password) as res in

if res then let s = select Authenticated s in serverBody(s) else let s = select AccessDenied s in close s unless

DBCorrupt(y) 7→cancel s; log("Database Corrupt: " + y) TooManyConnections(y) 7→cancel s; log("Too many connections: " + y)

An exception in checkDetails might be raised by the term raise DatabaseCorrupt(filename), for example. Our approach generalises straightforwardly to handle this example.

Syntax. Figure 8 shows extensions to EGV for exceptions with payloads. We introduce a type of exceptions, Exn. We assume a countably infinite set X ∈E of exception names, and a type schema function Σ(X) = A mapping exception names to payload types. We extend raise to take a term of type Exn as its argument. Finally, we generalise tryLasxinMotherwiseN to tryLasxinMunlessH, where H is an exception handler with clauses {Xi(yi) 7→Ni}i, such that Xi is an exception name; yi binds the payload; and Ni is the clause to be evaluated when the exception is raised.

Typing Rules. The TP-Exn rule ensures that an exception's payload matches its expected type. The TP-Raise and TP-Try rules are the natural extensions of T-Raise and T-Try.

Semantics. Our presentation is similar to operational accounts of effect handlers; the formulation here is inspired by that of Hillerström et al. [2017]. To define the semantics of the generalised exception handling construct, we first introduce the auxiliary function handled(E), which defines the exceptions handled in a given evaluation context:

handled(P) = ∅ handled(try E as x in M unless H) = handled(E) ∪dom(H) handled(E) = handled(E′), if E is not a try and E′ is the immediate subcontext of E

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 18 -->

28:18 S. Fowler et al.

Syntax

Types A, B ::= · · · | Exn Terms L, M, N ::= · · · | X(M) | raise M | try L as x in M unless H Exception Handlers H ::= {Xi(xi) 7→Ni }i Runtime Syntax

Evaluation Contexts E ::= · · · | raise E | try E as x in M unless H

Term typing Σ(X) = A Γ ⊢M :A

TP-Try

Γ1 ⊢L :A Γ2,x :A ⊢M : B (Γ2,yi : Σ(Xi) ⊢Ni : B)i Γ1, Γ2 ⊢try L as x in M unless {Xi(yi) 7→Ni }i : B

TP-Exn

TP-Raise

Σ(X) = A Γ ⊢M :A

Γ ⊢M : Exn

Γ ⊢X(M) : Exn

Γ ⊢raise M :A

Term and Configuration Reduction M −→M N C −→D

EP-Val try V as x in M unless H −→M M{V /x} EP-Raise

F [try E[raise X(V )] as x in M unless H] −→ F [N {V /y}] ∥ E where X < handled(E)

(X(y) 7→N) ∈H EP-RaiseChild ◦E[raise X(V )] −→ E ∥ V where X < handled(E) EP-RaiseMain • E[raise X(V )] −→ halt ∥ E ∥ V where X < handled(E)

Fig. 8. User-defined Exceptions with Payloads

The EP-Raise rule handles an exception. The side conditions ensure that the exception is caught by the nearest matching handler and is handled by the appropriate clause. As with plain EGV, all free names are safely discarded. The EP-RaiseChild and EP-RaiseMain rules cover the cases where an exception is unhandled. Due to the use of the handled function we no longer require pure contexts. All of EGV's metatheoretic properties (preservation, global progress, confluence, and termination) adapt straightforwardly to this extension.

### 4.2 Unrestricted Types and Access Points

Unrestricted (intuitionistic) types allow some values to be used in a non-linear fashion. Access points [Gay and Vasconcelos 2010] provide a more flexible method of session initiation than fork, allowing two threads to dynamically establish a session. Both features are useful in practice: unrestricted types because some data is naturally multi-use, and access points because they admit cyclic communication topologies supporting racey stateful servers such as chat servers. Access points decouple spawning a thread from establishing a session. An access point has the unrestricted type AP(S); we write un(A) to mean that A is unrestricted and un(Γ) if un(Ai) for all xi : Ai ∈Γ. Figure 9 shows the syntax, typing rules, and reduction rules for EGV extended with access points.

Unrestricted Types. To support unrestricted types, we introduce a splitting judgement (Γ = Γ1+Γ2), which allows variables of unrestricted type to be shared across sub-environments, but requires linear variables to be used only in a single sub-environment. We relax rule T-Var to allow the use of unrestricted environments, and adapt all rules containing multiple subterms to use the splitting judgement. We detail T-App in the figure; the adaptations of other rules are similar. While unrestricted types are useful in general, we show the specific case of unrestricted access points.

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 19 -->

Exceptional Asynchronous Session Types 28:19

Syntax

Types A ::= · · · | AP(S) Access Point Names z Terms M ::= · · · | z | spawn M | newS | request M | accept M Configurations C ::= · · · | (νz)C | z(X, Y) Type Environments Γ ::= · · · | Γ,z : AP(S) Runtime Type Environments ∆::= · · · | ∆,z : S

Splitting Γ = Γ1 + Γ2

un(A)

Γ,x : A = (Γ1,x : A) + (Γ2,x : A)

· = · + ·

Typing Γ ⊢M : A

T-Var

T-App

x : A ∈Γ un(Γ)

Γ ⊢x : A

TA-Spawn

TA-New

Γ ⊢M : 1

Γ ⊢spawn M : 1

Γ ⊢newS : AP(S)

Reduction C −→D

E-Spawn F [spawn M] −→ F [()] ∥◦M E-New F [newS] −→ (νz)(F [z] ∥z(ϵ,ϵ)) z is fresh E-Accept F [accept z] ∥z(X, Y) −→ (νa)(F [a] ∥z({a} ∪X, Y)) a is fresh E-Reqest F [request z] ∥z(X, Y) −→ (νa)(F [a] ∥z(X, {a} ∪Y)) a is fresh E-Match z({a} ∪X, {b} ∪Y) −→ z(X, Y) ∥a(ϵ)↭b(ϵ)

Configuration Typing Γ; ∆⊢ϕ C

TA-ApName

TA-Ap

Γ,z : AP(S); ∆,z :S ⊢ϕ C

un(Γ)

Γ; ∆⊢ϕ (νz)C

Γ,z : AP(S); X :S, Y :S,z :S ⊢◦z(X, Y)

fork M ≜let ap = newS in spawn (M (accept ap)); request ap

Reduction rules. We let z range over access point names. Configuration (νz)C denotes binding access point name z in C, and z(X, Y) is an access point with name z and two sets X and Y containing endpoints to be matched.

Γ = Γ1 + Γ2 Γ,x : A = (Γ1,x : A) + Γ2

Γ = Γ1 + Γ2 Γ,x : A = Γ1 + (Γ2,x : A)

Γ = Γ1 + Γ2 Γ1 ⊢M : A ⊸B Γ2 ⊢N : A

Γ ⊢M N : B ...

TA-Reqest

TA-Accept

Γ ⊢M : AP(S)

Γ ⊢M : AP(S)

Γ ⊢accept M : S

Γ ⊢request M : S

TA-ConnectN

Γ = Γ1 + Γ2

−−→ b :T ⊢ϕ1 C

Γ1, −−→ a :S; ∆1,

−−→ a :S ⊢ϕ2 D

Γ2, −−→ b :T; ∆2,

−−−→ a :S♯,

−−−−→ b :T ♯⊢ϕ1+ϕ2 C ∥D

Γ; ∆1, ∆2,

Fig. 9. Access Points

Access points. The spawn M construct spawns M as a new thread, newS creates a fresh access point, and request M and accept M generate fresh endpoints that are matched up nondeterministically to form channels. With access points we can macro-express fork:

Rule E-Spawn creates a new child thread but, unlike fork, returns the unit value instead of creating a channel and returning an endpoint. Rule E-New creates a new access point with fresh name z. Rules E-Accept and E-Reqest create a fresh name a, returning the newly-created name

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 20 -->

28:20 S. Fowler et al.

to the thread, and adding the name to sets X and Y respectively. Rule E-Match matches two endpoints a and b contained in X and Y, and creates an empty buffer a(ϵ)↭b(ϵ).

Configuration typing. Configuration typing judgements again have the shape Γ; ∆⊢ϕ C. Whereas Γ may contain unrestricted variables, ∆remains entirely linear.

Read bottom-up, rule TA-ApName adds an unrestricted reference z : AP(S) to Γ, and a linear entry z : S to ∆. Rule TA-Ap types an access point configuration. We write X : S for a1 : S, . . . ,an : S, where X = {a1, . . . ,an}. For an access point z(X, Y) to be well-typed, ∆must contain z : S, along with the names in X having type S and the names in Y having type S. Rule T-ConnectN generalises T-Connect1 and T-Connect2 to allow any number of channels to communicate across a buffer; this therefore introduces the possibility of deadlock.

Interaction with cancellation. We need no additional reduction rules to account for interaction between access points and channel cancellation. Should an endpoint waiting to be matched be cancelled, it is paired as usual, and interaction with its associated buffer raises an exception:

a ∥F [receive b] ∥z({a}, {b}) =⇒ a ∥F [receive b] ∥z(ϵ,ϵ) ∥a(ϵ)↭b(ϵ)

=⇒ a ∥F [raise] ∥ b ∥z(ϵ,ϵ) ∥a(ϵ)↭b(ϵ)

Metatheory. By decoupling process and channel creation we lose the guarantee that the communication topology is acyclic, and therefore introduce the possibility of deadlock. Preservation continues to hold--in fact, we gain a stronger preservation result since the use of TA-ConnectN allows typeability to be preserved by equivalences.

Theorem 4.1 (Preservation Modulo Eqivalence (Access Points)). If Ψ; ∆⊢ϕ C and C =⇒D, then there exist Ψ′, ∆′ such that Ψ; ∆−→Ψ′; ∆′ and Ψ′; ∆′ ⊢ϕ D.

Proof. By induction on the derivation of C −→D and preservation by ≡. Full details can be found in the online appendix. □

Alas, the introduction of cyclic topologies and therefore the loss of deadlock-freedom necessarily violates global progress. Nevertheless, a weaker form of progress still holds: if a configuration does not reduce, then it is due to deadlock rather than cancellation.

Theorem 4.2 (Progress (Access Points)). Suppose ·; · ⊢ϕ C and C Y=⇒. Then each thread in C is either a value; a buffer; a zapper thread; an access point; requesting or accepting on an access point; or ready to perform a communication action.

If C contains a thread ϕM and ready(a, M) for some name a, then C contains some buffer a(ϵ)↭b(−→ W ), and C does not contain a zapper thread b.

Proof. We can prove a similar property for open configurations by induction on the derivation of Ψ; ∆⊢ϕ C; the above result arises as a corollary and by inspection of the reduction rules. □

In the presence of access points confluence and termination no longer hold: access points are nondeterministic and can encode higher-order state and hence fixpoints via Landin's knot.

### 4.3 Recursive Session Types

Recursive session types support repeating protocols. The extension of EGV with recursive session types is standard [Lindley and Morris 2016, 2017] and orthogonal to the main ideas of this paper, so we do not spell out the details here. The implementation (§5) does provide recursive session types.

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 21 -->

Exceptional Asynchronous Session Types 28:21

## 5 SESSION TYPES WITHOUT TIERS

In this section we describe our extensions to Links to support exception handling, as well as extensions to the Links concurrency runtimes to support distribution. Links [Cooper et al. 2007] is a statically-typed, ML-inspired, impure functional programming language designed for the web. Links is designed to allow code for all "tiers" of a web application--client, server, and database--to be written in a single language. Lindley and Morris [2017] extend Links with first-class session types, relying on lightweight linear typing [Mazurak et al. 2010] and row polymorphism [Rémy 1994]. We extend their work to account for distributed web applications, which amongst other things necessitates handling failure.

### 5.1 The Links Model

Links provides a uniform language for web applications. Client code is compiled to JavaScript, server code is interpreted, and database queries are compiled to SQL. Each client and server has its own concurrency runtime, providing lightweight processes and message passing communication. Earlier versions of Links [Cooper et al. 2007] invoked a fresh copy of the server per server request and communication between client and server was via RPC calls. Advances such as WebSockets allow socket-like bidirectional asynchronous communication between client and server, in turn allowing richer applications where data (for example, comments on a GitHub pull request) flows more freely between client and server. Moving to a model based on lightweight threads and session-typed channels avoids the inversion of control inherent in RPC-style systems, and allows development to be driven by the communication protocol.

Links now adopts a persistent application server model, incorporating client-server communication using session-typed channels. Since channels are a location-transparent abstraction, we also optionally allow the abstraction of client-to-client communication, routed through the server.

### 5.2 Concurrency

Links provides typed actor-style concurrency where processes have a single incoming message queue and can send asynchronous messages. Lindley and Morris [2017] extend Links with sessiontyped channels, using Links' process-based model but replacing actor mailboxes with session-typed channels. We extend their implementation to support distribution and failure handling.

The client relies on continuation-passing style (CPS), trampolining, and co-operative threading. Client code is compiled to CPS, and explicit yield instructions are inserted at every function application. When a process has yielded a given number of times, the continuation is pushed to the back of a queue, and the next process is pulled from the front of the queue. While modern browsers are beginning to integrate tail-recursion, and we have updated the Links library to support it, adoption is not yet widespread. Thus, we periodically discard the call stack using a trampoline. Cooper [2009] discusses the Links client concurrency model in depth. The server implements concurrency on top of the OCaml lwt library [Vouillon 2008], which provides lightweight cooperative threading. At runtime, a channel is represented as a pair of endpoint identifiers:

(Peer endpoint, Local endpoint)

Endpoint identifiers are unique. If a channel (a,b) exists at a given location, then that location should contain a buffer for b.

### 5.3 Distributed Communication

To support bidirectional communication between client and server we use WebSockets [Fette and Melnikov 2011]. A WebSocket connection is initiated by a client request to the server. The server generates a web page and a unique identifier for the connection. Messages sent by the server prior

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 22 -->

28:22 S. Fowler et al.

to the connection being established are buffered and delivered once it has been established. A JSON protocol is used to encode messages such as access point operations, remote session messages, and endpoint cancellation notifications.

It is possible that one client will hold one endpoint of a channel, and another client will hold the other endpoint. In order to provide the illusion of client-to-client communication, we route the communication between the two clients via the server. The server maintains a map

Endpoint ID 7→Location

where Location is either Server or Client(ID), where ID identifies a particular client. The map is updated if a new connection is established; an endpoint is sent as part of a message; or a client disconnects. The server also maintains a map

Client ID 7→[Channel]

associating each client with the publicly-facing channels residing on that client, where Channel is a pair of endpoints (a,b) such thatb is the endpoint residing on the client. Much like TCP connections, WebSocket connections raise an event when a connection is disconnected. Upon receiving such an event, all channels associated with the client are cancelled, and exceptions are invoked as per the exception handling mechanism described in §2 and §5.4.

Distributed Delegation. It is possible to send endpoints as part of a message. Session delegation in the presence of distributed communication requires some care to ensure that messages are delivered to the correct participant; our implementation adapts the algorithms of Hu et al. [2008]. Further details can be found in the online appendix.

### 5.4 Session Typing with Failure Handling

Effect Handlers. Effect handlers [Plotkin and Pretnar 2013] provide a modular approach to programming with user-defined effects. Exception handlers are a special case of effect handlers. Consequently, we leverage the existing implementation of effect handlers in Links [Hillerström and Lindley 2016; Hillerström et al. 2017]. In §4 we generalise try −as −in −otherwise−to accommodate user defined exceptions. Effect handlers generalise further to support what amounts to resumable exceptions in which the handler has access not only to a payload, but also the delimited continuation (i.e. evaluation context) from the point at which the exception was raised up to the handler, allowing effect handlers to implement arbitrary side-effects; not just exceptions. We translate exception handling as follows.

JraiseK = do raise Jtry L as x in M otherwise NK = handle JLK with

return x 7→JMK raise r 7→cancel r; JNK

The introduction form do op invokes an operation op (which may represent raising an exception or some other effect). The elimination form handle M withH runs effect handler H on the computation M. In general an effect handler H consists of a return clause of the form return x 7→N, which behaves just like the success continuation (x in N) of an exception handler, and a collection of operation clauses, each of the form op ®p r 7→N, specifying how to handle an operation analogously to how exception handler clauses specify how to handle an exception, except that as well as binding payload parameters ®p, an operation clause also binds a resumption parameter r. The resumption r binds a closure representing the continuation up to the nearest enclosing effect handler, allowing control to pass back to the program after handling the effect. In the case of our translation, the raise operation has no payload, and rather than invoking the resumption r we cancel it, assuming the natural extension of cancellation to arbitrary linear values, whereby all free names in the value

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 23 -->

Exceptional Asynchronous Session Types 28:23

are cancelled (r being bound to the current evaluation context reified as a value). A formalisation of linear effect handlers for session typing is outside the scope of this paper and left as future work.

Raising exceptions. An exception may be raised either explicitly through raise (desugared to do raise), or a blocked receive where the peer endpoint has been cancelled. Thus, we know statically where exceptions may be raised. To support cancellation of closures on the client, we adorn closures with an explicit environment field that can be directly inspected. Currently, Links does not closure-convert continuations on the client, so we use a workaround to simulate cancelling a resumption (as required by the translation J−K). When compiling client code, for each occurrence of do raise, we compile a function that inspects all affected variables and cancels any affected endpoints in the continuation. For each occurrence of receive, we compile a continuation to cancel affected endpoints to be invoked by the runtime system if the receive operation fails.

### 5.5 Distributed Exceptions

Our implementation fully supports the semantics described in §2. The concurrency runtime at each location maintains a set of cancelled endpoints.

Cancellation. Suppose endpoint a is connected to peer endpoint b. If a is cancelled, then all endpoints in the queue for a are also cancelled according to the E-Zap rule. If a and b are at the same location, then a is added to the set of cancelled endpoints. If they are at different locations, then a cancellation notification for a is routed to b's location. Zapper threads are modelled in the implementation by recording sets of cancelled endpoints and propagating cancellation messages.

Failed communications. Again, suppose endpoint a is connected to peer endpoint b. Should a process attempt to read from a when the buffer for a is empty, then the runtime will check to see whether b is in the set of cancelled endpoints. If so, then a is cancelled and an exception is raised in the blocked process; if not, the process is suspended until a message is ready. Should the runtime later add b to the set of cancelled endpoints, then again a is cancelled and an exception raised. These actions implement the E-ReceiveZap rule.

Disconnection. To handle disconnection, the server maintains a map from client IDs to the list of endpoints at the associated client. WebSockets--much like TCP sockets--raise a closed event on disconnection. Consequently, when a connection is closed, the runtime looks up the endpoints owned by the terminated client and notifies all other clients containing the peer endpoints.

## 6 EXAMPLE: A CHAT APPLICATION

In this section we outline the design and implementation of a web-based chat application in Links making use of distributed session-typed channels. We write the following informal specification:

• To initialise, a client must: - connect to the chat server; then - send a nickname; then - receive the current topic and list of nicknames. • After initialisation the client is connected and can: - send a chat message to the room; or - change the room's topic; or - receive messages from other users; or - receive changes of topic from other users. • Clients cannot connect with a nickname that is already in use in the room. • All participants should be notified whenever a participant joins or leaves the room.

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 24 -->

28:24 S. Fowler et al.

typename ChatClient = !Nickname.

[&| Join:

?(Topic, [Nickname], ClientReceive).ClientSend,

Nope:End |&];

typename ClientReceive =

[&| Join : ?Nickname .ClientReceive,

Chat : ?(Nickname, Message).ClientReceive,

NewTopic : ?Topic .ClientReceive,

Leave : ?Nickname .ClientReceive

|&];

typename ClientSend =

[+| Chat : ?Message.ClientSend,

Topic : ?Topic .ClientSend |+];

typename ChatServer = ~ChatClient;

typename WorkerSend = ~ClientReceive;

typename WorkerReceive = ~ClientSend;

Fig. 10. Chat Application Session Types

Session Types. We can encode much of the specification more precisely as a session type, as shown in Figure 10. The client begins by sending a nickname, and then offers the server a choice of a Join message or a Nope message. In the former case, the client then receives a triple containing the current topic, a list of existing nicknames, and an endpoint (of type ClientReceive) for receiving further updates from the server; and may then continue to send messages to the server as a connected client endpoint (of type ClientSend). (Observe the essential use of session delegation.) In the latter case, communication is terminated. The intention is that the server will respond with Nope if a client with the supplied nickname is already in the chat room (the details of this check are part of the implementation, not part of the communication protocol).

The ClientReceive endpoint allows the client to offer a choice of four different messages: Join,

Chat, NewTopic, or Leave. In each case the client then receives a payload (depending on the choice, a nickname, pair of nickname and chat message, or topic change) before offering another choice. The

ClientSend endpoint allows the client to select between two different messages: Chat and NewTopic. In each case the client subsequently sends a payload (a chat message or a new topic) before selecting another choice. The chat server communicates with the client along endpoints with dual types.

How can session types help? The connect function (Fig. 11a) is run when a client enters a nickname. First, the client requests a fresh channel of type ChatClient from access point wap of type

AP(ChatServer). Next, the client obtains the content of the DOM input box for the nickname by calling getInputContents(nameBoxId), where nameBoxId is the DOM ID for the nickname entry box. Next, the client sends the nickname to the server and waits for a response; in the case of a Join message, the client receives the room data and an incoming message channel, and calls the beginChat function. In the case of a Nope message, an error is printed and the session ends.

Now, suppose the developer forgets to write code to check the server response (Fig. 11b). This implementation is incorrect since there is a communication mismatch: the server is expecting to

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 25 -->

Exceptional Asynchronous Session Types 28:25

fun connect() {

var s = request(wap);

var nick = getInputContents(nameBoxId);

var s = send(nick, s);

offer(s) {

case Join(s) ->

var ((topic, nicks, incoming), s) =

receive(s);

beginChat(topic, nicks, incoming, s)

case Nope(s) ->

print("Nickname '" ^^ nick ^^ "' already taken")

}

}

(a) Correct connect function

Fig. 11. Implementations of connect function

Disconnection. Figure 12b shows the implementation of a worker process which receives messages from a client. The worker takes the nickname of the client, as well as a channel endpoint of type

WorkerReceive (which is the dual of ClientSend). The server offers the client a choice of sending a message (Chat), or changing topic (NewTopic); in each case, the associated data is received and a message dispatched to the supervisor process by calling chat or newTopic. When a client closes its connection to the server, all associated endpoints are cancelled. Consequently, an exception will be raised when evaluating the offer or receive expressions. To handle disconnection, we wrap the function in an exception handler, which recursively calls worker if the interaction is successful, and notifies the supervisor that the user has left via a call to leave if an exception is raised.

Additional examples. We have concentrated on the chat server example for exposition, but have also implemented an extended chat server and a multiplayer game. These can be found at http://www.github.com/SimonJF/distributed-links-examples.

## 7 RELATED WORK

### 7.1 Session Types with Failure Handling

fun connect() {

var s = request(wap);

var nick = getInputContents(nameBoxId);

var s = send(nick, s);

var ((topic, nicks, incoming), s) =

receive(s);

beginChat(topic, nicks, incoming, s)

}

(b) Incorrect connect function

accept or reject the request to join the room, whereas the client is expecting to receive data about the room. However, since s has type ChatClient but does not follow the protocol, Links catches the communication mismatch statically. Similarly, Links will statically detect an unused endpoint (e.g. the developer forgets to finish a protocol) or an endpoint being used more than once, as in §1.2.

Architecture. Figure 12a depicts the architecture of the chat application. Each client has a process which sends messages over a distributed session channel of type ClientSend to its own worker process on the server, which in turn sends internal messages to a supervisor process containing the state of the chat room. These messages trigger the supervisor process to broadcast a message to all chat clients over a channel of type ~ClientReceive. As is evident from the figure, the communication topology is cyclic; in order to construct this topology the code makes essential use of access points.

Carbone et al. [2008] provide the first formal basis for exceptions in a session-typed process calculus. Our approach provides significant simplifications: zapper threads provide a simpler semantics and remove the need for their queue levels, meta-reduction relation, and liveness protocol.

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 26 -->

28:26 S. Fowler et al.

Client 1

Server

CC

CS

Client 1

Worker 1

S

CR

Supervisor

Client 2

CR

S

Client 2

Worker 2

CC CS

CC = ChatClient CS = ChatServer CR = ClientReceive S = Supervisor

(a) Architecture

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.

sig worker : (Nickname, WorkerReceive) ~> ()

fun worker(nick, c) {

try {

offer(c) {

case Chat(c) ->

var (msg, c) = receive(c);

chat(nick, msg); c

case NewTopic(c) ->

var (topic, c) = receive(c);

newTopic(topic); c

}

} as (c) in {

worker(nick, c)

} otherwise { leave(nick) }

}

(b) Worker Implementation

Fig. 12. Chat Application Architecture and Worker Implementation

Our work draws on that of Mostrous and Vasconcelos [2014], who introduce the idea of cancellation. Our work differs from theirs in several key ways. Their system is a process calculus; ours is a λ-calculus. Their channels are synchronous; ours are asynchronous. Their exception handling construct scopes over a single action; ours scopes over an arbitrary computation.

Caires and Pérez [2017] describe a core, logically-inspired process calculus supporting nondeterminism and abortable behaviours encoded via a nondeterminism modality. Processes may either provide or not provide a prescribed behaviour; if a process attempts to consume a behaviour that is not provided, then its linear continuation is safely discarded by propagating the failure of sessions contained within the continuation. Their approach is similar in spirit to our zapper threads. Additionally, they give a core λ-calculus with abortable behaviours and exception handling, and define a type-preserving translation into their core process calculus.

Our approach differs in several important ways. First, our semantics is asynchronous, handling the intricacies involved with cancelling values contained in message queues. Second, we give a direct semantics to EGV, whereas Caires and Pérez rely on a translation into their underlying process calculus. Third, to handle the possibility of disconnection, our calculus allows any channel to be discarded, whereas they opt for an approach more closely resembling checked exceptions, aided by a monadic presentation.

The above works are all theoretical. Backed by our theoretical development, our implementation integrates session types and exceptions, extending Links.

Multiparty Session Types. Fowler [2016] describes an Erlang implementation of the Multiparty Session Actor framework proposed by Neykova and Yoshida [2014, 2017b] with a limited form of failure recovery; Neykova and Yoshida [2017a] present a more comprehensive approach, based on refining existing Erlang supervision strategies. Chen et al. [2016] introduce a formalism based on multiparty session types [Honda et al. 2016] that handles partial failures by transforming programs to detect possible failures at a set of statically determined synchronisation points. These approaches rely on a fixed communication topology. Delegation implies location transparency, thus we must consider dynamic topologies. Adameit et al. [2017] describe a synchronous multiparty session calculus to handle link failures in distributed systems. They introduce optional blocks, inspired by


<!-- page 27 -->

Exceptional Asynchronous Session Types 28:27

subsessions [Demangeon and Honda 2012]; progress is maintained by specifying a set of default values to use should the subsession fail.

### 7.2 Session Types and Distribution

Hu et al. [2008] introduce Session Java (SJ), which allows distributed session-based communication in the Java programming language. Hu et al. are the first to present the challenges of distributed delegation along with distributed algorithms which address those challenges. We adapt their algorithms to web applications. SJ provides statically scoped exception handling, propagating exceptions to ensure liveness, but this feature is not formalised.

Scalas and Yoshida [2016] introduce lchannels, a library implementation of session types in Scala; their approach detects duplicate endpoint use at runtime. By virtue of the translation into the linear π-calculus introduced by Kobayashi [2002] and later expanded on by Dardha et al. [2017], lchannels is particularly amenable to distribution. Scalas et al. [2017] build upon this approach to translate a multiparty session calculus into the linear π-calculus, providing the first distributed implementation of multiparty session types to support delegation.

### 7.3 Session Types via Affine Types

Rust [Matsakis and Klock II 2014] provides ownership types [Clarke 2003], ensuring that an object has at most one owner. Jespersen et al. [2015] use Rust's ownership types to encode affine session types, but since affine endpoints can be discarded implicitly, their library does not guarantee progress. Although it is not possible to distinguish between dynamic failure and a developer forgetting to finish an implementation, our semantics can be implemented using Rust's destructor mechanism, enabling a progress property [Kokke 2018].

## 8 CONCLUSION AND FUTURE WORK

Session types allow protocol conformance to be checked statically. The prevailing consensus has hitherto been to require that endpoints be used linearly to enforce session fidelity and prevent premature discarding of open channels. We have argued that in order to write realistic applications in the presence of distribution and failure, linearity should be supplemented with an explicit cancellation operation. We show that, even in the presence of channel cancellation, our core calculus is well-behaved, being deadlock-free, type sound, confluent, and terminating.

In tandem with the formal development, we have developed an extension of the Links programming language to support distributed session-based communication for web applications, thus providing the first implementation of asynchronous session types with failure handling in a functional programming language. Our implementation leverages recent work on effect handlers.

Future work. Our implementation combines linearity and effect handlers. Linear effect handlers are new, and a ripe area of study in their own right; we plan to formalise session-typed concurrency and failure handling directly in terms of linear effect handlers. Multiparty session types [Honda et al. 2016] are yet to be included as a first-class construct of a core functional language. A natural starting point is to identify a λ-calculus into which we can translate the MCP calculus of Carbone et al. [2016] and then investigate how our approach adapts to the multiparty setting.

ACKNOWLEDGMENTS Thanks to James McKinna and the anonymous reviewers for detailed comments and suggestions. This work was supported by EPSRC grants EP/L01503X/1 (EPSRC CDT in Pervasive Parallelism) and EP/K034413/1 (From Data Types to Session Types--A Basis for Concurrency and Distribution), and an LFCS internship.

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 28 -->

28:28 S. Fowler et al.

REFERENCES

Manuel Adameit, Kirstin Peters, and Uwe Nestmann. 2017. Session types for link failures. In FORTE (Lecture Notes in

Computer Science), Vol. 10321. Springer, 1-16. H. P. Barendregt. 1984. The Lambda Calculus Its Syntax and Semantics (revised ed.). Vol. 103. North Holland. Nick Benton and Andrew Kennedy. 2001. Exceptional Syntax. Journal of Functional Programming 11, 4 (2001), 395-410. Luís Caires and Jorge A Pérez. 2017. Linearity, control effects, and behavioral types. In ESOP (Lecture Notes in Computer

Science). Springer, 229-259. Luís Caires and Frank Pfenning. 2010. Session types as intuitionistic linear propositions. In CONCUR (Lecture Notes in

Computer Science), Vol. 10. Springer, 222-236. Marco Carbone, Ornela Dardha, and Fabrizio Montesi. 2014. Progress as compositional lock-freedom. In COORDINATION

(Lecture Notes in Computer Science). Springer, 49-64. Marco Carbone, Kohei Honda, and Nobuko Yoshida. 2008. Structured interactional exceptions in session types. In CONCUR

(Lecture Notes in Computer Science). Springer, 402-417. Marco Carbone, Sam Lindley, Fabrizio Montesi, Carsten Schürmann, and Philip Wadler. 2016. Coherence generalises duality:

A logical explanation of multiparty session types. In CONCUR (LIPIcs), Vol. 59. Schloss Dagstuhl - Leibniz-Zentrum fuer Informatik, 33:1-33:15. Tzu-Chun Chen, Malte Viering, Andi Bejleri, Lukasz Ziarek, and Patrick Eugster. 2016. A type theory for robust failure

handling in distributed systems. In FORTE (Lecture Notes in Computer Science), Vol. 9688. Springer, 96-113. David Gerard Clarke. 2003. Object Ownership and Containment. Ph.D. Dissertation. New South Wales, Australia. AAI0806678. Ezra Cooper. 2009. Programming Language Features for Web Application Development. Ph.D. Dissertation. University of

Edinburgh. Ezra Cooper, Sam Lindley, Philip Wadler, and Jeremy Yallop. 2007. Links: Web programming without tiers. In FMCO (Lecture

Notes in Computer Science). Springer, 266-296. Ornela Dardha, Elena Giachino, and Davide Sangiorgi. 2017. Session types revisited. Inf. Comput. 256 (2017), 253-286. Romain Demangeon and Kohei Honda. 2012. Nested protocols in session types. In CONCUR (Lecture Notes in Computer

Science), Vol. 7454. Springer, 272-286. Ian Fette and Alexey Melnikov. 2011. The WebSocket Protocol. RFC 6455. RFC Editor. 70 pages. http://www.rfc-editor.org/

rfc/rfc6455.txt Simon Fowler. 2016. An Erlang implementation of multiparty session actors. In ICE (EPTCS), Vol. 223. 36-50. Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova. 2018. Exceptional Asynchronous Session Types: Session

Types without Tiers (extended version). http://www.simonjf.com/writing/zap-extended.pdf. (2018). Simon J Gay and Vasco T Vasconcelos. 2010. Linear type theory for asynchronous session types. Journal of Functional

Programming 20, 1 (2010), 19-50. Daniel Hillerström and Sam Lindley. 2016. Liberating effects with rows and handlers. In TyDe@ICFP. ACM, 15-27. Daniel Hillerström, Sam Lindley, Robert Atkey, and K. C. Sivaramakrishnan. 2017. Continuation passing style for effect

handlers. In FSCD (LIPIcs), Vol. 84. Schloss Dagstuhl - Leibniz-Zentrum fuer Informatik, 18:1-18:19. Kohei Honda. 1993. Types for dyadic interaction. In CONCUR (Lecture Notes in Computer Science). Springer, 509-523. Kohei Honda, Vasco T Vasconcelos, and Makoto Kubo. 1998. Language primitives and type discipline for structured

communication-based programming. In ESOP (Lecture Notes in Computer Science). Springer, 122-138. Kohei Honda, Nobuko Yoshida, and Marco Carbone. 2016. Multiparty asynchronous session types. Journal of the ACM

(JACM) 63, 1 (2016), 9. Raymond Hu, Nobuko Yoshida, and Kohei Honda. 2008. Session-based distributed programming in Java. In ECOOP (Lecture

Notes in Computer Science). Springer, 516-541. Thomas Bracht Laumann Jespersen, Philip Munksgaard, and Ken Friis Larsen. 2015. Session types for Rust. In WGP. ACM,

13-22. Naoki Kobayashi. 2002. Type systems for concurrent programs. In 10th Anniversary Colloquium of UNU/IIST (Lecture Notes

in Computer Science), Vol. 2757. Springer, 439-453. Wen Kokke. 2018. rusty-variation: a library for deadlock-free session-typed communication in Rust. https://github.com/

wenkokke/rusty-variation. (2018). Sam Lindley and J. Garrett Morris. 2015. A semantics for propositions as sessions. In ESOP (Lecture Notes in Computer

Science), Vol. 9032. Springer, 560-584. Sam Lindley and J Garrett Morris. 2016. Talking bananas: structural recursion for session types. In ICFP. ACM, 434-447. Sam Lindley and J Garrett Morris. 2017. Lightweight functional session types. In Behavioural Types: from Theory to Tools.

River Publishers, 265-286. Nicholas D. Matsakis and Felix S. Klock II. 2014. The Rust language. In HILT. ACM, 103-104. Karl Mazurak, Jianzhou Zhao, and Steve Zdancewic. 2010. Lightweight linear types in System F°. In TLDI. ACM, 77-88. Robin Milner. 1999. Communicating and mobile systems: the pi calculus. Cambridge university press.

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.


<!-- page 29 -->

Exceptional Asynchronous Session Types 28:29

Dimitris Mostrous and Vasco Thudichum Vasconcelos. 2014. Affine Sessions. In COORDINATION (Lecture Notes in Computer

Science), Vol. 8459. Springer, 115-130. Rumyana Neykova and Nobuko Yoshida. 2014. Multiparty session actors. In COORDINATION (Lecture Notes in Computer

Science), Vol. 8459. Springer, 131-146. Rumyana Neykova and Nobuko Yoshida. 2017a. Let it recover: multiparty protocol-induced recovery. In CC. ACM, 98-108. Rumyana Neykova and Nobuko Yoshida. 2017b. Multiparty session actors. Logical Methods in Computer Science 13, 1 (2017). Luca Padovani. 2017. A simple library implementation of binary sessions. Journal of Functional Programming 27 (2017), e4. Jorge A Pérez, Luís Caires, Frank Pfenning, and Bernardo Toninho. 2012. Linear logical relations for session-based

concurrency. In ESOP (Lecture Notes in Computer Science). Springer, 539-558. Gordon D. Plotkin and Matija Pretnar. 2013. Handling algebraic effects. Logical Methods in Computer Science 9, 4 (2013). Didier Rémy. 1994. Type inference for records in a natural extension of ML. In Theoretical Aspects Of Object-Oriented

Programming, Carl A. Gunter and John C. Mitchell (Eds.). MIT Press, Cambridge, MA, 67-95. Alceste Scalas, Ornela Dardha, Raymond Hu, and Nobuko Yoshida. 2017. A linear decomposition of multiparty sessions

for safe distributed programming. In ECOOP (LIPIcs), Vol. 74. Schloss Dagstuhl - Leibniz-Zentrum fuer Informatik, 24:1-24:31. Alceste Scalas and Nobuko Yoshida. 2016. Lightweight session programming in scala. In ECOOP (LIPIcs), Vol. 56. Schloss

Dagstuhl - Leibniz-Zentrum fuer Informatik, 21:1-21:28. Jérôme Vouillon. 2008. Lwt: a cooperative thread library. In ML. ACM, 3-12. Philip Wadler. 2014. Propositions as sessions. Journal of Functional Programming 24, 2-3 (2014), 384-418.

Proc. ACM Program. Lang., Vol. 3, No. POPL, Article 28. Publication date: January 2019.
