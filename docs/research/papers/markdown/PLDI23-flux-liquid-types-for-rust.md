# Flux: Liquid Types for Rust

> **Machine-generated Markdown conversion — this is a MODIFIED version.**
> Converted from `PLDI23-flux-liquid-types-for-rust.pdf` with PyMuPDF. Layout, mathematics,
> figures and tables are lossy; **quote from the PDF, not from this file.**
> Page anchors below correspond to PDF pages.

- **Citation:** Nico Lehmann, Adam T. Geller, Niki Vazou, Ranjit Jhala. PLDI 2023. doi:10.1145/3591283
- **Licence:** CC-BY 4.0
- **Source:** https://ranjitjhala.github.io/static/flux-pldi23.pdf

---


<!-- page 1 -->

## Flux: Liquid Types for Rust

NICO LEHMANN, UC San Diego, USA ADAM T. GELLER, University of British Columbia, Canada NIKI VAZOU, IMDEA, Spain RANJIT JHALA, UC San Diego, USA

We introduce Flux, which shows how logical refinements can work hand-in-glove with Rust's ownership mechanisms to yield ergonomic type-based verification of low-level pointer manipulating programs. First, we design a novel refined type system for Rust that indexes mutable locations, with pure (immutable) values that can appear in refinements, and then exploits Rust's ownership mechanisms to abstract sub-structural reasoning about locations within Rust's polymorphic type constructors, while supporting strong updates. We formalize the crucial dependency upon Rust's strong aliasing guarantees by exploiting the stacked borrows aliasing model to prove that "well-borrowed evaluations of well-typed programs do not get stuck". Second, we implement our type system in Flux, a plug-in to the Rust compiler that exploits the factoring of complex invariants into types and refinements to efficiently synthesize loop annotations--including complex quantified invariants describing the contents of containers--via liquid inference. Third, we evaluate Flux with a benchmark suite of vector manipulating programs and a previously verified secure sandboxing library to demonstrate the advantages of refinement types over program logics as implemented in the state-of-theart Prusti verifier. While Prusti's more expressive program logic can, in general, verify deep functional correctness specifications, for the lightweight but ubiquitous and important verification use-cases covered by our benchmarks, liquid typing makes verification ergonomic by whittling specification lines by a factor of two, verification time by an order of magnitude, and annotation overhead from up to 24% of code size (average 14%), to nothing at all.

CCS Concepts: • Theory of computation →Type structures; Separation logic; • Software and its engineering →Software verification.

Additional Key Words and Phrases: Rust, liquid types, heap-manipulating programs

ACM Reference Format: Nico Lehmann, Adam T. Geller, Niki Vazou, and Ranjit Jhala. 2023. Flux: Liquid Types for Rust. Proc. ACM Program. Lang. 7, PLDI, Article 169 (June 2023), 25 pages. https://doi.org/10.1145/3591283

flux (/fl2ks/) n. 1 a flowing or flow. 2 a substance used to refine metals. v. 3 to melt; make fluid.

## 1 INTRODUCTION

Low-level, pointer-manipulating programs are tricky to write and devilishly hard to verify, requiring complex spatial program logics that support reasoning about aliasing [O'Hearn 2004; Reynolds 2002]. The Rust programming language [Matsakis and Klock II 2014] uses the mechanisms of ownership types [Clarke et al. 1998; Noble et al. 1998] to abstract fast pointer-based libraries inside typed APIs that let clients write efficient applications with static memory and thread safety. Recent systems

Authors' addresses: Nico Lehmann, UC San Diego, USA, nlehmann@ucsd.edu; Adam T. Geller, University of British Columbia, Canada, atgeller@cs.ubc.ca; Niki Vazou, IMDEA, Madrid, Spain, niki.vazou@imdea.org; Ranjit Jhala, UC San Diego, USA, rjhala@ucsd.edu.

This work is licensed under a Creative Commons Attribution 4.0 International License.

© 2023 Copyright held by the owner/author(s).

2475-1421/2023/6-ART169 https://doi.org/10.1145/3591283

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.


<!-- page 2 -->

169:2 Nico Lehmann, Adam T. Geller, Niki Vazou, and Ranjit Jhala

like Prusti [Astrauskas et al. 2019], RustHorn [Matsushita et al. 2021], and Creusot [Denis et al. 2022] have taken advantage of these ownership mechanisms to shield the programmer from some spatial assertions helping them instead focus on writing pure, first-order logic specifications which can be automatically verified by a solver.

Even with these advances, verification remains unpleasant. The programmer is still encumbered with providing verbose annotations to persuade the solver of the legitimacy of their code. For instance, when working over collections, program-logic based methods require the use of loop invariants that are universally quantified to account for the potentially unbounded contents of the collection. Such invariants often require a sophisticated understanding of the underlying spatial program logic, and worse, the quantification makes them difficult to synthesize.

Refinements types have obviated these problems in the purely functional setting [Constable and Smith 1987; Rushby et al. 1998; Xi and Pfenning 1999a]. Refinements express complex invariants by composing type constructors with simple quantifier-free logical predicates. Thus, they let us use syntax directed subtyping to decompose complex reasoning about those invariants into efficiently decidable (quantifier free) validity queries over the predicates, thereby enabling Horn-clause based annotation synthesis which makes verification ergonomic [Rondon et al. 2008]. Sadly, refinements have remained a fish out of water in the imperative setting. Mutation changes the type of variables and aliasing makes it difficult to track those changes, making it hard for types to soundly depend on the shifting sands of program values. Previous systems [Bakst and Jhala 2016; Rondon et al. 2010; Sammler et al. 2021; Toman et al. 2020] attempted to bridge the gap between pure refinements and impure heap locations using sub-structural type systems, but proved impractical as the retrofitted effect systems complicate specifications with non-idiomatic spatial constraints.

In this paper, we introduce Flux, which shows how refinements can work hand in glove with ownership mechanisms to yield ergonomic type-based verification for imperative (safe) Rust. Via three concrete contributions, we show how Flux lets the programmer abstract fast low-level libraries in refined APIs so that static typing yields application level correctness guarantees with minimal programmer annotation overhead.

1. Design and Formalization (§3) Our first contribution is the design of a type system that seamlessly extends Rust's types with refinements in three steps. Following previous systems [Bakst and Jhala 2016; Sammler et al. 2021], Flux starts by indexing mutable locations, with pure (immutable) values that can appear in refinements. Next, Flux shows how to exploit Rust's ownership mechanisms to encapsulate locations, thereby abstracting sub-structural reasoning within Rust's type constructors. Finally, Flux extends and refines Rust's mutable references with a notion of strong references that precisely track strong updates that alter the type of the mutated object. Crucially, our design relies on the strong aliasing guarantees ensured by Rust without the need to reimplement the complex rules of the borrow checker [Jung et al. 2017; Weiss et al. 2019]. We formalize this requirement by defining an operational semantics instrumented with a "dynamic borrow checker" as defined by the Stacked Borrows aliasing discipline [Jung et al. 2019]. Armed with this dynamic interpretation of Rust's aliasing model we prove soundness of our type system, which ensures that "well-borrowed evaluations of well-typed programs do not get stuck" (Theorem 3.1).

2. Implementation (§4) Our second contribution is an implementation of the declarative type system as a plug-in to the Rust compiler. Flux works in three phases. In the first spatial phase, Flux automatically uses the function signatures to infer a mapping between program identifiers and heap locations, and the precise points where the refinements on a location may be assumed and must be asserted. At this juncture, the intermediate refinements are still unknown. Thus, in the second checking phase we perform refinement type checking using Horn variables for the unknown refinements, generating a system of Horn constraints, a solution to which implies the

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.


<!-- page 3 -->

Flux: Liquid Types for Rust 169:3

#[flux::sig(fn(i32[@n]) -> bool[0<n])] fn is_pos(n: i32) -> bool {

if n > 0 { true } else { false } }

Fig. 1. Examples showing Flux basic features: indexed types, existential types and refinement parameters.

## 2 A TOUR OF FLUX

1Rust has primitive types for signed and unsigned integers of 8, 16, 32, 64 and 128 bits, plus the pointer-sized integers usize and isize.

#[flux::sig(fn(i32[@x]) -> i32{v:x<=v && 0<=v})] fn abs(x: i32) -> i32 {

if x < 0 { -x } else { x } }

program is well-typed. Finally, in the third inference phase, we solve the constraints, using the fixpoint implementation of Cosman and Jhala [2017], and either verify the program or pinpoint an error when no solution exists. Crucially, factoring complex invariants into type constructors and simple refinements lets the solver efficiently synthesize solutions from a small set of quantifier-free templates. 3. Evaluation (§5) Our third contribution is an empirical evaluation that demonstrates the advantages of Flux's refinement type-based verification over program logic based approaches. To do so, we use Flux and Prusti [Astrauskas et al. 2019], a state-of-the-art Rust verifier, to prove the absence of index-overflow errors in a suite of vector-manipulating programs, and security properties in parts of a previously verified sandboxing library. Prusti's program logic can, in general, verify deep functional correctness specifications beyond the scope of Flux. However, for the ubiquitous and important lightweight verification use cases exemplified by our benchmarks, our evaluation shows how Flux's refined types naturally capture invariants and heap update specifications that must otherwise be spelled out via complex (quantified) program logic assertions. Consequently, we show how liquid typing makes lightweight verification ergonomic by whittling verification time by an order of magnitude, specification sizes by a factor of 2, and the loop-invariant annotation overhead from up to 24% of code size (average 14%), to nothing at all.

Let us begin with a high-level overview of Flux's key features that illustrates how liquid refinements work hand-in-glove with Rust's types to yield a compact way to specify correctness requirements and an automatic way to verify them with minimal programmer overhead. First, we show how Flux decorates types with logical refinements that capture invariants (§2.1). Next, we demonstrate how Rust's ownership types allows us to precisely track refinements in the presence of imperative mutation (§2.2). Finally, we show how the combination of ownership and refinement types enables ergonomic verification, by looking at some examples that work over unbounded collections (§2.3).

### 2.1 Refinements

Refinement types allow expressions in some underlying, typically decidable, logic to be used to constrain the set of values inhabited by a type, thereby tracking additional information about the values of the type they refine [Jhala and Vazou 2021]. Indexed Types An indexed type [Xi and Pfenning 1999a] in Flux refines a Rust base type by indexing it with a refinement value. Each indexed type is associated with a refinement sort and it must be indexed by values of that sort. The meaning of the index varies depending on the type. For example, Rust primitive integers can be indexed by integers in the logic (of sort int) describing the exact integer they are equal to. Hence, indexed integers correspond to singleton types, for instance, the type i32[n] describes 32-bit1 signed integers equal to n and the type usize[n+1] describes a pointer-sized unsigned integer equal to n + 1. Consequently, Flux can verify that the

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.


<!-- page 4 -->

169:4 Nico Lehmann, Adam T. Geller, Niki Vazou, and Ranjit Jhala

Rust expression 1 + 2 + 3 has the type i32[6]. Similarly, the boolean type bool[b] is indexed by the boolean value b (of sort bool) it is equal to. For example, Flux can type the Rust expression 1 + 2 + 3 <= 10 as bool[true]. Indices do not always encode singletons. As an example, the type RVec<T>[n] of growable vectors is indexed by their length (of sort int), as detailed later in §2.3. Even though most of our examples have a single index, types can have multiple indices. For example, in §5 we index a type for 2-D matrices by both the number of rows and columns. Refinement Parameters Flux's function signatures can be parameterized by variables in the refinement logic. Informally, such refinement parameters behave like ghost variables that exist solely for verification, but do not exist at run-time. Flux automatically instantiates the refinement parameters using the actual arguments passed in at the respective sites.

The first two features--indices and refinement parameters--are illustrated by the refined signature for the function is_pos specified with the attribute #[flux::sig(...)] on the left in fig. 1. The function is_pos tests whether a 32-bit signed integer is positive. The signature uses a refinement parameter n to specify that the function takes as input an integer equal to n and returns a boolean equal to n > 0. The syntax @n is used to bind and quantify over n for the scope of the function. Existential Types Indexed types suffice when we know the exact value of the underlying term, i.e., we can represent it with a singleton expression in the refinement logic. However, often we want to specify that the underlying value is from a set denoted by a refinement constraint [Constable and Smith 1987; Rushby et al. 1998]. Flux accommodates such specifications via existential types of the form {v. B[v] | p} where: v is a variable in the refinement logic, B[v] is a base type indexed by v, and p is a predicate constraining v. For example, the existential type {v. i32[v] | v > 0} specifies the set of positive 32-bit integers. Similarly, the set of non-empty vectors is described by the type {v. RVec<T>[v] | v > 0}. We define the syntax B{v: p} to mean {v. B[v] | p}. Hence, the two types above abbreviate to i32{v: v > 0} and RVec<T>{v: v > 0}. Further, we write B to abbreviate B{v: true} and nat to abbreviate i32{v: v >= 0}.

Existential types are illustrated by the signature for the function abs shown on the right in fig. 1 which computes the absolute value of the i32 input x.2 The function's output type is an existential that specifies that the returned value is a non-negative i32 whose values is at least as much as x.

### 2.2 Ownership

The whole point of Rust, of course, is to allow for efficient imperative sharing and updates, without sacrificing thread- or memory-safety. This is achieved via an ownership type system that ensures that aliasing and mutation cannot happen at the same time [Clarke et al. 2013; Jung et al. 2017; Noble et al. 1998]. Next, let's see how Flux lets logical constraints ride shotgun with Rust's ownership types to scale refinement types to an imperative setting. Exclusive Ownership Rust's most basic form of ownership is exclusive ownership, in which only one function has the right to mutate a memory location. In Flux, exclusive ownership plays crucial role: by ruling out aliasing, we can safely perform strong updates [Ahmed et al. 2007; Smith et al. 2000], i.e., we can change the refinements on a type when updating data, and thereby, use different types to denote the values at that location at different points in time. For example, if a variable x has type i32[n], after executing the statement x += 1, the type of x is updated to i32[n + 1]. Borrowing Exclusive ownership suffices for local updates but for more complex data, functions must eventually relinquish ownership to other functions that update and read the data in some fashion. Rust's unique approach to allow this is called borrowing, via two kinds of references that

2The attentive reader will note that the implementation of abs causes an overflow if x equals i32::MIN. Flux can easily verify the absence of overflows statically, but to keep the examples short we assume that overflows are being checked at run-time which is enabled in the compiler with the flag -C overflow-checks=yes.

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.


<!-- page 5 -->

Flux: Liquid Types for Rust 169:5

1 #[flux::sig(fn(&mut nat))] 2 fn decr(x: &mut i32) { 3 let y = *x; 4 if y > 0 { 5 *x = y - 1; 6 } 7 } 8 9 #[flux::sig(fn(bool) -> nat)] 10 fn ref_join(z: bool) -> i32 { 11 let mut x = 1; 12 let mut y = 2; 13 let r = if z { &mut x } else { &mut y }; 14 decr(r); 15 x 16 }

17 fn swap<T>(x: &mut T, y: &mut T); 18 19 #[flux::sig(fn() -> nat)] 20 fn use_swap() -> i32 { 21 let mut x = 0; 22 let mut y = 1; 23 swap(&mut x, &mut y); 24 x 25 } 26 27 #[flux::sig( 28 fn(x: &strg i32[@n]) 29 ensures *x: i32[n + 1])] 30 fn incr(x: &mut i32) { 31 *x += 1; 32 }

Fig. 2. Examples showing the interaction between refinement types and ownership types.

grant temporary access to a memory location. First, a value of type &T is a shared reference, that can be used to access the T value in a read-only fashion. Second, a value of type &mut T is a mutable reference that can be used to write or update the contents of a T value. For safety, Rust allows multiple aliasing (read-only) shared references but only one mutable reference to a value at a time.

Flux exploits the semantics of mutable references to attach invariants to data. Crucially, updates through a mutable reference &mut T do not change the type T, or in other words, mutating through a mutable reference can only perform weak updates. This behavior ensures that mutations through an &mut T will preserve the invariants encoded in T. For example, consider the function decr in fig. 2, whose plain rust signature is fn(&mut i32) -> (). (Hereafter, we follow the standard Rust style and omit the return type if it is the unit type ().) The Flux signature takes as input an &mut nat (i.e., &mut i32{v: v >= 0}) imposing on the function the obligation to preserve the invariant that the reference points to a natural number. This means that the update in line 5 must preserve the type, which Flux can prove assuming the condition in the branch.

Imprecise Alias Information A mutable reference will typically point to a memory location that cannot be determined statically. Still, we would like to track refinements on the locations that may be pointed to by a reference. Next, we show how Flux leverages Rust's borrowing rules to track refinements in the presence of imprecise aliasing information.

Consider the function ref_join in fig. 2. The syntax &mut x (resp. &mut y) in line 13 is used to create a mutable reference by temporarily borrowing the content of x (resp. y). Then, depending on the branch condition, r will point to either x or y. Acknowledging the reference may end up pointing to an unknown location, when borrowing x, Flux updates its type to account for possible mutations through r, which in turn, must only allow updates guaranteeing x will continue to have this type after the borrow ends. Concretely, Flux updates the type of x to be nat, and assigns r the type &mut nat. When the borrow expires after line 14, we can read again from x knowing it is still a natural number. Note that at the time x is borrowed in line 13, Flux does not know immediately what type should be assigned to x as the appropriate type depends on subsequent uses of x and r. In §3 and §4 we resp. show how Flux's liquid typing can check and automatically infer this type.

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.


<!-- page 6 -->

169:6 Nico Lehmann, Adam T. Geller, Niki Vazou, and Ranjit Jhala

1 impl RVec<T> { 2 fn new() -> RVec<T>[0]; 3 fn len(self: &RVec<T>[@n]) -> usize[n]; 4 fn get(self: &RVec<T>[@n], idx: usize{v: v < n}) -> &T; 5 fn get_mut(self: &mut RVec<T>[@n], idx: usize{v: v < n}) -> &mut T; 6 fn push(self: &strg RVec<T>[@n], value: T) ensures *self: RVec<T>[n + 1]; 7 }

Fig. 3. A refined API for vectors indexed by their size.

Specs for Free via Polymorphism For a classical type system, polymorphism facilitates code reuse: we can use the same datatype to hold integers or strings or booleans etc.. Flux exploits the combination of polymorphism and mutable references to generate compact specifications. Consider the function use_swap in fig. 2 which uses the function swap from the Rust standard library to swap the values of x and y. The plain Rust signature of swap is fn<T>(&mut T, &mut T) where T is a polymorphic type parameter. Just using the plain Rust signature--and no other specifications-- Flux can verify the post-condition of use_swap by automatically instantiating the parameter T to be nat via liquid typing. After the function returns, x and y are guaranteed to have type nat because by virtue of taking &mut T references, swap will respect the invariants in T.

Strong updates We have seen how the mechanisms Rust uses to control mutation interact with refinements. Exclusive ownership provides local strong updates, i.e., within the function owning a value, and mutable references can be used to temporarily relinquish ownership and provide weak updates while preserving the ability to track refinements in the presence of imprecise alias information. While powerful, these mechanisms are insufficient for refinement type checking.

In many situations, we would like to lend a value to other functions that change the value's refinement upon their return. To this end, Flux extends Rust with strong references, written &strg T, which refine Rust's &mut T and, like regular mutable references, also grant temporary exclusive access but allow strong updates by tracking the precise location the reference points to. Flux accommodates strong references by extending function signatures to specify the updated type of each strong reference after the function returns. For example, consider the signature of incr in fig. 2. The Flux signature refines the plain Rust signature to specify that (1) the argument x is a strong reference to an i32[n] and (2) the updated type of the location pointed to by x is i32[n + 1] as denoted by the function's ensures clause. With this specification, Flux can verify that after executing the statements let mut x = 1; incr(&mut x) the type of x is i32[2].

### 2.3 Unbounded Collections

Next, we illustrate how the fundamental mechanisms introduced so far enable ergonomic verification by showing how they can be used to automatically verify lightweight properties about unbounded collections. First, we present a refined API for vectors. Second, we use this API to concisely specify fragments of an implementation of the k-means clustering algorithm. Finally, we present a refined implementation of a linked list, to illustrate how standard type constructors can be used to compose complex structures from simple ones, in a way that, dually, lets standard syntax directed typing rules decompose complex reasoning about those structures into efficiently decidable (quantifier free) validity queries over the constituents.

A Refined Vector API Fig. 3 summarizes the signatures for RVec--vectors refined by their size.

• new constructs empty vectors: the return type RVec<T>[0] states the returned vector has size 0.

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.


<!-- page 7 -->

Flux: Liquid Types for Rust 169:7

• len can be used to determine the size of the vector. The method takes a shared reference, which implicitly specifies the vector will have the same length after the function returns. Moreover, the returned type usize[n] stipulates that the result equals the receiver's size. • get and get_mut are used to access the elements of the vector: get returns a shared (read-only) reference while get_mut returns a mutable one that can be used to update the vector. The type for the index idx specifies that only valid indices (less than size n) can be used to access the receiving vector. Crucially, by taking a mutable reference, get_mut guarantees that the length of the vector and the type of the elements it contains remain the same after the function returns. Furthermore, by returning a mutable reference, which can point to a possible unbounded set of locations, users of get_mut must respect the invariants in T when mutating the reference, ensuring the vector will continue to hold elements of type T. • push is used to grow the vector by one element at a time. It takes a strong reference and specifies that the length of the vector has increased by one after the function returns, via the ensures clause *self: RVec<T>[n + 1].

Constructing a Vector Fig. 4 shows a couple of functions taken from an implementation of the kmeans clustering algorithm. The function init_zeros takes as input a usize equal to n and returns as output an n-dimensional vector of 32-bit floats (f32), specified as RVec<f32>[n]. The variable vec is initialized with an empty vector using the function new in line 5. Similarly, the counter i is initialized with 0 in line 6. In each iteration, the method push is called on vec incrementing its size by one. Correspondingly, the counter i is also incremented by one. Flux's liquid typing exploits these strong updates to automatically infer that i is equal to the length of vec and since the loop exists with i = n, the returned value vec has type RVec<f32>[n]. Quantified Invariants via Polymorphism RVec is polymorphic over T: the type of elements it contains. We can instantiate T with arbitrary refined types, which is exploited by Flux to compactly specify that all elements of the vector satisfy some invariant. For instance, the function normalize_centers in fig. 4 uses RVec<RVec<f32>[n]>[k] to concisely specify a collection of k-centers, each of which is an n-dimensional point. Program logic based methods must use universally quantified formulas to express such properties, which increases the specification burden on programmers (who must now write tricky quantified invariants), and the verification burden on the solver (which must now reason about those quantified invariants!). In contrast, Flux's type-directed method automatically verifies that, despite working over mutable references, we can be sure all the inner vectors still have the same length after the function returns even though we are passing mutable references to these vectors to the function normal in line 25.

A Refined Linked List Fig. 5 shows a standard definition of a recursive List using an enum which is Rust's syntax to declare algebraic data types. As required by Rust, each recursive occurrence of the type needs to be guarded by a pointer to ensure the size is known at compile time. We use the standard type Box<T>, which represents an owned (heap-allocated) pointer to values of type T. The annotation #[flux::refined_by(len: int)] on top of the enum declares that the type is indexed by an integer in the logic, which we mean to represent the length of the list. Each variant is annotated with the attribute #[flux::variant(...)] to specify a refined signature for the constructor. We define the Nil case to return a List<T>[0], declaring its length to be zero. In the Cons case, given a value of type T and a List of length n (inside a Box) the constructor returns a list of length n + 1 as declared by the return type List<T>[n + 1].

Finally, we show how the indexed length can be used to specify the method append at the bottom of fig. 5. The method takes two lists of length n and m, consuming the second one and appending it to the end of the first one in place, i.e., using Rust's idioms to avoid copying. The ensures *self: List<T>[n + m] clause specifies that the length of the first list gets updated

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.


<!-- page 8 -->

169:8 Nico Lehmann, Adam T. Geller, Niki Vazou, and Ranjit Jhala

1 #[flux::sig( 2 fn(usize[@n]) -> RVec<f32>[n] 3 )] 4 fn init_zeros(n:usize) -> RVec<f32> { 5 let mut vec = RVec::new(); 6 let mut i = 0; 7 while i < n { 8 vec.push(0.0); 9 i += 1; 10 } 11 vec 12 } 13 14

15 #[flux::sig(fn(usize[@n], 16 &mut RVec<RVec<f32>[n]>[@k], 17 &RVec<f32>[k]))] 18 fn normalize_centers( 19 n: usize, 20 cs: &mut RVec<RVec<f32>>, 21 ws: &RVec<usize>, 22 ) { 23 let mut i = 0; 24 while i < cs.len() { 25 normal(cs.get_mut(i), *ws.get(i)); 26 i += 1; 27 } 28 }

Fig. 4. Code taken from an implementation of the k-means clustering algorithm. The code uses RVec to represent k-centers of n-dimensional points.

1 #[flux::refined_by(len: int)] 2 enum List<T> { 3 #[flux::variant(List<T>[0])] 4 Nil, 5 #[flux::variant((T, Box<List<T>[@n]>) -> List<T>[n + 1])] 6 Cons(T, Box<List<T>>) 7 } 8 9 impl<T> List<T> { 10 #[flux::sig(fn(self: &strg List<T>[@n], List<T>[@m]) ensures *self: List<T>[n+m])] 11 fn append(&mut self, other: List<T>) { 12 match self { 13 List::Cons(_, tl) => tl.append(other), 14 List::Nil => *self = other, 15 } 16 } 17 }

Fig. 5. Implementation of a refined linked list.

to n + m. The implementation recursively matches on the list self until Nil is found, at which point self is updated in place to point to other. Using standard syntax directed typing rules, Flux decomposes the verification into a (quantifier free) verification condition of the form:

(0 = 푛⇒푚= 푛+ 푚) ∧(푣+ 1 = 푛⇒푣+ 푚+ 1 = 푛+ 푚)

where the first conjunct checks that *self has type List<T>[m] after the update in the base case, and the second checks that *self has type List<T>[n + m] after the recursive call.

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.


<!-- page 9 -->

Flux: Liquid Types for Rust 169:9

Refinements 푟 ::= 푎| ℓ| true | false | 0, ±1, . . . | 푟= 푟| ¬푟| 푟[∧, ∨] 푟| 푟[+, −, ∗] 푟 Expressions 푒 ::= let 푥= new(휌) in 푒| unpack(푥,푎) in 푒| call 푒[푟](av) | 푝:= 푒 | if 푒{푒} else {푒} | let 푥= 푒in 푒| &strg 푝| &mut 푝| &shr 푝| ∗푝| 푥| 푣 Values 푣 ::= rec 푓[푎](푥) := 푒| true | false | 0, ±1, . . . | h | ptr(ℓ,푡) A-values av ::= 푥| 푣 Places 푝 ::= 푥| ptr(ℓ,푡) Types 휏 := 퐵[푟] | {푎. 퐵[푎] | 푟} | ptr(휂) | &휇휏| | ∀푎: 휎. fn(T;휏) →휏/T Base Types 퐵 ::= int | bool Contexts Modifier 휇 ::= mut | shr Value Γ := ∅| Γ,푥:휏 Locations 휂 := ℓ| 휌 Refinement Δ := ∅| Δ,푎: 휎| Δ,푟 Sorts 휎 := int | bool | loc Location T := ∅| T,휂↦→휏

Fig. 6. Syntax of 휆LR.

## 3 FORMALIZATION

In this section, we introduce 휆LR, a core calculus which models Rust's safe fragment extended with refinement types. To aid understanding, we first describe the syntax (§3.1) and type system (§3.2) using only simple data types (int and bool). Next, we show how to extend the system with vectors (§3.3). Crucially, we define 휆LR's type system as an analysis to be layered on top of Rust's ownership system. Instead of relying on the details of the borrow checker, we capture this requirement by instrumenting the operational semantics with a dynamic analysis based on the Stacked Borrows aliasing discipline [Jung et al. 2019] and use it to prove soundness of 휆LR's type system (§3.4). The complete definitions and proofs can be found in our technical appendix [Lehmann et al. 2023b].

### 3.1 Syntax of 휆LR.

Fig. 6 summarizes the syntax of 휆LR. Most of the grammar is based on a standard call-by-value language with (Rust-like) references. In the following we discuss the bits that are different. Refinements The language of logical refinements includes refinement variables, constants for booleans and integers, and operations for equality, boolean logic, and integer arithmetic. We write refinement variables as 푎in general and (by convention) as 휌when referring to an abstract location. Additionally, refinements contain concrete locations ℓwhich show up due to the operational rules. Expressions Local variables, introduced with let-bindings and written 푥or 푓, are pure values. This differs from Rust's local variables which are mutable and addressable. To model Rust's variables correctly, we use let 푥= new(휌) in 푒to bind the local variable 푥to a heap-allocated location represented by the variable 휌.

A function is declared as rec 푓[푎](푥) := 푒, where 푓is a binder for the (potentially) recursive call, 푎is a list of refinement parameters, and 푥a list of binders for the arguments. Functions can be called using call 푒[푟](av), where 푟is the list used to instantiate refinement parameters and av is a list of arguments. Arguments must be A-values (either 푥or 푣), which simplifies the typing rules.

The unpack(푥,푎) in 푒instruction is used when a variable 푥has type {푏. 퐵[푏] | 푟} to introduce a fresh name 푎for the (existentially quantified) refinement variable 푏.

In addition to Rust's &shr 푝and &mut 푝borrow expressions, 휆LR includes &strg 푝to borrow a strong pointer. Borrows are restricted over a place 푝which can be either a variable or a tagged pointer ptr(ℓ,푡). Tagged pointers only show up at run-time and are discussed in §3.4. The same place-only restriction applies to the left-hand side of an assignment 푝:= 푒and to dereferences ∗푝.

A poison value, h, is used to represent uninitialized memory and will cause the program to get stuck if used in any way that affects the evaluation (e.g., as a branch condition). We also use h to evaluate an expression when the value is not relevant, e.g., the value returned by an assignment.

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.


<!-- page 10 -->

169:10 Nico Lehmann, Adam T. Geller, Niki Vazou, and Ranjit Jhala

decr : fn(&mut nat) → let decr = rec 푓[](푥) :=

Δ1 = ∅; Γ1 = gt: ..., sub: ...,

푓: ...,푥: &mut nat; T1 = ∅ let 푦= ∗푥in Δ2 = ∅; Γ2 = Γ1,푦: nat; T2 = T1 unpack (푦,푎푦) in Δ3 = 푎푦: int,푎푦≥0; Γ3 = Γ1,푦: int[푎푦]; T3 = T1 if call gt[푎푦, 0](푦, 0) {

Δ4 = Δ3,푎푦> 0; Γ4 = Γ3; T4 = T1 푥:= call sub[푎푦, 1](푦, 1) T-ass : Δ4 ⊢int[푎푦−1] ≼nat } else {h}

Types As discussed in §2.1, indexed types 퐵[푟] and existential types {푎. 퐵[푎] | 푟} refine a base type 퐵, which can be either int or bool. Next, the type describes uninitialized memory (h).

There are two kinds of pointer types: strong pointers ptr(휂) and (borrowed) references &휇휏. A strong pointer ptr(휂) points to a precise location 휂(either concrete ℓor abstract 휌). Because we model the stack using heap allocations, strong pointers also represent Rust's local variables unifying the treatment of exclusive ownership and strong references discussed in §2.2. References &휇휏, which represent standard Rust references, are qualified by a modifier 휇which can be either shr (for shared references) or mut (for mutable references).

A function type ∀푎:휎. fn(T푖;휏) →휏/T표, can be parameterized by a list of refinement variables 푎each with a declared sort 휎. The location contexts T푖and T표capture the type of locations before and after the function call. For example,

∀푎:int, 휌:loc. fn(휌↦→int[푎]; ptr(휌)) → /휌↦→int[푎+ 1]

### 3.2 Type Checking of 휆LR.

The typing judgment Δ; Γ; T푖⊢푒: 휏⊣T표states that under the refinement context Δ, value context Γ, and input location context T푖, the expression 푒has type 휏and produces a location context T표. The output type and location context can respectively be weakened using the judgments for subtyping (Δ ⊢휏1 ≼휏2) and location context inclusion ( Δ ⊢T1 ⇒T2).

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.

ref_join : ∀푎:bool. fn(bool[푎]) →nat let ref_join = rec 푓[푎](푧) :=

Δ1 = 푎:bool; Γ1 = decr:. . . , 푓:. . . ,푧:bool[푎]; T1 = ∅ let 푥= new(휌푥) in Δ2 = Δ1, 휌푥:loc; Γ2 = Γ1,푥:ptr(휌푥); T2 = T1, 휌푥↦→ 푥:= 1; Δ3 = Δ2; Γ3 = Γ2; T3 = T1, 휌푥↦→int[1] let 푦= new(휌푦) in 푦:= 2; Δ4 = Δ3, 휌푦:loc; Γ4 = Γ3,푦:ptr(휌푦); T4 = T3, 휌푦↦→int[2] let 푟= if 푧{

Δ51 = Δ4,푎; Γ51 = Γ4; T51 = T4 &mut 푥 : &mutnat, by rule T-bsmut Δ61 = Δ51; Γ61 = Γ4; T61 = 휌푥↦→nat, 휌푦↦→int[2] } else {

Δ52 = Δ4, ¬푎; Γ52 = Γ4; T52 = T4 &mut 푦 : &mutnat, by rule T-bsmut Δ62 = Δ52; Γ62 = Γ4; T62 = 휌푥↦→int[1], 휌푦↦→nat } Δ7 = Δ4; Γ7 = Γ4; T7 = 휌푥↦→nat, 휌푦↦→nat in Δ8 = Δ7; Γ8 = Γ7,푟:&mutnat; T8 = T7 call decr(푟); ∗푥 : nat, by T-deref-strg

Fig. 7. 휆LR encoding and type checking of the examples decr (lef) and ref_join (right) from § 2.

is the type of a function that takes a strong pointer to an int[푎] and updates it to int[푎+ 1] (the type of incr in fig. 2). We omit the list of refinement parameters, the list of arguments, or the input and output location contexts if they are empty.

Figs. 8 and 9 define the three main judgments of 휆LR. They use three kinds of contexts (fig. 6). The refinement context Δ maps refinement variables to sorts and also contains predicates that relate these variables. The value context Γ tracks local variables in scope and maps them to types. Finally, the location context T describes ownership of locations with their corresponding types.


<!-- page 11 -->

Flux: Liquid Types for Rust 169:11

Expression Typing Δ; Γ; T ⊢푒: 휏⊣T

Δ; Γ; T푖⊢푒: 휏1 ⊣T Δ ⊢휏1 ≼휏 Δ ⊢T ⇒T표 Δ; Γ; T푖⊢푒: 휏⊣T표

T-sub

Δ; Γ; T푖⊢푒푥: 휏푥⊣T Δ; Γ,푥:휏푥; T ⊢푒: 휏⊣T표 Δ; Γ; T푖⊢let 푥= 푒푥in 푒: 휏⊣T표

T-let

∀푖.Δ; Γ; T ⊢av푖: 휃· 휏푖⊣T Δ; Γ; T ⊢푒: ∀푎: 휎. fn(T푖;휏) →휏표/T표⊣T1, T2 휃= [푟/푎] Δ ⊢T1 ⇒휃· T푖 ∀푖.Δ ⊢푟푖: 휎푖 Δ; Γ; T ⊢call 푒[푟](av) : 휃· 휏표⊣휃· T표, T2

Δ,푎: sort(퐵),푟; Γ1,푥:퐵[푎], Γ2; T푖⊢푒: 휏⊣T표 Δ; Γ1,푥:{푎. 퐵[푎] | 푟}, Γ2; T푖⊢unpack(푥,푎) in 푒: 휏⊣T표

Δ; Γ; T표⊢푝: &mut휏⊣T표 Δ; Γ; T푖⊢푒: 휏푣⊣T표 Δ ⊢휏푣≼휏

T-ass

Δ; Γ; T푖⊢푝:= 푒: ⊣T표

Values

Δ,푎:휎; Γ,푥:휏, 푓: ∀푎:휎. fn(T푖;휏) →휏/T표; T푖⊢푒: 휏⊣T표 Δ; Γ; T ⊢rec 푓[푎](푥) := 푒: ∀푎: 휎. fn(T푖;휏) →휏/T표⊣TT-fun

푐∈{true, false}

Δ; Γ; T ⊢푐: bool[푐] ⊣TT-bool Δ; Γ; T ⊢h : ⊣TT-mem Δ; Γ; T ⊢푖: int[푖] ⊣TT-int

Borrows

Δ; Γ; T ⊢푝: ptr(휂) ⊣T

Δ; Γ; T ⊢&strg 푝: ptr(휂) ⊣TT-bstrg

Δ ⊢T(휂) ≼휏 Δ; Γ; T ⊢푝: ptr(휂) ⊣T

Δ; Γ; T ⊢&mut 푝: &mut휏⊣T[휂↦→휏] T-bsmut

Dereference

Δ; Γ; T ⊢푝: &휇휏⊣T

Δ; Γ; T ⊢∗푝: 휏⊣T T-deref

Δ ⊢wf 휏 Δ ⊢wf T표 Δ, 휌:loc; Γ,푥:ptr(휌); T푖, 휌↦→ ⊢푒: 휏⊣T표

T-new

Δ; Γ; T푖⊢let 푥= new(휌) in 푒: 휏⊣T표

Δ; Γ; T푖⊢푒: bool[푟] ⊣T표

Δ,푟; Γ; T표⊢푒1 : 휏⊣T Δ, ¬푟; Γ; T표⊢푒2 : 휏⊣T

Δ; Γ; T푖⊢if 푒{푒1} else {푒2} : 휏⊣TT-if

T-call

T-unpack

Δ; Γ; T표⊢푝: ptr(휂) ⊣T표

Δ; Γ; T푖⊢푒: 휏⊣T표 Δ; Γ; T푖⊢푝:= 푒: ⊣T표[휂↦→휏] T-ass-strg

푥: 휏∈Γ

Δ; Γ; T ⊢푥: 휏⊣TT-var

Δ; Γ; T ⊢푝: &mut휏⊣T

Δ; Γ; T ⊢&mut 푝: &mut휏⊣TT-bmut

Δ ⊢휏′ ≼휏 Δ; Γ; T ⊢푝: &휇휏′ ⊣T

Δ; Γ; T ⊢&shr 푝: &shr휏⊣TT-bshr

Δ; Γ; T ⊢푝: ptr(휂) ⊣T

Δ; Γ; T ⊢∗푝: T(휂) ⊣T T-deref-strg

Fig. 8. Expression typing of 휆LR (some well-formedness requirements are omited).

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.


<!-- page 12 -->

169:12 Nico Lehmann, Adam T. Geller, Niki Vazou, and Ranjit Jhala

Context inclusion Δ ⊢T ⇒T

Δ ⊢T1 ⇒T2 Δ ⊢T2 ⇒T3 Δ ⊢T1 ⇒T3

C-trans

Δ ⊢T1 ⇒T2 Δ ⊢T, T1 ⇒T, T2

Δ ⊢T, T′ ⇒TC-Weak

Subtyping Δ ⊢휏≼휏

Δ ⊢ptr(휂) ≼ptr(휂) S-ptr Δ ⊢ ≼ S-mem

Δ,푎: sort(퐵),푟⊢퐵[푎] ≼휏

Δ ⊢{푎. 퐵[푎] | 푟} ≼휏 S-unpack

Δ ⊢휏1 ≼휏2 Δ ⊢&shr 휏1 ≼&shr 휏2

S-shr

Δ,푎: 휎|= 푟2 ⇒푟1 Δ,푎: 휎⊢T2푖⇒T1푖 ∀푖.Δ,푎: 휎⊢휏2푖≼휏1푖 Δ,푎: 휎⊢T1표⇒T2표 Δ,푎: 휎⊢휏1표≼휏2표 Δ ⊢∀푎: 휎. fn(T1푖;휏1) →휏1표/T1표≼∀푎: 휎. fn(T2푖;휏2) →휏2표/T2표

Fig. 9. Context Inclusion & Subtyping of 휆LR.

To see these judgments in action, we will go through parts of the typing derivations of two examples from §2.2: decr and ref_join. Fig. 7 presents the encoding of both examples in 휆LR. Example 1 The translated version of decr, together with some annotations describing the contexts at each step, is shown on the left of fig. 7. The Rust's operators greater than (>) and subtraction (-) are modeled respectively as the predefined functions gt and sub, with the following types:

sub : ∀(푎1,푎2 :int). fn(int[푎1], int[푎2]) →int[푎1 −푎2]

Next, since 푥is a reference, T-deref is used to give ∗푥type nat, which is then assigned to 푦in the value context (by rule T-let). Remember that nat abbreviates {푏. int[푏] | 푏≥0}, so the next instruction unpacks the existential with a fresh variable 푎푦, extending the refinement context with 푎푦:int,푎푦≥0 and updating the type of 푦to int[푎푦] (rule T-unpack).

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.

T′ is a permutation of T

Δ ⊢T ⇒T′ C-Perm

Δ ⊢휏1 ≼휏2 Δ ⊢휂↦→휏1 ⇒휂↦→휏2

C-Frame

C-Sub

Δ |= 푟1 = 푟2 Δ ⊢퐵[푟1] ≼퐵[푟2]S-idx

Δ |= 푟2[푟1/푎]

Δ ⊢퐵[푟1] ≼{푎. 퐵[푎] | 푟2}S-ex

Δ ⊢휏1 ≼휏2 Δ ⊢휏2 ≼휏1 Δ ⊢&mut 휏1 ≼&mut 휏2

S-mut

S-fun

gt : ∀(푎1,푎2 :int). fn(int[푎1], int[푎2]) →bool[푎1 > 푎2]

Type-checking of decr begins by applying T-fun to check the function as fn(&mut nat) → . Consequently, 푥is assigned type &mutnat in the initial value context inside the function body. The context also contains bindings for the recursive call 푓and the predefined functions gt and sub.

In the call to gt, 푎푦and 0 are used to instantiate the refinement parameters. The rule T-call, first checks that they have the correct sorts (the well-sorted judgment is defined in the technical appendix Lehmann et al. [2023b]). In this case, they both have sort int matching the sorts of 푎1 and 푎2 declared in the function signature. Given these refinement arguments, rule T-call defines the substitution 휃= [푎푦/푎1][0/푎2] to check subtyping for the arguments (rule T-int types integers as


<!-- page 13 -->

Flux: Liquid Types for Rust 169:13

Values 푣 ::= · · · | Vec휏::new | Vec휏::push | Vec휏::index_mut | vec휏(푛, ptr(ℓ,푡)) Base Types 퐵 ::= · · · | Vec휏

Vec휏::new : fn() →Vec휏[0]

Vec휏::index_mut : ∀푎:int. fn(&mutVec휏[푎], {푏. int[푏] | 0 ≤푏< 푎}) →&mut휏

singletons):

After applying the substitution, the types match exactly and subtyping is trivially satisfied. Note that the rule T-call also needs to check inclusion for the function's input location context (allowing framing). In this case, since gt has empty location contexts the requirement is satisfied trivially.

푎푦:int,푎푦≥0,푎푦> 0 ⊢int[푎푦−1] ≼{푏. int[푏] | 푏≥0}

Subtyping, via rule S-ex, reduces the above to the following validity query

푎푦:int,푎푦≥0,푎푦> 0 |= 푎푦−1 ≥0

which is decided valid in the theory of linear arithmetic. Thus, type-checking of decr succeeds.

Thus, the rule T-sub weakens each context to obtain 휌푥↦→nat, 휌푦↦→nat as the join. Finally, after the call to decr, the rule T-deref-strg types ∗푥as nat which matches the declared return type.

Vec휏::push : ∀푛:int, 휌:loc. fn(휌↦→Vec휏[푛]; ptr(휌),휏) → /휌↦→Vec휏[푛+ 1]

Fig. 10. Extension of 휆LR with Vectors.

(1) Δ3 ⊢int[푎푦] ≼휃· int[푎1] (2) Δ3 ⊢int[0] ≼휃· int[푎2]

Applying the substitution to the return type of gt gives bool[푎푦> 0], which is used as the condition in the if statement. So, rule T-if checks the then branch in a refinement context extended with the assumption 푎푦> 0. The goal is to prove that the assignment to 푥is safe in this context. First, by rule T-call the result of calling sub has type int[푎푦−1]. Then, since 푥is a reference, the rule T-ass is used to check the assignment generating the following subtyping constraint:

Example 2 The 휆LR version of ref_join is shown on the right of fig. 7. The initialization of 푥(resp. 푦) is translated into 휆LR as an allocation followed by an assignment. Therefore, first the rule T-new

is used to type the allocation. This has three effects: (1) it extends the refinement context with a fresh location 휌푥, (2) it binds 푥as a strong pointer ptr(휌푥), and (3) it marks the new location as uninitialized 휌푥↦→ . This new location is local, in that the output type and location context of the rule cannot refer to it, which is imposed by well-formedness premises. (Well-formedness is checked w.r.t. binders in Δ.) Then, since 푥is a strong pointer, the rule T-ass-strg types the assignment and strongly updates the type of 휌푥to int[1]. The initialization of 푦proceeds analogously.

Next, to type &mut 푥(resp. &mut 푦) the rule T-bsmut is used and "picks" nat as the bound in the premise (via inference as explained in §4.2). This choice has the effect of weakening the type associated to 휌푥(resp. 휌푦). At this point, the two branches have the following location contexts:

(then) 휌푥↦→nat, 휌푦↦→int[2] (else) 휌푥↦→int[1], 휌푦↦→nat.

### 3.3 Extension of 휆LR with Vectors.

In §2.3 we presented an API for refined vectors indexed by their size. In this section we show how 휆LR is extended with a similar API. We treat vectors as a primitive, i.e., with dedicated typing and operational rules (§3.4). This differs from Rust, where vectors are not a primitive but rather implemented using unsafe operations which are properly "encapsulated" [Jung et al. 2017]. In our

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.


<!-- page 14 -->

169:14 Nico Lehmann, Adam T. Geller, Niki Vazou, and Ranjit Jhala

setting, encapsulated means that if programmers use the exposed vector API but otherwise avoid unsafe operations themselves, then their programs should not exhibit unsafe/undefined behavior.

To encapsulate the unsafe operations, the implementation of vectors in Rust contains run-time checks to ensure vectors are never accessed with invalid indices. Our extension of 휆LR with vectors removes these checks and illustrates how (in principle) unsafe operations can also be encapsulated under a refined API with the same safety guarantees.

Fig. 10 summarizes how the system is extended with vectors. Base types are extended with Vec휏, i.e., vectors of elements of type 휏(which can be indexed by their size). Values are extended with functions on vectors, which are given types mirroring the API described in §2.3. Finally, the value vec휏(푛, ptr(ℓ,푡)), represents a vector that points to a block of memory starting at ℓthat holds 푛 (contiguous) elements of type 휏. This value is not part of the surface syntax, and as such does not have a top-level type, but shows up at run-time as part of the operational rules for vectors.

### 3.4 Soundness of 휆LR.

We ensure soundness of 휆LR--extended with vectors--by proving standard progress and preservation theorems. For space restrictions, we only give a high-level description of the soundness theorem. The detailed proofs and the full definition of our call-by-value small-step operational semantics can be found in the technical appendix [Lehmann et al. 2023b].

The operational semantics follows the Stacked Borrows aliasing discipline [Jung et al. 2019]. In Stacked Borrows, pointers ptr(ℓ,푡) are tagged and for each location, additional state is used to track existing pointers to the location. The extra state is used to detect violations of Rust's borrowing rules at run-time. We define our operational semantics to return an error if any such violation is detected. Concretely, given a heap ℎmapping locations to values and a stacked borrows state 휍, an expression 푒can take an evaluation step ⟨ℎ,휍,푒⟩⇝⟨ℎ표,휍표,푒표⟩or return an aliasing violation error ⟨ℎ,휍,푒⟩⇝ERR. We say that an evaluation is well-borrowed when it does not return an error.

To relate the run-time state with the static type system, we define a dynamic environment that maps value pointers to pointer types (either a strong pointer or a reference). Then, we extend the typing judgment with an extra context Σ and give pointers ptr(ℓ,푡) type Σ(ℓ, 푡). Finally, we define a well-typed state relation T; Σ ⊢⟨ℎ,휍⟩, that intuitively states that--if the stacked borrows rules are followed--it is safe to read from a pointer ptr(ℓ,푡) at type Σ(ℓ, 푡).

With these definitions in place, we proved the following soundness statement:

Theorem 3.1 (Soundness). If ∅; ∅; T푖; Σ푖⊢푒푖: 휏⊣T, T푖; Σ푖⊢⟨ℎ푖,휍푖⟩, and ⟨ℎ푖,휍푖,푒푖⟩⇝★⟨ℎ,휍,푒⟩, then one of the following holds

(1) ⟨ℎ표,휍표,푒푖⟩⇝ERR, or (2) 푒is a value and there exist T표and Σ표⊇Σ푖such that ∅; ∅; T표; Σ표⊢푒: 휏⊣T, or (3) there exists T표, Σ표⊇Σ푖, ℎ표,휍표, and 푒표such that ⟨ℎ,휍,푒⟩⇝⟨ℎ표,휍표,푒표⟩, T표; Σ표⊢⟨ℎ표,휍표⟩, and

∅; ∅; T표; Σ표⊢푒표: 휏⊣T.

That is, well-borrowed evaluations of well-typed programs do not get stuck. This implies, for example, that vectors are always accessed with valid indices.

## 4 ALGORITHMIC VERIFICATION

Flux implements the type checking rules presented in §3 as a Rust compiler plugin, adding an extra analysis step to the compiler pipeline. As a plugin, Flux operates on programs that have already been analyzed by the compiler. This has two major benefits. First, the compiler's intermediate representations are elaborated with inferred type information which is used by our analysis. Second, we can assume programs satisfy Rust's borrowing rules, which our analysis relies on.

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.


<!-- page 15 -->

Flux: Liquid Types for Rust 169:15

Concretely, Flux performs its analysis on the compiler's Mid-level Intermediate Representation (MIR). The MIR is a control-flow graph (CFG) representation, unlike our core calculus, which relies on recursive functions to represent complex control-flow constructs. However, both representations are easy to relate via the correspondence defined by Appel [2007].

Still, there are three key challenges to address in bridging the gap between the formalism presented in §3 and our implementation. First (§4.1), the syntax of 휆LR has explicit refinement annotations that do not appear in Rust's MIR. Second (§4.2), some judgments in 휆LR have rules (e.g., T-fun, T-bsmut, and T-sub) with a non-deterministic choice of types that the implementation needs to infer. Finally (§4.3), Flux supports polymorphic types which are crucial for ergonomic specification and verification, but require instantiating type parameters with refinement types.

### 4.1 Refinement Annotations

Flux, following the essence of refinement typing, does not modify the syntax of Rust programs, but allows refined function signatures. Thus, users must declare refined signatures for top-level functions (using the syntax described in §2), but the placement of unpack instructions and the instantiation of refinement parameters at function calls are automatically inferred by Flux.

Flux places unpack instructions implicitly and on-the-fly, by eagerly generating a fresh refinement variable as soon as an existential type enters the value context. As an example, recall the translated version of decr (§3.2). As soon as 푦: {푏. int[푏] | 0 ≥푏} is introduced in the value context, Flux places an implicit unpack(푦,푎푦) with a fresh refinement variable 푎푦.

The instantiation of refinement parameters is performed by syntax-directed unification during subtyping [Economou et al. 2022]. For example, in the function decr, the comparison y > 0 is encoded in 휆LR as call gt[푎푦, 0](푦, 0), where 푎푦and 0 instantiate the parameters 푎1 and 푎2 in the signature of gt:

∀(푎1,푎2 :int). fn(int[푎1], int[푎2]) →bool[푎1 > 푎2]

In the implementation, the instantiation for 푎1 and 푎2 needs to be inferred. To do so, when the types of the actual arguments int[푎푦] and int[0] are checked for subtyping against the type of the formals int[푎1] and int[푎2], we unify their indices and instantiate 푎1 to 푎푦and 푎2 to 0.

To guarantee that unification always succeeds, refinement parameters must be restricted to appear in certain positions within types. The exact restrictions are best described by Economou et al. [2022], but the intuition is that a refinement parameter must be used at least once as an index in argument position. Since the surface syntax does not expose explicit quantification, but rather uses the @n syntax to declare refinement parameters, we can guarantee that signatures satisfy the restrictions by restricting the positions where @n can be used. For example, Flux will reject the signature fn() -> i32[@n], because the parameter n is declared in return position. More notably, parameters cannot be declared inside a type argument of a polymorphic type constructor. This restriction can be circumvented by declaring extra parameters. For instance, the function normalize_centers in fig. 4 binds n by taking an extra usize[@n] as the first argument, because Flux would reject the signature if declared as:

fn(&mut RVec<RVec<f32>[@n]>[@k], &RVec<f32>[k])) As a limitation of the current implementation, n must be attached to a computational parameter, but this is in principle a ghost parameter.

### 4.2 Refinement Inference

Several rules of fig. 8 have cases where types are inferred. For example, T-fun guesses the type of the function, T-bsmut guesses the type of the resulting mutable reference, and T-sub guesses weakened types, allowing unification at join points and function calls. Flux's inference proceeds in

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.


<!-- page 16 -->

169:16 Nico Lehmann, Adam T. Geller, Niki Vazou, and Ranjit Jhala

three phases. To illustrate these phases, let us see how types can be inferred at the join point after the if statement in the ref_join function (§3.2). In summary, the application of the rules requires inferring three types 휏1, 휏2, and 휏satisfying the following requirements:

(1) 푎⊢int[1] ≼휏1 (2) ¬푎⊢int[2] ≼휏2 (3) ∅⊢&mut 휏1 ≼휏 (4) ∅⊢&mut 휏2 ≼휏

In the then branch, the borrow of 푥weakens its type from int[1] to 휏1 (by T-bsmut) leading to (1). Similarly, in the else branch borrowing 푦leads to (2). Finally, in the assignment to 푟, the reference type in each branch must be unified to a common type 휏, leading to (3) and (4).

Phase 1: Shape Inference Flux begins by inferring the shape of the types to the most general one that can satisfy the subtyping requirements. In our example, to satisfy (1) we know that either S-idx or S-ex must apply. Thus, 휏1 must be either an indexed type or an existential. We note that, without changing the typability of the program, we can always choose 휏1 to be an existential, because for any indexed type 퐵[푛] we have 퐵[푛] ⪯{푏. 퐵[푏] | 푏= 푛} and {푏. 퐵[푏] | 푏= 푛} ⪯퐵[푛]. Therefore, Flux determines 휏1 to have the shape:

휏1 {푏. int[푏] | 휅1(푏)}

Crucially, this type contains a refinement predicate 휅1, i.e., an unknown predicate whose value will be decided in the next phase. Similarly, Flux determines the shapes of 휏2 and 휏to be:

휏2 {푏. int[푏] | 휅2(푏)} 휏 &mut {푏. int[푏] | 휅(푏)}

Phase 2: Constraint Generation Next, Flux uses the type checking rules to generate a verification condition (VC) that constrains the unknown predicates. For our example, it yields the below VC:

(1) 푎⇒휅1(1) ∧ (2) ¬푎⇒휅2(2) ∧ (3) 휅1(푏) ⇒휅(푏) ∧휅(푏) ⇒휅1(푏) ∧ (4) 휅2(푏) ⇒휅(푏) ∧휅(푏) ⇒휅2(푏) (5) 휅(푏) ⇒푏≥0 ∧푏≥0 ⇒휅(푏) (6) 휅(푏) ⇒푏≥0

Here, the conjuncts (1) to (4) correspond to the subtyping requirements generated in the if statement, while (5) and (6) correspond to the constraints generated by the call to decr and the return type respectively.

Phase 3: Liquid Inference Finally, Flux uses the Liquid Fixpoint3 horn constraint solver to synthesize a solution for the unknown predicates using predicate abstraction. More concretely, it finds a solution for the 푘-predicates over an abstract domain of formulas generated by conjunctions of predefined atomic predicates [Cosman and Jhala 2017]. In our example, Liquid Fixpoint finds the solution 휅(푏),휅1(푏),휅2(푏) := 푏≥0, which satisfies the original subtyping requirements. In general, the unknown 휅predicates are Horn variables that may have multiple arguments, allowing liquid inference to track dependencies between multiple program variables, thereby enabling Flux to automatically synthesize loop invariants.

### 4.3 Polymorphic Instantiation

As described in §2, Flux exploits polymorphism to infer invariants over elements of polymorphic type constructors. To achieve this, Flux instantiates type parameters with existentials containing unknown predicates. For instance, consider the function below that creates a single element vector.

3https://github.com/ucsd-progsys/liquid-fixpoint

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.


<!-- page 17 -->

Flux: Liquid Types for Rust 169:17

Table 1. Experimental results comparing Flux and Prusti. LOC is the number of lines of Rust source code, Spec is the number of lines for function specifications, Annot is the amount of lines for user-specified loop invariants, and (% LOC) is the ratio of loop-invariant lines to Rust source code, and Time (s) is the time in seconds required to verify the code (trusted code does not have time).

Flux Prusti

LOC Spec Time (s) LOC Spec Annot (% LOC) Time (s)

Library RVec 41 20 - 45 29 - - - RMat 22 6 0.21 33 15 - - - Total 63 26 0.21 78 44 - - -

Benchmark bsearch 25 1 0.18 25 0 1 4% 3.25 dotprod 12 1 0.14 12 1 1 8% 2.75 fft 162 7 0.70 188 22 24 12% 166.76 heapsort 37 2 0.22 37 5 9 24% 8.25 simplex 118 8 0.45 125 25 8 6% 12.19 kmeans 85 8 0.43 87 37 10 11% 13.41 kmp 48 2 0.51 49 4 7 14% 10.23 Total 487 29 2.63 423 94 60 14% 217.91

Case Study WaVe 5585 318 16 5585 1001 47 0.8% 2040

#[flux::sig(fn() -> RVec<i32{v: v > 0}>)] fn make_vec() -> RVec<i32> {

let vec = RVec::new(); // vec ↦→RVec<i32{휈: 휅1(휈)}>[0] RVec::push(&mut vec, 42); // vec ↦→RVec<i32{휈: 휅2(휈)}>[1] vec }

The comments show the type of vec after each statement. In the call to new, Flux needs to instantiate the parameter T in the return type RVec<T>[0]. We extract from the Rust compiler that T needs to be an i32 but its refinement is unknown. Thus, Flux instantiates T with the template i32{휈: 휅1(휈)} where 휅1 is a fresh unknown predicate. Similarly, the call to push generates the template i32{휈: 휅2(휈)} . Type-checking the program with these templates generates the (Horn) VC (휅1(휈) ⇒휅2(휈)) ∧(휈= 42 ⇒휅2(휈)) ∧(휅2(휈) ⇒휈> 0). The first two conjuncts correspond to subtyping for the two arguments to push; the third relates the type of vec to the output type. Using liquid inference, Flux solves 휅1(휈) := 휈> 0 and 휅2(휈) := 휈> 0 which is strong enough to check the above verification condition is valid, and hence, verify the type of make_vec.

## 5 EVALUATION

Next, we present an empirical evaluation of the benefits of Flux's refinement type-based, lightweight verification compared to classic program logic-based approaches as embodied in Prusti [Astrauskas et al. 2019], a state-of-the-art program logic based verifier for Rust that also exploits the implicit capability information present in Rust's type system to reduce the verification overhead. Prusti supports deep verification, i.e., it allows users to verify various forms of functional correctness properties (e.g., sorted-ness) not expressible in Flux. However, we show that for many common and important use-cases, Flux's type-based lightweight verification is more attractive.

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.


<!-- page 18 -->

169:18 Nico Lehmann, Adam T. Geller, Niki Vazou, and Ranjit Jhala

In particular, our evaluation focuses on three dimensions for comparison: do types (§5.2) enable compact specifications? (§5.3) require fewer annotations? (§5.4) facilitate faster verification?

### 5.1 Benchmarks

We compare Flux and Prusti on two sets of benchmarks: a set of vector-manipulating programs from the literature and a larger case study porting WaVe: a Rust-based Web-Assembly sandboxing runtime [Johnson et al. 2023].

Case Study: Vector Bounds Checking Our first set of benchmarks is a set of vector-manipulating programs drawn from the literature [Rondon et al. 2008], which implement loop-heavy algorithms over the RVec library discussed in §2.3. Some benchmarks use RMat, a refined 2-dimensional matrix indexed by the number of rows and columns, which was implemented on top of RVec as a vector of vectors. In each case, the verification goal is to prove the safety of vector accesses for the program. The benchmarks are listed in table 1. The first five benchmarks are ported from the Dsolve project [Rondon et al. 2008], a refinement type system for Ocaml. These include implementations of: Binary Search (bsearch), computing the Dot Product of two vectors (dotprod), Fast Fourier Transform (fft), Heap Sort (heapsort), and the Simplex algorithm for Linear Programming (simplex). The last two benchmarks are implementations of the k-means clustering algorithm (kmeans) and the Knuth-Morris-Pratt string-searching algorithm (kmp). These two were chosen to highlight the ability of Flux to express quantified invariants via polymorphism. In each case, we first verified the code in Flux and then replicated it as closely as possible in Prusti.

Case Study: Verified Sandboxing in WaVe Our second set of benchmarks is from a Web-Assembly sandboxing library previously written in Rust and already verified with Prusti [Johnson et al. 2023]. This case study evaluates whether Flux's refinement types are expressive enough to capture realworld security requirements, while still offering advantages in terms of annotation and verification overhead. In brief, the security properties include checking that (1) vectors and slices are accessed within their bounds, (2) memory accesses granted by the sandbox stay within the sandbox's memory region and (3) symbolic links in filepath components are fully resolved to point within the sandbox. We refer the reader to [Johnson et al. 2023] for more details. For our case study, we ported all the Prusti specifications in WaVe to Flux. Crucially, we were able to use Flux's refined struct mechanism to compactly capture all the secure sandboxing specifications as refinement types, thereby using plain Rust typing and polymorphism to entirely eliminate quantifiers from the specifications.

Setup We ran all the experiments on a laptop running Fedora 36 with 32GB of memory and a 12th Gen Intel(R) Core(TM) i7-1280P CPU. We used the following versions of the software required to run Prusti: (1) Prusti commit 673a095d, (2) Z3 v4.8.6, and (3) openjdk-17.0.4.1. To measure times for Prusti, we timed the execution of running the prusti-rustc command line tool on each individual benchmark, setting the check_overflows flag to false. Table 1 summarizes statistics about the implementations, including lines of code (LOC). The LOC count has small differences between Flux and Prusti. This discrepancy is mostly due to differences in the way RVec has to be specified in Prusti, which sometimes requires adjustments to the code, as we explain in §5.2. We emphasize that the main takeaway with the case study row is just that Flux's refinement types are expressive enough to specify and efficiently verify the security properties for a complex sandbox [Johnson et al. 2023]. The reduction in specification size (and hence, likely verification time) is largely because we were able to encapsulate the requirements as refined structs e.g., that representing the "Virtual Machine Context" (VmCtx) which then let us replace a swathe of Prusti's requires and ensures clauses simply with the Rust type signature. Finally, it is likely that if the Prusti developers rewrote the specifications, they could exploit their knowledge of what yields the

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.


<!-- page 19 -->

Flux: Liquid Types for Rust 169:19

// Rust Specification ------------------------------------------------------------- fn store(&mut self, idx: usize, value: T)

// Prusti Specification ----------------------------------------------------------- #[requires(idx < self.len())] // (1) #[ensures(self.len() == old(self.len()))] // (2) #[ensures(forall(|i: usize| (i < self.len() && i != idx) ==> // (3) self.lookup(i) == old(self.lookup(i))))] #[ensures(self.lookup(idx) == value)] // (4)

// Flux Specification ------------------------------------------------------------- fn store(self: &mut RVec<T>[@n], i: usize{v: v < n}, value: T)

Fig. 11. The specifications for RVec::store in Rust, Prusti and Flux,

most efficiently solved VCs to shrink specification size--e.g., by using the type invariant mechanism [Astrauskas et al. 2022] to compactly specify the requirements on VmCtx--and hence, verification time.

### 5.2 Compact API Specifications

The column Spec in table 1 shows the lines of code required for function specifications in Flux and Prusti. For the most part, the number of lines are similar, but slightly larger for Prusti, mostly due to the style of splitting annotations out into separate lines,e.g., for pre- and post-conditions. However, in some important situations, Flux's type-based specifications allow for APIs that are shorter to write, faster to verify, and easier to reuse.

Quantifiers vs. Polymorphism In §2.3 we showed a concise and precise interface for RVec which uses polymorphism to express quantified invariants over the elements of the vector. An interesting piece of this interface is get_mut, used to grant mutable access to the vector while maintaining the invariants over its elements. The simplest way to provide a comparable interface in Prusti is by defining a store function with the specification in fig. 11. (Prusti also supports the specification of get_mut using a more advance feature called pledges, but it has the same drawbacks as store.) This function takes a mutable reference to the vector, an index, and a value to store in that index. The specification in Prusti requires the index to be within bounds (condition 1) and ensures that the vector has the same length after the function returns (condition 2) and all the elements in the vector remain unchanged except for the one being updated which instead gets the new value (conditions 3 and 4).

Prusti's specification is strictly more expressive than Flux's specification. For instance, Prusti's specification can be used to verify relational properties between elements (e.g., sortedness). In contrast, Flux's signature, which relies on polymorphism, can only express unary predicates that must be true for all elements. However, quantifying over the elements is necessary to verify kmeans, kmp, and wave, as these benchmarks store pointers and array indices within containers, and hence require tracking invariants of those indices using quantified contracts. For the rest of the benchmarks, a weaker specification that only tracks the vector's length (i.e., conditions 1 and 2) is sufficient.

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.


<!-- page 20 -->

169:20 Nico Lehmann, Adam T. Geller, Niki Vazou, and Ranjit Jhala

### 5.3 Fewer Annotations

The greatest payofffrom refinement types is that by eschewing quantified assertions, they eliminate the annotation overhead for loop invariants. The column Annot in table 1 shows the number of lines taken by Prusti's loop invariant annotations. The annotation overhead for Prusti is non-trivial: up to 24% (average 14%) of the implementation lines of code. In contrast, the column is missing for Flux as it automatically synthesizes the equivalent information via liquid typing §4.

Easy Invariants via Typing For most of the benchmarks, loop invariants express either simple inequalities or tedious bookkeeping (e.g., the length of a vector remains constant through a loop). While simple, they still have to be discovered and manually annotated by the user. The fft benchmark is a particularly egregious example, requiring a substantial amount of annotations, as it has a high number of (nested) loops that require annotation. The following snippet shows the annotations required for one of the loops:

body_invariant!(px.len() == n + 1 && py.len() == n + 1); body_invariant!(i0 <= i1 && i1 <= i2 && i2 <= i3 && i3 <= n);

The first invariant asserts that the lengths of the vectors px and py stay constant through the loop. In Prusti, this must be spelled out as an invariant because the signature for store (fig. 11) says the output-length is the same as the input-length (old), forcing the verifier to explicitly propagate these equalities in the verification conditions. In contrast, as the reference is marked as mut (but not strg), Flux leaves the sizes unchanged and directly uses the same size-index during verification! The second specifies simple inequalities between i0, i1, i2, i3 and n. As this is just a conjunction of quantifier free formulas, it is easily inferred by liquid typing, requiring zero annotations.

Quantified Loop Invariants vs Polymorphism However, several benchmarks require complex universally quantified invariants in Prusti, but are equivalently handled by Flux's support for type polymorphism. For example, the function kmp_table from the kmp string matching benchmark takes as input a vector p of length m and computes a vector t of the same length containing indices into p (i.e., integers between 0 and m). The function also uses two additional variables i and j, which are updated through the function's main loop. The following snippet shows the annotation required by Prusti to verify the implementation of kmp_table:

body_invariant!(forall(|x: usize| x < t.len() ==> t.lookup(x) < i)); body_invariant!(j < i && t.len() == p.len());

The first invariant is the critical one that asserts that in each iteration every element in t must be less than the current value of i. By using polymorphism to quantify over the elements of t, Flux can reduce the inference of this invariant to the inference of a quantifier free formula, liberating the user from manually annotating it.

### 5.4 Faster Verification

The columns Time (s) in table 1 show times taken by Flux and Prusti for each benchmark. To be fair to Prusti, we only use the full specification for store when necessary and default to the weaker one otherwise (without conditions 3 and 4). Prusti consistently takes at least one order of magnitude longer to verify each benchmark, taking close to 3 minutes to verify fft, verified by Flux in 0.7 sec. Note that Flux is faster despite spending time to synthesize loop invariants, unlike Prusti, where this information is furnished by the user. We speculate that there are at least two different reasons for the gap. First, with Prusti's VCs, the SMT solver must instantiate and check quantified loop invariants which are known to cause performance issues in SMT solvers [Leino and Pit-Claudel 2016]. Second, verification with Prusti implicitly constructs proofs for memory safety (via reduction to the Viper IR), which is an overhead Flux avoids by relying upon Rust's guarantees.

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.


<!-- page 21 -->

Flux: Liquid Types for Rust 169:21

While this is advantageous for our benchmarks, Prusti's approach allows it to be used to verify unsafe code, which presently impossible with Flux. Further experimentation with Prusti may shed more light on the gap and yield optimizations that bring the verification times closer.

## 6 RELATED WORK

Rust formal semantics The Stacked Borrows [Jung et al. 2019] aliasing discipline proposes an operational semantics for Rust with the intention of defining undefined behavior when memory accesses through references and raw pointers are combined. Our formalization (§3), uses stacked borrows to characterize the requirements on memory accesses that Flux relies on.

RustBelt [Jung et al. 2017] provides a formalization of Rust aimed at proving that unsafe library implementations encapsulate their unsafe behavior under a well-typed interface. To achieve this they define a semantic interpretation of Rust ownership types in Iris [Jung et al. 2018] and prove that a library using unsafe operations satisfies the predicates of its interface semantic interpretation. It would be interesting to extend RustBelt with refinement types and use the same semantic approach to prove libraries using unsafe operations can be encapsulated under a refined interface.

Weiss et al. [2019] follow a different approach at formalizing Rust. Their model Oxide formalizes a language which is closer to surface Rust, it is based on an interpretation of lifetimes as provenance sets, and resembles the prototype borrow checker implementation Polonius [Matsakis 2018].

Refinement types and imperative code Refinement types were originally developed for the verification of functional programs with ML style references [Freeman and Pfenning 1991; Xi and Pfenning 1999b]. Many of these early ideas were summarized in the Applied Type System framework (ATS) [Xi 2004] which further supported pointer manipulation via a notion of stateful views that required manually provided proofs to track ownership [Zhu and Xi 2005].

The idea of synthesizing refinements via liquid type inference was also introduced for an ML like language [Rondon et al. 2008] and latter extended to heap-manipulating programs. Based on earlier work on alias typing [Ahmed et al. 2007; Smith et al. 2000], Csolve [Rondon et al. 2010] extends C with liquid types to allow the verification of low-level programs using pointer arithmetic. Subsequent work extends Csolve to handle a restricted form of parallelism with shared state [Kawaguchi et al. 2012]. On a similar note, Alias Refinement Types (ART) [Bakst and Jhala 2016] builds on alias types to allow the verification of linked data structures. Like ATS, this line of work focuses on the manipulation of raw pointer using ad-hoc ways to control aliasing that have to be retrofitted into the language. In contrast, Flux builds on top of Rust references abstracting the spatial reasoning within Rust's type system.

Asynchronous liquid separation types Kloos et al. [2015] describes a type system that combines refinements with a concurrent separation logic to verify asynchronous Ocaml programs with mutable state. More recently, Sammler et al. [2021] proposed a type system that combines ownership and refinement types to provide automated verification for C programs. Their focus is on providing a foundational tool that produces proofs in Coq and it follows an approach similar to RustBelt by defining a semantic interpretation of the type system in Iris. Our extension to Rust with refinement types resembles RefinedC, but their model of ownership is different from Rust's references and requires the manual annotation of loops to track ownership.

Rust verification tools Several program logic based tools exist for the verification of heavyweight functional correctness properties of Rust programs. Prusti encodes programs into Viper [Müller et al. 2016]; RustHorn [Matsushita et al. 2021] generates constrained Horn clauses [Bjørner et al. 2015]; and Creusot [Denis et al. 2022] extracts programs into WhyML [Filliâtre and Paskevich 2013]. Ullrich [2016] defines an encoding of safe Rust into a functional program, which can be interactively verified in Lean [de Moura et al. 2015]. Similarly, Merigoux et al. [2021] define a

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.


<!-- page 22 -->

169:22 Nico Lehmann, Adam T. Geller, Niki Vazou, and Ranjit Jhala

translation into F∗, but they target a fragment of Rust without mutation, which they use to verify cryptographic algorithms. Ho and Protzenko [2022] extend the translation to support mutation via backward functions, which, like lenses, update the heap post-mutation. All these tools leverage Rust's ownership types to abstract the low level details of reasoning about aliasing and to provide a specification language in a program logic. As discussed in §5, using a program logic comes at the cost of complex user-specified universally quantified invariants. In contrast Flux aims to make lightweight verification automatic and ergonomic by restricting specifications so that that the type system itself becomes a syntax-directed decision procedure for universally quantified assertions, thereby enabling automatic (quantifier-free) invariant inference, and eliminating programmer overhead. Bounded verification of Rust programs has also been done via model checking [Balasubramanian et al. 2017; VanHattum et al. 2022] or symbolic execution [Lindner et al. 2018].

## 7 CONCLUSIONS & FUTURE WORK

We presented Flux, which shows how logical refinements can be married with Rust's ownership mechanisms to yield ergonomic type-based verification for imperative code. Crucially, our design lets Flux express complex invariants by composing type constructors with simple quantifier-free logical predicates, and dually, use syntax directed subtyping to decompose complex reasoning about those invariants into efficiently decidable (quantifier free) validity queries over the predicates. This marriage makes verification ergonomic by allowing us to use predictable Horn-clause based machinery to automatically synthesize complicated loop-invariant annotations.

Of course, all marriages involve some compromise. By design, Flux restricts the specifications to those that can be expressed by the combination of type constructors and quantifier-free refinements. Program logic based methods like Prusti are more liberal. Their recursive heap predicates and universally quantified assertions permit specifications about the exact values in containers, and hence, verification of correctness properties which are currently out of Flux's reach. In future, it would be interesting to see how to recoup such expressiveness, perhaps by incorporating techniques like reflection [Vazou et al. 2018], that has proven effective in the purely functional setting.

## 8 DATA AVAILABILITY STATEMENT

The source code of Flux is publicly available at https://github.com/flux-rs/flux. Additionally, a snapshot of the software used for the evaluation in §5, together with instructions on how to replicate the results can be found in the accompanying artifact [Lehmann et al. 2023a].

ACKNOWLEDGMENTS We thank the reviewers who evaluated the artifact for sharing their experience using Flux, our shepherd Peter-Michael Osera and the anonymous referees for their excellent suggestions for improving the paper, and Gilles Barthe who helped us flesh out the design of Flux in its early stages. This work was supported by the NSF grants CNS-2120642, CNS-2155235, CCF-1918573, CCF-1911213, the Horizon Europe ERC Starting Grant CRETE (GA: 101039196), the US Office of Naval Research HACKCRYPT (Ref. N00014-19-1-2292), and generous gifts from Microsoft Research.

REFERENCES

Amal Ahmed, Matthew Fluet, and Greg Morrisett. 2007. Lˆ 3: a linear language with locations. Fundamenta Informaticae 77,

4 (2007), 397-449. Andrew W Appel. 2007. Compiling with continuations. Cambridge university press. https://doi.org/10.1017/ CBO9780511609619 Vytautas Astrauskas, Aurel Bílý, Jonáš Fiala, Zachary Grannan, Christoph Matheja, Peter Müller, Federico Poli, and

Alexander J. Summers. 2022. The Prusti Project: Formal Verification for Rust. In NASA Formal Methods, Jyotirmoy V.

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.


<!-- page 23 -->

Flux: Liquid Types for Rust 169:23

Deshmukh, Klaus Havelund, and Ivan Perez (Eds.). Springer International Publishing, Cham, 88-108. https://link. springer.com/chapter/10.1007/978-3-031-06773-0_5 Vytautas Astrauskas, Peter Müller, Federico Poli, and Alexander J. Summers. 2019. Leveraging Rust Types for Modular

Specification and Verification. Proc. ACM Program. Lang. 3, OOPSLA, Article 147 (oct 2019), 30 pages. https://doi.org/10. 1145/3360573 Alexander Bakst and Ranjit Jhala. 2016. Predicate Abstraction for Linked Data Structures. In Verification, Model Checking, and

Abstract Interpretation, Barbara Jobstmann and K. Rustan M. Leino (Eds.). Springer Berlin Heidelberg, Berlin, Heidelberg, 65-84. https://doi.org/10.1007/978-3-662-49122-5_3 Abhiram Balasubramanian, Marek S. Baranowski, Anton Burtsev, Aurojit Panda, Zvonimir Rakamari, and Leonid Ryzhyk.

2017. System Programming in Rust: Beyond Safety. SIGOPS Oper. Syst. Rev. 51, 1, 94-99. https://doi.org/10.1145/3139645. 3139660 Nikolaj Bjørner, Arie Gurfinkel, Ken McMillan, and Andrey Rybalchenko. 2015. Horn clause solvers for program verification.

In Fields of Logic and Computation II. Springer, 24-51. https://doi.org/10.1007/978-3-319-23534-9_2 Dave Clarke, Johan Östlund, Ilya Sergey, and Tobias Wrigstad. 2013. Ownership Types: A Survey. In Aliasing in Object-

Oriented Programming. Types, Analysis and Verification, Dave Clarke, James Noble, and Tobias Wrigstad (Eds.). Lecture Notes in Computer Science, Vol. 7850. Springer, 15-58. https://doi.org/10.1007/978-3-642-36946-9_3 David G. Clarke, John M. Potter, and James Noble. 1998. Ownership Types for Flexible Alias Protection. In Proceedings of

the 13th ACM SIGPLAN Conference on Object-Oriented Programming, Systems, Languages, and Applications (Vancouver, British Columbia, Canada) (OOPSLA '98). Association for Computing Machinery, New York, NY, USA, 48-64. https: //doi.org/10.1145/286936.286947 R. L. Constable and S. F. Smith. 1987. Partial Objects In Constructive Type Theory. In LICS. Benjamin Cosman and Ranjit Jhala. 2017. Local Refinement Typing. Proc. ACM Program. Lang. 1, ICFP, Article 26 (aug 2017),

27 pages. https://doi.org/10.1145/3110270 Leonardo de Moura, Soonho Kong, Jeremy Avigad, Floris van Doorn, and Jakob von Raumer. 2015. The Lean Theorem

Prover (System Description). In Automated Deduction - CADE-25, Amy P. Felty and Aart Middeldorp (Eds.). Springer International Publishing, Cham, 378-388. https://doi.org/10.1007/978-3-319-21401-6_26 Xavier Denis, Jacques-Henri Jourdan, and Claude Marché. 2022. Creusot: A Foundry for the Deductive Verification of Rust

Programs. In Formal Methods and Software Engineering, Adrian Riesco and Min Zhang (Eds.). Springer International Publishing, Cham, 90-105. https://doi.org/10.1007/978-3-031-17244-1_6 Dimitrios J Economou, Neel Krishnaswami, and Jana Dunfield. 2022. Focusing on Liquid Refinement Typing. arXiv preprint

arXiv:2209.13000 (2022). Jean-Christophe Filliâtre and Andrei Paskevich. 2013. Why3 -- Where Programs Meet Provers. In Programming Languages

and Systems, Matthias Felleisen and Philippa Gardner (Eds.). Springer Berlin Heidelberg, Berlin, Heidelberg, 125-128. https://doi.org/10.1007/978-3-642-37036-6_8 Tim Freeman and Frank Pfenning. 1991. Refinement Types for ML. In Proceedings of the ACM SIGPLAN 1991 Conference on

Programming Language Design and Implementation (Toronto, Ontario, Canada) (PLDI '91). Association for Computing Machinery, New York, NY, USA, 268-277. https://doi.org/10.1145/113445.113468 Son Ho and Jonathan Protzenko. 2022. Aeneas: Rust Verification by Functional Translation. Proc. ACM Program. Lang. 6,

ICFP, Article 116 (aug 2022), 31 pages. https://doi.org/10.1145/3547647 Ranjit Jhala and Niki Vazou. 2021. Refinement Types: A Tutorial. Foundations and Trends® in Programming Languages 6,

3-4 (2021), 159-317. https://doi.org/10.1561/2500000032 E. Johnson, E. Laufer, Z. Zhao, D. Gohman, S. Narayan, S. Savage, D. Stefan, and F. Brown. 2023. WaVe: A Verifiably Secure

WebAssembly Sandboxing Runtime. In 2023 2023 IEEE Symposium on Security and Privacy (SP) (SP). IEEE Computer Society, Los Alamitos, CA, USA, 1986-2001. https://doi.org/10.1109/SP46215.2023.00114 Ralf Jung, Hoang-Hai Dang, Jeehoon Kang, and Derek Dreyer. 2019. Stacked Borrows: An Aliasing Model for Rust. Proc.

ACM Program. Lang. 4, POPL, Article 41 (dec 2019), 32 pages. https://doi.org/10.1145/3371109 Ralf Jung, Jacques-Henri Jourdan, Robbert Krebbers, and Derek Dreyer. 2017. RustBelt: Securing the Foundations of the

Rust Programming Language. Proc. ACM Program. Lang. 2, POPL, Article 66 (dec 2017), 34 pages. https://doi.org/10. 1145/3158154 Ralf Jung, Robbert Krebbers, Jacques-Henri Jourdan, Aleš Bizjak, Lars Birkedal, and Derek Dreyer. 2018. Iris from the ground

up: A modular foundation for higher-order concurrent separation logic. Journal of Functional Programming 28 (2018), e20. https://doi.org/10.1017/S0956796818000151 Ming Kawaguchi, Patrick Rondon, Alexander Bakst, and Ranjit Jhala. 2012. Deterministic Parallelism via Liquid Effects. In

Proceedings of the 33rd ACM SIGPLAN Conference on Programming Language Design and Implementation (Beijing, China) (PLDI '12). Association for Computing Machinery, New York, NY, USA, 45-54. https://doi.org/10.1145/2254064.2254071 Johannes Kloos, Rupak Majumdar, and Viktor Vafeiadis. 2015. Asynchronous Liquid Separation Types. In 29th European

Conference on Object-Oriented Programming, ECOOP 2015, July 5-10, 2015, Prague, Czech Republic (LIPIcs, Vol. 37), John Tang

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.


<!-- page 24 -->

169:24 Nico Lehmann, Adam T. Geller, Niki Vazou, and Ranjit Jhala

Boyland (Ed.). Schloss Dagstuhl - Leibniz-Zentrum für Informatik, 396-420. https://doi.org/10.4230/LIPIcs.ECOOP.2015. 396 Nico Lehmann, Adam T. Geller, Niki Vazou, and Ranjit Jhala. 2023a. Flux: Liquid Types for Rust - Artifact. https://doi.org/10.

5281/zenodo.7682468 Nico Lehmann, Adam T. Geller, Niki Vazou, and Ranjit Jhala. 2023b. Flux: Liquid Types for Rust - Technical Appendix.

https://github.com/flux-rs/pldi23-artifact K. Rustan M. Leino and Clément Pit-Claudel. 2016. Trigger Selection Strategies to Stabilize Program Verifiers. In Computer

Aided Verification - 28th International Conference, CAV 2016, Toronto, ON, Canada, July 17-23, 2016, Proceedings, Part I (Lecture Notes in Computer Science, Vol. 9779), Swarat Chaudhuri and Azadeh Farzan (Eds.). Springer, 361-381. https: //doi.org/10.1007/978-3-319-41528-4_20 Marcus Lindner, Jorge Aparicius, and Per Lindgren. 2018. No Panic! Verification of Rust Programs by Symbolic Execution.

In 2018 IEEE 16th International Conference on Industrial Informatics (INDIN). 108-114. https://doi.org/10.1109/INDIN. 2018.8471992 Niko Matsakis. 2018. An alias-based formulation of the borrow checker. https://smallcultfollowing.com/babysteps/blog/

2018/04/27/an-alias-based-formulation-of-the-borrow-checker/ Nicholas D Matsakis and Felix S Klock II. 2014. The rust language. In ACM SIGAda Ada Letters, Vol. 34. ACM, 103-104. Yusuke Matsushita, Takeshi Tsukada, and Naoki Kobayashi. 2021. RustHorn: CHC-Based Verification for Rust Programs.

ACM Trans. Program. Lang. Syst. 43, 4, Article 15 (oct 2021), 54 pages. https://doi.org/10.1145/3462205 Denis Merigoux, Franziskus Kiefer, and Karthikeyan Bhargavan. 2021. Hacspec: succinct, executable, verifiable specifications

for high-assurance cryptography embedded in Rust. Ph. D. Dissertation. Inria. Peter Müller, Malte Schwerhoff, and Alexander J. Summers. 2016. Viper: A Verification Infrastructure for Permission-Based

Reasoning. In Verification, Model Checking, and Abstract Interpretation, Barbara Jobstmann and K. Rustan M. Leino (Eds.). Springer Berlin Heidelberg, Berlin, Heidelberg, 41-62. https://doi.org/10.1007/978-3-662-49122-5_2 James Noble, Jan Vitek, and John Potter. 1998. Flexible Alias Protection. In ECOOP'98 - Object-Oriented Programming, 12th

European Conference, Brussels, Belgium, July 20-24, 1998, Proceedings (Lecture Notes in Computer Science, Vol. 1445), Eric Jul (Ed.). Springer, 158-185. https://doi.org/10.1007/BFb0054091 Peter W. O'Hearn. 2004. Resources, Concurrency and Local Reasoning. In CONCUR 2004 - Concurrency Theory, Philippa

Gardner and Nobuko Yoshida (Eds.). Springer Berlin Heidelberg, Berlin, Heidelberg, 49-67. https://doi.org/10.1007/9783-540-28644-8_4 John C. Reynolds. 2002. Separation Logic: A Logic for Shared Mutable Data Structures. In Proceedings of the 17th Annual

IEEE Symposium on Logic in Computer Science (LICS '02). IEEE Computer Society, USA, 55-74. Patrick Maxim Rondon, Ming Kawaguchi, and Ranjit Jhala. 2010. Low-Level Liquid Types. In Proceedings of the 37th Annual

ACM SIGPLAN-SIGACT Symposium on Principles of Programming Languages (Madrid, Spain) (POPL '10). Association for Computing Machinery, New York, NY, USA, 131-144. https://doi.org/10.1145/1706299.1706316 Patrick M. Rondon, Ming Kawaguci, and Ranjit Jhala. 2008. Liquid Types. In Proceedings of the 29th ACM SIGPLAN

Conference on Programming Language Design and Implementation (Tucson, AZ, USA) (PLDI '08). Association for Computing Machinery, New York, NY, USA, 159-169. https://doi.org/10.1145/1375581.1375602 J. Rushby, S. Owre, and N. Shankar. 1998. Subtypes for specifications: predicate subtyping in PVS. IEEE Transactions on

Software Engineering 24, 9 (1998), 709-720. https://doi.org/10.1109/32.713327 Michael Sammler, Rodolphe Lepigre, Robbert Krebbers, Kayvan Memarian, Derek Dreyer, and Deepak Garg. 2021. RefinedC:

Automating the Foundational Verification of C Code with Refined Ownership Types. In Proceedings of the 42nd ACM SIGPLAN International Conference on Programming Language Design and Implementation (Virtual, Canada) (PLDI 2021). Association for Computing Machinery, New York, NY, USA, 158-174. https://doi.org/10.1145/3453483.3454036 Frederick Smith, David Walker, and Greg Morrisett. 2000. Alias Types. In Programming Languages and Systems, Gert Smolka

(Ed.). Springer Berlin Heidelberg, Berlin, Heidelberg, 366-381. John Toman, Ren Siqi, Kohei Suenaga, Atsushi Igarashi, and Naoki Kobayashi. 2020. ConSORT: Context- and Flow-Sensitive

Ownership Refinement Types for Imperative Programs. In Programming Languages and Systems, Peter Müller (Ed.). Springer International Publishing, Cham, 684-714. https://doi.org/10.1007/978-3-030-44914-8_25 Sebastian Ullrich. 2016. Simple verification of rust programs via functional purification. Master's Thesis, Karlsruher Institut

für Technologie (KIT) (2016). Alexa VanHattum, Daniel Schwartz-Narbonne, Nathan Chong, and Adrian Sampson. 2022. Verifying Dynamic Trait

Objects in Rust. In Proceedings of the 44th International Conference on Software Engineering: Software Engineering in Practice (Pittsburgh, Pennsylvania) (ICSE-SEIP '22). Association for Computing Machinery, New York, NY, USA, 321-330. https://doi.org/10.1145/3510457.3513031 Niki Vazou, Anish Tondwalkar, Vikraman Choudhury, Ryan G. Scott, Ryan R. Newton, Philip Wadler, and Ranjit Jhala.

2018. Refinement reflection: complete verification with SMT. Proc. ACM Program. Lang. 2, POPL (2018), 53:1-53:31. https://doi.org/10.1145/3158141

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.


<!-- page 25 -->

Flux: Liquid Types for Rust 169:25

Aaron Weiss, Olek Gierczak, Daniel Patterson, and Amal Ahmed. 2019. Oxide: The essence of rust. arXiv preprint

arXiv:1903.00982 (2019). Hongwei Xi. 2004. Applied type system. In Types for Proofs and Programs: International Workshop, TYPES 2003, Torino, Italy,

April 30-May 4, 2003, Revised Selected Papers. Springer, 394-408. https://doi.org/10.1007/978-3-540-24849-1_25 Hongwei Xi and Frank Pfenning. 1999a. Dependent Types in Practical Programming. In Proceedings of the 26th ACM

SIGPLAN-SIGACT Symposium on Principles of Programming Languages (San Antonio, Texas, USA) (POPL '99). Association for Computing Machinery, New York, NY, USA, 214-227. https://doi.org/10.1145/292540.292560 Hongwei Xi and Frank Pfenning. 1999b. Dependent Types in Practical Programming. In Proceedings of the 26th ACM

SIGPLAN-SIGACT Symposium on Principles of Programming Languages (San Antonio, Texas, USA) (POPL '99). Association for Computing Machinery, New York, NY, USA, 214-227. https://doi.org/10.1145/292540.292560 Dengping Zhu and Hongwei Xi. 2005. Safe Programming with Pointers through Stateful Views. In Proceedings of the 7th

International Conference on Practical Aspects of Declarative Languages (Long Beach, CA) (PADL'05). Springer-Verlag, Berlin, Heidelberg, 83-97. https://doi.org/10.1007/978-3-540-30557-6_8

Received 2022-11-10; accepted 2023-03-31

Proc. ACM Program. Lang., Vol. 7, No. PLDI, Article 169. Publication date: June 2023.
