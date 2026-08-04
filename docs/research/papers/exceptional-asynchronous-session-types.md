# Exceptional Asynchronous Session Types: Session Types without Tiers

**Machine conversion:** extracted from the adjacent PDF with `pypdf`; page boundaries are retained, while equations, figures, and multi-column layout may not round-trip faithfully. Consult the PDF for authoritative pagination and notation.

## PDF page 1

Exceptional Asynchronous Session Types
Session Types without Tiers (Extended Version)

SIMON FOWLER, The University of Edinburgh, UK
SAM LINDLEY,The University of Edinburgh, UK
J. GARRETT MORRIS,The University of Kansas, USA
SÁRA DECOVA, The University of Edinburgh, UK
Session types statically guarantee that communication complies with a protocol. However, most accounts of
sessiontypingdo notaccountforfailure,whichmeans they areoflimitedusein real applications—especially
distributed applications—where failure is pervasive.
   Wepresentthefirstformalintegrationofasynchronoussessiontypeswithexceptionhandlinginafunctional
programming language. We define a core calculus which satisfies preservation and progress properties, is
deadlock free, confluent, and terminating.
   Weprovidethefirstimplementationofsessiontypeswithexceptionhandlingforafully-fledgedfunctional
programming language, by extending the Links web programming language; our implementation draws
on existing work on effect handlers. We illustrate our approach through a running example of two-factor
authentication, and a larger example of a session-based chat application where communication occurs over
session-typed channels and disconnections are handled gracefully.

1   INTRODUCTION
Withthe growthof theinternetand mobiledevices,as wellas thefailureof Moore’slaw, concur-
rencyanddistributionhavebecomecentraltomanyapplications.Writingcorrectconcurrentand
distributedcoderequireseffectivetoolsforreasoningaboutcommunicationprotocols.Whiledata
types provide an effectivetoolforreasoning about the shape of data communicated, protocols also
require us to reason about the order in which messages are transmitted.
   Session types [Honda1993;Honda et al                     . 1998] are types for protocols. They describe both
the shape and order of messages. If a program type-checks according to its session type, then
it is statically guaranteed to comply with the corresponding protocol. Alas, most accounts of
session types do not handle failure, which means they are of limited use in distributed settings
where failure is pervasive. Inspired by work ofMostrous and Vasconcelos[2014], we present
the first account of asynchronous session types in a functional programming language, which
smoothlyhandlesbothdistributionandfailure. Wepresentbothacorecalculusenjoyingstrong
metatheoreticalcorrectness propertiesanda practicalimplementationas anextensionof theLinks
web programming language [Cooper et al.2007].

1.1   Session Types
Weillustratesessiontypeswithabasicexampleoftwo-factorauthentication.Auserinputstheir
credentials. If the login attempt is from a known device, then they are authenticated and may
proceedtoperformprivilegedactions. Iftheloginattempt isfromanunrecognised device,then
theuserissentachallengecode.Theyenterthechallengecodeintoahardwarekeywhichyieldsa
response code. If the user responds with the correct response code, then they are authenticated.
   A session type specifies the communication behaviour of one endpoint of a communication
channelparticipatinginadialogue(or session)withtheotherendpointofthechannel.Fig.1shows
the session types of two channel endpoints connecting a client and a server. Fig.1ashows the

Authors’addresses:SimonFowler,TheUniversityofEdinburgh,UK,simon.fowler@ed.ac.uk;SamLindley,TheUniversity
ofEdinburgh,UK,sam.lindley@ed.ac.uk;J.GarrettMorris,TheUniversityofKansas,USA,garrett@ittc.ku.edu;SáraDecova,
The University of Edinburgh, UK, sara.decova@gmail.com.


                                                              , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 2

2                                                     Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova


TwoFactorServer≜                                               TwoFactorClient≜
   ?(Username,Password).⊕{                                        !(Username,Password).&{
     Authenticated : ServerBody,                                     Authenticated : ClientBody,
     Challenge : !ChallengeKey.?Response.                            Challenge : ?ChallengeKey.!Response.
        ⊕{Authenticated : ServerBody,                                   &{Authenticated : ClientBody,
            AccessDenied : End},                                           AccessDenied : End},
     AccessDenied : End}                                             AccessDenied : End}

             (a) Server Session Type                                         (b) Client Session Type


                                Fig. 1. Two-factor Authentication Session Types

session type for the server which first receives (?) a pair of a username and password from the
client.Next,theserverselects(⊕)whethertoauthenticatetheclient,issueachallenge,orrejectthe
credentials. If the server decides to issue a challenge, then it sends (!) the challenge string, awaits
theresponse,andeitherauthenticatesorrejectstheclient.TheServerBodytypeabstractsoverthe
remainder of the interactions, for example making a deposit or withdrawal.
   Duality. The client implements the dual session type, shown in Fig.1b. Whenever the server
receives a value, the client sends a value, and vice versa. Whenever the server makes a selection,
theclientoffersachoice(&),andviceversa.This duality betweenclientandserverensuresthat
each communication is matched by the other party. We denote duality with an overbar; thus
TwoFactorClient =      TwoFactorServer andTwoFactorServer =              TwoFactorClient.
   Implementing Two-factor Authentication. Let us suppose we have constructs for sending and
receiving along, and for closing, an endpoint.
     sendM N :S                whereM has type A, and N is an endpoint with session type!A.S
     receiveM: (A×S)     whereM is an endpoint with session type?A.S
     closeM:1                   whereM is an endpoint with session type End
Let us also suppose we have constructs for selecting and offering a choice:
selectℓj M :Sj                          whereM is an endpoint with session type⊕{ℓi :Si}i∈I, and j∈ I
offerM{ℓi (xi)7→ Ni}i∈I :A    whereM is an endpoint with session type&{ℓi :Si}i∈I, eachxi
                                      binds an endpoint with session typeSi, and each Ni has typeA
We can now write a client implementation.
         twoFactorClient :  (Username×Password×TwoFactorClient)⊸ 1
         twoFactorClient(username,password,s)≜
            lets = send (username,password)s in
            offers{Authenticated(s)7→ clientBody(s)
                      Challenge(s)  7→ let (key,s) = receives in
                                               lets = send (generateResponse(key))s in
                                               offers{Authenticated(s)7→clientBody(s)
                                                         AccessDenied(s)7→closes;loginFailed}
                      AccessDenied(s)7→ closes;loginFailed}
ThetwoFactorClientfunctiontakesthecredentialsandanendpointoftypeTwoFactorClientasits
arguments.Thecredentialsaresentalongtheendpoint,thenthreechoicesareoffereddependingon
whethertheserverauthenticatestheuser,sendsatwo-factorchallenge,orrejectstheauthentication


, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 3

Exceptional Asynchronous Session Types                                                                                         3

attempt. If the server authenticates the user, then the program progresses to the main application
(clientBody(s)).Iftheserversendsachallenge,thentheclientreceivesthechallengekey,andsends
the response, calculated by generateResponse. Two choices are then offered according to whether
the challenge response was successful. The rejection of an authentication attempt is part of the
protocol and not exceptional behaviour. We can also write a server implementation.

              twoFactorServer : TwoFactorServer⊸ 1
              twoFactorServer(s)≜ let ((username,password),s) = receives in
                                         if checkDetails(username,password) then
                                            lets = selectAuthenticateds inserverBody(s)
                                         else
                                            lets = selectAccessDenieds incloses

The twoFactorServer function takes an endpoint of type TwoFactorServer along which it receives
thecredentials,whicharecheckedusingcheckDetails.Ifthecheckpasses,thentheserverproceeds
tothe applicationbody(serverBody(s));if not,thenthe servernotifiesthe clientbyselectingthe
AccessDeniedbranch.Thisparticularserverimplementationoptstoneversendachallengerequest.
   Staticallycheckingsessiontypesdemandsasubstructuraltypesystem.Wediscussthreeoptions:
linear types, affine types, and linear types with explicit cancellation.

1.2   Linear Types
Simplyprovidingconstructsforsendingandreceivingvalues,andforselectingandofferingchoices,
is insufficient for safely implementing session types. Consider the following client:

                      wrongClient : TwoFactorClient⊸ 1
                      wrongClient(s)≜ lett = send ("Alice","hunter2")s in
                                            lett = send ("Bob","letmein")s in ...

Reuse ofs allows a (username, password) pair tobe sent along thesame endpoint twice, violating
thefundamentalpropertyof session fidelity,whichstatesthatinawell-typedprogram,communi-
cation over an endpoint matches its session type. To maintain session fidelity and ensure that all
communication actions in a session type occur, session type systems typically require that each
endpoint is used linearly—exactly once.
   Exceptions. In  practice,  linear  session  types  are  unrealistic.  Thus  far,  we  have  assumed
checkDetailsalwayssucceeds,whichmaybeplausibleifcheckingagainstanin-memorystore,but
not if connecting to a remote database. One option would be for checkDetails to return false on
failure,butthatwouldloseinformation.Instead,supposewehaveanexceptionhandlingconstruct.
As a first attempt, we might try to write:

               exnServer1 : TwoFactorClient⊸ 1
               exnServer1(s)≜ let ((username,password),s) = receives in
                                    tryif checkDetails(username,password) then
                                           lets = selectAuthenticateds inserverBody(s)
                                        else
                                           lets = selectAccessDenieds incloses
                                    catchlog("Database Error")
However, the above code does not type-checkand is unsafe. Linear endpoints is not usedin the
catch block and yet is still open if an exception is raised by checkDetails.


                                                              , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 4

4                                                     Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

   Asasecondattempt,wemaydecidetolocaliseexceptionhandlingtothecalltocheckDetails.
WeintroducecheckDetailsOpt,whichreturnsSome(result) ifthecallissuccessfulandNoneifnot.
      checkDetailsOpt :  (Username×Password)⊸ Option(Bool)
      checkDetailsOpt(username,password)≜ trySome(checkDetails(username,password))
                                                       catchNone

   exnServer2 : TwoFactorServer⊸ 1
   exnServer2(s)≜ let ((username,password),s) = receives in
                       casecheckDetailsOpt(username,password) of
                          Some(res)7→ if resthenlets = selectAuthenticateds inserverBody(s)
                                          elselets = selectAccessDenieds incloses
                          None7→ log("Database Error")
Still the code is unsafe as it does not use s in the None branch of the case-split. However, we
do now have more precise information about the type ofs, since it is unused in the try block in
checkDetailsOpt.OnesolutioncouldbetoadapttheprotocolbyaddinganInternalErrorbranch:
  TwoFactorServerExn≜ ?(Username,Password).⊕{
     Authenticated : ServerBody,
     Challenge : !ChallengeKey.Response.⊕{Authenticated : ServerBody,AccessDenied : End},
     AccessDenied : End,
     InternalError : End}
WecoulduseselectInternalErrors intheNonebranchtoyieldatype-correctprogram,butdoing
so would be unsatisfactory as it clutters the protocol and the implementation with failure points.
   Disconnection. The problemof failure iscompounded by thepossibility of disconnection.On a
singlemachineitmaybeplausibletoassumethatcommunicationalwayssucceeds.Inadistributed
setting this assumption is unrealistic as parties may disconnect without warning. The problem is
particularly acute in web applications as a client may close the browser at any point. In order to
adequately handle failure we must incorporate some mechanism for detecting disconnection.

1.3   Affine Types
We began by assuminglinear types—each endpointmust be used exactly once. Onemight consider
relaxinglineartypesto affine types—eachendpointmustbeused at most once.Staticallychecked
affine types form the basis of the existing Rust implementation of session types [Jespersen et al .
2015] and dynamically checked affine types form the basis of the OCaml FuSe [Padovani2017]
and Scalalchannels [Scalas and Yoshida2016] session type libraries. Affine types present two
quandaries arising from endpoints being silently discarded. First, a developer receives no feedback
ifthey accidentally forgetto finisha protocolimplementation. Second,if anexception israised in
an evaluation context that captures an open endpoint then the peer may be left waiting forever.

1.4   Linear Types with Explicit Cancellation
MostrousandVasconcelos[2014]addressthedifficultiesoutlinedabovethroughan                           explicit discard
(or cancellation) operator. (They characterise their sessions as affine, but it is important not to
confuse their system with affine type systems, as in §1.3, which allow variables to be discarded
implicitly.)Theirapproachboilsdowntothreekeyprinciples:endpointscanbeexplicitlydiscarded;
an exception is thrown if a communication cannot succeed because a peer endpoint has been
cancelled; and endpoint cancellations are propagated when endpoints become inaccessible due to


, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 5

Exceptional Asynchronous Session Types                                                                                         5

anexceptionbeingthrown. Theyintroduce aprocess calculusincluding theterma (“cancela”),
which indicates that endpointa may no longer be used to perform communications. They provide
anexceptionhandlingconstructwhichattemptsacommunicationaction,runninganexception
handleriftheactionfails,andshowthatexplicitcancellationiswell-behaved:theircalculussatisfies
preservation and global progress (well-typed processes never get stuck), and is confluent.
   Explicitcancellationneatlyhandlesfailurewhilerulingoutaccidentallyincompleteimplementa-
tions and providing a mechanism for notifying peers when an exception is raised. In this paper we
take advantage of explicit cancellation to formalise and implement asynchronous session types
withfailurehandlinginadistributedfunctionalprogramminglanguage;thisisnotmerelyaroutine
adaptation of the ideas ofMostrous and Vasconcelosfor the following reasons:
    • They present a process calculus, but we work in a functional programming language.
    • Communicationintheirsystemis synchronous,dependingonarendezvousbetweensender
       and receiver. We require asynchronous communication, which is more amenable to imple-
       mentation in a distributed setting.
    • Theirexceptionhandlingconstructisoverasinglecommunicationactionanddoesnotallow
       nestedexceptionhandling.Thisdesignisdifficulttoreconcilewithafunctionallanguage,as
       it is inherently non-compositional. Our exception handling construct is compositional.
   Wedefineacoreconcurrentλ-calculus,ExceptionalGV (EGV),withasynchronoussession-typed
communication and exception handling. As with the calculus ofMostrous and Vasconcelos, an
exception is raised when a communication action fails. But our compositional exception handling
constructcanbearbitrarilynested,andallowsexceptionhandlingovermultiplecommunication
actions. Using EGV, we may implement the two factor authentication server as follows:

           exnServer3 : TwoFactorServer⊸ 1
           exnServer3(s)≜ let ((username,password),s) = receives in
                               trycheckDetails(username,password) asresin
                                  if resthenlets = selectAuthenticateds inserverBody(s)
                                  elselets = selectAccessDenieds incloses
                               otherwise
                                  cancels; log("Database Error")

FollowingBentonandKennedy[2001],anexceptionhandler                   tryLasx inMotherwiseN takesan
explicit success continuationM as well as the usual failure continuation N. If checkDetails fails
with anexception,thens is safely discardedusing cancel, whichtakes an endpoint andreturns
the unit value. Disconnection is handled by cancelling all endpoints associated with a client. If a
peer tries to read along a cancelled endpoint then an exception is thrown.
   We implement the constructs described by EGV as an extension to Links [Cooper et al . 2007],
a functional programming language for the web. Our implementation is based on a minimal
translation to effect handlers [Plotkin and Pretnar2013].

1.5   Contributions
This paper makes five main contributions:
   (1) ExceptionalGV (§2),acorelinearlambdacalculusextendedwithasynchronoussession-typed
       channels and exception handling. We prove (§3) that the core calculus enjoys preservation,
       progress, a strong form of confluence called the diamond property, and termination.
   (2) Extensions to EGV (§4) supporting exception payloads, unrestricted types, and access points
       (which provide a more flexible means of session initiation).


                                                             , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 6

6                                                     Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova


   try                                       lets  =
      lets = fork (λt.cancelt) in               fork (λt.
      let (res,s) = receives in                    let (res,t) = receivet in           let f =  (λx.sendx s) in
      closes;res                                   closet;res) in                      raise;
   asresin                                   letu = fork (λv.cancelv) in               f (5)
      print ("Result: "+res)                 letu = sendsu in
   otherwiseprint"Error!"                    closeu                                          (c) Closures

(a) Cancellation and Exceptions                    (b) Delegation


                                             Fig. 2. Failure Examples



   (3) ThedesignandimplementationofanextensionoftheLinkswebprogramminglanguageto
       support tierless webapplications which can communicate usingsession-typedchannels (§5).
   (4) Client and server backends for Links implementing session typing with exception handling
       (§5.4), drawing on connections with effect handlers [Plotkin and Pretnar2013].
   (5) Exampleapplications usingtheinfrastructure (§6).Inaddition toourtwo-factor authentica-
       tion workflow we outline the implementation of a chat server.
   Linksisopen-sourceandfreely-available.Thewebsitecanbefoundathttp://www.links-lang.org
andthesourceathttp://www.github.com/links-lang/links.Usersofthe         opamtoolcaninstallLinks
by invokingopam install links.
   The rest of thepaper is structuredas follows:§2presentsExceptional GV and§3its metatheory;
§4discusses extensions to Exceptional GV; §5describes the implementation; §6presents a chat
application written in Links; §7discusses related work; and §8concludes.

2   EXCEPTIONAL GV
In this section, we introduce Exceptional GV (henceforth EGV). GV is a core session-typed linear
λ-calculus that has a tight correspondence with classical linear logic [Lindley and Morris2015;
Wadler2014]. EGV is an asynchronous variant of GV with support for failure handling.
   Due to GV’s close correspondence with classical linear logic, EGV has a strong metatheory,
enjoying preservation, global progress, the diamond property, and termination. Much like the
simply-typedλ-calculus, this well-behaved core must be extended to be expressive enough to
writelargerapplications.Nonetheless,thecorecalculusaloneisexpressiveenoughtosupportour
two-factor authentication example, and to support server applications which gracefully handle
disconnection. In §3, we show that cancellation is well-behaved, and does not violate any of the
corepropertiesofGV.In§4,followingLindleyandMorris[2015,2017],weextendEGVmodularly
withstandardfeatures ofourimplementation, someofwhich provide weakerguarantees.Channel
cancellation and exceptions are orthogonal to these features.

2.1   Integrating Sessions with Exceptions, by Example
Integratingsessiontypeswithfailurehandlingintoahigher-orderfunctionallanguagerequires
care.Fig.2illustratesthreeimportantcases:cancellationandexceptions,delegation,andclosures.
In order to initiate a session, we adopt the fork primitive ofLindley and Morris[2015]. Given a
termM oftypeS⊸ 1,the term forkM oftype                   S createsa freshchannel withendpointsa oftype
S andb of type    S, forks a child thread that executesM a, and returns endpointb.


, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 7

Exceptional Asynchronous Session Types                                                                                         7


 Types                        A,B,C ::=1|  A⊸ B|  A+B|  A×B|  S
 Session Types                S,T ::=!A.S|  ?A.S|  End
 Variables                        x,y
 Terms                      L,M,N ::=x|λx.M|  M N|   ()|  let () = M inN|   (M,N )|  let (x,y) = M inN
                                |  inlM|  inrM|  caseLof{inlx7→ M;inry7→ N}
                                |  forkM|  sendM N|  receiveM|  closeM
                                |  cancelM|  raise|  tryLasx inM otherwiseN
 Type Environments        Γ ::=·| Γ,x :A


                                                  Fig. 3. Syntax


  Cancellation and Exceptions. Fig.2aforks a thread which immediately cancels its endpoint. The
parent attempts to receive, but the message can never arrive so an exception is raised and the
otherwise clause is invoked.

  Delegation. A central feature ofπ-calculus is mobility of names. In session calculi sending an
endpointisknownassessiondelegation.ThecodeinFig.2bbeginsbyforkingathreadandreturning
endpoints. Thechild ispassed endpointt on whichit blocks receiving. Next,the parent forksa
second child, yielding endpointu. The second child is passed endpointv, which is immediately
discardedusingcancel.Nowtheparentthreadsendsendpoints alongu.Endpoints willneverbe
receivedasthepeerendpointv ofu hasbeencancelled.Inturn,thisrenderss irretrievableandan
exception is thrown in the first child thread, as it can never receive a value.

  Closures. Itiscrucialthatcancellationplaysnicelywithclosures.ThecodeinFig.2cdefinesa
function f which sends its argumentx alongs. The parent thread then raises an exception. Ass
appearsintheclosureboundto f,whichappearsinthecontinuationandisthusdiscarded,s must
be cancelled.

2.2   Syntax and Typing Rules for Terms
Fig.3givesthesyntaxofEGV.Typesincludeunit(       1),linearfunctions(A⊸ B),linearsums(A+B),
linear tensor products (A×B), and session types (S).
  Termsincludevariables(x)andtheusualintroductionandeliminationformsforlinearfunctions,
unit,products,andsums.WewriteM;N assyntacticsugarforlet () = M inN andletx = M inN
for  (λx.N ) M. The standard session typing primitives [Lindley and Morris2015] are as follows:
forkM createsafreshchannelwithendpointsa oftypeS andb oftype                        S,forksachildthreadthat
executesM a,and returnsendpointb;sendM N sendsM alongendpoint N;receiveM receives
along endpointM; and closeM closes an endpoint when a session is complete.
  Weintroducethreenewtermconstructstosupportsessiontypingwithfailurehandling:cancelM
explicitlydiscardssessionendpointM;raiseraisesanexception;andtryLasx inM otherwiseN
evaluatesL, on success binding the result tox inM and on failure evaluating N.
  Explicit success continuations. Benton and Kennedy[2001] argue that:
       From the points of view of programming pragmatics, rewriting and operational se-
       mantics, thesyntactic construct used forexception handling in ML-likeprogramming
       languages,andinmuchtheoreticalworkonexceptions,hassubtlyundesirablefeatures.
BentonandKennedyshowthatexplicitsuccesscontinuationsavoidthesubtlyundesirablefeatures
theyidentify;correspondingly,weadopttheirconstruct.Moreover,explicitsuccesscontinuations


                                                             , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 8

8                                                     Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

Term Typing                                                                                                               Γ⊢ M:A
             T-Var                    T-Abs                           T-App
                                        Γ,x :A⊢ M:B                   Γ1⊢ M:A⊸ B      Γ2⊢ N :A
             x :A⊢x :A                Γ⊢λx.M:A⊸ B                             Γ1,Γ2⊢ M N :B

                T-LetUnit                          T-Pair                         T-LetPair
 T-Unit                   Γ1⊢ M:1                         Γ1⊢ M:A                          Γ1⊢ M:A×B
                          Γ2⊢ N :A                        Γ2⊢ N :B                     Γ2,x :A,y:B⊢ N :C
 ·⊢  ():1        Γ1,Γ2⊢ let () = M inN :A           Γ1,Γ2⊢  (M,N ):A×B            Γ1,Γ2⊢ let (x,y) = M inN :C

   T-Inl                     T-Inr                     T-Case
        Γ⊢ M:A                    Γ⊢ M:B                Γ1⊢ L:A+B      Γ2,x :A⊢ M:C      Γ2,y:B⊢ N :C
   Γ⊢ inlM:A+B               Γ⊢ inrM:A+B                    Γ1,Γ2⊢ caseLof{inlx7→ M;inry7→ N}:C

   T-Fork                 T-Send                               T-Recv                           T-Close
   Γ⊢ M:S⊸ 1              Γ1⊢ M:A      Γ2⊢ N :!A.S                   Γ⊢ M:?A.S                    Γ⊢ M:End
   Γ⊢ forkM:S                 Γ1,Γ2⊢ sendM N :S                Γ⊢ receiveM: (A×S)               Γ⊢ closeM:1

        T-Cancel                     T-Try                                                    T-Raise
             Γ⊢ M:S                  Γ1⊢ L:A      Γ2,x :A⊢ M:B      Γ2⊢ N :B
         Γ⊢ cancelM:1                  Γ1,Γ2⊢ tryLasx inM otherwiseN :B                       ·⊢ raise:A
Duality                                                                                                          S
                      !A.S = ?A.S                   ?A.S = !A.S                  End = End


                                        Fig. 4. Term Typing and Duality


alignwiththedefinitionofhandlersforalgebraiceffects[PlotkinandPretnar2013]thatweusein
our implementation (§5.4).

   Branching and selection. Though our implementation supports select and offer directly, and we
usetheminexamples,weomitthemfromthecorecalculus(followingLindleyandMorris[2015,
2017]) as they can be encoded using sums and delegation [Dardha et al.2017;Kobayashi2002].

   Typing. Fig.4gives the typing rules for EGV. As usual, linearity is enforced by splitting environ-
mentswhentypingsubterms,ensuringT-Vartakesasingletonenvironment,andleafrulesT-Unit
and T-Raise take an emptyenvironment. We writeΓ1,Γ2 to mean thedisjoint union ofΓ1 andΓ2.
Thebulkofthe rulesarestandardforalinearλ-calculus.Sessiontypesare relatedby duality.The
T-Fork rule forks a thread connected by dual endpoints of a channel. The rules T-Send, T-Recv,
and T-Closecapture session-typed communication.
   Asexceptionsdonotreturnvalues,theruleT-RaiseallowsanexceptiontobegivenanytypeA.
Rule T-Try embraces explicit success continuations as advocated byBenton and Kennedy[2001],
bindingaresultinM ifL evaluatessuccessfully.TheT-Cancelruleexplicitlydiscardsanendpoint.
Naïvelyimplemented,cancellationviolatesprogress:athreadcoulddiscardanendpoint,leavinga
peerwaitingforever.Weavoidthispitfallbyraisinganexceptionwhenacommunicationaction
would wait forever due to cancellation.

2.3   Operational Semantics
We now give a small-step operational semantics for EGV.


, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 9

Exceptional Asynchronous Session Types                                                                                         9


      Runtime Types                                 R ::=S|  S♯
      Names                                        a,b,c
      Terms                                              M ::=···| a
      Values                                    U,V,W ::=a|λx.M|   ()|   (V,W )|  inlV|  inrV
      Configurations      C,D,E ::= (νa)C|C∥D|ϕM|  halt| a| a(−→V )↭b(−→W )
      Thread Flags                ϕ ::=•|◦
      Top-level threads       T ::=•M|  halt
      Auxiliary threads       A ::=◦M| a| a(−→V )↭b(−→W )
      Type Environments                     Γ ::=···| Γ,a :S
      Runtime Type Environments        ∆ ::=·| ∆,a : R
      Evaluation Contexts                        E ::=[]|  E M| V E
                                                 |  let () = E inM|   (E,M)|   (V,E)|  let (x,y) = E inM
                                                 |  inlE|  inrE|  caseE of{inlx7→ M;inrx7→ N}
                                                 |  forkE|  sendE M|  sendV E|  receiveE|  closeE
                                                 |  cancelE|  tryE asx inM otherwiseN
      Pure Contexts                                  P ::=[]|  P M| V P
                                                 |  let () = P inM|   (P,M)|   (V,P)|  let (x,y) = P inM
                                                 |  inlP|  inrP|  caseP of{inlx7→ M;inrx7→ N}
                                                 |  forkP|  sendP M|  sendV P|  receiveP|  closeP
                                                 |  cancelP
      Thread Contexts       F ::=ϕE
      Configuration Contexts     G ::=[]|   (νa)G|G∥C

Syntactic Sugar
                                    V≜ a1∥···∥ an  wherefn(V ) ={ai}i
                                     P≜ a1∥···∥ an  wherefn(P) ={ai}i
                                     E≜ a1∥···∥ an  wherefn(E) ={ai}i


                                              Fig. 5. Runtime Syntax





   Runtime Syntax. Fig.5showsthe runtime syntax of EGV. We write        S♯ for the type ofa channel
which canbe split intotwo endpoints oftypesS and                S. RuntimetypesR areeither session types or
channeltypes.Weextendthesyntaxoftermstoincludenamesrangedoverbya,b,c.Depending
on context, a namea is variously used to identify a channel of typeS♯ and each of its endpoints of
typeS and    S.Valuesarestandard.Thesemanticsmakes useof configurations,whicharesimilarto
π-calculusprocesses: (νa)C bindsnameainconfigurationC,andC∥D istheparallelcomposition
ofconfigurationsC andD.ProgramthreadstaketheformϕM,whereϕ isathreadflagidentifying
whetherthetermisthemainthread (•),whichreturnsatop-levelresult,orachildthread (◦),which
does not, and must return the unit value. A configuration has at most one main thread. As well
as program threads, configurations include three special forms of thread. A zapper thread ( a)
managesanendpointa thathasbeencancelled,andisusedtopropagatefailure.A halted thread
(halt) arises when the main thread has crashed due to an uncaught exception. A buffer thread
(a(−→V )↭b(−→W )) models asynchrony:−→V and−→W are sequences of values ready to be received along
endpointsa andb respectively. We find it useful to distinguish top-level threadsT (main threads
and halted threads) from auxiliary threadsA (child threads, zapper threads, and buffer threads).


                                                               , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 10

10                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

Term Reduction                                                                                                                               M−→M N

              E-Lam                                                                                          (λx.M)V−→M  M{V/x}
              E-Unit                                          let () =  () inM−→M  M
              E-Pair                             let (x,y) =  (V,W ) inM−→M  M{V/x,W/y}
              E-Inl        caseinlV of{inlx7→ M;inry7→ N}−→M  M{V/x}
              E-Inr       caseinrV of{inlx7→ M;inry7→ N}−→M  N{V/y}
              E-Val                     tryV asx inM otherwiseN−→M  M{V/x}
              E-Lift                                                           E[M]−→M  E[M′],    ifM−→M M′
Configuration Equivalence                              C≡D
          C∥  (D∥E)≡  (C∥D)∥E    C∥D≡D∥C                            (νa)(νb)C≡  (νb)(νa)C

                                  C∥  (νa)D≡  (νa)(C∥D),     ifa< fn(C)

    a(−→V )↭b(−→W )≡b(−→W )↭a(−→V )   ◦ ()∥C≡C                    (νa)(νb)( a∥ b∥ a(ϵ)↭b(ϵ))∥C≡C
Configuration Reduction                              C−→D
E-Fork     F[fork (λx.M)]−→     (νa)(νb)(F[a]∥◦M{b/x}∥ a(ϵ)↭b(ϵ)),    wherea,b are fresh
E-Send           F[sendU a]∥ a(−→V )↭b(−→W )−→F[a]∥ a(−→V )↭b(−→W·U )
E-Receive        F[receivea]∥ a(U·−→V )↭b(−→W )−→F[(U,a)]∥ a(−→V )↭b(−→W )
E-Close                (νa)(νb)(F[closea]∥F′[closeb]∥ a(ϵ)↭b(ϵ))−→F[()]∥F′[()]
E-Cancel                 F[cancela]−→F[()]∥ a
E-Zap                        a∥ a(U·−→V )↭b(−→W )−→  a∥ U∥ a(−→V )↭b(−→W )
E-CloseZap         F[closea]∥ b∥ a(ϵ)↭b(ϵ)−→F[raise]∥ a∥ b∥ a(ϵ)↭b(ϵ)
E-ReceiveZap      F[receivea]∥ b∥ a(ϵ)↭b(−→W )−→F[raise]∥ a∥ b∥ a(ϵ)↭b(−→W )
E-Raise      F[tryP[raise]asx inM otherwiseN]−→F[N]∥ P
E-RaiseChild                 ◦P[raise]−→  P
E-RaiseMain                 •P[raise]−→  halt∥ P
E-LiftC                     G[C]−→G[D],     ifC−→D
E-LiftM                                    ϕM−→ ϕM′,        ifM−→M M′


                      Fig. 6. Reduction and Equivalence for Terms and Configurations



  Environments. We extend type environmentsΓ to include runtime names of session type and
introduce runtime type environments∆, which type both buffer endpoints of session type and
channels of typeS♯ for someS, but not object variables.

  Contexts. Evaluation contexts E are set up for standard left-to-right call-by-value evaluation.
Pure contextsP are those evaluation contexts that include no exception handling frames. Thread
contextsF support reduction in program threads. Configuration contextsG support reduction
underν-binders and parallel composition.

  Free Names. We let the meta operation fn(−) denote the set of free names in a term, type
environment, buffer environment, value, configuration, pure context, or evaluation context.

  Syntactic Sugar. We follow the standard convention that parallel composition of configurations
associatestotheright.Wewrite V, P,and E,asshorthandfortheparallelcompositionofzapper
threads for each free name in valuesV, pure contextsP, and evaluation contextsE, respectively.


, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 11

Exceptional Asynchronous Session Types                                                                                       11

  Following prior work on linear functional languages with session types [Gay and Vasconcelos
2010;LindleyandMorris2015,2016,2017],wepresentthesemanticsofEGVviaadeterministic
reduction relation on terms (−→M), an equivalence relation on configurations (≡), and a nonde-
terministicreduction relationon configurations(−→).We write=⇒forthe relation≡−→≡.Fig.6
presents reduction and equivalence rules for terms and configurations.
  Term Reduction. Reduction on terms is standard call-by-valueβ-reduction.
  ConfigurationEquivalence. Arunningprogramcanmakeuseofthestandardstructuralπ-calculus
equivalencerules[Milner1999]ofassociativityandcommutativityofparallelcomposition,name
restrictionreordering, andscopeextrusion.Formally,equivalenceisdefinedasthesmallest con-
gruencerelationsatisfying theequivalenceaxioms inFigure6.Weincorporate afurther ruleto
allow buffers to be treated symmetrically and two garbage collection rules, allowing completed
child threads and cancelled empty buffers to be discarded.
  Communication and Concurrency. The E-Fork rule createstwo freshnames for eachendpoint of
achannel,returningonenameandsubstitutingtheotherinthebodyofthespawnedthread,as
wellascreatingachannelwithtwoemptybuffers.TheE-SendandE-Receiverulessendtoand
receive from a buffer. The E-Closerule discards an empty buffer once a session is complete.
  Cancellation. TheE-Cancelrulecancelsanendpointbycreatingazapperthread.TheE-Zaprule
ensuresthatwhenanendpointiscancelled,allendpointsinthebufferofthecancelledendpointare
alsocancelled:itdequeuesavaluefromtheheadofthebufferandcancelsanyendpointscontained
within the dequeued value ( U). It is applied repeatedly until the buffer is empty.
  Raising Exceptions. FollowingMostrousandVasconcelos[2014],anexceptionisraisedwhenit
would be otherwise impossible for a communication action to succeed. The E-ReceiveZap rule
raises an exception if an attempt is made to receive along an endpoint whose buffer is empty and
whosepeerendpointhasbeencancelled.Similarly,E-CloseZapraisesanexceptionifanattemptis
made to closea channel where thepeerendpoint has been cancelled. Thereis no rule for thecase
whereathreadtriestosendavaluealongacancelledendpoint;thefreenamesinthecommunicated
valuemusteventuallybecancelled,butthisis achieved throughE-Zap.Wechoosenottoraise an
exception in this case since to do so would violate confluence, which we discuss in more detail
in §3.4. Not raising exceptions on sends to dead peers is standard in languages such as Erlang.
  Handling Exceptions. TheE-Raiseruleinvokestheotherwiseclauseifanexceptionisraised,
while also cancelling all endpoints inthe enclosing pure context. If an unhandled exception occurs
in a child thread, then all free endpoints in the evaluation context are cancelled and the thread
is terminated (E-RaiseChild). If the exception is in the main thread then all free endpoints are
cancelled and the main thread reduces to halt (E-RaiseMain).

2.4   Synchrony
As we areinterested in writing distributedapplications, we consider asynchronoussession types.
However,oursemanticsadaptsstraightforwardlytothesynchronoussetting,whereasendtoa
cancelled peer must also raise an exception:
              E-SyncComm F[sendV a]∥F′[receivea]−→F[a]∥F′[(V,a)]
              E-SyncSendZap     F[sendV a]∥ a−→F[raise]∥ V∥ a∥ a
              E-SyncRecvZap    F[receivea]∥ a−→F[raise]∥ a∥ a
                                            (νa)( a∥ a)∥C≡C



                                                           , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 12

12                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

Term Typing           Γ⊢ M :A          Session Slicing          S/−→A   Queue Typing                       Γ⊢−→V :−→A
            T-Name                                  S/ϵ =S
                                                                                          Γ1⊢V :A      Γ2⊢−→V :−→A
            a:S⊢a:S                         !A.S/A·−→A =S/−→A             ·⊢ϵ :ϵ            Γ1,Γ2⊢V·−→V :A·−→A

Configuration Typing                                                                                                 Γ;∆⊢ϕC
                        T-Nu                                 T-Mix
                         Γ;∆,a :S♯⊢ϕC                         Γ1;∆1⊢ϕ1C      Γ2;∆2⊢ϕ2D
                          Γ;∆⊢ϕ  (νa)C                         Γ1,Γ2;∆1,∆2⊢ϕ1+ϕ2C∥D

         T-Connect1                                              T-Connect2
         Γ1,a :S;∆1⊢ϕ1C      Γ2;∆2,a :       S⊢ϕ2D               Γ1;∆1,a :  S⊢ϕ1C      Γ2,a :S;∆2⊢ϕ2D
             Γ1,Γ2;∆1,∆2,a :S♯⊢ϕ1+ϕ2C∥D                              Γ1,Γ2;∆1,∆2,a :S♯⊢ϕ1+ϕ2C∥D

                                                                               T-Buffer
 T-Main             T-Child                                                                 S/−→A = S′/−→B
  Γ⊢ M :A            Γ⊢ M : 1          T-Halt             T-Zap                     Γ1⊢−→V :−→A      Γ2⊢−→W :−→B

  Γ;·⊢••M           Γ;·⊢◦◦M            ·;·⊢• halt         a :S;·⊢◦ a            Γ1,Γ2;a :S,b :S′⊢◦ a(−→V )↭b(−→W )

Flag Combination           ϕ1 +ϕ2 =ϕ3                             Session Type Reduction                       S−→S′
          •+◦ =•  ◦+• =•                                                   ?A.S−→S                !A.S−→S
          ◦+◦ =◦  •+• undefined
Environment Reduction                                                                                        Γ;∆−→Γ′;∆′
                S−→S′                                 S−→S′                                 S−→S′
       Γ,a :S;∆−→Γ,a :S′;∆                   Γ;∆,a :S−→Γ;∆,a :S′                  Γ;∆,a :S♯−→Γ;∆,a :S′♯


                                               Fig. 7. Runtime Typing


3   METATHEORY
Even in the presence of channel cancellation and exceptions, EGV retains GV’s strong metathe-
ory [Lindley and Morris2015]. The central property of session-typed systems is session fidelity:
all communication follows the prescribed session types. Session fidelity follows as a corollary of
preservation of configuration typing under reduction.
   Sessioncalculiwithrootsinlinearlogicaredeadlock-freeasinterpretingthelogicalcutruleasa
combinationofnamerestrictionandparallelcompositionnecessarilyensuresacyclicity[Caires
and Pfenning2010]. It is also possible to use deadlock-freedom to derive a global progress result.
We prove that global progress holds even in the presence of channel cancellation. (Our proof is
direct,notrequiringcatalyserprocesses[Carboneetal .2014;MostrousandVasconcelos2014].)We
also prove that EGV is confluent and terminating.

3.1   Runtime Typing
Tostateourmainresultswerequiretypingrulesfornamesandconfigurations.Thesearegiven
in Fig.7. As names        a must be substituted for variables at runtime, we extend the term typing
rules with T-Name. The configuration typing judgement has the shapeΓ;∆⊢ϕC, which states
that under type environment Γ, runtime environment ∆, and thread flagϕ, configurationC is


, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 13

Exceptional Asynchronous Session Types                                                                                       13

well-typed.Weadditionally requirethatfn(Γ)∩fn(∆) =∅.Thread flagsensure thattherecan be
at most one top-level thread which can return a value:• denotes a configuration with a top-level
thread and◦ denotes a configuration without. The main thread returns the result of running a
program. Any configurationC such thatΓ;∆⊢•C has exactly one main thread or halted thread
as a subconfiguration. We writeΓ;∆⊢•C : A whenever the derivation ofΓ;∆⊢•C contains a
subderivation of the form
                                     Γ′⊢ M :A
                                     Γ′;.⊢••M        or          ·;·⊢• halt
Wesay that aC is a ground configuration if thereexistsAsuch that·;·⊢•C :AandAcontains no
session types or function types.
   TheT-Nuruleintroducesachannelname;T-Connect1 andT-Connect2 connecttwoconfig-
urations over a channel; and T-Mix composes two configurations that share no channels. The
latter three rules use the + operator to combine the flags from subconfigurations. The T-Main
andT-Childrulesintroducemainandchildthreads.Childthreadsalwaysreturntheunitvalue.
The T-Halt rule types the halt configuration, which signifies that an unhandled exception has
occurred in the main thread.The T-Zap rule types a zapper thread, given a single name in the type
environment. TheT-Bufferrule ensures that buffers containvalues corresponding to the session
types of their endpoints. This is the only rule that consumes names from the runtimeenvironment.
Buffersrelyontwoauxiliaryjudgements.ThequeuetypingjudgementΓ⊢−→V :−→A statesthatunder
type environmentΓ, the sequence of values−→V  have types−→A. The session slicing operator S/−→A
captures reasoning about session types discounting values contained in the buffer. The session
typesoftwobufferendpointsarecompatibleiftheyaredualuptovaluescontainedinthebuffer.
Thepartialityof theslicingoperatorcoupled withthedualityconstraintensures thatatleastone
queueinabufferisalwaysempty. AppendixAshowsanexampleconfigurationtypingderivation.

3.2   Preservation
Preservation for the functional fragment of EGV is standard.
   Lemma 3.1 (Preservation (Terms)).  IfΓ⊢ M :AandM−→M M′, thenΓ⊢ M′ :A.
   Given a relationR, we writeR? for its reflexive closure. We writeΨ for the restriction of type
environmentsΓ to contain runtime names but no variables:
                                                Ψ::=·| Ψ,a :S
   Preservation of typing by configuration reduction holds only for closed configurations.
   Theorem 3.2 (Preservation).  If Ψ;∆⊢ϕC andC−→C′, then there exist Ψ′,∆′ such that
Ψ;∆−→? Ψ′;∆′ andΨ′;∆′⊢ϕC′.
   Proof. By induction on the derivation ofC−→C′, making use of Lemma3.1, and lemmas for
subconfigurationtypeability andreplacement.Theproof casescanbefound inAppendixC.1.              □

   Typing and Configuration Equivalence. As is common in logically-inspired session-typed func-
tional languages [Lindley and Morris2015,2017], typeability of configurations is                              not preserved
by equivalence. Consider Γ;∆⊢ϕ    (νa)(νb)(C∥    (D∥E)) with a∈  fn(C), b∈  fn(D), and
a,b∈ fn(E). ButΓ;∆ ⊬ϕ   (νa)(νb)((C∥D)∥E). Fortunately this looseness of the equivalence
relation is unproblematic: we may always safely re-associate parallel composition (for example,
Γ;∆⊢ϕ   (νa)(νb)((C∥E)∥D);  see AppendixC.1), and any reduction sequence which uses
ill-typed equivalences may be replaced by one that does not.


                                                             , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 14

14                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

  Theorem3.3(PreservationModuloEqivalence).  IfΨ;∆⊢ϕC,C≡D,andD−→D′,then:
  (1)There exists some E≡D and someE′ such thatΨ;∆⊢ϕE andE−→E′
  (2)There exist    Ψ′,∆′ such thatΨ;∆−→? Ψ′;∆′ andΨ′;∆′⊢ϕE′
  (3)D′≡E′
  Proof. The only non-trivial reductions are those involving a synchronisation with a buffer
(E-Send, E-Receive, E-Close, E-Zap, E-CloseZap, E-ReceiveZap).Theonlyequivalencerulethat
can lead to an ill-typed configuration is associativity of parallel composition
                                       C∥  (D∥E)≡  (C∥D)∥E
where both compositionsarise fromthe T-Connect1 and T-Connect2 rules. Theonly reasonto
apply theassociativity rule from left-to-rightis to enable threadsinsideC andD to synchronise.
Butforsynchronisationtobepossibletheremustexistanamea suchthata∈ fn(C) anda∈ fn(D).
Because the left-hand-side of the equation is well-typed, we know thatC andE have no names in
common,thatD andE shareaname,andthattheright-hand-sidemustbewell-typedasthereis
still exactly one channel connecting each of the parallel compositions. The argument for applying
therulefromright-to-leftissymmetric.Insummary,anyill-typeduseofequivalenceisuseless.   □

3.3   Progress
ToprovethatEGVenjoysastrongnotionofprogressweidentifya canonical form forconfigura-
tions. We prove that every well-typed configuration is equivalent to a well-typed configuration
incanonicalform,andthatgroundconfigurationscanalwayseitherreduce,orareequivalentto
either a value orhalt.
  The functional fragment of EGV enjoys progress.
  Lemma 3.4 (Progress: Open Terms).  IfΨ⊢ M :A, then either:
    • M is a value;
    • there exists someM′ such thatM−→M M′; or
    • there existE,N such thatM can be writtenE[N], whereN is eitherraise or a communication /
       concurrency primitive of the form:forkV,sendV W,receiveV,closeV, orcancelV.
  Proof. By induction on the derivation ofΨ⊢ M :A.                                                    □
  Toreasonaboutprogressofconfigurations,wecharacterisecanonicalforms,whichmakeexplicit
the property that at most one name is shared between threads. Recall thatA ranges over auxiliary
threads andT over top-level threads (Fig.5). Let  M range over configurations of the form:
                                            A1∥···∥Am∥T
  Definition 3.5 (Canonical Form).  AconfigurationC isin canonical form ifthereisasequenceof
namesa1,...,an, a sequence of configurationsA1,...,An, and a configurationM, such that:
                        C =  (νa1)(A1∥  (νa2)(A2∥···∥  (νan)(An∥M)...))
whereai∈ fn(Ai) for eachi∈ 1..n.
  The following lemma implies that communication topologies are always acyclic.
  Lemma 3.6.  IfΓ;∆⊢ϕC andC =G[D∥E], then fn(D)∩fn(E) is either∅ or{a} for somea.
  Proof. By induction on the derivation of Γ;∆⊢ϕC; the only interesting rules are those for
parallel composition. Asthe environments are well-formed, fn(Γ)∩fn(∆) =∅. Thus, T-Connect1
andT-Connect2 allowexactlyonenametobeshared,whereasT-Mixforbidssharingofnames.   □


, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 15

Exceptional Asynchronous Session Types                                                                                       15

  All well-typed configurations can be written in canonical form.
  Theorem 3.7 (Canonical Forms).  GivenC such thatΓ;∆⊢•C, there exists someD≡C such
thatΓ;∆⊢•D andD is in canonical form.
  Proof. Byinductiononthecountofν-boundvariables,followingLindleyandMorris[2015]and
makinguseofLemma3.6.TheadditionalfeaturesofEGVdonotchangetheessentialargument.
The full proof can be found in AppendixC.2.                                                                            □
  Next,wecharacterisethreadswhicharereadytoperformacommunicationactiononanendpoint.
  Definition 3.8.  WesaythattermM is ready to perform an action on namea ifM isabouttosend
on, receive on, close, or cancela. Formally:
   ready(a,M)≜∃E.(M = E[sendV a])∨ (M = E[receivea])∨ (M = E[closea])∨ (M = E[cancela])
  Usingthenotionofareadythread,wemayclassifyanotionofprogressforopenconfigurations.
  Theorem 3.9 (Progress: Open).  SupposeΨ;∆⊢•C, whereC is in canonical form.
  LetC =  (νa1)(A1∥  (νa2)(A2∥···∥  (νan)(An∥M)...)).
  Either there exists someC′ such thatC =⇒C′, or:
  (1)For     1≤ i≤ n, each auxiliary threadAi is either:
       (a) achildthread◦M forwhichthereexistsa∈{aj| 1≤ j≤ i}∪fn(Ψ) suchthatready(a,M);
       (b)a zapper thread  ai; or
       (c)a buffer.
  (2)M =A′1∥···∥A′m∥T such that for1≤ j≤m:
       (a)A′j is either:
         (i) achildthread◦N withN =  ()orready(a,N )forsomea∈{ai| 1≤ i≤ n}∪fn(Ψ)∪fn(∆);
         (ii)a zapper thread   a for somea∈{ai| 1≤ i≤ n}∪fn(Ψ)∪fn(∆); or
         (iii)a buffer.
       (b) EitherT =•N, where N is either a value or ready(a,N ) for some a∈{ai| 1≤ i≤
         n}∪fn(Ψ)∪fn(∆); orT= halt.
  Proof. Theresultfollowsfromamoreverbose,butfiner-grained,propertywhichweproveby
induction on the derivation ofΨ;∆⊢•C. Full details are in AppendixC.3.                                       □
  This theorem tells us that open reduction cannot “go wrong”. A progress theorem states that
eitherreductionispossibleortheconfigurationisavalue.Conditions1(a)(b)(c)and2(a)(b)constitute
a suitable generalisation of ‘value’.
  By restricting attention to closed environments, we obtain a tighter progress property.
  Theorem 3.10 (Progress: Closed).  Suppose·;·⊢•C whereC is in canonical form.
  LetC =  (νa1)(A1∥  (νa2)(A2∥···∥  (νan)(An∥M)...)).
  Either there exists someC′ such thatC =⇒C′, or:
  (1)For     1≤ i≤ n, each auxiliary threadAi is either:
       (a)a child thread ◦M for someM such that ready(ai,M); or
       (b)a zapper thread  ai; or
       (c)a buffer.
  (2)Either M =•W for some valueW, orM = halt.
  The above progress results do not specifically mention deadlock. However, Lemma3.6ensures
deadlock-freedom.Nevertheless,communicationcanstillbeblockedifanendpointappearsinthe
value returned bythe mainthread. Aconservative wayof disallowingendpoints inthe resultis to


                                                             , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 16

                                                                     16                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

                                                                     insistthat thereturntype oftheprogram befreeof sessiontypes andfunctiontypes(closures may
                                                                     capture endpoints). All configurations of such a programs are ground configurations.
                                                                        Theorem3.11 (GlobalProgress).  SupposeC is a ground configuration. Either there exists some
                                                                     C′ such thatC =⇒C′; orC≡•V; orC≡ halt.
                                                                        Proof. AsaconsequenceofTheorem3.10,eitherthereexistssome   C′ suchthatC =⇒C′,or
                                                                     C Y=⇒ and each threadAi must be a zapper, a buffer, or ready to perform an action. IfC Y=⇒,
                                                                     sinceC isground, byLemma3.6,we havethatnothread canbereadyto performanaction. Thus,
                                                                     eachAi must be either◦(), azapper, oran empty buffer. The resultthen follows bythe garbage
                                                                     collection congruences of Fig.6.                                                                                       □

                                                                     3.4   Confluence
                                                                     EGV enjoys a strong form of confluence known as the diamond property [Barendregt1984].
                                                                        Theorem 3.12 (Diamond Property).  IfΨ;∆⊢ϕC, andC =⇒D1, andC =⇒D2, then either
                                                                     D1≡D2, or there exists someD3 such thatD2 =⇒D3 andD2 =⇒D3.
                                                                        Proof. First, note that−→M is entirely deterministic and hence confluent due to the call-by-
                                                                     value,left-to-rightorderingimposedbyevaluationcontexts.Bylinearity,weknowthatendpointsto
                                                                     differentbuffers maynotbeshared,so itfollowsthat communicationactionson differentchannels
                                                                     may be performedin anyorder. Asynchronyand cancellationintroducetwo criticalpairs which
                                                                     may be resolved in a single step; see AppendixC.4for details.                                                      □
                                                                        Remark. Thesystembecomesnon-confluentifwechoosetoraiseanexceptionwhensendingto
                                                                     a cancelledbuffer. Supposethat instead ofthe current semantics,we wereto replaceE-Sendwith
                                                                     the following two rules:
                                                                                (νb)(F[sendU a]∥ a(−→V )↭b(−→W )∥ϕM) −→        (νb)(F[a]∥ a(−→V )↭b(−→W·U )∥ϕM)
                                                                                       F[sendU a]∥ b∥ a(−→V )↭b(−→W ) −→ F[raise]∥ b∥ U∥ a(−→V )↭b(−→W )
                                                                      Then, sending and cancelling peer endpoints of a buffer results in a non-convergent critical pair:







                                                                     In either case, the endpoints contained inU will still eventually be cancelled, thus preservation
                                                                     andglobalprogressstillhold.However,thelackofconfluenceaffectsexactly when theexception
                                                                     is raised in contextF. This decision has practical significance, in that it characterises the race
                                                                     between sending a message and propagating a cancellation notification.

                                                                     3.5   Termination
                                                                     As EGV is linear, it has an elementary strong normalisation proof.
                                                                        Theorem 3.13(Strong Normalisation).  IfΨ;∆⊢ϕC, then there are no infinite=⇒ reduction
                                                                     sequences fromC.
                                                                        Proof. Letthesizeofaconfigurationbethesumofthesizesoftheabstractsyntaxtreesofallof
                                                                     thetermscontainedinitsmainthreads,childthreads,andbuffers,moduloexhaustivelyapplying
                                                                     thegarbagecollectionequivalencesfromleft-to-right.Thesizeofaconfigurationisinvariantunder
                                                                     ≡ and strictly decreases under−→, hence=⇒reduction must always terminate.                    □


                                                                     , Vol. 1, No. 1, Article . Publication date: November 2018.

                              (νb)(F[sendU a]∥F′[cancelb]∥ a(−→V )↭b(−→W ))

(νb)(F[a]∥F′[cancelb]∥ a(−→V )↭b(−→W·U ))                 (νb)(F[sendU a]∥F′[()]∥ b∥ a(−→V )↭b(−→W ))

(νb)(F[a]∥F′[()]∥ b∥ a(−→V )↭b(−→W·U ))                  (νb)(F[raise]∥F′[()]∥ b∥ U∥ a(−→V )↭b(−→W ))

## PDF page 17

Exceptional Asynchronous Session Types                                                                                       17

Syntax
               Types                            A,B ::=···| Exn
               Terms                      L,M,N ::=···| X (M)| raiseM| tryLasx inM unlessH
               Exception Handlers         H ::={Xi (xi )7→ Ni}i
Runtime Syntax
                       Evaluation ContextsE ::=···| raiseE| tryE asx inM unlessH
Term typing                                                                                             Σ(X ) =AΓ⊢ M:A
                                                             TP-Try
   TP-Exn                            TP-Raise                                       Γ1⊢ L:A
   Σ(X ) =A      Γ⊢ M:A                Γ⊢ M:Exn                  Γ2,x :A⊢ M:B              (Γ2,yi :Σ(Xi )⊢ Ni :B)i
        Γ⊢X (M):Exn                  Γ⊢ raiseM:A              Γ1,Γ2⊢ tryLasx inM unless{Xi (yi )7→ Ni}i :B
Term and Configuration Reduction                                                                               M−→M NC−→D

    EP-Val                   tryV asx inM unlessH −→M    M{V/x}
    EP-Raise
       F[tryE[raiseX (V )]asx inM unlessH] −→ F[N{V/y}]∥ E     whereX < handled(E)
                                                                                                (X (y)7→ N )∈ H
    EP-RaiseChild     ◦E[raiseX (V )] −→   E∥ V                   whereX < handled(E)
    EP-RaiseMain     •E[raiseX (V )] −→      halt∥ E∥ V        whereX < handled(E)


                                  Fig. 8. User-defined Exceptions with Payloads


Weconjecturethatthestrongnormalisationresultcontinuestoholdinthepresenceofunrestricted
typesorsharedchannelsforsessioninitiation,buttheprooftechniqueisnecessarilymoreinvolved.
WebelievethatalogicalrelationsargumentalongthelinesofPérezetal   .[2012]oraCPStranslation
along the lines ofLindley and Morris[2016] would suffice.

4   EXTENSIONS
4.1   User-defined Exceptions with Payloads
Inordertofocusontheinterplaybetweenexceptionsandsessiontypeswehavethusfarconsidered
handling a single kind of exception. In practice it can be useful to distinguish between multiple
kinds of user-defined exception, each of which may carry a payload.
   ConsideragainhandlingtheexceptionincheckDetails.Anexceptionmayariseifthedatabase
is corrupt, or if there are too many connections. We might like to handle each case separately:

       exnServer4(s)≜
          let ((username,password),s) = receives in
          trycheckDetails(username,password) asresin
             if resthenlets = selectAuthenticateds inserverBody(s)
             elselets = selectAccessDenieds incloses
          unless
              DBCorrupt(y)    7→ cancels; log("Database Corrupt: " + y)
              TooManyConnections(y)7→ cancels; log("Too many connections: " + y)

An exception in checkDetails might be raised by the term raiseDatabaseCorrupt(filename), for
example. Our approach generalises straightforwardly to handle this example.


                                                               , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 18

18                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

   Syntax. Figure 8shows extensions to EGV for exceptions with payloads. We introduce a type of
exceptions, Exn. We assume acountably infinite setX∈E of exception names,and a type schema
functionΣ(X ) =Amapping exception names to payload types. We extend raise to take a term of
typeExnasitsargument.Finally,wegeneralisetryLasxinMotherwiseN totryLasxinMunlessH,
whereH isanexceptionhandlerwithclauses{Xi (yi)7→ Ni}i,suchthatXi isanexceptionname;
yi binds the payload; and Ni is the clause to be evaluated when the exception is raised.
   Typing Rules. The TP-Exn ruleensuresthatan exception’spayload matchesits expectedtype.
The TP-Raiseand TP-Tryrules are the natural extensions of T-Raise andT-Try.
   Semantics. Ourpresentationissimilartooperationalaccountsofeffecthandlers;theformulation
here is inspired by that ofHillerström et al   . [2017]. To define the semantics of the generalised
exceptionhandlingconstruct,wefirstintroducetheauxiliaryfunctionhandled(E),whichdefines
the exceptions handled in a given evaluation context:
           handled(P) =∅               handled(tryE asx inM unlessH) = handled(E)∪dom(H)
           handled(E) = handled(E′),    ifE is not atry andE′ is the immediate subcontext ofE
TheEP-Raiserulehandlesanexception.Thesideconditionsensurethattheexceptioniscaughtby
thenearestmatchinghandlerandishandledbytheappropriateclause.AswithplainEGV,allfree
namesaresafelydiscarded.TheEP-RaiseChildandEP-RaiseMainrulescoverthecaseswherean
exceptionisunhandled.Duetotheuseofthehandledfunctionwenolongerrequirepurecontexts.
AllofEGV’smetatheoreticproperties(preservation,globalprogress,confluence,andtermination)
adapt straightforwardly to this extension.

4.2   Unrestricted Types and Access Points
Unrestricted (intuitionistic) types allow some values to be used in a non-linear fashion. Access
points [Gay and Vasconcelos2010] provide a more flexible method of session initiation than
fork,allowingtwothreadstodynamicallyestablishasession.Bothfeaturesareusefulinpractice:
unrestrictedtypesbecausesomedataisnaturallymulti-use,andaccesspointsbecausetheyadmit
cyclic communication topologies supporting racey stateful servers such as chat servers. Access
points decouplespawningathreadfromestablishingasession.Anaccesspointhastheunrestricted
type AP(S); we write un(A) to mean thatAis unrestricted and un(Γ) if un(Ai) for allxi :Ai∈ Γ.
Figure 9shows the syntax, typing rules, and reduction rules for EGV extended with access points.
   UnrestrictedTypes. Tosupportunrestrictedtypes,weintroduceasplittingjudgement(Γ =Γ1+Γ2),
which allows variables of unrestricted type to be shared across sub-environments, but requires
linear variables to be used only in a single sub-environment. We relax rule T-Var to allow the
use of unrestricted environments, and adapt all rules containing multiple subterms to use the
splitting judgement. We detail T-App in the figure; theadaptations of other rules are similar. While
unrestricted types are useful in general, we show the specific case of unrestricted access points.
   Access points. The spawnM construct spawnsM as a new thread, newS creates a fresh access
point,andrequestM andacceptM generatefreshendpointsthatarematchedupnondeterminis-
tically to form channels. With access points we can macro-express fork:
                  forkM≜ letap = newS inspawn (M  (acceptap)); requestap
   Reduction rules. Weletz rangeoveraccesspointnames. Configuration (νz)C denotesbinding
access point name z inC, and z(X,Y) is an access point with name z and two setsX andY
containing endpoints to be matched.
   Rule E-Spawn creates a new child thread but, unlike fork, returns the unit value instead of
creating a channeland returning an endpoint. Rule E-New creates a new accesspoint with fresh


, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 19

Exceptional Asynchronous Session Types                                                                                       19


Syntax
         Types                                      A::=···|  AP(S)
         Access Point Names                z
         Terms                                     M ::=···| z|  spawnM|  newS|  requestM|  acceptM
         Configurations      C ::=···|   (νz)C| z(X,Y)
         Type Environments             Γ ::=···| Γ,z : AP(S)
         Runtime Type Environments ∆::=···| ∆,z :S
Splitting                                                                                                                 Γ =Γ1 +Γ2

                              un(A)                              Γ =Γ1 +Γ2                         Γ =Γ1 +Γ2
· =·+·         Γ,x :A=  (Γ1,x :A)+ (Γ2,x :A)              Γ,x :A=  (Γ1,x :A)+Γ2            Γ,x :A=Γ1 + (Γ2,x :A)
Typing                                                                                                                     Γ⊢ M :A
          T-Var                            T-App
          x :A∈ Γ        un(Γ)              Γ =Γ1 +Γ2      Γ1⊢ M :A⊸ B      Γ2⊢ N :A
                Γ⊢x :A                                         Γ⊢ M N : B                                          ...

      TA-Spawn                    TA-New                        TA-Reqest                    TA-Accept
          Γ⊢ M : 1                                                Γ⊢ M : AP(S)                 Γ⊢ M : AP(S)
      Γ⊢ spawnM : 1                Γ⊢ newS : AP(S)              Γ⊢ requestM :     S           Γ⊢ acceptM :S
Reduction                                    C−→D
      E-Spawn         F[spawnM] −→ F[()]∥◦M
      E-New           F[newS] −→        (νz)(F[z]∥ z(ϵ,ϵ))                     z is fresh
      E-Accept     F[acceptz]∥ z(X,Y) −→        (νa)(F[a]∥ z({a}∪X,Y))        a is fresh
      E-Reqest    F[requestz]∥ z(X,Y) −→        (νa)(F[a]∥ z(X,{a}∪Y))        a is fresh
      E-Match                          z({a}∪X,{b}∪Y) −→    z(X,Y)∥ a(ϵ)↭b(ϵ)

Configuration Typing                                                                                                 Γ;∆⊢ϕCTA-ConnectN
                                                                                             Γ =Γ1 +Γ2
TA-ApName                       TA-Ap                                                  Γ1,−−→a:S;∆1,−−−→b:T⊢ϕ1C
Γ,z:AP(S);∆,z:S⊢ϕC                                un(Γ)                                Γ2,−−−→b:T;∆2,−−→a:S⊢ϕ2D
      Γ;∆⊢ϕ  (νz)C              Γ,z:AP(S);X:S,Y:S,z:S⊢◦z(X,Y)                              −−−→  −−−−→
                                                                                Γ;∆1,∆2,  a:S♯,  b:T♯⊢ϕ1+ϕ2C∥D


                                                Fig. 9. Access Points

namez. Rules E-Accept and E-Reqest create a fresh namea, returning the newly-created name
to the thread, and adding the name to setsX andY respectively. Rule E-Match matches two
endpointsa andb contained inX andY, and creates an empty buffera(ϵ)↭b(ϵ).
   Configurationtyping. ConfigurationtypingjudgementsagainhavetheshapeΓ;∆⊢ϕC.Whereas
Γ may contain unrestricted variables,∆ remains entirely linear.
   Read bottom-up, rule TA-ApName adds an unrestricted referencez : AP(S) toΓ, and a linear
entryz :S to∆.RuleTA-Aptypesanaccesspointconfiguration.WewriteX :S fora1 :S,...,an :S,
whereX ={a1,...,an}. Foran accesspointz(X,Y) to bewell-typed,∆ must containz :S, along
withthenamesinXhavingtypeS andthenamesinY havingtypeS.RuleT-ConnectNgeneralises
T-Connect1 andT-Connect2 toallowanynumberofchannelstocommunicateacrossabuffer;
this therefore introduces the possibility of deadlock.


                                                               , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 20

                                                                     20                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

                                                                        Interaction with cancellation. Weneed noadditional reductionrules toaccount forinteraction
                                                                     between access points and channel cancellation. Should an endpoint waiting to be matched be
                                                                     cancelled, it is paired as usual, and interaction with its associated buffer raises an exception:
                                                                                a∥F[receiveb]∥ z({a},{b}) =⇒ a∥F[receiveb]∥ z(ϵ,ϵ)∥ a(ϵ)↭b(ϵ)
                                                                                                                    =⇒ a∥F[raise]∥ b∥ z(ϵ,ϵ)∥ a(ϵ)↭b(ϵ)
                                                                     We might replace E-Match with the following three rules.
                                                                      (νa)(νb)(ϕ1M∥ϕ2N∥ z({a}∪X,{b}∪Y))−→ (νa)(νb)(ϕ1M∥ϕ2N∥ z(X,Y)∥ a(ϵ)↭b(ϵ))
                                                                                             (νa)( a∥ z({a}∪X,Z)−→z(X,Y)
                                                                                             (νa)( a∥ z(X,{a}∪Z)−→z(X,Y)
                                                                     The first matches only non-cancelled endpoints, whereas the second and third clean up cancelled
                                                                     endpoints whichare presentin the accesspoint. These rulesare anoptimisation and notrequired
                                                                     to retain preservation or the weaker notion of progress that holds in the presence of access points.
                                                                     These rules introduce a further non-convergent critical pair:







                                                                        Metatheory. By decoupling process and channel creation we lose the guarantee that the com-
                                                                     municationtopologyisacyclic,andthereforeintroducethepossibilityofdeadlock.Preservation
                                                                     continuestohold—infact,wegainastrongerpreservationresultsincetheuseof TA-ConnectN
                                                                     allows typeability to be preserved by equivalences.
                                                                        Theorem 4.1 (Preservation Modulo Eqivalence (Access Points)).
                                                                     IfΨ;∆⊢ϕC andC =⇒D, then there existΨ′,∆′ such thatΨ;∆−→Ψ′;∆′ andΨ′;∆′⊢ϕD.
                                                                        Proof. ByinductiononthederivationofC−→D andpreservationby≡;seeAppendixD.             □
                                                                        Alas,theintroductionofcyclictopologiesandthereforethelossofdeadlock-freedomnecessarily
                                                                     violatesglobalprogress.Nevertheless,aweakerform ofprogressstillholds:ifaconfiguration does
                                                                     not reduce, then it is due to deadlock rather than cancellation.
                                                                        Theorem4.2(Progress(AccessPoints)).  Suppose·;·⊢ϕC andC Y=⇒. Then each thread inC is
                                                                     either a value; a buffer; a zapper thread; an access point; requesting or accepting on an access point; or
                                                                     ready to perform a communication action.
                                                                        IfC contains a threadϕM  and ready(a,M) for some name a, thenC contains some buffer
                                                                     a(ϵ)↭b(−→W ), andC does not contain a zapper thread b.
                                                                        Proof. Wecanproveasimilarpropertyforopenconfigurationsbyinductiononthederivation
                                                                     ofΨ;∆⊢ϕC; the above result arises as a corollary and by inspection of the reduction rules.     □
                                                                        In the presence of access points confluence and termination no longer hold: access points are
                                                                     nondeterministic and can encode higher-order state and hence fixpoints via Landin’s knot.

                                                                     4.3   Recursive Session Types
                                                                     Recursivesessiontypessupportrepeatingprotocols.TheextensionofEGVwithrecursivesession
                                                                     typesisstandard[LindleyandMorris2016,2017]andorthogonaltothemainideasofthispaper,so
                                                                     wedonotspelloutthedetailshere.Theimplementation(§5)doesproviderecursivesessiontypes.


                                                                     , Vol. 1, No. 1, Article . Publication date: November 2018.

                              (νa)(νb)(F[cancela]∥ϕM∥ z({a}∪X,{b}∪Y))

(νa)(νb)(F[()]∥ a∥ϕM∥ z({a}∪X,{b}∪Y)                    (νa)(νb)(F[cancela]∥ϕM∥ a(X,Y)∥ a(ϵ)↭b(ϵ))

       (νb)(F[()]∥ϕM∥ z(X,{b}∪Y))                        (νa)(νb)(F[()]∥ a∥ϕM∥ z(X,Y)∥ a(ϵ)↭b(ϵ))

## PDF page 21

Exceptional Asynchronous Session Types                                                                                       21

5   SESSION TYPES WITHOUT TIERS
In this section we describe our extensions to Links to support exception handling, as well as
extensions to the Links concurrency runtimes to support distribution. Links [Cooper et al . 2007] is
astatically-typed,ML-inspired,impurefunctionalprogramminglanguagedesignedfortheweb.
Links is designed to allow code forall “tiers” of a web application—client, server, and database—to
be written in a single language.Lindley and Morris[2017] extend Links with first-class session
types, relying on lightweight linear typing [Mazurak et al . 2010] and row polymorphism [Rémy
1994]. We extend their work to account for distributed web applications, which amongst other
things necessitates handling failure.

5.1   The Links Model
Linksprovidesauniformlanguageforwebapplications.ClientcodeiscompiledtoJavaScript,server
code isinterpreted, and database queriesare compiled toSQL. Each client andserver has itsown
concurrencyruntime,providinglightweightprocessesandmessagepassingcommunication.Earlier
versions of Links [Cooper et al . 2007] invoked a fresh copy of the server per server request and
communicationbetweenclientandserverwasviaRPCcalls.AdvancessuchasWebSocketsallow
socket-likebidirectionalasynchronouscommunicationbetween clientandserver,inturnallowing
richerapplicationswheredata(forexample,commentsonaGitHubpullrequest)flowsmorefreely
between client and server. Moving to a model based on lightweight threads and session-typed
channelsavoidstheinversionofcontrolinherentinRPC-stylesystems,andallowsdevelopmentto
be driven by the communication protocol.
  Links now adopts apersistent application servermodel, incorporating client-servercommunica-
tion using session-typed channels. Since channels are a location-transparent abstraction, we also
optionally allow the abstraction of client-to-client communication, routed through the server.

5.2   Concurrency
Links provides typed actor-style concurrency where processes have a single incoming message
queueandcansendasynchronousmessages.LindleyandMorris[2017]extendLinkswithsession-
typedchannels,usingLinks’process-basedmodelbutreplacingactormailboxeswithsession-typed
channels. We extend their implementation to support distribution and failure handling.
  Theclientreliesoncontinuation-passingstyle(CPS),trampolining,andco-operativethreading.
Client code is compiled to CPS, and explicityield instructions are inserted at every function
application.Whenaprocesshasyieldedagivennumberoftimes,thecontinuationispushedtothe
backofaqueue,andthenextprocessispulledfromthefrontofthequeue.Whilemodernbrowsers
are beginning to integrate tail-recursion, and we have updated the Links library to support it,
adoption is not yet widespread. Thus, we periodically discard the call stack using a trampoline.
Cooper[2009] discusses the Links client concurrency model in depth. The server implements
concurrency on top of the OCamllwt library [Vouillon2008], which provides lightweight co-
operative threading. At runtime, a channel is represented as a pair of endpoint identifiers:
                                   (Peer endpoint,Local endpoint)
Endpoint identifiers are unique. If a channel  (a,b) exists at a given location, then that location
should contain a buffer forb.

5.3   Distributed Communication
To supportbidirectional communication between client and server we use WebSockets [Fetteand
Melnikov2011]. A WebSocket connection is initiated by a client request to the server. The server
generates a webpage and a unique identifierfor the connection. Messages sent by theserver prior


                                                             , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 22

22                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

totheconnectionbeingestablishedarebufferedanddeliveredonceithasbeenestablished.AJSON
protocol isused to encodemessages such asaccess point operations,remote sessionmessages, and
endpoint cancellation notifications.
  It ispossible thatone client willhold oneendpoint of achannel, andanother client willhold the
other endpoint. In order to provide the illusion of client-to-client communication, we route the
communication between the two clients via the server. The server maintains a map

                                        Endpoint ID7→Location
whereLocationiseitherServerorClient(ID),whereIDidentifiesa particularclient.The map
isupdated ifanew connectionisestablished;an endpointissent aspartof amessage;or aclient
disconnects. The server also maintains a map
                                         Client ID7→ [Channel]
associating eachclient with thepublicly-facing channels residing onthat client, whereChannel
is a pair of endpoints  (a,b) such that b is the endpoint residing on the client. Much like TCP
connections, WebSocket connections raise an event when a connection is disconnected. Upon
receivingsuch anevent,allchannels associatedwith theclient arecancelled,and exceptionsare
invoked as per the exception handling mechanism described in §2and §5.4.
  DistributedDelegation. Itispossibletosendendpointsaspartofamessage.Sessiondelegationin
thepresenceofdistributedcommunicationrequiressomecaretoensurethatmessagesaredelivered
to the correct participant; our implementation adapts the algorithms ofHu et al   . [2008]. Further
details can be found in AppendixE.

5.4   Session Typing with Failure Handling
  Effect Handlers. Effect handlers [Plotkin and Pretnar2013] provide a modular approach to
programmingwith user-defined effects.Exceptionhandlersare aspecialcaseofeffecthandlers.
Consequently, weleveragethe existing implementationof effecthandlers in Links[Hillerström
and Lindley2016;Hillerström et al          . 2017]. In §4we generalise            try− as− in− otherwise− to
accommodateuser definedexceptions. Effecthandlersgeneralise furthertosupport whatamounts
toresumableexceptionsinwhichthehandlerhasaccessnotonlytoapayload,butalsothedelimited
continuation (i.e. evaluation context) from the point at which the exception was raised up to
the handler, allowing effect handlers to implement arbitrary side-effects; not just exceptions. We
translate exception handling as follows.
      JraiseK = doraise   JtryLasx inM otherwiseNK = handleJLKwith
                                                                            returnx7→JMK
                                                                            raiser 7→ cancelr;JNK
Theintroductionformdoopinvokesanoperationop(whichmayrepresentraisinganexceptionor
some other effect). The elimination form handleMwithH runs effect handlerH on the computa-
tionM.IngeneralaneffecthandlerH consistsofa return clause oftheformreturnx7→ N,which
behaves just like the success continuation (x in N) of an exception handler, and a collection of
operation clauses,eachoftheformop⃗pr7→ N,specifyinghowtohandleanoperationanalogously
tohowexceptionhandlerclausesspecifyhowtohandleanexception,exceptthataswellasbinding
payload parameters ⃗p, an operation clause also binds a resumption parameterr. The resumptionr
bindsa closurerepresenting thecontinuation upto thenearest enclosingeffect handler,allowing
control to pass back to the program after handling the effect. In the case of our translation, the
raiseoperationhasnopayload,andratherthaninvokingtheresumptionr wecancelit,assuming
thenaturalextensionofcancellationtoarbitrarylinearvalues,wherebyallfreenamesinthevalue


, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 23

Exceptional Asynchronous Session Types                                                                                       23

are cancelled (r being bound to the current evaluation context reified as a value). A formalisation
oflineareffecthandlersforsessiontypingisoutsidethescopeofthispaperandleftasfuturework.
   As a preprocessing step, before translating to effect handlers, we insert a dummy exception
handler around each forked thread
                         LforkMM⋆ = fork (tryLMM⋆ asx in () otherwise ())
which has the effect of simulating the E-RaiseChild rule, ensuring that unhandled exceptions are
trapped and all endpoints in the context are cancelled if an exception is raised.
   As weare targeting linear effecthandlers, thesharing oflinearvariables betweenthesuccess
and failure continuations of an exception handler is problematic since there is no reason, a priori,
to assume that operations should not be handled more than once. The issue can be resolved by
restricting the typing rule for try in order to disallow any free variables in the continuations:
                                   T-TryRestricted
                                   Γ⊢ L:A       x :A⊢ M:B  ·⊢ N :B
                                    Γ⊢ tryLasx inM otherwiseN :B
Thisrulemaylookoverlyrestrictive,butinfactitstillallowsustosimulatetheunrestrictedrule
via a simple macro translation using a Maybe type:
       LtryLasx inM otherwiseNM† = casetryLLM† asx inSomex otherwiseNoneof
                                                    Somex7→LMM†
                                                    None 7→LNM†
Links performs this translation as another preprocessing step.
   Raising exceptions. An exception may be raised either explicitly through raise (desugared to
do raise), or a blocked receive where the peer endpoint has been cancelled. Thus, we know
statically where exceptions may be raised. To support cancellation of closures on the client, we
adornclosureswithanexplicitenvironmentfieldthatcanbedirectlyinspected.Currently,Links
doesnotclosure-convertcontinuationsontheclient,soweuseaworkaroundtosimulatecancelling
aresumption(asrequiredbythetranslationJ−K).Whencompilingclientcode,foreachoccurrence
of do raise, we compile a function that inspects all affected variables and cancels any affected
endpointsinthecontinuation.Foreachoccurrenceofreceive,wecompileacontinuationtocancel
affected endpoints to be invoked by the runtime system if the receive operation fails.

5.5   Distributed Exceptions
Ourimplementationfullysupportsthesemanticsdescribedin§2.Theconcurrencyruntimeateach
location maintains a set of cancelled endpoints.
   Cancellation. Suppose endpoint a is connected to peer endpoint b. If a is cancelled, then all
endpoints in the queue fora are also cancelled according to the E-Zap rule. Ifa andb are at the
same location,thena is addedto the set ofcancelled endpoints. Ifthey are at differentlocations,
thenacancellationnotificationfora isroutedtob’slocation.Zapperthreadsaremodelledinthe
implementation by recording sets of cancelled endpoints and propagating cancellation messages.
   Failed communications. Again, suppose endpointa is connected to peer endpointb. Should a
processattempttoreadfroma whenthebufferfora isempty,thentheruntimewill checktosee
whetherb isinthesetofcancelledendpoints.Ifso,thena iscancelledandanexceptionisraisedin
the blocked process; if not, the process is suspended until a message is ready. Should the runtime
later addb to the set of cancelled endpoints, then again a is cancelled and an exception raised.
These actions implement the E-ReceiveZap rule.


                                                             , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 24

24                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova




typenameChatClient=!Nickname.
  [&|Join:
         ?(Topic,[Nickname],ClientReceive).ClientSend,
       Nope:End|&];


typenameClientReceive=
  [&|Join    :?Nickname          .ClientReceive,
       Chat    :?(Nickname,Message).ClientReceive,
       NewTopic:?Topic             .ClientReceive,
       Leave   :?Nickname          .ClientReceive
  |&];


typenameClientSend=
  [+|Chat :?Message.ClientSend,
       Topic:?Topic .ClientSend|+];


typenameChatServer=~ChatClient;
typenameWorkerSend=~ClientReceive;
typenameWorkerReceive=~ClientSend;



                                    Fig. 10. Chat Application Session Types


  Disconnection. Tohandle disconnection,theserver maintainsamap fromclientIDsto thelistof
endpoints at the associated client. WebSockets—much like TCP sockets—raise a closed event on
disconnection. Consequently, when a connection is closed, the runtime looks up the endpoints
owned by the terminated client and notifies all other clients containing the peer endpoints.

6   EXAMPLE: A CHAT APPLICATION
In this section we outlinethe design and implementation of a web-based chatapplication in Links
making use of distributed session-typed channels. We write the following informal specification:
    • To initialise, a client must:
       – connect to the chat server; then
       – send a nickname; then
       – receive the current topic and list of nicknames.
    • After initialisation the client is connected and can:
       – send a chat message to the room; or
       – change the room’s topic; or
       – receive messages from other users; or
       – receive changes of topic from other users.
    • Clients cannot connect with a nickname that is already in use in the room.
    • All participants should be notified whenever a participant joins or leaves the room.
  SessionTypes. Wecanencodemuchofthespecificationmorepreciselyasasessiontype,asshown
inFigure10.Theclientbeginsbysendinganickname,andthenofferstheserverachoiceofa   Join
messageoraNopemessage.Intheformercase,theclientthenreceivesatriplecontainingthecurrent
topic, a list of existing nicknames, and an endpoint (of typeClientReceive) for receiving further
updates from the server; and may then continue to send messages to the server as a connected


, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 25

Exceptional Asynchronous Session Types                                                                                       25



funconnect(){
  vars=request(wap);
  varnick=getInputContents(nameBoxId);                                funconnect(){
  vars=send(nick,s);                                                    vars=request(wap);
  offer(s){                                                             varnick=getInputContents(nameBoxId);
    caseJoin(s)->                                                       vars=send(nick,s);
       var((topic,nicks,incoming),s)=                                   var((topic,nicks,incoming),s)=
         receive(s);                                                       receive(s);
       beginChat(topic,nicks,incoming,s)                                beginChat(topic,nicks,incoming,s)
    caseNope(s)->                                                     }
       print("Nickname'"^^nick^^"'alreadytaken")
  }                                                                          (b) Incorrectconnect function
}

                (a) Correctconnect function


                                 Fig. 11. Implementations ofconnect function


client endpoint (of typeClientSend). (Observe the essential use of session delegation.) In the latter
case,communicationisterminated.TheintentionisthattheserverwillrespondwithNopeifaclient
with the supplied nickname is already in the chat room (the details of this check are part of the
implementation, not part of the communication protocol).
   TheClientReceive endpoint allows the client to offer a choice of four different messages:Join,
Chat,NewTopic,orLeave.In eachcase theclient thenreceivesa payload(depending onthe choice, a
nickname,pairofnicknameandchatmessage,ortopicchange)beforeofferinganotherchoice.The
ClientSendendpointallowstheclienttoselectbetweentwodifferentmessages:ChatandNewTopic.In
each casethe client subsequently sendsa payload (achat message or anew topic) before selecting
another choice. The chat server communicates with the client along endpoints with dual types.
   How can session types help? Theconnect function (Fig.11a) is run when a client enters a nick-
name. First, the client requests a fresh channel of typeChatClient from access pointwap of type
AP(ChatServer). Next, the client obtains the content of the DOM input box for the nickname by
callinggetInputContents(nameBoxId), wherenameBoxId is the DOM ID for the nickname entry box.
Next, the client sends the nickname to the server and waits for a response; in the case of aJoin
message,theclientreceivestheroomdataandanincomingmessagechannel,andcallsthebeginChat
function. In the case of aNope message, an error is printed and the session ends.
   Now,suppose the developerforgets to write code to check theserver response (Fig.11b). This
implementation is incorrect sincethereis a communication mismatch: the serveris expecting to
acceptorreject therequesttojointhe room,whereastheclientisexpecting toreceivedataabout
theroom. However,sinces hastypeChatClient butdoes notfollowthe protocol,Linkscatches the
communication mismatch statically. Similarly, Links will statically detect an unused endpoint (e.g.
the developer forgets to finish a protocol) or an endpoint being used more than once, as in §1.2.
   Architecture. Figure12adepictsthearchitectureofthechatapplication.Eachclienthasaprocess
which sends messages over a distributed session channel of typeClientSend to its own worker
processontheserver,whichinturnsendsinternalmessagestoasupervisorprocesscontainingthe
state of the chat room. These messages trigger the supervisor process to broadcast a message to all
chatclientsoverachanneloftype~ClientReceive.Asisevidentfromthefigure,thecommunication
topologyiscyclic;inordertoconstruct thistopologythecodemakesessentialuse ofaccesspoints.


                                                              , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 26

26                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova



                                                               sigworker:(Nickname,WorkerReceive)~>()
                                                               funworker(nick,c){
                                                                 try{
                                                                   offer(c){
                                                                      caseChat(c)->
                                                                        var(msg,c)=receive(c);
                                                                        chat(nick,msg);c
                                                                      caseNewTopic(c)->
                                                                        var(topic,c)=receive(c);
                                                                        newTopic(topic);c
                                                                   }
                                                                 }as(c)in{
                                                                   worker(nick,c)
                                                                 }otherwise{leave(nick)}
                     (a) Architecture                          }

                                                                          (b) Worker Implementation

                     Fig. 12. Chat Application Architecture and Worker Implementation


   Disconnection. Figure12bshowstheimplementationofaworkerprocesswhichreceivesmessages
fromaclient.Theworkertakesthenicknameoftheclient,aswellasachannelendpointoftype
WorkerReceive (which is the dual ofClientSend). The server offers the client a choice of sending a
message (Chat), or changing topic (NewTopic); in each case, the associated data is received and a
message dispatched to the supervisor process by callingchat ornewTopic. When a client closes its
connectiontotheserver,allassociatedendpointsarecancelled.Consequently,anexceptionwill
be raised when evaluating theoffer orreceive expressions. To handle disconnection, we wrap the
function in an exception handler, which recursively callsworker if the interaction is successful, and
notifies the supervisor that the user has left via a call toleave if an exception is raised.

   Additional examples. We have concentrated on the chat server example for exposition, but
have also implemented an extended chat server and a multiplayer game. These can be found
athttp://www.github.com/SimonJF/distributed-links-examples.

7   RELATED WORK
7.1   Session Types with Failure Handling
Carboneetal.[2008]providethefirstformalbasisforexceptionsinasession-typedprocesscalculus.
Ourapproachprovidessignificant simplifications:zapperthreadsprovidea simplersemanticsand
remove the need for their queue levels, meta-reduction relation, and liveness protocol.
   OurworkdrawsonthatofMostrousandVasconcelos[2014],whointroducetheideaofcancella-
tion. Our work differs from theirs in several key ways. Their system is a process calculus; ours is
aλ-calculus.Their channelsaresynchronous; oursareasynchronous. Theirexceptionhandling
construct scopes over a single action; ours scopes over an arbitrary computation.
   Caires and Pérez[2017] describe a core, logically-inspired process calculus supporting non-
determinism and abortable behaviours encoded via a nondeterminism modality. Processes may
either provide or not provide a prescribed behaviour; if a process attempts to consume a behaviour
thatisnotprovided,thenits linearcontinuationissafelydiscardedbypropagatingthefailureof
sessionscontainedwithinthecontinuation.Theirapproachissimilarinspirittoourzapperthreads.


, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 27

Exceptional Asynchronous Session Types                                                                                       27

Additionally, theygive a coreλ-calculus withabortable behavioursand exceptionhandling, and
define a type-preserving translation into their core process calculus.
   Our approach differs in several important ways. First, our semantics is asynchronous, handling
the intricacies involved with cancelling values contained in message queues. Second, we give a
direct semantics to EGV, whereasCaires and Pérezrely on a translation into their underlying
processcalculus.Third,tohandlethepossibilityofdisconnection,ourcalculusallows any channel
to be discarded, whereas they opt for an approach more closely resembling checked exceptions,
aided by a monadic presentation.
   Theaboveworksarealltheoretical.Backedbyourtheoreticaldevelopment,ourimplementation
integrates session types and exceptions, extending Links.
   Multiparty Session Types. Fowler[2016] describesan Erlangimplementationof theMultiparty
Session Actor framework proposed byNeykova and Yoshida[2014,2017b] with a limited form of
failure recovery;Neykova and Yoshida[2017a] present a more comprehensive approach, based on
refining existingErlang supervision strategies.Chenet al.[2016]introduce a formalism basedon
multipartysessiontypes[Hondaetal .2016]thathandlespartialfailuresbytransformingprograms
todetectpossiblefailuresatasetofstaticallydeterminedsynchronisationpoints.Theseapproaches
rely on a fixed communication topology. Delegation implies location transparency, thus we must
consider dynamic topologies.Adameit et al            . [2017] describe a synchronous multiparty session
calculus to handle link failures in distributed systems. They introduce optional blocks, inspired by
subsessions[DemangeonandHonda2012];progressismaintainedbyspecifyingasetofdefault
values to use should the subsession fail.

7.2   Session Types and Distribution
Huetal.[2008]introduceSessionJava(SJ),whichallowsdistributedsession-basedcommunication
intheJava programminglanguage.Huetal.arethefirst topresentthechallengesof distributed
delegation along with distributed algorithms which address those challenges. We adapt their
algorithms to web applications. SJ provides statically scoped exception handling, propagating
exceptions to ensure liveness, but this feature is not formalised.
   Scalas and Yoshida[2016] introduce          lchannels, a library implementation of session types in
Scala; their approach detectsduplicate endpoint use at runtime. By virtueof the translation into
thelinearπ-calculusintroducedbyKobayashi[2002]andlaterexpandedonbyDardhaetal                              .[2017],
lchannelsisparticularlyamenabletodistribution.Scalasetal                   .[2017]builduponthisapproachto
translate a multiparty session calculus into the linearπ-calculus, providing the first distributed
implementation of multiparty session types to support delegation.

7.3   Session Types via Affine Types
Rust[MatsakisandKlockII2014]provides            ownershiptypes[Clarke2003],ensuringthatanobjecthas
atmostoneowner.Jespersenetal.[2015]useRust’sownershiptypestoencodeaffinesessiontypes,
but since affine endpoints can be discarded implicitly, their library does not guarantee progress.
Althoughit isnotpossible todistinguishbetween dynamicfailureand adeveloper forgettingto
finish an implementation, our semantics can be implemented using Rust’s destructor mechanism,
enabling a progress property [Kokke2018].

8   CONCLUSION AND FUTURE WORK
Sessiontypesallowprotocolconformancetobecheckedstatically.Theprevailingconsensushas
hitherto been to require that endpoints be used linearly to enforce session fidelity and prevent
prematurediscardingofopenchannels.Wehavearguedthatinordertowriterealisticapplications


                                                              , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 28

28                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

in the presence of distribution and failure, linearity should be supplemented with an explicit
cancellation operation. We show that, even in the presence of channel cancellation, our core
calculus is well-behaved, being deadlock-free, type sound, confluent, and terminating.
   In tandem with the formal development, we have developed an extension of the Links pro-
gramming language to support distributed session-based communication for web applications,
thus providing the first implementation of asynchronous session types with failure handling in a
functionalprogramming language.Ourimplementationleverages recentworkoneffect handlers.

   Future work. Our implementation combines linearity and effect handlers. Linear effect handlers
arenew,andaripeareaofstudyintheirownright;weplantoformalisesession-typedconcurrency
andfailurehandlingdirectlyintermsoflineareffecthandlers.Multipartysessiontypes[Honda
etal.2016]areyettobeincludedasafirst-classconstructofacorefunctionallanguage.Anatural
starting point is to identify aλ-calculus into which we can translate the MCP calculus ofCarbone
et al.[2016] and then investigate how our approach adapts to the multiparty setting.

ACKNOWLEDGMENTS
Thanks to James McKinna and the anonymous reviewers for detailed comments and suggestions.
This work was supported by EPSRC grants EP/L01503X/1 (EPSRC CDT in Pervasive Parallelism)
and EP/K034413/1(FromData Types toSession Types—ABasis forConcurrencyand Distribution),
and an LFCS internship.

REFERENCES
Manuel Adameit, Kirstin Peters, and Uwe Nestmann. 2017.  Session types for link failures. In FORTE (Lecture Notes in
   Computer Science), Vol. 10321. Springer, 1–16.
H. P. Barendregt. 1984. The Lambda Calculus Its Syntax and Semantics (revised ed.). Vol. 103. North Holland.
Nick Benton and Andrew Kennedy. 2001. Exceptional Syntax. Journal of Functional Programming 11, 4 (2001), 395–410.
LuísCairesandJorgeAPérez.2017. Linearity,controleffects,andbehavioraltypes.In ESOP (Lecture Notes in Computer
   Science). Springer, 229–259.
Luís Caires and Frank Pfenning. 2010. Session types as intuitionistic linear propositions. In CONCUR (Lecture Notes in
   Computer Science), Vol. 10. Springer, 222–236.
MarcoCarbone,OrnelaDardha,andFabrizioMontesi.2014. Progressascompositionallock-freedom.In COORDINATION
   (Lecture Notes in Computer Science). Springer, 49–64.
MarcoCarbone,KoheiHonda,andNobukoYoshida.2008. Structuredinteractionalexceptionsinsessiontypes.In CONCUR
   (Lecture Notes in Computer Science). Springer, 402–417.
MarcoCarbone,SamLindley,FabrizioMontesi,CarstenSchürmann,andPhilipWadler.2016. Coherencegeneralisesduality:
   Alogicalexplanationofmultipartysessiontypes.In CONCUR (LIPIcs),Vol.59.SchlossDagstuhl-Leibniz-Zentrumfuer
   Informatik, 33:1–33:15.
Tzu-Chun Chen, Malte Viering, Andi Bejleri, Lukasz Ziarek, and Patrick Eugster. 2016. A type theory for robust failure
   handling in distributed systems. In FORTE (Lecture Notes in Computer Science), Vol. 9688. Springer, 96–113.
DavidGerardClarke.2003. ObjectOwnershipandContainment. Ph.D.Dissertation.NewSouthWales,Australia. AAI0806678.
Ezra Cooper. 2009. Programming Language Features for Web Application Development. Ph.D. Dissertation. University of
   Edinburgh.
EzraCooper,SamLindley,PhilipWadler,andJeremyYallop.2007. Links:Webprogrammingwithouttiers.InFMCO (Lecture
   Notes in Computer Science). Springer, 266–296.
Ornela Dardha, Elena Giachino, and Davide Sangiorgi. 2017. Session types revisited. Inf. Comput. 256 (2017), 253–286.
RomainDemangeonandKoheiHonda.2012. Nestedprotocolsinsessiontypes.In CONCUR (Lecture Notes in Computer
   Science), Vol. 7454. Springer, 272–286.
Ian Fette and Alexey Melnikov. 2011. The WebSocket Protocol. RFC 6455. RFC Editor. 70 pages.http://www.rfc-editor.org/
   rfc/rfc6455.txt
Simon Fowler. 2016. An Erlang implementation of multiparty session actors. In ICE (EPTCS), Vol. 223. 36–50.
Simon J Gay and Vasco T Vasconcelos. 2010. Linear type theory for asynchronous session types. Journal of Functional
   Programming 20, 1 (2010), 19–50.
Daniel Hillerström and Sam Lindley. 2016. Liberating effects with rows and handlers. In TyDe@ICFP. ACM, 15–27.


, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 29

Exceptional Asynchronous Session Types                                                                                       29


DanielHillerström,SamLindley,RobertAtkey,andK.C.Sivaramakrishnan.2017. Continuationpassingstyleforeffect
   handlers. In FSCD (LIPIcs), Vol. 84. Schloss Dagstuhl - Leibniz-Zentrum fuer Informatik, 18:1–18:19.
Kohei Honda. 1993. Types for dyadic interaction. In CONCUR (Lecture Notes in Computer Science). Springer, 509–523.
Kohei Honda, Vasco T Vasconcelos, and Makoto Kubo. 1998.  Language primitives and type discipline for structured
   communication-based programming. In ESOP (Lecture Notes in Computer Science). Springer, 122–138.
Kohei Honda, Nobuko Yoshida, and Marco Carbone. 2016. Multiparty asynchronous session types. Journal of the ACM
   (JACM) 63, 1 (2016), 9.
Raymond Hu, Nobuko Yoshida, and Kohei Honda. 2008. Session-based distributed programming in Java. In ECOOP (Lecture
   Notes in Computer Science). Springer, 516–541.
ThomasBrachtLaumannJespersen,PhilipMunksgaard,andKenFriisLarsen.2015. SessiontypesforRust.In WGP.ACM,
   13–22.
Naoki Kobayashi. 2002. Type systems for concurrent programs. In 10th Anniversary Colloquium of UNU/IIST (Lecture Notes
   in Computer Science), Vol. 2757. Springer, 439–453.
WenKokke.2018.rusty-variation:alibraryfordeadlock-freesession-typedcommunicationinRust.https://github.com/
   wenkokke/rusty-variation. (2018).
Sam Lindley and J. Garrett Morris. 2015. A semantics for propositions as sessions. In ESOP (Lecture Notes in Computer
   Science), Vol. 9032. Springer, 560–584.
Sam Lindley and J Garrett Morris. 2016. Talking bananas: structural recursion for session types. In ICFP. ACM, 434–447.
SamLindleyandJGarrettMorris.2017. Lightweightfunctionalsessiontypes. In Behavioural Types: from Theory to Tools.
   River Publishers, 265–286.
Nicholas D. Matsakis and Felix S. Klock II. 2014. The Rust language. In HILT. ACM, 103–104.
Karl Mazurak, Jianzhou Zhao, and Steve Zdancewic. 2010. Lightweight linear types in System F°. In TLDI. ACM, 77–88.
Robin Milner. 1999. Communicating and mobile systems: the pi calculus. Cambridge university press.
DimitrisMostrousandVascoThudichumVasconcelos.2014. AffineSessions.InCOORDINATION (LectureNotesinComputer
   Science), Vol. 8459. Springer, 115–130.
Rumyana Neykova and Nobuko Yoshida. 2014. Multiparty session actors. In COORDINATION (Lecture Notes in Computer
   Science), Vol. 8459. Springer, 131–146.
RumyanaNeykovaandNobukoYoshida.2017a. Letitrecover:multipartyprotocol-inducedrecovery.In CC.ACM,98–108.
RumyanaNeykovaandNobukoYoshida.2017b. Multipartysessionactors. LogicalMethodsinComputerScience 13,1(2017).
Luca Padovani. 2017. Asimple library implementationof binary sessions. Journal of Functional Programming 27 (2017),e4.
Jorge A Pérez, Luís Caires, Frank Pfenning, and Bernardo Toninho. 2012.   Linear logical relations for session-based
   concurrency. In ESOP (Lecture Notes in Computer Science). Springer, 539–558.
Gordon D. Plotkin and Matija Pretnar. 2013. Handling algebraic effects. Logical Methods in Computer Science 9, 4 (2013).
Didier Rémy. 1994.  Type inference for records in a natural extension of ML.  In Theoretical Aspects Of Object-Oriented
   Programming, Carl A. Gunter and John C. Mitchell (Eds.). MIT Press, Cambridge, MA, 67–95.
Alceste Scalas,Ornela Dardha,Raymond Hu, andNobuko Yoshida. 2017. A lineardecomposition ofmultiparty sessions
   for safe distributed programming. In ECOOP (LIPIcs), Vol. 74. Schloss Dagstuhl - Leibniz-Zentrum fuer Informatik,
   24:1–24:31.
AlcesteScalasandNobukoYoshida.2016. Lightweightsessionprogramminginscala.In ECOOP (LIPIcs),Vol.56.Schloss
   Dagstuhl - Leibniz-Zentrum fuer Informatik, 21:1–21:28.
Jérôme Vouillon. 2008. Lwt: a cooperative thread library. In ML. ACM, 3–12.
Philip Wadler. 2014. Propositions as sessions. Journal of Functional Programming 24, 2-3 (2014), 384–418.





















                                                                            , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 30

30                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

APPENDIX CONTENTS

    A      Example Runtime Typing Derivation                                                              31
    B       Deadlock-freedom                                                                                                      32
    C       Supplement to Section3(Metatheory of EGV)                                                        33
    C.1         Preservation                                                                                                        33
    C.2         Canonical Forms                                                                                                 46
    C.3         Progress                                                                                                                    46
    C.4         Confluence                                                                                                  50
    D      Supplement to Section4.1(Metatheory of EGV with Access Points)                   51
    E       Distributed Delegation                                                                                     54
    E.1         Challenges of Distributed Delegation                                                         54
    E.2         Approaches to Distributed Delegation                                                       55
    E.3         Delegation in Distributed Session Links                                                             55
    E.4         Correctness                                                                                                         56



















































, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 31

Exceptional Asynchronous Session Types                                                                                       31

A   EXAMPLE RUNTIME TYPING DERIVATION
We give an example derivation to illustrate how channels are introduced by name restrictions
and then split into endpoints using the T-Connecti rules. We assume suitable encodings of linear
booleans and integers using linear sums and products.
   Let us assume we have derivations for:
Γ1,a : !Int.End⊢◦ E[send5a] : 1      Γ2,b : ?Bool.?Int.End⊢ E′[receiveb] :A  ·⊢ true : Bool
We construct a derivationDof (νa)(νb)(◦E[send5a]∥  (a(ϵ)↭b(true)∥•E′[receiveb])).
   First letD1 be the following subderivation.

                           ?Int.End/ϵ =  !Bool.!Int.End/Bool        ·⊢ϵ :ϵ  ·⊢ true : Bool
                                   ·;a : ?Int.End,b : !Bool.!Int.End⊢◦ a(ϵ)↭b(true)            T-Buffer
   Then letD2 be the following subderviation.

                                            Γ2,b : ?Bool.?Int.End⊢ E′[receiveb] :A
       T-Connect2                    D1    Γ2,b : ?Bool.?Int.End;·⊢••E′[receiveb] T-Main
                       Γ2;a : ?Int.End,b :  (?Bool.?Int.End)♯⊢• a(ϵ)↭b(true)∥•E′[receiveb]

   The complete derivationD is as follows.

                  T-Thread Γ1,a : !Int.End⊢◦ E[send5a] : 1Γ1,a : !Int.End;·⊢◦ E[send5a]        D2

  Γ1,Γ2;a :  (!Int.End)♯,b :  (?Bool.?Int.End)♯⊢◦◦E[send5a]∥  (a(ϵ)↭b(true)∥•E′[receiveb]) T-Connect1
          Γ1,Γ2;a :  (!Int.End)♯⊢•  (νb)(◦E[send5a]∥  (a(ϵ)↭b(true)∥•E′[receiveb]))              T-Nu
               Γ1,Γ2;·⊢••(νa)(νb)(◦E[send5a]∥  (a(ϵ)↭b(true)∥•E′[receiveb]))                    T-Nu

   LetusreadDbottom-upwards.The twoinstancesofthe T-Nu ruleintroducechannelsa andb
into the runtime environment. The T-Connect1 rule splits channela into dual endpoints: on the
lefttheendpointa appearsinthetypeenvironmentandthesendingthread;ontherighttheend
pointa appears in theruntime environment and thebuffer. The T-Connect2 rule splits channelb
intodual endpoints:onthe lefttheendpointb appearsin theruntimeenvironment andthebuffer;
on the right the endpointb appears in the type environment and the receiving thread.

























                                                                 , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 32

32                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

B   DEADLOCK-FREEDOM
Herewegiveagraph-theoreticaccountofdeadlock-freedominEGV,independentofournotionof
progress, followingLindley and Morris[2015].
  Due to the asynchronous semantics of EGV, sending on an endpoint and cancelling an endpoint
reduceimmediately.Deadlocksmaythereforeonlyoccurwhencyclesoccurreceivingorclosingan
endpoint.Webeginbyclassifyingthenotionofa blocked thread:thatis,athreadwhichisblocked
performing an action on some channel endpoint.
  Definition B.1. We say that termM is blocked on namea ifM is about to receive on or closea.
Formally:
                    blocked(a,M)≜∃E. (M = E[receivea])∨ (M = E[closea])
Giventhenotionofablockedthread,wemaycharacterisethenotionofadependencybetween
communication actions.
  Definition B.2. LetC be a configuration such thata andb are not bound byC. We say thata
depends onb inC, written depends(a,b,C), ifC is a buffer connecting a andb, or a appears in
some thread blocked onb, or ifa depends on some namec which depends onb. Formally:
    • depends(a,b,a(−→V )↭b(−→W ))
    • depends(a,b,b(−→W )↭a(−→V ))
    • depends(a,b,ϕM)≜ blocked(b,M)∧a∈ fn(M)
    • depends(a,b,C)≜∃G,D,E,c.C≡G[D∥E]∧depends(a,c,D)∧depends(c,b,E)
  Remark. Theabovedefinitionofdependencyisanover-approximationtotheintuitivenotion,as
abufferneednothavedependenciesinbothdirections,butforourpurposesthisdoesnotmatter.
  Definition B.3. We say that a configuration is deadlocked if it contains cyclic dependencies:
       deadlocked(C)≜∃D,E,a,b.C≡G[D∥E]∧depends(a,b,D)∧depends(b,a,E)
With these definitions in place, we can show that EGV configurations are deadlock-free.
  Lemma B.4.  Ifdepends(a,b,C) thena,b∈ fn(C).
  Proof. By induction on the definition of depends(a,b,C).                                            □
  Theorem B.5.  IfΓ;∆⊢C, then¬deadlocked(C).
  Proof. By contradiction. Suppose deadlocked(C), that is:
                  ∃D,E,a,b.C≡G[D∥E]∧depends(a,b,D)∧depends(b,a,E)
Thus by LemmaB.4,                 a,b∈ fn(D) andb,a∈ fn(E). Then by Lemma3.6,    C must be ill-typed.    □
  Remark. We regard blocked threads as deadlocked only if there is a cyclic dependency. It is
perfectly possible for a configuration to include blocked threads without there being a deadlock.
    • Deadlock-free open terms can block on external communication along a free endpoint.
    • Deadlock-freeclosedtermscanblockoncommunicationalonganendpointthatappearsin
       thereturnvalueofaprogram.Thisalsoamountstobeingblockedonexternalcommunication.
Allblockedthreadscan beruledout byrestrictingthe typeof aprogramto befreeof bothsession
types and function types (the latter is necessary as closures can capture endpoints).






, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 33

Exceptional Asynchronous Session Types                                                                                       33

C   SUPPLEMENT TO SECTION3(METATHEORY OF EGV)
C.1   Preservation
In this section, we present proofs that typeability is preserved by configuration reduction.
C.1.1    Equivalence. We begin by describing the properties of configuration equivalence. As de-
scribedin§3,typeabilityofconfigurationsis    not preservedbyequivalence.Nonetheless,LemmaC.1
showsthat onlythe associativityof parallelcomposition maycausea configurationto beill-typed.
  LemmaC.1.  IfΓ;∆⊢ϕC andC≡D, where the derivation ofC≡D does not contain a use of the
axiom for associativity, thenΓ;∆⊢ϕD.
  Proof. Byinduction onthederivationofC≡D,examining theequivalencein bothdirections
to account for symmetry. We show that a typing derivation ofthe left-hand side of an equivalence
rule implies the existence of the right-hand side, and vice versa.
  That reflexivity, transitivity, and symmetry of the equivalence relation respect typing follows
immediately because equality of typing derivations is an equivalence relation.
  We make implicit use of the induction hypothesis.
Congruencerules
Case  Name restriction
                                                   C≡D
                                               (νa)C≡  (νa)D



                              Γ;∆,a :S♯⊢ϕC                    Γ;∆,a :S♯⊢ϕD
                               Γ;∆⊢ϕ  (νa)C  ⇐⇒                 Γ;∆⊢ϕ  (νa)D
Case  Parallel Composition

                                                   C≡D
                                               C∥E≡D∥E
  There are three subcases, based on whether the parallel composition arises from T-Connect1,
T-Connect2, orT-Mix.
  SubcaseT-Mix

                Γ1;∆1⊢ϕ1C      Γ2;∆2⊢ϕ2E                      Γ1;∆1⊢ϕ1D      Γ2;∆2⊢ϕ2E
                 Γ1,Γ2;∆1,∆2⊢ϕ1+ϕ2C∥E  ⇐⇒                       Γ1,Γ2;∆1,∆2⊢ϕ1+ϕ2D∥E
  SubcaseT-Connect1

   Γ1,a :S;∆1⊢ϕ1C      Γ2;∆2,a :       S⊢ϕ2E                  Γ1,a :S;∆1⊢ϕ1D      Γ2;∆2,a :        S⊢ϕ2E
       Γ1,Γ2;∆1,∆2,a :S♯⊢ϕ1+ϕ2C∥E   ⇐⇒                            Γ1,Γ2;∆1,∆2,a :S♯⊢ϕ1+ϕ2D∥E
  SubcaseT-Connect2

   Γ1;∆1,a :  S⊢ϕ1C      Γ2,a :S;∆2⊢ϕ2E                       Γ1;∆1,a :  S⊢ϕ1D      Γ2,a :S;∆2⊢ϕ2E
       Γ1,Γ2;∆1,∆2,a :S♯⊢ϕ1+ϕ2C∥E   ⇐⇒                            Γ1,Γ2;∆1,∆2,a :S♯⊢ϕ1+ϕ2D∥E


                                                           , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 34

34                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

EquivalenceAxioms

CaseC∥D≡D∥C

  There are three subcases, based on which rule is used for parallel composition.
  SubcaseT-Mix


               Γ1;∆1⊢ϕ1C      Γ2;∆2⊢ϕ2D
                Γ1,Γ2;∆1,∆2⊢ϕ1+ϕ2C∥D  ⇐⇒     Γ2;∆2⊢ϕ2D      Γ1;∆1⊢ϕ1CΓ1,Γ2;∆1,∆2⊢ϕ2+ϕ1D∥C

  SubcaseT-Connect1


   Γ1,a :S;∆1⊢ϕ1C      Γ2;∆2,a :      S⊢ϕ2D                             S⊢ϕ2D      Γ1,a :S;∆1⊢ϕ1C
       Γ1,Γ2;∆1,∆2,a :S♯⊢ϕ1+ϕ2C∥D   ⇐⇒     Γ2;∆2,a :Γ1,Γ2;∆1,∆2,a :S♯⊢ϕ2+ϕ1D∥C

  SubcaseT-Connect2


   Γ1;∆1,a : S⊢ϕ1C      Γ2,a :S;∆2⊢ϕ2D                                                           S⊢ϕ1C
       Γ1,Γ2;∆1,∆2,a :S♯⊢ϕ1+ϕ2C∥D   ⇐⇒     Γ2,a :S;∆2⊢ϕ2D      Γ1;∆1,a :Γ1,Γ2;∆1,∆2,a :S♯⊢ϕ2+ϕ1D∥C

CaseC∥  (νa)D≡  (νa)(C∥D) ifa< fn(C)

Thereareagainthreesubcasesbasedonwhichparallelcompositionruleisused.Theexactrule
does not affect the discussion, so without loss of generality we assume this is T-Mix.

                          Γ2;∆2,a :S♯⊢ϕ2D                    Γ1;∆1⊢ϕ1C      Γ2;∆2,a :S♯⊢ϕ2D
       Γ1;∆1⊢ϕ1C           Γ2;∆2⊢ϕ2  (νa)D                     Γ1,Γ2;∆1,∆2,a :S♯⊢ϕ1+ϕ2C∥D
          Γ1,Γ2;∆1,∆2⊢ϕ1+ϕ2C∥  (νa)D  ⇐⇒                       Γ1,Γ2;∆1,∆2⊢ϕ1+ϕ2  (νa)(C∥D)

  In the left-to-right direction, thatΓ1,Γ2;∆1,∆2,a :S♯ is well-defined follows becausea< fn(C).

Case   (νa)(νb)C≡  (νb)(νa)C


                     Γ;∆,a :S♯,b :T♯⊢ϕC                      Γ;∆,b :T♯,a :S♯⊢ϕC
                       Γ;∆,a :S♯⊢ϕ  (νb)C                     Γ;∆,b :T♯⊢ϕ  (νa)C
                        Γ;∆⊢ϕ  (νa)(νb)C  ⇐⇒                    Γ;∆⊢ϕ  (νb)(νa)C


Case a(−→V )↭b(−→W )≡b(−→W )↭a(−→V )


S/−→A = T/−→B      Γ1⊢−→V :−→A      Γ2⊢−→W :−→B               T/−→B = S/−→A      Γ2⊢−→W :−→B      Γ1⊢−→V :−→A
      Γ1,Γ2;a :S,b :T⊢◦ a(−→V )↭b(−→W )   ⇐⇒                       Γ1,Γ2;a :S,b :T⊢◦ b(−→W )↭a(−→V )


, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 35

Exceptional Asynchronous Session Types                                                                                       35


  The above holds becauseS/−→A =T/−→B⇐⇒ T/−→B =                 S/−→A:

                                      S/−→A = T/−→B
                                          ⇐⇒    (duality)

                                      S/−→A = T/−→B
                                          ⇐⇒    (duality is involutive)
                                      S/−→A =T/−→B
                                          ⇐⇒    (equality is symmetric)
                                      T/−→B =  S/−→A


Case◦()∥C≡C


                               ·⊢  () : 1
                               ·;·⊢◦◦()       Γ;∆⊢ϕC
                                    Γ;∆⊢ϕ◦()∥C   ⇐⇒    Γ;∆⊢ϕC


Case   (νa)(νb)( a∥ b∥ a(ϵ)↭b(ϵ))∥C≡C




                                       S/ϵ =T/ϵ        ·⊢ϵ :ϵ       ·⊢ϵ :ϵ
                     b :T;·⊢◦ b             ·;a :S,b :T⊢◦ a(ϵ)↭b(ϵ)
   a :S;·⊢◦ a                   ·;a :S,b :T ♯⊢◦ b∥ a(ϵ)↭b(ϵ)
                   ·;a :S♯,b :T ♯⊢◦ a∥ b∥ a(ϵ)↭b(ϵ)
                    ·;a :S♯⊢◦  (νb)( a∥ b∥ a(ϵ)↭b(ϵ))
                    ·;·⊢◦  (νa)(νb)( a∥ b∥ a(ϵ)↭b(ϵ))                               Γ;∆⊢ϕC
                        Γ;∆⊢ϕ   (νa)(νb)( a∥ b∥ a(ϵ)↭b(ϵ))∥C           ⇐⇒    Γ;∆⊢ϕC
                                                                                                                 □

  Whileitistruethatre-associatingparallelcompositionmaycauseaconfigurationtobeill-typed,
LemmaC.2shows that it is always possible to re-associate parallel composition either directly, or
by first commuting two subconfigurations.

  Lemma C.2 (Associativity).

    • IfΓ;∆⊢ϕC∥  (D∥E), then eitherΓ;∆⊢ϕ  (C∥D)∥E orΓ;∆⊢ϕ  (C∥E)∥D.
    • IfΓ;∆⊢ϕ  (C∥D)∥E, then eitherΓ;∆⊢ϕC∥  (D∥E) orΓ;∆⊢ϕD∥  (C∥E).

  Proof. ThecaseswhereeitherparallelcompositionarisesbyT-Mixareunproblematicandcan
be re-associated withoutjeopardising typeability. Therefore, we concentrate on the cases where
both compositions arise via T-Connecti.

CaseC∥  (D∥E)


                                                             , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 36

36                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

By the assumption thatΓ;∆⊢ϕC∥   (D∥E) we have thatΓ = Γ1,Γ2,Γ3, and∆ = ∆1,∆2,∆3,a :
S♯,b :T♯, andϕ =ϕ1 +ϕ2 +ϕ3. Thereare 8cases, basedon whethera,b∈ fn(C) ora,b∈ fn(D)
(itcannotbethecasethata,b∈ fn(E),asE onlyoccursunderasingleparallelcomposition),and
the exact dualisation (i.e., whether composition happens via T-Connect1 or T-Connect2).
  Ofthese,weareonlyinterestedinthecaseswherethesharingofthenamesdiffers,asopposed
tothedualisation.Thus,weconsiderthefollowingtwocases,wherebothcompositionsoccurusing
T-Connect1:
  (1) Γ1,a :S;∆1⊢ϕ1C, andΓ2,b :T;∆2,a :         S⊢ϕ2D, andΓ3;∆3,b :      T⊢ϕ3E
  (2) Γ1,a :S;∆1⊢ϕ1C, andΓ2,b :T;∆2⊢ϕ2D, andΓ3;∆3,a :              S,b : T⊢ϕ3E
  Subcasea∈ fn(C),a,b∈D,b∈E

                                      Γ2,b :T;∆2,a :  S⊢ϕ2D      Γ3;∆3,b :     T⊢ϕ3E
                  Γ1,a :S;∆1⊢ϕ1C          Γ2,Γ3;∆2,∆3,a :  S,b :T♯⊢ϕ2+ϕ3D∥E
                       Γ1,Γ2,Γ3;∆1,∆2,∆3,a :S♯,b :T♯⊢ϕ1+ϕ2+ϕ3C∥  (D∥E)
 AsD containsbotha andb,associativitydoesnotalterthesharingofnamesandmaybeapplied
  safely.

                Γ1,a :S;∆1⊢ϕ1C      Γ2,b :T;∆2,a :       S⊢ϕ2D
                    Γ1,Γ2,b :T;∆1,∆2,a :S♯⊢ϕ1+ϕ2C∥D           Γ3;∆3,b :           T⊢ϕ3E
                       Γ1,Γ2,Γ3;∆1,∆2,∆3,a :S♯,b :T♯⊢ϕ1+ϕ2+ϕ3  (C∥D)∥E
Subcasea∈ fn(C);b∈D;a,b∈E

                                        Γ2,b :T;∆2⊢ϕ2D      Γ3;∆3,a :       S,b : T⊢ϕ3E
                Γ1,a :S;∆1⊢ϕ1C              Γ2,Γ3;∆2,∆3,a :   S,b :T♯⊢ϕ2+ϕ3D∥E
                       Γ1,Γ2,Γ3;∆1,∆2,∆3,a :S♯,b :T♯⊢ϕ1+ϕ2+ϕ3C∥  (D∥E)
  Here, we may not apply associativity directly. But, we may first commuteD andE:
                                        Γ3;∆3,a :  S,b : T⊢ϕ3E      Γ2,b :T;∆2⊢ϕ2D
                Γ1,a :S;∆1⊢ϕ1C              Γ2,Γ3;∆2,∆3,a :   S,b :T♯⊢ϕ2+ϕ3E∥D
                       Γ1,Γ2,Γ3;∆1,∆2,∆3,a :S♯,b :T♯⊢ϕ1+ϕ2+ϕ3C∥  (E∥D)
  and from here we may safely re-associate to the left:

                   Γ2,a :S;∆2⊢ϕ1C      Γ3;∆3,a :     S,b : T⊢ϕ3E
                      Γ2,Γ3;∆2,∆3,a :S♯,b :    T⊢ϕ1+ϕ2D∥E    Γ3,b :T;∆3⊢ϕ3D
                       Γ1,Γ2,Γ3;∆1,∆2,∆3,a :S♯,b :T♯⊢ϕ1+ϕ2+ϕ3  (C∥E)∥D

Case   (C∥D)∥E

  (1) Γ1,a :S;∆1⊢ϕ1C, andΓ2,b :T;∆2,a :         S⊢ϕ2D, andΓ3;∆3,b :      T⊢ϕ3E
  (2) Γ1,a :S,b :T;∆1⊢ϕ1C, andΓ2;∆2,b :         T⊢ϕ2D, andΓ3;∆3,a :       S⊢ϕ3E
  Subcasea∈C;a,b∈D;b∈E


, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 37

Exceptional Asynchronous Session Types                                                                                       37

  Assumption:
                 Γ1,a :S;∆1⊢ϕ1C      Γ2,b :T;∆2,a :         S⊢ϕ2D
                     Γ1,Γ2,b :T;∆1,∆2,a :S♯⊢ϕ1+ϕ2C∥D           Γ3;∆3,b :               T⊢ϕ3E
                        Γ1,Γ2,Γ3;∆1,∆2,∆3,a :S♯,b :T♯⊢ϕ1+ϕ2+ϕ3  (C∥D)∥E
 Applyingassociativityheredoesnotmaketheconfigurationill-typed,asD containsbothnames:
                                           Γ2,b :T;∆2,a :   S⊢ϕ2D      Γ3;∆3,b :       T⊢ϕ3E
                 Γ1,a :S;∆1⊢ϕ1C                Γ2,Γ3;∆2,∆3,a :    S,b :T♯⊢ϕ2+ϕ3D∥E
                        Γ1,Γ2,Γ3;∆1,∆2,∆3,a :S♯,b :T♯⊢ϕ1+ϕ2+ϕ3C∥  (D∥E)
Subcasea,b∈C;a∈D;b∈E
  Assumption:
                 Γ1,a :S,b :T;∆1⊢ϕ1C      Γ2;∆2,a :         S⊢ϕ2D
                     Γ2,Γ3,b :T;∆2,∆3,a :S♯⊢ϕ2+ϕ3C∥D           Γ3;∆3,b :               T⊢ϕ3E
                        Γ1,Γ2,Γ3;∆1,∆2,∆3,a :S♯,b :T♯⊢ϕ1+ϕ2+ϕ3  (C∥D)∥E
  By commutativity:
                 Γ2;∆2,a :  S⊢ϕ2D      Γ1,a :S,b :T;∆1⊢ϕ1C
                     Γ2,Γ3,b :T;∆2,∆3,a :S♯⊢ϕ2+ϕ1D∥C           Γ3;∆3,b :               T⊢ϕ3E
                        Γ1,Γ2,Γ3;∆1,∆2,∆3,a :S♯,b :T♯⊢ϕ1+ϕ2+ϕ3  (D∥C)∥E
  By associativity:
                                           Γ1,a :S,b :T;∆1⊢ϕ1C      Γ3;∆3,b :          T⊢ϕ3E
                 Γ2;∆2,a :  S⊢ϕ2D               Γ1,Γ3,a :S;∆1,∆3,b :T♯⊢ϕ1+ϕ3C∥E
                        Γ1,Γ2,Γ3;∆1,∆2,∆3,a :S♯,b :T♯⊢ϕ1+ϕ2+ϕ3D∥  (C∥E)
  as required.
                                                                                                                □
C.1.2    Configuration Reduction. We may now show that configuration reduction preserves ty-
peability of configurations. We begin by stating some auxiliary results about substitution and
evaluation contexts.
  Typing of terms is preserved by substitution.
  Lemma C.3 (Substitution).  If:
  (1) Γ1⊢ M : B
  (2) Γ2,x : B⊢ N :A
  (3) Γ1,Γ2 is well-defined
thenΓ1,Γ2⊢ N{M/x} :A.
  Proof. By induction on the derivation ofΓ2,x : B⊢ N :A.                                            □
  LemmaC.4showsthatasubtermofawell-typedevaluationcontext                 E (andthereforealsoapure
evaluationcontextP)istypeablewithasubsetofthetypeenvironment.LemmaC.5statesthatthe
subterm of a well-typed evaluation context can be replaced. Both follow the formulation ofGay
and Vasconcelos[2010].


                                                            , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 38

38                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

   LemmaC.4(Typeabilityofsubterms).  IfDisaderivationofΓ⊢ E[M] :A,thenthereexistΓ1,Γ2
andB such thatΓ =Γ1,Γ2, thatD has a subderivationD′ that concludesΓ2⊢ M : B, and the position
ofD′ inD corresponds to the position of the hole inE.
   Proof. By induction on the structure ofE.                                                                □

   Lemma C.5 (Replacement (evaluation contexts)).  If:
    • D is a derivation ofΓ1,Γ2⊢ E[M] :A
    • D′ is a subderivation ofD concludingΓ2⊢ M : B
    • The position ofD′ inD corresponds to that of the hole in E
    • Γ3⊢ N : B
    • Γ1,Γ3 is well-defined
   thenΓ1,Γ3⊢ E[N] :A.
   Proof. By induction on the structure ofE.                                                                □

   To prove preservation on configurations, we must first establish some auxiliary results on
configuration contexts. LemmaC.6states how we may type subconfigurations.
   Lemma C.6 (Typeability of subconfigurations).  IfD is a derivation ofΓ;∆⊢ϕG[C], then
there existΓ′,∆′,ϕ′ such thatD has a subderivationD′ that concludesΓ′;∆′⊢ϕ′C, and the position
ofD′ inD corresponds to the position of the hole inG.
   Proof. By induction on the structure ofG.                                                               □

   LemmaC.7statesthatwemayreplaceasubconfigurationofaconfigurationcontext.Thelemma
isslightlycomplicatedbythefactthat (νa)G bindsavariablea,butreplacementissafeifthetyping
environments are related by the environment reduction relation.
   Lemma C.7 (Replacement (configurations)).  If:
    • D is a derivation ofΓ;∆⊢ϕG[C]
    • D′ is a subderivation ofD concluding thatΓ′;∆′⊢ϕ′C for someΓ′,∆′,ϕ′
    • Γ′′;∆′′⊢ϕ′C′ for someΓ′′,∆′′ such thatΓ′;∆′−→? Γ′′;∆′′
    • The position ofD inD′ corresponds to that of the hole inG
   then there exist someΓ′′′,∆′′′ such thatΓ′′′;∆′′′⊢ϕG[C′] andΓ;∆−→? Γ′′′;∆′′′.
   Proof. By induction on the structure ofG.                                                               □

Theorem3.2(Preservation(Configurations)
AssumeΓ only contains entries of the formai :Si.
   IfΓ;∆⊢ϕC andC−→D, then there existΓ′,∆′ such thatΓ;∆−→? Γ′;∆′ andΓ′;∆′⊢ϕD.

   Proof. ByinductiononthederivationofC−→D.Wherethereisachoiceofvalueforϕ,we
consider the case whereϕ =•; the cases whereϕ =◦ are similar.

Case  E-Fork

Assumption:
                                           Γ1,Γ2⊢•E[forkλx.M] :A
                                           Γ1,Γ2;·⊢••E[forkλx.M]


, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 39

Exceptional Asynchronous Session Types                                                                                       39

   By LemmaC.4:
                                                  Γ2,x :S⊢ M : 1
                                                Γ2⊢λx.M :S⊸ 1
                                                Γ2⊢ forkλx.M :      S
   By LemmaC.3,               Γ2,b : S⊢ M{b/x} : 1, and by LemmaC.5,               Γ1,a :S⊢ E[a] : A. As duality is
involutive,  S =S.
   Reconstructing:

                                                                  S/ϵ =   S/ϵ        ·⊢ϵ :ϵ          ·⊢ϵ :ϵ
        Γ1,a : S⊢ E[a] :A           Γ2,b :S⊢◦◦M{b/x}                    ·;a :S,b :  S⊢◦ a(ϵ)↭b(ϵ)
        Γ1,a : S;·⊢••E[a]                        Γ2;a :S,b :S♯⊢◦ M{b/x}∥ a(ϵ)↭b(ϵ)

                           Γ1,Γ2;a :  S♯,b :S♯⊢••E[a]∥◦M{b/x}∥ a(ϵ)↭b(ϵ)
                           Γ1,Γ2;a :  S♯⊢•  (νb)(•E[a]∥◦M{b/x}∥ a(ϵ)↭b(ϵ))
                            Γ1,Γ2;·⊢•  (νa)(νb)(•E[a]∥◦M{b/x}∥ a(ϵ)↭b(ϵ))

Case  E-Send
Assumption:

               Γ1,Γ2⊢ E[sendU a] :C                   S/−→A =  T/−→B      Γ3⊢−→V :−→A      Γ4⊢−→W :−→B
            Γ1,Γ2,a :S;·⊢••E[sendU a]                       Γ3,Γ4;a :  S,b :T⊢◦ a(−→V )↭b(−→W )

                         Γ1,Γ2,Γ3,Γ4;a :S♯,b :T⊢••E[sendU a]∥ a(−→V )↭b(−→W )
   By LemmaC.4:
                                       Γ2⊢U :A       a : !A.S′⊢a : !A.S′
                                           Γ2,a : !A.S′⊢ sendU a :S′

   Thus,S = !A.S′, and      S = ?A.S′. We may therefore refine our original derivation:

       Γ1,Γ2,a : !A.S′⊢ E[sendU a] :C                 ?A.S′/−→A =  T/−→B      Γ3⊢−→V :−→A      Γ4⊢−→W :−→B
       Γ1,Γ2,a : !A.S′;·⊢••E[sendU a]                       Γ3,Γ4;a : ?A.S′,b :T⊢◦ a(−→V )↭b(−→W )

                      Γ1,Γ2,Γ3,Γ4;a : !A.S′♯,b :T⊢••E[sendU a]∥ a(−→V )↭b(−→W )

Since ?A.S′/−→A =    T/−→B is well-defined, we have that−→A =ϵ. By the definition of slicing, we have
that T =   !B1.··· .!Bn.!A.S′, where−→B = B1,...,Bn. It follows that            S′/−→A = T/−→B·A.
   By LemmaC.5, we have              Γ1,Γ2,a :S′⊢ E[a] :C.
   Reconstructing:

          Γ1,a :S′⊢ E[a] :C            S′/−→A =  T/−→B·A      Γ3⊢−→V :−→A      Γ2,Γ4⊢−→W·U :−→B·A
          Γ1,a :S′;·⊢••E[a]                      Γ2,Γ3,Γ4;a :   S′,b :T⊢◦ a(−→V )↭b(−→W·U )

                           Γ1,Γ2,Γ3,Γ4;a :S′♯,b :T⊢••E[a]∥ a(−→V )↭b(−→W·U )


                                                               , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 40

40                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

   Finally, we must show environment reduction:

                                                      !A.S′−→S′
                         Γ1,Γ2,Γ3,Γ4;a :  (!A.S′)♯,b :T−→Γ1,Γ2,Γ3,Γ4;a :S′♯,b :T
as required.

Case  E-Receive

Assumption:

               Γ1,a :S⊢ E[receivea] :C             S/−→A =T/−→B      Γ2,Γ3⊢U·−→V :−→A      Γ4⊢−→W :−→B
               Γ1,a :S;·⊢• E[receivea]                   Γ2,Γ3,Γ4;a :  S,b :T⊢◦ a(U·−→V )↭b(−→W )
                          Γ1,Γ2,Γ3,Γ4;a :S♯,b :T⊢••E[receivea]∥ a(U·−→V )↭b(−→W )
ByTheorem C.4:

                                                 a : ?A.S′⊢a : ?A.S′
                                          a : ?A.S′⊢ receivea :  (A×S′)

Thus, we have that S = ?A.S′ and             S = !A.S′, and we may therefore refine the original typing
derivation:

                                                                        Γ1⊢U :A      Γ3⊢−→V :−→A′

   Γ1,a : ?A.S′⊢ E[receivea] :C             !A.S′/A·−→A′ =   T/−→B         Γ2,Γ3⊢U·−→V :A·−→A′          Γ4⊢−→W :−→B
   Γ1,a : ?A.S′;·⊢• E[receivea]                          Γ2,Γ3,Γ4;a : !A.S′,b :T⊢◦ a(U·−→V )↭b(−→W )
                       Γ1,Γ2,Γ3,Γ4;a :  (?A.S′)♯,b :T⊢••E[receivea]∥ a(U·−→V )↭b(−→W )


   By LemmaC.5, we have               Γ1,Γ2,a : S′⊢ E[(U,a)] : C (that Γ1,Γ2 is defined follows from the
fact that Γ1 and Γ2 are sub-environments of the original typing environment and are therefore
necessarily disjoint).
   By the definition of slicing,!A.S′/A·−→A′⇐⇒                S′/−→A′.
   Thus, recomposing:

             Γ1,Γ2,a :S′⊢ E[(U,a)] :C                S′/−→A′ =  T/−→B      Γ3⊢−→V :−→A′      Γ4⊢−→W :−→B
              Γ1,Γ2,a :S′;·⊢• E[(U,a)]                      Γ3,Γ4;a :  S′,b :T⊢◦ a(−→V )↭b(−→W )
                           Γ1,Γ2,Γ3,Γ4;a :S′♯,b :T⊢••E[(U,a)]∥ a(−→V )↭b(−→W )
   Finally, we must show environment reduction:

                                                      ?A.S′−→S′
                         Γ1,Γ2,Γ3,Γ4;a :  (?A.S′♯);b :T−→Γ1,Γ2,Γ3,Γ4;a :S′♯,b :T
as required.

Case  E-Close


, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 41

Exceptional Asynchronous Session Types                                                                                       41

Assumption:

                                       Γ2,b :T⊢ E′[closeb] : 1          S/ϵ =  T/ϵ       ·⊢ϵ :ϵ        ·⊢ϵ :ϵ
      Γ1,a :S⊢ E[closea] :C           Γ2,b :T;·⊢◦◦E′[closeb]                 ·;a :S,b : T⊢◦ a(ϵ)↭b(ϵ)
     Γ1,a :S;·⊢••E[closea]                         Γ2;a : S,b :T♯⊢◦ E′[closeb]∥ a(ϵ)↭b(ϵ)
                        Γ1,Γ2;a :S♯,b :T♯⊢••E[closea]∥◦E′[closeb]∥ a(ϵ)↭b(ϵ)
                        Γ1,Γ2;a :S♯⊢•  (νb)(•E[closea]∥◦E′[closeb]∥ a(ϵ)↭b(ϵ))
                         Γ1,Γ2;·⊢•  (νa)(νb)(•E[closea]∥◦E′[closeb]∥ a(ϵ)↭b(ϵ))


   By LemmaC.4:
                           a : End⊢a : End                            b : End⊢b : End
                         a : End⊢ closea : 1                        b : End⊢ closeb : 1
By LemmaC.5, we have that              Γ1⊢ E[()] :C and thatΓ2⊢ E′[()] : 1. Thus by T-Mix, we may show:
                                       Γ1⊢ E[()] :C           Γ2⊢ E[()] : 1
                                       Γ1;·⊢••E[()]           Γ2;·⊢◦◦E[()]
                                           Γ1,Γ2;·⊢••E[()]∥◦E[()]
as required.

Case  E-Cancel

                                         F[cancela]−→F[()]∥ a
   Assumption:
                                               Γ⊢ E[cancela] :C
                                               Γ;·⊢••E[cancela]
   By LemmaC.4,              Γ =Γ1,Γ2, where
                                                     Γ2⊢a :S
                                                 Γ2⊢ cancela : 1
   Thus Γ2 = a : S. By LemmaC.5,               Γ1⊢ E[()] : C. By T-Zap, we have that a : S⊢◦ a. Thus,
recomposing:
                                        Γ⊢ E[()] :C
                                        Γ1;·⊢••E[()]          a :S;·⊢◦ a
                                            Γ1,a :S;·⊢••E[()]∥ a
as required.

Case  E-Zap

                    a∥ a(U·−→V )↭b(−→W )−→ a∥ c1∥···∥ cn∥ a(−→V )↭b(−→W )
where fn(U ) ={ci}i.


                                                              , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 42

42                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

   Assumption:

                                        S/−→A =  T/−→B      Γ1,Γ2⊢U·−→V :−→A      Γ3⊢−→W :−→B
                  a :S;·⊢◦ a                  Γ1,Γ2,Γ3;a :   S,b :T⊢◦ a(U·−→V )↭b(−→W )

                               Γ1,Γ2,Γ3;a :S♯,b :T⊢◦ a∥ a(U·−→V )↭b(−→W )

   Bythedefinitionofslicing,wehavethatthereexistsomeAandS′ suchthat                             S = !A.S′.Thus,we
may refine our judgement:

                                    !A.S′/A·−→A′ =   T/−→B      Γ1,Γ2⊢U·−→V :A·−→A′      Γ3⊢−→W :−→B
         a : ?A.S′;·⊢◦ a                         Γ1,Γ2,Γ3;a :  S,b :T⊢◦ a(U·−→V )↭b(−→W )

                               Γ1,Γ2,Γ3;a :S♯,b :T⊢◦ a∥ a(U·−→V )↭b(−→W )

   By the definition of buffer typing, we have thatΓ1⊢ U : A. By the definition of the reduction
rule, fn(U ) ={ci}i, and by assumption,Γ contains only runtime names. Thus, we may conclude
thatU is closed and therefore thatΓ1 =c1 :S1,...cn :Sn for some session typesS1,...Sn.
   Bythedefinitionofslicing,wehavethat!A.S′/A·−→A′⇐⇒                      S′/−→A′.Correspondingly,byT-Buffer,
we may show

                                S′/−→A′ = T/−→B      Γ2⊢−→V :−→A′      Γ3⊢−→W :−→B
                                       Γ2,Γ3;a :  S′,b :T⊢◦ a(−→V )↭b(−→W )
   By repeated applications of T-Zap and T-Mix, we have that

                Γ2,Γ3,c1 :S1,...,cn :Sn;a :       S′,b :T⊢◦ c1∥···∥ cn∥ a(−→V )↭b(−→W )
Recomposing:

                                                                    S′/−→A′ =T/−→B      Γ2⊢−→V :−→A′      Γ3⊢−→W :−→B
                                              cn :Sn;·⊢◦ cn              Γ2,Γ3;a :S′,b :T⊢◦ a(−→V )↭b(−→W )
                                                                               ...
                         c1 :S1;·⊢◦ c1
     a :S′;·⊢◦ a               Γ2,Γ3,c1 :S1, ...,cn :Sn;a :   S′,b :T⊢◦ c1∥ ...∥ cn∥ a(−→V )↭b(−→W )
                  Γ2,Γ3,c1 :S1, ...,cn :Sn;a :S′♯,b :T⊢◦ a∥ c1∥ ...∥ cn∥ a(−→V )↭b(−→W )


   Finally, we must show environment reduction:
                                                    ?A.S′−→S′
         Γ2,Γ3,c1 :S1,...,cn :Sn;a :  (?A.S′♯),b :T−→Γ2,Γ3,c1 :S1,...,cn :Sn;a :S′♯,b :T
as required.

Case  E-CloseZap


                F[closea]∥ b∥ a(ϵ)↭b(ϵ)−→F[raise]∥ a∥ b∥ a(ϵ)↭b(ϵ)


, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 43

Exceptional Asynchronous Session Types                                                                                       43

Assumption:

                                                                  S = T       ·⊢ϵ :ϵ         ·⊢ϵ :ϵ
          Γ,a :S⊢ E[closea] :C              b :T;·⊢◦ b              ·;a : S,b : T⊢◦ a(ϵ)↭b(ϵ)
          Γ,a :S;·⊢••E[closea]                        ·;a : S,b :T♯⊢◦ b∥ a(ϵ)↭b(ϵ)
                            Γ;a :S♯,b :T♯⊢••E[closea]∥ b∥ a(ϵ)↭b(ϵ)
  By LemmaC.4:
                                               a : End⊢a : End
                                              a :S⊢ closea : 1
  We may therefore refine our original derivation:


                                                                  End = End         ·⊢ϵ :ϵ         ·⊢ϵ :ϵ
    Γ,a : End⊢ E[closea] :C               b : End;·⊢◦ b             ·;a : End,b : End⊢◦ a(ϵ)↭b(ϵ)
    Γ,a : End;·⊢••E[closea]                          ·;a : End,b : End♯⊢◦ b∥ a(ϵ)↭b(ϵ)
                         Γ;a : End♯,b : End♯⊢••E[closea]∥ b∥ a(ϵ)↭b(ϵ)
  By LemmaC.5,              Γ⊢ E[raise] :C.
  Thus, recomposing:

                                                                      End = End        ·⊢ϵ :ϵ        ·⊢ϵ :ϵ
                                                b : End;·⊢◦ b            ·;a : End,b : End⊢◦ a(ϵ)↭b(ϵ)
   Γ⊢ E[raise] :C         a : End;·⊢◦ a                   ·;a : End,b : End♯⊢◦ b∥ a(ϵ)↭b(ϵ)
    Γ⊢••E[raise]                            ·;a : End♯,b : End♯⊢◦ a∥ b∥ a(ϵ)↭b(ϵ)
                            Γ;a : End♯,b : End♯⊢••E[closea]∥ b∥ a(ϵ)↭b(ϵ)
as required.

Case  E-ReceiveZap

            •E[receivea]∥ b∥ a(ϵ)↭b(−→W )−→•E[raise]∥ a∥ b∥ a(ϵ)↭b(−→W )
  Assumption:

                                                               S/ϵ =  T/−→B       ·⊢ϵ :ϵ       Γ2⊢−→W :−→B
    Γ1,a :S⊢ E[receivea] :C               b :T;·⊢◦ b                 Γ2;a : S,b : T⊢◦ a(ϵ)↭b(−→W )
    Γ1,a :S;·⊢••E[receivea]                           Γ2;a : S,b :T♯⊢◦ b∥ a(ϵ)↭b(−→W )
                       Γ1,Γ2;a :S♯,b :T♯⊢••E[receivea]∥ b∥ a(ϵ)↭b(−→W )
  By LemmaC.4:
                                             a : ?A.S′⊢a : ?A.S′
                                      a : ?A.S′⊢ receivea :  (A×S′)


                                                            , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 44

44                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

  By LemmaC.5,              Γ1⊢ E[raise] :S′. Thus, recomposing:

                                                                  S/ϵ =T/−→B       ·⊢ϵ :ϵ       Γ2⊢−→W :−→B
                                               b :T;·⊢◦ b               Γ2;a :S,b : T⊢◦ a(ϵ)↭b(−→W )
     Γ1⊢ E[raise] :C         a :S;·⊢ a                     Γ2;a :S,b :T♯⊢◦ b∥ a(ϵ)↭b(−→W )
     Γ1;·⊢••E[raise]                          Γ2;a :S♯,b :T♯⊢◦ a∥ b∥ a(ϵ)↭b(−→W )
                          Γ1,Γ2;a :S♯,b :T♯⊢••E[raise]∥ a∥ b∥ a(ϵ)↭b(−→W )
as required.

Case  E-Raise

                •E[tryP[raise]asx inM otherwiseN]−→ E[N]∥ c1∥···∥ cn
and fn(P) ={ci}i.
  Assumption:

                             Γ⊢ E[tryP[raise]asx inM otherwiseN] :A′
                             Γ;·⊢••E[tryP[raise]asx inM otherwiseN]
  By LemmaC.4, there exist              Γ1,Γ2,A,B,C such thatΓ =Γ1,Γ2,Γ3 and
                          Γ2⊢ P[raise] :A      Γ3,x : B⊢ M :C      Γ3⊢ N :C
                             Γ2,Γ3⊢ tryP[raise]asx inM otherwiseN :C
SinceΓ containsonlyruntimenamesandfn(P) ={ci}i,weknowthatΓ2 =c1 :S1,...,cn :Sn for
someSi.
  By LemmaC.5, we have that:

                                               Γ1,Γ3⊢ E[N] :A′
  By repeated applications of T-Zapand T-Mix, we have thatΓ2⊢ c1∥···∥ cn.
  Therefore, recomposing:

                                                        cn−1 :Sn−1;·⊢◦ cn−1           cn :Sn;·⊢◦ cn
          Γ1,Γ3⊢ E[N] :C          c1 :S1;·⊢◦ c1                                ...

          Γ1,Γ3;·⊢••E[N]                        c1 :S1,...,cn :Sn;·⊢◦ c1∥···∥ cn
                            Γ1,Γ3,c1 :S1,...,cn :Sn;·⊢••E[N]∥ c1∥···∥ cn


  as required.

Case  E-RaiseChild

                                       ◦P[raise]−→ c1∥···∥ cn
Assumption:
                                                Γ⊢ P[raise] : 1
                                               Γ;·⊢◦◦P[raise]


, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 45

Exceptional Asynchronous Session Types                                                                                       45

   ByLemmaC.4,theknowledgethat              Γ containsonlyruntimenames,theknowledgethatfn(P) =
c1,...,cn,and thetypingrule T-Raise,we have thatΓ =c1 :S1,...,cn :Sn forsome sessiontypes
{Si}i.
   Thus, by repeated applications of T-Zap andT-Mix, we may deduce that
                                            Γ;·⊢◦ c1∥···∥ cn
as required.

Case  E-RaiseMain

                                   •P[raise]−→ halt∥ c1∥···∥ cn
wherefn(P) ={ci}i.
   Assumption:
                                               Γ⊢ P[raise] :C
                                               Γ;·⊢••P[raise]
   ByLemmaC.4,theknowledgethat              Γ containsonlyruntimenames,theknowledgethatfn(P) =
c1,...,cn,and thetypingrule T-Raise,we have thatΓ =c1 :S1,...,cn :Sn forsome sessiontypes
{Si}i.
   By repeated applications of T-Zap and T-Mix, we may deduce that
                                            Γ;·⊢◦ c1∥···∥ cn
   ByT-Halt, we have that·;·⊢• halt. Thus, recomposing, we arrive at

                                                    cn−1 :Sn−1;·⊢◦ cn−1           cn :Sn;·⊢◦ cn
                              c1 :S1;·⊢◦ c1                                ...

             ·;·⊢• halt                     c1 :S1,...,cn :Sn;·⊢◦ c1∥···∥ cn
                             Γ1,Γ3,c1 :S1,...,cn :Sn;·⊢• halt∥ c1∥···∥ cn
as required.

Case  LiftC

Assumptions:
    • Γ;∆⊢ϕG[C]
    •C−→D
   LetDbe aderivation ofΓ;∆⊢ϕG[C]. ByLemmaC.6, we have thatthereexists some                  D′ such
thatD′ isasubderivationofDconcludingΓ′;∆′⊢ϕ′C,wherethepositionofD′ inDcorresponds
to that of the hole inG.
   By the IH, we have that there exists someΓ′′;∆′′ such thatΓ;∆−→? Γ′′;∆′′ andΓ′′;∆′′⊢ϕD.
   ByLemmaC.7,wehavethatthereexistsome              Γ′′′;∆′′′suchthatΓ;∆−→? Γ′′′;∆′′′andΓ′′′;∆′′′⊢ϕ
G[D], as required.
Case  E-LiftM

Assumptions:
                                                   Γ⊢ M :A
                                                   Γ;·⊢••M


                                                            , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 46

46                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

andM−→M N. By Lemma3.1, we have that              Γ⊢ N :A. Recomposing:
                                                   Γ⊢ N :A
                                                   Γ;·⊢••N
as required.                                                                                                           □

C.2   Canonical Forms
Theorem3.7:CanonicalForms            GivenC such thatΓ;∆⊢•C, there exists someC′≡C such that
Γ;∆⊢•C′ andC′ is in canonical form.

   Proof. The proof is by induction on the count ofν-bound variables, followingLindley and
Morris[2015]. Without loss of generality, assume that the        ν-bound variables ofC are distinct. Let
{ai| 1≤ i≤ n} be theset ofν-bound variablesinC and let{Dj| 1≤ j≤m} be theset of threads
inC.
   In thecase thatn = 0, byLemmaC.1we cansafely commute themain threadsuch thatit isthe
rightmostconfiguration,andassociateparallelcompositiontotherightusingLemmaC.2toderive
a well-typed canonical form.
   Inthe casethatn≥ 1,pick someai andDj suchthatai isthe onlyν-boundvariable infn(Dj);
Lemma3.6and a standard counting argument ensure that such a name and configuration exist.
By the equivalence rules, there existsE such thatΓ;∆⊢ϕC≡  (νai)(Dj∥E) (thatai is the only
ν-boundvariableinfn(Dj) ensureswell-typing).Moreover,wehavethatthereexistΓ′⊆ Γ,∆′⊆ ∆,
andS,suchthateitherΓ′,ai :S;∆′⊢ϕE orΓ′;∆′,ai :S⊢ϕE.Bytheinductionhypothesis,there
existsE′ in canonical form such that eitherΓ′,ai : S;∆′⊢ϕE≡E′ orΓ′;∆′,ai : S⊢ϕE≡E′.
LetC′ =  (νai)(Dj∥E′).ByconstructionitholdsthatΓ;∆⊢ϕC≡C′ andthatC′ isincanonical
form.                                                                                                                   □

C.3   Progress
ToproveTheorem3.9,weproveasimilarpropertyinwhichcanonicalconfigurationsaredecom-
posed step-by-step rather than in one go.
   Definition C.8 (Open Progress). SupposeΨ;∆⊢•C, whereC is in canonical form andC Y=⇒.
   We say thatC satisfies open progress if:
  (1)C =  (νa)(A∥D), whereΨ =Ψ1,Ψ2 and∆ =∆1,∆2 such that either:
       (a) Ψ1,a :S;∆1⊢◦A andΨ2;∆2,a :            S⊢•D whereD satisfiesopenprogress,andA iseither:
         (i)A thread ◦M where ready(b,M) for someb∈ fn(Ψ1,a :S); or
         (ii)A zapper thread   a; or
         (iii)A buffer      b(−→V )↭c(−→W ) whereb,c ,a and eithera∈−→V ora∈−→W
       (b) Ψ1;∆1,a :    S⊢◦A and Ψ2,a : S;∆2⊢•D, whereD satisfies open progress, andA is
         eithera(−→V )↭b(−→W ) orb(−→V )↭a(−→W ) for someb∈ fn(∆1)
  (2)C =A∥M, whereΨ =Ψ1,Ψ2 and either:
       (a) ∆ = ∆1,∆2,a : S♯, whereΨ1,a : S;∆1⊢◦A andΨ2;∆2,a :                    S⊢•M, whereM satisfies
         open progress, andA is either:
         (i)A thread ◦M where ready(b,M) for someb∈ fn(Ψ1,a :S); or
         (ii)A zapper thread   a; or
         (iii)A buffer      b(−→V )↭c(−→W ) whereb,c ,a and eithera∈ fn(−→V ) ora∈ fn(−→W )
       (b) ∆ = ∆1,∆2,a : S♯, whereΨ1;∆1,a :           S⊢◦A andΨ2,a : S;∆2⊢•M, whereM satisfies
         open progress, andA is eithera(−→V )↭b(−→W ) orb(−→V )↭a(−→W ) for someb∈ fn(∆1)


, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 47

Exceptional Asynchronous Session Types                                                                                       47

       (c) ∆ =∆1,∆2, whereΨ1;∆1⊢◦A andΨ2;∆2⊢•M, whereM satisfies open progress, and
         A is either:
         (i)A thread ◦M where eitherM =  (), or ready(a,M) for somea∈ fn(Ψ1); or
         (ii)A zapper thread   a for somea∈ fn(Ψ1); or
         (iii)A buffer      a(−→V )↭b(−→W ) for somea,b∈ fn(∆1)
  (3)C =T, where either:
       (a)T =•N, where N is either a value or ready(b,N ) for someb∈ fn(Ψ)
       (b)T = halt
  Lemma C.9.  SupposeΨ;∆⊢•C, whereC is in canonical form andC Y=⇒. ThenC satisfies open
progress.
  Proof. ByinductiononthederivationofΨ;∆⊢•C.Wehavethreecases,basedonthestructure
of the given canonical form.
CaseC =  (νa)(A∥D), witha∈ fn(A), and whereD is in canonical form
  By assumption, we know thatΨ;∆⊢ϕ  (νa)(A∥D).
  This configuration is typeable by T-Nu, followed by either T-Connect1 or T-Connect2. As
the definition of canonical forms requires thata∈ fn(A), it cannot be the case that the parallel
composition arises as a result of T-Mix.
  WeconsiderthesetwosubcasestoshowthatA satisfiesthepropertiesrequiredbyopenprogress.
  SubcaseT-Connect1

                                Ψ1,a :S;∆1⊢ϕ1A      Ψ2;∆2,a :          S⊢ϕ2D
                                    Ψ1,Ψ2;∆1,∆2,a :S♯⊢ϕ1+ϕ2A∥D
                                    Ψ1,Ψ2;∆1,∆2⊢ϕ1+ϕ2  (νa)(A∥D)
  By the definition of auxiliary threads and inversion on the typing relation, we know thatA is of
  the following forms:
  •◦ M, wherea∈ fn(M), andΨ1,a :S⊢ M : 1
  • a
  • b(−→V )↭c(−→W ), whereb,c∈ fn(∆1) anda∈ fn(V )
  • b(−→V )↭c(−→W ), whereb,c∈ fn(∆1) anda∈ fn(W )
 (sincea< fn(∆)1, it cannot be the case thata appears as a buffer endpoint).
    Lemma3.4tellsusthateitherthereexistssome                M′ suchthatM−→M M′;thatM isavalue;or
  thereexistE,N suchthatM = E[N]whereN iseitherraiseoracommunicationandconcurrency
  construct. SinceC Y=⇒, we have that M is unable to reduce (as otherwiseC could reduce by
  E-LiftM). Sincea∈ fn(M) anda does not have type1, it cannot be the case thatM is a value.
    Therefore, we have thatM has the formE[N], where N is either raise or a communication
 / concurrency construct. This cannot be fork, since fork may always reduce by E-Fork, nor
  can it be raise, which could reduce by E-Raise, or E-RaiseChild depending on the enclosing
  evaluation context. Thus, there must exist someb∈ fn(Ψ,a :S) such that ready(b,M).
  SubcaseT-Connect2

                                 Ψ1;∆1,a :   S⊢◦A      Ψ2,a :S;∆2⊢•D
                                      Ψ1,Ψ2;∆1,∆2,a :S♯⊢•A∥D
                                       Ψ1,Ψ2;∆1,∆2⊢•  (νa)(A∥D)


                                                            , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 48

48                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

  By the definition of auxiliary threads and inversion on the typing relation, we know thatA is of
  the following forms:
  • a(−→V )↭b(−→W ), whereb∈ fn(∆)1
  • b(−→V )↭a(−→W ), whereb∈ fn(∆)1
  (asa∈ fn(A) anda∈ fn(∆)1,itcannotbethecasethatA isachildthreadorazapperthread,as
  these require empty runtime typing environments).
Bytheinductionhypothesis,weknowthatD satisfiesopenprogress;hence (νa)(A∥D) satisfies
open progress.
CaseC =A∥M
Therearethreesubcases,basedonwhethertheparallelcompositionarisesasaresultof T-Connect1,
T-Connect2, orT-Mix.
  SubcaseT-Connect1

                                 Ψ1,a :S;∆1⊢◦A      Ψ2;∆2,a :         S⊢•M
                                      Ψ1,Ψ2;∆1,∆2,a :S♯⊢•A∥M
  By inversion on the typing rules, we have thatA may be:
  • A child thread◦M, wherea∈ fn(M)
  • A zapper thread a
  • A bufferb(−→V )↭c(−→W ), whereb,c ,a and eithera∈ fn(−→V ) ora∈ fn(−→W )
  In the case of (1), by Lemma3.4, we have that either                 M is a value; there exists N such that
  M−→M N; orM = E[N]for someE,N, where N is a communication / concurrency construct.
    By T-Child, Ψ1,a : S⊢ M : 1. Since a∈ fn(M) and the only value with type 1 is the unit
  value () ittherefore cannotbe thecase thatM isa value.SinceC Y=⇒,it cannotbe thecase that
  M−→M N,since otherwiseC couldreduce.Thus,it mustbe thecasethatM = E[N] where N
  eitherraiseoracommunicationandconcurrencyconstruct;bysimilarreasoningasabovecases,
  it therefore must be the case that ready(b,M) for someb∈ fn(Ψ1,a :S).
    (2) and (3) satisfy the required conditions by definition.
  SubcaseT-Connect2

                                   Ψ1;∆1,a :   S⊢◦A;Ψ2,a :S;∆2⊢•M
                                      Ψ1,Ψ2;∆1,∆2,a :S♯⊢•A∥M
  Sincetheruntimetypingenvironment∆1,a :               S isnon-empty,itcannotbethecasethatA isa
  child thread or zapper thread. Thus,A must either be of the form:
  (1) a(−→V )↭b(−→W ), wherea,b∈ fn(∆)1; or
  (2) b(−→V )↭a(−→W ), wherea,b∈ fn(∆)1
 which satisfy the required conditions by definition.
  SubcaseT-Mix

                                       Ψ1;∆1⊢◦A      Ψ2;∆2⊢•M
                                          Ψ1,Ψ2;∆1,∆2⊢•A∥M
    By inversion on the typing rules, we have thatA may either be:
  (1)A child thread ◦M
  (2)A zapper thread   a for somea∈ fn(Ψ1)


, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 49

Exceptional Asynchronous Session Types                                                                                       49

  (3)A buffer thread     a(−→V )↭b(−→W ) for somea,b∈ fn(∆1)
     ByLemma3.4,wehavethat                 M iseitheravalueV;thereexistssome N suchthatM−→M N;
  or M = E[N] for some E,N such that N is either raise or a communication and concurrency
  primitive. It cannot be the case thatM−→M N since otherwise the configuration could reduce.
     By T-Child, it must be the case thatΨ1;∆1⊢ M : 1; ifM is a value then by inversion on the
  term typing rules, it must be the case thatM =  ().
     Following thesame reasoning as previous cases, ifM = E[N]then it mustbethat ready(a,M)
  for somea∈ fn(Ψ)1.
Bytheinductionhypothesis,weknowthatM satisfiesopenprogress;henceA∥M satisfiesopen
progress.
CaseC =T
Assumption:Ψ;∆⊢•T. By the definition ofT, we have two subcases:
  SubcaseT =•M

                                                   Ψ⊢ M :A
                                                   Ψ;·⊢••M
     ByLemma3.4,wehavethateither                M isavalue;thatthereexistssomeN suchthatM−→M N;
  or that there exist someE,N such thatM = E[N] where N is a communication / concurrency
  primitive.
     Again,asC Y=⇒,it cannotbethe casethatM−→M N,since otherwiseC couldreduce. IfM is
  a value, thenT satisfies open progress.
     Finally,ifM = E[N] where N isa eitherraise orcommunication /concurrency primitive,it
  cannotbethecasethatN = raisesinceitcouldreduceeitherbyE-RaiseorE-RaiseMain,andit
  cannot be the case that N = forkM′ since it could reduce by T-Fork. Therefore it must be the
  case that ready(a,M) for somea∈ fn(Ψ), satisfying open progress, as required.
  SubcaseT = halt
  Immediate by the definition of open progress.
                                                                                                                □
  Theorem3.9provides a more global and concise view of the properties exhibited by a non-
reducing process in canonical form, and arises as an immediate corollary.
Theorem3.9      SupposeΨ;∆⊢•C whereC is in canonical form andC Y=⇒.
  LetC =  (νa1)(A1∥  (νa2)(A2∥···∥  (νan)(An∥M))...)).
  Either there exists someC′ such thatC =⇒C′, or:
  (1)For     1≤ i≤ n, each thread inAi is either:
       (a) achildthread◦M forwhichthereexistsa∈{aj| 1≤ j≤ i}∪fn(Ψ) suchthatready(a,M);
       (b)a zapper thread  ai; or
       (c)a buffer.
  (2)M =A′1∥···∥A′m∥T such that for1≤ j≤m:
       (a)A′j is either:
         (i) achildthread◦N suchthatN =  () orready(a,N ) forsomea∈{ai| 1≤ i≤ n}∪fn(Ψ)∪
            fn(∆);
         (ii)a zapper thread   a for somea∈{ai| 1≤ i≤ n}∪fn(Ψ)∪fn(∆); or
         (iii)a buffer.
       (b) EitherT  =•N, where N is either a value or ready(a,N ) for some a∈{ai| 1≤ i≤
         n}∪fn(Ψ)∪fn(∆); orT = halt.


                                                            , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 50

                                                                        50                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

                                                                        C.4   Confluence
                                                                        Theorem3.12(Diamond Property)              If Ψ;∆⊢ϕC, andC =⇒D1, andC =⇒D2, then either
                                                                        D1≡D2, or there exists someD3 such thatD2 =⇒D3 andD2 =⇒D3.

                                                                           Proof. As noted in Section3.4,   −→M is deterministic and hence confluent due to the setup
                                                                        of term evaluation contexts, and linearity ensures that endpoints to a buffer may not be shared.
                                                                        Consequently, communication actions on different channels may be performed in any order.
                                                                           Nevertheless, two critical pairs arise due to asynchrony. The first arises when it is possible to
                                                                        sendtoorreceivefromabuffer;thereisachoiceofwhetherthesendorthereceivehappensfirst.
                                                                        Both cases reduce to the same configuration after a single further step.







                                                                        The second critical pair arises when sending to a buffer where the peer endpoint has a non-empty
                                                                        bufferandhas beencancelled.Thereis achoiceastowhetherthe valueattheheadof thequeueis
                                                                        cancelledbeforeorafterthesendtakesplace.Again,bothcasesreducetothesameconfiguration
                                                                        after a single further step.







                                                                                                                                                                                         □


































                                                                        , Vol. 1, No. 1, Article . Publication date: November 2018.

                                 F[sendU a]∥ a(−→V )↭b(V·−→W )∥F′[receiveb]F[sendU a]∥ b∥ a(−→V )↭b(V·−→W )

F[a]∥ a(−→V )↭b(V·−→W·U )∥F′[receiveb]F[a]∥ b∥ a(−→V )↭b(V·−→W·U )F[sendU a]∥ b∥ V∥ a(−→V )↭b(−→W )F[sendU a]∥ a(−→V )↭b(−→W )∥F′[(V,b)]

                                        F[a]∥ a(−→V )↭b(−→W·U )∥F′[(V,b)]F[a]∥ b∥ V∥ a(−→V )↭b(−→W·U )

## PDF page 51

Exceptional Asynchronous Session Types                                                                                       51

D   SUPPLEMENT TO SECTION4.1(METATHEORY OF EGV WITH ACCESS POINTS)
In this section, we prove that the extension of EGV with access points satisfies preservation.
   Lemma D.1 (Preservation, Access Points (Eqivalence)).  If Γ;∆⊢ϕC andC≡D, then
Γ;∆⊢ϕD
   Proof. By induction on the derivation ofC≡D. Rule T-ConnectN subsumes T-Connect1
andT-Connect2,sothemajorityofcasesaresimilartothosewehaveproveninLemmaC.1.We
consider the case for associativity in detail.
CaseC∥  (D∥E)≡  (C∥D)∥E


                                                      Γ =Γ1 +Γ′
                                                                    Γ′ =Γ2 +Γ3
                                     Γ2,b1 :T1, ...,bm′ :Tm′,−−−−→c :S′;∆2,a1 :S1, ...,am : Sm,−−−−→d :T′⊢ϕ2D
                                    Γ3,bm′+1, ...,bn′ :Tn′,−−−−→d :T′;∆3,am+1 :Sm+1, ...,an :  Sn,−−−−→c :S′⊢ϕ3E
                                                                      −−−−−→  −−−−−→
      Γ1,−−−→a :S;∆1,−−−→b :T⊢ϕ1C               Γ′,−−−→b :T;∆2,∆3,−−−→a :S,c :S′♯,d :T′♯⊢ϕ2+ϕ3D∥E
                                       −−−−−→ −−−−−→ −−−−−→  −−−−−→
                         Γ;∆1,∆2,∆3,   a :S♯, b :T ♯,c :S′♯, d :T′♯⊢ϕ1+ϕ2+ϕ3C∥  (D∥E)
where         −−−→
                                  a : S =a1 :   S1,...,am :    Sm,...,an :    Sn
                                  −−−→
                                  b :T =b1 :T1,...,bm′ :Tm′,...,bn′ :Tn′

                                                        ⇐⇒


                                                     Γ =Γ′′+Γ3

                             Γ′′ =Γ1 +Γ2
                       Γ1,−−−→a :S;∆1,−−−→b :T⊢ϕ1C
           Γ2,b1 :T1, ...,bm :Tm,−−−−→c :S′;∆2,−−−−→d :T′⊢ϕ2D
                    Γ′′,am+1 :Sm+1, ...,an :Sn
                    ∆1,∆2,a1 :S♯1, ...,am :S♯m,
                       b1 :T ♯1 , ...,bm′ :T ♯                         Γ3,bm′+1, ...,bn :Tn,−−−−→d :T′;
                                           m′;
             bm′+1 :Tm′+1, ...bn′ :  Tn′,⊢ϕ1+ϕ2C∥D              ∆3,am+1 :   Sm+1, ...,an :  Sn,−−−−→c :S′⊢ϕ3E
                                       −−−−−→ −−−−−→ −−−−−→  −−−−−→
                         Γ;∆1,∆2,∆3,   a :S♯, b :T ♯,c :S′♯, d :T′♯⊢ϕ1+ϕ2+ϕ3C∥  (D∥E)
                                                                                                                     □
   The lemmas for subterm typeability and replacement are slightly different as we must consider
unrestricted environments.
   Lemma D.2 (Typeability of subterms (Access Points)).  IfD is a derivation ofΓ⊢ E[M] : A,
then there exist Γ1,Γ2 and B such that Γ = Γ1 +Γ2, thatD has a subderivationD′ that concludes
Γ2⊢ M : B, and the position ofD′ inD corresponds to the position of the hole inE.
   Proof. By induction on the structure ofE.                                                                □
   Lemma D.3 (Replacement (Access Points)).  If:
    • D is a derivation ofΓ⊢ E[M] :A, such thatΓ =Γ1 +Γ2
    • D′ is a subderivation ofD concludingΓ2⊢ M : B


                                                               , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 52

52                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

    • The position ofD′ inD corresponds to that of the hole in E
    • Γ3⊢ N : B
    • Γ′ =Γ1 +Γ3 is defined
   thenΓ′⊢ E[N] :A.
   Proof. By induction on the structure ofE.                                                                □

   Theorem D.4 (Preservation, Access Points).  IfΓ;∆⊢ϕC andC =⇒D, thenΓ;∆⊢ϕD.
   Proof. Recall that =⇒ is defined as≡−→≡. Therefore, the result arises by induction on the
derivation ofC−→D and as a corollary of LemmaD.1.
   Again,sinceT-ConnectNsubsumesT-Connect1 andT-Connect2,itsufficesonlytoprovethe
new cases required for access point reduction.

Case  E-Spawn

Assumption:
                                              Γ⊢ E[spawnM] :C
                                             Γ;·⊢••E[spawnM]
   By LemmaD.2, we have that            Γ =Γ1 +Γ2, and
                                                    Γ2⊢ M : 1
                                               Γ2⊢ spawnM : 1
   By LemmaD.3, we have that            Γ1⊢ E[()] :C.
   Recomposing:
                                Γ =Γ1 +Γ2      Γ1;·⊢• E[()]      Γ2;·⊢◦◦M
                                               Γ;·⊢• E[()]∥◦M
   as required.

Case  E-New

Assumption:
                                                Γ⊢ E[newS] :C
                                                Γ;·⊢••E[newS]
   By LemmaD.2and               TA-New, we have that·⊢ newS : AP(S).
   By LemmaD.3, we have that            Γ,z : AP(S)⊢ E[z] :C.
   Thus, we can show:
                              Γ,z : AP(S)⊢ E[z] :C
                              Γ,z : AP(S);·⊢••E[z]  ·;z : AP(S)⊢◦ z(ϵ,ϵ)
                                     Γ,z : AP(S);z :S⊢••E[z]∥ z(ϵ,ϵ)
                                          Γ;·⊢•  (νz)(•E[z]∥ z(ϵ,ϵ))
as required.

Case  E-Accept


, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 53

Exceptional Asynchronous Session Types                                                                                       53

Assumption:
                        Γ⊢ E[acceptz] :C
                        Γ;·⊢••E[acceptz]  ·;z :S,X :            S,Y :S⊢◦ z(X,Y)
                             Γ;z :S,X :  S,Y :S⊢••E[acceptz]∥ z(X,Y)
  ByLemmaD.2,wehavethat            Γ =Γ1+Γ2 andthatΓ2⊢ acceptz :S.ThusbyTA-Acceptwehave
thatz : AP(S)∈ Γ.
  By LemmaD.3, we have that            Γ,a :S⊢ E[a] :C.
  Recomposing, we have that:
                   Γ,a :S⊢ E[a] :C
                   Γ,a :S;·⊢••E[a]  ·;z :S,X :           S,a : S,Y :S⊢◦ z({a}∪X,Y)
                          Γ;z :S,X :   S,Y :S,a :S♯⊢••E[a]∥ z({a}∪X,Y)
                          Γ;z :S,X :   S,Y :S⊢•  (νa)(•E[a]∥ z({a}∪X,Y))
Case  E-Request
Assumption:
                       Γ⊢ E[requestz] :C
                       Γ;·⊢••E[requestz]  ·;z :S,X :             S,Y :S⊢◦ z(X,Y)
                             Γ;z :S,X :  S,Y :S⊢••E[acceptz]∥ z(X,Y)
  By LemmaD.2, we have that            Γ =Γ1 +Γ2 and thatΓ2⊢ requestz :       S. Thus by TA-Reqest we
have thatz : AP(S)∈ Γ.
  By LemmaD.3, we have that            Γ,a :S⊢ E[a] :C. As duality is involutive, we have that     S =S.
  Recomposing, we have that:
                   Γ,a : S⊢ E[a] :C
                   Γ,a : S;·⊢••E[a]  ·;z :S,X :          S,Y :S,a :S⊢◦ z(X,{a}∪Y)

                          Γ;z :S,X :   S,Y :S,a :   S♯⊢••E[a]∥ z(X,{a}∪Y)
                          Γ;z :S,X :   S,Y :S⊢•  (νa)(•E[a]∥ z(X,{a}∪Y))
as required.
Case  E-Match
Assumption:

                         ·;z :S,a :  S,X :  S,b :S,Y :S⊢◦ z({a}∪X,{b}∪Y)
  Recomposing:
                                                       S/ϵ =  S/ϵ  ·⊢ϵ :ϵ  ·⊢ϵ :ϵ
               ·;z :S,X :  S,Y :S⊢◦ z(X,Y)                 ·;a : S,b :S⊢◦ a(ϵ)↭b(ϵ)
                        ·;z :S,a : S,X :  S,b :S,Y :S⊢◦ z(X,Y)∥ a(ϵ)↭b(ϵ)

                                                                                                            □



                                                          , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 54

54                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova


                        B  b=⇒c  C
                                                                              A  e=⇒a  B    B  b=⇒c  C






                     (a) Simple Delegation

                     A  e=⇒a  B    B  b=⇒c  C







                                                                           (c) Entangled Delegation
                 (b) Simultaneous Delegation


                                      Fig. 13. Cases of Distributed Delegation





E   DISTRIBUTED DELEGATION
Akey featureofπ-calculusis mobility,that is,sending channelnames asvalues.In session-based
languages and calculi, mobility is realised as session delegation, allowing session-typed channel
endpoints to be sent over other session-typed channels. We sawan example of session delegation
in §6, in the  ChatClient type:

   typenameChatClient=!Nickname.
     [&|Join:?(Topic,[Nickname],ClientReceive).ClientSend,
         Nope:End|&];
An endpoint of typeClientReceive is passed as a message.

E.1   Challenges of Distributed Delegation
Session delegation is a vital abstraction in session-based programming. However, its integration
with both asynchrony and distribution brings several challenges. The seminal work on distributed
delegation is Session Java [Hu et al.2008].
   Fig.13shows three scenarios of distributed delegation, as described byHu et al             . [2008]. We
write X   x=⇒y  Y to indicate that X wishes to send x to Y overy on the basis that X’s last known

location ofthe correspondingendpoint fory isY. Now supposeB  b=⇒c  C. FollowingHuet al        . [2008],
we refer toB as the session-sender,C as the session-receiver, andAas a passive party. Thereis no
happens-before relation betweenAsending a message toB alonga, andB delegatingb toC along
c. Thus, a message could be sent toAafter Ahas given up control ofa. FollowingHu et al        . [2008],
we call such messages lost messages.


, Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 55

Exceptional Asynchronous Session Types                                                                                       55


                         1.A→S :Send(t,v,[b7→−→V])
                         2.        A :start recording lost messages−→W forb
                         3.         S :σ =σ[b7→ B];δ =δ∪{t}
                         4.S→ B :Deliver(t,v,[b7→−→V])
                         5.S→A :GetLostMessages([b])
                         6.        A :stop recording lost messages forb
                         7.A→S :LostMessageResponse([b7→−→W])
                         8.S→ B :Commit(t,[b7→−→W])
                         9.         S :δ =δ\{t}
                       10.        B :buffers[b] =−→V ++−→W ++−→U
                                     where−→U = messages received for b between (3) and (8)


                              Fig. 14. Operation of Distributed Delegation Protocol


E.2   Approaches to Distributed Delegation
The simplest safe way to implement distributed delegation is to store all buffers on the server, but
thisrequiresablockingremotecallforeveryreceiveoperation.Asecondnaïvemethodisindefinite
redirection, where the session-sender indefinitely forwards all messages to the session-receiver.
Thisretainsbufferlocality,butrequiresthesession-sendertoremainonlineforthedurationofthe
delegated session.
   Huetal.[2008]describetwomorerealisticdistributeddelegationalgorithms:a         resendingprotocol,
which re-sends lost messages after a connection for the delegated session is established, and a
forwarding protocol,which forwardslost messages before thedelegatedsessionis established.The
key idea behind both algorithms is to establish a connection between the passive party and the
session-receiver,ensurethatthelostmessagesarereceivedbythesession-receiver,andtocontinue
the session only once lost messages are received.

E.3   Delegation in Distributed Session Links
Alas, we cannot directly re-use the resending and forwarding protocols ofHu et al   . [2008] because
of twofundamental differencesin oursetting: Links clientsdo notconnect toeach otherdirectly,
and in Links multiple sessions may be sent at once. Thus, we describe the high-level details of a
modified algorithm which addresses these two constraints. We utilise two key ideas:
    • Much like the resending protocol, lost messages are retrieved and relayed to the session-
       receiver once the new session has been established.
    • Weensurethe session-receiverendpoint isnotdelegated untilthe delegationhascompleted,
       byqueueing messagesthat includethe session-receiverendpoint,and resendingthem once
       delegation has completed.
   Wenow considerthecasewheresession-senderandsession-receiver aredifferentclients; the
case where session-sender is a client and session-receiver the server is similar. Let client A be
session-sender and clientB be session-receiver.

   Example. Suppose clientAsends a valuev containing a session endpointd along channel (s,t),
recalling thats is thepeer endpoint andt is thelocal endpoint. Theinitial endpoint locationtable
is:
                                      σ≜ [s7→A,t7→ B,b7→A,c7→A]


                                                                , Vol. 1, No. 1, Article . Publication date: November 2018.

## PDF page 56

56                                                   Simon Fowler, Sam Lindley, J. Garrett Morris, and Sára Decova

Fig.14showstheoperationofthedelegationprotocolonthisexample.InStep1,          Asendsamessage
to the server S, containing the peer endpoint t, value to sendv, and the buffer−→V  forb, before
beginningtorecordlostmessagesforb.Uponreceivingthismessage,theserverupdatesitsinternal
mapping forthe location ofb to beB, addst to theset ofdelegation carriersδ, andsends a Deliver
message containingt,v, and−→V, beforesending aGetLostMessages request toA. Uponreceiving
this message, A will stop recording lost messages forb, and relay the lost messages−→W forb to
S.TheserverthensendsaCommitmessagecontainingt andthelostmessagesforalldelegated
endpoints, and removest from the set of delegation carriers.
   The final buffer forb is the concatenation of the initial buffer−→V, the lost messages−→W, and all
messages−→U received forb before the Commit message.

E.4   Correctness
We arguecorrectness of the algorithmin a similarmanner toHu etal   . [2008]. Dueto co-operative
threading,wecantreateachsequenceofactionshappeningatasingleparticipant(forexample,
steps3–8)asatomic.Since(asperstep3)theendpointlocationtableisupdatedpriortothelost
messagerequest,wecansafelysplitthebufferofthedelegatedsessionintothreeparts:theinitial
bufferbeingdelegated(−→V);thelostmessages(−→W);andthemessagesreceivedafterthechangein
thelookuptablebutbeforetheCommitmessageisreceived(−→U)andreassemblethem,retaining
ordering.
   Inoursetting,sincesessionchannelsarenotassociatedwithsockets,simultaneousdelegation
(Fig.13b) canbehandled inthesamewayas simpledelegation.Inthe caseofentangleddelegation
(Fig.13c),sincedelegationcarriersmaynotbedelegatedthemselvesuntilthelostmessageshave
been received, we can be sure that the lost message requests are sent to the correct participant.
Hence, the case devolves to simple delegation.



































, Vol. 1, No. 1, Article . Publication date: November 2018.
