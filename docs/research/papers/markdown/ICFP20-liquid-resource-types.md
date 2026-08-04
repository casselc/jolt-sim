# Liquid Resource Types

> **Machine-generated Markdown conversion — this is a MODIFIED version.**
> Converted from `ICFP20-liquid-resource-types.pdf` with PyMuPDF. Layout, mathematics,
> figures and tables are lossy; **quote from the PDF, not from this file.**
> Page anchors below correspond to PDF pages.

- **Citation:** Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, Nadia Polikarpova. ICFP 2020. doi:10.1145/3408988
- **Licence:** CC-BY 4.0 (Crossref)
- **Source:** https://www.cs.cmu.edu/~janh/assets/pdf/WangKRPH20.pdf

---


<!-- page 1 -->

## Liquid Resource Types

TRISTAN KNOTH, University of California, San Diego, USA DI WANG, Carnegie Mellon University, USA ADAM REYNOLDS, University of California, San Diego, USA JAN HOFFMANN, Carnegie Mellon University, USA NADIA POLIKARPOVA, University of California, San Diego, USA

This article presents liquid resource types, a technique for automatically verifying the resource consumption of functional programs. Existing resource analysis techniques trade automation for flexibility - automated techniques are restricted to relatively constrained families of resource bounds, while more expressive proof techniques admitting value-dependent bounds rely on handwritten proofs. Liquid resource types combine the best of these approaches, using logical refinements to automatically prove precise bounds on a program's resource consumption. The type system augments refinement types with potential annotations to conduct an amortized resource analysis. Importantly, users can annotate data structure declarations to indicate how potential is allocated within the type, allowing the system to express bounds with polynomials and exponentials, as well as more precise expressions depending on program values. We prove the soundness of the type system, provide a library of flexible and reusable data structures for conducting resource analysis, and use our prototype implementation to automatically verify resource bounds that previously required a manual proof.

CCS Concepts: • Software and its engineering →Functional languages; • Theory of computation → Program analysis.

Additional Key Words and Phrases: Automated amortized resource analysis, Refinement types

ACM Reference Format: Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova. 2020. Liquid Resource Types. Proc. ACM Program. Lang. 4, ICFP, Article 106 (August 2020), 63 pages. https://doi.org/10.1145/3408988

## 1 INTRODUCTION

Open any algorithms textbook and one will read about a number of sorting algorithms, all functionally equivalent. Why then, are there so many algorithms that do the same thing? The answer is that there are subtle differences in their performance characteristics. Consider, for example, the choice between quicksort and insertion sort. In the worst case, both algorithms run in quadratic time. Insertion sort, however, only needs to move the values that are out of place, so it can perform much better on mostly-sorted data.

Authors' addresses: Tristan Knoth, University of California, San Diego, USA, tknoth@ucsd.edu; Di Wang, Carnegie Mellon University, USA, diw3@cs.cmu.edu; Adam Reynolds, University of California, San Diego, USA, acreynol@ucsd.edu; Jan Hoffmann, Carnegie Mellon University, USA, jhoffmann@cmu.edu; Nadia Polikarpova, University of California, San Diego, USA, npolikarpova@ucsd.edu.

Permission to make digital or hard copies of part or all of this work for personal or classroom use is granted without fee provided that copies are not made or distributed for profit or commercial advantage and that copies bear this notice and the full citation on the first page. Copyrights for third-party components of this work must be honored. For all other uses, contact the owner/author(s). © 2020 Copyright held by the owner/author(s).

2475-1421/2020/8-ART106 https://doi.org/10.1145/3408988

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 2 -->

106:2 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

insert = λx. λxs.

match xs with

Nil →Cons x xs Cons hd tl →if hd <x

then Cons hd (tick 1 (insert x tl)) else Cons x (Cons hd tl)

Fig. 1. Insertion sort

Resource analysis. Choosing between implementations of seemingly simple functions like these requires precise resource analysis. Thus, there has been a lot of existing work in both inferring and verifying bounds on a program's resource consumption. In general existing approaches must trade automation for flexibility and precision.

yields List Int1, a type of lists where every element has a single unit of potential.

More interestingly, the combination of refinements and potential annotations allows ReSyn to verify value-dependent resource bounds. To this end, ReSyn supports the use of conditional linear arithmetic (CLIA) terms as potential annotations, as opposed to just constants. For example, ReSyn can also check insert against the type x :a →xs : List aite(x >ν,1,0) →List a, which states that insert

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.

sort = λxs.

match xs with

Nil →Nil Cons hd tl →

insert hd (tick 1 (sort tl))

On one end of the spectrum, Resource-Aware ML (RaML) [Hoffmann and Hofmann 2010b] automatically infers polynomial bounds on recursive programs by allocating potential amongst data structures. RaML reduces least upper bound inference to finding a minimal solution to a system of linear constraints corresponding to the program's resource demands. On the other hand, RelCost [Radicek et al. 2018a] offers greater flexibility at the expense of automation. RelCost allows users to prove precise resource bounds that depend on program values, but requires hand-written proofs.

For example, consider insertion sort: Fig. 1 shows a recursive implementation of this sorting algorithm in a functional language. In this example we adopt a simple cost model where recursive calls incur unit cost, and all other operations do not require resources; we indicate this by wrapping recursive calls in a special operation tick, which consumes a given amount of resources. RaML can infer a tight quadratic bound on the cost of evaluating sort : 0.5(n2 +n), where n is the length of the input list. RelCost allows one to prove a more complex bound: insertion sort requires resources proportional to the number of out-of-order pairs in the input. However, the proof must be written by hand. Is it possible to develop a technique that admits both automation and expressiveness and can automatically verify these kinds of fine-grained bounds?

Liquid Types and Resources. Recent work on ReSyn [Knoth et al. 2019] takes a first step in this direction by extending a liquid type system with resource analysis. Liquid types [Rondon et al. 2008] support automatic verification of nontrivial functional properties with an SMT solver. ReSyn augments an existing liquid type system [Polikarpova et al. 2016] with a single construct: types can be annotated with a numeric quantity called potential. For example, a value of type Int1 carries a single unit of potential, which can be used to pay for an operation with unit cost. Combined with polymorphic datatypes, this mechanism can describe uniform assignment of potential to the elements of a data structure. For example, instantiating a polymorphic list type List a with a 7→Int1

The ReSyn type checker verifies that a program has enough potential to pay for all operations that may occur during evaluation. For example, ReSyn can check the implementation of insert in Fig. 1 against the (polymorphic) type x :a →xs : List a1 →List a to verify that the function makes one recursive call per element in the input xs. Here List a1 stands for the type of lists where each element has one more unit of potential than prescribed by type a.


<!-- page 3 -->

Liquid Resource Types 106:3

data ISList a where

data QList a where

ISNil ::ISList a ISCons ::x: a →xs: ISList aite(x >ν,1,0) →ISList a

QNil ::QList a QCons ::a →QList a1 →QList a

Fig. 2. Two list types defined with inductive potentials: QList carries quadratic potential; in ISList, elements in the tail only have potential when they are larger than the head.

only makes a recursive call for each element in xs smaller than x. The annotation on the type of the list elements conditionally assigns potential to a value in the list only when it is smaller than x1. ReSyn reduces this type checking problem to a system of second-order CLIA constraints, which can be solved relatively efficiently using existing program synthesis techniques [Alur et al. 2013].

Challenge: Analyzing super-linear bounds. A major limitation of the ReSyn type system is that it only supports linear bounds. In particular, a type of the form List ap distributes the potential p uniformly throughout the list, and hence cannot express resource consumption of a super-linear function like insertion sort, which traverses the end of the input list more often than the beginning (recall that insertion sort recursively sorts the tail of the list and traverses the newly sorted tail again to insert an element). To verify this function, we need a type that allots more potential to elements in the tail of a list than the head. In this paper, we propose two simple extensions to the ReSyn type system to support the verification of super-linear resource bounds, while still generating only second-order CLIA constraints to keep type checking efficiently decidable.

Super-linear Resource Analysis with Inductive Potentials. Our first insight is that we can describe non-uniform allocation of potential in a data structure by embedding potential annotations into datatype definitions. We dub this mechanism inductive potentials. For example, the datatype QList in Fig. 2 (left) represents lists where every element has one more unit of potential than the one before it (the total amount of potential in the list is thus quadratic in its length). We express this non-uniform distribution of potential with the type of QCons: the elements in the tail of the list are of type a1 instead of a, indicating that they must contain one more unit of potential than the head does. The datatype ISList in Fig. 2 (right) is similar, but only assigns extra potential to those elements of the tail that are smaller than the head. Using these custom datatypes we can specify a coarse-grained (with QList) and fine-grained (with ISList) resource bound for insertion sort. Importantly, all potential annotations are still expressed in CLIA, so we can verify super-linear resource bounds while reusing ReSyn's constraint-solving infrastructure.

Flexibility via Abstract Potentials. Inductive potentials, as descried so far, are somewhat restrictive. One must define a custom datatype for every resource bound. In the insertion sort example, we had to define QList to perform a coarse-grained analysis and ISList to perform a fine-grained analysis; moreover, both types have a fixed constant 1 embedded in their definition, so if the cost of tick inside insert were to increase, these types would no longer work. This is clearly unwieldy: instead, we would like to be able to write libraries of reusable data structures, each able to express a broad family of resource bounds.

To address this limitation, our second insight is to parameterize datatypes by numeric logic-level functions, which can then be used inside the datatype definition to allocate potential. We dub this second type system extension abstract potentials. With abstract potentials, the programmer can define a single datatype that represents a family of resource bounds, and then instantiate it with appropriate potential functions to verify different concrete bounds. For example, instead of defining

1Throughout the paper, the special variable ν refers to an arbitrary inhabitant of the annotated type.

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 4 -->

106:4 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

QList and ISList separately, we can define a more general type List a ⟨q :: a →a →Nat⟩, where the parameter q abstracts over the potential annotation in the constructor. We can then instantiate q with different logic-level functions to perform different analyses. Importantly, type checking still generates constraints in the same logic fragment as ReSyn. This design enables our type checker to automate resource analyses that would have previously required a handwritten proof.

Contributions. In summary, this paper the following technical contributions:

(1) Liquid resource types (LRT), a flexible type system for automatic resource analysis. With

inductive and abstract potentials, programmers can analyze a variety of resource bounds by specifying how potential is allocated within a data structure. (2) Semantics and a soundness proof for the type system, including user-defined inductive data

types. (3) A prototype implementation, LRTChecker, that automatically checks precise value-dependent

resource bounds with existing constraint solving technology. (4) A library of data types corresponding to families of resource bounds, such as lists admitting

polynomial or exponential bounds over their length, and trees admitting linear combinations of their size and height. (5) An evaluation on a set of challenging examples showing that LRTChecker automatically

performs resource analyses out of scope of prior approaches.

## 2 OVERVIEW

We begin with examples to better illustrate how liquid resource types enable the automatic verification of precise resource bounds. First, we show how ReSyn integrates resource analysis into a liquid type system. Second, we show how inductive potentials enable the analysis of super-linear bounds. Finally, we show how abstract potentials make this paradigm flexible and reusable.

### 2.1 Background: ReSyn

Liquid Types. In a refinement type system [Denney 1999; Swamy et al. 2016], types are annotated with logical predicates that constrain the range of their values. For instance, the type of natural numbers can be expressed as type Nat = {Int |ν≥0}, where the special variable ν, as before, denotes an inhabitant of the type. Liquid types [Rondon et al. 2008; Vazou et al. 2013] are a kind of refinement types that restrict logical refinements to only appear on scalar (i.e. non-function) types, and be expressed in decidable logics. Due to these restrictions, liquid types support fully automatic verification of nontrivial functional properties with the help of an SMT solver.

Potential Annotations. ReSyn [Knoth et al. 2019] extends liquid types with the ability to reason about the resource consumption of programs in addition to their functional properties. To this end, a type can also be annotated with a numeric logic expression called potential, as well as a logical refinement. For example, the type Nat1 ranges over natural numbers that carry a single unit of potential. Intuitively, potential can be used to "pay" for evaluating special tick terms, which are placed throughout the program to encode a cost model. For example, the context [x : Nat1] has a total of 1 unit of free potential, which is sufficient to type-check a term like tick 1 (). Because duplicating potential would lead to unsound resource analysis, ReSyn's type system is affine, which means that creating two copies of a context--for example, to type-check both sides of an application--requires distributing the available potential between them.

Simple potential annotations can be combined with other features of the type system, such as polymorphic datatypes, to specify more complex allocation of resources. For example, instantiating a polymorphic datatype List a with a 7→Nat1 yields the type List Nat1 of natural-number lists that carry one unit of potential per element. Here and throughout the paper, a missing potential

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 5 -->

Liquid Resource Types 106:5

1 [insert: x : a →xs : List aP →List a]

2 [insert: . . ., x: a, xs: List aP ]

3 [insert: . . ., x: a, xs: List aP ]

4 [insert: . . ., x: a, xs: List a]

5 [insert: . . ., x: a, xs: List a, hd: aP , tl: List aP ]

6 [insert: . . ., x: a, xs: List a, hd: ap1, tl: List aq1]

7 [insert: . . ., x: a, xs: List a, hd: ap2, tl: List aq2, hd <x]

8 [insert: . . ., x: a, xs: List a, hd: ap2−1, tl: List aq2, hd <x]

9 [insert: . . ., x: a, xs: List a, hd: ap2, tl: List aq2, ¬(hs <x)]

Note that only "top-level" potential in a type contributes to the free potential of the context: for example, the context [xs : List Nat1] has no free potential (which makes sense, since xs could be empty). The potential bundled inside an inductive datatype can be freed via pattern matching: for example, matching the xs variable above against Cons hd tl extends the context with new bindings hd :: Nat1 and tl :: List Nat1; this new context has a single unit of free potential attached to hd (which also makes sense, since we now know that xs had at least one element).

1 insert = λx. λxs.

2 match

3 xs with

## 4 Nil →Cons x Nil

## 5 Cons hd tl →

6 if hd <x

7 then Cons hd (tick 1

8 (insert x tl))

9 else Cons x (Cons hd tl)

Fig. 3. On the right, the implementation of insert alongside the contexts used for type checking. Each line of the program corresponds to a subexpression that generates resource constraints, with the typing context relevant for constraint generation alongside it to the left. The start of the match expression is split between two lines to separate the context used to type the entire match expression from the context used to type the scrutinee. P is used as a symbolic resource annotation, as we will check this program against different bounds by providing concrete valuations for P.

annotation defaults to zero, so the type above stands for (List Nat1)0. This default annotation hints at our more general notion of type substitution, where potential annotations are added together: instantiating a polymorphic datatype List am with a 7→Natn yields the type List Natm+n.

Using potential annotations and tick terms, ReSyn is able to specify upper bounds on resource consumption of recursive functions. Consider, for example, the function insert that inserts a value into a sorted list xs, as shown in Fig. 1 (left). We wish to check that insert traverses the list linearly: more precisely, that it only makes a single recursive call per list element. To this end, we wrap the recursive call in a tick with unit cost, and annotate insert with the following type signature, which allocates one unit of potential per element of the input list:

insert :: x : a →xs : List a1 →List a

Type checking. We now describe how ReSyn checks insert against this specification. At a high level, type checking reduces to generating a system of linear arithmetic constraints asserting that it is possible to partition the potential available in the context amongst all expressions that need to be evaluated. If this system of constraints is satisfiable, the given resource bound is sufficient. We generate three kinds of constraints: sharing constraints, which nondeterministically partition resources between subexpressions, subtyping constraints, which check that a given term has enough potential to be used in a given context, and well-formedness constraints, which assert that potential annotations are non-negative.

Fig. 3 illustrates type-checking of insert: its left-hand side shows the context in which various subexpressions are checked (for now you can ignore the path constraints, shown in red). The annotations in the figure are abstract; we will use the same figure to describe how we check both dependent and constant resource bounds. For this first example, we set P = 1 in the top-level type annotation of insert - we are checking that insert only makes one recursive call per element in xs.

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 6 -->

106:6 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

The body of insert starts with a pattern match, which requires distributing the resources in the context on line 2 between the match scrutinee and the branches. This context has no free potential, but it does have some bundled potential in xs: List a1; bundled potential also has to be shared between the two copies of the context, since it could later be freed by pattern matching. In this case, however, xs is not mentioned in either of the branches, so for simplicity we elide the sharing constraints and assign all its potential to line 3, leaving List a1 in the context of the match scrutinee and List a0 in the context of the branches. Matching the scrutinee type List a1 against the type of the Cons constructor introduces new bindings hd :: a1 and tl :: List a1 into the context: now we have 1 unit of free potential at our disposal, as the input list has at least one element.

When checking the conditional, we must again partition all available resources between the guard and either of the two branches. In particular, we partition the hd binding from line 5 into hd : ap1 and hd : ap2, generating a sharing constraint that reduces to 1 = p1 + p2. Similarly, we also partition the remaining potential in tl into tl : List aq1 and tl : List aq2, which produces a constraint 1 = q1 + q2 preventing us from reusing potential still contained in the list. ReSyn partitions resources non-deterministically and offloads the work of finding a concrete partitioning to the constraint solver. Neither the guard nor the else branch contains a tick expression, so they generate only trivial constraints. The then branch is more involved, as it does contain a tick with a unit cost. We must pay for this tick using the free potential p2 on hdleaving hd : ap2−1 in the context when checking the expression inside the tick on line 8. Like all bindings in the context, this binding generates a well-formedness constraint on its type, which reduces to the arithmetic constraint p2 −1 ≥0, thereby implicitly checking that p2 is sufficient to pay for the tick.

Finally, type-checking the application of insert x to tl produces a subtyping constraint between the actual and the formal argument types: Γ ⊢List aq2 <: List a1. This in turn reduces to an arithmetic constraint q2 ≥1, asserting that tl contains enough potential to execute the recursive call.

Now, consider the complete system of generated arithmetic constraints:

∃p1,p2,q1,q2 ∈N. 1 = p1 + p2 ∧1 = q1 + q2 ∧p2 −1 ≥0 ∧q2 ≥1

Though elided above, recall that all symbolic annotations are also required to be non-negative. This system of constraints is satisfiable by setting p2,q2 = 1 and the rest of the unknowns to 0, which ReSyn automatically infers using an SMT solver.

Value-dependent resource bounds. ReSyn also supports verification of dependent resource bounds. We can use a logic-level conditional to give the following more precise bound for insert:

insert :: x : a →xs : List aite(x >ν,1,0) →List a

The dependent annotation on xs indicates that only those list elements smaller than x carry potential, reflecting the fact that the implementation does not make any recursive calls once it has found the appropriate place to insert x.

Type checking proceeds similarly to the non-dependent case, except that we set P = ite(x > ν, 1, 0) and treat all other symbolic potential annotations as unknown logic-level terms over the program variables (including the special variable ν). As a result, type checking generates second-order CLIA constraints, which are universally quantified over the program variables, and may contain assumptions on these variables, derived from their logical refinements or from path constraints of branching expressions. For example, Fig. 3 shows in red the path constraints derived from the conditional. In particular, when checking the first branch, we can assume that hd <x holds and thus conclude that hd has potential 1 in this branch and is able to pay the cost of tick. When we check that an annotation is well-formed, we must also assume that the relevant variable's logical refinements hold. For example, to check that the annotation p2(x,ν) on hd is non-negative we must assert that ν = hd.

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 7 -->

Liquid Resource Types 106:7

∃p1,p2,q1,q2 ∈N × N →N.∀x, hd,ν.

ite(x > ν, 1, 0) = p1(x,ν) + p2(x,ν) Sharing hd (line 5)

∧ite(x > ν, 1, 0) = q1(x,ν) + q2(x,ν) Sharing tl (line 5)

∧(ν = hd ∧hd < x) =⇒p2(x,ν) −1 ≥0 Well-formedness of hd (line 8)

Limitations. While ReSyn's type system enables the analysis of the resource consumption of a wide variety of functions, and can automatically check value-dependent resource bounds, it still falls short of analyzing many useful programs. The system only expresses linear bounds, which are sufficient for many data structure traversals, but not sufficient for programs that compose several traversals. Thus, ReSyn cannot check the resource consumption of sort. We need a way to extend this technique to programs with more complex recursive structure. ReSyn also formalizes the technique only for lists, while we would like to be able to analyze programs that manipulate arbitrary algebraic data types.

### 2.2 Our Contribution: Liquid Resource Types

To address these limitations and enable verification of super-linear bounds, this work extends the ReSyn type system with two powerful mechanisms: inductive potentials allow the programmer to define inductively how potential is allocated within a datatype, while abstract potentials support parameterizing datatype definitions by potential functions. We dub the extended type system liquid resource types (LRT).

Õ

Õ

Õ

Φ(L) =

p +

i

i

j>i

We can now specify that insertion sort runs in quadratic time by giving it the type:

According to the formula above, this type assigns xs the total potential of 0.5(n2 + n), which is precisely the bound inferred by RaML, as we mentioned in the introduction. More interestingly, we can use value-dependent inductive potentials to specify a tighter bound for sort, by the replacing QList in the type signature above with ISList defined in Fig. 2 (right). In an ISList, the elements in the tail only carry the extra potential when their value is less than the head. Hence, the total potential stored in an ISList a1 is equal to the number of list elements plus the number of out-of-order pairs of list elements. Verifying sort against this bound implies, for example, that insertion sort behaves

More precisely, the full system of constraints (omitting irrelevant program variables) becomes:

∧hd < x =⇒q2(x,ν) ≥ite(x > ν, 1, 0) Subtyping of tl (from recursive call)

ReSyn satisfies these constraints by setting p2,q2 = λ(x,ν).ite(x > ν, 1, 0), and the rest of the unknowns to to λ(x,ν).0. Synthesis of CLIA expressions is a well-studied problem [Alur et al. 2013; Reynolds et al. 2019], and ReSyn uses counterexample-guided inductive synthesis (CEGIS) [SolarLezama et al. 2006] to solve the particular form of constraints that arise.

Inductive Potentials. Inductive potentials are expressed simply as potential annotations on constructors of a datatype. Fig. 2 (left) shows a simple example of a datatype, QList, with inductive potentials. Here the QCons constructor mandates that the tail of the list (a) carries at least one more unit of potential in each element than the head, and (b) is itself a QList. As a result, the total potential in a value L = [a1,a2, . . . ,an] of type QList T is quadratic in n and given by the following expression (where p is the potential of type T):

i = n(n + 2p −1)

1 = np + Õ

i

sort :: xs : QList a1 →List a

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 8 -->

106:8 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

data List t <q::t →t →Nat> where

Nil ::List t <q> Cons ::x: t →xs: List tq(x,ν) <q> →List t <q>

1 [insert: ∀b.x : b →xs : List b1 →List b, sort: ∀c.xs : List c1 ⟨Q⟩→List c]

2 [insert, sort: . . ., xs: List a1 ⟨Q⟩]

3 [insert, sort: . . ., xs: List a1 ⟨Q⟩]

4 [insert, sort: . . ., xs: List a]

5 [insert, sort: . . ., xs: List a, hd: a1, tl: List a1+Q(hd,ν) ⟨Q⟩]

6 [insert, sort: . . ., xs: List a, hd: ap1, tl: List aq1(hd,ν) ⟨q1⟩]

7 [insert, sort: . . ., xs: List a, hd: ap2, tl: List aq2(hd,ν) ⟨q2⟩]

8 [insert, sort: . . ., xs: List a, hd: ap2−1, tl: List aq2(hd,ν) ⟨q2⟩]

linearly on a fully sorted list (with no decreasing element pairs) and takes the full 0.5(n2 + n) steps on a list sorted in reverse order.

Õ

Φ(L) =

p(ai) +

i

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.

Fig. 4. A list datatype parameterized by a value-dependent, quadratic abstract potential.

1 sort = λxs.

2 match

3 xs with

## 4 Nil →Nil

## 5 Cons hd tl →

6 insert hd

7 (tick 1

8 (sort tl))

Fig. 5. Similar to Figure Fig. 3, the evolution of the typing context while checking different subexpressions of sort. Q is used as a symbolic resource annotation, as we will check this program against different bounds by providing concrete valuations for Q.

While inductive potentials are able to express non-linear bounds, on their own, they are difficult to use: the non-linear coefficient of a resource bound is built into the datatype definition, and hence any slight change in the analysis or the cost model--such as changing the cost of a recursive call from 1 to 2--requires defining a new datatype. We would like to be able to reuse the structure of these types without relying on the precise potential annotations embedded within.

Abstract potentials. To make inductive potentials reusable, we introduce the second new feature of LRT, which we dub abstract potentials. This feature is inspired by abstract refinement types [Vazou et al. 2013], which parameterize datatypes by a refinement predicate; similarly, LRT allows parameterizing a datatype a potential function. Consider the definition of the List datatype in Fig. 4: this datatype is parameterized by a numeric logic-level function q, which represents the additional potential contained in every element of every proper suffix of the list. This interpretation is revealed in the Cons constructor, where the value q(x,ν) is added to the linear potential annotation on the tail of the list. Note that since q is a function, this datatype subsumes both QSort and ISSort, as well as a broad range of value-dependent "quadratic" potential functions. More precisely, if a list element ν of type T carries p(ν) units of potential, then the total potential in a list L = [a1,a2, . . . ,an] of type List T is given by the following formula:

Õ

Õ

q(ai,aj)

i

j>i

Note that we can add higher-arity abstract potentials to extend the List datatype to support higherdegree polynomials. Similarly, we can add a unary abstract potential p(ν) to express the linear component of the list potential more explicitly (as opposed to relying on polymorphism in the type of the elements).

Type checking. With abstract potentials, we can use the same List datatype from Fig. 4 to verify both coarse- and fine-grained bounds for insertion sort. For the coarse-grained case, we can give


<!-- page 9 -->

Liquid Resource Types 106:9

this function the following type signature:

sort :: xs : List a1 ⟨λ(_, _).1⟩→List a

As before, omitted potential annotations are zero by default, so the return type List a is short for (List a0 ⟨λ(_, _).0⟩)0 The type checking process is illustrated in Fig. 5, where we set Q = λ(_, _).1. The initial context contains bindings for both the helper function insert and the function sort itself, which can be used to make a recursive call. More precisely, the binding for sort is added to the context as a result of type-checking the implicit fixpoint construct that wraps the lambda abstraction. Importantly for this example, LRT supports polymorphic recursion: the type c of list elements in the recursive call can be different from the type a of list elements in the body.

The top-level term in the body of sort is a pattern-match, so, as before, we have to split the context between the scrutinee and the branches. Since neither of the branches mentions xs, for simplicity we omit the sharing constraints and leave all of its potential with line 3, thus inferring the type List a1 ⟨1⟩for the scrutinee. Matching this type against the return type of the Cons constructor in Fig. 4, yields the substitution t 7→a1,q 7→1, adding the following two new bindings to the context of the Cons branch: hd : a1 and tl : List a2 ⟨λ(_, _).1⟩. Importantly, the tail list tl ends up with more linear potential than the original list xs, which is precisely the purpose of the inductive potential annotations in Fig. 4, and is necessary to afford both the recursive call and the call to insert.

Proceeding with type-checking the Cons branch, note that there are three terms that consume resources: the application of insert hs, the tick expression, and the recursive call. We can use the free unit of potential attached to hd to pay for tick. As for tl, recall that it has twice the potential that the recursive call to sort consumes, and we would like to "save up" this extra potential to pay for the application of insert hs to the result of the recursive call. This is where polymorphic recursion comes in: the type checker is free to instantiate c in the type of the recursive call with as, essentially giving every list element some amount of extra potential s which is simply "piped through" the call; LRT leaves the exact value of s for the solver to find.

All together, type checking leaves us the following system of arithmetic constraints:

∃p1,p2,q1,q2,s ∈N.p1 + p2 = 1 ∧p2 −1 ≥0

∧q1 + q2 = 2 ∧q2 ≥s + 1 ∧s ≥1

which is satisfiable with p2,q2,s = 1 and the rest of unknowns set to 0. Note that while the annotations in Fig. 5 involve applications of abstract potentials, all potential functions involved in the coarse-grained version of the example are constants, so we can treat these as simple first-order numerical constraints.

Value-dependent resource bounds. Instantiating the abstract potentials with non-constant functions allows us to use the exact same List datatype to verify a fine-grained bound for insertion sort. To this end, we give it the type signature:

sort :: xs : List a1 ⟨λ(x1,x2). ite(x1 > x2, 1, 0)⟩→List a

Type checking still proceeds as illustrated in Fig. 5, except we setQ = λ(x1,x2). ite(x1 > x2, 1, 0). One key difference is that matching the type of the scrutinee xs against the return type of Cons requires applying the abstract potential function to yield tl : List a1+ite(x>ν,1,0) ⟨λ(x1,x2). ite(x1 > x2, 1, 0)⟩, in the context. The generated arithmetic constraints are similar to the coarse-grained case, but now symbolic potentials can be functions, so the constraints are second-order and must quantify over

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 10 -->

106:10 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

the program variables hd,ν and parameters x1,x2 of abstract potentials:

∃p1,p2,q1,q2,s ∈N × N →N. ∀hd,ν,x1,x2 ∈N.

p1(hd,ν) + p2(hd,ν) = 1 Sharing hd (line 5)

∧p2(hd,ν) −1 ≥0 Well-formedness of hd (line 8)

∧q1(hd,ν) + q2(hd,ν) = 1 + ite(hd > ν, 1, 0) Sharing tl (line 5)

∧q2(hd,ν) ≥s(hd,ν) + 1 Subtyping from the call to sort

∧s(hd,ν) ≥ite(hd > ν, 1, 0) Subtyping from the call to insert

The solver can validate these constraints by setting p2, λ(x1,x2).1, q2,s = λ(x1,x2).ite(x1 > x2, 1, 0), and the rest of the unknowns to λ(x1,x2).0. Importantly, even though inductive and abstract potentials significantly increase the expressiveness of the type system, the generated constraints still belong to the same logic fragment (second-order CLIA), as constraints generated by ReSyn, and hence are efficiently decidable. This is a consequence of the core design principle that differentiates LRT from other fine-grained resource analysis techniques [Handley et al. 2020; Radicek et al. 2018b; Wang et al. 2017]: to encode complex resource consumption, rather than increasing the complexity of the resource annotations, we embed simple annotations into complex types.

Although in this section we focused solely on the resource consumption of insertion sort, LRT is also able to specify and verify its functional properties--that the output list is sorted and contains the same number and/or set of elements as the input list. To this end, LRT relies on existing liquid type checking techniques [Polikarpova et al. 2016; Vazou et al. 2013]. Additionally, while this section only shows the use of inductive and abstract potentials for expressing quadratic potentials on lists, Sec. 4 further demonstrates the flexibility of this specification style. In particular, we show how to use abstract potentials to analyze exponential-time algorithms, as well as reason about the resource consumption of tree-manipulating programs in terms of their height and size.

## 3 TECHNICAL DETAILS

In this section, we formulate a substantial subset of our type system as a core calculus and prove type soundness. This subset features natural numbers and Booleans that are refined by their values, as well as user-defined inductive datatypes that can be refined by user-defined measures. The gap from the core calculus to our full type system involves abstract refinements and polymorphic datatypes. The restriction to this subset in the technical development is only for brevity and proofs carry over to all the features of our tool.

### 3.1 Setting the Stage: A Resource-Aware Core Language

Syntax. Fig. 6 presents the grammar of terms in the core calculus via abstract binding trees [Harper 2016]. We extend the core language of Re2 [Knoth et al. 2019] with natural numbers, null tuples, ordered pairs, and replace lists with general inductive data structures. Expressions are in a-normalform [Sabry and Felleisen 1992], which means that syntactic forms for non-tail positions allow only atoms ˆa ∈Atom, which are irreducible terms, e.g., variables and values, without loss of expressivity. The restriction simplifies typing rules in our system, as we will explain in Sec. 3.4. We further identify a subset SimpAtom of Atom that contains interpretable atoms in the refinement logic. Intuitively, the type of an interpretable atom a ∈SimpAtom admits a well-defined interpretation that maps the value of a to its logical refinements, e.g., lists can be refined by their lengths. A value v ∈Val is an atom without reference to any program variable. An inductive data structure C(v0, ⟨v1, · · · ,vm⟩) is represented by the constructor name C, the stored data v0 in this constructor,

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 11 -->

Liquid Resource Types 106:11

a ∈SimpAtom F x | n | true | false | triv | pair(a1,a2) | C(a0, ⟨a1, · · · ,am⟩) ˆa ∈Atom F a | λ(x.e0) | fix(f .x.e0)

⟨e,q⟩7→⟨e′,q′⟩

(E-Cond-True)

(E-Cond-False)

⟨if(true, e1, e2), q⟩7→⟨e1, q⟩

⟨if(false, e1, e2), q⟩7→⟨e2, q⟩

(E-MatP-Val)

(E-Tick)

⟨tick(c, e0), q⟩7→⟨e0, q −c ⟩

(E-MatD-Val)

v0 ∈Val v1 ∈Val · · · vmj ∈Val

⟨matd(Cj(v0, ⟨v1, · · · , vmj ⟩), −−−−−−−−−−−−−−−−−−−−−−−−−→ Cj(x0, ⟨x1, · · · , xmj ⟩).ej), q⟩7→⟨[v0, v1, · · · , vmj /x0, x1, · · · , xmj ]ej, q⟩

(E-App-Abs)

v2 ∈Val

⟨app(λ(x .e0), v2), q⟩7→⟨[v2/x]e0, q⟩

Fig. 7. Selected rules of the small-step operational cost semantics

and a sequence of child nodes ⟨v1, · · · ,vm⟩. Note that the core language has two kinds of match expressions: matp for pairs and matd for inductive data structures.

without running out of resources, and q′ resources are left. Fig. 7 shows some of the reduction rules of the small-step cost semantics. Note that all the judgments ⟨e,q⟩7→⟨e′,q′⟩implicitly constrain that q,q′ ≥0, so in the rule (E-Tick) for resource consumption, we do not need to distinguish whether the cost c is nonnegative or not.

e ∈Exp F a | if(a0,e1,e2) | matp(a0,x1.x2.e1) | matd(a0, −−−−−−−−−−−−−−−−−−−−−→ Cj(x0, ⟨x1, · · · ,xmj ⟩).ej) | app(ˆa1, ˆa2) | let(e1,x.e2) | impossible | tick(c,e0) v ∈Val F n | true | false | triv | pair(v1,v2) | C(v0, ⟨v1, · · · ,vm⟩) | λ(x.e0) | fix(f .x.e0)

Fig. 6. Syntax of the core calculus

(E-Let-Val)

v1 ∈Val

⟨let(v1, x .e2), q⟩7→⟨[v1/x]e2, q⟩

v1 ∈Val v2 ∈Val

⟨matp(pair(v1, v2), x1.x2.e1), q⟩7→⟨[v1, v2/x1, x2]e1, q⟩

(E-App-Fix)

v2 ∈Val

⟨app(fix(f .x .e0), v2), q⟩7→⟨[fix(f .x .e0), v2/f , x]e0, q⟩

The syntactic form impossible is used as a placeholder for unreachable code, e.g., the thenbranch of a conditional expression whose predicate is always false. The syntactic form tick(c,e0) is introduced to define the cost model, and it is intended to cost c ∈Z units of resource and then reduce to e0. A negative c means that −c units of resource will become available. The tick expressions support flexible user-defined resource metrics. For example, the programmers can wrap every recursive call in tick(1, ·) to count those function calls; alternatively, they may wrap every data constructor in tick(c, ·) to keep track of memory consumption, where c is the amount of memory allocated by the constructor.

Semantics. The resource consumption of a program is determined by a small-step operational cost semantics. The semantics is a standard structural semantics augmented with a resource parameter, which indicates the amount of available resources. The single-step reduction judgments have the form ⟨e,q⟩7→⟨e′,q′⟩, where e and e′ are expressions, and q,q′ ∈Z+

0 are nonnegative integers. The intuitive meaning of such a judgment is that with q units of available resources, e reduces to e′

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 12 -->

106:12 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

Refinement ψ,ϕ F ν | x | n | ⋆| ⊤| ¬ψ | ψ1 ∧ψ2 | ϕ1 ≤ϕ2 | ϕ1 + ϕ2 | ψ1 = ψ2 | ∀a : ∆.ψ | a | λa : ∆.ψ | ψ1 ψ2 | (ψ1,ψ2) | ψ.1 | ψ.2 Sort

∆ F B | N | U | δα | ∆1 × ∆2 | ∆1 ⇒∆2 Base Type Resource-Annotated Type

◁,π (−−−−−−−→ C : (T,m)) | m · α T F Rϕ

B F nat | bool | unit | B1 × B2 | indθ

Refinement Type Type Schema R F {B | ψ } | m · (x :Tx →T) S F T | ∀α.S

Fig. 8. Syntax of the core type system

The multi-step reduction relation 7→∗is defined as the reflexive transitive closure of 7→. Multi-step reduction can be used to reason about high-water mark resource usage of a reduction from e to e′, by finding the minimal q such that ⟨e,q⟩7→∗⟨e′,q′⟩for some q′. For monotone resources such as time, the high-water mark cost coincides with the net cost, i.e., the sum of costs specified by tick expressions in the reduction. In general, net costs are invariant, i.e., p −p′ = q −q′ if ⟨e,p⟩7→m ⟨e′,p′⟩and ⟨e,q⟩7→m ⟨e′,q′⟩, where 7→m is the m-element composition of 7→.

### 3.2 Types and Refinements

Refinements. We follow the approach of liquid types [Knoth et al. 2019; Polikarpova et al. 2016; Rondon et al. 2012] and develop a refinement language that is distinct from the term language. Fig. 8 formulates the syntax of the core type system. The refinement language is essentially a simply-typed lambda calculus augmented with logical connectives and linear arithmetic. As terms are classified by types, refinements ψ,ϕ are classified by sorts ∆. The core type system's sorts include Booleans B, natural numbers N, nullary U and binary products ∆1 × ∆2, arrows ∆1 ⇒∆2, and uninterpreted symbols δα parametrized by type variables α. In our system, logical constraints ψ have sort B, potential annotations ϕ have sort N, and refinement-level functions have arrow

sorts. Refinements can reference program variables. Our system interprets a program variable of Boolean, natural-number, or product type as its value, type variable α as an uninterpreted symbol of sort δα, and inductive datatype as its measurement, which is computed by a total function ID : (values of datatype D) →(refinements of sort ∆D). The function ID is derived by user-defined measures for datatypes, which we omit from the formal presentation; Although measures play an important role in specifying functional properties (e.g., in [Polikarpova et al. 2016]), they are orthogonal to resource analysis. We include the full development with measures in Appendix B

Formally, we define the following interpretation I(·) to reflect interpretable atoms a ∈SimpAtom as their logical refinements:

I(x) = x

I(n) = n I(triv) = ⋆

I(true) = ⊤ I(false) = ⊥

I(pair(a1,a2)) = (I(a1), I(a2)) I(C(a0, ⟨a1, · · · ,am⟩)) = ID(C(a0, ⟨a1, · · · ,am⟩))

Example 3.1 (Interpretations of datatypes). Consider a natural-number list type NatList with constructors Nil and Cons. In the core language, an empty list is encoded as Nil(triv, ⟨⟩) and a

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 13 -->

Liquid Resource Types 106:13

INatList(Nil(triv, ⟨⟩))

In the rest of this section, we will assume that the type NatList admits a length interpretation.

We will use the abbreviations ⊥, ∨, =⇒, ≥, <, >, ite with obvious semantics; e.g., ψ1 ∨ψ2

def= (ψ0 =⇒ψ1) ∧(¬ψ0 =⇒ψ2). We will also abbreviate the m-element sum ψ +ψ + · · · +ψ as m ×ψ. We will use finite-product sorts ∆1 × ∆2 × · · · × ∆m, or Îm

¬(¬ψ1 ∧¬ψ2) and ite(ψ0,ψ1,ψ2)

i=1 ∆i for short, with an obvious encoding with nullary and binary products. We will also write ψ.i as the i-th projection from a refinement of a finite-product sort.

◁,π (−−−−−−−→ C : (T,m)) consists of a sequence of constructors, each of which has a nameC, a content typeT (which must be a scalar type), and a finite numberm ∈Z+

type variables. An inductive datatype indθ

child nodes. In terms of recursive types, (−−−−−−−→ C : (T,m)) compactly represents rec(X.−−−−−−−−−→ C :T × Xm), where Xm is them-element product type X ×X ×· · ·×X, e.g., the type NatList in Example 3.1 can be seen as an abbreviation of ind(Nil : (unit, 0), Cons : (nat, 1)). We will explain the resource-related parameters θ,◁, and π later in Sec. 3.3. Type variables α are annotated with a multiplicity m ∈Z+

Refinement types R are subset types and dependent arrow types. Inhabitants of a subset type {B | ψ } are values of type B that satisfy the refinement ψ. The refinement ψ is a logical formula over program variables and a special value variable ν, which is distinct from program variables and represents the inhabitant itself. For example, {bool | ¬ν} is a type of false, {nat | ν > 0} is a type of positive integers, and {NatList | ν = 1} stands for singleton lists of natural numbers. A dependent arrow type x :Tx →T is a function type whose return type may reference its formal argument x. Similar to type variables, these arrow types are also annotated with a multiplicity m ∈Z+

We will abbreviate 1 · α as α, {B | ⊤} as B, ∞· (x :Tx →T) as x :Tx →T, and R0 as R.

singleton list containing a zero is represented as Cons(0, ⟨Nil(triv, ⟨⟩)⟩). Below defines an interpretation INatList : (values of NatList) →(refinements of sort N) that computes the length of a list:

def= 0, INatList(Cons(vh, ⟨vt⟩))

def= INatList(vt) + 1.

def=

Types. We adapt the methodology of Re2 [Knoth et al. 2019] and classify types into four categories. Base types B are natural numbers, Booleans, nullary and binary products, inductive datatypes, and

0 of

0 ∪{∞}, which specifies an upper bound on the number of references for a program variable of such a type. For example, ind(Nil : (unit, 0), Cons : (2 · α, 1)) denotes a universal list, each of whose elements can be used at most twice.

0 ∪{∞} bounding from above the number of times a function of such a type can be applied. Resource-annotated types Rϕ are refinement types R augmented with potential annotations ϕ. The resource annotations are used to carry out the potential method of amortized analysis [Tarjan 1985]; intuitively, Rϕ assigns ϕ units of potential to values of the refinement type R. The potential annotation ϕ can also reference the value variable ν. For example, NatList2×ν describes naturalnumber lists ℓwith 2 · INatList(ℓ) = 2 · |ℓ| units of potential where |ℓ| is the length of ℓ. As we will show in Sec. 3.3, the same potential can also be expressed by assigning 2 units of potential to each element in the list.

Type schemas represent possibly polymorphic types, where the type quantifier ∀is only allowed to appear outermost in a type. Similar to Re2 [Knoth et al. 2019], we only permit polymorphic types to be instantiated with scalar types, which are resource-annotated base types (possibly with subset constraints). Intuitively, the restriction derives from the fact that our refinement-level logic is first-order, which renders our type system decidable.

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 14 -->

106:14 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

### 3.3 Potentials of Inductive Data Structures

0 )k and denote the annotated type by NatList®q. The annotation is intended to assign q1 units of potential to every element of the list, q2 units of potential to every element of every suffix of the list (i.e., to every ordered pair of elements), q3 units of potential to the elements of the suffixes of the suffixes (i.e., to every ordered triple of elements), etc. Let ℓbe a list of type NatList and Φ(ℓ: NatList®q) be its potential with respect to the annotated type. Then the potential function Φ(·) can be expressed as a linear combination of binomial coefficients, where |ℓ| is the length of ℓ:

k Õ

Φ(ℓ: NatList®q) =

i=1

Proposition 3.2. Define the potential function Φ(·) for type NatList®q as follows:

Φ(Nil(triv, ⟨⟩) : NatList®q)

def= 0, Φ(Cons(vh, ⟨vt⟩) : NatList®q)

where a potential shift operator ◁is defined as ◁(®q)

Based on the observation presented above, prior work [Hoffmann et al. 2011a; Hoffmann and Hofmann 2010b] builds an automatic resource analysis that infers polynomial resource bounds via efficient linear programming (LP). In this work, our main goal is not to develop an automatic inference algorithm, but rather to extend the expressivity of the potential annotations.

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.

Resource-annotated types Rϕ provide a mechanism to specify potential functions of inductive data structures in terms of their interpretations. However, this mechanism is not so expressive because it can only describe potential functions that are linear with respect to the interpretations of data structures, since our refinement logic only has linear arithmetic. One way to support non-linear potentials is to extend the refinement logic with non-linear arithmetic, which would come at the expense of decidability of the type system. In contrast, our type system adapts the idea of univariate polynomial potentials [Hoffmann and Hofmann 2010b] to a refinement-type setting. This combination allows us to not only reason about polynomial resource bounds with linear arithmetic in the refinement logic, but also derive fine-grained resource bounds that go beyond the scope of prior work on typed-based amortized resource analysis [Hoffmann et al. 2011a; Hoffmann and Hofmann 2010b; Knoth et al. 2019].

Simple numeric annotations. We start by adding numeric annotations to datatypes, following the approach of univariate polynomial potentials [Hoffmann and Hofmann 2010b]. Recall the type NatList introduced in Example 3.1. We now annotate it with a vector ®q = (q1, · · · ,qk) ∈(Z+

k Õ

|ℓ|

Õ

qi ·

1≤j1<···<ji ≤|ℓ| qi =

. (1)

i

i=1

For example, NatList(2) assigns 2 units of potential to each list element, so it describes lists ℓwith 2 · |ℓ| units of potential. As shown by the proposition below, one benefit of the binomial representation in (1) is that the potential function Φ(·) can be defined inductively on the data structure, and be expressed using only linear arithmetic.

def= q1 + Φ(vt : NatList◁(®q)),

def= (q1 + q2,q2 + q3, · · · ,qk−1 + qk,qk). Then (1) gives a closed-form solution to the inductive definition above.

Dependent annotations. Our first step is to generalize numeric potential annotations to dependent ones. The idea is to express the potential annotations in the refinement language of our type system. For example, we can annotate the type NatList with a vector θ = (θ1, · · · ,θk), where θi is a refinement-level abstraction of sort Ni ⇒N, for every i = 1, · · · ,k. Intuitively, θi denotes the amount of potential assigned to ordered i-tuple of elements in a list, depending on the actual values


<!-- page 15 -->

Liquid Resource Types 106:15

of the elements, i.e., let ℓ= [v1, · · · ,v|ℓ|] be a list of natural numbers, then the potential function Φ(·) with respect to the dependently annotated type NatListθ can be expressed as

k Õ

Φ(ℓ: NatListθ) =

i=1

Example 3.3 (Dependent potential annotations). Suppose we want to assign the number of ordered pairs (a,b) satisfying a > b in a list ℓof type NatListθ as the potential of ℓ. Then the desired potential function is Φ(ℓ: NatListθ) = Í

1≤j1<j2 ≤|ℓ| ite(vj1 > vj2, 1, 0). Compared with (2), a feasible θ = (θ1,θ2) can be defined as follows:

def= λx : N.0, θ2

θ1

Later we will show the dependent annotation given here can be used to derive a fine-grained resource bound for insertion sort at the end of Sec. 3.4.

Proposition 3.4. Define the potential function Φ(·) for type NatListθ as follows:

Φ(Nil(triv, ⟨⟩) : NatListθ)

def= 0, Φ(Cons(vh, ⟨vt⟩) : NatListθ)

where a dependent potential shift operator ◁is defined in the refinement-level language as

def= λy : N.λ(θ1 : N ⇒N, · · · ,θk : Nk ⇒N).(θ ′

◁

def= λx : N.(θ1(x) + θ2(y,x)), θ ′

where θ ′

θk(y,x)), and θ ′

k

Generic annotations. In general, the potential annotation θ does not need to have the form of vectors of refinement-level functions; it can be an arbitrary well-sorted refinement, as long as we know how to extract potentials from it (e.g., a projection from θ = (θ1, · · · ,θk) to θ1), and how to shift potential annotations to get annotations for child nodes (e.g., Proposition 3.4). This form of generic annotations formulates the notion of abstract potentials (introduced in Sec. 2.2), which is one major contribution of this paper.

Φ(Nil(triv, ⟨⟩) : NatListθ)

def= 0,

Φ(Cons(vh, ⟨vt⟩) : NatListθ)

◁,π (−−−−−−−→ C : (T,m)), where C's are constructor names, T's are content types of data stored at constructors, and m's are numbers of child nodes of constructors. Let the potential annotation θ be sorted ∆θ , and values of content type Tj be sorted as ∆Tj for each constructor Cj : (Tj,mj). Then the extraction operator π is supposed to

Recall that in our type system, an inductive datatype is represented as indθ

Õ

1≤j1<···<ji ≤|ℓ| θi(vj1, · · · ,vji ). (2)

def= λ(x1 : N,x2 : N).ite(x1 > x2, 1, 0).

Although dependent annotations seem to complicate the representation of potential functions, they do retain the benefit of numeric annotations. The key observation is that we can still express the potential shift operator ◁in our refinement language, which only permits linear arithmetic. Below presents a generalization of Proposition 3.2.

def= θ1(vh) + Φ(vt : NatList◁(vh)(θ)),

1, · · · ,θ ′ k),

def= λx : N2.(θ2(x) + θ3(y,x)), ..., θ ′

def= λx : Nk−1.(θk−1(x) +

k−1

def= θk. Then (2) gives a closed-form solution to the inductive definition above.

In our type system, we parametrize inductive datatypes with not only a potential annotation θ, but also a shift operator ◁and an extraction operator π. For natural-number lists of type NatListθ, the potential function Φ(·) is defined inductively in terms of ◁and π as follows:

def= π(vh)(θ) + Φ(vt : NatList◁(vh)(θ)).

be a tuple, the j-th component of which is a refinement-level function with sort ∆Tj ⇒∆θ ⇒N, i.e., extracts potential for the j-th constructor from the annotation θ. Similarly, the shift operator ◁ is also a tuple whose j-th component is a refinement-level function with sort ∆Tj ⇒∆θ ⇒∆mj

θ , i.e.,

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 16 -->

106:16 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

◁,π (−−−−−−−→ C : (T,m)))

Φ(Cj(v0, ⟨v1, · · · ,vmj ⟩) : indθ

NatList(θ1,θ2) def= ind(θ1,θ2)

where ◁= (◁Nil, ◁Cons) and π = (πNil, πCons) are defined as follows:

def= λ_ : U.λ(θ1 : N ⇒N,θ2 : N × N ⇒N).0,

πNil

def= λy : N.λ(θ1 : N ⇒N,θ2 : N × N ⇒N).θ1(y),

πCons

◁Nil

def= λ_ : U.λ(θ1 : N ⇒N,θ2 ; N × N ⇒N).⋆,

◁Cons

Different instantiations of θ1,θ2 lead to different potential functions. Example 3.3 presents an instantiation to count the out-of-order pairs in a natural-number list. Meanwhile, one can implement

def= λx : N × N.q2 as constant functions.

the simple numeric annotations (q1,q2) by setting θ1

### 3.4 Typing Rules

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.

shifts potential annotations for the child nodes of the j-th constructor. With the two operators ◁, π and the potential annotation θ, we can now define the potential function Φ(·) for general inductive datatypes as an inductive function:

def= Φ(v0 : Tj)

+ π.j(I(v0))(θ)

(3)

mj Õ

◁,π (−−−−−−−→ C : (T,m))).

Φ(vi : ind◁.j(I(v0))(θ).i

+

i=1

Note that (i) the definition above includes the potential of the value v0 stored at the constructor with respect to its typeTj, because the elements in the data structure may also carry potentials, and (ii) we use the interpretation I(·) defined in Sec. 3.2 to interpret values as their logical refinements.

Example 3.5 (Generic potential annotations). Recall the dependently annotated list type NatList(θ1,θ2) in Example 3.3. We can now formalize it in the core type system. Let

◁,π (Nil : (unit, 0), Cons : (nat, 1)),

def= λy : N.λ(θ1 : N ⇒N,θ2 : N × N ⇒N).(λx : N.θ1(x) + θ2(y,x),θ2).

def= λx : N.q1 and θ2

In this section, we formulate our type system as a set of derivation rules. The typing context Γ is a sequence of bindings for program variables x, bindings for refinement variables a, type variables α, path constraints ψ, and free potentials ϕ:

Γ F · | Γ,x : S | Γ,a : ∆| Γ, α | Γ,ψ | Γ,ϕ.

Our type system consists of five kinds of judgments: sorting, well-formedness, subtyping, sharing, and typing. We omit sorting and well-formedness rules and include them in Appendix B The sorting judgment Γ ⊢ψ ∈∆states that a term ψ has a sort ∆under the context Γ in the refinement language. A type S is said to be well-defined under a context Γ, denoted by Γ ⊢S type, if every referenced variable in S is in the proper scope.

Typing with refinements. Fig. 9 presents the typing rules of the core type system. The typing judgment Γ ⊢e :: S states that the expression e has type S under context Γ. Its intuitive meaning is that if all path constraints in Γ are satisfied, and there is at least the amount resources as indicated by the potential in Γ then this suffices to evaluate e to a value v that satisfies logical constraints indicated by S, and after the evaluation there are at least as many resources available as indicated by the potential in S. The rules can be organized into syntax-directed and structural rules.


<!-- page 17 -->

Liquid Resource Types 106:17

Structural rules (S-*) can be applied to every expression; in the implementation, we apply these rules strategically to avoid redundant proof search.

The auxiliary atomic-typing judgment Γ ⊢a : B assigns base types to interpretable atoms a ∈SimpAtom. Atomic typing is useful in the rule (T-SimpAtom), which uses the interpretation I(·) to derive a most precise refinement type for interpretable atoms, e.g., true is typed {bool | ν = ⊤}, 5 is typed {nat | ν = 5}, and a singleton list Cons(5, ⟨Nil(triv, ⟨⟩)⟩) is typed {NatListθ | ν = 1} with some appropriate θ (recall that NatList admits a length interpretation).

The subtyping judgment Γ ⊢T1 <: T2 is defined via a common approach for refinement types, with the extra requirement that the potential in T1 should be not less than that in T2. Fig. 10 shows the subtyping rules. A canonical use of subtyping is to "forget" locally introduced program variables in the result type of an expression, e.g., to "forget" x in the type of e2 when typing let(e1,x.e2). In rule (Sub-Dtype), we introduce a partial order ⊑∆θ over potential annotations θ of sort ∆θ. For example, if θ1 and θ2 are sorted N, then θ1 ⊑N θ2 is encoded as θ1 ≤θ2 in the refinement language. We carefully define the partial order, in a way that the partial-order relation can be encoded as a first-order fragment of the refinement language. Notable is that we introduce validity-checking judgments Γ |= ψ to reason about logical constraints, i.e., to state that the Boolean-sorted refinement ψ is always true under any instance of the context Γ. We formalize the validity-checking relation via

a set-based denotational semantics for the refinement language. Validity checking is then reduced to Presburger arithmetic, making it decidable. The full development of validity checking is included in Sec. B.8

The rule (T-MatD) reasons about invariants for inductive datatypes. These invariants come from the associated interpretation of inductive data structures, e.g., the length of a list Cons(ah, ⟨at⟩) is one plus the length of its tail at. Intuitively, if the data structure a0 can be deconstructed as Cj(x0, ⟨x1, · · · ,xmj ⟩) of a datatype D with the form indθ

◁,π (−−−−−−−→ C : (T,m)), then by the definition of the interpretation I(·), we can derive

I(a0) = I(Cj(x0, ⟨x1, · · · ,xmj ⟩)) = ID(Cj(x0, ⟨x1, · · · ,xmj ⟩)),

which is exactly the path constraint required by the rule (T-MatD) to type the j-th branch ej. For example, if a0 has type NatListθ, then the path constraints for the Nil(_, ⟨⟩) and Cons(xh, ⟨xt⟩) constructors become I(a0) = 0 and I(a0) = xt + 1, respectively.

The type system has two rules for function applications: (T-App) and (T-App-SimpAtom). In the former case, the function return typeT does not mention x, and thus can be directly used as the type of the application. This rule deals with cases e.g. for all applications with higher-order arguments, since our sorting rules prevent functions from showing up in the refinements language. In the latter case, the function return type T mentions x, but the argument has a scalar type, and thus must be an interpretable atom a ∈SimpAtom, so we can substitute x in T with its interpretation I(a). Note that it is the use of a-normal-form that brings us the ability to derive precise types for dependent function applications.

Resources. There are two typing rules for the syntactic form tick(c,e0), one for nonnegative costs and the other for negative costs. The rule (T-Tick-N) assumes c < 0 and adds −c units of free potential to the context for typing e0. The rule (T-Tick-P) behaves differently; it states that tick(c,e0) is only typable in a context containing a free-potential term c. Nevertheless, we can use the rule (S-Transfer) to rearrange free potentials within the context into this form, as long as the total amount of free potential stays unchanged. In the rule (S-Transfer), Φ(Γ) extracts all the free potentials in the context Γ, while |Γ| removes all the free potentials, i.e., |Γ| keeps the functional specifications of Γ.

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 18 -->

106:18 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

Γ ⊢a : B

(SimpAtom-Var)

(SimpAtom-Bool)

Γ(x) = {B | ψ }ϕ

b ∈{true, false}

Γ ⊢b : bool

Γ ⊢x : B

(SimpAtom-ConsD)

(SimpAtom-Pair)

⊢Γ . Γ1 | Γ2 Γ1 ⊢a1 : B1 Γ2 ⊢a2 : B2 Γ ⊢pair(a1, a2) : B1 × B2

Γ ⊢e :: S

(T-SimpAtom)

(T-Var)

Γ ⊢a : B

Γ(x) = S

Γ ⊢a :: {B | ν = I(a)}

Γ ⊢x :: S

(T-Cond)

Γ ⊢a0 : bool Γ, I(a0) ⊢e1 :: T Γ, ¬I(a0) ⊢e2 :: T

(T-Tick-N)

c < 0 Γ, −c ⊢e0 :: T

Γ ⊢tick(c, e0) :: T

Γ ⊢if(a0, e1, e2) :: T

(T-MatD)

⊢Γ . Γ1 | Γ2 Γ1 ⊢a0 : indθ

for each j, Γ2,j

◁,π (−−−−−−−−→ C : (T , m)), ..., xmj :ind

x1 :ind◁.j(x0)(θ ).1

Γ2,j ⊢ej :: T ′

Γ ⊢matd(a0, −−−−−−−−−−−−−−−−−−−−−−−−−→ Cj(x0, ⟨x1, · · · , xmj ⟩).ej) :: T ′

(T-Let)

⊢Γ . Γ1 | Γ2 Γ ⊢T2 type Γ1 ⊢e1 :: S1 Γ2, x : S1 ⊢e2 :: T2 Γ ⊢let(e1, x .e2) :: T2

(T-App-SimpAtom)

(T-App)

⊢Γ . Γ1 | Γ2 Γ1 ⊢ˆa1 :: 1 · (x :Tx →T ) Γ2 ⊢ˆa2 :: Tx Γ ⊢T type

Γ ⊢app( ˆa1, ˆa2) :: T

(T-Abs)

Γ ⊢Tx type Γ, x : Tx ⊢e0 :: T ⊢Γ . Γ | Γ

Γ ⊢λ(x .e0) :: x :Tx →T

(T-Fix)

(S-Gen)

S = ∀−→α .x :Tx →T Γ ⊢S type Γ, f : S, −→α , x : Tx ⊢e0 :: T ⊢Γ . Γ | Γ

Γ ⊢fix(f .x .e0) :: S

(S-Transfer)

Γ′ ⊢e :: S Γ |= Φ(Γ) = Φ(Γ′) |Γ| = |Γ′|

(S-Subtype)

Γ ⊢e :: T1 Γ ⊢T1 <: T2 Γ ⊢e :: T2

Γ ⊢e :: S

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.

(SimpAtom-Nat)

(SimpAtom-Unit)

Γ ⊢n : nat

Γ ⊢triv : unit

⊢Γ . Γ1 | Γ2 Γ1 ⊢a0 :: Tj Γ2 ⊢⟨a1, · · · , amj ⟩: Îmj

◁,π (−−−−−−−−→ C : (T , m))

i=1 ind◁.j(I(a0))(θ ).i

◁,π (−−−−−−−−→ C : (T , m))

Γ, π .j(I(a0))(θ) ⊢Cj(a0, ⟨a1, · · · , amj ⟩) : indθ

(T-Imp)

(T-Tick-P)

Γ |= ⊥ Γ ⊢T type

c ≥0 Γ ⊢e0 :: T

Γ ⊢impossible :: T

Γ, c ⊢tick(c, e0) :: T

(T-MatP)

⊢Γ . Γ1 | Γ2 Γ1 ⊢a0 : B1 × B2 Γ ⊢T type Γ2, x1 : B1, x2 : B2, I(a0) = (x1, x2) ⊢e1 :: T

Γ ⊢matp(a0, x1.x2.e1) :: T

◁,π (−−−−−−−−→ C : (T , m)) Γ ⊢T ′ type

def=

Γ2, x0 :Tj,

◁.j(x0)(θ ).mj ◁,π (−−−−−−−−→ C : (T , m)), I(a0) = ID(Cj(x0, ⟨x1, ..., xmj ⟩)), π .j(x0)(θ)

,

⊢Γ . Γ1 | Γ2 Γ1 ⊢ˆa1 :: 1 · (x : {B | ψ }ϕ →T ) Γ2 ⊢a2 :: {B | ψ }ϕ

Γ ⊢app( ˆa1, a2) :: [I(a2)/x]T

(T-Abs-Lin)

Γ ⊢Tx type Γ, x : Tx ⊢e0 :: T

m × Γ ⊢λ(x .e0) :: m · (x :Tx →T )

v ∈Val Γ, α ⊢v :: S Γ, α ⊢S . S | S

(S-Inst)

Γ ⊢e :: ∀α .S Γ ⊢{B | ψ }ϕ type

Γ ⊢e :: [{B | ψ }ϕ/α]S

Γ ⊢v :: ∀α .S

(S-Relax)

Γ ⊢e :: Rϕ Γ ⊢ϕ′ ∈N

Γ, ϕ′ ⊢e :: Rϕ+ϕ′

Fig. 9. Typing rules


<!-- page 19 -->

Liquid Resource Types 106:19

To carry out amortized resource analysis [Tarjan 1985], our type system is supposed to properly reason about potentials, that is, potentials cannot be generated from nothing. This linear nature of potentials motivates us to develop an affine type system [Walker 2002]. As in Re2 [Knoth et al. 2019], we have to introduce explicit sharing to use a program variable multiple times. The sharing judgment takes the form Γ ⊢S . S1 | S2 and is intended to state that under the context Γ, the potential associated with type S is apportioned into two parts to be associated with type S1 and type S2. Fig. 10 also presents the sharing rules. In rule (Share-Dtype), we introduce a notation θ = θ1 ⊕∆θ θ2, which means that the annotation θ is the "sum" of two annotations θ1,θ2 that have sort ∆θ. For example, we define θ1 ⊕N θ2 by θ1 + θ2 in the refinement language. Similar to the partial order ⊑∆θ , which is used in the subtyping rules, we encode the "sum" operator ⊕∆θ using a first-order fragment of the refinement language. The sharing relation is further extended to context sharing, written ⊢Γ . Γ1 | Γ2, which means that Γ1 and Γ2 have the same sequence of bindings as Γ, but the free potentials in Γ are split into two parts to be associated with Γ1 and Γ2. Context sharing is used extensively in the typing rules where the expression has at least two sub-expressions to evaluate, e.g., in the rule (T-Let) for an expression let(e1,x.e2), we apprortion Γ into Γ1 and Γ2, use Γ1 for typing e1 and Γ2 for typing e2. Note that the rule (T-Abs) and (T-Fix) has self-sharing ⊢Γ . Γ | Γ as a premise, which means that the function can only use free variables with zero potential in the context. This restriction ensures that the program cannot gain potential through free variables by repeatedly applying a function of type ∞· (x :Tx →T) with an infinite multiplicity.

The rule (T-Abs-Lin) is introduced for typing functions with upper bounds on the number of applications. The rule associates a multiplicity m ∈Z+

0 with the function type as the upper bound. We use a finer-grained premise than context self-sharing to state that the potential of the free variables in the function is enough to pay for m function applications. This rule is useful for deriving types of curried functions e.g. a function of type x :Tx →y :Ty →T that require nonzero units of potential in its first argument x. In that case, a function f can be assigned a type x :Tx →m · (y :Ty →T), which means that the potential stored in the first argument x is enough for the partially applied function app(f ,x) to be invoked for m times.

The elimination rule (T-MatD) realizes the inductively defined potential function in (3): for typing the j-th branch ej, one has to add bindings of the content type x0 : Tj and properly

◁,π (−−−−−−−→ C : (T,m)), as well as a free-potential term π.j(x0)(θ) indicated by the potential-extraction operator π.j, to the context. The introduction rule (SimpAtomConsD) stores the amount of potentials required for deconstructing data structures. For typing Cj(a0, ⟨a1, · · · ,amj ⟩) with type indθ

shifted types for child nodes xi : ind◁.j(x0)(θ).i

◁,π (−−−−−−−→ C : (T,m)), the rule requires π.j(I(a0))(θ) as free potential in the context, which is used to pay for potential extraction π.j, and a premise stating that each child node ai has a corresponding properly-shifted annotated datatype ind◁.j(I(a0))(θ).i

◁,π (−−−−−−−→ C : (T,m)). Finally, the structural rule (S-Relax) is usually used when we are analyzing function applications. Both the rule (T-App) and the rule (T-App-SimpAtom) use up all the potential in the context, but in practice it is necessary to pass some potential through the function call to analyze non-tail-recursive programs. This is achieved by using the rule (S-Relax) at a function application with ϕ′ as the potential threaded to the computation that continues after the function returns.

Example 3.6 (Insertion sort). As shown in Sec. 2.2, our type system is able to verify that an implementation of insertion sort performs exactly the same amount of insertions as the number of out-of-order pairs in the input list. We rewrite the function insert as follows in the core calculus,

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 20 -->

106:20 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

Γ ⊢S . S1 | S2

(Share-Nat)

(Share-Bool)

Γ ⊢nat . nat | nat

Γ ⊢bool . bool | bool

(Share-Dtype)

(Share-Prod)

Γ ⊢B1 . B11 | B12 Γ ⊢B2 . B21 | B22 Γ ⊢B1 × B2 . B11 × B21 | B12 × B22

Γ ⊢indθ

(Share-Tvar)

α ∈Γ m = m1 + m2 Γ ⊢m · α . m1 · α | m2 · α

(Share-Arrow)

Γ ⊢(x :Tx →T ) type m = m1 + m2 Γ ⊢(m · (x :Tx →T )) . (m1 · (x :Tx →T )) | (m2 · (x :Tx →T ))

Γ ⊢T1 <: T2

(Sub-Nat)

(Sub-Unit)

Γ ⊢nat <: nat

Γ ⊢unit <: unit

(Sub-Dtype)

Γ ⊢−→ T <: −→ T ′ Γ ⊢θ, θ ′ ∈∆θ Γ |= θ ′ ⊑∆θ θ

◁,π (−−−−−−−−−→ C : (T ′, m))

◁,π (−−−−−−−−→ C : (T , m)) <: indθ ′

Γ ⊢indθ

(Sub-Arrow)

Γ ⊢T ′

x <: Tx Γ, x : T ′

x ⊢T <: T ′ m ≥m′

Γ ⊢m · (x :Tx →T ) <: m′ · (x :T ′

x →T ′)

using the dependently annotated list type NatList(θ1,θ2) from Example 3.3:

insert :: y : nat →ℓ: NatList(λx : N.ite(y>x,1,0),λx : N×N.0) →NatList(λx : N.0,λx : N×N.0)

insert = λ(y.fix(f .ℓ.matd(ℓ,

Nil(_, ⟨⟩).Cons(y, ⟨Nil(triv, ⟨⟩)⟩),

Cons(h, ⟨t⟩).let(y > h,b.

We assume that a comparison function > with signature a : nat →b : nat →{bool | ν = (a > b)} is provided in the typing context. Next, we illustrate how our type system justifies the number of recursive calls in insert is bounded by the number of elements in ℓthat are less than the element y that is being inserted to ℓ. Suppose Γ is a typing context that contains the signature of >, as well as type bindings for y, f , and ℓ. To reason about the pattern match on the list ℓ, we apply the

def= NatList(λx : N.0,λx : N×N.0):

(T-MatD) rule, where T

⊢Γ . Γ1 | Γ2 Γ1 ⊢ℓ: NatList(λx : N.ite(y>x,1,0),λx : N×N.0)

Γ2, ℓ= 0 ⊢e1 :: T Γ2, h : nat, t : NatList(λx : N.ite(y>x,1,0),λx : N×N.0), ℓ= t + 1, ite(y > h, 1, 0) ⊢e2 :: T

Γ ⊢matd(ℓ, Nil(_, ⟨⟩).e1, Cons(h, ⟨t ⟩).e2) :: T

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.

(Share-Poly)

(Share-Unit)

Γ, α ⊢S . S | S

Γ ⊢unit . unit | unit

Γ ⊢∀α .S . ∀α .S | ∀α .S

Γ ⊢−→ T . −→ T1 | −→ T2 Γ ⊢θ, θ1, θ2 ∈∆θ Γ |= θ = θ1 ⊕∆θ θ2

◁,π (−−−−−−−−→ C : (T , m)) . indθ1

◁,π (−−−−−−−−−→ C : (T1, m)) | indθ2

◁,π (−−−−−−−−−→ C : (T2, m))

(Share-Subset)

Γ ⊢B . B1 | B2 Γ ⊢{B | ψ } type

Γ ⊢{B | ψ } . {B1 | ψ } | {B2 | ψ }

(Share-Pot)

Γ ⊢R . R1 | R2 Γ, ν : R |= ϕ = ϕ1 + ϕ2

Γ ⊢Rϕ . R1ϕ1 | R2ϕ2

(Sub-Prod)

(Sub-Bool)

Γ ⊢B1 <: B′

1 Γ ⊢B2 <: B′

2 Γ ⊢B1 × B2 <: B′

1 × B′ 2

Γ ⊢bool <: bool

(Sub-Subset)

Γ ⊢B1 <: B2 Γ, ν : B1 |= ψ1 =⇒ψ2 Γ ⊢{B1 | ψ1} <: {B2 | ψ2}

(Sub-TVar)

α ∈Γ m1 ≥m2 Γ ⊢m1 · α <: m2 · α

(Sub-Pot)

Γ ⊢R1 <: R2 Γ, ν : R1 |= ϕ1 ≥ϕ2

Γ ⊢R1ϕ1 <: R2ϕ2

Fig. 10. Sharing and subtyping

if(b, tick(1, let(app(f ,t),t ′.Cons(h, ⟨t ′⟩))), Cons(y, ⟨Cons(h, ⟨t⟩)⟩)))


<!-- page 21 -->

Liquid Resource Types 106:21

For the context sharing, we apportion all the potential of ℓto Γ1 and the rest of potential of Γ to Γ2. In fact, since y and f do not carry potentials, the context Γ2 is potential-free i.e. ⊢Γ2 . Γ2 | Γ2. For the Nil-branch, e1 is a value that describes a singleton list containing y, thus we can easily conclude this case by rule (SimpAtom-ConsD) and the fact that the return type T is potential-free. For the Cons-branch, we first apply the (T-Let) rule with (T-App-SimpAtom) rule to derive a precise refinement type for the comparison result b:

⊢Γ2 . Γ2 | Γ2 Γ2, h : · · · , t : · · · , ℓ= t + 1, 0 ⊢y > h :: {bool | ν = (y > h)} Γ2, h : · · · , t : · · · , ℓ= t + 1, ite(y > h, 1, 0), b : {bool | ν = (y > h)} ⊢e3 :: T

Γ2, h : · · · , t : · · · , ℓ= t + 1, ite(y > h, 1, 0) ⊢let(y > h, b.e3) :: T

Then we use the rule (T-Cond) to reason about the conditional expression e3:

Γ2, h : · · · , t : · · · , ℓ= t + 1, ite(y > h, 1, 0), b : {bool | ν = (y > h)}, b ⊢e4 :: T Γ2, h : · · · , t : · · · , ℓ= t + 1, ite(y > h, 1, 0), b : {bool | ν = (y > h)}, ¬b ⊢e5 :: T

Γ2, h : · · · , t : · · · , ℓ= t + 1, ite(y > h, 1, 0), b : {bool | ν = (y > h)} ⊢if(b, e4, e5) :: T

By validity checking, we can show that y : nat,h : nat,b : {bool | ν = (y > h)},b |= y > h, thus y : nat,h : nat,b : {bool | ν = (y > h)},b |= ite(y > h, 1, 0) = 1. Then, by the (S-Transfer) rule

on the goal involving the then-branch e4, it suffices to show that Γ2,h : · · · ,t : · · · , ℓ= t + 1,b : {bool | ν = (y > h)},b, 1 ⊢e4 :: T. Note that we now have one unit of free potential in the context, so we can use it for typing the tick expression by (T-Tick-P):

Γ2, h : · · · , t : · · · , ℓ= t + 1, b : {bool | ν = (y > h)}, b ⊢let(app(f , t), t′.Cons(h, ⟨t′⟩)) :: T

Γ2, h : · · · , t : · · · , ℓ= t + 1, b : {bool | ν = (y > h)}, b, 1 ⊢tick(1, let(app(f , t), t′.Cons(h, ⟨t′⟩))) :: T

It remains to derive the type of the recursive function application app(f ,t), and the list construction Cons(h, ⟨t ′⟩) where t ′ is the return of the application. The derivation is straightforward as f has type ℓ: NatList(λx : N.ite(y>x,1,0),λx : N×N.0) →T, t has type NatList(λx : N.ite(y>x,1,0),λx : N×N.0), thus the returned list t ′ has type T and so does Cons(h, ⟨t ′⟩).

We now turn to the function sort that makes use of insert:

sort :: ℓ: NatList(λx : N.1,λ(x1 : N,x2 : N).ite(x1>x2,1,0)) →NatList(λx : N.0,λx : N×N.0)

sort = fix(f .ℓ.matd(ℓ,

Nil(_, ⟨⟩).Nil(triv, ⟨⟩),

Cons(h, ⟨t⟩).tick(1, let(app(f ,t),t ′.let(app(insert,h),ins.app(ins,t ′)))))

Recall that in Example 3.3, we explain that the type of the argument list ℓdefines a potential function in terms of the number of out-of-order pairs in ℓ. Let Γ′ be a typing context that contains the signature of insert, as well as potential-free type bindings for f and ℓ. Using the shift operation ◁for NatList, we are supposed to derive the following judgment for the Cons-branch of the pattern match:

Γ′,h : nat,t : NatList(λx : N.1+ite(h>x,1,0),λ(x1 : N,x2 : N).ite(x1>x2,1,0)), ℓ= t+1 ⊢let(app(f ,t),t ′. · · · ) :: T .

However, we get stuck here, because there is a mismatch between the argument type of f i.e. sort, and the shifted type of the tail list t in the context.

Polymorphic recursion. In general, it is often necessary to type recursive function calls with a type that has different potential annotations from the declared types of the recursive functions. We achieve this using polymorphic recursion that allows recursive calls to be instantiated with types that have different potential annotations. Although we get stuck when typing sort in Example 3.6, we will show how our system is able to type a polymorphic version of sort, which has been informally demonstrated in Sec. 2.2.

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 22 -->

106:22 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

Listθ(α) ≡indθ

where ◁= (◁Nil, ◁Cons), π = (πNil, πCons) are defined as follows:

def= λ_.λθ.0, πCons

πNil

◁Nil

def= λ_.λθ.⋆, ◁Cons

We then generalize the type signatures of insert and sort with the polymorphic list type:

### 3.5 Soundness

Lemma 3.9 (Preservation). If q ⊢e :: S, p ≥q and ⟨e,p⟩7→⟨e′,p′⟩, then p′ ⊢e′ :: S.

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.

Example 3.7 (Insertion sort with polymorphic recursion). We start with a polymorphic list type, which is supported by our implementation but not formulated in the core calculus:

◁,π (Nil : unit, Cons : (x :α) × List◁Cons(x)(θ)(αθ(x,ν))),

def= λy.λθ.0,

def= λy.λθ.θ.

insert :: ∀α.y :α →ℓ: Listλ(x1,x2).0(αite(y>ν,1,0)) →Listλ(x1,x2).0(α), (4)

sort :: ∀α.ℓ: Listλ(x1,x2).ite(x1>x2,1,0)(α1) →Listλ(x1,x2).0(α) (5)

Similar to the type derivation in Example 3.6, we are supposed to derive the following judgment for the Cons-branch of the pattern match in the implementation of sort:

Γ′,h : α,t : Listλ(x1,x2).ite(x1>x2,1,0)(α1+ite(h>ν,1,0)), ℓ= t + 1 ⊢let(app(f ,t),t ′. · · · ) :: T .

Now the function f is bound to the polymorphic type in (5). To type the function call app(f ,t), we instantiate f with αite(h>ν,1,0), i.e., f has type ℓ: Listλ(x1,x2).ite(x1>x2,1,0)(α1+ite(h>ν,1,0)) → Listλ(x1,x2).0(αite(h>ν,1,0)). Thus, the type of the return value t ′ of app(f ,t) matches the argument type of insert, and we can derive the function application let(app(insert,h),ins.app(ins,t ′)) has the desired return type Listλ(x1,x2).0(α).

We now extend Re2's type soundness [Knoth et al. 2019] to new features we introduced in previous sections, including refinement-level computation and user-defined inductive datatypes. The soundness of the type system is based on progress and preservation, and takes resources into account. The progress theorem states that if q ⊢e :: S, then either e is already a value, or we can make a step from e with at least q units of available resource. Intuitively, progress indicates that our type system derives bounds that are indeed upper bounds on the high-water mark of resource usage.

Lemma 3.8 (Progress). If q ⊢e :: S and p ≥q, then either e ∈Val or there exist e′ and p′ such that ⟨e,p⟩7→⟨e′,p′⟩.

Proof. By strengthening the assumption to Γ ⊢e :: S where Γ is a sequence of type variables and free potentials, and then induction on Γ ⊢e :: S. □

The preservation theorem then relates leftover resources after a step in computation and the typing judgment for the new term to reason about resource consumption.

Proof. By strengthening the assumption to Γ ⊢e :: S where Γ is a sequence of free potentials, and then induction on Γ ⊢e :: S, followed by inversion on the evaluation judgment ⟨e,p⟩7→⟨e′,p′⟩. □

As in other refinement type systems, purely syntactic soundness statement about results of computations (i.e., they are well-typed values) is unsatisfactory. Thus, we also formulate a denotational notation of consistency. For example, the literal b = true, but not b = false, is consistent with 0 ⊢b :: {bool | ν}; A list of values ℓ= [v1, · · · ,vn] is consistent with q ⊢ℓ:: NatListλx : N.x, if q ≥Ín

i=1 vi. We then show that well-typed values are consistent with their typing judgement.


<!-- page 23 -->

Liquid Resource Types 106:23

Lemma 3.10 (Consistency). If q ⊢v :: S, then v satisfies the conditions indicated by S and q is greater than or equal to the potential stored in v with respect to S.

Proof. By inversion on the typing judgment we have q ⊢v : B for some base type B or v is an abstraction. The latter case is easy as the refinement language cannot mention function values. For the former case, we proceed by strengthening the assumption to Γ ⊢v : B where Γ is a sequence of type variables and free potentials, then induction on Γ ⊢v : B. □

As a result of the lemmas above, we derive the following main technical theorem of this paper.

Theorem 3.11 (Soundness). If q ⊢e :: S and p ≥q then either

• ⟨e,p⟩7→∗⟨v,p′⟩and v is consistent with p′ ⊢v :: S or • for every n there is ⟨e′,p′⟩such that ⟨e,p⟩7→n ⟨e′,p′⟩.

Detailed proofs are included in Appendix C

## 4 EVALUATION

We have implemented the new features of liquid resource types, inductive and abstract potentials, on top of the ReSyn type checker; we refer to the resulting implementation as LRTChecker. In this section, we evaluate LRTChecker according to three metrics:

Expressiveness: How well can LRTChecker express non-linear and dependent bounds? To what extent can LRTChecker express bounds that systems like ReSyn and RaML could not?

Automation: Can LRTChecker automatically verify expressive bounds which other tools cannot? Are the verification times reasonable?

Flexibility: Can we define reusable datatypes that can express a variety of resource bounds across different programs?

### 4.1 Reusable Datatypes

We first describe a small library of resource-annotated datatypes we created, which we will use to specify type signatures for our benchmark functions. The definitions of the four datatypes are listed in 1. Since potential is only specified inductively in these definitions, we also provide a closed form expression for the potential associated with each such data structure (omitting the potential stored in the element type a). The proofs of these closed forms can be found in Appendix A.

List and EList are general purpose list data structures that contain quadratic and exponential potential, respectively. In particular, List admits dependent potential expressions, as the abstract potential parameter is a function of the list elements. This list type can be adapted to express higher-degree polynomial potential functions via the generalized left shift operation, described in Sec. 3.3. EList can be modified to express exponential potential for any positive integer base k by modifying the type of the second argument to Cons:

Cons :: x : aq →xs : EList a ⟨k · q⟩→EList a ⟨q⟩

Such a list contains q · (kn −1) units of potential; k has to be fixed for annotations to remain linear.

LTree is a binary tree with values (and thus, potential) stored in its leaves. We show that the total potential stored in the tree is q · n · h, where n is the number of leaves in the tree and h is its height. If we additionally assume that the tree is balanced, then h = O(log(n)), and hence the amount of potential in the tree is O(n · log(n)). In Sec. 4.2 we use this tree as an intermediate data structure in order to reason about logarithmic bounds.

PTree is a binary tree with elements in the nodes, which uses dependent potential annotations to specify the exact path through the tree that carries potential; we refer to this data structure as pathed potential tree. PTree is parameterized by a boolean-sorted abstract refinement [Vazou et al.

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 24 -->

106:24 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

Table 1. Annotated data structures with their corresponding potential functions. n is taken to be the number of elements in the data structure. In PTree, |ℓ| is the length of the path specified by the predicate p.

1 data List a <q::a→a→Int> where

Nil ::List a <q> Cons ::x: a →List aq x <q> →List a <q>

2 data EList a <q::Int> where

Nil ::EList a <q> Cons ::x: aq →EList a <2∗q> →EList a <q>

3 data LTree a <q::Int> where

Leaf ::a →LTree a <q> Node ::LTree aq <q> →LTree aq <q> →LTree a <q>

data PTree a <p::a→Bool, q::Int> where

Leaf ::PTree a <p,q> Node ::x: aq →PTree a <p, ite(p(x), q, 0)>

→PTree a <p, ite(p(x), 0, q)> →PTree a <p,q>

### 4.2 Benchmark Programs

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.

Datatype Potential Interpretation

i<j q(ai,aj)

Í

q · (2n −1)

≈q · n log2(n)

q · |ℓ|

2013], p, which is then used in the potential annotations to conditionally allocate potential either to the left or to the right subtree, depending on the element in the node. Since p is used to pick exactly one subtree at each step, it specifies a path from root to leaf.

These data structures showcase a variety of ways in which liquid resource types can be used to reason about a program's performance. Additionally, because the interpretation of abstract potentials is left entirely to the user, one can define custom data structures to describe other resource bounds as needed.

We evaluate the expressiveness of LRTChecker on a suite of 12 benchmark programs listed in Tab. 2. The resource consumptions of these benchmarks covers a wide range of complexity classes. We choose functions with quadratic, exponential, logarithmic, and value-dependent resource bounds in order to showcase the breadth of bounds LRTChecker can verify. We are able to express these bounds using only the datatypes from 1, showing the flexibility and reusability of these datatype definitions. The cost model in all benchmarks is the number of recursive calls (as in Sec. 2).

Benchmarks 1-7 require only standard quadratic bounds. Benchmarks 2-7 are those programs from the original Synqid benchmark suite [Polikarpova et al. 2016] that ReSyn could not handle, because they require non-linear bounds. Some of the analyses, such as merge sort, are overapproximate. Benchmark 8 moves beyond polynomials, solving the well-known subset sum problem. The function runs in exponential time, so we can write a resource bound using our EList data structure to require exponential potential in the input. Once we have verified that subsetSum :: EList Int ⟨2⟩→Int → Bool, we can use the provided closed-form potential function to calculate total resource usage: 2(2n −1), exactly the number of recursive calls made at runtime. Benchmark 9 illustrates how LRTChecker can verify a more precise O(n log(n)) bound for a version of merge sort. LRT is unable to allocate logarithmic amount of potential directly to a list, hence we specify this benchmark using LTree as an intermediate data structure. Prior work has


<!-- page 25 -->

Liquid Resource Types 106:25

Table 2. Functional benchmarks. For each benchmark, we list its type signature, verification time (t), and source for the benchmark - either RaML [Hoffmann and Hofmann 2010b], Synquid [Polikarpova et al. 2016], or RelCost [Radicek et al. 2018a].

Type No. Description Type Signature t (s) Source

## 1 All ordered pairs

List a2 ⟨2⟩→List (Pair a) 0.5 RaML 2 List Reverse List a2 ⟨1⟩→List a 0.4 Synqid 3 List Remove Duplicates List a2 ⟨1⟩→List a 0.4 Synqid 4 Insertion Sort (Coarse) List a2 ⟨1⟩→List a 0.6 Synqid 5 Selection Sort List a4 ⟨3⟩→List a 0.5 Synqid 6 Quick Sort List a3 ⟨3⟩→List a 1.0 Synqid 7 Merge Sort List a2 ⟨2⟩→List a 0.9 Synqid Non-Polynomial

Polynomial

Quadratic

Potential

## 8 Subset Sum

EList Int ⟨2⟩→Int →Bool 0.3 - 9 Merge Sort Flatten LTree a1 ⟨1⟩→List a 0.9 - ValueDependent

Potential

## 10 Insertion Sort (Fine)

List a1 ⟨λx1, x2. ite(x1 < x2, 1, 0)⟩→List a 5.4 RelCost 11 BST Insert x : a →PTree a ⟨λx1. x < x1, 1⟩→PTree a ⟨λx1. x < x1, 0⟩ 2.4 - 12 BST Member x : a →PTree a ⟨λx1. x < x1, 1⟩→Bool 6.0 -

Potential

shown [Augusteijn 1999] that merge sort can be written more explicitly as a composition of two function: build, which converts a list into a tree (where each internal node represents a split of a list into halves), and flatten, which takes a tree and recursively merges its subtrees into a single sorted list. In the traditional implementation of merge sort, the two passes are fused, and the intermediate tree is never constructed; however, keeping this tree explicit, enables us to specify a logarithmic bound on the flatten phase of merge sort, which performs the actual sorting. We accomplish this by typing its input as LTree a1 ⟨1⟩; because build always constructs balanced trees, this tree carries approximately n log(n) units of potential, where n is the number of leaves in the tree, which coincides with the number of list elements. Unfortunately, LRT is unable to express a precise resource specification for the build phase of merge sort, or for the traditional, fused version without the intermediate tree.

Benchmarks 10 through 12 show the expressiveness of value-dependent potentials. Benchmark 10 is the dependent version of insertion sort from Sec. 2. Benchmarks 11 and 12 use the PTree data structure to allocate linear potential along a value-dependent path in a binary tree. We use PTree to specify the resource consumption of inserting into and checking membership in a binary search tree. PTree allows us to assign potential only along the specific path taken while searching for the relevant node in the tree. As a result, we can endow our tree with exactly the amount of potential required to execute member or insert on an arbitrary BST. If we have the additional guarantee that our BST is balanced, we can also conclude that these bounds are logarithmic, as the relevant path is the same length as the height of the tree.

### 4.3 Discussion and Limitations

Tab. 2 confirms that LRTChecker is reasonably efficient: verification takes under a second for simple numeric bounds (benchmarks 1-9). The precision of value-dependent bounds (benchmarks 10-12) comes with slightly higher verification times--up to six seconds; these benchmarks generate secondorder CLIA constraints and require the use of ReSyn's CEGIS solver (as opposed to first-order constraints that can be handled by an SMT solver).

No other automated resource analysis system can verify all of our benchmarks in Sec. 4.2. RelCost can be used to verify all of these bounds, but provides no automation. ReSyn cannot verify any of our benchmarks, as it can only reason about linear resource consumption. RaML can infer an appropriate bound for benchmarks 1-7, which all require quadratic potential. However, RaML cannot reason about the other examples, as it cannot reason about program values, and only

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 26 -->

106:26 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

support polynomials. RaML relies on a built-in definition of potential in a data structure, while LRTChecker exposes allocation of potential via datatype declarations, allowing the programmer to easily configure it to handle non-polynomial bounds. In particular, a LRTChecker user can adopt RaML's treatment of polynomial resource bounds via our List type, and can also write non-polynomial specifications with other datatypes from our library or with a custom datatype.

The RelCost formalism presented in [Radicek et al. 2018a] allows one to manually verify all of the bounds in Sec. 4.2. [Çiçek et al. 2019] presents an implementation of a subset RelCost. This tool can be used to automatically verify non-linear bounds that are dependent only on the length of a list. To verify non-linear bounds, the system still generates non-linear constraints, and thus relies on incomplete heuristics for constraint solving. Benchmarks 10-12 in Tab. 2 all consist of conditional bounds, which are not supported by the implementation of RelCost.

Despite LRTChecker's flexibility, it has some limitations. Firstly, our resource bounds must be defined inductively over the function's input, and hence we cannot express bounds that do not match the structure of the input type. A prototypical example is the logarithmic bound for merge sort: we can specify this bound for the flatten phase, which operates over a tree (where the logarithm is "reified" in the tree height), but not for merge sort as a whole that operates over a list.

Secondly, LRTChecker cannot express multivariate resource bounds. Consider a function that takes two lists and returns a list of every pair in the cartestian product of the two inputs. This function runs in O(m · n), where m and n are the lengths of the two input lists. There is no way to express this bound by annotating the types of input lists with terms form CLIA.

Finally, LRTChecker can verify, but not infer resource bounds. So while verification is automatic, finding the correct type signature must be done manually, even if the correct data structure has been selected. Simple modifications would allow the system to infer non-dependent resource bounds following the approach of RaML [Hoffmann and Hofmann 2010b], but this technique does not generalize to the dependent case.

## 5 RELATED WORK

Verification and inference techniques for resource analysis have been extensively studied. Traditionally, automatic techniques for resource analysis are based on a two-phase process: (1) extract recurrence relations from a program and (2) solve recurrence relations to obtain a closed-form bound. This strategy has been pioneered by Wegbreit [Wegbreit 1975] and has been later been studied for imperative programs [Albert et al. 2011, 2015] using techniques such as abstract interpretation and symbolic analysis [Kincaid et al. 2017, 2019]. The approach can also be used for higher-order functional programs by extracting higher-order recurrences [Danner et al. 2015]. Other resource analysis techniques are based on static analysis [Gulwani et al. 2009a,b; Sinn et al. 2014; Zuleger et al. 2011] and term rewriting [Avanzini and Moser 2013; Brockschmidt et al. 2014; Hofmann and Moser 2015; Noschinski et al. 2013].

Most closely related to our work are type-based approaches to resource bound analysis. We biuld upon type-based automated amortized resource analysis (AARA). AARA has been introduced by Hofmann and Jost [Hofmann and Jost 2003] to automatically derive linear bounds on the heap-space consumption of first-order programs. It has then been extended to higher-order programs [Jost et al. 2010], polynomial bounds [Hoffmann et al. 2011b; Hoffmann and Hofmann 2010a] and userdefined types [Hoffmann et al. 2017; Jost et al. 2010]. Most recently, AARA has been combined with refinement types [Freeman and Pfenning 1991] in the Re2 type system [Knoth et al. 2019] behind ReSyn, a resource-aware program synthesizer. None of these works support user-defined potential functions. As discussed in Sec. 1, this paper extends Re2 with inductive datatypes that can be annotated with custom potential functions. The introduction of abstract potential functions allows this work to reuse ReSyn's constraint solving infrastructure when reasoning about richer

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 27 -->

Liquid Resource Types 106:27

resource bounds. This work also formalizes the technique for user-defined inductive datatypes, while the Re2 formalism admitted only reasoning about lists.

Several other works have used refinement types and dependent types for resource bound analysis. Danielsson [Danielsson 2008] presented a dependent cost monad that has been integrated in the proof assistant Agda. dℓPCF [Lago and Gaboardi 2011] introduced linear dependent types to reason about the worst-case cost of PCF terms. Granule [Orchard et al. 2019] introduces graded modal types, combining the indexed types of dℓPCF with bounded linear logic [Girard et al. 1992] and other modal type systems [Brunel et al. 2014; Ghica and Smith 2014]. While useful for a variety of applications, such as enforcing stateful protocols, reasoning about privacy, and bounding variable reuse, these techniques do not allow an amortized resource analysis. Çiçek et al. [Çiçek et al. 2017, 2019] have pioneered the use of relational refinement type systems for verifying the bounds on the difference of the cost of two programs. It has been shown that linear AARA can be embedded in a generalized relational type systems for monadic refinements [Radicek et al. 2018b]. While this article does not consider relational verification, the presented type system allows for decidable type checking and is a conservative extension of AARA instead of an embedding.

Similarly, TiML [Wang et al. 2017] implements (non-relational) refinement types in the proof assistant Coq to aid verification of resource usage. A recent article also studied refinement types for a language with lazy evaluation [Handley et al. 2020]. However, these works do not directly support amortized analysis and do not reduce type checking of non-linear bounds to linear constraints.

ACKNOWLEDGMENTS We thank the anonymous reviewers and our shepherd, Richard Eisenberg, for their valuable and detailed feedback on earlier versions of this article.

This article is based on research supported by DARPA under AA Contract FA8750-18-C-0092 and by the National Science Foundation under SaTC Award 1801369, CAREER Award 1845514, and SHF Awards 1812876, 1814358, and 2007784. Any opinions, findings, and conclusions contained in this document are those of the authors and do not necessarily reflect the views of the sponsoring organizations.

REFERENCES

E. Albert, P. Arenas, S. Genaim, and G. Puebla. 2011. Closed-Form Upper Bounds in Static Cost Analysis. J. Automated

Reasoning 46 (February 2011). Issue 2. E. Albert, J. C. Fernández, and G. Román-Díez. 2015. Non-cumulative Resource Analysis. In Tools and Algs. for the Construct.

and Anal. of Syst. (TACAS'15). Rajeev Alur, Rastislav Bodík, Garvit Juniwal, Milo M. K. Martin, Mukund Raghothaman, Sanjit A. Seshia, Rishabh Singh,

Armando Solar-Lezama, Emina Torlak, and Abhishek Udupa. 2013. Syntax-guided synthesis. In Formal Methods in Computer-Aided Design, FMCAD 2013, Portland, OR, USA, October 20-23, 2013. 1-8. http://ieeexplore.ieee.org/document/ 6679385/ Lex Augusteijn. 1999. Sorting Morphisms. In Advanced Functional Programming, S. Doaitse Swierstra, José N. Oliveira, and

Pedro R. Henriques (Eds.). Springer Berlin Heidelberg, Berlin, Heidelberg, 1-27. M. Avanzini and G. Moser. 2013. A Combination Framework for Complexity. In Int. Conf. on Rewriting Techniques and

Applications (RTA'13). M. Brockschmidt, F. Emmes, S. Falke, C. Fuhs, and J. Giesl. 2014. Alternating Runtime and Size Complexity Analysis of

Integer Programs. In Tools and Algs. for the Construct. and Anal. of Syst. (TACAS'14). Aloïs Brunel, Marco Gaboardi, Damiano Mazza, and Steve Zdancewic. 2014. A Core Quantitative Coeffect Calculus. In

Proceedings of the 23rd European Symposium on Programming Languages and Systems - Volume 8410. Springer-Verlag, Berlin, Heidelberg, 351-370. https://doi.org/10.1007/978-3-642-54833-8_19 E. Çiçek, G. Barthe, M. Gaboardi, D. Garg, and J. Hoffmann. 2017. Relational Cost Analysis. In Princ. of Prog. Lang. (POPL'17). Ezgi Çiçek, Weihao Qu, Gilles Barthe, Marco Gaboardi, and Deepak Garg. 2019. Bidirectional type checking for relational

properties. In Proceedings of the 40th ACM SIGPLAN Conference on Programming Language Design and Implementation, PLDI 2019, Phoenix, AZ, USA, June 22-26, 2019, Kathryn S. McKinley and Kathleen Fisher (Eds.). ACM, 533-547. https:

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 28 -->

106:28 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

//doi.org/10.1145/3314221.3314603 Nils Anders Danielsson. 2008. Lightweight Semiformal Time Complexity Analysis for Purely Functional Data Structures. In

35th ACM Symp. on Principles Prog. Langs. (POPL'08). 133-144. N. Danner, D. R. Licata, and R. Ramyaa. 2015. Denotational Cost Semantics for Functional Languages with Inductive Types.

In Int. Conf. on Functional Programming (ICFP'15). Ewen Denney. 1999. A theory of program refinement. Ph.D. Dissertation. University of Edinburgh, UK. http://hdl.handle.

net/1842/381 T. Freeman and F. Pfenning. 1991. Refinement Types for ML. In Prog. Lang. Design and Impl. (PLDI'91). Dan R. Ghica and Alex I. Smith. 2014. Bounded Linear Types in a Resource Semiring. In Proceedings of the 23rd European

Symposium on Programming Languages and Systems - Volume 8410. Springer-Verlag, Berlin, Heidelberg, 331-350. https: //doi.org/10.1007/978-3-642-54833-8_18 Jean-Yves Girard, Andre Scedrov, and Philip J. Scott. 1992. Bounded Linear Logic: A Modular Approach to Polynomial-Time

Computability. Theor. Comput. Sci. 97, 1 (1992), 1-66. Sumit Gulwani, Sagar Jain, and Eric Koskinen. 2009a. Control-Flow Refinement and Progress Invariants for Bound Analysis.

In Conf. on Prog. Lang. Design and Impl. (PLDI'09). 375-385. S. Gulwani, K. K. Mehra, and T. M. Chilimbi. 2009b. SPEED: Precise and Efficient Static Estimation of Program Computational

Complexity. In Princ. of Prog. Lang. (POPL'09). Martin A. T. Handley, Niki Vazou, and Graham Hutton. 2020. Liquidate your assets: reasoning about resource usage in

liquid Haskell. PACMPL 4, POPL (2020), 24:1-24:27. https://doi.org/10.1145/3371092 R. Harper. 2016. Practical Foundations for Programming Languages. Cambridge University Press. Jan Hoffmann, Klaus Aehlig, and Martin Hofmann. 2011a. Multivariate Amortized Resource Analysis. In 38th Symp. on

Principles of Prog. Langs. (POPL'11). 357-370. J. Hoffmann, K. Aehlig, and M. Hofmann. 2011b. Multivariate Amortized Resource Analysis. In Princ. of Prog. Lang. (POPL'11). J. Hoffmann, A. Das, and S.-C. Weng. 2017. Towards Automatic Resource Bound Analysis for OCaml. In Princ. of Prog. Lang.

(POPL'17). J. Hoffmann and M. Hofmann. 2010a. Amortized Resource Analysis with Polynomial Potential. In European Symp. on

Programming (ESOP'10). Jan Hoffmann and Martin Hofmann. 2010b. Amortized Resource Analysis with Polynomial Potential - A Static Inference

of Polynomial Bounds for Functional Programs. In In Proceedings of the 19th European Symposium on Programming (ESOP'10) (Lecture Notes in Computer Science), Vol. 6012. Springer, 287-306. M. Hofmann and S. Jost. 2003. Static Prediction of Heap Space Usage for First-Order Functional Programs. In Princ. of Prog.

Lang. (POPL'03). M. Hofmann and G. Moser. 2015. Multivariate Amortised Resource Analysis for Term Rewrite Systems. In Int. Conf. on

Typed Lambda Calculi and Applications (TLCA'15). S. Jost, K. Hammond, H.-W. Loidl, and M. Hofmann. 2010. Static Determination of Quantitative Resource Usage for

Higher-Order Programs. In Princ. of Prog. Lang. (POPL'10). Z. Kincaid, J. Breck, A. F. Boroujeni, and T. Reps. 2017. Compositional Recurrence Analysis Revisited. In Prog. Lang. Design

and Impl. (PLDI'17). Z. Kincaid, J. Cyphert, J. Breck, and T. Reps. 2019. Non-linear Reasoning for Invariant Synthesis. In Princ. of Prog. Lang.

(POPL'19). Tristan Knoth, Di Wang, Nadia Polikarpova, and Jan Hoffmann. 2019. Resource-Guided Program Synthesis. In Proceedings

of the 40th ACM SIGPLAN Conference on Programming Language Design and Implementation (PLDI 2019). Association for Computing Machinery, New York, NY, USA, 253-268. https://doi.org/10.1145/3314221.3314602 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova. 2020. Liquid Resource Types (Extended

Version). (2020). arXiv:cs.PL/2006.16233 U. D. Lago and M. Gaboardi. 2011. Linear Dependent Types and Relative Completeness. In Logic in Computer Science

(LICS'11). L. Noschinski, F. Emmes, and J. Giesl. 2013. Analyzing Innermost Runtime Complexity of Term Rewriting by Dependency

Pairs. J. Automated Reasoning 51 (June 2013). Issue 1. Dominic Orchard, Vilem-Benjamin Liepelt, and Harley Eades III. 2019. Quantitative Program Reasoning with Graded Modal

Types. Proc. ACM Program. Lang. 3, ICFP, Article 110 (July 2019), 30 pages. https://doi.org/10.1145/3341714 Nadia Polikarpova, Ivan Kuraj, and Armando Solar-Lezama. 2016. Program synthesis from polymorphic refinement types.

In Programming Language Design and Implementation (PLDI). 522-538. Ivan Radicek, Gilles Barthe, Marco Gaboardi, Deepak Garg, and Florian Zuleger. 2018a. Monadic refinements for relational

cost analysis. PACMPL 2, POPL (2018), 36:1-36:32. https://doi.org/10.1145/3158124 Ivan Radicek, Gilles Barthe, Marco Gaboardi, Deepak Garg, and Florian Zuleger. 2018b. Monadic refinements for relational

cost analysis. PACMPL 2, POPL (2018), 36:1-36:32. https://doi.org/10.1145/3158124

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 29 -->

Liquid Resource Types 106:29

Andrew Reynolds, Viktor Kuncak, Cesare Tinelli, Clark W. Barrett, and Morgan Deters. 2019. Refutation-based synthesis in

SMT. Formal Methods Syst. Des. 55, 2 (2019), 73-102. Patrick Maxim Rondon, Alexander Bakst, Ming Kawaguchi, and Ranjit Jhala. 2012. CSolve: Verifying C with Liquid Types.

In Computer Aided Verification - 24th International Conference, CAV 2012, Berkeley, CA, USA, July 7-13, 2012 Proceedings. 744-750. https://doi.org/10.1007/978-3-642-31424-7_59 Patrick Maxim Rondon, Ming Kawaguchi, and Ranjit Jhala. 2008. Liquid types. In PLDI. A. Sabry and M. Felleisen. 1992. Reasoning about Programs in Continuation-Passing Style. In LISP and Functional Program-

ming (LFP'92). Moritz Sinn, Florian Zuleger, and Helmut Veith. 2014. A Simple and Scalable Approach to Bound Analysis and Amortized

Complexity Analysis. In Computer Aided Verification - 26th Int. Conf. (CAV'14). 743-759. Armando Solar-Lezama, Liviu Tancau, Rastislav Bodík, Sanjit A. Seshia, and Vijay A. Saraswat. 2006. Combinatorial

sketching for finite programs. In ASPLOS. Nikhil Swamy, Cătălin Hriundefinedcu, Chantal Keller, Aseem Rastogi, Antoine Delignat-Lavaud, Simon Forest, Karthikeyan

Bhargavan, Cédric Fournet, Pierre-Yves Strub, Markulf Kohlweiss, Jean-Karim Zinzindohoue, and Santiago ZanellaBéguelin. 2016. Dependent Types and Multi-Monadic Effects in F*. In Proceedings of the 43rd Annual ACM SIGPLAN-SIGACT Symposium on Principles of Programming Languages (POPL '16). Association for Computing Machinery, New York, NY, USA, 256-270. https://doi.org/10.1145/2837614.2837655 R. E. Tarjan. 1985. Amortized Computational Complexity. SIAM J. Algebraic Discrete Methods 6 (August 1985). Issue 2. Niki Vazou, Patrick Maxim Rondon, and Ranjit Jhala. 2013. Abstract Refinement Types. In ESOP. D. Walker. 2002. Substructural Type Systems. In Advanced Topics in Types and Programming Languages. MIT Press. P. Wang, D. Wang, and A. Chlipala. 2017. TiML: A Functional Language for Practical Complexity Analysis with Invariants.

In Object-Oriented Prog., Syst., Lang., and Applications (OOPSLA'17). Ben Wegbreit. 1975. Mechanical Program Analysis. Commun. ACM 18, 9 (1975), 528-539. F. Zuleger, M. Sinn, S. Gulwani, and H. Veith. 2011. Bound Analysis of Imperative Programs with the Size-change Abstraction.

In Static Analysis Symp. (SAS'11).

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 30 -->

106:30 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

A APPENDIX In order for our closed-form potential function to be valid, we need to check that for each data structure we require that for each constructor, the sum of potentials of the arguments r1, . . . ,rs of that constructor equals the potential of the overall data structure.

A.1 List: Dependent Quadratic Potential

data List a <q::a →a →Nat> where

Nil::List a <q> Cons::x: a →xs: List aq(x,v ) <q> →List a <q>

Claim. Let ℓ:: List a⟨q⟩be of length n, so that ℓis [a1, . . . ,an]. Then:

Õ

ΦList(ℓ) =

Proof. Matching ℓto Nil, we have

ΦList(uNil

Õ

i ) = 0 =

i

Matching ℓto Cons x xs, we have

ΦList(uCons

Õ

i ) = ΦList(xs) =

i

A.2 List: Exponential Potential

data EList a <q::Int> where

Nil::EList a <q> Cons::x: aq →xs: EList a <q + q> →EList a <q>

Claim. Let ℓ:: EList a ⟨q :: Nat⟩be of length n. Then:

ΦEList(ℓ) = q ∗(2n −1) is a sound potential function.

Proof. Matching ℓto Nil, we have

Õ

i

Matching ℓto Cons x xs, we have

ΦEList(uCons

Õ

i

A.3 Balanced Binary Tree: Logarithmic potential

data LTree a <q::Nat> where

Leaf::aq →LTree a <q> Node::LTree aq <q> →LTree aq <q> →LTree a <q>

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.

1≤i<j ≤n q(ai,aj) is a sound potential function.

Õ

1≤i<j ≤n q(ai,aj) = ΦList(ℓ).

Õ

Õ

2≤i ≤n q(a1,ai) +

2≤i<j ≤n q(ai,aj)

Õ

1≤i<j ≤n q(ai,aj) = ΦList(ℓ).

=

ΦEList(uNili ) = 0 = q ∗(2n −1) = ΦEList .

i ) = ΦEList(x) + ΦEList = q + (q + q) 2n−1 −1 = q ∗(1 + 2 ∗2n−1 −2)

= q + 2q ∗(2n−1 −1) = q ∗(2n −1).


<!-- page 31 -->

Liquid Resource Types 106:31

Claim. Let t :: LTree a ⟨q :: Nat⟩be a balanced tree storing n values (leaves). Then

ΦLT ree(t) = q ∗(n log2(n)) is a sound potential function.

Proof. Matching t to Leaf, we have

ΦLTree(uLeaf

Õ

i

Matching t to Node x l r, we have

ΦLTree(uNode

Õ

i

n

n

2 log2 n

h

i

q ∗

+ q ∗

= q ∗

= 2

A.4 Tree: Potential on path

data PTree a <p::a →Bool, q::Int> where

Leaf ::PTree a <p,q> Node ::x: aq

→l: PTree a <p,if (p x) then q else 0> →r: PTree a <p,if (p x) then 0 else q> →PTree a <p,q>

Claim. Let t :: PTree a ⟨p :: a →Bool,q :: Nat⟩. Then

ΦPTree(t) = q ∗|ℓ| is a sound potential function,

for the path ℓto leaf defined by the predicate p having length |ℓ|. Proof. Matching t to Leaf, we have

ΦPTree(uLeaf

Õ

i

Matching t on Node x l r, we have

Õ

ΦPTree(uNode

i

= q + (if p(x) then q else 0) ∗(|ℓ| −1) + (if p(x) then 0 else q) ∗(|ℓ| −1)

B FULL SPECIFICATION OF THE TYPE SYSTEM

B.1 Inductive Datatypes with Measures

In the full type system, an inductive datatype indθ

i ) = q = q ∗n log2(n) = ΦLTree(t).

i ) = ΦLTree(l) + ΦLTree(r) = ΦLTree(x) + 2 ∗ΦLTree(l)

n + n(log2(n) −1)

= q ∗n log2(n) = ΦLTree(t).

i ) = 0 = q ∗|ℓ| = ΦPTree(t).

i ) = ΦPTree(x) + ΦPTree(l) + ΦPTree(r)

= q + q ∗(|ℓ| −1) = q ∗|ℓ| = ΦPTree(t).

µ,◁,π (−−−−−−−→ C : (T,m)) consists of a sequence of constructors, each of which has a nameC, a content typeT (which must be a scalar type), and a finite number m ∈Z+

0 of child nodes. The parameter µ specifies a measure of the inductive datatype, which is a tuple of refinement-level functions, which is used to derive the interpretation ID for a datatype D. Intuitively, the j-th component of µ, written µ.j, should be a function of sort ∆Tj ⇒∆mj

D ⇒∆D where ∆Tj is the sort for refinements of the content type Tj, i.e., a function computes the measurement of a data structure Cj(v0, ⟨v1, · · · ,vmj ⟩) as µ.j(s0)(s1, · · · ,smj ), where s0 is the logical refinement of the content value v0, and s1, · · · ,smj are ∆D-sorted measurements of child nodes v1, · · · ,vmj .

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 32 -->

106:32 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

def= λ_ : U.λ_ : U.0, µCons

µNil

The first argument of both µNil and µCons reflects the corresponding content type. In addition, µCons has a second argument that represents the measurement of the child node i.e. the length of the tail list. We can now define INatList as

INatList(Nil(triv, ⟨⟩))

INatList(Cons(vh, ⟨vt⟩))

Inspired by Example B.1, for a general inductive datatype D with the form indµ(−−−−−−−→ C : (T,m)), we can inductively define a measure ID for values of this datatype using µ:

ID(Cj(a0, ⟨a1, · · · ,amj ⟩))

B.2 Sorting: Γ ⊢ψ ∈∆

Refinements are classified by sorts. The sorting judgment Γ ⊢ψ ∈∆states that a refinementψ has a sort ∆under a context Γ. The typing context is needed because refinements can reference program variables. Fig. 11 presents the sorting rules. To reflect types of program variables in the refinement level, we define a relation B ⇝∆as follows. The relation ⇝defines a total function from base types to sorts.

(K-Prod)

B1 ⇝∆1 B2 ⇝∆2 B1 × B2 ⇝∆1 × ∆2

(K-Nat)

(K-Bool)

(K-Unit)

nat ⇝N

bool ⇝B

unit ⇝U

(Sc-Bool)

(Sc-Nat)

(Sc-Unit)

B scalar

N scalar

U scalar

B.3 Type Wellformedness: Γ ⊢S type

A type S is said to be wellformed under a context Γ if the following three properties hold:

µ,◁,π (−−−−−−−→ C : (T,m)). Our metatheory does not impose a specific definition of well-definedness of inductive datatypes, but rather states that these types are consistent with their subtyping and sharing relation. For example, for subtyping, we want to ensure that if B = indθ

µ,◁,π (−−−−−−−→ C : (T,m)) <: indθ ′

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.

Example B.1 (Measures in the refinement language). Recall the length measure INatList for naturalnumber lists in Example 3.1. We want to redefine NatList as indµ(Nil : (unit, 0), Cons : (nat, 1)) with some proper µ such that INatList can be derived from µ. Indeed, we can define µ = (µNil, µCons) where

def= λh : N.λt : N.t + 1.

def= µNil(I(triv))(⋆) ≡0,

def= µCons(I(vh))(INatList(vt)) ≡INatList(vt) + 1.

def= µ.j(I(a0))(I(a1), · · · , I(amj )).

(K-Dtype)

µ,◁,π (−−−−−−−−→ C : (T , m))

(K-Tvar)

D = indθ

D ⇝∆D

m · α ⇝δα

We also define a relation ∆scalar to state a sort ∆is first-order as follows. The relation is used to define first-order quantifications.

(Sc-Prod)

(Sc-Tvar)

∆1 scalar ∆2 scalar

δα scalar

∆1 × ∆2 scalar

• every referenced program variables in S is in the correct scope, and • polymorphic types can never carry positive potential. Fig. 12 presents the type wellformedness rules. In the rule (Wf-Dtype), we make use of another judgment to check the well-definedness of datatypes D = indθ

µ,◁,π (−−−−−−−−→ C : (T ′,m)) = B′, then for every j, the shifted types for children


<!-- page 33 -->

Liquid Resource Types 106:33

Γ ⊢ψ ∈∆

(S-Var)

(S-Nat)

⊢Γ context Γ(x) ⇝∆

⊢Γ context

Γ ⊢n ∈N

Γ ⊢x ∈∆

(S-And)

(S-Rel)

Γ ⊢ψ1 ∈B Γ ⊢ψ2 ∈B

Γ ⊢ϕ1 ∈N Γ ⊢ϕ2 ∈N

Γ ⊢ψ1 ∧ψ2 ∈B

Γ ⊢ϕ1 ≤ϕ2 ∈B

(S-Eq)

(S-Lvar)

Γ ⊢ψ1 ∈∆ Γ ⊢ψ2 ∈∆ ∆scalar

Γ ⊢ψ1 = ψ2 ∈B

(S-App)

Γ ⊢ψ1 ∈∆⇒∆′ Γ ⊢ψ2 ∈∆

Γ ⊢ψ1 ψ2 ∈∆′

(S-Proj-Left)

(S-Proj-Right)

Γ ⊢ψ ∈∆1 × ∆2

Γ ⊢ψ ∈∆1 × ∆2

Γ ⊢ψ .1 ∈∆1

Γ ⊢ψ .2 ∈∆2

Γ ⊢S type

(Wf-Nat)

(Wf-Bool)

⊢Γ context

⊢Γ context

Γ ⊢nat type

Γ ⊢bool type

(Wf-Dtype)

Γ ⊢indµ,◁,π (−−−−−−−−→ C : (T , m)) indexed by ∆θ Γ ⊢θ ∈∆θ

µ,◁,π (−−−−−−−−→ C : (T , m)) type

Γ ⊢indθ

(Wf-Refined)

(Wf-Arrow)

Γ ⊢B type Γ, ν : B ⊢ψ ∈B

Γ ⊢Tx type Γ, x : Tx ⊢T type

Γ ⊢{B | ψ } type

Γ ⊢m · (x :Tx →T ) type

(Wf-Poly)

Γ, α ⊢S . S | S

Γ ⊢∀α .S type

(S-Triv)

(S-Top)

(S-Neg)

⊢Γ context

⊢Γ context

Γ ⊢ψ ∈B

Γ ⊢⋆∈U

Γ ⊢⊤∈B

Γ ⊢¬ψ ∈B

(S-Op)

Γ ⊢ϕ1 ∈N Γ ⊢ϕ2 ∈N

Γ ⊢ϕ1 + ϕ2 ∈N

(S-Abs)

Γ, a : ∆⊢ψ ∈∆′

⊢Γ context Γ(a) = ∆

Γ ⊢λa : ∆.ψ ∈∆⇒∆′

Γ ⊢a ∈∆

(S-Pair)

Γ ⊢ψ1 ∈∆1 Γ ⊢ψ2 ∈∆2 Γ ⊢(ψ1, ψ2) ∈∆1 × ∆2

(S-Forall)

Γ, a : ∆⊢ψ ∈B ∆scalar

Γ ⊢∀a : ∆.ψ ∈B

Fig. 11. Sorting rules

(Wf-Unit)

(Wf-Prod)

⊢Γ context

Γ ⊢B1 type Γ ⊢B2 type

Γ ⊢unit type

Γ ⊢B1 × B2 type

(Wf-Tvar)

⊢Γ context α ∈Γ

Γ ⊢m · α type

(Wf-Pot)

Γ ⊢R type Γ, ν : R ⊢ϕ ∈N

Γ ⊢Rϕ type

Fig. 12. Type well-formedness rules

nodes of the j-th constructor of B and B′ satisfy the subtyping relation accordingly. The reasoning on sharing follows the same scheme as subtyping. The rule (Dtype-Index) below formalizes the idea.

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 34 -->

106:34 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

(Dtype-Index)

mj D ⇒∆D ) Γ ⊢◁∈Îk

∀j :Tj ⇝∆Tj Γ ⊢µ ∈Îk

j=1(∆Tj ⇒∆

Γ<: = Γ, θ : ∆θ , θ′ : ∆θ , indθµ,◁,π (−−−−−−−−→ C : (T , m)) <: indθ′

◁j (y)(θ ).i µ,◁,π (−−−−−−−−→ C : (T , m)))

for each j, Γ<:, y : Tj ⊢(Îmj

i=1 ind

Γ. = Γ, θ : ∆θ , θ1 : ∆θ , θ2 : ∆θ , indθµ,◁,π (−−−−−−−−→ C : (T , m)) . indθ1 µ,◁,π (−−−−−−−−−→ C : (T1, m)) | indθ2 µ,◁,π (−−−−−−−−−→ C : (T2, m)) for each j,

π .j(y)(θ )

◁j (y)(θ ).i µ,◁,π (−−−−−−−−→ C : (T , m)))

Γ., y : Tj ⊢(Îmj

. (Îmj

i=1 ind

i=1 ind

Γ ⊢indµ,◁,π (−−−−−−−−→ C : (T , m)) indexed by ∆θ

def= ψ1 = ψ2

ψ1 ⊑B ψ2

def= ψ1 ≤ψ2

ψ1 ⊑N ψ2

def= ψ1 = ψ2

ψ1 ⊑U ψ2

def= ψ1 = ψ2

ψ1 ⊑δα ψ2

ψ1 ⊑∆1×∆2 ψ2

def= ∀a : ∆1.ψ1(a) ⊑∆2 ψ2(a)

ψ1 ⊑∆1⇒∆2 ψ2

def= ψ = ψ1 +ψ2

ψ = ψ1 ⊕N ψ2

ψ = ψ1 ⊕∆1×∆2 ψ2

ψ = ψ1 ⊕∆1⇒∆2 ψ2

def= ¬⊤

ψ = ψ1 ⊕_ ψ2

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.

mj θ ) Γ ⊢π ∈Îk

j=1(∆Tj ⇒∆θ ⇒N)

j=1(∆Tj ⇒∆θ ⇒∆

µ,◁,π (−−−−−−−−−→ C : (T ′, m))

π .j(y)(θ′)

◁j (y)(θ′).i µ,◁,π (−−−−−−−−−→ C : (T ′, m)))

π .j(y)(θ )

<: (Îmj

i=1 ind

π .j(y)(θ1)

π .j(y)(θ2)

◁j (y)(θ1).i µ,◁,π (−−−−−−−−−→ C : (T1, m)))

◁j (y)(θ2).i µ,◁,π (−−−−−−−−−→ C : (T2, m)))

| (Îmj

i=1 ind

Note that there are subtyping and sharing relations appearing in the antecedents of the judgments, which are not covered in our definition of typing contexts. In the metatheory, we "instantiate" the rule above with some properly designed subtyping and sharing relations that can be encoded in the refinement language. As shown in Fig. 10, we achieve this by introducing the partial-order ⊑∆ and sum ⊕∆operators as follows.

def= ψ1.1 ⊑∆1 ψ2.1 ∧ψ1.2 ⊑∆2 ψ2.2

def= (ψ.1 = ψ1.1 ⊕∆1 ψ2.1) ∧(ψ.2 = ψ1.2 ⊕∆2 ψ2.2)

def= ∀a : ∆1.ψ(a) = ψ1(a) ⊕∆2 ψ2(a)

B.4 Context Wellformedness: ⊢Γ context A context Γ is said to be wellformed if every binding in Γ is wellformed under a "prefix" context before it. Recall that the context is a sequence of variable bindings, type variables, path conditions, and free potentials. Fig. 13 shows these rules.

B.5 Context Sharing: ⊢Γ . Γ1 | Γ2 We have already presented type sharing rules. To apportion the associated potential of Γ properly to two contexts Γ1, Γ2 with the same sequence of bindings, we introduce context sharing relations. The rules are summarized in Fig. 14.


<!-- page 35 -->

Liquid Resource Types 106:35

(Wf-Bind-Type)

(Wf-Empty)

⊢Γ context Γ ⊢S type

⊢· context

⊢Γ, x : S context

(Wf-Bind-TVar)

⊢Γ context

⊢Γ, α context

Fig. 13. Context wellformedness rules

(Share-Bind-Type)

(Share-Empty)

⊢Γ . Γ1 | Γ2 Γ ⊢S . S1 | S2 ⊢Γ, x : S . Γ1, x : S1 | Γ2, x : S2

⊢· . · | ·

(Share-Bind-Cond)

(Share-Bind-TVar)

⊢Γ . Γ1 | Γ2 Γ ⊢ψ ∈B

⊢Γ . Γ1 | Γ2 ⊢Γ, α . Γ1, α | Γ2, α

⊢Γ, ψ . Γ1, ψ | Γ2, ψ

B.6 Total Free Potential: Φ(Γ)

The free potentials of a context Γ, written Φ(Γ), include all the potential bindings, as well as outermost annotated potentials of variable bindings.

Φ(Γ,x : (m · (y :Ty →T))ϕ) = Φ(Γ) + ϕ Φ(Γ,ϕ) = Φ(Γ) + ϕ

B.7 Type Substitution: [{B | ψ }ϕ/α]S In Re2, type substitution is restricted to resource-annotated subset types. The substitution [{B | ψ }ϕ/α]S should take care of logical refinements and potential annotations from both S and {B | ψ }ϕ. Following gives the definition.

[U /α]unit = unit

[U /α]nat = nat

[U /α]bool = bool

[U /α](B1 × B2) = {B′

where [U /α]B1 = {B′

µ,◁,π (−−−−−−−→ C : (T,m)) = indθ

[U /α]indθ

[U /α]m · β = m · β

[{B | ψ }ϕ/α]m · α = {m × B | ψ }m×ϕ

[U /α]{B | ψ } = {B′ | ψ ∧ψ ′}ϕ′

(Wf-Bind-Sort)

(Wf-Bind-Cond)

⊢Γ context

⊢Γ context Γ ⊢ψ ∈B

⊢Γ, a : ∆context

⊢Γ, ψ context

(Wf-Bind-Pot)

⊢Γ context Γ ⊢ϕ ∈N

⊢Γ, ϕ context

(Share-Bind-Sort)

⊢Γ . Γ1 | Γ2 ⊢Γ, a : ∆. Γ1, a : ∆| Γ2, a : ∆

(Share-Bind-Pot)

⊢Γ . Γ1 | Γ2 Γ |= ϕ = ϕ1 + ϕ2 ⊢Γ, ϕ . Γ1, ϕ1 | Γ2, ϕ2

Fig. 14. Context sharing rules

Φ(·) = 0 Φ(Γ, α) = Φ(Γ)

Φ(Γ,x : {B | ψ }ϕ) = Φ(Γ) + [x/ν]ϕ Φ(Γ,ψ) = Φ(Γ)

Φ(Γ,x : ∀α.S) = Φ(Γ) Φ(Γ,a : ∆) = Φ(Γ)

1 × B′ 2 | [ν.1/ν]ψ1 ∧[ν.2/ν]ψ2}[ν .1/ν]ϕ1+[ν .2/ν]ϕ2

1 | ψ1}ϕ1, [U /α]B2 = {B′ 2 | ψ2}ϕ2

µ,◁,π (−−−−−−−−−−−−−→ C : ([U /α]T,m))

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 36 -->

106:36 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

where [U /α]B = {B′ | ψ ′}ϕ′

[U /α]m · (x :Tx →T) = m · (x : [U /α]Tx →[U /α]T)

[U /α]Rϕ = R′ϕ+ϕ′

where [U /α]R = R′ϕ′

[U /α]∀β.S = ∀β.[U /α]S

Type multiplication is defined as follows.

m × bool = bool

m × nat = nat

m × unit = unit

µ,◁,π (−−−−−−−→ C : (T,k)) = indm×θ

m × indθ

m1 × (m2 · α) = (m1 · m2) · α

m1 × (m2 · (x :Tx →T)) = (m1 · m2) · (x :Tx →T)

Semantics of Sorts. A sort ∆represents a set L∆M of ∆-sorted refinements. The following gives the definition of L∆M. Note that we only define the semantics for sorts that do not contain uninterpreted sorts. We denote such sorts by ∆o.

LBM = {⊤, ⊥}

LNM = Z+

## 0 LUM = {⋆}

L∆1 ⇒∆2M = L∆1M →L∆2M

TE(unit) = U

TE(bool) = B

TE(nat) = N

TE(B1 × B2) = TE(B1) × TE(B2)

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.

m × (B1 × B2) = (m × B1) × (m × B2)

µ,◁,π (−−−−−−−−−−−→ C : (m ×T,k))

m × {B | ψ } = {m × B | ψ }

m × Rϕ = (m × R)m×ϕ

B.8 Validity Checking In this section, we define the validity checking judgment Γ |= ψ where Γ is a wellformed context and ψ is a Boolean-sorted refinement, following the approach of Re2 [Knoth et al. 2019]. Intuitively, the judgment states that the formula ψ is always true under any instance of Γ. Our approach is to define a set-based denotational semantics for refinements and then reduce the validity checking to Presburger arithmetic.

L∆1 × ∆2M = {(ψ1,ψ2) : ψ1 ∈L∆1M ∧ψ2 ∈L∆2M}

Semantics of Types. As we have already done in the sorting rules, scalar types are reflected in the refinement level. To interpret a wellformed scalar type as a sort without uninterpreted sorts, we define a transformation TE(·) from types to sorts, parametrized by an environment that resolves uninterpreted sorts δα.


<!-- page 37 -->

Liquid Resource Types 106:37

TE(D) = ∆D where D = indθ

TE(m · α) = E(δα)

Semantics of Contexts. To give a meaning to a context Γ, we need to assign an instance for each variable binding with a scalar type, as well as type variables. Intuitively, a context Γ represents a set of environments that resolves both program variables and uninterpreted sorts. Making use of semantics for sorts and types defined above, we can define LΓM inductively as follows.

L·M = {∅}

LΓ,x : (m · (y :Ty →T))ϕM = LΓM

LΓ,x : ∀α.SM = LΓM

LΓ,ψM = LΓM

LΓ,ϕM = LΓM

JxK(E) = E(x)

JaK(E) = E(a)

JnK(E) = n

J⋆K(E) = ⋆

J⊤K(E) = ⊤

J¬ψK(E) = ¬JψK(E)

Jψ1 ∧ψ2K(E) = Jψ1K(E) ∧Jψ2K(E)

JnK(E) = n

Jϕ1 ≤ϕ2K(E) = Jϕ1K(E) ≤Jϕ2K(E)

Jϕ1 + ϕ2K(E) = Jϕ1K(E) + Jϕ2K(E)

Jψ1 = ψ2K(E) = Jψ1K(E) = Jψ2K(E)

Jλa : ∆.ψK(E) = f where f (ϕ)

Jψ1 ψ2K(E) = Jψ1K(E)(Jψ2K(E))

J(ψ1,ψ2)K(E) = (Jψ1K(E), Jψ2K(E))

Jψ.1K(E) = let (ψl,ψr) = JψK(E) in ψl Jψ.2K(E) = let (ψl,ψr) = JψK(E) in ψr

µ,◁,π (−−−−−−−→ C : (T,m))

LΓ,x : {B | ψ }ϕM = {E[x 7→ψ] : E ∈LΓM ∧ψ ∈LTE(B)M}

LΓ,a : ∆M = {E[a 7→ψ] : E ∈LΓM ∧ψ ∈L∆M}

LΓ, αM = {E[δα 7→∆] | E ∈LΓM ∧∆∈∆o}

Semantics of Refinements. The meaning of a refinement ψ is defined with respect to its sorting judgment Γ ⊢ψ ∈∆. The following defines an evaluation map JψK : LΓM →L∆M, by induction on the derivation of the sorting judgment, or essentially structural induction on ψ.

J∀a : ∆.ψK(E) = ∀ϕ.ϕ ∈L∆M =⇒JψK(E[a 7→ϕ])

def= JψK(E[a 7→ϕ])

Validity Checking. Now we show how to assign meanings to contexts and refinements, then the last step to define Γ |= ψ is to collect all the refinement constraints mentioned in Γ.

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 38 -->

106:38 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

BΓ(x : (m · (y :Ty →T))ϕ) = ⊤

B(Γ,x : (m · (y :Ty →T))ϕ) = B(Γ)

Now we can define the validity checking judgment Γ |= ψ.

Γ |= ψ

Further, we can embed our denotational semantics for refinements in Presburger arithmetic, so we can also write the validity checking as the following formula

∀E ∈LΓM: E |= B(Γ) =⇒ψ,

where |= is interpreted in Presburger arithmetic.

B.9 Definition of Consistency To describe soundness of our type system, we will need a notion of consistency. Basically, given a typing judgment Γ ⊢v :: S of a value, we want to know that under the context Γ, v satisfies the logical conditions indicated by S, as well as Γ has sufficient amount of potential to be stored in v with respect to S.

IV (Γ,x : (m · (y :Ty →T))ϕ) = IV (Γ)

IV (Γ,x : ∀α.S) = IV (Γ)

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.

We first define how to extract constraints from a type binding. Note that only scalar types (i.e., subset types) can carry logical refinements.

BΓ(x : {B | ψ }ϕ) = [x/ν]ψ

BΓ(x : ∀α.S) = ⊤

Then we define B(Γ) to collect all the constraints from variable bindings and path conditions in Γ. It is defined inductively on Γ.

B(·) = ⊤

B(Γ,x : S) = B(Γ) ∧BΓ(x : S)

B(Γ,x : ∆) = B(Γ)

B(Γ, α) = B(Γ)

B(Γ,ψ) = B(Γ) ∧ψ

B(Γ,ϕ) = B(Γ)

def= ∀E ∈LΓM: JB(Γ) =⇒ψK(E)

We use I(·) to transform a value stack V to a refinement environment E with respect to a context Γ. The stack V maps type variables to concrete types, program variables to values, and index variables to refinements. The environment E is used to define validity checking in former sections. The following defines the transformation IV (Γ) by induction on Γ.

IV (·) = ∅

IV (Γ,x : {B | ψ }ϕ) = IV (Γ)[x 7→I(V (x))]

IV (Γ,a : ∆) = IV (Γ)[a 7→V (a)]

IV (Γ, α) = let E = IV (Γ) in

E[δα 7→TE(V (α))]

IV (Γ,ψ) = IV (Γ)

IV (Γ,ϕ) = IV (Γ)


<!-- page 39 -->

Liquid Resource Types 106:39

ΨV (pair(v1,v2) : {B1 × B2 | ψ }ϕ) = [I(pair(v1,v2))/ν]ψ

µ,◁,π (−−−−−−−→ C : (T,m)) | ψ }

ΨV (Cj(v0, ⟨v1, · · · ,vmj ⟩) : {indθ

ΨV (v : (m · (x :Tx →T))ϕ) = ⊤

The following defines how to collect path conditions of a stack V with respect to its typing context Γ, written ΨV (Γ).

ΨV (·) = ⊤

ΨV (Γ,a : ∆) = ΨV (Γ)

ΨV (Γ,x : (m · (y :Ty →T))ϕ) = ΨV (Γ)

ΨV (Γ,x : ∀α.S) = ΨV (Γ)

ΨV (Γ, α) = ΨV (Γ)

ΨV (Γ,ϕ) = ΨV (Γ)

ΦV (pair(v1,v2) : {B1 × B2 | ψ }ϕ) = [I(pair(v1,v2))/ν]ϕ

µ,◁,π (−−−−−−−→ C : (T,m)) | ψ }

ΦV (Cj(v0, ⟨v1, · · · ,vmj ⟩) : {indθ

Now we define how to extract constraints from a value with respect to its type. It is similar to how we extract constraints from a typing binding in the refinement level. The differences are that (i) we need to use the interpretation I(·) to map values to refinements, (ii) we need to take care of list elements and pair components, (iii) we need to substitute type variables with concrete types, and (iv) for polymorphic type schemas, we assert that the constraints hold for all instantiations.

ΨV (b : {bool | ψ }ϕ) = [I(b)/ν]ψ

ΨV (u : {unit | ψ }ϕ) = [I(u)/ν]ψ

ΨV (n : {nat | ψ }ϕ) = [I(n)/ν]ψ

ϕ

) = [I(Cj(v0, ⟨v1, · · · ,vmj ⟩))/ν]ψ ∧ΨV (v0 : Tj)

mj Û

µ,◁,π (−−−−−−−→ C : (T,m)))

ΨV (vj : ind◁.j(I(v0))(θ).i

∧

i=1

ΨV (v : {m · α | ψ }ϕ) = ΨV (v : [V (α)/α]{m · α | ψ })

ΨV (v : ∀α.S) = ∀{B | ψ }ϕ : ΨV ′(v : S)

where Γ ⊢{B | ψ }ϕ type

and V ′ = V [α 7→{B | ψ }ϕ]

ΨV (Γ,x : {B | ψ }ϕ) = ΨV (Γ) ∧ΨV (V (x) : {B | ψ }ϕ)

ΨV (Γ,ψ) = ΨV (Γ) ∧ψ

Similar to logical refinements, we can also collect potential annotations. The following defines ΦV (v : S) as the potential stored in the value v with respect to the type S under the stack V .

ΦV (b : {bool | ψ }ϕ) = [I(b)/ν]ϕ

ΦV (u : {unit | ψ }ϕ) = [I(u)/ν]ϕ

ΦV (n : {nat | ψ }ϕ) = [I(n)/ν]ϕ

ϕ

) = [I(Cj(v0, ⟨v1, · · · ,vmj ⟩))/ν]ϕ + ΦV (v0 : Tj)

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 40 -->

106:40 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

ΦV (v : (m · (x :Tx →T))ϕ) = ϕ

Also we have a stack version for potentials ΦV (Γ).

ΦV (·) = 0

ΦV (Γ,a : ∆) = ΦV (Γ)

ΦV (Γ,x : (m · (y :Ty →T))ϕ) = ΦV (Γ) + ϕ

ΦV (Γ,x : ∀α.S) = ΦV (Γ)

ΦV (Γ, α) = ΦV (Γ)

ΦV (Γ,ψ) = ΦV (Γ)

Finally, we are able to define two notions of consistency for values and stacks, respectively.

Definition B.3 (Stack consistency). An environment V ′ is said to be consistent with Γ ⊢V ′ :: Γ′, if for for all · ⊢V :: Γ, E = IV (Γ) such that E |= ΨV (Γ), we have E′ |= ΨV ,V ′(Γ′) ∧ΦV (Γ) ≥ΦV ,V ′(Γ′)

where E′ def= IV ,V ′(Γ, Γ′).

C PROOFS FOR SOUNDNESS

C.1 Progress

is consistent with Γ2 ⊢⟨v1, · · · ,vmj ⟩: Îmj

µ,◁,π (−−−−−−−→ C : (T,m)) | ν = I(Cj(v0, ⟨v1, · · · ,vmj ⟩))}.

is consistent with Γ, π.j(I(v0))(θ) ⊢ Cj(v0, ⟨v1, · · · ,vmj ⟩) :: {indθ

Proof.

Fix · ⊢V :: Γ, E = IV (Γ) s.t. E |= ΨV (Γ)

=⇒ΦV (Γ) = ΦV (Γ1) + ΦV (Γ2) 6

Γ1 ⊢v0 :: Tj consistent [premise] 7

µ,◁,π (−−−−−−−→ C : (T,m)) | ν = I(⟨· · · ⟩)} consistent [premise] 8

i=1 ind◁.j(I(v0))(θ).i

Γ2 ⊢⟨v1, · · · ,vmj ⟩:: {Îmj

Γ ⊢Cj(v0, ⟨v1, · · · ,vmj ⟩) : indθ

Γ ⊢Cj(v0, ⟨v1, · · · ,vmj ⟩) :: {indθ

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.

+ π.j(I(v0))(θ)

mj Õ

µ,◁,π (−−−−−−−→ C : (T,m)))

ΦV (vj : ind◁.j(I(v0)).i

+

i=1

ΦV (v : {m · α | ψ }ϕ) = ΦV (v : [V (α)/α](m · α)ϕ)

ΦV (v : ∀α.S) = 0

ΦV (Γ,x : {B | ψ }ϕ) = ΦV (Γ) + ΦV (V (x) : {B | ψ }ϕ)

ΦV (Γ,ϕ) = ΦV (Γ) + ϕ

Definition B.2 (Value consistency). A value v ∈Val is said to be consistent with Γ ⊢v :: S, if for all · ⊢V :: Γ, E = IV (Γ) such that E |= ΨV (Γ), we have E |= ΨV (v : S) ∧ΦV (Γ) ≥ΦV (v : S).

Lemma C.1. Let Γ = q | α. If ⊢Γ . Γ1 | Γ2, v0 is consistent with Γ1 ⊢v0 :: Tj, and ⟨v1, · · · ,vmj ⟩

µ,◁,π (−−−−−−−→ C : (T,m)), then Cj(v0, ⟨v1, · · · ,vmj ⟩)

i=1 ind◁.j(I(v0))(θ).i

⊢Γ . Γ1 | Γ2 [premise]

µ,◁,π (−−−−−−−→ C : (T,m)) [typing]

µ,◁,π (−−−−−−−→ C : (T,m)) | ν = I(Cj(v0, ⟨· · · ⟩))} [typing]


<!-- page 41 -->

Liquid Resource Types 106:41

ΨV (Cj(v0, ⟨v1, · · · ,vmj ⟩) : {indθ

= [I(Cj(v0, ⟨· · · ⟩))/ν](ν = I(Cj(v0, ⟨· · · ⟩))∧

mj Û

µ,◁,π (−−−−−−−→ C : (T,m)))

ΨV (vi : ind◁.j(I(v0))(θ).i

ΨV (v0 : Tj) ∧

i=1

mj Û

µ,◁,π (−−−−−−−→ C : (T,m)))

ΨV (vi : ind◁.j(I(v0))(θ).i

= ΨV (v0 : Tj) ∧

i=1

µ,◁,π (−−−−−−−→ C : (T,m))

ΦV (Cj(v0, ⟨v1, · · · ,vmj ⟩) : indθ

mj Õ

ΦV (v0 : Tj) + π.j(I(v0))(θ) +

i=1

mj Õ

= ΦV (v0 : Tj) + π.j(I(v0))(θ) +

i=1

E |= ΨV (v0 : Tj) ∧ΦV (Γ1) ≥ΦV (v0 : Tj) [C.1]

mj Û

µ,◁,π (−−−−−−−→ C : (T,m)))∧

ΨV (vj : ind◁.j(I(v0))(θ).i

E |=

i=1

mj Õ

ΦV (Γ2) + π.j(I(v0))(θ) ≥π.j(I(v0))(θ) +

i=1

done [C.1]

Proposition C.2. If ⟨e,p⟩7→⟨e′,p′⟩and c ≥0, then ⟨e,p + c⟩7→⟨e′,p′ + c⟩.

Proposition C.3. If v ∈Val, Γ ⊢v :: T1, Γ ⊢T1 <: T2, · ⊢V :: Γ and E = IV (Γ) such that E |= ΨV (Γ), then E |= ΨV (v : T1) =⇒(ΨV (v : T2) ∧ΦV (v : T1) ≥ΦV (v : T2)).

Proof. By induction on Γ ⊢v :: T1, followed by an induction on atomic typing.

inversion on Γ ⊢B1 <: B2, we know that B2 = indθ ′

Γ ⊢−→ T <: −→ T ′. By (Dtype-Index), for all i, we know that

Γ<:,y : Tj ⊢ind◁j(y)(θ).i

where

Γ<:

and Γ<:,y : Tj |= π.j(y)(θ) ≥π.j(y)(θ ′). By the substitution lemma, we have

µ,◁,π (−−−−−−−→ C : (T,m)) <: ind◁.j(I(v0))(θ ′).i

Γ ⊢ind◁.j(I(v0))(θ).i

µ,◁,π (−−−−−−−→ C : (T,m)) | ν = I(Cj(v0, ⟨· · · ⟩))})

0 ) = 0+

µ,◁,π (−−−−−−−→ C : (T,m)))

ΦV (vj : ind◁.j(I(v0))(θ).i

µ,◁,π (−−−−−−−→ C : (T,m)))

ΦV (vj : ind◁.j(I(v0))(θ).i

µ,◁,π (−−−−−−−→ C : (T,m))

0 ) [C.1]

ΦV (vj : ind◁.j(I(v0))(θ).i

□

Proof. By induction on ⟨e,p⟩7→⟨e′,p′⟩. □

• (SimpAtom-ConsD): Letv = Cj(v0, ⟨v1, · · · ,vmj ⟩) for some j and B1 = indθ µ,◁,π (−−−−−−−→ C : (T,m)). By

µ,◁,π (−−−−−−−−→ C : (T ′,m)) for some θ ′, −→ T ′ satisfying

µ,◁,π (−−−−−−−−→ C : (T ′,m))

µ,◁,π (−−−−−−−→ C : (T,m)) <: ind◁j(y)(θ ′).i

def= Γ,θ : ∆θ,θ ′ : ∆θ, B1 <: B2,

µ,◁,π (−−−−−−−−→ C : (T ′,m)),

and Γ |= π.j(I(v0))(θ) ≥π.j(I(v0))(θ ′). Thus, by induction hypothesis, for all i, we know that E |= ΦV (vi : ind◁.j(I(v0))(θ).i

µ,◁,π (−−−−−−−−→ C : (T ′,m))). By an inner induction on Γ1 ⊢v0 :: Tj, we obtain that E |= ΦV (v0 : Tj) ≥ΦV (v0 : T ′

µ,◁,π (−−−−−−−→ C : (T,m))) ≥ΦV (vi : ind◁.j(I(v0))(θ ′).i

j ).

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 42 -->

106:42 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

By the definition of potential, we have

ΦV (v : B1) = ΦV (v0 : Tj) + π.j(I(v0))(θ) +

ΦV (v : B2) = ΦV (v0 : T ′

j ) + π.j(I(v0))(θ ′) +

and conclude E |= ΦV (v : B1) ≥ΦV (v : B2) by the inequalities derived above.

Proposition C.4. If v ∈Val, Γ ⊢v :: S, Γ ⊢S . S1 | S2, · ⊢V :: Γ and E = IV (Γ) such that E |= ΨV (Γ), then E |= ΦV (v : S) = ΦV (v : S1) + ΦV (v : S2).

Proof. By induction on Γ ⊢v :: T1, followed by an induction on atomic typing.

By inversion on Γ ⊢B . B1 | B2, we know that B1 = indθ1

indθ2

µ,◁,π (−−−−−−−→ C : (T,m)) . ind◁.j(y)(θ1).i

Γ.,y : Tj ⊢ind◁.j(y)(θ).i

where

Γ.

µ,◁,π (−−−−−−−→ C : (T,m)) . ind◁j(I(v0))(θ1).i

Γ ⊢ind◁j(I(v0))(θ).i

we know that E |= ΦV (vi : ind◁j(I(v0))(θ).i

ΦV (vi : ind◁j(I(v0))(θ2).i

ΦV (v : B) = ΦV (v0 : Tj) + π.j(I(v0))(θ) +

ΦV (v : B1) = ΦV (v0 : T1j) + π.j(I(v0))(θ1) +

ΦV (v : B2) = ΦV (v0 : T2j) + π.j(I(v0))(θ2) +

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.

mj Õ

µ,◁,π (−−−−−−−→ C : (T,m))),

ΦV (vi : ind◁.j(I(v0))(θ).i

i=1

mj Õ

µ,◁,π (−−−−−−−−→ C : (T ′,m))),

ΦV (vi : ind◁.j(I(v0))(θ ′).i

i=1

□

• (SimpAtom-ConsD): Let v = Cj(v0, ⟨v1, · · · ,vmj ⟩) for some j and B = indθ µ,◁,π (−−−−−−−→ C : (T,m)).

µ,◁,π (−−−−−−−−→ C : (T1,m)), B2 =

µ,◁,π (−−−−−−−−→ C : (T2,m)) for some θ1,θ2, −→ T1, −→ T2 satisfying Γ ⊢−→ T . −→ T1 | −→ T2. By (Dtype-Index), for all i, we know that

µ,◁,π (−−−−−−−−→ C : (T1,m)) | ind◁.j(y)(θ2).i

µ,◁,π (−−−−−−−−→ C : (T2,m))

def= Γ,θ : ∆θ,θ1 : ∆θ,θ2 : ∆θ, B . B1 | B2,

and Γ.,y : Tj |= π.j(y)(θ) = π.j(y)(θ1) + π.j(y)(θ2). By the substitution lemma, we have

µ,◁,π (−−−−−−−−→ C : (T2,m)) and Γ |= π.j(I(v0))(θ) = π.j(I(v0))(θ1) + π.j(I(v0))(θ2). Thus, by induction hypothesis, for all i,

µ,◁,π (−−−−−−−−→ C : (T1,m)) | ind◁j(I(v0))(θ2).i

µ,◁,π (−−−−−−−→ C : (T,m))) = ΦV (vi : ind◁j(I(v0))(θ1).i

µ,◁,π (−−−−−−−−→ C : (T1,m)))+

µ,◁,π (−−−−−−−−→ C : (T2,m))). By an inner induction on Γ1 ⊢v0 :: Tj, we obtain that ΦV (v0 : Tj) = ΦV (v0 : T1j) + ΦV (v0 : T2j). By the definition of potential, we have

mj Õ

µ,◁,π (−−−−−−−→ C : (T,m))),

ΦV (vi : ind◁j(I(v0))(θ).i

i=1

mj Õ

µ,◁,π (−−−−−−−−→ C : (T1,m))),

ΦV (vi : ind◁j(I(v0))(θ1).i

i=1

mj Õ

µ,◁,π (−−−−−−−−→ C : (T2,m))),

ΦV (vi : ind◁j(I(v0))(θ2).i

i=1

and conclude E |= ΦV (v : B) = ΦV (v : B1) + ΦV (v : B2) by the equalities derived above.

□

Lemma C.5. If Γ = q | α, Γ ⊢a : B, · ⊢V :: Γ and p ≥ΦV (Γ), then a ∈Val and a is consistent with Γ ⊢a :: {B | ν = I(a)}.


<!-- page 43 -->

Liquid Resource Types 106:43

Proof. By induction on Γ ⊢a : B:

(SimpAtom-True)

SPS a = true, B = bool

true ∈Val [value]

ΨV (true : {bool | ν = I(true)})

= [I(true)/ν](ν = I(true)) = ⊤

ΦV (true : bool0) = 0 ≤ΦV (Γ)

(SimpAtom-False)

SPS a = false, B = bool

false ∈Val [value]

ΨV (false : {bool | ν = I(false)})

= [I(false)/ν](ν = I(false)) = ⊤

ΦV (false : bool0) = 0 ≤ΦV (Γ)

(SimpAtom-Pair)

SPS a = pair(a1,a2), B = B1 × B2

⊢Γ . Γ1 | Γ2 [premise] 9

Γ1 ⊢a1 : B1 [premise] 10

Γ2 ⊢a2 : B2 [premise] 11

a1 ∈Val,a1 consistent [ind. hyp., C.1] 12

a2 ∈Val,a2 consistent [ind. hyp., C.1] 13

pair(a1,a2) ∈Val [value]

ΨV (pair(a1,a2) : {B1 × B2 | ν = I(pair(a1,a2)})

= [I(pair(a1,a2))/ν](ν = I(pair(a1,a2))) = ⊤

ΦV (pair(a1,a2) : (B1 × B2)0)

(SimpAtom-ConsD)

SPS a = Cj(ˆa0, ⟨a1, · · · ,amj ⟩), B = indθ

Γ contains no variables =⇒a0 ∈Val 14

Γ1 ⊢a0 :: Tj [premise] 15

µ,◁,π (−−−−−−−→ C : (T,m)) [premise] 16

i=1 ind◁.j(I(a0))(θ).i

Γ2 ⊢⟨a1, · · · ,amj ⟩: Îmj

a0 consistent [Thm. C.6, C.1, C.1]

∀j : aj ∈Val,aj consistent [ind. hyp., C.1]

Cj(a0, ⟨a1, · · · ,amj ⟩) ∈Val [value]

Cj(a0, ⟨a1, · · · ,amj ⟩) consistent [Lem. C.1]

= ΦV (a1 : B1) + ΦV (a2 : B2) ≤ΦV (Γ1) + ΦV (Γ2) = ΦV (Γ) [C.1,C.1,C.1]

µ,◁,π (−−−−−−−→ C : (T,m))

Γ = Γ′, π.j(I(a0))(θ), ⊢Γ′ . Γ1 | Γ2 [premise]

□

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 44 -->

106:44 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

Proof. By induction on Γ ⊢e :: S:

(T-SimpAtom)

SPS e = a,S = {B | ν = I(a)}

a ∈Val,a consistent [Lem. C.5]

(T-Imp)

SPS e = impossible,S = T

⊤=⇒⊥ exfalso

(T-Consume-P)

SPS Γ = (Γ′,c),e = tick(c,e0),c ≥0

p ≥ΦV (Γ) = ΦV (Γ′) + c ≥c

(T-Consume-N)

SPS e = tick(c,e0),c < 0

(T-Cond)

SPS e = if(a0,e1,e2),S = T

Γ ⊢a0 : bool [premise] 17

a0 ∈Val [Lem. C.5] 18

inv. on C.1 with C.1

case a0 = true

case a0 = false

(T-MatP)

SPS e = matp(a0,x1.x2.e1),S = T

Γ1 ⊢a0 : B1 × B2 [premise] 19

a0 ∈Val [Lem. C.5] 20

inv. on C.1 with C.1

a0 = pair(v1,v2), Γ11 ⊢v1 : B1, Γ12 ⊢v2 : B2, ⊢Γ1 . Γ11 | Γ12

(T-MatD)

SPS e = matd(a0, −−−−−−−−−−−−−−−−−−−−−→ Cj(x0, ⟨x1, · · · ,xmj ⟩).ej),S = T

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.

Theorem C.6 (Progress). If Γ = q | α, Γ ⊢e :: S, · ⊢V :: Γ and p ≥ΦV (Γ), then either e ∈Val and e is consistent with Γ ⊢e :: S, or there exist e′ and p′ such that ⟨e,p⟩7→⟨e′,p′⟩.

Γ |= ⊥ [premise]

⟨e,p⟩7→⟨e0,p −c⟩ [eval.]

⟨e,p⟩7→⟨e0,p −c⟩ [eval.]

⟨e,p⟩7→⟨e1,p⟩ [eval.]

⟨e,p⟩7→⟨e2,p⟩ [eval.]

⊢Γ . Γ1 | Γ2 [premise]

⟨e,p⟩7→⟨[v1,v2/x1,x2]e1,p⟩ [eval.]


<!-- page 45 -->

Liquid Resource Types 106:45

µ,◁,π (−−−−−−−→ C : (T,m)) [premise] 21

Γ1 ⊢a0 : indθ

a0 ∈Val [Lem. C.5] 22

inv. on C.1 with C.1

case a0 = Cj(v0, ⟨v1, · · · ,vmj ⟩)

(T-Let)

SPS e = let(e1,x.e2),S = T2

=⇒ΦV (Γ) = ΦV (Γ1) + ΦV (Γ2) 23

Γ1 ⊢e1 :: S1 [premise] 24

p ≥ΦV (Γ1) [asm., C.1] 25

ind. hyp. on C.1 with C.1

case ⟨e1,p⟩7→⟨e′

1,p′⟩

⟨e,p⟩7→⟨let(e′

case e1 ∈Val

(T-App)

SPS e = app(ˆa1, ˆa2),S = T

Γ1 ⊢ˆa1 :: 1 · (x :Tx →T) [premise] 26

Γ contains no variables

=⇒ˆa1, ˆa2 ∈Val 27

inv. on C.1 with C.1

case e1 = λ(x.e0)

case e2 = fix(f .x.e0)

(T-App-SimpAtom)

SPS e = app(ˆa1,a2),S = [I(a2)/x]T

Γ1 ⊢ˆa1 :: 1 · (x : {B | ψ }ϕ →T) [premise] 28

Γ contains no variables

=⇒ˆa1 ∈Val 29

a2 ∈Val [Lem. C.5]

⊢Γ . Γ1 | Γ2 [premise]

⟨e,p⟩7→⟨[v0,v1, · · · ,vmj /x0,x1, · · · ,xmj ]ej,p⟩ [eval.]

⊢Γ . Γ1 | Γ2 [premise]

1,x.e2),p′⟩ [eval.]

⟨e,p⟩7→⟨[e1/x]e2,p⟩ [eval.]

⊢Γ . Γ1 | Γ2 [premise]

Γ2 ⊢ˆa2 :: Tx [premise]

⟨e,p⟩7→⟨[ˆa2/x]e0,p⟩ [eval.]

⟨e,p⟩7→⟨[fix(f .x.e0), ˆa2/f ,x]e0,p⟩ [eval.]

⊢Γ . Γ1 | Γ2 [premise]

Γ2 ⊢ˆa2 :: {B | ψ }ϕ [premise]

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 46 -->

106:46 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

inv. on C.1 with C.1

case e1 = λ(x.e0)

case e2 = fix(f .x.e0)

(T-Abs)

SPS e = λ(x.e0),S = x :Tx →T

λ(x.e0) ∈Val [value]

ΨV (λ(x.e0) : x :Tx →T) = ⊤

ΦV (λ(x.e0) : (x :Tx →T)0) = 0 ≤ΦV (Γ)

(T-Abs-Lin)

SPS Γ = m · Γ′,e = λ(x.e0),S = m · (x :Tx →T)

λ(x.e0) ∈Val [value]

ΨV (λ(x.e0) : m · (x :Tx →T)) = ⊤

ΦV (λ(x.e0) : (m · (x :Tx →T))0) = 0 ≤ΦV (Γ)

(T-Fix)

SPS e = fix(f .x.e0),S = ∀−→α .x :Tx →T

fix(f .x.e0) ∈Val [value]

ΨV (fix(f .x.e0) : S) = ⊤

ΦV (fix(f .x.e0) : S) = 0 ≤ΦV (Γ)

(S-Gen)

SPS e = v,S = ∀β.S′

Γ, β ⊢v :: S′ [premise] 30

v ∈Val [premise]

ΦV (v : ∀β.S′) = 0 ≤ΦV (Γ)

for all Γ ⊢{B | ψ }ϕ type

let V ′ = V [β 7→{B | ψ }ϕ]

ΦV ′(Γ, β) = ΦV (Γ)

ind. hyp. on C.1 with p ≥ΦV ′(Γ, β)

case ⟨v,p⟩7→⟨e′,p′⟩

contradict v ∈Val

case v ∈Val

=⇒ΨV (v : ∀β.S′) = ⊤

(S-Inst)

SPS S = [{B | ψ }ϕ/α ′]S′

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.

⟨e,p⟩7→⟨[a2/x]e0,p⟩ [eval.]

⟨e,p⟩7→⟨[fix(f .x.e0),a2/f ,x]e0,p⟩ [eval.]

Γ, f : S, −→α ,x : Tx ⊢e0 :: T [premise]

ΨV ′(v : S′) = ⊤ [ind. hyp.]


<!-- page 47 -->

Liquid Resource Types 106:47

Γ ⊢e :: ∀α ′.S′ [premise] 31

ind. hyp. on C.1 with p ≥ΦV (Γ)

case ⟨e,p⟩7→⟨e′,p′⟩

done

case e ∈Val

ΨV [α ′7→{B |ψ }ϕ](e : S′) = ⊤

ΨV (e : [{B | ψ }ϕ/α ′]S′) = ⊤

ΦV (e : [{B | ψ }ϕ/α ′]S′) = 0

ΦV (e : [{B | ψ }ϕ/α ′]S′) ≤ΦV (Γ)

(S-Subtype)

SPS S = T2 Γ ⊢e :: T1 [premise] 32

Γ ⊢T1 <: T2 [premise] 33

ind. hyp. on C.1 with p ≥ΦV (Γ)

case ⟨e,p⟩7→⟨e′,p′⟩

done

case e ∈Val

ΨV (e : T2) = ⊤

ΦV (e : T2) ≤ΦV (Γ)

(S-Transfer)

Γ′ ⊢e :: S [premise] 34

Γ′ = q′ | α ∧ΦV (Γ) = ΦV (Γ′) 35

p ≥ΦV (Γ′) 36

ind. hyp. on C.1 with C.1

case ⟨e,p⟩7→⟨e′,p′⟩

done

case e ∈Val

ΨV (e : ∀α ′.S′) = ⊤ [ind. hyp.]

Γ, α ′ ⊢S′ . S′ | S′ [wellformed.]

ΦV [α ′7→{B |ψ }ϕ](e : S′) = 0 [Prop. C.4]

ΨV (e : T1) = ⊤ [ind. hyp.]

ΨV (e : T1) =⇒ΨV (e : T2) [Prop. C.3, C.1]

ΦV (e : T1) ≤ΦV (Γ) [ind. hyp.]

ΨV (e : T1) =⇒(ΦV (e : T1) ≥ΦV (e : T2)) [Prop. C.3, C.1]

Γ |= Φ(Γ) = Φ(Γ′) [premise]

ΨV (e : S) = ⊤ [ind. hyp.]

ΦV (e : S) ≤ΦV (Γ′) [ind. hyp.]

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 48 -->

106:48 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

(S-Relax)

SPS Γ = (Γ′,ϕ′),S = Rϕ+ϕ′

Γ′ ⊢e :: Rϕ [premise] 37

p ≥ΦV (Γ′,ϕ′) = ΦV (Γ′) + ϕ′ 38

ind. hyp. on C.1 with C.1

case ⟨e,p⟩7→⟨e′,p′⟩

done

case e ∈Val

C.2 Substitution

Proposition C.7. If Γ ⊢e :: S and ⊢Γ, Γ′ context, then Γ, Γ′ ⊢e :: S.

Proposition C.8. If Γ1 ⊢e :: S and ⊢Γ . Γ1 | Γ2, then Γ ⊢e :: S.

Proposition C.9. If Γ ⊢v :: {B | ψ }ϕ and v ∈Val, then Γ ⊢v :: {B | ν = I(v)}ϕ.

Proposition C.10. If Γ ⊢v :: Rϕ and v ∈Val, then Γ |= Φ(Γ) ≥[I(v)/ν]ϕ.

Lemma C.13. If Γ,ψ, Γ′ ⊢J and Γ |= ψ, then Γ, Γ′ ⊢J.

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.

ΦV (e : S) ≤ΦV (Γ) [C.1]

ΨV (e : R) = ⊤ [ind. hyp.]

ΦV (e : Rϕ) ≤ΦV (Γ′) [ind. hyp.]

ΦV (e : Rϕ+ϕ′) ≤ΦV (Γ′,ϕ′) [C.1]

□

Proof. By induction on Γ ⊢e :: S. □

Proof. By induction on Γ1 ⊢e :: S. □

Proof. By induction on Γ ⊢v :: {B | ψ }ϕ. □

Proof. By induction on Γ ⊢v :: Rϕ. □

Proposition C.11. If Γ ⊢v :: S, Γ ⊢S . S1 | S2 and v ∈Val, then there exist Γ1 and Γ2 such that ⊢Γ . Γ1 | Γ2, and Γ1 ⊢v :: S1, Γ2 ⊢v :: S2.

Proof. By induction on Γ ⊢v :: S. □

Proposition C.12. If Γ ⊢v :: S, Γ ⊢S . S | S and v ∈Val, then there exists Γ′ such that ⊢Γ . Γ | Γ′ (so ⊢Γ′ . Γ′ | Γ′), and Γ′ ⊢v :: S.

Proof. By induction on Γ ⊢v :: S. □

Proof. By induction on Γ,ψ, Γ′ ⊢J. □

Lemma C.14. Suppose J is a judgment other than typing. (1) If Γ1,x : {B | ψ }ϕ, Γ′ ⊢J, Γ2 ⊢t :: {B | ψ }ϕ, t ∈Val and ⊢Γ . Γ1 | Γ2, then Γ, [I(t)/x]Γ′ ⊢

[I(t)/x]J. (2) If Γ1,x : Sx, Γ′ ⊢J, Sx is non-scalar/poly, Γ2 ⊢t :: Sx, t ∈Val and ⊢Γ . Γ1 | Γ2, then Γ, Γ′ ⊢J.

Proof. By induction on Γ,x : Sx, Γ′ ⊢J. □


<!-- page 49 -->

Liquid Resource Types 106:49

Γ, Γ′ ⊢[t/x]a : B.

Proof of (1). By induction on Γ1,x : {Bx | ψ }ϕ, Γ′ ⊢a : B:

(SimpAtom-Var)=

SPS a = x, B = Bx [t/x]a = t, [I(t)/x]B = Bx

(SimpAtom-Var),

SPS a = y

[t/x]a = y

case y ∈Γ

B = base of Γ1(y)

Γ ⊢Γ(y) . Γ1(y) | Γ2(y)

Γ(y) = {B | ψ ′}ϕ′

case y ∈Γ′

B = base of Γ′(y), Γ′(y) = {B | ψ ′}ϕ′

([I(t)/x]Γ′)(y) =

{[I(t)/x]B | [I(t)/x]ψ ′}[I(t)/x]ϕ′

(SimpAtom-ConsD)

SPS a = Cj(a0, ⟨a1, · · · ,amj ⟩), B = indθ

⊢Γ1,x : {Bx | ψ }ϕ, Γ′′, π.j(I(a0))(θ) .

Γ11,x : {B1 | ψ }ϕ1, Γ′

1,ϕ′ 1 |

Γ12,x : {B2 | ψ }ϕ2, Γ′

1,ϕ′ 1 ⊢a0 :: Tj [premise] 39

Γ11,x : {B1 | ψ }ϕ1, Γ′

µ,◁,π (−−−−−−−→ C : (T,m)) [premise] 40

2,ϕ′ 2 ⊢⟨a1, · · · ,amj ⟩: Îmj i=1 ind◁.j(I(a0))(θ).i

Γ12,x : {B2 | ψ }ϕ2, Γ′

exist Γ21, Γ22 s.t. ⊢Γ2 . Γ21 | Γ22,

. (Γ11, Γ21), [I(t)/x](Γ′

1,ϕ′ 1) ⊢

Lemma C.15. (1) If Γ1,x : {Bx | ψ }ϕ, Γ′ ⊢e : B, Γ2 ⊢t :: {Bx | ψ }ϕ,t ∈Val and ⊢Γ . Γ1 | Γ2, then Γ, [I(t)/x]Γ′ ⊢

[t/x]a : [I(t)/x]B. (2) If Γ1,x : Sx, Γ′ ⊢a : B, Sx is non-scalar/poly, Γ2 ⊢t :: Sx, t ∈Val and ⊢Γ . Γ1 | Γ2, then

Γ ⊢t :: {Bx | ψ }ϕ [Prop. C.8]

Γ ⊢t :: {Bx | ν = I(t)}ϕ [Prop. C.9]

Γ, [I(t)/x]Γ′ ⊢t :: {Bx | ν = I(t)}ϕ [Prop. C.7]

Γ, [I(t)/x]Γ′ ⊢t : Bx [typing]

Γ, [I(t)/x]Γ′ ⊢y : B [typing]

Γ, [I(t)/x]Γ′ ⊢y : [I(t)/x]B [typing]

µ,◁,π (−−−−−−−→ C : (T,m))

2,ϕ′ 2 [premise]

Γ21 ⊢t :: {B1 | ψ }ϕ1, Γ22 ⊢t :: {B2 | ψ }ϕ2 [Prop. C.11]

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 50 -->

106:50 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

ind. hyp. on C.2

. (Γ12, Γ22), [I(t)/x](Γ′

2,ϕ′ 2) ⊢

µ,◁,π (−−−−−−−−−−−−−−−→ C : ([I(t)/x]T,m))

[t/x]⟨a1, · · · ,amj ⟩: ind[I(t)/x]θ

⊢Γ . Γ1 | Γ2 =⇒

⊢Γ . (. (Γ11, Γ21) |. (Γ12, Γ22)

Γ,x : {Bx | ψ }ϕ ⊢Γ′, π.j(I(a0))(θ) . Γ′

Γ ⊢[I(t)/x](Γ′′, π.j(I(a0))(θ)) . [I(t)/x](Γ′

Γ, [I(t)/x]Γ′′, π.j(I([t/x]a0))([I(t)/x]θ) ⊢[t/x]a :

ind◁.j(I([t/x]a0))([I(t)/x]θ).i

Γ, Γ′ ⊢[t/x]e :: S.

Proof of (1). By induction on Γ1,x : {B | ψ }ϕ, Γ′ ⊢e :: S:

(T-SimpAtom)

SPS e = a,S = {B′ | ν = I(a)}

Γ, [I(t)/x]Γ′ ⊢[t/x]a ::

{[I(t)/x]B′ | ν = I([t/x]a)} =

[I(t)/x]({B′ | ν = I(a)})

(T-Var)=

SPS e = x,S = {B | ψ }ϕ

[t/x]e = t, [I(t)/x]S = {B | ψ }ϕ

(T-Var),

SPS e = y,S = Γ(y)

[t/x]e = y

case y ∈Γ

WLOG Γ(y) = {B′ | ψ ′}ϕ′

Γ ⊢Γ(y) . Γ1(y) | Γ2(y)

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.

[t/x]a0 :: [I(t)/x]Tj [Thm. C.16, C.2]

1,ϕ′ 1 | Γ′ 2,ϕ′ 2 =⇒

1,ϕ′ 1) | [I(t)/x](Γ′ 2,ϕ′ 2) [Lem. C.14]

µ,◁,π (−−−−−−−−−−−−−−−→ C : ([I(t)/x]T,m)) [typing]

□

Theorem C.16 (Substitution). (1) If Γ1,x : {B | ψ }ϕ, Γ′ ⊢e :: S, Γ2 ⊢t :: {B | ψ }ϕ, t ∈Val and ⊢Γ . Γ1 | Γ2, then Γ, [I(t)/x]Γ′ ⊢

[t/x]e :: [I(t)/x]S. (2) If Γ1,x : Sx, Γ′ ⊢e :: S, Sx is non-scalar/poly, Γ2 ⊢t :: Sx, t ∈Val and ⊢Γ . Γ1 | Γ2, then

Γ1,x : {B | ψ }ϕ, Γ′ ⊢a : B′ [premise]

Γ, [I(t)/x]Γ′ ⊢[t/x]a : [I(t)/x]B′ [Lem. C.15]

{[I(t)/x]B′ | ν = I([t/x]a)} [typing]

Γ ⊢t :: {B | ψ }ϕ [Prop. C.8]

Γ, [I(t)/x]Γ′ ⊢t :: {B | ψ }ϕ [Prop. C.7]


<!-- page 51 -->

Liquid Resource Types 106:51

1 | ψ ′ 1}ϕ′ 1

let Γ1(y) = {B′

1 | ψ ′ 1}ϕ′ 1

[I(t)/x]S = S = {B′

Γ, [I(t)/x]Γ′ ⊢y :: {B′ | ψ ′}ϕ′ [typing]

case y ∈Γ′

WLOG Γ′(y) = {B′ | ψ ′}ϕ′

S = {B′ | ψ ′}ϕ′

[I(t)/x]S =

{[I(t)/x]B′ | [I(t)/x]ψ ′}[I(t)/x]ϕ′

([I(t)/x]Γ′)(y) =

{[I(t)/x]B′ | [I(t)/x]ψ ′}[I(t)/x]ϕ′

Γ, [I(t)/x]Γ′ ⊢y ::

{[I(t)/x]B′ | [I(t)/x]ψ ′}[I(t)/x]ϕ′ [typing]

(T-Imp)

SPS e = impossible,S = T

[t/x]e = impossible

[I(t)/x]S = [I(t)/x]T

Γ1,x : {B | ψ }ϕ, Γ′ |= ⊥ [premise] 41

Γ1,x : {B | ψ }ϕ, Γ′ ⊢T type [premise] 42

Γ, [I(t)/x]Γ′ |= ⊥ [Lem. C.14, C.2]

Γ, [I(t)/x]Γ′ ⊢[I(t)/x]T type [Lem. C.14, C.2]

Γ, [I(t)/x]Γ′ ⊢impossible :: [I(t)/x]T [typing]

(T-Consume-P)

SPS e = tick(c,e0),c ≥0,S = T

SPS Γ′ = Γ′′,c [premise]

[t/x]e = tick(c, [t/x]e0)

[I(t)/x]S = [I(t)/x]T

Γ1,x : {B | ψ }ϕ, Γ′′ ⊢e0 :: T [premise] 43

ind. hyp. on C.2

Γ, [I(t)/x]Γ′′ ⊢[t/x]e0 :: [I(t)/x]T

Γ, [I(t)/x]Γ′′,c ⊢tick(c, [t/x]e0) :: [I(t)/x]T [typing]

Γ, [I(t)/x]Γ′,c ⊢tick(c, [t/x]e0) :: [I(t)/x]T

(T-Consume-N)

SPS e = tick(c,e0),c < 0,S = T

[t/x]e = tick(c, [t/x]e0)

[I(t)/x]S = [I(t)/x]T

Γ1,x : {B | ψ }ϕ, Γ′, −c ⊢e0 :: T [premise] 44

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 52 -->

106:52 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

ind. hyp. on C.2

Γ, [I(t)/x]Γ′, −c ⊢[t/x]e0 :: [I(t)/x]T

(T-Cond)

SPS e = if(a0,e1,e2),S = T

[t/x]e = if([t/x]a0, [t/x]e1, [t/x]e2)

[I(t)/x]S = [I(t)/x]T

Γ1,x : {B | ψ }ϕ, Γ′, I(a0) ⊢

e1 :: T [premise] 45

Γ1,x : {B | ψ }ϕ, Γ′, ¬I(a0) ⊢

e2 :: T [premise] 46

. (Γ1, Γ2), [I(t)/x]Γ′ ⊢[t/x]a0 : bool [Lem. C.15] 47

ind. hyp. on C.2

. (Γ1, Γ2), [I(t)/x]Γ′, [I(t)/x]I(a0) ⊢

[t/x]e1 :: [I(t)/x]T 48

ind. hyp. on C.2

. (Γ1, Γ2), [I(t)/x]Γ′, [I(t)/x]¬I(a0) ⊢

[t/x]e2 :: [I(t)/x]T 49

typing on C.2, C.2, C.2

Γ, [I(t)/x]Γ′ ⊢

if([t/x]e0, [t/x]e1, [t/x]e2) :: [I(t)/x]T

(T-MatP)

SPS e = matp(a0,x1.x2.e1),S = T

[t/x]e = matp([t/x]a0,x1.x2.[t/x]e1)

[I(t)/x]S = [I(t)/x]T

⊢Γ1,x : {B | ψ }ϕ, Γ′ .

Γ11,x : {B1 | ψ }ϕ1, Γ′

1 |

Γ12,x : {B2 | ψ }ϕ2, Γ′

Γ11,x : {B1 | ψ }ϕ1, Γ′

Γ12,x : {B2 | ψ }ϕ1, Γ′

2,x1 : A1,x2 : A2, I(a0) = (x1,x2) ⊢e1 : T [premise] 50

exist Γ21, Γ22 s.t. ⊢Γ2 . Γ21 | Γ22,

. (Γ11, Γ21), [I(t)/x]Γ′

1 ⊢[t/x]a0 : [I(t)/x]A1 × [I(t)/x]A2 [Lem. C.15] 51

ind. hyp. on C.2 with C.2

. (Γ12, Γ22), [I(t)/x]Γ′

2,x1 : [I(t)/x]A1,

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.

Γ, [I(t)/x]Γ′ ⊢tick(c, [t/x]e0) :: [I(t)/x]T [typing]

Γ1,x : {B | ψ }ϕ, Γ′ ⊢a0 : bool [premise]

2 [premise]

1 ⊢a0 : A1 × A2 [premise]

Γ21 ⊢t :: {B1 | ψ }ϕ1, Γ22 ⊢t :: {B2 | ψ }ϕ2 [Prop. C.11]


<!-- page 53 -->

Liquid Resource Types 106:53

x2 : [I(t)/x]A2, I([t/x]a0) = (x1,x2) ⊢

[t/x]e1 :: [I(t)/x]T 52

⊢Γ . Γ1 | Γ2 =⇒⊢Γ . (. (Γ11, Γ21)) | (. (Γ12, Γ22))

Γ,x : {B | ψ }ϕ ⊢Γ′ . Γ′

1 | Γ′ 2 =⇒

Γ ⊢[I(t)/x]Γ′ . [I(t)/x]Γ′

typing on C.2, C.2

Γ, [I(t)/x]Γ′ ⊢matp([t/x]a0,x1.x2.[t/x]e1) :: [I(t)/x]T

(T-MatD)

SPS e = matd(a0, −−−−−−−−−−−−−−−−−−−−−→ Cj(x0, ⟨x1, · · · ,xmj ⟩).ej),,S = T ′

[t/x]e = matd([t/x]a0, −−−−−−−−−−−−−−−−−−−−−−−−−−→ Cj(x0, ⟨x1, · · · ,xmj ⟩).[t/x]ej)

[I(t)/x]S = [I(t)/x]T ′

⊢Γ1,x : {B | ψ }ϕ, Γ′ .

Γ11,x : {B1 | ψ }ϕ1, Γ′

1 |

Γ12,x : {B2 | ψ }ϕ2, Γ′

Γ11,x : {B1 | ψ }ϕ1, Γ′

∀j : Γ12,x : {B2 | ψ }ϕ2, Γ′

I(a0) = µ.j(x0)(· · · ), π.j(x0)(θ) ⊢ej :: T ′ [premise] 53

exist Γ21, Γ22 s.t. ⊢Γ2 . Γ21 | Γ22,

Γ21 ⊢t :: {B1 | ψ }ϕ1, Γ22 ⊢t :: {B2 | ψ }ϕ2 [Prop. C.11] 54

. (Γ11, Γ21), [I(t)/x]Γ′

1 ⊢

µ,◁,π (−−−−−−−−−−−−−−−→ C : ([I(t)/x]T,m)) [Lem. C.15] 55

[t/x]a0 : ind[I(t)/x]θ

ind. hyp. on C.2 with C.2

∀j :. (Γ12, Γ22), [I(t)/x]Γ′

2,x0 : [I(t)/x]Tj, −−−−−−−−−−−−−−−−−−−−−−−−−−−−−−−−−−−−−−−→ xi : ind◁.j(x0)([I(t)/x]θ).i

µ,◁,π (−−−−−−−−−−−−−−−→ C : ([I(t)/x]T,m)),

I([t/x]a0) = µ.j(x0)(· · · ), π.j(x0)([I(t)/x]θ) ⊢

[t/x]ej :: [I(t)/x]T ′ 56

⊢Γ . Γ1 | Γ2 =⇒

⊢Γ . (. (Γ11, Γ21)) | (. (Γ12, Γ22))

Γ,x : {B | ψ }ϕ ⊢Γ′ . Γ′

1 | Γ′ 2 =⇒

Γ ⊢[I(t)/x]Γ′ . [I(t)/x]Γ′

typing on C.2, C.2

Γ, [I(t)/x]Γ′ ⊢

matd([t/x]a0, −−−−−−−−−−−−−−−−−−−−−−−−−−→ Cj(x0, ⟨x1, · · · ,xmj ⟩).[t/x]ej)

:: [I(t)/x]T ′

1 | [I(t)/x]Γ′ 2 [Lem. C.14]

2 [premise]

1 ⊢a0 : indθ µ,◁,π (−−−−−−−→ C : (T,m)) [premise]

2,x0 : Tj, −−−−−−−−−−−−−−−−−−−−−−−−−→ xi : ind◁.j(x0)(θ).i

µ,◁,π (−−−−−−−→ C : (T,m)),

1 | [I(t)/x]Γ′ 2 [Lem. C.14]

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 54 -->

106:54 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

(T-Let)

SPS e = let(e1,y.e2),S = T2 [t/x]e = let([t/x]e1,y.[t/x]e2)

[I(t)/x]S = [I(t)/x]T2

⊢Γ1,x : {B | ψ }ϕ, Γ′ .

Γ11,x : {B1 | ψ }ϕ1, Γ′

1 |

Γ12,x : {B2 | ψ }ϕ2, Γ′

Γ11,x : {B1 | ψ }ϕ1, Γ′

1 ⊢e1 :: S1 [premise] 57

Γ12,x : {B2 | ψ2}ϕ2, Γ′

2,y : S1 ⊢e2 :: T2 [premise] 58

exist Γ21, Γ22 s.t. ⊢Γ2 . Γ21 | Γ22,

Γ21 ⊢t :: {B1 | ψ }ϕ1, Γ22 ⊢t :: {B2 | ψ }ϕ2 [Prop. C.11] 59

ind. hyp. on C.2 with C.2

. (Γ11, Γ21), [I(t)/x]Γ′

1 ⊢[t/x]e1 :: [I(t)/x]S1 60

ind. hyp. on C.2 with C.2

. (Γ12, Γ22), [I(t)/x]Γ′

2,y : [I(t)/x]S1 ⊢

[t/x]e2 :: [I(x)/t]T2 61

⊢Γ . Γ1 | Γ2 =⇒

⊢Γ . (. (Γ11, Γ21)) | (. (Γ12, Γ22))

Γ,x : {B | ψ }ϕ ⊢Γ′ . Γ′

1 | Γ′ 2 =⇒

Γ ⊢[I(t)/x]Γ′ . [I(t)/x]Γ′

typing on C.2, C.2

Γ, [I(t)/x]Γ′ ⊢

let([t/x]e1,y.[t/x]e2) :: [I(t)/x]T2

(T-Abs)

SPS e = λ(y.e0),S = (y :Ty →T)0

[t/x]e = λ(y.[t/x]e0)

[I(t)/x]S = (y : [I(t)/x]Ty →[I(t)/x]T)0

Γ1,x : {B | ψ }ϕ, Γ′,y : Ty ⊢e0 :: T [premise] 62

⊢Γ1,x : {B | ψ }ϕ, Γ′ .

Γ1,x : {B | ψ }ϕ, Γ′ |

Γ ⊢{B | ψ }ϕ . {B | ψ }ϕ | {B | ψ }ϕ

exist Γ′

ind. hyp. on C.2

. (Γ1, Γ′

2), [I(t)/x]Γ′,y : [I(t)/x]Ty ⊢

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.

2 [premise]

1 | [I(t)/x]Γ′ 2 [Lem. C.14]

Γ1,x : {B | ψ }ϕ, Γ′ [premise]

2 s.t. Γ′ 2 ⊢t :: {B | ψ }ϕ, ⊢Γ2 . Γ2 | Γ′ 2 [Prop. C.12]


<!-- page 55 -->

Liquid Resource Types 106:55

[t/x]e0 :: [I(t)/x]T 63

⊢(. (Γ1, Γ′

2)) . (. (Γ1, Γ′ 2)) | (. (Γ1, Γ′ 2))

Γ,x : {B | ψ }ϕ ⊢Γ′ . Γ′ | Γ′ =⇒

⊢. (Γ1, Γ′

2), [I(t)/x]Γ′ .

2), [I(t)/x]Γ′ |

. (Γ1, Γ′

. (Γ1, Γ′

2), [I(t)/x]Γ′

typing on C.2

2), [I(v)/x]Γ′ ⊢

. (Γ1, Γ′

λ(y.[t/x]e0) :: y : [I(t)/x]Ty →[I(t)/x]T

Γ, [I(v)/x]Γ′ ⊢

(T-Abs-Lin)

SPS e = λ(y.e0),S = m · (y :Ty →T)

SPS Γ1,x : {B | ψ }ϕ, Γ′ =

1 ,x : {B′′ | ψ }ϕ′′, Γ′′′)

m · (Γ′′

[t/x]e = λ(y.[t/x]e0)

[I(t)/x]S = m · (y : [I(t)/x]Ty →[I(t)/x]T)

1 ,x : {B′′ | ψ }ϕ′′, Γ′′′,y : Ty ⊢e0 :: T [premise] 64

Γ′′

exist Γ′′

Γ′′

ind. hyp. on C.2

. (Γ′′

1 , Γ′′ 2 ), [I(t)/x]Γ′′′,y : [I(t)/x]Ty ⊢

[t/x]e0 :: [I(t)/x]T 65

typing on C.2

m · (. (Γ′′

1 , Γ′′ 2 ), [I(v)/x]Γ′′′) ⊢

λ(y.[t/x]e0) ::

m · (y : [I(t)/x]Ty →[I(t)/x]T)

Γ, [I(v)/x]Γ′ ⊢

λ(y.[t/x]e0)

:: m · (y : [I(t)/x]Ty →[I(t)/x]T)

(T-Fix)

SPS e = fix(f .y.e0),S = ∀−→α .y :Ty →T

[t/x]e = fix(f .y.[t/x]e0)

⊢Γ1,x : {B | ψ }ϕ, Γ′ .

Γ1,x : {B | ψ }ϕ, Γ′ |

Γ ⊢[I(t)/x]Γ′ . [I(t)/x]Γ′ | [I(t)/x]Γ′ [Lem. C.14]

λ(y.[t/x]e0) :: y : [I(t)/x]Ty →[I(t)/x]T [Prop. C.8]

2 s.t. Γ2 =. (m · Γ′′ 2 , _), and [Prop. C.11,

2 ⊢t :: {B′′ | ψ }ϕ′′ C.12]

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 56 -->

106:56 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

Γ1,x : {B | ψ }ϕ, Γ′, f : S, −→α ,y : Ty ⊢e0 :: T

ind. hyp.

Γ, [I(t)/x]Γ′, f : [I(t)/x]S,y : Ty ⊢

[I(t)/x]e0 :: [I(t)/x]T

Γ, [I(t)/x]Γ′ ⊢fix(f .y.[t/x]e0) :: [I(t)/x]S

(T-App-SimpAtom)

SPS e = app(ˆa1,a2),S = [I(a2)/y]T

[t/x]e = app([t/x]ˆa1, [t/x]a2)

[I(t)/x]S = [I(t)/x][I(a2)/y]T

⊢Γ1,x : {B | ψ }ϕ, Γ′ .

Γ11,x : {B1 | ψ }ϕ1, Γ′

1 |

Γ12,x : {B2 | ψ }ϕ2, Γ′

Γ11,x : {B1 | ψ }ϕ1, Γ′

1 ⊢ˆa1

:: 1 · (y : {By | ψy}ϕy →T) [premise] 66

2 ⊢a2 :: {By | ψy}ϕy [premise] 67

Γ12,x : {B2 | ψ }ϕ2, Γ′

exist Γ21, Γ22 s.t. ⊢Γ2 . Γ21 | Γ22,

Γ21 ⊢t :: {B1 | ψ }ϕ1, Γ22 ⊢t :: {B2 | ψ }ϕ2 [Prop. C.11] 68

ind. hyp. on C.2 with C.2

. (Γ11, Γ21), [I(t)/x]Γ′

1 ⊢[t/x]ˆa1 ::

1 · (y : [I(t)/x]{By | ψy}ϕy →[I(t)/x]T) 69

ind. hyp. on C.2 with C.2

. (Γ12, Γ22), [I(t)/x]Γ′

2 ⊢

[t/x]a2 :: [I(t)/x]{By | ψy}ϕy 70

⊢Γ . Γ1 | Γ2 =⇒

⊢Γ . (. (Γ11, Γ21)) | (. (Γ12, Γ22))

Γ,x : {B | ψ }ϕ ⊢Γ′ . Γ′

1 | Γ′ 2 =⇒

Γ ⊢[I(t)/x]Γ′ . [I(t)/x]Γ′

typing on C.2, C.2

Γ, [I(t)/x]Γ′ ⊢

app([t/x]ˆa1, [t/x]a2) :: [I(t), I(a2)/x,y]T

(T-App)

SPS e = app(e1,e2),S = T

[t/x]e = app([t/x]ˆa1, [t/x]ˆa2)

[I(t)/x]S = [I(t)/x]T

⊢Γ1,x : {B | ψ }ϕ, Γ′ .

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.

Γ1,x : {B | ψ }ϕ, Γ′ [premise]

2 [premise]

1 | [I(t)/x]Γ′ 2 [Lem. C.14]


<!-- page 57 -->

Liquid Resource Types 106:57

Γ11,x : {B1 | ψ }ϕ1, Γ′

1 |

Γ12,x : {B2 | ψ }ϕ2, Γ′

Γ11,x : {B1 | ψ }ϕ1, Γ′

1 ⊢ˆa1 :: 1 · (y :Ty →T) [premise] 71

Γ12,x : {B2 | ψ }ϕ2, Γ′

2 ⊢ˆa2 :: Ty [premise] 72

exist Γ21, Γ22 s.t. ⊢Γ2 . Γ21 | Γ22,

Γ21 ⊢t :: {B1 | ψ }ϕ1, Γ22 ⊢t :: {B2 | ψ }ϕ2 [Prop. C.11] 73

ind. hyp. on C.2 with C.2

. (Γ11, Γ21), [I(t)/x]Γ′

1 ⊢[t/x]ˆa1 ::

1 · (y : [I(t)/x]Ty →[I(t)/x]T) 74

ind. hyp. on C.2 with C.2

. (Γ12, Γ22), [I(t)/x]Γ′

2 ⊢

[t/x]ˆa2 :: [I(t)/x]Ty 75

⊢Γ . Γ1 | Γ2 =⇒

⊢Γ . (. (Γ11, Γ21)) | (. (Γ12, Γ22))

Γ,x : {B | ψ }ϕ ⊢Γ′ . Γ′

1 | Γ′ 2 =⇒

Γ ⊢[I(t)/x]Γ′ . [I(t)/x]Γ′

typing on C.2, C.2

Γ, [I(t)/x]Γ′ ⊢

app([t/x]ˆa1, [t/x]ˆa2) :: [I(t)/x]T

(S-Gen)

SPS e = v,S = ∀α.S′

[t/x]e = [t/x]v

[I(t)/x]S = ∀α.[I(t)/x]S′

ind. hyp.

Γ, [I(t)/x]Γ′, α ⊢[t/x]v :: [I(t)/x]S′

(S-Inst)

SPS S = [{B′ | ψ ′}ϕ′/α]S′

[I(t)/x]S =

[[I(t)/x]{B′ | ψ ′}ϕ′/α][I(t)/x]S′

ind. hyp.

Γ, [I(t)/x]Γ′ ⊢[t/x]e :: ∀α.[I(t)/x]S′

2 [premise]

1 | [I(t)/x]Γ′ 2 [Lem. C.14]

Γ1,x : {B | ψ }ϕ, Γ′, α ⊢v :: S′ [premise]

Γ, [I(t)/x]Γ′ ⊢[t/x]v :: ∀α.[I(t)/x]S′ [typing]

Γ1,x : {B | ψ }ϕ, Γ′ ⊢e :: ∀α.S′ [premise]

Γ1,x : {B | ψ }ϕ, Γ′ ⊢{B′ | ψ ′}ϕ′ type [premise]

Γ, [I(t)/x]Γ′ ⊢[I(t)/x]{B′ | ψ ′}ϕ′ type [Lem. C.14]

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 58 -->

106:58 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

Γ, [I(t)/x]Γ′ ⊢[t/x]e ::

[[I(t)/x]{B′ | ψ ′}ϕ′/α][I(t)/x]S′ [typing]

(S-Subtype)

SPS S = T2 [I(t)/x]S = [I(t)/x]T2

Γ1,x : {B | ψ }ϕ, Γ′ ⊢e :: T1 [premise]

ind. hyp.

Γ, [I(t)/x]Γ′ ⊢[t/x]e :: [I(t)/x]T1

Γ1,x : {B | ψ }ϕ, Γ′ ⊢T1 <: T2 [premise]

Γ, [I(t)/x]Γ′ ⊢[I(t)/x]T1 <: [I(t)/x]T2 [Lem. C.14]

Γ, [I(t)/x]Γ′ ⊢[t/x]e :: [I(t)/x]T2 [typing]

(S-Transfer)

1,x : {B | ψ }ϕ′, Γ′′

SPS Γo = Γ′

let ˜Γ = Γ1,x : {B | ψ }ϕ, Γ′

Γo ⊢e :: S [premise] 76 ˜Γ |= Φ(˜Γ) = Φ(Γo) [premise] 77

Γ ⊢{B | ψ }ϕ . {B0 | ψ }ϕ | {B | ψ }0

Lem. C.4, exist Γ′

2 and Γ′′ 2 s.t.

2 | Γ′′ 2 Γ′

⊢Γ2 . Γ′

2 ⊢t :: {B0 | ψ }ϕ

=⇒Γ2 |= Φ(Γ′

2) ≥[I(t)/ν]ϕ [Prop. C.10]

Γ′′

2 ⊢t :: {B | ψ }0

2 , [I(t)/ν]ϕ′ ⊢t :: {B | ψ }ϕ′ [relax]

Γ′′

ind. hyp. on C.2

1, Γ′′ 2 , [I(t)/ν]ϕ′), [I(t)/x]Γ′′ ⊢

. (Γ′

[t/x]e :: [I(t)/x]S 78

Lem. C.14 on C.2

Γ, [I(t)/x]Γ′ |=

[I(t)/x]Φ(˜Γ) = [I(t)/x]Φ(Γo)

[I(t)/x]Φ(˜Γ) = Φ(Γ1)+

[I(t)/ν]ϕ + Φ([I(t)/x]Γ′) [def.] 79

[I(t)/x]Φ(Γo) = Φ(Γ′

1)+

[I(t)/ν]ϕ′ + Φ([I(t)/x]Γ′′) [def.] 80

Φ(Γ, [I(t)/x]Γ′) =

Φ(Γ1) + Φ(Γ2) + Φ([I(t)/ν]Γ′) =

Φ(Γ′

1) + Φ(Γ′ 2) + Φ(Γ′′ 2 ) + Φ([I(t)/ν]Γ′′)+

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 59 -->

Liquid Resource Types 106:59

Φ(Γ′

1) + Φ(Γ′′ 2 ) + Φ([I(t)/x]Γ′′)+

[I(t)/x]ϕ′ =

Φ(. (Γ′

1, Γ′′ 2 , [I(t)/ν]ϕ′), [I(t)/x]Γ′′)

recall C.2, and then typing, relax

Γ, [I(t)/x]Γ′ ⊢[I(t)/x]e :: [I(t)/x]S

(S-Relax)

SPS S = Rϕ+ϕ′

[I(t)/x]S = [I(t)/x]R[I(t)/x]ϕ+[I(t)/x]ϕ′

ind. hyp.

Γ, [I(t)/x]Γ′ ⊢[t/x]e :: [I(t)/x]R[I(t)/x]ϕ

Γ, [I(t)/x]Γ′, [I(t)/x]ϕ′ ⊢[t/x]e ::

C.3 Preservation

Theorem C.18 (Preservation). If Γ = q, Γ ⊢e :: S, p ≥Φ∅(Γ) and ⟨e,p⟩7→⟨e′,p′⟩, then p′ ⊢e′ :: S.

Proof. By induction on Γ ⊢e :: S:

(T-Consume-P)

SPS Γ = (Γ′,c),e = tick(c,e0),c ≥0

SPS S = T

Γ′ ⊢e0 :: T [premise] 81

inv. on ⟨e,p⟩7→⟨e′,p′⟩

e′ = e0,p′ = p −c ≥Φ∅(Γ) −c = Φ∅(Γ′)

p′ ⊢e0 :: T [relax, C.3]

(T-Consume-N)

SPS e = tick(c,e0),c < 0,S = T

Γ, −c ⊢e0 :: T [premise] 82

inv. on ⟨e,p⟩7→⟨e′,p′⟩

e′ = e0,p′ = p −c ≥Φ∅(Γ) −c

[I(t)/ν](ϕ′ −ϕ) ≥ [C.2, C.2]

Γ1,x : {B | ψ }ϕ, Γ′ ⊢e :: Rϕ [premise]

Γ1,x : {B | ψ }ϕ, Γ′ ⊢ϕ′ ∈N [premise]

Γ, [I(t)/x]Γ′ ⊢[I(t)/x]ϕ′ ∈N [Lem. C.14]

[I(t)/x]R[I(t)/x]ϕ+[I(t)/x]ϕ′ [typing]

□

Proposition C.17. If ⟨e,p⟩7→⟨e′,p′⟩and ⟨e,q⟩7→⟨e′′,q′⟩, then e′ = e′′ and q −p = q′ −p′.

Proof. By induction on ⟨e,p⟩7→⟨e′,p′⟩and then inversion on ⟨e,q⟩7→⟨e′′,q′⟩. □

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 60 -->

106:60 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

p′ ⊢e0 :: T [relax, C.3]

(T-Cond)

SPS e = if(a0,e1,e2),S = T

Γ ⊢a0 : bool [premise]

Γ, I(a0) ⊢e1 :: T [premise] 83

Γ, ¬I(a0) ⊢e2 :: T [premise]

inv. on ⟨e,p⟩7→⟨e′,p′⟩

case ⟨e,p⟩7→⟨e1,p⟩

a0 = true [premise]

I(a0) = ⊤

Γ |= ⊤

Γ ⊢e1 :: T [Lem. C.13, C.3]

p ≥Φ∅(Γ) [asm.]

p ⊢e1 :: T [relax]

case ⟨e,p⟩7→⟨e2,p⟩

a0 = false [premise]

similar to a0 = true

(T-MatP)

SPS e = matp(a0,x1.x2.e1),S = T

⊢Γ . Γ1 | Γ2 [premise]

=⇒Φ∅(Γ) = Φ∅(Γ1) + Φ∅(Γ2) 84

Γ1 ⊢a0 : B1 × B2 [premise]

Γ2,x1 : B1,x2 : B2, I(a0) = (x1,x2) ⊢e1 :: T [premise] 85

inv. on ⟨e,p⟩7→⟨e′,p′⟩

a0 = pair(v1,v2),e′ = [v1,v2/x1,x2]e,p′ = p

I(a0) = (I(v1), I(v2))

Γ11 ⊢v1 : B1, Γ12 ⊢v2 : B2, ⊢Γ1 . Γ11 | Γ12 [inv.] 86

. (Γ2, Γ1), I(a0) = (I(v1), I(v2)) ⊢[v1,v2/x1,x2]e1 :: T [Thm. C.16, C.3, C.3 ]

Γ |= I(a0) = (I(v1), I(v2))

Γ ⊢[v1,v2/x1,x2]e1 :: T [Lem. C.13]

p ≥Φ∅(Γ) [asm., C.3]

p ⊢e′ :: T ′ [relax]

(T-MatD)

SPS e = matd(a0, −−−−−−−−−−−−−−−−−−−−−→ Cj(x0, ⟨x1, · · · ,xmj ⟩).ej),S = T ′

⊢Γ . Γ1 | Γ2 [premise]

=⇒Φ∅(Γ) = Φ∅(Γ1) + Φ∅(Γ2) 87

µ,◁,π (−−−−−−−→ C : (T,m)) [premise]

Γ1 ⊢a0 : indθ

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 61 -->

Liquid Resource Types 106:61

−−−−−−−−−−−−−−−−−−−−−−−−−→ xi : ind◁.j(x0)(θ).i

µ,◁,π (−−−−−−−→ C : (T,m)),

∀j : Γ2,x0 : Tj,

I(a0) = µ(Cj(x0, ⟨· · · ⟩)) ⊢ej :: T ′ [premise] 88

inv. on ⟨e,p⟩7→⟨e′,p′⟩

∃j : ⟨e,p⟩7→⟨[v0,v1, · · · ,vmj /x0,x1, · · · ,xmj ]ej,p⟩

a0 = Cj(v0, ⟨v1, · · · ,vmj ⟩) [premise]

I(a0) = µ.j(I(v0))(I(v1), · · · , I(vmj ))

Γ11 ⊢v0 :: T,

i=1 ind◁.j(I(v0))(θ).i

Γ12 ⊢⟨v1, · · · ,vmj ⟩: Îmj

1, π.j(I(v0))(θ), ⊢Γ′ 1 . Γ11 | Γ12 [inv.] 89

Γ1 = Γ′

. (Γ2, Γ′

Γ |= I(a0) = µ.j(I(v0))(I(v1), · · · , I(vmj ))

p ≥Φ∅(Γ) [asm., C.3]

p ⊢e′ :: T ′ [relax]

(T-Let)

SPS e = let(e1,x.e2),S = T2

⊢Γ . Γ1 | Γ2

=⇒Φ∅(Γ) = Φ∅(Γ1) + Φ∅(Γ2) [premise] 90

Γ1 ⊢e1 :: S1 [premise] 91

Γ2,x : S1 ⊢e2 :: T2 [premise] 92

inv. on ⟨e,p⟩7→⟨e′,p′⟩

case ⟨e,p⟩7→⟨let(e′

1,x.e2),p′⟩

1,p′⟩ [premise] 93

⟨e1,p⟩7→⟨e′

p −Φ∅(Γ2) ≥Φ∅(Γ1) [asm., C.3] 94

Thm. C.6 on C.3 with C.3

1,p′ −Φ∅(Γ2)⟩ [Prop. C.17, C.3] 95

⟨e1,p −Φ∅(Γ2)⟩7→⟨e′

ind. hyp. on C.3 with C.3, C.3

p′ −Φ∅(Γ2) ⊢e′

1 :: S1 . (p′ −Φ∅(Γ2), Γ2) ⊢let(e′

case ⟨e,p⟩7→⟨[e1/x]e2,p⟩

µ,◁,π (−−−−−−−→ C : (T,m)),

1), I(a0) = µ.j(I(v0))(I(v1), · · · , I(vmj )), π.j(I(v0))(θ) ⊢ [Thm. C.16,

[v0,v1, · · · ,vmj /x0,x1, · · · ,xmj ]ej :: T ′ C.3, C.3]

Γ ⊢[v0,v1, · · · ,vmj /x0,x1, · · · ,xmj ]ej :: T ′ [Lem. C.13]

1,x.e2) :: T2 [typing]

p′ ⊢e′ :: T2 [transfer]

e1 ∈Val [premise]

. (Γ1, Γ2) ⊢[e1/x]e2 :: T2 [Thm. C.16, C.3]

Φ∅(Γ1) + Φ∅(Γ2) ⊢e′ :: T2 [transfer]

p ⊢e′ :: T2 [relax]

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 62 -->

106:62 Tristan Knoth, Di Wang, Adam Reynolds, Jan Hoffmann, and Nadia Polikarpova

(T-App-SimpAtom)

SPS e = app(ˆa1,a2),S = T

⊢Γ . Γ1 | Γ2

=⇒Φ∅(Γ) = Φ∅(Γ1) + Φ∅(Γ2) [premise]

Γ1 ⊢ˆa1 :: 1 · (x : {Bx | ψx }ϕx →T) [premise] 96

Γ2 ⊢a2 :: {Bx | ψx }ϕx [premise]

inv. on ⟨e,p⟩7→⟨e′,p′⟩

case ⟨e,p⟩7→⟨[a2/x]e0,p⟩

ˆa1 = λ(x.e0),a2 ∈Val [premise]

inv. on C.3

Γ1,x : {Bx | ψx }ϕx ⊢e0 :: T 97

Γ ⊢[a2/x]e0 :: [I(a2)/x]T [Thm. C.16, C.3]

p ≥Φ∅(Γ) [asm.]

p ⊢e′ :: T [relax]

case ⟨e,p⟩7→⟨[e1,a2/f ,x]e0,p⟩

e1 = fix(f .x.e0),a2 ∈Val [premise]

similar to e1 = λ(x.e0)

(T-App)

SPS e = app(ˆa1, ˆa2),S = T

⊢Γ . Γ1 | Γ2

=⇒Φ∅(Γ) = Φ∅(Γ1) + Φ∅(Γ2) [premise]

Γ1 ⊢ˆa1 :: 1 · (x :Tx →T) [premise] 98

Γ2 ⊢ˆa2 :: Tx [premise]

inv. on ⟨e,p⟩7→⟨e′,p′⟩

case ⟨e,p⟩7→⟨[ˆa2/x]e0,p⟩

ˆa1 = λ(x.e0), ˆa2 ∈Val [premise]

inv. on C.3

Γ1,x : Tx ⊢e0 :: T 99

Γ ⊢[ˆa2/x]e0 :: T [Thm. C.16, C.3]

p ≥Φ∅(Γ) [asm.]

p ⊢e′ :: T [relax]

case ⟨e,p⟩7→⟨[e1, ˆa2/f ,x]e0,p⟩

e1 = fix(f .x.e0), ˆa2 ∈Val [premise]

similar to e1 = λ(x.e0)

(S-Inst)

SPS S = [{B | ψ }ϕ/α]S′

Γ ⊢e :: ∀α.S′ [premise] 100

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.


<!-- page 63 -->

Liquid Resource Types 106:63

ind. hyp. on C.3

p′ ⊢e′ :: ∀α.S′

p′ ⊢e′ :: [{B | ψ }ϕ/α]S′ [typing]

(S-Subtype)

SPS S = T2 Γ ⊢e :: T1 [premise] 101

Γ ⊢T1 <: T2 [premise]

ind. hyp. on C.3

p′ ⊢e′ :: T1 p′ ⊢e′ :: T2 [typing]

(S-Transfer)

Γ′ ⊢e :: S, Γ |= Φ(Γ) = Φ(Γ′) [premise] 102

Γ′ = q′ ∧Φ∅(Γ) = Φ∅(Γ′)

p ≥Φ∅(Γ′) 103

ind. hyp. on C.3 with C.3

p′ ⊢e′ :: S

(S-Relax)

SPS Γ = (Γ′,ϕ′),S = Rϕ+ϕ′

Γ′ ⊢e :: Rϕ [premise] 104

p −ϕ′ ≥Φ∅(Γ′) [asm.] 105

Thm. C.6 on C.3 with C.3

⟨e,p −ϕ′⟩7→⟨e′,p′ −ϕ′⟩ [Prop. C.17, asm.] 106

ind. hyp. on C.3 with C.3, C.3

p′ −ϕ′ ⊢e′ :: Rϕ

p′ −ϕ′,ϕ′ ⊢e′ :: Rϕ+ϕ′ [relax]

p′ ⊢e′ :: Rϕ+ϕ′ [transfer]

□

Proc. ACM Program. Lang., Vol. 4, No. ICFP, Article 106. Publication date: August 2020.
