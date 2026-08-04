# Soundly Handling Linearity

**Machine conversion:** extracted from the adjacent PDF with `pypdf`; page boundaries are retained, while equations, figures, and multi-column layout may not round-trip faithfully. Consult the PDF for authoritative pagination and notation.

## PDF page 1

Soundly Handling Linearity

WENHAO TANG,           The University of Edinburgh, United Kingdom
DANIEL HILLERSTRÖM,                                Huawei Zurich Research Center, Switzerland
SAM LINDLEY,                   The University of Edinburgh, United Kingdom
J. GARRETT MORRIS,                  University of Iowa, USA

We proposeanovelapproachtosoundlycombininglineartypeswithmulti-shot effecthandlers.Lineartype
systems statically ensure that resources such as file handles and communication channels are used exactly
once.Effecthandlersprovidearichmodularprogrammingabstractionforimplementingfeaturesrangingfrom
exceptionstoconcurrencytobacktracking.Whereasconventionallineartypesystemsbakeintheassumption
that continuations are invoked exactly once, effect handlers allow continuations to be discarded (e.g. for
exceptions) or invoked more than once (e.g. for backtracking). This mismatch leads to soundness bugs in
existing systems such as the programming language Links, which combines linearity (for session types)
with effect handlers. We introduce control-flow linearity as a means to ensure that continuations are used in
accordance with the linearity of any resources they capture, ruling out such soundness bugs.
   We formalise the notion of control-flow linearity in a System F-style core calculus F◦eff equipped with
linear types, an effect type system, and effect handlers. We define a linearity-aware semantics in order to
formallyprovethatF◦eff preservestheintegrityoflinearvaluesinthesensethatnolinearvalueisdiscardedor
duplicated.Inordertoshowthatcontrol-flowlinearitycanbemadepractical,weadaptLinksbasedonthe
design of F◦eff, in doing so fixing a long-standing soundness bug.
   Finally,tobetterexposethepotentialofcontrol-flowlinearity,wedefineanML-stylecorecalculus Q◦eff,
based on qualified types, which requires no programmer provided annotations, and instead relies entirely
on type inference to infer control-flow linearity. Both linearity and effects are captured by qualified types.
Q◦eff overcomes a number of practical limitations of F◦eff, supporting abstraction over linearity, linearity
dependencies between type variables, and a much more fine-grained notion of control-flow linearity.
CCS Concepts:•Theoryofcomputation→Controlprimitives;Typestructures.
Additional Key Words and Phrases: control-flow linearity, multi-shot continuations, linear resources
ACMReferenceFormat:
WenhaoTang,Daniel Hillerström,SamLindley,and J.GarrettMorris.2024.Soundly HandlingLinearity. Proc.
ACM Program. Lang. 8, POPL, Article 54 (January 2024),51pages.https://doi.org/10.1145/3632896

1   INTRODUCTION
Many programming languages support linear resources such as file handles, communication
channels,networkconnections,andsoforth.Specialcaremustbetakentopreservetheintegrity
of linear resources in the presence of first-class continuations that may be invoked multiple
times [Friedman and Haynes1985], as a linear resource may be inadvertently be accessed more
than once. Java [Pressler2018] and                   OCaml [Sivaramakrishnan et al . 2021] have each recently
beenretrofittedwithfacilitiesforprogrammingwithfirst-classcontinuationsthatmustbeinvoked

Authors’addresses:WenhaoTang,TheUniversityofEdinburgh,UnitedKingdom,wenhao.tang@ed.ac.uk;DanielHiller-
ström,HuaweiZurichResearchCenter,Switzerland,daniel.hillerstrom@ed.ac.uk;SamLindley,TheUniversityofEdinburgh,
United Kingdom, sam.lindley@ed.ac.uk;J. Garrett Morris, University of Iowa, USA, garrett-morris@uiowa.edu.

Permissionto makedigital orhard copiesof partorall ofthis workfor personalorclassroomuseis grantedwithout fee
provided that copies are not made or distributed for profit or commercial advantage and that copies bear this notice and
the full citation on the first page. Copyrights for third-party components of this work must be honored. For all other uses,
contact the owner/author(s).
© 2024 Copyright held by the owner/author(s).
ACM 2475-1421/2024/1-ART54
https://doi.org/10.1145/3632896


                                  Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 2

54:2                                                                              Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

exactly once, partly in order to avoid such pitfalls. Nonetheless, multi-shot continuations are a
compelling feature, supporting applications such as backtracking search [Friedman et al . 1984]
and probabilisticprogramming [Kiselyov and Shan2009]. In this paper weexplore how tosoundly
handle linearity in the presence of multi-shot effect handlers [Plotkin and Pretnar2013].
   Wefirstillustratetheissueswithcombininglinearitywithmulti-shoteffecthandlersbyexhibiting
a soundness bug in the programming languageLinks [Cooper et al . 2006], which is equipped with
linear session-typed channels [Lindley and Morris2017] and effect handlers with multi-shot
continuations [Hillerström et al . 2020a]. We begin by defining a function           outch that forks a child
process and returns an output channel for communicating with it. The idea is that we will use
a combination of exceptions and multi-shot continuations to send two integers, rather than an
integerfollowedbya string,alongtheendpoint(withsessiontype !Int.!String.End)returnedby
the function outch.

sig  outch  :  ()  ~>  !Int.!String.End
fun  outch()  {
  fork(fun(ic)  {
     var  (i,  ic)  =  receive(ic);          #  receive  the  integer
     var  (s,  ic)  =  receive(ic);          #  receive  the  string
     println(intToString(i)  ^^  s);    #  convert,  concat,  and  print
     close(ic)                                            #  close  the  input  channel
  })
}
The primitive fork creates a child process and two endpoints of a session-typed channel. One
endpoint ispassed to thechild process and theother endpoint isreturned to the caller. Here the
function returns an output endpoint of type !Int.!String.End and the child process is supplied
withaninputendpointoftype ?Int.?String.End.Thechildreceivesanintegerandastringonthe
input endpoint, then prints them out before closing the endpoint.
   Nowweinvoke outchina contextinwhich weexploitthe powerofmulti-shot continuationsto
return twice and the power of exceptions to abort the current computation.

handle({
  var  oc  =  outch();
  var  msg  =  if  (do  Choose)  42  else  84;  #  choose  an  integer  message  to  send
  var  oc  =  send(msg,  oc);
  do  Fail;                                                          #  this  is  our  exception
  var  oc  =  send("well-typed",  oc);
  close(oc)
})  {
  case  <Fail>  ->  ()
  case  <Choose  =>  resume>  ->  resume(true);  resume(false)
}
Wehandle a computation that performs two operations: 1) Choose  :  ()  =>  Bool; and 2) Fail  :
forall  a.  ()  =>  a. The handled computation invokes outch, forking a child process and binding
theoutputendpointoftheresultingchannelto oc.Next,itinvokestheoperation Choosetoselect
between twopossible integermessages, whichis sent onthe channel.Then, itperforms the Fail
operation,beforesendingastringalongthechannelandclosingit.Thisisallverywellandsatisfies
thetype-checker;however,thedescribedcontrolflowisnotactuallywhathappens,becauseinfact
the continuation of Choose is invoked twice and the continuation of Fail is never invoked. The


Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 3

Soundly Handling Linearity                                                                                                                                                 54:3

behaviours of Fail and Choose are definedby the corresponding operationclauses of thehandler.
For Fail the captured continuation is discarded (it must be: it is never bound); for Choose the
continuation is bound to resumeand invoked twice: first with trueand then with false.
   Running the program causes a segmentation fault when printing the received values, as it
erroneouslyattempts toconcatenatea stringwithan integer.To seewhy,follow thecontrolflow
oftheparent process.Itperforms Choose,whichinitially selects 42andsendsit overthechannel.
Thechildprocessreceivesthisintegerandsubsequentlyexpectstoreceiveastring.Backonthe
parentprocessexecutionisabortedviaFail,whichcausestheinitialinvocationofresumetoreturn,
leading to the second invocation of resume, which restores the aborted context at the point of
selectinganinteger.NowChooseselects84andsendsitoverthechannel.Thechildprocessreceives
this second integer, mistakenly treating it as a string.
   In this paper we rule out such soundness bugs by tracking control-flow linearity: a means to
staticallyassurehowoftenacontinuationmaybeinvoked,mediatingbetweenlinearresourcesand
effectfuloperationstoensurethateffecthandlerscannotviolatelinearityconstraintsonresources.
   The main contributions of this paper are:
    • We give high-level overview of the main ideas of the paper through a series of worked
       examplesthatillustratethedifficultiesofcombiningeffecthandlerswithlinearity,howthey
       canberesolvedbytrackingcontrol-flowlinearity,andhowtheapproachcanberefinedusing
       qualified types [Jones1994] (Section2).
    • We introduce F◦eff (pronounced “F-eff-pop”), a System F-style core calculus equipped with
       linear types, an effect type system, and effect handlers (Section3). We prove syntactic type
       soundness and a semantic linear safety property.
    • Inspired by F◦eff we implement control-flow linearity in Links, fixing a long-standing type-
       soundness bug (Section4).
    • MotivatedbyexpressivenesslimitationsofF◦eff weintroduceQ◦eff (pronounced“Q-eff-pop”),an
       ML-stylecorecalculusinspiredbyQuill[Morris2016]and                    Rose[MorrisandMcKinna2019],
       basedonqualifiedtypes(Section5).Weprovesoundnessandcompletenessoftypeinference
       for Q◦eff.Alongtheway,weidentifyasemanticsoundness buginQuillandconjectureafix.
Section6outlines howcontrol-flow linearityapplies to shallowhandlers [Hillerströmand Lindley
2018]. Section7discusses related work and Section8conclude and discusses future work.

2   OVERVIEW
In this section, we give a high-level overview of the main ideas of the paper by way of a series
ofexamples.Wefirstcomparestandardvaluelinearitywithnon-standardcontrol-flowlinearity,
illustrating how the latter may be tracked in an explicit calculus F◦eff (Section3). For readability
weomituninterestingsyntacticartifactsfromourexamples.Weshowhowcontrol-flowlinearity
allows linear resources and multi-shot continuations to coexist peacefully. We then highlight
two limitations of F◦eff: linear types require syntactic overhead which harms modularity, and row-
polymorphism based effect types lead to coarse tracking of control-flow linearity. We exploit
qualified types to relax both limitations in an ML-style calculus Q◦eff (Section5).

2.1   Value Linearity
Valuelinearityclassifiestheuseofvalues:linearvaluesmustbeusedexactlyoncewhereasunlimited
values can be used zero, one, or multiple times (linear types differ from uniqueness types, which
insteadtrackthenumberofreferencestoavalue).Equivalently,valuelinearitycharacteriseswhether
valuescontainlinearresources:linearvaluescancontainlinearresourceswhereasunlimitedvalues
cannot.Conventionallineartypesystemstrackvaluelinearity. F◦eff adaptsthesubkinding-based


                                Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 4

54:4                                                                              Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

linear type system of F◦ [Mazurak et al . 2010]. The linearity   𝑌 of a value type is part of its kind
Type𝑌 and can be either linear◦ or unlimited•. For example, file handles are linear resources
(File : Type◦) and integers are unlimited resources (Int : Type•).
   A linearity annotation on a𝜆-abstraction defines the linearity of the function itself. Consider
thefollowing functionfaithfulWritewhichtakes afile handle𝑓 andreturns anotherfunction that
takes a string𝑠, faithfully writes𝑠 to𝑓, and then closes the file handle.
                       faithfulWrite : File→•(String→◦())
                       faithfulWrite=𝜆•𝑓.(𝜆◦𝑠.let𝑓′← write(𝑠,𝑓) in close𝑓′)
Theouterunlimitedfunction(→•)yieldsalinearfunction(→◦)expectingastring.Thelineartype
system dictates that the inner function is linear as it captures the linear file handle𝑓.
   One important property of value linearity is that unlimited value types can be treated as linear
value types, as it is always safe to use unlimited values (which contain no linear resources) just
once. This property is embodied by the subkinding relation⊢ Type•≤ Type◦ in F◦eff. For instance,
consider the polymorphic identity function.
                                        id :∀𝜇Row𝛼Type◦.𝛼→•𝛼 !{𝜇}
                                        id=Λ𝜇Row𝛼Type◦.𝜆•𝑥.𝑥
The return type of the function is a computation type𝛼 !{𝜇} where𝛼 is the linear type of values
returned (𝑥 is used exactly once) and𝜇 is the row of effects performed by the function. (We chose
to omit the corresponding effect annotations in the signature of faithfulWrite because they are
empty,buthenceforthwewillwritethemexplicitly.)Subkindingallowstheidentityfunctiontobe
appliedtobothlinearandunlimitedvalues.Itisalwayssoundtouseanunlimitedvalueexactly
once. Thus, we have both⊢ Int : Type◦ and⊢ File : Type◦, and if𝑅 is an effect row type:
                                          id𝑅 File : File→• File!{𝑅}
                                          id𝑅 Int  : Int→• Int!{𝑅}

2.2   Control-Flow Linearity
Control-flowlinearitytrackshowmanytimescontrolmayenteralocalcontext:acontrol-flow-
linear context must be entered exactly once; a control-flow-unlimited context may be entered
zero, one, or multiple times. Equivalently, control-flow linearity characterises whether a local
context captures linear resources: a control-flow-linear context can capture linear resources; a
control-flow-unlimited context cannot.
   To better explain control-flow linearity, we first reprise the soundness problem due to the
interactionoflinearresourcesandmulti-shotcontinuationsofSection1viaasimplerexamplein          F◦eff.
ConsiderthefollowingfunctiondubiousWrite✗,whichtakesafilehandleandnon-deterministically
writes"A" or "B" to it depending onthe result ofChoose. Weignore control-flow linearityfor now.
                  dubiousWrite✗  : File→•()!{Choose :()↠ Bool}
                  dubiousWrite✗=𝜆•𝑓.
                        let𝑏←(do Choose()){Choose:()↠Bool} in
                        let𝑠← if𝑏 then "A" else "B" in                continuation ofChoose
                        let𝑓′← write(𝑠,𝑓) in close𝑓′
Thedo Choose() expressioninvokesoperationChoosewithaunitargument. F◦eff adaptsaneffect
systembasedonRémy-stylerowpolymorphism[HillerströmandLindley2016;LindleyandCheney
2012].Effecttypesin        F◦eff arerowscontainingoperationlabelswiththeirsignaturesandendedwith
potential row variables. The effect type{Choose :()↠ Bool} denotes that dubiousWrite✗ may


Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 5

Soundly Handling Linearity                                                                                                                                                 54:5

invoke the operation Choose, which takes a unit and returns a boolean value as indicated by its
signature()↠ Bool.The problemarises whenwe handleChooseusing multi-shotcontinuations.
    let𝑓← open"C.txt" in handle(dubiousWrite✗𝑓) with{Choose _𝑟↦→𝑟 true;𝑟 false}
Thefile"C.txt"isopenedandthefilehandleisboundto𝑓 beforedubiousWrite✗𝑓 ishandledbyan
effecthandlerthathandlestheChooseoperation.Inthehandlerclause,𝑟 bindsthecontinuationof
Choose, which expects a parameter of type Bool. As𝑟 is invoked twice (first with true and then
with false), thefile handle𝑓 is writtenand closedtwice, which leadsto aruntime errorbecauseit
is closedbefore the secondwrite. Theessential problem isthat thecontinuation of Chooseshould
be used linearly as it captures the linear file handle𝑓, but it is invoked twice by the effect handler.
Conventionallineartypesystemscannotdetectthiskindoferrorastheyonlytrackvaluelinearity.
   Motivatedbytheobservationthatonlyalocalcontext,reifiedasthecontinuationofanoperation,
maybecapturedbyamulti-shothandler,wetrackcontrol-flowlinearityatthegranularityofopera-
tions.Weusethecontrol-flowlinearityofanoperationtorepresentthecontrol-flowlinearityofthe
continuation of the operation. Control-flow-linear operations can be used in contexts which may
containlinearresources,whereascontrol-flow-unlimitedoperationscannot.Anoperationsignature
𝐴↠𝑌𝐵 is annotated with a linearity𝑌 to denote its control-flow linearity. The dubiousWrite✗
function can now be rewritten to correctly track control-flow linearity as follows.
                  dubiousWrite✓  : File→•()!{Choose :()↠◦ Bool}
                  dubiousWrite✓=𝜆•𝑓.
                       let◦𝑏←(do Choose()){Choose:()↠◦Bool} in
                        let◦𝑠← if𝑏 then "A" else "B" in               continuation of Choose
                        let◦𝑓′← write(𝑠,𝑓) in close𝑓′
Now, the typeof dubiousWrite✓ specifies thatthe operation Choose :()↠◦ Bool is control-flow
linear (i.e. the continuation of Choose is linear). We also annotate let-bindings with linearity
information. In let𝑌𝑥←𝑀  in𝑁, the term𝑁 has control-flow linearity𝑌, and in particular
the◦ annotations on the let-bindings in dubiousWrite✓ permit the use of the linear file handle
throughout.
   The lineartype system of F◦eff uses thecontrol-flow linearity of operationsto restrict the useof
continuationsinhandlers,whichensuresthatcontrol-flow-linearcontextsareenteredonlyonce.
For instance, consider the handling of dubiousWrite✓ with the same multi-shot handler.
    let𝑓← open"C.txt" in handle(dubiousWrite✓𝑓) with{Choose _𝑟↦→𝑟 true;𝑟 false}
This is ill-typed due to the fact that Choose is control-flow linear, which means the resumption𝑟
has a linear function type, meaning it must be applied exactly once.
   Welift the control-flow linearity of operations to effect row typesand reflect it in their kinds
Row𝑌.Similartovaluelinearity,wealsohaveasubkindingrelationforcontrol-flowlinearity.Recall
thatthe control-flowlinearityof (theoperationsin)effect rowtypesis actuallythecontrol-flow
linearity of their contexts, not themselves. This induces a duality between value linearity and
control-flow linearity paralleling the duality between positive values and negative continuations.
Asaconsequence,thesubkindingrelationforcontrol-flowlinearityis⊢ Row◦≤ Row•,thereverse
ofthatforvaluelinearity.Intuitively,thissaysthatcontrol-flow-linearoperationscanbetreated
as control-flow-unlimited operations, because it is safe to use control-flow-linear operations in
unlimitedcontexts.Forexample,considerthefollowingfunctiontossCoinwhichtakesafunction
that returns a boolean and tosses a coin using this function.
                tossCoin :∀𝜇Row•.(()→• Bool!{𝜇})→• String!{𝜇}
                tossCoin=Λ𝜇Row•.𝜆•𝑔. let•𝑏←𝑔() in if𝑏 then "heads" else "tails"


                                Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 6

54:6                                                                              Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

As no linear resource isused, the effect type of tossCoin and its parameteris given by a control-
flow-unlimited row variable𝜇 : Row•. Via subkinding, we can instantiate𝜇 with operations with
either control-flow linearity. For instance, suppose we have⊢𝑅1  : Row• and⊢𝑅2  : Row◦ for
𝑅1= Choose :()↠• Booland𝑅2= Choose :()↠◦ Bool, then:
                          tossCoin𝑅1(𝜆•().(do Choose()){𝑅1}) : String!{𝑅1}
                          tossCoin𝑅2(𝜆•().(do Choose()){𝑅2}) : String!{𝑅2}
   The subkinding relation of control-flow linearity only influences how operations are used,
not how they are handled. We can use control-flow-linear operations as control-flow-unlimited
operations(i.e.,usetheminunlimitedcontexts),butthisdoesnotimplythatwecanhandle control-
flow-linear operations as control-flow-unlimited operations (i.e., handle them by resuming any
number of times). Our linear type system does not allow control-flow-linear operations to be
handled by multi-shot handlers despite the subkinding relation Row◦≤ Row•. This is because
when handling, we directly look at the control-flow linearity on operation signatures instead of
theirkinds,whereno↠◦ canbeupcastto↠•.Thiscanbeseenmoreclearlyfromthetypingrules
in Section3.2. We formally state the soundness of              F◦eff in Sections3.4and3.5.

2.3   Qualified Linear Types
As we have seen from the examples so far, F◦eff requires linearity annotations on𝜆-abstractions
and let-bindings. Though this can suffice for an explicit calculus, it can prove cumbersome for
practicalprogramminglanguagesandcurtailthemodularityofprograms.Unfortunately,wecannot
entirelyovercometheselimitationsbyintroducingsubsumptionrelationsbetweentypes,orusing
Hindley-Milnertype inferencetoinfer them.Thereason isthatthere areinnerdependencies on
the linearity. For instance, consider the following function verboseId which is almost the same
as the function id in Section2.1but outputs the log message              "id is called" using the operation
Print : String↠() before returning.
               verboseId :∀𝜇Row𝑌1𝛼Type𝑌2.𝛼→𝑌0𝛼 !{Print : String↠𝑌3();𝜇}
               verboseId=Λ𝜇Row𝑌1𝛼Type𝑌2.𝜆𝑌0𝑥.let𝑌4()← do Print "id is called" in𝑥
   Dependingondifferentchoicesof𝑌0,𝑌1,𝑌2,𝑌3,and𝑌4,wecangivetenwelltypedvariationsof
verboseId.Theirtypes are shown asfollows,omittingprimarykinds andsignaturesforreadability.
                       ∀𝜇•𝛼•.𝛼→•𝛼 !{Print :•;𝜇}             ∀𝜇•𝛼•.𝛼→◦𝛼 !{Print :•;𝜇}
                       ∀𝜇•𝛼•.𝛼→•𝛼 !{Print :◦;𝜇}             ∀𝜇•𝛼•.𝛼→◦𝛼 !{Print :◦;𝜇}
                       ∀𝜇◦𝛼•.𝛼→•𝛼 !{Print :•;𝜇}             ∀𝜇◦𝛼•.𝛼→◦𝛼 !{Print :•;𝜇}
                       ∀𝜇◦𝛼•.𝛼→•𝛼 !{Print :◦;𝜇}             ∀𝜇◦𝛼•.𝛼→◦𝛼 !{Print :◦;𝜇}
                       ∀𝜇◦𝛼◦.𝛼→•𝛼 !{Print :◦;𝜇}             ∀𝜇◦𝛼◦.𝛼→◦𝛼 !{Print :◦;𝜇}
The key observation is that the control-flow linearity of the operation Print (as well as the row
variable𝜇)dependsonthevaluelinearityoftheparametertype𝛼,becausetheparameter𝑥 isused
inthecontinuationofPrint.Toexpressthiskindofdependency,weusealineartypesystembased
on qualified types inspired by Quill [Morris2016]. In the ML-style calculus                     Q◦eff with qualified
linear types, verboseId can be written and ascribed a principal type as follows.
                       verboseId :∀𝛼𝜇𝜙𝜙′.(𝛼⪯𝜙)⇒𝛼→𝜙′𝛼 !{Print :𝜙;𝜇}
                       verboseId=𝜆𝑥.do Print "42";𝑥
Thelinearityvariables𝜙 and𝜙′ quantifyover◦and•.Wedonotusekindstorepresentlinearityof
type variables; instead, all linearity information is represented using predicatesof the form𝜏⪯𝜏′,
where𝜏 is a valuetype, row type or linearitytype(◦,• or a linearityvariable). The type scheme of


Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 7

Soundly Handling Linearity                                                                                                                                                 54:7

verboseIdisextendedwiththepredicate𝛼⪯𝜙,meaningthatthevaluelinearityof𝛼 islessthan
that of𝜙, which is the control-flow linearity of Print. This type scheme succinctly expresses all ten
possibilitieslistedabove.Thetypeinferencealgorithmof Q◦eff (Section5.4)infersallsuchlinearity
dependency constraints without the need for any type, effect, or linearity annotations.

2.4   Qualified Effect Types
In additionto the syntacticoverhead oflinear types, therow-based effectsystem of F◦eff is alsonot
entirely satisfying when tracking control-flow linearity. Row-based effect systems have demon-
strated their practicality in research languages such as Links [Hillerström and Lindley2016],
Koka [Leijen2017],and                    Frank [Lindley etal . 2017]. Insuch effectsystems, sequenced computa-
tions must have the same effect type, which can be smoothly realised by unification in systems
based on Hindley-Milner type inference. However, though fixing effect types between sequenced
computations is often acceptable, it does introduce some imprecision, and this can become more
pronounced when control-flow linearity is brought into the mix.
   ToseetheproblemconcretelyinF◦eff,considerthefollowingfunctionverboseClosewhichtakesa
file handle, reads a string using the operation Get :()↠ String, closes the file handle, and outputs
the string using the operation Print : String↠().

       verboseClose : File→•()!{𝑅}
       verboseClose=𝜆•𝑓. let◦𝑠←(do Get()){𝑅1} in let•()← close𝑓 in(do Print𝑠){𝑅2}

   Note that the second let-binding does not need to be annotated as linear, because the linear
resource𝑓 doesnot appearafter it.Thelinear resource𝑓 alsodoes notappearin thecontinuation
ofPrint.Since𝑅1,𝑅2,and𝑅 shouldbeequalintherow-basedeffectsystemofF◦eff,omittingthefull
operation signatures for simplicity, we could write𝑅=𝑅1 =𝑅2 ={Get :◦,Print :•} in the ideal
case. However, this is actually ill-typed because all operations in𝑅1 should be control-flow linear,
as the linear resource𝑓 is used in their continuations.
   An intuitive way to relax this limitation of F◦eff is to introduce a trivial subtyping relation on
concreteeffectrowtypes.Wesay𝑅1 isasubtypeof𝑅2,ifalloperationlabelsin𝑅1 arealsoin𝑅2
with thesame signatures, andwhen𝑅1 ends witha row variable,𝑅2 must endwith the samerow
variable. Then, in the verboseClose example, we can write𝑅1 ={Get :◦},𝑅2 ={Print :•}, and
𝑅={Get :◦,Print :•}, which are safe given that𝑅1 and𝑅2 are both subtypes of𝑅.
   Wecallthesubtypingrelationtrivialbecauseitdoesnotallowsubtypingbetweenrowvariables;
an open row𝑅1 is a subtype of𝑅2 only if𝑅2 contains the same row variable as𝑅1. For the above
verboseCloseexamplethisworks,butforotherfunctionswhichmakegreateruseofpolymorphism,
it can still seem overly-restrictive. For instance, consider the following function sandwichClose
which takes two functions and a file handle, and makes a sandwich using them.

                sandwichClose :(()→•()!{𝑅1},File,()→•()!{𝑅2})→•()!{𝑅}
                sandwichClose=𝜆•(𝑔,𝑓,ℎ). let◦()←𝑔() in let•()← close𝑓 inℎ()

Usingourtrivial-subtypingworkaround,werequireboth𝑅1and𝑅2tobesubtypesof𝑅.Theproblem
appearswhenwetrytobepolymorphicover𝑅1 and𝑅2.Becausetheyaresubtypesofthesamerow
type𝑅, their row variables must be the same, i.e., we can only write𝑅1=𝑅2=𝜇 in F◦eff.
   To support non-trivial subtyping relations between row variables, we may again use qualified
types,thistimetoexpressrowsubtypingconstraints.Inadditiontoqualifiedlineartypes, Q◦eff also
supports qualified effect types inspired by Rose [Morris and McKinna2019]. In                         Q◦eff, the function
sandwichClosecanbegiventhe followingtype.Notethatherewestill choosetofixfunctionsto
be unlimited for readability.


                                Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 8

54:8                                                                              Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris


                sandwichClose  :∀𝜇1𝜇2𝜇.(𝜇1⩽𝜇,𝜇2⩽𝜇,File⪯𝜇1)
                                  ⇒(()→•()!{𝜇1},File,()→•()!{𝜇2})→•()!{𝜇}
                sandwichClose =𝜆•(𝑔,𝑓,ℎ). let()←𝑔() in let()← close𝑓 inℎ()
Theconstraints𝜇1⩽𝜇 and𝜇2⩽𝜇 expressthatrows𝜇1 and𝜇2 arecontainedin𝜇,andtheconstraint
File⪯𝜇1 expressesthatthevaluelinearityofFileislessthanthecontrol-flowlinearityof𝜇1,which
essentially means that𝜇1 is control-flow linear. As in Section2.3, the type inference algorithm of
Q◦eff infers these row subtyping constraints without the need for any annotation. The qualified
lineartypesand qualifiedeffect typesof Q◦eff aredecidable. Wegive aconstraint solvingalgorithm
which checks the satisfiability of both linearity constraints and row constraints in Section5.6.

3   AN EXPLICIT HANDLER CALCULUS WITH LINEAR TYPES
Inthissection,wepresentthesyntax,type-and-effectsystem,operationalsemanticsandmetatheory
of F◦eff,aSystemF-stylefine-graincall-by-valuecalculuswithlineartypesandeffecthandlers. F◦eff
is basedon the core languageof Links which adaptsthe subkinding-based linear typesystem of
F◦ [Mazurak et al . 2010] and a row-based effect system [Hillerström and Lindley2016;Lindley and
Cheney2012]. Thelinear typesystem andeffect systemof          F◦eff areextended totrack control-flow
linearity, whichaddressesthe soundnessproblemarisingfrom theinterferenceoflinear resources
andmulti-shotcontinuations.Weshowthat F◦eff istrulylinearitysafebydefiningalinearity-aware
semanticsand provingthatno linearresourceis discardedorduplicatedduring evaluationinthe
presence of multi-shot effect handlers.

3.1   Syntax and Kinding Rules
Figure1showsthe syntax oftypes, kinds,contexts, values,and computations of    F◦eff. We introduce
asyntacticcategory𝑌 forlinearityconsistingof•and◦,whichintuitivelymeansunlimitedand
linear, respectively. The meaning of linearity varies for values and effects; value types track value
linearity,andeffecttypestrackcontrol-flowlinearity.Everythingrelevanttolinearityishighlighted
in the figure. The remaining part is a relatively standard fine-grain call-by-value calculus with
effect handlers and row-based effect system [Hillerström et al.2020a].
   F◦eff explicitlydistinguishesbetweenvaluetypesandcomputationtypesaswellastheirterms.
Valuetypesincludetypevariables𝛼,functiontypes𝐴→𝑌𝐶,andpolymorphictypes∀𝑌𝛼𝐾.𝐶.Value
terms include value variables𝑥,𝜆-abstractions𝜆𝑌𝑥𝐴.𝑀, and type abstractionsΛ𝑌𝛼𝐾.𝑀. Function
types, polymorphic types, and abstractions are annotated with their value linearity𝑌. In examples
we will freely make use of base types and algebraic data types whose treatment is quite standard.
We elect to allow polymorphic computation types rather than applying the value restriction.
   A computation type𝐴!𝐸 comprises a result value type𝐴 and an effect type𝐸 specifying the
operations that the computation might perform. Effect types{𝑅} are represented by row types𝑅.
Eachoperationlabelinrowsisannotatedwith apresencetype𝑃,whichindicates thatthelabelis
either absent Abs, present with signature𝐴↠𝑌𝐵, or polymorphic𝜃 in its presence. An operation
signature𝐴↠𝑌𝐵 describes an operationwith parameter oftype𝐴 that returnsa result of type𝐵
and whose control-flow linearity is𝑌. Row types are either open(ending with arow variable𝜇)
or closed (ending with·, which we often omit). We identify rows up to reordering of labels and
ignore absent labels in closed row types [Rémy1994]. Handler types       𝐶⇒𝐷 represent handlers
transforming computationsof type𝐶 to computationsof type𝐷. Byconvention,welet𝛼 range
over value type variables,𝜇 over row type variables, and𝜃 over presence type variables, but we
also let𝛼 range over all over them (e.g. when binding quantifiers of unspecified kind).
   Function application𝑉𝑊 and type application𝑉𝑇 are standard. A computation(return𝑉)𝐸
returnsthevalue𝑉.Anoperationinvocation(doℓ𝑉)𝐸 invokestheoperationℓ withparameter


Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 9

Soundly Handling Linearity                                                                                                                                                 54:9

      Value types       𝐴,𝐵 ::=𝛼|𝐴→𝑌𝐶|∀𝑌𝛼𝐾.𝐶
      Computation types 𝐶,𝐷 ::=𝐴!𝐸
      Effect types        𝐸 ::={𝑅}
      Row types         𝑅 ::=ℓ :𝑃;𝑅|𝜇|·
      Presence types      𝑃 ::= Abs|𝐴↠𝑌𝐵|𝜃
      Handler types       𝐹 ::=𝐶⇒𝐷
      Types            𝑇 ::=𝐴|𝑅|𝑃|𝐶|𝐸|𝐹
      Kinds            𝐾 ::=Type𝑌| RowL𝑌| Presence𝑌| Effect| Comp| Handler
      Linearity          𝑌 ::=           •|◦
      Label sets     L ::=∅|{ℓ}⊎L
      Type contexts              Γ ::=·| Γ,𝑥 :𝐴
      Kind contexts              Δ ::=·| Δ,𝛼 :𝐾
      Values         𝑉,𝑊 ::=𝑥|𝜆𝑌𝑥𝐴.𝑀| Λ𝑌𝛼𝐾.𝑀
      Computations    𝑀,𝑁 ::=𝑉𝑊|𝑉𝑇|(return𝑉)𝐸|(doℓ𝑉)𝐸
                                      |  let𝑌𝑥←𝑀 in𝑁| handle𝑀 with𝐻
      Handlers          𝐻 ::={return𝑥↦→𝑀}|{ℓ𝑝𝑟↦→𝑀}⊎𝐻


                 Fig. 1. Syntax of Types, Kinds, Contexts, Values and Computations of F◦eff



𝑉.Theyarebothannotatedwiththeireffecttypesfordeterministictyping.Sequencinglet𝑌𝑥←
𝑀 in𝑁 evaluates𝑀 andbindsitsresultto𝑥 in𝑁.Thelinearity𝑌 basicallyindicatesthecontrol-
flowlinearityof𝑁.Handlinghandle𝑀 with𝐻 handlescomputation𝑀 withhandler𝐻.Handlers
aregivenbyareturnclausereturn𝑥↦→𝑀,whichbindsthereturnedvalueas𝑥 in𝑀,andalistof
operationclausesℓ𝑝𝑟↦→𝑀,whichbindtheoperationparameterto𝑝 andcontinuationto𝑟 in𝑀.
  We have six kinds𝐾, one for each syntactic category of types. Kinds are parameterised by
linearity𝑌. Thekinds ofvalue types Type𝑌 denote valuelinearity, andthe kinds ofpresence types
Presence𝑌 androwtypesRowL𝑌 denotecontrol-flowlinearity. ThelabelsetL tracksthe labels
thatshouldnotappearinarow,whichisusedtoavoidduplicatedlabelsinrows.Thekindsofeffect,
computation, and handlertypes are notannotatedwith any linearity information.TypecontextsΓ
associate value variables with types, and kind contextsΔ associate type variables with kinds.
  Figure2givesthekindingrules.Linearity-relevantpartsarehighlighted.Thekindingrelation
Δ⊢𝑇  :𝐾 states that type𝑇 has kind𝐾 in contextΔ. The subkinding relation⊢𝐾≤𝐾′ states
that𝐾 is a subkind of𝐾′. We sometimes write simplyΔ⊢𝑇 :𝑌 for value, rowand presence types
whentheunderlyingkindisclear.Thekindingrulesforeffect,computation,andhandlertypesare
standard[Hillerströmetal .2020a]andirrelevanttolinearity(         K-Effect,K-Comp,andK-Handler).
  Thekindcontextmaintainskindsforvariables(K-TyVar).Thevaluelinearityoffunctionand
polymorphictypescomesfromtheirannotations(K-ForallandK-Fun).Basetypeshavetheirown
valuelinearity,e.g.,⊢ File :◦and⊢ Int :•.Thevaluelinearityof(omitted)algebraicdatatypeslike
pair types(𝐴,𝐵) is lifted from their components;⊢(𝐴,𝐵) :◦ if either⊢𝐴 :◦ or⊢𝐵 :◦.
  AsshowninSection2.1,forvaluelinearity,wehaveasubkindingrelation   ⊢ Type•≤ Type◦ given
by subkinding rules S-Lin and S-Type. This allows us to use unlimited value types as linear value
types since it is always safe to use unlimited values linearly (e.g., the function id in Section2.1).
  We track control-flow linearity at the granularity of operations, and lift it to the kinds of
presence types and row types. Absent labels and empty rows can be given any control-flow
linearity(K-AbsentandK-EmptyRow).Thecontrol-flowlinearityofpresentlabelscomesdirectly


                              Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 10

54:10                                                                            Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

 ⊢𝑌≤𝑌′       ⊢𝐾≤𝐾′
                 S-Type                       S-Pres                                S-Row
S-Lin                   ⊢𝑌≤𝑌′                            ⊢𝑌′≤𝑌                              ⊢𝑌′≤𝑌
 ⊢•≤◦             ⊢ Type𝑌≤ Type𝑌′              ⊢ Presence𝑌≤ Presence𝑌′               ⊢ RowL   𝑌≤ RowL      𝑌′

 Δ⊢𝑇 :𝐾
                                                         K-Fun                           K-Comp
                         K-Forall                             Δ⊢𝐴 : Type𝑌′                 Δ⊢𝐴 : Type𝑌
K-TyVar                    Δ,𝛼 :𝐾⊢𝐶 : Comp                    Δ⊢𝐶 : Comp                    Δ⊢𝐸 : Effect
 Δ,𝛼 :𝐾⊢𝛼 :𝐾              Δ⊢∀𝑌𝛼𝐾.𝐶 : Type𝑌                Δ⊢𝐴→𝑌𝐶 : Type𝑌                  Δ⊢𝐴!𝐸 : Comp

K-Effect                 K-Present                           K-Absent                       K-EmptyRow
  Δ⊢𝑅 : Row∅
Δ⊢{𝑅} : Effect           Δ⊢𝐴↠𝑌𝐵 : Presence𝑌                   Δ⊢ Abs : Presence𝑌            Δ⊢· : RowL𝑌

           K-ExtendRow                          K-Handler                            K-Upcast
             Δ⊢𝑃 : Presence𝑌                         Δ⊢𝐶 : Comp                        Δ⊢𝑇 :𝐾
             Δ⊢𝑅 : RowL⊎{ℓ}      𝑌                   Δ⊢𝐷 : Comp                        ⊢𝐾≤𝐾′
            Δ⊢ℓ :𝑃;𝑅 : RowL       𝑌              Δ⊢𝐶⇒𝐷 : Handler                      Δ⊢𝑇 :𝐾′


                                Fig. 2. Kinding and Subkinding Rules for F◦eff


fromoperationsignatures(K-Present).Thecontrol-flowlinearityofrowextensionsaregivenby
the labels and remaining rows (K-ExtendRow).
  AsshowninSection2.2,control-flowlinearityisdualtovaluelinearityinsomesense:wehave
⊢ RowL◦≤ RowL• and⊢ Presence◦≤ Presence• given by subkinding rules S-Lin, S-Pres, and
S-Row.Thisallowslineareffectrowstobeusedasunlimitedeffectrowsasitisalwayssafetouse
control-flow-linear operations in unlimited contexts (e.g., the function tossCoin in Section2.2).

3.2   Typing Rules
We define two auxiliary relations in Figure3for typing rules. The judgement   Δ⊢Γ :𝑌 states that
under kind contextΔ all types inΓ have linearity𝑌. As the subkinding relation for value linearity
holdsthatType•≤ Type◦,therelationΔ⊢Γ :•guaranteesthatallvariablesinΓ areunlimitedand
the relationΔ⊢Γ :◦ is a tautology. Dually, as the subkinding relation for control-flow linearity
holds that Row◦≤ Row•, the relationΔ⊢𝑅 :◦ guarantees that all operations in𝑅 are control-flow
linear and the relationΔ⊢𝑅 :• is a tautology. The context splitting judgementΔ⊢ Γ = Γ1+Γ2
statesthatunderkindcontextΔthetypecontextΓ iswellformedandcanbesplitintotwocontexts
Γ1 andΓ2 suchthateachlinearvariableonlyappearsinoneofthem.WewriteΔ⊢Γ1+Γ2 whenwe
onlycareaboutsplittingresults,andwriteΓ1+Γ2 intypingruleswhenthekindcontextΔ isclear.
  The typingrules for values,computations, and handlersare givenin Figure4. Linearity-relevant
parts are highlighted. The relations Δ;Γ⊢𝑉  :𝐴, Δ;Γ⊢𝑀  :𝐶, and Δ;Γ⊢𝐻  :𝐶⇒𝐷, state
respectivelythat:value𝑉 hastype𝐴,computation𝑀 hastype𝐶 andhandler𝐻 hastype𝐶⇒𝐷 in
contextsΔ andΓ. As usual, the type contexts and types are well formed under the kind contexts.
  The T-Var rule requires the remaining context to be unlimited. The T-Abs and T-TAbs rules
checkthevaluelinearityoffunctionsandpolymorphiccomputationsagainstthatofthecontextvia


Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 11

Soundly Handling Linearity                                                                                                                                               54:11

 Δ⊢Γ :𝑌                                            L-Extend
                       L-Empty                     Δ⊢Γ :   𝑌       Δ⊢𝐴 : Type𝑌

                        Δ⊢· :  𝑌                          Δ⊢(Γ,𝑥 :𝐴) :    𝑌
 Δ⊢Γ=Γ1+Γ2
                   C-Empty                     C-Unl           •      Δ⊢Γ=Γ1+Γ2
                                                 Δ⊢𝐴 : Type
                   Δ⊢·=·+·                      Δ⊢Γ,𝑥 :𝐴=(Γ1,𝑥 :𝐴)+(Γ2,𝑥 :𝐴)

          C-LinLeft     ◦      Δ⊢Γ=Γ1+Γ2                   C-LinRight    ◦      Δ⊢Γ=Γ1+Γ2
          Δ⊢𝐴 : Type                                       Δ⊢𝐴 : Type
             Δ⊢Γ,𝑥 :𝐴=(Γ1,𝑥 :𝐴)+Γ2                            Δ⊢Γ,𝑥 :𝐴=Γ1+(Γ2,𝑥 :𝐴)


                            Fig. 3. Linearity of Contexts and Context Splitting


 Δ;Γ⊢𝑉 :𝐴       Δ;Γ⊢𝑀 :𝐶        Δ;Γ⊢𝐻 :𝐶⇒𝐷

                                  T-Abs                                   T-TAbs
       T-Var                       Δ⊢Γ :𝑌       Δ⊢𝐴 : Type𝑌′                Δ⊢Γ :𝑌  𝛼  ∉ ftv(Γ)
             Δ⊢Γ :•                      Δ;Γ,𝑥 :𝐴⊢𝑀 :𝐶                          Δ,𝛼 :𝐾;Γ⊢𝑀 :𝐶
        Δ;Γ,𝑥 :𝐴⊢𝑥 :𝐴               Δ;Γ⊢𝜆𝑌𝑥𝐴.𝑀 :𝐴→𝑌𝐶                       Δ;Γ⊢Λ𝑌𝛼𝐾.𝑀 :∀𝑌𝛼𝐾.𝐶

     T-App                          T-TApp
     Δ;Γ1⊢𝑉 :𝐴→𝑌𝐶                    Δ;Γ⊢𝑉 :∀𝑌𝛼𝐾.𝐶                 T-Return
         Δ;Γ2⊢𝑊 :𝐴                        Δ⊢𝑇 :𝐾                   Δ;Γ⊢𝑉 :𝐴      Δ⊢𝐸 : Effect
     Δ;Γ1+Γ2⊢𝑉𝑊 :𝐶                  Δ;Γ⊢𝑉𝑇 :𝐶[𝑇/𝛼]                    Δ;Γ⊢(return𝑉)𝐸 :𝐴!𝐸

      T-Do                                      T-Seq
           𝐸 ={ℓ :𝐴↠𝑌𝐵;𝑅}                        Δ;Γ1⊢𝑀 :𝐴!{𝑅}      Δ;Γ2,𝑥 :𝐴⊢𝑁 :𝐵!{𝑅}
      Δ;Γ⊢𝑉 :𝐴      Δ⊢𝐸 : Effect                            Δ⊢Γ2 :𝑌           Δ⊢𝑅 :𝑌
          Δ;Γ⊢(doℓ𝑉)𝐸 :𝐵!𝐸                           Δ;Γ1+Γ2⊢ let𝑌𝑥←𝑀 in𝑁 :𝐵!{𝑅}

                                              T-Handler
                                                      𝐻 ={return𝑥↦→𝑀}⊎{ℓ𝑖𝑝𝑖𝑟𝑖↦→𝑁𝑖}𝑖
                                              𝐶 =𝐴!{(ℓ𝑖 :𝐴𝑖↠𝑌𝑖𝐵𝑖)𝑖;𝑅}   𝐷 =𝐵!{(ℓ𝑖 :𝑃)𝑖;𝑅}

T-Handle                                                   Δ⊢Γ :•       Δ;Γ,𝑥 :𝐴⊢𝑀 :𝐷
Δ;Γ1⊢𝐻 :𝐶⇒𝐷      Δ;Γ2⊢𝑀 :𝐶                              [Δ;Γ,𝑝𝑖 :𝐴𝑖,𝑟𝑖 :𝐵𝑖→𝑌𝑖𝐷⊢𝑁𝑖 :𝐷]𝑖

 Δ;Γ1+Γ2⊢ handle𝑀 with𝐻 :𝐷                                        Δ;Γ⊢𝐻 :𝐶⇒𝐷


                                       Fig. 4. Typing Rules for F◦eff


thepremiseΔ⊢Γ :𝑌.Thetypingrulesforfunctionapplicationandtypeapplicationarestandard
(T-AppandT-TApp). Note thatwe need to splitthe context in theT-Apprule to avoidduplicating
linear variables. The T-Return rule does not constrain the effects. The T-Do rule ensures that


                             Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 12

54:12                                                                            Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

 Δ⊢𝑅⩽𝑅′ :𝐾
            Δ⊢𝑅 :𝐾                  Δ⊢𝑅1⩽𝑅2 :𝐾      Δ⊢𝑅2⩽𝑅3 :𝐾                             Δ⊢𝜇 :𝐾
         Δ⊢𝑅⩽𝑅 :𝐾                               Δ⊢𝑅1⩽𝑅3 :𝐾                               Δ⊢·⩽𝜇 :𝐾

                     Δ⊢𝑃 : Presence𝑌                                    Δ⊢𝑃 : Presence𝑌
                 Δ⊢𝑅1⩽𝑅2 : RowL⊎{ℓ}𝑌                                Δ⊢𝑅1⩽𝑅2 : RowL⊎{ℓ}𝑌
            Δ⊢ℓ : Abs;𝑅1⩽ℓ :𝑃;𝑅2 : RowL𝑌                        Δ⊢ℓ :𝑃;𝑅1⩽ℓ :𝑃;𝑅2 : RowL𝑌


                                Fig. 5. Trivial Subtyping for Effect Row Types


the operationℓ and its parameter𝑉 agree with the effect signature𝐸. TheT-Handle rule uses a
handler of type𝐶⇒𝐷 to handle a computation of type𝐶.
  TheT-Handlerrulechecksthat(deep)handlersmustnotuseanylinearvariablesviathepremise
Δ⊢Γ :• because they are recursively applied during evaluation. More importantly, it connects the
control-flowlinearityof operationswiththe valuelinearityof resumptionfunctions.Inthe typing
judgementofeachoperationclauseℓ𝑖 :𝐴𝑖↠𝑌𝑖𝐵𝑖,thecontinuation𝑟𝑖 isgiventhevaluelinearity𝑌𝑖,
whichisexactlythecontrol-flowlinearityofℓ𝑖 thatrestrictstheuseofℓ𝑖’scontinuation.Concretely,
when𝑌𝑖 =◦,thecontinuationofℓ𝑖 mayusesomelinearresources.Making𝑟𝑖 linearguaranteesthat
theyareusedexactlyonce.When𝑌𝑖 =•,thecontinuationofℓ𝑖 mustnotuseanylinearresources
and𝑟𝑖 isunlimited.NotethatthesubkindingrelationRow◦≤ Row• doesnotinfluencethehandling
behaviour, because the T-Handler rule uses the linearity annotations on operation signatures.
  The T-Seq rule for sequencing is the most important rule for tracking control-flow linearity,
becausethisistheprimarysourceofsequentialcontrolflowinafine-graincall-by-valuecalculus.
Though handling is another source of sequential control flow, deep handlers are unlimited and
cannotinfluencecontrol-flow linearity. We willdiscuss theextension ofshallowhandlerswhich
may capture linear resources and influence control-flow linearity in Section6.
  Remember that for let𝑌𝑥←𝑀  in𝑁, the linearity annotation𝑌 indicates the control-flow
linearity of𝑁 which determines how many times the control can enter𝑁. Concretely, when
𝑌 =◦,𝑁 may use some linear variables bound outside (Δ⊢ Γ2  :◦), and all operations in𝑀
should be control-flow linear (Γ⊢𝑅 :◦); when𝑌 =•,𝑁 cannot use any linear variables from
the context (Δ⊢ Γ2 :•), and operations in𝑀 have no restriction on their control-flow linearity
(Δ⊢𝑅  :•). The dubiousWrite✓ in Section2.2is an example. Note that technically, the third
sequencing let◦𝑓′← write(𝑠,𝑓) in close𝑓′ can be changed to let• because no linear variable
bound outside is used by the context let𝑓′← _ in close𝑓′.
  AsweobservedbythefunctionverboseCloseinSection2.4,thefactthatthe             T-Seqrulerequires
the𝑀 and𝑁 to have the same effect type is too restrictive for tracking control-flow linearity. We
can improve it by using a trivial subtyping relation between effect types as follows.
               T-SeqSub
                            Δ;Γ1⊢𝑀 :𝐴!{𝑅1}      Δ;Γ2,𝑥 :𝐴⊢𝑁 :𝐵!{𝑅2}
                Δ⊢Γ2 :𝑌            Δ⊢𝑅1 :𝑌            Δ⊢𝑅1⩽𝑅 :𝐾               Δ⊢𝑅2⩽𝑅 :𝐾
                                  Δ;Γ1+Γ2⊢ let𝑌𝑥←𝑀 in𝑁 :𝐵!{𝑅}
The trivialsubtyping relation oneffect row typesare shown inFigure5.The judgement   Δ⊢𝑅⩽
𝑅′ :𝐾 makes it explicit that𝑅 and𝑅′ are well kinded and can be given kind𝐾 under kind context
Δ.Itsimplyrequiresthatalloperationlabelswiththeirsignaturesandrowvariablein𝑅 mustalso


Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 13

Soundly Handling Linearity                                                                                                                                               54:13

appearin𝑅′.This subtypingrelationdoes notallownon-trivialsubtypingbetweenrowvariables.
We consider a more expressive alternative using qualified types in Section5.

3.3   Operational Semantics


E-App        (𝜆𝑌𝑥𝐴.𝑀)𝑉{𝑀[𝑉/𝑥]
E-TApp        (Λ𝑌𝛼𝐾.𝑀)𝑇{𝑀[𝑇/𝛼]
E-Seq           let𝑌𝑥←(return𝑉)𝐸 in𝑁{𝑁[𝑉/𝑥]
E-Ret        handle(return𝑉)𝐸 with𝐻{𝑁[𝑉/𝑥],                        where(return𝑥↦→𝑁)∈𝐻
E-Op        handleE[(doℓ𝑉)𝐸] with𝐻{𝑁[𝑉/𝑝,(𝜆𝑌𝑦𝐵.handleE[(return𝑦)𝐸] with𝐻)/𝑟],
                                             whereℓ ∉ bl(E),(ℓ𝑝𝑟↦→𝑁)∈𝐻, and(ℓ :𝐴→𝑌𝐵)∈𝐸
E-Lift           E[𝑀]{E[𝑁],                                                          if𝑀{𝑁
                Evaluation contextsE ::=[]| let𝑌𝑥←E in𝑁| handleE with𝐻
   bl([])=∅        bl(let𝑌𝑥←E in𝑁)= bl(E)        bl(handleE with𝐻)= bl(E)∪dom(𝐻)

                               Fig. 6. Small-step Operational Semantics of F◦eff

  Figure6gives a standard small-step operational semantics for    F◦eff [Hillerström et al . 2020a]. It
is clear from the definition of evaluation contexts that let-binding and handling are indeed the
only two constructs that influence the control flow. The function bl(−) computes the set of bound
operation labels inanevaluationcontextE,i.e.theoperationlabelsforwhichasuitablehandler
hasbeeninstalled.Thepurposeofthisfunctionistoensurethatanyoperationinvocation(doℓ𝑉)
is always handled by the innermost suitable handler.

3.4   Metatheory
We now prove a type soundness result for F◦eff. First we define normal forms of computations.
  Definition 3.1 (Computation Normal Forms). We say a computation𝑀 is in a normal form with
respect to𝐸, if it is either of the form𝑀 =(return𝑉)𝐸′ or𝑀 =E[(doℓ𝑉)𝐸′] forℓ∈𝐸 and
ℓ ∉ bl(E).
  Syntactic type soundness of F◦eff relies on progress and subject reduction. The proofs can be
found in AppendicesA.2andA.3.
  Theorem 3.2 (Progress).  If⊢𝑀 :𝐴!𝐸, then either there exists𝑁 such that𝑀{𝑁 or𝑀 is in a
normal form with respect to𝐸.

  Theorem 3.3 (Subject reduction).  IfΔ;Γ⊢𝑀 :𝐶 and𝑀{𝑁, thenΔ;Γ⊢𝑁 :𝐶.
  Wenowshow thatour trackingofvalue linearityand control-flowlinearityin thetypesystem
issound,byprovingthatlinearvariablesneverappearintermsthatareclaimedtobeunlimited.In
F◦eff,atermisclaimedtobeunlimitedifitappearsinanunlimitedvalue,acontrol-flow-unlimited
context, or a deep handler. The following theorem covers all three of these cases.
  Theorem 3.4 (Unlimited is unlimited).
    1.Unlimited values are unlimited: if  Δ;Γ⊢𝑉 :𝐴 andΔ⊢𝐴 :•, thenΔ⊢Γ :•.
    2. Unlimited continuations are unlimited: ifΔ;Γ⊢E[(doℓ𝑉)𝐸] :𝐶 for𝐸 ={ℓ :𝐴↠•𝐵;𝑅}
       andℓ ∉ bl(E), then there exists Δ⊢ Γ = Γ1+ Γ2 such that Δ⊢ Γ1  :• and Δ;Γ1,𝑦  :𝐵⊢
       E[(return𝑦)𝐸] :𝐶.


                               Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 14

54:14                                                                            Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

     L-App          (𝜆𝑌𝑥𝐴.𝑀)𝑉  S∅{𝑀[𝑉′/𝑥], where(𝑉′,S)= tag(𝑉)
     L-TApp         (Λ𝑌𝛼𝐾.𝑀)𝑇  ∅∅{𝑀[𝑇/𝛼]
     L-Seq                     let𝑌𝑥← return𝑉 in𝑁  S∅{𝑁[𝑉′/𝑥], where(𝑉′,S)= tag(𝑉)
     L-Ret            handle(return𝑉)𝐸 with𝐻  S∅{𝑁[𝑉′/𝑥],
                                                    where(return𝑥↦→𝑁)∈𝐻,(𝑉′,S)= tag(𝑉)
     L-Op            handleE[(doℓ𝑉)𝐸] with𝐻  S∅{𝑁[𝑉′/𝑝,𝑊′/𝑟],
                                           whereℓ ∉ bl(E),(ℓ𝑝𝑟↦→𝑁)∈𝐻,(ℓ :𝐴↠𝑌𝐵)∈𝐸,
                                                      𝑊 =𝜆𝑌𝑦𝐵.handleE[(return𝑦)𝐸] with𝐻,
                                               (𝑉′,S1)= tag(𝑉),(𝑊′,S2)= tag(𝑊),S=S1∪S2
     L-Remove          F[𝑉◦]  ∅{𝑉◦}{F[𝑉]
     L-Lift            E[𝑀]  ST{E[𝑁],                         if𝑀ST{𝑁
             Evaluation contexts  E ::=[]| let𝑌𝑥←E in𝑁| handleE with𝐻
             Tag-removing contexts F ::=[]𝑉|[]𝑇

                      Fig. 7. Linearity-aware Small-step Operational Semantics of F◦eff


   3.Deep handlers are unlimited: if  Δ;Γ⊢𝐻 :𝐶⇒𝐷, thenΔ⊢Γ :•.
  The proof can be found in AppendixA.1.
  However,Theorem3.4onlycaresaboutthestatictrackingoflinearvariables.Itsaysnothing
abouttheuse oflinearvaluesduring evaluationdirectly.Inthe nextsection,weprove thatin F◦eff
no linear value is ever discarded or duplicated during evaluation, by defining a linearity-aware
semantics inspired byWalker[2005],Mazurak et al.[2010], andMorris[2016].

3.5   Linearity Safety of Evaluation
Inthissection,wedesignalinearity-awaresemanticsof F◦eff,extendingthesmall-stepoperational
semanticstotracktheintroductionandeliminationoflinearvalues,andprovethatalllinearvalues
are used exactly once during evaluation.
  Wefirstextendthesyntaxofvalueswithvaluesmarkedwithlineartags𝑉◦ toindicatelinear
values during evaluation. The typing rules simply ignore the linear tags.
                                          Values 𝑉 ::=···|𝑉◦
Werestrictattentiontoclosedcomputationsanddefinetwoauxiliaryfunctionslin(𝑉) andtag(𝑉)
for closed values as follows.         true    if·;·⊢𝑉 :𝐴 and· ⊬𝐴 :•

                    lin(𝑉)   =       false   otherwise
                                    (𝑉◦,{𝑉◦})    if lin(𝑉) and𝑉 ≠𝑊◦ for any𝑊
                   tag(𝑉)   =        (𝑉,∅)           otherwise

The predicate lin(𝑉) holds when𝑉 is a genuine linear value as opposed to an unlimited value that
hasbeenupcasttobelinearbysubkinding.Theoperationtag(𝑉) tagsavalueaslinearifitisand
has not been tagged, and yields a pair of the possibly tagged𝑉 and a multiset containing the value
if it is newly tagged and nothing otherwise.
  The linearity-aware semantics is given in Figure7. We augment the previous reduction relation
𝑀{𝑁 with two multi-sets𝑀ST{𝑁, whereS contains the linear values introduced by this


Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 15

Soundly Handling Linearity                                                                                                                                               54:15

reductionstep,andT containsthelinearvalueseliminatedbythisreductionstep.Notethatin F◦eff,
we cannot duplicate or discard a value before we bind it. We introduce linear values at the first
time they are bound to variables(L-App, L-Seq, L-Ret and L-Op). Take L-App for example. When
𝑉 is a non-tagged real linear value (the first case of tag(𝑉)), we tag it and add it to the multiset
of introduced linear values. Otherwise,𝑉 is either not really linear or has been tagged already
(which implies that we have already introduced it). We do not need to update the multisets. We
eliminatelinearvalueswhentheyaredestructed(L-Remove).Asweonlyhavetermabstraction
andtypeabstractionasvalueconstructors,thetag-removingcontextsF capturetheeliminationof
these two cases. It is easy to extend the linearity-aware semantics with other value constructors.
Therelationshipbetweenthetwosemanticsisstraightforward:erasingthelineartagsfromthe
linearity-aware semantics yields the original semantics.
   Wewriteℒ(𝑀),ℒ(𝑉),ℒ(E) andℒ(F) forthemultisetsoftaggedlinearvalueswithin𝑀,𝑉,
E, andF, respectively. They are given by the homomorphic extension of the following equation.
                                            ℒ(𝑉◦)={𝑉◦}∪ℒ(𝑉)
   We define the notion of linear safety similarly to Theorem3.4. A term is linear safe if there are
no tagged linear values in terms that are claimed to be unlimited.
   Definition3.5(LinearSafety). Awell-typedcomputation𝑀 orvalue𝑉 islinearsafe ifandonlyif:
   (1)For every value subterm 𝑊 of the form𝜆•𝑥𝐴.𝑁 orΛ•𝛼𝐾.𝑁,ℒ(𝑊)=∅.
   (2) For every computation subterm𝑁 of the formE[(doℓ𝑉){ℓ:𝐴↠•𝐵;𝑅}] whereℓ ∉ bl(E),
       ℒ(E)=∅.
   (3)For every handler subterm  𝐻,ℒ(𝐻)=∅.
(An alternative way to read Item1is as “for every value subterm   𝑊 with an unlimited type”.)
   Finally,thefollowing theoremstatesthatlinearsafetyis preserved byevaluation,andtagged
linear values are not duplicated or discarded during evaluation.
   Theorem 3.6 (Reduction Safety).  For any closed, well-typed and linear safe computation𝑀 in
F◦eff, if𝑀ST{𝑁, then𝑁 is linear safe andℒ(𝑀)∪S=ℒ(𝑁)∪T.
   The proof can be found in AppendixA.4. Note that tracking linear values explicitly during
evaluationisimportantforshowingthattheyareindeedusedsafely.Otherwise,itisevenunclear
how to state what reduction safety means in the original semantics.

4   CONTROL-FLOW LINEARITY IN LINKS
In this section, we describe our implementation of control-flow linearity tracking in Links. The
implementation fixes a long-standing type soundness bug in Links arising from the interaction
between session types and effect handlers, as we described in the introduction.
   Links is an ML-style language with type inference, linearly typed session types (based on
F◦ [Lindley and Morris2017]), and a row-based effect type system [Hillerström and Lindley2016].
In Links we write Unl for• and Any for◦. The latter is Any as any value can be soundly used
once. The subkindingrelation⊢ Type•≤ Type◦ (Unl≤ Any) allows type variablesof kind Any to be
unifiedwithtypesofeitherkind.Thisallowsustowritefunctionsthatmayacceptbothlinearand
nonlinear values, e.g. theidentity function fun  id(x){x}  :  (a::Any)  ->  (a::Any). Here, we can
instantiatethetypevariable atoalineartype,suchas !Int.End,oranunlimitedtype,suchas Int.
   To make type inference deterministic, Links makes use of two different keywords for defining
unlimited functions and linear functions, which are fun and linfun respectively. For instance, we
can define a channel version of the function faithfulWrite in Section2.1as follows.
fun  faithfulSend(c)  {  linfun  (s)  {  var  c  =  send(s,  c);  close(c)  }  }


                                Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 16

54:16                                                                            Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

Theinferredtypeis(!(a::Any).End)  ->  (a::Any)  ~@  ().ThefaithfulSendfunctiontakesapoly-
morphic channel c and returns a linear function (indicated by ~@ instead of the usual arrow ~>)
that sends a polymorphic value𝑠 over the channel c. If we wanted to we could restrict the inferred
type of the channel c and the input𝑠 by supplying a type annotation to either.
   Totrack control-flowlinearity werepurpose theexisting effectsystem andadd twonew control
flow kinds Any (for•) and Lin (for◦) to signify whether a given context allows control flow to
be unlimited or linear. We further add a new effectful operation space for control-flow-linear
operations,which issyntactically denotedby thearrow =@,in additionto theexisting operation
space denoted by =>. The subkinding relation⊢ Row◦≤ Row• (Lin≤ Any) is implemented by
allowing row variables of kind Any to be unified with both control-flow-linear and unlimited
operations and other row variables ofarbitrary kinds. In contrast, row variables ofkind Lin can
onlybeunifiedwithcontrol-flow-linearoperationsandrowvariablesofkind Lin.Thechangefrom
Unl to Lin is consistent with the duality between value linearity and control-flow linearity.
   SinceLinksisapracticalprogramminglanguage,sequencingisoftenimplicit.Insteadofwriting
linearity annotations on all sequencing, we assume that control-flow linearity is unlimited by
default, and introduce the keyword xlin to switch the control-flow linearity to linear. We also
add the construct lindo to invoke control-flow-linear operations in addition to the existing do
forcontrol-flow-unlimitedoperations.Toillustratetheuseoftheseextensions,letusconsidera
channel version of the function dubiousWrite✓ from Section2.2.

sig  dubiousSend  :  (!String.End)  {Choose:()  =@  Bool|_::Lin}~>  ()
fun  dubiousSend(c)  {xlin;  var  c  =  send(if  (lindo Choose)  "A"  else "B",  c);  close(c)}
The dubiousSend takes a channel c, non-deterministically sends "A" or "B" through it depending
on the result of the operation Choose, and closes the remaining channel. We use xlin to switch the
control-flow linearity to linear so that we can use the linear channel c and must use the control-
flow-linearoperationChoose:()  =@  Boolwiththekeywordlindo.Ifwereplacelindowithdothen
Links correctly rejects the code as the continuation captures the linear endpoint c. The example
fromthe introductionwillberejectedforthe samereason.Forlinear effect handlers,weusethe
linear arrow syntax =@ to bind linear continuations of control-flow-linear operations.

fun(c)  {handle  ({xlin;  dubiousSend(c)})  {case  <Choose  =@  r>  ->  xlin;  r(true)}  }
Here, we interpret the operation Choose as true. The use of xlin in the Choose-clause is necessary
becausethereifiedcontinuation𝑟 islinear.Asthecontinuationisusedlinearly,Linkscorrectly
accepts this program.
   Our implementation works well with previous programs using the effect handler feature in
Links and fixes the type soundness bug. However, being based on F◦, Links suffers from the
limitations outlined in Section2.In the next section, we present a considerably more expressive
calculus, Q◦eff, which uses qualified types for both linearity and effects, enabling a much more
fine-grainedanalysisofcontrol-flowlinearity,andavoidingtheneedtodistinguishbetweenlinear
and non-linear variants of term syntax. We leave the implementation of Q◦eff to future work.

5   AN IMPLICIT CALCULUS WITH QUALIFIED TYPES
Inthissection,wepropose Q◦eff,anML-stylecalculuswhichenhances F◦eff (anditsimplementation
inLinks)intwodirections:minimisingsyntacticoverheadsandimprovingaccuracyofcontrol-flow
linearity tracking. The core idea is to use qualified types for both linear types and effect types.
ThequalifiedlineartypesystemisinspiredbyQuill[Morris2016],whicheliminatesthelinearity
annotationsontermsandsupportsprincipaltypes.Thequalifiedeffectsystemisinspiredbythe
row containment predicate of Rose [Morris and McKinna2019] and the subtyping-based effect


Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 17

Soundly Handling Linearity                                                                                                                                               54:17

systemof Eff[Karachaliasetal .2020;Pretnar2014],whichallowsnon-trivialsubtypingconstraints
between row variables.

5.1   Syntax
Figure8showsthesyntaxofqualifiedtypesof    Q◦eff.Wenamesomesyntacticcategoriesfordefining
meta functions.The remaining syntaxis given infull in AppendixB.1, which ismostly identical
to that of F◦eff, except that we introduce generalising let-bindings let𝑥 =𝑉  in𝑀 to replace
explicittypeabstractionandimplicit instantiationinplaceoftypeapplicationandremovealltype
annotations and linearity annotations.

    Linearity      𝑌 ::=𝜙|•|◦                              Qualified types       𝜌 ::=𝐴|𝜋⇒𝜌
    Types         𝜏 ::=𝐴|𝑅|𝑌                               Type schemes     TySch∋𝜎 ::=𝜌|∀𝛼.𝜎
    Predicates   Pred∋𝜋 ::=𝜏1⪯𝜏2|𝑅1⩽𝑅2                     Type contexts         Env∋ Γ ::=·| Γ,𝑥 :𝜎
                               | 𝑅⊥L                       Predicate sets        PSet∋𝑃 ::=·|𝑃,𝜋

                                    Fig. 8. Syntax of Qualified Types of Q◦eff


   Linearity. In addition to concrete linearities◦ and•, Q◦eff has linearity variables𝜙. This is
essentialtohaveprincipaltypesandmoreexpressiveconstraints.Forexample,theidentityfunction
𝜆𝑥.return𝑥 can be given the principal type∀𝛼𝜇𝜙.𝛼→𝜙𝛼 !{𝜇}, which can be instantiated to
either alinear function(by instantiating𝜙 to◦)or anunlimitedfunction (byinstantiating𝜙 to•).
   Qualified types. The syntactic category𝜏 includes value types, row types, and linearity types.
Qualified types𝜌 restrict value types by predicates. The linearity predicate𝜏1⪯𝜏2 means the
linearity of𝜏1 is less than𝜏2 (e.g.,•⪯◦). Note that we allow directly using value types and row
typesinthelinearitypredicates,sinceeveryvaluetypehasitsvaluelinearity,andeveryeffectrow
type has its control-flow linearity. The row predicates𝑅1⩽𝑅2 means𝑅1 is a sub-row of𝑅2, and
𝑅⊥L means𝑅 does not contain labels inL.
   Kinding. Forconcisenessweomitkindsandinferthekindofatypevariablefromitsname.As
usual, we let𝛼 range over value types,𝜇 range over row types, and𝜙 range over linearity types.
Wealsolet𝛼 rangeoveralloftheminthedefinitionoftypeschemes∀𝛼.𝜎.Allrowsareassumed
to be well-formed (no duplicated labels). To simplify type inference, the predicate𝜇⊥L will be
used in place of kinds RowL to track labels that may not occur in rows. This is just a convenience,
though,asthecorrespondingkindsofrowtypevariablescanbecomputedfromtheinferredtypes.

5.2   Typing
Figure9gives representative syntax-directed typing rules for    Q◦eff; the remaining rules are given
in full in AppendixB.2. The judgement      𝑃| Γ⊢𝑀 :𝐶 states that, under predicate assumptions𝑃
andtypingassumptionsΓ,theterm𝑀 hastype𝐶,andsimilarlyforthejudgementsforvaluesand
handlers.Asusualforqualifiedtypesystems,thetypingrulesdependonanentailmentrelation
𝑃⊢𝜋 (and an auxiliary relation𝑃⊢Γ⪯𝜏), discussed in the following section.
   Rule Q-Let demonstratesthe treatmentof linearityin Q◦eff.Wedivide thecontext inthree:Γ1 is
used exclusive in the bound term𝑉,Γ2 is used exclusively in the body𝑀, andΓ is used in both
(and so its types must be unlimited).
   Rule Q-Do demonstratesthe useof constraintsin Q◦eff togeneralise subtypingbetween effect
rows.Itstatesthatif𝑉 isavalueoftype𝐴ℓ,thendoℓ𝑉 hasresulttype𝐵ℓ andeffectrow𝑅.We
assume thatthe parameter and resulttypes of operationsare given by animplicit global context


                               Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 18

54:18                                                                            Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

 𝑃| Γ⊢𝑉 :𝐴        𝑃| Γ⊢𝑀 :𝐶        𝑃| Γ⊢𝐻 :𝐶⇒𝐷

               Q-Let                                                         Q-Do
               𝑄| Γ1,Γ⊢𝑉 :𝐴   𝜎 = gen((Γ1,Γ),𝑄⇒𝐴)                                  𝑃| Γ⊢𝑉 :𝐴ℓ
                    𝑃| Γ2,Γ,𝑥 :𝜎⊢𝑀 :𝐶   𝑃⊢Γ⪯•                                 𝑃⊢{ℓ :𝐴ℓ↠𝑌𝐵ℓ}⩽𝑅
                       𝑃| Γ1,Γ2,Γ⊢ let𝑥 =𝑉 in𝑀 :𝐶                            𝑃| Γ⊢ doℓ𝑉 :𝐵ℓ !{𝑅}

                                                       Q-Handler
  Q-Seq                                                   𝐻 ={return𝑥↦→𝑀}⊎{ℓ𝑖𝑝𝑖𝑟𝑖↦→𝑁𝑖}𝑖
            𝑃| Γ1,Γ⊢𝑀 :𝐴!{𝑅1}                          𝐶 =𝐴!{(ℓ𝑖 :𝐴𝑖↠𝑌𝑖𝐵𝑖)𝑖;𝑅1}   𝐷 =𝐵!{𝑅2}
         𝑃| Γ2,Γ,𝑥 :𝐴⊢𝑁 :𝐵!{𝑅2}                                        𝑃| Γ,𝑥 :𝐴⊢𝑀 :𝐷
          𝑃⊢𝑅1⩽𝑅   𝑃⊢𝑅2⩽𝑅                                   [𝑃| Γ,𝑝𝑖 :𝐴𝑖,𝑟𝑖 :𝐵𝑖→𝑌𝑖𝐷⊢𝑁𝑖 :𝐷]𝑖
          𝑃⊢Γ2⪯𝑅1   𝑃⊢Γ⪯•                                𝑃⊢Γ⪯•   𝑃⊢𝑅1⩽𝑅2   𝑃⊢𝑅1⊥{ℓ𝑖}𝑖
  𝑃| Γ1,Γ2,Γ⊢ let𝑥←𝑀 in𝑁 :𝐵!{𝑅}                                        𝑃| Γ⊢𝐻 :𝐶⇒𝐷

                                  wheregen(Γ,𝜌)=∀(ftv(𝜌)\ftv(Γ)).𝜌.

                            Fig. 9. Selected Syntax-directed Typing Rules for Q◦eff


Π={ℓ1 :𝐴ℓ1↠𝐵ℓ1,···}.𝑅 must license effectℓ. We again rely on entailment: the constraints𝑃
must be sufficient to show that the singleton row{ℓ :𝐴ℓ↠𝑌𝐵ℓ} is contained within𝑅.
  Rule Q-Seq demonstrates the remaining novelty of qualified types in Q◦eff. Several of its uses
of entailment follow the previous patterns. The bindings inΓ are available in both𝑀 and𝑁, so
𝑃⊢ Γ⪯• requires that their types be unlimited. We want flexibility in combining the effects in
𝑀 and𝑁, so the conditions𝑃⊢𝑅𝑖⩽𝑅 assure that the effects of each are included in the effects
of the entire computation. This allows us to avoid having to unify row types in examples like
sandwichClose (Section2.4) whichcauses inaccuracy for tracking control-flow linearity.Finally,
𝑁 is in the continuation of all operations in𝑀, so the value linearity of types inΓ2 must be less
than the control-flow linearity of operations in𝑅1. Note that the two kinding judgements in T-Seq
in Figure4are now combined into one entailment judgement 𝑃⊢ Γ2⪯𝑅1. The duality we have
identifiedbetweenvaluelinearityandcontrol-flowlinearityisreflectedbythefactthatvaluetypes
appear on the left of⪯ and effect row types appear on the right.
  RuleQ-Handlerusesthelackingpredicate𝑃⊢𝑅1⊥{ℓ𝑖}𝑖 toensurethatthehandledoperations
are not in the remaining part of the input effect row𝑅1, and requires𝑅1 to be a sub-row of the
output effect row𝑅2. This is used to allow the handled operationsℓ𝑖 to appear in𝑅2.

5.3   Entailment
Figure10defines the entailment relations between predicates  𝑃⊢𝑄. It also defines an auxiliary
entailment relation𝑃⊢ Γ⪯𝜏 which compares the linearity of all variables in Γ and𝜏. The
algorithmic version of these relations will be given in Section5.5.
  These two entailment relations are both defined as the conjunction of sub-relations as indicated
by P-PredSet and P-Context. For𝑃⊢𝑄, we only need to use entailment relations of the form
𝑃⊢𝜋. The P-Subsume is standard. The linearity predicate⪯ is reflexive (P-Refl), with◦ as top
(P-Lin)and•asbottom(P-Unl)elements.Thetwo-wayrulesP-FunandP-Rowdefinethelinearity
offunctionsandrows.Wemakeuseofthefactthatinthelinearity predicatesgeneratedbytyping
rules,functions onlyappear onthe left,androws onlyappearon theright. Herewedo notinclude


Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 19

Soundly Handling Linearity                                                                                                                                               54:19

 𝑃⊢𝜋     𝑃⊢𝑄       𝑃⊢𝜎⪯𝜏        𝑃⊢Γ⪯𝜏

    P-Subsume                                                                        P-Fun
    𝜋∈𝑃                  P-Refl              P-Lin               P-Unl                    𝑃⊢𝑌⪯𝜏
                                                                                     ============================
     𝑃⊢𝜋                 𝑃⊢𝜏⪯𝜏               𝑃⊢𝜏⪯◦               𝑃⊢•⪯𝜏               𝑃⊢(𝐴→𝑌𝐶)⪯𝜏

   P-Row
   [𝑃⊢𝜏⪯𝑌](𝑙:𝐴↠𝑌𝐵)∈𝑅                 P-Sub                        P-Lack                      P-PredSet
   ==================================𝑃⊢𝜏⪯𝜇 when𝜇∈𝑅set(𝑅1)⊆ set(𝑅2) dom(𝑅)∩L=∅                  [𝑃⊢𝜋]𝜋∈𝑄
          𝑃⊢𝜏⪯𝑅                          𝑃⊢𝑅1⩽𝑅2                       𝑃⊢𝑅⊥L                      𝑃⊢𝑄

       P-Quantifier                              P-Qualifier                       P-Context
       𝑃⊢[𝜏′/𝛼]𝜎⪯𝜏 for some𝜏′                     𝑃⊢𝜋   𝑃⊢𝜌⪯𝜏                      [𝑃⊢𝜎⪯𝜏](𝑥:𝜎)∈Γ
              𝑃⊢(∀𝛼.𝜎)⪯𝜏                           𝑃⊢(𝜋⇒𝜌)⪯𝜏                            𝑃⊢Γ⪯𝜏


                Fig. 10. Entailment Relations for Predicates and other Judgement Relations


entailment rules for base types, but in practice we would have axioms like𝑃⊢ Int⪯• and
𝑃⊢◦⪯ File.Forrowpredicates,wewriteset(𝑅) forthesetofallelements(comprisingoperation
labelswith theirsignatures androw variables)of𝑅,anddom(𝑅) forthe setof alllabelsof𝑅.We
define the row predicates directly by set operations (P-Suband P-Lack).
  The entailment relation𝑃⊢ Γ⪯𝜏 is defined using𝑃⊢𝜎⪯𝜏 which compares the linearity
of a type scheme𝜎 and a type𝜏. Our treatment of the linearity of type schemes is novel, and
addresses asoundness bugin Quill. Therule P-Quantifier which characterisesthe linearityof
polymorphic types may be surprising. It states that the linearity of a polymorphic type∀𝛼.𝜎 is
less than𝜏 if there exists an instantiation of it whose linearity is less than𝜏. This is because the
linearityofapolymorphictypeshouldcapturethelinearityofvaluesthatinhabitthattype.Avalue
ofa polymorphictypecan beunderstood astheintersection ofvaluesofall possibleinstantiations
of the type. If one of these instantiation gives a type that is less linear than𝜏, then the value
itselfmustbelesslinearthan𝜏 nomatterwhatotherinstantiationsare.Forexample,considerthe
identityfunctionid=𝜆𝑥.return𝑥 whichisobviouslyunlimited.Wegiveidapolymorphictype
∀𝜙𝛼𝜇.𝛼→𝜙𝛼 !{𝜇} tomake itpossible touseit asbotha linearfunction (byinstantiating𝜙 to◦)
andanunlimitedfunction(byinstantiating𝜙 to•).Thus,wehaveexpressiveprincipaltypesforid
without adding subtyping between linearity types to the type system.
  TheruleP-Qualifiermayalsobesurprising.Tocomparethelinearityofaqualifiedtype𝜋⇒𝜌
with𝜏, we require the predicate𝜋 to hold and then compare the linearity of the remaining part𝜌
with𝜏.Atfirstglance,thecondition𝑃⊢𝜋 mayseemunnecessary:if𝜋 mustholdininstantiations
of thistype, surelywe canassume it incheckingthe type’slinearity. However, particularlyin local
definitions,predicatesmaymentiontypevariablesnot quantifiedinthoseschemes.Wedonotwant
to assume anything about the instantiation of those variables. Consider the following function.
                                     𝜆𝑥.let𝑓 =𝜆().𝑥 in return(𝑓,𝑓)

The polymorphic function𝑓 can be given the principal type𝜎 =∀𝜙𝜇.(𝛼⪯𝜙)⇒()→𝜙𝛼 !{𝜇}
where𝛼 is the type of𝑥. Note that the constraint mentions𝛼, which is bound outside this type
scheme. Then,since𝑓 is duplicatedin return(𝑓,𝑓), thetyping of itcollects the constraint𝜎⪯•.
Obviously, wewant to know from𝜎⪯• that𝛼 should be unlimited since𝑥 is alsoduplicated.One


                              Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 20

54:20                                                                            Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

possible derivation of𝑃⊢𝜎⪯•is shown as follows.
                                           𝑃⊢𝜙′⪯•
                 𝑃⊢𝛼⪯𝜙′             𝑃⊢()→𝜙′𝛼 !{𝜇′}⪯• P-Function
                    𝑃⊢(𝛼⪯𝜙′)⇒()→𝜙′𝛼 !{𝜇′}⪯•     P-Qualifier
                        𝑃⊢(∀𝜙𝜇.(𝛼⪯𝜙)⇒()→𝜙𝛼 !{𝜇})⪯•             P-Quantifier
In P-Quantifier we instantiate𝜙 and𝜇 with variables𝜙′ and𝜇′. In order to prove𝜎⪯• from
𝑃, we must then prove𝛼⪯𝜙′ and𝜙′⪯•. Note that𝜙′ and𝜇′ are not fresh, but should instead
appear in𝑃, e.g., we might have𝑃 ={𝛼⪯𝜙′,𝜙′⪯•}. If we instead assumed𝛼⪯𝜙, or removed
thecondition entirelyfromP-Qualifier,then𝑃 wouldnot needto restrict𝛼 atall. Wecould later
instantiate𝛼 with a linear type, say File, and use this term to unsoundly copy file handles.
  Readers may worry that the P-Qualifier rule is as general as it could be, because it always
requires𝑃⊢𝜋.For example,consider let𝑓 =𝑉 in𝑀 where𝑓 :𝜎 does notappear freelyin𝑀.We
collecttheconstraint𝜎⪯•.Constraintsof𝑉 thatarecapturedin𝜎 donotnecessarilyneedtobe
satisfied, because𝑓 is not used. However, we believe that binding unsatisfiable values has little
benefits and can hide potential bugs in practice.
  Notethattheseentailmentrulesareintentionallymadeassimpleaspossible.Forexample,we
donot includeanytransitivity rules.Theentailment rulesalsodonot checkpotentially conflicted
predicatesinpredicatesetssincetheruleP-Subsumeallowscollectinganypredicates.Wesaythat
predicateset𝑃 issatisfiableifthereexistsasubstitution𝜃 suchthat·⊢𝜃𝑃,anddefinethesolutions
of it as J𝑃K𝑠𝑎𝑡 ={𝜃|·⊢𝜃𝑃}. Transitivity of⪯ is admissible when considering the solutions of
predicates,e.g., J𝜙1⪯𝜙2,𝜙2⪯•K𝑠𝑎𝑡 = J𝜙1⪯𝜙2,𝜙2⪯•,𝜙1⪯•K𝑠𝑎𝑡 ={[•/𝜙1,•/𝜙2]}.InSection5.6,
we will give an algorithm to check the satisfiability of constraint sets.

5.4   Type Inference
Figure11shows representative type inference rules for      Q◦eff; the remainder are given in full in
AppendixB.3.OurtypeinferencealgorithmisbasedonAlgorithm W[DamasandMilner1982]
extended for qualified types [Jones1994]. In               Γ⊢𝑉  :𝐴⊣𝜃,𝑃,Σ, the input includes the current
contextΓ and value𝑉, and the output includes the inferred type𝐴, substitution𝜃, predicate set𝑃,
and variable setΣ of used term variables. Note that the predicates𝑃 are an output of inference,
notaninput;ratherthancheckingentailment,asthesyntax-directedtyperulesdo,wewillemit
a constraint set sufficient to guarantee typing. In the next section, we discuss our algorithm to
guarantee thatinferred constraintsets arenot unsatisfiable.As usual, thesubstitution𝜃 has been
already applied to𝐴 and𝑃.
  RuleQ-LetWdemonstratesthetreatmentoflinearity.WewriteΓ|Σ forthetypecontextgenerated
by restrictingΓ to variables inΣ. We begin by inferring types for𝑉 and𝑀. Variable setsΣ1 andΣ2
capturethose variablesused ineach;any variableinΣ1∪Σ2 mustbe unlimited.Wealsoaccount
forthepossibilitythatthevariable𝑥 maynotbeusedin𝑀—thatistosay,thatitmayappearinΣc2,
thecomplementoftheusedvariablesΣ2.Wegeneratethecorrespondingunlimitednessconstraints
using the auxiliary function factorise, discussed next. Rule Q-DoW emits the constraint that the
singleton effect row be included in the output row. Rule Q-SeqW combines these techniques.
  We prove soundness and completeness of type inference with respect to the syntax-directed
typesystem.Wewrite𝜃|Γ forthesubstitutiongeneratedbyrestrictingthedomainof𝜃 tothefree
variables inΓ and(𝜃 =𝜃′)|Γ for𝜃|Γ =𝜃′|Γ.
  Theorem 5.1 (Soundness).  IfΓ⊢𝑉  :𝐴⊣𝜃,𝑃,Σ, then𝑃|𝜃Γ|Σ⊢𝑉  :𝐴. The same applies to
computation and handler typing.



Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 21

Soundly Handling Linearity                                                                                                                                               54:21

 Γ⊢𝑉 :𝐴⊣𝜃,𝑃,Σ          Γ⊢𝑀 :𝐶⊣𝜃,𝑃,Σ            Γ⊢𝐻 :𝐶⇒𝐷⊣𝜃,𝑃,Σ

Q-LetW
 Γ⊢𝑉 :𝐴⊣𝜃1,𝑃1,Σ1   𝜎 = gen(𝜃1Γ,𝑃1⇒𝐴)                           Q-DoW
            𝜃1Γ,𝑥 :𝜎⊢𝑀 :𝐶⊣𝜃2,𝑃2,Σ2                                 Γ⊢𝑉 :𝐴⊣𝜃1,𝑃,Σ   𝐴∼𝐴ℓ :𝜃2
      𝑄 = un(𝜃2𝜃1Γ|Σ1∩Σ2)∪un(𝜃2(𝑥 :𝜎)|Σc2)                     𝜇,𝜙 fresh   𝑄 = sub((ℓ :𝐴ℓ↠𝜙𝐵ℓ),𝜇)
Γ⊢ let𝑥 =𝑉 in𝑀 :𝐶⊣𝜃2𝜃1,𝑃2∪𝑄,Σ1∪(Σ2\𝑥)                            Γ⊢ doℓ𝑉 :𝐵ℓ !{𝜇}⊣𝜃2𝜃1,𝜃2𝑃∪𝑄,Σ

    Q-SeqW
          Γ⊢𝑀 :𝐴!{𝑅1}⊣𝜃1,𝑃1,Σ1   𝜃1Γ,𝑥 :𝐴⊢𝑁 :𝐵!{𝑅2}⊣𝜃2,𝑃2,Σ2   𝜇 fresh
    𝑄 = un(𝜃2𝜃1Γ|Σ1∩Σ2)∪un(𝜃2(𝑥 :𝐴)|Σc2)∪leq(𝜃2𝜃1Γ|Σ2,𝜃2𝑅1)∪sub(𝜃2𝑅1,𝜇)∪sub(𝑅2,𝜇)
                    Γ⊢ let𝑥←𝑀 in𝑁 :𝐵!𝜇⊣𝜃2𝜃1,𝜃2𝑃1∪𝑃2∪𝑄,Σ1∪(Σ2\𝑥)

      leq(Γ,𝜏)= factorise(Γ⪯𝜏)        un(Γ)= leq(Γ,•)        sub(𝑅1,𝑅2)= factorise(𝑅1⩽𝑅2)

                               Fig. 11. Selected Type Inference Rules for Q◦eff


  Theorem 5.2 (Completeness).  If𝑃|𝜃Γ⊢𝑉 :𝐴, thenΓ⊢𝑉 :𝐴′⊣𝜃′,𝑄,Σ and there exists𝜃′′
suchthat𝐴=𝜃′′𝐴′,𝑃⊢𝜃′′𝑄,and(𝜃 =𝜃′′𝜃′)|Γ.Thesameappliestocomputationandhandlertyping.
The proofs can be found in AppendixC.3and depend on the correctness of               factorise, discussed
next. Note that we do not need to incorporate the subtyping relation into the statement of the
completenesstheorembecauseweonlyhavesubtypingbetweenrowtypesanddonotallowimplicit
subsumption (unlike traditional subtyping systems).

5.5   Factorising Predicates


 factorise : Pred→ PSet                                  factorise :(TySch⪯ Type)→ PSet
 factorise(𝜏⪯𝜏)=∅                                        factorise((∀𝛼.𝜎)⪯𝜏)=
 factorise(𝜏⪯◦)=∅                                           factorise([𝛽/𝛼]𝜎⪯𝜏) for some fresh𝛽
 factorise(•⪯𝜏)=∅                                        factorise((𝜋⇒𝜎)⪯𝜏)=
 factorise(𝐴→𝑌𝐶⪯𝜏)= factorise(𝑌⪯𝜏)                          factorise(𝜋)∪factorise(𝜎⪯𝜏)
 factorise(𝜏⪯𝐾 ;𝜇)=                                      factorise :(Env⪯ Type)→ PSet
    factorise(𝜏⪯𝐾)∪factorise(𝜏⪯𝜇)                        factorise(Γ⪯𝜏)=Ð
 factorise(𝜏⪯𝐾)=Ð                                                                (𝑥:𝜎)∈Γfactorise(𝜎⪯𝜏)

       (ℓ:𝐴↠𝑌𝐵)∈𝐾 factorise(𝜏⪯𝑌)                         factorise : PSet→ PSet
 factorise(𝑅1⩽𝑅2)=∅, when set(𝑅1)⊆ set(𝑅2)               factorise(𝑃)=Ð𝜋∈𝑃 factorise(𝜋)
 factorise(𝑅⊥L)=∅, when dom(𝑅)∩L=∅
 factorise(𝜋)=𝜋

                                    Fig. 12. Factorisation of Constraints

ThefactorisefunctionisdefinedinFigure12;itfactorsconstraintsintosimplerpredicatesfollowing
the entailment rules in Figure10. We use  𝐾 to represent rows consisting of only operation labels.
  The only surprising case is for(∀𝛼.𝜎)⪯𝜏. Rule P-Quantifier requires that we find some
instancesuchthat𝜎[𝜏′/𝛼]⪯𝜏.Ratherthansearchforsuchaninstance,wesimplypickafreshtype


                              Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 22

54:22                                                                            Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

variable𝛽.Asaresult,ourtypeinferencealgorithmislikelytoproduceambiguoustypeschemes,in
whichquantifiedtypevariablesappear only inpredicates.Suchtypeschemesaretypicallyrejected
[Jones1994],asthemeaningofambiguouslytypedtermsisundefined.However,asourlinearity
predicates do not have any intrinsic semantics, but only constrain the use of terms, we do not
believe these constraints lead to semantic ambiguity. One interesting property of factorise is that
thelinearitypredicatesinitsresultsareonlybetweenvaluetypevariables𝛼,rowtypevariables𝜇,
and linearity types𝑌.
   We prove the correctness of factorise with respect to the entailment rules in Figure10.
   Theorem 5.3 (Correctness of factorisation).  If factorise(𝑃) =𝑄, then𝑄⊢𝑃 and𝑃⊢𝑄. If
factorise(Γ⪯𝜏)=𝑄, then𝑄⊢Γ⪯𝜏 and for any𝑃⊢Γ⪯𝜏, there exists𝜃 such that𝑃⊢𝜃𝑄.
   The proof can be found in AppendixC.1.

5.6   Constraint Solving
Finally,wemustcheckthatinferredconstraintsetsaresatisfiable;wedonotwanttoconcludethat
a program is well-typed, but only under the assumption that a linear type is unlimited.
   Wedefine a constraint solving algorithm solve(𝑃) for checking the satisfiabilityof the predicate
set𝑃, inspired by solving algorithms for general subtyping constraints [Pottier1998,2001;Pretnar
2014]. Thetricky part comparedto solving usual subtypingconstraints is thatwe need to carefully
dealwiththeinteractionbetweenrowsubtypingconstraintsandlinearityconstraints.Forinstance,
𝑅1⩽𝑅2 and𝜏⪯𝑅2 actuallyimplies𝜏⪯𝑅1.To resolvetheinteraction, thealgorithmproceeds by
first transforming row subtyping constraints to those of the forms𝜇⩽𝑅, so that we can always
simplyinstantiate𝜇 onthelefttotheemptyrow·forwhich𝜏⪯·alwaysholds.Then,thealgorithm
computes the transitive closure of linearity constraints and rejects◦⪯•. The full algorithm is
giveninAppendixB.4.Wehavethefollowingtheoremonthecorrectnessoftheconstraintsolving
algorithm, in which we write J𝑃K𝑠𝑎𝑡𝜃 for the substitution set{𝜃′𝜃|𝜃′∈ J𝑃K𝑠𝑎𝑡}.
   Theorem 5.4 (Correctness of constraint solving).  For any constraint set𝑃 generated by the
type inference of Q◦eff, solve(𝑃) always terminates.
    • If it fails, then𝑃 is not satisfiable.
    • If it returns(𝜃,𝑄), then𝑃 is satisfiable and J𝑃K𝑠𝑎𝑡 = J𝑄K𝑠𝑎𝑡𝜃.
   The proof can be found in AppendixC.4, whose main idea is to show that every step of the
algorithm preserves solutions, and the output predicate set has one solution.
   Weleavethedesignofconstraintsimplificationalgorithmsaspracticalconcerns.Someexisting
algorithms on simplifying general subtyping constraints are promising [Pottier1998,2001].

6   SHALLOW HANDLERS
Uptonowwehave concentratedon deep effecthandlers,which wraptheoriginalhandleraround
the body ofcaptured continuations.Given thisautomatic reuse ofthe handler, thehandler itself
cannot capture any linear resources. In contrast, shallow handlers [Hillerström and Lindley2018;
Kammar et al.2013] do not wrapthe original handler aroundthe body of capturedcontinuations,
whichmeansshallowhandlerscancapturelinearresourcesandthusinfluencecontrol-flowlinearity.
Inthissection,wediscusstheextensionsofF◦eff andQ◦eff withshallowhandlersandtheirchallenges.
   Let us first consider shallow handlers in F◦eff. We write𝐻† for a shallow handler. The only
difference in the operational semantics is the new E-Op† rule for handling with shallow handlers.
          E-Op†    handleE[(doℓ𝑉)𝐸] with𝐻†{𝑁[𝑉/𝑝,(𝜆𝑌𝑦𝐵.E[(return𝑦)𝐸])/𝑟],
                                    whereℓ ∉ bl(E),(ℓ𝑝𝑟↦→𝑁)∈𝐻† and(ℓ :𝐴→𝑌𝐵)∈𝐸


Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 23

Soundly Handling Linearity                                                                                                                                               54:23

UnlikeinE-Op,thebodyofthecontinuationisnothandledby𝐻†.Whereasdeephandlersperform
afoldoveracomputationtreesshallowhandlersperformacase-split.Assuch,weknowthatexactly
one operation clause or the return clause will be invoked, and providingall allowed operations are
linear each clause may capture the same linear resources. The typing rule is as follows.
                         T-ShallowHandler
                                𝐻 ={return𝑥↦→𝑀}⊎{ℓ𝑖𝑝𝑖𝑟𝑖↦→𝑁𝑖}𝑖
                         𝐶 =𝐴!{(ℓ𝑖 :𝐴𝑖↠𝑌𝑖𝐵𝑖)𝑖;𝑅}   𝐷 =𝐵!{(ℓ𝑖 :𝑃)𝑖;𝑅}
                            Δ⊢Γ :𝑌            Δ⊢𝑅 :𝑌       Δ;Γ,𝑥 :𝐴⊢𝑀 :𝐷
                                   [Δ;Γ,𝑝𝑖 :𝐴𝑖,𝑟𝑖 :𝐵𝑖→𝑌𝑖𝐶⊢𝑁𝑖 :𝐷]𝑖
                                             Δ;Γ⊢𝐻† :𝐶⇒𝐷

Instead of requiring value linearity ofΓ to be unlimited as in the deep handler rule T-Handler,
werequirethevaluelinearityofΓ tocoincidewiththecontrol-flowlinearityof𝑅,theeffectrow
oftheunhandledoperations.Thisisbecausetheshallowhandlermaybecapturedaspartofthe
continuationsoftheseunhandledoperationsinouterhandlers.Concretely,when𝑌 =◦,theshallow
handler may use linear variables from the context, and unhandled operations are control-flow
linear; when𝑌 =•, theshallow handler cannot useany linear variablesfrom the context, andwe
have no restriction on the control-flow linearity of unhandled operations.
  We can also easily extend Q◦eff with shallow handlers.

                    Q-ShallowHandler
                                𝐻 ={return𝑥↦→𝑀}⊎{ℓ𝑖𝑝𝑖𝑟𝑖↦→𝑁𝑖}𝑖
                             𝐶 =𝐴!{(ℓ𝑖 :𝐴𝑖↠𝑌𝑖𝐵𝑖)𝑖;𝑅1}   𝐷 =𝐵!{𝑅2}
                    𝑃| Γ,𝑥 :𝐴⊢𝑀 :𝐷  [𝑃| Γ,𝑝𝑖 :𝐴𝑖,𝑟𝑖 :𝐵𝑖→𝑌𝑖𝐶⊢𝑁𝑖 :𝐷]𝑖
                             𝑃⊢Γ⪯𝑅1   𝑃⊢𝑅1⩽𝑅2   𝑃⊢𝑅1⊥{ℓ𝑖}𝑖
                                             𝑃| Γ⊢𝐻 :𝐶⇒𝐷

Inplace of𝑃⊢Γ⪯• in Q-Handler,we have𝑃⊢Γ⪯𝑅1,which restrictsthevalue linearityofthe
type context to be less than the control-flow linearity of unhandled operations in𝑅1.
  Shallowhandlersaretypicallyusedtogetherwithrecursivefunctionstoimplementmoregeneral
recursive behaviours than the structural recursion of deep handlers. It is straightforward to extend
F◦eff and Q◦eff with recursive functions [Hillerström et al . 2020a;Mazurak et al          . 2010]. Obviously
recursive functions are themselves unlimited so cannot capture linear resources, but that does
not preclude explicitly threading a linear resource through a recursive function that installs a
shallowhandler.Weusethesyntax rec𝑓𝑥.𝑀 todefinea recursivefunction𝑓 withparameter𝑥
and function body𝑀. The typing rules and semantics rule for it in F◦eff and Q◦eff are as follows.

T-Rec                                                     Q-Rec
 Δ;Γ,𝑓 :𝐴→•𝐶,𝑥 :𝐴⊢𝑀 :𝐶      Δ⊢Γ :•                         Δ;Γ,𝑓 :𝐴→•𝐶,𝑥 :𝐴⊢𝑀 :𝐶   𝑃⊢Γ⪯•
        Δ;Γ⊢ rec𝑓𝐴→•𝐶𝑥.𝑀 :𝐴→•𝐶                                      𝑃| Γ⊢ rec𝑓𝑥.𝑀 :𝐴→•𝐶

                           E-Rec (rec𝑓𝑥.𝑀)𝑉{𝑀[(rec𝑓𝑥.𝑀)/𝑓,𝑉/𝑥]
  Asanexample,wecanwritethefollowingrecursivefunctionwithFile𝑓 whichtakesafilehandle
𝑓 and interprets all Print operations in𝑀 as writing to file𝑓.
                   withFile𝑓 = rec withFile𝑓.handle𝑀 with
                                 {return𝑥↦→ Close𝑓 ;𝑥
                                   Print𝑠𝑟↦→ let𝑓′← write(𝑠,𝑓) in withFile𝑓′𝑟}


                              Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 24

54:24                                                                            Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

Notethatthis examplecanalsobeimplemented withadeephandlerby requiringthehandlerto
returnafunctionwhichtakesthefilehandleasaparameter.Shallowhandlersprovideuswitha
more direct programming style.
  Althoughourtwonewtypingrules are straightforwardandentirelybackwardcompatiblewith
thecurrentsystems,shallowhandlerscanactually introducemorechallengestotrackcontrol-flow
linearity.Thisisessentiallybecauseshallowhandlersaremoreflexiblethandeephandlersanddo
not handle all invocations of the same operation uniformly. With only deep handlers, it is natural
forall invocationsofanoperation tohavethe samecontrol-flowlinearityas theyarehandled by
thesame handler.However, withshallowhandlers, differentinvocationsof thesame operationcan
behandledbydifferenthandlers,resultingindifferentcontrol-flowlinearity.Forexample,consider
the following program hesitantClose which makes choices before and after closing the file𝑓.

                        hesitantClose=𝜆𝑓.do Choose();close𝑓;do Choose()

ThecontinuationofthefirstChoosecontainsthelinearfilehandle𝑓,whereasthesecondonedoes
not. Technically, the handler for the second Choose can resume any number of times. However,
neither the effect system of F◦eff nor that of Q◦eff is able to ascribe a different control-flow linearity
to the two invocations of Choose, which means we must handle both invocations linearly. One
potential solution is to track the order and duplication of effects in the effect system. However,
thiskindofinformationisknowntobetoocumbersomeforeffectsystems.Amorelightweight
solution is to exploit named handlers [Biernacki et al . 2020;Xie et al        . 2022] to assign        Choose
operationsindifferentpositionstodifferentshallowhandlers.Weleavethedesignofanergonomic
andexpressiveeffectsystemfortrackingcontrol-flowlinearityofshallowhandlerstofuturework.

7   RELATED WORK
  LinearResourcesandControlEffects. Exceptionhandlerswithfinallyclausesareacommonwayof
managinglinearresources.Exceptionhandlersprovideaformofunwindprotection,whichenables
theprogrammertosupplythelogictoreleaseacquiredresourcesinthefinallyclause,whichgets
executed irrespective of whether a fault occurs. Similarly, the defer statement in Go [Donovan
andKernighan2015]deferstheexecutionofitsoperanduntilthedefiningfunctionreturnseither
successfullyorviaafault.Thustheprogrammercanconvenientlyacquireaparticularresourceand
includethedeferredlogicforreleasingitonthenextlineofcode.Anothervariationisautomatic
resource block management asin the C++ RAII idiom [Combette and Munch-Maccagnoni2018]
and Java’s try-with-resource [Goslinget al . 2023],both ofwhichoffer ameansforautomatically
acquiringandreleasingresourcesinthestaticscope.InSchemethefundamentalresourceprotection
mechanismistheprocedure dynamic-wind[FriedmanandHaynes1985].Itisageneralisationof
unwind protection intended to be used in the presence of first-class control, where control may
enter and leave the same computation multiple times. It takes three functional arguments: the
first is the resource acquisition procedure, whichgets applied when control enters dynamic-wind;
the second is themain computation, whichmay usethe acquired resources; andthe third isthe
resource release procedure, which is applied when control is about to leave dynamic-wind.
  BrachthäuserandLeijen[2023]presentaconstraintsystembasedonqualifiedtypesforprogram-
mingwith multi-shoteffect handlersand linearresources inKoka.They usethese constraintsto
mark some effects as linear. However, they do not include a linear type system and instead rely on
pre-declaringthelinearityofoperations(i.e.,noinferenceforcontrol-flowlinearity)andasyntactic
checktoensurethatresumptionsarenotinvokedmorethanonce.Comparedtothequalifiedeffect
system of Q◦eff, their system does not support effect subtyping and abstraction over linearity.


Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 25

Soundly Handling Linearity                                                                                                                                               54:25

   Structural Types and Control Effects. TovandPucella[2011b]proposeacalculus         𝜆URAL(𝒞) which
extends the substructural𝜆-calculus𝜆URAL [Ahmed et al . 2005] with abstract control effects     𝒞
given by a set of effects, a pure effect, and an effect-sequencing operator. They show how to
instantiate𝜆URAL(𝒞) withconcretecontroleffectsincludingexceptionsandshift/reset[Danvyand
Filinski1990] separately. Similar to                     F◦eff and Q◦eff, the𝜆URAL(𝒞) calculus also uses type-and-effect
systemto checkthat controleffectsdo notviolate thesubstructuralusage guaranteesfor values.It
includesajudgementoneffecttypestodeterminewhethercontroleffectsmaydiscardorduplicate
theircontinuations, whichroughlycorresponds toournotion ofcontrol-flow linearity.Themain
differencebetweenourworkand𝜆URAL(𝒞) isthatweconsiderthetrackingofcontrol-flowlinearity
in the presence of algebraic effects and effect handlers, whicharemoreinvolvedthan exceptions
and shift/reset both statically and dynamically. While it is theoretically possible to instantiate
𝜆URAL(𝒞) to effect handlers, this task is itself highly non-trivial due to the richer effect systems of
effect handlers. Conversely, we can also easily encode exceptions and shift/reset as user-defined
effects in F◦eff and Q◦eff using effect handlers [Forster et al.2019;Piróg et al.2019].

   LinearTypeSystems. Typeinferencewithlineartypesisawell-studiedarea.Mazuraketal    .[2010]
propose using kinds to track linearity, using subkinding to enable polymorphism over linearities.
Tov and Pucella[2011a] develop an expanded approach to tracking structural restrictions in kinds;
among other differences they introduce subtyping for function types and require fewer explicit
linearityannotationsthanMazuraketal       ..Ganetal.[2014]usequalifiedtypestocharacterisetypes
that admit structural rules in a substructural type system: for example, in a linear type system,
unlimited types are exactly types𝜏 that support operations dup :𝜏→(𝜏,𝜏) and drop :𝜏→().
Morris[2016] extends the approach ofTov and Pucellato generalise the treatment of function
types, introducing the linearity ordering constraint𝜏⪯𝜐; he also generalises their description of
unlimited types to type schemes, but does so unsoundly. In contrast, the current work does not
interpret unlimited types via operations like dup and drop; we also avoid Morris’s unsoundness in
the treatment of type schemes. An alternative approach tracks linearity exclusively in function
types,ratherthaninkinds.ThisapproachisdevelopedbyGhicaandSmith[2014],McBride[2016],
andAtkey[2018],andhasbeenimplementedinIdris[Brady2021]andanextensiontotheGHC
Haskell compiler [Bernardy et al.2018].

   Row-based Effect Types. Row types and row polymorphism are a popular way of implementing
effectsystemsinprogramminglanguages.Links[HillerströmandLindley2016]adoptsRémystyle
rowpolymorphism[Rémy1994],wheretherowtypesareabletorepresenttheabsenceoflabels
and each label is restricted to appear at most once. Koka [Leijen2017] and                    Frank [Lindley et al .
2017]userowpolymorphism basedonscopedlabels[Leijen2005] whichallowsduplicatedlabels.
We believe the idea of tracking control-flow linearity in F◦eff should work well with all kinds of
different row-based effect systems.

   Subtyping-basedEffectTypes. Someversionsof Eff[BauerandPretnar2014;Pretnar2014]usean
effectsystembasedonsubtyping.Karachaliasetal                .[2020]describeanexplicittargetcalculus         ExEff
withasubtyping-basedeffectsystemandatypeinferencealgorithmthatelaboratesEffsourcecode
intoit. Effusesa row-likerepresentationofeffecttypesand definesa subtypingrelationfor effect
types similar to the that of Q◦eff. One difference is that Eff incorporates full subtyping relations
between all types and implicit subsumption, whereas we only introduce subtyping between row
types and allow explicit subsumption in necessary positions (like Q-Seq and Q-Handle). In this
respect our qualified effect system is more lightweight. Algebraic subtyping [Dolan2016;Dolan
andMycroft2017]combinessubtypingandparametricpolymorphismwithelegantprincipaltypes.


                                Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 26

54:26                                                                            Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

Itwould beinteresting toexplore thepossibility ofcombining lineartypesand effecttypes based
on algebraic subtyping with control-flow linearity.

   One-shotcontroloperators. One-shotcontinuationswerefirstintroducedbyFriedmanandHaynes
[1985]intheformofalinearvariantofcall/cc.Similarly,Filinski[1992]considersaone-shotvariant
of theC operator [Felleisen et al.1987].

   One-shot Effect Handlers. OCaml5[Sivaramakrishnanetal .2021],the        C++-effectslibrary[Ghica
etal.2022],andthetypedcontinuationsproposalforaddingeffecthandlersto        WebAssembly[Hiller-
ström et al. 2022;Phipps-Costin et al        . 2023] all implement dynamically-checked one-shot effect
handlers. Continuations captured by such effect handlers can be thought of as linear resources
themselves,andthusplaynicelywithotherlinearresources.Anyattempttoinvokeacontinuation
more than once throws a runtime error. In contrast, our type systems can be used to statically
ensure that handlers are one-shot. In fact, its considerably easier to build a system that ensures
that all handlers areuniformly one-shotthan asystem likeours thatsupports both one-shotand
multi-shot handlers, as in the former case there is no need to track the use of linear resources
specially.Anotheradvantageofone-shotcontinuationsisthattheyadmitefficientimplementations
whicharecompatiblewithlinearresources,asaone-shotcontinuationneednotcopyitsunderlying
stack[Bruggemanetal .1996].Hillerströmetal         .[2023]presentasubstructuraltypesystemfora
calculus witheffect handlersbased ondual intuitionisticlinear logic [Barber1996] whichrestricts
all effect handlersto be one-shot (actuallyone- or zero-shot). They use itto show an asymptotic
performance gap between one-shot and multi-shot effect handlers, but are not concerned with
linear resources other than continuations.

   Multi-shot Effect Handlers. Eff [Bauer and Pretnar2015],                  Effekt [Brachthäuser et al . 2020],
Koka[Leijen2017],and                   Helium[Biernackietal .2019]areresearchprogramminglanguages with
multi-shot handlers. In contrast to one-shot handlers, multi-shot handlers can invoke the captured
continuationsanarbitrarynumberoftimes.Thisenables arangeofinterestingapplications.For
instance,asymptoticefficientbacktrackingsearch[Hillerströmetal .2020b],nondeterminism[Kam-
mar et al. 2013], and UNIX fork-style concurrency [Hillerström2022] can all be given a direct
semantics in terms of multi-shot handlers.However, one obstacle is thatthe aforementioned lan-
guagescannotstaticallyoptimiseusesofone-shotcontinuations,astheymustconservativelyexpect
theambientcontexttohavenonlinearcontrolflow,thusrequiringthemtocopythecontinuationa
priori [Hillerström2016;Hillerström et al                            . 2016]. Our type systems can enable static optimisation
of one-shot continuations through static identification of linear and nonlinear contexts.

8   CONCLUSION AND FUTURE WORK
Wehaveexploredtheinterplaybetweeneffecthandlersandlineartypes.Wehavedemonstrated
that in order to soundly combine potentially non-linear effect handlers with linear types, it is
necessarytoaddamechanismfortrackingcontrol-flowlinearitytoo.Weincorporatedcontrol-flow
linearityintotwoquitedifferentcorelanguagesaswellasrealisingcontrol-flowlinearityinLinks.
   Directions for future work include: implementing a programming language based on Q◦eff;
developing more precise type systems for combining control-flow linearity with shallow handlers;
combiningcontrol-flowlinearitywithotherformsofeffecttypesystems,suchasthosethatsupport
generativeeffects,duplicateeffects,capabilities,andmodaleffecttypes;adaptingtheconstraints
of Q◦eff to algebraicsubtyping [Dolanand Mycroft2017]; and adaptingcontrol-flow linearityfor
uniqueness types and for quantitive type theory [Atkey2018;McBride2016].


Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 27

Soundly Handling Linearity                                                                                                                                               54:27

DATA AVAILABILITY STATEMENT
The implementation of F◦eff inLinks is available on Zenodo [Tang et al.2023].

ACKNOWLEDGMENTS
This work was supported by the UKRI Future Leaders Fellowship “Effect Handler Oriented Pro-
gramming” (reference number MR/T043830/1).

REFERENCES
AmalJ.Ahmed,MatthewFluet,andGregMorrisett.2005. Astep-indexedmodelofsubstructuralstate.InICFP.ACM,78–91.
   https://doi.org/10.1145/1086365.1086376
Robert Atkey. 2018. Syntax and Semantics of Quantitative Type Theory. In LICS. ACM, 56–65.https://doi.org/10.1145/
   3209108.3209189
Andrew Barber.1996. Dual Intuitionistic Linear Logic. Technical Report ECS-LFCS-96-347.Laboratory forFoundations of
   Computer Science, The University of Edinburgh, UK.
AndrejBauerandMatijaPretnar.2014. AnEffectSystemforAlgebraicEffectsandHandlers. Log.MethodsComput.Sci.10,4
   (2014).https://doi.org/10.2168/LMCS-10(4:9)2014
Andrej Bauer and Matija Pretnar. 2015.  Programming with Algebraic Effects and Handlers.  J. Log. Algebraic Methods
   Program.84, 1 (2015), 108–123.https://doi.org/10.1016/J.JLAMP.2014.02.001
Jean-Philippe Bernardy, Mathieu Boespflug, Ryan R. Newton, Simon Peyton Jones, and Arnaud Spiwack. 2018.  Linear
   Haskell:PracticalLinearityinaHigher-OrderPolymorphicLanguage. Proc.ACMProgram.Lang.2,POPL(2018),5:1–5:29.
   https://doi.org/10.1145/3158093
Dariusz Biernacki, Maciej Piróg, Piotr Polesiuk, and Filip Sieczkowski. 2019. Abstracting Algebraic Effects. Proc. ACM
   Program. Lang.3, POPL (2019), 6:1–6:28.https://doi.org/10.1145/3290319
DariuszBiernacki,MaciejPiróg,PiotrPolesiuk,andFilipSieczkowski.2020. Bindersbyday,labelsbynight:effectinstances
   via lexically scoped handlers. Proc. ACM Program. Lang.4, POPL (2020), 48:1–48:29.https://doi.org/10.1145/3371116
Jonathan Immanuel Brachthäuser and Daan Leijen. 2023.  Qualified Effect Types – Taming Control-Flow through Linear
   Effect Handlers. TechnicalReportMSR-TR-2023-42.Microsoft.https://www.microsoft.com/en-us/research/publication/
   qualified-effect-types/
Jonathan ImmanuelBrachthäuser, PhilippSchuster, andKlaus Ostermann.2020. Effectsas Capabilities:Effect Handlers and
   LightweightEffectPolymorphism. Proc. ACM Program. Lang. 4,OOPSLA(2020),126:1–126:30.https://doi.org/10.1145/
   3428194
Edwin C. Brady. 2021.  Idris 2: Quantitative Type Theory in Practice. In ECOOP (LIPIcs, Vol. 194). Schloss Dagstuhl -
   Leibniz-Zentrum für Informatik, 9:1–9:26.https://doi.org/10.4230/LIPIcs.ECOOP.2021.9
CarlBruggeman,OscarWaddell,andR.KentDybvig.1996. RepresentingControlinthePresenceofOne-ShotContinuations.
   In PLDI. ACM, 99–107.https://doi.org/10.1145/231379.231395
GuillaumeCombetteandGuillaumeMunch-Maccagnoni.2018. AResourceModalityforRAII.In LOLA 2018: Workshop on
   Syntax and Semantics of Low-Level Languages. 1–4.
Ezra Cooper, Sam Lindley, Philip Wadler, and Jeremy Yallop. 2006.  Links: Web Programming Without Tiers. In FMCO
   (Lecture Notes in Computer Science, Vol. 4709). Springer, 266–296.https://doi.org/10.1007/978-3-540-74792-5_12
Luís Damas and Robin Milner. 1982.  Principal Type-Schemes for Functional Programs. In POPL. ACM Press, 207–212.
   https://doi.org/10.1145/582153.582176
Olivier Danvy and Andrzej Filinski. 1990.  Abstracting Control. In LISP and Functional Programming. ACM, 151–160.
   https://doi.org/10.1145/91556.91622
Stephen Dolan. 2016. Algebraic Subtyping. Ph.D. Dissertation. Computer Laboratory, University of Cambridge, United
   Kingdom.
Stephen Dolan and Alan Mycroft. 2017. Polymorphism, subtyping, and type inference in MLsub. In POPL. ACM, 60–72.
   https://doi.org/10.1145/3009837.3009882
AlanA.A. DonovanandBrianW. Kernighan. 2015. The Go Programming Language (1sted.). Addison-WesleyProfessional.
MatthiasFelleisen,DanielP.Friedman,EugeneE.Kohlbecker,andBruceF.Duba.1987. ASyntacticTheoryofSequential
   Control. Theor. Comput. Sci.52 (1987), 205–237.https://doi.org/10.1016/0304-3975(87)90109-5
Andrzej Filinski. 1992. Linear Continuations. In POPL. ACM Press, 27–38.https://doi.org/10.1145/143165.143174
YannickForster,OhadKammar,SamLindley,andMatijaPretnar.2019. Ontheexpressivepowerofuser-definedeffects:
   Effect handlers, monadic reflection, delimited control.   J. Funct. Program. 29 (2019), e15.https://doi.org/10.1017/
   S0956796819000121


                                      Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 28

54:28                                                                            Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris


Daniel P. Friedman and Christopher T. Haynes. 1985.   Constraining Control. In POPL. ACM Press, 245–254.https:
   //doi.org/10.1145/318593.318654
Daniel P. Friedman, Christopher T Haynes, and Eugene Kohlbecker. 1984. Programming with Continuations. In Program
   Transformation and Programming Environments, Peter Pepper (Ed.). Springer Berlin Heidelberg, Berlin, Heidelberg,
   263–274.https://doi.org/10.1007/978-3-642-46490-4_23
Edward Gan, Jesse A. Tov, and Greg Morrisett. 2014. Type Classes for Lightweight Substructural Types. In LINEARITY
   (EPTCS, Vol. 176). 34–48.https://doi.org/10.4204/EPTCS.176.4
Dan R. Ghica, Sam Lindley, Marcos Maroñas Bravo, and Maciej Piróg. 2022. High-level effect handlers in C++. Proc. ACM
   Program. Lang.6, OOPSLA2 (2022), 1639–1667.https://doi.org/10.1145/3563445
DanR.Ghica andAlexI.Smith.2014. BoundedLinearTypesin aResourceSemiring.In ESOP (Lecture Notes in Computer
   Science, Vol. 8410). Springer, 331–350.https://doi.org/10.1007/978-3-642-54833-8_18
JamesGosling,BillJoy,GuySteele,GiladBracha,AlexBuckley,DanielSmith,andGavinBierman.2023. TheJavaLanguage
   Specification:JavaSE20Edition.https://docs.oracle.com/javase/specs/jls/se20/html/index.html. [Accessed2023-07-11].
DanielHillerström.2022. Foundations for Programming and Implementing Effect Handlers. Ph.D.Dissertation.Schoolof
   Informatics, The University of Edinburgh, UK.
DanielHillerström,DaanLeijen,SamLindley,MatijaPretnar,AndreasRossberg,andKCSivamarakrishnan.2022.WebAssem-
   blyTypedContinuationsProposal.https://github.com/wasmfx/specfx/blob/main/proposals/continuations/Explainer.md
   [Accessed 2023-11-14].
DanielHillerströmandSamLindley.2016. Liberatingeffectswithrowsandhandlers.In TyDe@ICFP.ACM,15–27.https:
   //doi.org/10.1145/2976022.2976033
DanielHillerströmandSamLindley.2018. ShallowEffectHandlers.InAPLAS (LectureNotesinComputerScience,Vol.11275).
   Springer, 415–435.https://doi.org/10.1007/978-3-030-02768-1_22
Daniel Hillerström,Sam Lindley,and Robert Atkey.2020a. Effect handlersvia generalisedcontinuations. J. Funct. Program.
   30 (2020), e5.https://doi.org/10.1017/S0956796820000040
Daniel Hillerström, Sam Lindley, and John Longley. 2020b. Effects for Efficiency: Asymptotic Speedup with First-Class
   Control. Proc. ACM Program. Lang.4, ICFP (2020), 100:1–100:29.https://doi.org/10.1145/3408982
Daniel Hillerström, Sam Lindley, and John Longley. 2023. Asymptotic Speedup with Effect Handlers. Draft.
Daniel Hillerström. 2016. Compilation of Effect Handlers and their Applications in Concurrency. Master by Researchthesis.
   School of Informatics, The University of Edinburgh, UK.
DanielHillerström,SamLindley,andKCSivaramakrishnan.2016. CompilingLinksEffectHandlerstotheOCamlBackend.
   ML Workshop.
MarkP.Jones.1994. ATheoryofQualifiedTypes. Sci.Comput.Program.22,3(1994),231–256.https://doi.org/10.1016/0167-
   6423(94)00005-0
OhadKammar,SamLindley,andNicolasOury.2013. HandlersinAction.In ICFP.ACM,145–158.https://doi.org/10.1145/
   2500365.2500590
Georgios Karachalias, Matija Pretnar, Amr Hany Saleh, Stien Vanderhallen, and Tom Schrijvers. 2020.  Explicit effect
   subtyping. J. Funct. Program.30 (2020), e15.https://doi.org/10.1017/S0956796820000131
Oleg Kiselyov and Chung-chieh Shan. 2009.  Embedded Probabilistic Programming. In DSL (Lecture Notes in Computer
   Science, Vol. 5658). Springer, 360–384.https://doi.org/10.1007/978-3-642-03034-5_17
Daan Leijen. 2005.  Extensible records with scoped labels. In Trends in Functional Programming (Trends in Functional
   Programming, Vol. 6). Intellect, 179–194.
Daan Leijen. 2008. HMF: simple type inference for first-class polymorphism.In ICFP. ACM, 283–294.https://doi.org/10.
   1145/1411204.1411245
Daan Leijen. 2017. Type directed compilation of row-typed algebraic effects. In POPL. ACM, 486–499.https://doi.org/10.
   1145/3009837.3009872
Sam Lindley and James Cheney. 2012.  Row-based effect types for database integration. In TLDI. ACM, 91–102.https:
   //doi.org/10.1145/2103786.2103798
Sam Lindley, Conor McBride, and Craig McLaughlin. 2017. Do be do be do. In POPL. ACM, 500–514.https://doi.org/10.
   1145/3009837.3009897
SamLindleyandJGarrettMorris.2017. Lightweightfunctionalsessiontypes. Behavioural Types: from Theory to Tools. River
   Publishers (2017), 265–286.
Alberto Martelliand Ugo Montanari.1982. An Efficient UnificationAlgorithm. ACM Trans. Program. Lang. Syst. 4, 2(1982),
   258–282.https://doi.org/10.1145/357162.357169
KarlMazurak, JianzhouZhao,andSteve Zdancewic.2010. LightweightLinearTypes inSystemF𝑜 (TLDI ’10).Association
   for Computing Machinery, New York, NY, USA, 77–88.https://doi.org/10.1145/1708016.1708027
Conor McBride. 2016. I Got Plenty o’ Nuttin’. In A List of Successes That Can Change the World - Essays Dedicated to Philip
   Wadler on the Occasion of His 60th Birthday (Lecture Notes in Computer Science, Vol. 9600), Sam Lindley, Conor McBride,


Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 29

Soundly Handling Linearity                                                                                                                                               54:29


   Philip W. Trinder, and Donald Sannella (Eds.). Springer, 207–233.https://doi.org/10.1007/978-3-319-30936-1_12
J.GarrettMorris.2016. Thebestofbothworlds:linearfunctionalprogrammingwithoutcompromise.InICFP.ACM,448–461.
   https://doi.org/10.1145/2951913.2951925
J. Garrett Morris and James McKinna. 2019. Abstracting extensible data types: or, rows by any other name. Proc. ACM
   Program. Lang.3, POPL (2019), 12:1–12:28.https://doi.org/10.1145/3290325
LunaPhipps-Costin,AndreasRossberg,ArjunGuha,DaanLeijen,DanielHillerström,KCSivaramakrishnan,MatijaPretnar,
   andSamLindley.2023. ContinuingWebAssemblywithEffectHandlers. Proc. ACM Program. Lang.7,OOPSLA2(2023),
   460–485.https://doi.org/10.1145/3622814
Maciej Piróg, Piotr Polesiuk, and Filip Sieczkowski. 2019. Typed Equivalence of Effect Handlers and Delimited Control. In
   FSCD (LIPIcs, Vol. 131).SchlossDagstuhl-Leibniz-ZentrumfürInformatik,30:1–30:16.https://doi.org/10.4230/LIPICS.
   FSCD.2019.30
Gordon D. Plotkin and Matija Pretnar. 2013. Handling Algebraic Effects. Log. Methods Comput. Sci. 9, 4 (2013).
François Pottier. 1998. Type inference in the presence of subtyping: from theory to practice. Ph.D. Dissertation. INRIA.
FrançoisPottier.2001. SimplifyingSubtypingConstraints:ATheory. Inf. Comput.170,2(2001),153–183.https://doi.org/10.
   1006/inco.2001.2963
RonPressler.2018. ProjectLoom:FibersandContinuationsfortheJavaVirtualMachine.https://cr.openjdk.org/~rpressler/
   loom/Loom-Proposal.html. Accessed 2023-04-14.
MatijaPretnar.2014. InferringAlgebraicEffects. Log. Methods Comput. Sci.10,3(2014).https://doi.org/10.2168/LMCS-10(3:
   21)2014
Didier Rémy. 1994. Theoretical Aspects of Object-oriented Programming. MIT Press, Cambridge, MA, USA, Chapter Type
   Inference for Records in Natural Extension of ML, 67–95.
K.C. Sivaramakrishnan,Stephen Dolan,Leo White,Tom Kelly, SadiqJaffer,andAnil Madhavapeddy.2021. Retrofitting
   effect handlers onto OCaml. In PLDI. ACM, 206–221.https://doi.org/10.1145/3453483.3454039
Wenhao Tang, Daniel Hillerström,Sam Lindley, and Garrett Morris.2023. POPL24 Artifact for Soundly Handling Linearity.
   https://doi.org/10.5281/zenodo.10120126
JesseA.TovandRiccardoPucella.2011a. Practicalaffinetypes.In POPL.ACM,447–458.https://doi.org/10.1145/1926385.
   1926436
Jesse A. Tov and Riccardo Pucella. 2011b.   A theory of substructural types and control. In OOPSLA. ACM, 625–642.
   https://doi.org/10.1145/2048066.2048115
David Walker. 2005. Substructural type systems. Advanced topics in types and programming languages (2005), 3–44.
Ningning Xie, Youyou Cong, Kazuki Ikemori, and Daan Leijen. 2022. First-Class Names for Effect Handlers. Proc. ACM
   Program. Lang.6, OOPSLA2 (2022), 30–59.https://doi.org/10.1145/3563289


A   PROOFS OF F◦eff
In this section, we prove the theorems in Section3.

A.1   Unlimited is Unlimited
   Theorem 3.4 (Unlimited is unlimited).
    1.Unlimited values are unlimited: if  Δ;Γ⊢𝑉 :𝐴 andΔ⊢𝐴 :•, thenΔ⊢Γ :•.
    2. Unlimited continuations are unlimited: ifΔ;Γ⊢E[(doℓ𝑉)𝐸] :𝐶 for𝐸 ={ℓ :𝐴↠•𝐵;𝑅}
        andℓ ∉ bl(E), then there exists Δ⊢ Γ = Γ1+ Γ2 such that Δ⊢ Γ1  :• and Δ;Γ1,𝑦  :𝐵⊢
        E[(return𝑦)𝐸] :𝐶.
    3.Deep handlers are unlimited: if  Δ;Γ⊢𝐻 :𝐶⇒𝐷, thenΔ⊢Γ :•.
   Proof.
1. Unlimited values are unlimited. By induction on the typing derivationΔ;Γ⊢𝑉 :𝐴.
Case T-Var. Trivial.
Case T-Abs.Δ⊢𝐴→𝑌𝐶 :• gives𝑌 =•, which then givesΔ⊢Γ :•.
Case T-TAbs.Δ⊢∀𝑌𝛼𝐾.𝐶 :• gives𝑌 =•, which then givesΔ⊢Γ :•.
2. Unlimited continuations are unlimited. Byℓ ∉ bl(E) and straightforward induction on typing
derivations, we have𝐶 = _!{ℓ :𝐴↠•𝐵;_}. By induction onΔ;Γ⊢E[(doℓ𝑉)𝐸] :𝐶.


                                     Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 30

54:30                                                                            Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

Case
                                    T-Do
                                          𝐸 ={ℓ :𝐴↠𝑌𝐵;𝑅}
                                     Δ;Γ⊢𝑉 :𝐴      Δ⊢𝐸 : Effect
                                        Δ;Γ⊢(doℓ𝑉)𝐸 :𝐵!𝐸
      Immediately, we haveΔ;𝑦 :𝐵⊢(return𝑦)𝐸 :𝐵!𝐸 andΔ⊢· :•.
Case
             T-Seq
                   Δ;Γ1⊢E′[(doℓ𝑉)𝐸] :𝐴′!𝐸′(1)      Δ;Γ2,𝑥 :𝐴′⊢𝑁 :𝐵′!𝐸′
             𝐸′={ℓ :𝐴↠•𝐵;𝑅′}      Δ⊢Γ2 :𝑌(2)      Δ⊢(ℓ :𝐴↠•𝐵;𝑅′) :𝑌(3)
                          Δ;Γ1+Γ2⊢ let𝑌𝑥←E′[(doℓ𝑉)𝐸] in𝑁 :𝐵′!𝐸′
      By(3), we have   𝑌 =•. Then, by(2), we have       Δ⊢ Γ2  :•. By the IH on(1), there exists
      Δ⊢Γ1=Γ11+Γ12 suchthatΔ⊢Γ11 :• andΔ;Γ11,𝑦 :𝐵⊢E′[(return𝑦)𝐸] :𝐴′!𝐸′.Applying
      T-Seq to it, we haveΔ;Γ3,𝑦 :𝐵⊢ let𝑌𝑥←E′[(return𝑦)𝐸] in𝑁 :𝐵′!𝐸′,Δ⊢ Γ = Γ12+Γ3
      andΔ⊢Γ3 :• whereΔ⊢Γ3=Γ2+Γ11.
Case
               T-Handle
               Δ;Γ1⊢E′[(doℓ𝑉)𝐸] :𝐴′!𝐸′(1)      Δ;Γ2⊢𝐻 :𝐴′!𝐸′⇒𝐵′!𝐹′(2)
                         Δ;Γ1+Γ2⊢ handleE′[(doℓ𝑉)𝐸] with𝐻 :𝐵′!𝐹′
      By(2), we have        Δ⊢ Γ2  :•. By the IH on(1), there exists       Δ⊢ Γ1 = Γ11+ Γ12 such that
      Δ⊢ Γ11 :• andΔ;Γ11,𝑦 :𝐵⊢E′[(return𝑦)𝐸] :𝐴′!𝐸′. Applying T-Handle to it, we have
      Δ;Γ3,𝑦 :𝐵⊢ handleE′[(return𝑦)𝐸] with𝐻 :𝐵′!𝐹′,Δ⊢Γ=Γ12+Γ3 andΔ⊢Γ3 :•where
      Δ⊢Γ3=Γ2+Γ11.
3. Deep handlers are unlimited. Directly follows from T-Handler.
                                                                                                          □

A.2   Progress
  Lemma A.1 (Canonical forms).
   1.If⊢𝑉 :𝐴→𝑌𝐵, then𝑉 is of shape𝜆𝑌𝑥𝐴.𝑀.
   2.If⊢𝑉 :∀𝑌𝛼𝐾.𝐶, then𝑉 is of shapeΛ𝑌𝛼𝐾.𝑀.
  Proof. Directly follows from the typing rules.                 □
  Theorem 3.2 (Progress).  If⊢𝑀 :𝐴!𝐸, then either there exists𝑁 such that𝑀{𝑁 or𝑀 is in a
normal form with respect to𝐸.
  Proof. By induction on the typing derivation⊢𝑀 :𝐴!𝐸.
Case
                                      T-App
                                      ⊢𝑉 :𝐴→𝑌𝐶  ⊢𝑊 :𝐴
                                                ⊢𝑉𝑊 :𝐶
      By LemmaA.1, we have       𝑉 =𝜆𝑌𝑥𝐴.𝑀. Reduced by E-App.
Case
                                  T-TApp
                                   Δ;Γ⊢𝑉 :∀𝑌𝛼𝐾.𝐶      Δ⊢𝑇 :𝐾

                                          Δ;Γ⊢𝑉𝑇 :𝐶[𝑇/𝛼]


Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 31

Soundly Handling Linearity                                                                                                                                               54:31

      By LemmaA.1, we have       𝑉 =Λ𝑌𝛼𝐾.𝑀. Reduced by E-TApp.
Case T-Return. In a normal form with respect to𝐸.
Case T-Do. In a normal form with respect to𝐸.
Case
                              T-Seq
                               Δ;Γ1⊢𝑀 :𝐴!𝐸      Δ;Γ2,𝑥 :𝐴⊢𝑁 :𝐵!𝐸
                                  𝐸 ={𝑅}      Δ⊢Γ2 :𝑌      Δ⊢𝑅 :𝑌
                                  Δ;Γ1+Γ2⊢ let𝑌𝑥←𝑀 in𝑁 :𝐵!𝐸
      By a case analysis on𝑀.
      Subcase𝑀 =(return𝑁)𝐸. Reduced by E-Seq.
      Subcase Otherwise. By the IH, if𝑀 {𝑁, then the original term is reduced by E-Lift.
         Otherwise,𝑀 isinanormalformwithrespectto𝐸,whichimpliestheoriginaltermisalso
         in a normal form with respect to𝐸.
Case
                  T-Handle
                  Δ;Γ1⊢𝐻 :𝐶⇒𝐷      Δ;Γ2⊢𝑀 :𝐶   𝐶 =𝐴!𝐸′   𝐷 =𝐵!𝐸
                                   Δ;Γ1+Γ2⊢ handle𝑀 with𝐻 :𝐷
      By a case analysis on𝑀.
      Subcase𝑀 =(return𝑁)𝐸′. Reduced by E-Ret.
      Subcase𝑀 =E[(doℓ𝑉)𝐸′′] withℓ ∉ bl(E) and(ℓ𝑝𝑟↦→𝑁)∈𝐻. The original term is
         reduced byE-Op.
      Subcase Otherwise. By the IH, if𝑀 {𝑁, then the original term is reduced by E-Lift.
         Otherwise,𝑀 isinanormalformwithrespectto𝐸′.ByDefinition3.1,     𝑀 =E[(doℓ𝑉)𝐸′′]
         forℓ∈𝐸′ andℓ ∉ bl(E). By the last subcase,ℓ is also not handled by𝐻. Thus, the original
         term is also in a normal form with respect to𝐸.
                                                                                                            □

A.3   Subject Reduction
  Lemma A.2 (Substitution).
   1. Preservation of kinds under type substitution: if Δ,𝛼  :𝐾′⊢𝑇  :𝐾 and Δ⊢𝑇′  :𝐾′, then
      Δ⊢𝑇[𝑇′/𝛼] :𝐾.
   2. Preservation of types under type substitution: ifΔ⊢𝑇 :𝐾, thenΔ,𝛼 :𝐾;Γ⊢𝑀 :𝐶 implies
      Δ;Γ[𝑇/𝛼]⊢𝑀[𝑇/𝛼] :𝐶[𝑇/𝛼],andΔ,𝛼 :𝐾;Γ⊢𝑉 :𝐴 impliesΔ;Γ[𝑇/𝛼]⊢𝑉[𝑇/𝛼] :𝐴[𝑇/𝛼],
      andΔ,𝛼 :𝐾;Γ⊢𝐻 :𝐶⇒𝐷 impliesΔ;Γ[𝑇/𝛼]⊢𝐻[𝑇/𝛼] :(𝐶⇒𝐷)[𝑇/𝛼].
   3. Preservation of types under value substitution: if Δ⊢ Γ1 :𝑌, Δ;Γ1⊢𝑉  :𝐴 and Δ⊢𝐴 :𝑌,
      thenΔ;Γ2,𝑥 :𝐴⊢𝑀 :𝐶 impliesΔ;Γ1+Γ2⊢𝑀[𝑉/𝑥] :𝐶, andΔ;Γ2,𝑥 :𝐴⊢𝑊  :𝐵 implies
      Δ;Γ1+Γ2⊢𝑊[𝑉/𝑥] :𝐵, andΔ;Γ2,𝑥 :𝐴⊢𝐻 :𝐶⇒𝐷 impliesΔ;Γ1+Γ2⊢𝐻[𝑉/𝑥] :𝐶⇒𝐷.
  Proof. Weapplyvariousstructurallemmaslikeweakening,permutationofcontexts,andprop-
erties of context splitting in the following proofs.
1.Preservationofkindsundertypesubstitution.Straightforwardinductiononthekindingderivations.
2.Preservationoftypesundertypesubstitution.ByLemmaA.2.1andstraightforwardmutualinduction
on the typing derivations.
3. Preservation of types under value substitution.Bymutualinductiononthe typingderivations.□
  Theorem 3.3 (Subject reduction).  IfΔ;Γ⊢𝑀 :𝐶 and𝑀{𝑁, thenΔ;Γ⊢𝑁 :𝐶.
  Proof. By induction on the typing derivationΔ;Γ⊢𝑀 :𝐶.


                              Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 32

54:32                                                                            Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

Case
                              T-App
                               Δ;Γ1⊢𝑉 :𝐴→𝑌𝐶(1)      Δ;Γ2⊢𝑊 :𝐴(2)
                                             Δ;Γ1+Γ2⊢𝑉𝑊 :𝐶

       ThereductioncanonlybederivedusingE-App,whichimplies𝑉 =𝜆𝑌𝑥𝐴.𝑁 and(𝜆𝑌𝑥𝐴.𝑁)𝑊{
       𝑁[𝑊/𝑥]. Inversion on(1)gives       Δ;Γ1,𝑥 :𝐴⊢𝑁 :𝐶(3). Case analysis on the linearity of  𝐴:
       Subcase Δ⊢𝐴  :•(4). Applying Theorem3.4.1 to(2)gives                  Δ⊢ Γ2  :•(5). Applying
         LemmaA.2.3 to(2),(3),(4)and(5)gives                                     Δ;Γ1+Γ2⊢𝑁[𝑊/𝑥] :𝐶.
       Subcase Δ⊢𝐴 :◦(4). We always have    Δ⊢ Γ2 :◦(5). Applying LemmaA.2.3 to(2),(3),(4)
         and(5)gives         Δ;Γ1+Γ2⊢𝑁[𝑊/𝑥] :𝐶.
Case
                                 T-TApp
                                 Δ;Γ⊢𝑉 :∀𝑌𝛼𝐾.𝐶(1)      Δ⊢𝑇 :𝐾(2)
                                             Δ;Γ⊢𝑉𝑇 :𝐶[𝑇/𝛼]

       The reduction can only be derived usingE-TApp, which implies𝑉 =Λ𝑌𝛼𝐾.𝑁 and
       (Λ𝑌𝛼𝐾.𝑁)𝑇 {𝑁[𝑇/𝛼]. Inversion on(1)gives       Δ,𝛼  :𝐾;Γ⊢𝑁  :𝐶(3). By  𝛼 ∉ ftv(Γ),
       applying LemmaA.2.2 to(2)and(3)gives                             Δ;Γ⊢𝑁[𝑇/𝛼] :𝐶[𝑇/𝛼].
Case T-Return. No reduction.𝑀 is in a normal form.
Case T-Do. No reduction.𝑀 is in a normal form.
Case
                         T-Seq
                         Δ;Γ1⊢𝑀 :𝐴!{𝑅}(1)      Δ;Γ2,𝑥 :𝐴⊢𝑁 :𝐵!{𝑅}(2)
                                           Δ⊢Γ2 :𝑌      Δ⊢𝑅 :𝑌
                                   Δ;Γ1+Γ2⊢ let𝑌𝑥←𝑀 in𝑁 :𝐵!{𝑅}
       By a case analysis on the next rule used by reduction:
       Subcase E-Lift.Suppose𝑀{𝑀′.TheIHon(1)gives       Δ;Γ1⊢𝑀′ :𝐴!{𝑅}.Then,by T-Seq
         we haveΔ;Γ1+Γ2⊢ let𝑌𝑥←𝑀′ in𝑁 :𝐵!{𝑅2}.
       Subcase E-Seq.𝑀 =(return𝑉){𝑅}.Inversionon(1)gives       Δ;Γ1⊢𝑉 :𝐴(3).With(2)and(3),
         our goal follows from a case analysis on the linearity of𝐴 similar to theT-App case.
Case
                               T-Handle
                               Δ;Γ1⊢𝑀 :𝐶(1)      Δ;Γ2⊢𝐻 :𝐶⇒𝐷(2)
                                    Δ;Γ1+Γ2⊢ handle𝑀 with𝐻 :𝐷
       By a case analysis on the next rule used by reduction:
       Subcase E-Lift. Suppose𝑀{𝑀′. The IHon(1)gives       Δ;Γ1⊢𝑀′ :𝐶. Then, byT-Handle
         we haveΔ;Γ1+Γ2⊢ handle𝑀′ with𝐻 :𝐷.
       Subcase E-Ret.𝑀 =(return𝑉)𝐸 and(return𝑥↦→𝑁)∈𝐻. Suppose𝐶 =𝐴!𝐸. Inversion
         on(1)gives       Δ;Γ1⊢𝑉 :𝐴(3).Inversionon(2)gives          Δ;Γ2,𝑥 :𝐴⊢𝑁 :𝐷(4).With(3)and(4),
         our goal follows from a case analysis on the linearity of𝐴 similar to theT-App case.
       Subcase E-Op.𝑀 =E[(doℓ𝑉)𝐸],ℓ ∉ bl(E)and(ℓ𝑝𝑟↦→𝑁)∈𝐻.Suppose(ℓ :𝐴→𝑌𝐵)∈
         𝐸 and𝑊 =𝜆𝑌𝑦𝐵.handleE[(return𝑦)𝐸] with𝐻.Thereductionishandle𝑀 with𝐻{
         𝑁[𝑉/𝑝,𝑊/𝑟]. Inversion on(2)gives       Δ;Γ2,𝑝 :𝐴,𝑟 :𝐵→𝑌𝐷⊢𝑁 :𝐷(3). By astraightfor-
         ward induction on(1)similar to the proofof Theorem3.4.2, it iseasy to show that there
         existsΔ⊢Γ1=Γ11+Γ12 suchthatΔ;Γ11⊢𝑉 :𝐴(4)and    Δ;Γ12,𝑦 :𝐵⊢E[(return𝑦)𝐸] :𝐶(5).


Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 33

Soundly Handling Linearity                                                                                                                                               54:33

         With(3)and(4), bya case analysison the linearityof         𝐴 similar tothe T-App case,we have
         Δ;Γ11+(Γ2,𝑟 :𝐵→𝑌𝐷)⊢𝑁[𝑉/𝑝] :𝐷(6). Then by another case analysis on 𝑌:
         subcase𝑌 =•.ByTheorem3.4.2wehave        Δ⊢Γ12 :•.ApplyingT-HandleandT-Absto(5),
           wehaveΔ;Γ12+Γ2⊢𝑊 :𝐵→𝑌𝐷(7).ApplyingTheorem3.4.3 to(2)wehave                 Δ⊢Γ2 :•.
           Finally,applyingLemmaA.2.3to(6)and(7),wehave                            Δ;Γ11+Γ12+Γ2⊢𝑁[𝑉/𝑝,𝑊/𝑟] :𝐷.
         subcase𝑌 =◦.ApplyingT-HandleandT-Absto(5),wehave      Δ;Γ12+Γ2⊢𝑊 :𝐵→𝑌𝐷(7).
           We always haveΔ⊢Γ12+Γ2 :◦. Finally, applying LemmaA.2.3 to(6)and(7), we have
           Δ;Γ11+Γ12+Γ2⊢𝑁[𝑉/𝑝,𝑊/𝑟] :𝐷.
                                                                                                            □

A.4   Linearity Safety of Evaluation
  Lemma A.3 (Linearvariables appear exactly once).  IfΔ;Γ,𝑥 :𝐴⊢𝑉 :𝐵 andΔ ⊬𝐴 :•, then
𝑥 appears exactly once in𝑉. IfΔ;Γ,𝑥 :𝐴⊢𝑀 :𝐶 andΔ ⊬𝐴 :•, then𝑥 appears exactly once in𝑀.
  Proof. Bythedefinitionofthecontextsplittingrelationandstraightforwardinductionontyping
derivations.                               □

  LemmaA.4(Preservationoflinearsafetyundersubstitution).  Given closed and linear
safe𝑉 and𝑀, if⊢𝑉 :𝐴 and·;𝑥 :𝐴⊢𝑀 :𝐶, then𝑀[𝑉′/𝑥] is linear safe where(𝑉′,_)= tag(𝑉).
  Proof. Case analysis on the linearity of𝐴.
Case⊢𝐴 :•.Wehave𝑉′=𝑉.By thelinearsafetyof𝑉,we haveℒ(𝑉)=∅.The linearsafetyof
      𝑀[𝑉′/𝑥] follows from the linear safety of𝑀.
Case ⊬𝐴 :•. By Theorem3.4,    𝑥 does not appear in unlimited values, continuations and handlers
      of𝑀. Thus,𝑉′ does not appear in unlimited values, continuations and handlers of𝑀[𝑉′/𝑥].
      The linear safety of𝑀[𝑉′/𝑥] then directly follows from the linear safety of𝑀 and𝑉.
                                                                                                            □

  Theorem 3.6 (Reduction Safety).  For any closed, well-typed and linear safe computation𝑀 in
F◦eff, if𝑀ST{𝑁, then𝑁 is linear safe andℒ(𝑀)∪S=ℒ(𝑁)∪T.
  Proof. Weproceedbyinductiononthelinearity-awarereductionrulesdefinedinFigure7.To
avoid name conflicts, we consider  ˆ𝑀ST{  ˆ𝑁.
Case
                    L-App (𝜆𝑌𝑥𝐴.𝑀)𝑉S∅{𝑀[𝑉′/𝑥], where(𝑉′,S)= tag(𝑉)
      Thelinearsafetyof  ˆ𝑀 givesthelinearsafetyof𝑀 and𝑉.Thelinearsafetyof  ˆ𝑁 followsfrom
      LemmaA.4. By inversion on                    ˆ𝑀,𝑉 has type𝐴. Case analysis on the linearity of𝐴:
      Subcase⊢𝐴 :•.Wehavelin(𝑉)= falseandtag(𝑉)={𝑉,∅}.Bythefactthat𝑉 isclosedand
         linear safe, we haveℒ(𝑉)=∅. Our goal follows fromℒ( ˆ𝑀)∪∅=ℒ(𝑀)=ℒ( ˆ𝑁)∪∅.
      Subcase ⊬𝐴  :•. We have lin(𝑉) = true. By LemmaA.3,       𝑥 appears in𝑀 exactly once.
         If𝑉 =𝑊◦ for some𝑊, then we haveℒ( ˆ𝑀)∪∅ = ℒ(𝑀)∪ℒ(𝑉) = ℒ(𝑀[𝑉/𝑥]) =
         ℒ( ˆ𝑁)∪∅.Otherwise,wehaveℒ( ˆ𝑀)∪{𝑉◦}=ℒ(𝑀)∪ℒ(𝑉)∪{𝑉◦}=ℒ(𝑀)∪ℒ(𝑉◦)=
         ℒ(𝑀[𝑉◦/𝑥])=ℒ( ˆ𝑁)∪∅.
Case
                                  L-TApp (Λ𝑌𝛼𝐾.𝑀)𝑇∅∅{𝑀[𝑇/𝛼]
      The linear safety of  ˆ𝑁 directly follows from the linear safety of  ˆ𝑀. We haveℒ( ˆ𝑀)∪∅ =
      ℒ(𝑀)=ℒ( ˆ𝑁)∪∅.


                              Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 34

54:34                                                                            Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

Case
            L-Seq   let𝑌𝑥← return𝑉 in𝑁S∅{𝑁[𝑉′/𝑥], where(𝑉′,S)= tag(𝑉)

      Thelinearsafetyof  ˆ𝑀 givesthelinearsafetyof𝑁 and𝑉.Thelinearsafetyof  ˆ𝑁 followsfrom
      LemmaA.4. Suppose    ⊢𝑉  :𝐴. Our goal follows from a case analysis on the linearity of𝐴
      similar to theL-App case.
Case
                       L-Ret   handle(return𝑉)𝐸 with𝐻S∅{𝑁[𝑉′/𝑥],
                               where(return𝑥↦→𝑁)∈𝐻,(𝑉′,S)= tag(𝑉)
      The linear safety of  ˆ𝑀 gives the linear safety of𝑉,𝐻 and𝑁. The linear safety of  ˆ𝑁 follows
      from LemmaA.4. Suppose    ⊢𝑉 :𝐴. Our goal follows from a case analysis on the linearity of
      𝐴 similar to theL-App case.
Case
                   L-Op   handleE[(doℓ𝑉)𝐸] with𝐻S∅{𝑁[𝑉′/𝑝,𝑊′/𝑟],
                          whereℓ ∉ bl(E),(ℓ𝑝𝑟↦→𝑁)∈𝐻,(ℓ :𝐴↠𝑌𝐵)∈𝐸,
                                    𝑊 =𝜆𝑌𝑦𝐵.handleE[(return𝑦)𝐸] with𝐻,
                              (𝑉′,S1)= tag(𝑉),(𝑊′,S2)= tag(𝑊),S=S1∪S2
      Thelinearsafetyof  ˆ𝑀 givesthelinearsafetyof𝑉,𝐻,𝑁 andE.Weneedtoshowthelinear
      safetyof𝑊.If𝑌 =◦,thelinearsafetyof𝑊 directlyfollowsfromthelinearsafetyofE and𝐻.
      If𝑌 =•, by the linear safety ofE[(doℓ𝑉)𝐸] we haveℒ(E)=∅. By the linear safety of𝐻
      wehaveℒ(𝐻)=∅.Thus,ℒ(𝑊)=∅,whichgivesusthelinearsafetyof𝑊.Thelinearsafety
      of  ˆ𝑁 follows from LemmaA.4. Then,we prove the equation. By inversion on    (doℓ𝑉)𝐸, we
      have⊢𝑉 :𝐴.Suppose⊢𝑊 :𝐵→𝑌𝐶.Bythelinearsafetyof𝐻,wehaveℒ(𝐻)=ℒ(𝑁)=∅.
      By a case analysis on the linearity of𝐴.
      Subcase⊢𝐴 :•. We have lin(𝑉) = false and tag(𝑉) ={𝑉,∅}. By the fact that𝑉 is closed
        and linear safe, we haveℒ(𝑉)=∅. By a case analysis on the linearity of𝐵→𝑌𝐶.
        subcase⊢𝐵→𝑌𝐶 :•. We have lin(𝑊)= false and tag(𝑊)={𝑊,∅}. By the fact that𝑊
          is closed and linear safe, we haveℒ(𝑊) =∅. Our goal follows fromℒ( ˆ𝑀)∪∅=∅=
          ℒ( ˆ𝑁)∪∅.
        subcase⊢𝐵→𝑌𝐶 :◦.Wehavelin(𝑊)= trueandtag(𝑊)={𝑊◦,{𝑊◦}}.ByLemmaA.3,
          𝑟 appearsin𝑁 exactlyonce.Wehaveℒ( ˆ𝑀)∪{𝑊◦}=ℒ(E)∪{𝑊◦}=ℒ(𝑊◦)=ℒ( ˆ𝑁).
      Subcase ⊬𝐴 :•. We have lin(𝑉) = true. By LemmaA.3,       𝑝 appears in𝑁 exactly once. If
        𝑉 =𝑉1◦ for some𝑉1, we have𝑉◦=(𝑉,∅). By a case analysis on the linearity of𝐵→𝑌𝐶.
        subcase⊢𝐵→𝑌𝐶 :•. We have lin(𝑊) = false and tag(𝑊) ={𝑊,∅}. By the fact that
          𝑊 is closed and linear safe, we haveℒ(𝑊) =∅. Our goal follows fromℒ( ˆ𝑀)∪∅ =
          ℒ(𝑉)=ℒ( ˆ𝑁)∪∅.
        subcase⊢𝐵→𝑌𝐶 :◦.Wehavelin(𝑊)= trueandtag(𝑊)={𝑊◦,{𝑊◦}}.ByLemmaA.3,
          𝑟 appears in𝑁 exactly once. We haveℒ( ˆ𝑀)∪{𝑊◦} = ℒ(𝑉)∪ℒ(E)∪{𝑊◦} =
          ℒ(𝑉)∪ℒ(𝑊◦)=ℒ( ˆ𝑁).
        Otherwise, we have𝑉◦=(𝑉◦,{𝑉◦}). By a case analysis on the linearity of𝐵→𝑌𝐶.
        subcase⊢𝐵→𝑌𝐶 :•. We have lin(𝑊) = false and tag(𝑊) ={𝑊,∅}. By the fact that
          𝑊 is closed and linear safe, we haveℒ(𝑊) =∅. Our goal follows fromℒ( ˆ𝑀)∪∅ =
          ℒ(𝑉)∪{𝑉◦}=ℒ(𝑉◦)=ℒ( ˆ𝑁)∪∅.
        subcase⊢𝐵→𝑌𝐶 :◦.Wehavelin(𝑊)= trueandtag(𝑊)={𝑊◦,{𝑊◦}}.ByLemmaA.3,
          𝑟 appearsin𝑁 exactlyonce.Wehaveℒ( ˆ𝑀)∪{𝑊◦,𝑉◦}=ℒ(𝑉)∪ℒ(E)∪{𝑊◦,𝑉◦}=
          ℒ(𝑉◦)∪ℒ(𝑊◦)=ℒ( ˆ𝑁).


Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 35

Soundly Handling Linearity                                                                                                                                               54:35

Case
                                   L-Remove F[𝑉◦]  ∅{𝑉◦}{F[𝑉]
      The linear safety of  ˆ𝑁 directly follows from the linear safety of  ˆ𝑀. We haveℒ( ˆ𝑀)∪∅ =
      ℒ(F)∪ℒ(𝑉◦)=ℒ(F)∪ℒ(𝑉)∪{𝑉◦}=ℒ( ˆ𝑁)∪{𝑉◦}.
Case
                               L-LiftE[𝑀]ST{E[𝑁], if𝑀ST{𝑁
      Thelinearsafetyof  ˆ𝑀 givesthelinear safetyofE and𝑀.ByIH,wehavethelinearsafety
      of𝑁. The linear safety of  ˆ𝑁 follows from the linear safety ofE and𝑁. By IH, we have
      ℒ(𝑀)∪S = ℒ(𝑁)∪T. Our goal follows fromℒ( ˆ𝑀)∪S = ℒ(E)∪ℒ(𝑀)∪S =
      ℒ(E)∪ℒ(𝑁)∪T =ℒ( ˆ𝑁)∪T.
                                                                                                           □

B   FULL SPECIFICATION OF Q◦eff
Inthissection,wegivethefullsyntax,typingrules,typeinference,andconstraintsolvingalgorithm
of Q◦eff in Section5.

B.1   Full Syntax
The full syntax of Q◦eff is given in Figure13. Note that we introduce the syntactic category of
concrete rows to simplify the presentation of the constraint solving algorithm.

         Value types          𝐴,𝐵 ::=𝛼|𝐴→𝑌𝐶
         Computation types     𝐶,𝐷 ::=𝐴!𝐸
         Handler types          𝐹 ::=𝐶⇒𝐷
         Effect types            𝐸 ::={𝑅}
         Concrete row types   CRow∋𝐾 ::=·|ℓ :𝐴↠𝑌𝐵;𝐾
         Row types                      Row∋𝑅 ::=𝜇|𝐾|𝐾 ;𝑅
         Linearity types          𝑌 ::=            𝜙|•|◦
         Types                𝜏 ::=𝐴|𝑅|𝑌
         Predicates                      Pred∋𝜋 ::=𝜏1⪯𝜏2|𝑅1⩽𝑅2|𝑅⊥L
         Qualified types          𝜌 ::=𝐴|𝜋⇒𝜌
         Type schemes             TySch∋𝜎 ::=𝜌|∀𝛼.𝜎
         Label sets        L ::=∅|{ℓ}⊎L
         Type contexts                 Env∋ Γ ::=·| Γ,𝑥 :𝜎
         Predicate sets                PSet∋𝑃 ::=·|𝑃,𝜋
         Values             𝑉,𝑊 ::=𝑥|𝜆𝑥.𝑀
         Computations        𝑀,𝑁 ::=𝑉𝑊| return𝑉| doℓ𝑉|                            let𝑥 =𝑉 in𝑀
                                                |  let𝑥←𝑀 in𝑁| handle𝑀 with𝐻
         Handlers             𝐻 ::={return𝑥↦→𝑀}|{ℓ𝑝𝑟↦→𝑀}⊎𝐻


                                         Fig. 13. The Syntax of Q◦eff


B.2   Full Typing Rules
The full syntax-directed typing rules for Q◦eff is given in Figure14. Note that in the qualified
effect system of Q◦eff, we only have subtyping between row types and use them in Q-Do, Q-Seq,
Q-Handle, and Q-Handler. This is different from other type systems with general subtyping,


                              Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 36

54:36                                                                            Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

where thesubtyping relation is usedeverywhere. For example,in the Q-App rule, we requirethe
argumenttypetobeequaltotheparametertypeofthefunction,insteadofrequiringasubtyping
relation.Havingafullsubtypingrelationbetweenanytypesdoesnothelpimprovetheaccuracyof
tracking control-flow linearity; subtyping between effect rows is enough.

𝑃| Γ⊢𝑉 :𝐴        𝑃| Γ⊢𝑀 :𝐶       𝑃| Γ⊢𝐻 :𝐶⇒𝐷

        Q-Var                       Q-Abs                            Q-App
             𝑃⊢Γ⪯•                    𝑃| Γ,𝑥 :𝐴⊢𝑀 :𝐶                      𝑃| Γ1,Γ⊢𝑉 :𝐴→𝑌𝐶
           (𝑃⇒𝐴)⊑𝜎                         𝑃⊢Γ⪯𝑌                     𝑃| Γ2,Γ⊢𝑊 :𝐴   𝑃⊢Γ⪯•
         𝑃| Γ,𝑥 :𝜎⊢𝑥 :𝐴             𝑃| Γ⊢𝜆𝑥.𝑀 :𝐴→𝑌𝐶                       𝑃| Γ1,Γ2,Γ⊢𝑉𝑊 :𝐶

        Q-Let
        𝑄| Γ1,Γ⊢𝑉 :𝐴   𝜎 = gen((Γ1,Γ),𝑄⇒𝐴)                          Q-Return
             𝑃| Γ2,Γ,𝑥 :𝜎⊢𝑀 :𝐶   𝑃⊢Γ⪯•                                      𝑃| Γ⊢𝑉 :𝐴
                𝑃| Γ1,Γ2,Γ⊢ let𝑥 =𝑉 in𝑀 :𝐶                          𝑃| Γ⊢ return𝑉 :𝐴!{𝑅}

    Q-Do                               Q-Seq
          𝑃| Γ⊢𝑉 :𝐴ℓ                    𝑃| Γ1,Γ⊢𝑀 :𝐴!{𝑅1}   𝑃| Γ2,Γ,𝑥 :𝐴⊢𝑁 :𝐵!{𝑅2}
     𝑃⊢{ℓ :𝐴ℓ↠𝑌𝐵ℓ}⩽𝑅                   𝑃⊢𝑅1⩽𝑅   𝑃⊢𝑅2⩽𝑅   𝑃⊢Γ2⪯𝑅1   𝑃⊢Γ⪯•
    𝑃| Γ⊢ doℓ𝑉 :𝐵ℓ !{𝑅}                          𝑃| Γ1,Γ2,Γ⊢ let𝑥←𝑀 in𝑁 :𝐵!{𝑅}

                                                   Q-Handler
                                                      𝐻 ={return𝑥↦→𝑀}⊎{ℓ𝑖𝑝𝑖𝑟𝑖↦→𝑁𝑖}𝑖
   Q-Handle                                        𝐶 =𝐴!{(ℓ𝑖 :𝐴𝑖↠𝑌𝑖𝐵𝑖)𝑖;𝑅1}   𝐷 =𝐵!{𝑅2}
        𝑃| Γ1,Γ⊢𝐻 :𝐴!{𝑅1}⇒𝐷                                       𝑃| Γ,𝑥 :𝐴⊢𝑀 :𝐷
           𝑃| Γ2,Γ⊢𝑀 :𝐴!{𝑅}                             [𝑃| Γ,𝑝𝑖 :𝐴𝑖,𝑟𝑖 :𝐵𝑖→𝑌𝑖𝐷⊢𝑁𝑖 :𝐷]𝑖
         𝑃⊢Γ⪯•   𝑃⊢𝑅⩽𝑅1                             𝑃⊢Γ⪯•   𝑃⊢𝑅1⩽𝑅2   𝑃⊢𝑅1⊥{ℓ𝑖}𝑖
   𝑃| Γ1,Γ2,Γ⊢ handle𝑀 with𝐻 :𝐷                                   𝑃| Γ⊢𝐻 :𝐶⇒𝐷


                              Fig. 14. Syntax-directed Typing Rules for Q◦eff


B.3   Type Inference Algorithm
ThefulltypeinferenceofQ◦eff isgiveninFigure15.Itusestheunificationrelations  𝜏∼𝜏′ :𝜃 which
states that𝜃 is the principal unifier of types𝜏 and𝜏′, and𝐶∼𝐶′ :𝜃 which states that𝜃 is the
principalunifier forcomputationtypes𝐶 and𝐶′.The unificationrelationsaredirectly definedby
the unification function.

                       U-Type                                U-Comp
                        unify(𝜏∼𝜏′)=𝜃                        unify(𝐶∼𝐶′)=𝜃
                            𝜏∼𝜏′ :𝜃                               𝐶∼𝐶′ :𝜃
  Figure16gives unification function      unify(𝑈) which takes a set of unification predicates and
returnstheprincipal unifiersofthem.Itisrelatively standard[MartelliandMontanari1982].The
arrow⇀ indicates a meta function that might fail. FollowingLeijen[2008] we explicitly indicate


Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 37

Soundly Handling Linearity                                                                                                                                               54:37

the successful return of a result by return. The auxiliary functions urow and ulin are given and
explained in . The unification predicates and predicate sets are defined as follows.
                           Unification predicates   UPred∋𝑢 ::=𝜏∼𝜏′|𝐶∼𝐶′
                           Unification sets               USet∋𝑈 ::=𝑈,𝑢
   Notethatitispossibletopostponethesolvingofunificationconstraintstotheconstraintsolving
algorithm. We opt for this mixed style presentation for Q◦eff in order to keep close to the original
presentation of qualified types [Jones1994], and to keep the constraint set cleaner.

B.4   Constraint Solving Algorithm
The constraint solving algorithm of Q◦eff is given in Figure17.
   The function ulin unifies two linearity types. The function ulab unifies the signatures of shared
labels of two concrete rows. The function urow wraps ulab. The function trlin computes the
transitive closure of linearity constraints.
   The function srow(𝜃,𝑃,𝑄) solves row constraints. It takes the current substitution𝜃 and the
currently solved predicate set𝑃, and solves the predicates in𝑄. The basic idea is to transform the
row subtypingpredicates toforms of𝜇⩽𝑅 androwlacking predicatesto formsof𝜇⊥L, which
we call solved forms.It doesa caseanalysis onthe firstpredicate in𝑄.For instance, considerthe
most complicated case𝐾1;𝜇1⩽𝐾2;𝜇2. It first unifies the common labels of𝐾1 and𝐾2. When𝐾1 is
a subset of𝐾2, we can directly transform it to the solved form; otherwise, we allocatea fresh row
variable to substitute𝜇2 and transform it to the solved form. Note that we also need to move all
previouslysolvedpredicatestotheunsolvedpredicateset,becausetherowvariable𝜇2issubstituted,
which might turn some predicates in solved forms to unsolved forms.
   Themainfunctionsolvesequentiallysolvesrowconstraintsusingsrowandlinearityconstraints
usingtrlin.Notethatweusefactorisetofactorisetheoutputpredicatesettotransformthelinearity
constraints into the simplest form (i.e., only between value type variables, row variables, and
linearity), which is suitable for computing the transitive closure using trlin.


































                                 Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 38

54:38                                                                            Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

Γ⊢𝑉 :𝐴⊣𝜃,𝑃,Σ          Γ⊢𝑀 :𝐶⊣𝜃,𝑃,Σ         Γ⊢𝐻 :𝐶⇒𝐷⊣𝜃,𝑃,Σ

                                         Q-LetW
       Q-VarW                              Γ⊢𝑉 :𝐴⊣𝜃1,𝑃1,Σ1   𝜎 = gen(𝜃1Γ,𝑃1⇒𝐴)
         (𝑥 :∀𝛼.𝑃⇒𝐴)∈ Γ                              𝜃1Γ,𝑥 :𝜎⊢𝑀 :𝐶⊣𝜃2,𝑃2,Σ2
        𝛽 fresh   𝜃 =[𝛽/𝛼]                     𝑄 = un(𝜃2𝜃1Γ|Σ1∩Σ2)∪un(𝜃2(𝑥 :𝜎)|Σc2)
        Γ⊢𝑥 :𝜃𝐴⊣𝜃,𝜃𝑃,{𝑥}                 Γ⊢ let𝑥 =𝑉 in𝑀 :𝐶⊣𝜃2𝜃1,𝑃2∪𝑄,Σ1∪(Σ2\𝑥)

                                                 Q-AppW
 Q-AbsW                                          Γ⊢𝑉 :𝐴⊣𝜃1,𝑃1,Σ1   𝜃1Γ⊢𝑊 :𝐵⊣𝜃2,𝑃2,Σ2
  𝛼,𝜙 fresh      Γ,𝑥 :𝛼⊢𝑀 :𝐶⊣𝜃,𝑃,Σ                    𝛼,𝜇,𝜙 fresh   𝜃2𝐴∼(𝐵→𝜙𝛼 !𝜇) :𝜃3
   𝑄 = leq(𝜃Γ|Σ,𝜙)∪un(𝜃(𝑥 :𝛼)|Σc)                  𝑃 =𝜃3(𝜃2𝑃1∪𝑃2)   𝑄 = un(𝜃3𝜃2𝜃1Γ|Σ1∩Σ2)
   Γ⊢𝜆𝑥.𝑀 :𝜃𝛼→𝜙𝐶⊣𝜃,𝑃∪𝑄,Σ\𝑥                          Γ⊢𝑉𝑊 :𝜃3(𝛼 !𝜇)⊣𝜃3𝜃2𝜃1,𝑃∪𝑄,Σ1∪Σ2

    Q-SeqW
          Γ⊢𝑀 :𝐴!{𝑅1}⊣𝜃1,𝑃1,Σ1   𝜃1Γ,𝑥 :𝐴⊢𝑁 :𝐵!{𝑅2}⊣𝜃2,𝑃2,Σ2   𝜇 fresh
    𝑄 = un(𝜃2𝜃1Γ|Σ1∩Σ2)∪un(𝜃2(𝑥 :𝐴)|Σc2)∪leq(𝜃2𝜃1Γ|Σ2,𝜃2𝑅1)∪sub(𝜃2𝑅1,𝜇)∪sub(𝑅2,𝜇)
                   Γ⊢ let𝑥←𝑀 in𝑁 :𝐵!𝜇⊣𝜃2𝜃1,𝜃2𝑃1∪𝑃2∪𝑄,Σ1∪(Σ2\𝑥)

                                                  Q-DoW
       Q-ReturnW                                      Γ⊢𝑉 :𝐴⊣𝜃1,𝑃,Σ   𝐴∼𝐴ℓ :𝜃2
        Γ⊢𝑉 :𝐴⊣𝜃,𝑃,Σ   𝜇 fresh                     𝜇,𝜙 fresh   𝑄 = sub((ℓ :𝐴ℓ↠𝜙𝐵ℓ),𝜇)
        Γ⊢ return𝑉 :𝐴!{𝜇}⊣𝜃,𝑃,Σ                     Γ⊢ doℓ𝑉 :𝐵ℓ !{𝜇}⊣𝜃2𝜃1,𝜃2𝑃∪𝑄,Σ

       Q-HandleW
                Γ⊢𝐻 :𝐴!{𝑅1}⇒𝐷⊣𝜃1,𝑃1,Σ1   𝜃1Γ⊢𝑀 :𝐴′!{𝑅}⊣𝜃2,𝑃2,Σ2
       𝜃2𝐴∼𝐴′ :𝜃3   𝑃 =𝜃3(𝜃2𝑃1∪𝑃2)   𝑄 = sub(𝜃3𝑅,𝜃3𝜃2𝑅1)∪un(𝜃3𝜃2𝜃1Γ|Σ1∩Σ2)
                      Γ⊢ handle𝑀 with𝐻 :𝜃3𝜃2𝐷⊣𝜃3𝜃2𝜃1,𝑃∪𝑄,Σ1∪Σ2

Q-HandlerW
                            𝛼,𝜙𝑖,𝜇 fresh      Γ,𝑥 :𝛼⊢𝑀 :𝐷⊣𝜃0,𝑃0,Σ0
[𝜃𝑖−1(Γ,𝑝𝑖 :𝐴ℓ𝑖,𝑟𝑖 :𝐵ℓ𝑖→𝜙𝑖𝐷)⊢𝑁𝑖 :𝐷𝑖⊣𝜃′𝑖,𝑃𝑖,Σ𝑖   𝐷𝑖∼𝜃′𝑖𝜃𝑖−1𝐷 :𝜃′′𝑖   𝜃𝑖 =𝜃′′𝑖𝜃′𝑖𝜃𝑖−1]𝑛𝑖=1
                       𝐶 =𝜃𝑛(𝛼 !{(ℓ𝑖 :𝐴ℓ𝑖↠𝜙𝑖𝐵ℓ𝑖)𝑖 ;𝜇})   𝐵!{𝑅}=𝜃𝑛𝐷
 Σ=(Σ0\{𝑥})∪(∪𝑛𝑖=1(Σ𝑖\{𝑝𝑖,𝑟𝑖}))   𝑃 =(∪𝑛𝑖=0𝜃𝑛𝑃𝑖)∪un(𝜃𝑛Γ|Σ)∪sub(𝜇,𝑅)∪lack(𝜇,{ℓ𝑖}𝑖)
                   𝑄 = un(𝜃𝑛(𝑥 :𝛼)|Σc0)∪(∪𝑛𝑖=1un(𝜃𝑛(𝑝𝑖 :𝐴ℓ𝑖,𝑟𝑖 :𝐵ℓ𝑖→𝜙𝑖𝐷)))
               Γ⊢{return𝑥↦→𝑀}⊎{ℓ𝑖𝑝𝑖𝑟𝑖↦→𝑁𝑖}𝑛𝑖=1 :𝐶⇒𝜃𝑛𝐷⊣𝜃𝑛,𝑃∪𝑄,Σ

                 leq(Γ,𝜏)= factorise(Γ⪯𝜏)           sub(𝑅1,𝑅2)= factorise(𝑅1⩽𝑅2)
                    un(Γ)= leq(Γ,•)                 lack(𝑅,L)= factorise(𝑅⊥L)


                                     Fig. 15. Type Inference of Q◦eff






Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 39

Soundly Handling Linearity                                                                                                                                               54:39

       unify : USet⇀ Subst                                           unify(𝐾1∼𝐾2,𝑈)=
       unify(·)= return𝜄                                                let(𝐾′1,𝐾′2,𝜃)= urow(𝐾1,𝐾2)
                                                                        assert set(𝐾′1)= set(𝐾′2)
       unify(𝛼∼𝛼,𝑈)= unify(𝑈)                                           unify(𝜃𝑈)𝜃
       unify(𝛼∼𝜏,𝑈)=                                                 unify(𝐾1;𝜇1∼𝐾2,𝑈)=
          assert𝛼 ∉ ftv(𝜏)                                              let(𝐾′1,𝐾′2,𝜃)= urow(𝐾1,𝐾2)
          let𝜃 =[𝜏/𝛼]                                                   assert set(𝐾′1)⊆ set(𝐾2)
          unify(𝜃𝑈)𝜃                                                    assume fresh𝜇
       unify(𝜏∼𝛼,𝑈)=                                                    let𝜃′=[((𝐾′2\𝐾′1);𝜇)/𝜇1]
          unify(𝛼∼𝜏,𝑈)                                                  unify(𝜃′𝜃𝑈)𝜃′𝜃
       unify(𝐴!{𝑅}∼𝐴′!{𝑅′},𝑈)=                                       unify(𝐾2∼𝐾1;𝜇1,𝑈)=
          unify(𝐴∼𝐴′,𝑅∼𝑅′,𝑈)                                            unify(𝐾1;𝜇1∼𝐾2,𝑈)
       unify((𝐴→𝑌𝐶)∼(𝐴′→𝑌′𝐶′),𝑈)=                                    unify(𝐾1;𝜇1∼𝐾2;𝜇2,𝑈)=
          unify(𝐴∼𝐴′,𝐶∼𝐶′,𝑌∼𝑌′,𝑈)                                       let(𝐾′1,𝐾′2,𝜃)= urow(𝐾1,𝐾2)
       unify(𝑌∼𝑌′,𝑈)=                                                   assume fresh𝜇
          let𝜃 = ulin(𝑌,𝑌′)                                             let𝜃′=[((𝐾′2\𝐾′1);𝜇)/𝜇1,
          unify(𝜃𝑈)𝜃                                                                ((𝐾′1\𝐾′2);𝜇)/𝜇2]
                                                                        unify(𝜃′𝜃𝑈)𝜃′𝜃


                                             Fig. 16. Unification of Q◦eff









































                                Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 40

54:40                                                                            Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris


  srow : (Subst×PSet×PSet)                                urow : (CRow×CRow)
        ⇀(Subst×PSet)                                           ⇀(CRow×CRow×Subst)
  srow(𝜃,𝑃,·)= return(𝜃,𝑃)                                urow(𝐾,𝐾′)=
  srow(𝜃,𝑃,(𝜏1⪯𝜏2,𝑄))=                                      let𝜃 = ulab(𝐾,𝐾′)
     srow(𝜃,(𝑃,𝜏1⪯𝜏2),𝑄)                                    return(𝜃𝐾,𝜃𝐾′,𝜃)

  srow(𝜃,𝑃,(𝐾1⩽𝐾2,𝑄))=                                    ulab :(CRow×CRow)⇀ Subst
     let(𝐾′1,𝐾′2,𝜃′)= urow(𝐾1,𝐾2)                         ulab(·,𝐾)= return𝜄
     assert set(𝐾′1)⊆ set(𝐾′2)                            ulab(𝐾,·)= return𝜄
     srow(𝜃′𝜃,𝜃′𝑃,𝜃′𝑄)                                    ulab((ℓ :𝑌1;𝐾1),(ℓ :𝑌2;𝐾2))=
  srow(𝜃,𝑃,(𝐾1;𝜇⩽𝐾2;𝜇,𝑄))=                                  let𝜃 = ulin(𝑌1,𝑌1)
     let(𝐾′1,𝐾′2,𝜃′)= urow(𝐾1,𝐾2)                           let𝜃′= ulab(𝜃𝐾1,𝜃𝐾2)
     assert set(𝐾′1)⊆ set(𝐾′2)                              return𝜃′𝜃
     srow(𝜃′𝜃,𝜃′𝑃,𝜃′𝑄)                                    ulab((ℓ :𝑌 ;𝐾1),𝐾2)= ulab(𝐾1,𝐾2)
                                                          ulab(𝐾1,(ℓ :𝑌 ;𝐾2))= ulab(𝐾1,𝐾2)
  srow(𝜃,𝑃,(𝐾1;𝜇⩽𝐾2,𝑄))=                                  ulin :(Lin×Lin)⇀ Subst
     let(𝐾′1,𝐾′2,𝜃′)= urow(𝐾1,𝐾2)                         ulin(𝑌,𝑌)= return𝜄
     assert set(𝐾′1)⊆ set(𝐾′2)                            ulin(•,◦) = fail
     srow(𝜃′𝜃,(𝜃′𝑃,𝜇⩽(𝐾′2\𝐾′1)),𝜃′𝑄)                      ulin(◦,•) = fail
  srow(𝜃,𝑃,(𝐾1⩽𝐾2;𝜇2,𝑄))=                                 ulin(𝜙,𝑌) = return[𝑌/𝜙]
     let(𝐾′1,𝐾′2,𝜃′)= urow(𝐾1,𝐾2)                         ulin(𝑌,𝜙) = return[𝑌/𝜙]
     if set(𝐾′1)⊆ set(𝐾′2)
     then srow(𝜃′𝜃,𝜃′𝑃,𝜃′𝑄)                               trlin :(PSet×PSet)→ PSet
     elseassume fresh𝜇                                    trlin(𝑃,·)=𝑃
          let𝜃′′=[((𝐾′1\𝐾′2);𝜇)/𝜇2]𝜃′                     trlin(𝑃,(𝑅1⩽𝑅2,𝑄))= trlin(𝑃,𝑄)
          srow(𝜃′′𝜃,·,𝜃′′(𝑄,𝑃))                           trlin(𝑃,(𝜏1⪯𝜏2,𝑄))= trlin(𝑃∪𝑃′′,𝑄)
  srow(𝜃,𝑃,(𝐾1;𝜇1⩽𝐾2;𝜇2,𝑄))=                                where
     let(𝐾′1,𝐾′2,𝜃′)= urow(𝐾1,𝐾2)                              𝑃′=𝑃∪{𝜏1⪯𝜏1,𝜏2⪯𝜏2}
     if set(𝐾′1)⊆ set(𝐾′2)                                     𝑃′′={𝜏′1⪯𝜏′2|{𝜏′1⪯𝜏1,𝜏2⪯𝜏′2}⊆𝑃′}
     then srow(𝜃′𝜃,(𝜃′𝑃,𝜇1⩽(𝐾′2\𝐾′1);𝜇2),𝜃′𝑄)             solve : PSet⇀(Subst×PSet)
     elseassume fresh𝜇                                    solve(𝑃)=
          let𝜃′′=[((𝐾′1\𝐾′2);𝜇)/𝜇2]𝜃′                       let(𝜃,𝑄)= srow(𝜄,·,𝑃)
          srow(𝜃′′𝜃,𝜇1⩽(𝐾′2\𝐾′1);𝜇,𝜃′′(𝑄,𝑃))                let𝑄′= trlin(·,factorise(𝑄))
  srow(𝜃,𝑃,(𝐾⊥L,𝑄))=                                        assert(◦⪯•)∉𝑄′
     assert dom(𝐾)∩L=∅                                      return(𝜃,𝑄)
     srow(𝜃,𝑃,𝑄)
  srow(𝜃,𝑃,(𝐾 ;𝜇⊥L,𝑄))=
     assert dom(𝐾)∩L=∅
     srow(𝜃,(𝑃,𝜇⊥L),𝑄)

                                      Fig. 17. Constraint Solving of Q◦eff










Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 41

Soundly Handling Linearity                                                                                                                                               54:41

C   PROOFS OF Q◦eff
In this section, we prove the theorems in Section5.

C.1   Correctness of Factorisation
We first prove some useful properties of the entailment relations.
  Theorem C.1 (Properties of the entailment relation).  The entailment relation between
predicate sets satisfies the following properties:
    • Monotonicity. If𝑄⊆𝑃, then𝑃⊢𝑄.
    • Transitivity. If𝑃1⊢𝑃2 and𝑃2⊢𝑃3, then𝑃1⊢𝑃3.
    • Closure property. If𝑃⊢𝑄, then𝜃𝑃⊢𝜃𝑄.
    • Weakening. If𝑃⊢𝑄, then𝑃,𝑃′⊢𝑄.
  Proof.
Monotonicity. Directly follows from P-Subsume andP-PredSet.
Transitivity. By P-PredSet, we only need to prove that if𝑃1⊢𝑃2 and𝑃2⊢𝜋, then𝑃1⊢𝜋. By
straightforward induction on𝑃2⊢𝜋.
Closureproperty.ByP-PredSet,weonlyneedtoprovethatif𝑃⊢𝜋 then𝜃𝑃⊢𝜃𝜋.Bystraightforward
induction on𝑃⊢𝜋.
Weakening. By P-PredSet, we only need to prove that if𝑃⊢𝜋 then𝑃,𝑃′⊢𝜋. By straightforward
induction on𝑃⊢𝜋.
                                                                                                               □
  LemmaC.2(Inverseclosureproperty).  If𝑃⊢𝜃(𝜎⪯𝜏), then there exists𝑃′⊢𝜎⪯𝜏 such that
𝑃⊢𝜃𝑃′.
  Proof. By induction on the entailment relations.
Case
                                   P-Quantifier
                                    𝑃⊢[𝜏′/𝛼]𝜃(𝜎⪯𝜏)(1) for some𝜏′
                                             𝑃⊢𝜃((∀𝛼.𝜎)⪯𝜏)
       Assume that𝛼 ∉  dom(𝜃) and𝛼 ∉  ftv(𝜏) without loss of generality. We can commute
       [𝜏′/𝛼] and𝜃 in(1). By the IH on(1), there exists      𝑃′⊢[𝜏′/𝛼](𝜎⪯𝜏) such that𝑃⊢𝜃𝑃′. By
       P-Quantifier, we have𝑃′⊢(∀𝛼.𝜎)⪯𝜏.
Case
                                     P-Qualifier
                                     𝑃⊢𝜃𝜋(1)   𝑃⊢𝜃(𝜌⪯𝜏)(2)
                                            𝑃⊢𝜃((𝜋⇒𝜌)⪯𝜏)
       BytheIHon(1),thereexists   𝑃1⊢𝜋 suchthat𝑃⊢𝜃𝑃1.BytheIHon(2),thereexists   𝑃2⊢𝜌⪯𝜏
       suchthat𝑃⊢𝜃𝑃2.ByP-Qualifier,wehave𝑃1∪𝑃2⊢(𝜋⇒𝜌)⪯𝜏.ByP-PredSet,wehave
       𝑃⊢𝜃(𝑃1∪𝑃2).
Case For all other cases of𝑃⊢𝜃𝜋, just take𝑃′=𝜋.
                                                                                                               □
  Theorem 5.3 (Correctness of factorisation).  If factorise(𝑃) =𝑄, then𝑄⊢𝑃 and𝑃⊢𝑄. If
factorise(Γ⪯𝜏)=𝑄, then𝑄⊢Γ⪯𝜏 and for any𝑃⊢Γ⪯𝜏, there exists𝜃 such that𝑃⊢𝜃𝑄.
  Proof. Thefirstpart ofthetheoremis kindofobviousbecause factorise(𝑃) isalmostdirectly
definedfromtheentailmentrulesinFigure10.Weprovetheauxiliarylemmathatif      factorise(𝜋)=𝑄,


                               Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 42

54:42                                                                            Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

then𝑄⊢𝜋 and𝜋⊢𝑄. Both directions follow from straightforward induction on the definition
of factorise. Note that in the proof of𝜋⊢𝑄, we apply the bottom-up direction of the two-way
rules P-Fun and P-Row. Then, given factorise(𝑃) = Ð𝜋∈𝑃 factorise(𝜋), by the lemma we have
factorise(𝜋)⊢𝜋 for all𝜋∈𝑃, which then giveÐ𝜋∈𝑃 factorise(𝜋)⊢𝑃 by P-PredSet and the
weakening of TheoremC.1. We also have that    𝜋⊢ factorise(𝜋) for all𝜋∈𝑃, which then give
𝑃⊢Ð𝜋∈𝑃 factorise(𝜋) by P-PredSetand the weakening of TheoremC.1.
  Forthesecondpartofthetheorem,weprovetheauxiliarylemmathatiffactorise(𝜎⪯𝜏)=𝑄,
then𝑄⊢𝜎⪯𝜏 andforany𝑃⊢𝜎⪯𝜏,thereexists𝜃 suchthat𝑃⊢𝜃𝑄.The𝑄⊢𝜎⪯𝜏 followsfrom
straightforward induction on the definition of factorise. The other direction is more involved. We
proceed by induction on the definition of factorise.
Case
                 factorise((∀𝛼.𝜎)⪯𝜏)= factorise([𝛽/𝛼]𝜎⪯𝜏)(1) for some fresh𝛽
      Suppose factorise((∀𝛼.𝜎)⪯𝜏) =𝑄. We want to show that for any𝑃⊢(∀𝛼.𝜎)⪯𝜏 (2),
      thereexists𝜃 suchthat𝑃⊢𝜃𝑄.By(2)and         P-Quantifier,thereexists𝜃1=[𝜏′/𝛼] suchthat
      𝑃⊢𝜃1𝜎⪯𝜏. Let𝜃2 =[𝜏′/𝛽]. We have𝑃⊢𝜃2[𝛽/𝛼]𝜎⪯𝜏. By LemmaC.2, there exists       𝑃′
      suchthat𝑃′⊢[𝛽/𝛼]𝜎⪯𝜏 (3)and  𝑃⊢𝜃2𝑃′.By(3)andtheIHon(1),thereexists      𝜃3 suchthat
      𝑃′⊢𝜃3𝑄. Then,by theclosure propertyand transitivityof TheoremC.1, wehave    𝑃⊢𝜃2𝜃3𝑄.
Case
                  factorise((𝜋⇒𝜎)⪯𝜏)= factorise(𝜋)(1)∪  factorise(𝜎⪯𝜏)(2)
      Suppose factorise(𝜋) =𝑄1 and factorise(𝜎⪯𝜏) =𝑄2. For any𝑃⊢(𝜋⇒𝜎)⪯𝜏, by
      P-Qualifier,wehave𝑃⊢𝜋 and𝑃⊢𝜎⪯𝜏.BytheIHon(1),thereexists   𝜃1suchthat𝑃⊢𝜃1𝑄1.
      BytheIHon(2),thereexists   𝜃2 suchthat𝑃⊢𝜃2𝑄2.Notethatdom(𝜃1)∩dom(𝜃2)=∅.Thus,
      we have𝑃⊢𝜃1𝜃2(𝑄1∪𝑄2).
Case
                                             factorise(𝜋)=𝑄
      Bythefirstpartofthetheoremwhichhasbeenproved,wehave𝜋⊢𝑄.Forany𝑃⊢𝜋,bythe
      transitivity of TheoremC.1, we have    𝑃⊢𝑄.
Withthislemma,ourgoalfollowsfromasimilaranalysistotheproofofthefirstpartofthetheorem
since P-Context andP-PredSet are both conjunction rules.
                                                                                                            □

C.2   Principal Unifier
WehavethefollowinglemmasfortheunificationfunctioninFigure16anditsauxiliary functions.
  LemmaC.3(Principalauxiliaryunifiers).  Given𝐾1 and𝐾2, let𝐾′1 =(𝐾1|dom(𝐾1)∩dom(𝐾2)) and
𝐾′2 =(𝐾2|dom(𝐾1)∩dom(𝐾2)). If ulab(𝐾1,𝐾2) =𝜃, then for any𝜃′𝐾′1 =𝜃′𝐾′2, there exists𝜃′′ such that
𝜃′=𝜃′′𝜃; if it fails, then𝐾′1 and𝐾′2 cannot be unified.
  Proof. By straightforward induction on the definition of urow, ulabandulin.     □
  LemmaC.4(Principalunifiers).  If𝐴∼𝐵 :𝜃, then for any𝜃′𝐴=𝜃′𝐵, there exists𝜃′′ such that
𝜃′=𝜃′′𝜃; if it fails, then𝐴 and𝐵 cannot be unified. The same applies to computation types.
  Proof. By straightforward induction on the definition of unify(𝑈).         □

C.3   Soundness and Completeness of Type Inference
We prove the soundness and completeness of type inference as well as auxiliary lemmas.
  Lemma C.5 (Closure property of typing).  If𝑃| Γ⊢𝑉 :𝐴, then𝜃𝑃|𝜃Γ⊢𝑉 :𝜃𝐴. The same
applies to computation and handler typing.


Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 43

Soundly Handling Linearity                                                                                                                                               54:43

  Proof. By the closure property of TheoremC.1and straightforward induction on the typing
derivations.                               □

  LemmaC.6(Weakeningofpredicates).  If𝑃| Γ⊢𝑉 :𝐴,then𝑃,𝑃′| Γ⊢𝑉 :𝐴.Thesameapplies
to computation and handler typing.

  Proof. BytheweakeningpropertyofTheoremC.1andstraightforwardinductiononthetyping
derivations.                               □

  LemmaC.7 (Extrais unlimited).  If𝑃| Γ⊢𝑉 :𝐴, then𝑃′| Γ,𝑥 :𝜎⊢𝑉 :𝐴 for any𝑃′⊢𝑃 and
𝑃′⊢𝜎⪯•. The same applies to computation and handler typing.

  Proof. By straightforward induction on the typing derivations.          □

  Theorem 5.1 (Soundness).  IfΓ⊢𝑉  :𝐴⊣𝜃,𝑃,Σ, then𝑃|𝜃Γ|Σ⊢𝑉  :𝐴. The same applies to
computation and handler typing.
  Proof. By mutual induction on the type inference derivationsΓ⊢𝑉 :𝐴⊣𝜃,𝑃,Σ,Γ⊢𝑀 :𝐶⊣
𝜃,𝑃,Σ, andΓ⊢𝐻 :𝐶⇒𝐷⊣𝜃,𝑃,Σ.
Case
                         Q-VarW
                         (𝑥 :∀𝛼.𝑃⇒𝐴)∈ Γ             𝛽 fresh   𝜃 =[𝛽/𝛼](1)
                                         Γ⊢𝑥 :𝜃𝐴⊣𝜃,𝜃𝑃,{𝑥}
      By(1), we have   𝜃𝑃⇒𝜃𝐴⊑𝜃(∀𝛼.𝑃⇒𝐴). Our goal then follows from Q-Var.
Case
                         Q-LetW
                         Γ⊢𝑉 :𝐴⊣𝜃1,𝑃1,Σ1(1)   𝜎 = gen(𝜃1Γ,𝑃1⇒𝐴)
                                   𝜃1Γ,𝑥 :𝜎⊢𝑀 :𝐶⊣𝜃2,𝑃2,Σ2(2)
                               𝑄 = un(𝜃2𝜃1Γ|Σ1∩Σ2)∪un(𝜃2(𝑥 :𝜎)|Σc2)
                         Γ⊢ let𝑥 =𝑉 in𝑀 :𝐶⊣𝜃2𝜃1,𝑃2∪𝑄,Σ1∪(Σ2\𝑥)
      By the IH on(1), we have   𝑃1|𝜃1Γ|Σ1⊢𝑉  :𝐴. By LemmaC.5, we have       𝜃2𝑃1|𝜃2𝜃1Γ⊢
      𝑉  :𝜃2𝐴(3). By the IH on(2), we have     𝑃2|𝜃2(𝜃1Γ,𝑥  :𝜎)|Σ2⊢𝑀  :𝜃2𝐶(4). Let  𝜎′ =
      gen(𝜃2𝜃1Γ,𝜃2𝑃1⇒𝜃2𝐴). Notice that𝜃2 is generated by the type inference judgement(2),
      whichcannotsubstitute anyvariablesboundby𝜎 (i.e.,variables inftv(𝑃1⇒𝐴)\ftv(𝜃1Γ)).
      Thus, we have𝜃2𝜎 =𝜎′. Let Σ′2 = Σ2\𝑥, Γ1 =(𝜃2𝜃1Γ)|Σ1\Σ′2, Γ2 =(𝜃2𝜃1Γ)|Σ′2\Σ1, Γ′ =
      (𝜃2𝜃1Γ)|Σ1∩Σ′2.By(3)and(4),wehave       𝜃2𝑃1| Γ1,Γ′⊢𝑉 :𝜃2𝐴(5)and 𝑃2| Γ2,Γ′,(𝑥 :𝜎′)|Σ2⊢𝑀 :
      𝜃2𝐶.ByLemmaC.7wehave       𝑃2∪un((𝑥 :𝜎′)|Σc2)| Γ2,Γ′,𝑥 :𝜎′⊢𝑀 :𝜃2𝐶(6).ByTheorem5.3,
      we have𝑄⊢Γ′⪯•. Our goal follows from Q-Let,(5),(6)and LemmaC.6.
Case
                              Q-AbsW
                              𝛼,𝜙 fresh      Γ,𝑥 :𝛼⊢𝑀 :𝐶⊣𝜃,𝑃,Σ(1)
                                  𝑄 = leq(𝜃Γ|Σ,𝜙)∪un(𝜃(𝑥 :𝛼)|Σc)
                                 Γ⊢𝜆𝑥.𝑀 :𝜃𝛼→𝜙𝐶⊣𝜃,𝑃∪𝑄,Σ\𝑥
      By the IH on(1), we have   𝑃|(𝜃Γ,𝑥 :𝜃𝛼)|Σ⊢𝑀 :𝐶. Our goal follows from LemmaC.7,
      Theorem5.3and          Q-Abs.


                             Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 44

54:44                                                                            Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

Case

                       Q-AppW
                       Γ⊢𝑉 :𝐴⊣𝜃1,𝑃1,Σ1(1)   𝜃1Γ⊢𝑊 :𝐵⊣𝜃2,𝑃2,Σ2(2)
                              𝛼,𝜇,𝜙 fresh   𝜃2𝐴∼(𝐵→𝜙𝛼 !𝜇) :𝜃3(3)
                             𝑃 =𝜃3(𝜃2𝑃1∪𝑃2)   𝑄 = un(𝜃3𝜃2𝜃1Γ|Σ1∩Σ2)
                              Γ⊢𝑉𝑊 :𝜃3(𝛼 !𝜇)⊣𝜃3𝜃2𝜃1,𝑃∪𝑄,Σ1∪Σ2

      By the IH on(1), we have   𝑃1|𝜃1Γ⊢𝑉  :𝐴. By the IH on(2), we have   𝑃2|𝜃2𝜃1Γ⊢𝑊  :𝐵.
      By LemmaC.5, we have       𝜃3𝜃2𝑃1|𝜃3𝜃2𝜃1Γ⊢𝑉  :𝜃3𝜃2𝐴(4)and  𝜃3𝑃2|𝜃3𝜃2𝜃1Γ⊢𝑊  :𝜃3𝐵(5).
      By(3), we have   𝜃3𝜃2𝐴 =𝜃3(𝐵→𝜙𝛼 !𝜇). Let Γ1 =(𝜃3𝜃2𝜃1Γ)|Σ1\Σ2, Γ2 =(𝜃3𝜃2𝜃1Γ)|Σ2\Σ1,
      Γ′=(𝜃3𝜃2𝜃1Γ)|Σ1∩Σ2.By(4)and(5),wehave       𝜃3𝜃2𝑃1| Γ1,Γ′⊢𝑉 :𝜃3𝜃2𝐴(6)and 𝜃3𝑃2| Γ2,Γ′⊢
      𝑊 :𝜃3𝐵(7). ByTheorem5.3, wehave     𝑄⊢Γ′⪯•. Ourgoal follows fromQ-App,(6),(7), and
      LemmaC.6.
Case

                                   Q-ReturnW
                                    Γ⊢𝑉 :𝐴⊣𝜃,𝑃,Σ(1)   𝜇 fresh
                                     Γ⊢ return𝑉 :𝐴!{𝜇}⊣𝜃,𝑃,Σ

      Our goal follows from the IH on(1)and         Q-Return.
Case

                               Q-DoW
                                 Γ⊢𝑉 :𝐴⊣𝜃1,𝑃,Σ(1)   𝐴∼𝐴ℓ :𝜃2
                               𝜇,𝜙 fresh   𝑄 = sub((ℓ :𝐴ℓ↠𝜙𝐵ℓ),𝜇)
                                 Γ⊢ doℓ𝑉 :𝐵ℓ !{𝜇}⊣𝜃2𝜃1,𝜃2𝑃∪𝑄,Σ

      Our goal follows from the IH on(1),         Q-Do, Theorem5.3, and LemmaC.5.
Case

    Q-SeqW
      Γ⊢𝑀 :𝐴!{𝑅1}⊣𝜃1,𝑃1,Σ1(1)   𝜃1Γ,𝑥 :𝐴⊢𝑁 :𝐵!{𝑅2}⊣𝜃2,𝑃2,Σ2(2)   𝜇 fresh
    𝑄 = un(𝜃2𝜃1Γ|Σ1∩Σ2)∪un(𝜃2(𝑥 :𝐴)|Σc2)∪leq(𝜃2𝜃1Γ|Σ2,𝜃2𝑅1)∪sub(𝜃2𝑅1,𝜇)∪sub(𝑅2,𝜇)
                    Γ⊢ let𝑥←𝑀 in𝑁 :𝐵!𝜇⊣𝜃2𝜃1,𝜃2𝑃1∪𝑃2∪𝑄,Σ1∪(Σ2\𝑥)

      SimilartotheQ-LetWandQ-AppWcases.LetΣ′2=Σ2\𝑥,Γ1=(𝜃2𝜃1Γ)|Σ1\Σ′2,Γ2=(𝜃2𝜃1Γ)|Σ′2\Σ1,
      Γ′=(𝜃2𝜃1Γ)|Σ1∩Σ′2.BytheIHon(1)andLemmaC.5,wehave          𝜃2𝑃1| Γ1,Γ′⊢𝑀 :𝜃2(𝐴!{𝑅1})(3).
      BytheIHon(2),wehave   𝑃2| Γ2,Γ′,(𝑥 :𝐴)|Σ2⊢𝑁 :𝐵!{𝑅2}(4).Ourgoalfollowsfrom     Q-Seq,
      (3),(4), Theorem5.3, LemmaC.6and LemmaC.7.
Case

       Q-HandleW
             Γ⊢𝐻 :𝐴!{𝑅1}⇒𝐷⊣𝜃1,𝑃1,Σ1(1)   𝜃1Γ⊢𝑀 :𝐴′!{𝑅}⊣𝜃2,𝑃2,Σ2(2)
        𝜃2𝐴∼𝐴′ :𝜃3   𝑃 =𝜃3(𝜃2𝑃1∪𝑃2)   𝑄 = sub(𝜃3𝑅,𝜃3𝜃2𝑅1)∪un(𝜃3𝜃2𝜃1Γ|Σ1∩Σ2)
                       Γ⊢ handle𝑀 with𝐻 :𝜃3𝜃2𝐷⊣𝜃3𝜃2𝜃1,𝑃∪𝑄,Σ1∪Σ2

      ByasimilarprooftotheQ-Appcase,ourgoalfollowsfromtheIHson(1)and(2),Theorem5.3,
      LemmaC.5and LemmaC.6.


Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 45

Soundly Handling Linearity                                                                                                                                               54:45

Case
                Q-HandlerW
                           𝛼,𝜙𝑖,𝜇 fresh      Γ,𝑥 :𝛼⊢𝑀 :𝐷⊣𝜃0,𝑃0,Σ0(1)
                       [𝜃𝑖−1(Γ,𝑝𝑖 :𝐴ℓ𝑖,𝑟𝑖 :𝐵ℓ𝑖→𝜙𝑖𝐷)⊢𝑁𝑖 :𝐷𝑖⊣𝜃′𝑖,𝑃𝑖,Σ𝑖(2)
                               𝐷𝑖∼𝜃′𝑖𝜃𝑖−1𝐷 :𝜃′′𝑖   𝜃𝑖 =𝜃′′𝑖𝜃′𝑖𝜃𝑖−1]𝑛𝑖=1
                        𝐶 =𝜃𝑛(𝛼 !{(ℓ𝑖 :𝐴ℓ𝑖↠𝜙𝑖𝐵ℓ𝑖)𝑖 ;𝜇})   𝐵!{𝑅}=𝜃𝑛𝐷
                                  Σ=(Σ0\{𝑥})∪(∪𝑛𝑖=1(Σ𝑖\{𝑝𝑖,𝑟𝑖}))
                       𝑃 =(∪𝑛𝑖=0𝜃𝑛𝑃𝑖)∪un(𝜃𝑛Γ|Σ)∪sub(𝜇,𝑅)∪lack(𝜇,{ℓ𝑖}𝑖)
                    𝑄 = un(𝜃𝑛(𝑥 :𝛼)|Σc0)∪(∪𝑛𝑖=1un(𝜃𝑛(𝑝𝑖 :𝐴ℓ𝑖,𝑟𝑖 :𝐵ℓ𝑖→𝜙𝑖𝐷)))
                Γ⊢{return𝑥↦→𝑀}⊎{ℓ𝑖𝑝𝑖𝑟𝑖↦→𝑁𝑖}𝑛𝑖=1 :𝐶⇒𝜃𝑛𝐷⊣𝜃𝑛,𝑃∪𝑄,Σ
      Thetypeinferenceforhandlersisthemostcomplicated,butthereisnothingreallynewabout
      the proof compared to previous cases. By the IH on(1), we have   𝑃0|𝜃0(Γ,𝑥 :𝛼)|Σ0⊢𝑀 :𝐷.
      BytheIHon(2),wehave   𝑃𝑖|𝜃′𝑖𝜃𝑖−1(Γ,𝑝𝑖 :𝐴ℓ𝑖,𝑟𝑖 :𝐵ℓ𝑖→𝜙𝑖𝐷)|Σ𝑖⊢𝑁𝑖 :𝐷𝑖.ByLemmaC.5,we
      have𝜃𝑛𝑃0|𝜃𝑛(Γ,𝑥 :𝛼)|Σ0⊢𝑀 :𝜃𝑛𝐷 and𝜃𝑛𝑃𝑖|𝜃𝑛(Γ,𝑝𝑖 :𝐴ℓ𝑖,𝑟𝑖 :𝐵ℓ𝑖→𝜙𝑖𝐷)|Σ𝑖⊢𝑁𝑖 :𝜃𝑛𝐷𝑖.
      By LemmaC.7, we have
                 𝜃𝑛𝑃0∪un(𝜃𝑛Γ|Σ)∪un(𝜃𝑛(𝑥 :𝛼)|Σc0)|𝜃𝑛(Γ|Σ,𝑥 :𝛼)⊢𝑀 :𝜃𝑛𝐷(3)
      and
𝜃𝑛𝑃𝑖∪un(𝜃𝑛Γ|Σ)∪un(𝜃𝑛(𝑝𝑖 :𝐴ℓ𝑖,𝑟𝑖 :𝐵ℓ𝑖→𝜙𝑖𝐷))|𝜃𝑛(Γ|Σ,𝑝𝑖 :𝐴ℓ𝑖,𝑟𝑖 :𝐵ℓ𝑖→𝜙𝑖𝐷)⊢𝑁𝑖 :𝜃𝑛𝐷𝑖(4)
      By Theorem5.3, we have    𝑃∪𝑄⊢{𝜇⩽𝑅,𝜇⊥{ℓ𝑖}𝑖}. Our goal follows fromQ-Handler,(3),
      (4), and LemmaC.6.
                                                                                                         □

  Lemma C.8(More general contexts).  If𝑃| Γ,𝑥 :𝜎⊢𝑉 :𝐴 and𝜎⊑𝜎′, then𝑃| Γ,𝑥 :𝜎′⊢𝑉 :
𝐴. The same applies to computation and handler typing.
  Proof. By straightforward induction on the typing derivation.           □
  LemmaC.9(Zeroisunlimited).  If𝑃| Γ,𝑥 :𝜎⊢𝑉 :𝐴and𝑥 doesnotappearin𝑉,then𝑃⊢𝜎⪯•.
The same applies to computation and handler typing.
  Proof. By straightforward induction on the typing derivation.           □

  LemmaC.10(Closurepropertyoffactorisation).  Iffactorise(𝑃)=𝑄, thenfactorise(𝜃𝑃)=
𝜃𝑄. Iffactorise(Γ⪯𝜏)=𝑄, then factorise(𝜃(Γ⪯𝜏))=𝜃𝑄.
  Proof. BytheclosurepropertyofTheoremC.1andstraightforwardinductiononthedefinition
offactorise.                               □

  Theorem 5.2 (Completeness).  If𝑃|𝜃Γ⊢𝑉 :𝐴, thenΓ⊢𝑉 :𝐴′⊣𝜃′,𝑄,Σ and there exists𝜃′′
suchthat𝐴=𝜃′′𝐴′,𝑃⊢𝜃′′𝑄,and(𝜃 =𝜃′′𝜃′)|Γ.Thesameappliestocomputationandhandlertyping.
  Proof. By mutual induction on the syntax-directed typing derivations𝑃| Γ⊢𝑉 :𝐴,𝑃| Γ⊢𝑀 :
𝐶, and𝑃| Γ⊢𝐻 :𝐶⇒𝐷.
Case
                                 Q-Var
                                  𝑃⊢Γ⪯•   𝑃⇒𝐴⊑∀𝛼.𝑄⇒𝐵
                                    𝑃|𝜃(Γ,𝑥 :∀𝛼.𝑄⇒𝐵)⊢𝑥 :𝐴


                             Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 46

54:46                                                                            Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

      By𝑃⇒𝐴⊑∀𝛼.𝑄⇒𝐵,thereexists𝜃1 suchthat𝐴=𝜃1𝐵 and𝑃⊢𝜃1𝑄.ByQ-VarW,wehave
      the following derivation
                                Q-VarW
                                         𝛽 fresh   𝜃′=[𝛽/𝛼]
                                Γ,𝑥 :∀𝛼.𝑄⇒𝐵⊢𝑥 :𝜃′𝐵⊣𝜃′,𝜃′𝑄,{𝑥}
      Let𝜃′′=𝜃𝜃1[𝛼/𝛽], we have𝐴=𝜃1𝐵=𝜃′′𝜃′𝐵,𝑃⊢𝜃1𝑄 =𝜃′′𝜃′𝑄, and(𝜃 =𝜃′′𝜃′)|Γ.
Case
                       Q-Let
                       𝑃1|𝜃(Γ1,Γ)⊢𝑉 :𝐴(1)   𝜎 = gen(𝜃(Γ1,Γ),𝑃1⇒𝐴)
                            𝑃2|𝜃(Γ2,Γ),𝑥 :𝜎⊢𝑀 :𝐶(2)   𝑃2⊢𝜃Γ⪯•
                                  𝑃2|𝜃(Γ1,Γ2,Γ)⊢ let𝑥 =𝑉 in𝑀 :𝐶
      By the IH on(1), we have       Γ1,Γ⊢𝑉 :𝐴′⊣𝜃1,𝑃′1,Σ1 and there exists𝜃′1 such that𝐴 =𝜃′1𝐴′,
      𝑃1⊢𝜃′1𝑃′1, and(𝜃 =𝜃′1𝜃1)|Γ1,Γ. By contextweakening, we haveΓ1,Γ2,Γ⊢𝑉 :𝐴′⊣𝜃1,𝑃′1,Σ1(3).
      We also have𝜎 = gen(𝜃(Γ1,Γ2,Γ),𝑃1⇒𝐴). Let𝜎′ = gen(𝜃1(Γ1,Γ2,Γ),𝑃′1⇒𝐴′). By(𝜃 =
      𝜃′1𝜃1)|Γ1,Γ,itiseasytoseethat𝜎⊑𝜃′1𝜎′.Thenby(2)andLemmaC.8,wehave          𝑃2|𝜃(Γ2,Γ),𝑥 :
      𝜃′1𝜎′⊢𝑀 :𝐶, which further implies𝑃2|𝜃3𝜃′1𝜃1(Γ2,Γ,𝑥 :𝜎′)⊢𝑀 :𝐶(4)for some  𝜃3 with
      𝜃 =𝜃3𝜃′1𝜃1.By theIHon(4),wehave   𝜃1(Γ2,Γ,𝑥 :𝜎′)⊢𝑀 :𝐶′⊣𝜃2,𝑃′2,Σ2 andthere exists𝜃′2
      suchthat𝐶 =𝜃′2𝐶′,𝑃2⊢𝜃′2𝑃′2,and(𝜃3𝜃′1=𝜃′2𝜃2)|Γ2,Γ.Bycontextweakeningand𝜃1𝜎′=𝜎′,we
      have𝜃1(Γ1,Γ2,Γ),𝑥 :𝜎′⊢𝑀 :𝐶′⊣𝜃2,𝑃′2,Σ2(5). Let  𝑄 = un(𝜃2𝜃1(Γ1,Γ2,Γ)|Σ1∩Σ2)∪un(𝜃2(𝑥 :
      𝜎)|Σc2). By Q-LetW,(3)and(5), we have
                       Γ1,Γ2,Γ⊢ let𝑥 =𝑉 in𝑀 :𝐶′⊣𝜃2𝜃1,𝑃′2∪𝑄,Σ1∪(Σ2\𝑥)
      With𝜃′=𝜃′1𝜃′2,wehave(𝜃 =𝜃′𝜃2𝜃1)|Γ1,Γ2,Γ3.ByΣ1∩Σ2⊆ dom(Γ),LemmaC.9,LemmaC.10
      and Theorem5.3, there exists    𝜃𝑝 such that𝑃2⊢𝜃𝑝𝜃′𝑄(6). Let  𝜃′′ =𝜃𝑝𝜃′. Our goal follows
      from(𝜃 =𝜃′′𝜃2𝜃1)|Γ1,Γ2,Γ3,𝐶 =𝜃′′𝐶′, and𝑃2⊢𝜃′′(𝑃′2∪𝑄).
Case
                               Q-Abs
                               𝑃|𝜃Γ,𝑥 :𝐴⊢𝑀 :𝐶(1)   𝑃⊢𝜃Γ⪯𝑌
                                        𝑃|𝜃Γ⊢𝜆𝑥.𝑀 :𝐴→𝑌𝐶
      Take a fresh variable𝛼 and let𝜃1=𝜃[𝐴/𝛼]. By(1), we have   𝑃|𝜃1(Γ,𝑥 :𝛼)⊢𝑀 :𝐶(2). By
      the IHon(2), we have       Γ,𝑥 :𝛼⊢𝑀 :𝐶′⊣𝜃′,𝑃′,Σ(3)andthere exists 𝜃′′ such that𝐶 =𝜃′′𝐶′,
      𝑃⊢𝜃′′𝑃′, and(𝜃1=𝜃′′𝜃′)|Γ,𝑥:𝛼. Let𝑄 = leq(𝜃′Γ|Σ,𝜙)∪un(𝜃′(𝑥 :𝛼)|Σc) By Q-AbsW and(3),
      taking a fresh variable𝜙, we have
                                Γ⊢𝜆𝑥.𝑀 :𝜃′𝛼→𝜙𝐶′⊣𝜃′,𝑃′∪𝑄,Σ\𝑥
      With𝜃2 =𝜃′′[𝑌/𝜙], we have(𝜃 =𝜃2𝜃′)|Γ,𝑥:𝛼. By𝑃⊢𝜃Γ⪯𝑌, LemmaC.9, LemmaC.10,
      andTheorem5.3,thereexists    𝜃𝑝 suchthat𝑃⊢𝜃𝑝𝜃2𝑄.Let𝜃3=𝜃𝑝𝜃2.Ourgoalfollowsfrom
      (𝜃 =𝜃3𝜃′)|Γ,𝑥:𝛼,𝐴→𝑌𝐶 =𝜃3(𝜃′𝛼→𝜙𝐶′) and𝑃⊢𝜃3(𝑃′∪𝑄).
Case
             Q-App
             𝑃|𝜃(Γ1,Γ)⊢𝑉 :𝐵→𝑌𝐶(1)   𝑃|𝜃(Γ2,Γ)⊢𝑊 :𝐵(2)   𝑃⊢𝜃Γ⪯•
                                        𝑃|𝜃(Γ1,Γ2,Γ)⊢𝑉𝑊 :𝐶
      By the IH on(1), we have       Γ1,Γ⊢𝑉 :𝐴′⊣𝜃1,𝑃1,Σ1(3)and there exists  𝜃′1 such that𝐵→𝑌
      𝐶 =𝜃′1𝐴′,𝑃⊢𝜃′1𝑃1, and(𝜃 =𝜃′1𝜃1)|Γ1,Γ. Let𝜃 =𝜃′𝜃′1𝜃1 where𝜃′ only substitutes type


Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 47

Soundly Handling Linearity                                                                                                                                               54:47

      variables only appearing inΓ2. By the IH on(2), we have   𝜃1(Γ2,Γ)⊢𝑊  :𝐵′⊣𝜃2,𝑃2,Σ2(4)
      and there exists𝜃′2 such that𝐵 =𝜃′2𝐵′,𝑃⊢𝜃′2𝑃2, and(𝜃′𝜃′1 =𝜃′2𝜃2)|Γ2,Γ(5). Take fresh
      variables𝛼,𝜇,𝜙. By𝐵→𝑌𝐶 =𝜃′1𝐴′, the unification𝜃2𝐴′∼𝐵′→𝜙𝛼 !𝜇 :𝜃3 succeeds. By
      LemmaC.4and(5), there exists           𝜃4 such that𝜃4𝜃3(𝜃2𝐴′) =𝜃4𝜃3(𝐵′→𝜙𝛼 !𝜇) =𝐵→𝑌𝐶.
      Let𝑃3 =𝜃3(𝜃2𝑃1∪𝑃2) and𝑄 = un(𝜃3𝜃2𝜃1(Γ1,Γ2,Γ)|Σ1∩Σ2). By Q-AppW,(3),(4)and context
      weakening,wehaveΓ1,Γ2,Γ⊢𝑉𝑊 :𝜃3(𝛼 !𝜇)⊣𝜃3𝜃2𝜃1,𝑃3∪𝑄,Σ1∪Σ2.With𝜃′′=𝜃4𝜃′2𝜃′1,we
      have(𝜃 =𝜃′′𝜃3𝜃2𝜃1)|Γ1,Γ2,Γ.ByΣ1∩Σ2⊆ dom(Γ),𝑃⊢𝜃Γ⪯•,LemmaC.10,andTheorem5.3,
      we have𝑃⊢𝜃𝑝𝜃′′𝑄. Let𝜃5 =𝜃𝑝𝜃′′. Our goal follows from𝐶 =𝜃5𝜃3(𝛼 !𝜇),𝑃⊢𝜃5(𝑃3∪𝑄)
      and(𝜃 =𝜃5𝜃3𝜃2𝜃1)|Γ1,Γ2,Γ.
Case
                                       Q-Return
                                             𝑃|𝜃Γ⊢𝑉 :𝐴(1)
                                       𝑃|𝜃Γ⊢ return𝑉 :𝐴!{𝑅}
      Our goal follows from the IH on(1).
Case
                            Q-Do
                            𝑃|𝜃Γ⊢𝑉 :𝐴ℓ(1)   𝑃⊢{ℓ :𝐴ℓ↠𝑌𝐵ℓ}⩽𝑅
                                        𝑃|𝜃Γ⊢ doℓ𝑉 :𝐵ℓ !{𝐸}
      Similartopreviouscases.OurgoalfollowsfromtheIHon(1),LemmaC.4,andTheorem5.3.
Case
                Q-Seq
                𝑃|𝜃(Γ1,Γ)⊢𝑀 :𝐴!{𝑅1}(1)   𝑃|𝜃(Γ2,Γ),𝑥 :𝐴⊢𝑁 :𝐵!{𝑅2}(2)
                     𝑃⊢𝑅1⩽𝑅   𝑃⊢𝑅2⩽𝑅   𝑃⊢𝜃Γ2⪯𝑅1   𝑃⊢𝜃Γ⪯•
                               𝑃|𝜃(Γ1,Γ2,Γ)⊢ let𝑥←𝑀 in𝑁 :𝐵!{𝑅}
      By the IH on(1), we have       Γ1,Γ⊢𝑀 :𝐴′!{𝑅′1}⊣𝜃1,𝑃1,Σ1(4)and there exists  𝜃′1 such that
      𝐴!{𝑅1} =𝜃′1(𝐴′!{𝑅′1}),𝑃⊢𝜃′1𝑃1, and(𝜃 =𝜃′1𝜃1)|Γ1,Γ. Let𝜃 =𝜃′𝜃′1𝜃1 where𝜃′ substitutes
      type variables only appearing inΓ2. By(2), we have   𝑃|𝜃′𝜃′1𝜃1(Γ2,Γ,𝑥 :𝐴′)⊢𝑁 :𝐵!{𝑅2}(3).
      By the IH on(3), we have   𝜃1(Γ2,Γ,𝑥 :𝐴′)⊢𝑁 :𝐵′!{𝑅′2}⊣𝜃2,𝑃2,Σ2(5)and there exists  𝜃′2
      suchthat𝐵!{𝑅2}=𝜃′2(𝐵′!{𝑅′2}),𝑃⊢𝜃′2𝑃2 and(𝜃′𝜃′1=𝜃′2𝜃2)|Γ2,Γ.Takeafreshvariable𝜇.Let
      𝑄 = un(𝜃2𝜃1(Γ1,Γ2,Γ)|Σ1∩Σ2)∪un(𝜃2(𝑥 :𝐴)|Σc2)∪leq(𝜃2𝜃1(Γ1,Γ2,Γ)|Σ2,𝜃2𝑅1)∪sub(𝜃2𝑅1,𝜇)∪
      sub(𝑅2,𝜇).ByQ-SeqW,(4),(5),andcontextweakening,wehave        Γ1,Γ2,Γ⊢ let𝑥←𝑀 in𝑁 :
      𝐵′!{𝑅2}⊣𝜃2𝜃1,𝜃2𝑃1∪𝑃2∪𝑄,Σ1∪(Σ2\𝑥).With𝜃′′=[𝑅/𝜇]𝜃′2𝜃′1,wehave(𝜃 =𝜃′′𝜃2𝜃1)|Γ1,Γ2,Γ.
      ByΣ1∩Σ2⊆ dom(Γ),𝑃⊢𝜃Γ⪯•,LemmaC.9,              Γ2=Γ|Σ2,𝑃⊢𝜃Γ2⪯𝑅1,𝑃⊢𝑅1⩽𝑅,𝑃⊢𝑅2⩽𝑅,
      LemmaC.10andTheorem5.3,thereexists           𝜃𝑝 suchthat𝑃⊢𝜃𝑝𝜃′′𝑄.Let𝜃3=𝜃𝑝𝜃′′.Ourgoal
      follows from𝐵!{𝑅2}=𝜃3(𝐵′!{𝜇}),𝑃⊢𝜃3(𝜃2𝑃1∪𝑃2), and(𝜃 =𝜃3𝜃2𝜃1)|Γ1,Γ2,Γ.
Case
                Q-Handle
                𝑃|𝜃(Γ1,Γ)⊢𝐻 :𝐴!{𝑅1}⇒𝐷(1)   𝑃|𝜃(Γ2,Γ)⊢𝑀 :𝐴!{𝑅}(2)
                                      𝑃⊢𝜃Γ⪯•   𝑃⊢𝑅⩽𝑅1(3)
                                𝑃|𝜃(Γ1,Γ2,Γ)⊢ handle𝑀 with𝐻 :𝐷
      ByasimilarprooftotheQ-Appcase,ourgoalfollowsfromtheIHson(1)and(2),LemmaC.10,
      Theorem5.3,andLemmaC.4.Theonlydifferenceisthesubtypingconstraint                           sub(𝜃3𝑅,𝜃3𝜃2𝑅1)
      used byQ-HandleW, which follows from(3).


                              Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 48

54:48                                                                            Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

Case
             Q-Handler
                           𝐶 =𝐴!{(ℓ𝑖 :𝐴ℓ𝑖↠𝑌𝑖𝐵𝑖)𝑖;𝑅1}   𝐷 =𝐵!{𝑅2}
              𝑃|𝜃Γ,𝑥 :𝐴⊢𝑀 :𝐷(1) [𝑃|𝜃Γ,𝑝𝑖 :𝐴𝑖,𝑟𝑖 :𝐵𝑖→𝑌𝑖𝐷⊢𝑁𝑖 :𝐷]𝑖(2)
                            𝑃⊢𝜃Γ⪯•   𝑃⊢𝑅1⩽𝑅2   𝑃⊢𝑅1⊥{ℓ𝑖}𝑖
                      𝑃|𝜃Γ⊢{return𝑥↦→𝑀}⊎{ℓ𝑖𝑝𝑖𝑟𝑖↦→𝑁𝑖}𝑛𝑖=1 :𝐶⇒𝐷
      Thetypingruleforhandleristhemostcomplicatedone,butthereisactuallynothingnew
      oftheproofcomparedtopreviouscasesforotherrules.Foreachtypingderivationonthe
      handler clauses, we do a similar proof to the Q-Abscase. Take fresh variables𝛼,𝜙𝑖, and𝜇.
      First, by(1)we have   𝑃|𝜃[𝐴/𝛼](Γ,𝑥 :𝛼)⊢𝑀 :𝐷. By the IH on it, we haveΓ,𝑥 :𝛼⊢𝑀 :
      𝐷′⊣𝜃0,𝑃0,Σ0(3)andthereexists 𝜃′0 suchthat𝐷 =𝜃′0𝐷′,𝑃⊢𝜃′0𝑃0 and(𝜃[𝐴/𝛼] =𝜃′0𝜃0)|Γ,𝑥:𝛼.
      Let𝜃𝑎0 =𝜃0 and𝜃𝑏0 =𝜃′0. We have(𝜃𝑏0𝜃𝑎0 =𝜃)|Γ.
      By the typing derivation on the first handler clause in(2), we have   𝑃|𝜃𝑏0[𝑌1/𝜙1]𝜃𝑎0(Γ,𝑝1 :
      𝐴1,𝑟1 :𝐵1→𝜙1𝐷)⊢𝑁1 :𝐷. By the IH on it, we have𝜃𝑎0(Γ,𝑝1 :𝐴1,𝑟1 :𝐵1→𝜙1𝐷)⊢𝑁1 :
      𝐷1⊣𝜃1,𝑃1,Σ1 and𝜃′1 such that𝐷 =𝜃′1𝐷1,𝑃⊢𝜃′1𝑃1 and(𝜃𝑏0[𝑌1/𝜙1] =𝜃′1𝜃1)|(Γ,𝑝1:𝐴1,𝑟1:𝐵1→𝜙1𝐷).
      By𝐷 =𝜃′1𝐷1,theunification𝐷1∼𝜃′1𝜃1𝐷′ :𝜃𝑥1 succeeds.ByLemmaC.4,thereexists       𝜃𝑦1 such
      that𝜃𝑦1𝐷1=𝐷. Set𝜃𝑎1 =𝜃𝑥𝜃1𝜃𝑎0 and𝜃𝑏1 =𝜃′1𝜃𝑦1. We have(𝜃𝑏1𝜃𝑎1 =𝜃)|Γ.
      Repeatingtheabove processforevery𝑖 from 2to𝑛,wehave𝜃𝑎𝑖−1(Γ,𝑝𝑖 :𝐴𝑖,𝑟𝑖 :𝐵𝑖→𝜙𝑖𝐷)⊢
      𝑁𝑖 :𝐷𝑖⊣𝜃𝑖,𝑃𝑖,Σ𝑖 (4)and (𝜃𝑏𝑖𝜃𝑎𝑖 =𝜃)|Γ. Let
                   𝐶′=𝜃𝑎𝑛(𝛼 !{(ℓ𝑖 :𝐴ℓ𝑖↠𝜙𝑖𝐵ℓ𝑖)𝑖 ;𝜇})
                    𝐵′!{𝑅}=𝜃𝑎𝑛𝐷′
                    Σ=(Σ0\{𝑥})∪(∪𝑛𝑖=1(Σ𝑖\{𝑝𝑖,𝑟𝑖}))
                    𝑃′=(∪𝑛𝑖=0𝜃𝑎𝑛𝑃𝑖)∪un(𝜃𝑎𝑛Γ|Σ)∪sub(𝜇,𝑅)∪lack(𝜇,{ℓ𝑖}𝑖)
                   𝑄′= un(𝜃𝑎𝑛(𝑥 :𝛼)|Σc0)∪(∪𝑛𝑖=1un(𝜃𝑎𝑛(𝑝𝑖 :𝐴ℓ𝑖,𝑟𝑖 :𝐵ℓ𝑖→𝜙𝑖𝐷)))
      By Q-HandlerW,(3), and(4), we have             Γ⊢{return𝑥↦→𝑀}⊎{ℓ𝑖𝑝𝑖𝑟𝑖↦→𝑁𝑖}𝑛𝑖=1 :𝐶′⇒
      𝜃𝑎𝑛𝐷′⊣𝜃𝑎𝑛,𝑃′∪𝑄′,Σ.With𝜃′=𝜃𝑏𝑛[𝑅1/𝜇],wehave(𝜃 =𝜃′𝜃𝑎𝑛)|Γ.ByLemmaC.9,LemmaC.10,
      and Theorem5.3there exists    𝜃𝑝 such that𝑃⊢𝜃𝑝𝜃′(𝑃∪𝑄). Let𝜃′′=𝜃𝑝𝜃′. Our goal follows
      from𝐶⇒𝐷 =𝜃′′(𝐶′⇒𝜃𝑎𝑛𝐷′),𝑃⊢𝜃′′(𝑃∪𝑄), and(𝜃 =𝜃′′𝜃𝑎𝑛)|Γ.
                                                                                                          □

C.4   Correctness of Constraint Solving
  LemmaC.11.  Ifurow(𝐾1,𝐾2) returns(𝐾′1,𝐾′2,𝜃),then J𝐾1⩽𝐾2K𝑠𝑎𝑡 = J𝐾′1⩽𝐾′2K𝑠𝑎𝑡𝜃;ifitfails,then
𝐾1⩽𝐾2 is not satisfiable.
  Proof. ByLemmaC.3,thesubstitution       𝜃 returnedbyurow(𝐾1,𝐾2) istheprincipalunifierthat
unifiesthelinearity typesofthesame labelsin𝐾1 and𝐾2,whichis anecessaryconditionfor any
solution of𝐾1⩽𝐾2.                            □
  Lemma C.12.  If factorise(𝑃)=𝑄, then J𝑃K𝑠𝑎𝑡 = J𝑄K𝑠𝑎𝑡.
  Proof. By Theorem5.3, we have    𝑃⊢𝑄 and𝑄⊢𝑃. For any𝜃∈ J𝑃K𝑠𝑎𝑡, we have·⊢𝜃𝑃. By the
closurepropertyofTheoremC.1,wehave    𝜃𝑃⊢𝜃𝑄.BythetransitivityofTheoremC.1,wehave
·⊢𝜃𝑄, which implies𝜃∈  J𝑄K𝑠𝑎𝑡. Symmetrically, for any𝜃∈  J𝑄K𝑠𝑎𝑡, we can prove𝜃∈  J𝑃K𝑠𝑎𝑡.
Finally, we have J𝑃K𝑠𝑎𝑡 = J𝑄K𝑠𝑎𝑡.                       □
  Theorem 5.4 (Correctness of constraint solving).  For any constraint set𝑃 generated by the
type inference of Q◦eff, solve(𝑃) always terminates.


Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 49

Soundly Handling Linearity                                                                                                                                               54:49

    • If it fails, then𝑃 is not satisfiable.
    • If it returns(𝜃,𝑄), then𝑃 is satisfiable and J𝑃K𝑠𝑎𝑡 = J𝑄K𝑠𝑎𝑡𝜃.

   Proof. Thetermination oftrlinandfactoriseisobvious. Itmaybe notveryobviousthat srow
always terminatessince thesrow(𝜃,𝑃,𝑄) moves thesolved predicates in𝑃 to theset ofunsolved
constraints𝑄 in some cases. Note that only row subtyping constraints of forms𝐾1⩽𝐾2;𝜇2 and
𝐾1;𝜇1⩽𝐾2;𝜇2 mightrequire resolving previouslysolved constraintsbecause theysubstitute row
variables. In both cases, when set(𝐾′1) ⊈ set(𝐾′2), we substitute𝜇2 with(𝐾′1\𝐾′2);𝜇. Notice that
the number of labels used in the whole predicate set is finite, and the srow fails when there are
duplicatedlabelsin thesamerow,which impliesthatthiskind ofsubstitutionterminates.Finally,
we can conclude thatsrow terminates.
   For the correctness, the idea is to show that every step preserves solutions. We first show
srow preserves solutions by proving a lemma that if srow(𝜃,𝑃,𝑄) returns(𝜃′𝜃,𝑄′), then we have
J𝑃∪𝑄K𝑠𝑎𝑡 =  J𝑄′K𝑠𝑎𝑡𝜃′; if it fails, then𝑃∪𝑄 is not satisfiable. We prove by induction on the
definition of srow.
Case
                                            srow(𝜃,𝑃,·)= return(𝜃,𝑃)

       Our goal follows from J𝑃K𝑠𝑎𝑡 = J𝑃K𝑠𝑎𝑡𝜄.
Case
                            srow(𝜃,𝑃,(𝜏1⪯𝜏2,𝑄))= srow(𝜃,(𝑃,𝜏1⪯𝜏2),𝑄)(1)

       Our goal follows from the IH on(1)and           J𝑃∪(𝜏1⪯𝜏2,𝑄)K𝑠𝑎𝑡 = J(𝑃,𝜏1⪯𝜏2)∪𝑄K𝑠𝑎𝑡.
Case
                                      srow(𝜃,𝑃,(𝐾1⩽𝐾2,𝑄))=
                                          let(𝐾′1,𝐾′2,𝜃′)= urow(𝐾1,𝐾2)(2)
                                          assert set(𝐾′1)⊆ set(𝐾′2)(3)
                                          srow(𝜃′𝜃,𝜃′𝑃,𝜃′𝑄)(1)
       Obviously(3) fails when𝐾′1⩽𝐾′2 is not satisfiable. Our goal follows from the IH on(1),
       LemmaC.11on(2), and                                   J𝑃∪(𝐾1⩽𝐾2,𝑄)K𝑠𝑎𝑡 = J𝜃′𝑃∪𝜃′𝑄K𝑠𝑎𝑡𝜃′.
Case
                                      srow(𝜃,𝑃,(𝐾1;𝜇⩽𝐾2;𝜇,𝑄))=
                                          let(𝐾′1,𝐾′2,𝜃′)= urow(𝐾1,𝐾2)(2)
                                          assert set(𝐾′1)⊆ set(𝐾′2)(3)
                                          srow(𝜃′𝜃,𝜃′𝑃,𝜃′𝑄)(1)

       Obviously(3) fails when𝐾′1⩽𝐾′2 is not satisfiable. Our goal follows from the IH on(1),
       LemmaC.11on(2), and                                   J𝑃∪(𝐾1;𝜇⩽𝐾2;𝜇,𝑄)K𝑠𝑎𝑡 = J𝜃′𝑃∪𝜃′𝑄K𝑠𝑎𝑡𝜃′.
Case
                                    srow(𝜃,𝑃,(𝐾1;𝜇⩽𝐾2,𝑄))=
                                       let(𝐾′1,𝐾′2,𝜃′)= urow(𝐾1,𝐾2)(2)
                                       assert set(𝐾′1)⊆ set(𝐾′2)(3)
                                       srow(𝜃′𝜃,(𝜃′𝑃,𝜇⩽(𝐾′2\𝐾′1)),𝜃′𝑄)(1)

       Obviously(3) fails when𝐾′1⩽𝐾′2 is not satisfiable. Our goal follows from the IH on(1),
       LemmaC.11on(2), and                                   J𝑃∪(𝐾1;𝜇⩽𝐾2,𝑄)K𝑠𝑎𝑡 = J(𝜃′𝑃,𝜇⩽(𝐾′2\𝐾′1))∪𝜃′𝑄K𝑠𝑎𝑡𝜃′.


                                 Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 50

54:50                                                                            Wenhao Tang, Daniel Hillerström, Sam Lindley, and J. Garrett Morris

Case
                                     srow(𝜃,𝑃,(𝐾1⩽𝐾2;𝜇2,𝑄))=
                                        let(𝐾′1,𝐾′2,𝜃′)= urow(𝐾1,𝐾2)
                                        assume fresh𝜇
                                        if set(𝐾′1)⊆ set(𝐾′2)
                                        thensrow (𝜃′𝜃,𝜃′𝑃,𝜃′𝑄)(1)
                                        else let𝜃′′=[((𝐾′1\𝐾′2);𝜇)/𝜇2]𝜃′
                                             srow(𝜃′′𝜃,·,𝜃′′(𝑄,𝑃))(2)
       For the true branch, our goal follows from the IH on(2), LemmaC.3, and
                                J𝑃∪(𝐾1⩽𝐾2;𝜇2,𝑄)K𝑠𝑎𝑡 = J𝜃′𝑃∪𝜃′𝑄K𝑠𝑎𝑡𝜃′
       For the false branch, our goal follows from the IH on(2), LemmaC.3, and

                                J𝑃∪(𝐾1⩽𝐾2;𝜇2,𝑄)K𝑠𝑎𝑡 = J𝜃′′(𝑄,𝑃)K𝑠𝑎𝑡𝜃′′
       Both of the above equations follow from the fact that in order to solve𝐾1⩽𝐾2;𝜇2, it is
       necessarytounifythelinearitytypesofthesamelabelsin𝐾1 and𝐾2,andinstantiate𝜇2 with
       at least other labels only in𝐾1 (no instantiation needed when set(𝐾′1)⊆ set(𝐾′2)).
Case
                            srow(𝜃,𝑃,(𝐾1;𝜇1⩽𝐾2;𝜇2,𝑄))=
                               let(𝐾′1,𝐾′2,𝜃′)= urow(𝐾1,𝐾2)
                               assume fresh𝜇
                               if set(𝐾′1)⊆ set(𝐾′2)
                               thensrow (𝜃′𝜃,(𝜃′𝑃,𝜇1⩽(𝐾′2\𝐾′1);𝜇2),𝜃′𝑄)(1)
                               else let𝜃′′=[((𝐾′1\𝐾′2);𝜇)/𝜇2]𝜃′
                                     srow(𝜃′′𝜃,𝜇1⩽(𝐾′2\𝐾′1);𝜇,𝜃′′(𝑄,𝑃))(2)
       For the true branch of if, our goal follows from the IH on(1), LemmaC.3, and

                 J𝑃∪(𝐾1;𝜇1⩽𝐾2;𝜇2,𝑄)K𝑠𝑎𝑡 = J(𝜃′𝑃,𝜇1⩽(𝐾′2\𝐾′1);𝜇2)∪𝜃′𝑄)K𝑠𝑎𝑡𝜃′
       For the false branch of if, our goal follows from the IH on(1), LemmaC.3, and
                  J𝑃∪(𝐾1;𝜇1⩽𝐾2;𝜇2,𝑄)K𝑠𝑎𝑡 = J(𝜇1⩽(𝐾′2\𝐾′1);𝜇)∪𝜃′′(𝑄,𝑃)K𝑠𝑎𝑡𝜃′′

       Both ofthe abovetwo equations follow from thefact thatin order tosolve𝐾1;𝜇1⩽𝐾2;𝜇2, it
       isnecessarytounifythelinearitytypesofthesamelabelsin𝐾1 and𝐾2,andinstantiate𝜇2
       with at least other labels only in𝐾1 (no instantiation needed when set(𝐾′1)⊆ set(𝐾′2)).
Case
                                        srow(𝜃,𝑃,(𝐾⊥L,𝑄))=
                                           assert dom(𝐾)∩L=∅(2)
                                           srow(𝜃,𝑃,𝑄)(1)

       Obviously(2)fails when        𝐾⊥L is not satisfiable. Our goal follows the IH on(1).
Case
                                        srow(𝜃,𝑃,(𝐾 ;𝜇⊥L,𝑄))=
                                           assert dom(𝐾)∩L=∅(2)
                                           srow(𝜃,(𝑃,𝜇⊥L),𝑄)(1)
       Obviously(2)fails when        𝐾 ;𝜇⊥L is not satisfiable. Our goal follows the IH on(1).


Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.

## PDF page 51

Soundly Handling Linearity                                                                                                                                               54:51

   Then,wecanconclude thatifsrow𝜄,·,𝑃 returns(𝜃,𝑄),thenwehave J𝑃K𝑠𝑎𝑡 = J𝑄K𝑠𝑎𝑡𝜃;ifitfails,
then𝑃 is not satisfiable. Moreover, in𝑄, row subtyping constraints are all in the forms of𝜇⩽𝐾
and𝜇⩽𝐾 ;𝜇′.
   By LemmaC.12, we have                         J𝑄K𝑠𝑎𝑡 = Jfactorise(𝑄)K𝑠𝑎𝑡. Moreover, in factorise(𝑄), linearity con-
straints areall inatomic forms, whichmeans theyare onlybetween type variables,row variables,
and linearity types𝑌.
   Let𝑄′′ = factorise(𝑄). For trlin(·,𝑄′′)=𝑄′, we want to show that J𝑄′K𝑠𝑎𝑡 = Jfactorise(𝑄)K𝑠𝑎𝑡.
Notice that trlin(·,𝑄′′) essentially computes the transitive closure of the linearity constraints in
𝑄′′.Obviouslywehave J𝑄′′K𝑠𝑎𝑡⊆ J𝑄′K𝑠𝑎𝑡.Fortheotherdirection,weneedtoshowthatforany
{𝜏1⪯𝜏2,𝜏2⪯𝜏3}⊆𝑄′ and𝜃∈ J𝜏1⪯𝜏2,𝜏2⪯𝜏3K𝑠𝑎𝑡, we have·⊢𝜃(𝜏1⪯𝜏3). Notice that the type
inferenceofQ◦eff onlygenerateslinearityconstraintsofformsΓ⪯𝜏,whichmeansrowsonlyappear
on the RHS. Thus, after factorisation,𝜃𝜏2 can only be𝐴 or𝑌. The·⊢𝜃(𝜏1⪯𝜏3) follows from a
straightforward case analysis on𝜃𝜏2.
   Finally,if◦⪯•∈𝑄′,then𝑄′ isobviouslynotsatisfiable.Otherwise,wehaveatrivialsolutionby
substituting all row variables with the empty row·, value variables with(), and linearity variables
with•.Wealsohave J𝑃K𝑠𝑎𝑡 = J𝑄′K𝑠𝑎𝑡𝜃,whichfurtherimpliesthetrivialsolutionof𝑄′ alsogivesa
solution of𝑃. These results also hold for𝑄 since J𝑄′K𝑠𝑎𝑡 = Jfactorise(𝑄)K𝑠𝑎𝑡 = J𝑄K𝑠𝑎𝑡.   □

Received 2023-07-11; accepted 2023-11-07













































                                 Proc. ACM Program. Lang., Vol. 8, No. POPL, Article 54. Publication date: January 2024.
