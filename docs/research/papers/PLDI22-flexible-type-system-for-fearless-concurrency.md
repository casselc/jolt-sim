# A Flexible Type System for Fearless Concurrency

**Machine conversion:** extracted from the adjacent PDF with `pypdf`; page boundaries are retained, while equations, figures, and multi-column layout may not round-trip faithfully. Consult the PDF for authoritative pagination and notation.

## PDF page 1

       A Flexible Type System for Fearless Concurrency

                   Mae Milano                                            Julia        Turcotti                             Andrew C. Myers
      University of California, Berkeley                    University         of   California,                  Berkeley   Cornell University
                Berkeley, CA, USA                                      Berkeley,              CA,       USA                   Ithaca,NY,USA
            mpmilano@berkeley.edu                                 turcotti.julia@gmail.com                                andru@cs.cornell.edu
Abstract                                                                               1   Introduction
This       paper    proposes       a  new   type  system           for     concurrent      pro-The promise of a language with lightweight, safe concur-
grams,          allowing threads       to exchange             complex  object   graphsrency has long beenattractive. Such a languagewould stat-
without          risking         destructive  data     races.       While       this     goal       isicallyensurefreedomfromdestructiveraces,avoidingthe
shared  by    a  rich      history  of    past       work,      existing           solutions               ei-cost of synchronization except when concurrent threads ex-
ther       rely     on    strictly           enforced  heap        invariants                that       prohibitplicitly communicate. Our goal is to obtain this łfearless
natural           programming                  patterns             or    demand            pervasive  annota-concurrencyž[35]foralanguagewithpervasivemutability
tions        even    for     simple           programming                  tasks.         As  a  result,         pastat its core. Broadly speaking, past efforts to design such a
systems            cannot           express     intuitively     simple          code    without             un-languagefallintothreecamps.Some,likeRust[36],simplify
natural              rewrites            or     substantial                     annotation                     burdens.          Our       workreasoning by severely limiting the shape of representable
avoids       these        pitfalls           through        a  novel   type  system           that       pro-datastructuresÐmakingtheimplementationofcommondata
vides        sound         reasoning             about     separation               in   the     heap        whilestructures,likethedoublylinkedlist,unapproachablebynon-
remaining              flexible enough to support a                             wide range of desir-experts1.Inothers[17,26,28,29,33,46],harshlimitations
able      heap       manipulations.                    This      new   sweet   spot   is   attainedonaliasing causedatastructuretraversal andmanipulation
by    enforcing       a  heap        domination                  invariant              similarly              to    priorto involve significant mutation of the object graph even for
work,      but     tempering        it   by    allowing        complex  exceptions                thatsimple computationsÐfor example, in these systems remov-
add     little       annotation               burden.      Our      results        include:           (1)     codeingthetailofarecursivelylinearsinglylinkedlistincursa
examples           showing       that      common            data      structure  manipula-writetoeachlistnodetraversed.Existingapproachesthat
tions       which        are  difficult or            impossible to           express in      prioravoideitherpitfallrequiresignificantprogrammerannota-
work      are  natural           and      direct   in    our      system,            (2)    a  formal           prooftiontoexplainaliasinginformationdirectlytothecompiler
of   correctness          demonstrating                      that      well-typed  programs             can-[8,    12,    13].
not     encounter                destructive  data       races        at   run      time, and      (3)     anThis paperintroduces anew typesystem forfearless con-
efficient type checker implemented in                            Gallina and OCaml.    currency. As in prior work, the goal is to statically ensure
                                                                                       thatat anypointduring execution,thepart ofthe heap ac-
CCS     Concepts:                •  Software  and       its engineering         →     Con-cessible to a given threadÐwhat we call its reservationÐis
current       programming                      languages;                    Concurrent      program-disjoint from the reservations of all other threads. Inspired
ming          structures.                                                              by Tofte and Talpin [49], the object graph is partitioned
Keywords:     concurrency, type  systems,              aliasing                        into a set of regions, a purely compile-time construct which
                                                                                       groupsobjectsthatenterorleaveathread’sreservationasa
ACM         Reference                   Format:                                        unit.Neitherregionsnorreservationsarefixed;bothcanand
Mae Milano, Julia Turcotti, and Andrew C. Myers. 2022. A Flexible                      shouldchangeduringprogramexecutiontoreflectthemove-
Type  System           for     Fearless             Concurrency. In    Proceedings        of   the     43rdmentofobjectsamongthreads.Asinpriorwork[17,26,28],
ACM      SIGPLAN              International                   Conference       on    Programming                   Languageour type system supports both inter-and intra-region refer-
Design          and      Implementation                       (PLDI         ’22),       June       13ś17,          2022,        San      Diego,ences;intra-regionreferencesmayfreelylinkobjectswithin
CA,      USA.        ACM,       New   York,      NY, USA,        16    pages.           https://doi.org/10.the same region, allowing programmers to easily form arbi-
1145/3519939.3523443                                                                   traryobjectgraphs,whileinter-regionreferencesaretracked
                                                                                       by the type system and stored in appropriately annotated
                                                                                       isolated fields. By tracking this information, the type sys-
                                                                                       tem ensures that threads do not reference objects outside
                                                                                       their reservations. Unlike in prior work, this guarantee is
This work is licensed under a Creative Commons Attribution 4.0 Interna-                provided without requiring that isolated field references sat-
tional License.                                                                        isfya globaldominationinvariant atalltimesÐandwithout
                                                                                       requiringanyannotationsfromtheprogrammerexceptat
PLDI ’22, June 13ś17, 2022, San Diego, CA, USA                                         function boundaries.
© 2022 Copyright held by the owner/author(s).
ACM ISBN 978-1-4503-9265-5/22/06.                                                      1That doubly linked lists pose a real challenge is affirmed by top search
https://doi.org/10.1145/3519939.3523443                                                results for łhow to write a doubly linked list in Rustž [18,      41].


                                                                                458                                             Most up-to-date version: 02/14/2025

## PDF page 2

PLDI ’22, June 13ś17, 2022, San Diego, CA, USA                                                   Mae       Milano,            Julia       Turcotti,             and      Andrew            C.    Myers

   For rich object graphs, this increased expressive power                        struct sll_node {                   struct dll_node {
poses a challenge: to soundly approximate reservations at                           iso payload : data;                 iso payload : data;
run time, the type system must accurately determine to                              iso next : sll_node?;               next : dll_node;
whichregioneachaccessedobjectbelongs,andfurther,which                             }                                     prev : dll_node;
regionsarecontainedwithinthereservationatruntime.This                                                                 }
determinationismadeparticularlydifficultbecausereserva-                           struct sll {                        struct dll {
tions can grow and shrink dynamically as threads exchange                           iso hd : sll_node?;                 iso hd : dll_node?
portions of the object graph.                                                     }                                   }
   Our key insight begins by leveraging domination prop-
erties in the heap to force isolated field references to dom-               Figure1.Asinglylinkedlistandcirculardoublylinkedlist.
inate [43] their reachable subgraphs, yielding a notion of                  Fields are not nullable by default; the? annotation on types
encapsulation similar to prior work [29]. We then temper                    indicates thatthis field storesa łmaybež ofthe appropriate
thisstrongand restrictiveglobaldominationproperty with                      type, effectively making it nullable. Theiso keyword en-
anewfocusmechanisminspiredbyVault[23]:objectsmay                            forces transitive domination.
becometemporarilyfocused,causingtheirisolatedfields’tar-
gets to be explicitly tracked by the type system, and thereby
exempted from domination requirements. This weaker heap                        Our primary contributions are summarized as follows:
invariant,whichwecalltempereddomination,allowsgreater                            • A new invariant, tempered domination, which al-
flexibilitywithlowerannotationoverheadthaninanyprior                               lows statically tracked violations of the traditional
language. It improves on traditional affine-reference lan-                         globaldominationinvariantwithafocusconstruct[23].
guages by enforcing a tree of regions rather than a tree of                      • Aregion-basedtype systemcapableoftracking the
objects,allowingmorenaturalstructuresthanarepossiblein                             relationships between regions, without requiring an-
Rust[36].Ontheotherhand,thefocusmechanismskirtsthe                                 notations or explicit scopes to do so.
needtomaintainaglobaldominationinvariantatalltimes,                              • Aformalpaperproofofsoundnessthatshowswell
avoiding the destructive read or swap primitives needed                            typed programs have no destructive data races.
in existing tree-of-regions languages such as L42, LaCasa,                       • A new primitive to dynamically discover detailed
Mezzo, and others [3,    4,  17,    26,    28,    46].                             region graphsand expose them to static analysis.
   Twomorenovelfeaturesenhanceexpressivenessofour                                • Expressive function types capable of statically de-
language: (1) a new primitiveifdisconnected that dynami-                           scribing complex heap manipulations.
callydeterminesifaregioncanbesafelysplitatruntime,and                            • A type checker implemented in OCaml, and verified
(2) expressive function types whose parameters and results                         inCoq,capableofcheckingourmostcomplexexam-
need not be dominators.                                                            ples in seconds.
   Our type system can naturally represent many mutable
datastructuresfoundinpriorwork,withoutrelyingonheavy                        2   A Tail of Two Lists
annotations, unnatural representations, destructive reads,
or swap primitives. For example, our type system admits                     Webeginbyexplainingkeyconceptsofthenewtypesystem,
straightforwardrepresentationsofboth doublylinkedlists                      using two linked list implementations as guiding examples.
with shared ownership and singly linked lists with recur-                   2.1   Reservations and Tempered Domination
sivelylinearownership,improvingonamotivatingexample
formuchpriorwork[17,26,28]inthefirstcaseandoffering                         Ourlanguagepreventsdestructiveracesbydividingtherun-
the celebrated mechanisms of uniqueness and borrowing                       timeheapintoasetofdisjointreservations,oneperthread.A
popularized by Rust [36] in the second.                                     thread’sreservationistheportionoftheheapthatitmayac-
   This work brings together the benefits of two traditional                cessatanyparticular time. Bykeepingreservationsdisjoint,
lines of prior work without adding significant complexity.                  andensuringnothreadattemptstoaccessanobjectoutside
Forexample,bothsinglyand doublylinked listssupport tra-                     itsreservation,weguaranteefreedomfromdestructiveraces;
versal,removal,andinsertionfunctionswhichlookmuchas                         in other words, it isreservation-safe.
theywouldinanintroductoryprogrammingclass,requiring                            As the program executes and threads exchange objects,
little annotation or run-time overhead. All these operations                reservations must shift accordingly. When a thread sends
enjoyfearlessconcurrency:addedelementsmayhavebeen                           an object to another thread, its reservation must lose access
received from remote threads and removed elements may                       to that object’s reachable subgraph, which includes the ob-
be immediately sent to a new thread, all without additional                 ject itself as well as all objects transitively reachable from
dynamic concurrency control mechanisms or the risk of                       it.Conversely,whena thread   receivesanobject, itsreserva-
destructiveraces.Noexistinglanguagewithfearlessconcur-                      tion expands; the thread gains access to the object and its
rencycanas naturallyexpressthisrange ofdatastructures.                      reachable subgraph.


                                                                       459

## PDF page 3

A FlexibleType Systemfor FearlessConcurrency                                                                                              PLDI’22, June 13ś17,2022, San Diego, CA, USA

    def remove_tail(n: sll_node) : data? {                                    the final elementfrom a singly linked list, returninga domi-
         letsome(next) = n.nextin {                                           nating reference. The caller ofremove_tail may leverage
             if (is_none(next.next)) {                                        theseparationbetweentheremovednodeandlistparameter
                 n.next =none;                                                to, forexample,safely sendthe removed nodeto a distinct
                 some(next.payload)                                           thread without losing access to the list itself2.
             }else{ remove_tail(next) }                                          In implementing this function, this code first attempts
         }else {none }                                                        to dereference the argument’snext field, storing it in the
    }                                                                         variablenext. It then checks ifnext is the tail of the list,
Figure2.Removingthefinal elementofa singly linked list.                       removing it from the list and returning itspayload if so.
Notethatboththereturnedresultandlistremainmutable,                            Otherwise, itrecursively callsremove_tailon thenextel-
and the returned result is no longer encapsulated by the                      ement.Note something surprising: thiscode violates global
linkedlist,unlikeinpriorwork(e.g.,[26,          46]).Notealsothat             domination! Both thenext variable and the list parameter
thisfunctionreturnsnoneonlistsofsizeone,asitwouldbe                           holdreferencestosll_node’siso-declared(hencedominat-
impossible to separate the list from its tail in this case.                   ing)next field.
                                                                                 In fact, performing a non-destructive traversal of this list
                                                                              whileenforcingglobaldominationoverallnext fieldsisim-
                                                                              possible; all such traversals will require at least a łcursorž
                                                                              variable pointing at the current position in the list, which
   Thekeychallengeisensuringreservationsafetyatcom-                           will necessarily alias thenext pointer of that position’s pre-
piletime.Consider,forexample,alinkedlistcontainingsome                        decessor.
abstract payload typedata, used as a messaging queue to                          Ourlanguagethusdoesnotenforceatraditionallystrict
communicatewithotherthreads.Twopossibledefinitionsof                          global domination invariant; rather than forcing references
such a list are found in figure    1. While these code examples               stored iniso fields to always be transitively dominating, we
aresimple,theyexposetwokeychallenges:theabilitytorep-                         temperthisrequirementwithatype-levelmechanismthat
resentcyclicdatastructures,andtheabilitytotraversetrees                       explicitly tracks the targets of some references, requiring
ofunique references. In orderto safelyadd objectsreceived                     transitive domination for exactly those references iniso
fromotherthreadstoeitherlist,ortoremoveobjectsfrom                            fields which are not explicitly tracked by the type system.
eitherlisttosendtootherthreads,thecompilermustreason                         Wecall this weakened property tempered domination.
about reachability and aliasing, both between the list nodes                     Tempereddominationgeneralizespriorworkthatrelies
and their payloads, and between the list nodes themselves.                    on global domination [26ś28,        46]. Crucially, tracking, and
   Tomakethisreasoningtractableforboththecompilerand                          indeed the decision of which references to track, occurs
theprogrammer,oursystemreliesontransitivelydominating                         withoutexplicituserinstructionÐrequiringannotationsonly
references: references which lie on all paths from the root of                atfunctionboundaries.Whenwedescribethemechanismsin
the object graph to all objects transitively reachable from                   placeforpreservingtempereddominationintheremainder
thatreference.Thesereferencesaredominators[43]ofentire                        of this paper, we referto transitively dominating references
subgraphs;therefore,athreadwhichlosesaccesstosucha                            as simply dominating references.
reference,for examplebysendingit toanotherthread, also
losesaccess toits reachablesubgraph. Hence,marking only                       2.2   Aliasing and Reachable Subgraphs
this singlereference asinvalid maintains reservationsafety.                  While an otherwiseuntrackediso field in some objecto is
Weuse thekeywordiso(łisolatedž)todescribefieldswhich                          guaranteed to contain a dominating reference, it is not in
containtransitivelydominatingreferences,therebyexposing                       generalguaranteedthatoitselfisuniquelyreferenced;infact
knowledge of domination in the object graph to the type                       many aliases of any given object may be accessible at any
system. Looking back to the example code in figure              1, we         particular time.When checkinganiso field dereference,it
see thatiso appears onthe list payloads inboth linked list                    isthereforenecessarytoensuretheprogramhasnotalready
implementations, and that it also appears on the list spine                   accessed thatsame object’siso field from some other alias.
itself inthe caseof thesingly linkedlist, indicatingthat the                     Forexample,considerthecirculardoublylinkedlistim-
only wayto initiallyreach asingly linkedlist nodeis from                      plementationfromfigure        1.Figure   3 illustratestwopossible
its predecessor.                                                              instances ofthis list; notethat alist of size1 is represented
   Ifallisofieldscontaintransitivelydominatingreferences,                     bya singlelistnode whoseprevandnextpointersare self-
apropertywecallglobaldomination,thenwecansafelyrea-                           references.
son aboutseparation in the heapwhen accessing such data
structures. But global domination is too strong a property                    2Thisisincontrasttoexistingsystems[46],inwhichsimilarcodewould
to be enforced at all times. For example, consider the code                   stillassociatethetailwiththelistevenafterreturningit,foreverentwining
in figure  2, which, given the head node, attempts to remove                  the fateofthe tail withthatofthe list.


                                                                        460

## PDF page 4

PLDI ’22, June 13ś17, 2022, San Diego, CA, USA                                                     Mae       Milano,            Julia       Turcotti,             and      Andrew            C.    Myers



               dll                         dll                                    def remove_tail(l : dll) : data? {
                  head                       head                                      letsome(hd) = l.hdin {
                         payload                    payload                                let tail = hd.prev;
        prev dll_node                   dll_node                                           tail.prev.next = hd;

           prev                       prev  next                                           hd.prev = tail.prev;
                   next                                                                    //to  ensure   disjointness   for  if−disconnected
             dll_node    payload
                                                                                           tail.next = tail; tail.prev = tail;
                next                                                                       ifdisconnected(tail,hd) {
                                                                                               l.hd =some (hd);  //l.hd invalid  at  branch  start
Figure 3. Two circular doubly linked lists, of size 2 and of                                   some(tail.payload) }
size 1.                                                                                    else {
                                                                                               l.hd =none;
                                                                                               some (hd.payload) }}
    def remove_tail(l : dll) : data? {                                                 else {none } }
         letsome(hd) = l.hdin {
             let tail = hd.prev;                                              Figure5.Retrievingthetailofacirculardoubly linkedlist
             tail.prev.next = hd;                                             (fixed)
             hd.prev = tail.prev;
             some (tail.payload)
         }else {none } }                                                      size of the list a priori, an if-statement alone would not be
                                                                              enoughtoallowthetypesystemtomakethatsamededuction.
Figure4.Retrievingthetailofacirculardoubly linked list                           To solve this, our work introduces a new primitive con-
(broken)                                                                      ditional form calledifdisconnected. This conditional per-
                                                                              forms a run-time check to establish if its arguments’ reach-
                                                                              ablesubgraphsarenon-intersecting;iftheyare,itentersthe
   As with the singly linked list, we might wish to remove                    firstbranch,andotherwiseenterstheelsebranch.Wesee
thetailfromthiscirculardoublylinkedlist;ourfirstattempt                       this construct in use in figure 5. Here, the existing logic
to do so is in figure 4. This code takes advantage of the                     is enhanced by replacing what was once a plain return
circular structure of this list, jumping straight to the end via              oftail.payload to a call toifdisconnected, returning
hd’sprevpointer.Afterpatchingthelistpointerstoexclude                         tail.payloadwhenithasbeensuccessfullydisconnected
the tail node, we return theiso-annotatedtail.payload                         insize2+cases,andreturningthehead’spayloadinthesize
reference.Asinthesinglylinkedlist,thisfunctionhasbeen                         1 case. Note that the programmer must manually repoint
declared toreturn onlydominating references,so thecaller                      thetail’snextandprevfieldsawayfromtheremainderof
ofremove_tail should be able to use this payload freely                       thelist,as disconnectionisasymmetricproperty: itisjust
without regard for its former attachment to the list3.                        as essential thattail cannot reachhead as it is thathead
   Sadly, this code contains an error. When passed a list of                  cannot reachtail. Additionally, the type system does not
size 2, this code functions as expected; the tail node is ex-                 knowwhich ofhdandtailconnecttol.hd,necessitating
cised from the list, removing all external references to the                  thatl.hdbe reassigned even in thethenbranch.
payloadexcepttheonereturnedfromthefunctionitself.But                             Despiteitsdynamicnature,therun-timecomplexityforif
when passed a list of size 1, the code behaves differently:                   disconnected isquite reasonableÐinthisexample, itwould
hdandhd.prevarein factthesame object(fig3), rendering                         only require reading the metadata of a single object. No-
ineffective the assignments that attempt to remove it from                    tably, the newifdisconnected mechanism cannot be ap-
thelist.Here,thereturnedpayloadactuallyisn’tadominat-                         proximated by mechanisms in similar prior work.
ingreference; thelistretainsthe sameshapeasbefore,and                         3   A Small Language with Dynamic
stillprovidesaccesstothereturnedpayload.Whilethepro-
grammercouldeliminatethiserrorbyswappingthepayload                                 Reservation Safety
with adummy value, thatfix isundesirable. Itwould satisfy                    We formalizeour workas asmallcore concurrentlanguage
the type checker, but not remove the bugÐreplacing a static                  with mutable objects, passed by reference.
error with a dynamic one when the dummy value is later
unexpectedly encountered.                                                     3.1   Syntax
   The correct fix for figure 4 is to add code which handles                  The syntax of the language can be found in figure 6. Be-
listsisoflengthone,perhapsbyaddinganif-statement.But                          yond standard imperative constructs, structures, and a first-
whilethismaybesufficientfortheprogrammertoknowthe                             classłmaybežconstruct,twonovelfeaturesstandout:theif
                                                                              disconnectedprimitiveandblockingmessagingprimitives
3This is in contrast to work in the vein of extended Balloon Types [46].      send-       𝜏 andrecv-       𝜏.



                                                                        461

## PDF page 5

A FlexibleType Systemfor FearlessConcurrency                                                                                              PLDI’22, June 13ś17,2022, San Diego, CA, USA


            (functiondefinition)fdef ::=def fn :𝜏fn{𝑒}
                          (program)𝑝 ::=fdef; 𝑝 | 𝑒
                        (expression)𝑒 ::=𝑙 | 𝑥 | 𝑒;𝑒 | 𝑒.𝑓  | 𝑒.𝑓  =𝑒 | 𝑥 =𝑒 | fn( 𝑥,...,𝑥    ) | 𝑒 ⊕𝑒 |new𝜏 |declare𝑥 :𝜏in {𝑒}
                                          |if ( 𝑒) {𝑒}else {𝑒} |while ( 𝑒) {𝑒} |send-𝜏( 𝑒) |recv-𝜏() | if disconnected( 𝑥,𝑥 ) {𝑒}else {𝑒}
                                          |none𝜏 |some( 𝑒) |letsome( 𝑥) = ( 𝑒)in {𝑒}else {𝑒}
              (evaluation context)𝐸[] ::= [];𝑒 | 𝑒.𝑓  = [] | 𝑥 = [] | [] ⊕𝑒 | 𝑙 ⊕ [] |if([]){ 𝑒}else {𝑒}
                                          |send-𝜏([]) | some([]) | letsome( 𝑥) = ([]) in {𝑒}else {𝑒}

                                                        Figure 6. Corelanguage syntax

3.2   Semantics                                                                aliases. For example, in the doubly linked list example from
Figure  7 presents selected rules of the small-step semantics                  figure 3,thetypesystemmustrecognizethathdandhd.tail
forasinglethread;explicitconcurrencyconstructsareadded                         may be aliases, and sohd.payload andhd.tail.payload
in section   7. The only values are locations. The small-step                  may be as well. It must also ensure that operations which
configuration is largely standard, including a storeℎ map-                     remove anobject from thecurrent thread’s reservationalso
ping locations to objects, a stack𝑠 mapping variable names                     render all aliasesof this object statically unusable.
to locations, and an expression 𝑒 which is evaluated with                      4.1   Regions
reference to the storeand stack.
   The final element of the configuration, 𝑑, is not stan-                    Totrackaliasing,thetypesystemusesregions[49]todescribe
dard; thiscontextmodels the(dynamic) reservationand is                         disjointsubgraphsoftheoverallobjectgraph,staticallyasso-
consulted whenever a location is used. For example, rules                      ciatingeachreferencewitharegioninwhichitstargetlives.
E2 -Variable-Ref-Stepand           E5a  -Final-Reference-Stepś                 By ensuring that all possible references to the same object
Variable check𝑑 to confine variable and field reads to loca-                   are labeledwith thesame region,the typesystem canuse a
tions within the reservation, and       E8 - Assign-Var-Step and               setofregionsasaconservativecompile-timeapproximation
E7a  -Final-Assignment-StepśVariablecheck𝑑 toconfine                           to a run-time reservation. When an object is lost from the
variable and field assignments similarly. If any expression                    reservation,thetypesysteminvalidatesallreferencestothat
attemptstoreadorwritelocationsthatarenotinthecurrent                           objectbypreventingtheuseofanyreferencesthattargetits
reservation, no rules apply and the program cannot step;                       region.Effectively,thetypesystemtreatseachregionasan
theprogram intentionallyłgetsstuck.žBy augmentingthe                           affineresourcewhichisconsumedbyreservation-shrinking
small-stepsemantics withthispervasivedynamicreserva-                           operations on its constituent objects.
tioncheck,wecanbeguaranteedthatÐprovidedreservations                              Forexample,figure       8 circlesregions in the doublylinked
arealwaysdisjointÐnoprogramcandestructivelyrace.In                             list instances of figure    3. Entire list spines lie in the same
section   4 we introduce a type system for which we have                       region,whichcausesthestaticerrorfrominoriginalattempt:
provenprogressandpreservation(section              6)withrespectto             bothhd andhd.next are in the same region, so the type
this small-step system, in turn proving that no well-typed                     system always treats themas potential aliases.
programs get stuckÐand therefore, no reservation checks
ever fail. Hence, a real implementation has no need to track                   4.2   Focus
the reservation or to performsuch checks at run time.                         Thetempereddominationinvariantrequiresthatuntracked
   In contrast to the erasable dynamic reservation checks,                     iso fields must dominate their reachable subgraph, while
theifdisconnected mechanism has unavoidable run-time                           trackediso fields are unrestricted. Over the course of pro-
cost.Itmustensurethattheobjectgraphsreachablefromits                           gram execution,untrackedisofields maybecome tracked,
arguments are non-intersecting, as specified in rules            E15a          and trackediso fields may in turn become untracked. To
and  E15b. A naive implementation of this check would be                       allowtrackedisofieldstobesafelyuntracked,ourtypesys-
unacceptably inefficient, as it would require a complete tra-                  temensuresthatalltrackedisofieldshavestaticallyknown
versal ofthe objectgraphs reachablefrom botharguments;                         target regions.To avoid unsoundness, we must ensure that
a moreefficient implementation is described in section             5.2.        potential aliases do not have conflicting static tracking in-
                                                                               formation. To this end, we introduce a focus mechanism,
4   Type System                                                               which allows variables to become tracked only in regions in
                                                                              which no other variables are currently tracked. Since vari-
Thetypesystemisbuiltaroundmaintainingtempereddomi-                             ables from distinct regions are necessarilydistinct, this en-
nation:untrackedisofieldsalwaysdominatetheirreachable                          suresnoisofieldeverbecomestrackedviamultiple aliases.
subgraph.Toestablishthisinvariant,thetypesystemmustbe                         This non-aliasing behavior is formalized as invariant              I6 in
able to determine when two different isolated fields may be                    the appendix.


                                                                         462

## PDF page 6

PLDI ’22, June 13ś17, 2022, San Diego, CA, USA                                                               Mae       Milano,            Julia       Turcotti,             and      Andrew            C.    Myers


                                                                     ( 𝑑,ℎ,𝑠,𝑒  ) eval−−−→( 𝑑,ℎ,𝑠,𝑒  )


                E1  - Evaluation-Context-Step                 E2  - Variable-Ref-Step                                E5a  - Final-Reference-StepśVariable
                   ( 𝑑,ℎ,𝑠,𝑒  ) eval−−−→( 𝑑′, ℎ′,𝑠 ′,𝑒 ′)        𝑠( 𝑥) =𝑙        𝑙 ∈𝑑                                𝑠( 𝑥) =𝑙        𝑙,𝑙 𝑓  ∈𝑑        ℎ𝑣( 𝑙)[𝑓] =𝑙𝑓
               ( 𝑑,ℎ,𝑠,𝐸   [𝑒]) eval−−−→( 𝑑′, ℎ′,𝑠 ′,𝐸 [𝑒′])  ( 𝑑,ℎ,𝑠,𝑥  ) eval−−−→( 𝑑,ℎ,𝑠,𝑙  )                            ( 𝑑,ℎ,𝑠,𝑥.𝑓    ) eval−−−→( 𝑑,ℎ,𝑠,𝑙    𝑓)


     E7a  - Final-Assignment-StepśVariable                                              E8  - Assign-Var-Step
                              𝑠( 𝑥) =𝑙        𝑙,𝑙 𝑓  ∈𝑑                                                         𝑙 ∈𝑑
    ( 𝑑,ℎ ⊎ ( 𝑙 ↦→ ( 𝜏,𝑣 )),𝑠,𝑥.𝑓    =𝑙𝑓) eval−−−→( 𝑑,ℎ ⊎ ( 𝑙 ↦→ ( 𝜏,𝑣 [𝑓 ↦→𝑙𝑓])),𝑠,𝑙  𝑓)( 𝑑,ℎ,𝑠  ⊎ ( 𝑥 ↦→𝑙old),𝑥  =𝑙) eval−−−→( 𝑑,ℎ,𝑠  ⊎ ( 𝑥 ↦→𝑙),𝑙 )


                                                                                       E15a  - If-Disconnected-Success-Step
     E11  - Declare-Var-Step                                                            tracked-set(𝑟  ·⟨⟩;𝑥 :𝑟 𝜏;·;ℎ,𝑠 ) ∩tracked-set(𝑟  ·⟨⟩;𝑦 :𝑟 𝜏;·;ℎ,𝑠 ) = ∅

     ( 𝑑,ℎ,𝑠, declare𝑥 :𝜏in {𝑒}) eval−−−→( 𝑑,ℎ,𝑠  [𝑥  ↦→ ⊥],𝑒 )                        ( 𝑑,ℎ,𝑠, if disconnected( 𝑥,𝑦 ) {𝑒succ}else {𝑒fail}) eval−−−→( 𝑑,ℎ,𝑠,𝑒   succ)


                                              E15b   - If-Disconnected-Failure-Step
                                               tracked-set(𝑟  ·⟨⟩;𝑥 :𝑟 𝜏;·;ℎ;𝑠) ∩tracked-set(𝑟  ·⟨⟩;𝑦 :𝑟 𝜏;·;ℎ;𝑠) ≠ ∅
                                              ( 𝑑,ℎ,𝑠, if disconnected( 𝑥,𝑦 ) {𝑒succ}else {𝑒fail}) eval−−−→( 𝑑,ℎ,𝑠,𝑒   fail)

                         Figure 7.Selected small-step rules. Full small-step rules can be found in the appendix.



           dll                                   dll                                     Thevariabletypingcontext Γ isalargelystandardbind-
                                                                                      ingenvironmentrecordingthetypeandregionofvariables;
               head                                  head                             the heap context H is interpreted as a set of tracking con-
 prev                   payload                               payload                 textsoftheform𝑟◦⟨𝑋⟩.Eachtrackingcontextbeginswitha
        dll_node                              dll_node                                region capability𝑟, the complete set of which serves to con-

     prev                                  prev    next                               servatively approximate the dynamic reservation.Were our
                next    payload                                                       tracking contexts to contain only this𝑟, they would match
        dll_node                                                                      thetrackingcontextofLaCasa[28,29];indeed,severalrulesÐ

             next                                                                     thosewhichintroduce,check,andeliminateregionsÐrequire
                                                                                      only this level of detail.
Figure 8. Two circular doubly linked lists, with regions
drawn.                                                                                4.4   Expression Typing with Tracking Contexts
                                                                                      In addition to the top-level structure describing the set of
 (type)𝜏 ::=  Struct | Struct?                H ::=  𝑟◦⟨𝑋⟩,  H | ·                    tracked regions in H, the full tracking context𝑟◦⟨𝑥◦[𝑓↣
          ◦ ::=  † | ·                         𝑋 ::=  𝑥◦[𝐹], 𝑋  | ·                   𝑟,...   ]...  ⟩ includes a description 𝑥◦[𝑓↣ 𝑟,...   ] of the re-
                                                                                      gion structure discovered by our focus mechanism: namely,
          Γ ::=  𝑥 :𝑟 𝜏,  Γ | ·                𝐹 ::=   𝑓↣𝑟, 𝐹  | ·                    trackedvariables𝑥 intheregion𝑟,whereeach 𝑓↣𝑟 maps
                                                                                      trackedfields 𝑓 totheir targetregions𝑟.Both variablesand
      Figure 9.Surface context definitions forH and Γ                                 regions also includea pinning annotation describedby the
                                                                                      metavariable ◦. Pinning a region (resp. variable) prevents
4.3   Typing Judgments and Static Contexts                                            any new variables (resp.iso fields) from becoming tracked
                                                                                      in that region (resp. variable). Pinning is necessary when
Thetyping judgmenthas theformH;Γ ⊢𝑒 :𝑟 𝜏 ⊣ H;Γ,fol-                                   the typing context might only have partial static informa-
lowingthegrammarinfigure9.Itassociatesanexpression                                    tion about the heap, and allows the typesystem to express
𝑒 with a type𝜏 and a region𝑟. Recall from section 4.1 that                            abstraction overH.
regions are treated linearly in the type system; the trans-                              Figure 10 shows how the context H is used to type ex-
formation of linear contexts is represented not by context                            pressions. First, note that H prevents the type system from
splitting[51]but byłinputž(before⊢)and łoutputž (after⊣)                              confusing aniso field with potential aliases; as shown in
contexts. The difference between input and output contexts                            T5 - Isolated-Field-Reference,                    noiso field of some vari-
captures𝑒’s effects on the type state. To be well-formed, all                         able maybe accessed unless both that variableand itsfield
staticcontexts(Γ,H,𝑋,𝐹)cannotcontainduplicatebindings                                 arealreadypresentinthetrackingcontext,andtherecorded
for regions, variables, or fields.                                                    region targeted by that field is itself present inH.


                                                                               463

## PDF page 7

A FlexibleType Systemfor FearlessConcurrency                                                                                              PLDI’22, June 13ś17,2022, San Diego, CA, USA


                                                                      H;Γ ⊢𝑒 :𝑟 𝜏 ⊣ H;Γ


      T2  - Variable-Ref                     T3  - Seqence                                                      T4  - Non-Isolated-Field-Reference
     𝑥 :𝑟 𝜏 ∈ Γ       𝑟 ∈ regs(H)           H;Γ ⊢𝑒 :𝑟 𝜏 ⊣ H′;Γ′        H′;Γ′ ⊢𝑒′ :𝑟′𝜏′ ⊣ H′′;Γ′′                H;Γ ⊢𝑒 :𝑟 𝜏 ⊣ H′;Γ′        · 𝑓 𝜏𝑓  ∈ fields( 𝜏)
         H;Γ ⊢𝑥 :𝑟 𝜏 ⊣ H;Γ                                H;Γ ⊢𝑒;𝑒′ :𝑟′𝜏′ ⊣ H′′;Γ′′                                       H;Γ ⊢𝑒.𝑓  :𝑟 𝜏𝑓 ⊣ H′;Γ


  T5  - Isolated-Field-Reference                                                   T6  - Non-Isolated-Field-Assignment
 iso 𝑓 𝜏𝑓  ∈ fields( 𝜏)        𝑟◦⟨𝑥◦′[𝑓↣𝑟𝑓,𝐹 ],𝑋 ⟩ ∈ H        𝑟◦′′𝑓  ⟨𝑋′⟩ ∈ H     H;Γ ⊢𝑒𝑓 :𝑟 𝜏𝑓 ⊣ H′;Γ′        H′;Γ′ ⊢𝑒 :𝑟 𝜏 ⊣ H′′;Γ′′        · 𝑓 𝜏𝑓  ∈ fields( 𝜏)
                 H;𝑥 :𝑟 𝜏, Γ ⊢𝑥.𝑓  :𝑟𝑓 𝜏𝑓 ⊣ H;𝑥 :𝑟 𝜏, Γ                                                  H;Γ ⊢𝑒.𝑓  =𝑒𝑓 :𝑟 𝜏𝑓 ⊣ H′′;Γ′′


        T7  - Isolated-Field-Assignment                                                                 T8  - Assign-Var
       H;Γ ⊢𝑒𝑓 :𝑟𝑓 𝜏𝑓 ⊣ H′,𝑟 ◦⟨𝑥◦′[𝑓↣𝑟old,𝐹 ],𝑋 ⟩;𝑥 :𝑟 𝜏, Γ′    iso 𝑓 𝜏𝑓  ∈ fields( 𝜏)                 H;Γ ⊢𝑒 :𝑟 𝜏 ⊣ H′;Γ′,𝑥  :𝑟old 𝜏        𝑥 ∉ vars(H  ′)
                  H;Γ ⊢𝑥.𝑓  =𝑒𝑓 :𝑟𝑓 𝜏𝑓 ⊣ H′,𝑟 ◦⟨𝑥◦′[𝑓↣𝑟𝑓,𝐹 ],𝑋 ⟩;𝑥 :𝑟 𝜏, Γ′                                     H;Γ ⊢𝑥 =𝑒 :𝑟 𝜏 ⊣ H′;Γ′,𝑥  :𝑟 𝜏


                          T10  - New-Loc                                     T11  - Declare-Var
                         H;Γ ⊢new-𝜏 :𝑟 𝜏 ⊣ H,𝑟  ·⟨⟩;Γ                        H;Γ,𝑥  : ⊥𝜏 ⊢𝑒 :𝑟 𝜏′ ⊣ H′;Γ′,𝑥  :𝑟out 𝜏        𝑥 ∉ vars(H  ′)
                                                                                     H;Γ ⊢declare𝑥 :𝜏in {𝑒} :𝑟 𝜏′ ⊣ H′;Γ′


   T13  - If-Statement                                                                                    T14  - While-Loop
   H;Γ ⊢𝑒𝑏 :𝑟𝑏bool ⊣ H′;Γ′        H′;Γ′ ⊢𝑒𝑡 :𝑟 𝜏 ⊣ H′′;Γ′′        H′;Γ′ ⊢𝑒𝑓 :𝑟 𝜏 ⊣ H′′;Γ′′                H;Γ ⊢𝑒𝑏 :𝑟𝑏bool ⊣ H;Γ        H;Γ ⊢𝑒 :𝑟 𝜏 ⊣ H;Γ
                          H;Γ ⊢if ( 𝑒𝑏) {𝑒𝑡}else {𝑒𝑓} :𝑟 𝜏 ⊣ H′′;Γ′′                                          H;Γ ⊢while ( 𝑒𝑏) {𝑒} :𝑟𝑢unit ⊣𝑟·𝑢⟨⟩, H;Γ


                     T15  - If-Disconnected
                     𝑟·𝑥⟨⟩,𝑟  ·𝑦⟨⟩, H;𝑥 :𝑟𝑥 𝜏𝑥,𝑦  :𝑟𝑦 𝜏𝑦, Γ;· ⊢𝑒succ :𝑟out 𝜏out ⊣ H′;Γ′       𝑟·⟨⟩, H;𝑥 :𝑟 𝜏𝑥,𝑦  :𝑟 𝜏𝑦, Γ;· ⊢𝑒fail :𝑟out 𝜏out ⊣ H′;Γ′
                                𝑟·⟨⟩, H;𝑥 :𝑟 𝜏𝑥,𝑦  :𝑟 𝜏𝑦, Γ ⊢if disconnected ( 𝑥,𝑦 )in {𝑒succ}else {𝑒fail} :𝑟out 𝜏out ⊣ H′;Γ′


T16   - Send                                                                     TS1   - Virtual-Transformation-Structural
                                            T17  - Receive                                                             vir
      H;Γ ⊢𝑒 :𝑟𝑒 𝜏 ⊣ H′,𝑟  ·𝑒⟨⟩;Γ′                                               H;Γ ⊢𝑒 :𝑟 𝜏 ⊣ H′;Γ′        (H  ′;Γ′)  ⇝ (   ¯H′; ¯Γ′)        𝑟 ∈ regs(   ¯H′)
H;Γ ⊢send-𝜏( 𝑒) :𝑟unit ⊣ H′,𝑟  ·⟨⟩;Γ′       H;Γ ⊢recv-𝜏()  :𝑟 𝜏 ⊣ H,𝑟  ·⟨⟩;Γ                            H;Γ ⊢𝑒 :𝑟 𝜏 ⊣  ¯H′; ¯Γ′

                             Figure 10.Selected typing rules. Full typing rules can befound in the appendix.

   This tracking context also allowsiso fields to be freely                              Anotableabsenceinfigure            10 isanyrulewhichintroduces
reassigned,evenifdoingsowouldcreatecyclesintheobject                                  or eliminates elements in a tracking context. This role is
graph. This is safe because tempered domination requires                              played by     TS1   - Virtual-Transformation-Structural,
domination only on untrackediso fields; fields explicitly                            whichallowsinvariant-preservingvirtualtransformationsto
mentioned in H are exempt. Consider, for example, type-                               beperformed on static contexts.
checkingx.f = ewith             T7  -Isolated-Field-Assignment.
Thisruleplacesnorestrictionson𝑒 beyondensuringthatit
type-checks, and that 𝑥.𝑓  remains valid and tracked after                            4.5   Virtual Transformations
checking𝑒.The rulesimply updates𝑥.𝑓 ’stracking informa-                               Rule  TS1   servestoexposearichlanguageofvirtualtransfor-
tion in the output context.                                                           mationsspecifiedbythe          V  rulesinfigure      11.Theserulesma-
   We sometimes require the tracking context of a region                              nipulateH tomatchtherequirementsofthesyntax-directed
to be empty, containing no tracked variables and thus no                             T  rules.Forexample,considertheprogram𝑥 =new-𝜏() ;𝑥.𝑓 .
trackedfields.Astempereddominationweakensglobaldom-                                  Aftertype-checkingthefirstexpressioninthissequencevia
ination only for tracked isolatedfields,empty tracking con-                          T10    and   T8  - Assign-Var, we could obtain the following
texts prove that everyiso field withinthat regioncontains a                           typing judgment:
dominatingreference,andthusissafetotransmitbetween                                                ·;𝑥 : ⊥𝜏 ⊢𝑥 =new-𝜏()  :𝑟 𝜏 ⊣𝑟·⟨⟩;𝑥 :𝑟 𝜏
threads via    T16   - Send (which requires an empty context)
and  T17   - Receive (which assumes one).                                             Ifwethenmovedontochecking𝑥.𝑓 ,rulesT3-Seqenceand
   Notethatrulessuchas           T10  -New-Loc,whichaddregions,                      T5   - Isolated-Field-Reference would seem natural yet be
variables, or fields to existing contexts, enforce freshness                          inapplicable.Thisisbecausetheoutputcontextofnew-𝜏() ’s
because well-formed contexts cannotduplicatebindings.                                 derivation has the form𝑟·⟨⟩;𝑥 : 𝑟 𝜏, but the field reference
                                                                                      rule requires a contextlike𝑟·⟨𝑥·[𝑓↣𝑟𝑓]⟩,𝑟  ·𝑓 ⟨⟩;𝑥 :𝑟 𝜏.


                                                                               464

## PDF page 8

PLDI ’22, June 13ś17, 2022, San Diego, CA, USA                                                     Mae       Milano,            Julia       Turcotti,             and      Andrew            C.    Myers


                                                                 (H ;Γ)  vir⇝ ( H;Γ)

 V1  - Focus                                        V2 - Unfocus                             V3  - Explore
 (𝑟  ·⟨⟩, H;𝑥 :𝑟 𝜏, Γ)  vir⇝ (𝑟  ·⟨𝑥·[]⟩, H;𝑥 :𝑟 𝜏, Γ)(𝑟  ◦⟨𝑥·[],𝑋 ⟩, H;Γ)  vir⇝ (𝑟  ◦⟨𝑋⟩, H;Γ)(𝑟  ◦⟨𝑥·[𝐹], 𝑋⟩, H;Γ)  vir⇝ (𝑟  ◦⟨𝑥·[𝑓↣𝑟𝑓, 𝐹],𝑋 ⟩,𝑟  ·𝑓 ⟨⟩, H;Γ)


   V4  - Retract                                                    V5 - Attach
   (𝑟  ◦⟨𝑥◦′[𝑓↣𝑟𝑓, 𝐹],𝑋 ⟩,𝑟  ·𝑓 ⟨⟩, H;Γ)  vir⇝ (𝑟  ◦⟨𝑥◦′[𝐹], 𝑋⟩, H;Γ)(𝑟  ·1⟨𝑋1⟩, 𝑟◦2⟨𝑋2⟩, H;Γ)  vir⇝ (𝑟  ◦2⟨𝑋1[𝑟1 ↦→𝑟2], 𝑋2[𝑟1 ↦→𝑟2]⟩, H[𝑟1 ↦→𝑟2];Γ[𝑟1 ↦→𝑟2])

                                                 Figure 11.Virtual Transformation Rules.

   Note that these contexts describe the same heap! Asx.f                     unify its branches which appear equivalent at the time of
is a dominatingreference,it isequally correctto represent                     checkingtheconditional,butarenotequallyabletocheck
it as explicitly tracked or as untracked. This needed shift                   subsequentexpressions.Werewetoemployanoraclewhich
betweendifferentbutequivalentrepresentationsofthesame                         canproduceaprecisetargetunificationcontext,typecheck-
heap is performed by ruleTS1. In this particularcase, trans-                  ingagain becomesefficiently, greedilydecidable.In theab-
formationsV1-FocusandV3-Exploreachievethedesired                              sence of such an oracle, backtracking search must be per-
transformation:                                                               formedonthechoiceofaunificationtarget.Wenotehowever
    (𝑟  ·⟨⟩;_)    vir⇝ (𝑟  ·⟨𝑥·[]⟩;_)    vir⇝ (𝑟  ·⟨𝑥·[𝑓↣𝑟𝑓]⟩,𝑟  ·            that, due to our choice to limit typeableiso field accesses
                                                           𝑓 ⟨⟩;_)            toonlyfieldsofcurrentlydeclaredvariables,thenumberof
Note here that V1 - Focus requires the target region to be                    H contextsreachablebyvirtualtransformationisbounded
emptyandunpinned,ensuringwedonotinadvertentlyfocus                            abovebythenumberofvariablescurrentlyinscope.Thus,
two aliases of the same object. Equally, V3 - Explore relies                  even a naive search suffices to obtain completeness, at the
on well-formedness of its contexts to ensure no fields are                    cost ofrun time exponential inthe number of variablesand
exploredtwice.Conversely,therulesV4-RetractandV2-                             the length of the longest function. Heuristics for speeding
Unfocuscan be usedto transform aheap context in which                         up search are briefly discussed in section5.1.
anexplicitlytrackedvariablepointstoanemptyregioninto
onewhereboththatvariablebecomesuntrackedanditsdes-
tinationregionisdropped,invalidatinganyotherreferences                        4.7   Abstraction by Framing and Pinning
to theretracted target’s region andrestoring domination in                    Figure12introducesruleTS2-Framing-Structural,                                    which
the process.                                                                  exposesoursecondnon-syntaxdirectedtypingrule:framing.

4.6   Decidability of Virtual Transformations                                 Framingallowsourtypingrulestoignoreirrelevantportions
                                                                              of the static contexts H and Γ, letting the type checker tem-
Anastutereadermaynotethat,unliketheinitialtypingrules                         poraryframeawayregionsinH,variablesinΓ,andportions
in figure 10,    TS1 - Virtual-Transformation-Structural                      of tracking contexts.
is not syntax-directed. We present a decision procedure for                      Whileframingisastandardfeaturewhenreasoningabout
typecheckingwithvirtualtransformations.Itrunsincommon-                        separation [45], its inclusion in our system is complicated
case polynomial and worst-case exponential time.                              by tempereddomination. Naivelyallowing variableswithin
   In general, given a source (H , Γ) and a target (H  ′, Γ′),                trackingcontextstobeframedawaywouldseeminglyviolate
theproblemofdiscoveringa vir⇝pathbetweenthetwoiseffi-                         tempered domination; it would take an invariant-satisfying
cientlydecidablebyagreedyapproach.Effectively,thetype                         contextwithexplicitdominationexceptions,andreplaceit
checker can defer applying any virtual transformation until                   withoneinwhichnorecordofthoseexceptionsappearsÐ
itencountersarule whose type constraints arenotsatisfied                      without making corresponding changes to the heap.
by the current heap context; in the absence of branching                         The pinning annotation (4.4) solves this problem. Pinning
constructs, such a deferral can never affect typability. De-                  elementsofatrackingcontextindicatesthatthoseelements
ciding whether application of TS1 sufficiently transforms                     havepartialinformation:thatis,it                    cannotbeassumedthat
typingcontextstoallowsyntax-directedapplicationssuch                          untrackedisofieldsofapinnedregionorvariablecontain
asT7-Isolated-Field-AssignmentandT16-Sendreduces                              dominatingreferences.Byleveragingpinning,wecanadmit
to this path finding problem.                                                 framingruleswhichweakenelementsoftrackingcontexts
   Unfortunately, unification between disparate branches,                     without introducing unsoundness. Since a pinned context
suchasinT13-If-StatementandT15-If-Disconnected,                               mayonlybeobtainedbyframing,anypinnedcontextalways
cannot rely solely on a greedy approach. To satisfy the con-                  approximatessomefullyunpinnedcontext,whichavoidsthe
ditional typing rules, unification must occur at the time of                  need to further temper tempered domination in our proofs
checkingtheconditionalÐandtheremaybemanywaysto                                of progress and preservation.


                                                                        465

## PDF page 9

A FlexibleType Systemfor FearlessConcurrency                                                                                              PLDI’22, June 13ś17,2022, San Diego, CA, USA



                    TS2  - Framing-Structural
                                                      frm(𝑒)                    frm(𝑒)
                    H;Γ ⊢𝑒 :𝑟 𝜏 ⊣ H′;Γ′        (H ;Γ)  ⇝𝐴    (   ¯H; ¯Γ)        (H  ′;Γ′)⇝𝐴    (   ¯H′; ¯Γ′)        𝑟 ∈ regs(   ¯H′)frm(𝑒)
                                                        ¯H; ¯Γ ⊢𝑒 :𝑟 𝜏 ⊣  ¯H′; ¯Γ′                                                                              (H ;Γ)⇝𝐴    (H ;Γ)


                             F1 - Region-Framing                F2  - Region-Pinnedness-Framing             F3 - Tracked-Variable-Framing
                                   frm(𝑒)                                    frm(𝑒)                          dom(  ¯𝑋) ∩ ( NV( 𝑒) ∪dom( Γ)) = ∅
                            (H ;Γ)  ⇝                          ( 𝑟†⟨𝑋⟩, H;Γ)  ⇝                                          frm(𝑒)
                                    ·; ¯H   (H ⊎   ¯H;Γ)                       𝑟;·   ( 𝑟·⟨𝑋⟩, H;Γ)         ( 𝑟†⟨𝑋⟩, H;Γ)  ⇝
                                                                                                                          𝑟; ¯𝑋   ( 𝑟†⟨𝑋 ⊎ ¯𝑋⟩, H;Γ)

  F4  - Variable-Pinnedness-Framing                     F5 - Field-Framing                                        F6 - Variable-Framing
                      frm(𝑒)                                                frm(𝑒)                                      frm(𝑒)
  ( 𝑟◦⟨𝑥†[𝐹],𝑋 ⟩, H;Γ) ⇝                               ( 𝑟◦⟨𝑥†[𝐹],𝑋 ⟩, H;Γ)  ⇝                                   (H ;Γ)   ⇝
                       𝑟;𝑥   ( 𝑟◦⟨𝑥·[𝐹],𝑋 ⟩, H;Γ)                           𝑟;𝑥, ¯𝐹  ( 𝑟◦⟨𝑥†[𝐹 ⊎ ¯𝐹],𝑋 ⟩, H;Γ)            ·;¯Γ   (H ;Γ ⊎ ¯Γ)

                                                             Figure 12. Framing rules

   While   TS2  isnotsyntax-directed,anaivegreedyapproach                          Twoprinciplesdrovethedesignofthisuser-facingsyntax.
forms a sound, complete, and efficient decision procedure                       The first is that programmers should never directly mention
for its insertion during type-checking. For details, see the                    regions, as their direct inclusion in syntax here could lead
appendix.                                                                       programmers to expect them to be usable elsewhere in the
                                                                                program.The secondistoleanongood defaultsthatmatch
4.8   Introducing a Function Abstraction                                        programmer expectations; only exceptional code should re-
A function abstraction should capture all available static                      quireadditionalannotation.
tracking information about its arguments as input, and al-                         Following the principle of good defaults, for unannotated
low arbitrary transformations of that information as output.                    functions, threeassumptions hold:
Followingthisprinciple,oursystemprovidesfunctiontypes                                • At input, each parameter comes from a distinct un-
(H ;Γ) ⇒ (H  ′;Γ′;𝑟,𝜏 ) with three main components: (1) an                              pinned region with no tracking context.
inputpair (H , Γ) inwhich Γ capturesthefunction’sparam-                              • At output, each parameter remains in that region,
eters with their expected region and type, and H captures                              which again must beunpinned and empty.
thetrackingcontextsofthoseregions,possiblyclosedover                                 • Areturnedresultisinitsownunpinned,emptyregion.
the tracked isolated references in those contexts; (2) an out-                  These assumptions suffice to write functions that perform
putpair (H  ′, Γ′) whichcapturesthefinalstateofthesame                          in-placemanipulationsoftree-likeisolateddatastructures.
variables and regions; and (3) the region 𝑟 and type 𝜏 of                       Notably,functionrequirementsareonlycheckedatthebegin-
the returned value. Rules       T0  - Function-Definition and                   ningandend ofeach function body;function bodies which
T9 -Function-Applicationintegratethesefunctiontypes.                            onlytemporarilydeviatefromtheseexpectedpropertiesstill
T0  requires that the function body be well-typed with the                      requireno annotation.
giveninputand outputcontexts,and              T9 requiresthat,up to                In lieu of presenting the full surface language for func-
renamingofvariablesandregions,thecallsite’sH, Γ match                           tiondeclarations,wehighlightinterestingcasesbyexample
the function’s input H, Γ.                                                      in the style of section      2. Theconcat function in figure           14
   Atfirstglance,thisrelianceonanexactmatchofcontexts                           illustrates an example ofthe most commonlyneeded anno-
may appear restrictive; however, function declarations need                     tation on functions in our system:consumes, which indi-
onlyincludeelementsinH and Γ relevanttothatfunction’s                           cates the annotated input is consumed by the function. A
execution.Pinningannotationsinthefunctiondeclaration                            function can consume a parameter in more than one way.
allow call sites to produce an exact match by using                TS2  -       Intuitively,itcouldsendthatparametertoanotherthread;in
Framing-Structuraltoframeawayanyirrelevantportions                              thecaseoffigure      14,theparameterisretractedintoaniso
of the applicationcontext.                                                      fieldoftheotherparameter,concatenatingtheliststogether
4.9   A UsableFunction Syntax                                                   and becoming wholly owned by the larger list in the pro-
                                                                                cess.Interestingly,ourfullimplementationofasinglylinked
TheH and Γ contextsarecomplexandwouldbeonerousto                                listÐconsistingof8functionsÐrequiresonlythisconsumes
expect aprogrammer to write down directly. We therefore                         annotation,and even thenin just two places.
exposeanalternatesurfacesyntaxfordescribingfunction                                But there is need for function syntax more expressive
types.Thissyntax isintendedto be moreintuitive forpro-                          than justconsumes annotations. Consider for example the
grammers, while maintaining the full expressive power of                        get_nth_node function in figure           14. This function takes a
the typesystem.                                                                 circulardoublylinkedlistandreturnsamutablereference


                                                                          466

## PDF page 10

PLDI ’22, June 13ś17, 2022, San Diego, CA, USA                                                       Mae       Milano,            Julia       Turcotti,             and      Andrew            C.    Myers



                                        T0  - Function-Definition
                                        H;Γ ⊢𝑒 :𝑟 𝜏 ⊣ H′;Γ′       𝜏fn = (H ;Γ) ⇒ (H  ′;Γ′;𝑟,𝜏 )        ( fn,𝜏𝑓 ) ∈ F
                                                                   ⊢def fn :𝜏𝑓{𝑒}


                         T9  - Function-Application
                                                ( fn, ( H;𝑥′1 :𝑟′1 𝜏1,...,𝑥      ′𝑛 :𝑟′𝑛 𝜏𝑛) ⇒ (H  ′;Γ′;𝑟′0,𝜏 0)) ∈ F
                         Γ ⊢𝑥𝑖 :𝑟𝑖 𝜏𝑖    𝑟′𝑖 ↦→𝑟𝑖 ⊑ Φ𝑟 ∈ bijections( RegionNames)                             Φ𝑥 =𝑥′𝑖 ↦→𝑥𝑖 ∈ bijections( VariableNames)
                                            Φ𝑥( Φ𝑟( H));Γ ⊢ fn( 𝑥1,...,𝑥      𝑛) :𝑟0 𝜏0 ⊣ Φ𝑥( Φ𝑟(H  ′));Φ𝑥( Φ𝑟( Γ′))

                                       Figure 13.Function application and definition typing rules


    def concat(l1, l2 : sll_node) : unitconsumes l2 {                          5.1   Heuristics for Virtual Transformation Search
     letsome(l1_next) = l1.next    in {                                        As discussedin section4.6, the     TS1rule inour typesystem,
        concat(l1_next, l2);                                                   governing focus, explore, and all other virtual transforma-
     }else { l1.next =some l2;}}                                               tionsnecessarytotransformtheheapcontext,isnotsyntax-
    def get_nth_node(l : dll, pos :int) : dll_node?                            directed.Severalheuristicsimplementedbythetypechecker
     after: l.hd ~ result {                                                    keeptypecheckingefficientinpractice.Inparticular,weaim
        letsome(node) = l.hd    in {                                           to avoid backtracking searchwhen unifying the branches of
          while (pos > 0) {                                                    a conditional.
            node = node.next;                                                     At the heart of the difficulty in unifying the typing con-
            pos = pos - 1                                                      texts of branches is the information loss associated with
          };some(node)                                                         keyvirtualtransformationssuchasV2-UnfocusandV5-
        }else {none } }                                                        Attach. Unification can thusbe viewed as the problem of
Figure 14. Concatenating two lists, and returning the𝑛th                       inferringwhich linearresources mustbepreserved totype-
node of a doubly linked list                                                   checkagivenprogramsuffix.Byemployinglivenessanalysis
                                                                               of variables and isolated fields as a unification oracle, our
                                                                               checker can verify our largest examples in a handful of sec-
                                                                               onds. When necessary, our tool still falls back to search.
                                                                               Other approachesÐsuchas user annotationsor an external
to the𝑛th node,wrapping around if necessary.When type-                         constraintsolverÐmaybeusefulforpathologicalcases.More
checkinganapplicationofthisfunction,itisessentialthat                          details appear in the appendix.
thetypesystemknowsabouttherelationshipbetweenthe                               5.2   Efficiently Checking Mutual Disconnection
function’s argument and its returned resultÐnamely that,                       We implemented a version of theifdisconnected check (in-
rather than living in its own unrelated region as would be                     troducedinsection3.2)thatisefficientbasedontwo usage
the default, the function result lives in the same region as                   assumptions. The first assumption is that data structure de-
the argument’sisohd field. We capture this relationship                        signersprefertokeepregionssmallwhenpossible,placing
with the syntaxafter : 𝑎 ∼ 𝑏, which means that after the                       theisokeywordatabstractionboundariesÐforexample,col-
functionreturns,theregionsofobjects𝑎 and𝑏 arethesame.                          lectionsplacetheircontentsinisofields,aswedoinfigure1.
Here,𝑎 and𝑏 could bevariables, fields,or the return result                     Thesecondassumptionisthatifdisconnectediscommonly
itself as in this example. Combined with the pinning syntax,                   used to detach a smallportion of a regionÐoften as smallas
this ∼ syntax suffices to regain the full expressive power of                  a single object (as in figure5).
functiontypes.ProgrammerscancleanlyexpressfunctionsÐ                              Following these assumptions, we propose a two-step pro-
likeget_nth_nodeÐthatwouldbedifficultifnotimpossible                           cess for the efficient implementation ofifdisconnected.
to represent in prior work.                                                    First, store a reference count which tracks immediate heap
5   Implementation                                                             references stored in non-iso fields of structures. This stored
                                                                               referencecountisupdatedonlyonfieldassignment,anddoes
The typesystem has been implementedas aproverśverifier                         notneedtobemodifiedÐorcheckedÐonassignmenttolocal
architecture which we have made publicly available. The                        variables,functioninvocation,oratanyothertime.Thus,it
prover is written in ∼4,100 lines of OCaml, and its output                     is lighter-weight than conventional reference counts.
typingderivationsarecheckedbyaverifierwrittenin∼2,000                             Second,theifdisconnected checkitself isimplemented
linesof Coq,making iteasy tocheck byinspection thatthe                         viainterleavedtraversals oftheobjectgraphsrooted byits
type system is implemented faithfully.                                         twoarguments,ignoringreferenceswhichpointoutsidethe


                                                                         467

## PDF page 11

A FlexibleType Systemfor FearlessConcurrency                                                                                              PLDI’22, June 13ś17,2022, San Diego, CA, USA

current region, and stopping when the smaller of the two                    𝑑 will arrest the program in a łstuckž state, and we have
has beenfully explored(or apoint ofintersection has been                    presented typing rules with a complex context H, which
found).Duringthistraversal,thealgorithmcountsthenum-                        statically models capabilities to access a shared heap. The
ber of times it has encountered each object, assembling a                   missing piece of the puzzle is a run-time invariant using the
traversal reference count. At the end of the traversal, it com-             information in H to guarantee that well-typed programs
paresthistraversalreferencecountwiththestoredreference                      never encounter thatstuck state. This iseasily phrased:
count, concluding that the object graphs are disconnected                   Definition(Invariant       I1  -Reservation-Sufficiency). All
ifthecountsmatch,andconservativelyassumingthatthey                          locations that could be the result of stepping a well-typed
remain connected if the counts do not match.                                expression arecontained in the dynamicreservation𝑑.
   Thesoundnessofthisstrategyrelies ontwothings:tem-
pereddominationenforcedonisofieldsbythetypesystem,                             An immediate consequence of           I1 is that any variables
andaccuracyofthestoredheapreferencecounts.Thetyping                         bound in Γ to a region tracked in H are mapped (by the
rule forifdisconnected ensures that its arguments come                      dynamic stack 𝑠) to a location in 𝑑. This is because             T2  -
fromthesameregion,andthatnothingwithinthatregionis                          Variable-Refguaranteeswell-typedaccesstoanysuchvari-
tracked.Each untrackediso fieldroots adistinct,fully inde-                  ables, and   E2  - Variable-Ref-Step steps them directly to
pendentobjectgraph;thusnoobjectbeyondanisofieldcan                          theirboundlocations.Similarly,transitivetargetsoffields
be the first point of intersection betweenifdisconnected’s                  arein𝑑.Invariant     I1 isthusexactlythemissingpiecetobind
arguments. This eliminates any need for the traversal to                    well-typedness to reservation safety. Naturally, its preser-
search beyond aniso field.                                                  vation as programs step is a nontrivial proof goal, so we
   Ourchoicetoterminatethetraversalafteronlythesmaller                      introduceasecondinvariant        I2 whichimplies     I1andiscloser
graphisexplored,meanwhile,isjustifiedbyreferencecounts.                     to the formalisms of the language:
Thefearhereisthat,byterminatingourexplorationearly,                         Definition(Invariant       I2  -Tree-Of-Untracked-Regions).
wemayhavemissedsomepathfromthelargerobjectgraph                             Anytwopathsinthedynamicheapthatbegininatracked
into thesmaller. Such apath would necessarilyinclude an                     region and terminate at the samelocation traverse the same
unexploredreferencetargetinganobjectinthesmallergraph.                      sequence of untracked isolated references.
Theexistenceofthisunexploredreferencewouldbereflected
inthestoredreferencecount,causingthestoredreference                            This invariant is fundamental because it directly encodes
countto exceed the traversal reference count.                               the coretempered dominationinvariant: in particular, that
   Canthischeckbedoneefficiently?Forcaseswhichfollow                        beyondourstaticallytrackedsetwecanassumethatalliso
ourexpectedusepatternsÐliketheoneinfigure              5,wherethe           fields contain dominating references.
smallergraph’snon-isoreferencespointonlytotheobject                            To further motivate     I2, recall that the accepted static evi-
itselfÐthe traversal terminates immediately after encounter-                dencefortheseparationoftwoobjectsistheirpresencein
ing only a single object, or a small number of closely linked               separateregions(consider        T16  -Send),andthatuntracked
objects.Butintheworstcase,thischeckmayinvolvetravers-                       isolatedreferencesarealwaysassumedtopointtountracked
inganentireregionofarbitrarysize.Suchatraversalwould                        regions(see    V3  -Explore).Thus,anecessaryconditionfor
cut against the intended use-cases ofifdisconnected; we                     safetyisthatlocationsservingasthetargetofuntrackediso-
wouldthusconsidertheseusesmorelikelytoariseasare-                           latedreferencesmayneverbeboundtovariablesintracked
sultofbuggycodethanofintentionaldesign.Inthesebuggy                         regions;otherwise,thatvariablecouldbeaccessedevenafter
cases,ourifdisconnectedcheckwouldstillimproveonsys-                         is dropped from the reservation.       I2 captures this condition.
tems which rely ondestructive reads, replacing unexpected                      The appendix formalizes both         I1 and  I2, as well as addi-
run-time crashes later in the program with astatic error (or                tional formal invariants encoding expected agreement be-
anunexpectedlyslowno-op)atthepointthebugactuallyoc-                         tweenthestaticanddynamiccontexts.Alloftheseinvariants
curs. Returning tofigure     5, even werewe to introduce abug               togethercapturethenotionofasoundconfigurationusedin
byfailingtocorrectlydisconnecttheobjectgraphÐforexam-                       the following theorems.
plebyomittingtheassignmentswhichimmediatelyprecede                          Theorem    6.1    (Progress). Given    the    well    typed
theifdisconnectedcheckÐtheresultingtraversalwouldin-                        expression H;Γ ⊢ 𝑒 : 𝑟 𝜏 ⊣ H′;Γ′ with sound configuration
cur nearly no additionalcost, withifdisconnected’s check                    (H , Γ,𝑑,ℎ,𝑠,𝑒   ),thereexistsastep ( 𝑑,ℎ,𝑠,𝑒  )−−−→( 𝑑′,ℎ ′,𝑠 ′,𝑒 ′)eval
still terminating after only two objects areencountered.

                                                                            Theorem6.2(Preservation).  Giventhewelltypedexpression
6   Correctness                                                             H;Γ ⊢𝑒 :𝑟 𝜏 ⊣ H′;Γ′withsoundconfiguration(H , Γ,𝑑,ℎ,𝑠  )
Wehavediscussedindetailthesurfacesyntaxandsmall-step                        andstep( 𝑑,ℎ,𝑠,𝑒  )−−−→( 𝑑′,ℎ ′,𝑠 ′,𝑒 ′),thereexist  ¯H, ¯Γsuchthateval
semantics of our language, whose rules guarantee that any                    ¯H; ¯Γ ⊢𝑒′ :𝑟 𝜏 ⊣ H′;Γ′ andtheconfiguration (   ¯H, ¯Γ,𝑑 ′,ℎ ′,𝑠 ′)
attempttoaccessalocationoutsidethedynamicreservation                        is sound.


                                                                      468

## PDF page 12

PLDI ’22, June 13ś17, 2022, San Diego, CA, USA                                                    Mae       Milano,            Julia       Turcotti,             and      Andrew            C.    Myers

   Proofs of 6.1 and 6.2 are provided in the appendix. To-                   structswithisofieldsneednoannotationunless theytake
gether, these theorems imply that invariants I1 and I2 hold                  orreturnobjectgraphsthatviolatethetempereddomination
across the execution of a well typed program. This estab-                    invariantÐfor example, overlapping object graphs and non-
lishes tempered domination is preserved, and it establishes                  tree object graphs.
thecoresafetypropertyofoursystem:inawelltypedpro-                               We have found that functions whose arguments’ object
gram, no thread accesses memory outside its reservation.                     graphsoverlap(liketheget_nth_nodeexample)areusually
                                                                             easy to annotate, while functions that deviate from tem-
7   Concurrency                                                              pered domination at function boundaries are improved by
The results from section 6 show that our system can guar-                    signature-levelannotationsdescribingtheshapeoftheiriso-
antee the reservation safety of sequential programs. Impor-                  lated object graph. As an example, theshuffle function of
tantly, this result also means that concurrency is safe.                     the appendix’s redśblacktree takes 7 tree nodes in an arbi-
   Wemodelgeneral,message-passingconcurrencythrough                          trary, possibly deeply aliased state and returns them with a
the expressionssend-𝜏( 𝑒) andrecv-𝜏()  (T16  - Send and                      fixed,treepointerstructure.Expressingthatinformationin
T17- Receivein the type system of section4).                                 thesignatureprovidesalevelofstaticsafetyusuallyfound
   The concurrent configuration consists of a single shared                  only in dependently typed languages.
heapℎ, and an𝑛-tuple of threads, each with its own reser-                       Thus,ourexperiencesuggeststhatbesidesofferingstrong
vation𝑑𝑖,variablestore𝑠𝑖 andexpression𝑒𝑖 currentlyunder                      safety guarantees, this language is intuitively usable.
evaluation. Soundness of a concurrent configuration con-
sistsoftherespectivesoundnessandwell-typednessofeach                         9   Related Work
thread’s𝑒𝑖 with respect to the configuration ( 𝑑𝑖,ℎ,𝑠  𝑖), along             Thetypesystemweproposeowesmuchtotherichhistoryof
with pairwise disjointness of the reservations𝑑𝑖.                            relatedlanguagedesigns.Inparticular,itexploitsinnovations
   Stepping a concurrent configuration occurs by stepping                    from several important lines of research: ownership types
an individual thread, by updating that thread’s 𝑑𝑖,𝑠 𝑖,𝑒 𝑖 as                andcapabilities,regions,andlineartypes(andlinearregions).
well as the shared ℎ, or by stepping two threads together                    Wenowattempttobroadlycharacterizenotableworkfrom
thathavereachedasend-𝜏/recv-𝜏 pair.Thissteppingrule                          each line of research, and discuss how our work differs.
isillustratedinfigure15.Itstepsinthecontextoftheshared
heap ℎ, but only updates the respective reservations and                     9.1   Ownership Types and Nonlinear Uniqueness
expressionsof thesending andreceivingthreads. Inparticu-                     Whileweusetheterminologyoffocus[23]andregions[48],
lar,itidentifiesthelocation𝑙root thatthesending threadhas                    the closest antecedent tofocus is in CQual [1, 25], while the
chosen, readsℎ to identify the set𝑑sep of locations that are                 closest cousin to our regions is ownership contexts [16]. The
live(i.e.,reachable)from𝑙root,andstepsif𝑑sep isentirelycon-                  primary differencebetween our regions and ownershipcon-
tained within the sending thread’s reservation, transferring                 textsisthatownershipcontextsarefixed:objectsforeverlive
itto thereceiving thread’sreservation alongwithaccess to                     withinasingleownershipcontext,andownershipcontexts
the location𝑙root.                                                           cannot be merged, consumed, or generated on the fly.
   ProgressandPreservationintheconcurrentconfiguration                          Recognizing these limitations, later work introduced the
are also stated and proved in the appendix, notably estab-                   ability tomix ownershipwith uniqueness [2, 3, 8, 31, 38, 46].
lishingthat nothread’ssoundnessrelies onℎ outsideof its                      Theselanguagesallenforceuniquenessstrictly:auniqueref-
reservation𝑑𝑖, and that the rules T16 and T17 are sufficient                 erenceistheonlyreferencethatpointstoitsreferent.Clarke
toconcludeEC3-Communication-Paired-Stepcanbeap-                              and Wrigstadweakened this constraint byintroducing the
plied without getting stuck on ownership transfer, yielding                  ideaofexternaluniqueness,andwithittheideaofadominat-
sound post-transfer configurations for both threads.                         ing reference: an externally unique reference is traversed on
8   Expressiveness                                                           allpathsfromrootstotheobjecttowhichitrefers[14,15].
                                                                             Externallyuniquereferencesaresimilartoisofields,butiso
To explorethe expressiveness ofthe type system,we have                       fieldsdominateallobjectsreachablefromtheirtarget,whileÐ
written thousands of lines of algorithmic code, data struc-                  in its original formulationÐexternal references dominate
turemanipulations,andexperimentedwithfunctionabstrac-                        justtheir target. This weaker invariant prevents externally
tionsrangingfromtrivialtopathological.Largesamplesof                         uniquereferencesfromimplyingtransitiveownership.Other
thiscodearepresentedintheappendix,includingcomplete                          variationsonownership alsoexist;somework makesown-
singly and doubly linked lists and a redśblack tree.                         ingobjectsexplicit,abstractsthemwithcapabilities,orviews
   Our experience suggests that functions in our language                    them as modifiers [9,    13,    17,    20,    28,    29,    39,    40].
placeno unnaturalrestrictionson commoncoding patterns,                          Of particular note is the LaCasa language of Haller and
requiringannotationsonlywhentheisokeywordsareadded                           Odersky [28, 29], which our work subsumes. LaCasa’s sur-
tostructdefinitions.Further,evenfunctionsthatmanipulate                      face language (and accompanying annotation burden) are


                                                                       469

## PDF page 13

A FlexibleType Systemfor FearlessConcurrency                                                                                              PLDI’22, June 13ś17,2022, San Diego, CA, USA


                                                        ℎ ⊢ ( 𝑑,𝑒 ;𝑑,𝑒 )−−−−−−−−→ ( 𝑑,𝑒 ;𝑑,𝑒 )comm-eval


                           EC3  - Communication-Paired-Step
                                                           𝑑sep =live-set( 𝑟·⟨⟩;·;𝑙 :𝑟 𝜏;ℎ;·)
                          ℎ ⊢ ( 𝑑𝑎 ⊎𝑑sep,𝐸 ∗𝑎[send-𝜏( 𝑙root)];𝑑𝑏,𝐸 ∗       −−−−−−−−→ ( 𝑑𝑎,𝐸 ∗𝑎[new-unit];𝑑𝑏 ⊎𝑑sep,𝐸 ∗comm-eval
                                                              𝑏[recv-𝜏()])                                        𝑏[𝑙root])

                                 Figure 15. Steppingsend/recv pairs in the concurrent configuration

quite similar; both designs haveiso (@unique) fields that                    cyclic data structure patterns is encouraging, but remains
dominatethereachableobjectgraph;bothannotatemethods                          above the annotation budget thatwe believe is desirable for
similarly and rely on linearly tracked region capabilities.                  such common datastructures [54].
   A major limitation of these systems, including LaCasa, is                    TofteandTalpinintroducedtheideaofregions[19,                30, 48],
their inability to change thread reservations without mutat-                 which enable safe stack-based memory management in a
ingobjects.Eachsystemhasaway todropanobjectfrom                              language with dynamic allocation. A hallmark of region-
a thread’s reservation, rendering the thread unable to use                   basedtypesystemsisthatfunctionstypesspecifytheregions
theobject subsequently. Lackingafocus mechanism,these                        thefunctionmayaccess[49].Thelargestdifferencebetween
languages cannot determine which references need to be                       ourregionsandthoseofTofteandTalpinisthatourregions
invalidated when an object is lost. Rather than make lost                    are notfixed. They canbe merged, renamed,retracted into
objects statically inaccessible, most employ a łdestructive                  andexploredoutfromotherregions.Thisflexibilityremoves
readžthatimplicitlynullstheminstead[2,           5, 7,8, 15],though          theneedforcomplexeffectannotationsonfunctions;wecan
other approaches exist, such as łswapž [28,           29, 33]. Other         representcomplexobjectgraphsbytheirsimpleentrypoints,
systems, such as L42 and Servetto’s extension to Balloon                     and declarefunctions only as taking these entry points.
types [26,  46], have anotion of łlendingž a reference, allow-                  Ourlanguagetracksregions linearly.Whilemostexisting
ingthetailtobereturnedfromalist,butwithoutseparating                         workthatuseslinearregionsreliesonałswapžordestructive-
itfromthelistabstraction,andthusalsowithoutneedingto                         readprimitive[6,     21, 24, 28, 29],someexistingworkfeatures
invalidate potential aliases. These systems cannot efficiently               the ability to łopenž a region and freely access the objects
implementtheremove_tailfunctionfromfigure                  2;totruly         within it for a limited scopeÐmuch as our language can
free the tail of the list from its original owner, they would                temporarily focus objects [23,       52].
requireawriteoperationtoeachnodeinthelistinorderto                              Fähndrich and DeLine’s Vault language [23] directly in-
repairdestructivereadsperformedonthewaydown.Some                             spiredourfocusmechanism.Vault isaprimarilylinearlan-
systems adopt (or aim to adopt) Alias Burying [10] to avoid                  guage for reasoning about protocol state; its focus allows
implicitnullingwhen allaliasestoauniqueobjectaredead,                        particularobjects tobefreely aliased,exemptingthemfrom
butthis mechanismcouldnotrepairthelinkedlistexample.                         the requirements of linearity. A linear field of a potentially
                                                                             nonlinear objectin Vault isroughly analogoustoisofields
9.2   Linear Systems and Regions                                             in our type system. This analogy is rough, however; ouriso
Since initially popularized by Wadler [51], many linear lan-                 fieldsmayrefertoobjectsthatarefreelyaliasedwithintheir
guages have been proposed [21,          36,  42, 47,  50, 52] which          region,whileVault’slinearfields mustbeuniquereferences.
can prevent destructive races without relying on destruc-                    As in our work, Vault prevents access toiso fields unless
tive reads or swappingÐbut at the cost of making direct                      their containing object is focused, though only for writing;
representationsofgraphdatastructurescumbersome.These                         reading is always permitted
languageswouldnotbeabletodirectlyrepresentthedoubly                             Incomparison,oursystemrequireslessrigidmanagement
linkedlistfromfigure      1.Muchoftherecentinterestaround                    offocusedobjectsanddoesnotenforcelinearity onisoob-
this class of languages has centered on Rust [36], the first                 jects themselvesÐjust on their regions. All referencesÐeven
such language to gain widespread adoption [32,            34, 44, 53].       those iniso fieldsÐcan point to objects that participate in
WhileRustaceanshavediscoveredavarietyofcleverwaysto                          cycles;thiswouldnotbepossibleinVault,reducingtheease
simulate cyclic data structureswithin itstype system, those                  of implementing the doubly linked list in figure          1. Addition-
techniques often resemble how our system would behave                        ally,adoptionandfocusinVaultareannotation-heavy;Vault
were one to have a single object per region; complex graphs                  does not infer necessary focus points, so the programmer
arepossible,butthecostisadramaticincreaseinstatictrack-                      must explicitly fold and unfold accessible object trees.
ing,muchofitbornedirectlybytheuserintheformofextra                              A Vault-like adoption mechanism is also found in the
annotations,arelianceonunsafecode,orłcleverhacksžlike                        Mezzo language [4] to allow non-tree object graphs. It is
using indices into a linearly owned array as a stand-in for                  missingtheaccompanyingfocus,however,whichmaypose
references.Recentwork intousingłghostcellsžtoachieve


                                                                        470

## PDF page 14

PLDI ’22, June 13ś17, 2022, San Diego, CA, USA                                                  Mae       Milano,            Julia       Turcotti,             and      Andrew            C.    Myers

problemsinteracting safelywithMezzo’snoveltake onde-                           Table 1.Comparison with related language designs.
structivereads.ItisunclearifMezzo’sadoptionmechanism
allows theformation of arbitrarygraphs, or onlyDAGs,but                                 Language        sll   dll-repr     Simple
it isdifficult to see howa doubly linked listcould be imple-                            Rust            ✓         ×           ∼
mented in Mezzo without relying on implicit nulling.                                    Unique          ✓         ×           ∼
                                                                                        Vault           ✓         ∼           ∼
9.3   Immutability and Fractional Permissions                                           Mezzo           ∼         ∼           ✓
                                                                                        LaCasa          ×         ✓           ✓
Severalrelatedsystemsoffertheabilitytotemporarilyshare                                  OwnerJ          ×         ✓           ✓
mutableobjectswithimmutable references,andtorecover                                     Pony            ∼         ✓           ∼
mutabilityonceallsharedreferences’lifetimeshaveended[17,                                M#              ×         ✓           ✓
23,27].ThisbannerfeatureofRust[36]appearsinMezzo[4]                                     This paper      ✓         ✓           ✓
andwasaddedtoVaultthroughBoyland’sworkonfractional
permissions[11].M#[27],anevolutionofSing#[22],alsofea-
turesrecoveringmutabilityÐlatergeneralizedbyPony[17]
and L42 [26].
   Todeterminethelifetimeofconcurrentlysharedimmutable                     9.5   Comparison with Closely Related Work
references, these systems all support mutability recovery                  Thesystemsthatcomeclosesttomatchingourdesigngoals
only when using structured parallelism or explicit recovery                are summarized intable1.In thełsllžcolumn, systemsare
scopes:allpossiblealiases,includingthosepassedtothreads,                   marked that can implementremove_tail from our singly
will have been reclaimed by a statically known program                     linked list (without requiring O(list-size) object mutations).
pointÐusually whenall other threads involvedin communi-                    The łdll-reprž has a check for systems that can directly rep-
cationhave died.Incontrast, oursimple,unstructuredsend                     resent thedoubly linked list atall, and thełsimplež column
and receive mechanism cannot track which references are                    markssystemswhichrequirefewannotationsforstraightfor-
transmitted. Threads have no lifetime, so it is impossible to              wardimplementationsofcommonlistmutations.Tothebest
know if a reference sent to another thread is ever returned.               of our knowledge, no previous system is able to represent
   Instead,we expecttotake theapproach outlinedinGal-                      remove_tail from a doubly linked list without relying on
lifrey [37], in which a dynamic mechanism manages shared                   destructivereadsoraswap primitive.Finally,thełOwnerJž
immutabilityandmutabilitybyrelyingonreplication.Alter-                     row captures the close descendants of original ownership
nativelywecouldleverageourequivalentofłlendingžrefer-                      typesystems,includingPRFJ[8]andAliasJava[2](section
encestofunctionsduringafunctioncall;makingthosecalls                       9.1),while thełUniquežrow captures thelimitations oftype
asynchronous, and providing a built-in future mechanism                    systems in the style of Wadler’s popularization [51].
bywhichłlentžreferencesmaybereturned,isapromising
avenue by which recoverable mutability may be supported.
                                                                           10   Conclusion
9.4   Significant Complexity                                               Westartedbyobservingthatexpressiveness-limitingheap
                                                                           invariants and intimidating annotations are fatal flaws in
Several systems manage to ensure reservation safety and                    existingsafeconcurrencyapproaches.Ourcoreinsightsare
avoidimplicit null (or swap),but introducesignificant user-                thattheseinvariantscanbeweakenedwithoutlosingpower
facing complexity [8, 12, 13, 17, 33]. These languages fre-                as long as they stay recoverable through virtual transfor-
quentlyfeatureexplicit,exactregionorownershipannota-                       mations, and that careful type-system design can preserve
tions, provide a type parameterization mechanism which                     decidability in lieu of annotation. The result is a type sys-
allows the creation of classes whose ownership or region                   temthat replacesstricturewith flexibilityandcaution with
informationis determinedat instantiationtime, orrely on                    fearlessnessÐa new sweet spot in this design space that
amultitudeofreferencequalifierscapableofdiscussingex-                      lowers the cost of safe concurrency and opens promising
actly how various objects may relate in the object graph.                  avenues for future work.
While such systems are quite flexible, they force the user
to reason directly about concepts, like regions and region                 Acknowledgments
membership,whichweintentionallykeepimplicit.Herethe
complexity does not appear to be incidental; it is not clear               We thank the anonymous reviewers, Rolph Recto, Tom Ma-
howtoidentifyałsimplecorežlanguagethatwouldbecom-                          grino, Gabriel Matute, Justin Lubin, Marco Servetto (author
pleteon itsown. Indeed,our experiencedesigning thistype                    of [46]), and our shepherd,Ralf Jung, for their feedback and
system speaks to the speed at which complexity can creep                   suggestions. This work was supportedby the National Sci-
in from apparently innocuous design choices.                               ence Foundation under Grant No. 1717554.


                                                                      471

## PDF page 15

A FlexibleType Systemfor FearlessConcurrency                                                                                              PLDI’22, June 13ś17,2022, San Diego, CA, USA

References                                                                                  [16] David G.Clarke,John M. Potter, and James Noble.1998. Ownership
 [1] Alex Aiken, Jeffrey S. Foster, John Kodumal, and Tachio Terauchi.                           TypesforFlexibleAliasProtection.InProceedingsofthe13thACMSIG-
      2003. CheckingandInferringLocalNon-Aliasing.InProceedingsofthe                             PLANConferenceonObject-OrientedProgramming,Systems,Languages,
      ACM SIGPLAN 2003 Conference on Programming Language Design and                             andApplications (Vancouver,BritishColumbia,Canada)(OOPSLA’98).
      Implementation (SanDiego,California,USA)(PLDI’03).Association                              AssociationforComputingMachinery, NewYork, NY, USA,48ś64.
      forComputingMachinery, NewYork, NY, USA,129ś140.                                      [17] SylvanClebsch,SophiaDrossopoulou,Sebastian Blessing,andAndy
 [2] Jonathan Aldrich, Valentin Kostadinov, and Craig Chambers. 2002.                            McNeil. 2015.   Deny Capabilities for Safe, Fast Actors. In 5th Int’l
     AliasAnnotationsfor ProgramUnderstanding.In17th ACMSIGPLAN                                  WorkshoponProgrammingBasedonActors,Agents,andDecentralized
      Conf. on Object-Oriented Programming, Systems,Languages and Appli-                         Control (AGERE!). 1ś12.
      cations (OOPSLA) (Seattle, Washington, USA). 311ś330.                                 [18] Russell Cohen. 2018.  Why Writing a Linked List in (safe) Rust is
 [3] Paulo Sérgio Almeida. 1997. Balloon Types: Controlling Sharing of                           So Damned Hard.        https://rcoh.me/posts/rust-linked-list-basically-
      State in Data Types. In ECOOP’97 Ð Object-Oriented Programming,                            impossible/
      MehmetAkşitandSatoshiMatsuoka(Eds.).SpringerBerlinHeidelberg,                         [19] Karl Crary, David Walker, and Greg Morrisett. 1999.  Typed Mem-
      Berlin, Heidelberg, 32ś59.                                                                 ory Management in a Calculus of Capabilities. In Proceedings of the
 [4] ThibautBalabonski,FrançoisPottier,andJonathanProtzenko.2016.                                26th ACM SIGPLANśSIGACT Symposium on Principles of Program-
     TheDesignandFormalizationofMezzo,aPermission-BasedProgram-                                  ming Languages (San Antonio, Texas, USA) (POPL ’99). Association
      ming Language. ACM Transactions on Programming Languages and                               forComputingMachinery, NewYork, NY, USA,262ś275.
      Systems(TOPLAS) 38, 4(2016), 1ś94.                                                    [20] DavidCunningham,SophiaDrossopoulou,andSusanEisenbach.2007.
 [5] Anindya Banerjee and David A. Naumann. 2002. Representation Inde-                           Universes for Race Safety. Verification and Analysis of Multi-threaded
      pendence,ConfinementandAccessControl[ExtendedAbstract].In                                  Java-likePrograms(VAMP) (2007), 20ś51.
      Proceedingsof the29thACM SIGPLANśSIGACT SymposiumonPrinci-                            [21] Robert DeLine and Manuel Fähndrich. 2004. Typestates for Objects.
      plesofProgrammingLanguages (Portland,Oregon)(POPL’02).Associ-                              InECOOP2004śObject-OrientedProgramming,MartinOdersky(Ed.).
      ationforComputingMachinery, NewYork, NY, USA,166ś177.                                      SpringerBerlin Heidelberg, Berlin, Heidelberg, 465ś490.
 [6] NelsE.Beckman,KevinBierhoff,andJonathanAldrich.2008.Verifying                          [22] Manuel Fähndrich, Mark Aiken, Chris Hawblitzel, Orion Hodson,
      CorrectUsageofAtomicBlocksandTypestate.InProceedingsofthe                                  GalenHunt,JamesR.Larus,andStevenLevi.2006. LanguageSupport
      23rdACMSIGPLANConferenceonObject-OrientedProgrammingSys-                                   for Fast and Reliable Message-Based Communication in Singularity
      temsLanguagesandApplications (Nashville,TN,USA) (OOPSLA’08).                               OS. SIGOPS Oper.Syst. Rev.40, 4(April 2006),177ś190.
     AssociationforComputingMachinery,NewYork,NY,USA,227ś244.                               [23] Manuel Fähndrich and Robert DeLine. 2002.  Adoption and Focus:
 [7] Chandrasekhar Boyapati, Robert Lee, and Martin Rinard. 2002. Own-                           PracticalLinearTypesforImperativeProgramming.InACMSIGPLAN
      ership Types for Safe Programming: Preventing Data Races and Dead-                         Conf. onProgrammingLanguageDesign andImplementation(PLDI).
      locks. SIGPLANNot. 37, 11(Nov. 2002),211ś230.                                         [24] MatthewFluet,GregMorrisett,andAmalAhmed.2006.LinearRegions
 [8] ChandrasekharBoyapatiandMartinRinard.2001. AParameterized                                   AreAll You Need.In EuropeanSymposium onProgramming.Springer,
     Type System for Race-Free Java Programs. In 16th ACM SIGPLAN                                7ś21.
      Conf. on Object-Oriented Programming, Systems,Languages and Appli-                    [25] JeffreyS.Foster,TachioTerauchi,andAlexAiken.2002.Flow-Sensitive
      cations (OOPSLA). Tampa Bay, FL.                                                           TypeQualifiers.InProceedingsoftheACMSIGPLAN2002Conferenceon
 [9] JohnBoyland,JamesNoble,andWilliamRetert.2001. Capabilitiesfor                               Programming Language Design and Implementation (Berlin, Germany)
      Sharing.InECOOP2001ÐObject-OrientedProgramming,JùrgenLind-                                 (PLDI’02).AssociationforComputingMachinery,NewYork,NY,USA,
      skov Knudsen (Ed.). Springer Berlin Heidelberg, Berlin, Heidelberg,                        1ś12.
      2ś27.                                                                                 [26] Paola Giannini, Marco Servetto, and Elena Zucca. 2016.  Types for
[10] John Tang Boyland. 2001. Alias Burying: Unique variables without                            Immutability and Aliasing Control.In ICTCS, Vol.16. 62ś74.
      DestructiveReads. Software:PracticeandExperience 31,6(2001),533ś                      [27]  ColinS.Gordon,MatthewJ.Parkinson,JaredParsons,AleksBromfield,
      553.                                                                                       and Joe Duffy. 2012.   Uniqueness and Reference Immutability for
[11] JohnTangBoyland. 2010. SemanticsofFractionalPermissionswith                                 Safe Parallelism. In Proceedings of the ACM International Conference
      Nesting. ACM Trans. Program. Lang. Syst. 32, 6, Article 22 (Aug. 2010),                    onObjectOriented ProgrammingSystemsLanguagesandApplications
      33pages.                                                                                   (Tucson, Arizona, USA) (OOPSLA ’12). Association for Computing
[12] JohnTangBoylandandWilliamRetert.2005. ConnectingEffectsand                                  Machinery, NewYork, NY, USA,21ś40.
      Uniquenesswith Adoption. InProceedings ofthe32ndACMSIGPLANś                           [28] PhilippHallerandAlexLoiko.2016. LaCasa:LightweightAffinityand
      SIGACT Symposium on Principles of Programming Languages (Long                              ObjectCapabilitiesinScala.InProceedingsofthe2016ACMSIGPLAN
      Beach, California, USA) (POPL ’05). Association for Computing Ma-                          International Conference on Object-Oriented Programming, Systems,
      chinery, NewYork, NY, USA,283ś295.                                                         Languages, andApplications. 272ś291.
[13] EliasCastegrenandTobiasWrigstad.2016. ReferenceCapabilitiesfor                         [29] Philipp Haller and Martin Odersky. 2010. Capabilities for Uniqueness
      Concurrency Control. In 30th European Conference on Object-Oriented                        and Borrowing. In European Conferenceon Object-Oriented Program-
      Programming(ECOOP2016) (LeibnizInternationalProceedingsinInfor-                            ming. Springer, 354ś378.
      matics(LIPIcs),Vol.56),ShriramKrishnamurthiandBenjaminS.Lerner                        [30] FritzHenglein,HenningMakholm,andHenningNiss.2001. ADirect
     (Eds.).SchlossDagstuhlśLeibniz-ZentrumfuerInformatik,Dagstuhl,                              ApproachtoControl-FlowSensitiveRegion-BasedMemoryManage-
      Germany, 5:1ś5:26.                                                                         ment.InProceedingsofthe3rdACMSIGPLANInternationalConference
[14] DaveClarkeandTobiasWrigstad.2003.ExternalUniquenessIsUnique                                 onPrinciplesandPracticeofDeclarativeProgramming (Florence,Italy)
      Enough.InECOOP2003śObject-OrientedProgramming,LucaCardelli                                 (PPDP’01).AssociationforComputingMachinery,NewYork,NY,USA,
     (Ed.). SpringerBerlin Heidelberg, Berlin, Heidelberg, 176ś200.                              175ś186.
[15] DaveClarke,TobiasWrigstad,JohanÖstlund,andEinarBrochJohnsen.                           [31] JohnHogg.1991. Islands:AliasingProtectioninObject-OrientedLan-
      2008. MinimalOwnershipforActiveObjects.InAsianSymposiumon                                  guages. In Conference Proceedings on Object-Oriented Programming
      ProgrammingLanguages andSystems. Springer, 139ś154.                                        Systems,Languages,andApplications (Phoenix,Arizona,USA)(OOP-
                                                                                                 SLA ’91). Association for Computing Machinery, New York, NY, USA,
                                                                                                 271ś285.



                                                                                     472

## PDF page 16

PLDI ’22, June 13ś17, 2022, San Diego, CA, USA                                                                           Mae       Milano,            Julia       Turcotti,             and      Andrew            C.    Myers


[32]  ThomasBrachtLaumannJespersen,PhilipMunksgaard,andKenFriis                                [43]  ReeseT.Prosser.1959.ApplicationsofBooleanMatricestotheAnalysis
      Larsen. 2015. Session Types forRust. In Proceedings ofthe 11th ACM                             ofFlowDiagrams.InPapersPresentedattheDecember1-3,1959,Eastern
      SIGPLAN Workshop on Generic Programming (Vancouver, BC, Canada)                                Joint IRE-AIEE-ACM Computer Conference (Boston, Massachusetts)
     (WGP 2015). Association for Computing Machinery, New York, NY,                                  (IRE-AIEE-ACM ’59 (Eastern)). Association for Computing Machinery,
      USA, 13ś22.                                                                                    New York, NY, USA, 133ś138.
[33]  Trevor Jim, J. Gregory Morrisett, Dan Grossman, Michael W Hicks,                         [44]  Eric Reed. 2015.  Patina: A Formalization of the Rust Programming
      James Cheney, and Yanling Wang. 2002. Cyclone: A Safe Dialect of C..                           Language. University of Washington, Department of Computer Science
      In USENIX Annual Technical Conference, General Track. 275ś288.                                 and Engineering, Tech. Rep. UW-CSE-15-03-02 (2015).
[34]  RalfJung,Jacques-HenriJourdan,RobbertKrebbers,andDerekDreyer.                            [45]  JohnC.Reynolds.2002. SeparationLogic:ALogicforSharedMutable
      2017. RustBelt: Securing the Foundations of the Rust Programming                               Data Structures. In Proceedings of the 17th Annual IEEE Symposium
      Language. Proc. ACM Program. Lang. 2, POPL, Article 66 (Dec. 2017),                            on Logic in Computer Science (LICS ’02). IEEE Computer Society, USA,
      34 pages.                                                                                      55ś74.
[35]  Steve Klabnik and Carol Nichols. 2019. The Rust Programming Lan-                         [46]  Marco Servetto, David J. Pearce, Lindsay Groves, and Alex Potanin.
      guage (Covers Rust 2018). No Starch Press.                                                     2013.  Balloon Types for Safe Parallelisation over Arbitrary Object
[36]  Nicholas D. Matsakis and Felix S. Klock. 2014.  The Rust Language.                             Graphs. In Workshop on Determinism and Correctness in Parallel Pro-
      ACM SIGAda Ada Letters 34, 3 (2014), 103ś104.                                                  gramming (WoDet). 107.
[37]  Mae Milano,Rolph Recto, Tom Magrino,and Andrew C.Myers. 2019.                            [47]  SjaakSmetsers,ErikBarendsen,MarkovanEekelen,andRinusPlas-
      ATour ofGallifrey,aLanguageforGeodistributedProgramming.In                                     meijer.1994. Guaranteeing SafeDestructiveUpdatesThroughaType
      3rd Summit on Advances in Programming Languages (SNAPL).                                       SystemwithUniquenessInformationforGraphs.InGraphTransfor-
[38]  Naftaly H. Minsky. 1996. Towards Alias-Free Pointers. In ECOOP ’96                             mations in Computer Science, Hans Jürgen Schneider and Hartmut
     Ð Object-Oriented Programming, Pierre Cointe (Ed.). Springer Berlin                             Ehrig(Eds.).SpringerBerlinHeidelberg,Berlin,Heidelberg,358ś379.
      Heidelberg, Berlin, Heidelberg, 189ś209.                                                 [48]  MadsTofteandJean-PierreTalpin.1994. ImplementationoftheTyped
[39]  Peter Müller and Arnd Poetzsch-Heffter. 1999.  Universes: A Type                               Call-by-Value𝜆-Calculus Using a Stack of Regions. In Proceedings of
      System for Controlling Representation Exposure. In Programming                                 the 21st ACM SIGPLANśSIGACT Symposium on Principles of Program-
      Languages and Fundamentals of Programming, Vol. 263. 204.                                      mingLanguages (Portland,Oregon,USA) (POPL’94).Associationfor
[40]  PeterMüllerandArseniiRudich.2007.OwnershipTransferinUniverse                                   Computing Machinery, New York, NY, USA, 188ś201.
      Types. In Proceedings of the 22nd Annual ACM SIGPLAN Conference                          [49]  Mads Tofte and Jean-Pierre Talpin. 1997. Region-Based Memory Man-
      onObject-OrientedProgrammingSystemsandApplications (Montreal,                                  agement. Information and Computation 132, 2 (1997), 109ś176.
      Quebec,Canada)(OOPSLA’07).AssociationforComputingMachinery,                              [50]  Vasco T. Vasconcelos. 2012. Fundamentals of Session Types. Informa-
      New York, NY, USA, 461ś478.                                                                    tion and Computation 217 (2012), 52ś70.
[41]  ndrewxie (https://users.rust lang.org/u/ndrewxie). 2019.    What’s                       [51]  Philip Wadler. 1990. Linear types can change the world!. In Program-
      the  łbestž  way  to  implement  a  doubly-linked  list  in  Rust?                             ming Concepts and Methods, M. Broy and C. Jones (Eds.). North Hol-
      https://users.rust-lang.org/t/whats-the-best-way-to-implement-a-                               land.
      doubly-linked-list-in-rust/27899                                                         [52]  DavidWalkerandKevinWatkins.2001. OnRegionsandLinearTypes
[42]  MartinOdersky.1992. ObserversforLinear Types.InESOP’92,Bernd                                   (Extended Abstract). SIGPLAN Not. 36, 10 (Oct. 2001), 181ś192.
      Krieg-Brückner(Ed.).SpringerBerlinHeidelberg,Berlin,Heidelberg,                          [53]  AaronWeiss,DanielPatterson,NicholasD.Matsakis,andAmalAhmed.
      390ś407.                                                                                       2019.  Oxide: The Essence of Rust.  arXiv preprint arXiv:1903.00982
                                                                                                     (2019).
                                                                                               [54]  Joshua Yanovski, Hoang-Hai Dang, Ralf Jung, and Derek Dreyer. 2021.
                                                                                                     GhostCell: Separating Permissions from Data in Rust.  Proc. ACM
                                                                                                     Program. Lang. 5, ICFP, Article 92 (aug 2021), 30 pages.
























                                                                                        473
