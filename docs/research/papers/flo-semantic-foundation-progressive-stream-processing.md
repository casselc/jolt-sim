# Flo: A Semantic Foundation for Progressive Stream Processing

**Machine conversion:** extracted from the adjacent PDF with `pypdf`; page boundaries are retained, while equations, figures, and multi-column layout may not round-trip faithfully. Consult the PDF for authoritative pagination and notation.

## PDF page 1

Flo: A Semantic Foundation for Progressive Stream
Processing

SHADAJ LADDAD,                                                                      UC Berkeley, USA
ALVIN CHEUNG,                                                                  UC Berkeley, USA
JOSEPH M. HELLERSTEIN,                                                                                                  UC Berkeley, USA
MAE MILANO,                                                         Princeton University, USA

Streaming systems are present throughout modern applications, processing continuous data in real-time.
Existingstreaminglanguageshaveavarietyofsemanticmodelsandguaranteesthatareoftenincompatible.Yet
alltheselanguagesareconsidered“streaming”—whatdotheyhaveincommon?Inthispaper,weidentifytwo
generalyetprecisesemanticproperties:streamingprogressandeagerexecution.Together,theyensurethat
streamingoutputsaredeterministicandkeptfreshwithrespecttostreaminginputs.Weformallydefinethese
properties in the contextof Flo, a parameterized streaming languagethat abstracts over dataflow operators
andtheunderlyingstructureofstreams.Itleveragesalightweighttypesystemtodistinguishboundedstreams,
which allow operators to blockon termination, from unbounded ones. Furthermore, Floprovides constructs
for dataflow composition and nested graphs with cycles. To demonstrate the generality of our properties, we
show how key ideas from representative streaming and incremental computation systems—Flink, LVars, and
DBSP—have semantics that can be modeled in Flo and guarantees that map to our properties.
CCS Concepts:                                            •     Software and its engineering                                                                                           →          Specialized application languages                                                                                                      ;    •     Theory of
computation                                         →          Streamingmodels                                                       .
Additional Key Words and Phrases: stream processing, dataflow languages, incremental computation
ACMReferenceFormat:
Shadaj Laddad, Alvin Cheung, Joseph M. Hellerstein, and Mae Milano. 2025. Flo: A Semantic Foundation
for Progressive Stream Processing.                                                                                             Proc. ACM Program. Lang.                                                                        9, POPL, Article 9 (January 2025),                                                                                           30        pages.
https://doi.org/10.1145/3704845

1   Introduction
Streamprocessingisanincreasinglyimportantcomponentofmodernapplications,fromreal-time
analyticstocollaborativetools.Theseapplicationsmustrespondwithlowlatencytoeventsasthey
arise and often process long streams of data. Furthermore, these applications often involve stateful
processing, where the output of a computation depends on the history of the inputs.
          Many streaming applications are expressed as dataflow programs [                                                                                                                                                                                                            4], specified as a directed
graphofoperators.Eachnodeisanoperatorthatconsumesandproducesdataelements,andthe
edgesrepresenttheflowofdatabetweenthem.ThismodelisusedinsystemslikeApacheFlink[                                                                                                                                                                                                                                                                                               15       ],
Spark [                      45       ], StreamIt [                                   42       ], and many functional-reactive programming languages [                                                                                                                                                                                38       ]. Dataflow
programsbenefit frombeing writtenina declarativemanner thatabstractsaway fromlow-level
detailssuchashowoperatorsarescheduledandwherestateinthesystemisaccumulated[                                                                                                                                                                                                                                                                                    1,       7,       20       ,

Authors’ContactInformation:ShadajLaddad,UCBerkeley,USA,shadaj@cs.berkeley.edu;AlvinCheung,UCBerkeley,USA,
akcheung@cs.berkeley.edu;JosephM.Hellerstein,UCBerkeley,USA,hellerstein@cs.berkeley.edu;MaeMilano,Princeton
University, USA, mpmilano@cs.princeton.edu.






©      2025 Copyright held by the owner/author(s).
ACM 2475-1421/2025/1-ART9
https://doi.org/10.1145/3704845


                                                                                                           Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 2

9:2                                                                              Shadaj Laddad, Alvin Cheung, Joseph M. Hellerstein, and Mae Milano

21]. This makes it easy for compilers to optimize dataflow programs, since they can rearrange and
transform operators within the graph without affecting the observable behavior of the program.
   Existing streaming languages present a variety of semantics and aim to provide various guar-
antees. But several streaming languages do not even agree on what constitutes a stream! They
can be ordered sequences [15, 42], or sets [6], or even lattices [30] or Z-Sets [13]. These languages
also vary in their semantics for state persistence, and offer a range of approaches for concepts like
windowedaggregationsandbatchedexecution.Buttheyalsohavemuchincommon:streaming
languagestolerate changinginputs andaim toproduceoutputs asearly aspossible. Yet theseideas
have remained fuzzy and tied to incompatible semantics.
   In this paper, we distill these common traits into two key properties: streaming progress
andeagerexecution. We formally define these properties in the context of Flo, a parameterized
streaminglanguagethataccommodatesarangeofstreamingsemanticswithcompositionaldataflow.
Flo abstracts away from notions of underlying collection types, such as ordered sequences, and
supports semantics that many streaming languages cannot reason about [19], such as retractions.
   A key challenge in streaming systems is ensuring that the program makes progress. Unlike
traditional languages, the definition of progress in streaming languages has long remained fuzzy
and tied to very specific semantics. In Flo, we introduce ageneralyetprecise formal definition
calledstreamingprogress,whichuses stream termination (inspiredbyworkfromthedatabases
community [43]) as a common semantic feature to make guarantees about streaming outputs.
Streaming progress guarantees that a Flo program producesasmuchoutput as possible given its
input, and that the programwillnotblockon a stream that may never terminate.
   Toenforcestreamingprogress,weintroducealightweighttypesystemthatdifferentiatesbetween
bounded andunboundedstreams. Boundedstreams are guaranteedto eventuallyterminate,while
unbounded streams maynever terminate.Operatorscan only block on bounded streams,and must
alwaysmakeprogresswithrespect tounboundedstreams.Theselightweighttypescan belayered
on arbitrary underlying collection types, such as Stream Types [19], sets, or even lattices.
   Wherestreamingprogressfocusesonensuringthatoutputsareproducedinatimelyfashion
relative to inputs,eagerexecution ensures that the outputs are deterministic. Many streaming
systems make strong assumptions about how operators are executed. For example, Dedalus [6]
processesbatcheswithasinglegloballoop,whileNaiad[34]processesmessagesone-by-one.InFlo,
wegeneralizetherequirementofdeterministicprocessingintoeagerexecution.Thisproperty
enforces that Flo caneagerly execute downstream operators while their inputs arestillbeing
updated. Thisproperty allowsfor arbitrary executionschedules whilearriving at adeterministic
result,whichgiveslow-levelschedulerssignificantpowertodecidewhenoperatorsshouldberun.
   Flo is a declarative dataflow language that takes inspiration from the iterative processing of
actors [23], but uses an event loop that maintains several independent input and output queues.
Rather than process messages one by one, programs in Flo describe a dataflow that operates over
concretecollectionsofdata.Infact,thesecollectionsare finite,unlikemodelsofstreamssuchas
co-inductive lists. To implement streaming applications, these concrete inputs can be extended,
and the execution of the Flo program can be safely resumed over these new inputs.
   Floalsosupportsstreamsofstreams,whichcapturebehaviorsuchasbatching.Inspiredby
ingress/egress nodes in Naiad [34], nested streams can be processed bynesteddataflowgraphs,
whichiterativelyprocesschunksofdatasourcedfromalargerstreamwithsupportforcarrying
state across iterations. This makes it possible toprecisely implement a wide range of applications,
such as windowed aggregations, processing data with minibatches, or iterative algorithms.
   Floisaparameterizedfamilyoflanguageswhichbringtheirowndatatypesandoperators.Our
proofs of streaming progress and eager execution are compositional, reducing the proof burden to
individualoperators. ThisallowsFlo tocapturethe essenceofa widerangeof streamingsystems


Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 3

Flo: A Semantic Foundation for Progressive Stream Processing                                                                                          9:3

under a single model, allowing for composition that spans these approaches. To demonstrate this
generality, we show how Flo can be used to model key ideas from a representative variety of
streaminglanguagesandincrementalcomputationsystems—Flink[15],LVars[30],andDBSP[13]—
and show how existing semantic goalsfrom each map tostreaming progress and eagerexecution.
  In summary, we make the following contributions:

    • We formally definestreamingprogress andeagerexecution in the context of Flo, and
       specify a type system that reasons about stream termination (Section3).
    • We introduce constructs in Flo forcomposingoperators into dataflow graphs and prove
       that they preserve our key properties (Section4).
    • Wedescribethesemanticsof nestedstreamsandgraphsinFloanddemonstratehowthey
       integrate with streaming progress and eager execution (Section5).
    • We show how theessence and key capabilitiesof existingstreaminglanguages map to
       Flo and its foundational properties (Section6).

2   Motivating Example
Tounderstandwhyweneedamodelforstreamingsystemswithstrongsemanticguarantees,let
uswalkthroughthechallengesadevelopermayfacewhilewritingasimpleprogramthatsumsup
a stream of numbers.
  We will accept a sequence of numbers from a streaming source, sum them up, and emit the
resultingsumasthesinglefixedvalueintheoutputstreamofourprogram.Streamingsourcesand
sinksaremodeledasinputsandoutputstoadataflowgraph,sowewillnothaveexplicitoperators
for those. Instead, we can focus on just the core computation of summing up the numbers. A naive
attempt may use a foldoperator, which accumulates a value over a stream of data. In Rust:

     output = input.fold(0, |acc, x| acc + x)
  Thisprogramissimple,butithas acriticalflaw:thefoldoperatorisdefinedovera fixed input
collection.Operationallythismeansitwillcontinueprocessingwithoutproducinganyoutputuntil
thestreamsomehowexplicitlyterminates.Thisconcernisnotaddressedinthespecification.Ina
streamingsystem,thisisacommonmistakethatcanleadtoprogramsthathangindefinitelywhile
consuming resources.
  We next envision a number of ways a programmer could recognize and address this issue by
choosing alternative semantics for this program. We categorize them into strategies that motivate
the key properties we aim to establish with Flo:streamingprogress andeagerexecution.

2.1   Checking Boundedness Constraints
Ourprogram abovedoesworkona subsetofinput streams:thosethat arefinitelybounded,i.e.
wherethe“last”elementoftheinputstreamisguaranteedtoarrive.Unfortunately,thisprogramis
not well-defined on unbounded streams since we may accumulate the aggregation forever. In our
semantics,wewillmodelthisfailurecaseasanoperatorthatdoesnotsatisfystreamingprogress.
  To resolve this, we can imagine classifying input streams via a subtype that would capture
the boundedness property. We could then declare that the semantics of the fold operator are
defined(correct)onboundedinputstreams,butundefined(incorrect)onunboundedinputstreams.
Boundednessannotationsonstreamsandoperatorswouldallowustostaticallyanalyzetheprogram
above as incorrect, and suggest a fix: find a way to ensure that inputis bounded.
  Butwhat iftheprogrammer’s intentwas tohandlean unbounded inputstream?Twonatural
variations to this specification are possible, as we discuss next.


                                Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 4

9:4                                                                              Shadaj Laddad, Alvin Cheung, Joseph M. Hellerstein, and Mae Milano

2.2   Coercing to Bounded Streams
Manystreamingapplicationsandlanguagesaddressthemismatchbetweenunboundedstreamsand
operatorsthatrequireboundednessbyintroducingconstructsforcomputingoverfinitebatchesor
“windows”oftheinputstream[15].Perhapsthisiswhatourprogrammerintended:theiruseof
fold was intended to be scoped to a finite substream of input.
   To capture this idea, we can envision a program variant that uses a batch operator to emit a
stream of streams, where each inner stream is a batch of the original input. There are many
possible “windowing”semantics forsuch a batch operator, but letus assumethat any such batch
operatorensuresthateachinnerstreamisboundedbyspecification.Inthatcase,itiscorrectto
employ fold over theinner streams,eventhough theouter streammay beunbounded. We can
specify how each inner stream is handled via a nest operator that allows us to define a nested
dataflow graph to run for each of these inner streams:
      output = input.batch().nest(|inner| {
           inner.fold(0, |acc, x| acc + x)
      })
   The output of thisprogram is another streamof streams, where each innerstream is the (single)
sum of a batch of the input. This avoids the semantic problem of our previous example: even if
inputisunbounded,eachinnerargumenttonestisbounded,andhencecanbepassedintofold.
Moreover, if input is bounded, this program can (withappropriate parameterization) producethe
same result as our original program, by treating the whole input as a single batch. Hence in some
sensewehavenotdriftedtoofarfromwhatseemstohavebeentheprogrammer’soriginalintent.

2.3   Embracing Streaming Operators
An alternative“fix” tothe initial programwould be toreplace the fold operator witha streaming
variant like scan that emits the “running” sum:
      output = input.scan(0, |acc, x| acc + x)
   Onthepositiveside,thisprogramworksonbothunboundedandboundedinputstreams(andit
willsatisfyourformaldefinitionforstreamingprogress).However,itseemsratherdistantfromour
original program: in particular, there is no way to make it produce the same result as our original
program if input is bounded.
   Instead, we could imagine a streaming operator whose output is a singleton stream of one
monotonicallygrowingvalue.Ateachstep,thisaggregatorcomputesanupdatedsum,butignores
the result if it is smaller than the previous aggregated result. We could then write a program
consuming an unbounded input stream:
      output = input.sum_lattice()
Onceagain,foraboundedinput,thisprogramwillproducethesameresultasouroriginalprogram.
Itis,however,adeparturefromtraditionalstreamingsystems:foranunboundedinput,theoutput
ofthesum_latticeoperator“grows”inthedomainofnaturalnumbersratherthaninadomainof
collections.
   To get back to the domain of collections, such a “monotonic singleton” stream can be passed
into a monotone function that emits an event upon reaching a threshold:
      output = input.sum_lattice().event_when_above(100)
   This is a common pattern in monitoring systems, and is a simplified version of the approach
takenbyLVars [30].Whydoesthethresholdneedto bemonotone?Thisboilsdowntooursecond
formal property: eager execution. This requires that the overall program yields deterministic
results even if we eagerly execute operators onpartial inputs. If thisthreshold were notmonotone,


Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 5

Flo: A Semantic Foundation for Progressive Stream Processing                                                                                          9:5

there couldbenon-determinism due to whenthe threshold is evaluated.Buteagerexecution is a
moregeneral propertythanmonotonicity;we willshowthatit isequallymeaningfulin contexts
wherethereisnonaturalorderingofvalues,suchasinincrementalcomputationswithretractions.

2.4   Discussion
We started with a program that is ill-specified over unbounded streams. We saw various ways
to“fix”this problem,inspiredbysalient designpointsofdifferent streaminglanguages.Whatis
keyisthatalthoughthesetechniquesweremotivatedbyideasfromdifferentlanguages,theyall
servetosatisfytwogeneralpropertiesofprogramswritteninFlo:streamingprogressandeager
execution. In the following sections, we will walk through the formal semantics of Flo and show
howwe canpreciselydefine thesepropertieswhile retainingtheflexibility toimplementawide
range of streaming semantics found in the literature and used in practice.

3   Collections, Streams, Operators, and Core Properties
The Flo model is based on specifications of dataflow pipelines, wherecollections of data elements
are transformedbyoperators such asmap, filter,or join. Thisisinspired byexistingsystems
such as Flink [15], but with a critical difference that Flo is parameterized over collection types and
operators. This enables us to reason about a wide range of streaming paradigms and capture the
essence of languages like LVars [30], Bloom [5], and Temporel [39] under a single model.
   Inthissection,wedefineafamilyofcollectionlanguages𝐿𝐶,operatorlanguages𝐿𝑂,andspecify
theformalpropertiesthattheselanguagesmustsatisfy.InSection4,wewilldefineanewfamilyof
languages𝐿𝐺 which include mechanisms to compose operators into a dataflow graph. Finally, in
Section5, we will extend    𝐿𝐺 with built-in operators for executing nested graphs. Our goal is to
prove eager execution and streaming progress for all these languages.

3.1   The Flo Event Loop
Beforewecandiveintothesemanticsoftheselanguages,weneedtofirstdiscusshowFloprograms
areexecuted.Flodeviatesfromclassicstreamingmodelsinthatitusesanactor-inspiredeventloop
where messages are received, processed, and outputs are emitted. This means that the Flo program
itselfisalwaysexecutingoverconcrete,finitecollectionsofdataratherthanabstractstreams.We
describe a lightweight pseudocode for the event loop of a Flo program in Figure1.

      𝑂← tuple of empty collections for each output
      𝐺← initial Flo program
      loop
          Δ← tuple of new data batches for each input
          𝐼← inputs of𝐺
          𝐺←𝐺 with inputs set to𝐼++Δ
          𝐺,𝑂←𝐺 after running an arbitrary number of small-steps with initial output𝑂
          𝑂←  remaining data after sending arbitrary part of𝑂

                             Fig. 1. The event loop used to execute Flo programs.

   Wheneverabatchofnewdataisreceived,weuseaconcatenationoperator++toaddthisto
theexistinginputs.Inclassicalstreamingsystems,suchasthoseproposedinFlink[15]andStream
Types[19],thiscorrespondstoappendingnewelementstotheendoftheexistingdata.ButinFlo,
our formalization makes it possible for this concatenation operator to take many forms, including
those that do not monotonically grow the collection.


                                Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 6

9:6                                                                              Shadaj Laddad, Alvin Cheung, Joseph M. Hellerstein, and Mae Milano

   Theotherkeyaspecttonoteisthatwerunan arbitrary numberofsmall-stepsoftheprogram𝐺
in each iteration,rather than running it until thereis nothing to be done.We also allow theevent
loop toarbitrarily choosewhich data issent at theend of eachiteration; the outputsneed notbe
consumed accordingto concatenation order. Later inthis section, wewill introduce key properties
that ensure that this loop will always make progress and yield deterministic results.

3.2   Collection Values, Expressions, and Types
Flo programs manipulate collections, which are concrete, finite values used to capture inputs,
outputs,and(inSection4)intermediatestatesoftheprogram.Collectionvaluescanbeupdatedas
newdataarrivesorasanoperatorconsumesdata,butthewayacollectionvaluechangesovertime
does not need to follow a partial order,makingitpossibleforoursemanticstocaptureapplications
such as incremental computation over relations.
   We define a collection language𝐿𝐶 =(𝐶,++,𝐸𝐶,𝑇𝐶,⟦⟧𝐶,⌊⌋𝐶,type𝐶,fix) as a tuple of:
    • C: the set of collection values, which are mathematical objects
    •++   :𝐶×𝐶→𝐶: a “concatenation” function on collections
    •𝐸𝐶: the set of collection expressions, which are syntactic objects
    •𝑇𝐶⊆P(𝐶): the set of collection types, which are sets of collection values
    •⟦⟧𝐶 :𝐸𝐶→𝐶: a total denotational semantics that maps collection expressions to values
    •⌊⌋ 𝐶 :𝐶 ⇀𝐸𝐶: a partial lowering function that maps collection values to expressions
    • type𝐶 :𝐸𝐶→𝑇𝐶: a total typing function that maps collection expressions to types
    • fix :𝐶→𝐶, a transformation from a value into an equivalent1 one that is fixed
   We additionally define: fixed(𝑐)≜∀𝑐′∈𝐶.𝑐++𝑐′=𝑐 and∅∈𝐶 is identity on the RHS of++.
   We constrain𝐿𝐶 via the following well-formedness conditions:
                                  ∀𝑒∈𝐸𝐶.⟦𝑒⟧𝐶∈ type𝐶(𝑒)∧⌊⟦𝑒⟧𝐶⌋𝐶 =𝑒
                                      ∀𝑐∈𝐶. fixed(fix(𝑐))∧𝑐++∅=𝑐
                             ∀𝜏∈𝑇𝐶,𝑐∈𝜏,𝑐′,𝑐′′∈𝐶.𝑐++𝑐′=𝑐′′ =⇒𝑐′′∈𝜏
   Thelanguage ofcollectionsinvolvesbothmathematicaland syntacticrepresentations.Ourdefi-
nitionofcollectionsiscenteredaroundcollectionvalues,whichare theunderlyingmathematical
objectsbeingmanipulated.Atthesyntaxlevel,werepresentthesewithcollectionexpressions,
which canbe lifted tovalues via adenotational semantics, andthen lowered backdown to syntax
using the⌊⌋𝐶 function. We also define a typing function type𝐶 that maps collection expressions to
types, which are simply sets of collection values.
   Akey differencebetweenthe Flomodel andotherstreaming semantics[19]is thattheconcate-
nation functiondoesnot need to follow a partial order over collection types, or satisfy algebraic
propertieslikecommutativityorassociativity.What does interestusisthequestionofwhenthe
concatenation function reaches a fixpoint. The fixed predicate identifies a collection value such
that no more data can be added to it, which we will leverage to define streaming progress.
   Collections can take on a variety of forms. A common collection in streaming systems is the
ordered sequence, which captures an ordered list of elements. But collections could also be multi-
sets—asinstreamingextensionstoSQL[11]—orsets,asinDedalus[6]—whereorderoftendoesnot
affect semantics. A “collection” can even be a single value where “concatenating” to the collection
updatesthevalue—asin ourlattice_sum resultinSection2.Wewilllayout detailedexamplesof
concrete collection types in Section6.

1Thedefinitionofequivalenceisuptothecollection(forexample,concatenatingastreamterminatororsettingamaximum
size), and determines the guarantees provided by streaming progress (Definition3.3)


Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 7

Flo: A Semantic Foundation for Progressive Stream Processing                                                                                          9:7

3.3   Stream Types and Boundedness
Collectionsdescribethevaluesthatarebeingprocessedbyoperators,butourdiscussionsofarhas
beenmorereminiscentofbatch processingthanstreaming.Ouruniqueinterestinstreamingisthe
evolutionofcollectionsovertime.Inourmotivation,weidentifiedtwokeyaspectsofastreaming
program’s behavior with respect to time:eagerexecution makes it possible to correctly process
newly-arrived dataon an inputto getan updatedoutput, andstreamingprogress ensures that
the program will not unexpectedly block on a collection becoming fixed.
  To formally define streaming progress later in this section, we need to add a layer on top of
collection types, which we call stream types. In our model, the key property we care about is
whether a collection value will eventually becomefixed (using the definition from Section3.2),
orifitmayneverbecome that.Tocapturethis, weuseaboundednessflaginspiredbyworkin
databases [43], which is either Bounded or Unbounded. We define a stream type as a pair of a
collection type and a flag on the left of Figure2. We will see stream types in action in Section3.6.

        ⟨stream-type⟩ ::=( ⟨T⟩,B |U)                       reflexive-subtype         bound-subtype
                                                         𝑆≤𝑆                         (𝐶,B)≤(𝐶,U)

  Fig. 2. The grammar for stream types, where𝑇∈𝑇𝐶, and the subtyping relationship for stream types.

  Note that collection expressions are not typed directly to a stream type, instead stream types
are used as markers on inputs and outputs of a Flo program. We also have a simple subtyping
relationship,whereastreamtypethatisdeclaredasboundedcanbeusedinanunboundedcontext,
becauseanunboundedstreamhasnorestrictionsonhowthecollectionvaluebehavesovertime.We
listthetypingruleforthisrelationshipontherightofFigure2,where ≤ isasubtypingrelationship
we will use in the rules for composing operators.

3.4   Operators
Floprogramstransforminputcollectionsintooutputcollections.Thistransformationiscarriedout
byoperatorsthatconsumedatafromseveralinputcollectionstoupdateoutputcollections.Inthis
section,welayoutthefamilyofoperatorlanguages𝐿𝑂,whichcapturesFloprogramswithasingle
operator. Because programs writtenin this languagefit the general structureof the Flo event loop,
wewillusethislanguagetolayoutallthekeypropertiesweaimtoproveaboutFlo.InSection4,
wewillextend thislanguageto𝐿𝐺 tocapturethe compositionofoperatorsintoadataflowgraph.
  We will use the notation[𝐶] to represent tuples whose elements are each in C (and similarly
for[𝐸𝐶]),whichdenoteshaving multipleinputsoroutputs.Wewillalsodenote𝑇𝑆 tobetheset
ofall streamtypes and[𝑇𝑆] tobe atuple ofmanystream types.Tuplesof streamtypes followan
element-wise subtyping relationship.
  We define an operator language𝐿𝑂 =(𝐿𝐶,𝐸𝑂,→𝛿,𝑂𝑅𝐷𝑂,⊢𝑂) as a tuple of:
    •𝐿𝐶 =(𝐶,++,𝐸𝐶,𝑇𝐶,⟦⟧𝐶,⌊⌋𝐶,type𝐶,fix): a well-formed collection language
    •𝐸𝑂: a language of operator expressions, which are syntactic objects
    •(𝐼,𝑒𝑜)→𝛿(𝐼,𝑒𝑜,𝑂), a small-step operational semantics where𝐼,𝑂∈[𝐶] and𝑒𝑜∈𝐸𝑂
    •(𝐼,𝑒𝑜)≺𝑂(𝐼,𝑒𝑜)∈𝑂𝑅𝐷𝑂, a set of partial orders on collections where𝐼∈[𝐶] and𝑒𝑜∈𝐸𝑂
       (for some operators, we will omit the operator expression in the partial order)
    •⊢𝑂:𝑒𝑂 :(𝜏𝑆 ↩→𝜏𝑆,≺𝑂) atypingrelationbetweenelements𝑒𝑂∈𝐸𝑂,streamtypes𝜏𝑆∈[𝑇𝑆],
       and partial orders≺𝑂∈𝑂𝑅𝐷𝑂
  Weaugmentthiswiththefollowingdefinitions:Given𝐿𝑂 =(𝐿𝐶,𝐸𝑂,→𝑂,𝑂𝑅𝐷𝑂,⊢𝑂),wedefine:
    • The set of operator types:𝑇𝑂 ={𝜏𝑖 ↩→𝜏𝑜,≺|𝜏𝑖,𝜏𝑜∈[𝑇𝑆]∧≺∈𝑂𝑅𝐷𝑂}


                                Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 8

9:8                                                                              Shadaj Laddad, Alvin Cheung, Joseph M. Hellerstein, and Mae Milano

    • The small-step relation→𝑂={((𝐼,𝑒,𝑂),(𝐼′,𝑒′,𝑂++𝑂′))|(𝐼,𝑒)→𝛿(𝐼′,𝑒′,𝑂′)}
    • The typing relation on small-step configurations:
       ⊢𝑂𝑒 :((𝜏𝑖,𝐵𝑖)... ↩→(𝜏𝑜...,𝐵𝑜),≺𝑂)   𝐼∈(𝜏𝑖×...)   𝑂∈(𝜏𝑜×...)
                              ⊢→:(𝐼,𝑒,𝑂) :(𝜏𝑖... ↩→𝜏𝑜...,≺𝑂)
   We further constrain𝐿𝑂 via the following well-formedness condition:
                            ∀≺∈𝑂𝑅𝐷𝑂.≺  is finite and downwards-closed
   We also require,∀𝑒,𝑒′∈𝐸𝑂,𝜏∈𝑇𝑂,𝐼,𝐼′,𝑂,𝑂′∈[𝐶].⊢→(𝐼,𝑒,𝑂) :𝜏∧(𝐼,𝑒,𝑂)→𝑂(𝐼′,𝑒′,𝑂′)
(For all well-typed expressions which step):
    •→𝑂 must be confluent
    •⊢→(𝐼′,𝑒′,𝑂′) :𝜏 (type preservation)
    •𝜏 =(...,≺) =⇒(𝑒′,𝐼′)≺(𝑒,𝐼) (steps reduce the operator or its inputs)
   Letusbreakdowntheintuitionbehindtheseproperties.Everyoperatorhasatypewithseveral
input stream types and output stream types. The semantics of each operator are defined by the
small-steprelation→𝛿,wheretheinputandoperatorexpression(whichmaycarrystate)areused
toproduceanupdatedinput,operatorexpression,andanoutputcollection.Thesmall-steprelation
→𝑂 transforms this relation into a classic operationalsemantics form, where theoutput generated
by→𝛿 is concatenated to theexisting output (this concatenated form will be key to Definition3.1).
   A key property of operators is the confluence of→𝑂. In Flo, wedonot require there to be a
uniquesmallstepthat canbetakenfora given inputandoperatorexpression.Forexample,when
processinga setof values,anoperator maychooseto processthem inanyorder. Butconfluence
guaranteesthatthereexistssomelaterstate(𝐼′,𝑒′,𝑂′) whichalltracesofsmallstepsstartingfrom
(𝐼,𝑒,𝑂) will eventually reach. For operators that do have this non-determinism, proofs of this
property typically involve a commutativity argument over the order of processing inputs.
   Each operator also has a partial order over the operator expression and its inputs≺, which is
providedbythetypingrelation⊢𝑂 andmustbepreservedacrosssmall-steps.Wecanusethisto
prove our first property on operators in𝐿𝑂, that they always reach a stuck state in finite steps:
   Lemma3.1(OperatorStuckState).  Given an operator𝑜𝑝, for all input states𝐼 and output states
𝑂, there is a finite number of small steps that can be taken before no more small steps can be applied.
   Proof. We leverage the partial order for this operator≺. Since there are a finite number of
operator expressionsand collection valuessmaller than theinitial state, andeach step reducesthe
expression orits input,and theorder is preserved across steps,there mustbe afinite numberof
totalstepsthatcanbetakenbeforeeithernostepappliesorthereisnosmalleroperatororinputin
the partial order.                                                                                     □

   Notethatourdefinitionforstuckstatedoesnotrequiretheexpressiontobereducedtosome
terminatingform,suchastheinputsallbeingempty.Weonlyrequirethatnomorestepscanbe
taken,whichallowsustofurtherloosentherequirementsforcollections;thereisnoneedtodefine
auniquebottomvalue,forexample.Combinedwiththeconfluenceofsmall-steps,thisimpliesthat
every operator will eventually reach a unique stuck state.

3.5   Eager Execution
Flohingesontwokeypropertiesthatenablesafeandprogressiveexecutionoverstreaminginputs:
eager execution and streaming progress. The first guarantees that if new data arrives after
partial inputs have already been processed, then we can safely resume the execution of the Flo
program while arriving at a deterministic result. The second guarantees the program will never


Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 9

Flo: A Semantic Foundation for Progressive Stream Processing                                                                                          9:9

blockon thefixedness ofan inputthat may neverbecome fixed. InSection4,wewill provethat
both of these properties are true of well-typed graphs and Flo as a whole.
   Eager execution avoids the situation where all input to an operator must be computed before
the operator can begin execution. Instead, we require all operators to prove that they can begin
processingpartialinputsandreceiveadditionaldatalaterviaconcatenation,whilestillproducing
the same result as if all the data was present from the start. This enables flexibility for scheduling
and ensures that the outputs of a Flo program are deterministic even if an arbitrary number of
small steps are run during each iteration of the event loop.
Definition3.1(EagerExecution). Consideranoperator𝑜𝑝∈𝐸𝑂.Forallinputs𝐼∈[𝐶],outputs
𝑂∈[𝐶], concatenated collectionΔ∈[𝐶], updated operator𝑜𝑝′∈𝐸𝑂, input collection,𝐼′∈[𝐶],
output collection𝑂′∈[𝐶] such that
                     (𝐼,𝑜𝑝,𝑂)→𝑂(𝐼′,𝑜𝑝′,𝑂′) and(𝐼++Δ,𝑜𝑝,𝑂)→𝑂(𝐼′′,𝑜𝑝′′,𝑂′′)
   there exists a stuck state(𝐼′′′,𝑜𝑝′′′,𝑂′′′) such that

                                    (𝐼′++Δ,𝑜𝑝′,𝑂′)→𝑂∗(𝐼′′′,𝑜𝑝′′′,𝑂′′′)
   and
                                     (𝐼′′,𝑜𝑝′′,𝑂′′)→𝑂∗(𝐼′′′,𝑜𝑝′′′,𝑂′′′)
   Note that a simple inductive extension of this property tells us that we can introduce a single
additionalchunkof dataofanysizeinterleaved withexecutingsmallsteps fortheoperator,and
stillendupinthesamestuckstateasifthedatawaspresentfromthestart.Afurtherinductive
argument saysthatif wehaveseveral chunksto concatenate,they canbe introducedatany time
interleaved with steps of the operator while still arriving at the same stuck state.

3.6   Streaming Progress
Streaming progress is a more challenging property to define. Unlike classic correctness properties
suchasdeterminism,streamingprogressisfocusedonensuringthatoutputsarekept fresh with
respect to certain inputs. Let us first formally define freshness asoutputmaximality.
Definition 3.2 (Output Maximality).  We are given a well-typed (according to⊢→) small-step
configuration((𝑖0...𝑖𝑛),𝑜𝑝,𝑂) and well-typed final outputs𝑜′0...𝑜′𝑚 such that:
   ((𝑖0,...,𝑖𝑛),𝑜𝑝,𝑂)→𝑂∗(𝐼′,𝑜𝑝′,(𝑜′0,...,𝑜′𝑚)) and(𝐼′,𝑜𝑝′,(𝑜′0,...,𝑜′𝑚)) is stuck.
   Then the given output𝑜′0...𝑜′𝑚 ismaximal if
              ((fix(𝑖0),...,fix(𝑖𝑛)),𝑜𝑝,𝑂)→𝑂∗((𝑖′′0,...,𝑖′′𝑛),𝑜𝑝′′,(fix(𝑜′0),...,fix(𝑜′𝑚)))
   and((𝑖′′0,...,𝑖′′𝑛),𝑜𝑝′′,(fix(𝑜′0),...,fix(𝑜′𝑚))) is stuck.
   Consider our motivating example. Some operators (scan) can satisfy Output Maximality for all
inputs because at any point in the execution, we can reach a state where all outputs are released,
andnomoreoutputswouldbereleasediftheinputbecamefixed.Butotheroperators(fold)cannot
satisfyOutputMaximalityforallinputs,becauseweneverreachastatewithanyoutputsreleased
unless the input is fixed, at which point the output is released (and hence changes).
   Thisiswherethestreamtypesweintroducedearliercomein,whichwillallowustodefinea
property for streaming progress that works for all operators. Each operator annotates its inputs
andoutputswithboundednessflags.Intuitively,ifaninputisunbounded,wewanttoprevent
the problem we have illustrated with fold: we do not want the operator to block until the input
becomesfixed. Bycontrast,ifaninput isbounded,itmay makesenseforanoperator (e.g., fold)
to withhold some outputs until the input becomes fixed.


                                 Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 10

9:10                                                                            Shadaj Laddad, Alvin Cheung, Joseph M. Hellerstein, and Mae Milano

   OutputMaximalityandstreamtypestogetherenableustoensurethatanoperatoralwayskeeps
its outputs as fresh as possible: bounded inputs are guaranteed to produce outputs (after becoming
fixed), as are unbounded inputs (since they do not block on fixedness).
   Finally, to enable composition across multiple operators, we want to derive restrictions on
the outputs from input properties. Once theboundedinputs are fixed, theboundedoutputs
mustbecomefixedinafinitenumberofstepstoavoidblockingdownstreamoperators.Withthat
intuition in place, we formally define streaming progress in terms of Output Maximality:
Definition 3.3 (Streaming Progress).  Consider a well-typed operator𝑜𝑝 with type⊢𝑂 𝑜𝑝   :
((𝐼0,𝐵𝐼,0)...(𝐼𝑛,𝐵𝐼,𝑛)) ↩→((𝑂0,𝐵𝑂,0)...(𝑂𝑚,𝐵𝑂,𝑚)). Consider all well-typed inputs𝑖0...𝑖𝑛∈𝐶
such that𝐵𝐼,𝑗 =B =⇒  fixed(𝑖𝑗) (the boundedinputs are fixed).
   Let us also consider all well-typed initial outputs𝑂 and final outputs𝑜′0...𝑜′𝑚, such that:
                               ((𝑖0,...,𝑖𝑛),𝑜𝑝,𝑂)→𝑂∗(𝐼′,𝑜𝑝′,(𝑜′0,...,𝑜′𝑚))
   and(𝐼′,𝑜𝑝′,(𝑜′0,...,𝑜′𝑚)) is stuck. Then the operator𝑜𝑝 satisfies streaming progress if:
    •𝑜′0...𝑜′𝑚 aremaximal for the operator𝑜𝑝 with inputs𝑖0...𝑖𝑛 and initial outputs𝑂
    •∀𝑗.𝐵𝑂,𝑗 =B =⇒  fixed(𝑜′𝑗) (the boundedoutputs are fixed)
   Any operator in an implementation of Flo must satisfy these properties. We will show in the
next section that these properties are automatically preserved when composing operators into
graphs, which alleviates any further proof burden for the implementation.

4   Composition with Graphs
Programs in Flo are formed by composing operators into a directed-acyclic graph, where each
node is an operator and each edge captures an intermediate collection of data elements. In Flo,
we express these directed acyclic graphs as expressions of𝐿𝐺 through recursive constructs for
sequential and parallel composition, such as in Figure3.

                                                                  (
                                                                     (
                                                                       (({_} map); ({_} scan)) |
                                                                        ({_} filter));
                                                                     ({_} join));
                                                                  (({_} map) | ({_} map))


Fig. 3. A dataflow graph and its decomposition into an expression in our language (with parentheses for
clarity). Magenta boxes represent parallel composition and blue boxes represent sequential composition.

   Unlikebefore,thegraphlanguage𝐿𝐺 isnotparameterizedonanynewdefinitions,andcanbe
directlylayeredonanyinstanceofanoperatorlanguage𝐿𝑂 =(𝐿𝐶,𝐸𝑂,→𝛿,𝑂𝑅𝐷𝑂,⊢𝑂).Welayer
on this language a few additional constructs:
    •𝐸𝐺: the language of graph expressions, which are syntactic objects (Figure4)
    •⊢  :𝑒𝐺 :(𝜏𝑆 ↩→𝜏𝑆,≺) a typing relation between elements𝑒𝐺∈𝐸𝐺, stream types𝜏𝑆∈[𝑇𝑆],
       and partial orders≺∈Ð𝑛∈N(𝑂𝑅𝐷𝑂)𝑛 (Figure5)
    •𝑒𝑔→Δ(𝑒𝑔,𝑂), a small-step operational semantics where𝑂∈[𝐶] and𝑒𝑔∈𝐸𝐺 (Figure6)
   Wewillalsoaugmentthiswiththesmall-steprelation:→={((𝑔,𝑂),(𝑔′,𝑂++𝑂′))|𝑔→Δ(𝑔′,𝑂′)}
   Sequentialcompositionpassestheoutputsofonesubgraphintotheinputsoftheother,andisthe
primarywaythatoperatorscanbechainedtogetherinaFloprogram.Parallelcompositionmakes


Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 11

Flo: A Semantic Foundation for Progressive Stream Processing                                                                                         9:11

it possible to capture portions of the graph where several operators can be run independently on
separatesetsofinputstoproduceseparateoutputs.WelayoutthegrammarforgraphsinFigure4.

                                             𝑒 ::=𝑒|𝑒|𝑒;𝑒|{𝑆}[𝑂]

                Fig. 4. The grammar for graphs of a Flo program, where𝑆∈[𝐸𝐶] and𝑂∈𝐸𝑂.

   Note that we include a state term𝑆, which collects inputs to an operator. This term will be
essential when formalizing our small-step semantics, which needs to reason about buffered inputs
at an arbitrary position in a graph. Our type system models graphs in terms of their input and
outputstreamtypes,andapartialorderoverinputslikeforoperators.Welistthetypingrulesfor
graphsinFigure5andsmall-stepoperationalsemanticsinFigure6.Inoursemantics,wewilluse  ·
to denote tuple concatenation, when dealing with types or values.

    seqence
     ⊢𝑒1 :(𝐼1 ↩→𝑂1,≺1)  ⊢𝑒2 :(𝐼2 ↩→𝑂2,≺2)                       par
                          𝑂1≤𝐼2                                  ⊢𝑒1 :(𝐼1 ↩→𝑂1,≺1)  ⊢𝑒2 :(𝐼2 ↩→𝑂2,≺2)
                 ⊢𝑒1;𝑒2 :(𝐼1 ↩→𝑂2,≺1)                                ⊢𝑒1|𝑒2 :(𝐼1·𝐼2 ↩→𝑂1·𝑂2,≺1·≺2)

                   operator
                    ⊢𝑂𝑜𝑝 :(𝐼 ↩→𝑂,≺)   𝐼 =((𝑆0,𝐵0),...(𝑆𝑛,𝐵𝑛)) ∀𝑖. type𝐶(𝑠𝑖)=𝑆𝑖
                                        ⊢{(𝑠0,...,𝑠𝑛)}[𝑜𝑝] :(𝐼 ↩→𝑂,(≺))


                               Fig. 5. Type semantics for graphs of a Flo program.



                                      inputs(𝑒1;𝑒2)≜ inputs(𝑒1)
                                     inputs(𝑒1|𝑒2)≜ inputs(𝑒1)·inputs(𝑒2)
                                   inputs({𝐼}[𝑜𝑝])≜𝐼


                  setinput(𝑒1;𝑒2,𝐼)≜ setinput(𝑒1,𝐼);𝑒2
           setinput(𝑒1|𝑒2,𝐼1·𝐼2)≜ setinput(𝑒1,𝐼1)| setinput(𝑒2,𝐼2)
             setinput({𝐼}[𝑜𝑝],𝐼′)≜{𝐼′}[𝑜𝑝]                                                     when|𝐼|=|𝐼′|

           seqence-left                                                            seqence-right
                                   𝑒1→Δ(𝑒′1,𝐼′)                                        𝑒2→Δ(𝑒′2,𝑂′)
           (𝑒1;𝑒2)→Δ(𝑒1′;setinput(𝑒2,⌊⟦inputs(𝑒2)⟧𝐶++𝐼′⌋𝐶),∅)                      (𝑒1;𝑒2)→Δ(𝑒1;𝑒′2,𝑂′)

  par-left                               par-right                              operator
         𝑒1→Δ(𝑒′1,𝑂′1)                          𝑒2→Δ(𝑒′2,𝑂′2)                       (⟦𝐼⟧𝐶,𝑜𝑝)→𝛿(𝐼′,𝑜𝑝′,𝑂′)
  (𝑒1|𝑒2)→Δ(𝑒′1|𝑒2,𝑂′1,∅)                (𝑒1|𝑒2)→Δ(𝑒1|𝑒′2,∅,𝑂′2)                 ({𝐼}[𝑜𝑝])→Δ({⌊𝐼′⌋𝐶}[𝑜𝑝′],𝑂′)


                            Fig. 6. Small-step semantics for graphs of a Flo program.

   Before we continue, let us prove that graphs satisfy preservation.


                                  Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 12

9:12                                                                            Shadaj Laddad, Alvin Cheung, Joseph M. Hellerstein, and Mae Milano

   Lemma4.1(GraphPreservation).  Givenagraph𝑔oftype(𝐼 ↩→𝑂,≺),outputstate𝑆 =(𝑠0...𝑠𝑛),
and updated output state𝑆′ =(𝑠′0...𝑠′𝑛) such that𝑂 =((𝑇0,_)...(𝑇𝑛,_)) and∀𝑖. type𝐶(𝑠𝑖) =𝑇𝑖, if
(𝑔,𝑆) takes a step to(𝑔′,𝑆′), then𝑔′ is also of type(𝐼 ↩→𝑂,≺) and∀𝑖. type𝐶(𝑠′𝑖)=𝑇𝑖.

   Proof. We can prove this by structural induction over the graph.
   BaseCase: A graph with a single operator. By operator preservation, we know that the type
of𝐼 is the same as the type of𝐼′, that𝑜𝑝′ has the same type, and that𝑂′ has the same type as𝑂.
Therefore, the graph as a whole has the same type and the output is of the correct type.
   InductiveStep: Proof by cases:
   SequentialComposition: Ifwe applythe sequence-left rule,then byinduction we know that
𝑒1 hasthesametypeas𝑒′1,and𝐼′ hasthesametypesastheinputsof𝑒2.Therefore,whenweset
the inputs of𝑒2 to𝐼′, we preserve the typing (due to well-formedness of the denotational lifting
and syntactical lowering). Since the output is unchanged, we satisfy preservation.
   Ifweapplythesequence-rightrule,thenbyinductionweknowthat𝑒2 hasthesametypeas𝑒′2,
and the output has the same type due to concatenation. Therefore, we satisfy preservation.
   Parallel Composition: In both rules, we use induction to know the types of both sides are
preserved. The typing rule for parallel simply composes these types, so we are done.            □

4.1   Graph Stuck State
Now,letusextendthepropertieswerequireofoperatorstographsasawhole.First,wewillextend
Operator Stuck State (Lemma3.1).

   Lemma 4.2 (Graph Stuck State).  Given a graph initialized with a fixed set of input collection
values, running the graph will eventually reach a stuck state where no additional steps can be taken.

   Proof. We can prove this by structural induction over the graph.
   BaseCase:A graph with a single operator. By Lemma3.1.
   InductiveStep: A graph such that its subgraphs satisfy Graph Stuck State. Proof by cases:
   SequentialComposition:Thereareonlytwosmallstepsthatcanbetakenatanypoint,forthe
leftor right.If weonlystep oneof thetwosubgraphs, byinduction thatside willeventuallyreach
astuckstate.Iftheleft sidereachesastuckstate,thenrunningtherightside willneverre-enable
theleftsidebythedefinitionof→.Iftherightsidereachesastuckstate,wemaybeabletorunthe
left sidewhich mayre-enable the rightside, butthis will cycleback tothe left andeventually the
left side will be stuck. Therefore, the graph as a whole will reach a stuck state.
   ParallelComposition:Thetwosubgraphsareindependent,andsobytheinductivehypothesis
we know that both will eventually reach a stuck state, and their composition is a stuck state.   □

4.2   Determinism and Eager Execution
Themost significantchange betweenreasoning aboutoperators inisolationand thecomposition
of them is thatat any point when executing a graph,theremay be multiple small steps foreach
operatorthatcanbetaken.Weneedtoprovewecannon-deterministicallyexecutetheseoperators
whilearrivingatthesameoutput.Toprovethisforallgraphs,wewillalsoneedtoextendEager
Executionto graphs.Theseproofs aremutuallyrecursive,sowe willprovethem simultaneously.
Bothour definitionslooknearly identicaltothosefor operators,justwith theuseof thegeneral
small step relation rather than only for operators.
   A quick aside on notation. In this section, we will use the shorthand{𝐼}𝑔 to denote a graph𝑔
whose inputs are set to𝐼, so{𝐼}𝑔= setinput(𝑔,𝐼).



Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 13

Flo: A Semantic Foundation for Progressive Stream Processing                                                                                         9:13

Definition4.1(Determinism). Considera graph𝑔.For allinputs𝐼 andinitial outputs𝑂 wherea
small step for({𝐼}𝑔,𝑂) exists, there exists a later state𝑔′, inputs𝐼′, and outputs𝑂′ such that in
every trace of small steps({𝐼}𝑔,𝑂)→∗({𝐼′}𝑔′,𝑂′) we eventually reach this later state.
  Note that combined with stuck states (Lemma4.2), this implies that every graph will eventually
reach aunique stuck state. This is because we can always take a series of steps to arrive at the
same later state, and eventually we will reach a point where no more steps can be taken.
Definition4.2(EagerExecution). Consideragraph𝑔.Forallinputstreams𝐼,outputstreams𝑂,
delta setΔ, updated graph𝑔′, input stream,𝐼′, and output stream𝑂′ such that

                                            ({𝐼}𝑔,𝑂)→({𝐼′}𝑔′,𝑂′)
there exists a stuck state𝑓 such that

                              ({𝐼++Δ}𝑔,𝑂)→∗𝑓 and({𝐼′++Δ}𝑔′,𝑂′)→∗𝑓
  Lemma 4.3.  Consider any expression. It must satisfy:
  (1)Determinism
  (2)Eager Execution
  Proof. We can prove this by structural induction over the graph.
  BaseCase:A graph with a single operator.
  (1)By confluence of →𝑂.
  (2)By Definition3.1.
  InductiveStep: A graph such that its subgraphs satisfy both (1) and (2). Proof by cases:
  SequentialComposition: a graph of form𝑎;𝑏
  (1) Weknowthatthereisatleastonesmall-stepthatcanbetaken,andtheonlyoptionsareto
       recursivelystep𝑎 or𝑏.Let usdefine an execution trace thatcaptures anordered sequenceof
       small-stepstotake.Thistracewillhavetheform“(𝑎𝑖|𝑏)+”,witheachelementdirectingusto
       takethecorrespondingsmallstepcorrespondingtothenamedsubgraph,withtheindicesfor
       𝑎 countingupfrom 0.Givenatrace𝑡 =“𝑠0...𝑠𝑛”,wedefine→𝑡 totakethestepsinorder.For
       eachinstanceof𝑎𝑖,theindexletsusuniquelyidentifythesmall-steprulethatwillbeapplied
       to𝑎.For𝑏,the tokenrepresents takinganysmall-step on𝑏.We willcall atraceafter which
       no more steps can be taken a terminating trace.
       Next,letus defineequivalencebetweena pairoftraces𝑡1 and𝑡2.Two tracesareequivalent
       if executing both on the same initial state results in the same final state, even with non-
       deterministic selection of which small-step to run for each𝑏. We will prove that for any pair
       of terminating traces𝑡1 and𝑡2, the traces are equivalent.
       Consider a trace of the form “prefix𝑏𝑎𝑖...𝑎𝑗𝑏∗”. The execution of this looks like
                  ({𝐼𝑝𝑎}𝑎𝑝;{𝐼𝑝𝑏}𝑏𝑝,𝑂𝑝)→prefix({𝐼𝑎}𝑎;{𝐼𝑏}𝑏,𝑂)→𝑏({𝐼𝑎}𝑎;{𝐼′𝑏}𝑏′,𝑂′)
                           →𝑎𝑖...𝑗({𝐼′𝑎}𝑎′;{𝐼′′𝑏}𝑏′,𝑂′)→∗𝑏({𝐼′𝑎}𝑎′;{𝐼′′′𝑏}𝑏′′,𝑂′′)
       First,bythedefinitionof→Δ,weknowthat𝐼′′𝑏 =𝐼′𝑏++Δ𝑖++Δ𝑖+1....Then,inductivelyEager
       Executionappliedto𝑏 letsusrewrite“𝑏𝑎𝑖...𝑎𝑗𝑏∗”to“𝑎𝑖...𝑎𝑗𝑏∗”(notethatthenumber
       of trailing𝑏 in the rewritten suffix may be arbitrary), because the execution of𝑎𝑖...𝑎𝑗
       simply introduces additional data for𝑏 to process. This results in the following execution
                               ({𝐼𝑝𝑎}𝑎𝑝;{𝐼𝑝𝑏}𝑏𝑝,𝑂𝑝)→prefix({𝐼𝑎}𝑎;{𝐼𝑏}𝑏,𝑂)
                   →𝑎𝑖...𝑗({𝐼′𝑎}𝑎′;{𝐼𝑏++Δ𝑖++Δ𝑖+1...}𝑏,𝑂)→∗𝑏({𝐼′𝑎}𝑎′;{𝐼′′′𝑏}𝑏′′,𝑂′′)


                                 Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 14

9:14                                                                            Shadaj Laddad, Alvin Cheung, Joseph M. Hellerstein, and Mae Milano

       Therefore, the trace prefix𝑏𝑎𝑖...𝑎𝑗𝑏∗ is equivalent to prefix𝑎𝑖...𝑎𝑗𝑏∗.
       If we repeatedly apply this rewrite to both tracesto pull all𝑎𝑖 to the front, we will arrive at
       two traces of the form𝑎0...𝑎𝑛𝑏∗ and𝑎0...𝑎𝑚𝑏∗. We know that both original traces are
       terminating,thereforeafterrunning𝑎0...𝑎𝑛 and𝑎0...𝑎𝑚 eventhoughthe𝑏sbetweenthe
       elementshavebeenremoved,therewillbenomoresmallstepsthatcanbetakenon𝑎.By
       determinismfrominduction,since𝑎 hasterminatedthetraces𝑎0...𝑎𝑛 and𝑎0...𝑎𝑚 result
       in the same state and are equivalent. Similarly, because our rewrites preserve equivalence,
       by determinism we know that after running𝑏∗ on both traces, we will reach the same final
       state. Therefore, the traces are equivalent and𝑎;𝑏 satisfies determinism.
   (2)We can split into cases based on the small step that could be taken.
       Case1:Thesmallstepison𝑎.ByDefinition4.2,weknowthatwecanintroducethedelta
       beforeorafterthesmallstepon𝑎 andthencontinuerunningsmallstepsfor𝑎 untilreaching
       the common later state for𝑎, which is also our overall later state𝑓.
       Case 2: The small step is on𝑏. If we run the small step, then introduce the delta, let the
       state immediately after introducing the delta be𝑓. If we instead first introduce the delta,
       thenrun𝑏, thestateafter isalso𝑓 because runningthesmall stepfor𝑏 isunaffectedbythe
       introduction of the delta.
   ParallelComposition: a graph of form𝑎|𝑏
   (1) The small steps for a parallel composition just run the small steps for either side, which are
       independent. Therefore by induction both sides will step to a deterministic state.
   (2) Inparallelcomposition,theintroductionofadeltaresultsinindependentchunksbeingadded
       to both sides. If we step the graph first, that just steps one of the sides, so the inductive
       hypothesis holds on one of the sides and the other side is unaffected.
                                                                                                                   □

4.3   Streaming Progress
   Lemma 4.4 (Streaming Progress for Graphs).  Consider a well-typed graph𝑔 with type⊢
𝑔  :(((𝐼0,𝐵𝐼,0)...(𝐼𝑛,𝐵𝐼,𝑛)) ↩→((𝑂0,𝐵𝑂,0)...(𝑂𝑚,𝐵𝑂,𝑚)),≺) such that inputs(𝑔) =𝑖0...𝑖𝑛 and
𝐵𝐼,𝑗 =B =⇒  fixed(𝑖𝑗). Consider all well-typed outputs𝑂 and𝑜′0...𝑜′𝑚 such that
                                          (𝑔,𝑂)→∗(𝑔′,(𝑜′0,...,𝑜′𝑚))
and(𝑔′,(𝑜′0,...,𝑜′𝑚)) is stuck. Then𝑜′𝑗 must be fixed if𝐵𝑂,𝑗 =B and there must also be a stuck state
                       ({(fix(𝑖0),...,fix(𝑖𝑛))}𝑔,𝑂)→∗(𝑔′′,(fix(𝑜′0),...,fix(𝑜′𝑚)))
   Proof. We can prove this by structural induction over the graph.
   BaseCase: A graph with a single operator. By Definition3.3.
   InductiveStep: Proof by cases:
   SequentialComposition:WecanapplyLemma4.3toonlyfocusontraceswherewerunthe
left half until stuck state and then the right half. First, we apply streaming progress to the left half,
which tells usthat we will outputintermediate collections such thateach output with a bounded
streamtypewillhaveafixedvalue.Thissatisfiesthepremiseforinductionontherightsubgraph,
so we can apply streaming progress again to know that each bounded output will be fixed. Using
thesameproofstructure,weknowthattheintermediatecollectionswillbemaximalwithrespect
to the unbounded inputs, so the final outputs will be maximal as well.
   ParallelComposition: Because both sides are independent, we can simply use induction on
eachside. Becauseallboundedoutputs willbefixedand alloutputsare maximalwithrespectto
the unbounded inputs, we satisfy streaming progress.                                             □


Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 15

Flo: A Semantic Foundation for Progressive Stream Processing                                                                                         9:15

5   Nested Streams and Graphs
Sofar,we haveconsidereddataflow programswith adirectpath ofoperatorsfrom eachinputto
the outputs. But for many applications, it is necessary to perform stateful, iterative computations
over an input stream. In Flo, we tackle this using constructs fornestedstreamsandgraphs.
  Before we dive into formal semantics, let us lay out a high-level overview of our approach to
nesting.First,weintroducenestedstreams,whichareaspecifictypeofstreamthatencapsulate
severalsmallerstreams.Wedefineasetofrestrictionsforhowoperatorsmustgeneratesuchnested
streams, in particular how boundedness of the inner streams is enforced.
  Once we have nested streams, we need an operator that can transform them. This is where
thenestoperatorcomesin,whichmakesitpossibletotransformanestedstreambydefininga
nested Flo graph that should be run on each inner stream. We introduce the write_defer and
read_deferoperators,whichcanbeusedtopassstateacrosstheiterationsforeachinnerstreamto
enableiterativecomputation.Weprovethattheseoperatorssatisfyallthecoreoperatorproperties,
therefore preserving the high-level guarantees we have established for Flo.

5.1   Nested Streams
OurdefinitionofFlosofarhasdealtonlywithanabstractnotion ofcollectionsandoperators.But
the nest operator isa concrete instance, and so we also need a concrete collection typefor it to
consumeandproduce.Furthermore,thiscollectiontypemuststorenestedstreamsinawaythat
preserves boundedness properties and allows the inner graph to manipulate the inner streams.
  Totacklethis,weintroducetheorderedsequenceofstreamsinFigure7.Thiscollectiontype,
denoted[(𝑆0,...𝑆𝑛)] isparameterizedoverseveralinnerstreamtypes𝑆𝑖 =(𝐶𝑖,𝐵𝑖).Valuesofthis
type are stored as a list of tuples[(𝑐0,0,...𝑐0,𝑛),...,(𝑐𝑚,0,...𝑐𝑚,𝑛)], where each𝑐𝑖,𝑗 is a value of
type𝐶𝑗. The terminator symbol⊗ indicates the end of a stream.


                        [((𝐶1,𝐵1),...,(𝐶𝑚,𝐵𝑚))]≜{[(𝑐1,1,...),...,(𝑐𝑛,1,...)]|
                              ∀𝑖,𝑗𝑐𝑖,𝑗∈𝐶𝑗∧(𝑖 > 1∧𝐵𝑗 =B) =⇒  fixed(𝑐𝑖,𝑗)
                       }∪{[⊗,(𝑐1,1,...),...,(𝑐𝑛,1,...)]|
                              ∀𝑖,𝑗𝑐𝑖,𝑗∈𝐶𝑗∧(𝐵𝑗 =B) =⇒  fixed(𝑐𝑖,𝑗)}
                                            [⊗,...]++𝑥 =[⊗,...]
                                      [𝑐1,...,𝑐𝑛]++⊗=[⊗,𝑐1,...,𝑐𝑛]
                       [𝑐1,...,𝑐𝑛]++((𝑣1,...,𝑣𝑚),true)=[(𝑣1,...,𝑣𝑛),𝑐1,...,𝑐𝑛]
            [(𝑣1,...,𝑣𝑚),...,𝑐𝑛]++((𝛿1,...,𝛿𝑚),false)=[(𝑣1++𝛿1,...,𝑣𝑚++𝛿𝑚),...,𝑐𝑛]

        Fig. 7. The collection type and concatenation operator for the ordered sequence of streams.

  Theconcatenationoperatoronthiscollectiontypetakesanorderedsequenceofstreamsand
either theterminator⊗,thetupleofthebooleantrueandatupleofcollectionsvaluesmatchingthe
innerstreamtypes,oratupleofthebooleanfalseandatupleofconcatenationvaluescorresponding
to the right-hand side accepted by++ for each inner stream type. If the boolean flag is true, the
concatenation operator extends the collection with the tuple used as the new leftmost value. If it is
false, theoperator usesthe concatenation operatorof eachof the innerstream typesto extend the
existing leftmost collections with the new values.
  There is another key concern we need to address. Once a new tuple of collectionsis pushed into
theorderedsequence,noneoftheothertupleswillevergrowthroughconcatenation.Weneedto


                                Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 16

9:16                                                                            Shadaj Laddad, Alvin Cheung, Joseph M. Hellerstein, and Mae Milano

ensure that these finalized tuples satisfy the restrictions of the inner stream types; in particular
that they satisfy boundedness properties. To do this, we require that all tuples of collections after
the leftmost one have fixed collections for each bounded stream type.

5.2   Nesting Graphs
The nest operator maps nested streams by transforming their inner streams one-by-one using an
inner Flo graph. These inner graphs have special privileges: they can define iterative computations
bypassingdata acrossexecutionsonsubsequent innerstreams.Todo this,developersusepairs
of read_deferandwrite_deferoperatorswithmatchingkeys. Anydatasenttoa write_defer
operator will be emitted by the corresponding read_defer operator when processing the next
inner stream (for the first step, read_defer takes an initial value as a parameter).
   Beforewediveintotheformalsemanticsoftheseoperators,letuswalkthroughasimpleexample
toshowhownest,write_defer,andread_defercanbecombinedtoenableiterativecomputation.
We will implement a classic iterative algorithm where we are given a set of directed edges and
wanttocomputewhichnodesarereachablefromarootwithinafixedradius.Ouralgorithmstarts
with a single root node, and in a loop identifies the next “layer” of reachable nodes.













             Fig. 8. An example of identifying nodes within a fixed radius using nested graphs.

   First,weneedacollectiontypeforsetsofnodesandsetsofedges(usingstandardsemantics),
alongwithsomeoperatorsinspiredbyrelationalalgebra.Weomitthedetailedsemanticsforbrevity,
butthesearestraightforwardtodefine.Thejoinoperatortakesinasetofnodesandasetofedges,
and identifiesthe destination of alledges originating at anode in theinput set. The tee operator
consumes a single stream and emits a pair of streams, each duplicating the input.
   Next, we mustgenerate a stream-of-streams that drives thenestedgraph. For graph reachability
withinafixedradius𝑛,weneedtorun𝑛 iterationsoftheinnergraph.Toachievethis,weintroduce
a repeat_nested operator which consumes a stream and a naturalnumber singleton𝑘, and emits
a stream with𝑘 inner streams, each of which duplicates the contents of the input.
   Putting these operators together, we show how to implement this algorithm in Figure8. On
every iteration,we first collectthe nodes reachedup to theprevious iteration using read_defer,
withaninitial valueofjusttheroot node 0.Then,weemitthenextlayer ofreachablenodesand
also send them to write_defer to be used in the next iteration. In the output of this program, we
willhaveastreamofsetsofnodes,whereeachsetcontainsthenodesreachablefromtherootwith
increasing radii up to the fixed limit.
   InFlo,nestisastandardoperatorthatsatisfiesalltheproofobligations,soitcanbe...nested!
Thismakesitpossibletobuildarbitrarilycomplexnestedcycles.Forexample,wecantweakthe
graph reachability example toallow recomputing the reachability analysis with extendedradii. In
this algorithm, we can use the output from a previous query to “bootstrap” the next query, and
only run iterations to extend the radius rather than starting from scratch.


Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 17

Flo: A Semantic Foundation for Progressive Stream Processing                                                                                         9:17



















            Fig. 9. An example of graph reachability with a dynamic radius, using nested cycles.

   In this example program, we assume that the input edges have already been shaped into an
unbounded stream-of-streams where each inner stream contains the full set of edges2. The queries,
which represent expansionsof the radius, arealso a stream-of-streams where eachinner stream
isasingletoncontainingtheamounttoexpandtheradiusby.Weuseanewzipoperatortofeed
multiple nested streams into nest by tupling their inner streams pairwise.
   Weuseanewlastoperatortoextractthefinalsetemittedbyreachability,whichwedeferto
bootstrapthenextquery.Toinjectthesenodes,weuseanewoperatornest_oncewhichgenerates
aninfinitestream-of-streamswherethefirstinnerstreamcontainstheinputandtherestareempty.
Then,insidethereachabilitygraph,weuseunion(whichperformssetunion)toaddthebootstrap
nodes. Finally, we use repeat_nested as before to drive iterations of graph reachability.

5.3   Type Semantics
Now, we are ready to lay out the formal semantics for nested graphs, beginning with the type
semantics. First, we define the defer operators: write_defer takes a key as a parameter and
accumulates a bounded stream as input, and on the next iteration any matching read_defer with
thesamekeywillemittheaccumulatedcollection.Type-safetyfor theseoperatorsisabitmore
complex, since we need to ensure that there is a single write_defer for each key and that the
stream types being written match the types being read.
   To achieve this, we introduce a new pair of contexts𝑅 and𝑊 to our typing rules (⊢ and⊢𝑂)
whicheachstoreamapfromkeystostreamtypes.Wewillusecontext𝑊 substructurally,admitting
onlyexchange(butnotweakeningorcontraction)onthiscontext.Whentypinganestedgraph,
thesecontextsaresetto(arbitrary)identicalvalues,whichenforcesthatthesametypesarewritten
andread. Onthewrite-side,we alsoenforcethat eachkeyis writtenexactlyonceby splittingthe
𝑊 keys at each composition until there is one key isolated to each write_defer. For read_defer,
we have two variants because the optional second parameter stores a value to be emitted.
   Thenestoperatortakesagraph𝑔oftype𝐼 ↩→𝑂 withpartialorder≺𝑔.Eachstreamin𝑂 mustbe
boundedsothattheinnergraphfinishesinfinitetime.Theoperatoritselftakesastreamofstreams
and emits astream of streams, where the innertypes are𝐼 and𝑂 respectively.The boundedness
of the outer output (denoted𝑋) is the same as the outer input. We also include a variant of nest
with an additional parameter that stores the initial graph for the next iteration. We re-define our

2We could also consume the set of edges only once and “persist” them across iterations of the nested graph by sending a
copy across a defer cycle. But that adds complexity to this example that distracts from nested cycles.


                                 Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 18

9:18                                                                            Shadaj Laddad, Alvin Cheung, Joseph M. Hellerstein, and Mae Milano

                    seqence
                    𝑅;𝑊1⊢𝑒1 :(𝐼1 ↩→𝑂1,≺1)   𝑅;𝑊2⊢𝑒2 :(𝐼2 ↩→𝑂2,≺2)   𝑂1≤𝐼2
                                        𝑅;𝑊1,𝑊2⊢𝑒1;𝑒2 :(𝐼1 ↩→𝑂2,≺1)

                           par
                           𝑅;𝑊1⊢𝑒1 :(𝐼1 ↩→𝑂1,≺1)   𝑅;𝑊2⊢𝑒2 :(𝐼2 ↩→𝑂2,≺2)
                                 𝑅;𝑊1,𝑊2⊢𝑒1|𝑒2 :(𝐼1·𝐼2 ↩→𝑂1·𝑂2,≺1·≺2)

                operator
                𝑅;𝑊⊢𝑂𝑜𝑝 :(𝐼 ↩→𝑂,≺)   𝐼 =((𝑆0,𝐵0),...(𝑆𝑛,𝐵𝑛)) ∀𝑖. type𝐶(𝑠𝑖)=𝑆𝑖
                                    𝑅;𝑊⊢{(𝑠0,...,𝑠𝑛)}[𝑜𝑝] :(𝐼 ↩→𝑂,(≺))

   read-defer-value-type                                        read-defer-no-value-type
             type𝐶(𝑣)=𝐶         fixed(⟦𝑣⟧𝐶)                    𝑅,𝑘 :𝐶;∅⊢𝑂 read_defer(k) :(() ↩→(𝐶,B),∅)
    𝑅,𝑘 :𝐶;∅⊢𝑂 read_defer(k, v) :(() ↩→(𝐶,B),∅)

write-defer-type                                     nest-type
𝑅;𝑘 :𝐶⊢𝑂 write_defer(k) :((𝐶,B) ↩→(),∅)                   𝐷;𝐷⊢𝑔 :(𝐼 ↩→(𝑂1,...),≺𝑔) ∀𝑖.𝑂𝑖 =(𝐶𝑖,B)
                                                      𝑅;∅⊢𝑂 nest(𝑔) :(([𝐼],𝑋) ↩→([(𝑂1,...)],𝑋),≺nest(≺𝑔))

         nest-with-copy-type
          𝐷;𝐷⊢𝑔 :(𝐼 ↩→(𝑂1,...𝑂𝑚),≺𝑔)   𝐷;𝐷⊢𝑔𝑜 :(𝐼 ↩→(𝑂1,...𝑂𝑚),≺𝑔)   𝑂𝑖 =(𝐶𝑖,B)
                       𝑅;∅⊢𝑂 nest(𝑔,𝑔𝑜) :(([𝐼],𝑋) ↩→([(𝑂1...𝑂𝑚)],𝑋),≺nest(≺𝑔))


         Fig. 10. Type semantics with defer contexts, and forread_defer,write_defer, and nest.


corecompositiontypesemanticswiththesecontextsaswellasforwrite_defer,read_defer,and
nestinFigure10.Notethatthis requiresamodificationto thefulltypesystem; wedothisin the
usualway.Inparticular,notethatasexistingoperatorsneverhavegraphsassubterms,theywillbe
lifted into our context-enhanced system with arbitrary𝑅 and empty𝑊 contexts.

5.4   Operational Semantics
The nest operator processes tuples of inner streams one-by-one, maintaining the current inner
streams at the rightmost element of the input. It shifts to the next tuple of inner streams once the
graph reaches a stuck state and all the outputs (including those to write_defer) are fixed. The
nestoperatorfirststoresacopyoftheinitialgraphasasecondparameter(thisvariantislower
inthepartialorder fornest).Toprocessan innerstream,weuse setinput tosettheinnergraph
inputs, step the inner graph, and then use inputs to propagate input consumption to the nested
stream. Once the input only contains a terminator, the operator emits a terminator as well.
   Note that write_defer has no small-step rules; its behavior is handled by the semantics for
nest. The read_defer operator takes a single small-step, which emits its collection parameter.
Thiscollectionparameteriseitheradefaultvalue(forthefirsttupleofinnerstreams)oravalue
from write_defer. Whenshifting to thenext innerstream input, weuse the collect_defer helper
to accumulate the inputs to each write_defer into a map, and then use the set_defer helper to
create a copy of the initial graph with the corresponding read_defer operators updated to use
those collections. We visualize this behavior in Figure11where a stream-of-streams on the left,
with later elements lower, is transformed into another stream-of-streams. We then lay out the
formal operational semantics in Figure12.


Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 19

Flo: A Semantic Foundation for Progressive Stream Processing                                                                                         9:19




















Fig. 11.  Visualization of the nest, read_defer, and write_defer operators, where the nested streams on
the left and right have later elements lower.



                       collect_defer(𝑒1;𝑒2)≜ collect_defer(𝑒1)∪collect_defer(𝑒2)
                      collect_defer(𝑒1|𝑒2)≜ collect_defer(𝑒1)∪collect_defer(𝑒2)
     collect_defer({𝐼}[write_defer(k)])≜{𝑘 :𝐼}
                    collect_defer({𝐼}[𝑜𝑝])≜⊗                                                                when𝑜𝑝 ≠ write_defer

                         set_defer(𝑒1;𝑒2,𝑀)≜ set_defer(𝑒1,𝑀);set_defer(𝑒2,𝑀)
                       set_defer(𝑒1|𝑒2,𝑀)≜ set_defer(𝑒1,𝑀)| set_defer(𝑒2,𝑀)
      set_defer({}[read_defer(𝑘,𝑣)],𝑀)≜{}[read_defer(𝑘,𝑀[𝑘])]
                      set_defer({𝐼}[𝑜𝑝],𝑀)≜{𝐼}[𝑜𝑝]                                                    when𝑜𝑝 ≠ read_defer

  nest-first                                                                         nest-first-fixed
                                    𝐼 ≠⊗                                             ([⊗],nest(𝑔))→𝛿([⊗],nest(𝑔,𝑔),⊗)
  ([...,𝐼],nest(𝑔))→𝛿([...,𝐼],nest(𝑔,𝑔),((⊥,...,⊥),𝑡𝑟𝑢𝑒))

               nest-run-graph
                                         (setinput(𝑔,⌊𝐼⌋𝐶))→Δ(𝑔′,(𝑂′1,...,𝑂′𝑚))
                ([...,𝐼],nest(𝑔,𝑔𝑜))→𝛿([...,⟦inputs(g′)⟧𝐶],nest(𝑔′,𝑔𝑜),((𝑂′1,...,𝑂′𝑚),𝑓𝑎𝑙𝑠𝑒))

 nest-run-step
  (setinput(𝑔,⌊𝐼⌋𝐶),(𝑂1,...,𝑂𝑚)) is stuck ∀𝑚.fixed(𝑂𝑚) ∀𝑑∈collect_defer(𝑔) fixed(𝑑)   𝐼𝑛𝑒𝑥𝑡 ≠⊗
   ([...,𝐼𝑛𝑒𝑥𝑡,𝐼],nest(𝑔,𝑔𝑜))→𝛿([...,𝐼𝑛𝑒𝑥𝑡],nest(set_defer(𝑔𝑜,collect_defer(𝑔)),𝑔𝑜),((⊥,...,⊥),𝑡𝑟𝑢𝑒))

                             nest-run-fixed
                              (setinput(𝑔,⌊𝐼⌋𝐶),(𝑂1,...,𝑂𝑚)) is stuck ∀𝑚.fixed(𝑂𝑚)
                                       ([⊗,𝐼],nest(𝑔,𝑔𝑜))→𝛿([⊗],nest(𝑔𝑜,𝑔𝑜),⊗)

                                    read-defer-emit
                                    ((),read_defer(𝑘,𝑣))→𝛿((),read_defer(𝑘),𝑣)

                       Fig. 12. Small-step semantics for thenestand read_deferoperators.


                                      Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 20

9:20                                                                            Shadaj Laddad, Alvin Cheung, Joseph M. Hellerstein, and Mae Milano

5.5   Operator Properties
Because nest is a standard operator, it must satisfy all Flo’s core operator properties. First, we
define the partial order≺nest(≺𝑔), which is parameterized over the partial order for the inner
graph. Our small step semantics either consume the rightmost input inner stream or reduce it
according to the nested graph’s partial order. So we have

                                               [...]≺nest(≺𝑔)[...,𝐼]

                                       [...,𝐼′]≺nest(≺𝑔)[...,𝐼] if𝐼′≺𝑔𝐼

                                                 ⊗≺nest(≺𝑔)[...]
   For read_defer, any operator expression without the value parameter is smaller than any with
it, sothe step forread_deferreduces theoperatorexpression. Sincewrite_defer takes nosteps,
it satisfies our operator proof obligations trivially. We can now prove the properties of nest.

   Operator Well-Formedness. Whenwe stepacrossan input(nest-run-step andnest-run-
fixed), theinput isupdated to aprefix, whichsatisfies our firstcase ofthe partialorder. Theonly
otherrulethatmodifiesinputsisnest-run-graph,whichwillonlytouchtheinputsifitrecursively
stepsaleft-mostoperatorthatconsumesthoseinputs.BecauseofLemma3.1,weknowthatrunning
any of these operators will reduce the input along the partial order for the inner graph.         □

   Operator Preservation. There are only two ways we modify the inputs and outputs; either
we push or pop an entire tuple of inner streams or update the rightmost input or leftmost output.
In the first case, we only push⊥, and popping does not affect the type of the collection. When
we update an input/output instead, Lemma4.1guarantees that this is safe. In all our rules, the
operator is only changed by setting the inputs of the graph, which is safe because the input types
are unchanged.                                                                                       □

   Operator Determinism. First, nest-first or nest-first-fixed will execute, then nest-run-
graph will run until stuck state, then nest-run-step will run, until the input stream is fixed and
nest-run-fixedis run. Innest-run-graph, the onlyrule where we recursively apply astep, we
know that the stuck state exists (Lemma4.2) and is deterministic (Lemma4.3). Therefore,                                    nest is
deterministic.                                                                                        □

   Eager Execution. For nest-first-fixed and nest-run-fixed, because the input collection is
alreadyfixed deltashave noeffect.For nest-first,regardlessofwhether thedeltais introduced
beforeorafter,thefinalstatewillbethesamebecausewecopytheinputas-isandaconcatenation
will never affect𝐼 ≠⊗ because an element can never be replaced by the terminator. Because
nest-run-graph will run untilthe inner graph reaches astuck state, we can applyLemma4.3to
know that introducinga delta beforeor after the stepwill result in thesame final state, because
introducing a delta to the nested stream will only affect the last element𝐼. For nest-run-step, the
deltawillneveraffect𝐼,andanydeltato𝐼𝑛𝑒𝑥𝑡 willbeappliedthesamebeforeorafterthestep.  □

   Streaming Progress. If the input isbounded,the output will become fixed because each itera-
tionwillfinishinfinitetimebyLemma4.4.Ifitisunbounded,theinputsequencebeingfixedonly
affects nest-first-fixed and nest-run-fixed rules, which simply concatenate a terminator to the
output sequence without modifying it in any other way.                                          □


Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 21

Flo: A Semantic Foundation for Progressive Stream Processing                                                                                         9:21

6   Case Studies
Flo aims to provide strong guarantees that are meaningful across a range of applications while
remainingsufficientlyabstracttocaptureavarietyofsemantics.Inthissection,wedemonstratethe
expressivenessofFlobyusingittoimplementthekeyideasfoundinexistingstreaminglanguages.
Notethatour goalisnot toshowhow toimplementtheseentirelanguages inFlo,ratherthat key
ideas from themcan be expressed and satisfy ourproperties. We focus onthree existing languages:
  (1)Flink [15], a popular streaming framework that features windowed aggregation functions.
  (2)LVars [30], a language for parallel programming that uses lattices to ensure determinism.
  (3)DBSP [13], a system for incremental view maintenance that uses z-sets to model relations.

6.1   Flink
Flink[15]isaclassicexampleofastreamingdataflowlanguage.LikeFlo,Flinkusescompositionsof
operatorstodescribecomputationsoverstreams.AkeytechniquefromFlinkistheuseof windows
toenableaggregationsoverfixed-timeintervalsof aninfinitestream.Wewillshowthatthisidea
fromFlinkcanbemodeledinFloasatimestampedcollectiontype,wherewindowingoperators
generate streams-of-streams which can then be aggregated in a nested graph.
  Flink uses ordered sequencesas its primary semantics for streams. We can model this in Flo by
introducing an ordered sequence collection, which simplystores a list of values where the newest
items are on the left and the oldest elements on the right. We define this collection in Figure13.


                     S<V>≜{[𝑣1,...,𝑣𝑛]|∀𝑖.𝑣𝑖∈𝑉}∪{[⊗,𝑣1...𝑣𝑛]|∀𝑖.𝑣𝑖∈𝑉}
                            [𝑣1,...,𝑣𝑛]++[𝑑1,...,𝑑𝑚] =[𝑑1,...,𝑑𝑚,𝑣1,...,𝑣𝑛]
                                             [⊗,...]++𝑥 =[⊗,...]
                                       [𝑣1,...,𝑣𝑛]++⊗=[⊗,𝑣1,...,𝑣𝑛]

              Fig. 13. Collection type and concatenation operator for ordered sequences in Flo.

  With a collection type for ordered sequences, we can define classic operators found in Flink
suchasmap.WecanalsodefinesemanticsforfoldthatmatchestheFlinksemanticsofemittinga
streamcontainingasinglevalue,whichistheresultoftheaggregation.Wecandefinethetypeand
operational semantics for these operators in Figure14(we omit partial orders for brevity).


      map-type                                                  map
                        ⊢𝑓  :𝑇→𝑈                                                   𝑓(ℎ)⇓𝑢
      ⊢𝑂 map(𝑓) :((S<T>,𝑋) ↩→(S<U>,𝑋),≺map)                      ([...,ℎ],map(𝑓))→𝛿([...],map(𝑓),[𝑢])

        map-terminator                                  fold-type
        ([⊗],map(𝑓))→𝛿(⊗,map(𝑓),⊗)                                ⊢𝑎𝑐𝑐 :𝑈  ⊢𝑓  :(𝑈,𝑇)→𝑈
                                                        ⊢𝑂 fold(𝑎𝑐𝑐,𝑓) :((S<T>,𝐵) ↩→(S<U>,𝐵),≺fold)

  fold                                                         fold-terminator
                    𝑓(𝑎𝑐𝑐,ℎ)⇓𝑎𝑐𝑐′                              ([⊗],fold(𝑎𝑐𝑐,𝑓))→𝛿(⊗,fold(𝑎𝑐𝑐,𝑓),[⊗,𝑎𝑐𝑐])
  ([...,ℎ],fold(𝑎𝑐𝑐,𝑓))→𝛿([...],fold(𝑎𝑐𝑐′,𝑓),[])


                           Fig. 14. Operational semantics for Flink operators in Flo.


                                 Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 22

9:22                                                                            Shadaj Laddad, Alvin Cheung, Joseph M. Hellerstein, and Mae Milano

  Map processes elements one by one and passes through the terminator, so it satisfies eager
executionandstreamingprogresseasily.Butnotethatforthefoldoperatortosatisfystreaming
progress, its input must be bounded, otherwise the step that emits the aggregated value when the
input becomes fixed would be illegal.
  Nowgivenanunboundedstream,howdoweusefold?Flink’sansweristousewindows,where
theaggregationisrunoverblocksofdatadefinedbytimestampintervals.Thisideamapsperfectly
to the Flo model, where we can convert an unbounded stream of timestamps-value pairs into a
stream-of-streams (as in Section5) and then use a nested graph to aggregate over each window.
  To implementthis windowing operator,wewill use theinternal state ofthe operatorto store
the values corresponding to the next window. When a timestamp farther than the end of the
currentintervalisreceived,weemittheaccumulatedwindow.Becausetheoperatorusestimestamp
boundaries todeterminewhen toemit innerstreams, theinner streamsarebounded eventhough
theouter stream-of-streamsisunbounded. We omitdetailedproofsfor brevity, butthisoperator
also satisfies eager execution and streaming progress. We can sketch the type and operational
semantics for this operator in Figure15(again omitting the partial order for brevity).


        window-type
         𝑖𝑛𝑡𝑒𝑟𝑣𝑎𝑙 is an amount of time   𝑇 is a timestamp ∀𝑖𝑡𝑖 is a timestamp ∀𝑖⊢𝑣𝑖 :𝐷
        ⊢𝑂 window(𝑖𝑛𝑡𝑒𝑟𝑣𝑎𝑙,[(𝑣1,𝑡1),...(𝑣𝑛,𝑡𝑛)]) :((S<(D, T)>,𝑋) ↩→([(S<D>,𝐵)],𝑋),≺window)

              window-first
               ([...(𝑣𝑛,𝑡𝑛)],window(𝑖𝑛𝑡𝑒𝑟𝑣𝑎𝑙,[]))→𝛿([...],window(𝑖𝑛𝑡𝑒𝑟𝑣𝑎𝑙,[(𝑣𝑛,𝑡𝑛)]),[])

                      window
                                               𝑡𝑛−𝑤𝑡𝑚≤ interval
                      ([...(𝑣𝑛,𝑡𝑛)],window(𝑖𝑛𝑡𝑒𝑟𝑣𝑎𝑙,[(𝑤1,𝑤𝑡1),...,(𝑤𝑚,𝑤𝑡𝑚)]))→𝛿
                       ([...],window(𝑖𝑛𝑡𝑒𝑟𝑣𝑎𝑙,[(𝑣𝑛,𝑡𝑛),(𝑤1,𝑤𝑡1),...,(𝑤𝑚,𝑤𝑡𝑚)]),[])

                      window-emit
                                               𝑡𝑛−𝑤𝑡𝑚 > interval
                      ([...(𝑣𝑛,𝑡𝑛)],window(𝑖𝑛𝑡𝑒𝑟𝑣𝑎𝑙,[(𝑤1,𝑤𝑡1),...,(𝑤𝑚,𝑤𝑡𝑚)]))→𝛿
                           ([...],window(𝑖𝑛𝑡𝑒𝑟𝑣𝑎𝑙,[(𝑣𝑛,𝑡𝑛)]),([𝑤1,...,𝑤𝑚],𝑡𝑟𝑢𝑒))


                      Fig. 15. Type and operational semantics for thewindow operator.

  To complete our example of how patterns from Flink can be modeled in Flo, we can perform
aggregations over these windows by using a nested graph. We can pass the result of the window
operatorintothenestoperatordefinedinSection5,andusethe           foldoperatorinsidethenested
graph. Because the nested stream is bounded, this will typecheck and the aggregation will be
appropriately computed for each window.

6.2   LVars
LVars [30] is a language for deterministic parallel programming that uses lattice-based data struc-
turestoensuredeterminism.AkeyinsightofLVarsistoleveragemonotonicitytoensuredetermin-
ism,byrequiringthatpiecesofstatearealwaysupdatedmonotonically,andrestrictingreadsof
thestate tothreshold queriesthat checkif thestate islarger thanagiven value.Wewill showthat
theessenceofLVarscanbemodeledinFloasaspecialcollectiontype,wherethresholdqueries
can be used to safely read from lattice values that are derived from unbounded aggregations.


Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 23

Flo: A Semantic Foundation for Progressive Stream Processing                                                                                         9:23

  First, let us define the collection for an LVar. Consider a lattice defined by a set of values𝐿, a
bottom value⊥, and the lattice join operator⊔. We will define the LVar collection type a tuple of
thelatticevalueandabooleanflag,wherethebooleanflagindicateswhetherthevalueis fixed or
not. We will use the lattice join for concatenation, and the⊗ terminator to terminate a collection.


                             LVar<L>≜{(𝑣,true)|𝑣∈𝐿}∪{(𝑣,false)|𝑣∈𝐿}
                                        (𝑣1,false)++𝑣2=(𝑣1⊔𝑣2,false)
                                           (𝑣1,true)++𝑣2=(𝑣1,true)
                                           (𝑣1,false)++⊗=(𝑣1,true)

                     Fig. 16. Type semantics and concatenation operator for LVars in Flo.

  There are many operators that can produce an LVar from various input collection types. Let us
useordered sequences asanexample.Wecandefinea fold_latticeoperatorwhichtransforms
each value into a lattice and then applies the lattice join across the sequence. We define the type
and operational semantics for this operator in Figure17.

 fold-lattice-type                                         fold-lattice
          ⊢𝑓  :𝑇→𝑈   𝑈 is a lattice                                          𝑣 ≠⊗   𝑓(𝑣)⇓𝑙
  ⊢ fold_lattice(𝑓) :(S<T>,𝑋) ↩→(LVar<U>,𝑋)                ([...,𝑣],fold_lattice(𝑓))→𝛿([...],fold_lattice(𝑓),𝑙)

                                fold-lattice-terminated
                                 ([⊗],fold_lattice(𝑓))→𝛿(⊗,fold_lattice(𝑓),⊗)


                   Fig. 17. Type and operational semantics for thefold_lattice operator.

  We omit detailed proofs of the core operator properties for brevity here, but note that the
boundedness of the output is equal to the boundedness of the input. This is because we can
guarantee a terminator on the output when the input will become fixed. In addition, we satisfy
eager execution because we always consume elements from the rightmost side, and concatenation
to the input can only introduce new elements on the left.
  Consider a naive attempt to implement an operator that converts an LVar<T> back into an
ordered sequence [T] by generating a stream containing a single value with that LVar:
                               ((𝑣,_),to_sequence)→𝛿(⊗,to_sequence,[𝑣])
  This operator will be illegal because it does not satisfy eager execution. Recall that we are
interested in convergence regardless of whether a delta is introduced before or after the step. If we
introduce adelta thatchanges thelattice value, theoutput sequence would be differentdepending
onthisschedulingdecision,makingtheoperatornon-deterministic.Let’smakeanotherattemptto
implement this operator, where we wait for the LVar to be fixed first:
                             ((𝑣,𝑡𝑟𝑢𝑒),to_sequence)→𝛿(⊗,to_sequence,[𝑣])
  This operator satisfies eager execution, but now fails to satisfy streaming progress when
instantiatedwithanunboundedstreaminginput!Ifweruntheoperatoronanunfixedinput,the
outputwillbeanemptysequence.Butifweterminatethisinput,theoutputwillgrowtoincludethe
lattice value,which isillegal becausestreaming progress mandatesthat theonly changebetween
theseexecutionsshouldbethattheoutputalsobecomesfixed,withoutanychangestoitscontents.


                                 Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 24

9:24                                                                            Shadaj Laddad, Alvin Cheung, Joseph M. Hellerstein, and Mae Milano

Afixistorestrictthetypingrulesfortheoperatortoonlyacceptboundedinputs,sothattheinput
is guaranteed to be eventually fixed.
   What can we do with unbounded LVars? The fundamental properties of Flo and the original
LVarspapercometothesameconclusion:wemustuseathresholdqueryinstead.Wecandefinean
operator that takes anLVar anda threshold value, and emits the threshold ifthe input exceeds it.
We list the type and operational semantics for this operator in Figure18(omitting partial orders).

                          lvar-threshold-type
                                    ∀𝑖.𝑡𝑖∈𝑈∧∀𝑖,𝑗𝑘.𝑖 ≠𝑗∧𝑡𝑖⊔𝑡𝑗 =𝑘
                           ⊢𝑂 thresh(𝑡1,...) :((LVar<U>,𝑋) ↩→(S<U>,𝑋),≺thresh)

   lvar-threshold                                              lvar-threshold-terminated
                        𝑣⊔𝑡𝑖 =𝑣                                ((𝑣,𝑡𝑟𝑢𝑒),thresh(...))→𝛿(⊗,thresh(...),⊗)
   ((𝑣,_),thresh(𝑡1,...))→𝛿(⊗,thresh(𝑡1,...),𝑡𝑖)


                    Fig. 18. Type and operational semantics for thethresholdoperator.

   This operator satisfiesboth eager execution and streaming progress, making it safeto use in a
Flo program. The more general properties required for Flo, which do not involve partial orders
overcollectionvaluesoranyalgebraicproperties,stillmapverypreciselytotheapproachtakenin
LVars to enable deterministic data processing.

6.3   DBSP
Another point in the streaming language design space comes from the database community.
DBSP[13]introducesaformalmodelforrelational operatorsthatcanbeincrementallyexecuted
onliveupdatingdatabases.AkeyinsightofDBSPisthatrelationswithincrementalupdatescan
be modeledas z-sets, where eachelement in the sethas an integer cardinality,such that negative
valuescorrespondtoretractionsofdata.WewillshowthattheessenceofDBSPcanbemodeledin
Flobyusingaspecialcollectiontypeforz-sets,whereincrementaloperationsoverthesecorrespond
to satisfying eager execution.
   First,letusdefinethecollectionforaz-setinFigure19.Wewilldefinethez-setcollectiontype
as a mapof keys tointeger cardinalities as wellas a boolean flagthat indicates that thecollection
isfixed.Theconcatenationoperatorsimplycombinesthetwomapsbyaddingthecardinalitiesof
matching keys, and the⊗ terminator makes the collection fixed.

                 Cardinality Maps:𝑀 ={𝑘1 :𝑣1,...} where𝑣𝑖∈Z,𝑀[𝑘] = 0if𝑘 ∉𝑀
                                       (𝑀1+𝑀2)[𝑘] =𝑀1[𝑘]+𝑀2[𝑘]
                            ZSet ={(𝑚,true)|𝑚∈𝑀}∪{(𝑚,false)|𝑚∈𝑀}
                                  (𝑀1,𝑓𝑎𝑙𝑠𝑒)++𝑀2={(𝑀1+𝑀2,𝑓𝑎𝑙𝑠𝑒)}
                                         (𝑀,_)++⊗={(𝑀,𝑡𝑟𝑢𝑒)}


                    Fig. 19. Collection type and concatenation operator for z-sets in Flo.

   InDBSP,inputstotheprogramarez-sets,andwewilltakethesameapproachwhenmapping
this toFlo. Next, we define operators over z-sets. Letus define map, ageneral version ofprojection,
inFigure20.Weomit typingrulesforbrevity,buttheoutputboundednessisthesameastheinput.


Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 25

Flo: A Semantic Foundation for Progressive Stream Processing                                                                                         9:25


            map-zset                                          map-zset-terminated
                        𝑓(𝑘1,𝑣1)⇓𝑣′                           (({},𝑡𝑟𝑢𝑒),map(𝑓))→𝛿(⊗,map(𝑓),⊗)
              (({𝑘1 :𝑣1,...},_),map(𝑓))→𝛿
             (({...},_),map(𝑓),({𝑘1 :𝑣′},_))


                               Fig. 20. Small-step semantics for the map operator.

   Thisoperatortriviallysatisfiesstreamingprogress,becausenooutputsaregatedontermination.
InDBSP,theprimarygoalisincrementalexecution:wecanintroduceadditionalinputandtheoutput
will be updated to the result on the full input. This is exactly the definition of eagerexecution.
Ouroperatorssatisfythispropertybecausetheyaredistributiveoverthez-set.Considerprocessing
akey𝑘1 with cardinality𝑣1 only tohaveit re-introducedby adelta withcardinality𝑣2.If thedelta
is applied beforethe operator, theoperatorwill directly emita value withcardinality𝑣1+𝑣2. Ifthe
delta isapplied after, cardinality𝑣1 will beemitted, andlater theoperator willemit𝑣2 which will
be added together by concatenation.


                                        (𝑀1 ⊲⊳𝑀2)[𝑘] =𝑀1[𝑘]·𝑀2[𝑘]
                 join-zset
                                        ((𝑀′1,𝑠1),(𝑀′2,𝑠2),⊲⊳(𝑀1,𝑀2))→𝛿
                  (({},𝑠1),({},𝑠2),⊲⊳(𝑀1+𝑀′1,𝑀2+𝑀′2),(𝑀1 ⊲⊳𝑀′2+𝑀′1 ⊲⊳𝑀2+𝑀′1 ⊲⊳𝑀′2))

                                 join-zset-terminated
                                 (({},𝑡𝑟𝑢𝑒),({},𝑡𝑟𝑢𝑒),⊲⊳(_,_))→𝛿(⊗,⊗,⊲⊳,⊗)

                              Fig. 21. Operational semantics for the join operator.

   Amore interestingoperator isthe naturaljoin (⊲⊳),which takestwo z-setsand producesa new
z-set byjoining on akey.First, we definea⊲⊳ operator onz-sets which joinsthem by takingthe
productofcardinalitiesofmatchingkeys.Toperformanincrementaljoin,westorethez-setswhich
have already been processed in the state of the operator. We can then apply the z-set property
(𝑎+𝑎′) ⊲⊳(𝑏+𝑏′)=𝑎 ⊲⊳𝑏+𝑎′ ⊲⊳𝑏+𝑎 ⊲⊳𝑏′+𝑎′ ⊲⊳𝑏′. Weuse this in a sketch for theoperational
semantics in Figure21(again, omitting type semantics but using only unbounded streams).
   Again,whatisinterestinghereisthatprovingeagerexecutionalignsexactlywiththeincremental
computationgoalinDBSP.InDBSP,proofsofcorrectnesshingeonthejoinoperatorbeingbilinear,
because𝑎·(𝑏+𝑐) =𝑎·𝑏+𝑎·𝑐. This is exactly the property we need to prove eager execution,
because the operator must be distributive over concatenations to the z-set. This is a powerful
demonstration of the flexibility of Flo, as it can precisely capture the semantics of incremental
computation with retractions, a key limitation of approaches like Stream Types [19].

6.4   Putting It Together
What is particularly exciting is that all these case studies fit into the common model of Flo. In
fact, we could unify all three into a single language, since the operators are all composable and
can be used together. For example, we shared the ordered sequence collection between Flink and
LVars, so the operators we defined in both could easily be mixed together to compute a threshold
over windowed aggregates. This shows the power of the abstract approach taken by Flo; we can
capture awide rangeof semanticsunderone roof,while stillprovidingstrong guaranteesabout
the behavior of the system as a whole.


                                 Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 26

9:26                                                                            Shadaj Laddad, Alvin Cheung, Joseph M. Hellerstein, and Mae Milano

7   Related Work
Flo builds onthe vast bodies of workon streaming language design from boththe programming
languages and databases communities. We leverage the insights across both traditional stream
processing and incremental computation to devise a new model for progressive streams.

7.1   Stream Types and Deterministic Dataflow
ThemostcloselyrelatedworktoFlorecentlyisStreamTypes[19],whichprovidesarichtypesystem
thatcanpreciselycapturethestructureofelementsinastream.StreamTypesfocusoncapturing
ordering invariants, such as the presence of certain elements within bracketing pairs. These fine-
grained types make it possible to prove strong semantic guarantees about the implementation of
operators, such as determinism when operating on prefixes of data.
   ThesepropertiesmapwelltotheeagerexecutionandstreamingprogresspropertiesofFlo,which
takes a more abstract compositional approach to stream semantics. In this way, Stream Types and
Flocanbecomplementary,sinceStreamTypescanbeusedtoprovethatoperatorsinaFlolanguage
satisfy theproperties requiredby Flo.Flo’s notionof streams,however,is moregeneral thanthat
of Stream Types; indeed, one of the key limitations of Stream Types is that they cannot model
incremental computation with retractions, a key feature of DBSP that Flo can capture.
   Other work defines streams as monoids [32, 33] and uses monotone operators to ensure de-
terminism. Wegeneralize thisapproachby relaxingtheir monotonicity requirementsinto eager
execution,andbyrelyingonanotionofconcatenationthatgeneralizestheirmonoidalstructure.
This enables Flo to be used to model retractions that the monoidal approach cannot capture.

7.2   Stream Query Languages
“Continuous” query languages over streams have been a topic of recurring interest in database
research since the 1990s. A recent tutorial article overviews the history of that work [14], and
highlights thefoundational influence of CQL[8] onlanguage semantics. CQL extendsSQL with
operators that map a family of timestamped stream collections (unbounded, in our terminology) to
relations (bounded) and vice-versa; SQL is used as an inner language to map relations to relations.
CQLassumesatotallyordered,timesteppedmodelofexecutioninwhichalldataforeachtimestep
is known to be available when that timestep is processed. Like many stream query languages of its
time,CQLdoesnotaddressdelaydirectly:“Oursemanticsdoesnotdictate‘liveness’ofcontinuous
query output—that issue is relegated to latency management in the query processor [10,16]”.
   The same tutorial also points out various constructs that stream query languages introduced
for tracking progress,includingpunctuations[43],watermarks[3],heartbeats[41],slack[2],and
frontiers[34].Whilesomeoftheseareoperational(e.g.,timeout-based),manyfitourframeworkin
two places: families of collection types that admit reasoning about fixedness (e.g., mixing data and
control messages), and language constructs for extracting bounded “inner” collections.
   Anadditionalrecurringdiscussioninthesesystemsrelatestothepracticalissueof“late-arriving
information” or “out of order processing,” in which input values arrive that require a system to
“compensate”foror“retract”previously-emittedoutputvalues.AsillustratedinSection6.3,recent
approaches[13,34]showhowtheseconcernscanbemadeorthogonaltoourdiscussionhereby
lifting compensations and their handling into richer collection types and operator algebras.

7.3   Streaming Dataflow Systems
Therehas been muchworkonbuilding performant streamingdataflow systems,particularlyfor
useinanalyticalworkloads.SystemslikeSamza[36],Storm[24],Flink[15],Heron[29],Beam[31],
and Spark Streaming [45] all provide complete systems for stream dataflow. These systems are


Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 27

Flo: A Semantic Foundation for Progressive Stream Processing                                                                                         9:27

highly performant, and as a result, they focus on the operational aspects of streaming systems,
such as fault tolerance, scalability, and low-latency processing. As such, many of the contributions
ofthese systemscenter onmanaging persistenceof dataondistributed nodesand preservationof
deterministic outputs in the face of failures, an operational concern that we abstract away in Flo.
  Morerecentworkhasfocusedonbatchingasawaytoimproveperformance[28,37],whichcan
bemodeledinFlousingnestedstreams.Alltheseapproaches,however,generallyfocusonordered
sequences as a global stream type, rather than allowing programs to mix and match collection
typesasinFlo.AlthoughFloisatheoreticalfoundation,webelievethereismuchworktobedone
in building a practical streaming system that can leverage the guarantees provided by Flo.

7.4   Reactive, Incremental, and Stream-Based Programming
Muchworkexistsonfunctionalreactiveprogramming(FRP),aparadigminwhichprogramsare
continuouslyre-run(oftenincrementally)onever-changinginputs[18,25–27,38].Theseprograms
canbeformalizedasstreams,andareoftencompiledtoastreamingdataflowrepresentationsimilar
to those we explore in this paper. Of particular interest are papers which reason about avoiding
space-time leaks [26,27], requiring a property similar to our streaming progress condition.
  Otherworkin thisspacehasfocusedon thecorrespondencebetweenLTLand FRP[18,25,38],
orhave focusedonthe incrementalizationoffunctional programs[22, 44].While ourworkalso
reasons about properties like equivalence under re-ordering, eventual termination, and avoiding
space-timeleaks, wechoose anew,more generalformalismboth better-suitedto ourdomainand
less opinionated about the definitions of “streams” and “operators.”
  Manystream-basedlanguageshavepreciseideasofhowtodefinebothstreamsandcomputations
[9,12,17,35,42].Whilemuchofthisworkisinterestedinpropertiessimilartoeagerexecution
andstreamingprogress,allofitisformalizedwithasyntaxandsemanticsforaparticularlanguage.
In contrast, Flo offers an abstract, general framework for streaming languages, with only enough
constraintstoproveourcoreproperties.WebelievethatFloprovidesabasistobuildsuchlanguages.
  Anincremental,streaming languageof particularinterest isNaiad [34], whichuses adataflow
modelthatsupportsincrementalexecutionofdataflowwithcycles.Ourmodelofnestedstreamsis
inspiredbyNaiad,whichsimilarlyusesspecialoperatorstodescribehowstreamsarefedintoout
ofnestedloops.InFlo,ourcollectiontypefororderedsequencesofstreamsrequiresinnerstreams
tobeprocessedin-order,whileNaiadallowsfor“time-travelling”withvectortimestampstoallow
modifications to already-processed streams.One could imagine implementing this in Flousing a
specialized collection type and nesting operator for timestamped messages.
  Other work in the streaming space focuses on a similar goal of unifying several streaming
semanticsunderonelanguage [40].Butthisworkmakeslimitedguaranteesaboutthebehavior
of the program, with respect to both correctness and liveness of outputs. Flo provides a similar
general model, but supports compositional proofs of determinism and completeness of outputs.

8   Conclusion
Inthispaper,weintroducedFlo,aparameterizedstreamingdataflowlanguagethatprovidesstrong
guaranteesabout thebehaviorofstreaming computations.Floidentifiestwokey propertieswhich
are general yet necessary for streaming programs:streamingprogress andeagerexecution. We
formallymodelthesepropertiesandshowthattheyarepreservedacrosscomposition.Furthermore,
weshowedthatFlosupportsnestedstreamsandgraphswhilemaintainingthesemanticguarantees
ofthelanguage.TodemonstratethecapabilitiesofFlo,weshowedthatFlocancaptureawiderange
of streaming semantics, from windowed aggregation in Flink, to monotone thresholds in LVars,
andevenincrementalcomputationinDBSP.WebelievethatFloprovidesapowerfulfoundation
for building streaming systems that can be used to more strongly reason about their guarantees.


                               Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 28

9:28                                                                            Shadaj Laddad, Alvin Cheung, Joseph M. Hellerstein, and Mae Milano

Acknowledgments
We thank our anonymous reviewers for their insightful feedback on this paper. This work is
supported in part by National Science Foundation CISE Expeditions Award CCF-1730628, IIS-
1955488,IIS-2027575,DOEawardDE-SC0016260,AROawardW911NF2110339,andONRaward
N00014-21-1-2724,andbygiftsfromAmazonWebServices,AntGroup,Ericsson,Futurewei,Google,
Intel, Meta, Microsoft, Scotiabank, and VMware. Shadaj Laddad is supported in part by the NSF
Graduate Research Fellowship Program under Grant No. DGE 2146752. Any opinions, findings,
andconclusionsorrecommendationsexpressedinthismaterialarethoseoftheauthorsanddonot
necessarily reflect the views of the National Science Foundation.

References
 [1] DanielJAbadi,YanifAhmad,MagdalenaBalazinska,UgurCetintemel,MitchCherniack,Jeong-HyonHwang,Wolfgang
     Lindner,AnuragMaskey,AlexRasin,EstherRyvkina,etal.2005. Thedesignoftheborealisstreamprocessingengine..
     In Cidr, Vol. 5. 277–289.
 [2] Daniel J Abadi, Don Carney, Ugur Cetintemel, Mitch Cherniack, Christian Convey, Sangdon Lee, Michael Stonebraker,
     Nesime Tatbul,and Stan Zdonik.2003. Aurora: anew modeland architecturefor data stream management. the VLDB
     Journal 12 (2003), 120–139.
 [3] TylerAkidau,RobertBradshaw,CraigChambers,SlavaChernyak,RafaelJFernández-Moctezuma,ReuvenLax,Sam
     McVeety,DanielMills,FrancesPerry,EricSchmidt,etal.2015. Thedataflowmodel:apracticalapproachtobalancing
     correctness, latency, and cost in massive-scale, unbounded, out-of-order data processing. Proceedings of the VLDB
     Endowment 8, 12 (2015), 1792–1803.
 [4] TylerAkidau,RobertBradshaw,CraigChambers,SlavaChernyak,RafaelJ.Fernández-Moctezuma,ReuvenLax, Sam
     McVeety,DanielMills,FrancesPerry,EricSchmidt,andSamWhittle.2015. Thedataflowmodel:apracticalapproach
     to balancing correctness, latency, and cost in massive-scale, unbounded, out-of-order data processing. Proc. VLDB
     Endow.8, 12 (aug 2015), 1792–1803.https://doi.org/10.14778/2824032.2824076
 [5] Peter Alvaro, Neil Conway, Joseph M Hellerstein, and William R Marczak. 2011. ConsistencyAnalysis in Bloom: a
     CALM and Collected Approach.. In CIDR. Citeseer, 249–260.
 [6] PeterAlvaro,WilliamR.Marczak,NeilConway,JosephM.Hellerstein,DavidMaier,andRussellSears.2011. Dedalus:
     DataloginTimeandSpace.In Datalog Reloaded,OegedeMoor,GeorgGottlob,TimFurche,andAndrewSellers(Eds.).
     Springer Berlin Heidelberg, Berlin, Heidelberg, 262–281.
 [7] Arvind Arasu, Shivnath Babu,and Jennifer Widom. 2006. The CQL continuous query language: semantic foundations
     and query execution. The VLDB Journal 15 (2006), 121–142.
 [8] Arvind Arasu, Shivnath Babu,and Jennifer Widom. 2006. The CQL continuous query language: semantic foundations
     and query execution. The VLDB Journal 15 (2006), 121–142.
 [9] MichaelArntzeniusandNeel Krishnaswami.2019. Seminaïveevaluationforahigher-orderfunctionallanguage. Proc.
     ACM Program. Lang. 4, POPL, Article 22 (dec 2019), 28 pages.https://doi.org/10.1145/3371090
[10] Brian Babcock, Shivnath Babu, Rajeev Motwani, and Mayur Datar. 2003. Chain: Operator scheduling for memory
     minimization indata streamsystems. In Proceedings of the 2003 ACM SIGMOD international conference on Management
     of data. 253–264.
[11] Shivnath Babu and Jennifer Widom. 2001.  Continuous queries over data streams.  SIGMOD Rec. 30, 3 (sep 2001),
     109–120.https://doi.org/10.1145/603867.603884
[12] GérardBerryand LaurentCosserat.1985. TheESTERELsynchronous programminglanguageand itsmathematical
     semantics.In Seminar on Concurrency: Carnegie-Mellon University Pittsburgh, PA, July 9–11, 1984.Springer,389–448.
[13] MihaiBudiu,TejChajed,FrankMcSherry,LeonidRyzhyk,andValTannen.2023. DBSP:AutomaticIncrementalView
     Maintenance for Rich Query Languages. Proc. VLDB Endow. 16, 7 (mar 2023), 1601–1614.https://doi.org/10.14778/
     3587136.3587137
[14] ParisCarbone,MariosFragkoulis,VasilikiKalavri,andAsteriosKatsifodimos.2020. Beyondanalytics:Theevolution
     ofstreamprocessingsystems.In Proceedings of the 2020 ACM SIGMOD international conference on Management of data.
     2651–2658.
[15] Paris Carbone, Asterios Katsifodimos, Stephan Ewen, Volker Markl, Seif Haridi, and Kostas Tzoumas. 2015. Apache
     flink:Streamandbatchprocessinginasingleengine. TheBulletinoftheTechnicalCommitteeonDataEngineering 38,4
     (2015).
[16] Don Carney, Uğur Çetintemel, Alex Rasin, Stan Zdonik, Mitch Cherniack, and Mike Stonebraker. 2003.  Operator
     scheduling in a data stream manager. In Proceedings 2003 VLDB Conference. Elsevier, 838–849.


Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 29

Flo: A Semantic Foundation for Progressive Stream Processing                                                                                         9:29


[17] P. Caspi,D. Pilaud, N.Halbwachs, and J.A. Plaice. 1987. LUSTRE: adeclarative language forreal-time programming.
      In Proceedings of the 14th ACM SIGACT-SIGPLAN Symposium on Principles of Programming Languages (Munich, West
      Germany) (POPL ’87).AssociationforComputingMachinery,NewYork,NY,USA,178–188.https://doi.org/10.1145/
      41625.41641
[18] Andrew Cave, Francisco Ferreira, Prakash Panangaden, and Brigitte Pientka. 2014.   Fair reactive programming.
      In Proceedings of the 41st ACM SIGPLAN-SIGACT Symposium on Principles of Programming Languages (San Diego,
      California, USA) (POPL ’14). Association for Computing Machinery, New York, NY, USA, 361–372.https://doi.org/10.
      1145/2535838.2535881
[19] Joseph W. Cutler, Christopher Watson, Emeka Nkurumeh, Phillip Hilliard, Harrison Goldstein, Caleb Stanford, and
      Benjamin C. Pierce. 2024.   Stream Types.   Proc. ACM Program. Lang. 8, PLDI, Article 204 (jun 2024), 25 pages.
      https://doi.org/10.1145/3656434
[20] Tathagata Das, Yuan Zhong, Ion Stoica, and Scott Shenker. 2014. Adaptive Stream Processing using Dynamic Batch
      Sizing. In Proceedings of the ACM Symposium on Cloud Computing (Seattle, WA, USA) (SOCC ’14). Association for
      Computing Machinery, New York, NY, USA, 1–13.https://doi.org/10.1145/2670979.2670995
[21] Jon Gjengset, Malte Schwarzkopf, Jonathan Behrens, Lara Timbó Araújo, Martin Ek, Eddie Kohler, M. Frans Kaashoek,
      andRobertMorris.2018. Noria:dynamic,partially-statefuldata-flowforhigh-performancewebapplications.In 13th
      USENIX Symposium on Operating Systems Design and Implementation (OSDI 18).USENIXAssociation,Carlsbad,CA,
      213–231.https://www.usenix.org/conference/osdi18/presentation/gjengset
[22] MatthewA.Hammer,KhooYitPhang,MichaelHicks,andJeffreyS.Foster.2014. Adapton:composable,demand-driven
      incrementalcomputation.In Proceedings of the 35th ACM SIGPLAN Conference on Programming Language Design and
      Implementation (Edinburgh, United Kingdom) (PLDI ’14). Association for Computing Machinery, New York, NY, USA,
      156–166.https://doi.org/10.1145/2594291.2594324
[23] Carl Hewitt, Peter Bishop, and Richard Steiger. 1973. A universal modular ACTOR formalism for artificial intelligence.
      In Proceedings of the 3rd International Joint Conference on Artificial Intelligence (Stanford, USA) (IJCAI’73). Morgan
      Kaufmann Publishers Inc., San Francisco, CA, USA, 235–245.
[24] AnkitJain. 2017. Mastering apache storm: Real-time big data streaming using kafka, hbase and redis. PacktPublishing
      Ltd.
[25] Alan Jeffrey. 2012.  LTL types FRP: linear-time temporal logic propositions as types, proofs as functional reactive
      programs. In Proceedings of the Sixth Workshop on Programming Languages Meets Program Verification (Philadelphia,
      Pennsylvania, USA) (PLPV ’12). Association for Computing Machinery, New York, NY, USA, 49–60.https://doi.org/10.
      1145/2103776.2103783
[26] AlanJeffrey.2014. Functionalreactivetypes.In Proceedings of the Joint Meeting of the Twenty-Third EACSL Annual
      Conference on Computer Science Logic (CSL) and the Twenty-Ninth Annual ACM/IEEE Symposium on Logic in Computer
      Science (LICS) (Vienna, Austria) (CSL-LICS ’14). Association for Computing Machinery, New York, NY, USA, Article 54,
      9 pages.https://doi.org/10.1145/2603088.2603106
[27] NeelakantanR.Krishnaswami.2013. Higher-orderfunctionalreactiveprogrammingwithoutspacetimeleaks. SIGPLAN
      Not. 48, 9 (sep 2013), 221–232.https://doi.org/10.1145/2544174.2500588
[28] Lars Kroll, Klas Segeljakt, Paris Carbone, Christian Schulte, and Seif Haridi. 2019. Arc: an IR for batch and stream
      programming.In Proceedings of the 17th ACM SIGPLAN International Symposium on Database Programming Languages
      (Phoenix, AZ, USA) (DBPL 2019). Association for Computing Machinery, New York, NY, USA, 53–58.https://doi.org/
      10.1145/3315507.3330199
[29] Sanjeev Kulkarni, Nikunj Bhagat, Maosong Fu, Vikas Kedigehalli, Christopher Kellogg, Sailesh Mittal, Jignesh M.
      Patel, Karthik Ramasamy, and Siddarth Taneja. 2015. Twitter Heron: Stream Processing at Scale. In Proceedings of the
      2015 ACM SIGMOD International Conference on Management of Data (Melbourne, Victoria, Australia) (SIGMOD ’15).
      Association for Computing Machinery, New York, NY, USA, 239–250.https://doi.org/10.1145/2723372.2742788
[30] Lindsey Kuper and Ryan R. Newton. 2013.  LVars: lattice-based data structures for deterministic parallelism. In
      Proceedings of the 2nd ACM SIGPLAN Workshop on Functional High-Performance Computing (Boston, Massachusetts,
      USA) (FHPC ’13). Association for Computing Machinery, New York, NY, USA, 71–84.https://doi.org/10.1145/2502323.
      2502326
[31] Jan Lukavsky. 2022. Building Big Data Pipelines with Apache Beam: Use a single programming model for both batch and
      stream data processing. Packt Publishing Ltd.
[32] KonstantinosMamouras.2020. Semanticfoundationsfordeterministicdataflowandstreamprocessing.InProgramming
      Languages and Systems: 29th European Symposium on Programming, ESOP 2020, Held as Part of the European Joint
      Conferences on Theory and Practice of Software, ETAPS 2020, Dublin, Ireland, April 25–30, 2020, Proceedings 29. Springer
      International Publishing, 394–427.
[33] KonstantinosMamouras,CalebStanford,RajeevAlur,ZacharyG.Ives,andValTannen.2019. Data-tracetypesfor
      distributed stream processing systems. In Proceedings of the 40th ACM SIGPLAN Conference on Programming Language


                                         Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.

## PDF page 30

9:30                                                                            Shadaj Laddad, Alvin Cheung, Joseph M. Hellerstein, and Mae Milano


      Design and Implementation (Phoenix,AZ,USA) (PLDI 2019).Association forComputingMachinery,New York,NY,
      USA, 670–685.https://doi.org/10.1145/3314221.3314580
[34] Derek G. Murray, Frank McSherry, Rebecca Isaacs, Michael Isard, Paul Barham, and Martín Abadi. 2013.  Naiad:
      A Timely Dataflow System. In Proceedings of the Twenty-Fourth ACM Symposium on Operating Systems Principles
      (Farminton,Pennsylvania) (SOSP ’13).AssociationforComputingMachinery,NewYork,NY,USA,439–455.https:
      //doi.org/10.1145/2517349.2522738
[35] DavidNavalho,SérgioDuarte,NunoPreguiça,andMarcShapiro.2013. Incrementalstreamprocessingusingcom-
      putational conflict-free replicated data types. In Proceedings of the 3rd International Workshop on Cloud Data and
      Platforms (Prague,CzechRepublic) (CloudDP ’13).AssociationforComputingMachinery,NewYork,NY,USA,31–36.
      https://doi.org/10.1145/2460756.2460762
[36] ShadiA.Noghabi,KartikParamasivam,YiPan,NavinaRamesh,JonBringhurst,IndranilGupta,andRoyH.Campbell.
      2017.  Samza: stateful scalable stream processing at LinkedIn.  Proc. VLDB Endow. 10, 12 (aug 2017), 1634–1645.
      https://doi.org/10.14778/3137765.3137770
[37] Shoumik Palkar, James Thomas, Deepak Narayanan, Pratiksha Thaker, Rahul Palamuttam, Parimajan Negi, Anil
      Shanbhag,MalteSchwarzkopf,HolgerPirk,SamanAmarasinghe,SamuelMadden,andMateiZaharia.2018. Evaluating
      End-to-EndOptimizationforDataAnalyticsApplicationsinWeld. Proc. VLDB Endow.11,9(may2018),1002–1015.
      https://doi.org/10.14778/3213880.3213890
[38] JenniferPaykin,NeelakantanRKrishnaswami,andSteveZdancewic.2016. Theessenceofevent-drivenprogramming.
      Leibniz, Leibniz International Proceedings in Informatics (2016).
[39] Amir Shaikhha, Dan Suciu, Maximilian Schleich, and Hung Ngo. 2024. Optimizing Nested Recursive Queries. Proc.
      ACM Manag. Data 2, 1, Article 16 (mar 2024), 27 pages.https://doi.org/10.1145/3639271
[40] Robert Soulé, Martin Hirzel, Robert Grimm, Buğra Gedik, Henrique Andrade, Vibhore Kumar, and Kun-Lung Wu.
      2010. Auniversalcalculusforstreamprocessinglanguages.In Programming Languages and Systems: 19th European
      Symposium on Programming, ESOP 2010, Held as Part of the Joint European Conferences on Theory and Practice of
      Software, ETAPS 2010, Paphos, Cyprus, March 20-28, 2010. Proceedings 19. Springer, 507–528.
[41] UtkarshSrivastava andJennifer Widom.2004. Flexible timemanagement indatastream systems.In Proceedings of the
      twenty-third ACM SIGMOD-SIGACT-SIGART symposium on Principles of database systems. 263–274.
[42] WilliamThies,MichalKarczmarek,andSamanP.Amarasinghe.2002. StreamIt:ALanguageforStreamingApplications.
      InProceedingsofthe11thInternationalConferenceonCompilerConstruction(CC’02).Springer-Verlag,Berlin,Heidelberg,
      179–196.
[43] PeterA.Tucker,DavidMaier,TimSheard,andLeonidasFegaras.2003. Exploitingpunctuationsemanticsincontinuous
      data streams. IEEE Transactions on Knowledge and Data Engineering 15, 3 (2003), 555–568.
[44] DanielM.YellinandRobertE.Strom.1991. INC:alanguageforincrementalcomputations. ACM Trans. Program. Lang.
      Syst. 13, 2 (apr 1991), 211–236.https://doi.org/10.1145/103135.103137
[45] Matei Zaharia, Tathagata Das, Haoyuan Li, Timothy Hunter, Scott Shenker, and Ion Stoica. 2013. Discretized streams:
      fault-tolerant streaming computation at scale. In Proceedings of the Twenty-Fourth ACM Symposium on Operating
      Systems Principles (Farminton, Pennsylvania) (SOSP ’13). Association for Computing Machinery, New York, NY, USA,
      423–438.https://doi.org/10.1145/2517349.2522737


























Proc. ACM Program. Lang., Vol. 9, No. POPL, Article 9. Publication date: January 2025.
