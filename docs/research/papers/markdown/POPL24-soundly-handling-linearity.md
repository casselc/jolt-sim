# Soundly Handling Linearity

> **Machine-generated Markdown conversion — this is a MODIFIED version.**
> Converted from `POPL24-soundly-handling-linearity.pdf` with PyMuPDF. Layout, mathematics,
> figures and tables are lossy; **quote from the PDF, not from this file.**
> Page anchors below correspond to PDF pages.

- **Citation:** Wenhao Tang, Daniel Hillerstrom, Sam Lindley, J. Garrett Morris. POPL 2024. doi:10.1145/3632896
- **Licence:** CC-BY 4.0 (arXiv posting)
- **Source:** https://arxiv.org/abs/2307.09383

---


<!-- page 1 -->

## Soundly Handling Linearity

WENHAO TANG, The University of Edinburgh, United Kingdom

DANIEL HILLERSTRÖM, Huawei Zurich Research Center, Switzerland SAM LINDLEY, The University of Edinburgh, United Kingdom J. GARRETT MORRIS, University of Iowa, USA

## arXiv:2307.09383v2  [cs.PL]  2 Jan 2024

We propose a novel approach to soundly combining linear types with multi-shot effect handlers. Linear type systems statically ensure that resources such as file handles and communication channels are used exactly once. Effect handlers provide a rich modular programming abstraction for implementing features ranging from exceptions to concurrency to backtracking. Whereas conventional linear type systems bake in the assumption that continuations are invoked exactly once, effect handlers allow continuations to be discarded (e.g. for exceptions) or invoked more than once (e.g. for backtracking). This mismatch leads to soundness bugs in existing systems such as the programming language Links, which combines linearity (for session types) with effect handlers. We introduce control-flow linearity as a means to ensure that continuations are used in accordance with the linearity of any resources they capture, ruling out such soundness bugs.

We formalise the notion of control-flow linearity in a System F-style core calculus F◦

eff equipped with linear types, an effect type system, and effect handlers. We define a linearity-aware semantics in order to formally prove that F◦

eff preserves the integrity of linear values in the sense that no linear value is discarded or duplicated. In order to show that control-flow linearity can be made practical, we adapt Links based on the design of F◦

eff, in doing so fixing a long-standing soundness bug. Finally, to better expose the potential of control-flow linearity, we define an ML-style core calculus Q◦

eff, based on qualified types, which requires no programmer provided annotations, and instead relies entirely on type inference to infer control-flow linearity. Both linearity and effects are captured by qualified types. Q◦

eff overcomes a number of practical limitations of F◦

eff, supporting abstraction over linearity, linearity dependencies between type variables, and a much more fine-grained notion of control-flow linearity.

CCS Concepts: • Theory of computation →Control primitives; Type structures.

Additional Key Words and Phrases: control-flow linearity, multi-shot continuations, linear resources

ACM Reference Format: Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris. 2024. Soundly Handling Linearity. Proc. ACM Program. Lang. 8, POPL, Article 54 (January 2024), 51 pages. https://doi.org/10.1145/3632896

## 1 INTRODUCTION

Many programming languages support linear resources such as file handles, communication channels, network connections, and so forth. Special care must be taken to preserve the integrity of linear resources in the presence of first-class continuations that may be invoked multiple times [Friedman and Haynes 1985], as a linear resource may be inadvertently be accessed more than once. Java [Pressler 2018] and OCaml [Sivaramakrishnan et al. 2021] have each recently been retrofitted with facilities for programming with first-class continuations that must be invoked

Authors' addresses: Wenhao Tang, The University of Edinburgh, United Kingdom, wenhao.tang@ed.ac.uk; Daniel Hillerström, Huawei Zurich Research Center, Switzerland, daniel.hillerstrom@ed.ac.uk; Sam Lindley, The University of Edinburgh, United Kingdom, sam.lindley@ed.ac.uk; J. Garrett Morris, University of Iowa, USA, garrett-morris@uiowa.edu.

Permission to make digital or hard copies of part or all of this work for personal or classroom use is granted without fee provided that copies are not made or distributed for profit or commercial advantage and that copies bear this notice and the full citation on the first page. Copyrights for third-party components of this work must be honored. For all other uses, contact the owner/author(s). © 2024 Copyright held by the owner/author(s). ACM 2475-1421/2024/1-ART54 https://doi.org/10.1145/3632896

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 2 -->

54:2 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

exactly once, partly in order to avoid such pitfalls. Nonetheless, multi-shot continuations are a compelling feature, supporting applications such as backtracking search [Friedman et al. 1984] and probabilistic programming [Kiselyov and Shan 2009]. In this paper we explore how to soundly handle linearity in the presence of multi-shot effect handlers [Plotkin and Pretnar 2013].

We first illustrate the issues with combining linearity with multi-shot effect handlers by exhibiting a soundness bug in the programming language Links [Cooper et al. 2006], which is equipped with linear session-typed channels [Lindley and Morris 2017] and effect handlers with multi-shot continuations [Hillerström et al. 2020a]. We begin by defining a function outch that forks a child process and returns an output channel for communicating with it. The idea is that we will use a combination of exceptions and multi-shot continuations to send two integers, rather than an integer followed by a string, along the endpoint (with session type !Int.!String.End) returned by the function outch.

## sig outch : () ~> !Int.!String.End

## fun outch() {

## fork(fun(ic) {

## var (i, ic) = receive(ic);

# receive the integer

## var (s, ic) = receive(ic);

# receive the string

println(intToString(i) ^^ s); # convert, concat, and print

close(ic) # close the input channel

})

}

The primitive fork creates a child process and two endpoints of a session-typed channel. One endpoint is passed to the child process and the other endpoint is returned to the caller. Here the function returns an output endpoint of type !Int.!String.End and the child process is supplied with an input endpoint of type ?Int.?String.End. The child receives an integer and a string on the input endpoint, then prints them out before closing the endpoint.

Now we invoke outch in a context in which we exploit the power of multi-shot continuations to return twice and the power of exceptions to abort the current computation.

## handle({

## var oc = outch();

## var msg = if (do Choose) 42 else 84; # choose an integer message to send

## var oc = send(msg, oc);

## do Fail;

# this is our exception

## var oc = send("well-typed", oc);

close(oc)

}) {

## case <Fail> -> ()

## case <Choose => resume> -> resume(true); resume(false)

}

We handle a computation that performs two operations: 1) Choose : () => Bool; and 2) Fail :

forall a. () => a. The handled computation invokes outch, forking a child process and binding the output endpoint of the resulting channel to oc. Next, it invokes the operation Choose to select between two possible integer messages, which is sent on the channel. Then, it performs the Fail operation, before sending a string along the channel and closing it. This is all very well and satisfies the type-checker; however, the described control flow is not actually what happens, because in fact the continuation of Choose is invoked twice and the continuation of Fail is never invoked. The

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 3 -->

Soundly Handling Linearity 54:3

behaviours of Fail and Choose are defined by the corresponding operation clauses of the handler. For Fail the captured continuation is discarded (it must be: it is never bound); for Choose the continuation is bound to resume and invoked twice: first with true and then with false.

Running the program causes a segmentation fault when printing the received values, as it erroneously attempts to concatenate a string with an integer. To see why, follow the control flow of the parent process. It performs Choose, which initially selects 42 and sends it over the channel. The child process receives this integer and subsequently expects to receive a string. Back on the parent process execution is aborted via Fail, which causes the initial invocation of resume to return, leading to the second invocation of resume, which restores the aborted context at the point of selecting an integer. Now Choose selects 84 and sends it over the channel. The child process receives this second integer, mistakenly treating it as a string.

In this paper we rule out such soundness bugs by tracking control-flow linearity: a means to statically assure how often a continuation may be invoked, mediating between linear resources and effectful operations to ensure that effect handlers cannot violate linearity constraints on resources.

The main contributions of this paper are:

• We give high-level overview of the main ideas of the paper through a series of worked examples that illustrate the difficulties of combining effect handlers with linearity, how they can be resolved by tracking control-flow linearity, and how the approach can be refined using qualified types [Jones 1994] (Section 2). • We introduce F◦ eff (pronounced "F-eff-pop"), a System F-style core calculus equipped with linear types, an effect type system, and effect handlers (Section 3). We prove syntactic type soundness and a semantic linear safety property. • Inspired by F◦ eff we implement control-flow linearity in Links, fixing a long-standing typesoundness bug (Section 4). • Motivated by expressiveness limitations of F◦ eff we introduce Q◦

eff (pronounced "Q-eff-pop"), an ML-style core calculus inspired by Quill [Morris 2016] and Rose [Morris and McKinna 2019], based on qualified types (Section 5). We prove soundness and completeness of type inference for Q◦

eff. Along the way, we identify a semantic soundness bug in Quill and conjecture a fix.

Section 6 outlines how control-flow linearity applies to shallow handlers [Hillerström and Lindley 2018]. Section 7 discusses related work and Section 8 conclude and discusses future work.

## 2 OVERVIEW

In this section, we give a high-level overview of the main ideas of the paper by way of a series of examples. We first compare standard value linearity with non-standard control-flow linearity, illustrating how the latter may be tracked in an explicit calculus F◦

eff (Section 3). For readability we omit uninteresting syntactic artifacts from our examples. We show how control-flow linearity allows linear resources and multi-shot continuations to coexist peacefully. We then highlight two limitations of F◦

eff: linear types require syntactic overhead which harms modularity, and rowpolymorphism based effect types lead to coarse tracking of control-flow linearity. We exploit qualified types to relax both limitations in an ML-style calculus Q◦

eff (Section 5).

### 2.1 Value Linearity

Value linearity classifies the use of values: linear values must be used exactly once whereas unlimited values can be used zero, one, or multiple times (linear types differ from uniqueness types, which instead track the number of references to a value). Equivalently, value linearity characterises whether values contain linear resources: linear values can contain linear resources whereas unlimited values cannot. Conventional linear type systems track value linearity. F◦

eff adapts the subkinding-based

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 4 -->

54:4 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

linear type system of F◦[Mazurak et al. 2010]. The linearity 𝑌of a value type is part of its kind Type𝑌and can be either linear ◦or unlimited •. For example, file handles are linear resources (File : Type◦) and integers are unlimited resources (Int : Type•).

A linearity annotation on a 𝜆-abstraction defines the linearity of the function itself. Consider the following function faithfulWrite which takes a file handle 𝑓and returns another function that takes a string 𝑠, faithfully writes 𝑠to 𝑓, and then closes the file handle.

faithfulWrite : File →• (String →◦()) faithfulWrite = 𝜆•𝑓.(𝜆◦𝑠.let 𝑓′ ←write (𝑠, 𝑓) in close 𝑓′)

The outer unlimited function (→•) yields a linear function (→◦) expecting a string. The linear type system dictates that the inner function is linear as it captures the linear file handle 𝑓.

One important property of value linearity is that unlimited value types can be treated as linear value types, as it is always safe to use unlimited values (which contain no linear resources) just once. This property is embodied by the subkinding relation ⊢Type• ≤Type◦in F◦

eff. For instance, consider the polymorphic identity function.

id : ∀𝜇Row 𝛼Type◦. 𝛼→• 𝛼! {𝜇} id = Λ𝜇Row 𝛼Type◦. 𝜆•𝑥. 𝑥

The return type of the function is a computation type 𝛼! {𝜇} where 𝛼is the linear type of values returned (𝑥is used exactly once) and 𝜇is the row of effects performed by the function. (We chose to omit the corresponding effect annotations in the signature of faithfulWrite because they are empty, but henceforth we will write them explicitly.) Subkinding allows the identity function to be applied to both linear and unlimited values. It is always sound to use an unlimited value exactly once. Thus, we have both ⊢Int : Type◦and ⊢File : Type◦, and if 𝑅is an effect row type:

id 𝑅File : File →• File ! {𝑅} id 𝑅Int : Int →• Int ! {𝑅}

### 2.2 Control-Flow Linearity

Control-flow linearity tracks how many times control may enter a local context: a control-flowlinear context must be entered exactly once; a control-flow-unlimited context may be entered zero, one, or multiple times. Equivalently, control-flow linearity characterises whether a local context captures linear resources: a control-flow-linear context can capture linear resources; a control-flow-unlimited context cannot.

To better explain control-flow linearity, we first reprise the soundness problem due to the interaction of linear resources and multi-shot continuations of Section 1 via a simpler example in F◦

eff. Consider the following function dubiousWrite✗, which takes a file handle and non-deterministically writes "A" or "B" to it depending on the result of Choose. We ignore control-flow linearity for now.

dubiousWrite✗: File →• () ! {Choose : () ↠Bool} dubiousWrite✗= 𝜆•𝑓.

let 𝑏←(do Choose ()){Choose:()↠Bool} in

let 𝑠←if 𝑏then "A" else "B" in let 𝑓′ ←write (𝑠, 𝑓) in close 𝑓′

continuation of Choose

The do Choose () expression invokes operation Choose with a unit argument. F◦

eff adapts an effect system based on Rémy-style row polymorphism [Hillerström and Lindley 2016; Lindley and Cheney 2012]. Effect types in F◦ eff are rows containing operation labels with their signatures and ended with potential row variables. The effect type {Choose : () ↠Bool} denotes that dubiousWrite✗may

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 5 -->

Soundly Handling Linearity 54:5

let 𝑓←open "C.txt" in handle (dubiousWrite✗𝑓) with {Choose _ 𝑟↦→𝑟true ;𝑟false}

dubiousWrite✓: File →• () ! {Choose : () ↠◦Bool} dubiousWrite✓= 𝜆•𝑓.

let◦𝑏←(do Choose ()){Choose:()↠◦Bool} in

let◦𝑠←if 𝑏then "A" else "B" in let◦𝑓′ ←write (𝑠, 𝑓) in close 𝑓′

The linear type system of F◦

let 𝑓←open "C.txt" in handle (dubiousWrite✓𝑓) with {Choose _ 𝑟↦→𝑟true ;𝑟false}

This is ill-typed due to the fact that Choose is control-flow linear, which means the resumption 𝑟 has a linear function type, meaning it must be applied exactly once.

tossCoin : ∀𝜇Row•.(() →• Bool ! {𝜇}) →• String ! {𝜇} tossCoin = Λ𝜇Row•.𝜆•𝑔. let• 𝑏←𝑔() in if 𝑏then "heads" else "tails"

invoke the operation Choose, which takes a unit and returns a boolean value as indicated by its signature () ↠Bool. The problem arises when we handle Choose using multi-shot continuations.

The file "C.txt" is opened and the file handle is bound to 𝑓before dubiousWrite✗𝑓is handled by an effect handler that handles the Choose operation. In the handler clause, 𝑟binds the continuation of Choose, which expects a parameter of type Bool. As 𝑟is invoked twice (first with true and then with false), the file handle 𝑓is written and closed twice, which leads to a runtime error because it is closed before the second write. The essential problem is that the continuation of Choose should be used linearly as it captures the linear file handle 𝑓, but it is invoked twice by the effect handler. Conventional linear type systems cannot detect this kind of error as they only track value linearity.

Motivated by the observation that only a local context, reified as the continuation of an operation, may be captured by a multi-shot handler, we track control-flow linearity at the granularity of operations. We use the control-flow linearity of an operation to represent the control-flow linearity of the continuation of the operation. Control-flow-linear operations can be used in contexts which may contain linear resources, whereas control-flow-unlimited operations cannot. An operation signature 𝐴↠𝑌𝐵is annotated with a linearity 𝑌to denote its control-flow linearity. The dubiousWrite✗ function can now be rewritten to correctly track control-flow linearity as follows.

continuation of Choose

Now, the type of dubiousWrite✓specifies that the operation Choose : () ↠◦Bool is control-flow linear (i.e. the continuation of Choose is linear). We also annotate let-bindings with linearity information. In let𝑌𝑥←𝑀in 𝑁, the term 𝑁has control-flow linearity 𝑌, and in particular the ◦annotations on the let-bindings in dubiousWrite✓permit the use of the linear file handle throughout.

eff uses the control-flow linearity of operations to restrict the use of continuations in handlers, which ensures that control-flow-linear contexts are entered only once. For instance, consider the handling of dubiousWrite✓with the same multi-shot handler.

We lift the control-flow linearity of operations to effect row types and reflect it in their kinds Row𝑌. Similar to value linearity, we also have a subkinding relation for control-flow linearity. Recall that the control-flow linearity of (the operations in) effect row types is actually the control-flow linearity of their contexts, not themselves. This induces a duality between value linearity and control-flow linearity paralleling the duality between positive values and negative continuations. As a consequence, the subkinding relation for control-flow linearity is ⊢Row◦≤Row•, the reverse of that for value linearity. Intuitively, this says that control-flow-linear operations can be treated as control-flow-unlimited operations, because it is safe to use control-flow-linear operations in unlimited contexts. For example, consider the following function tossCoin which takes a function that returns a boolean and tosses a coin using this function.

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 6 -->

tossCoin 𝑅1 (𝜆•().(do Choose ()){𝑅1}) : String ! {𝑅1} tossCoin 𝑅2 (𝜆•().(do Choose ()){𝑅2}) : String ! {𝑅2}

### 2.3 Qualified Linear Types

As we have seen from the examples so far, F◦

verboseId : ∀𝜇Row𝑌1 𝛼Type𝑌2 . 𝛼→𝑌0 𝛼! {Print : String ↠𝑌3 () ; 𝜇} verboseId = Λ𝜇Row𝑌1 𝛼Type𝑌2 . 𝜆𝑌0𝑥. let𝑌4 () ←do Print "id is called" in 𝑥

∀𝜇• 𝛼•.𝛼→• 𝛼! {Print : • ; 𝜇}

∀𝜇• 𝛼•.𝛼→• 𝛼! {Print : ◦; 𝜇}

∀𝜇◦𝛼•.𝛼→• 𝛼! {Print : • ; 𝜇}

∀𝜇◦𝛼•.𝛼→• 𝛼! {Print : ◦; 𝜇}

∀𝜇◦𝛼◦.𝛼→• 𝛼! {Print : ◦; 𝜇}

verboseId : ∀𝛼𝜇𝜙𝜙′. (𝛼⪯𝜙) ⇒𝛼→𝜙′ 𝛼! {Print : 𝜙; 𝜇} verboseId = 𝜆𝑥. do Print "42" ; 𝑥

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

54:6 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

As no linear resource is used, the effect type of tossCoin and its parameter is given by a controlflow-unlimited row variable 𝜇: Row•. Via subkinding, we can instantiate 𝜇with operations with either control-flow linearity. For instance, suppose we have ⊢𝑅1 : Row• and ⊢𝑅2 : Row◦for 𝑅1 = Choose : () ↠• Bool and 𝑅2 = Choose : () ↠◦Bool, then:

The subkinding relation of control-flow linearity only influences how operations are used, not how they are handled. We can use control-flow-linear operations as control-flow-unlimited operations (i.e., use them in unlimited contexts), but this does not imply that we can handle controlflow-linear operations as control-flow-unlimited operations (i.e., handle them by resuming any number of times). Our linear type system does not allow control-flow-linear operations to be handled by multi-shot handlers despite the subkinding relation Row◦≤Row•. This is because when handling, we directly look at the control-flow linearity on operation signatures instead of their kinds, where no ↠◦can be upcast to ↠•. This can be seen more clearly from the typing rules in Section 3.2. We formally state the soundness of F◦

eff in Sections 3.4 and 3.5.

eff requires linearity annotations on 𝜆-abstractions and let-bindings. Though this can suffice for an explicit calculus, it can prove cumbersome for practical programming languages and curtail the modularity of programs. Unfortunately, we cannot entirely overcome these limitations by introducing subsumption relations between types, or using Hindley-Milner type inference to infer them. The reason is that there are inner dependencies on the linearity. For instance, consider the following function verboseId which is almost the same as the function id in Section 2.1 but outputs the log message "id is called" using the operation Print : String ↠() before returning.

Depending on different choices of 𝑌0, 𝑌1, 𝑌2, 𝑌3, and 𝑌4, we can give ten well typed variations of verboseId. Their types are shown as follows, omitting primary kinds and signatures for readability.

∀𝜇• 𝛼•.𝛼→◦𝛼! {Print : • ; 𝜇}

∀𝜇• 𝛼•.𝛼→◦𝛼! {Print : ◦; 𝜇}

∀𝜇◦𝛼•.𝛼→◦𝛼! {Print : • ; 𝜇}

∀𝜇◦𝛼•.𝛼→◦𝛼! {Print : ◦; 𝜇}

∀𝜇◦𝛼◦.𝛼→◦𝛼! {Print : ◦; 𝜇}

The key observation is that the control-flow linearity of the operation Print (as well as the row variable 𝜇) depends on the value linearity of the parameter type 𝛼, because the parameter 𝑥is used in the continuation of Print. To express this kind of dependency, we use a linear type system based on qualified types inspired by Quill [Morris 2016]. In the ML-style calculus Q◦

eff with qualified linear types, verboseId can be written and ascribed a principal type as follows.

The linearity variables 𝜙and 𝜙′ quantify over ◦and •. We do not use kinds to represent linearity of type variables; instead, all linearity information is represented using predicates of the form 𝜏⪯𝜏′, where 𝜏is a value type, row type or linearity type (◦, • or a linearity variable). The type scheme of


<!-- page 7 -->

Soundly Handling Linearity 54:7

verboseId is extended with the predicate 𝛼⪯𝜙, meaning that the value linearity of 𝛼is less than that of 𝜙, which is the control-flow linearity of Print. This type scheme succinctly expresses all ten possibilities listed above. The type inference algorithm of Q◦

eff (Section 5.4) infers all such linearity dependency constraints without the need for any type, effect, or linearity annotations.

### 2.4 Qualified Effect Types

In addition to the syntactic overhead of linear types, the row-based effect system of F◦

eff is also not entirely satisfying when tracking control-flow linearity. Row-based effect systems have demonstrated their practicality in research languages such as Links [Hillerström and Lindley 2016], Koka [Leijen 2017], and Frank [Lindley et al. 2017]. In such effect systems, sequenced computations must have the same effect type, which can be smoothly realised by unification in systems based on Hindley-Milner type inference. However, though fixing effect types between sequenced computations is often acceptable, it does introduce some imprecision, and this can become more pronounced when control-flow linearity is brought into the mix.

To see the problem concretely in F◦

eff, consider the following function verboseClose which takes a file handle, reads a string using the operation Get : () ↠String, closes the file handle, and outputs the string using the operation Print : String ↠().

verboseClose : File →• () ! {𝑅} verboseClose = 𝜆•𝑓. let◦𝑠←(do Get ()){𝑅1} in let•() ←close 𝑓in (do Print𝑠){𝑅2}

Note that the second let-binding does not need to be annotated as linear, because the linear resource 𝑓does not appear after it. The linear resource 𝑓also does not appear in the continuation of Print. Since 𝑅1, 𝑅2, and 𝑅should be equal in the row-based effect system of F◦

eff, omitting the full operation signatures for simplicity, we could write 𝑅= 𝑅1 = 𝑅2 = {Get : ◦, Print : •} in the ideal case. However, this is actually ill-typed because all operations in 𝑅1 should be control-flow linear, as the linear resource 𝑓is used in their continuations.

An intuitive way to relax this limitation of F◦

eff is to introduce a trivial subtyping relation on concrete effect row types. We say 𝑅1 is a subtype of 𝑅2, if all operation labels in 𝑅1 are also in 𝑅2 with the same signatures, and when 𝑅1 ends with a row variable, 𝑅2 must end with the same row variable. Then, in the verboseClose example, we can write 𝑅1 = {Get : ◦}, 𝑅2 = {Print : •}, and 𝑅= {Get : ◦, Print : •}, which are safe given that 𝑅1 and 𝑅2 are both subtypes of 𝑅.

We call the subtyping relation trivial because it does not allow subtyping between row variables; an open row 𝑅1 is a subtype of 𝑅2 only if 𝑅2 contains the same row variable as 𝑅1. For the above verboseClose example this works, but for other functions which make greater use of polymorphism, it can still seem overly-restrictive. For instance, consider the following function sandwichClose which takes two functions and a file handle, and makes a sandwich using them.

sandwichClose : (() →• () ! {𝑅1}, File, () →• () ! {𝑅2}) →• () ! {𝑅} sandwichClose = 𝜆•(𝑔, 𝑓,ℎ). let◦() ←𝑔() in let•() ←close 𝑓in ℎ()

Using our trivial-subtyping workaround, we require both 𝑅1 and 𝑅2 to be subtypes of 𝑅. The problem appears when we try to be polymorphic over 𝑅1 and 𝑅2. Because they are subtypes of the same row type 𝑅, their row variables must be the same, i.e., we can only write 𝑅1 = 𝑅2 = 𝜇in F◦

eff. To support non-trivial subtyping relations between row variables, we may again use qualified types, this time to express row subtyping constraints. In addition to qualified linear types, Q◦

eff also supports qualified effect types inspired by Rose [Morris and McKinna 2019]. In Q◦

eff, the function sandwichClose can be given the following type. Note that here we still choose to fix functions to be unlimited for readability.

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 8 -->

54:8 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

sandwichClose : ∀𝜇1 𝜇2 𝜇.(𝜇1 ⩽𝜇, 𝜇2 ⩽𝜇, File ⪯𝜇1)

⇒(() →• () ! {𝜇1}, File, () →• () ! {𝜇2}) →• () ! {𝜇} sandwichClose = 𝜆•(𝑔, 𝑓,ℎ). let () ←𝑔() in let () ←close 𝑓in ℎ() The constraints 𝜇1 ⩽𝜇and 𝜇2 ⩽𝜇express that rows 𝜇1 and 𝜇2 are contained in 𝜇, and the constraint File ⪯𝜇1 expresses that the value linearity of File is less than the control-flow linearity of 𝜇1, which essentially means that 𝜇1 is control-flow linear. As in Section 2.3, the type inference algorithm of Q◦

eff infers these row subtyping constraints without the need for any annotation. The qualified linear types and qualified effect types of Q◦

eff are decidable. We give a constraint solving algorithm which checks the satisfiability of both linearity constraints and row constraints in Section 5.6.

## 3 AN EXPLICIT HANDLER CALCULUS WITH LINEAR TYPES

In this section, we present the syntax, type-and-effect system, operational semantics and metatheory of F◦

eff, a System F-style fine-grain call-by-value calculus with linear types and effect handlers. F◦

eff is based on the core language of Links which adapts the subkinding-based linear type system of F◦[Mazurak et al. 2010] and a row-based effect system [Hillerström and Lindley 2016; Lindley and Cheney 2012]. The linear type system and effect system of F◦

eff are extended to track control-flow linearity, which addresses the soundness problem arising from the interference of linear resources and multi-shot continuations. We show that F◦

eff is truly linearity safe by defining a linearity-aware semantics and proving that no linear resource is discarded or duplicated during evaluation in the presence of multi-shot effect handlers.

### 3.1 Syntax and Kinding Rules

Figure 1 shows the syntax of types, kinds, contexts, values, and computations of F◦

eff. We introduce a syntactic category 𝑌for linearity consisting of • and ◦, which intuitively means unlimited and linear, respectively. The meaning of linearity varies for values and effects; value types track value linearity, and effect types track control-flow linearity. Everything relevant to linearity is highlighted in the figure. The remaining part is a relatively standard fine-grain call-by-value calculus with effect handlers and row-based effect system [Hillerström et al. 2020a].

F◦

eff explicitly distinguishes between value types and computation types as well as their terms. Value types include type variables 𝛼, function types 𝐴→𝑌𝐶, and polymorphic types ∀𝑌𝛼𝐾.𝐶. Value terms include value variables 𝑥, 𝜆-abstractions 𝜆𝑌𝑥𝐴.𝑀, and type abstractions Λ𝑌𝛼𝐾.𝑀. Function types, polymorphic types, and abstractions are annotated with their value linearity 𝑌. In examples we will freely make use of base types and algebraic data types whose treatment is quite standard. We elect to allow polymorphic computation types rather than applying the value restriction.

A computation type 𝐴! 𝐸comprises a result value type 𝐴and an effect type 𝐸specifying the operations that the computation might perform. Effect types {𝑅} are represented by row types 𝑅. Each operation label in rows is annotated with a presence type 𝑃, which indicates that the label is either absent Abs, present with signature 𝐴↠𝑌𝐵, or polymorphic 𝜃in its presence. An operation signature 𝐴↠𝑌𝐵describes an operation with parameter of type 𝐴that returns a result of type 𝐵 and whose control-flow linearity is 𝑌. Row types are either open (ending with a row variable 𝜇) or closed (ending with ·, which we often omit). We identify rows up to reordering of labels and ignore absent labels in closed row types [Rémy 1994]. Handler types 𝐶⇒𝐷represent handlers transforming computations of type 𝐶to computations of type 𝐷. By convention, we let 𝛼range over value type variables, 𝜇over row type variables, and 𝜃over presence type variables, but we also let 𝛼range over all over them (e.g. when binding quantifiers of unspecified kind).

Function application 𝑉𝑊and type application 𝑉𝑇are standard. A computation (return 𝑉)𝐸

returns the value 𝑉. An operation invocation (do ℓ𝑉)𝐸invokes the operation ℓwith parameter

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 9 -->

Soundly Handling Linearity 54:9

Value types 𝐴, 𝐵::= 𝛼| 𝐴→𝑌𝐶| ∀𝑌𝛼𝐾.𝐶 Computation types 𝐶, 𝐷::= 𝐴! 𝐸 Effect types 𝐸::= {𝑅} Row types 𝑅::= ℓ: 𝑃;𝑅| 𝜇| · Presence types 𝑃::= Abs | 𝐴↠𝑌𝐵| 𝜃 Handler types 𝐹::= 𝐶⇒𝐷 Types 𝑇::= 𝐴| 𝑅| 𝑃| 𝐶| 𝐸| 𝐹 Kinds 𝐾::= Type𝑌| RowL 𝑌| Presence𝑌| Effect | Comp | Handler Linearity 𝑌::= • | ◦ Label sets L ::= ∅| {ℓ} ⊎L Type contexts Γ ::= · | Γ,𝑥: 𝐴 Kind contexts Δ ::= · | Δ, 𝛼: 𝐾 Values 𝑉,𝑊::= 𝑥| 𝜆𝑌𝑥𝐴.𝑀| Λ𝑌𝛼𝐾.𝑀 Computations 𝑀, 𝑁::= 𝑉𝑊| 𝑉𝑇| (return 𝑉)𝐸| (do ℓ𝑉)𝐸

| let𝑌𝑥←𝑀in 𝑁| handle 𝑀with 𝐻 Handlers 𝐻::= {return 𝑥↦→𝑀} | {ℓ𝑝𝑟↦→𝑀} ⊎𝐻

Fig. 1. Syntax of Types, Kinds, Contexts, Values and Computations of F◦

eff

𝑉. They are both annotated with their effect types for deterministic typing. Sequencing let𝑌𝑥←

𝑀in 𝑁evaluates 𝑀and binds its result to 𝑥in 𝑁. The linearity 𝑌basically indicates the controlflow linearity of 𝑁. Handling handle 𝑀with 𝐻handles computation 𝑀with handler 𝐻. Handlers are given by a return clause return 𝑥↦→𝑀, which binds the returned value as 𝑥in 𝑀, and a list of operation clauses ℓ𝑝𝑟↦→𝑀, which bind the operation parameter to 𝑝and continuation to 𝑟in 𝑀.

We have six kinds 𝐾, one for each syntactic category of types. Kinds are parameterised by linearity 𝑌. The kinds of value types Type𝑌denote value linearity, and the kinds of presence types Presence𝑌and row types RowL𝑌denote control-flow linearity. The label set L tracks the labels that should not appear in a row, which is used to avoid duplicated labels in rows. The kinds of effect, computation, and handler types are not annotated with any linearity information. Type contexts Γ associate value variables with types, and kind contexts Δ associate type variables with kinds.

Figure 2 gives the kinding rules. Linearity-relevant parts are highlighted. The kinding relation Δ ⊢𝑇: 𝐾states that type 𝑇has kind 𝐾in context Δ. The subkinding relation ⊢𝐾≤𝐾′ states that 𝐾is a subkind of 𝐾′. We sometimes write simply Δ ⊢𝑇: 𝑌for value, row and presence types when the underlying kind is clear. The kinding rules for effect, computation, and handler types are standard [Hillerström et al. 2020a] and irrelevant to linearity (K-Effect, K-Comp, and K-Handler).

The kind context maintains kinds for variables (K-TyVar). The value linearity of function and polymorphic types comes from their annotations (K-Forall and K-Fun). Base types have their own value linearity, e.g., ⊢File : ◦and ⊢Int : •. The value linearity of (omitted) algebraic datatypes like pair types (𝐴, 𝐵) is lifted from their components; ⊢(𝐴, 𝐵) : ◦if either ⊢𝐴: ◦or ⊢𝐵: ◦.

As shown in Section 2.1, for value linearity, we have a subkinding relation ⊢Type• ≤Type◦given by subkinding rules S-Lin and S-Type. This allows us to use unlimited value types as linear value types since it is always safe to use unlimited values linearly (e.g., the function id in Section 2.1).

We track control-flow linearity at the granularity of operations, and lift it to the kinds of presence types and row types. Absent labels and empty rows can be given any control-flow linearity (K-Absent and K-EmptyRow). The control-flow linearity of present labels comes directly

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 10 -->

⊢𝑌≤𝑌′ ⊢𝐾≤𝐾′

S-Pres

S-Type

⊢𝑌≤𝑌′

S-Lin

⊢Type𝑌≤Type𝑌′

⊢• ≤◦

Δ ⊢𝑇: 𝐾

K-Forall

Δ, 𝛼: 𝐾⊢𝐶: Comp

K-TyVar

Δ ⊢∀𝑌𝛼𝐾.𝐶: Type𝑌

Δ, 𝛼: 𝐾⊢𝛼: 𝐾

K-Effect

K-Present

Δ ⊢𝑅: Row∅ Δ ⊢{𝑅} : Effect

Δ ⊢𝐴↠𝑌𝐵: Presence𝑌

K-ExtendRow

K-Handler

Δ ⊢𝑃: Presence𝑌

Δ ⊢𝑅: RowL⊎{ℓ} 𝑌

Δ ⊢ℓ: 𝑃;𝑅: RowL 𝑌

Fig. 2. Kinding and Subkinding Rules for F◦

### 3.2 Typing Rules

The T-Var rule requires the remaining context to be unlimited. The T-Abs and T-TAbs rules check the value linearity of functions and polymorphic computations against that of the context via

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

54:10 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

S-Row

⊢𝑌′ ≤𝑌

⊢𝑌′ ≤𝑌

⊢Presence𝑌≤Presence𝑌′

⊢RowL 𝑌≤RowL 𝑌′

K-Fun

K-Comp

Δ ⊢𝐴: Type𝑌′

Δ ⊢𝐴: Type𝑌

Δ ⊢𝐶: Comp

Δ ⊢𝐸: Effect

Δ ⊢𝐴→𝑌𝐶: Type𝑌

Δ ⊢𝐴! 𝐸: Comp

K-Absent

K-EmptyRow

Δ ⊢Abs : Presence𝑌

Δ ⊢· : RowL 𝑌

K-Upcast

Δ ⊢𝑇: 𝐾 ⊢𝐾≤𝐾′

Δ ⊢𝐶: Comp Δ ⊢𝐷: Comp

Δ ⊢𝑇: 𝐾′

Δ ⊢𝐶⇒𝐷: Handler

eff

from operation signatures (K-Present). The control-flow linearity of row extensions are given by the labels and remaining rows (K-ExtendRow).

As shown in Section 2.2, control-flow linearity is dual to value linearity in some sense: we have ⊢RowL◦≤RowL• and ⊢Presence◦≤Presence• given by subkinding rules S-Lin, S-Pres, and S-Row. This allows linear effect rows to be used as unlimited effect rows as it is always safe to use control-flow-linear operations in unlimited contexts (e.g., the function tossCoin in Section 2.2).

We define two auxiliary relations in Figure 3 for typing rules. The judgement Δ ⊢Γ : 𝑌states that under kind context Δ all types in Γ have linearity 𝑌. As the subkinding relation for value linearity holds that Type• ≤Type◦, the relation Δ ⊢Γ : • guarantees that all variables in Γ are unlimited and the relation Δ ⊢Γ : ◦is a tautology. Dually, as the subkinding relation for control-flow linearity holds that Row◦≤Row•, the relation Δ ⊢𝑅: ◦guarantees that all operations in 𝑅are control-flow linear and the relation Δ ⊢𝑅: • is a tautology. The context splitting judgement Δ ⊢Γ = Γ1 + Γ2 states that under kind context Δ the type context Γ is well formed and can be split into two contexts Γ1 and Γ2 such that each linear variable only appears in one of them. We write Δ ⊢Γ1 + Γ2 when we only care about splitting results, and write Γ1 + Γ2 in typing rules when the kind context Δ is clear.

The typing rules for values, computations, and handlers are given in Figure 4. Linearity-relevant parts are highlighted. The relations Δ; Γ ⊢𝑉: 𝐴, Δ; Γ ⊢𝑀: 𝐶, and Δ; Γ ⊢𝐻: 𝐶⇒𝐷, state respectively that: value 𝑉has type 𝐴, computation 𝑀has type 𝐶and handler 𝐻has type 𝐶⇒𝐷in contexts Δ and Γ. As usual, the type contexts and types are well formed under the kind contexts.


<!-- page 11 -->

Soundly Handling Linearity 54:11

Δ ⊢Γ : 𝑌

L-Empty

Δ ⊢· : 𝑌

Δ ⊢Γ = Γ1 + Γ2

C-Unl

C-Empty

Δ ⊢· = · + ·

C-LinLeft

Δ ⊢𝐴: Type◦ Δ ⊢Γ = Γ1 + Γ2 Δ ⊢Γ,𝑥: 𝐴= (Γ1,𝑥: 𝐴) + Γ2

Δ; Γ ⊢𝑉: 𝐴 Δ; Γ ⊢𝑀: 𝐶 Δ; Γ ⊢𝐻: 𝐶⇒𝐷

T-Abs

Δ ⊢Γ : 𝑌 Δ ⊢𝐴: Type𝑌′

T-Var

Δ; Γ,𝑥: 𝐴⊢𝑀: 𝐶

Δ ⊢Γ : •

Δ; Γ ⊢𝜆𝑌𝑥𝐴. 𝑀: 𝐴→𝑌𝐶

Δ; Γ,𝑥: 𝐴⊢𝑥: 𝐴

T-App

T-TApp

Δ; Γ1 ⊢𝑉: 𝐴→𝑌𝐶

Δ; Γ ⊢𝑉: ∀𝑌𝛼𝐾.𝐶

Δ; Γ2 ⊢𝑊: 𝐴

Δ ⊢𝑇: 𝐾

Δ; Γ1 + Γ2 ⊢𝑉𝑊: 𝐶

Δ; Γ ⊢𝑉𝑇: 𝐶[𝑇/𝛼]

T-Seq

T-Do

𝐸= {ℓ: 𝐴↠𝑌𝐵;𝑅} Δ; Γ ⊢𝑉: 𝐴 Δ ⊢𝐸: Effect

Δ; Γ ⊢(do ℓ𝑉)𝐸: 𝐵! 𝐸

T-Handler

T-Handle

Δ; Γ1 ⊢𝐻: 𝐶⇒𝐷 Δ; Γ2 ⊢𝑀: 𝐶

Δ; Γ1 + Γ2 ⊢handle 𝑀with 𝐻: 𝐷

Fig. 4. Typing Rules for F◦

the premise Δ ⊢Γ : 𝑌. The typing rules for function application and type application are standard (T-App and T-TApp). Note that we need to split the context in the T-App rule to avoid duplicating linear variables. The T-Return rule does not constrain the effects. The T-Do rule ensures that

L-Extend

Δ ⊢Γ : 𝑌 Δ ⊢𝐴: Type𝑌

Δ ⊢(Γ,𝑥: 𝐴) : 𝑌

Δ ⊢𝐴: Type• Δ ⊢Γ = Γ1 + Γ2 Δ ⊢Γ,𝑥: 𝐴= (Γ1,𝑥: 𝐴) + (Γ2,𝑥: 𝐴)

C-LinRight

Δ ⊢𝐴: Type◦ Δ ⊢Γ = Γ1 + Γ2 Δ ⊢Γ,𝑥: 𝐴= Γ1 + (Γ2,𝑥: 𝐴)

Fig. 3. Linearity of Contexts and Context Splitting

T-TAbs

Δ ⊢Γ : 𝑌 𝛼∉ftv(Γ) Δ, 𝛼: 𝐾; Γ ⊢𝑀: 𝐶

Δ; Γ ⊢Λ𝑌𝛼𝐾. 𝑀: ∀𝑌𝛼𝐾.𝐶

T-Return

Δ; Γ ⊢𝑉: 𝐴 Δ ⊢𝐸: Effect

Δ; Γ ⊢(return 𝑉)𝐸: 𝐴! 𝐸

Δ; Γ1 ⊢𝑀: 𝐴! {𝑅} Δ; Γ2,𝑥: 𝐴⊢𝑁: 𝐵! {𝑅} Δ ⊢Γ2 : 𝑌 Δ ⊢𝑅: 𝑌

Δ; Γ1 + Γ2 ⊢let𝑌𝑥←𝑀in 𝑁: 𝐵! {𝑅}

𝐻= {return 𝑥↦→𝑀} ⊎{ℓ𝑖𝑝𝑖𝑟𝑖↦→𝑁𝑖}𝑖 𝐶= 𝐴! {(ℓ𝑖: 𝐴𝑖↠𝑌𝑖𝐵𝑖)𝑖;𝑅} 𝐷= 𝐵! {(ℓ𝑖: 𝑃)𝑖;𝑅} Δ ⊢Γ : • Δ; Γ,𝑥: 𝐴⊢𝑀: 𝐷 [Δ; Γ, 𝑝𝑖: 𝐴𝑖,𝑟𝑖: 𝐵𝑖→𝑌𝑖𝐷⊢𝑁𝑖: 𝐷]𝑖

Δ; Γ ⊢𝐻: 𝐶⇒𝐷

eff

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 12 -->

Δ ⊢𝑅⩽𝑅′ : 𝐾

Δ ⊢𝑅: 𝐾

Δ ⊢𝑅⩽𝑅: 𝐾

Δ ⊢𝑃: Presence𝑌

Δ ⊢𝑅1 ⩽𝑅2 : RowL⊎{ℓ}𝑌

Δ ⊢ℓ: Abs;𝑅1 ⩽ℓ: 𝑃;𝑅2 : RowL𝑌

Remember that for let𝑌𝑥←𝑀in 𝑁, the linearity annotation 𝑌indicates the control-flow linearity of 𝑁which determines how many times the control can enter 𝑁. Concretely, when 𝑌= ◦, 𝑁may use some linear variables bound outside (Δ ⊢Γ2 : ◦), and all operations in 𝑀 should be control-flow linear (Γ ⊢𝑅: ◦); when 𝑌= •, 𝑁cannot use any linear variables from the context (Δ ⊢Γ2 : •), and operations in 𝑀have no restriction on their control-flow linearity (Δ ⊢𝑅: •). The dubiousWrite✓in Section 2.2 is an example. Note that technically, the third sequencing let◦𝑓′ ←write (𝑠, 𝑓) in close 𝑓′ can be changed to let• because no linear variable bound outside is used by the context let 𝑓′ ←_ in close 𝑓′.

T-SeqSub

Δ; Γ1 ⊢𝑀: 𝐴! {𝑅1} Δ; Γ2,𝑥: 𝐴⊢𝑁: 𝐵! {𝑅2} Δ ⊢Γ2 : 𝑌 Δ ⊢𝑅1 : 𝑌 Δ ⊢𝑅1 ⩽𝑅: 𝐾 Δ ⊢𝑅2 ⩽𝑅: 𝐾

Δ; Γ1 + Γ2 ⊢let𝑌𝑥←𝑀in 𝑁: 𝐵! {𝑅}

The trivial subtyping relation on effect row types are shown in Figure 5. The judgement Δ ⊢𝑅⩽ 𝑅′ : 𝐾makes it explicit that 𝑅and 𝑅′ are well kinded and can be given kind 𝐾under kind context Δ. It simply requires that all operation labels with their signatures and row variable in 𝑅must also

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

54:12 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

Δ ⊢𝑅1 ⩽𝑅2 : 𝐾 Δ ⊢𝑅2 ⩽𝑅3 : 𝐾

Δ ⊢𝜇: 𝐾

Δ ⊢𝑅1 ⩽𝑅3 : 𝐾

Δ ⊢· ⩽𝜇: 𝐾

Δ ⊢𝑃: Presence𝑌

Δ ⊢𝑅1 ⩽𝑅2 : RowL⊎{ℓ}𝑌

Δ ⊢ℓ: 𝑃;𝑅1 ⩽ℓ: 𝑃;𝑅2 : RowL𝑌

Fig. 5. Trivial Subtyping for Effect Row Types

the operation ℓand its parameter 𝑉agree with the effect signature 𝐸. The T-Handle rule uses a handler of type 𝐶⇒𝐷to handle a computation of type 𝐶.

The T-Handler rule checks that (deep) handlers must not use any linear variables via the premise Δ ⊢Γ : • because they are recursively applied during evaluation. More importantly, it connects the control-flow linearity of operations with the value linearity of resumption functions. In the typing judgement of each operation clause ℓ𝑖: 𝐴𝑖↠𝑌𝑖𝐵𝑖, the continuation 𝑟𝑖is given the value linearity 𝑌𝑖, which is exactly the control-flow linearity of ℓ𝑖that restricts the use of ℓ𝑖's continuation. Concretely, when 𝑌𝑖= ◦, the continuation of ℓ𝑖may use some linear resources. Making 𝑟𝑖linear guarantees that they are used exactly once. When 𝑌𝑖= •, the continuation of ℓ𝑖must not use any linear resources and 𝑟𝑖is unlimited. Note that the subkinding relation Row◦≤Row• does not influence the handling behaviour, because the T-Handler rule uses the linearity annotations on operation signatures.

The T-Seq rule for sequencing is the most important rule for tracking control-flow linearity, because this is the primary source of sequential control flow in a fine-grain call-by-value calculus. Though handling is another source of sequential control flow, deep handlers are unlimited and cannot influence control-flow linearity. We will discuss the extension of shallow handlers which may capture linear resources and influence control-flow linearity in Section 6.

As we observed by the function verboseClose in Section 2.4, the fact that the T-Seq rule requires the 𝑀and 𝑁to have the same effect type is too restrictive for tracking control-flow linearity. We can improve it by using a trivial subtyping relation between effect types as follows.


<!-- page 13 -->

Soundly Handling Linearity 54:13

### 3.3 Operational Semantics

E-App (𝜆𝑌𝑥𝐴.𝑀) 𝑉{ 𝑀[𝑉/𝑥] E-TApp (Λ𝑌𝛼𝐾.𝑀)𝑇{ 𝑀[𝑇/𝛼] E-Seq let𝑌𝑥←(return 𝑉)𝐸in 𝑁{ 𝑁[𝑉/𝑥] E-Ret handle (return 𝑉)𝐸with 𝐻{ 𝑁[𝑉/𝑥], where (return 𝑥↦→𝑁) ∈𝐻 E-Op handle E[(do ℓ𝑉)𝐸] with 𝐻{ 𝑁[𝑉/𝑝, (𝜆𝑌𝑦𝐵.handle E[(return 𝑦)𝐸] with 𝐻)/𝑟],

where ℓ∉bl(E), (ℓ𝑝𝑟↦→𝑁) ∈𝐻, and (ℓ: 𝐴→𝑌𝐵) ∈𝐸 E-Lift E[𝑀] { E[𝑁], if 𝑀{ 𝑁

Evaluation contexts E ::= [ ] | let𝑌𝑥←E in 𝑁| handle E with 𝐻

bl([ ]) = ∅ bl(let𝑌𝑥←E in 𝑁) = bl(E) bl(handle E with 𝐻) = bl(E) ∪dom(𝐻)

Fig. 6. Small-step Operational Semantics of F◦

Figure 6 gives a standard small-step operational semantics for F◦

### 3.4 Metatheory

We now prove a type soundness result for F◦

Definition 3.1 (Computation Normal Forms). We say a computation 𝑀is in a normal form with respect to 𝐸, if it is either of the form 𝑀= (return 𝑉)𝐸′ or 𝑀= E[(do ℓ𝑉)𝐸′] for ℓ∈𝐸and ℓ∉bl(E).

Syntactic type soundness of F◦

Theorem 3.3 (Subject reduction). If Δ; Γ ⊢𝑀: 𝐶and 𝑀{ 𝑁, then Δ; Γ ⊢𝑁: 𝐶.

We now show that our tracking of value linearity and control-flow linearity in the type system is sound, by proving that linear variables never appear in terms that are claimed to be unlimited. In F◦

Theorem 3.4 (Unlimited is unlimited).

appear in 𝑅′. This subtyping relation does not allow non-trivial subtyping between row variables. We consider a more expressive alternative using qualified types in Section 5.

eff

eff [Hillerström et al. 2020a]. It is clear from the definition of evaluation contexts that let-binding and handling are indeed the only two constructs that influence the control flow. The function bl(−) computes the set of bound operation labels in an evaluation context E, i.e. the operation labels for which a suitable handler has been installed. The purpose of this function is to ensure that any operation invocation (do ℓ𝑉) is always handled by the innermost suitable handler.

eff. First we define normal forms of computations.

eff relies on progress and subject reduction. The proofs can be found in Appendices A.2 and A.3.

Theorem 3.2 (Progress). If ⊢𝑀: 𝐴! 𝐸, then either there exists 𝑁such that 𝑀{ 𝑁or 𝑀is in a normal form with respect to 𝐸.

eff, a term is claimed to be unlimited if it appears in an unlimited value, a control-flow-unlimited context, or a deep handler. The following theorem covers all three of these cases.

1. Unlimited values are unlimited: if Δ; Γ ⊢𝑉: 𝐴and Δ ⊢𝐴: •, then Δ ⊢Γ : •. 2. Unlimited continuations are unlimited: if Δ; Γ ⊢E[(do ℓ𝑉)𝐸] : 𝐶for 𝐸= {ℓ: 𝐴↠• 𝐵;𝑅} and ℓ∉bl(E), then there exists Δ ⊢Γ = Γ1 + Γ2 such that Δ ⊢Γ1 : • and Δ; Γ1,𝑦: 𝐵⊢ E[(return 𝑦)𝐸] : 𝐶.

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 14 -->

L-App (𝜆𝑌𝑥𝐴.𝑀) 𝑉 S

L-TApp (Λ𝑌𝛼𝐾.𝑀)𝑇 ∅ ∅{ 𝑀[𝑇/𝛼]

L-Seq let𝑌𝑥←return 𝑉in 𝑁 S

L-Ret handle (return 𝑉)𝐸with 𝐻 S

L-Op handle E[(do ℓ𝑉)𝐸] with 𝐻 S

L-Remove F [𝑉◦] ∅ {𝑉◦}{ F [𝑉]

L-Lift E[𝑀] S T{ E[𝑁], if 𝑀 S T{ 𝑁

Evaluation contexts E ::= [ ] | let𝑌𝑥←E in 𝑁| handle E with 𝐻 Tag-removing contexts F ::= [ ] 𝑉| [ ] 𝑇

Fig. 7. Linearity-aware Small-step Operational Semantics of F◦

3. Deep handlers are unlimited: if Δ; Γ ⊢𝐻: 𝐶⇒𝐷, then Δ ⊢Γ : •.

The proof can be found in Appendix A.1. However, Theorem 3.4 only cares about the static tracking of linear variables. It says nothing about the use of linear values during evaluation directly. In the next section, we prove that in F◦

eff no linear value is ever discarded or duplicated during evaluation, by defining a linearity-aware semantics inspired by Walker [2005], Mazurak et al. [2010], and Morris [2016].

### 3.5 Linearity Safety of Evaluation

In this section, we design a linearity-aware semantics of F◦

We first extend the syntax of values with values marked with linear tags 𝑉◦to indicate linear values during evaluation. The typing rules simply ignore the linear tags.

Values 𝑉::= · · · | 𝑉◦

We restrict attention to closed computations and define two auxiliary functions lin(𝑉) and tag(𝑉) for closed values as follows.

true if ·; · ⊢𝑉: 𝐴and · ⊬𝐴: • false otherwise

lin(𝑉) =

tag(𝑉) =

The predicate lin(𝑉) holds when 𝑉is a genuine linear value as opposed to an unlimited value that has been upcast to be linear by subkinding. The operation tag(𝑉) tags a value as linear if it is and has not been tagged, and yields a pair of the possibly tagged 𝑉and a multiset containing the value if it is newly tagged and nothing otherwise.

The linearity-aware semantics is given in Figure 7. We augment the previous reduction relation 𝑀{ 𝑁with two multi-sets 𝑀 S T{ 𝑁, where S contains the linear values introduced by this

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

54:14 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

∅{ 𝑀[𝑉′/𝑥], where (𝑉′, S) = tag(𝑉)

∅{ 𝑁[𝑉′/𝑥], where (𝑉′, S) = tag(𝑉)

∅{ 𝑁[𝑉′/𝑥], where (return 𝑥↦→𝑁) ∈𝐻, (𝑉′, S) = tag(𝑉)

∅{ 𝑁[𝑉′/𝑝,𝑊′/𝑟], where ℓ∉bl(E), (ℓ𝑝𝑟↦→𝑁) ∈𝐻, (ℓ: 𝐴↠𝑌𝐵) ∈𝐸,

𝑊= 𝜆𝑌𝑦𝐵.handle E[(return 𝑦)𝐸] with 𝐻, (𝑉′, S1) = tag(𝑉), (𝑊′, S2) = tag(𝑊), S = S1 ∪S2

eff

eff, extending the small-step operational semantics to track the introduction and elimination of linear values, and prove that all linear values are used exactly once during evaluation.

(𝑉◦, {𝑉◦}) if lin(𝑉) and 𝑉≠𝑊◦for any 𝑊 (𝑉, ∅) otherwise


<!-- page 15 -->

Soundly Handling Linearity 54:15

reduction step, and T contains the linear values eliminated by this reduction step. Note that in F◦

eff, we cannot duplicate or discard a value before we bind it. We introduce linear values at the first time they are bound to variables (L-App, L-Seq, L-Ret and L-Op). Take L-App for example. When 𝑉is a non-tagged real linear value (the first case of tag(𝑉)), we tag it and add it to the multiset

of introduced linear values. Otherwise, 𝑉is either not really linear or has been tagged already (which implies that we have already introduced it). We do not need to update the multisets. We eliminate linear values when they are destructed (L-Remove). As we only have term abstraction and type abstraction as value constructors, the tag-removing contexts F capture the elimination of these two cases. It is easy to extend the linearity-aware semantics with other value constructors. The relationship between the two semantics is straightforward: erasing the linear tags from the linearity-aware semantics yields the original semantics.

We write ℒ(𝑀), ℒ(𝑉), ℒ(E) and ℒ(F ) for the multisets of tagged linear values within 𝑀, 𝑉, E, and F , respectively. They are given by the homomorphic extension of the following equation.

ℒ(𝑉◦) = {𝑉◦} ∪ℒ(𝑉)

We define the notion of linear safety similarly to Theorem 3.4. A term is linear safe if there are no tagged linear values in terms that are claimed to be unlimited.

Definition 3.5 (Linear Safety). A well-typed computation 𝑀or value 𝑉is linear safe if and only if: (1) For every value subterm 𝑊of the form 𝜆•𝑥𝐴.𝑁or Λ•𝛼𝐾.𝑁, ℒ(𝑊) = ∅. (2) For every computation subterm 𝑁of the form E[(do ℓ𝑉){ℓ:𝐴↠•𝐵;𝑅}] where ℓ∉bl(E),

ℒ(E) = ∅. (3) For every handler subterm 𝐻, ℒ(𝐻) = ∅. (An alternative way to read Item 1 is as "for every value subterm 𝑊with an unlimited type".)

Finally, the following theorem states that linear safety is preserved by evaluation, and tagged linear values are not duplicated or discarded during evaluation.

Theorem 3.6 (Reduction Safety). For any closed, well-typed and linear safe computation 𝑀in F◦

eff, if 𝑀 S T{ 𝑁, then 𝑁is linear safe and ℒ(𝑀) ∪S = ℒ(𝑁) ∪T.

The proof can be found in Appendix A.4. Note that tracking linear values explicitly during evaluation is important for showing that they are indeed used safely. Otherwise, it is even unclear how to state what reduction safety means in the original semantics.

## 4 CONTROL-FLOW LINEARITY IN LINKS

In this section, we describe our implementation of control-flow linearity tracking in Links. The implementation fixes a long-standing type soundness bug in Links arising from the interaction between session types and effect handlers, as we described in the introduction.

Links is an ML-style language with type inference, linearly typed session types (based on F◦[Lindley and Morris 2017]), and a row-based effect type system [Hillerström and Lindley 2016]. In Links we write Unl for • and Any for ◦. The latter is Any as any value can be soundly used once. The subkinding relation ⊢Type• ≤Type◦(Unl ≤Any) allows type variables of kind Any to be unified with types of either kind. This allows us to write functions that may accept both linear and nonlinear values, e.g. the identity function fun id(x){x} : (a::Any) -> (a::Any). Here, we can instantiate the type variable a to a linear type, such as !Int.End, or an unlimited type, such as Int.

To make type inference deterministic, Links makes use of two different keywords for defining unlimited functions and linear functions, which are fun and linfun respectively. For instance, we can define a channel version of the function faithfulWrite in Section 2.1 as follows.

## fun faithfulSend(c) { linfun (s) { var c = send(s, c); close(c) } }

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 16 -->

54:16 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

The inferred type is (!(a::Any).End) -> (a::Any) ~@ (). The faithfulSend function takes a polymorphic channel c and returns a linear function (indicated by ~@ instead of the usual arrow ~>) that sends a polymorphic value 𝑠over the channel c. If we wanted to we could restrict the inferred type of the channel c and the input 𝑠by supplying a type annotation to either.

To track control-flow linearity we repurpose the existing effect system and add two new control flow kinds Any (for •) and Lin (for ◦) to signify whether a given context allows control flow to be unlimited or linear. We further add a new effectful operation space for control-flow-linear operations, which is syntactically denoted by the arrow =@, in addition to the existing operation space denoted by =>. The subkinding relation ⊢Row◦≤Row• (Lin ≤Any) is implemented by allowing row variables of kind Any to be unified with both control-flow-linear and unlimited operations and other row variables of arbitrary kinds. In contrast, row variables of kind Lin can only be unified with control-flow-linear operations and row variables of kind Lin. The change from

Unl to Lin is consistent with the duality between value linearity and control-flow linearity.

Since Links is a practical programming language, sequencing is often implicit. Instead of writing linearity annotations on all sequencing, we assume that control-flow linearity is unlimited by default, and introduce the keyword xlin to switch the control-flow linearity to linear. We also add the construct lindo to invoke control-flow-linear operations in addition to the existing do for control-flow-unlimited operations. To illustrate the use of these extensions, let us consider a channel version of the function dubiousWrite✓from Section 2.2.

## sig dubiousSend : (!String.End) {Choose:() =@ Bool|_::Lin}~> ()

fun dubiousSend(c) {xlin; var c = send(if (lindo Choose) "A" else "B", c); close(c)}

The dubiousSend takes a channel c, non-deterministically sends "A" or "B" through it depending on the result of the operation Choose, and closes the remaining channel. We use xlin to switch the control-flow linearity to linear so that we can use the linear channel c and must use the controlflow-linear operation Choose:() =@ Bool with the keyword lindo. If we replace lindo with do then Links correctly rejects the code as the continuation captures the linear endpoint c. The example from the introduction will be rejected for the same reason. For linear effect handlers, we use the linear arrow syntax =@ to bind linear continuations of control-flow-linear operations.

## fun(c) {handle ({xlin; dubiousSend(c)}) {case <Choose =@ r> -> xlin; r(true)} }

Here, we interpret the operation Choose as true. The use of xlin in the Choose-clause is necessary because the reified continuation 𝑟is linear. As the continuation is used linearly, Links correctly accepts this program.

Our implementation works well with previous programs using the effect handler feature in Links and fixes the type soundness bug. However, being based on F◦, Links suffers from the limitations outlined in Section 2. In the next section, we present a considerably more expressive calculus, Q◦

eff, which uses qualified types for both linearity and effects, enabling a much more fine-grained analysis of control-flow linearity, and avoiding the need to distinguish between linear and non-linear variants of term syntax. We leave the implementation of Q◦

eff to future work.

## 5 AN IMPLICIT CALCULUS WITH QUALIFIED TYPES

In this section, we propose Q◦

eff, an ML-style calculus which enhances F◦

eff (and its implementation in Links) in two directions: minimising syntactic overheads and improving accuracy of control-flow linearity tracking. The core idea is to use qualified types for both linear types and effect types. The qualified linear type system is inspired by Quill [Morris 2016], which eliminates the linearity annotations on terms and supports principal types. The qualified effect system is inspired by the row containment predicate of Rose [Morris and McKinna 2019] and the subtyping-based effect

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 17 -->

Soundly Handling Linearity 54:17

### 5.1 Syntax

Figure 8 shows the syntax of qualified types of Q◦

eff, except that we introduce generalising let-bindings let 𝑥= 𝑉in 𝑀to replace explicit type abstraction and implicit instantiation in place of type application and remove all type annotations and linearity annotations.

Linearity 𝑌::= 𝜙| • | ◦ Types 𝜏::= 𝐴| 𝑅| 𝑌 Predicates Pred ∋𝜋::= 𝜏1 ⪯𝜏2 | 𝑅1 ⩽𝑅2

| 𝑅⊥L

Fig. 8. Syntax of Qualified Types of Q◦

Linearity. In addition to concrete linearities ◦and •, Q◦

### 5.2 Typing

Figure 9 gives representative syntax-directed typing rules for Q◦

eff; the remaining rules are given in full in Appendix B.2. The judgement 𝑃| Γ ⊢𝑀: 𝐶states that, under predicate assumptions 𝑃 and typing assumptions Γ, the term 𝑀has type 𝐶, and similarly for the judgements for values and handlers. As usual for qualified type systems, the typing rules depend on an entailment relation 𝑃⊢𝜋(and an auxiliary relation 𝑃⊢Γ ⪯𝜏), discussed in the following section.

Rule Q-Let demonstrates the treatment of linearity in Q◦

eff. We divide the context in three: Γ1 is used exclusive in the bound term 𝑉, Γ2 is used exclusively in the body 𝑀, and Γ is used in both (and so its types must be unlimited).

Rule Q-Do demonstrates the use of constraints in Q◦

system of Eff [Karachalias et al. 2020; Pretnar 2014], which allows non-trivial subtyping constraints between row variables.

eff. We name some syntactic categories for defining meta functions. The remaining syntax is given in full in Appendix B.1, which is mostly identical to that of F◦

Qualified types 𝜌::= 𝐴| 𝜋⇒𝜌 Type schemes TySch ∋𝜎::= 𝜌| ∀𝛼.𝜎 Type contexts Env ∋Γ ::= · | Γ,𝑥: 𝜎 Predicate sets PSet ∋𝑃::= · | 𝑃, 𝜋

eff

eff has linearity variables 𝜙. This is essential to have principal types and more expressive constraints. For example, the identity function 𝜆𝑥.return 𝑥can be given the principal type ∀𝛼𝜇𝜙. 𝛼→𝜙𝛼! {𝜇}, which can be instantiated to either a linear function (by instantiating 𝜙to ◦) or an unlimited function (by instantiating 𝜙to •).

Qualified types. The syntactic category 𝜏includes value types, row types, and linearity types. Qualified types 𝜌restrict value types by predicates. The linearity predicate 𝜏1 ⪯𝜏2 means the linearity of 𝜏1 is less than 𝜏2 (e.g., • ⪯◦). Note that we allow directly using value types and row types in the linearity predicates, since every value type has its value linearity, and every effect row type has its control-flow linearity. The row predicates 𝑅1 ⩽𝑅2 means 𝑅1 is a sub-row of 𝑅2, and 𝑅⊥L means 𝑅does not contain labels in L.

Kinding. For conciseness we omit kinds and infer the kind of a type variable from its name. As usual, we let 𝛼range over value types, 𝜇range over row types, and 𝜙range over linearity types. We also let 𝛼range over all of them in the definition of type schemes ∀𝛼.𝜎. All rows are assumed to be well-formed (no duplicated labels). To simplify type inference, the predicate 𝜇⊥L will be used in place of kinds RowL to track labels that may not occur in rows. This is just a convenience, though, as the corresponding kinds of row type variables can be computed from the inferred types.

eff to generalise subtyping between effect rows. It states that if 𝑉is a value of type 𝐴ℓ, then do ℓ𝑉has result type 𝐵ℓand effect row 𝑅. We assume that the parameter and result types of operations are given by an implicit global context

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 18 -->

𝑃| Γ ⊢𝑉: 𝐴 𝑃| Γ ⊢𝑀: 𝐶 𝑃| Γ ⊢𝐻: 𝐶⇒𝐷

Q-Let

𝑄| Γ1, Γ ⊢𝑉: 𝐴 𝜎= gen((Γ1, Γ),𝑄⇒𝐴) 𝑃| Γ2, Γ,𝑥: 𝜎⊢𝑀: 𝐶 𝑃⊢Γ ⪯•

𝑃| Γ1, Γ2, Γ ⊢let 𝑥= 𝑉in 𝑀: 𝐶

Q-Seq

𝑃| Γ1, Γ ⊢𝑀: 𝐴! {𝑅1} 𝑃| Γ2, Γ,𝑥: 𝐴⊢𝑁: 𝐵! {𝑅2}

𝑃⊢𝑅1 ⩽𝑅 𝑃⊢𝑅2 ⩽𝑅 𝑃⊢Γ2 ⪯𝑅1 𝑃⊢Γ ⪯•

𝑃| Γ1, Γ2, Γ ⊢let 𝑥←𝑀in 𝑁: 𝐵! {𝑅}

where gen(Γ, 𝜌) = ∀(ftv(𝜌)\ftv(Γ)).𝜌.

Fig. 9. Selected Syntax-directed Typing Rules for Q◦

Π = {ℓ1 : 𝐴ℓ1 ↠𝐵ℓ1, · · · }. 𝑅must license effect ℓ. We again rely on entailment: the constraints 𝑃 must be sufficient to show that the singleton row {ℓ: 𝐴ℓ↠𝑌𝐵ℓ} is contained within 𝑅.

Rule Q-Seq demonstrates the remaining novelty of qualified types in Q◦

These two entailment relations are both defined as the conjunction of sub-relations as indicated by P-PredSet and P-Context. For 𝑃⊢𝑄, we only need to use entailment relations of the form 𝑃⊢𝜋. The P-Subsume is standard. The linearity predicate ⪯is reflexive (P-Refl), with ◦as top (P-Lin) and • as bottom (P-Unl) elements. The two-way rules P-Fun and P-Row define the linearity of functions and rows. We make use of the fact that in the linearity predicates generated by typing rules, functions only appear on the left, and rows only appear on the right. Here we do not include

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

54:18 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

Q-Do

𝑃| Γ ⊢𝑉: 𝐴ℓ 𝑃⊢{ℓ: 𝐴ℓ↠𝑌𝐵ℓ} ⩽𝑅

𝑃| Γ ⊢do ℓ𝑉: 𝐵ℓ! {𝑅}

Q-Handler

𝐻= {return 𝑥↦→𝑀} ⊎{ℓ𝑖𝑝𝑖𝑟𝑖↦→𝑁𝑖}𝑖 𝐶= 𝐴! {(ℓ𝑖: 𝐴𝑖↠𝑌𝑖𝐵𝑖)𝑖;𝑅1} 𝐷= 𝐵! {𝑅2} 𝑃| Γ,𝑥: 𝐴⊢𝑀: 𝐷 [𝑃| Γ, 𝑝𝑖: 𝐴𝑖,𝑟𝑖: 𝐵𝑖→𝑌𝑖𝐷⊢𝑁𝑖: 𝐷]𝑖 𝑃⊢Γ ⪯• 𝑃⊢𝑅1 ⩽𝑅2 𝑃⊢𝑅1 ⊥{ℓ𝑖}𝑖 𝑃| Γ ⊢𝐻: 𝐶⇒𝐷

eff

eff. Several of its uses of entailment follow the previous patterns. The bindings in Γ are available in both 𝑀and 𝑁, so 𝑃⊢Γ ⪯• requires that their types be unlimited. We want flexibility in combining the effects in 𝑀and 𝑁, so the conditions 𝑃⊢𝑅𝑖⩽𝑅assure that the effects of each are included in the effects of the entire computation. This allows us to avoid having to unify row types in examples like sandwichClose (Section 2.4) which causes inaccuracy for tracking control-flow linearity. Finally, 𝑁is in the continuation of all operations in 𝑀, so the value linearity of types in Γ2 must be less than the control-flow linearity of operations in 𝑅1. Note that the two kinding judgements in T-Seq in Figure 4 are now combined into one entailment judgement 𝑃⊢Γ2 ⪯𝑅1. The duality we have identified between value linearity and control-flow linearity is reflected by the fact that value types appear on the left of ⪯and effect row types appear on the right.

Rule Q-Handler uses the lacking predicate 𝑃⊢𝑅1 ⊥{ℓ𝑖}𝑖to ensure that the handled operations are not in the remaining part of the input effect row 𝑅1, and requires 𝑅1 to be a sub-row of the output effect row 𝑅2. This is used to allow the handled operations ℓ𝑖to appear in 𝑅2.

### 5.3 Entailment

Figure 10 defines the entailment relations between predicates 𝑃⊢𝑄. It also defines an auxiliary entailment relation 𝑃⊢Γ ⪯𝜏which compares the linearity of all variables in Γ and 𝜏. The algorithmic version of these relations will be given in Section 5.5.


<!-- page 19 -->

Soundly Handling Linearity 54:19

𝑃⊢𝜋 𝑃⊢𝑄 𝑃⊢𝜎⪯𝜏 𝑃⊢Γ ⪯𝜏

P-Subsume

P-Refl

P-Lin

𝜋∈𝑃

𝑃⊢𝜋

𝑃⊢𝜏⪯𝜏

𝑃⊢𝜏⪯◦

P-Row

[𝑃⊢𝜏⪯𝑌] (𝑙:𝐴↠𝑌𝐵)∈𝑅 𝑃⊢𝜏⪯𝜇when 𝜇∈𝑅

P-Sub

set(𝑅1) ⊆set(𝑅2)

𝑃⊢𝜏⪯𝑅 ==================================

𝑃⊢𝑅1 ⩽𝑅2

P-Quantifier

𝑃⊢[𝜏′/𝛼]𝜎⪯𝜏for some 𝜏′

𝑃⊢(∀𝛼.𝜎) ⪯𝜏

entailment rules for base types, but in practice we would have axioms like 𝑃⊢Int ⪯• and 𝑃⊢◦⪯File. For row predicates, we write set(𝑅) for the set of all elements (comprising operation labels with their signatures and row variables) of 𝑅, and dom(𝑅) for the set of all labels of 𝑅. We define the row predicates directly by set operations (P-Sub and P-Lack).

The rule P-Qualifier may also be surprising. To compare the linearity of a qualified type 𝜋⇒𝜌 with 𝜏, we require the predicate 𝜋to hold and then compare the linearity of the remaining part 𝜌 with 𝜏. At first glance, the condition 𝑃⊢𝜋may seem unnecessary: if 𝜋must hold in instantiations of this type, surely we can assume it in checking the type's linearity. However, particularly in local definitions, predicates may mention type variables not quantified in those schemes. We do not want to assume anything about the instantiation of those variables. Consider the following function.

𝜆𝑥.let 𝑓= 𝜆().𝑥in return (𝑓, 𝑓)

P-Fun

𝑃⊢𝑌⪯𝜏

P-Unl

𝑃⊢(𝐴→𝑌𝐶) ⪯𝜏 ============================

𝑃⊢• ⪯𝜏

P-Lack

P-PredSet

dom(𝑅) ∩L = ∅

[𝑃⊢𝜋]𝜋∈𝑄

𝑃⊢𝑅⊥L

𝑃⊢𝑄

P-Qualifier

P-Context

𝑃⊢𝜋 𝑃⊢𝜌⪯𝜏

[𝑃⊢𝜎⪯𝜏] (𝑥:𝜎)∈Γ

𝑃⊢(𝜋⇒𝜌) ⪯𝜏

𝑃⊢Γ ⪯𝜏

Fig. 10. Entailment Relations for Predicates and other Judgement Relations

The entailment relation 𝑃⊢Γ ⪯𝜏is defined using 𝑃⊢𝜎⪯𝜏which compares the linearity of a type scheme 𝜎and a type 𝜏. Our treatment of the linearity of type schemes is novel, and addresses a soundness bug in Quill. The rule P-Quantifier which characterises the linearity of polymorphic types may be surprising. It states that the linearity of a polymorphic type ∀𝛼.𝜎is less than 𝜏if there exists an instantiation of it whose linearity is less than 𝜏. This is because the linearity of a polymorphic type should capture the linearity of values that inhabit that type. A value of a polymorphic type can be understood as the intersection of values of all possible instantiations of the type. If one of these instantiation gives a type that is less linear than 𝜏, then the value itself must be less linear than 𝜏no matter what other instantiations are. For example, consider the identity function id = 𝜆𝑥.return 𝑥which is obviously unlimited. We give id a polymorphic type ∀𝜙𝛼𝜇. 𝛼→𝜙𝛼! {𝜇} to make it possible to use it as both a linear function (by instantiating 𝜙to ◦) and an unlimited function (by instantiating 𝜙to •). Thus, we have expressive principal types for id without adding subtyping between linearity types to the type system.

The polymorphic function 𝑓can be given the principal type 𝜎= ∀𝜙𝜇.(𝛼⪯𝜙) ⇒() →𝜙𝛼! {𝜇} where 𝛼is the type of 𝑥. Note that the constraint mentions 𝛼, which is bound outside this type scheme. Then, since 𝑓is duplicated in return (𝑓, 𝑓), the typing of it collects the constraint 𝜎⪯•. Obviously, we want to know from 𝜎⪯• that 𝛼should be unlimited since 𝑥is also duplicated. One

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 20 -->

possible derivation of 𝑃⊢𝜎⪯• is shown as follows.

𝑃⊢𝜙′ ⪯•

𝑃⊢() →𝜙′ 𝛼! {𝜇′} ⪯•

𝑃⊢𝛼⪯𝜙′

𝑃⊢(𝛼⪯𝜙′) ⇒() →𝜙′ 𝛼! {𝜇′} ⪯•

𝑃⊢(∀𝜙𝜇.(𝛼⪯𝜙) ⇒() →𝜙𝛼! {𝜇}) ⪯•

### 5.4 Type Inference

Figure 11 shows representative type inference rules for Q◦

We prove soundness and completeness of type inference with respect to the syntax-directed type system. We write 𝜃|Γ for the substitution generated by restricting the domain of 𝜃to the free variables in Γ and (𝜃= 𝜃′)|Γ for 𝜃|Γ = 𝜃′|Γ.

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

54:20 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

P-Function

P-Qualifier

P-Quantifier

In P-Quantifier we instantiate 𝜙and 𝜇with variables 𝜙′ and 𝜇′. In order to prove 𝜎⪯• from 𝑃, we must then prove 𝛼⪯𝜙′ and 𝜙′ ⪯•. Note that 𝜙′ and 𝜇′ are not fresh, but should instead appear in 𝑃, e.g., we might have 𝑃= {𝛼⪯𝜙′,𝜙′ ⪯•}. If we instead assumed 𝛼⪯𝜙, or removed the condition entirely from P-Qualifier, then 𝑃would not need to restrict 𝛼at all. We could later instantiate 𝛼with a linear type, say File, and use this term to unsoundly copy file handles.

Readers may worry that the P-Qualifier rule is as general as it could be, because it always requires 𝑃⊢𝜋. For example, consider let 𝑓= 𝑉in 𝑀where 𝑓: 𝜎does not appear freely in 𝑀. We collect the constraint 𝜎⪯•. Constraints of 𝑉that are captured in 𝜎do not necessarily need to be satisfied, because 𝑓is not used. However, we believe that binding unsatisfiable values has little benefits and can hide potential bugs in practice.

Note that these entailment rules are intentionally made as simple as possible. For example, we do not include any transitivity rules. The entailment rules also do not check potentially conflicted predicates in predicate sets since the rule P-Subsume allows collecting any predicates. We say that predicate set 𝑃is satisfiable if there exists a substitution 𝜃such that · ⊢𝜃𝑃, and define the solutions of it as J𝑃K𝑠𝑎𝑡= {𝜃| · ⊢𝜃𝑃}. Transitivity of ⪯is admissible when considering the solutions of predicates, e.g., J𝜙1 ⪯𝜙2,𝜙2 ⪯•K𝑠𝑎𝑡= J𝜙1 ⪯𝜙2,𝜙2 ⪯•,𝜙1 ⪯•K𝑠𝑎𝑡= {[•/𝜙1, •/𝜙2]}. In Section 5.6, we will give an algorithm to check the satisfiability of constraint sets.

eff; the remainder are given in full in Appendix B.3. Our type inference algorithm is based on Algorithm W [Damas and Milner 1982] extended for qualified types [Jones 1994]. In Γ ⊢𝑉: 𝐴⊣𝜃, 𝑃, Σ, the input includes the current context Γ and value 𝑉, and the output includes the inferred type 𝐴, substitution 𝜃, predicate set 𝑃, and variable set Σ of used term variables. Note that the predicates 𝑃are an output of inference, not an input; rather than checking entailment, as the syntax-directed type rules do, we will emit a constraint set sufficient to guarantee typing. In the next section, we discuss our algorithm to guarantee that inferred constraint sets are not unsatisfiable. As usual, the substitution 𝜃has been already applied to 𝐴and 𝑃.

Rule Q-LetW demonstrates the treatment of linearity. We write Γ|Σ for the type context generated by restricting Γ to variables in Σ. We begin by inferring types for 𝑉and 𝑀. Variable sets Σ1 and Σ2 capture those variables used in each; any variable in Σ1 ∪Σ2 must be unlimited. We also account for the possibility that the variable 𝑥may not be used in 𝑀--that is to say, that it may appear in Σc

2, the complement of the used variables Σ2. We generate the corresponding unlimitedness constraints using the auxiliary function factorise, discussed next. Rule Q-DoW emits the constraint that the singleton effect row be included in the output row. Rule Q-SeqW combines these techniques.

Theorem 5.1 (Soundness). If Γ ⊢𝑉: 𝐴⊣𝜃, 𝑃, Σ, then 𝑃| 𝜃Γ|Σ ⊢𝑉: 𝐴. The same applies to computation and handler typing.


<!-- page 21 -->

Soundly Handling Linearity 54:21

Γ ⊢𝑉: 𝐴⊣𝜃, 𝑃, Σ Γ ⊢𝑀: 𝐶⊣𝜃, 𝑃, Σ Γ ⊢𝐻: 𝐶⇒𝐷⊣𝜃, 𝑃, Σ

Q-LetW

Γ ⊢𝑉: 𝐴⊣𝜃1, 𝑃1, Σ1 𝜎= gen(𝜃1Γ, 𝑃1 ⇒𝐴) 𝜃1Γ,𝑥: 𝜎⊢𝑀: 𝐶⊣𝜃2, 𝑃2, Σ2 𝑄= un(𝜃2𝜃1Γ|Σ1∩Σ2) ∪un(𝜃2(𝑥: 𝜎)|Σc

2)

Γ ⊢let 𝑥= 𝑉in 𝑀: 𝐶⊣𝜃2𝜃1, 𝑃2 ∪𝑄, Σ1 ∪(Σ2\𝑥)

Q-SeqW

Γ ⊢𝑀: 𝐴! {𝑅1} ⊣𝜃1, 𝑃1, Σ1 𝜃1Γ,𝑥: 𝐴⊢𝑁: 𝐵! {𝑅2} ⊣𝜃2, 𝑃2, Σ2 𝜇fresh 𝑄= un(𝜃2𝜃1Γ|Σ1∩Σ2) ∪un(𝜃2(𝑥: 𝐴)|Σc

Γ ⊢let 𝑥←𝑀in 𝑁: 𝐵! 𝜇⊣𝜃2𝜃1,𝜃2𝑃1 ∪𝑃2 ∪𝑄, Σ1 ∪(Σ2\𝑥)

leq(Γ,𝜏) = factorise(Γ ⪯𝜏) un(Γ) = leq(Γ, •) sub(𝑅1, 𝑅2) = factorise(𝑅1 ⩽𝑅2)

Fig. 11. Selected Type Inference Rules for Q◦

The proofs can be found in Appendix C.3 and depend on the correctness of factorise, discussed next. Note that we do not need to incorporate the subtyping relation into the statement of the completeness theorem because we only have subtyping between row types and do not allow implicit subsumption (unlike traditional subtyping systems).

### 5.5 Factorising Predicates

factorise : Pred →PSet factorise(𝜏⪯𝜏) = ∅ factorise(𝜏⪯◦) = ∅ factorise(• ⪯𝜏) = ∅ factorise(𝐴→𝑌𝐶⪯𝜏) = factorise(𝑌⪯𝜏) factorise(𝜏⪯𝐾; 𝜇) =

factorise(𝜏⪯𝐾) ∪factorise(𝜏⪯𝜇) factorise(𝜏⪯𝐾) = Ð

(ℓ:𝐴↠𝑌𝐵)∈𝐾factorise(𝜏⪯𝑌) factorise(𝑅1 ⩽𝑅2) = ∅, when set(𝑅1) ⊆set(𝑅2) factorise(𝑅⊥L) = ∅, when dom(𝑅) ∩L = ∅ factorise(𝜋) = 𝜋

The factorise function is defined in Figure 12; it factors constraints into simpler predicates following the entailment rules in Figure 10. We use 𝐾to represent rows consisting of only operation labels.

Q-DoW

Γ ⊢𝑉: 𝐴⊣𝜃1, 𝑃, Σ 𝐴∼𝐴ℓ: 𝜃2 𝜇,𝜙fresh 𝑄= sub((ℓ: 𝐴ℓ↠𝜙𝐵ℓ), 𝜇)

Γ ⊢do ℓ𝑉: 𝐵ℓ! {𝜇} ⊣𝜃2𝜃1,𝜃2𝑃∪𝑄, Σ

2) ∪leq(𝜃2𝜃1Γ|Σ2,𝜃2𝑅1) ∪sub(𝜃2𝑅1, 𝜇) ∪sub(𝑅2, 𝜇)

eff

Theorem 5.2 (Completeness). If 𝑃| 𝜃Γ ⊢𝑉: 𝐴, then Γ ⊢𝑉: 𝐴′ ⊣𝜃′,𝑄, Σ and there exists 𝜃′′

such that 𝐴= 𝜃′′𝐴′, 𝑃⊢𝜃′′𝑄, and (𝜃= 𝜃′′𝜃′)|Γ. The same applies to computation and handler typing.

factorise : (TySch ⪯Type) →PSet factorise((∀𝛼.𝜎) ⪯𝜏) =

factorise([𝛽/𝛼]𝜎⪯𝜏) for some fresh 𝛽 factorise((𝜋⇒𝜎) ⪯𝜏) =

factorise(𝜋) ∪factorise(𝜎⪯𝜏)

factorise : (Env ⪯Type) →PSet factorise(Γ ⪯𝜏) = Ð

(𝑥:𝜎)∈Γ factorise(𝜎⪯𝜏)

factorise : PSet →PSet factorise(𝑃) = Ð

𝜋∈𝑃factorise(𝜋)

Fig. 12. Factorisation of Constraints

The only surprising case is for (∀𝛼.𝜎) ⪯𝜏. Rule P-Quantifier requires that we find some instance such that 𝜎[𝜏′/𝛼] ⪯𝜏. Rather than search for such an instance, we simply pick a fresh type

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 22 -->

54:22 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

variable 𝛽. As a result, our type inference algorithm is likely to produce ambiguous type schemes, in which quantified type variables appear only in predicates. Such type schemes are typically rejected [Jones 1994], as the meaning of ambiguously typed terms is undefined. However, as our linearity predicates do not have any intrinsic semantics, but only constrain the use of terms, we do not believe these constraints lead to semantic ambiguity. One interesting property of factorise is that the linearity predicates in its results are only between value type variables 𝛼, row type variables 𝜇, and linearity types 𝑌.

We prove the correctness of factorise with respect to the entailment rules in Figure 10.

Theorem 5.3 (Correctness of factorisation). If factorise(𝑃) = 𝑄, then 𝑄⊢𝑃and 𝑃⊢𝑄. If factorise(Γ ⪯𝜏) = 𝑄, then 𝑄⊢Γ ⪯𝜏and for any 𝑃⊢Γ ⪯𝜏, there exists 𝜃such that 𝑃⊢𝜃𝑄.

The proof can be found in Appendix C.1.

### 5.6 Constraint Solving

Finally, we must check that inferred constraint sets are satisfiable; we do not want to conclude that a program is well-typed, but only under the assumption that a linear type is unlimited.

We define a constraint solving algorithm solve(𝑃) for checking the satisfiability of the predicate set 𝑃, inspired by solving algorithms for general subtyping constraints [Pottier 1998, 2001; Pretnar 2014]. The tricky part compared to solving usual subtyping constraints is that we need to carefully deal with the interaction between row subtyping constraints and linearity constraints. For instance, 𝑅1 ⩽𝑅2 and 𝜏⪯𝑅2 actually implies 𝜏⪯𝑅1. To resolve the interaction, the algorithm proceeds by first transforming row subtyping constraints to those of the forms 𝜇⩽𝑅, so that we can always simply instantiate 𝜇on the left to the empty row · for which 𝜏⪯· always holds. Then, the algorithm computes the transitive closure of linearity constraints and rejects ◦⪯•. The full algorithm is given in Appendix B.4. We have the following theorem on the correctness of the constraint solving algorithm, in which we write J𝑃K𝑠𝑎𝑡𝜃for the substitution set {𝜃′𝜃| 𝜃′ ∈J𝑃K𝑠𝑎𝑡}.

Theorem 5.4 (Correctness of constraint solving). For any constraint set 𝑃generated by the type inference of Q◦

eff, solve(𝑃) always terminates. • If it fails, then 𝑃is not satisfiable. • If it returns (𝜃,𝑄), then 𝑃is satisfiable and J𝑃K𝑠𝑎𝑡= J𝑄K𝑠𝑎𝑡𝜃.

The proof can be found in Appendix C.4, whose main idea is to show that every step of the algorithm preserves solutions, and the output predicate set has one solution.

We leave the design of constraint simplification algorithms as practical concerns. Some existing algorithms on simplifying general subtyping constraints are promising [Pottier 1998, 2001].

## 6 SHALLOW HANDLERS

Up to now we have concentrated on deep effect handlers, which wrap the original handler around the body of captured continuations. Given this automatic reuse of the handler, the handler itself cannot capture any linear resources. In contrast, shallow handlers [Hillerström and Lindley 2018; Kammar et al. 2013] do not wrap the original handler around the body of captured continuations, which means shallow handlers can capture linear resources and thus influence control-flow linearity. In this section, we discuss the extensions of F◦

eff and Q◦

eff with shallow handlers and their challenges. Let us first consider shallow handlers in F◦

eff. We write 𝐻† for a shallow handler. The only difference in the operational semantics is the new E-Op† rule for handling with shallow handlers.

E-Op† handle E[(do ℓ𝑉)𝐸] with 𝐻† { 𝑁[𝑉/𝑝, (𝜆𝑌𝑦𝐵.E[(return 𝑦)𝐸])/𝑟],

where ℓ∉bl(E), (ℓ𝑝𝑟↦→𝑁) ∈𝐻† and (ℓ: 𝐴→𝑌𝐵) ∈𝐸

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 23 -->

Soundly Handling Linearity 54:23

T-ShallowHandler

𝐻= {return 𝑥↦→𝑀} ⊎{ℓ𝑖𝑝𝑖𝑟𝑖↦→𝑁𝑖}𝑖 𝐶= 𝐴! {(ℓ𝑖: 𝐴𝑖↠𝑌𝑖𝐵𝑖)𝑖;𝑅} 𝐷= 𝐵! {(ℓ𝑖: 𝑃)𝑖;𝑅} Δ ⊢Γ : 𝑌 Δ ⊢𝑅: 𝑌 Δ; Γ,𝑥: 𝐴⊢𝑀: 𝐷 [Δ; Γ, 𝑝𝑖: 𝐴𝑖,𝑟𝑖: 𝐵𝑖→𝑌𝑖𝐶⊢𝑁𝑖: 𝐷]𝑖

Δ; Γ ⊢𝐻† : 𝐶⇒𝐷

We can also easily extend Q◦

eff with shallow handlers.

Q-ShallowHandler

𝐻= {return 𝑥↦→𝑀} ⊎{ℓ𝑖𝑝𝑖𝑟𝑖↦→𝑁𝑖}𝑖 𝐶= 𝐴! {(ℓ𝑖: 𝐴𝑖↠𝑌𝑖𝐵𝑖)𝑖;𝑅1} 𝐷= 𝐵! {𝑅2} 𝑃| Γ,𝑥: 𝐴⊢𝑀: 𝐷 [𝑃| Γ, 𝑝𝑖: 𝐴𝑖,𝑟𝑖: 𝐵𝑖→𝑌𝑖𝐶⊢𝑁𝑖: 𝐷]𝑖 𝑃⊢Γ ⪯𝑅1 𝑃⊢𝑅1 ⩽𝑅2 𝑃⊢𝑅1 ⊥{ℓ𝑖}𝑖

𝑃| Γ ⊢𝐻: 𝐶⇒𝐷

In place of 𝑃⊢Γ ⪯• in Q-Handler, we have 𝑃⊢Γ ⪯𝑅1, which restricts the value linearity of the type context to be less than the control-flow linearity of unhandled operations in 𝑅1.

eff and Q◦

T-Rec

Δ; Γ, 𝑓: 𝐴→• 𝐶,𝑥: 𝐴⊢𝑀: 𝐶 Δ ⊢Γ : •

Δ; Γ ⊢rec 𝑓𝐴→•𝐶𝑥.𝑀: 𝐴→• 𝐶

E-Rec (rec 𝑓𝑥.𝑀) 𝑉{ 𝑀[(rec 𝑓𝑥.𝑀)/𝑓,𝑉/𝑥]

withFile 𝑓= rec withFile 𝑓.handle 𝑀with

{return 𝑥↦→Close 𝑓;𝑥

Unlike in E-Op, the body of the continuation is not handled by 𝐻†. Whereas deep handlers perform a fold over a computation trees shallow handlers perform a case-split. As such, we know that exactly one operation clause or the return clause will be invoked, and providing all allowed operations are linear each clause may capture the same linear resources. The typing rule is as follows.

Instead of requiring value linearity of Γ to be unlimited as in the deep handler rule T-Handler, we require the value linearity of Γ to coincide with the control-flow linearity of 𝑅, the effect row of the unhandled operations. This is because the shallow handler may be captured as part of the continuations of these unhandled operations in outer handlers. Concretely, when 𝑌= ◦, the shallow handler may use linear variables from the context, and unhandled operations are control-flow linear; when 𝑌= •, the shallow handler cannot use any linear variables from the context, and we have no restriction on the control-flow linearity of unhandled operations.

Shallow handlers are typically used together with recursive functions to implement more general recursive behaviours than the structural recursion of deep handlers. It is straightforward to extend F◦

eff with recursive functions [Hillerström et al. 2020a; Mazurak et al. 2010]. Obviously recursive functions are themselves unlimited so cannot capture linear resources, but that does not preclude explicitly threading a linear resource through a recursive function that installs a shallow handler. We use the syntax rec 𝑓𝑥.𝑀to define a recursive function 𝑓with parameter 𝑥 and function body 𝑀. The typing rules and semantics rule for it in F◦

eff and Q◦

eff are as follows.

Q-Rec

Δ; Γ, 𝑓: 𝐴→• 𝐶,𝑥: 𝐴⊢𝑀: 𝐶 𝑃⊢Γ ⪯•

𝑃| Γ ⊢rec 𝑓𝑥.𝑀: 𝐴→• 𝐶

As an example, we can write the following recursive function withFile 𝑓which takes a file handle 𝑓and interprets all Print operations in 𝑀as writing to file 𝑓.

Print𝑠𝑟 ↦→let 𝑓′ ←write (𝑠, 𝑓) in withFile 𝑓′ 𝑟}

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 24 -->

54:24 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

Note that this example can also be implemented with a deep handler by requiring the handler to return a function which takes the file handle as a parameter. Shallow handlers provide us with a more direct programming style.

Although our two new typing rules are straightforward and entirely backward compatible with the current systems, shallow handlers can actually introduce more challenges to track control-flow linearity. This is essentially because shallow handlers are more flexible than deep handlers and do not handle all invocations of the same operation uniformly. With only deep handlers, it is natural for all invocations of an operation to have the same control-flow linearity as they are handled by the same handler. However, with shallow handlers, different invocations of the same operation can be handled by different handlers, resulting in different control-flow linearity. For example, consider the following program hesitantClose which makes choices before and after closing the file 𝑓.

hesitantClose = 𝜆𝑓.do Choose (); close 𝑓; do Choose ()

The continuation of the first Choose contains the linear file handle 𝑓, whereas the second one does not. Technically, the handler for the second Choose can resume any number of times. However, neither the effect system of F◦

eff nor that of Q◦

eff is able to ascribe a different control-flow linearity to the two invocations of Choose, which means we must handle both invocations linearly. One potential solution is to track the order and duplication of effects in the effect system. However, this kind of information is known to be too cumbersome for effect systems. A more lightweight solution is to exploit named handlers [Biernacki et al. 2020; Xie et al. 2022] to assign Choose operations in different positions to different shallow handlers. We leave the design of an ergonomic and expressive effect system for tracking control-flow linearity of shallow handlers to future work.

## 7 RELATED WORK

Linear Resources and Control Effects. Exception handlers with finally clauses are a common way of managing linear resources. Exception handlers provide a form of unwind protection, which enables the programmer to supply the logic to release acquired resources in the finally clause, which gets executed irrespective of whether a fault occurs. Similarly, the defer statement in Go [Donovan and Kernighan 2015] defers the execution of its operand until the defining function returns either successfully or via a fault. Thus the programmer can conveniently acquire a particular resource and include the deferred logic for releasing it on the next line of code. Another variation is automatic resource block management as in the C++ RAII idiom [Combette and Munch-Maccagnoni 2018] and Java's try-with-resource [Gosling et al. 2023], both of which offer a means for automatically acquiring and releasing resources in the static scope. In Scheme the fundamental resource protection mechanism is the procedure dynamic-wind [Friedman and Haynes 1985]. It is a generalisation of unwind protection intended to be used in the presence of first-class control, where control may enter and leave the same computation multiple times. It takes three functional arguments: the first is the resource acquisition procedure, which gets applied when control enters dynamic-wind; the second is the main computation, which may use the acquired resources; and the third is the resource release procedure, which is applied when control is about to leave dynamic-wind.

Brachthäuser and Leijen [2023] present a constraint system based on qualified types for programming with multi-shot effect handlers and linear resources in Koka. They use these constraints to mark some effects as linear. However, they do not include a linear type system and instead rely on pre-declaring the linearity of operations (i.e., no inference for control-flow linearity) and a syntactic check to ensure that resumptions are not invoked more than once. Compared to the qualified effect system of Q◦

eff, their system does not support effect subtyping and abstraction over linearity.

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 25 -->

Soundly Handling Linearity 54:25

Structural Types and Control Effects. Tov and Pucella [2011b] propose a calculus 𝜆URAL(𝒞) which extends the substructural 𝜆-calculus 𝜆URAL [Ahmed et al. 2005] with abstract control effects 𝒞 given by a set of effects, a pure effect, and an effect-sequencing operator. They show how to instantiate 𝜆URAL(𝒞) with concrete control effects including exceptions and shift/reset [Danvy and Filinski 1990] separately. Similar to F◦

eff, the 𝜆URAL(𝒞) calculus also uses type-and-effect system to check that control effects do not violate the substructural usage guarantees for values. It includes a judgement on effect types to determine whether control effects may discard or duplicate their continuations, which roughly corresponds to our notion of control-flow linearity. The main difference between our work and 𝜆URAL(𝒞) is that we consider the tracking of control-flow linearity in the presence of algebraic effects and effect handlers, which are more involved than exceptions and shift/reset both statically and dynamically. While it is theoretically possible to instantiate 𝜆URAL(𝒞) to effect handlers, this task is itself highly non-trivial due to the richer effect systems of effect handlers. Conversely, we can also easily encode exceptions and shift/reset as user-defined effects in F◦

eff and Q◦

eff and Q◦

eff using effect handlers [Forster et al. 2019; Piróg et al. 2019].

Linear Type Systems. Type inference with linear types is a well-studied area. Mazurak et al. [2010] propose using kinds to track linearity, using subkinding to enable polymorphism over linearities. Tov and Pucella [2011a] develop an expanded approach to tracking structural restrictions in kinds; among other differences they introduce subtyping for function types and require fewer explicit linearity annotations than Mazurak et al.. Gan et al. [2014] use qualified types to characterise types that admit structural rules in a substructural type system: for example, in a linear type system, unlimited types are exactly types 𝜏that support operations dup : 𝜏→(𝜏,𝜏) and drop : 𝜏→(). Morris [2016] extends the approach of Tov and Pucella to generalise the treatment of function types, introducing the linearity ordering constraint 𝜏⪯𝜐; he also generalises their description of unlimited types to type schemes, but does so unsoundly. In contrast, the current work does not interpret unlimited types via operations like dup and drop; we also avoid Morris's unsoundness in the treatment of type schemes. An alternative approach tracks linearity exclusively in function types, rather than in kinds. This approach is developed by Ghica and Smith [2014], McBride [2016], and Atkey [2018], and has been implemented in Idris [Brady 2021] and an extension to the GHC Haskell compiler [Bernardy et al. 2018].

Row-based Effect Types. Row types and row polymorphism are a popular way of implementing effect systems in programming languages. Links [Hillerström and Lindley 2016] adopts Rémy style row polymorphism [Rémy 1994], where the row types are able to represent the absence of labels and each label is restricted to appear at most once. Koka [Leijen 2017] and Frank [Lindley et al. 2017] use row polymorphism based on scoped labels [Leijen 2005] which allows duplicated labels. We believe the idea of tracking control-flow linearity in F◦

eff should work well with all kinds of different row-based effect systems.

Subtyping-based Effect Types. Some versions of Eff [Bauer and Pretnar 2014; Pretnar 2014] use an effect system based on subtyping. Karachalias et al. [2020] describe an explicit target calculus ExEff with a subtyping-based effect system and a type inference algorithm that elaborates Eff source code into it. Eff uses a row-like representation of effect types and defines a subtyping relation for effect types similar to the that of Q◦

eff. One difference is that Eff incorporates full subtyping relations between all types and implicit subsumption, whereas we only introduce subtyping between row types and allow explicit subsumption in necessary positions (like Q-Seq and Q-Handle). In this respect our qualified effect system is more lightweight. Algebraic subtyping [Dolan 2016; Dolan and Mycroft 2017] combines subtyping and parametric polymorphism with elegant principal types.

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 26 -->

54:26 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

It would be interesting to explore the possibility of combining linear types and effect types based on algebraic subtyping with control-flow linearity.

One-shot control operators. One-shot continuations were first introduced by Friedman and Haynes [1985] in the form of a linear variant of call/cc. Similarly, Filinski [1992] considers a one-shot variant of the C operator [Felleisen et al. 1987].

One-shot Effect Handlers. OCaml 5 [Sivaramakrishnan et al. 2021], the C++-effects library [Ghica et al. 2022], and the typed continuations proposal for adding effect handlers to WebAssembly [Hillerström et al. 2022; Phipps-Costin et al. 2023] all implement dynamically-checked one-shot effect handlers. Continuations captured by such effect handlers can be thought of as linear resources themselves, and thus play nicely with other linear resources. Any attempt to invoke a continuation more than once throws a runtime error. In contrast, our type systems can be used to statically ensure that handlers are one-shot. In fact, its considerably easier to build a system that ensures that all handlers are uniformly one-shot than a system like ours that supports both one-shot and multi-shot handlers, as in the former case there is no need to track the use of linear resources specially. Another advantage of one-shot continuations is that they admit efficient implementations which are compatible with linear resources, as a one-shot continuation need not copy its underlying stack [Bruggeman et al. 1996]. Hillerström et al. [2023] present a substructural type system for a calculus with effect handlers based on dual intuitionistic linear logic [Barber 1996] which restricts all effect handlers to be one-shot (actually one- or zero-shot). They use it to show an asymptotic performance gap between one-shot and multi-shot effect handlers, but are not concerned with linear resources other than continuations.

Multi-shot Effect Handlers. Eff [Bauer and Pretnar 2015], Effekt [Brachthäuser et al. 2020], Koka [Leijen 2017], and Helium [Biernacki et al. 2019] are research programming languages with multi-shot handlers. In contrast to one-shot handlers, multi-shot handlers can invoke the captured continuations an arbitrary number of times. This enables a range of interesting applications. For instance, asymptotic efficient backtracking search [Hillerström et al. 2020b], nondeterminism [Kammar et al. 2013], and UNIX fork-style concurrency [Hillerström 2022] can all be given a direct semantics in terms of multi-shot handlers. However, one obstacle is that the aforementioned languages cannot statically optimise uses of one-shot continuations, as they must conservatively expect the ambient context to have nonlinear control flow, thus requiring them to copy the continuation a priori [Hillerström 2016; Hillerström et al. 2016]. Our type systems can enable static optimisation of one-shot continuations through static identification of linear and nonlinear contexts.

## 8 CONCLUSION AND FUTURE WORK

We have explored the interplay between effect handlers and linear types. We have demonstrated that in order to soundly combine potentially non-linear effect handlers with linear types, it is necessary to add a mechanism for tracking control-flow linearity too. We incorporated control-flow linearity into two quite different core languages as well as realising control-flow linearity in Links.

Directions for future work include: implementing a programming language based on Q◦

eff; developing more precise type systems for combining control-flow linearity with shallow handlers; combining control-flow linearity with other forms of effect type systems, such as those that support generative effects, duplicate effects, capabilities, and modal effect types; adapting the constraints of Q◦

eff to algebraic subtyping [Dolan and Mycroft 2017]; and adapting control-flow linearity for uniqueness types and for quantitive type theory [Atkey 2018; McBride 2016].

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 27 -->

Soundly Handling Linearity 54:27

DATA AVAILABILITY STATEMENT

The implementation of F◦

eff in Links is available on Zenodo [Tang et al. 2023].

ACKNOWLEDGMENTS This work was supported by the UKRI Future Leaders Fellowship "Effect Handler Oriented Programming" (reference number MR/T043830/1).

REFERENCES

Amal J. Ahmed, Matthew Fluet, and Greg Morrisett. 2005. A step-indexed model of substructural state. In ICFP. ACM, 78-91.

https://doi.org/10.1145/1086365.1086376 Robert Atkey. 2018. Syntax and Semantics of Quantitative Type Theory. In LICS. ACM, 56-65. https://doi.org/10.1145/

3209108.3209189 Andrew Barber. 1996. Dual Intuitionistic Linear Logic. Technical Report ECS-LFCS-96-347. Laboratory for Foundations of

Computer Science, The University of Edinburgh, UK. Andrej Bauer and Matija Pretnar. 2014. An Effect System for Algebraic Effects and Handlers. Log. Methods Comput. Sci. 10, 4

(2014). https://doi.org/10.2168/LMCS-10(4:9)2014 Andrej Bauer and Matija Pretnar. 2015. Programming with Algebraic Effects and Handlers. J. Log. Algebraic Methods

Program. 84, 1 (2015), 108-123. https://doi.org/10.1016/J.JLAMP.2014.02.001 Jean-Philippe Bernardy, Mathieu Boespflug, Ryan R. Newton, Simon Peyton Jones, and Arnaud Spiwack. 2018. Linear

Haskell: Practical Linearity in a Higher-Order Polymorphic Language. Proc. ACM Program. Lang. 2, POPL (2018), 5:1-5:29. https://doi.org/10.1145/3158093 Dariusz Biernacki, Maciej Piróg, Piotr Polesiuk, and Filip Sieczkowski. 2019. Abstracting Algebraic Effects. Proc. ACM

Program. Lang. 3, POPL (2019), 6:1-6:28. https://doi.org/10.1145/3290319 Dariusz Biernacki, Maciej Piróg, Piotr Polesiuk, and Filip Sieczkowski. 2020. Binders by day, labels by night: effect instances

via lexically scoped handlers. Proc. ACM Program. Lang. 4, POPL (2020), 48:1-48:29. https://doi.org/10.1145/3371116 Jonathan Immanuel Brachthäuser and Daan Leijen. 2023. Qualified Effect Types - Taming Control-Flow through Linear

Effect Handlers. Technical Report MSR-TR-2023-42. Microsoft. https://www.microsoft.com/en-us/research/publication/ qualified-effect-types/ Jonathan Immanuel Brachthäuser, Philipp Schuster, and Klaus Ostermann. 2020. Effects as Capabilities: Effect Handlers and

Lightweight Effect Polymorphism. Proc. ACM Program. Lang. 4, OOPSLA (2020), 126:1-126:30. https://doi.org/10.1145/ 3428194 Edwin C. Brady. 2021. Idris 2: Quantitative Type Theory in Practice. In ECOOP (LIPIcs, Vol. 194). Schloss Dagstuhl -

Leibniz-Zentrum für Informatik, 9:1-9:26. https://doi.org/10.4230/LIPIcs.ECOOP.2021.9 Carl Bruggeman, Oscar Waddell, and R. Kent Dybvig. 1996. Representing Control in the Presence of One-Shot Continuations.

In PLDI. ACM, 99-107. https://doi.org/10.1145/231379.231395 Guillaume Combette and Guillaume Munch-Maccagnoni. 2018. A Resource Modality for RAII. In LOLA 2018: Workshop on

Syntax and Semantics of Low-Level Languages. 1-4. Ezra Cooper, Sam Lindley, Philip Wadler, and Jeremy Yallop. 2006. Links: Web Programming Without Tiers. In FMCO

(Lecture Notes in Computer Science, Vol. 4709). Springer, 266-296. https://doi.org/10.1007/978-3-540-74792-5_12 Luís Damas and Robin Milner. 1982. Principal Type-Schemes for Functional Programs. In POPL. ACM Press, 207-212.

https://doi.org/10.1145/582153.582176 Olivier Danvy and Andrzej Filinski. 1990. Abstracting Control. In LISP and Functional Programming. ACM, 151-160.

https://doi.org/10.1145/91556.91622 Stephen Dolan. 2016. Algebraic Subtyping. Ph. D. Dissertation. Computer Laboratory, University of Cambridge, United

Kingdom. Stephen Dolan and Alan Mycroft. 2017. Polymorphism, subtyping, and type inference in MLsub. In POPL. ACM, 60-72.

https://doi.org/10.1145/3009837.3009882 Alan A.A. Donovan and Brian W. Kernighan. 2015. The Go Programming Language (1st ed.). Addison-Wesley Professional. Matthias Felleisen, Daniel P. Friedman, Eugene E. Kohlbecker, and Bruce F. Duba. 1987. A Syntactic Theory of Sequential

Control. Theor. Comput. Sci. 52 (1987), 205-237. https://doi.org/10.1016/0304-3975(87)90109-5 Andrzej Filinski. 1992. Linear Continuations. In POPL. ACM Press, 27-38. https://doi.org/10.1145/143165.143174 Yannick Forster, Ohad Kammar, Sam Lindley, and Matija Pretnar. 2019. On the expressive power of user-defined effects:

Effect handlers, monadic reflection, delimited control. J. Funct. Program. 29 (2019), e15. https://doi.org/10.1017/ S0956796819000121

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 28 -->

54:28 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

Daniel P. Friedman and Christopher T. Haynes. 1985. Constraining Control. In POPL. ACM Press, 245-254. https: //doi.org/10.1145/318593.318654 Daniel P. Friedman, Christopher T Haynes, and Eugene Kohlbecker. 1984. Programming with Continuations. In Program

Transformation and Programming Environments, Peter Pepper (Ed.). Springer Berlin Heidelberg, Berlin, Heidelberg, 263-274. https://doi.org/10.1007/978-3-642-46490-4_23 Edward Gan, Jesse A. Tov, and Greg Morrisett. 2014. Type Classes for Lightweight Substructural Types. In LINEARITY

(EPTCS, Vol. 176). 34-48. https://doi.org/10.4204/EPTCS.176.4 Dan R. Ghica, Sam Lindley, Marcos Maroñas Bravo, and Maciej Piróg. 2022. High-level effect handlers in C++. Proc. ACM

Program. Lang. 6, OOPSLA2 (2022), 1639-1667. https://doi.org/10.1145/3563445 Dan R. Ghica and Alex I. Smith. 2014. Bounded Linear Types in a Resource Semiring. In ESOP (Lecture Notes in Computer

Science, Vol. 8410). Springer, 331-350. https://doi.org/10.1007/978-3-642-54833-8_18 James Gosling, Bill Joy, Guy Steele, Gilad Bracha, Alex Buckley, Daniel Smith, and Gavin Bierman. 2023. The Java Language

Specification: Java SE 20 Edition. https://docs.oracle.com/javase/specs/jls/se20/html/index.html. [Accessed 2023-07-11]. Daniel Hillerström. 2022. Foundations for Programming and Implementing Effect Handlers. Ph. D. Dissertation. School of

Informatics, The University of Edinburgh, UK. Daniel Hillerström, Daan Leijen, Sam Lindley, Matija Pretnar, Andreas Rossberg, and KC Sivamarakrishnan. 2022. WebAssem-

bly Typed Continuations Proposal. https://github.com/wasmfx/specfx/blob/main/proposals/continuations/Explainer.md [Accessed 2023-11-14]. Daniel Hillerström and Sam Lindley. 2016. Liberating effects with rows and handlers. In TyDe@ICFP. ACM, 15-27. https:

//doi.org/10.1145/2976022.2976033 Daniel Hillerström and Sam Lindley. 2018. Shallow Effect Handlers. In APLAS (Lecture Notes in Computer Science, Vol. 11275).

Springer, 415-435. https://doi.org/10.1007/978-3-030-02768-1_22 Daniel Hillerström, Sam Lindley, and Robert Atkey. 2020a. Effect handlers via generalised continuations. J. Funct. Program.

30 (2020), e5. https://doi.org/10.1017/S0956796820000040 Daniel Hillerström, Sam Lindley, and John Longley. 2020b. Effects for Efficiency: Asymptotic Speedup with First-Class

Control. Proc. ACM Program. Lang. 4, ICFP (2020), 100:1-100:29. https://doi.org/10.1145/3408982 Daniel Hillerström, Sam Lindley, and John Longley. 2023. Asymptotic Speedup with Effect Handlers. Draft. Daniel Hillerström. 2016. Compilation of Effect Handlers and their Applications in Concurrency. Master by Research thesis.

School of Informatics, The University of Edinburgh, UK. Daniel Hillerström, Sam Lindley, and KC Sivaramakrishnan. 2016. Compiling Links Effect Handlers to the OCaml Backend.

ML Workshop. Mark P. Jones. 1994. A Theory of Qualified Types. Sci. Comput. Program. 22, 3 (1994), 231-256. https://doi.org/10.1016/0167-

6423(94)00005-0 Ohad Kammar, Sam Lindley, and Nicolas Oury. 2013. Handlers in Action. In ICFP. ACM, 145-158. https://doi.org/10.1145/

2500365.2500590 Georgios Karachalias, Matija Pretnar, Amr Hany Saleh, Stien Vanderhallen, and Tom Schrijvers. 2020. Explicit effect

subtyping. J. Funct. Program. 30 (2020), e15. https://doi.org/10.1017/S0956796820000131 Oleg Kiselyov and Chung-chieh Shan. 2009. Embedded Probabilistic Programming. In DSL (Lecture Notes in Computer

Science, Vol. 5658). Springer, 360-384. https://doi.org/10.1007/978-3-642-03034-5_17 Daan Leijen. 2005. Extensible records with scoped labels. In Trends in Functional Programming (Trends in Functional

Programming, Vol. 6). Intellect, 179-194. Daan Leijen. 2008. HMF: simple type inference for first-class polymorphism. In ICFP. ACM, 283-294. https://doi.org/10.

1145/1411204.1411245 Daan Leijen. 2017. Type directed compilation of row-typed algebraic effects. In POPL. ACM, 486-499. https://doi.org/10.

1145/3009837.3009872 Sam Lindley and James Cheney. 2012. Row-based effect types for database integration. In TLDI. ACM, 91-102. https: //doi.org/10.1145/2103786.2103798 Sam Lindley, Conor McBride, and Craig McLaughlin. 2017. Do be do be do. In POPL. ACM, 500-514. https://doi.org/10.

1145/3009837.3009897 Sam Lindley and J Garrett Morris. 2017. Lightweight functional session types. Behavioural Types: from Theory to Tools. River

Publishers (2017), 265-286. Alberto Martelli and Ugo Montanari. 1982. An Efficient Unification Algorithm. ACM Trans. Program. Lang. Syst. 4, 2 (1982),

258-282. https://doi.org/10.1145/357162.357169 Karl Mazurak, Jianzhou Zhao, and Steve Zdancewic. 2010. Lightweight Linear Types in System F𝑜(TLDI '10). Association

for Computing Machinery, New York, NY, USA, 77-88. https://doi.org/10.1145/1708016.1708027 Conor McBride. 2016. I Got Plenty o' Nuttin'. In A List of Successes That Can Change the World - Essays Dedicated to Philip

Wadler on the Occasion of His 60th Birthday (Lecture Notes in Computer Science, Vol. 9600), Sam Lindley, Conor McBride,

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 29 -->

Soundly Handling Linearity 54:29

Philip W. Trinder, and Donald Sannella (Eds.). Springer, 207-233. https://doi.org/10.1007/978-3-319-30936-1_12 J. Garrett Morris. 2016. The best of both worlds: linear functional programming without compromise. In ICFP. ACM, 448-461.

https://doi.org/10.1145/2951913.2951925 J. Garrett Morris and James McKinna. 2019. Abstracting extensible data types: or, rows by any other name. Proc. ACM

Program. Lang. 3, POPL (2019), 12:1-12:28. https://doi.org/10.1145/3290325 Luna Phipps-Costin, Andreas Rossberg, Arjun Guha, Daan Leijen, Daniel Hillerström, KC Sivaramakrishnan, Matija Pretnar,

and Sam Lindley. 2023. Continuing WebAssembly with Effect Handlers. Proc. ACM Program. Lang. 7, OOPSLA2 (2023), 460-485. https://doi.org/10.1145/3622814 Maciej Piróg, Piotr Polesiuk, and Filip Sieczkowski. 2019. Typed Equivalence of Effect Handlers and Delimited Control. In

FSCD (LIPIcs, Vol. 131). Schloss Dagstuhl - Leibniz-Zentrum für Informatik, 30:1-30:16. https://doi.org/10.4230/LIPICS. FSCD.2019.30 Gordon D. Plotkin and Matija Pretnar. 2013. Handling Algebraic Effects. Log. Methods Comput. Sci. 9, 4 (2013). François Pottier. 1998. Type inference in the presence of subtyping: from theory to practice. Ph. D. Dissertation. INRIA. François Pottier. 2001. Simplifying Subtyping Constraints: A Theory. Inf. Comput. 170, 2 (2001), 153-183. https://doi.org/10.

1006/inco.2001.2963 Ron Pressler. 2018. Project Loom: Fibers and Continuations for the Java Virtual Machine. https://cr.openjdk.org/~rpressler/

loom/Loom-Proposal.html. Accessed 2023-04-14. Matija Pretnar. 2014. Inferring Algebraic Effects. Log. Methods Comput. Sci. 10, 3 (2014). https://doi.org/10.2168/LMCS-10(3:

21)2014 Didier Rémy. 1994. Theoretical Aspects of Object-oriented Programming. MIT Press, Cambridge, MA, USA, Chapter Type

Inference for Records in Natural Extension of ML, 67-95. K. C. Sivaramakrishnan, Stephen Dolan, Leo White, Tom Kelly, Sadiq Jaffer, and Anil Madhavapeddy. 2021. Retrofitting

effect handlers onto OCaml. In PLDI. ACM, 206-221. https://doi.org/10.1145/3453483.3454039 Wenhao Tang, Daniel Hillerström, Sam Lindley, and Garrett Morris. 2023. POPL24 Artifact for Soundly Handling Linearity.

https://doi.org/10.5281/zenodo.10120126 Jesse A. Tov and Riccardo Pucella. 2011a. Practical affine types. In POPL. ACM, 447-458. https://doi.org/10.1145/1926385.

1926436 Jesse A. Tov and Riccardo Pucella. 2011b. A theory of substructural types and control. In OOPSLA. ACM, 625-642.

https://doi.org/10.1145/2048066.2048115 David Walker. 2005. Substructural type systems. Advanced topics in types and programming languages (2005), 3-44. Ningning Xie, Youyou Cong, Kazuki Ikemori, and Daan Leijen. 2022. First-Class Names for Effect Handlers. Proc. ACM

Program. Lang. 6, OOPSLA2 (2022), 30-59. https://doi.org/10.1145/3563289

A PROOFS OF F◦

eff In this section, we prove the theorems in Section 3.

A.1 Unlimited is Unlimited

Theorem 3.4 (Unlimited is unlimited).

1. Unlimited values are unlimited: if Δ; Γ ⊢𝑉: 𝐴and Δ ⊢𝐴: •, then Δ ⊢Γ : •. 2. Unlimited continuations are unlimited: if Δ; Γ ⊢E[(do ℓ𝑉)𝐸] : 𝐶for 𝐸= {ℓ: 𝐴↠• 𝐵;𝑅} and ℓ∉bl(E), then there exists Δ ⊢Γ = Γ1 + Γ2 such that Δ ⊢Γ1 : • and Δ; Γ1,𝑦: 𝐵⊢ E[(return 𝑦)𝐸] : 𝐶. 3. Deep handlers are unlimited: if Δ; Γ ⊢𝐻: 𝐶⇒𝐷, then Δ ⊢Γ : •. Proof. 1. Unlimited values are unlimited. By induction on the typing derivation Δ; Γ ⊢𝑉: 𝐴.

Case T-Var. Trivial. Case T-Abs. Δ ⊢𝐴→𝑌𝐶: • gives 𝑌= •, which then gives Δ ⊢Γ : •. Case T-TAbs. Δ ⊢∀𝑌𝛼𝐾.𝐶: • gives 𝑌= •, which then gives Δ ⊢Γ : •.

2. Unlimited continuations are unlimited. By ℓ∉bl(E) and straightforward induction on typing derivations, we have 𝐶= _ ! {ℓ: 𝐴↠• 𝐵; _}. By induction on Δ; Γ ⊢E[(do ℓ𝑉)𝐸] : 𝐶.

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 30 -->

54:30 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

Case

T-Do

𝐸= {ℓ: 𝐴↠𝑌𝐵;𝑅} Δ; Γ ⊢𝑉: 𝐴 Δ ⊢𝐸: Effect

Δ; Γ ⊢(do ℓ𝑉)𝐸: 𝐵! 𝐸

Immediately, we have Δ;𝑦: 𝐵⊢(return 𝑦)𝐸: 𝐵! 𝐸and Δ ⊢· : •. Case

T-Seq

Δ; Γ1 ⊢E′[(do ℓ𝑉)𝐸] : 𝐴′ ! 𝐸′ (1) Δ; Γ2,𝑥: 𝐴′ ⊢𝑁: 𝐵′ ! 𝐸′

𝐸′ = {ℓ: 𝐴↠• 𝐵;𝑅′} Δ ⊢Γ2 : 𝑌(2) Δ ⊢(ℓ: 𝐴↠• 𝐵;𝑅′) : 𝑌(3)

Δ; Γ1 + Γ2 ⊢let𝑌𝑥←E′[(do ℓ𝑉)𝐸] in 𝑁: 𝐵′ ! 𝐸′

By (3), we have 𝑌= •. Then, by (2), we have Δ ⊢Γ2 : •. By the IH on (1), there exists Δ ⊢Γ1 = Γ11 + Γ12 such that Δ ⊢Γ11 : • and Δ; Γ11,𝑦: 𝐵⊢E′[(return 𝑦)𝐸] : 𝐴′ ! 𝐸′. Applying T-Seq to it, we have Δ; Γ3,𝑦: 𝐵⊢let𝑌𝑥←E′[(return 𝑦)𝐸] in 𝑁: 𝐵′ ! 𝐸′, Δ ⊢Γ = Γ12 + Γ3 and Δ ⊢Γ3 : • where Δ ⊢Γ3 = Γ2 + Γ11. Case

T-Handle

Δ; Γ1 ⊢E′[(do ℓ𝑉)𝐸] : 𝐴′ ! 𝐸′ (1) Δ; Γ2 ⊢𝐻: 𝐴′ ! 𝐸′ ⇒𝐵′ ! 𝐹′ (2)

Δ; Γ1 + Γ2 ⊢handle E′[(do ℓ𝑉)𝐸] with 𝐻: 𝐵′ ! 𝐹′

By (2), we have Δ ⊢Γ2 : •. By the IH on (1), there exists Δ ⊢Γ1 = Γ11 + Γ12 such that Δ ⊢Γ11 : • and Δ; Γ11,𝑦: 𝐵⊢E′[(return 𝑦)𝐸] : 𝐴′ ! 𝐸′. Applying T-Handle to it, we have Δ; Γ3,𝑦: 𝐵⊢handle E′[(return 𝑦)𝐸] with 𝐻: 𝐵′ ! 𝐹′, Δ ⊢Γ = Γ12 + Γ3 and Δ ⊢Γ3 : • where Δ ⊢Γ3 = Γ2 + Γ11. 3. Deep handlers are unlimited. Directly follows from T-Handler. □

A.2 Progress

Lemma A.1 (Canonical forms).

1. If ⊢𝑉: 𝐴→𝑌𝐵, then 𝑉is of shape 𝜆𝑌𝑥𝐴.𝑀. 2. If ⊢𝑉: ∀𝑌𝛼𝐾.𝐶, then 𝑉is of shape Λ𝑌𝛼𝐾.𝑀.

Proof. Directly follows from the typing rules. □

Theorem 3.2 (Progress). If ⊢𝑀: 𝐴! 𝐸, then either there exists 𝑁such that 𝑀{ 𝑁or 𝑀is in a normal form with respect to 𝐸.

Proof. By induction on the typing derivation ⊢𝑀: 𝐴! 𝐸. Case

T-App

⊢𝑉: 𝐴→𝑌𝐶 ⊢𝑊: 𝐴

⊢𝑉𝑊: 𝐶

By Lemma A.1, we have 𝑉= 𝜆𝑌𝑥𝐴.𝑀. Reduced by E-App. Case

T-TApp

Δ; Γ ⊢𝑉: ∀𝑌𝛼𝐾.𝐶 Δ ⊢𝑇: 𝐾

Δ; Γ ⊢𝑉𝑇: 𝐶[𝑇/𝛼]

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 31 -->

Soundly Handling Linearity 54:31

By Lemma A.1, we have 𝑉= Λ𝑌𝛼𝐾.𝑀. Reduced by E-TApp. Case T-Return. In a normal form with respect to 𝐸. Case T-Do. In a normal form with respect to 𝐸. Case

T-Seq

Δ; Γ1 ⊢𝑀: 𝐴! 𝐸 Δ; Γ2,𝑥: 𝐴⊢𝑁: 𝐵! 𝐸 𝐸= {𝑅} Δ ⊢Γ2 : 𝑌 Δ ⊢𝑅: 𝑌

Δ; Γ1 + Γ2 ⊢let𝑌𝑥←𝑀in 𝑁: 𝐵! 𝐸

By a case analysis on 𝑀. Subcase 𝑀= (return 𝑁)𝐸. Reduced by E-Seq. Subcase Otherwise. By the IH, if 𝑀{ 𝑁, then the original term is reduced by E-Lift.

Otherwise, 𝑀is in a normal form with respect to 𝐸, which implies the original term is also in a normal form with respect to 𝐸. Case

T-Handle

Δ; Γ1 ⊢𝐻: 𝐶⇒𝐷 Δ; Γ2 ⊢𝑀: 𝐶 𝐶= 𝐴! 𝐸′ 𝐷= 𝐵! 𝐸

Δ; Γ1 + Γ2 ⊢handle 𝑀with 𝐻: 𝐷

By a case analysis on 𝑀. Subcase 𝑀= (return 𝑁)𝐸′. Reduced by E-Ret. Subcase 𝑀= E[(do ℓ𝑉)𝐸′′] with ℓ∉bl(E) and (ℓ𝑝𝑟↦→𝑁) ∈𝐻. The original term is

reduced by E-Op. Subcase Otherwise. By the IH, if 𝑀{ 𝑁, then the original term is reduced by E-Lift.

Otherwise, 𝑀is in a normal form with respect to 𝐸′. By Definition 3.1, 𝑀= E[(do ℓ𝑉)𝐸′′] for ℓ∈𝐸′ and ℓ∉bl(E). By the last subcase, ℓis also not handled by 𝐻. Thus, the original term is also in a normal form with respect to 𝐸.

□

A.3 Subject Reduction

Lemma A.2 (Substitution).

1. Preservation of kinds under type substitution: if Δ, 𝛼: 𝐾′ ⊢𝑇: 𝐾and Δ ⊢𝑇′ : 𝐾′, then Δ ⊢𝑇[𝑇′/𝛼] : 𝐾. 2. Preservation of types under type substitution: if Δ ⊢𝑇: 𝐾, then Δ, 𝛼: 𝐾; Γ ⊢𝑀: 𝐶implies Δ; Γ[𝑇/𝛼] ⊢𝑀[𝑇/𝛼] : 𝐶[𝑇/𝛼], and Δ, 𝛼: 𝐾; Γ ⊢𝑉: 𝐴implies Δ; Γ[𝑇/𝛼] ⊢𝑉[𝑇/𝛼] : 𝐴[𝑇/𝛼], and Δ, 𝛼: 𝐾; Γ ⊢𝐻: 𝐶⇒𝐷implies Δ; Γ[𝑇/𝛼] ⊢𝐻[𝑇/𝛼] : (𝐶⇒𝐷)[𝑇/𝛼]. 3. Preservation of types under value substitution: if Δ ⊢Γ1 : 𝑌, Δ; Γ1 ⊢𝑉: 𝐴and Δ ⊢𝐴: 𝑌, then Δ; Γ2,𝑥: 𝐴⊢𝑀: 𝐶implies Δ; Γ1 + Γ2 ⊢𝑀[𝑉/𝑥] : 𝐶, and Δ; Γ2,𝑥: 𝐴⊢𝑊: 𝐵implies Δ; Γ1 + Γ2 ⊢𝑊[𝑉/𝑥] : 𝐵, and Δ; Γ2,𝑥: 𝐴⊢𝐻: 𝐶⇒𝐷implies Δ; Γ1 + Γ2 ⊢𝐻[𝑉/𝑥] : 𝐶⇒𝐷.

Proof. We apply various structural lemmas like weakening, permutation of contexts, and properties of context splitting in the following proofs. 1. Preservation of kinds under type substitution. Straightforward induction on the kinding derivations. 2. Preservation of types under type substitution. By Lemma A.2.1 and straightforward mutual induction on the typing derivations. 3. Preservation of types under value substitution. By mutual induction on the typing derivations. □

Theorem 3.3 (Subject reduction). If Δ; Γ ⊢𝑀: 𝐶and 𝑀{ 𝑁, then Δ; Γ ⊢𝑁: 𝐶.

Proof. By induction on the typing derivation Δ; Γ ⊢𝑀: 𝐶.

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 32 -->

54:32 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

Case

T-App

Δ; Γ1 ⊢𝑉: 𝐴→𝑌𝐶(1) Δ; Γ2 ⊢𝑊: 𝐴(2)

Δ; Γ1 + Γ2 ⊢𝑉𝑊: 𝐶

The reduction can only be derived using E-App, which implies𝑉= 𝜆𝑌𝑥𝐴.𝑁and (𝜆𝑌𝑥𝐴.𝑁)𝑊{ 𝑁[𝑊/𝑥]. Inversion on (1) gives Δ; Γ1,𝑥: 𝐴⊢𝑁: 𝐶(3). Case analysis on the linearity of 𝐴: Subcase Δ ⊢𝐴: • (4). Applying Theorem 3.4.1 to (2) gives Δ ⊢Γ2 : • (5). Applying

Lemma A.2.3 to (2), (3), (4) and (5) gives Δ; Γ1 + Γ2 ⊢𝑁[𝑊/𝑥] : 𝐶. Subcase Δ ⊢𝐴: ◦(4). We always have Δ ⊢Γ2 : ◦(5). Applying Lemma A.2.3 to (2), (3), (4)

and (5) gives Δ; Γ1 + Γ2 ⊢𝑁[𝑊/𝑥] : 𝐶. Case

T-TApp

Δ; Γ ⊢𝑉: ∀𝑌𝛼𝐾.𝐶(1) Δ ⊢𝑇: 𝐾(2)

Δ; Γ ⊢𝑉𝑇: 𝐶[𝑇/𝛼]

The reduction can only be derived using E-TApp, which implies 𝑉= Λ𝑌𝛼𝐾.𝑁and

(Λ𝑌𝛼𝐾.𝑁) 𝑇{ 𝑁[𝑇/𝛼]. Inversion on (1) gives Δ, 𝛼: 𝐾; Γ ⊢𝑁: 𝐶(3). By 𝛼∉ftv(Γ), applying Lemma A.2.2 to (2) and (3) gives Δ; Γ ⊢𝑁[𝑇/𝛼] : 𝐶[𝑇/𝛼]. Case T-Return. No reduction. 𝑀is in a normal form. Case T-Do. No reduction. 𝑀is in a normal form. Case

T-Seq

Δ; Γ1 ⊢𝑀: 𝐴! {𝑅} (1) Δ; Γ2,𝑥: 𝐴⊢𝑁: 𝐵! {𝑅} (2) Δ ⊢Γ2 : 𝑌 Δ ⊢𝑅: 𝑌

Δ; Γ1 + Γ2 ⊢let𝑌𝑥←𝑀in 𝑁: 𝐵! {𝑅}

By a case analysis on the next rule used by reduction: Subcase E-Lift. Suppose 𝑀{ 𝑀′. The IH on (1) gives Δ; Γ1 ⊢𝑀′ : 𝐴! {𝑅}. Then, by T-Seq

we have Δ; Γ1 + Γ2 ⊢let𝑌𝑥←𝑀′ in 𝑁: 𝐵! {𝑅2}. Subcase E-Seq. 𝑀= (return 𝑉){𝑅}. Inversion on (1) gives Δ; Γ1 ⊢𝑉: 𝐴(3). With (2) and (3),

our goal follows from a case analysis on the linearity of 𝐴similar to the T-App case. Case

T-Handle

Δ; Γ1 ⊢𝑀: 𝐶(1) Δ; Γ2 ⊢𝐻: 𝐶⇒𝐷(2)

Δ; Γ1 + Γ2 ⊢handle 𝑀with 𝐻: 𝐷

By a case analysis on the next rule used by reduction: Subcase E-Lift. Suppose 𝑀{ 𝑀′. The IH on (1) gives Δ; Γ1 ⊢𝑀′ : 𝐶. Then, by T-Handle

we have Δ; Γ1 + Γ2 ⊢handle 𝑀′ with 𝐻: 𝐷. Subcase E-Ret. 𝑀= (return 𝑉)𝐸and (return 𝑥↦→𝑁) ∈𝐻. Suppose 𝐶= 𝐴! 𝐸. Inversion

on (1) gives Δ; Γ1 ⊢𝑉: 𝐴(3). Inversion on (2) gives Δ; Γ2,𝑥: 𝐴⊢𝑁: 𝐷(4). With (3) and (4), our goal follows from a case analysis on the linearity of 𝐴similar to the T-App case. Subcase E-Op. 𝑀= E[(do ℓ𝑉)𝐸], ℓ∉bl(E) and (ℓ𝑝𝑟↦→𝑁) ∈𝐻. Suppose (ℓ: 𝐴→𝑌𝐵) ∈

𝐸and𝑊= 𝜆𝑌𝑦𝐵.handle E[(return 𝑦)𝐸] with 𝐻. The reduction is handle 𝑀with 𝐻{ 𝑁[𝑉/𝑝,𝑊/𝑟]. Inversion on (2) gives Δ; Γ2, 𝑝: 𝐴,𝑟: 𝐵→𝑌𝐷⊢𝑁: 𝐷(3). By a straightforward induction on (1) similar to the proof of Theorem 3.4.2, it is easy to show that there exists Δ ⊢Γ1 = Γ11 +Γ12 such that Δ; Γ11 ⊢𝑉: 𝐴(4) and Δ; Γ12,𝑦: 𝐵⊢E[(return 𝑦)𝐸] : 𝐶(5).

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 33 -->

Soundly Handling Linearity 54:33

Δ; Γ11 + Γ12 + Γ2 ⊢𝑁[𝑉/𝑝,𝑊/𝑟] : 𝐷.

A.4 Linearity Safety of Evaluation

Lemma A.3 (Linear variables appear exactly once). If Δ; Γ,𝑥: 𝐴⊢𝑉: 𝐵and Δ ⊬𝐴: •, then 𝑥appears exactly once in 𝑉. If Δ; Γ,𝑥: 𝐴⊢𝑀: 𝐶and Δ ⊬𝐴: •, then 𝑥appears exactly once in 𝑀.

Lemma A.4 (Preservation of linear safety under substitution). Given closed and linear safe 𝑉and 𝑀, if ⊢𝑉: 𝐴and ·;𝑥: 𝐴⊢𝑀: 𝐶, then 𝑀[𝑉′/𝑥] is linear safe where (𝑉′, _) = tag(𝑉).

Proof. Case analysis on the linearity of 𝐴.

Case ⊢𝐴: •. We have 𝑉′ = 𝑉. By the linear safety of 𝑉, we have ℒ(𝑉) = ∅. The linear safety of

𝑀[𝑉′/𝑥] follows from the linear safety of 𝑀. Case ⊬𝐴: •. By Theorem 3.4, 𝑥does not appear in unlimited values, continuations and handlers

eff, if 𝑀 S T{ 𝑁, then 𝑁is linear safe and ℒ(𝑀) ∪S = ℒ(𝑁) ∪T.

Proof. We proceed by induction on the linearity-aware reduction rules defined in Figure 7. To avoid name conflicts, we consider ˆ𝑀 S T{ ˆ𝑁.

Case

L-App (𝜆𝑌𝑥𝐴.𝑀) 𝑉 S

L-TApp (Λ𝑌𝛼𝐾.𝑀)𝑇 ∅ ∅{ 𝑀[𝑇/𝛼]

With (3) and (4), by a case analysis on the linearity of 𝐴similar to the T-App case, we have

Δ; Γ11 + (Γ2,𝑟: 𝐵→𝑌𝐷) ⊢𝑁[𝑉/𝑝] : 𝐷(6). Then by another case analysis on 𝑌: subcase 𝑌= •. By Theorem 3.4.2 we have Δ ⊢Γ12 : •. Applying T-Handle and T-Abs to (5),

we have Δ; Γ12 + Γ2 ⊢𝑊: 𝐵→𝑌𝐷(7). Applying Theorem 3.4.3 to (2) we have Δ ⊢Γ2 : •. Finally, applying Lemma A.2.3 to (6) and (7), we have Δ; Γ11 + Γ12 + Γ2 ⊢𝑁[𝑉/𝑝,𝑊/𝑟] : 𝐷. subcase 𝑌= ◦. Applying T-Handle and T-Abs to (5), we have Δ; Γ12+Γ2 ⊢𝑊: 𝐵→𝑌𝐷(7).

We always have Δ ⊢Γ12 + Γ2 : ◦. Finally, applying Lemma A.2.3 to (6) and (7), we have

□

Proof. By the definition of the context splitting relation and straightforward induction on typing derivations. □

of 𝑀. Thus, 𝑉′ does not appear in unlimited values, continuations and handlers of 𝑀[𝑉′/𝑥]. The linear safety of 𝑀[𝑉′/𝑥] then directly follows from the linear safety of 𝑀and 𝑉.

□

Theorem 3.6 (Reduction Safety). For any closed, well-typed and linear safe computation 𝑀in F◦

∅{ 𝑀[𝑉′/𝑥], where (𝑉′, S) = tag(𝑉)

The linear safety of ˆ𝑀gives the linear safety of 𝑀and 𝑉. The linear safety of ˆ𝑁follows from Lemma A.4. By inversion on ˆ𝑀, 𝑉has type 𝐴. Case analysis on the linearity of 𝐴: Subcase ⊢𝐴: •. We have lin(𝑉) = false and tag(𝑉) = {𝑉, ∅}. By the fact that𝑉is closed and

linear safe, we have ℒ(𝑉) = ∅. Our goal follows from ℒ( ˆ𝑀) ∪∅= ℒ(𝑀) = ℒ( ˆ𝑁) ∪∅. Subcase ⊬𝐴: •. We have lin(𝑉) = true. By Lemma A.3, 𝑥appears in 𝑀exactly once.

If 𝑉= 𝑊◦for some 𝑊, then we have ℒ( ˆ𝑀) ∪∅= ℒ(𝑀) ∪ℒ(𝑉) = ℒ(𝑀[𝑉/𝑥]) = ℒ( ˆ𝑁)∪∅. Otherwise, we have ℒ( ˆ𝑀)∪{𝑉◦} = ℒ(𝑀)∪ℒ(𝑉)∪{𝑉◦} = ℒ(𝑀)∪ℒ(𝑉◦) = ℒ(𝑀[𝑉◦/𝑥]) = ℒ( ˆ𝑁) ∪∅. Case

The linear safety of ˆ𝑁directly follows from the linear safety of ˆ𝑀. We have ℒ( ˆ𝑀) ∪∅= ℒ(𝑀) = ℒ( ˆ𝑁) ∪∅.

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 34 -->

Case

L-Seq let𝑌𝑥←return 𝑉in 𝑁 S

L-Ret handle (return 𝑉)𝐸with 𝐻 S

L-Op handle E[(do ℓ𝑉)𝐸] with 𝐻 S

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

54:34 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

∅{ 𝑁[𝑉′/𝑥], where (𝑉′, S) = tag(𝑉)

The linear safety of ˆ𝑀gives the linear safety of 𝑁and 𝑉. The linear safety of ˆ𝑁follows from Lemma A.4. Suppose ⊢𝑉: 𝐴. Our goal follows from a case analysis on the linearity of 𝐴 similar to the L-App case. Case

∅{ 𝑁[𝑉′/𝑥], where (return 𝑥↦→𝑁) ∈𝐻, (𝑉′, S) = tag(𝑉)

The linear safety of ˆ𝑀gives the linear safety of 𝑉, 𝐻and 𝑁. The linear safety of ˆ𝑁follows from Lemma A.4. Suppose ⊢𝑉: 𝐴. Our goal follows from a case analysis on the linearity of 𝐴similar to the L-App case. Case

∅{ 𝑁[𝑉′/𝑝,𝑊′/𝑟], where ℓ∉bl(E), (ℓ𝑝𝑟↦→𝑁) ∈𝐻, (ℓ: 𝐴↠𝑌𝐵) ∈𝐸,

𝑊= 𝜆𝑌𝑦𝐵.handle E[(return 𝑦)𝐸] with 𝐻, (𝑉′, S1) = tag(𝑉), (𝑊′, S2) = tag(𝑊), S = S1 ∪S2

The linear safety of ˆ𝑀gives the linear safety of 𝑉, 𝐻, 𝑁and E. We need to show the linear safety of𝑊. If 𝑌= ◦, the linear safety of𝑊directly follows from the linear safety of E and 𝐻. If 𝑌= •, by the linear safety of E[(do ℓ𝑉)𝐸] we have ℒ(E) = ∅. By the linear safety of 𝐻 we have ℒ(𝐻) = ∅. Thus, ℒ(𝑊) = ∅, which gives us the linear safety of𝑊. The linear safety of ˆ𝑁follows from Lemma A.4. Then, we prove the equation. By inversion on (do ℓ𝑉)𝐸, we have ⊢𝑉: 𝐴. Suppose ⊢𝑊: 𝐵→𝑌𝐶. By the linear safety of 𝐻, we have ℒ(𝐻) = ℒ(𝑁) = ∅. By a case analysis on the linearity of 𝐴. Subcase ⊢𝐴: •. We have lin(𝑉) = false and tag(𝑉) = {𝑉, ∅}. By the fact that 𝑉is closed

and linear safe, we have ℒ(𝑉) = ∅. By a case analysis on the linearity of 𝐵→𝑌𝐶. subcase ⊢𝐵→𝑌𝐶: •. We have lin(𝑊) = false and tag(𝑊) = {𝑊, ∅}. By the fact that 𝑊

is closed and linear safe, we have ℒ(𝑊) = ∅. Our goal follows from ℒ( ˆ𝑀) ∪∅= ∅= ℒ( ˆ𝑁) ∪∅. subcase ⊢𝐵→𝑌𝐶: ◦. We have lin(𝑊) = true and tag(𝑊) = {𝑊◦, {𝑊◦}}. By Lemma A.3,

𝑟appears in 𝑁exactly once. We have ℒ( ˆ𝑀)∪{𝑊◦} = ℒ(E)∪{𝑊◦} = ℒ(𝑊◦) = ℒ( ˆ𝑁). Subcase ⊬𝐴: •. We have lin(𝑉) = true. By Lemma A.3, 𝑝appears in 𝑁exactly once. If

𝑉= 𝑉1◦for some 𝑉1, we have 𝑉◦= (𝑉, ∅). By a case analysis on the linearity of 𝐵→𝑌𝐶.

subcase ⊢𝐵→𝑌𝐶: •. We have lin(𝑊) = false and tag(𝑊) = {𝑊, ∅}. By the fact that

𝑊is closed and linear safe, we have ℒ(𝑊) = ∅. Our goal follows from ℒ( ˆ𝑀) ∪∅=

ℒ(𝑉) = ℒ( ˆ𝑁) ∪∅. subcase ⊢𝐵→𝑌𝐶: ◦. We have lin(𝑊) = true and tag(𝑊) = {𝑊◦, {𝑊◦}}. By Lemma A.3,

𝑟appears in 𝑁exactly once. We have ℒ( ˆ𝑀) ∪{𝑊◦} = ℒ(𝑉) ∪ℒ(E) ∪{𝑊◦} = ℒ(𝑉) ∪ℒ(𝑊◦) = ℒ( ˆ𝑁). Otherwise, we have 𝑉◦= (𝑉◦, {𝑉◦}). By a case analysis on the linearity of 𝐵→𝑌𝐶. subcase ⊢𝐵→𝑌𝐶: •. We have lin(𝑊) = false and tag(𝑊) = {𝑊, ∅}. By the fact that

𝑊is closed and linear safe, we have ℒ(𝑊) = ∅. Our goal follows from ℒ( ˆ𝑀) ∪∅=

ℒ(𝑉) ∪{𝑉◦} = ℒ(𝑉◦) = ℒ( ˆ𝑁) ∪∅. subcase ⊢𝐵→𝑌𝐶: ◦. We have lin(𝑊) = true and tag(𝑊) = {𝑊◦, {𝑊◦}}. By Lemma A.3,

𝑟appears in 𝑁exactly once. We have ℒ( ˆ𝑀) ∪{𝑊◦,𝑉◦} = ℒ(𝑉) ∪ℒ(E) ∪{𝑊◦,𝑉◦} = ℒ(𝑉◦) ∪ℒ(𝑊◦) = ℒ( ˆ𝑁).


<!-- page 35 -->

Soundly Handling Linearity 54:35

Case

L-Remove F [𝑉◦] ∅ {𝑉◦}{ F [𝑉]

The linear safety of ˆ𝑁directly follows from the linear safety of ˆ𝑀. We have ℒ( ˆ𝑀) ∪∅= ℒ(F ) ∪ℒ(𝑉◦) = ℒ(F ) ∪ℒ(𝑉) ∪{𝑉◦} = ℒ( ˆ𝑁) ∪{𝑉◦}. Case

L-Lift E[𝑀] S T{ E[𝑁], if 𝑀 S T{ 𝑁

B FULL SPECIFICATION OF Q◦

eff In this section, we give the full syntax, typing rules, type inference, and constraint solving algorithm of Q◦

eff in Section 5.

B.1 Full Syntax The full syntax of Q◦

eff is given in Figure 13. Note that we introduce the syntactic category of concrete rows to simplify the presentation of the constraint solving algorithm.

Value types 𝐴, 𝐵::= 𝛼| 𝐴→𝑌𝐶 Computation types 𝐶, 𝐷::= 𝐴! 𝐸 Handler types 𝐹::= 𝐶⇒𝐷 Effect types 𝐸::= {𝑅} Concrete row types CRow ∋𝐾::= · | ℓ: 𝐴↠𝑌𝐵;𝐾 Row types Row ∋𝑅::= 𝜇| 𝐾| 𝐾;𝑅 Linearity types 𝑌::= 𝜙| • | ◦ Types 𝜏::= 𝐴| 𝑅| 𝑌 Predicates Pred ∋𝜋::= 𝜏1 ⪯𝜏2 | 𝑅1 ⩽𝑅2 | 𝑅⊥L Qualified types 𝜌::= 𝐴| 𝜋⇒𝜌 Type schemes TySch ∋𝜎::= 𝜌| ∀𝛼.𝜎 Label sets L ::= ∅| {ℓ} ⊎L Type contexts Env ∋Γ ::= · | Γ,𝑥: 𝜎 Predicate sets PSet ∋𝑃::= · | 𝑃, 𝜋 Values 𝑉,𝑊::= 𝑥| 𝜆𝑥.𝑀 Computations 𝑀, 𝑁::= 𝑉𝑊| return 𝑉| do ℓ𝑉| let 𝑥= 𝑉in 𝑀

| let 𝑥←𝑀in 𝑁| handle 𝑀with 𝐻 Handlers 𝐻::= {return 𝑥↦→𝑀} | {ℓ𝑝𝑟↦→𝑀} ⊎𝐻

Fig. 13. The Syntax of Q◦

B.2 Full Typing Rules The full syntax-directed typing rules for Q◦

The linear safety of ˆ𝑀gives the linear safety of E and 𝑀. By IH, we have the linear safety of 𝑁. The linear safety of ˆ𝑁follows from the linear safety of E and 𝑁. By IH, we have ℒ(𝑀) ∪S = ℒ(𝑁) ∪T. Our goal follows from ℒ( ˆ𝑀) ∪S = ℒ(E) ∪ℒ(𝑀) ∪S = ℒ(E) ∪ℒ(𝑁) ∪T = ℒ( ˆ𝑁) ∪T.

□

eff

eff is given in Figure 14. Note that in the qualified effect system of Q◦

eff, we only have subtyping between row types and use them in Q-Do, Q-Seq, Q-Handle, and Q-Handler. This is different from other type systems with general subtyping,

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 36 -->

where the subtyping relation is used everywhere. For example, in the Q-App rule, we require the argument type to be equal to the parameter type of the function, instead of requiring a subtyping relation. Having a full subtyping relation between any types does not help improve the accuracy of tracking control-flow linearity; subtyping between effect rows is enough.

𝑃| Γ ⊢𝑉: 𝐴 𝑃| Γ ⊢𝑀: 𝐶 𝑃| Γ ⊢𝐻: 𝐶⇒𝐷

Q-Abs

Q-Var

𝑃| Γ,𝑥: 𝐴⊢𝑀: 𝐶

𝑃⊢Γ ⪯• (𝑃⇒𝐴) ⊑𝜎

𝑃⊢Γ ⪯𝑌

𝑃| Γ ⊢𝜆𝑥.𝑀: 𝐴→𝑌𝐶

𝑃| Γ,𝑥: 𝜎⊢𝑥: 𝐴

Q-Let

𝑄| Γ1, Γ ⊢𝑉: 𝐴 𝜎= gen((Γ1, Γ),𝑄⇒𝐴) 𝑃| Γ2, Γ,𝑥: 𝜎⊢𝑀: 𝐶 𝑃⊢Γ ⪯•

𝑃| Γ1, Γ2, Γ ⊢let 𝑥= 𝑉in 𝑀: 𝐶

Q-Do

Q-Seq

𝑃| Γ ⊢𝑉: 𝐴ℓ 𝑃⊢{ℓ: 𝐴ℓ↠𝑌𝐵ℓ} ⩽𝑅

𝑃| Γ ⊢do ℓ𝑉: 𝐵ℓ! {𝑅}

Q-Handle

𝑃| Γ1, Γ ⊢𝐻: 𝐴! {𝑅1} ⇒𝐷

𝑃| Γ2, Γ ⊢𝑀: 𝐴! {𝑅} 𝑃⊢Γ ⪯• 𝑃⊢𝑅⩽𝑅1 𝑃| Γ1, Γ2, Γ ⊢handle 𝑀with 𝐻: 𝐷

Fig. 14. Syntax-directed Typing Rules for Q◦

B.3 Type Inference Algorithm

The full type inference of Q◦

U-Type

unify(𝜏∼𝜏′) = 𝜃

𝜏∼𝜏′ : 𝜃

Figure 16 gives unification function unify(𝑈) which takes a set of unification predicates and returns the principal unifiers of them. It is relatively standard [Martelli and Montanari 1982]. The arrow ⇀indicates a meta function that might fail. Following Leijen [2008] we explicitly indicate

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

54:36 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

Q-App

𝑃| Γ1, Γ ⊢𝑉: 𝐴→𝑌𝐶 𝑃| Γ2, Γ ⊢𝑊: 𝐴 𝑃⊢Γ ⪯•

𝑃| Γ1, Γ2, Γ ⊢𝑉𝑊: 𝐶

Q-Return

𝑃| Γ ⊢𝑉: 𝐴

𝑃| Γ ⊢return 𝑉: 𝐴! {𝑅}

𝑃| Γ1, Γ ⊢𝑀: 𝐴! {𝑅1} 𝑃| Γ2, Γ,𝑥: 𝐴⊢𝑁: 𝐵! {𝑅2} 𝑃⊢𝑅1 ⩽𝑅 𝑃⊢𝑅2 ⩽𝑅 𝑃⊢Γ2 ⪯𝑅1 𝑃⊢Γ ⪯•

𝑃| Γ1, Γ2, Γ ⊢let 𝑥←𝑀in 𝑁: 𝐵! {𝑅}

Q-Handler

𝐻= {return 𝑥↦→𝑀} ⊎{ℓ𝑖𝑝𝑖𝑟𝑖↦→𝑁𝑖}𝑖 𝐶= 𝐴! {(ℓ𝑖: 𝐴𝑖↠𝑌𝑖𝐵𝑖)𝑖;𝑅1} 𝐷= 𝐵! {𝑅2} 𝑃| Γ,𝑥: 𝐴⊢𝑀: 𝐷 [𝑃| Γ, 𝑝𝑖: 𝐴𝑖,𝑟𝑖: 𝐵𝑖→𝑌𝑖𝐷⊢𝑁𝑖: 𝐷]𝑖 𝑃⊢Γ ⪯• 𝑃⊢𝑅1 ⩽𝑅2 𝑃⊢𝑅1 ⊥{ℓ𝑖}𝑖 𝑃| Γ ⊢𝐻: 𝐶⇒𝐷

eff

eff is given in Figure 15. It uses the unification relations 𝜏∼𝜏′ : 𝜃which states that 𝜃is the principal unifier of types 𝜏and 𝜏′, and 𝐶∼𝐶′ : 𝜃which states that 𝜃is the principal unifier for computation types 𝐶and 𝐶′. The unification relations are directly defined by the unification function.

U-Comp

unify(𝐶∼𝐶′) = 𝜃

𝐶∼𝐶′ : 𝜃


<!-- page 37 -->

Soundly Handling Linearity 54:37

the successful return of a result by return. The auxiliary functions urow and ulin are given and explained in . The unification predicates and predicate sets are defined as follows.

Unification predicates UPred ∋𝑢::= 𝜏∼𝜏′ | 𝐶∼𝐶′

Unification sets USet ∋𝑈::= 𝑈,𝑢

Note that it is possible to postpone the solving of unification constraints to the constraint solving algorithm. We opt for this mixed style presentation for Q◦

eff in order to keep close to the original presentation of qualified types [Jones 1994], and to keep the constraint set cleaner.

B.4 Constraint Solving Algorithm

The constraint solving algorithm of Q◦

eff is given in Figure 17. The function ulin unifies two linearity types. The function ulab unifies the signatures of shared labels of two concrete rows. The function urow wraps ulab. The function trlin computes the transitive closure of linearity constraints.

The function srow(𝜃, 𝑃,𝑄) solves row constraints. It takes the current substitution 𝜃and the currently solved predicate set 𝑃, and solves the predicates in 𝑄. The basic idea is to transform the row subtyping predicates to forms of 𝜇⩽𝑅and row lacking predicates to forms of 𝜇⊥L, which we call solved forms. It does a case analysis on the first predicate in 𝑄. For instance, consider the most complicated case 𝐾1 ; 𝜇1 ⩽𝐾2 ; 𝜇2. It first unifies the common labels of 𝐾1 and 𝐾2. When 𝐾1 is a subset of 𝐾2, we can directly transform it to the solved form; otherwise, we allocate a fresh row variable to substitute 𝜇2 and transform it to the solved form. Note that we also need to move all previously solved predicates to the unsolved predicate set, because the row variable 𝜇2 is substituted, which might turn some predicates in solved forms to unsolved forms.

The main function solve sequentially solves row constraints using srow and linearity constraints using trlin. Note that we use factorise to factorise the output predicate set to transform the linearity constraints into the simplest form (i.e., only between value type variables, row variables, and linearity), which is suitable for computing the transitive closure using trlin.

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 38 -->

Γ ⊢𝑉: 𝐴⊣𝜃, 𝑃, Σ Γ ⊢𝑀: 𝐶⊣𝜃, 𝑃, Σ Γ ⊢𝐻: 𝐶⇒𝐷⊣𝜃, 𝑃, Σ

Q-LetW

Q-VarW

(𝑥: ∀𝛼.𝑃⇒𝐴) ∈Γ 𝛽fresh 𝜃= [𝛽/𝛼]

Γ ⊢𝑥: 𝜃𝐴⊣𝜃,𝜃𝑃, {𝑥}

Q-AbsW

𝛼,𝜙fresh Γ,𝑥: 𝛼⊢𝑀: 𝐶⊣𝜃, 𝑃, Σ 𝑄= leq(𝜃Γ|Σ,𝜙) ∪un(𝜃(𝑥: 𝛼)|Σc)

Γ ⊢𝜆𝑥.𝑀: 𝜃𝛼→𝜙𝐶⊣𝜃, 𝑃∪𝑄, Σ\𝑥

Q-SeqW

Γ ⊢𝑀: 𝐴! {𝑅1} ⊣𝜃1, 𝑃1, Σ1 𝜃1Γ,𝑥: 𝐴⊢𝑁: 𝐵! {𝑅2} ⊣𝜃2, 𝑃2, Σ2 𝜇fresh 𝑄= un(𝜃2𝜃1Γ|Σ1∩Σ2) ∪un(𝜃2(𝑥: 𝐴)|Σc

Γ ⊢let 𝑥←𝑀in 𝑁: 𝐵! 𝜇⊣𝜃2𝜃1,𝜃2𝑃1 ∪𝑃2 ∪𝑄, Σ1 ∪(Σ2\𝑥)

Q-ReturnW

Γ ⊢𝑉: 𝐴⊣𝜃, 𝑃, Σ 𝜇fresh

Γ ⊢return 𝑉: 𝐴! {𝜇} ⊣𝜃, 𝑃, Σ

Q-HandleW

Γ ⊢𝐻: 𝐴! {𝑅1} ⇒𝐷⊣𝜃1, 𝑃1, Σ1 𝜃1Γ ⊢𝑀: 𝐴′ ! {𝑅} ⊣𝜃2, 𝑃2, Σ2 𝜃2𝐴∼𝐴′ : 𝜃3 𝑃= 𝜃3(𝜃2𝑃1 ∪𝑃2) 𝑄= sub(𝜃3𝑅,𝜃3𝜃2𝑅1) ∪un(𝜃3𝜃2𝜃1Γ|Σ1∩Σ2)

Γ ⊢handle 𝑀with 𝐻: 𝜃3𝜃2𝐷⊣𝜃3𝜃2𝜃1, 𝑃∪𝑄, Σ1 ∪Σ2

Q-HandlerW

𝛼,𝜙𝑖, 𝜇fresh Γ,𝑥: 𝛼⊢𝑀: 𝐷⊣𝜃0, 𝑃0, Σ0 [𝜃𝑖−1(Γ, 𝑝𝑖: 𝐴ℓ𝑖,𝑟𝑖: 𝐵ℓ𝑖→𝜙𝑖𝐷) ⊢𝑁𝑖: 𝐷𝑖⊣𝜃′

𝑖=1 𝐶= 𝜃𝑛(𝛼! {(ℓ𝑖: 𝐴ℓ𝑖↠𝜙𝑖𝐵ℓ𝑖)𝑖; 𝜇}) 𝐵! {𝑅} = 𝜃𝑛𝐷 Σ = (Σ0\{𝑥}) ∪(∪𝑛

𝑖=1(Σ𝑖\{𝑝𝑖,𝑟𝑖})) 𝑃= (∪𝑛

Γ ⊢{return 𝑥↦→𝑀} ⊎{ℓ𝑖𝑝𝑖𝑟𝑖↦→𝑁𝑖}𝑛

leq(Γ,𝜏) = factorise(Γ ⪯𝜏)

un(Γ) = leq(Γ, •)

Fig. 15. Type Inference of Q◦

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

54:38 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

Γ ⊢𝑉: 𝐴⊣𝜃1, 𝑃1, Σ1 𝜎= gen(𝜃1Γ, 𝑃1 ⇒𝐴) 𝜃1Γ,𝑥: 𝜎⊢𝑀: 𝐶⊣𝜃2, 𝑃2, Σ2 𝑄= un(𝜃2𝜃1Γ|Σ1∩Σ2) ∪un(𝜃2(𝑥: 𝜎)|Σc

2)

Γ ⊢let 𝑥= 𝑉in 𝑀: 𝐶⊣𝜃2𝜃1, 𝑃2 ∪𝑄, Σ1 ∪(Σ2\𝑥)

Q-AppW

Γ ⊢𝑉: 𝐴⊣𝜃1, 𝑃1, Σ1 𝜃1Γ ⊢𝑊: 𝐵⊣𝜃2, 𝑃2, Σ2 𝛼, 𝜇,𝜙fresh 𝜃2𝐴∼(𝐵→𝜙𝛼! 𝜇) : 𝜃3 𝑃= 𝜃3(𝜃2𝑃1 ∪𝑃2) 𝑄= un(𝜃3𝜃2𝜃1Γ|Σ1∩Σ2)

Γ ⊢𝑉𝑊: 𝜃3(𝛼! 𝜇) ⊣𝜃3𝜃2𝜃1, 𝑃∪𝑄, Σ1 ∪Σ2

2) ∪leq(𝜃2𝜃1Γ|Σ2,𝜃2𝑅1) ∪sub(𝜃2𝑅1, 𝜇) ∪sub(𝑅2, 𝜇)

Q-DoW

Γ ⊢𝑉: 𝐴⊣𝜃1, 𝑃, Σ 𝐴∼𝐴ℓ: 𝜃2 𝜇,𝜙fresh 𝑄= sub((ℓ: 𝐴ℓ↠𝜙𝐵ℓ), 𝜇)

Γ ⊢do ℓ𝑉: 𝐵ℓ! {𝜇} ⊣𝜃2𝜃1,𝜃2𝑃∪𝑄, Σ

𝑖𝜃𝑖−1]𝑛

𝑖, 𝑃𝑖, Σ𝑖 𝐷𝑖∼𝜃′

𝑖𝜃𝑖−1𝐷: 𝜃′′

𝑖 𝜃𝑖= 𝜃′′

𝑖𝜃′

𝑖=0𝜃𝑛𝑃𝑖) ∪un(𝜃𝑛Γ|Σ) ∪sub(𝜇, 𝑅) ∪lack(𝜇, {ℓ𝑖}𝑖) 𝑄= un(𝜃𝑛(𝑥: 𝛼)|Σc

0) ∪(∪𝑛 𝑖=1un(𝜃𝑛(𝑝𝑖: 𝐴ℓ𝑖,𝑟𝑖: 𝐵ℓ𝑖→𝜙𝑖𝐷)))

𝑖=1 : 𝐶⇒𝜃𝑛𝐷⊣𝜃𝑛, 𝑃∪𝑄, Σ

sub(𝑅1, 𝑅2) = factorise(𝑅1 ⩽𝑅2)

lack(𝑅, L) = factorise(𝑅⊥L)

eff


<!-- page 39 -->

Soundly Handling Linearity 54:39

unify : USet ⇀Subst

unify(·) = return𝜄

unify(𝛼∼𝛼,𝑈) = unify(𝑈)

unify(𝛼∼𝜏,𝑈) =

assert 𝛼∉ftv(𝜏) let 𝜃= [𝜏/𝛼] unify(𝜃𝑈)𝜃

unify(𝜏∼𝛼,𝑈) =

unify(𝛼∼𝜏,𝑈)

unify(𝐴! {𝑅} ∼𝐴′ ! {𝑅′},𝑈) =

unify(𝐴∼𝐴′, 𝑅∼𝑅′,𝑈)

unify((𝐴→𝑌𝐶) ∼(𝐴′ →𝑌′ 𝐶′),𝑈) =

unify(𝐴∼𝐴′,𝐶∼𝐶′,𝑌∼𝑌′,𝑈)

unify(𝑌∼𝑌′,𝑈) =

let 𝜃= ulin(𝑌,𝑌′) unify(𝜃𝑈)𝜃

Fig. 16. Unification of Q◦

unify(𝐾1 ∼𝐾2,𝑈) =

let (𝐾′

1, 𝐾′ 2,𝜃) = urow(𝐾1, 𝐾2) assert set(𝐾′

1) = set(𝐾′ 2) unify(𝜃𝑈)𝜃

unify(𝐾1 ; 𝜇1 ∼𝐾2,𝑈) =

let (𝐾′

1, 𝐾′ 2,𝜃) = urow(𝐾1, 𝐾2) assert set(𝐾′

1) ⊆set(𝐾2) assume fresh 𝜇 let 𝜃′ = [((𝐾′

2\𝐾′ 1) ; 𝜇)/𝜇1] unify(𝜃′𝜃𝑈)𝜃′𝜃

unify(𝐾2 ∼𝐾1 ; 𝜇1,𝑈) =

unify(𝐾1 ; 𝜇1 ∼𝐾2,𝑈)

unify(𝐾1 ; 𝜇1 ∼𝐾2 ; 𝜇2,𝑈) =

let (𝐾′

1, 𝐾′ 2,𝜃) = urow(𝐾1, 𝐾2) assume fresh 𝜇 let 𝜃′ = [((𝐾′

2\𝐾′ 1) ; 𝜇)/𝜇1, ((𝐾′

1\𝐾′ 2) ; 𝜇)/𝜇2] unify(𝜃′𝜃𝑈)𝜃′𝜃

eff

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 40 -->

srow : (Subst × PSet × PSet) ⇀(Subst × PSet)

srow(𝜃, 𝑃, ·) = return (𝜃, 𝑃)

srow(𝜃, 𝑃, (𝜏1 ⪯𝜏2,𝑄)) =

srow(𝜃, (𝑃,𝜏1 ⪯𝜏2),𝑄)

srow(𝜃, 𝑃, (𝐾1 ⩽𝐾2,𝑄)) =

1, 𝐾′ 2,𝜃′) = urow(𝐾1, 𝐾2) assert set(𝐾′

let (𝐾′

1) ⊆set(𝐾′ 2) srow(𝜃′𝜃,𝜃′𝑃,𝜃′𝑄)

srow(𝜃, 𝑃, (𝐾1 ; 𝜇⩽𝐾2 ; 𝜇,𝑄)) =

1, 𝐾′ 2,𝜃′) = urow(𝐾1, 𝐾2) assert set(𝐾′

let (𝐾′

1) ⊆set(𝐾′ 2) srow(𝜃′𝜃,𝜃′𝑃,𝜃′𝑄)

srow(𝜃, 𝑃, (𝐾1 ; 𝜇⩽𝐾2,𝑄)) =

1, 𝐾′ 2,𝜃′) = urow(𝐾1, 𝐾2) assert set(𝐾′

let (𝐾′

1) ⊆set(𝐾′ 2) srow(𝜃′𝜃, (𝜃′𝑃, 𝜇⩽(𝐾′

2\𝐾′ 1)),𝜃′𝑄)

srow(𝜃, 𝑃, (𝐾1 ⩽𝐾2 ; 𝜇2,𝑄)) =

1, 𝐾′ 2,𝜃′) = urow(𝐾1, 𝐾2) if set(𝐾′

let (𝐾′

1) ⊆set(𝐾′ 2) then srow(𝜃′𝜃,𝜃′𝑃,𝜃′𝑄) else assume fresh 𝜇

let 𝜃′′ = [((𝐾′

1\𝐾′ 2) ; 𝜇)/𝜇2]𝜃′

srow(𝜃′′𝜃, ·,𝜃′′(𝑄, 𝑃))

srow(𝜃, 𝑃, (𝐾1 ; 𝜇1 ⩽𝐾2 ; 𝜇2,𝑄)) =

1, 𝐾′ 2,𝜃′) = urow(𝐾1, 𝐾2) if set(𝐾′

let (𝐾′

1) ⊆set(𝐾′ 2) then srow(𝜃′𝜃, (𝜃′𝑃, 𝜇1 ⩽(𝐾′

2\𝐾′ 1) ; 𝜇2),𝜃′𝑄) else assume fresh 𝜇

let 𝜃′′ = [((𝐾′

1\𝐾′ 2) ; 𝜇)/𝜇2]𝜃′

srow(𝜃′′𝜃, 𝜇1 ⩽(𝐾′

2\𝐾′ 1) ; 𝜇,𝜃′′(𝑄, 𝑃))

srow(𝜃, 𝑃, (𝐾⊥L,𝑄)) =

assert dom(𝐾) ∩L = ∅ srow(𝜃, 𝑃,𝑄)

srow(𝜃, 𝑃, (𝐾; 𝜇⊥L,𝑄)) =

assert dom(𝐾) ∩L = ∅ srow(𝜃, (𝑃, 𝜇⊥L),𝑄)

Fig. 17. Constraint Solving of Q◦

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

54:40 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

urow : (CRow × CRow) ⇀(CRow × CRow × Subst) urow(𝐾, 𝐾′) =

let 𝜃= ulab(𝐾, 𝐾′) return (𝜃𝐾,𝜃𝐾′,𝜃)

ulab : (CRow × CRow) ⇀Subst ulab(·, 𝐾) = return 𝜄 ulab(𝐾, ·) = return 𝜄 ulab((ℓ: 𝑌1 ;𝐾1), (ℓ: 𝑌2 ;𝐾2)) =

let 𝜃= ulin(𝑌1,𝑌1) let 𝜃′ = ulab(𝜃𝐾1,𝜃𝐾2) return 𝜃′𝜃 ulab((ℓ: 𝑌;𝐾1), 𝐾2) = ulab(𝐾1, 𝐾2) ulab(𝐾1, (ℓ: 𝑌;𝐾2)) = ulab(𝐾1, 𝐾2)

ulin : (Lin × Lin) ⇀Subst ulin(𝑌,𝑌) = return 𝜄 ulin(•, ◦) = fail ulin(◦, •) = fail ulin(𝜙,𝑌) = return [𝑌/𝜙] ulin(𝑌,𝜙) = return [𝑌/𝜙]

trlin : (PSet × PSet) →PSet trlin(𝑃, ·) = 𝑃 trlin(𝑃, (𝑅1 ⩽𝑅2,𝑄)) = trlin(𝑃,𝑄) trlin(𝑃, (𝜏1 ⪯𝜏2,𝑄)) = trlin(𝑃∪𝑃′′,𝑄)

where

𝑃′ = 𝑃∪{𝜏1 ⪯𝜏1,𝜏2 ⪯𝜏2} 𝑃′′ = {𝜏′

1 ⪯𝜏′ 2 | {𝜏′ 1 ⪯𝜏1,𝜏2 ⪯𝜏′ 2} ⊆𝑃′}

solve : PSet ⇀(Subst × PSet) solve(𝑃) =

let (𝜃,𝑄) = srow(𝜄, ·, 𝑃) let 𝑄′ = trlin(·, factorise(𝑄)) assert (◦⪯•) ∉𝑄′

return (𝜃,𝑄)

eff


<!-- page 41 -->

Soundly Handling Linearity 54:41

C PROOFS OF Q◦

eff In this section, we prove the theorems in Section 5.

C.1 Correctness of Factorisation

We first prove some useful properties of the entailment relations.

Lemma C.2 (Inverse closure property). If 𝑃⊢𝜃(𝜎⪯𝜏), then there exists 𝑃′ ⊢𝜎⪯𝜏such that 𝑃⊢𝜃𝑃′.

Proof. By induction on the entailment relations. Case

P-Quantifier

𝑃⊢[𝜏′/𝛼]𝜃(𝜎⪯𝜏) (1) for some 𝜏′

𝑃⊢𝜃((∀𝛼.𝜎) ⪯𝜏)

P-Qualifier

𝑃⊢𝜃𝜋(1) 𝑃⊢𝜃(𝜌⪯𝜏) (2)

𝑃⊢𝜃((𝜋⇒𝜌) ⪯𝜏)

By the IH on (1), there exists 𝑃1 ⊢𝜋such that 𝑃⊢𝜃𝑃1. By the IH on (2), there exists 𝑃2 ⊢𝜌⪯𝜏 such that 𝑃⊢𝜃𝑃2. By P-Qualifier, we have 𝑃1 ∪𝑃2 ⊢(𝜋⇒𝜌) ⪯𝜏. By P-PredSet, we have 𝑃⊢𝜃(𝑃1 ∪𝑃2). Case For all other cases of 𝑃⊢𝜃𝜋, just take 𝑃′ = 𝜋.

Theorem 5.3 (Correctness of factorisation). If factorise(𝑃) = 𝑄, then 𝑄⊢𝑃and 𝑃⊢𝑄. If factorise(Γ ⪯𝜏) = 𝑄, then 𝑄⊢Γ ⪯𝜏and for any 𝑃⊢Γ ⪯𝜏, there exists 𝜃such that 𝑃⊢𝜃𝑄.

Theorem C.1 (Properties of the entailment relation). The entailment relation between predicate sets satisfies the following properties:

• Monotonicity. If 𝑄⊆𝑃, then 𝑃⊢𝑄. • Transitivity. If 𝑃1 ⊢𝑃2 and 𝑃2 ⊢𝑃3, then 𝑃1 ⊢𝑃3. • Closure property. If 𝑃⊢𝑄, then 𝜃𝑃⊢𝜃𝑄. • Weakening. If 𝑃⊢𝑄, then 𝑃, 𝑃′ ⊢𝑄. Proof. Monotonicity. Directly follows from P-Subsume and P-PredSet. Transitivity. By P-PredSet, we only need to prove that if 𝑃1 ⊢𝑃2 and 𝑃2 ⊢𝜋, then 𝑃1 ⊢𝜋. By straightforward induction on 𝑃2 ⊢𝜋. Closure property. By P-PredSet, we only need to prove that if 𝑃⊢𝜋then𝜃𝑃⊢𝜃𝜋. By straightforward induction on 𝑃⊢𝜋. Weakening. By P-PredSet, we only need to prove that if 𝑃⊢𝜋then 𝑃, 𝑃′ ⊢𝜋. By straightforward induction on 𝑃⊢𝜋.

□

Assume that 𝛼∉dom(𝜃) and 𝛼∉ftv(𝜏) without loss of generality. We can commute

[𝜏′/𝛼] and 𝜃in (1). By the IH on (1), there exists 𝑃′ ⊢[𝜏′/𝛼](𝜎⪯𝜏) such that 𝑃⊢𝜃𝑃′. By P-Quantifier, we have 𝑃′ ⊢(∀𝛼.𝜎) ⪯𝜏. Case

□

Proof. The first part of the theorem is kind of obvious because factorise(𝑃) is almost directly defined from the entailment rules in Figure 10. We prove the auxiliary lemma that if factorise(𝜋) = 𝑄,

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 42 -->

54:42 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

then 𝑄⊢𝜋and 𝜋⊢𝑄. Both directions follow from straightforward induction on the definition of factorise. Note that in the proof of 𝜋⊢𝑄, we apply the bottom-up direction of the two-way rules P-Fun and P-Row. Then, given factorise(𝑃) = Ð

𝜋∈𝑃factorise(𝜋), by the lemma we have factorise(𝜋) ⊢𝜋for all 𝜋∈𝑃, which then give Ð

𝜋∈𝑃factorise(𝜋) ⊢𝑃by P-PredSet and the weakening of Theorem C.1. We also have that 𝜋⊢factorise(𝜋) for all 𝜋∈𝑃, which then give 𝑃⊢Ð

𝜋∈𝑃factorise(𝜋) by P-PredSet and the weakening of Theorem C.1. For the second part of the theorem, we prove the auxiliary lemma that if factorise(𝜎⪯𝜏) = 𝑄, then 𝑄⊢𝜎⪯𝜏and for any 𝑃⊢𝜎⪯𝜏, there exists 𝜃such that 𝑃⊢𝜃𝑄. The 𝑄⊢𝜎⪯𝜏follows from straightforward induction on the definition of factorise. The other direction is more involved. We proceed by induction on the definition of factorise. Case

factorise((∀𝛼.𝜎) ⪯𝜏) = factorise([𝛽/𝛼]𝜎⪯𝜏) (1) for some fresh 𝛽 Suppose factorise((∀𝛼.𝜎) ⪯𝜏) = 𝑄. We want to show that for any 𝑃⊢(∀𝛼.𝜎) ⪯𝜏(2), there exists 𝜃such that 𝑃⊢𝜃𝑄. By (2) and P-Quantifier, there exists 𝜃1 = [𝜏′/𝛼] such that 𝑃⊢𝜃1𝜎⪯𝜏. Let 𝜃2 = [𝜏′/𝛽]. We have 𝑃⊢𝜃2[𝛽/𝛼]𝜎⪯𝜏. By Lemma C.2, there exists 𝑃′

such that 𝑃′ ⊢[𝛽/𝛼]𝜎⪯𝜏(3) and 𝑃⊢𝜃2𝑃′. By (3) and the IH on (1), there exists 𝜃3 such that 𝑃′ ⊢𝜃3𝑄. Then, by the closure property and transitivity of Theorem C.1, we have 𝑃⊢𝜃2𝜃3𝑄. Case

factorise((𝜋⇒𝜎) ⪯𝜏) = factorise(𝜋) (1) ∪factorise(𝜎⪯𝜏) (2) Suppose factorise(𝜋) = 𝑄1 and factorise(𝜎⪯𝜏) = 𝑄2. For any 𝑃⊢(𝜋⇒𝜎) ⪯𝜏, by P-Qualifier, we have 𝑃⊢𝜋and 𝑃⊢𝜎⪯𝜏. By the IH on (1), there exists 𝜃1 such that 𝑃⊢𝜃1𝑄1. By the IH on (2), there exists 𝜃2 such that 𝑃⊢𝜃2𝑄2. Note that dom(𝜃1) ∩dom(𝜃2) = ∅. Thus, we have 𝑃⊢𝜃1𝜃2(𝑄1 ∪𝑄2). Case

factorise(𝜋) = 𝑄 By the first part of the theorem which has been proved, we have 𝜋⊢𝑄. For any 𝑃⊢𝜋, by the transitivity of Theorem C.1, we have 𝑃⊢𝑄. With this lemma, our goal follows from a similar analysis to the proof of the first part of the theorem since P-Context and P-PredSet are both conjunction rules.

□

C.2 Principal Unifier

We have the following lemmas for the unification function in Figure 16 and its auxiliary functions.

1 = (𝐾1|dom(𝐾1)∩dom(𝐾2)) and 𝐾′

Lemma C.3 (Principal auxiliary unifiers). Given 𝐾1 and 𝐾2, let 𝐾′

2 = (𝐾2|dom(𝐾1)∩dom(𝐾2)). If ulab(𝐾1, 𝐾2) = 𝜃, then for any 𝜃′𝐾′ 1 = 𝜃′𝐾′ 2, there exists 𝜃′′ such that 𝜃′ = 𝜃′′𝜃; if it fails, then 𝐾′

1 and 𝐾′ 2 cannot be unified.

Proof. By straightforward induction on the definition of urow, ulab and ulin. □

Lemma C.4 (Principal unifiers). If 𝐴∼𝐵: 𝜃, then for any 𝜃′𝐴= 𝜃′𝐵, there exists 𝜃′′ such that 𝜃′ = 𝜃′′𝜃; if it fails, then 𝐴and 𝐵cannot be unified. The same applies to computation types.

Proof. By straightforward induction on the definition of unify(𝑈). □

C.3 Soundness and Completeness of Type Inference

We prove the soundness and completeness of type inference as well as auxiliary lemmas.

Lemma C.5 (Closure property of typing). If 𝑃| Γ ⊢𝑉: 𝐴, then 𝜃𝑃| 𝜃Γ ⊢𝑉: 𝜃𝐴. The same applies to computation and handler typing.

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 43 -->

Soundly Handling Linearity 54:43

Proof. By the closure property of Theorem C.1 and straightforward induction on the typing derivations. □

Lemma C.6 (Weakening of predicates). If 𝑃| Γ ⊢𝑉: 𝐴, then 𝑃, 𝑃′ | Γ ⊢𝑉: 𝐴. The same applies to computation and handler typing.

Proof. By the weakening property of Theorem C.1 and straightforward induction on the typing derivations. □

Lemma C.7 (Extra is unlimited). If 𝑃| Γ ⊢𝑉: 𝐴, then 𝑃′ | Γ,𝑥: 𝜎⊢𝑉: 𝐴for any 𝑃′ ⊢𝑃and 𝑃′ ⊢𝜎⪯•. The same applies to computation and handler typing.

Proof. By straightforward induction on the typing derivations. □

Theorem 5.1 (Soundness). If Γ ⊢𝑉: 𝐴⊣𝜃, 𝑃, Σ, then 𝑃| 𝜃Γ|Σ ⊢𝑉: 𝐴. The same applies to computation and handler typing.

Proof. By mutual induction on the type inference derivations Γ ⊢𝑉: 𝐴⊣𝜃, 𝑃, Σ, Γ ⊢𝑀: 𝐶⊣ 𝜃, 𝑃, Σ, and Γ ⊢𝐻: 𝐶⇒𝐷⊣𝜃, 𝑃, Σ.

Case

Q-VarW

(𝑥: ∀𝛼.𝑃⇒𝐴) ∈Γ 𝛽fresh 𝜃= [𝛽/𝛼] (1)

Γ ⊢𝑥: 𝜃𝐴⊣𝜃,𝜃𝑃, {𝑥}

By (1), we have 𝜃𝑃⇒𝜃𝐴⊑𝜃(∀𝛼.𝑃⇒𝐴). Our goal then follows from Q-Var. Case

Q-LetW

Γ ⊢𝑉: 𝐴⊣𝜃1, 𝑃1, Σ1 (1) 𝜎= gen(𝜃1Γ, 𝑃1 ⇒𝐴) 𝜃1Γ,𝑥: 𝜎⊢𝑀: 𝐶⊣𝜃2, 𝑃2, Σ2 (2) 𝑄= un(𝜃2𝜃1Γ|Σ1∩Σ2) ∪un(𝜃2(𝑥: 𝜎)|Σc

2)

Γ ⊢let 𝑥= 𝑉in 𝑀: 𝐶⊣𝜃2𝜃1, 𝑃2 ∪𝑄, Σ1 ∪(Σ2\𝑥)

By the IH on (1), we have 𝑃1 | 𝜃1Γ|Σ1 ⊢𝑉: 𝐴. By Lemma C.5, we have 𝜃2𝑃1 | 𝜃2𝜃1Γ ⊢ 𝑉: 𝜃2𝐴(3). By the IH on (2), we have 𝑃2 | 𝜃2(𝜃1Γ,𝑥: 𝜎)|Σ2 ⊢𝑀: 𝜃2𝐶(4). Let 𝜎′ =

gen(𝜃2𝜃1Γ,𝜃2𝑃1 ⇒𝜃2𝐴). Notice that 𝜃2 is generated by the type inference judgement (2), which cannot substitute any variables bound by 𝜎(i.e., variables in ftv(𝑃1 ⇒𝐴)\ftv(𝜃1Γ)). Thus, we have 𝜃2𝜎= 𝜎′. Let Σ′

2 = Σ2\𝑥, Γ1 = (𝜃2𝜃1Γ)|Σ1\Σ′ 2, Γ2 = (𝜃2𝜃1Γ)|Σ′ 2\Σ1, Γ′ = (𝜃2𝜃1Γ)|Σ1∩Σ′

2. By (3) and (4), we have 𝜃2𝑃1 | Γ1, Γ′ ⊢𝑉: 𝜃2𝐴(5) and 𝑃2 | Γ2, Γ′, (𝑥: 𝜎′)|Σ2 ⊢𝑀: 𝜃2𝐶. By Lemma C.7 we have 𝑃2 ∪un((𝑥: 𝜎′)|Σc

2) | Γ2, Γ′,𝑥: 𝜎′ ⊢𝑀: 𝜃2𝐶(6). By Theorem 5.3, we have 𝑄⊢Γ′ ⪯•. Our goal follows from Q-Let, (5), (6) and Lemma C.6. Case

Q-AbsW

𝛼,𝜙fresh Γ,𝑥: 𝛼⊢𝑀: 𝐶⊣𝜃, 𝑃, Σ (1) 𝑄= leq(𝜃Γ|Σ,𝜙) ∪un(𝜃(𝑥: 𝛼)|Σc)

Γ ⊢𝜆𝑥.𝑀: 𝜃𝛼→𝜙𝐶⊣𝜃, 𝑃∪𝑄, Σ\𝑥

By the IH on (1), we have 𝑃| (𝜃Γ,𝑥: 𝜃𝛼)|Σ ⊢𝑀: 𝐶. Our goal follows from Lemma C.7, Theorem 5.3 and Q-Abs.

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 44 -->

54:44 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

Case

Q-AppW

Γ ⊢𝑉: 𝐴⊣𝜃1, 𝑃1, Σ1 (1) 𝜃1Γ ⊢𝑊: 𝐵⊣𝜃2, 𝑃2, Σ2 (2) 𝛼, 𝜇,𝜙fresh 𝜃2𝐴∼(𝐵→𝜙𝛼! 𝜇) : 𝜃3 (3) 𝑃= 𝜃3(𝜃2𝑃1 ∪𝑃2) 𝑄= un(𝜃3𝜃2𝜃1Γ|Σ1∩Σ2)

Γ ⊢𝑉𝑊: 𝜃3(𝛼! 𝜇) ⊣𝜃3𝜃2𝜃1, 𝑃∪𝑄, Σ1 ∪Σ2

By the IH on (1), we have 𝑃1 | 𝜃1Γ ⊢𝑉: 𝐴. By the IH on (2), we have 𝑃2 | 𝜃2𝜃1Γ ⊢𝑊: 𝐵. By Lemma C.5, we have 𝜃3𝜃2𝑃1 | 𝜃3𝜃2𝜃1Γ ⊢𝑉: 𝜃3𝜃2𝐴(4) and 𝜃3𝑃2 | 𝜃3𝜃2𝜃1Γ ⊢𝑊: 𝜃3𝐵(5). By (3), we have 𝜃3𝜃2𝐴= 𝜃3(𝐵→𝜙𝛼! 𝜇). Let Γ1 = (𝜃3𝜃2𝜃1Γ)|Σ1\Σ2, Γ2 = (𝜃3𝜃2𝜃1Γ)|Σ2\Σ1, Γ′ = (𝜃3𝜃2𝜃1Γ)|Σ1∩Σ2. By (4) and (5), we have 𝜃3𝜃2𝑃1 | Γ1, Γ′ ⊢𝑉: 𝜃3𝜃2𝐴(6) and 𝜃3𝑃2 | Γ2, Γ′ ⊢ 𝑊: 𝜃3𝐵(7). By Theorem 5.3, we have 𝑄⊢Γ′ ⪯•. Our goal follows from Q-App, (6), (7), and

Lemma C.6. Case

Q-ReturnW

Γ ⊢𝑉: 𝐴⊣𝜃, 𝑃, Σ (1) 𝜇fresh

Γ ⊢return 𝑉: 𝐴! {𝜇} ⊣𝜃, 𝑃, Σ

Our goal follows from the IH on (1) and Q-Return. Case

Q-DoW

Γ ⊢𝑉: 𝐴⊣𝜃1, 𝑃, Σ (1) 𝐴∼𝐴ℓ: 𝜃2 𝜇,𝜙fresh 𝑄= sub((ℓ: 𝐴ℓ↠𝜙𝐵ℓ), 𝜇)

Γ ⊢do ℓ𝑉: 𝐵ℓ! {𝜇} ⊣𝜃2𝜃1,𝜃2𝑃∪𝑄, Σ

Our goal follows from the IH on (1), Q-Do, Theorem 5.3, and Lemma C.5. Case

Q-SeqW

Γ ⊢𝑀: 𝐴! {𝑅1} ⊣𝜃1, 𝑃1, Σ1 (1) 𝜃1Γ,𝑥: 𝐴⊢𝑁: 𝐵! {𝑅2} ⊣𝜃2, 𝑃2, Σ2 (2) 𝜇fresh 𝑄= un(𝜃2𝜃1Γ|Σ1∩Σ2) ∪un(𝜃2(𝑥: 𝐴)|Σc

2) ∪leq(𝜃2𝜃1Γ|Σ2,𝜃2𝑅1) ∪sub(𝜃2𝑅1, 𝜇) ∪sub(𝑅2, 𝜇)

Γ ⊢let 𝑥←𝑀in 𝑁: 𝐵! 𝜇⊣𝜃2𝜃1,𝜃2𝑃1 ∪𝑃2 ∪𝑄, Σ1 ∪(Σ2\𝑥)

Similar to the Q-LetW and Q-AppW cases. Let Σ′

2 = Σ2\𝑥, Γ1 = (𝜃2𝜃1Γ)|Σ1\Σ′ 2, Γ2 = (𝜃2𝜃1Γ)|Σ′ 2\Σ1, Γ′ = (𝜃2𝜃1Γ)|Σ1∩Σ′

2. By the IH on (1) and Lemma C.5, we have𝜃2𝑃1 | Γ1, Γ′ ⊢𝑀: 𝜃2(𝐴! {𝑅1}) (3). By the IH on (2), we have 𝑃2 | Γ2, Γ′, (𝑥: 𝐴)|Σ2 ⊢𝑁: 𝐵! {𝑅2} (4). Our goal follows from Q-Seq, (3), (4), Theorem 5.3, Lemma C.6 and Lemma C.7. Case

Q-HandleW

Γ ⊢𝐻: 𝐴! {𝑅1} ⇒𝐷⊣𝜃1, 𝑃1, Σ1 (1) 𝜃1Γ ⊢𝑀: 𝐴′ ! {𝑅} ⊣𝜃2, 𝑃2, Σ2 (2) 𝜃2𝐴∼𝐴′ : 𝜃3 𝑃= 𝜃3(𝜃2𝑃1 ∪𝑃2) 𝑄= sub(𝜃3𝑅,𝜃3𝜃2𝑅1) ∪un(𝜃3𝜃2𝜃1Γ|Σ1∩Σ2)

Γ ⊢handle 𝑀with 𝐻: 𝜃3𝜃2𝐷⊣𝜃3𝜃2𝜃1, 𝑃∪𝑄, Σ1 ∪Σ2

By a similar proof to the Q-App case, our goal follows from the IHs on (1) and (2), Theorem 5.3, Lemma C.5 and Lemma C.6.

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 45 -->

Soundly Handling Linearity 54:45

Case

Q-HandlerW

𝛼,𝜙𝑖, 𝜇fresh Γ,𝑥: 𝛼⊢𝑀: 𝐷⊣𝜃0, 𝑃0, Σ0 (1) [𝜃𝑖−1(Γ, 𝑝𝑖: 𝐴ℓ𝑖,𝑟𝑖: 𝐵ℓ𝑖→𝜙𝑖𝐷) ⊢𝑁𝑖: 𝐷𝑖⊣𝜃′

𝑖𝜃𝑖−1𝐷: 𝜃′′

𝑖=1 𝐶= 𝜃𝑛(𝛼! {(ℓ𝑖: 𝐴ℓ𝑖↠𝜙𝑖𝐵ℓ𝑖)𝑖; 𝜇}) 𝐵! {𝑅} = 𝜃𝑛𝐷 Σ = (Σ0\{𝑥}) ∪(∪𝑛

𝑖=1(Σ𝑖\{𝑝𝑖,𝑟𝑖})) 𝑃= (∪𝑛

𝑖=0𝜃𝑛𝑃𝑖) ∪un(𝜃𝑛Γ|Σ) ∪sub(𝜇, 𝑅) ∪lack(𝜇, {ℓ𝑖}𝑖) 𝑄= un(𝜃𝑛(𝑥: 𝛼)|Σc

Γ ⊢{return 𝑥↦→𝑀} ⊎{ℓ𝑖𝑝𝑖𝑟𝑖↦→𝑁𝑖}𝑛

𝜃𝑛𝑃0 ∪un(𝜃𝑛Γ|Σ) ∪un(𝜃𝑛(𝑥: 𝛼)|Σc

and

Lemma C.10 (Closure property of factorisation). If factorise(𝑃) = 𝑄, then factorise(𝜃𝑃) = 𝜃𝑄. If factorise(Γ ⪯𝜏) = 𝑄, then factorise(𝜃(Γ ⪯𝜏)) = 𝜃𝑄.

Case

Q-Var

𝑃⊢Γ ⪯• 𝑃⇒𝐴⊑∀𝛼.𝑄⇒𝐵

𝑃| 𝜃(Γ,𝑥: ∀𝛼.𝑄⇒𝐵) ⊢𝑥: 𝐴

𝑖, 𝑃𝑖, Σ𝑖(2) 𝐷𝑖∼𝜃′

𝑖𝜃𝑖−1]𝑛

𝑖 𝜃𝑖= 𝜃′′

𝑖𝜃′

0) ∪(∪𝑛 𝑖=1un(𝜃𝑛(𝑝𝑖: 𝐴ℓ𝑖,𝑟𝑖: 𝐵ℓ𝑖→𝜙𝑖𝐷)))

𝑖=1 : 𝐶⇒𝜃𝑛𝐷⊣𝜃𝑛, 𝑃∪𝑄, Σ

The type inference for handlers is the most complicated, but there is nothing really new about the proof compared to previous cases. By the IH on (1), we have 𝑃0 | 𝜃0(Γ,𝑥: 𝛼)|Σ0 ⊢𝑀: 𝐷. By the IH on (2), we have 𝑃𝑖| 𝜃′

𝑖𝜃𝑖−1(Γ, 𝑝𝑖: 𝐴ℓ𝑖,𝑟𝑖: 𝐵ℓ𝑖→𝜙𝑖𝐷)|Σ𝑖⊢𝑁𝑖: 𝐷𝑖. By Lemma C.5, we have 𝜃𝑛𝑃0 | 𝜃𝑛(Γ,𝑥: 𝛼)|Σ0 ⊢𝑀: 𝜃𝑛𝐷and 𝜃𝑛𝑃𝑖| 𝜃𝑛(Γ, 𝑝𝑖: 𝐴ℓ𝑖,𝑟𝑖: 𝐵ℓ𝑖→𝜙𝑖𝐷)|Σ𝑖⊢𝑁𝑖: 𝜃𝑛𝐷𝑖. By Lemma C.7, we have

0) | 𝜃𝑛(Γ|Σ,𝑥: 𝛼) ⊢𝑀: 𝜃𝑛𝐷(3)

𝜃𝑛𝑃𝑖∪un(𝜃𝑛Γ|Σ) ∪un(𝜃𝑛(𝑝𝑖: 𝐴ℓ𝑖,𝑟𝑖: 𝐵ℓ𝑖→𝜙𝑖𝐷)) | 𝜃𝑛(Γ|Σ, 𝑝𝑖: 𝐴ℓ𝑖,𝑟𝑖: 𝐵ℓ𝑖→𝜙𝑖𝐷) ⊢𝑁𝑖: 𝜃𝑛𝐷𝑖(4)

By Theorem 5.3, we have 𝑃∪𝑄⊢{𝜇⩽𝑅, 𝜇⊥{ℓ𝑖}𝑖}. Our goal follows from Q-Handler, (3), (4), and Lemma C.6.

□

Lemma C.8 (More general contexts). If 𝑃| Γ,𝑥: 𝜎⊢𝑉: 𝐴and 𝜎⊑𝜎′, then 𝑃| Γ,𝑥: 𝜎′ ⊢𝑉: 𝐴. The same applies to computation and handler typing.

Proof. By straightforward induction on the typing derivation. □

Lemma C.9 (Zero is unlimited). If 𝑃| Γ,𝑥: 𝜎⊢𝑉: 𝐴and 𝑥does not appear in𝑉, then 𝑃⊢𝜎⪯•. The same applies to computation and handler typing.

Proof. By straightforward induction on the typing derivation. □

Proof. By the closure property of Theorem C.1 and straightforward induction on the definition of factorise. □

Theorem 5.2 (Completeness). If 𝑃| 𝜃Γ ⊢𝑉: 𝐴, then Γ ⊢𝑉: 𝐴′ ⊣𝜃′,𝑄, Σ and there exists 𝜃′′

such that 𝐴= 𝜃′′𝐴′, 𝑃⊢𝜃′′𝑄, and (𝜃= 𝜃′′𝜃′)|Γ. The same applies to computation and handler typing.

Proof. By mutual induction on the syntax-directed typing derivations 𝑃| Γ ⊢𝑉: 𝐴, 𝑃| Γ ⊢𝑀: 𝐶, and 𝑃| Γ ⊢𝐻: 𝐶⇒𝐷.

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 46 -->

54:46 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

By 𝑃⇒𝐴⊑∀𝛼.𝑄⇒𝐵, there exists 𝜃1 such that 𝐴= 𝜃1𝐵and 𝑃⊢𝜃1𝑄. By Q-VarW, we have the following derivation

Q-VarW

𝛽fresh 𝜃′ = [𝛽/𝛼]

Γ,𝑥: ∀𝛼.𝑄⇒𝐵⊢𝑥: 𝜃′𝐵⊣𝜃′,𝜃′𝑄, {𝑥}

Let 𝜃′′ = 𝜃𝜃1[𝛼/𝛽], we have 𝐴= 𝜃1𝐵= 𝜃′′𝜃′𝐵, 𝑃⊢𝜃1𝑄= 𝜃′′𝜃′𝑄, and (𝜃= 𝜃′′𝜃′)|Γ. Case

Q-Let

𝑃1 | 𝜃(Γ1, Γ) ⊢𝑉: 𝐴(1) 𝜎= gen(𝜃(Γ1, Γ), 𝑃1 ⇒𝐴) 𝑃2 | 𝜃(Γ2, Γ),𝑥: 𝜎⊢𝑀: 𝐶(2) 𝑃2 ⊢𝜃Γ ⪯•

𝑃2 | 𝜃(Γ1, Γ2, Γ) ⊢let 𝑥= 𝑉in 𝑀: 𝐶

By the IH on (1), we have Γ1, Γ ⊢𝑉: 𝐴′ ⊣𝜃1, 𝑃′

1, Σ1 and there exists 𝜃′ 1 such that 𝐴= 𝜃′ 1𝐴′, 𝑃1 ⊢𝜃′

1𝑃′ 1, and (𝜃= 𝜃′ 1𝜃1)|Γ1,Γ. By context weakening, we have Γ1, Γ2, Γ ⊢𝑉: 𝐴′ ⊣𝜃1, 𝑃′ 1, Σ1 (3). We also have 𝜎= gen(𝜃(Γ1, Γ2, Γ), 𝑃1 ⇒𝐴). Let 𝜎′ = gen(𝜃1(Γ1, Γ2, Γ), 𝑃′

1 ⇒𝐴′). By (𝜃= 𝜃′

1𝜃1)|Γ1,Γ, it is easy to see that 𝜎⊑𝜃′ 1𝜎′. Then by (2) and Lemma C.8, we have 𝑃2 | 𝜃(Γ2, Γ),𝑥: 𝜃′

1𝜎′ ⊢𝑀: 𝐶, which further implies 𝑃2 | 𝜃3𝜃′ 1𝜃1(Γ2, Γ,𝑥: 𝜎′) ⊢𝑀: 𝐶(4) for some 𝜃3 with 𝜃= 𝜃3𝜃′

1𝜃1. By the IH on (4), we have 𝜃1(Γ2, Γ,𝑥: 𝜎′) ⊢𝑀: 𝐶′ ⊣𝜃2, 𝑃′ 2, Σ2 and there exists 𝜃′ 2 such that 𝐶= 𝜃′

2𝐶′, 𝑃2 ⊢𝜃′ 2𝑃′ 2, and (𝜃3𝜃′ 1 = 𝜃′ 2𝜃2)|Γ2,Γ. By context weakening and 𝜃1𝜎′ = 𝜎′, we have 𝜃1(Γ1, Γ2, Γ),𝑥: 𝜎′ ⊢𝑀: 𝐶′ ⊣𝜃2, 𝑃′

2, Σ2 (5). Let 𝑄= un(𝜃2𝜃1(Γ1, Γ2, Γ)|Σ1∩Σ2) ∪un(𝜃2(𝑥: 𝜎)|Σc

2). By Q-LetW, (3) and (5), we have

Γ1, Γ2, Γ ⊢let 𝑥= 𝑉in 𝑀: 𝐶′ ⊣𝜃2𝜃1, 𝑃′

2 ∪𝑄, Σ1 ∪(Σ2\𝑥)

With 𝜃′ = 𝜃′

1𝜃′ 2, we have (𝜃= 𝜃′𝜃2𝜃1)|Γ1,Γ2,Γ3. By Σ1 ∩Σ2 ⊆dom(Γ), Lemma C.9, Lemma C.10 and Theorem 5.3, there exists 𝜃𝑝such that 𝑃2 ⊢𝜃𝑝𝜃′𝑄(6). Let 𝜃′′ = 𝜃𝑝𝜃′. Our goal follows from (𝜃= 𝜃′′𝜃2𝜃1)|Γ1,Γ2,Γ3, 𝐶= 𝜃′′𝐶′, and 𝑃2 ⊢𝜃′′(𝑃′

2 ∪𝑄). Case

Q-Abs

𝑃| 𝜃Γ,𝑥: 𝐴⊢𝑀: 𝐶(1) 𝑃⊢𝜃Γ ⪯𝑌

𝑃| 𝜃Γ ⊢𝜆𝑥.𝑀: 𝐴→𝑌𝐶

Take a fresh variable 𝛼and let 𝜃1 = 𝜃[𝐴/𝛼]. By (1), we have 𝑃| 𝜃1(Γ,𝑥: 𝛼) ⊢𝑀: 𝐶(2). By the IH on (2), we have Γ,𝑥: 𝛼⊢𝑀: 𝐶′ ⊣𝜃′, 𝑃′, Σ (3) and there exists 𝜃′′ such that 𝐶= 𝜃′′𝐶′, 𝑃⊢𝜃′′𝑃′, and (𝜃1 = 𝜃′′𝜃′)|Γ,𝑥:𝛼. Let 𝑄= leq(𝜃′Γ|Σ,𝜙) ∪un(𝜃′(𝑥: 𝛼)|Σc) By Q-AbsW and (3), taking a fresh variable 𝜙, we have

Γ ⊢𝜆𝑥.𝑀: 𝜃′𝛼→𝜙𝐶′ ⊣𝜃′, 𝑃′ ∪𝑄, Σ\𝑥

With 𝜃2 = 𝜃′′[𝑌/𝜙], we have (𝜃= 𝜃2𝜃′)|Γ,𝑥:𝛼. By 𝑃⊢𝜃Γ ⪯𝑌, Lemma C.9, Lemma C.10, and Theorem 5.3, there exists 𝜃𝑝such that 𝑃⊢𝜃𝑝𝜃2𝑄. Let 𝜃3 = 𝜃𝑝𝜃2. Our goal follows from (𝜃= 𝜃3𝜃′)|Γ,𝑥:𝛼, 𝐴→𝑌𝐶= 𝜃3(𝜃′𝛼→𝜙𝐶′) and 𝑃⊢𝜃3(𝑃′ ∪𝑄). Case

Q-App

𝑃| 𝜃(Γ1, Γ) ⊢𝑉: 𝐵→𝑌𝐶(1) 𝑃| 𝜃(Γ2, Γ) ⊢𝑊: 𝐵(2) 𝑃⊢𝜃Γ ⪯•

𝑃| 𝜃(Γ1, Γ2, Γ) ⊢𝑉𝑊: 𝐶

By the IH on (1), we have Γ1, Γ ⊢𝑉: 𝐴′ ⊣𝜃1, 𝑃1, Σ1 (3) and there exists 𝜃′

1 such that 𝐵→𝑌

1𝐴′, 𝑃⊢𝜃′ 1𝑃1, and (𝜃= 𝜃′ 1𝜃1)|Γ1,Γ. Let 𝜃= 𝜃′𝜃′ 1𝜃1 where 𝜃′ only substitutes type

𝐶= 𝜃′

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 47 -->

Soundly Handling Linearity 54:47

variables only appearing in Γ2. By the IH on (2), we have 𝜃1(Γ2, Γ) ⊢𝑊: 𝐵′ ⊣𝜃2, 𝑃2, Σ2 (4) and there exists 𝜃′

2 such that 𝐵= 𝜃′ 2𝐵′, 𝑃⊢𝜃′ 2𝑃2, and (𝜃′𝜃′ 1 = 𝜃′ 2𝜃2)|Γ2,Γ (5). Take fresh variables 𝛼, 𝜇,𝜙. By 𝐵→𝑌𝐶= 𝜃′

1𝐴′, the unification 𝜃2𝐴′ ∼𝐵′ →𝜙𝛼! 𝜇: 𝜃3 succeeds. By Lemma C.4 and (5), there exists 𝜃4 such that 𝜃4𝜃3(𝜃2𝐴′) = 𝜃4𝜃3(𝐵′ →𝜙𝛼! 𝜇) = 𝐵→𝑌𝐶. Let 𝑃3 = 𝜃3(𝜃2𝑃1 ∪𝑃2) and 𝑄= un(𝜃3𝜃2𝜃1(Γ1, Γ2, Γ)|Σ1∩Σ2). By Q-AppW, (3), (4) and context weakening, we have Γ1, Γ2, Γ ⊢𝑉𝑊: 𝜃3(𝛼! 𝜇) ⊣𝜃3𝜃2𝜃1, 𝑃3 ∪𝑄, Σ1 ∪Σ2. With 𝜃′′ = 𝜃4𝜃′

2𝜃′ 1, we have (𝜃= 𝜃′′𝜃3𝜃2𝜃1)|Γ1,Γ2,Γ. By Σ1 ∩Σ2 ⊆dom(Γ), 𝑃⊢𝜃Γ ⪯•, Lemma C.10, and Theorem 5.3, we have 𝑃⊢𝜃𝑝𝜃′′𝑄. Let 𝜃5 = 𝜃𝑝𝜃′′. Our goal follows from 𝐶= 𝜃5𝜃3(𝛼! 𝜇), 𝑃⊢𝜃5(𝑃3 ∪𝑄) and (𝜃= 𝜃5𝜃3𝜃2𝜃1)|Γ1,Γ2,Γ. Case

Q-Return

𝑃| 𝜃Γ ⊢𝑉: 𝐴(1)

𝑃| 𝜃Γ ⊢return 𝑉: 𝐴! {𝑅}

Our goal follows from the IH on (1). Case

Q-Do

𝑃| 𝜃Γ ⊢𝑉: 𝐴ℓ(1) 𝑃⊢{ℓ: 𝐴ℓ↠𝑌𝐵ℓ} ⩽𝑅

𝑃| 𝜃Γ ⊢do ℓ𝑉: 𝐵ℓ! {𝐸}

Similar to previous cases. Our goal follows from the IH on (1), Lemma C.4, and Theorem 5.3. Case

Q-Seq

𝑃| 𝜃(Γ1, Γ) ⊢𝑀: 𝐴! {𝑅1} (1) 𝑃| 𝜃(Γ2, Γ),𝑥: 𝐴⊢𝑁: 𝐵! {𝑅2} (2) 𝑃⊢𝑅1 ⩽𝑅 𝑃⊢𝑅2 ⩽𝑅 𝑃⊢𝜃Γ2 ⪯𝑅1 𝑃⊢𝜃Γ ⪯•

𝑃| 𝜃(Γ1, Γ2, Γ) ⊢let 𝑥←𝑀in 𝑁: 𝐵! {𝑅}

By the IH on (1), we have Γ1, Γ ⊢𝑀: 𝐴′ ! {𝑅′

1} ⊣𝜃1, 𝑃1, Σ1 (4) and there exists 𝜃′ 1 such that 𝐴! {𝑅1} = 𝜃′

1(𝐴′ ! {𝑅′ 1}), 𝑃⊢𝜃′ 1𝑃1, and (𝜃= 𝜃′ 1𝜃1)|Γ1,Γ. Let 𝜃= 𝜃′𝜃′ 1𝜃1 where 𝜃′ substitutes type variables only appearing in Γ2. By (2), we have 𝑃| 𝜃′𝜃′

1𝜃1(Γ2, Γ,𝑥: 𝐴′) ⊢𝑁: 𝐵! {𝑅2} (3). By the IH on (3), we have 𝜃1(Γ2, Γ,𝑥: 𝐴′) ⊢𝑁: 𝐵′ ! {𝑅′

2} ⊣𝜃2, 𝑃2, Σ2 (5) and there exists 𝜃′ 2 such that 𝐵! {𝑅2} = 𝜃′

2(𝐵′ ! {𝑅′ 2}), 𝑃⊢𝜃′ 2𝑃2 and (𝜃′𝜃′ 1 = 𝜃′ 2𝜃2)|Γ2,Γ. Take a fresh variable 𝜇. Let 𝑄= un(𝜃2𝜃1(Γ1, Γ2, Γ)|Σ1∩Σ2) ∪un(𝜃2(𝑥: 𝐴)|Σc

2) ∪leq(𝜃2𝜃1(Γ1, Γ2, Γ)|Σ2,𝜃2𝑅1) ∪sub(𝜃2𝑅1, 𝜇) ∪ sub(𝑅2, 𝜇). By Q-SeqW, (4), (5), and context weakening, we have Γ1, Γ2, Γ ⊢let 𝑥←𝑀in 𝑁: 𝐵′ ! {𝑅2} ⊣𝜃2𝜃1,𝜃2𝑃1 ∪𝑃2 ∪𝑄, Σ1 ∪(Σ2\𝑥). With 𝜃′′ = [𝑅/𝜇]𝜃′

2𝜃′ 1, we have (𝜃= 𝜃′′𝜃2𝜃1)|Γ1,Γ2,Γ. By Σ1 ∩Σ2 ⊆dom(Γ), 𝑃⊢𝜃Γ ⪯•, Lemma C.9, Γ2 = Γ|Σ2, 𝑃⊢𝜃Γ2 ⪯𝑅1, 𝑃⊢𝑅1 ⩽𝑅, 𝑃⊢𝑅2 ⩽𝑅, Lemma C.10 and Theorem 5.3, there exists 𝜃𝑝such that 𝑃⊢𝜃𝑝𝜃′′𝑄. Let 𝜃3 = 𝜃𝑝𝜃′′. Our goal follows from 𝐵! {𝑅2} = 𝜃3(𝐵′ ! {𝜇}), 𝑃⊢𝜃3(𝜃2𝑃1 ∪𝑃2), and (𝜃= 𝜃3𝜃2𝜃1)|Γ1,Γ2,Γ. Case

Q-Handle

𝑃| 𝜃(Γ1, Γ) ⊢𝐻: 𝐴! {𝑅1} ⇒𝐷(1) 𝑃| 𝜃(Γ2, Γ) ⊢𝑀: 𝐴! {𝑅} (2) 𝑃⊢𝜃Γ ⪯• 𝑃⊢𝑅⩽𝑅1 (3)

𝑃| 𝜃(Γ1, Γ2, Γ) ⊢handle 𝑀with 𝐻: 𝐷

By a similar proof to the Q-App case, our goal follows from the IHs on (1) and (2), Lemma C.10, Theorem 5.3, and Lemma C.4. The only difference is the subtyping constraint sub(𝜃3𝑅,𝜃3𝜃2𝑅1) used by Q-HandleW, which follows from (3).

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 48 -->

Case

Q-Handler

𝐶= 𝐴! {(ℓ𝑖: 𝐴ℓ𝑖↠𝑌𝑖𝐵𝑖)𝑖;𝑅1} 𝐷= 𝐵! {𝑅2} 𝑃| 𝜃Γ,𝑥: 𝐴⊢𝑀: 𝐷(1) [𝑃| 𝜃Γ, 𝑝𝑖: 𝐴𝑖,𝑟𝑖: 𝐵𝑖→𝑌𝑖𝐷⊢𝑁𝑖: 𝐷]𝑖(2) 𝑃⊢𝜃Γ ⪯• 𝑃⊢𝑅1 ⩽𝑅2 𝑃⊢𝑅1 ⊥{ℓ𝑖}𝑖 𝑃| 𝜃Γ ⊢{return 𝑥↦→𝑀} ⊎{ℓ𝑖𝑝𝑖𝑟𝑖↦→𝑁𝑖}𝑛

0 = 𝜃0 and 𝜃𝑏 0 = 𝜃′ 0. We have (𝜃𝑏 0𝜃𝑎 0 = 𝜃)|Γ. By the typing derivation on the first handler clause in (2), we have 𝑃| 𝜃𝑏

1 𝐷1 = 𝐷. Set 𝜃𝑎 1 = 𝜃𝑥𝜃1𝜃𝑎 0 and 𝜃𝑏 1 = 𝜃′ 1𝜃𝑦 1 . We have (𝜃𝑏 1𝜃𝑎 1 = 𝜃)|Γ. Repeating the above process for every 𝑖from 2 to 𝑛, we have 𝜃𝑎

𝑖𝜃𝑎

𝑖= 𝜃)|Γ. Let

𝐶′ = 𝜃𝑎

𝑛(𝛼! {(ℓ𝑖: 𝐴ℓ𝑖↠𝜙𝑖𝐵ℓ𝑖)𝑖; 𝜇}) 𝐵′ ! {𝑅} = 𝜃𝑎

𝑛𝐷′

Σ = (Σ0\{𝑥}) ∪(∪𝑛

𝑖=1(Σ𝑖\{𝑝𝑖,𝑟𝑖})) 𝑃′ = (∪𝑛

𝑖=0𝜃𝑎

𝑛𝑃𝑖) ∪un(𝜃𝑎

𝑛Γ|Σ) ∪sub(𝜇, 𝑅) ∪lack(𝜇, {ℓ𝑖}𝑖) 𝑄′ = un(𝜃𝑎

0) ∪(∪𝑛 𝑖=1un(𝜃𝑎

𝑛(𝑥: 𝛼)|Σc

By Q-HandlerW, (3), and (4), we have Γ ⊢{return 𝑥↦→𝑀} ⊎{ℓ𝑖𝑝𝑖𝑟𝑖↦→𝑁𝑖}𝑛

𝑛𝐷′ ⊣𝜃𝑎

𝑛, 𝑃′ ∪𝑄′, Σ. With 𝜃′ = 𝜃𝑏

C.4 Correctness of Constraint Solving

1, 𝐾′ 2,𝜃), then J𝐾1 ⩽𝐾2K𝑠𝑎𝑡= J𝐾′ 1 ⩽𝐾′ 2K𝑠𝑎𝑡𝜃; if it fails, then 𝐾1 ⩽𝐾2 is not satisfiable.

Lemma C.11. If urow(𝐾1, 𝐾2) returns (𝐾′

Lemma C.12. If factorise(𝑃) = 𝑄, then J𝑃K𝑠𝑎𝑡= J𝑄K𝑠𝑎𝑡.

Theorem 5.4 (Correctness of constraint solving). For any constraint set 𝑃generated by the type inference of Q◦

eff, solve(𝑃) always terminates.

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

54:48 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

𝑖=1 : 𝐶⇒𝐷

The typing rule for handler is the most complicated one, but there is actually nothing new of the proof compared to previous cases for other rules. For each typing derivation on the handler clauses, we do a similar proof to the Q-Abs case. Take fresh variables 𝛼,𝜙𝑖, and 𝜇. First, by (1) we have 𝑃| 𝜃[𝐴/𝛼](Γ,𝑥: 𝛼) ⊢𝑀: 𝐷. By the IH on it, we have Γ,𝑥: 𝛼⊢𝑀: 𝐷′ ⊣𝜃0, 𝑃0, Σ0 (3) and there exists 𝜃′

0 such that 𝐷= 𝜃′ 0𝐷′, 𝑃⊢𝜃′ 0𝑃0 and (𝜃[𝐴/𝛼] = 𝜃′ 0𝜃0)|Γ,𝑥:𝛼. Let 𝜃𝑎

0 [𝑌1/𝜙1]𝜃𝑎 0 (Γ, 𝑝1 : 𝐴1,𝑟1 : 𝐵1 →𝜙1 𝐷) ⊢𝑁1 : 𝐷. By the IH on it, we have 𝜃𝑎

0 (Γ, 𝑝1 : 𝐴1,𝑟1 : 𝐵1 →𝜙1 𝐷) ⊢𝑁1 : 𝐷1 ⊣𝜃1, 𝑃1, Σ1 and 𝜃′

1 such that 𝐷= 𝜃′ 1𝐷1, 𝑃⊢𝜃′ 1𝑃1 and (𝜃𝑏 0 [𝑌1/𝜙1] = 𝜃′ 1𝜃1)|(Γ,𝑝1:𝐴1,𝑟1:𝐵1→𝜙1𝐷). By 𝐷= 𝜃′

1𝐷1, the unification 𝐷1 ∼𝜃′ 1𝜃1𝐷′ : 𝜃𝑥 1 succeeds. By Lemma C.4, there exists 𝜃𝑦 1 such that 𝜃𝑦

𝑖−1(Γ, 𝑝𝑖: 𝐴𝑖,𝑟𝑖: 𝐵𝑖→𝜙𝑖𝐷) ⊢ 𝑁𝑖: 𝐷𝑖⊣𝜃𝑖, 𝑃𝑖, Σ𝑖(4) and (𝜃𝑏

𝑛(𝑝𝑖: 𝐴ℓ𝑖,𝑟𝑖: 𝐵ℓ𝑖→𝜙𝑖𝐷)))

𝑖=1 : 𝐶′ ⇒ 𝜃𝑎

𝑛[𝑅1/𝜇], we have (𝜃= 𝜃′𝜃𝑎

𝑛)|Γ. By Lemma C.9, Lemma C.10, and Theorem 5.3 there exists 𝜃𝑝such that 𝑃⊢𝜃𝑝𝜃′(𝑃∪𝑄). Let 𝜃′′ = 𝜃𝑝𝜃′. Our goal follows from 𝐶⇒𝐷= 𝜃′′(𝐶′ ⇒𝜃𝑎

𝑛𝐷′), 𝑃⊢𝜃′′(𝑃∪𝑄), and (𝜃= 𝜃′′𝜃𝑎

𝑛)|Γ.

□

Proof. By Lemma C.3, the substitution 𝜃returned by urow(𝐾1, 𝐾2) is the principal unifier that unifies the linearity types of the same labels in 𝐾1 and 𝐾2, which is a necessary condition for any solution of 𝐾1 ⩽𝐾2. □

Proof. By Theorem 5.3, we have 𝑃⊢𝑄and 𝑄⊢𝑃. For any 𝜃∈J𝑃K𝑠𝑎𝑡, we have · ⊢𝜃𝑃. By the closure property of Theorem C.1, we have 𝜃𝑃⊢𝜃𝑄. By the transitivity of Theorem C.1, we have · ⊢𝜃𝑄, which implies 𝜃∈J𝑄K𝑠𝑎𝑡. Symmetrically, for any 𝜃∈J𝑄K𝑠𝑎𝑡, we can prove 𝜃∈J𝑃K𝑠𝑎𝑡. Finally, we have J𝑃K𝑠𝑎𝑡= J𝑄K𝑠𝑎𝑡. □


<!-- page 49 -->

Soundly Handling Linearity 54:49

• If it fails, then 𝑃is not satisfiable. • If it returns (𝜃,𝑄), then 𝑃is satisfiable and J𝑃K𝑠𝑎𝑡= J𝑄K𝑠𝑎𝑡𝜃.

Case

srow(𝜃, 𝑃, ·) = return (𝜃, 𝑃)

Our goal follows from J𝑃K𝑠𝑎𝑡= J𝑃K𝑠𝑎𝑡𝜄. Case

srow(𝜃, 𝑃, (𝜏1 ⪯𝜏2,𝑄)) = srow(𝜃, (𝑃,𝜏1 ⪯𝜏2),𝑄) (1)

Our goal follows from the IH on (1) and J𝑃∪(𝜏1 ⪯𝜏2,𝑄)K𝑠𝑎𝑡= J(𝑃,𝜏1 ⪯𝜏2) ∪𝑄K𝑠𝑎𝑡. Case

srow(𝜃, 𝑃, (𝐾1 ⩽𝐾2,𝑄)) =

let (𝐾′

1) ⊆set(𝐾′ 2) (3) srow(𝜃′𝜃,𝜃′𝑃,𝜃′𝑄) (1)

Obviously (3) fails when 𝐾′

srow(𝜃, 𝑃, (𝐾1 ; 𝜇⩽𝐾2 ; 𝜇,𝑄)) =

let (𝐾′

1) ⊆set(𝐾′ 2) (3) srow(𝜃′𝜃,𝜃′𝑃,𝜃′𝑄) (1)

Obviously (3) fails when 𝐾′

srow(𝜃, 𝑃, (𝐾1 ; 𝜇⩽𝐾2,𝑄)) =

1, 𝐾′ 2,𝜃′) = urow(𝐾1, 𝐾2) (2) assert set(𝐾′

let (𝐾′

1) ⊆set(𝐾′ 2) (3) srow(𝜃′𝜃, (𝜃′𝑃, 𝜇⩽(𝐾′

Obviously (3) fails when 𝐾′

Proof. The termination of trlin and factorise is obvious. It may be not very obvious that srow always terminates since the srow(𝜃, 𝑃,𝑄) moves the solved predicates in 𝑃to the set of unsolved constraints 𝑄in some cases. Note that only row subtyping constraints of forms 𝐾1 ⩽𝐾2 ; 𝜇2 and 𝐾1 ; 𝜇1 ⩽𝐾2 ; 𝜇2 might require resolving previously solved constraints because they substitute row variables. In both cases, when set(𝐾′

1) ⊈set(𝐾′ 2), we substitute 𝜇2 with (𝐾′ 1\𝐾′ 2) ; 𝜇. Notice that the number of labels used in the whole predicate set is finite, and the srow fails when there are duplicated labels in the same row, which implies that this kind of substitution terminates. Finally, we can conclude that srow terminates.

For the correctness, the idea is to show that every step preserves solutions. We first show srow preserves solutions by proving a lemma that if srow(𝜃, 𝑃,𝑄) returns (𝜃′𝜃,𝑄′), then we have J𝑃∪𝑄K𝑠𝑎𝑡= J𝑄′K𝑠𝑎𝑡𝜃′; if it fails, then 𝑃∪𝑄is not satisfiable. We prove by induction on the definition of srow.

1, 𝐾′ 2,𝜃′) = urow(𝐾1, 𝐾2) (2) assert set(𝐾′

1 ⩽𝐾′ 2 is not satisfiable. Our goal follows from the IH on (1), Lemma C.11 on (2), and J𝑃∪(𝐾1 ⩽𝐾2,𝑄)K𝑠𝑎𝑡= J𝜃′𝑃∪𝜃′𝑄K𝑠𝑎𝑡𝜃′. Case

1, 𝐾′ 2,𝜃′) = urow(𝐾1, 𝐾2) (2) assert set(𝐾′

1 ⩽𝐾′ 2 is not satisfiable. Our goal follows from the IH on (1), Lemma C.11 on (2), and J𝑃∪(𝐾1 ; 𝜇⩽𝐾2 ; 𝜇,𝑄)K𝑠𝑎𝑡= J𝜃′𝑃∪𝜃′𝑄K𝑠𝑎𝑡𝜃′. Case

2\𝐾′ 1)),𝜃′𝑄) (1)

1 ⩽𝐾′ 2 is not satisfiable. Our goal follows from the IH on (1), Lemma C.11 on (2), and J𝑃∪(𝐾1 ; 𝜇⩽𝐾2,𝑄)K𝑠𝑎𝑡= J(𝜃′𝑃, 𝜇⩽(𝐾′

2\𝐾′ 1)) ∪𝜃′𝑄K𝑠𝑎𝑡𝜃′.

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.


<!-- page 50 -->

Case

srow(𝜃, 𝑃, (𝐾1 ⩽𝐾2 ; 𝜇2,𝑄)) =

1, 𝐾′ 2,𝜃′) = urow(𝐾1, 𝐾2) assume fresh 𝜇 if set(𝐾′

let (𝐾′

1) ⊆set(𝐾′ 2) then srow(𝜃′𝜃,𝜃′𝑃,𝜃′𝑄) (1) else let 𝜃′′ = [((𝐾′

For the true branch, our goal follows from the IH on (2), Lemma C.3, and

J𝑃∪(𝐾1 ⩽𝐾2 ; 𝜇2,𝑄)K𝑠𝑎𝑡= J𝜃′𝑃∪𝜃′𝑄K𝑠𝑎𝑡𝜃′

For the false branch, our goal follows from the IH on (2), Lemma C.3, and

J𝑃∪(𝐾1 ⩽𝐾2 ; 𝜇2,𝑄)K𝑠𝑎𝑡= J𝜃′′(𝑄, 𝑃)K𝑠𝑎𝑡𝜃′′

1) ⊆set(𝐾′ 2)). Case

srow(𝜃, 𝑃, (𝐾1 ; 𝜇1 ⩽𝐾2 ; 𝜇2,𝑄)) =

1, 𝐾′ 2,𝜃′) = urow(𝐾1, 𝐾2) assume fresh 𝜇 if set(𝐾′

let (𝐾′

1) ⊆set(𝐾′ 2) then srow(𝜃′𝜃, (𝜃′𝑃, 𝜇1 ⩽(𝐾′

srow(𝜃′′𝜃, 𝜇1 ⩽(𝐾′

For the true branch of if, our goal follows from the IH on (1), Lemma C.3, and

J𝑃∪(𝐾1 ; 𝜇1 ⩽𝐾2 ; 𝜇2,𝑄)K𝑠𝑎𝑡= J(𝜃′𝑃, 𝜇1 ⩽(𝐾′

For the false branch of if, our goal follows from the IH on (1), Lemma C.3, and

J𝑃∪(𝐾1 ; 𝜇1 ⩽𝐾2 ; 𝜇2,𝑄)K𝑠𝑎𝑡= J(𝜇1 ⩽(𝐾′

1) ⊆set(𝐾′ 2)). Case

srow(𝜃, 𝑃, (𝐾⊥L,𝑄)) =

Obviously (2) fails when 𝐾⊥L is not satisfiable. Our goal follows the IH on (1). Case

srow(𝜃, 𝑃, (𝐾; 𝜇⊥L,𝑄)) =

Obviously (2) fails when 𝐾; 𝜇⊥L is not satisfiable. Our goal follows the IH on (1).

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

54:50 Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

1\𝐾′ 2) ; 𝜇)/𝜇2]𝜃′

srow(𝜃′′𝜃, ·,𝜃′′(𝑄, 𝑃)) (2)

Both of the above equations follow from the fact that in order to solve 𝐾1 ⩽𝐾2 ; 𝜇2, it is necessary to unify the linearity types of the same labels in 𝐾1 and 𝐾2, and instantiate 𝜇2 with at least other labels only in 𝐾1 (no instantiation needed when set(𝐾′

2\𝐾′ 1) ; 𝜇2),𝜃′𝑄) (1) else let 𝜃′′ = [((𝐾′

1\𝐾′ 2) ; 𝜇)/𝜇2]𝜃′

2\𝐾′ 1) ; 𝜇,𝜃′′(𝑄, 𝑃)) (2)

2\𝐾′ 1) ; 𝜇2) ∪𝜃′𝑄)K𝑠𝑎𝑡𝜃′

2\𝐾′ 1) ; 𝜇) ∪𝜃′′(𝑄, 𝑃)K𝑠𝑎𝑡𝜃′′

Both of the above two equations follow from the fact that in order to solve 𝐾1 ; 𝜇1 ⩽𝐾2 ; 𝜇2, it is necessary to unify the linearity types of the same labels in 𝐾1 and 𝐾2, and instantiate 𝜇2 with at least other labels only in 𝐾1 (no instantiation needed when set(𝐾′

assert dom(𝐾) ∩L = ∅(2) srow(𝜃, 𝑃,𝑄) (1)

assert dom(𝐾) ∩L = ∅(2) srow(𝜃, (𝑃, 𝜇⊥L),𝑄) (1)


<!-- page 51 -->

Soundly Handling Linearity 54:51

Then, we can conclude that if srow𝜄, ·, 𝑃returns (𝜃,𝑄), then we have J𝑃K𝑠𝑎𝑡= J𝑄K𝑠𝑎𝑡𝜃; if it fails, then 𝑃is not satisfiable. Moreover, in 𝑄, row subtyping constraints are all in the forms of 𝜇⩽𝐾 and 𝜇⩽𝐾; 𝜇′.

By Lemma C.12, we have J𝑄K𝑠𝑎𝑡= Jfactorise(𝑄)K𝑠𝑎𝑡. Moreover, in factorise(𝑄), linearity constraints are all in atomic forms, which means they are only between type variables, row variables, and linearity types 𝑌.

Let 𝑄′′ = factorise(𝑄). For trlin(·,𝑄′′) = 𝑄′, we want to show that J𝑄′K𝑠𝑎𝑡= Jfactorise(𝑄)K𝑠𝑎𝑡. Notice that trlin(·,𝑄′′) essentially computes the transitive closure of the linearity constraints in 𝑄′′. Obviously we have J𝑄′′K𝑠𝑎𝑡⊆J𝑄′K𝑠𝑎𝑡. For the other direction, we need to show that for any

{𝜏1 ⪯𝜏2,𝜏2 ⪯𝜏3} ⊆𝑄′ and 𝜃∈J𝜏1 ⪯𝜏2,𝜏2 ⪯𝜏3K𝑠𝑎𝑡, we have · ⊢𝜃(𝜏1 ⪯𝜏3). Notice that the type inference of Q◦

eff only generates linearity constraints of forms Γ ⪯𝜏, which means rows only appear on the RHS. Thus, after factorisation, 𝜃𝜏2 can only be 𝐴or 𝑌. The · ⊢𝜃(𝜏1 ⪯𝜏3) follows from a straightforward case analysis on 𝜃𝜏2.

Finally, if ◦⪯• ∈𝑄′, then 𝑄′ is obviously not satisfiable. Otherwise, we have a trivial solution by substituting all row variables with the empty row ·, value variables with (), and linearity variables with •. We also have J𝑃K𝑠𝑎𝑡= J𝑄′K𝑠𝑎𝑡𝜃, which further implies the trivial solution of 𝑄′ also gives a solution of 𝑃. These results also hold for 𝑄since J𝑄′K𝑠𝑎𝑡= Jfactorise(𝑄)K𝑠𝑎𝑡= J𝑄K𝑠𝑎𝑡. □

Received 2023-07-11; accepted 2023-11-07

Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.
