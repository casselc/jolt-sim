<!-- Generated from yarrow-effects-regions.pdf with pypdf 6.14.2; page boundaries preserved. Consult the PDF for equations and layout. -->



<!-- Page 1 -->

Yarrow: Reconciling Effects Handlers and Region-Based
Memory Management
ANDERS ALNOR MATHIASEN,Aarhus University, Denmark
AMIN TIMANY,Aarhus University, Denmark
LARS BIRKEDAL,Aarhus University, Denmark
We present a new ML-like programming language Yarrow with algebraic effects and region-based memory
management. Reconciling these programming language features into one language is challenging: the non-
local control flow of algebraic effects break the stack discipline of function calls and returns that region-based
memory management relies on, and multi-shot effect handlers break the invariant that regions can be exited
at most once. We present a program logic, called Yarrow Logic (YL), that supports safe and modular reasoning
about regions in the presence of one-shot and multi-shot effect handlers. We prove the logic sound w.r.t. the
operational semantics of Yarrow which is inspired by the runtime of OCaml but refined for regions. We use YL
to prove correctness of a number of case studies with algebraic effects, including checkpointing, asynchronous
computation and a LIFO data structure implementation. Since all memory locations used in these case studies
are allocated in regions, these case studies avoid using the less efficient garbage collected heap memory. We
have formalized the Yarrow’s operational semantics, the Yarrow program logic, and all our case studies using
the Iris separation logic framework on top of the Rocq Prover.
CCS Concepts:•Theory of computation → Logic and verification;Program semantics;Separation
logic;Hoare logic;Higher order logic;Operational semantics.
Additional Key Words and Phrases: Effect handlers, region-based memory management, formal verification,
programming languages, Iris, Rocq
1 Introduction
In recent years, programming language designers have adopted algebraic effects and effect handlers
[22, 36] in a wide variety of programming languages, including OCaml [39], Scala [5], WebAssembly
[35], Koka [26], Links [16], Eff [2], Frank [6] and Flix [29, 30]. Effect handlers give users the ability
to suspend the computation by performing effects, handle the effects using user-defined handlers,
and resume the computation through delimited continuations, where the effect handler is the
delimiter, either at most once (one-shot effects) or multiple times (multi-shot effects). There are
many successful applications of effect handlers; Dolan et al . [12, 13] show how effect handlers
can be used to implement a number of examples ranging from I/O operations and web servers to
asynchronous computation in OCaml. OCaml is known for its efficient runtime implementation
of effect handlers; delimited continuations are implemented by pointers to stack segments called
fibers[ 39]. Each effect handler creates a new fiber when it is installed. This results in an efficient
implementation of one-shot effects as delimited continuations are captured by obtaining a pointer
to a stack segment without additional allocations; multi-shot effects are less efficient and require
copying of continuations.
Region-Based Memory Management.Region-based memory management is a memory allocation
strategy used to avoid garbage collection pressure by instead allocating and safely reclaiming
memory in syntactically scoped program fragments [ 44, 46]. It is seen as a safer way to avoid
garbage collection overhead than full manual memory management, as in C, because region-based
memory management can be mostly automated [45]. Recently, OxCaml [15, 28] introduced region-
based memory management into OCaml by using a rich type system based on modes. One of
Authors’ Contact Information: Anders Alnor Mathiasen, Aarhus University, Denmark, alnor@cs.au.dk; Amin Timany,
Aarhus University, Denmark, timany@cs.au.dk; Lars Birkedal, Aarhus University, Denmark, birkedal@cs.au.dk.
arXiv:2607.15876v1  [cs.PL]  17 Jul 2026

<!-- Page 2 -->

2 Anders Alnor Mathiasen, Amin Timany, and Lars Birkedal
these modes is thelocality modethat controls (when applicable) whether a memory location is
allocated using alocalallocation or aglobalallocation. Global allocations happen on the heap,
while local allocations are placed in memory regions that correspond to the scope of a function.1 At
runtime, Birkedal et al. [3] showed that memory allocations of bounded regions can be placed on
the call stack whereas unbounded regions can use linked-list structures on the heap. The OxCaml
extensions do not support multi-shot effect handlers, and there does not exist any formalization of
the interaction between one-shot effect handlers and the OxCaml extensions.
Yarrow: Incorporating Effect Handlers with Region-Based Memory Management.In this paper,
we formalize an operational semantics for a new language, Yarrow, an ML-like language with
support for algebraic effects (one-shot and multi-shot) based on fibers [ 39], and with region-
based memory management for fine-grained memory control. A first natural question is what the
combined semantics of effect handlers and region-based memory management should be. This is
an inherently non-trivial question, as the non-local control flow that the delimited continuations of
effect handlers introduce complicates reasoning about region configurations. Normally, regions are
nested and follow a stack-like discipline, but with delimited continuations the control can jump
between different groups of nested regions. Moreover, multi-shot effects break the invariant that
regions can be exited at most once. For instance, consider the example below:
1region( (* create a new region *)
2letr =ref local0in(* allocate a reference "r" pointing to "0" in this region *)
3doFoo r; (* perform the effect Foo with argument "r" *)
4!r) (* load from the reference "r" *)
This example uses a memory region created with the region-construct; this means all local allo-
cations within the parentheses following region happen inside the memory associated with this
newly created region. Thus, in this example ref local allocates a reference inside the memory
of the region which is automatically freed when the scope of the region ends (this is contrary to
ref global which is a garbage-collected heap allocation). 2 Thereafter, the operation doFoo r
performs the effect with name Foo and passes it r as an argument. Now, the questions are: What
happens when we load from the reference r? Is it safe? Which value does the load produce? These
are the types of questions we answer in this paper; for this particular example, we argue that if
Foo is a one-shot effect, it is safe to load from r and the return value is 0, whereas if Foo were a
multi-shot effect, it would be unsafe to load from r. When Foo is a one-shot effect, loading from
r is safe because the region that holds r is captured as part of the continuation of Foo, and is
restored when the continuation is invoked. It is unsafe when Foo is a multi-shot effect because we
want to support region-based memory management where the memory of regions are freed upon
exiting them; usingris thus unsafe because multi-shot continuations exit regions multiple times,
and therefore the first invocation of the continuation will free the region before all subsequent
invocations.
Effect Handlers in Separation Logic.The separation logic framework Iris [ 19–21, 23] has been
used to make informal claims about the behavior of programming languages precise,e.g.,for Rust
[7, 18], C [32], OCaml [1, 33, 38], and WebAssembly [25, 37]. One successful application of Iris is to
study effect handlers; De Vilhena and Pottier [9] created a program logic for a language with effect
handlers and mutable heap allocated references. De Vilhena and Pottier [9] enable modular proofs
in that they establish, separately, correctness of effect handlers and of the programs producing the
effects handled by those handlers. This is achieved by specifying effect handlers through so called
1OxCaml features special keywords provided whereby the programmer can have finer control over the allocation [40].
2In Section 2.2, we recap region-based memory management and theregion-construct in more detail.

<!-- Page 3 -->

Yarrow: Reconciling Effects Handlers and Region-Based Memory Management 3
protocols; this is similar to how a function’s specification serves as a contract between the caller
and the callee.
The Yarrow Logic: Reasoning about Effect Handlers and Regions.In this paper we present a new
program logic, the Yarrow Logic (YL), which supports modular reasoning about effect handlers
and region-based memory management in Yarrow. We use YL to specify and prove correctness of
several examples featuring effect handlers, in order to exercise the Yarrow semantics, and a number
of case studies, to show that effect handlers can safely avoid garbage collection by using region-
based memory management. The case studies include checkpointing, asynchronous computation
and a LIFO data structure implementation.3 Reasoning about the language features of Yarrow is
highly non-trivial mainly due to the following two primary challenges introduced by the non-
well-bracketed control-flow of Yarrow, which YL solveswhile retaining the usual modularity of
higher-order impredicative separation logic:
Fine-Grained Reasoning About Region-Allocated Memory In region-based memory man-
agement we need to track the resources corresponding region-allocated memory and revoke them
when the region goes out of scope. This tracking and revocation is very natural [15] when control
flow is well-bracketed [41]. However, when control-flow is non-well-bracketed,e.g.,in the presence
of effect handlers, tracking resources that should be reclaimed becomes very intricate, so much so
that one may expect that the only way to cleanly track these resources is to forgo tracking individual
memory locations that facilitates modular proofs, and instead explicitly reason about regions using
a single high-level logical unit with all information about regions. In YL, however, we show how
reasoning about regions can be decomposed into (1) a logical unit representing configuration of
fibers with associated regions, which only tracks the high-level changes to regions at revocation
points, and (2) stack-points-to propositions (similar to separation logic’s points-to propositions used
for tracking heap allocations), which allow for fine-grained (per memory location) reasoning about
stack-allocated references. At the surface level YL’s approach to enable fine-grained reasoning
about region-allocated memory is not unlike existing approaches [15, 24, 43]; what is particularly
challenging here, and novel, is doing so in the presence of non-well-bracketed control-flow.
The Highly Dynamic Nature of Fibers The high-level configuration of fibers with regions that
we track in YL to enable revocation is subject to sophisticated changes due to the way fibers can
change upon suspension and resumption of computations in effect handlers; an effect handler
may start and end several regions, or install and uninstall other effect handlers, before it uses its
continuation. To solve this challenge in YL, we use the insight that the configuration of fibers and
regions should be a part of the contract between an effect handler and the program performing
the effect — continuations can capture regions when performing effects, making this a point of
temporary revocation for one-shot effects and permanent revocation for multi-shot effects. This
solution has led us to redesign the protocol concept of De Vilhena and Pottier [9] to take into
account the configuration of fibers and regions.
Contributions.In summary, the contributions of this paper are:
• An operational semantics for Yarrow, an ML-like language that supports region-based
memory management in the presence of (one-shot and multi-shot) effect handlers (Section 3).
The presence of effect handlers makes supporting region-based memory management
intricate. Prior work on region-based memory management was designed with the stack
discipline of calls and returns which is violated by the delimited continuations of effect
handlers. Capturing all the nuances of these intricacies requires our operational semantics
3We use the word LIFO here in place ofstackto avoid confusing with the (call) stack of the program that we will regularly
mention in the paper.

<!-- Page 4 -->

4 Anders Alnor Mathiasen, Amin Timany, and Lars Birkedal
to be more fine-grained than was previously needed for modeling languages with either
region-based memory management, or effect handlers.
• A program logic Yarrow Logic (Section 4), abbreviated YL, for proving correctness of Yarrow
programs. Reasoning about Yarrow is highly non-trivial as effect handlers break the property
of well-bracketed control flow, which in the past was relied upon for revocation of resources
tied to regions. Despite the challenges that come with the new combination of language
features, YL retains the modularity and simplicity of existing program logics.
• Specification and verification of a number of case studies, including a LIFO data structure
implementation (Section 5,) checkpointing (Section 6), and asynchronous computation
(Section 7). These case studies show that in certain applications programs written using
effect handlers in Yarrow can replace garbage collected heap references with region allocated
references.
• A mechanization of all results in this paper using the Rocq Prover. The Rocq mechanization
accompanies the paper.
In Section 2, we summarize existing work on effect handler fibers (Section 2.1) and region-based
memory management (Section 2.2).
2 Background
We proceed by explaining how effect handler fibers and region-based memory management work
separately. For the presentation of examples and case studies, we use an OCaml like syntax, the
calculus of our formalization is shown in Section 3.2.
2.1 Background: Effect Handler Fibers
In the runtime of OCaml [39], the stack is made up of a list of segments calledfibers. A new fiber
is created and appended to the list of fibers when an effect handler is installed. We proceed to
explain effect fibers using the Choose and State effects: the Choose effect is used for backtracking
in programs, and the State effect gives users access to a piece of state with the implementation
hidden behind the effect abstraction.
Choose and State effects.Consider the effect handlers in Figure 1 for the Choose and State effects
and ignore the code in the comments for now (we consider this code in Section 3). In the code, the
1lethandle_choose f = (*region( *)
2try(* (global,many) *) f ()with
3|effectChoose p k ->letx = k p.1in lety = k p.2in(x, y) |retx -> x (* ) *)
4
5lethandle_state init f = (*region( *)
6letr =ref(*local*) initin
7try(global,once) f ()with
8|effectState arg k -> (matchargwith| Read -> k !r | Write x -> (r <- x; k ()))
9|retx -> x (* ) *)
10
11letexample1 () = (*region( *)
12letx =doChoose (1, 2)in doState (Write ((doState Read) + x));doState Read (* ) *)
13
14lethandle_example1 () = (*region( *)
15assert(handle_state 0 (fun() => handle_choose example1) = (1, 3)) (* ) *)
Fig. 1.ChooseandStateeffect handlers with a closed example program.
handle_choose definition handles the Choose effect using the try-construct on line 2. Using the
try-construct, it is safe to perform the Choose effect (on line 12) inside the function for which we
install the effect handler (on line 15). Much like a match case, the try-construct has two cases: one
for when f performs the Choose effect (the handler branch), and one for when f terminates with a

<!-- Page 5 -->

Yarrow: Reconciling Effects Handlers and Region-Based Memory Management 5
value (the return branch). The handler branch takes as arguments the user-provided argumentp, and
a delimited continuation k, where the try-construct acts as the delimiter, for resuming computation
inside f at the exact point where the effect was performed. The effect Choose is a multi-shot effect
meaning the continuation can be used more than once; the implementation in Figure 1 makes
use of this by evaluating the continuation k on both components of the argument pair p, after
which it returns the result in a pair. The State effect is implemented on line 7 in handle_state by
internally using the heap allocated reference r. We use Read and Write as notation for sum types,
to branch on whether the user of the effect wants to read or write to the state,i.e., Read is InjL ()
andWrite xisInjR x.
Effect Fibers.Let us consider the layout of fibers at different points in the execution of the
closed example on line 14 in Figure 1. The example1 definition on line 11 uses effect handlers for
Choose and State. On line 14, handle_example1 executes example1 using the two effect handlers
handle_choose and handle_state. The state is initialized to 0 which results in the computation
returning the pair (1, 3) , as both executions created on line 12 with the Choose effect share the
same state of the State effect which behind the effect abstraction is a heap allocated reference. Just
before line 12 is executed the fiber configuration looks like this:
(Initial)
handle_example1()
handle_state()
State
handle_choose()
Choose
example1()
head
The layout of the fibers naturally follows the order in which they were installed (i.e.,they reflect
the order in handle_example1): the head of the list of fibers always points to the most recently
installed fiber, in this case the fiber for the Choose effect. Inside the fiber installed by the Choose
effect handler in handle_choose, we have the call stack for the example1 function. The Choose
fiber then points back to the fiber installed by the State effect handler in handle_state. This fiber
contains the call stack for the handle_choose function. Lastly, the State fiber points back to the
initialfiber; there is always an initial fiber that contains the call stack of the top most functions
of programs before any effect handlers are installed. In this case, the initial fiber contains the call
stack for thehandle_stateandhandle_example1functions.
When the Choose effect is performed on line 12 in Figure 1, computation is suspended and the
control is transferred to the effect handler inside handle_choose. This has the following effect on
the fiber configuration:
(Initial)
handle_example1()
handle_state()
State
handle_choose()
Choose
example1()
head k (Choose)
Above, we see that the head of the list now points to the State fiber. The delimited continuation
k used inside the effect handler Choose is a pointer to the Choose effect fiber; when an effect is
performed, we traverse the list of fibers from the head until the correct fiber is found and create
the continuation as a pointer to this fiber. The continuation k is thus a pointer to all the call stacks
required to resume computation at the point where it was suspended when the Choose effect was
performed. Similarly, when the State effect is performed,e.g.,on line 12 in Figure 1, the fiber
configuration looks like this:
(Initial)
handle_example1()
handle_state()
State
handle_choose()
Choose
example1()
head k (State)

<!-- Page 6 -->

6 Anders Alnor Mathiasen, Amin Timany, and Lars Birkedal
When the computation is resumed inside the effect handlers of State or Choose by using the
continuation, the fibers that the continuation points to are reattached to the list of fibers that the
head points to. Because the Choose effect is a multi-shot effect, in that its continuation k is used
many times in the effect handler on line 3 in Figure 1, we must make copies of the fibers that
the continuation of the Choose effect points to. Thus, when the continuation is used to resume
computation in the Choose effect, we create a copy of the fibers that the continuation points to
and attach those to the list of fibers that the head points to which still leaves the original fibers in
memory:
(Initial)
handle_example1()
handle_state()
State
handle_choose()
Choose
example1()
Choose
example1()
head k (Choose)
2.2 Background: Region-based Memory Management
In this subsection, we focus on regions (without effects). In the introduction (Section 1), we already
saw a glimpse of the kind of regions we consider in this paper, namely regions à la Tofte and Talpin
[46] but with a construct similar to that of Lorenzen et al. [28] and Georges et al. [15]. The work
by Lorenzen et al. [28] and Georges et al. [15] differentiates between two types of allocations: local
allocations in regions and global allocations on the heap. To be precise, the construct region(...) ,
as seen in the introduction (Section 1), is used to create lexically scoped regions with a stack-like
discipline. All local allocations are associated with the lexically nearest enclosing region construct.
Alocality mode 𝑙, which can be either local or global, is used to differentiate between local
allocations in regions and global heap allocations; a reference to a value v is allocated using ref
l v where l is the locality mode. We remark that the mode notation used here are arguments
to program constructs and not types (our examples are untyped). 4 To explain the semantics of
regions, and in particular nested regions, we consider the example in Figure 2. In this example, both
1letfoo x =region(lety =ref local1in(x, y))
2
3letbar () =region(letx =ref local0in letp = foo xin!p.1)
Fig. 2. Nested Regions Example.
functions create a region around their function bodies. Indeed, as a default we associate regions
with function bodies (insertion of region can be delegated to the compiler), but exceptions can
be made to this to principle, we will see one example of where such an exception can be useful
later in Section 7. The function foo returns a pair; the first entry in the pair is the argument that
foo receives, and the second entry is a reference, locally allocated in its own region. The function
bar creates a local allocation x in its region, and then calls foo with x before it loads from the
first value of the pair that it has gotten from the call to foo. Below, we visualize how the stack
evolves throughout the execution of the function bar, the arrows signify changes to the stack
configuration:
(free)
bar()
[𝑥↦→0]
(free)
bar()
[𝑥↦→0]
foo()
[𝑦↦→1]
bar()
[𝑥↦→0]
(free)
(free)
Each stack configuration consists of zero or more stack frames. Under each stack frame, we also
write the allocations made in the region associated with the function that uses this stack frame.
4In the work by Georges et al. [15] they use mode annotations on types with the @ character and superscript notation for
modes of program constructs.

<!-- Page 7 -->

Yarrow: Reconciling Effects Handlers and Region-Based Memory Management 7
We emphasize that regions are not necessarily a part of the stack frame, the memory underlying
regions can be placed elsewhere on the heap (usually bounded regions go on the call stack and
unbounded on the heap, see Birkedal et al . [3]). However, the regions follow a stack discipline,
enforced by the region-construct: before foo returns the pair in Figure 2, the stack configuration is
as the third configuration above with the call stack, and thus the regions of both functions are still
in memory. Afterfooreturns its pair and execution resumes inbar, we move along to the fourth
stack configuration, where the region of foo has been freed, and its contents deallocated. When
bar loads from the first entry in the pair it received from foo, it is safe as the first entry is x, which
is still alive, in bar’s own region. Note that if the load had been from the second entry in the pair,
it would have been unsafe, asylived in the region offoo, which was freed whenfooreturned.
3 Operational Semantics
In this section, we explain our new operational semantics for Yarrow, an ML-like language with effect
handlers and region-based memory management. Before we explain the details of the operational
semantics in (Section 3.2), we give an overview of the ideas behind the operational semantics in
(Section 3.1).
3.1 Overview
We introduce our new operational semantics using two examples, the first example focuses on
one-shot effects and the second example focuses on multi-shot effects.
One-Shot Effects.For one-shot effects, we illustrate the point that continuations capture regions,
and we can resume the computation with captured regions as if nothing happened to these regions
(this is not the case when we consider multi-shot effects next). Consider theIncrement First Example
in Figure 3. The figure contains two definitions handle_inc_fst and example2; at the end of
1lethandle_inc_fst f =region(
2letx =ref local0in
3try(global,once) f xwith|effectIncFst p k -> (p.1 <- !p.1 + 1); k () |retx -> x)
4
5letexample2 x =region(
6lety =ref local1in letp = (x, y)in doIncFst p;assert(!p.1 + !p.2 = 2))
7
8lethandle_example2 () =region(handle_inc_fst example2)
Fig. 3. Increment First Example.
the figure,handle_inc_fstis applied toexample2inhandle_example2. After creating a region,
handle_inc_fst allocates a reference x pointing to 0 locally in this region. Next, an effect handler
with operation IncFst is installed around the application of the function argument to the newly
allocated reference x. In our ML-like language, we use mode notation with the try-construct: the
try-construct takes a pair of modes where the first entry is a locality mode that can be global or
local, just like with references, and the second entry is an affinity mode that can be either once or
many. The locality mode controls whether the closure of the continuation k in the handler branch
is allocated locally in the region where the effect handler is situated, or globally on the heap. Later
in Section 3.2, we will also see how function closures can be allocated in regions instead of on the
heap, but all the functions we have seen so far have non-empty closures. The affinity mode controls
whether the effect is a one-shot or multi-shot effect,i.e.,how many times the continuation k can be
called in the handler branch. For the effect handler in the handle_inc_fst definition of Figure 3,
we use the (global,once) combination; once because it is a one-shot effect, and for simplicity
to focus on the core parts of regions and effects, we use the global mode in this example (our
case studies use continuations with locally allocated closures). The handler branch for the IncFst

<!-- Page 8 -->

8 Anders Alnor Mathiasen, Amin Timany, and Lars Birkedal
effect handler takes a pair and increments a reference in the pair’s first entry. No assumptions are
made about the second entry in the pair. Moving our attention to the example2 definition, this
function performs the effect IncFst with argument p. The first entry of the pair p is the function
argument x of example2, whereas the second entry is a reference y locally allocated inside the
regionexample2. The configuration of fibers just beforedoIncFst pis executed is this:
(Initial)
handle_example2()
[]
handle_inc_fst()
[𝑥↦→0]
IncFst
example2()
[𝑦↦→1]
head
Above, we first notice how, contrary the fiber configurations in Section 2.1, we have regions inside
the stack frames like we saw in Section 2.2. This fiber configuration is made up of two fibers: the
head of the list points to the fiber installed by the IncFst effect, the IncFst fiber again points to
the initial fiber that always marks the end of the stack. Observe how the two entries in the pair p
used as argument to the effect IncFst are references allocated in different regions: x is allocated
in the region created by handle_inc_fst, and the reference y is allocated in the region created
by the example2 function. Further, the example2 function is located in the fiber installed by the
IncFst effect handler; this follows the structure of the program in Figure 3 where example2 is
called after the IncFst effect handler is installed. When doIncFst p is executed on line 6, and
control is transferred to the effect handler, the fiber configuration changes to this:
(Initial)
handle_example2()
[]
handle_inc_fst()
[𝑥↦→0]
IncFst
example2()
[𝑦↦→1]
head k (IncFst)
The head now points to the initial fiber, and the continuation available inside the handler branch
of the IncFst effect points to the IncFst fiber. As the handler branch increments the reference x,
through the first entry in the pair it receives, it executes safely; the referencex sits in a region which
is a part of the current stack. We deem it undefined behavior to use local references that are not part
of the current stack,i.e.,local references allocated in a region of a fiber that a continuation points
to and not a fiber from the list of fibers that make up the current stack, such as if the increment
had instead been done on the reference y through the second entry in the pair. We classify this
as undefined behavior for two reasons: (1) to ensure that references can only be used inside the
scope of the region they belong to; and (2) to ensure that reclamation of memory used by fibers of
continuations should not depend on anything except on how the continuation itself is used.
After the handler branch of IncFst is executed, and the computation is resumed by using the
continuation, the fiber configuration is this:
(Initial)
handle_example2()
[]
handle_inc_fst()
[𝑥↦→1]
IncFst
example2()
[𝑦↦→1]
head
We are back to the configuration we had before doIncFst p was executed on line 6, except the
handler branch of IncFst has been executed in the meantime which results in x now storing
1 inside the region of the handle_inc_fst function. This fiber configuration implies that the
assertion on line 6 holds.
Multi-Shot Effects.For multi-shot effects, we can not resume computation using continuations
that capture regions as part of the fibers they point to and keep using the reference allocated in

<!-- Page 9 -->

Yarrow: Reconciling Effects Handlers and Region-Based Memory Management 9
these regions. To illustrate this, we reconsider the example with the multi-shot effect Choose and
the one-shot effect State from Figure 1, but this time we also take into account the code in the
comments for regions and modes. The reference r inside the handler for the State effect on line
6 is now a local reference. We use the once mode for the State effect and the many mode for the
Choose effect, as they are respectively one-shot and multi-shot effects. Just before theChoose effect
is performed on line 12, the fiber configuration is shown below:
(Initial)
handle_example1()
[]
handle_state()
[𝑟↦→𝑥]
State
handle_choose()
[]
Choose
example1()
[]
head
This is similar to the fiber configuration we saw in Section 2.1 when we also considered the
closed example handle_example1. This time regions are included in the fiber configuration which
illustrates that the reference used to implement the State effect sits inside the region of the
handle_state function. When the Choose effect is performed on line 12, and control is suspended
to the effect handler inhandle_choose, the fiber configuration changes to this:
(Initial)
handle_example1()
[]
handle_state()
[𝑟↦→𝑥]
State
handle_choose()
[]
Choose
example1()
[]
head k (Choose)
The continuation of Choose captures a fiber with an empty region, but this is not always the case;
suppose the Choose effect handler is instead installed before the State handler as in the code
below:
1lethandle_unsafe () =region(handle_choose (fun() => handle_state 0 example1))
Before performing the Choose effect in example1 using handle_unsafe, the fiber configuration
looks like this:
(Initial)
handle_unsafe()
[]
handle_choose()
[]
Choose
handle_state()
[𝑟↦→𝑥]
State
example1()
[]
head
This fiber configuration reflects that we have changed the order in which the effect handlers are
installed. When the Choose effect is performed in example1, the fiber configuration changes to the
one below where the continuation k in the Choose effect handler now captures both the Choose
andStatefiber which includes a non-empty region:
(Initial)
handle_unsafe()
[]
handle_choose()
[]
Choose
handle_state()
[𝑟↦→𝑥]
State
example1()
[]
head k (Choose)
The local reference inside the region ofhandle_state is implicitly deallocated when the multi-shot
effect Choose is performed. This is because the continuationk captures the region ofhandle_state,
and k is a multi-shot continuation; multi-shot continuations are created by making copies of the
fibers they point to, but we can not make copies of regions and continue using the same local
references inside these regions when computation is resumed as the underlying memory of the
regions may have changed. When computation is resumed using the continuation k in the effect

<!-- Page 10 -->

10 Anders Alnor Mathiasen, Amin Timany, and Lars Birkedal
handler for Choose, we return to the fiber configuration we had before Choose was performed
insideexample1on line 12 in Figure 1, except the referenceris no longer useable:
(Initial)
handle_unsafe()
[]
handle_choose()
[]
Choose
handle_state()
[]
State
example1()
[]
Choose
handle_state()
[𝑟↦→𝑥]
State
example1()
[]
head k (Choose)
This fiber configuration implies that it is unsafe to use the State effect on lines 19 and 20 in
the example1 function when using handle_unsafe, as the reference r is no longer useable as
part of the list of fibers that makes up the current stack (the data x may still be in the region of
handle_stateat ruuntime but not at the locationrrepresents)
Skippable Detour about References and Multi-Shot Continuations.References captured by multi-
shot continuations can be reused under certain limiting restrictions. For example, Muhcu et al .
[34] describes a runtime for a language with multi-shot effects where continuations can capture
stack reference as part of their closures by using an indirection layer for references. In that setting,
a stack reference points to a reference in an indirection layer which again points to the actual
stack. This approach [34], however, only works as long as no two continuations ever capture the
same fiber. If we instead took the approach of reusing the same region, or parts of it, for multiple
fibers to preserve the local references, we could no longer implement true region-based memory
management where the memory underlying regions is freed when code exits the scope of a region;
a region can be exited more than once, making it unsafe for all invocations of a continuation, apart
from the very first, to use memory in regions.
3.2 Syntax and Operational Semantics
In this subsection, we formalize the operational semantics for the runtime model we gave an
overview of in the previous subsection. We start by defining the expressions and values of our
language below, where we omit products, sums, unary operators and binary operators for brevity.
𝑧∈Z𝑥∈Varℓ∈Loc𝑖𝑑∈Cidop∈Operation
𝛼∈Addresses ::=saℓ|haℓ 𝑙∈Locality ::=local|global𝑎∈Affinity ::=once|many
𝑣∈Val ::=()|𝑧|𝑥|true|false|𝛼|𝜆 (𝛼,𝑖𝑑)𝑓 𝑥.𝑒|cont (𝛼,𝑖𝑑)𝐾𝜃 ℓ|kont (𝛼,𝑖𝑑)𝐾𝜃|...
𝑒∈Expr ::=𝑣|𝑥|let𝑥=𝑒 1 in𝑒 2|𝑒 1𝑒2|if𝑒 1 then𝑒 2 else𝑒 3|ref𝑙 𝑒|!𝑒|𝑒 1←𝑒 2
|(match𝑒 1 with inl𝑥 1 =>𝑒 1|inj𝑥 2 =>𝑒 2)|𝜆 𝑙 𝑓 𝑥.𝑒|region𝑒|end𝑒
|doop𝑒|effectop𝑣 𝐾|(try(𝑙,𝑎)𝑒 1 withop𝑥k.𝑒 2|ret𝑥.𝑒 3)
|(installed(𝑙,𝑎)𝑒 1 withop𝑥k.𝑒 2|ret𝑥.𝑒 3)|...
Expressions and values.The values in the language include the unit value, integers, booleans,
addresses,𝜆-abstractions, one-shot continuations and multi-shot continuations. Addresses are
either stack addresses saℓ , with a logical locationℓ, or heap address haℓ likewise with a location.
In𝜆-abstractions,𝑓 is the recursive occurrence in the body𝑒,𝑥 is an argument and the superscript
pair(𝛼,𝑖𝑑) says that the closure of the abstraction, represented with closure identifier𝑖𝑑, is located
at address𝛼. This way of tracking closures is similar to Georges et al. [15], even though Georges
et al. formalize a language without effect handlers. For our continuation values, we use the same
mechanism for tracking closures. The value for one-shot continuations cont(𝛼,𝑖𝑑)𝐾 𝜃 ℓ, takes
an evaluation context𝐾 (which we define momentarily), a list of fibers𝜃 (which we also define
momentarily) and a locationℓ that is used to enforce the affinity of one-shot continuations. The

<!-- Page 11 -->

Yarrow: Reconciling Effects Handlers and Region-Based Memory Management 11
value for multi-shot continuation kont(𝛼,𝑖𝑑)𝐾𝜃 is like the value for one-shot continuations, except
it does not have the location that we use to enforce affinity.
The expressions in our language include values, variables, conditionals, sequencing, function
application, allocation of local references in regions and global references on the heap, and ex-
pressions for storing and loading from references. In addition, we have the expression version of
𝜆-abstractions𝜆𝑙 𝑓 𝑥.𝑒 ; this is used to allocate function closures, the local mode𝑙 in superscript
determines whether the closure is allocated in a region or on the heap. Further, we have theregion𝑒
expression for starting a region around an expression𝑒, and end𝑒 which we use to mark when the
scope of a region ends and its underlying memory is freed. Lastly, we have a number of expressions
for algebraic effects: we saw the expression try(𝑙,𝑎)𝑒 1 withop𝑥 k.𝑒 2|ret𝑥.𝑒 3 throughout
Section 3.1 for installing an effect handler and its underlying fiber. Contrary to the previous work
on program logics for effect handlers in Iris [9], our try-expression is parameterized with a pair
of modes for controlling whether the effect is one-shot or multi-shot, and if the closure of the
continuation𝑘 is allocated locally in a region or on the heap. Similarly to how end𝑒 marks the
end of a region, installed(𝑙,𝑎)𝑒 1 withop𝑥 k.𝑒 2|ret𝑥.𝑒 3 marks the end of a fiber: when the
expression𝑒1 evaluates to a value, the return branch𝑒3 is executed with the value as argument,
and at the same time, the fiber with operation op that the try-expression installed is removed from
the list of fibers that make up the stack. When an effect is performed using doop𝑒 , we use the
mechanism from De Vilhena and Pottier [9] to capture the paused computation in continuation
values, this is what the effectop𝑣 𝐾 expression is for. We will see exactly how this works when
we present the reduction rules of our language which are based onevaluation contexts.
Evaluation contexts.We use evaluation contexts 𝐾 below to define the language.• is the empty
context, and𝐾[𝑒] is notation for filling out the evaluation context𝐾 with the expression𝑒. We list
the evaluation contexts of our language below (again, we omit contexts for products, sums, unary
operators and binary operators for brevity).
𝑁′∈NeutralCtx′ ::=let𝑥=•in𝑒|𝑒• | •𝑣|if•then𝑒 1 else𝑒 2|ref𝑙• |!•
|𝑒←•|•←𝑣|(match•with inl𝑥 1 =>𝑒 1|inj𝑥 2 =>𝑒 2)
|end• |doop• |(installed(𝑙,𝑎) •withop𝑥k.𝑒 2|ret𝑥.𝑒 3)|...
𝐾′∈EvalCtx′ ::=𝑁 ′|(try(𝑙,𝑎) •withop𝑥k.𝑒 1|ret𝑥.𝑒 2)
𝑁∈NeutralCtx ::=• |𝑁 ′[𝑁]𝐾∈EvalCtx ::=• |𝐾′[𝐾]
We say that all evaluation contexts except those for the try-expression areneutral contexts. We
need neutrals contexts for when we capture delimited continuations using the effectop𝑣 𝐾
expression in the reduction rules.
Reduction Rules.The program state ( ℎ,𝜃,ℓ𝑠, ids) used in our reduction rules is defined as follows:
ℎ∈Heap≜Loc fin− −⇀(Val+Cid)ℓ𝑠∈Locations≜P fin(Loc)ids∈Identifiers≜P fin(Cid)
𝛿∈StackFrame≜List(Loc×(Val+Cid))𝜔∈StackFrames≜List StackFrame
𝜃∈Fibers≜List(Operation×StackFrames)𝜎∈State≜Heap×Fibers×Locations×Identifiers
The state consists of the heapℎ, the installed fibers𝜃 that make up the stack, a set of locationsℓ𝑠 and
a set of closure identifiers ids. In the definition of the heapℎ above, we see how the heap consists
of both values and closure identifiers. We keep around a set ids in the program state that tracks all
the previous used closure identifiers to make sure we are always generating new identifiers when
allocating closures. Fibers, like the heap, can contain both values and closure identifiers. Fibers
are built using a number of layers: we retain the association of regions with stack frames that we
used throughout Section 3.1. Thus, fibers are defined as a list of stack frames, a stack frame is a list

<!-- Page 12 -->

12 Anders Alnor Mathiasen, Amin Timany, and Lars Birkedal
of locations paired with either the value or the closure identifier that it stores. The set of logical
locationsℓ𝑠 tracks all the locations that have previously been used for local allocations. When
making a local allocation, we can not rely on the fibers in the program state alone when we want to
generate a fresh location; continuation values also hold fibers with locations in them. Thus, we use
the setℓ𝑠 for new local locations. Having defined the program state, we can define the reduction
relations for the operational semantics of Yarrow.
Our small-step operational semantics consists of two reduction relations, the base step (⇝) and
the context step (→), both with signature Expr×State→Expr×State . The context step only has
one reduction rule shown below:
Evaluation-Context-Step
(𝑒,H)⇝(𝑒 ′,H′)
(𝐾[𝑒],H)→(𝐾[𝑒 ′],H′)
This rule evaluates an expression in an evaluation context 𝐾[𝑒] to𝐾[𝑒′], if there is a base step
reduction from𝑒 to𝑒′. In Figure 4, we display an excerpt of base step reduction rules unrelated to
algebraic effects, and in Figure 5 we display another excerpt that are about algebraic effects. First,
we focus on the reduction rules that are not about algebraic effects.
In theRegionrule, the region𝑒 expression steps to the end𝑒 expression, doing so, a new empty
stack frame is inserted in the most recently installed fiber. When the expression𝑒 in end𝑒 evaluates
to a value𝑣, the top most stack frame𝛿 is freed, in theEndrule, which effectively ends the region
as the local references that reside in the stack frame𝛿 are no longer available. Local references are
always allocated into the top most stack frame which corresponds to the most recently started
region (Alloc-Local). Loads and stores to local references (Store-Local) happen in place in the stack
frame where the reference was allocated. Global heap references follow a standard semantics,e.g.,
Alloc-GlobalandLoad-Global. We also use references when allocating function closures: In the
Function-GlobalandFunction-Localrules, the expression 𝜆𝑙 𝑓 𝑥.𝑒 steps to the value𝜆(𝛼,𝑖𝑑)𝑓 𝑥.𝑒 ,
𝑖𝑑 is a new identifier in the set of closure identifiersids in the program state. The superscript(𝛼,𝑖𝑑)
indicates that the address of the closure for this 𝜆-abstaction (the closure being represented by
the closure id𝑖𝑑) is𝛼. The address𝛼, which is either a heap address or a stack address depending
on the locality mode𝑙, is set to point to the closure identifier. When applying a𝜆-abstaction, we
check in the antecedent of theApplicationrule whether the closure is still alive. That is, the phrase
"(𝛼,𝑖𝑑)alive in𝜎 " means that the address𝛼 points to𝑖𝑑 on the heapℎ in𝜎, or the address𝛼 points
to𝑖𝑑in any of the stack frames inside the fibers𝜃in the state𝜎.
Next we turn our attention to the reduction rules in Figure 5 about algebraic effects. The
Handler-Installrule installs an effect handler and its corresponding fiber by stepping from the
try-expression to the corresponding installed-expression. Initially, the newly installed fiber in
the program state does not have any stack frames. As mentioned earlier in this subsection, when
an effect is performed using doop𝑣 , the expression is replaced with effectop𝑣• (see theDo
rule). From here, an evaluation context𝐾 is created in the effectop𝑣 𝐾 expression by consuming
surrounding neutral context using theEffrule. If the evaluation context surrounding effectop𝑣𝐾
is not a neutral context, it is by definition a try-context. When such a context is encountered, and
the operation of the effectop𝑣 𝐾 expression is not the same as the operation in the try-context,
we simply keep propagating upwards in the program, as shown in theHandler-NEQrule, until
the correct effect handler is found. When the operation matches and the correct effect handler is
found, we create a continuation value and substitute it, together with the argument provided when
performing the effect, into the handler branch of the effect handler, seeHandler-Global-Multiand
Handler-Local-One. For instance, in theHandler-Global-Multireduction rule, where the effect is

<!-- Page 13 -->

Yarrow: Reconciling Effects Handlers and Region-Based Memory Management 13
Regionregion𝑒,(ℎ,𝜃++[(op, 𝜔)],ℓ𝑠,ids)  ⇝ end𝑒,(ℎ,𝜃++[(op, 𝜔++[[]])],ℓ𝑠,ids)  
Endend𝑣,(ℎ,𝜃++[(op, 𝜔++[𝛿])],ℓ𝑠,ids)  ⇝ 𝑣,(ℎ,𝜃++[(op, 𝜔)],ℓ𝑠,ids) 
Alloc-Global
ℓfresh inℎ
ref global𝑣,(ℎ,𝜃,ℓ𝑠,ids)  ⇝ haℓ,(ℎ[ℓ:=𝑣],𝜃,ℓ𝑠,ids) 
Alloc-Local
ℓfresh inℓ𝑠
ref local𝑣,(ℎ,𝜃++[(op, 𝜔++[𝛿])],ℓ𝑠,ids)  ⇝ saℓ,(ℎ,𝜃++[(op, 𝜔++[𝛿++[(ℓ,𝑣)]])],ℓ𝑠⊎{ℓ},ids) 
Load-Global!(haℓ),(ℎ[ℓ:=𝑣],𝜃,ℓ𝑠,ids)  ⇝ 𝑣,(ℎ[ℓ:=𝑣],𝜃,ℓ𝑠,ids) 
Store-Local(saℓ)←𝑣 1,(ℎ,𝜃 1++[(op, 𝜔1++[𝛿1++[(ℓ,𝑣 2)]++𝛿 2]++𝜔 2)]++𝜃 2,ℓ𝑠,ids)  ⇝(),(ℎ,𝜃 1++[(op, 𝜔1++[𝛿1++[(ℓ,𝑣 1)]++𝛿 2]++𝜔 2)]++𝜃 2,ℓ𝑠,ids) 
Function-Global
ℓfresh inℎ 𝑖𝑑fresh inids
(𝜆global 𝑓 𝑥.𝑒),(ℎ,𝜃,ℓ𝑠,ids)  ⇝ (𝜆(haℓ,𝑖𝑑) 𝑓 𝑥.𝑒),(ℎ[ℓ:=𝑖𝑑],𝜃,ℓ𝑠,ids⊎{𝑖𝑑}) 
Function-Local
ℓfresh inℓ𝑠 𝑖𝑑fresh inids
(𝜆local 𝑓 𝑥.𝑒),(𝜃++[(op, 𝜔++[𝛿])],ℓ𝑠,ids)  ⇝(𝜆(saℓ,𝑖𝑑) 𝑓 𝑥.𝑒),(ℎ,𝜃++[(op, 𝜔++[𝛿++[(ℓ,𝑖𝑑)]])],ℓ𝑠⊎{ℓ},ids⊎{𝑖𝑑}) 
Application
(𝛼,𝑖𝑑)alive in𝜎
(𝜆(ℓ,𝑖𝑑) 𝑓 𝑥.𝑒)𝑣,𝜎  ⇝ 𝑒[(𝜆(ℓ,𝑖𝑑) 𝑓 𝑥.𝑒)/𝑓][𝑣/𝑥],𝜎 
Fig. 4. Selected base steps reduction rules.
multi-shot and the continuation closure is stored on the heap, the continuation value
kont(haℓ,𝑖𝑑)(installed(global,many)𝐾withop𝑥k.𝑒 1|ret𝑥.𝑒 2)([(op,𝜔)]++𝜃 2)
is created. This value is made up of three parts: (1) the pair(haℓ,𝑖𝑑) stating that the continuation
closure represented by the identifier𝑖𝑑 is stored in the heap locationℓ (2) an evaluation context
consisting of𝐾, from the effectop𝑣 𝐾 expression, around which the effect handler is reinstalled
(reinstalling the effect handler givesdeep-handlersemantics contary toshallow-handlers where the
effect handler is not reinstalled) (3) the fibers that the continuation captures,i.e.,all the fibers up
to and including the fiber installed by effect handler. TheHandler-Local-Onerule follows a similar
pattern when creating a continuation value for a one-shot effect with a locally allocated closure,
except the continuation closure is stored at a stack locationℓ2, and we allocate a heap locationℓ1 to
enforce that the continuation is not called more than once:
cont(saℓ 2,𝑖𝑑)(installed(local,once)𝐾withop𝑥k.𝑒 1|ret𝑥.𝑒 2)([(op,𝜔)]++𝜃 2)ℓ 1.
For brevity, we have omitted the reduction rules for multi-shot effects with local continuation
closures and one-shot effects with global continuation closures, the rules we have shown (Handler-
Global-MultiandHandler-Local-One) cover all the aspects of the rules. When applying a one-shot

<!-- Page 14 -->

14 Anders Alnor Mathiasen, Amin Timany, and Lars Birkedal
Dodoop𝑣,𝜎  ⇝ effectop𝑣•,𝜎  Eff𝑁[effectop𝑣 𝐾],𝜎  ⇝ effectop𝑣 𝑁[𝐾],𝜎 
Handler-Installtry(𝑙,𝑎)𝑒withop𝑥k.𝑒 1|ret𝑥.𝑒 2,(ℎ,𝜃,ℓ𝑠,ids)  ⇝installed(𝑙,𝑎)𝑒withop𝑥k.𝑒 1|ret𝑥.𝑒 2,(ℎ,𝜃++[(op,[])],ℓ𝑠,ids) 
Handler-NEQ
op≠op ′
installed(𝑙,𝑎)(effectop𝑣 𝐾)withop ′𝑥k.𝑒 1|ret𝑥.𝑒 2,𝜎  ⇝effectop𝑣(installed(𝑙,𝑎)𝐾withop ′𝑥k.𝑒 1|ret𝑥.𝑒 2),𝜎 
Handler-Local-One
ℓ1 fresh inℎ ℓ 2 fresh inℓ𝑠 𝑖𝑑fresh inids opnot installed in𝜃 2
installed(local,once)(effectop𝑣 𝐾)withop𝑥k.𝑒 1|ret𝑥.𝑒 2,
(ℎ,𝜃 1++[(op′,𝜔++[𝛿])]++[(op,𝜔)]++𝜃 2,ℓ𝑠,ids)  ⇝𝑒1[𝑣/𝑥][(cont (saℓ 2,𝑖𝑑)(installed(local,once)𝐾withop𝑥k.𝑒 1|ret𝑥.𝑒 2)([(op,𝜔)]++𝜃 2)ℓ 1)/k],
(ℎ[ℓ 1 :=false],𝜃 1++[(op′,𝜔++[𝛿++[(ℓ 2,𝑖𝑑)]])],ℓ𝑠⊎{ℓ 2},ids⊎{𝑖𝑑}) 
Handler-Global-Multi
ℓfresh inℎ 𝑖𝑑fresh inids opnot installed in𝜃 2
installed(global,many)(effectop𝑣 𝐾)withop𝑥k.𝑒 1|ret𝑥.𝑒 2,(ℎ,𝜃 1++[(op,𝜔)]++𝜃 2,ℓ𝑠,ids)  ⇝𝑒1[𝑣/𝑥][(kont (haℓ,𝑖𝑑)(installed(global,many)𝐾withop𝑥k.𝑒 1|ret𝑥.𝑒 2)([(op,𝜔)]++𝜃 2))/k],
(ℎ[ℓ:=𝑖𝑑],𝜃 1,ℓ𝑠,ids⊎{𝑖𝑑}) 
Handler-Returninstalled(𝑙,𝑎)𝑣withop𝑥k.𝑒 1|ret𝑥.𝑒 2,(ℎ,𝜃++[(op,𝜔)],ℓ𝑠,ids)  ⇝ 𝑒2[𝑣/𝑥],(ℎ,𝜃,ℓ𝑠,ids) 
Cont-One
(𝛼,𝑖𝑑)alive in(ℎ,𝜃 1,ℓ𝑠,ids)
(cont(𝛼,𝑖𝑑)𝐾𝜃 2ℓ)𝑣,(ℎ[ℓ:=false],𝜃 1,ℓ𝑠,ids)  ⇝ 𝐾[𝑣],(ℎ[ℓ:=true],𝜃 1++𝜃2,ℓ𝑠,ids) 
Cont-Multi
(ℓ,𝑖𝑑)alive in(ℎ,𝜃 1,ℓ𝑠,ids)
(kont(𝛼,𝑖𝑑)𝐾𝜃 2)𝑣,(ℎ,𝜃 1,ℓ𝑠,ids))⇝ 𝐾[𝑣],(ℎ,𝜃 1++(𝑟𝑒𝑚_𝑙𝑜𝑐𝑠𝜃 2),ℓ𝑠,ids) 
Fig. 5. Selected base step reduction rules for effects handlers.
continuation in theCont-Onereduction rule, we make sure the continuation has not been used
before by checking that the location ℓ points to false, whereafter we flip it to true, and that the
continuation closure is still alive like in theApplicationrule. afterwards, the continuation is called
with a value𝑣 by placing𝑣 in the context of the continuation value𝐾 and pushing the fibers of the
continuation value𝜃2 onto the stack. InCont-Multi, when a continuation of a multi-shot effect is
called, we use the function𝑟𝑒𝑚_𝑙𝑜𝑐𝑠 to remove the locations in the fibers𝜃2, that is,𝑟𝑒𝑚_𝑙𝑜𝑐𝑠𝜃 2
contains all the same stack frames as 𝜃2, except the stack frames are empty. Because the stack
frames are not removed, but emptied, the program𝐾[𝑣] that the continuation resumes, can allocate
into and end the regions it had already started, but it can not use the references that were already
allocated in these regions. This models our approach to multi-shot continuations explained in
Section 3.1.

<!-- Page 15 -->

Yarrow: Reconciling Effects Handlers and Region-Based Memory Management 15
4 Program Logic
In this section, we present the Yarrow Logic (YL), an Iris-based program logic built to reason about
the operational semantics of Yarrow we presented in Section 3.2. YL inherits all the reasoning
principles from Iris; we assume familiarity with separation logic and use this section to present the
features that are unique to YL.
4.1 Overview
Modular reasoning about memory in the Yarrow programming language is different from other
programming languages, as we have to (1) reclaim resources used to reason about memory in
regions that are captured by continuations, and (2) take into account that the configurations of
fibers and regions can change between suspending and resuming computations in effect handlers;
an effect handler can potentially start and end regions, or install and uninstall other effect handlers,
before using its continuation. Our choice oflogical resourcesand theeffectfull weakest precondition
are key to how we handle this complexity.
Logical resources.We use three types of logical resources. The first two are points-to resources
used for the stack and the heap.
ℓ↩→𝑣heap locationℓstores value𝑣(or alternatively a closure identifier,i.e.,ℓ↩→𝑖𝑑)
ℓ↦→𝑣stack locationℓstores value𝑣(or alternatively a closure identifier,i.e.,ℓ↦→𝑖𝑑)
As we assume that the memory pointed to by heap references are freed by the garbage collector,
YL, like most other Iris-based program logics, does not have to handle revocation of heap points-to
resources explicitly; they can simply be dropped in proofs when they are no longer needed; this is
always allowed in Iris since it is anaffineseparation logic,i.e.,we have 𝑃∗𝑄⊢𝑃 , for any𝑃 and𝑄.
Stack points-to resources used for references allocated in regions are, however, different: when
a region ends, or a continuation captures fibers when an effect is performed, we need to reclaim
stack points-to resources as it is unsafe to keep using them according to the operational semantics.
Therefore, the proofs in our logic use an additional resource, Stack𝜅 , to keep track of the shape of
the stack,i.e.,the state of the list of fibers that make up the stack. This allows us to always know
which stack points-to resources to revoke when regions end or effects are performed. To formally
describe the resource Stack𝜅we need the following additional definitions:
𝜋∈Domain≜List Loc𝜏∈Domains≜List Domain𝜅∈FiberDomains≜List(Operation×Domains)
Stack𝜅the current stack is made of out fibers with the domains represented by𝜅
The Stack𝜅 resource together with stack points-to resources for all the locations in𝜅 gives us the
full picture of the fibers that make up the stack, as FiberDomains only differ from physical Fibers
except for the values stored in the fibers’ locations which can be recovered from the stack points-to
resources. As we will see later in this section, proofs of programs that do not use region-based
memory management or effect handlers, can forget about this resource.
Effectful weakest precondition.To prove safety of programs in YL, we use an effectful weakest
precondition ewp𝑒 𝜌{Φ}, where the effect row 𝜌 represent the effects that are handled in the
expression𝑒, and the postcondition Φ is a predicate on the return value of the expression𝑒. The
type of the effect row is shown below and uses the notion of aprotocol. Our protocols are based on
a similar definition by De Vilhena and Pottier [9], but are redesigned to the fiber domains take into
account.
Ψ∈Protocol≜Val→FiberDomains→(Val→FiberDomains→iProp)→iProp
𝜌∈Row≜Operation fin− −⇀(Affinity×Protocol)

<!-- Page 16 -->

16 Anders Alnor Mathiasen, Amin Timany, and Lars Birkedal
Protocols work as a contract between the program performing the effect and the effect handler.
Our protocols are parameterized by the fiber domains used by the Stack resource since both the
effect handler and the program performing the effect can useStack𝜅 for revocation and we need to
know the state of𝜅 they are handed by the other end. We delay explaining the details of protocols
till we present the reasoning rules for performing and handling effects, as these are the only rules
that use them.
The Adequacy Theorem.Proofs in our logic start by assuming the resource Stack([(−,[[]])])
and no points-to resources. This reflects that when a program starts executing, we install an
initial fiber with one initial region, such that programs can make local allocations at the top level
without having to explicitly start a region. Specifically, the adequacy theorem of YL used to prove
safety and functional correctness of programs says:For all expressions 𝑒 and postcondition Φ , if
Stack([(−,[[]])])⊢ewp𝑒 𝜌{Φ} is provable,𝑒 can always take a step in the operational semantics of
Section 3.2 or it reduces to a value𝑣for whichΦ(𝑣)holds.
4.2 Reasoning Rules
We divide the reasoning rules of the program logic into four categories that we proceed to go over:
(1) structural rules (2) rules for memory management (3) rules for performing effects and (4) rules
for handling effects. In Section 4.3, we show how the reasoning rules can be used to verify an
example.
Structural rules.We display selected structural rules of the program logic in Figure 6. The rules
reassemble those of other Iris-based separation logics, except we have to take into account the
effect row in the effectful weakest precondition. For instance, in the monotonicity rules (EWP-
Mono and EWP-Mono-Pers) and the frame rules (EWP-Frame and EWP-Frame-Pers), we use the
persistently modality when the effect row𝜌 in ewp𝑣 𝜌{Φ} is not exclusively a row with one-shot
effects;𝜌 lists the effects𝑒 can use and if𝑒 can perform an effect where the continuation in the
effect handler is used multiple times (a multi-shot effect), then it is unsound to rely on exclu-
sively owned resources when modifying the postcondition, intuitively since we are modifying the
Φ𝑣⊢ewp𝑣 𝜌{Φ} (EWP-Val)
𝑜𝑛𝑐𝑒 𝜌−∗(∀𝑣.Φ𝑣−∗Ψ𝑣)∗ewp𝑣 𝜌{Φ}⊢ewp𝑣 𝜌{Ψ} (EWP-Mono)
(∀𝑣.Φ𝑣−∗Ψ𝑣)∗ewp𝑣 𝜌{Φ}⊢ewp𝑣 𝜌{Ψ} (EWP-Mono-Pers)
ewp𝑒 {𝑣.ewp𝑁[𝑣] 𝜌{Φ}}⊢ewp𝑁[𝑒] 𝜌{Φ} (EWP-Bind)
𝑒1 ⇝pure𝑒2∗⊲(ewp𝑒 2𝜌{Φ})⊢ewp𝑒 1𝜌{Φ} (EWP-Pure)
𝑜𝑛𝑐𝑒 𝜌−∗𝑃−∗ewp𝑒 𝜌{Φ}⊢ewp𝑒 𝜌{𝑣.Φ𝑣∗𝑃 } (EWP-Frame)
𝑃−∗ewp𝑒 𝜌{Φ}⊢ewp𝑒 𝜌{𝑣.Φ𝑣∗𝑃 } (EWP-Frame-Pers)
𝜌′⊆𝜌−∗ewp𝑒 𝜌′{Φ}⊢ewp𝑒 𝜌{Φ} (EWP-Row1)
Ψ′⊑Ψ−∗ewp𝑒 (op:(𝑎,Ψ ′))·𝜌{Φ}⊢ewp𝑒 (op:(𝑎,Ψ))·𝜌 {Φ} (EWP-Row2)
op1 ≠op 2∗ewp𝑒 (op2 :(𝑎 2,Ψ2))·(op 1 :(𝑎 1,Ψ1))·𝜌{Φ}
ewp𝑒 (op1 :(𝑎 1,Ψ1))·(op 2 :(𝑎 2,Ψ2))·𝜌{Φ} (EWP-Row3)
Fig. 6. Selected structural program logic rules.
postcondition every
time the continua-
tion is used to re-
sume computation
in𝑒. There are three
structural rules in-
volving effect rows:
EWP-Row1 rows lets
the user of the logic
weaken the effect
row (subset inclu-
sion on effect rows
𝜌1 ⊆𝜌 2 holds if
every binding in 𝜌1
occurs in𝜌2), EWP-
Row2 is used for
weakening a proto-
col (ordering of pro-
tocols is defined as
Ψ1⊑Ψ 2 ≜

<!-- Page 17 -->

Yarrow: Reconciling Effects Handlers and Region-Based Memory Management 17
(∀𝑣𝜅Φ.Ψ 1𝑣𝜅Φ−∗Ψ 2𝑣𝜅Φ) ) 5, and the order of effects that have different operations can be
changed using EWP-Row3 (·is the cons operation on effect rows).
Rules for memory management.Selected rules for memory management are shown in Figure 7.
The rules for managing the heap (EWP-Alloc-Global and EWP-Store-Global) are standard for Iris-
based program logics. The rules for managing fibers, and the regions we associate with stack frames
in the fibers, centers around the Stack𝜅 resource; EWP-Region inserts a new empty stack frame in
the most recently installed fiber. A local allocation (EWP-Alloc-Local) inserts a new location, in
the top most stack frame of the most recently installed fiber, and gives the user a stack points-to
resourceℓ↦→𝑣 . The rules for manipulating local references,e.g.,(EWP-Local-Local), work just like
their counterpart rules for the heap,i.e.,owning a points-to resource for a locationℓ implies that the
location belongs to a region that has not ended yet; what makes this possible is how deallocation
of stack points-to resources work: when a region ends in the EWP-End rule, one has to provide
the points-to resources for all the stack reference allocated in the region, and then, separately,
prove (without those stack points-to resources) that the postcondition Φ holds for a stack without
the topmost region. Lastly, we have allocation of functions (EWP-Func-Global) and application of
⊲(∀ℓ.ℓ↩→𝑣−∗Φ(haℓ))⊢ewpref global𝑣 𝜌{Φ} (EWP-Alloc-Global)
Stack(𝜅++[(op,𝜏++[𝜋])])∗
⊲(∀ℓ.Stack(𝜅++[(op,𝜏++[𝜋++[(ℓ,𝑣)]])])∗ℓ↦→𝑣−∗Φ(saℓ))
ewpref local𝑣 𝜌{Φ} (EWP-Alloc-Local)
ℓ↩→𝑤∗⊲(ℓ↩→𝑣−∗Φ())⊢ewp(haℓ)←𝑣 𝜌{Φ} (EWP-Store-Global)
ℓ↦→𝑣∗⊲(ℓ↦→𝑣−∗Φ𝑣)⊢ewp!(saℓ) 𝜌{Φ} (EWP-Load-Local)
Stack(𝜅++[(op,𝜏)])∗⊲(Stack(𝜅++[(op,𝜏++[])])−∗ewpend𝑒 𝜌{Φ})
ewpregion𝑒 𝜌{Φ} (EWP-Region)
Stack(𝜅++[(op,𝜏++[𝜋])])∗ ∗
(ℓ,𝑣)∈𝜋
ℓ↦→𝑣∗⊲(Stack(𝜅++[(op,𝜏)])−∗Φ𝑣)
ewpend𝑣 𝜌{Φ} (EWP-End)
⊲(∀ℓ𝑖𝑑.ℓ↩→𝑖𝑑−∗Φ(𝜆 (haℓ,𝑖𝑑) 𝑓 𝑥.𝑒))⊢ewp𝜆 global 𝑓 𝑥.𝑒 𝜌{Φ} (EWP-Func-Global)
ℓ↦→𝑖𝑑∗⊲(ℓ↦→𝑖𝑑−∗ewp𝑒[(𝜆 (saℓ,𝑖𝑑) 𝑓 𝑥.𝑒)/𝑓][𝑣/𝑥] 𝜌{Φ})
ewp(𝜆 (saℓ,𝑖𝑑) 𝑓 𝑥.𝑒)𝑣 𝜌{Φ}
(EWP-App-Local)
Fig. 7. Selected program logic rules for memory management.
functions (EWP-App-Local) which also interferes with memory management as function closures
are explicitly allocated in our language. Allocation of function closures is much like allocation
of references: in EWP-Func-Global, the user of the logic obtains a heap points-to resource with
a locationℓ pointing to an identifier𝑖𝑑, the(haℓ,𝑖𝑑) pair is then used in the𝜆-abstraction value
(allocation of local closures in region happens similarly, except the fibers are updated as in EWP-
Alloc-Local). When applying a lambda abstraction, one must show that the function closure is
not deallocated by proving ownership of the points-to resource associated with the𝜆-abstraction
5For a proposition 𝑃 , the persistently modality  enforces that𝑃 should be proven without any exclusive owned resources
such as a point to resource.

<!-- Page 18 -->

18 Anders Alnor Mathiasen, Amin Timany, and Lars Birkedal
value (EWP-App-Local); for globally allocated function closures, this is easy as heap points-to
resources are persistent, but stack points-to resource are deallocated when regions end as seen in
the EWP-End rule.
Rules for performing effects.Suppose we perform an effect with operation op as in the proof
goal of the EWP-Do-Once rule in Figure 8,i.e.,we want to prove ewpdoop𝑣 (op:(once,Ψ))·𝜌 {Φ}.
Here, the effect row tells us that the effect we are performing is a one-shot effect, and that the
contract we have with the effect handler is described by the protocol Ψ. First, we have to provide
the stack resource Stack(𝜅 1++[(op,𝜏)]++𝜅 2) together with stack points-to resources for a map
𝑚:∗ (ℓ,𝑣)∈𝑚 ℓ↦→𝑣 . The predicate isValid op𝜏 𝜅2𝑚 asserts that𝜅2 does not contain a fiber with
operation op, and the references in 𝑚 are equal to those allocated in the fibers ([(𝑜𝑝,𝜏)]++𝜅 2).
Intuitively, this means we have found the fiber of the effect we are performing, and at the same
time, deallocated all the references that the continuation of the effect handler captures in its regions.
The last obligation of the EWP-Do-Once rule, is to satisfy the protocol:
Ψ𝑣𝜅 1(𝜆𝑤𝜅 ′
1.Stack(𝜅 ′
1++[(op,𝜏)]++𝜅 2)−∗ ∗
(ℓ,𝑣)∈𝑚
ℓ↦→𝑣−∗Φ𝑤)
We always instantiate a protocol with the argument value𝑣 provided when performing the effect,
the part of the stack𝜅1 that the effect handler is executing with, and a proof obligation that must
be proven when resuming computation with a continuation using return value𝑤 and a potentially
updated stack described by𝜅′
1 (the stack can change if the effect handler starts or end regions and
installs or uninstall other effect handlers). The proof obligation says that given the stack resource
where the fibers that the continuation captured are back onto the stackStack(𝜅′
1++[(op,𝜏)]++𝜅 2),
and ownership of the stack points-to resources for the references in the regions of these fibers
∗ (ℓ,𝑣)∈𝑚 ℓ↦→𝑣 , the post-condition of the proof goal we had when performing the effect Φ𝑤 must
hold. The only difference between the EWP-Do-Once rule for one-shot effects and the EWP-Do-
Many rule for multi-shot effects is that when proving the post-condition of the proof goal we had
when performing the effect Φ𝑤 , we can not assume stack points-to resources for the references in
the regions of the fibers that the continuation capture.
∃𝜅1𝜅2𝜏𝑚.Stack(𝜅 1++[(op,𝜏)]++𝜅 2)∗ ∗
(ℓ,𝑣)∈𝑚
ℓ↦→𝑣∗isValid op𝜏𝜅 2𝑚
∗⊲Ψ𝑣𝜅 1(𝜆𝑤𝜅 ′
1.Stack(𝜅 ′
1++[(op,𝜏)]++𝜅 2)−∗ ∗
(ℓ,𝑣)∈𝑚
ℓ↦→𝑣−∗Φ𝑤)
ewpdoop𝑣 (op:(once,Ψ))·𝜌 {Φ} (EWP-Do-Once)
∃𝜅1𝜅2𝜏𝑚.Stack(𝜅 1++[(op,𝜏)]++𝜅 2)∗ ∗
(ℓ,𝑣)∈𝑚
ℓ↦→𝑣∗isValid op𝜅 1[(op,𝜏)]𝜅 2𝑚
∗⊲Ψ𝑣𝜅 1(𝜆𝑤𝜅 ′
1.Stack(𝜅 ′
1++[(op,𝜏)]++𝜅 2)−∗Φ𝑤)
ewpdoop𝑣 (op:(many,Ψ))·𝜌 {Φ} (EWP-Do-Many)
protAff𝑎Ψ∗Stack𝜅∗⊲hdlGlbΨ Φ 𝑒 𝑥𝑘ℎ𝑦𝑟 𝜌m𝜌Φ∗
⊲ Stack(𝜅++[(op,[])])−∗ewp𝑒 (op:(𝑎,Ψ))·𝜌 {𝑣.∃𝜅 ′.Φ𝑒𝜅′𝑣∗Stack(𝜅 ′++[(op,[])]) }
ewp(try(global,𝑎)𝑒withop𝑥k.ℎ|ret𝑦.𝑟) 𝜌{Φ} (EWP-Try)
Fig. 8. Selected program logic rules performing and handling effects.
Rules for handling effects.To install an effect handler with allocation of the continuation closure
on the heap, we use the EWP-Try rule in Figure 8 for both one-shot and multi-shot effects. In this
rule, we must first prove that the affinity of the protocolΨ matches the affinity variable𝑎 (we return

<!-- Page 19 -->

Yarrow: Reconciling Effects Handlers and Region-Based Memory Management 19
to how this is done when we present an example protocol in Section 4.3) and provide the stack
resource Stack𝜅 . There is then two proof obligations: (1) prove the handler branch and the return
branch are safe to execute; this is captured by the hdlGlb predicate whose definition we return to
shortly, and (2) prove that the expression𝑒 for which the effect is installed is safe to execute; this is
captured by the following proof goal:
Stack(𝜅++[(op,[])])−∗ewp𝑒 (op:(𝑎,Ψ))·𝜌 {𝑣.∃𝜅 ′.Φ𝑒𝜅′𝑣∗Stack(𝜅 ′++[(op,[])]) }
Here we get to assume the stack resource Stack(𝜅++[(op,[])]) with a fiber installed for the new
effect. Moreover, the new effect is added to the effect row in the effectful weakest precondition with
affinity𝑎 and protocol Ψ (it is here we see that the protocol is picked as part of the EWP-Try rule
and then used as a contract for performing the effect in𝑒, because the protocol Ψ is added to the
effect row in the effectful weakest precondition). As postcondition we must prove a user-defined
postcondition Φ𝑒, which we instantiate with the return value 𝑣 and the updated fiber domains
𝜅’. Moreover, we must also prove that the current fibers can be described by the stack resource
Stack(𝜅′++[(op,[])]) which captures that the top most fiber is the one for the effect with operation
op, but that all the fibers before that are now 𝜅′. The reason why the fibers installed before the
effect with operation op can change from𝜅 to𝜅′, is that inside𝑒, effects in the effect row𝜌 may
be performed, and the handlers for these effects can install other effects and, perhaps more likely,
make changes to regions bye.g.,allocating local references.
Next, we turn our attention to the hdlGlb predicate which captures the correctness of the return
branch𝑟 and the handler branchℎ. We show its definition in Figure 9. This predicate is divided
hdlGlbΨ Φ 𝑒 𝑥𝑘ℎ𝑦𝑟 𝜌m𝜌Φ≜m𝜌=once→𝑜𝑛𝑐𝑒 𝜌∧?m𝜌 
(∗Return branch:∗)
(∀𝑣𝜅.Φ 𝑒𝜅𝑣−∗Stack𝜅−∗ewp𝑟[𝑣/𝑦] 𝜌{Φ})∧
(∗Handler branch:∗)
(∀𝑣𝑘′.(∃𝜅.Stack𝜅∗Ψ𝑣𝜅(𝜆𝑤𝜅 ′.∀Φ′.Stack𝜅 ′−∗⊲hdlGlbΨ Φ 𝑒 𝑥𝑘ℎ𝑦𝑟 𝜌m𝜌Φ ′−∗ewp𝑘′𝑤 𝜌{Φ′}))
−∗ewpℎ[𝑘′/𝑘][𝑣/𝑥] 𝜌{Φ})
Fig. 9. Definition of the predicatehdlGlb.
into two parts, one part for the return branch and one part for the handler branch. Around the two
branches, there is a persistently modality conditional on the affinity mode m𝜌; when m𝜌 is once,
we can remove the persistently modality and use exclusively owned resources to prove correctness
of the return and handler branche. When the persistently modality is removed, it is required that
the existing effect row𝜌 can not have any multi-shot effects. This is because the program𝑒 that
we install the effect for can also use effects from 𝜌. If𝑒 uses a multi-shot effect, then execution
can enter the handler branchℎ and return branch𝑟 many times without using a continuation (we
reason about the use of continuations inside the proof of the handler branch), thus we can not rely
on exclusively owned resources when reasoning aboutℎand𝑟.
In the proof of the return branch𝑟, we get to assume the postcondition Φ𝑒𝜅𝑣 of the expression
𝑒 that the effect was installed for, where𝑣 is the return value of𝑒 and𝜅 corresponds to the fiber
domains when the execution of𝑒ended, together with the stack resource𝑆𝑡𝑎𝑐𝑘𝜅. In the proof of
the handler branchℎ,𝑘′ is the continuation which, together with the argument𝑣 that was used to
perform the effect, is substituted for the binders𝑘 and𝑥. We can assume the stack resource𝑆𝑡𝑎𝑐𝑘𝜅 ,
and the protocol instantiated as below:
Ψ𝑣𝜅(𝜆𝑤𝜅 ′.∀Φ′.Stack𝜅 ′−∗⊲hdlGlb𝑎Ψ Φ 𝑒 𝑥𝑘ℎ𝑦𝑟 𝜌m𝜌Φ ′−∗ewp𝑘′𝑤 𝜌{Φ′})

<!-- Page 20 -->

20 Anders Alnor Mathiasen, Amin Timany, and Lars Birkedal
Here we see the opposite end of the contract that a protocol Ψ establishes between an effect
handler and the program performing the effect (we saw the other end when we discussed the
EWP-Do-Once and EWP-DO-Many rules). Note that in the handler branch we get to assume the
protocol above, it is not a proof obligation. The predicate on the value𝑤 and the fiber configuration
𝜅′ that we instantiate the protocol with on this end, says that to get a proof of the effectful weakest
precondition for the continuation𝑘′ that we can use in the proof of the handler branch, we must
provide the stack resource Stack𝜅′ and prove the recursive occurrence of the hdlGlb predicate. The
reason we have to prove the recursive occurrence of the hdlGlb predicate is that the effect handler
is reinstalled as a part of the continuation.
In the last few paragraphs, we went over the proof rules for installing an effect handler with the
global mode global. If we want to use the local mode local, and install the continuation closure
in the region that the effect handler uses, we need to use a version of EWP-Try with the only
modification that hdlGlb is instead hdlLoc. We have included the definition of hdlLoc in Section B;
it works like hdlGlb, except each time the continuation is used in the handler branch, one must
prove that the continuation closure is still alive by showing ownership of a stack points resource
(the resource is provided as part of hdlLoc) with the closure identifier that the continuation uses —
like when applying a function in the EWP-App-Local rule.
4.3 Verification of the Increment First Example
In this subsection, we go over a proof sketch of the Increment First Example in Figure 3 with a focus
on using the rules for performing and handling effects. Our goal is to prove Stack([(−,[[]])])⊢
ewphandle_example2 () ⟨⟩{True}, so we can use the adequacy theorem of YL from Section 4.1.
We skip forward a bit to line 3 in the code just before the try-construct. At this point in the
proof, we have the resources Stack([(−,[[] ;[];[x]])]) and x↦→ 0reflecting that the regions
of handle_example2 and example2 are started, and in the region started in example2, we have
allocated the local reference x on line 2. As we need to reason about an effect handler, we use
the EWP-Try rule. Using this rule, we must pick a protocol that captures the interaction between
the effect handler and example2 x which is the code the effect is installed for. Throughout this
paper, we use a particular type of protocol, namely thesend-receive protocol, an extension of the
send-receive protocolfrom De Vilhena and Pottier [9] that also takes into account the fiber domains
used with theStackresource:
!®𝑥(𝑣1,𝜅 1){𝑃}.𝑎?®𝑦(𝑣2,𝜅 2){𝑄} ≜𝜆𝑣𝜅Φ.∃®𝑥.𝑣=𝑣 1∧𝜅=𝜅 1∗𝑃 𝑥∗?𝑎(∀®𝑦.𝑄−∗Φ𝑣 2𝜅2).
When a send-receive protocol on the form above is used, it means that the value argument𝑣 must
be equal to𝑣1 and that the fiber domains, when the fibers that the continuation capture are removed
from the stack, must be equal to𝜅1. The proposition𝑃 works as a precondition that the proof of
a program performing the effect must prove, and the effect handler can assume when handling
the effect. When the effect handler calls the continuation, it must call it with an argument equal
to𝑣2, be in a state where the fiber domains is equal to 𝜅2 (𝜅2 are the fiber domains before the
fibers of the continuation are appended to the stack) and prove that𝑄 holds. Thus,𝑃 and𝑄 can be
seen as precondition and postcondition of doop𝑣 . To make the send-receive protocol expressive,
it existentially quantifiers over the list of variables ®𝑥in𝑣1,𝜅1,𝑃,𝑣2,𝜅2 and𝑄, and universally
quantifiers over®𝑦in𝑣2,𝜅2 and𝑄. The affinity variable𝑎 controls whether the send-receive protocol
is for a one-shot or multi-shot effect; to control this we use thepersistently modality . When the
affinity variable𝑎 is once, ?𝑎𝑃 is the same as 𝑃 , whereas if𝑎 is many, the persistently modality
is removed and ?𝑎𝑃 becomes𝑃. In essence, the persistently modality is conditional on𝑎. The
send-receive protocol, together with the composition protocol below that composes two existing

<!-- Page 21 -->

Yarrow: Reconciling Effects Handlers and Region-Based Memory Management 21
protocols, are the only protocols we use in this paper:
Ψ1+Ψ 2 ≜𝜆𝑣𝜅Φ.Ψ 1𝑣𝜅Φ∨Ψ 2𝑣𝜅Φ
Here is the concrete send-receive protocol we use in this proof with the EWP-Try rule:
𝐼𝑁𝐶≜!𝜅 ℓ 1ℓ2𝑧((saℓ 1,saℓ 2),𝜅){ℓ1↦→𝑧}.once?((),𝜅){ℓ1↦→(𝑧+1) }
The𝐼𝑁𝐶 protocols says that the effect can be called with a pair of stack addresses(saℓ 1,saℓ 2) as
argument and any fiber domains𝜅 in the Stack resource. For the first entry in the pair, the stack
points-to resourceℓ1↦→𝑧 is required as precondition. When the effect resumes, it resumes with
the unit value and the fiber domains unchanged. The postcondition gives back the stack points-to
resource but the integer it points to is now incremented:ℓ 1↦→(𝑧+1).
Now that we have picked our protocol, we can proceed to prove the effect handler on line 3 in
Figure 3 and the code example2 x that the effect is installed for, as required by the EWP-Try rule.
When a protocol Ψ is made using send-receive and composition protocols, showing theprotAff𝑎Ψ
obligation in the EWP-Try rule amounts to checking whether the affinity variable used in send-
receive protocols matches𝑎. 6 In this example, we are installing the effect handler on line 3 with
theoncemode which matches the mode used in the𝐼𝑁𝐶protocol.
Proof of example2.The example2 function is used with the argument x for which we have the
stack points-to resource x↦→ 0. Because Stack([(−,[[] ;[];[x]])]) is the state of the stack resource
prior to using EWP-Try, we can also assume the stack resourceStack([(−,[[] ;[];[x]]) ;(IncFst,[])]) ,
updated with a new fiber for the effect handler that we just installed. We pick Φ𝑒 ≜𝜆𝜅_.𝜅=
[(−,[[];[];[x]])]∗x↦→1in EWP-Try which makes our proof obligation this:
ewpexample2 x (IncFst:(once, 𝐼𝑁𝐶)) {Stack([(−,[[];[];[x]]);(IncFst,[])])∗x↦→1 }
Let us skip ahead in the proof to where the effect is performed. Here, we have the resources
Stack([(−,[[] ;[];[x]]) ;(IncFst,[[𝑦]])]) , x↦→ 0and y↦→ 1, which reflects that the region of
example2started and the referenceyis allocated in it. The proof obligation at this point is:
ewpdoIncFst (x, y) (IncFst:(once, 𝐼𝑁𝐶))

𝑣.ewpend(𝑣;assert(!x + !y = 2)) (IncFst:(once, 𝐼𝑁𝐶))
{Stack([(−,[[];[];[x]]);(IncFst,[])])∗x↦→0}
	
Because the effect we want to perform is a one-shot effect, as seen in the effect row of our proof
obligation, we use the EWP-Once rule. This rule says that we must provide the stack points-to
resources for all locations of the regions in the fibers up to and including the fiber for the effect we
perform, as these are the fibers captured by the continuation. In our case, this amounts to the fiber
(IncFst,[[y]]) with one region containing y which we provide the stack points-to resource y↦→ 1
for. The fiber[(−,[[] ;[];[x]]) prior to the(IncFst,[[y]]) fiber, is what the effect handler gets to
use, but the protocol𝐼𝑁𝐶 makes no assumption about the contents of this fiber. As we are using(x,
y) as argument to the effect, the protocol𝐼𝑁𝐶 says that we must providex↦→ 0to the effect handler.
The postcondition of the protocol gives us back the stack points-to resource incremented (x↦→ 1),
and the fiber prior to the IncFst fiber is left unchanged. When proving the postcondition of our
proof obligation, we also get to assumeStack([(−,[[] ;[];[x]]) ;(IncFst,[[y]])]) ; because we were
performing a one-shot effect, y is still in region of the top most fiber, and we get back the points-to
resource y↦→ 1. With these resources in hand, we can prove the postcondition of the proof obligation
we listed above by satisfying the assertion and ending the top most regions with y in it, which
consumes the y↦→ 1resource and leaves us with theStack([(−,[[] ;[];[x]]) ;(IncFst,[])])∗x↦→ 1
resources as needed.
6In the Rocq formalization, we useupward closuresof protocols as in de Vilhena [8], but the protocols used in this paper are
equivalent to their upward closures when theprotAffproperty holds.

<!-- Page 22 -->

22 Anders Alnor Mathiasen, Amin Timany, and Lars Birkedal
Proof of the effect handler.In the proof obligation about the effect handler in the EWP-Try rule,
we instantiate the hdlGlb predicate which amounts to two proof obligations, one for the handler
branch and one for the return branch. The obligation for the return branch is shown below and
follows from the EWP-End rule:
Stack[(−,[[];[];[x]])]∗x↦→1⊢ewpend(end𝑣) ⟨⟩{True}
The resources 𝑆𝑡𝑎𝑐𝑘[(−,[[] ;[];[𝑥]])] and x↦→ 1are what example_2 x that we installed the
effect for return. Thus the above goal completes the proof obligations about the return branch
and the code surrounding the effect handler which amounts to ending the two regions started in
handle_inc_fstandhandle_example2. For the handler branch, the proof obligation is:
Stack𝜅∗x↦→0⊢ewp((x, y).1 <- !(x, y).1 + 1); k () ⟨⟩{ewpend(end𝑣) ⟨⟩{True}}
It is the𝐼𝑁𝐶 protocol that dictates we get to assume x↦→ 0and Stack𝜅 for some fiber domains𝜅.
Further, the protocol gives us the specification for calling the continuation in the hdlGlb predicate,
which says that if we can prove the recursive occurrence of the hdlGlb predicate, we get to assume:
Stack𝜅∗x↦→1⊢ewpk () ⟨⟩{ewpend(end𝑣) ⟨⟩{True}}
The specification captures that calling the continuation with the effect handler reinstalled (the
effect handler is reinstalled as part of k), the postcondition for the return branch that we already
proved holds (the return branch is used because the effect handler is reinstalled), as long as we can
prove the precondition of the continuation Stack𝜅∗x↦→ 1⊢ as specified by the𝐼𝑁𝐶 protocol. The
proof obligation for the handler branch follows from the specification of the continuation above,
as the increment of x turns x↦→ 0into x↦→ 1, leaving us with the exact resources and proof goal
needed. The only missing obligation is to prove the recursive occurrence of the hdlGlb predicate,
but this follow from Löb induction ((⊲𝑃⇒𝑃)⊢𝑃 ) applied at the beginning of the proof of the
effect handler.
5 Case Study: LIFO data structure
In this section, we show how an effect-handler implementation of a data structure can be made
with local allocations. We focus on a LIFO data structure,i.e.,a stack, but the approach generalizes
to other data structure implementations. The code of the LIFO data structure implementation is
shown in Figure 10, where handle_lifo is the implementation of the data structure. Our approach
to implementing data structures using local allocations in regions is to make all allocations in the
region of the effect handler; this also includes the continuations’ closure. In the implementation of
the LIFO effect, we use a local reference r that always points-to the element that was last inserted.
The LIFO data structure is implemented as a linked-list of local references and has two operations,
an Insert and a Remove operation defined using sum types. To handle the Insert operation with
argument x (x is the value we want to insert into the data structure), we load the head of the list
from therreference, and then updaterwith an optional of a newly allocated local reference; the
reference points to a pair of the value x and the previous head of the list hd. The protocol for the
LIFO effect that we define shortly makes sure that we never try to use the Insert operation on the
empty data structure, hence we never assert false on line 8. Thus, when the Remove operation is
used, we can update r by setting it to the next element in the linked-list that makes up the LIFO
data structure. Below is the LIFO effect protocol that we have proven the implementation against:
LIFO op𝜏lifo≜!𝜅 𝜋 1𝑥 𝑥𝑠(Insert𝑥,𝜅++[(op,𝜏++[𝜋 1])]){𝑙𝑖𝑓𝑜𝑥𝑠 𝜋 1}.once
?𝜋2((),𝜅++[(op,𝜏++[𝜋 2])]){𝑙𝑖𝑓𝑜(𝑥::𝑥𝑠)𝜋 2}
+!𝜅 𝜋1𝑥 𝑥𝑠(Remove,𝜅++[(op,𝜏++[𝜋 1])]){𝑙𝑖𝑓𝑜(𝑥::𝑥𝑠)𝜋 1}.once
?𝜋2(𝑥,𝜅++[(op,𝜏++[𝜋 2])]){𝑙𝑖𝑓𝑜𝑥𝑠𝜏 2}

<!-- Page 23 -->

Yarrow: Reconciling Effects Handlers and Region-Based Memory Management 23
Central to the specification is a predicate lifo𝑥𝑠 𝜋 that keeps track of the contents of the data
structure using a list𝑥𝑠, and a stack domain𝜋 which corresponds to the region of the effect handler.7
The list 𝑥𝑠 in the predicate changes as one would expect from using the Insert and Remove
operations. The stack domain𝜋 also changes, during the operation, from𝜋1 when performing the
LIFO effect to some other stack domain𝜋2 when resuming computation. We see that𝜋1 does in fact
correspond to the top most stack frame when performing the effect, as the protocol asserts that
the fiber domains in which the effect handler gets to execute are𝜅++[(op,𝜏++[𝜋 1])] (remember
these are the fiber domains after the fibers of continuation has been captured). Likewise, the effect
handler’s stack frame has changed to𝜋2 during the execution of the effect handler, as the protocol
states that the fiber domains are𝜅++[(op,𝜏++[𝜋 1])] when resuming computation (this is the state
before the fibers that the continuation capture are reinserted). This models that the implementation
of the specification can use the region associated with the top most stack frame to make local
allocations during the execution of the operations that the effect exposes; the fiber for the operation
opis some arbitrary fiber where theLIFOeffect handler is installed.
1lethandle_lifo f =region(
2letr =ref localNonein
3try(local,once) f ()with
4|effectLIFO arg k ->
5matchargwith
6| Insert x ->lethd = !rinr <- (Some (ref local(x, hd))); k ()
7| Remove ->match!rwith| None ->assertfalse | Some r ->letp = !rinr <- p.2; k p.1
8|retx -> x)
9
10letexample3 () =region(
11doLIFO (Insert 1);doLIFO (Insert 2);doLIFO (Insert 3);
12assert(doLIFO Remove = 3);assert(doLIFO Remove = 2);assert(doLIFO Remove = 1))
13
14lethandle_example3 () =region(handle_lifo example3)
Fig. 10. LIFO data structure implementation.
6 Case study: Checkpointing
In this section, we use an effect handler to implemented locally allocated checkpoints; checkpoints
are continuations that we store in the region where the effect handler is installed together with the
closure of the continuation. The implementation of theCheckpoint effect is shown in Figure 11, and
it is inspired by a similar example by Muhcu et al. [34]. The Checkpoint effect has two operations:
a Save operation to save a checkpoint at the current line in the code, and a Retry operation to
resume computation from the last saved checkpoint. In the definition of example4 on line 7, we
see how a checkpoint is saved. The branching on line 9, based on the value of the local reference r
allocated in the region ofhandle_example, goes to theif-branch asrpoints to0initially. In the
if-branch, r is incremented whereafter we retry computation from the checkpoint that was saved
on line 8 such we never have to assert false. Now that r is no longer 0, we go to the else branch
where the assertion holds. The implementation of the Checkpoint effect on line 8 in Figure 11
uses a local reference to store checkpoints in form of continuations. Continuations are locally
allocated in the region of the handle_checkpoint function. Checkpoint is a multi-shot effect,
because when a checkpoint is saved, we store the continuation, such that it can be called later
when the retry operation of the effect is performed, but we also use the continuation to resume
computation immediately.
7In the Rocq formalization, we have a lemma to exchange between lifo𝑥𝑠𝜋 and stack points-to resources that correspond
to the references in𝜋, these resources can be used to prove the isValid predicate in the EWP-DO rules when performing
other effects. Similar lemmas exist for the definitions used in the protocols of the other case studies that mention stack
domains or fiber domains.

<!-- Page 24 -->

24 Anders Alnor Mathiasen, Amin Timany, and Lars Birkedal
Below, we specify theCheckpointeffect with a protocol:
𝐶𝐻𝐸𝐶𝐾𝑃𝑂𝐼𝑁𝑇op𝜏cp noCp𝑃 1𝑃2 ≜
!𝜅1𝜋1(Save,𝜅 1++[(op,𝜏++[𝜋 1])]){(noCp𝜋 1∨cp𝜋 1)∗𝑃 1(𝜅1++[(op,𝜏)]) }.many
?𝜅2𝜋2((),𝜅 2++[(op,𝜏++[𝜋 2])]){cp𝜋 2∗(𝑃 1(𝜅2++[(op,𝜏)])∨𝑃 2(𝜅2++[(op,𝜏)])) }
+!𝜅 𝜋(Retry,𝜅++[(op,𝜏++[𝜋])]) {cp𝜋 1∗𝑃 2(𝜅++[(op,𝜏)]) }.many?((),[]) {False}
The protocol uses a number of predicates: cp and noCp are defined as part of the proof of the
effect handler, whereas𝑃1 and𝑃2, which we will refer to as user defined, are defined as part of
the proof of the program that uses the effect (c.f.the proof structure in Section 4.3). Ownership
of cp𝜋 signifies that a checkpoint is installed, and noCp𝜋 that no checkpoint is installed; these
predicates are parametrized over the stack frame of the Checkpoint effect handler as this contains
the region used to store checkpoints. To save a checkpoint, we must provide either noCp𝜋 1
or cp𝜋 1 as precondition together with the user defined predicate 𝑃1(𝜅1++[(op,𝜏)]) (the user-
defined predicates are parameterized over all stack frames, except the effect handler’s, to allow the
user to make assertions about stack points-to resources in regions). In the postcondition of the
Save operation, we can assert that our new checkpoint is installed in form of ownership of the
predicate cp𝜋 2 for a new stack frame𝜋2. We also gain𝑃1(𝜅2++[(op,𝜏)]) or𝑃2(𝜅2++[(op,𝜏)]) .
𝑃1(𝜅2++[(op,𝜏)]) is returned when computation is resumed just after saving the checkpoint, but
we also have to show that we can resume the computation using the continuation of the Save
operation when done through the Retry operation; in the protocol above, the specification for the
Retry operation states that the predicate𝑃2 must be provided in the precondition. As computation
is never resumed using the continuation of a Retry operation, the postcondition is simply false.
Instead, computation is resumed using the saved checkpoint and ownership of𝑃2(𝜅2++[(op,𝜏)]) as
shown in the postcondition of the Save operation. When proving safety of example4 in Figure 11,
𝑃1 is instantiated with the stack points-to resource of r pointing to0( 𝑟↦→ 0), and𝑃2 with the
resource pointing to1(𝑟↦→1).
1lethandle_checkpoint f =region(
2letr =ref local()in
3try(local,many) f ()with
4|effectCheckpoint arg k -> (matchargwith| Save -> r <- k; k () | Retry -> (!r) ())
5|retx -> x)
6
7letexample4 r () =region(
8doCheckpoint Save;
9if(!r = 0)then(r <- (!r + 1);doCheckpoint Retry;assertfalse)else(assert(!r = 1)))
10
11lethandle_example4 () =region(letr =ref local0inhandle_checkpoint (example4 r))
Fig. 11. Checkpoint Implementation.
7 Case Study: Asynchronous Computation
We have taken the asynchronous computation implementation by De Vilhena and Pottier[9], which
is originally based on an implementation by Dolan et al. [12], and turned global allocations into
local allocations by utilizing the flexibility of the region-construct; asynchronous computation is
based on recursion and tail-call optimization, but by omitting the creation of a region around the
recursive function, we can make local allocations into the region of the enclosing parent function.
Thus, in this case study, we break the association of a region per function body. 8 Due to space
constraints, we have included the full description of the asynchronous computation case study in
Section A.
8As mentioned in a previous footnote, OxCaml also features special keywords provided whereby the programmer can have
finer control over the allocation

<!-- Page 25 -->

Yarrow: Reconciling Effects Handlers and Region-Based Memory Management 25
8 Conclusion, Related Work and Future Work
In this paper we introduced Yarrow, an ML-like language with algebraic effects and region-based
memory management, and YL, a separation logic for modular reasoning about programs written in
Yarrow. We conducted a range of case studies showing that programs using one-shot and multi-shot
effect can avoid garbage collection by using region-based memory management. We have already
discussed related work closely along the way in the paper, here we discuss some additional related
work and point to future work.
Runtimes for Effect Handlers.A common way of implementing effect handlers is using continuation-
passing style (CPS) [17, 27]. This can be done in intermediate representation languages without
access to an explicit stack making it more widely applicable to different programming languages.
In CPS, continuations are allocated as heap objects that contain enough information to resume
computation. This is contrary to the approach by Sivaramakrishnan et al. [39] that we build upon
in this paper where capturing continuations amounts to saving a pointer to a fiber; the closures we
associate with continuations are like normal function closure, they do not contain all contents of
the stack necessary to resume computation as in CPS. The Flix programming language [ 29, 30]
implements effect handlers on top of the Java Virtual Machine (JVM), and the language has a
region construct. The combination of regions and effect handlers is undefined behavior [31], and
their region-based memory management has not been treated in any papers. As mentioned in
Section 3.1, Muhcu et al. [34] recently proposed a new runtime design for a language with effect
handlers (one-shot and multi-shot effects) and stack references based on the Effekt language [4]. In
this design stack references can be used even after performing multi-shot continuations by using
anindirection layer; stack pointers are accessed through indirection pointers. A stack reference
points to a pointer in the indirection layer which again points to the actual stack. This only works
for multi-shot effects as long as two continuations never capture the same stack segment, as the
case is with reentrant continuations. The paper does not come with a program logic or type system
for their runtime; we suspect that creating a program logic or type system that prohibits reentrant
continuations would be a significant challenge.
For future work, it is interesting to create a prototype runtime implementation for Yarrow and
experimentally compare the runtime of programs using effect handlers with and without region-
based memory management. The runtime could be complemented with inference of which regions
have bounded size and can thus be allocated on the call stack in the style of Birkedal et al. [3].
Relational Logics and Semantic Typing.Many Iris-based logics have been created to establish
relational properties about programs such ascontextual equivalence, seee.g.,[ 42]. Informally, two
programs are contextually equivalent if in all contexts they have the same observable behavior.
The main application of relational logics and contextual equivalence is to show that an ideal
implementation, serving as the specification, is equivalent to an efficient algorithm, serving as
the implementation, e.g. as seen in the works of Vindum and Birkedal [48] or Gäher et al. [14].
Recently de Vilhena et al. [11] made a relational logic to establish equivalences between programs
with effect handlers (they do not prove contextual equivalence). In the future, we could extend
YL to a relational logic. The extension can be used to establish equivalence between complex
implementations, optimized w.r.t efficiency by using effect handlers and region-based memory
management, and counterparts using simpler programming features. Another direction of future
work, is to build a type system for Yarrow usingsemantic typing[ 42]. Our logic can serve as
a foundation in the same way that the original work on effect handlers in separation logic by
De Vilhena and Pottier [9] was used to create a typesystem for effect handlers with affine types
[47], and a typesystem for effect handlers with dynamic labels [10]

<!-- Page 26 -->

26 Anders Alnor Mathiasen, Amin Timany, and Lars Birkedal
References
[1] Clément Allain and Gabriel Scherer. 2026. Zoo: A framework for the verification of concurrent OCaml 5 programs
using separation logic.Proceedings of the ACM on Programming Languages10, POPL (2026), 1702–1729.
[2] Andrej Bauer and Matija Pretnar. 2015. Programming with algebraic effects and handlers.Journal of logical and
algebraic methods in programming84, 1 (2015), 108–123.
[3] Lars Birkedal, Mads Tofte, and Magnus Vejlstrup. 1996. From region inference to von Neumann machines via region
representation inference. InProceedings of the 23rd ACM SIGPLAN-SIGACT symposium on Principles of programming
languages. 171–183.
[4] Jonathan Immanuel Brachthäuser, Philipp Schuster, and Klaus Ostermann. 2020. Effects as capabilities: effect handlers
and lightweight effect polymorphism.Proceedings of the ACM on Programming Languages4, OOPSLA (2020), 1–30.
[5] Jonathan Immanuel Brachthäuser, Philipp Schuster, and Klaus Ostermann. 2020. Effekt: Capability-passing style for
type-and effect-safe, extensible effect handlers in Scala.Journal of Functional Programming30 (2020), e8.
[6] Lukas Convent, Sam Lindley, Conor McBride, and Craig McLaughlin. 2020. Doo bee doo bee doo.Journal of Functional
Programming30 (2020), e9.
[7] Hoang-Hai Dang, Jacques-Henri Jourdan, Jan-Oliver Kaiser, and Derek Dreyer. 2019. RustBelt meets relaxed memory.
Proceedings of the ACM on Programming Languages4, POPL (2019), 1–29.
[8] Paulo Emílio de Vilhena. 2022.Proof of Programs with Effect Handlers. Theses. Université Paris Cité. https://inria.hal.
science/tel-03891381
[9] Paulo Emílio De Vilhena and François Pottier. 2021. A separation logic for effect handlers.Proceedings of the ACM on
Programming Languages5, POPL (2021), 1–28.
[10] Paulo Emílio de Vilhena and François Pottier. 2023. A type system for effect handlers and dynamic labels. InEuropean
Symposium on Programming. Springer, 225–252.
[11] Paulo Emílio de Vilhena, Simcha van Collem, Ines Wright, and Robbert Krebbers. 2026. A Relational Separation Logic
for Effect Handlers.Proceedings of the ACM on Programming Languages10, POPL (2026), 981–1009.
[12] Stephen Dolan, Spiros Eliopoulos, Daniel Hillerström, Anil Madhavapeddy, KC Sivaramakrishnan, and Leo White.
2017. Concurrent system programming with effect handlers. InInternational Symposium on Trends in Functional
Programming. Springer, 98–117.
[13] Stephen Dolan, Leo White, KC Sivaramakrishnan, Jeremy Yallop, and Anil Madhavapeddy. 2015. Effective concurrency
through algebraic effects. InOCaml Workshop, Vol. 13.
[14] Lennard Gäher, Michael Sammler, Simon Spies, Ralf Jung, Hoang-Hai Dang, Robbert Krebbers, Jeehoon Kang, and
Derek Dreyer. 2022. Simuliris: a separation logic framework for verifying concurrent program optimizations.Proc.
ACM Program. Lang.6, POPL, Article 28 (Jan. 2022), 31 pages. doi:10.1145/3498689
[15] Aïna Linn Georges, Benjamin Peters, Laila Elbeheiry, Leo White, Stephen Dolan, Richard A. Eisenberg, Chris Casinghino,
François Pottier, and Derek Dreyer. 2025. Data Race Freedom à la Mode.Proc. ACM Program. Lang.9, POPL, Article 23
(Jan. 2025), 31 pages. doi:10.1145/3704859
[16] Daniel Hillerström and Sam Lindley. 2016. Liberating effects with rows and handlers. InProceedings of the 1st
International Workshop on Type-Driven Development. 15–27.
[17] Daniel Hillerström, Sam Lindley, and Robert Atkey. 2020. Effect handlers via generalised continuations.Journal of
Functional Programming30 (2020), e5.
[18] Ralf Jung, Jacques-Henri Jourdan, Robbert Krebbers, and Derek Dreyer. 2017. RustBelt: securing the foundations of the
Rust programming language.Proc. ACM Program. Lang.2, POPL, Article 66 (Dec. 2017), 34 pages. doi:10.1145/3158154
[19] Ralf Jung, Robbert Krebbers, Lars Birkedal, and Derek Dreyer. 2016. Higher-order ghost state. InProceedings of the
21st ACM SIGPLAN International Conference on Functional Programming, ICFP 2016, Nara, Japan, September 18-22, 2016.
256–269. doi:10.1145/2951913.2951943
[20] Ralf Jung, Robbert Krebbers, Jacques-Henri Jourdan, Ales Bizjak, Lars Birkedal, and Derek Dreyer. 2018. Iris from
the ground up: A modular foundation for higher-order concurrent separation logic.J. Funct. Program.28 (2018), e20.
doi:10.1017/S0956796818000151
[21] Ralf Jung, David Swasey, Filip Sieczkowski, Kasper Svendsen, Aaron Turon, Lars Birkedal, and Derek Dreyer. 2015.
Iris: Monoids and Invariants as an Orthogonal Basis for Concurrent Reasoning. InProceedings of the 42nd Annual ACM
SIGPLAN-SIGACT Symposium on Principles of Programming Languages, POPL 2015, Mumbai, India, January 15-17, 2015.
637–650. doi:10.1145/2676726.2676980
[22] Ohad Kammar, Sam Lindley, and Nicolas Oury. 2013. Handlers in action.ACM SIGPLAN Notices48, 9 (2013), 145–158.
[23] Robbert Krebbers, Ralf Jung, Ales Bizjak, Jacques-Henri Jourdan, Derek Dreyer, and Lars Birkedal. 2017. The Essence
of Higher-Order Concurrent Separation Logic. InProgramming Languages and Systems - 26th European Symposium on
Programming, ESOP 2017, Held as Part of the European Joint Conferences on Theory and Practice of Software, ETAPS 2017,
Uppsala, Sweden, April 22-29, 2017, Proceedings. 696–723. doi:10.1007/978-3-662-54434-1_26

<!-- Page 27 -->

Yarrow: Reconciling Effects Handlers and Region-Based Memory Management 27
[24] Morten Krogh-Jespersen, Kasper Svendsen, and Lars Birkedal. 2017. A relational model of types-and-effects in higher-
order concurrent separation logic. InProceedings of the 44th ACM SIGPLAN Symposium on Principles of Programming
Languages, POPL 2017, Paris, France, January 18-20, 2017. 218–231. doi:10.1145/3009837.3009877
[25] Maxime Legoupil, June Rousseau, Aïna Linn Georges, Jean Pichon-Pharabod, and Lars Birkedal. 2024. Iris-MSWasm:
Elucidating and mechanising the security invariants of memory-safe WebAssembly.Proceedings of the ACM on
Programming Languages8, OOPSLA2 (2024), 304–332.
[26] Daan Leijen. 2014. Koka: Programming with row polymorphic effect types.arXiv preprint arXiv:1406.2061(2014).
[27] Daan Leijen. 2017. Type directed compilation of row-typed algebraic effects. InProceedings of the 44th ACM SIGPLAN
Symposium on Principles of Programming Languages. 486–499.
[28] Anton Lorenzen, Leo White, Stephen Dolan, Richard A. Eisenberg, and Sam Lindley. 2024. Oxidizing OCaml with
Modal Memory Management.Proc. ACM Program. Lang.8, ICFP, Article 253 (Aug. 2024), 30 pages. doi:10.1145/3674642
[29] Matthew Lutze, Magnus Madsen, Philipp Schuster, and Jonathan Immanuel Brachthäuser. 2023. With or Without
You: Programming with Effect Exclusion.Proc. ACM Program. Lang.7, ICFP, Article 204 (Aug. 2023), 28 pages.
doi:10.1145/3607846
[30] Magnus Madsen. 2022. The Principles of the Flix Programming Language. InProceedings of the 2022 ACM SIGPLAN
International Symposium on New Ideas, New Paradigms, and Reflections on Programming and Software(Auckland, New
Zealand)(Onward! 2022). Association for Computing Machinery, New York, NY, USA, 112–127. doi:10.1145/3563835.
3567661
[31] Magnus Madsen. 2026. Confirmed in personal communication with Magnus Madsen the lead developer of the Flix
programming language.
[32] William Mansky and Ke Du. 2024. An Iris instance for verifying CompCert C programs.Proceedings of the ACM on
Programming Languages8, POPL (2024), 148–174.
[33] Glen Mével, Jacques-Henri Jourdan, and François Pottier. 2020. Cosmo: a concurrent separation logic for multicore
OCaml.Proceedings of the ACM on Programming Languages4, ICFP (2020), 1–29.
[34] Serkan Muhcu, Philipp Schuster, Michel Steuwer, and Jonathan Immanuel Brachthäuser. 2025. Multiple Resumptions
and Local Mutable State, Directly.Proc. ACM Program. Lang.9, ICFP, Article 260 (Aug. 2025), 30 pages. doi:10.1145/
3747529
[35] Luna Phipps-Costin, Andreas Rossberg, Arjun Guha, Daan Leijen, Daniel Hillerström, KC Sivaramakrishnan, Matija
Pretnar, and Sam Lindley. 2023. Continuing WebAssembly with effect handlers.Proceedings of the ACM on Programming
Languages7, OOPSLA2 (2023), 460–485.
[36] Gordon Plotkin and Matija Pretnar. 2009. Handlers of algebraic effects. InEuropean Symposium on Programming.
Springer, 80–94.
[37] Xiaojia Rao, Aïna Linn Georges, Maxime Legoupil, Conrad Watt, Jean Pichon-Pharabod, Philippa Gardner, and Lars
Birkedal. 2023. Iris-wasm: Robust and modular verification of webassembly programs.Proceedings of the ACM on
Programming Languages7, PLDI (2023), 1096–1120.
[38] Remy Seassau, Irene Yoon, Jean-Marie Madiot, and François Pottier. 2025. Formal semantics and program logics for a
fragment of OCaml.Proceedings of the ACM on Programming Languages9, ICFP (2025), 128–159.
[39] KC Sivaramakrishnan, Stephen Dolan, Leo White, Tom Kelly, Sadiq Jaffer, and Anil Madhavapeddy. 2021. Retrofitting
effect handlers onto OCaml. InProceedings of the 42nd ACM SIGPLAN International Conference on Programming
Language Design and Implementation. 206–221.
[40] Jane Street. 2026. OxCaml Documentation. https://oxcaml.org/documentation/
[41] Amin Timany, Armaël Guéneau, and Lars Birkedal. 2024. The logical essence of well-bracketed control flow.Proceedings
of the ACM on Programming Languages8, POPL (2024), 575–603.
[42] Amin Timany, Robbert Krebbers, Derek Dreyer, and Lars Birkedal. 2024. A logical approach to type soundness.J.
ACM71, 6 (2024), 1–75.
[43] Amin Timany, Léo Stefanesco, Morten Krogh-Jespersen, and Lars Birkedal. 2018. A logical relation for monadic
encapsulation of state: proving contextual equivalences in the presence of runST.PACMPL2, POPL (2018), 64:1–64:28.
doi:10.1145/3158152
[44] Mads Tofte, Lars Birkedal, Martin Elsman, and Niels Hallenberg. 2004. A retrospective on region-based memory
management.Higher-Order and Symbolic Computation17, 3 (2004), 245–265.
[45] Mads Tofte, Lars Birkedal, Martin Elsman, Niels Hallenberg, Tommy Højfeld Olesen, Peter Sestoft, and Peter Bertelsen.
2001.Programming with regions in the ML Kit (for version 4). Technical Report. Technical report, IT University of
Copenhagen.
[46] Mads Tofte and Jean-Pierre Talpin. 1997. Region-based memory management.Information and computation132, 2
(1997), 109–176.
[47] Orpheas van Rooij and Robbert Krebbers. 2025. Affect: An Affine Type and Effect System.Proc. ACM Program. Lang.9,
POPL, Article 5 (Jan. 2025), 29 pages. doi:10.1145/3704841

<!-- Page 28 -->

28 Anders Alnor Mathiasen, Amin Timany, and Lars Birkedal
[48] Simon Friis Vindum and Lars Birkedal. 2021. Contextual refinement of the Michael-Scott queue (proof pearl). In
Proceedings of the 10th ACM SIGPLAN International Conference on Certified Programs and Proofs. 76–90.

<!-- Page 29 -->

Yarrow: Reconciling Effects Handlers and Region-Based Memory Management 29
A Asynchronous Computation
The asynchronous computation library is implemented using an effect handler, see Figure 12.
We focus on the parts of the implementation that we have changed to enable local allocations,
for a detailed explanation of all the code, we refer to the aforementioned papers. Asynchronous
computation provides two operations: Async𝑒 for asynchronously computing a task represented
by the expression𝑒. The Async𝑒 operation returns a promise𝑝 that a user thread can wait for the
completion of using the Await𝑝 operation. The Await𝑝 operation returns a value, this is the return
value of the task that created the promise𝑝. The implementation in Figure 12 is based on a function
fulfill, this function takes a task𝑒 and the promise𝑝 that the task should fulfill. Promises are
implemented as dynamically allocated references pointing to a list of waiting user threads, in our
version the references are locally allocated. The region that we use to allocate new promises in,
for every invocation of the Async𝑒 operation, is not the region of the fullfill function that
creates promises on line 11; the fullfill function is recursively called each time an asynchronous
computation is made (line 11), thus if we want to utilize tail call optimization, we can not rely on
the region of fullfill. Luckily, the region-construct is flexible in that we can allocate into the
region of the parent function,i.e., run, by omitting creation of a region in fullfill. Promises are
not the only things we allocate into the region of run: all the non-empty function closures, on line
7, 11 and 17 use this region too.9
1letnew_promise () =ref local(Waiting (list_nil ()))
2
3letnext q =ifqueue_empty qthen()else(queue_pop q) ()
4
5letrun main =region(
6letq = queue_create ()in
7letfulfill = (fun localp e =>
8try(local,once) e ()with
9|effectAC request k ->
10matchrequestwith
11| Async e ->letp = new_promise ()inqueue_push q (fun local() => k p); fullfill p e
12| Await p ->match!pwithDone v -> k v | Waiting ks -> p <- (Waiting (list_cons k ks))
13|retx ->
14match!pwith
15| Done v ->assertfalse
16| Waiting ks ->
17list_iter (fun localk => queue_push q (fun local() => k v)) ks; p <- (Done v); next q
18)in
19letp = new_promise ()in
20fullfill p main)
Fig. 12. Asynchronous Computation.
Below, we show the protocol for the asynchronous computation effect𝐴𝐶:
task𝑒 𝑃Φ≜∀𝜅 1.𝑃−∗Stack(𝜅 1++[(AC,[])])−∗sched𝜅 1−∗
ewp𝑒() (AC:(once,𝐴𝐶)) {𝑣.Φ𝑣∗∃𝜅 2,sched𝜅 2∗Stack(𝜅 2++[(AC,[])]) }
AC≜!𝑒Φ𝜅 1𝑃(Async𝑒,𝜅 1){𝑃∗sched𝜅 1∗⊲task𝑒 𝑃Φ }.once?𝑝𝜅 2(𝑝,𝜅 2){𝑖𝑠𝑃𝑟𝑜𝑚𝑖𝑠𝑒𝑝Φ∗sched𝜅 2}
+!𝑝Φ𝜅 1(Await𝑝,𝜅 1){𝑖𝑠𝑃𝑟𝑜𝑚𝑖𝑠𝑒𝑝Φ∗sched𝜅 1}.once?𝑦𝜅 2(𝑦,𝜅 2){Φ𝑦∗sched𝜅 2}
The protocol very closely resembles the one used by De Vilhena and Pottier [9], the differences
are best seen in the task predicate: each new task gets to use the Stack resource such that user
threads can create their own regions, and then we have a predicate sched𝜅 . The sched𝜅 predicate
is abstract to user threads, and defined as part of the proof of the effect handler, we use it to tie
the internal state of the effect handler implementation together with allocations in the region of
9We use the notation "fun𝑙arg => e " when we want to control where a function closure is allocated using the locality
mode𝑙.

<!-- Page 30 -->

30 Anders Alnor Mathiasen, Amin Timany, and Lars Birkedal
the fibers described by fiber domains𝜅. The postcondition of a task must show ownership of the
stack resource Stack and the sched predicate for some fiber domains𝜅2 but, importantly, the return
value must also satisfy the predicate Φ. The predicate Φ is a persistent predicate that a new task
promises to fulfill when the task is created using the Async operation. The Async operation returns
a promise𝑝 and another persistent predicate𝑖𝑠𝑃𝑟𝑜𝑚𝑖𝑠𝑒𝑝Φ stating that𝑝 promises to fullfill the
proof obligation described by Φ. In the specification of theAwait𝑝 operation, we use𝑖𝑠𝑃𝑟𝑜𝑚𝑖𝑠𝑒𝑝Φ
as precondition, and when the promise is fulfilled, we get to assume Φ𝑦 where𝑦 corresponds to
the return value of the task fulfilling the promise𝑝.
B Condition for effect handlers with locally allocated continuation closures
hdlLoc𝑎Ψ Φ 𝑒 𝑥𝑘ℎ𝑦𝑟 𝜌m𝜌Φ≜
m𝜌=once→𝑜𝑛𝑐𝑒 𝜌∧
?m𝜌 (∗𝑅𝑒𝑡𝑢𝑟𝑛𝑏𝑟𝑎𝑛𝑐ℎ∗)
(∀𝑣𝜅.Φ 𝑒𝜅𝑣−∗Stack𝜅−∗ewp𝑟[𝑣/𝑦] 𝜌{Φ})∧
(∗𝐸𝑓𝑓𝑒𝑐𝑡𝑏𝑟𝑎𝑛𝑐ℎ∗)
(∀𝑣𝑘′.(∃𝜅op𝜏 𝜋 ℓ𝑖𝑑 𝑃Φ 2𝜌′𝐾.Stack(𝜅++[(op,𝜏++[𝜋++[ℓ]])])∗ℓ↦→𝑖𝑑∗
Ψ𝑣(𝜅++[(op,𝜏++[𝜋])])(𝜆𝑤 2𝜅2.𝑃𝜅 2−∗ewp𝐾[𝑤 2] 𝜌{Φ2})∗
?𝑎(∀𝑤1𝜅1 Φ1.ℓ↦→𝑖𝑑−∗Stack𝜅 1−∗hdlLoc𝑎Ψ Φ 𝑒 𝑥𝑘ℎ𝑦𝑟 𝜌m𝜌Φ 1−∗
⊲(ℓ↦→𝑖𝑑−∗𝑃𝜅 1−∗ewp𝐾[𝑤 1] 𝜌′{Φ2})−∗
ewp𝑘′𝑤1𝜌{Φ1}))−∗
ewpℎ[𝑘′/𝑘][𝑣/𝑥] 𝜌{Φ})
Fig. 13. Definition of the predicatehdlLoc.