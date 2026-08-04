<!-- Generated from runners-in-action.pdf with pypdf 6.14.2; page boundaries preserved. Consult the PDF for equations and layout. -->



<!-- Page 1 -->

Runners in action
Danel Ahman and Andrej Bauer
Faculty of Mathematics and Physics
University of Ljubljana, Slovenia
Abstract. Runners of algebraic eﬀects, also known as comodels, pro-
vide a mathematical model of resource management. We show that they
also give rise to a programming concept that models top-level external
resources, as well as allows programmers to modularly deﬁne their own
intermediate “virtual machines”. We capture the core ideas of program-
ming with runners in an equational calculusλcoop, which we equip with
a sound and coherent denotational semantics that guarantees the lin-
ear use of resources and execution of ﬁnalisation code. We accompany
λcoop with examples of runners in action, provide a prototype language
implementation inOCaml, as well as aHaskell library based onλcoop.
Keywords: Runners, comodels, algebraic eﬀects, resources, ﬁnalisation.
1 Introduction
Computational eﬀects, such as exceptions, input-output, state, nondeterminism,
and randomness, are an important component of general-purpose programming
languages, whether they adopt functional, imperative, object-oriented, or other
programming paradigms. Even pure languages exhibit computational eﬀects at
the top level, so to speak, by interacting with their external environment.
In modern languages, computational eﬀects are often structured usingmon-
ads [22,23,36], or algebraic eﬀects and handlers[12,28,30]. These mechanisms
excel at implementation of computational eﬀects within the language itself. For
instance, the familiar implementation of mutable state in terms of state-passing
functions requires no native state, and can be implemented either as a monad or
using handlers. One is naturally drawn to using these techniques also for deal-
ing with actual eﬀects, such as manipulation of native memory and access to
hardware. These are represented inside the language as algebraic operations (as
in Eff [4]) or a monad (in the style ofHaskell’sIO), but treated specially by
the language’s top-level runtime, which invokes corresponding operating system
functionality. While this approach works in practice, it has some unfortunate
downsides too, namelylack of modularity and linearity, andexcessive generality.
Lackofmodularityiscausedbyhavingtheexternalresourceshard-codedinto
the top-level runtime. As a result, changing which resources are available and
how they are implemented requires modiﬁcations of the language implementa-
tion. Additional complications arise when a language supports several operating
systems and hardware platforms, each providing their own, diﬀerent feature set.
arXiv:1910.11629v2  [cs.PL]  18 Apr 2020

<!-- Page 2 -->

2 Danel Ahman and Andrej Bauer
One wishes that the ingenuity of the language implementors were better sup-
ported by a more ﬂexible methodology with a sound theoretical footing.
Excessive generality is not as easily discerned, because generality of program-
ming concepts makes a language expressive and useful, such as general algebraic
eﬀects and handlers enabling one to implement timeouts, rollbacks, stream redi-
rection[30],async&await[16],andconcurrency[9].However,theﬂipsideofsuch
expressive freedom is the lack of any guarantees about how external resources
will actually be used. For instance, consider a simple piece of code, written in
Eff-like syntax, which ﬁrst opens a ﬁle, then writes to it, and ﬁnally closes it:
let fh = open "hello.txt" in write (fh, "Hello, world."); close fh
What this program actually does depends on how the operationsopen, write,
and close are handled. For all we know, an enveloping handler may intercept the
write operation and discard its continuation, so thatclose never happens and
the ﬁle is not properly closed. Telling the programmer not to shoot themselves
in the foot by avoiding such handlers is not helpful, because the handler may
encounter an external reason for not being able to continue, say a full disk.
Evenworse,externalresourcesmaybemisusedaccidentallywhenwecombine
two handlers, each of which works as intended on its own. For example, if we
combine the above code with a non-deterministicchoose operation, as in
let fh = open "greeting.txt" in
let b = choose () in
if b then write (fh, "hello") else write (fh, "good bye"); close fh
and handle it with the standard non-determinism handler
handler { return xÑ [x], choose () kÑ return (append (k true) (k false)) }
The resulting program attempts to close the ﬁle twice, as well as write to it twice,
because the continuation k is invoked twice when handlingchoose. Of course,
with enough care all such situations can be dealt with, but that is beside the
point. It is worth sacriﬁcing some amount of the generality of algebraic eﬀects
and monads in exchange for predictable and safe usage of external computational
eﬀects, so long as the vast majority of common use cases are accommodated.
Contributions We address the described issues by showing how to design a
programming language based onrunners of algebraic eﬀects. We review runners
in §2 and recast them as a programming construct in §3. In §4, we presentλcoop,
a calculus that captures the core ideas of programming with runners. We provide
a coherent and sound denotational semantics forλcoop in §5, where we also prove
that well-typed code is properly ﬁnalised. In §6, we show examples of runners in
action.Thepaperisaccompaniedbyaprototypelanguage Coopanda Haskell
library Haskell-Coop, based onλcoop, see §7. The relationship betweenλcoop
and existing work is addressed in §8, and future possibilities discussed in §9.
The paper is also accompanied by an online appendix (https://arxiv.org/
abs/1910.11629) that provides the typing and equational rules we omit in §4.

<!-- Page 3 -->

Runners in action 3
Runners are modular in that they can be used not only to model the top-
level interaction with the external environment, but programmers can also use
them to deﬁne and nest their own intermediate “virtual machines”. Our runners
are eﬀectful: they may handle operations by calling further outer operations,
and raise exceptions and send signals, through which exceptional conditions and
runtime errors are communicated back to user programs in a safe fashion that
preserves linear usage of external resources and ensures their proper ﬁnalisation.
We achievesuitable generalityfor handling of external resources by showing
how runners provide implementations of algebraic operations together with a
natural notion of ﬁnalisation, and a strong guarantee that in the absence of
external kill signals the ﬁnalisation code is executed exactly once (Thm. 7). We
argue that for most purposes such discipline is well worth having, and giving up
the arbitrariness of eﬀect handlers is an acceptable price to pay. In fact, as will
be apparent in the denotational semantics, runners are simply a restricted form
of handlers, which apply the continuation at most once in a tail call position.
Runners guaranteelinear usage of resourcesnot through a linear or unique-
ness type system (such as in theClean programming language [15]) or a syntac-
tic discipline governing the application of continuations in handlers, but rather
by a design based on the linear state-passing technique studied by Møgelberg
and Staton [21]. In this approach, a computational resource may be implemented
without restrictions, but is then guaranteed to be used linearly by user code.
2 Algebraic eﬀects, handlers, and runners
We begin with a short overview of the theory of algebraic eﬀects and handlers,
as well as runners. To keep focus on how runners give rise to a programming
concept, we work naively in set theory. Nevertheless, we use category-theoretic
language as appropriate, to make it clear that there are no essential obstacles to
extending our work to other settings (we return to this point in §5.1).
2.1 Algebraic eﬀects and handlers
There is by now no lack of material on the algebraic approach to structuring
computational eﬀects. For an introductory treatment we refer to [5], while of
course also recommend the seminal papers by Plotkin and Power [25,28]. The
brief summary given here only recalls the essentials and introduces notation.
An(algebraic) signatureis given by a setΣ ofoperation symbols, and for each
opPΣ its operation signatureop :Aop⇝Bop, whereAop andBop are called the
parameter and arity set. AΣ-structure M is given by a carrier set|M|, and
for each operation symbol op P Σ, a map opM : AopˆpBop ñ |M|q Ñ |M|,
whereñ is set exponentiation. ThefreeΣ-structure TreeΣpXq over a setX is
the set of well-founded trees generated inductively by
–returnxP TreeΣpXq, for everyxPX, and
–oppa,κqP TreeΣpXq, for everyopPΣ, aPAop, andκ :BopÑ TreeΣpXq.

<!-- Page 4 -->

4 Danel Ahman and Andrej Bauer
We are abusing notation in a slight but standard way, by usingop both as the
name of an operation and a tree-forming constructor. The elements ofTreeΣpXq
are called computation trees: a leaf returnx represents a pure computation re-
turning a valuex, while oppa,κq represents an eﬀectful computation that calls
op with parametera and continuationκ, which expects a result fromBop.
An algebraic theory T “pΣT, EqTq is given by asignature ΣT and a set of
equations EqT. The equations EqT express computational behaviour via inter-
actions between operations, and are written in a suitable formalism, e.g., [30].
We explain these by way of examples, as the precise details do not matter for
our purposes. Let0“tu be the empty set and1“t‹u the standard singleton.
Example 1. Given a setC of possible states, the theory ofC-valued state has
two operations, whose somewhat unusual naming will become clear later on,
getenv : 1⇝C, setenv :C⇝ 1
and the equations (where we elide appearances of‹):
getenvpλc. setenvpc,κqq“ κ, setenvpc, getenvκq“ setenvpc,κcq,
setenvpc, setenvpc1,κqq“ setenvpc1,κq.
For example, the second equation states that reading state right after setting it
toc gives preciselyc. The third equation states thatsetenv overwrites the state.
Example 2. Given a set of exceptionsE, the algebraic theory ofE-many excep-
tions is given by a single operationraise :E⇝ 0, and no equations.
A T-model, also called a T-algebra, is a ΣT-structure which satisﬁes the
equations in EqT. Thefree T-model over a setX is constructed as the quotient
FreeT pXq“ TreeΣT pXq{„
by theΣT-congruence„ generated by EqT. Each opPΣT is interpreted in the
free model as the mappa,κqÞÑr oppa,κqs, wherer´s is the„-equivalence class.
FreeT p´q is the functor part of amonad on sets, whoseunit at a setX is
X return →→ TreeΣT pXq
r´s →→ →→ FreeT pXq.
The Kleisli extension for this monad is then the operation which lifts any map
f :XÑ TreeΣT pYq to the mapf: : FreeΣT pXqÑ FreeΣT pYq, given by
f:rreturnxs
def
“ fx, f :roppa,κqs
def
“r oppa,f:˝κqs.
That is,f: traverses a computation tree and replaces each leafreturnx withfx .
The preceding construction of free models and the monad may be retro-
ﬁtted to an algebraic signatureΣ, if we construeΣ as an algebraic theory with
no equations. In this case„ is just equality, and so we may omit the quotient

<!-- Page 5 -->

Runners in action 5
and the pesky equivalence classes. Thus the carrier of the freeΣ-model is the
set of well-founded treesTreeΣpXq, with the evident monad structure.
A fundamental insight of Plotkin and Power [25,28] was that many com-
putational eﬀects may be adequately described by algebraic theories, with the
elements of free models corresponding to eﬀectful computations. For example,
the monads induced by the theories from Examples 1 and 2 are respectively
isomorphic to the usualstate monad StCX
def
“p CñXˆCq and theexceptions
monad ExcEX
def
“ X`E.
Plotkin and Pretnar [30] further observed that the universal property of free
models may be used to model a programming concept known ashandlers. Given
a T -model M and a map f : X Ñ |M|, the universal property of the free
T -model gives us a uniqueT -homomorphism f; : FreeT pXqÑ| M| satisfying
f;rreturnxs“ fx, f ;roppa,κqs“ opMpa,f;˝κq.
A handler for a theoryT in a language such asEff amounts to a modelM
whose carrier|M| is the carrierFreeT 1pYq of the free model for some other the-
ory T1, while the associated handling construct is the inducedT -homomorphism
FreeT pXqÑ FreeT 1pYq. Thus handling transforms computations with eﬀectsT
to computations with eﬀectsT1. There is however no restriction on how a han-
dler implements an operation, in particular, it may use its continuation in an
arbitrary fashion. We shall put the universal property of free models to good use
as well, while making sure that the continuations are always used aﬃnely.
2.2 Runners
Much like monads, handlers are useful for simulating computational eﬀects, be-
cause they allow us to transformT -computations toT1-computations. However,
eventually there has to be a “top level” where such transformations cease and
actual computational eﬀects happen. For these we need another concept, known
as runners [35]. Runners are equivalent to the concept ofcomodels[27,31], which
are “just models in the opposite category”, although one has to apply the motto
correctly by using powers and co-powers where seemingly exponentials and prod-
ucts would do. Without getting into the intricacies, let us spell out the deﬁnition.
Deﬁnition 1.Arunner Rforasignature Σ isgivenbyacarrierset |R|together
with, for eachopPΣ, aco-operation opR :AopÑp| R|ñ Bopˆ| R|q.
Runners are usually deﬁned to have co-operations in the equivalent uncurried
form opR :Aopˆ| R|Ñ Bopˆ| R|, but that is less convenient for our purposes.
Runners may be deﬁned more generally for theoriesT , rather than just sig-
natures, by requiring that the co-operations satisfyEqT. We shall have no use
for these, although we expect no obstacles in incorporating them into our work.
A runner tells us what to do when an eﬀectful computation reaches the
top-level runtime environment. Think of |R| as the set of conﬁgurations of
the runtime environment. Given the current conﬁgurationc P |R|, the opera-
tion oppa,κq is executed as the corresponding co-operationopRac whose result

<!-- Page 6 -->

6 Danel Ahman and Andrej Bauer
pb,c1q PBopˆ| R| gives the result of the operation b and the next runtime
conﬁgurationc1. The continuationκb then proceeds in runtime conﬁgurationc1.
It is not too diﬃcult to turn this idea into a mathematical model. For any
X, the co-operations induce aΣ-structure M with |M|
def
“ St|R|X “ p|R| ñ
Xˆ| R|q and operations opM :AopˆpBopñ St|R|XqÑ St|R|X given by
opMpa,κq
def
“ λc.κ pπ1popRacqqpπ2popRacqq.
We may then use the universal property of the freeΣ-model to obtain aΣ-
homomorphism rX : TreeΣpXqÑ St|R|X satisfying the equations
rXpreturnxq“ λc.px,cq, rXpoppa,κqq“ opMpa, rX˝κq.
The map rX precisely captures the idea that a runnerruns computations by
transforming (static) computation trees into state-passing maps. Note how in
the above deﬁnition ofopM, the continuationκ is used in a controlled way, as
it appears precisely once as the head of the outermost application. In terms of
programming, this corresponds to linear use in a tail-call position.
Runnersarelessad-hocthantheymayseem.First,noticethat opM isjustthe
composition of the co-operationopR with the state monad’s Kleisli extension of
the continuationκ, and so is the standard way of turninggeneric eﬀectsintoΣ-
structures [26]. Second, the maprX is the component atX of a monad morphism
r : TreeΣp´qÑ St|R|. Møgelberg & Staton [21], as well as Uustalu [35], showed
that the passage from a runnerR to the corresponding monad morphismr forms
a one-to-one correspondence between the former and the latter.
As deﬁned, runners are too restrictive a model of top-level computation,
because the only eﬀect available to co-operations is state, but in practice the
runtime environment may also signal errors and perform other eﬀects, by calling
its own runtime environment. We are led to the following generalisation.
Deﬁnition 2.For a signatureΣ and monadT, aT-runner R forΣ, or just an
eﬀectful runner, is given by, for eachopPΣ, aco-operation opR :AopÑTB op.
The correspondence between runners and monad morphisms still holds.
Proposition 3. For a signature Σ and a monad T, the monad morphisms
TreeΣp´qÑ T are in one-to-one correspondence withT-runners forΣ.
Proof. This is an easy generalisation of the correspondence for ordinary runners.
Let us ﬁx a signatureΣ, and a monadT with unitη and Kleisli extension´:.
Let R be a T-runner for Σ. For any setX, R induces a Σ-structure M
with|M|
def
“ TX and opM :AopˆpBopñTXqÑ TX deﬁned asopMpa,κq
def
“
κ:popRaq. As before, the universal property of the free modelTreeΣpXqprovides
a uniqueΣ-homomorphism rX : TreeΣpXqÑ TX, satisfying the equations
rXpreturnxq“ ηXpxq, rXpoppa,κqq“ opMpa, rX˝κq.
The maps rX collectively give us the desired monad morphismr induced by R.
Conversely, givena monadmorphismθ : TreeΣp´qÑ T,wemay recover aT-
runner RforΣ by deﬁning the co-operations asopRa
def
“ θBoppoppa,λb. returnbqq.
It is not hard to check that we have described a one-to-one correspondence.[ \

<!-- Page 7 -->

Runners in action 7
3 Programming with runners
If ordinary runners are not general enough, the eﬀectful ones are too general:
parameterised by arbitrary monadsT, they do not combine easily and they lack
a clear notion of resource management. Thus, we now engineer more speciﬁc
monads whose associated runners can be turned into a programming concept.
While we give up complete generality, the monads presented below are still quite
versatile, as they are parameterised by arbitrary algebraic signaturesΣ, and so
are extensible and support various combinations of eﬀects.
3.1 The user and kernel monads
Eﬀectful source code running inside a runtime environment is just one example
of a more general phenomenon in which eﬀectful computations are enveloped by
a layer that provides a supervised access to external resources: a user process
is controlled by a kernel, a web page by a browser, an operating system by
hardware, or a virtual machine, etc. We shall adopt the parlance of software
systems, and refer to the two layers generically as theuser and kernel code.
Since the two kinds of code need not, and will not, use the same eﬀects, each
will be described by its own algebraic theory and compute in its own monad.
Weﬁrst addressthe kernel theory. Speciﬁcally,we lookfor analgebraic theory
suchthateﬀectfulrunnersfortheinducedmonadsatisfythefollowingdesiderata:
1. Runners support management and controlled ﬁnalisation of resources.
2. Runners may use further external resources.
3. Runners may signal failure caused by unavoidable circumstances.
The totality of external resources available to user code appears as a stateful
external environment, even though it has no direct access to it. Thus, kernel
computationsshouldcarrystate.Weachievethisbyincorporatingintothekernel
theory the operationsgetenv and setenv, and equations for state from Example 1.
Apart from managing state, kernel code should have access to further eﬀects,
which may be true external eﬀects, or some outer layer of runners. In either case,
we should allow the kernel code to call operations from a given signatureΣ.
Because kernel computations ought to be able to signal failure, we should
include an exception mechanism. In practice, many programming languages and
systems have two ﬂavours of exceptions, variously called recoverable and fatal,
checked and unchecked, exceptions and errors, etc. One kind, which we call just
exceptions, is raised by kernel code when a situation requires special attention
by user code. The other kind, which we callsignals, indicates an unrecoverable
condition that prevents normal execution of user code. These correspond pre-
cisely to the two standard ways of combining exceptions with state, namely the
coproduct and the tensor of algebraic theories [11]. The coproduct simply adjoins
exceptions raise :E⇝ 0 from Example 2 to the theory of state, while the tensor
extends the theory of state with signalskill :S⇝ 0, together with equations
getenvpλc. killsq“ kills, setenvpc, killsq“ kills. (1)

<!-- Page 8 -->

8 Danel Ahman and Andrej Bauer
These equations say that a signal discards state, which makes it unrecoverable.
To summarise, thekernel theory KΣ,E,S,C contains operations from a signa-
ture Σ, as well as state operationsgetenv : 1⇝ C, setenv : C⇝ 1, exceptions
raise :E⇝ 0, and signalskill :S⇝ 0, with equations for state from Example 1,
equations (1) relating state and signals, and for each operationopPΣ, equations
getenvpλc. oppa,κcqq“ oppa,λb. getenvpλc.κcb qq,
setenvpc, oppa,κqq“ oppa,λb. setenvpc,κbqq,
expressing that external operations do not interact with kernel state. It is not
diﬃcult to see thatKΣ,E,S,C induces, up to isomorphism, thekernel monad
KΣ,E,S,CX
def
“ Cñ TreeΣpppX`Eqˆ Cq` Sq.
How about user code? It can of course call operations from a signatureΣ
(not necessarily the same as the kernel code), and because we intend it to handle
exceptions, it might as well have the ability to raise them. However, user code
knows nothing about signals and kernel state. Thus, we choose theuser theory
UΣ,E to be the algebraic theory with operationsΣ, exceptionsraise :E⇝ 0, and
no equations. This theory induces theuser monad UΣ,EX
def
“ TreeΣpX`Eq.
3.2 Runners as a programming construct
In this section, we turn the ideas presented so far into programming constructs.
We strive for a realistic result, but when faced with several design options, we
prefer simplicity and semantic clarity. We focus here on translating the central
concepts, and postpone various details to §4, where we present a full calculus.
We codify the idea of user and kernel computations by having syntactic
categories for each of them, as well as one for values. We use lettersM, N to
indicate user computations,K,L for kernel computations, andV,W for values.
User and kernel code raise exceptions with operationraise, and catch them
with exception handlers based on Benton and Kennedy’sexceptional syntax[7],
tryM withtreturnxÞÑN,..., raiseeÞÑNe,... u,
and analogously for kernel code. The familiar binding constructlet x“M inN
is simply shorthand fortryM withtreturnxÞÑN,..., raiseeÞÑ raisee,... u.
As a programming concept, a runnerR takes the form
tpopxÞÑKopqopPΣuC,
where eachKop is a kernel computation, with the variablex bound inKop, so
that each clause opx ÞÑ Kop determines a co-operation for the kernel monad.
The subscriptC indicates the type of the state used by the kernel codeKop.
The corresponding elimination form is a handling-like construct
usingR @V runM ﬁnallyF, (2)

<!-- Page 9 -->

Runners in action 9
which uses the co-operations of runnerR “at” initial kernel stateV to run user
code M, and ﬁnalises its return value, exceptions, and signals withF, see (3)
below. When user codeM calls an operation op, the enveloping run construct
runs the corresponding co-operationKop of R. While doing so,Kop might raise
exceptions. But not every exception makes sense for every operation, and so
we assign to each operationop a set of exceptionsEop which the co-operations
implementing it may raise, by augmenting its operation signature withEop, as
op :Aop⇝Bop !Eop.
An exception raised by the co-operationKop propagates back to the operation
call in the user code. Therefore, an operation call should have not only a contin-
uation x.M receiving a result, but also continuationsNe, one for eachePEop,
oppV,px.M q,pNeqePEopq.
If Kop returns a valuebP Bop, the execution proceeds asMrb{xs, and asNe if
Kop raises an exceptionePEop. In examples, we use the generic versions of op-
erations [26], writtenopV, which pass on return values and re-raise exceptions.
One can pass exceptions back to operation calls also in a language with han-
dlers, such asEff, by changing the signatures of operations toAop⇝Bop`Eop,
and implementing the exception mechanism by hand, so that every operation call
is followed by a case distinction onBop`Eop. One is reminded of how operating
system calls communicate errors back to user code as exceptional values.
A co-operationKop may also send a signal, in which case the rest of the user
code M is skipped and the control proceeds directly to the corresponding case
of the ﬁnalisation partF of the run construct (2), whose syntactic form is
treturnx @cÞÑN,..., raisee @cÞÑNe,..., killsÞÑNs,... u. (3)
Speciﬁcally, ifM returns a valuev, thenN is evaluated withx bound tov andc
to the ﬁnal kernel state; ifM raises an exceptione (either directly or indirectly
via a co-operation ofR), thenNe is executed, again withc bound to the ﬁnal
kernel state; and if a co-operation ofR sends a signals, thenNs is executed.
Example 4. In anticipation of setting up the complete calculus we show how one
can work with ﬁles. The language implementors can provide an operationopen
which opens a ﬁle for writing and returns its ﬁle handle, an operationclose which
closes a ﬁle handle, and a runnerﬁleIO that implements writing. Let us further
suppose that ﬁleIO may raise an exceptionQuotaExceeded if a write exceeds the
user disk quota, and send a signal IOError if an unrecoverable external error
occurs. The following code illustrates how to guarantee proper closing of the ﬁle:
using ﬁleIO @ (open "hello.txt") run
write "Hello, world."
ﬁnally {
return x @ fhÑ close fh,
raise QuotaExceeded @ fhÑ close fh,
kill IOErrorÑ return () }

<!-- Page 10 -->

10 Danel Ahman and Andrej Bauer
Notice that the user code does not have direct access to the ﬁle handle. Instead,
the runner holds it in its state, where it is available to the co-operation that
implements write. The ﬁnalisation block gets access to the ﬁle handle upon suc-
cessful completion and raised exception, so it can close the ﬁle, but when a signal
happens the ﬁnalisation cannot close the ﬁle, nor should it attempt to do so.
We also mention that the code “cheats” by placing the call toopen in a posi-
tion where a value is expected. We should havelet-bound the ﬁle handle returned
by open outside the run construct, which would make it clear that opening the
ﬁle happensbefore this construct (and thatopen is not handled by the ﬁnalisa-
tion), but would also expose the ﬁle handle. Since there are clear advantages to
keeping the ﬁle handle inaccessible, a realistic language should accept the above
code and hoist computations from value positions automatically.
4 A calculus for programming with runners
Inspired by the semantic notion of runners and the ideas of the previous section,
we now present a calculus for programming with co-operations and runners,
called λcoop. It is a low-level ﬁne-grain call-by-value calculus [19], and as such
could inspire an intermediate language that a high-level language is compiled to.
4.1 Types
The types ofλcoop are shown in Fig. 1. Theground typescontain base types, and
are closed under ﬁnite sums and products. These are used in operation signa-
tures and as types of kernel state. (Allowing arbitrary types in either of these
entails substantial complications that can be dealt with but are tangential to
our goals.) Ground types can also come with corresponding constant symbolsf,
each associated with a ﬁxedconstant signature f :pA1,...,A nqÑ B.
We assume a supply of operation symbolsO, exception namesE, and signal
names S. Each operation symbolopP O is equipped with anoperation signature
Aop⇝Bop !Eop, which speciﬁes its parameter typeAop and arity typeBop, and
the exceptionsEop that the corresponding co-operations may raise in runners.
The value types extend ground types with two function types, and a type
of runners. The user function type X Ñ Y !pΣ,Eq classiﬁes functions tak-
ing arguments of type X to computations classiﬁed by theuser (computa-
tion) type Y !pΣ,Eq, i.e., those that return values of type Y, and may call
operations Σ and raise exceptionsE. Similarly, thekernel function typeX Ñ
Y☇pΣ,E,S,C q classiﬁes functions taking arguments of typeX to computations
classiﬁed by thekernel (computation) typeY☇pΣ,E,S,C q, i.e., those that return
values of typeY, and may call operationsΣ, raise exceptionsE, send signalsS,
and use state of typeC. We note that the ingredients for user and kernel types
correspond precisely to the parameters of the user monadUΣ,E and the kernel
monad KΣ,E,S,C from §3.1. Finally, therunner typeΣñpΣ1,S,C qclassiﬁes run-
ners that implement co-operations for the operationsΣ as kernel computations
which use operationsΣ1, send signalsS, and use state of typeC.

<!-- Page 11 -->

Runners in action 11
Ground typeA, B, C ::“ b base typeˇˇ unit unit typeˇˇ empty empty typeˇˇ AˆB product typeˇˇ A`B sum type
Constant signature: f :pA1,...,A nqÑ B
Signature Σ ::“t op1, op2,..., opnuĂ O
Exception setE ::“t e1,e 2,...,e nuĂ E
Signal setS ::“t s1,s 2,...,s nuĂ S
Operation signature: op :Aop⇝Bop !Eop
Value typeX, Y, Z ::“ A ground typeˇˇ XˆY product typeˇˇ X`Y sum typeˇˇ XÑY ! U user function typeˇˇ XÑY☇K kernel function typeˇˇ ΣñpΣ1,S,C q runner type
User (computation) type: X ! U where U“pΣ,Eq
Kernel (computation) type: X☇K where K“pΣ,E,S,C q
Fig. 1. The types ofλcoop.
4.2 Values and computations
The syntax of terms is shown in Fig. 2. The usual ﬁne-grain call-by-value strat-
iﬁcation of terms into pure values and eﬀectful computations is present, except
that we further distinguish betweenuser and kernel computations.
Values Among the values are variables, constants for ground types, and con-
structors for sums and products. There are two kinds of functions, for abstracting
over user and kernel computations. Arunner is a value of the form
tpopxÞÑKopqopPΣuC.
It implements co-operations for operationsop as kernel computationsKop, with
x bound inKop. The type annotationC speciﬁes the type of the state thatKop
uses. Note thatC ranges over ground types, a restriction that allows us to deﬁne
a naive set-theoretic semantics. We sometimes omit these type annotations.
User and kernel computationsThe user and kernel computations both have
pure computations, function application, exception raising and handling, stan-

<!-- Page 12 -->

12 Danel Ahman and Andrej Bauer
V alues
V,W ::“ x variableˇˇ fpV1,...,V nq ground constantˇˇ pq unitˇˇ pV,Wq pairˇˇ inlX,Y V
ˇˇ inrX,Y V injectionˇˇ funpx :XqÞÑ M user functionˇˇ funKpx :XqÞÑ K kernel functionˇˇ tpopxÞÑKopqopPΣuC runner
User computations
M,N ::“ returnV valueˇˇ V W applicationˇˇ tryM withtreturnxÞÑN,praiseeÞÑNeqePEu exception handlerˇˇ matchV withtpx,yqÞÑ Mu product eliminationˇˇ matchV withtuX empty eliminationˇˇ matchV withtinlxÞÑM, inryÞÑNu sum eliminationˇˇ opXpV,px.M q,pNeqePEopq operation callˇˇ raiseXe raise exceptionˇˇ usingV @W runM ﬁnallyF running user codeˇˇ kernelK @W ﬁnallyF switch to kernel mode
F ::“t returnx @cÞÑN,praisee @cÞÑNeqePE,pkillsÞÑNsqsPSu
Kernel computations
K,L ::“ returnCV valueˇˇ V W applicationˇˇ tryK withtreturnxÞÑL,praiseeÞÑLeqePEu exception handlerˇˇ matchV withtpx,yqÞÑ Ku product eliminationˇˇ matchV withtuX@C empty eliminationˇˇ matchV withtinlxÞÑK, inryÞÑLu sum eliminationˇˇ opXpV,px.K q,pLeqePEopq operation callˇˇ raiseX@Ce raise exceptionˇˇ killX@Cs send signalˇˇ getenvCpc.K q get kernel stateˇˇ setenvpV,Kq set kernel stateˇˇ userM with treturnxÞÑK,praiseeÞÑLeqePEu switch to user mode
Fig. 2. Values, user computations, and kernel computations ofλcoop.

<!-- Page 13 -->

Runners in action 13
dard elimination forms, and operation calls. Note that the typing annotations
on some of these diﬀer according to their mode. For instance, a user operation
call is annotated with the result typeX, whereas the annotationX @C on a
kernel operation call also speciﬁes the kernel state typeC.
The binding constructletX!E x“M inN is not part of the syntax, but is an
abbreviation for tryM withtreturnxÞÑN,praiseeÞÑ raiseXeqePEu, and there is
an analogous one for kernel computations. We often drop the annotationX!E.
Some computations are speciﬁc to one or the other mode. Only the kernel
mode may send a signal withkill, and manipulate state withgetenv and setenv,
but only the user mode has therun construct from §3.2. Finally, each mode has
the ability to “context switch” to the other one. The kernel computation
userM withtreturnxÞÑK,praiseeÞÑLeqePEu
runs a user computationM and handles the returned value and leftover excep-
tions with kernel computationsK and Le. Conversely, the user computation
kernelK @W ﬁnallytx @cÞÑM,praisee @cÞÑNeqePE,pkillsÞÑNsqsPSu
runs kernel computationK with initial stateW, and handles the returned value,
and leftover exceptions and signals with user computationsM, Ne, andNs.
4.3 Type system
We equipλcoop with a type system akin to type and eﬀect systems for algebraic
eﬀects and handlers [3,7,12]. We are experimenting with resource control, so it
makes sense for the type system to tightly control resources. Consequently, our
eﬀect system does not allow eﬀects to be implicitly propagated outwards.
In §4.1, we assumed that each operationopP O is equipped with some ﬁxed
operation signature op : Aop ⇝ Bop !Eop. We also assumed a ﬁxed constant
signature f : pA1,...,A nq ÑB for each ground constant f. We consider this
information to be part of the type system and say no more about it.
Values, user computations, and kernel computations each have a correspond-
ing typing judgement form and asubtyping relation, given by
Γ $V :X, Γ $M :X ! U, Γ $K :X☇K,
X ĎY, X ! U ĎY ! V, X ☇K ĎY☇L,
where Γ is a typing context x1 : X1,...,x n : Xn. The eﬀect information is an
over-approximation, i.e.,M and K employ at most the eﬀects described byU
and K. The complete rules for these judgements are given in the online appendix.
We comment here only on the rules that are peculiar toλcoop, see Fig. 3.
Subtyping of ground typesSub-Ground is trivial, as it relates only equal
types.Subtypingofrunners Sub-Runnerandkernelcomputations Sub-Kernel
requires equality of the kernel state typesC andC1 because state is used invari-
antly in the kernel monad. We leave it for future work to replaceC”C1 with
a lens [10] fromC1 to C, i.e., mapsC1 Ñ C and C1ˆCÑC1 satisfying state

<!-- Page 14 -->

14 Danel Ahman and Andrej Bauer
Sub-Ground
A ĎA
Sub-Runner
Σ1
1ĎΣ1 Σ2ĎΣ1
2 SĎS1 C”C1
Σ1ñpΣ2,S,C q ĎΣ1
1ñpΣ1
2,S1,C1q
Sub-Kernel
X ĎX1 ΣĎΣ1 EĎE1 SĎS1 C”C1
X☇pΣ,E,S,C q ĎX1☇pΣ1,E1,S1,C1q
TyUser-Try
Γ $M :X !pΣ,Eq Γ,x :X$N :Y !pΣ,E1q
`
Γ $Ne :Y !pΣ,E1q
˘
ePE
Γ $ tryM withtreturnxÞÑN,praiseeÞÑNeqePEu :Y !pΣ,E1q
TyUser-Run
F ”t returnx @cÞÑN,praisee @cÞÑNeqePE,pkillsÞÑNsqsPSu
Γ $V :ΣñpΣ1,S,C q Γ $W :C
Γ $M :X !pΣ,Eq Γ,x :X,c :C$N :Y !pΣ1,E1q`
Γ,c :C$Ne :Y !pΣ1,E1q
˘
ePE
`
Γ $Ns :Y !pΣ1,E1q
˘
sPS
Γ $ usingV @W runM ﬁnallyF :Y !pΣ1,E1q
TyUser-Op
U”pΣ,Eq opPΣ Γ $V :Aop
Γ,x :Bop$M :X ! U
`
Γ $Ne :X ! U
˘
ePEop
Γ $ opXpV,px.M q,pNeqePEopq :X ! U
TyKernel-Op
K”pΣ,E,S,C q opPΣ Γ $V :Aop
Γ,x :Bop$K :X☇K
`
Γ $Le :X☇K
˘
ePEop
Γ $ opXpV,px.K q,pLeqePEopq :X☇K
TyUser-Kernel
F ”t returnx @cÞÑN,praisee @cÞÑNeqePE,pkillsÞÑNsqsPSu
Γ $K :X☇pΣ,E,S,C q Γ $W :C Γ,x :X,c :C$N :Y !pΣ,E1q`
Γ,c :C$Ne :Y !pΣ,E1q
˘
ePE
`
Γ $Ns :Y !pΣ,E1q
˘
sPS
Γ $ kernelK @W ﬁnallyF :Y !pΣ,E1q
TyKernel-User
K”pΣ,E1,S,C q Γ $M :X !pΣ,Eq
Γ,x :X$K :Y☇K
`
Γ $Le :Y☇K
˘
ePE
Γ $ userM withtreturnxÞÑK,praiseeÞÑLeqePEu :Y☇K
Fig. 3. Selected typing and subtyping rules.

<!-- Page 15 -->

Runners in action 15
equations analogous to Example 1. It has been observed [24,31] that such a lens
in fact amounts to an ordinary runner forC-valued state.
The rulesTyUser-Op and TyKernel-Op govern operation calls, where we
have a success continuation which receives a value returned by a co-operation,
and exceptional continuations which receive exceptions raised by co-operations.
The ruleTyUser-Runrequires that the runnerV implementsall the opera-
tionsM can use, meaning that operations arenot implicitly propagated outside
a run block (which is diﬀerent from how handlers are sometimes implemented).
Of course, the co-operations of the runner may call further external operations,
as recorded by the signatureΣ1. Similarly, we require the ﬁnally blockF to in-
tercept all exceptions and signals that might be produced by the co-operations
of V or the user codeM. Such strict control is exercised throughout. For ex-
ample, inTyUser-Run, TyUser-Kernel, andTyKernel-User we catch all
the exceptions and signals that the code might produce. One should judiciously
relax these requirements in a language that is presented to the programmer, and
allow re-raising and re-sending clauses to be automatically inserted.
4.4 Equational theory
We present λcoop as an equational calculus, i.e., the interactions between its
components are described by equations. Such a presentation makes it easy to
reason about program equivalence. There are three equality judgements
Γ $V ”W :X, Γ $M”N :X ! U, Γ $K”L :X ! K.
It is presupposed that we only compare well-typed expressions with the indicated
types. For the most part, the context and the type annotation on judgements
will play no signiﬁcant role, and so we shall drop them whenever possible.
We comment on the computational equations for constructs characteristic
of λcoop, and refer the reader to the online appendix for other equations. When
read left-to-right, these equations explain the operational meaning of programs.
Of the three equations forrun, the ﬁrst two specify that returned values and
raised exceptions are handled by the corresponding clauses,
usingV @W runpreturnV1q ﬁnallyF ”NrV1{x,W{cs,
usingV @W runpraiseXeq ﬁnallyF ”NerW{cs,
whereF
def
“t returnx @cÞÑN,praisee @cÞÑNeqePE,pkillsÞÑNsqsPSu. The third
equation below relates running an operationopwith executing the corresponding
co-operation Kop, whereR stands for the runnertpopxÞÑKopqopPΣuC:
usingR @W runpopXpV,px.M q,pN1
e1qe1PEopqq ﬁnallyF ”
kernelKoprV{xs @W ﬁnally␣
returnx @c1ÞÑp usingR @c1 runM ﬁnallyFq,`
raisee1 @c1ÞÑp usingR @c1 runN1
e1 ﬁnallyFq
˘
e1PEop
,
pkillsÞÑNsqsPS
(

<!-- Page 16 -->

16 Danel Ahman and Andrej Bauer
Because Kop is kernel code, it is executed in kernel mode, whoseﬁnally clauses
specify what happens afterwards: ifKop returns a value, or raises an exception,
execution continues with a suitable continuation, withR wrapped around it; and
if Kop sends a signal, the corresponding ﬁnalisation code fromF is evaluated.
The next bundle describes how kernel code is executed within user code:
kernelpreturnCVq @W ﬁnallyF ”NrV{x,W{cs,
kernelpraiseX@Ceq @W ﬁnallyF ”NerW{cs,
kernelpkillX@Csq @W ﬁnallyF ”Ns,
kernelpgetenvCpc.K qq @W ﬁnallyF ” kernelKrW{cs @W ﬁnallyF,
kernelpsetenvpV,Kqq @W ﬁnallyF ” kernelK @V ﬁnallyF.
We also have an equation stating that an operation called in kernel mode prop-
agates out to user mode, with its continuations wrapped in kernel mode:
kernel opXpV,px.K q,pLe1qe1PEq @W ﬁnallyF ”
opXpV,px. kernelK @W ﬁnallyFq,pkernelLe1 @W ﬁnallyFqe1PEq.
Similar equations govern execution of user computations in kernel mode.
The remaining equations include standardβη-equations for exception han-
dling [7], deconstruction of products and sums, algebraicity equations for oper-
ations [33], and the equations of kernel theory from §3.1, describing howgetenv
and setenv work, and how they interact with signals and other operations.
5 Denotational semantics
We provide a coherent denotational semantics forλcoop, and prove it sound with
respect to the equational theory given in §4.4. Having eschewed all forms of
recursion, we may aﬀord to work simply over the category of sets and functions,
while noting that there is no obstacle to incorporating recursion at all levels and
switching to domain theory, similarly to the treatment of eﬀect handlers in [3].
5.1 Semantics of types
The meaning of terms is most naturally deﬁned by structural induction on their
typing derivations, which however are not unique inλcoop due to subsumption
rules. Thus we must worry about devising acoherentsemantics, i.e., one in which
all derivations of a judgement get the same meaning. We follow prior work on the
semantics of eﬀect systems for handlers [3], and proceed by ﬁrst giving askeletal
semantics ofλcoop in which derivations are manifestly unique because the eﬀect
information is unreﬁned. We then use the skeletal semantics as the frame upon
which rests a reﬁnement-style coherent semantics of the eﬀectful types ofλcoop.
Theskeletal typesarelike λcoop’stypes,butwithalleﬀectinformationerased.
In particular, the ground typesA, and hence the kernel state typesC, do not
change as they contain no eﬀect information. The skeletal value types are
P,Q ::“ A| unit| empty|PˆQ|P`Q|P ÑQ!|P ÑQ☇C| runnerC.

<!-- Page 17 -->

Runners in action 17
The skeletal versions of the user and kernel types areP ! and P☇C, respec-
tively. It is best to think of the skeletal types as ML-style types which implicitly
over-approximate eﬀect information by “any eﬀect is possible”, an idea which is
mathematically expressed by their semantics, as explained below.
First of all, the semantics of ground types is straightforward. One only needs
to provide sets denoting the base typesb, after which the ground types receive
the standard set-theoretic meaning, as given in Fig. 4.
Recall thatO, S, andE are the sets of all operations, signals, and exceptions,
and that eachopP O has a signatureop :Aop⇝Bop !Eop. Let us additionally
assume that there is a distinguished operationOP O with signatureO : 1⇝ 0 ! 0
(otherwise we adjoin it toO). It ensures that the denotations of skeletal user and
kernel types arepointed sets, while operationallyO indicates aruntime error.
Next, we deﬁne theskeletal user and kernel monadsas
UsX
def
“ UO,EX“ TreeOpX` Eq,
Ks
CX
def
“ KO,E,S,CX“pCñ TreeOppX` Eqˆ C` Sqq,
and RunnersC as the set of allskeletal runners R (with stateC), which are fami-
lies of co-operationstopR :rrAopssÑ KO,Eop,S,CrrBopssuopPO. Note thatKO,Eop,S,C
is a coproduct [11] of monadsCñ TreeOp´ˆ C` Sq and ExcEop, and thus the
skeletal runners are the eﬀectful runners for the former monad, so long as we
read the eﬀectful signaturesop : Aop ⇝ Bop !Eop as ordinary algebraic ones
op : Aop⇝ Bop`Eop. While there is no semantic diﬀerence between the two
readings, there is one of intention:KO,Eop,S,CrrBopssis a kernel computation that
(apart from using state and sending signals) returns values of typeBop and raises
exceptions Eop, whereasC ñ TreeOpprrBopss`Eopqˆ C` Sq returns values of
typeBop`Eop and raises no exceptions. We prefer the former, as it reﬂects our
treatment of exceptions as a control mechanism rather than exceptional values.
These ingredients suﬃce for the denotation of skeletal types as sets, as given
in Fig. 4. The user and kernel skeletal types are interpreted using the respective
skeletal monads, and hence the two function types as Kleisli exponentials.
We proceed with the semantics of eﬀectful types. Theskeleton of a value
type X is the skeletal typeX s obtained by removing all eﬀect information, and
similarly for user and kernel types, see Fig. 5. We interpret a value typeX as a
subsetrrrXsssĎrrX sssof the denotation of its skeleton, and similarly for user and
computation types. In other words, we treat the eﬀectful types asreﬁnements
of their skeletons. For this, we deﬁne the operationpX0,X 1q⇛pY0,Y 1q, for any
X0ĎX1 and Y0ĎY1, as the set of mapsX1ÑY1 restricted toX0ÑY0:
pX0,X 1q⇛pY0,Y 1q
def
“t f :X1ÑY1|@xPX0.fpxqP Y0u.
Next, observe that the user and the kernel monads preserve subset inclusions, in
the sense that UΣ,EX Ď UΣ1,E1X1 and KΣ,E,S,CX Ď KΣ1,E1,S1,CX1 if Σ Ď Σ1,
E Ď E1, S Ď S1, and X Ď X1. In particular, we always haveUΣ,EX Ď UsX
and KΣ,E,S,CX Ď Ks
CX. Finally, letRunnerΣ,Σ 1,SC Ď RunnersC be the subset
of those runners R whose co-operations forΣ factor through KΣ1,Eop,S,C, i.e.,
opR :rrAopssÑ KΣ1,Eop,S,CrrBopssĎ KO,Eop,S,CrrBopss, for each opPΣ.

<!-- Page 18 -->

18 Danel Ahman and Andrej Bauer
Ground types
rrbss
def
“ ¨¨¨ rr unitss
def
“ 1 rremptyss
def
“ 0
rrAˆBss
def
“ rrAssˆrrBss rr A`Bss
def
“ rrAss`rrBss
Skeletal types
rrPˆQss
def
“ rrPssˆrrQss rr P ÑQ!ss
def
“ rrPssñrrQ!ss
rrP`Qss
def
“ rrPss`rrQss rr P ÑQ☇Css
def
“ rrPssñrrQ☇Css
rrrunnerCss
def
“ RunnersrrCss rr P !ss
def
“ UsrrPss rr P☇Css
def
“ Ks
rrCssrrPss
rrx1 :P1,...,x n :Pnss
def
“ rrP1ssˆ¨¨¨ˆrr Pnss
Fig. 4. Denotations of ground and skeletal types.
Semantics of eﬀectful types is given in Fig. 5. From a category-theoretic
viewpoint, it assigns meaning in the categorySubpSetq whose objects are subset
inclusionsX0ĎX1 and morphisms fromX0ĎX1 toY0ĎY1 those mapsX1Ñ
Y1 that restrict toX0ÑY0. The interpretations of products, sums, and function
types are precisely the corresponding category-theoretic notionsˆ,`, and⇛ in
SubpSetq. Even better, the pairs of submonadsUΣ,E Ď Us and KΣ,E,S,C Ď Ks
C
are the “SubpSetq-variants” of the user and kernel monads. Such an abstract
point of view drives the interpretation of terms, given below, and it additionally
suggests how our semantics can be set up on top of a category other thanSet. For
example, if we replaceSet with the categoryCpo of ω-complete partial orders,
we obtain the domain-theoretic semantics of eﬀect handlers from [3] that models
recursion and operations whose signatures contain arbitrary types.
5.2 Semantics of values and computations
To give semantics toλcoop’s terms, we introduceskeletal typing judgements
Γ $s V :P, Γ $s M :P !, Γ $s K :P☇C,
which assign skeletal types to values and computations. In these judgements,Γ
is askeletal contextwhich assigns skeletal types to variables.
The rules for these judgements are obtained fromλcoop’s typing rules, by
excluding subsumption rules and by relaxing restrictions on eﬀects. For example,
the skeletal versions of the rulesTyV alue-Runnerand TyKernel-Kill are`
Γ,x :Aop$s Kop :Bop☇C
˘
opPΣ
Γ $stpopxÞÑKopqopPΣuC : runnerC
sP S
Γ $s killX@Cs :X s☇C
The relationship between eﬀectful and skeletal typing is summarised as follows:
Proposition 5. (1) Skeletal typing derivations are unique. (2) IfX ĎY, then
X s“Y s, and analogously for subtyping of user and kernel types. (3) IfΓ $V :X,
then Γ s$s V :X s, and analogously for user and kernel computations.

<!-- Page 19 -->

Runners in action 19
Skeletons
As def
“ A pΣñpΣ1,S,C qq
s def
“ runnerC pXˆYqs def
“ X sˆY s
pXÑY ! Uqs def
“ X sÑpY ! Uqs pX`Yqs def
“ X s`Y s
pXÑY☇Kqs def
“ X sÑpY☇Kqs pX ! Uqs def
“ X s!
px1 :X1,...,x n :Xnqs def
“ px1 :X s
1,...,x n :X s
nq p X☇pΣ,E,S,C qqs def
“ X s☇C
Denotations
rrrAsss
def
“ rrAss rrr XˆYsss
def
“ rrrXsssˆrrrXsss
rrrΣñpΣ1,S,C qsss
def
“ RunnerΣ,Σ 1,SrrrCsss rrr X`Ysss
def
“ rrrXsss`rrrXsss
rrrXÑY ! Usss
def
“ prrrXsss,rrX sssq⇛prrrY ! Usss,rrpY ! Uqsssq
rrrXÑY☇Ksss
def
“ prrrXsss,rrX sssq⇛prrrY☇Ksss,rrpY☇Kqsssq
rrrX !pΣ,Eqsss
def
“ UΣ,ErrrXsss rrrX☇pΣ,E,S,C qsss
def
“ KΣ,E,S,rrCssrrrXsss
rrrx1 :X1,...,x n :Xnsss
def
“ rrrX1sssˆ¨¨¨ˆrrr Xnsss
Fig. 5. Skeletons and denotations of types.
Proof. We prove (1) by induction on skeletal typing derivations, and (2) by
induction on subtyping derivations. For (1), we further use the occasional type
annotations, and the absence of skeletal subsumption rules. For proving (3),
suppose that D is a derivation ofΓ $V :X. We may translateD to itsskeleton
Ds deriving Γ s$s V :X s by replacing typing rules with matching skeletal ones,
skipping subsumption rules due to (2). Computations are treated similarly.[ \
Toensuresemantic coherence,weﬁrst deﬁnetheskeletal semanticsofskeletal
typing judgements,rrΓ $s V :Pss :rrΓssÑrrPss,rrΓ $s M :P !ss :rrΓssÑrrP !ss,
andrrΓ $s K :P☇Css :rrΓssÑrrP☇Css, by induction on their (unique) derivations.
Provided mapsrrA1ssˆ¨¨¨ˆrrAnssÑrrBssdenoting ground constantsf, values
are interpreted in a standard way, using the bi-cartesian closed structure of sets,
except for a runnertpopxÞÑKopqopPΣuC, which is interpreted at an environment
γPrrΓssas the skeletal runnertop :rrAopssÑ KO,Eop,S,rrCssrrBopssuopPO, given by
opa
def
“p if op PΣ thenρprrΓ,x :Aop$s Kop :Bop☇Csspγ,aqq else Oq.
Here the mapρ : Ks
rrCssrrBopssÑ KO,Eop,S,rrCssrrBopssis the skeletal kernel theory
homomorphism characterised by the equations
ρpreturn bq“ return b, ρ pop1pa1,κ,pνeqePEop1qq“ op1pa1,ρ ˝κ,pρpνeqqePEop1q,
ρpgetenvκq“ getenvpρ˝κq, ρ praiseeq“p if ePEop then raisee else Oq,
ρpsetenvpc,κqq“ getenvpc,ρ ˝κq, ρ pkillsq“ kills.
The purpose of O in the deﬁnition ofop is to model a runtime error when the
runner is asked to handle an unexpected operation, whileρ makes sure thatop
raises at most the exceptionsEop, as prescribed by the signature ofop.

<!-- Page 20 -->

20 Danel Ahman and Andrej Bauer
User and kernel computations are interpreted as elements of the correspond-
ing skeletal user and kernel monads. Again, most constructs are interpreted in
a standard way: returns as the units of the monads; the operationsraise, kill,
getenv, setenv, and ops as the corresponding algebraic operations; and match
statements as the corresponding semantic elimination forms. The interpretation
of exception handling oﬀers no surprises, e.g., as in [30], as long as we follow the
strategy of treating unexpected situations with the runtime errorO.
The most interesting part of the interpretation is the semantics of
Γ $spusingV @W runM ﬁnallyFq :Q!, (4)
where F
def
“ treturnx @cÞÑ N,praisee @cÞÑ NeqePE,pkillsÞÑ NsqsPSu. At an
environmentγPrrΓss,V is interpreted as a skeletal runner with staterrCss, which
induces a monad morphismr : TreeOp´qÑprr Cssñ TreeOp´ˆrrCss` Sqq, as
in the proof of Prop. 3. Letf : Ks
rrCssrrPss Ñ prrCss ñUsrrQssq be the skeletal
kernel theory homomorphism characterised by the equations
fpreturnpq“ λc.rrΓ,x :P,c :C$s N :Qsspγ,p,c q,
fpoppa,κ,pνeqePEopqq“ λc. oppa,λb.f pκbqc,pfpνeqcqePEopq,
fpraiseeq“ λc.pif ePE thenrrΓ,c :C$s Ne :Qsspγ,cq else Oq,
fpkillsq“ λc.pif sPS thenrrΓ $s Ns :Qssγ else Oq,
(5)
fpgetenvκq“ λc.f pκcqc, f psetenvpc1,κqq“ λc.fκc 1.
The interpretation of (4) atγ is fprrrPss`EprrΓ $s M :P !ssγqqprrΓ $s W :Cssγq,
which reads: map the interpretation ofM at γ from the skeletal user monad
to the skeletal kernel monad usingr (which models the operations ofM by the
cooperations ofV), and from there usingf to a maprrCssñ UsrrQss, that is then
applied to the initial kernel state, namely, the interpretation ofW at γ.
We interpret the context switchΓ $s kernel K @W ﬁnally F : Q! at an
environmentγPrrΓssas fprrΓ $s K :P☇CssγqprrΓ $s W :Cssγq, wheref is the
map (5). Finally,user context switch is interpreted much like exception handling.
We now deﬁne coherent semantics ofλcoop’s typing derivations by passing
through the skeletal semantics. Given a derivationD of Γ $V :X, its skeleton
Ds derivesΓ s$s V :X s. We identify the denotation ofV with the skeletal one,
rrrΓ $V :Xsss
def
“rrΓ s$s V :X sss :rrΓ sssÑrrX sss.
All that remains is to check thatrrrΓ $V :Xsssrestricts torrrΓsssÑrrrXsss. This
is accomplished by induction onD. The only interesting step is subsumption,
which relies on a further observation thatX ĎY impliesrrrXsssĎrrrYsss. Typing
derivations for user and kernel computations are treated analogously.
5.3 Coherence, soundness, and ﬁnalisation theorems
We are now ready to prove a theorem that guarantees execution of ﬁnalisation
code. But ﬁrst, let us record the fact that the semantics is coherent and sound.

<!-- Page 21 -->

Runners in action 21
Theorem 6 (Coherence and soundness). The denotational semantics of
λcoop is coherent, and it is sound for the equational theory ofλcoop from §4.4.
Proof. Coherence is established by construction: any two derivations of the same
typing judgement have the same denotation because they are both (the same)
restriction of skeletal semantics. For proving soundness, one just needs to unfold
the denotations of the left- and right-hand sides of equations from §4.4, and
compare them, where some cases rely on suitable substitution lemmas. [ \
To set the stage for the ﬁnalisation theorem, let us consider the computation
usingV @W runM ﬁnallyF, well-typed by the ruleTyUser-Run from Fig. 3.
At an environmentγPrrrΓsss, the ﬁnalisation clausesF are captured semantically
by theﬁnalisation mapφγ :prrrXsss`EqˆrrrCsss`SÑrrrY !pΣ1,E1qsss, given by
φγpι1pι1x,cqq
def
“rrrΓ,x :X,c :C$N :Y !pΣ1,E1qssspγ,x,c q,
φγpι1pι2e,cqq
def
“rrrΓ,c :C$Ne :Y !pΣ1,E1qssspγ,cq,
φγpι2psqq
def
“rrrΓ $Ns :Y !pΣ1,E1qsssγ.
Withφ in hand, we may formulate the ﬁnalisation theorem forλcoop, stating that
the semantics ofusingV @W runM ﬁnallyF is a computation tree all of whose
branches end with ﬁnalisation clauses fromF. Thus, unless some enveloping
runner sends a signal, ﬁnalisation withF is guaranteed to take place.
Theorem 7 (Finalisation).A well-typed run factors through ﬁnalisation:
rrrΓ $p usingV @W runM ﬁnallyFq :Y !pΣ1,E1qsssγ“φ:
γt,
for sometP TreeΣ1pprrrXsss`EqˆrrrCsss`Sq.
Proof. We ﬁrst prove thatfuc “ φ:
γpucq holds for all u P KΣ1,E,S,rrrCsssrrrXsss
and c P rrrCsss, where f is the map (5). The proof proceeds by computational
induction onu [29]. The ﬁnalisation statement is then just the special case with
u
def
“ rrrrXsss`EprrrΓ $M :X !pΣ,Eqsssγq and c
def
“rrrΓ $W :Csssγ. [ \
6 Runners in action
Let us show examples that demonstrate how runners can be usefully combined
to provide ﬂexible resource management. We implemented these and other ex-
amples in the languageCoop and a libraryHaskell-Coop, see §7.
To make the code more understandable, we do not adhere strictly to the
syntax ofλcoop, e.g., we use the generic versions of eﬀects [26], as is customary
in programming, and eﬀectful initialisation of kernel state as discussed in §3.2.
Example 8 (Nesting).In Example 4, we considered a runnerﬁleIO for basic ﬁle
operations. Let us suppose thatﬁleIO is implemented by immediate calls to the
operating system. Sometimes, we might prefer to accumulate writes and commit
them all at once, which can be accomplished by interposing betweenﬁleIO and
user code the following runneraccIO, which accumulates writes in its state:

<!-- Page 22 -->

22 Danel Ahman and Andrej Bauer
{ write s'Ñ let s = getenv () in setenv (concat s s') }string
By nesting the runners, and calling the outerwrite (the one ofﬁleIO) only in the
ﬁnalisation code foraccIO, the accumulated writes are commited all at once:
using ﬁleIO @ (open "hello.txt") run
using accIO @ (return "") run
write "Hello, world."; write "Hello, again."
ﬁnally { return x @ sÑ write s; return x }
ﬁnally { return x @ fhÑ ... , raise QuotaExceeded @ fhÑ ... , kill IOErrorÑ ... }
Example 9 (Instrumentation).Above, accIO implements the same signature as
ﬁleIOand thus intercepts operations without the user code being aware of it. This
kind of invisibility can be more generally used to implementinstrumentation:
using { ..., op xÑ let c = getenv () in setenv (c+1); op x, ... }int @ (return 0) run
M
ﬁnally { return x @ cÑ report_cost c; return x, ... }
Here the interposed runner implements all operations of some enveloping runner,
by simply forwarding them, while also measuring computational cost by counting
the total number of operation calls, which is then reported during ﬁnalisation.
Example 10 (ML-style references).Continuingwiththethemeofnestedrunners,
they can also be used to implement abstract and safe interfaces to low-level re-
sources. For instance, suppose we have a low-level implementation of a memory
heap that potentially allows unsafe memory access, and we would like to imple-
ment ML-style references on top of it. A good ﬁrst attempt is the runner
{ ref xÑ let h = getenv () in
let (r,h') = malloc h x in
setenv h'; return r,
get rÑ let h = getenv () in memread h r,
put (r, x)Ñ let h = getenv () in memset h r x }heap
whichhasthedesiredinterface,butstillsuﬀersfromthreedeﬁcienciesthatcanbe
addressed with further language support. First,abstract typeswould let us hide
the fact that references are just memory locations, so that the user code could
never devise invalid references or otherwise misuse them. Second, our simple
typing discipline forces all references to hold the same type, but in reality we
want them to have diﬀerent types. This could be achieved through quantiﬁcation
over types in the low-level implementation of the heap, as we have done in the
Haskell-Coop library using Haskell’sforall. Third, user code could hijack
a reference and misuse it out of the scope of the runner, which is diﬃcult to
prevent. In practice the problem does not occur because, so to speak, the runner
for references is at the very top level, from which user code cannot escape.
Example 11 (Monotonic state).Nested runners can also implement access re-
strictions to resources, with applications in security [8]. For example, we can

<!-- Page 23 -->

Runners in action 23
restrict the references from the previous example to be usedmonotonically by
associating a preorder with each reference, which assignments then have to obey.
Thisideaissimilartohowmonotonicstateisimplementedinthe F˚ language[2],
except that we make dynamic checks whereF˚ statically uses dependent types.
While we could simply modify the previous example, it is better to implement
anewrunnerwhichisnestedinsidethepreviousone,sothatweobtainamodular
solution that works withany runner implementing operationsref, get, and put:
{ mref x relÑ let r = ref x in
let m = getenv () in
setenv (add m (r,rel)); return r,
mget rÑ get r,
mput (r, y)Ñ let x = get r in
let m = getenv () in
match (sel m r) with
| inl relÑ if (rel x y) then put (r, y)
else raise MonotonicityViolation
| inr ()Ñ kill NoPreoderFound }mappref,intRelq
The runner’s state is a map from references to preorders on integers. The co-
operation mref x rel creates a new referencer initialised with x (by callingref of
the outer runner), and then adds the pairpr, relqto the map stored in the runner’s
state.Readingisdelegatedtotheouterrunner,whileassignmentﬁrstchecksthat
the new state is larger than the old one, according to the associated preorder. If
the preorder is respected, the runner proceeds with assignment (again delegated
to the outer runner), otherwise it reports a monotonicity violation. We may not
assume that every reference has an associated preorder, because user code could
pass tomput a reference that was created earlier outside the scope of the runner.
If this happens, the runner simply kills the oﬀending user code with a signal.
Example 12 (Pairing).Another form of modularity is achieved bypairing run-
ners. Given two runnerstpopxÞÑ KopqopPΣ1uC1 and tpop1xÞÑ Kop1qop1PΣ2uC2,
e.g., for state and ﬁle operations, we can use them side-by-side by combining
them into a single runner with operationsΣ1`Σ2 and kernel stateC1ˆC2, as
follows (the co-operationsop1 of the second runner are treated symmetrically):
{ op xÑ let (c,c') = getenv () in
user
kernel (Kop x) @ c ﬁnally {
return y @ c''Ñ return (inl (inl y, c'')),
(raise e @ c''Ñ return (inl (inr e, c'')))ePEop,
(kill sÑ return (inr s))sPS1}
with {
return (inl (inl y, c''))Ñ setenv (c'', c'); return y,
return (inl (inr e, c''))Ñ setenv (c'', c'); raise e,
return (inr s)Ñ kill s},
op' xÑ ... , ... }C1ˆC2
Notice how the innerkernel context switch passes to the co-operationKop only
its part of the combined state, and how it returns the result ofKop in a reiﬁed

<!-- Page 24 -->

24 Danel Ahman and Andrej Bauer
form (which requires treating exceptions and signals as values). The outeruser
context switch then receives this reiﬁed result, updates the combined state, and
forwards the result (return value, exception, or signal) in unreiﬁed form.
7 Implementation
We accompany the theoretical development with two implementations ofλcoop:
a prototype languageCoop [6], and aHaskell library Haskell-Coop [1].
Coop, implemented in OCaml, demonstrates what a more fully-featured
language based onλcoop might look like. It implements a bi-directional variant
of λcoop’s type system, extended with type deﬁnitions and algebraic datatypes,
to provide algorithmic typechecking and type inference. The operational seman-
tics is based on the computation rules of the equational theory from §4.4, but
extended with general recursion, pairing of runners from Example 12, and an in-
terface to theOCaml runtime calledcontainers—these are essentially top-level
runners deﬁned directly inOCaml. They are a modular and systematic way of
oﬀering several possible top-level runtime environments to the programmer.
The Haskell-Cooplibraryisashallowembeddingof λcoop in Haskell.The
implementation closely follows the denotational semantics ofλcoop. For instance,
user and kernel monads are implemented as correspondingHaskell monads.
Internally, the library uses theFreer monad of Kiselyov [14] to implement free
model monads for given signatures of operations. The library also provides a
means to run user code via Haskell’s top-level monads. For instance, code
that performs input-output operations may be run inHaskell’sIO monad.
Haskell’s advanced features make it possible to useHaskell-Coop to
implement several extensions to examples from §6. For instance, we implement
ML-style state that allow references holding arbitrary values (of diﬀerent types),
and state that usesHaskell’s type system to track which references are alive.
The library also provides pairing of runners from Example 12, e.g., to combine
state and input-output. We also use the library to demonstrate thatambient
functions from the Koka language [18] can be implemented with runners by
treating their binding and application as co-operations. (These are functions
that are bound dynamically but evaluated in the lexical scope of their binding.)
8 Related work
Comodels and (ordinary) runners have been used as a natural model of stateful
top-level behaviour. For instance, Plotkin and Power [27] have given a treatment
of operational semantics using the tensor product of a model and a comodel.
Recently, Katsumata, Rivas, and Uustalu have generalised this interaction of
models and comodels to monads and comonads [13]. An early version ofEff [4]
implemented resources, which were a kind of stateful runners, although they
lacked satisfactory theory. Uustalu [35] has pointed out that runners are the
additional structure that one has to impose on state to run algebraic eﬀects
statefully. Møgelberg and Staton’s [21] linear-use state-passing translation also

<!-- Page 25 -->

Runners in action 25
relies on equipping the state with a comodel structure for the eﬀects at hand.
Our runners arise when their setup is specialised to a certain Kleisli adjunction.
Our use of kernel state is analogous to the use of parameters in parameter-
passing handlers [30]: theirreturn clause also provides a form of ﬁnalisation, as
the ﬁnal value of the parameter is available. There is however no guarantee of
ﬁnalisation happening because handlers need not use the continuation linearly.
The need to tame the excessive generality of handlers, and willingness to give
it up in exchange for eﬃciency and predictability, has recently been recognised
by Multicore OCaml’s implementors, who have observed that in practice
most handlers resume continuations precisely once [9]. In exchange for impres-
sive eﬃciency, they require continuations to be used linearly by default, whereas
discarding and copying must be done explicitly, incurring additional cost. Lei-
jen [17] has extended handlers inKoka with a ﬁnally clause, whose semantics
ensures that ﬁnalisation happens whenever a handler discards its continuation.
Leijen also added aninitially clause to parameter-passing handlers, which is used
to compute the initial value of the parameter before handling, but that gets
executed again every time the handler resumes its continuation.
9 Conclusion and future work
We have shown that eﬀectful runners form a mathematically natural and mod-
ular model of resources, modelling not only the top level external resources, but
allowing programmers to also deﬁne their own intermediate “virtual machines”.
Eﬀectful runners give rise to a bona ﬁde programming concept, an idea we have
captured in a small calculus, calledλcoop, which we have implemented both as a
language and a library. We have givenλcoop an algebraically natural denotational
semantics, and shown how to program with runners through various examples.
We leave combining runners and general eﬀect handlers for future work. As
runners are essentially aﬃne handlers, inspired byMulticore OCaml we also
plan to investigate eﬃcient compilation for runners. On the theoretical side, by
developing semantics in a SubpCpoq-enriched setting [32], we plan to support
recursion at all levels, and remove the distinction between ground and arbitrary
types. Finally, by using proof-relevant subtyping [34] and synthesis of lenses [20],
we plan to upgrade subtyping from a simple inclusion to relating types by lenses.
Acknowledgements We thank Daan Leijen for useful discussions about initialisa-
tion and ﬁnalisation inKoka, as well as ambient values and ambient functions.
We thank Guillaume Munch-Maccagnoni and Matija Pretnar for discussing re-
sources and potential future directions forλcoop. We are also grateful to the
participants of the NII Shonan Meeting “Programming and reasoning with alge-
braic eﬀects and eﬀect handlers” for feedback on an early version of this work.
This project has received funding from the European Union’s Hori-
zon 2020 research and innovation programme under the Marie
Skłodowska-Curie grant agreement No 834146.
ThismaterialisbaseduponworksupportedbytheAirForceOﬃceofScientiﬁc
Research under award number FA9550-17-1-0326.

<!-- Page 26 -->

26 Danel Ahman and Andrej Bauer
References
1. Ahman, D.: Library Haskell-Coop. Available at https://github.com/
danelahman/haskell-coop (2019)
2. Ahman,D.,Fournet,C.,Hritcu,C.,Maillard,K.,Rastogi,A.,Swamy,N.:Recalling
a witness: foundations and applications of monotonic state. PACMPL2(POPL),
65:1–65:30 (2018)
3. Bauer, A., Pretnar, M.: An eﬀect system for algebraic eﬀects and handlers. Logical
Methods in Computer Science10(4) (2014)
4. Bauer, A., Pretnar, M.: Programming with algebraic eﬀects and handlers. J. Log.
Algebr. Meth. Program.84(1), 108–123 (2015)
5. Bauer, A.: What is algebraic about algebraic eﬀects and handlers? CoRR
abs/1807.05923 (2018)
6. Bauer, A.: Programming language coop. Available at https://github.com/
andrejbauer/coop (2019)
7. Benton, N., Kennedy, A.: Exceptional syntax. Journal of Functional Programming
11(4), 395–410 (2001)
8. Delignat-Lavaud, A., Fournet, C., Kohlweiss, M., Protzenko, J., Rastogi, A.,
Swamy, N., Zanella-Beguelin, S., Bhargavan, K., Pan, J., Zinzindohoue, J.K.: Im-
plementing and proving the tls 1.3 record layer. In: 2017 IEEE Symp. on Security
and Privacy (SP). pp. 463–482 (2017)
9. Dolan, S., Eliopoulos, S., Hillerström, D., Madhavapeddy, A., Sivaramakrishnan,
K.C., White, L.: Concurrent system programming with eﬀect handlers. In: Wang,
M., Owens, S. (eds.) Trends in Functional Programming. pp. 98–117. Springer
International Publishing, Cham (2018)
10. Foster,J.N.,Greenwald,M.B.,Moore,J.T.,Pierce,B.C.,Schmitt,A.:Combinators
for bidirectional tree transformations: A linguistic approach to the view-update
problem. ACM Trans. Program. Lang. Syst.29(3) (2007)
11. Hyland, M., Plotkin, G., Power, J.: Combining eﬀects: Sum and tensor. Theor.
Comput. Sci. 357(1–3), 70–99 (2006)
12. Kammar, O., Lindley, S., Oury, N.: Handlers in action. In: Proc. of 18th ACM
SIGPLAN Int. Conf. on Functional Programming, ICFP 2013. ACM (2013)
13. Katsumata, S., Rivas, E., Uustalu, T.: Interaction laws of monads and comonads.
CoRR abs/1912.13477 (2019)
14. Kiselyov, O., Ishii, H.: Freer monads, more extensible eﬀects. In: Proc. of 2015
ACM SIGPLAN Symp. on Haskell. pp. 94–105. Haskell ’15, ACM (2015)
15. Koopman, P., Fokker, J., Smetsers, S., van Eekelen, M., Plasmeijer, R.: Functional
Programming in Clean. University of Nijmegen (1998), draft
16. Leijen, D.: Structured asynchrony with algebraic eﬀects. In: Proceedings of
the 2nd ACM SIGPLAN International Workshop on Type-Driven Development,
TyDe@ICFP 2017, Oxford, UK, September 3, 2017. pp. 16–29. ACM (2017)
17. Leijen, D.: Algebraic eﬀect handlers with resources and deep ﬁnalization. Tech.
Rep. MSR-TR-2018-10, Microsoft Research (April 2018)
18. Leijen, D.: Programming with implicit values, functions, and control (or, implicit
functions: Dynamic binding with lexical scoping). Tech. Rep. MSR-TR-2019-7,
Microsoft Research (March 2019)
19. Levy, P.B.: Call-By-Push-Value: A Functional/Imperative Synthesis, Semantics
Structures in Computation, vol. 2. Springer (2004)
20. Miltner, A., Maina, S., Fisher, K., Pierce, B.C., Walker, D., Zdancewic, S.: Synthe-
sizing symmetric lenses. Proc. ACM Program. Lang.3(ICFP), 95:1–95:28 (2019)

<!-- Page 27 -->

Runners in action 27
21. Møgelberg, R.E., Staton, S.: Linear usage of state. Logical Methods in Computer
Science 10(1) (2014)
22. Moggi, E.: Computational lambda-calculus and monads. In: Proc. of 4th Ann.
Symp. on Logic in Computer Science, LICS 1989. pp. 14–23. IEEE (1989)
23. Moggi, E.: Notions of computation and monads. Inf. Comput.93(1), 55–92 (1991)
24. O’Connor, R.: Functor is to lens as applicative is to biplate: Introducing multiplate.
CoRR abs/1103.2841 (2011)
25. Plotkin,G.,Power,J.:Semanticsforalgebraicoperations.In:Proc.of17thConf.on
the Mathematical Foundations of Programming Semantics, MFPS XVII. ENTCS,
vol. 45, pp. 332–345. Elsevier (2001)
26. Plotkin, G., Power, J.: Algebraic operations and generic eﬀects. Appl. Categor.
Struct. (1), 69–94 (2003)
27. Plotkin, G., Power, J.: Tensors of comodels and models for operational semantics.
In: Proc. of 24th Conf. on Mathematical Foundations of Programming Semantics,
MFPS XXIV. ENTCS, vol. 218, pp. 295–311. Elsevier (2008)
28. Plotkin, G.D., Power, J.: Notions of computation determine monads. In: Proc. of
5th Int. Conf. on Foundations of Software Science and Computation Structures,
FOSSACS 2002. LNCS, vol. 2303, pp. 342–356. Springer (2002)
29. Plotkin, G.D., Pretnar, M.: A logic for algebraic eﬀects. In: Proc. of 23th Ann.
IEEE Symp. on Logic in Computer Science, LICS 2008. pp. 118–129. IEEE (2008)
30. Plotkin, G.D., Pretnar, M.: Handling algebraic eﬀects. Logical Methods in Com-
puter Science 9(4:23) (2013)
31. Power, J., Shkaravska, O.: From comodels to coalgebras: State and arrays. Electr.
Notes Theor. Comput. Sci.106, 297–314 (2004)
32. Power, J.: Enriched Lawvere theories. Theory Appl. Categ6(7), 83–93 (1999)
33. Pretnar, M.: The Logic and Handling of Algebraic Eﬀects. Ph.D. thesis, School of
Informatics, University of Edinburgh (2010)
34. Saleh, A.H., Karachalias, G., Pretnar, M., Schrijvers, T.: Explicit eﬀect subtyping.
In: Proc. of 27th European Symposium on Programming, ESOP 2018. pp. 327–354.
LNCS, Springer (2018)
35. Uustalu, T.: Stateful runners of eﬀectful computations. Electr. Notes Theor. Com-
put. Sci. 319, 403–421 (2015)
36. Wadler, P.: The essence of functional programming. In: Sethi, R. (ed.) Proc. of 19th
Ann. ACM SIGPLAN-SIGACT Symp. on Principles of Programming Languages,
POPL 1992. pp. 1–14. ACM (1992)
Open Access This chapter is licensed under the terms of the Creative Commons
Attribution 4.0 International License (http://creativecommons.org/licenses/by/4.0/),
which permits use, sharing, adaptation, distribution and reproduction in any medium
or format, as long as you give appropriate credit to the original author(s) and the
source, provide a link to the Creative Commons license and indicate if changes were
made.
Theimagesorotherthirdpartymaterialinthischapterareincludedinthechapter’s
Creative Commons license, unless indicated otherwise in a credit line to the material. If
material is not included in the chapter’s Creative Commons license and your intended
use is not permitted by statutory regulation or exceeds the permitted use, you will need
to obtain permission directly from the copyright holder.


<!-- Page 28 -->

28 Danel Ahman and Andrej Bauer
Appendix A: Typing rules ofλcoop
In this appendix we give the complete typing rules forλcoop. We refer to Figs. 1
and 2 for the syntax of types, values, and user and kernel computations. For
each operation symbolopP O, we assume a given and ﬁxed operation signature
op :Aop⇝Bop !Eop,
and for each ground constantf, we assume a signaturef : pA1,...,A nq ÑB,
both of which the typing rules refer to without further ado. Values, and user and
kernel computations each have a typing and a subtyping judgement of the form
Γ $V :X, Γ $M :X ! U, Γ $K :X☇K,
X ĎY, X ! U ĎY ! V, X ☇K ĎY☇L.
whereΓ is the customary typing context assigning value types to variables. The
subtyping rules are given in Fig. 6, and the typing rules in Figs. 7 to 9.
Appendix B: Equational theory ofλcoop
Values, user and kernel computations each have an equality judgement
Γ $V ”W :X Γ $M”N :X ! U Γ $K”L :X ! K.
It is presupposed that we only compare well-typed expressions with the indicated
types. For the most part, the context and the type annotation will play no part
in the equation, and so we shall drop them when no confusion can arise.
The computational equations are displayed in Figs. 10 and 11. These can
be read left-to-right as evaluation rules that explain the operational meaning of
computations. The remaining equations are displayed in Fig. 12. We omit stan-
dard equations which specify how substitution is performed, as well as equations
stating that equality is a congruence with respect to all the term formers.

<!-- Page 29 -->

Runners in action 29
Sub-Ground
A ĎA
Sub-Product
X ĎX1 Y ĎY1
XˆY ĎX1ˆY1
Sub-Sum
X ĎX1 Y ĎY1
X`Y ĎX1`Y1
Sub-UserFun
X1 ĎX Y ! U ĎY1 ! U1
XÑY ! U ĎX1ÑY1 ! U1
Sub-KernelFun
X1 ĎX Y ☇K ĎY1☇K1
XÑY☇K ĎX1ÑY1☇K1
Sub-Runner
Σ1
1ĎΣ1 Σ2ĎΣ1
2 SĎS1 C”C1
Σ1ñpΣ2,S,C q ĎΣ1
1ñpΣ1
2,S1,C1q
Sub-User
X ĎX1 ΣĎΣ1 EĎE1
X !pΣ,Eq ĎX1 !pΣ1,E1q
Sub-Kernel
X ĎX1 ΣĎΣ1 EĎE1
SĎS1 C”C1
X☇pΣ,E,S,C q ĎX1☇pΣ1,E1,S1,C1q
Subsume-V alue
Γ $V :X X ĎX1
Γ $V :X1
Subsume-User
Γ $M :X ! U X ! U ĎX1 ! U1
Γ $M :X1 ! U1
Subsume-Kernel
Γ $K :X☇K X☇K ĎX1☇K1
Γ $M :X1☇K1
Fig. 6. Subtyping and subsumption rules.
TyV alue-V ar
Γpxq” X
Γ $x :X
TyV alue-Const
pΓ $Vi :Aiq1ďiďn
Γ $ fpV1,...,V nq :B
TyV alue-Unit
Γ $pq : unit
TyV alue-Pair
Γ $V :X Γ $W :Y
Γ $pV,Wq :XˆY
TyV alue-Inl
Γ $V :X
Γ $ inlX,Y V :X`Y
TyV alue-Inr
Γ $W :Y
Γ $ inrX,Y W :X`Y
TyV alue-UserFun
Γ,x :X$M :Y ! U
Γ $ funpx :XqÞÑ M :XÑY ! U
TyV alue-KernelFun
Γ,x :X$K :Y☇K
Γ $ funKpx :XqÞÑ K :XÑY☇K
TyV alue-Runner`
Γ,x :Aop$Kop :Bop☇pΣ1,E op,S,C q
˘
opPΣ
Γ $tp opxÞÑKopqopPΣuC :ΣñpΣ1,S,C q
Fig. 7. Value typing rules.

<!-- Page 30 -->

30 Danel Ahman and Andrej Bauer
TyUser-Return
Γ $V :X
Γ $ returnV :X ! U
TyUser-Apply
Γ $V :XÑY ! U Γ $W :X
Γ $V W :Y ! U
TyUser-Try
Γ $M :X !pΣ,Eq Γ,x :X$N :Y !pΣ,E1q
`
Γ $Ne :Y !pΣ,E1q
˘
ePE
Γ $ tryM withtreturnxÞÑN,praiseeÞÑNeqePEu :Y !pΣ,E1q
TyUser-MatchPair
Γ $V :XˆY Γ,x :X,y :Y $M :Z ! U
Γ $ matchV withtpx,yqÞÑ Mu :Z ! U
TyUser-MatchEmpty
Γ $V : empty
Γ $ matchV withtuZ :Z ! U
TyUser-MatchSum
Γ $V :X`Y Γ,x :X$M :Z ! U Γ,y :Y $N :Z ! U
Γ $ matchV withtinlxÞÑM, inryÞÑNu :Z ! U
TyUser-Op
U”pΣ,Eq opPΣ Γ $V :Aop
Γ,x :Bop$M :X ! U
`
Γ $Ne :X ! U
˘
ePEop
Γ $ opXpV,px.M q,pNeqePEopq :X ! U
TyUser-Raise
ePE
Γ $ raiseXe :X !pΣ,Eq
TyUser-Run
F ”t returnx @cÞÑN,praisee @cÞÑNeqePE,pkillsÞÑNsqsPSu
Γ $V :ΣñpΣ1,S,C q Γ $W :C
Γ $M :X !pΣ,Eq Γ,x :X,c :C$N :Y !pΣ1,E1q`
Γ,c :C$Ne :Y !pΣ1,E1q
˘
ePE
`
Γ $Ns :Y !pΣ1,E1q
˘
sPS
Γ $ usingV @W runM ﬁnallyF :Y !pΣ1,E1q
TyUser-Kernel
F ”t returnx @cÞÑN,praisee @cÞÑNeqePE,pkillsÞÑNsqsPSu
Γ $K :X☇pΣ,E,S,C q Γ $W :C Γ,x :X,c :C$N :Y !pΣ,E1q`
Γ,c :C$Ne :Y !pΣ,E1q
˘
ePE
`
Γ $Ns :Y !pΣ,E1q
˘
sPS
Γ $ kernelK @W ﬁnallyF :Y !pΣ,E1q
Fig. 8. User typing rules.

<!-- Page 31 -->

Runners in action 31
TyKernel-Return
Γ $V :X
Γ $ returnCV :X☇pΣ,E,S,C q
TyKernel-Apply
Γ $V :XÑY☇K Γ $W :X
Γ $V W :Y☇K
TyKernel-Try
Γ $K :X☇pΣ,E,S,C q
Γ,x :X$L :Y☇pΣ,E1,S,C q
`
Γ $Le :Y☇pΣ,E1,S,C q
˘
ePE
Γ $ tryK withtreturnxÞÑL,praiseeÞÑLeqePEu :Y☇pΣ,E1,S,C q
TyKernel-MatchPair
Γ $V :XˆY Γ,x :X,y :Y $K :Z☇K
Γ $ matchV withtpx,yqÞÑ Ku :Z☇K
TyKernel-MatchEmpty
Γ $V : empty
Γ $ matchV withtuZ@C :Z☇pΣ,E,S,C q
TyKernel-MatchSum
Γ $V :X`Y Γ,x :X$K :Z☇K Γ,y :Y $L :Z☇K
Γ $ matchV withtinlxÞÑK, inryÞÑLu :Z☇K
TyKernel-Op
K”pΣ,E,S,C q opPΣ Γ $V :Aop
Γ,x :Bop$K :X☇K
`
Γ $Le :X☇K
˘
ePEop
Γ $ opXpV,px.K q,pLeqePEopq :X☇K
TyKernel-Raise
ePE
Γ $ raiseX@Ce :X☇pΣ,E,S,C q
TyKernel-Kill
sPS
Γ $ killX@Cs :X☇pΣ,E,S,C q
TyKernel-Getenv
Γ,c :C$K :X☇pΣ,E,S,C q
Γ $ getenvCpc.K q :X☇pΣ,E,S,C q
TyKernel-Setenv
Γ $V :C Γ $K :X☇pΣ,E,S,C q
Γ $ setenvpV,Kq :X☇pΣ,E,S,C q
TyKernel-User
K”pΣ,E1,S,C q Γ $M :X !pΣ,Eq
Γ,x :X$K :Y☇K
`
Γ $Le :Y☇K
˘
ePE
Γ $ userM withtreturnxÞÑK,praiseeÞÑLeqePEu :Y☇K
Fig. 9. Kernel typing rules.

<!-- Page 32 -->

32 Danel Ahman and Andrej Bauer
pfunpx :XqÞÑ MqV ”MrV{xs
trypreturnVq withH”NrV{xs
trypraiseXeq withH”Ne
trypopXpV,px.M q,pN1
e1qe1PEopqq withH”
opXpV,px. tryM withHq,
`
tryN1
e1 withH
˘
e1PEop
q
matchpV,Wq withtpx,yqÞÑ Mu” MrV{x,W{ys
matchV withtuX”N
matchpinlX,Y Vq withtinlxÞÑM, inryÞÑNu” MrV{xs
matchpinrX,Y Wq withtinlxÞÑM, inryÞÑNu” NrW{ys
usingV @W runpreturnV1q ﬁnallyF ”NrV1{x,W{cs
usingV @W runpraiseXeq ﬁnallyF ”NerW{cs
usingR @W run opXpV,px.M q,pN1
e1qe1PEopq ﬁnallyF ”
kernelKoprV{xs @W ﬁnallyF1
where F1 def
“ treturnx @c1ÞÑp usingR @c1 runM ﬁnallyFq,`
raisee1 @c1ÞÑp usingR @c1 runN1
e1 ﬁnallyFq
˘
e1PEop
,
pkillsÞÑNsqsPSu
kernelpreturnCVq @W ﬁnallyF ”NrV{x,W{cs
kernelpraiseX@Ceq @W ﬁnallyF ”NerW{cs
kernelpkillX@Csq @W ﬁnallyF ”Ns
kernelpgetenvCpc.K qq @W ﬁnallyF ” kernelKrW{cs @W ﬁnallyF
kernelpsetenvpV,Kqq @W ﬁnallyF ” kernelK @V ﬁnallyF
kernel opXpV,px.K q,pLe1qe1PEopq @W ﬁnallyF ”
opXpV,px. kernelK @W ﬁnallyFq,pkernelLe1 @W ﬁnallyFqe1PEopq
Abbreviations:
F
def
“ treturnx @cÞÑN,praisee @cÞÑNeqePE,pkillsÞÑNsqsPSu
H
def
“ treturnxÞÑN,praiseeÞÑNeqePEu
R
def
“ tpopxÞÑKopqopPΣuC
Fig. 10. Computational equations (user mode).

<!-- Page 33 -->

Runners in action 33
pfunKpx :XqÞÑ KqV ”KrV{xs
trypreturnVq withG”LrV{xs
trypraiseX@Ceq withG”Le
trypkillX@Csq withG” killX@Cs
trypopXpV,px.K q,pL1
e1qe1PEopqq withG”
opXpV,px. tryK withGq,
`
tryL1
e1 withG
˘
e1PEop
q
trypgetenvCpc.K qq withG” getenvCpc. tryK withGq
trypsetenvpV,Kqq withG” setenvpV, tryK withGq
matchpV,Wq withtpx,yqÞÑ Ku” KrV{x,W{ys
matchV withtuX@C”K
matchpinlX,Y Vq withtinlxÞÑK, inryÞÑLu” KrV{xs
matchpinrX,Y Wq withtinlxÞÑK, inryÞÑLu” LrW{ys
userpreturnVq withG”LrV{xs
userpraiseXeq withG”Le
userpopXpV,px.M q,pN1
e1qe1PEopqq withG”
opXpV,px. userM withGq,
`
userN1
e1 withG
˘
e1PEop
q
Abbreviation: G
def
“ treturnxÞÑL,praiseeÞÑLeqePEu
Fig. 11. Computational equations (kernel mode).
V ”pq : unit fun px :AqÞÑ V x”V funKpx :AqÞÑ V x”V
tryM withtreturnxÞÑ returnx,praiseeÞÑ raiseXeqePEu” M
tryK withtreturnxÞÑ returnx,praiseeÞÑ raiseX@CeqePEu” K
getenvCpc. setenvpc,Kqq” K
setenvpV, getenvCpc.K qq” setenvpV,KrV{csq
setenvpV, setenvpW,Kqq” setenvpW,Kq
getenvCpc. killX@Csq” killX@Cs
setenvpV, killX@Csq” killX@Cs
getenvCpc. opXpV,px.K q,pLeqePEopqq”
opXpV,px. getenvCpc.K qq,pgetenvCpc.L eqqePEopq
setenvpV, opXpV,px.K q,pLeqePEopqq”
opXpV,px. setenvpV,Kqq,psetenvpV,LeqqePEopq
Fig. 12. Other equations (forη-expansion and the kernel theory from §3.1).