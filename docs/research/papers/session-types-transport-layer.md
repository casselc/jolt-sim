# Session Types for the Transport Layer: Towards an Implementation of TCP

**Machine conversion:** extracted from the adjacent PDF with `pypdf`; page boundaries are retained, while equations, figures, and multi-column layout may not round-trip faithfully. Consult the PDF for authoritative pagination and notation.

## PDF page 1

          Session Types for the Transport Layer: Towards an
                                      Implementation of TCP*


                     Samuel Cavoj                     Ivan Nikitin                   Colin Perkins
                    samuel@cavoj.net               ivan@niktivan.org               csp@csperkins.org
                   University of Glasgow           University of Glasgow           University of Glasgow
                                                     Ornela Dardha
                                             ornela.dardha@glasgow.ac.uk
                                                   University of Glasgow


       Sessiontypesarea typingdisciplineusedtoformallydescribecommunication-drivenapplications
       withtheaimoffewererrorsandeasierdebugginglaterintothelifecycleofthesoftware. Protocolsat
       thetransport layersuchasTCP,UDP,and QUICunderpinmostof thecommunicationon themodern
       Internetandaffectbillionsofend-users. Thetransportlayerhasdifferentrequirementsandconstraints
       compared to the application layer resulting in different requirements for verification. Despite this,
       to our best knowledge, no work shows the application of session types at the transport layer.  In
       this work, we discuss how multiparty session types (MPST) can be applied to implement the TCP
       protocol. We develop an MPST-based implementation of a subset of a TCP server in Rust and test
       itsinteroperabilityagainsttheLinuxTCPstack. Ourresultshighlightthedifferencesinassumptions
       between session type theory and the way transport layer protocols are usually implemented. This
       work is the first step towards bringing session types into the transport layer.

1   Introduction

Sessiontypes[11]areatypingdisciplineforcommunicationprotocols. Theycandescribethesequence
ofmessagesexchangedbetweenparticipantsoveracommunicationchannelandcanbeusedtoverifythat
the protocol is implemented correctly or has certain desirable properties. Further, session types can be
realisedwithinprogramming languagesandusedto type-checktheimplementationofa protocolagainst
a session type definition, with type errors indicating inconsistencies between implementation and the
session type. Session types have been an active area of research since the beginning of the 1990s [11]
and have been implemented in a number of programming languages including C [26], Java [13] and Rust
[14, 15] and other programming languages [9, 16, 25, 27, 29].
    Network protocols that are part of the Internet Protocol suite (TCP/IP) are the foundation of the
Internet.  They are responsible for interoperability between different devices, operating systems, and
applications. To ensure that different implementations of the same protocol are compatible, they must
adhere to a technical specification which, in the case of Internet protocols, is defined in a series of
documents,knownasRFCs[8],developedbytheInternetEngineeringTaskForce(IETF). Specifically,
the latest version of the TCP protocol specification is defined in RFC 9293 [7].
    The IETF follows a consensus-based process when developing standards [4, 30], with protocol
specifications being developed in working group meetings and on mailing lists over a multi-year period.
The resulting RFCs are written primarily in English prose, allowing the documents to be used in the

   *Supported in part by the UK EPSRC grants EP/X027309/1 and EP/S036075/1.


D. Costa, R. Hu (Eds.): Programming Language Approaches to                      © S. Cavoj, I. Nikitin, C. Perkins, O. Dardha
Concurrency and Communication-cEntric Software 2024 (PLACES’24)                 This work is licensed under the
EPTCS 401, 2024, pp. 22–36, doi:10.4204/EPTCS.401.3                             Creative Commons Attribution License.

## PDF page 2

S.Cavoj,I.Nikitin,C.Perkins,O.Dardha                                                                                              23


consensus-buildingprocess,butthenatural languagecanbeambiguousandunclearandthiscanleadto
inconsistentand non-conformingimplementations. [22,23,28]. Inthis sense,ensuring thecorrectnessof
Internet protocols is vital. Developing formalised models of the protocols described in RFCs is one way
to achieve this. Session types are one such modelling technique that has not previously been explored for
transport-layer protocols, such as TCP.
    In this paper, we implement a core subset of the TCP protocol in the Rust programming language
and use session types to describe the network operations. Session types are encoded into native Rust
types and the type checker is used to verify that the implementation follows the session type specification.
Inthisway,theRustcompilerverifiesthattheimplementationoftheprotocoliscorrectintermsofthe
typesofmessagesexchangedandtheorderinwhichtheyareexchanged,i.e.,thatitfollowsthedeclared
session type, for a session type model describing a synchronous subset of TCP. Additionally, session
types are used to describe the application interface, so we can verify that the application uses the TCP
implementation correctly.
    Our contributions are as follows:
   1.  Session Types Libraries. We develop1 the libraries required for encoding the session type model
       into native Rust types in an ergonomic fashion (§4.1).
   2.  Implementation. WeimplementasubsetoftheTCPprotocol[7],includingkeyaspectsofboth
       theuser/TCPinterfaceandtheTCP/lower-levelinterface,inRustwhileadheringtothesessiontype
       model. Thisis donein away suchthat theRust compiler candetect deviation fromthe sessiontype
       (§4.4).
   3. Testing. We test our implementation against a real TCP stack (§5).
    The remainderof this paper isstructured as follows. Section 2 brieflyreviews the multiparty session
typemodelweuse. Section3outlineskeypropertiesofTCPanditsstatemachine. Section4describesour
session typedimplementation of TCPin Rust. Section 5 evaluates the correctnessof our implementation.
Finally, Section 6 reviews related work and concludes.

2   Session types

Session types [11] describe communication among participants in a distributed system in terms of the
typesandorderofmessagesthatareexchanged. Asinglesessiontypedescribesthesequenceofmessages
sent or received from the perspective of one of the participants. The theory of session types was later
extended to multiparty session types (MPST) which can describe protocols between any number of
participants [12].
    In this paper, the bottom-up multiparty session type approach [31] is used to describe TCP. An
example of a simple ping-pong protocol using this approach is demonstrated in Equation 1.  When
type-checking any type using the bottom-up approach, we must additionally choose a safety invariant.
Safetyinvariantsareparametersassociatedwiththepropertiesaprotocolmaydemonstrateduringruntime,
suchasdeadlock-freedomandliveness. Eachsafetyinvariantisaccompaniedbyspecifictypingrules(not
presentedhere)thatguaranteethemaintenanceofthecorrespondinginvariant. Iftheprotocolsuccessfully
type-checks with the instantiation ofthe safetyinvariant, itwill manifestthe propertyrepresented bythe
invariant during its runtime.


   1Our session type library and TCP implementation is available athttps://github.com/sammko/tcpst2

## PDF page 3

24                                                                                                                           SessiontypesforTCP



                               Γ1 =    s[a]:b ⊕ l1(ping) .b & l3(pong) .end,
                                        s[b]:a & l2(ping) .a ⊕ l1(pong) .end                                       (1)

    Theimplicationsofthisapproacharethatglobaltypesandtheconceptofdualityarenotused. Instead
of duality, the compatibility invariant is used to check that actions are dual between the given types.
However, a protocol can still be described using session types even if safety does not hold.


3   Transmission Control Protocol (TCP)

The TCP transport is layered ontop of the datagram service provided by the Internet Protocol (IP). The
IP layer provides an unreliable, best-effort, datagram service, where packets may be lost, duplicated,
delayed,orre-orderedintransit. TCPsegments,sentwithinIPpackets,containsequencenumbersand
acknowledgements such that, upon detection of a lost packet, either triggered by a timer expiration or
receipt of a triple-duplicate acknowledgement, the sender can re-transmit the lost segment.
    TCPisusuallyusedinaclient-servermanner,butalsosupportsararelyusedsimultaneousopenmode
withpeer-to-peerconnections. Inthecontextofthispaper, weassumeclient-serverusage,withoneside
beingapassiveserverlisteningforincomingconnections,whiletheotherisanactiveclientinitiatingthe
connection. We describe the operationof the TCP state machine below andprovide a diagram of the TCP
state transitions in Figure 1.
    The establishment of a reliable connection between two network devices is facilitated by the TCP
three-way handshake. It commences with the initiation of a connection with the client sending a TCP
segment with the SYN (synchronise) bit set in the header and containing the client’s initial sequence
number. The server responds with a segment with the SYN and ACK bits set, acknowledging the client’s
initial sequence number and providing the initial sequence number the server will use. Finally, the client
confirmstheestablishmentoftheconnectionbysendingasegmentwiththeACK(acknowledge)bitset.
This sequence ensures both sides agree on their initial sequence numbers and confirm their willingness to
communicate.
    TCPusesaslidingwindowalgorithmtomanagedatatransmissionbysendingsegmentswithsequence
numbers. The window size determines the number of unacknowledged segments in transit. The receiver
discardsunacceptablesegmentsfallingoutsidetheexpectedsequencerange,leadingtoretransmission
by the sender.  Acknowledgements are sent upon receiving new data, indicating the next expected
contiguoussequencenumber. TCPhandlespacketlossorreorderingattheIPlayerbydetectingduplicate
acknowledgements; a triple-duplicate acknowledgement triggers retransmission.  Additionally, TCP
utilises a retransmission timeout (RTO) mechanism, dynamically adjusted based on network conditions.
TCPbuffersplayacrucialroleonboththesenderandreceiversides,withthesendbufferholdingoutgoing
segmentsawaitingacknowledgementandthereceivebufferstoringincomingsegmentsyettobedelivered
to the application.
    TheTCPclosinghandshake,anotherthree-wayhandshakeinvolvingpacketswiththeFIN(finish)and
ACK(acknowledge)bitssignifiestheendofaconnection. TheinitialpartysendsaFINpacket,followed
by an acknowledgement from the other party, culminating in a reciprocal FIN-ACK exchange. The final
stepincludesanacknowledgementfromtheoriginalsender,leadingtotheTIME-WAITstate. Thisstate
ensures areliable closure, allowingthe handling of delayedor duplicate IPpackets before concludingthe
connection.

## PDF page 4

S.Cavoj,I.Nikitin,C.Perkins,O.Dardha                                                                                              25






































Figure 1:  The state transition diagram of TCP in RFC9293 [7].  We annotate the diagram with the
messages and transitions modelled in our implementation. Note that we do not model timeouts as part of
thetypesystem,hence,theTIME-WAITtoCLOSEDtransitionisnotimplementedusingsessiontypes.
Additionally,wedonotimplementtheactiveOPENcaseofthehandshakeforsimplicity(asthiswould
not demonstrate any new modelling or implementation techniques), this is however possible using our
implementation.

4   Implementation

WeimplementthebasicfunctionalityoftheTCPserverprotocolwhilemodellingboththenetworkand
the application interface using session types. Note that more information on the implementation can be
foundintheAppendix. Underthelessismore formalisationofmultipartysessiontypes[31],theroleswe
are considering the following:

Server User      The server application using the TCP protocol.
Server System The TCP implementation.

## PDF page 5

26                                                                                                                           SessiontypesforTCP


Client System  The TCP implementation on the other end of the network.

    Thechannel betweenthe ServerSystem andthe ClientSystem represents thenetwork. Themessages
exchanged between the Server User and the Server System are a formalisation of the user/TCP (i.e.,
application programming) interface and do not pass over the network.  The system call interfaces,
representing the user and the system (in this paper simulated through threads), each have a session type
which prescribes their behaviour relative to the other roles. The Client System role has no associated
session typein ourimplementation as itis assumedto be anotherhost on theInternet andnot part ofour
program.

4.1   Defining session types
ThebasicbuildingblocksofourimplementationarethegenericstructsOfferOne,OfferTwo,SelectOne,
andSelectTwo. Allofthese implementthe traitAction whichrepresents ageneral sessiontype. The
typeparametersof thestructsencodetherolethe action isperformedwithrespectto, thetypesofmes-
sagesexchanged, andthecontinuationofthesession. Inaddition, theEndstructisalsoanActionand
represents the end session type.
    TheOfferTwostructhasfivetypeparameters. Thefirstisthepeerrole,andthenexttwoarethetypes
of messages exchanged in either of the two branches of the offer and the final two parameters are the
session types of the continuations of the two branches.
pubstructOfferTwo<R,  M1,  M2,  A1,  A2>
whereR:Role,  M1:Message,  M2:Message,
      A1:Action,  A2:Action,
{
      phantom:PhantomData<(R,  M1,  M2,  A1,  A2)>,
}
The OfferTwo struct, as a way of encoding a session type construct in Rust, has type parameters but
contains no data. ThePhantomData-typed field contained within thestruct is a zero-sized marker type
that simulates a field of the given type to support the Rust type checker.2
    TheSelectTwo structhas thesametype parametersandis also azero-sizedtype. Finally,thenon-
branching actionsOfferOne andSelectOne have only three type parameters: the peer role, the message
type, and the continuation type, but are otherwise analogous.
    Todefineasessiontypeonecandefineatypealiasfortherootactionofthesession. Forexamplea
simple session type for a client-server interaction could be defined as follows:
typeServerSt  =OfferOne<Client,  Request,SelectOne<Client,  Response,End>>;
typeClientSt  =SelectOne<Server,  Request,OfferOne<Server,  Response,End>>;
    Thisbasicsyntax,however,quicklybecomesunwieldywhendefiningmorecomplexsessiontypes. To
address this,we have implementeda macrowhich convertsa morereadable syntaxinto thefull definition
of the type. Rust’smacro_rules! mechanism is powerful enough toallow usto define a syntax which
attemptstomimicthemathematicalnotation. ThemacroiscalledSt!andtheServerSttypefromthe
above example could be re-written as follows:
typeServerSt  =St![(Client&  Request).(Client+  Response).end]

   2https://doc.rust-lang.org/nomicon/phantom-data.html

## PDF page 6

S.Cavoj,I.Nikitin,C.Perkins,O.Dardha                                                                                              27


    The macro is recursive and supports arbitrary nesting of offers and selections. The full definition can
be found in thest      macros.rs file of the source code.

4.2   Multi-wayOffer branching
AsRust doesnotsupportvariadicgenerictypes, wearenot awareof awaytoimplement agenericOffer
typewhichwouldsupportavariablenumberofbranches. HenceweimplementOfferOneandOfferTwo
as separate constructs with some repetition in the corresponding infrastructure such as theoffer                         one,
offer    two and similar selection methods. These are described in §4.4.
    However, support for more than two branches is required in practice. A simple way to do this is to
implement OfferThree, OfferFour, ..., in the same way, along with the code supporting this. This
leads to more code duplication, but does not increase complexity, and the usage is straightforward.
    As an alternative, to avoid duplication, we chose a nesting approach where a branching of arity N is
transformedintoatwo-way branching betweenthefirstcaseandanN−1branchingoftheother cases.3
This is recursively expanded until it finally results in a tree of two-way forks, where each left branch
represents a single case from the original N. All right branches except the bottom-most one lead to a
virtual node which was not present in the original type.

4.3   Recursive session types
Type aliases in Rust cannot be recursive. The reason for this is that a type alias does not create a new
typeandismerelyanothernameforthesametype. Forinstance,definingatypealiastypeA  =  X<A>
is not allowed becausethe expansion would be infinite –the nameX<A>would expand toX<X<A>>, etc.
However, we somehow need to represent recursive session types.
    Fortunately, this is not difficult to circumvent. Whereas type aliases cannot be recursive, there is
no such restriction for types themselves, as long as the size of the type is finite. As such, types which
containarecursivecyclewithnoindirectionarenotallowedasthesizeofthetypeisinfinite. Butinserting
indirection into the cycle (such as a reference &T or Box<T>      ) resolves this problem since the size of a
reference does not depend on the size of the target typeT.

4.4   Using session types
A channel provides methods to send and receive messages which consume a corresponding session type
and return the continuation. The type of the channel is generic over the roles between which it exists and
the methodsignatures ensure thattheycan be onlycalled with an appropriate session type instance and
message. Consider a channel of typeChannel<R1,  R2> which we define as the endpoint belonging to
role R1, i.e. it can send to or receive from R2. Then its select               one method could have the following
signature:
fnselect_one<M,  A>(&mutself,  _o:SelectOne<R2,  M,  A>,  message:  M)  ->  A
whereM:Message,  A:Action;
    It is generic over the message type, but it has to match the one prescribed by the provided session
typed token. The role R2 is already bound by the channel type. The token is moved into this function,
so the owner cannot re-use it. The continuation type from the token is instantiated and returned to the

   3Naturally, it would be better to split into halves instead, reducing the expansion depth fromO(N) toO(logN) but this is
more difficult to implement and provides little practical benefit in all but the most extreme branching cases.

## PDF page 7

28                                                                                                                           SessiontypesforTCP


callerforfurtheroperations. And,ofcourse,themessageistransmittedovertheunderlyingtransportthe
nature of which is not restricted by this abstraction. The only requirement is that theMessage trait can
be convertedto a representationthat thechannel canprocess, which isthe reasonfor the trait in thefirst
place.
    The implementation of the offer methods is slightly more involved. Once a message is received
fromtheunderlyingtransportwemustdeterminewhichbranchoftheoffertotakeandconvertittothe
appropriatemessagetype. Weoutsourcethedecisiontoafunctionwe receiveasanargumentcalledthe
picker. We find that in our particular use case, having the capability to differentiate branches based on
externalcontextisnecessary. Thisallowsustodistinguishthereceiptofanexpectedpacketfromtheerror
condition when an unexpected packet is received

4.5   Establishing a Connection
A TCP connection is established via a three-way handshake as described in Section 3. We define the
ServerSystemSessionTypetodescribecreationoftheserversocket(receiptofOpenfromtheserver
user), creating the internal state (the “TCB”; §A.1), waiting for a SYN from the client, and generating the
SYN-ACKsegment,correspondingtothetransitionthroughtheLISTENstateofFigure1intotheSYN
RCVD state:
pubtypeServerSystemSessionType  =St![
      (RoleServerUser&  Open).
      (RoleServerUser+  TcbCreated).
      (RoleClientSystem&  Syn).
      (RoleClientSystem+  SynAck).
      ServerSystemSynRcvd
];
    The ServerSystemSynRcvd type describes the SYN RCVD state, with branches indicating the
transition to the ESTAB state inServerSystemCommLoop if the received ACK is acceptable or closing
the connection if not.
Rec!(pubServerSystemSynRcvd,  [
      (RoleClientSystem&  {
            Ack.  //acceptable(i.e.,matchestheSYN-ACKsent)
                  (RoleServerUser+  Connected).
                  ServerSystemCommLoop,
            Ack.  //unacceptable
                  (RoleClientSystem+  {
                        Ack.ServerSystemSynRcvd,  Rst.(RoleServerUser+  Close).end
                  })
       })
]);
The implementation of three-way handshake is further described in Appendix A.1.

4.6   Data Transmission and Re-transmission
When a TCP segment goes unacknowledged for a certain amount of time, it is retransmitted. There are
two implementationchoices that could be madehere: incorporatetimeouts into thetype system,or leave

## PDF page 8

S.Cavoj,I.Nikitin,C.Perkins,O.Dardha                                                                                              29


them out and instead signal session type transitions using external timeouts. The session type theory we
are using does not have a notion of timeouts, nor does any session type work containing timeouts [1, 2, 5]
havethe abilitytomodelthe operationsneededfor TCPtimeouts. Hence,we opttoemulate timeoutsby
introducingavirtualmessagetypeandaddingitasanotherbranchtotheoffersessiontype. Inthisbranch,
wecontinuewithaselectoperation,retransmittinganACKmessageandthenrecursivelyreceivingthe
next message. Theoffermethod on thenetwork channel now acceptsanother argument, specifying the
timeoutduration orNone ifno timeoutis desired. Ifthe retransmissionqueueis empty,notimeout should
beemployedaswerunintoanissueifitexpires–thesessiontyperequiresasegmenttobesent,butthere
is nothing to send. Further details around data transmission are in Appendix A.2.

4.7   Closing the connection
ClosingaTCPconnectionisatwo-stepprocessusuallycombinedintoathree-wayhandshake,asshownin
thelowerhalfofFigure1. Eachdirectionofthestreamcanbeclosedindependentlybysendingasegment
with the FIN bit set. The Server System session type describes receiving a FIN first and then deciding to
closeeventually,afterallowingthe usertosendmoredatausing theServerSystemCloseWaitsession
type:
Rec!(pubServerSystemCloseWait,  [
      (RoleServerUser&  {
            Data.
                  (RoleClientSystem+  Ack).
                  (RoleClientSystem&  Ack                                    /*emptyack*/                    ).
                  ServerSystemCloseWait,
            Close.
                  (RoleClientSystem+  FinAck).
                  (RoleClientSystem&  Ack).
                  end
      })
]);
    The case where the server closes first is handled by theServerSystemFinWait1type:
pubtypeServerSystemFinWait1=St![
      (RoleClientSystem&  {
            Ack.  //ACKofFIN
                  ServerSystemFinWait2,
            FinAck.  //FINandACKofourFINatthesametime
                  (RoleClientSystem+  Ack).
                  end
      })];
ThebranchintheServerSystemFinWait1typerepresentsthewaysinwhichtheclosinghandshakecan
proceed aftersending aFIN toclose theconnection andentering intothe FINWAIT-1 state(see Figure1):
eitherasegmentcontaininganACKisreceivedcausingthesystemtotransitiontoFINWAIT-2,waiting
for a segment containing a FIN indicating that the peer has also finished; or a segment with both FIN
and ACK is received causing the final ACK to be sent and terminating the connection via the implied
CLOSING and TIME-WAIT states. The ServerSystemFinWait2 implementation is analogous, but
elided due to space constraints.

## PDF page 9

30                                                                                                                           SessiontypesforTCP


    Finally, in a full TCP implementation, a “simultaneous close” situation can occur where both peers
decide to close at the same time. This is not handled by our implementation as it is rarely used and does
not fitwith thecall-and-responsestyle ofinteraction wemodel –there isno opportunityfor theserver to
decide to close while waiting for the client.

5   Evaluation

To evaluate our Server System component, we have implemented a simple echo server in the Server User.
Everypieceofdata itreceives fromthesystemissplitintolines, eachlineisreversedandthensent back.
    The functionality of the server tested is as follows:
Establishing a connection by running netcat and connecting to the server.
Exchanging data with the client by typing in messages manually.
Initiating connection close bysendinganemptylinetotheserver. Theserveruserhasbeenprogrammed
       to close the connection if an empty line is received.
Responding to connection close  bytyping^Cwhichcausesnetcattoclosethesocketandthereforesend
       a FIN to the server.
Correctly handling a FIN-ACK response to a FIN bypipinganemptylineimmediatelyfollowedby
       EOF to netcat.  In this situation netcat sends the empty line but does not shutdown the socket
       immediately. Instead it waits for the server to send a FIN-ACK and then sends a FIN-ACK in
       response.
    WetestedourTCPimplementationprimarilyagainsttheLinuxkernelTCPstack,runningourprogram
and connecting to it using a Linux user-space TCP client (netcat). We have used Scapy [32], a packet
manipulation framework, to emulate a misbehaving TCP client or network and evaluate the behaviour
of our server in response to this. This included sending packets with invalid sequence numbers, invalid
acknowledgement numbers, spurious retransmission or overlapping segments. Finally, we have tested
our implementation against the Linux kernel TCP stack with the addition of simulated network errors
using the netem module to introduce packet loss, delay and reordering. Our test script configures the
TCP  NODELAY option on the socket and sends messages in a loop with a small delay between them. This
ensures that the client sends a lot of small packets to observe the effect of packet loss and reordering.
Thereceiveddatawasthencomparedtotheexpectedoutput. Inallcases,weutilisedapacketsnifferto
monitor the communication.
    All of the presented test cases were found to be handled correctly provided the server is in the
ESTABLISHED state. During the opening three-way handshake, after the initial SYN segment, handling
isrobustaswell. Wefoundthattheservercanhandlepacketlossandreorderingerrorsandtheconnection
canrecoveroncetheimpairmentsarelifted. Theserverdoesnotcacheoutofordersegmentsinthereceive
window affectingperformance,since thesesegmentswill needtobe re-transmitted,but notcorrectness.
Thisisalimitationinherentinusingsynchronoussessiontypestomodelansynchronousprotocolthat
permits reordering and packet loss, and suggests future work to extend the modelling approach.

6   Related Work and Conclusion

Network protocols have been used as examples for various session type theories. The main protocols
used as a demonstration in many works are SMTP [3, 6, 17, 18, 19, 20, 21] and POP3 [10, 24]. Both of

## PDF page 10

S.Cavoj,I.Nikitin,C.Perkins,O.Dardha                                                                                              31


theseprotocolsareapplication-layerprotocols. Duetothis,modelsofSMTPandPOP3canassumethe
guaranteesprovidedbytheunderlyingtransportlayerprotocol–inmostcasesthisisTCP.Specifically,
any faults, retransmissions and packet re-orderings are handled by the transport layer. In addition to this,
theseworksdoimplementormodeltheprotocolsexactlyfromthespecificationwithsomeworksonly
implementing SMTP partially. The specific challenges presented by the network link arenot considered
andthecommunicationchannelisconsideredonlyinanabstractmanner. Thisalsomeansthat,unlikeour
implementationofTCP,theworksarenotshowntoconnectorworkwithexistingprotocolstacks,suchas
the kernel. Toour bestknowledge,ours isthe firstwork toconsider theimplementationof transportlayer
protocols from their specification using session types.
    Inthispaper,wehavemodelledTCPoftheInternetprotocolsuite[7]usingMPST[31]andimple-
mentedaproofofconceptintheRustprogramminglanguage,leveragingtheRusttypesystemandborrow
checkerto verify thattheimplementation complieswiththe sessiontype. We havesuccessfully testedour
implementationusingmanualtestingagainsttheLinuxkernelTCPstackaswellasmanuallyconstructed
TCP segments. In future work, we aim to address limitations of our implementation such as a lack of
timeoutsinthetypesystemandthesynchronousnatureofourimplementation. Weadditionallyaimto
model important aspects of the protocol such as congestion control in the future.

References

 [1]  Adam D. Barwell, Alceste Scalas, Nobuko Yoshida & Fangyi Zhou (2022): Generalised Multiparty Session
     TypeswithCrash-StopFailures. InBartekKlin,SlawomirLasota&AncaMuscholl,editors: 33rdInternational
     ConferenceonConcurrencyTheory,CONCUR2022,September12-16,2022,Warsaw,Poland,LIPIcs 243,
     SchlossDagstuhl -Leibniz-Zentrum f¨ur Informatik,pp. 35:1–35:25,doi:10.4230/LIPICS.CONCUR.2022.35.
 [2]  LauraBocchi,MaurizioMurgia,VascoThudichumVasconcelos&NobukoYoshida(2019): Asynchronous
     Timed Session Types - From Duality to Time-Sensitive Processes.  In Lu´ıs Caires, editor:  Programming
     Languages and Systems - 28th European Symposium on Programming, ESOP 2019, Held as Part of the
     EuropeanJointConferencesonTheoryandPracticeofSoftware,ETAPS2019,Prague,CzechRepublic,April
     6-11,2019,Proceedings,LectureNotesinComputerScience11423,Springer,pp.583–610,doi:10.1007/978-
     3-030-17184-1    21.
 [3]  LauraBocchi,MaurizioMurgia,VascoThudichumVasconcelos&NobukoYoshida(2019): Asynchronous
     Timed Session Types - From Duality to Time-Sensitive Processes.  In Lu´ıs Caires, editor:  Programming
     Languages and Systems - 28th European Symposium on Programming, ESOP 2019, Held as Part of the
     EuropeanJointConferencesonTheoryandPracticeofSoftware,ETAPS2019,Prague,CzechRepublic,April
     6-11,2019,Proceedings,LectureNotesinComputerScience11423,Springer,pp.583–610,doi:10.1007/978-
     3-030-17184-1    21.
 [4]  Scott O. Bradner (1996): The Internet Standards Process – Revision 3. RFC 2026, doi:10.17487/RFC2026.
     Available athttps://www.rfc-editor.org/info/rfc2026.
 [5]  MatthewAlanLeBrun&OrnelaDardha(2023): MAGπ: TypesforFailure-ProneCommunication. InThomas
     Wies,editor: ProgrammingLanguagesandSystems-32ndEuropeanSymposiumonProgramming,ESOP
     2023,HeldasPartoftheEuropeanJointConferencesonTheoryandPracticeofSoftware,ETAPS2023,Paris,
     France,April22-27,2023,Proceedings,LectureNotesinComputerScience13990,Springer,pp.363–391,
     doi:10.1007/978-3-031-30044-8       14.
 [6]  ChristianBartoloBurl`o,AdrianFrancalanza&AlcesteScalas(2021): OntheMonitorabilityofSessionTypes,
     in Theory and Practice (Artifact). DagstuhlArtifactsSer.7(2), pp. 02:1–02:3, doi:10.4230/DARTS.7.2.2.
 [7]  Wesley Eddy (2022): Transmission Control Protocol (TCP). RFC 9293, doi:10.17487/RFC9293. Available at
     https://www.rfc-editor.org/info/rfc9293.

## PDF page 11

32                                                                                                                           SessiontypesforTCP


 [8]  Heather Flanagan (2019): Fifty Years of RFCs.  RFC 8700, doi:10.17487/RFC8700.  Available at https:
      //www.rfc-editor.org/info/rfc8700.
 [9]  Simon Fowler (2016):  An Erlang Implementation of Multiparty Session Actors.  In Massimo Bartoletti,
      LudovicHenrio,SophiaKnight&HugoTorresVieira,editors: Proceedings9thInteractionandConcurrency
      Experience,ICE2016,Heraklion,Greece,8-9June2016,EPTCS223,pp.36–50,doi:10.4204/EPTCS.223.3.
[10]  SimonGay,Vasco Vasconcelos&Ant´onioRavara(2003): SessionTypesfor Inter-ProcessCommunication.
      Available athttps://www.dcs.gla.ac.uk/~simon/publications/TR-2003-133.pdf.
[11]  Kohei Honda, Vasco T. Vasconcelos & Makoto Kubo (1998): Language primitives and type discipline for
      structured communication-based programming.  In Chris Hankin, editor:  Programming Languages and
      Systems, Springer Berlin Heidelberg, Berlin, Heidelberg, pp. 122–138, doi:10.1007/BFb0053567.
[12]  KoheiHonda,NobukoYoshida&MarcoCarbone(2008): MultipartyAsynchronousSessionTypes. In: Proc.of
      the35thAnnualACMSIGPLAN-SIGACTSymposiumonPrinciplesofProgrammingLanguages,POPL’08,
      Association for Computing Machinery, New York, NY, USA, pp. 273–284, doi:10.1145/1328438.1328472.
[13]  Raymond Hu, Nobuko Yoshida & Kohei Honda (2008): Session-Based Distributed Programming in Java.
      In Jan Vitek, editor: ECOOP 2008 – Object-Oriented Programming, Springer Berlin Heidelberg, Berlin,
      Heidelberg, pp. 516–541, doi:10.1007/978-3-540-70592-5            22.
[14]  ThomasBrachtLaumannJespersen,PhilipMunksgaard&KenFriisLarsen(2015): SessionTypesforRust.
      In: Proceedingsofthe11thACMSIGPLANWorkshoponGenericProgramming,WGP2015,Associationfor
      Computing Machinery, New York, NY, USA, pp. 13–22, doi:10.1145/2808098.2808100.
[15]  Wen Kokke (2019): Rusty Variation: Deadlock-free Sessions with Failure in Rust. ElectronicProceedingsin
      TheoreticalComputerScience304, pp. 48–60, doi:10.4204/eptcs.304.4.
[16]  Wen Kokke & Ornela Dardha (2021): Deadlock-free session types in linear Haskell.  In: Haskell 2021:
      Proceedingsofthe14thACMSIGPLANInternationalSymposiumonHaskell,VirtualEvent,Korea,August
      26-27,2021, ACM, pp. 1–13, doi:10.1145/3471874.3472979.
[17]  Dimitrios Kouzapas, Ornela Dardha, Roly Perera & Simon J. Gay (2016): Typechecking protocols with
      Mungo and StMungo.  In: Proceedings of the 18th International Symposium on Principles and Practice
      of Declarative Programming, Edinburgh, United Kingdom, September 5-7, 2016, ACM, pp. 146–159,
      doi:10.1145/2967973.2968595.
[18]  Dimitrios Kouzapas, Ornela Dardha, Roly Perera & Simon J. Gay (2018): Typechecking protocols with
      Mungo and StMungo:   A session type toolchain for Java.    Sci. Comput. Program. 155,  pp. 52–75,
      doi:10.1016/J.SCICO.2017.10.006.
[19]  Nicolas Lagaillardie, Rumyana Neykova & Nobuko Yoshida (2022): Stay Safe Under Panic: Affine Rust
      Programming with Multiparty Session Types (Artifact).   Dagstuhl Artifacts Ser. 8(2), pp. 09:1–09:16,
      doi:10.4230/DARTS.8.2.9.
[20]  Sam Lindley & J. Garrett Morris (2015): A Semantics for Propositions as Sessions.  In Jan Vitek, editor:
      Programming Languages and Systems - 24th European Symposium on Programming, ESOP 2015, Held
      as Part of the European Joint Conferences on Theory and Practice of Software, ETAPS 2015, London,
      UK, April 11-18, 2015. Proceedings, Lecture Notes in Computer Science 9032, Springer, pp. 560–584,
      doi:10.1007/978-3-662-46669-8        23.
[21]  Sam Lindley& J. GarrettMorris (2016): Talkingbananas: structural recursionfor session types. InJacques
      Garrigue, Gabriele Keller & Eijiro Sumii, editors: Proceedings of the 21st ACM SIGPLAN International
      ConferenceonFunctionalProgramming,ICFP2016,Nara,Japan,September18-22,2016,ACM,pp.434–447,
      doi:10.1145/2951913.2951921.
[22]  Stephen McQuistin, Vivian Band, Dejice Jacob & Colin Perkins (2020): Parsing Protocol Standards to
      ParseStandard Protocols. In: ProceedingsoftheAppliedNetworkingResearchWorkshop, Association for
      Computing Machinery, New York, NY, USA, p. 25–31, doi:10.1145/3404868.3406671.

## PDF page 12

S.Cavoj,I.Nikitin,C.Perkins,O.Dardha                                                                                              33


[23]  Stephen McQuistin, Vivian Band, Dejice Jacob & Colin Perkins (2021):  Investigating Automatic Code
      Generation for Network Packet Parsing.  In:  Proceedings of the IFIP Networking Conference, pp. 1–9,
      doi:10.23919/IFIPNetworking52078.2021.9472829.
[24]  Matthias Neubauer & Peter Thiemann (2004): An Implementation of Session Types. In Bharat Jayaraman,
      editor:  Practical Aspects of Declarative Languages, 6th International Symposium, PADL 2004, Dallas,
      TX, USA, June 18-19, 2004, Proceedings, Lecture Notes in Computer Science 3057, Springer, pp. 56–70,
      doi:10.1007/978-3-540-24836-1       5.
[25]  Nicholas Ng & Nobuko Yoshida (2016):  Static deadlock detection for concurrent go by global session
      graph synthesis. In Ayal Zaks & Manuel V. Hermenegildo, editors: Proceedings of the 25th International
      ConferenceonCompilerConstruction,CC2016,Barcelona,Spain,March12-18,2016,ACM,pp.174–184,
      doi:10.1145/2892208.2892232.
[26]  NicholasNg,NobukoYoshida&KoheiHonda(2012): MultipartySessionC:SafeParallelProgrammingwith
      MessageOptimisation. In CarloA.Furia&SebastianNanz, editors: Objects,Models,Components,Patterns,
      Springer Berlin Heidelberg, Berlin, Heidelberg, pp. 202–218, doi:10.1007/978-3-642-30561-0               15.
[27]  Luca Padovani (2017): A simple library implementation of binary sessions.  J. Funct. Program. 27, p. e4,
      doi:10.1017/S0956796816000289.
[28]  Vern Paxson (1997):  Automated packet trace analysis of TCP implementations.  In: Proceedings of the
      ACM SIGCOMM’97 conference on Applications, technologies, architectures, and protocols for computer
      communication, pp. 167–179, doi:10.1145/263105.263160.
[29]  RiccardoPucella&JesseA.Tov(2008): Haskellsessiontypeswith(almost)noclass. InAndyGill,editor:
      Proceedings of the 1st ACM SIGPLAN Symposium on Haskell, Haskell 2008, Victoria, BC, Canada, 25
      September2008, ACM, pp. 25–36, doi:10.1145/1411286.1411290.
[30]  PeteResnick(2014): OnConsensusandHummingintheIETF. RFC7282,doi:10.17487/RFC7282. Available
      athttps://www.rfc-editor.org/info/rfc7282.
[31]  Alceste Scalas & Nobuko Yoshida (2019): Less is More: Multiparty Session Types Revisited. Proc. ACM
      Program.Lang. 3(POPL), doi:10.1145/3290343.
[32]Scapy community:       Scapy. https://scapy.net/.

A   Appendix

A.1   Three-way handshake
The session type and the TCP state machine are initiated in the CLOSED state:
letst  =  ServerSystemSessionType::new();
lettcp  =  TcpClosed::new();
The user role calls theOpen method and a TCB is created. This message is received usingofferone by
the system role:
let(_open,  st)  =  system_user_channel.offer_one(st);
ThesystemnowtransitionstotheLISTENstate,waitingforaconnectionestablishmenttoinitiate,and
sends aTcbCreated in response:
lettcp:  TcpListen  =  tcp.open(LocalAddr  {        /*...*/            });
letst  =  system_user_channel.select_one(st,  TcbCreated(()));
The next stepsare to wait fora SYN segment fromthe network and respondwith a SYN ACK segment.
Once a SYN segment is received we transition to the SYN-RCVD state:

## PDF page 13

                        34                                                                                                                           SessiontypesforTCP























                                                       Figure 2: TCP three-way Handshake with all roles.


                        let(addr,  syn,  st)  =  net_channel.offer_one_with_addr(st,  &tcp);

                        let(muttcp                /*Tcp<SynRcvd>*/                            ,  synack)  =  tcp.recv_syn(addr,  &syn);
                        letmutsyn_rcvd  =  net_channel.select_one(st,  addr,  synack);
                        The recursive SynRcvd session type handles unacceptable acknowledgements of SYN ACK segments
                        which need to be responded to with an ACK with the potential of a connection reset:
                        let(muttcp,  st)  =loop{
                              letst  =  syn_rcvd.inner();
                              lettcp_for_picker  =  tcp.for_picker();
                             Theoffer_two_filtered method can now be called on the network channel. This method takes
                        the session type, a picker function, and a channel filter:
                              matchnet_channel.offer_two_filtered(
                                    st,
                                    |packet|matchtcp_for_picker.acceptable(&packet)  {
                                           ReactionInner::Acceptable(_,  _)  =>  Branch::Left(
                                                 packet.into()),
                                           _  =>  Branch::Right(packet.into()),
                                    },
                                    &tcp,
                              )  {
                        Note that the picker determines which branch to take based on the TCP state machine.
                             The left branch of the session type corresponds to an acceptable ACK segment:
                                    Branch::Left((acceptable,  st))  =>  {
                                           lettcp:  Tcp<Established>  =  tcp








Client                Server System                Server User

                          CLOSED         Open


                                    TcbCreated
              SYN         LISTEN

          SYN ACK       SYN-RCVD


              ACK

                           ESTAB     Connected

## PDF page 14

S.Cavoj,I.Nikitin,C.Perkins,O.Dardha                                                                                              35


                       .recv_ack(&acceptable)
                       .empty_acceptable()
                       .expect("First␣ACK␣must␣be␣empty");
                  break(tcp,  st);
            }
However, if the ACK is not acceptable, the right branch is taken – an ACK is either sent back and the
system waits for another ACK:
            Branch::Right((unacceptable,  st))  =>  {
                  letremote_addr  =  tcp.remote_addr();
                  matchtcp.recv_ack(&unacceptable)  {
                       Reaction::NotAcceptable(tcp2,Some(response))  =>  {
                             letst  =  net_channel.select_left(
                                   st,  tcp2.remote_addr(),  response);
                             syn_rcvd  =  st;
                             tcp  =  tcp2;
                             continue;
                       }

Alternatively, an RST, notifying the user that the connection is being reset:
                       Reaction::Reset(Some(rst))  =>  {
                             letst  =  net_channel.select_right(
                                   st,  remote_addr,  rst);
                             letend=  system_user_channel.select_one(
                                   st,  Close(()));
                             net_channel.close(end);
                             system_user_channel.close(end);
                             return;
                       }

Finally,once outof theloop, the implementation isin theESTABstate andthe systemnotifies theuser
that the connection is established:
letmutrecursive  =  system_user_channel.select_one(st,  Connected(()));
info!("established");
This concludes the implementation of the three-way handshake.

A.2   Exchanging data

The main loop of the implementation waits to receive a segment (using an Offer session type) and
branches based on their type and whether it is acceptable or not.
Rec!(pubServerSystemCommLoop,  [
      (RoleClientSystem&  {

Acceptable with payload where an acceptable segment is received and there is data present:

## PDF page 15

36                                                                                                                           SessiontypesforTCP


       Ack.
             (RoleClientSystem+  Ack                                    /*empty*/              ).
             (RoleServerUser+  Data).
             (RoleServerUser&  {
                   Data.
                          (RoleClientSystem+  Ack                                    /*withdata*/                    ).
                          ServerSystemCommLoop,
                   Close.
                          (RoleClientSystem+  FinAck).
                          ServerSystemFinWait1
             }),


       Initially,dataacknowledgementisaccomplishedusinganemptyACKsegment. Thedatacontained
       withinthe ACKsegment isthen transmittedto theserver userwithin a message of typeData. The
       user hasthe option torespond by sendingback a message,leading to thetransmission of anACK
       with payload. Alternatively, the user may choose to initiate the closure of the connection, resulting
       in the transmission of a FIN ACK.
Acceptable without payload  In the case where these segments are acknowledgements of previously
       sent segments, we pass the ACK to the TCP state machine to update the state and update the
       retransmission queue:
       Ack.ServerSystemCommLoop,


FIN ACK Thepeerhasinitiatedclosingtheconnection. Inthiscase,theTCPstatemachinewilltransition
       from ESTABLISHED to the CLOSE-WAIT state.  Note that due to the absence of timeouts in
       thetypesystem,thetimeoutintheclose-waitstateisimplementedoutsidethesessiontypedstate
       machine.
       FinAck.
             (RoleClientSystem+  Ack                                    /*weACKtheFIN*/                          ).
             (RoleServerUser+  Close).
             ServerSystemCloseWait,


Unacceptable Asegmentwherethe sequence numbersarenotacceptablehasbeen received. Theserver
       will respond with an ACK which serves to inform the peer about the current receive window start
       and length [7].
       Ack.
             (RoleClientSystem+  Ack).
             ServerSystemCommLoop,


      })
]);
