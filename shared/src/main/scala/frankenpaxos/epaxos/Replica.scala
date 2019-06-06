package frankenpaxos.epaxos

import com.google.protobuf.ByteString
import frankenpaxos.Actor
import frankenpaxos.Chan
import frankenpaxos.Logger
import frankenpaxos.ProtoSerializer
import frankenpaxos.Util
import scala.collection.mutable
import scala.scalajs.js.annotation._

// By default, Scala case classes cannot be compared using comparators like `<`
// or `>`. This is particularly annoying for Ballots because we often want to
// compare them. The BallotsOrdering implicit class below allows us to do just
// that.
@JSExportAll
object BallotHelpers {
  object Ordering extends scala.math.Ordering[Ballot] {
    private def ballotToTuple(ballot: Ballot): (Int, Int) = {
      val Ballot(ordering, replicaIndex) = ballot
      (ordering, replicaIndex)
    }

    override def compare(lhs: Ballot, rhs: Ballot): Int = {
      val ordering = scala.math.Ordering.Tuple2[Int, Int]
      ordering.compare(ballotToTuple(lhs), ballotToTuple(rhs))
    }
  }

  def inc(ballot: Ballot): Ballot = {
    val Ballot(ordering, replicaIndex) = ballot
    Ballot(ordering + 1, replicaIndex)
  }

  def max(lhs: Ballot, rhs: Ballot): Ballot = {
    Ordering.max(lhs, rhs)
  }

  implicit class Comparators(lhs: Ballot) {
    def <(rhs: Ballot) = Ordering.lt(lhs, rhs)
    def <=(rhs: Ballot) = Ordering.lteq(lhs, rhs)
    def >(rhs: Ballot) = Ordering.gt(lhs, rhs)
    def >=(rhs: Ballot) = Ordering.gteq(lhs, rhs)
  }
}

import BallotHelpers.Comparators

@JSExportAll
object ReplicaInboundSerializer extends ProtoSerializer[ReplicaInbound] {
  type A = ReplicaInbound
  override def toBytes(x: A): Array[Byte] = super.toBytes(x)
  override def fromBytes(bytes: Array[Byte]): A = super.fromBytes(bytes)
  override def toPrettyString(x: A): String = super.toPrettyString(x)
}

@JSExportAll
object Replica {
  val serializer = ReplicaInboundSerializer

  // The special null ballot. Replicas set their vote ballot to nullBallot to
  // indicate that they have not yet voted.
  val nullBallot = Ballot(-1, -1)
}

@JSExportAll
class Replica[Transport <: frankenpaxos.Transport[Transport]](
    address: Transport#Address,
    transport: Transport,
    logger: Logger,
    config: Config[Transport]
    // TODO(mwhittaker): Uncomment and integrate state machine.
    // Public for Javascript.
    // val stateMachine: StateMachine
) extends Actor(address, transport, logger) {
  // Types /////////////////////////////////////////////////////////////////////
  override type InboundMessage = ReplicaInbound
  override def serializer = Replica.serializer
  type ReplicaIndex = Int

  // Fields ////////////////////////////////////////////////////////////////////
  logger.check(config.valid())
  logger.check(config.replicaAddresses.contains(address))
  private val index: ReplicaIndex = config.replicaAddresses.indexOf(address)

  private val replicas: Seq[Chan[Replica[Transport]]] =
    for (replicaAddress <- config.replicaAddresses)
      yield chan[Replica[Transport]](replicaAddress, Replica.serializer)

  private val otherReplicas: Seq[Chan[Replica[Transport]]] =
    for (a <- config.replicaAddresses if a != address)
      yield chan[Replica[Transport]](a, Replica.serializer)

  // A command (or noop) along with its sequence number and dependencies. In
  // the EPaxos paper, these triples are denoted like this:
  //
  //                      (\gamma, seq_\gamma, deps_\gamma)
  @JSExportAll
  case class CommandTriple(
      commandOrNoop: CommandOrNoop,
      sequenceNumber: Int,
      dependencies: Set[Instance]
  )

  // The core data structure of every EPaxos replica, the cmd log, records
  // information about every instance that a replica knows about. In the EPaxos
  // paper, the cmd log is visualized as an infinite two-dimensional array that
  // looks like this:
  //
  //      ... ... ...
  //     |___|___|___|
  //   2 |   |   |   |
  //     |___|___|___|
  //   1 |   |   |   |
  //     |___|___|___|
  //   0 |   |   |   |
  //     |___|___|___|
  //       Q   R   S
  //
  // The array is indexed on the left by an instance number and on the bottom
  // by a replica id. Thus, every cell is indexed by an instance (e.g. Q.1),
  // and every cell contains the state of the instance that indexes it.
  // `CmdLogEntry` represents the data within a cell, and `cmdLog` represents
  // the cmd log.
  //
  // Note that EPaxos has a small bug in how it implements ballots. The EPaxos
  // TLA+ specification and Go implementation have a single ballot per command
  // log entry. As detailed in [1], this is a bug. We need two ballots, like
  // what is done in normal Paxos. Note that we haven't proven this two-ballot
  // implementation is correct, so it may also be wrong.
  //
  // [1]: https://drive.google.com/open?id=1dQ_cigMWJ7w9KAJeSYcH3cZoFpbraWxm
  sealed trait CmdLogEntry

  // A NoCommandEntry represents a command entry for which a replica has not
  // yet received any command (i.e., hasn't yet received a PreAccept, Accept,
  // or Commit). This is possible, for example, when a replica receives a
  // Prepare for an instance it has previously not received any messages about.
  @JSExportAll
  case class NoCommandEntry(
      // ballot plays the role of a Paxos acceptor's ballot. voteBallot is absent
      // because it is always the null ballot.
      ballot: Ballot
  ) extends CmdLogEntry

  @JSExportAll
  case class PreAcceptedEntry(
      ballot: Ballot,
      voteBallot: Ballot,
      triple: CommandTriple
  ) extends CmdLogEntry

  @JSExportAll
  case class AcceptedEntry(
      ballot: Ballot,
      voteBallot: Ballot,
      triple: CommandTriple
  ) extends CmdLogEntry

  @JSExportAll
  case class CommittedEntry(
      // Note that ballots are missing from the committed entry because once a
      // command is committed, there is no need for them anymore.
      triple: CommandTriple
  ) extends CmdLogEntry

  // TODO(mwhittaker): We might need to add an ExecutedEntry case class.

  @JSExport
  protected val cmdLog: mutable.Map[Instance, CmdLogEntry] =
    mutable.Map[Instance, CmdLogEntry]()

  // Every replica maintains a local instance number i, initially 0. When a
  // replica R receives a command, it assigns the command instance R.i and then
  // increments i. Thus, every replica fills in the cmd log vertically within
  // its column from bottom to top. `nextAvailableInstance` represents i.
  @JSExport
  protected var nextAvailableInstance: Int = 0

  // The default fast path ballot used by this replica.
  @JSExport
  protected val defaultBallot: Ballot = Ballot(0, index)

  // The largest ballot ever seen by this replica. largestBallot is used when a
  // replica receives a nack and needs to choose a larger ballot.
  @JSExport
  protected var largestBallot: Ballot = Ballot(0, index)

  // TODO(mwhittaker): Add a client table. Potentially pull out some code from
  // Fast MultiPaxos so that client table code is shared across protocols.

  // When a replica receives a command from a client, it becomes the leader of
  // the command, the designated replica that is responsible for driving the
  // protocol through its phases to get the command chosen. LeaderState
  // represents the state of a leader during various points in the lifecycle of
  // the protocol, whether the leader is pre-accepting, accepting, or preparing
  // (during recovery).
  sealed trait LeaderState

  case class PreAccepting(
      // Every EPaxos replica plays the role of a Paxos proposer _and_ a Paxos
      // acceptor. The ballot and voteBallot in a command log entry are used
      // when the replica acts like an acceptor. leaderBallots is used when a
      // replica acts like a leader. In particular, leaderBallots[instance] is
      // the ballot in which the replica is trying to get a value chosen. This
      // value is like the ballot stored by a Paxos proposer. Note that this
      // implementation of ballots differs from the one in EPaxos' TLA+ spec.
      ballot: Ballot,
      // The command being pre-accepted.
      commandOrNoop: CommandOrNoop,
      // PreAcceptOk responses, indexed by the replica that sent them.
      responses: mutable.Map[ReplicaIndex, PreAcceptOk],
      // If true, this command should avoid taking the fast path and resort
      // only to the slow path. In the normal case, avoid is false. During
      // recovery, avoid may sometimes be true.
      avoidFastPath: Boolean,
      // A timer to re-send PreAccepts.
      resendPreAcceptsTimer: Transport#Timer,
      // After a leader receives a classic quorum of responses, it waits a
      // certain amount of time for the fast path before reverting to the
      // classic path. This timer is used for that waiting.
      defaultToSlowPathTimer: Option[Transport#Timer]
  ) extends LeaderState

  case class Accepting(
      // See above.
      ballot: Ballot,
      // The command being accepted.
      triple: CommandTriple,
      // AcceptOk responses, indexed by the replica that sent them.
      responses: mutable.Map[ReplicaIndex, AcceptOk],
      // A timer to re-send Accepts.
      resendAcceptsTimer: Transport#Timer
  ) extends LeaderState

  // TODO(mwhittaker): Might want to add an Executing phase so that this leader
  // knows to send the result of the command back to the client.

  case class Preparing(
      // See above.
      ballot: Ballot,
      // Prepare responses, indexed by the replica that sent them.
      responses: mutable.Map[ReplicaIndex, PrepareOk],
      // A timer to re-send Prepares.
      resendPreparesTimer: Transport#Timer
  ) extends LeaderState

  @JSExport
  protected val leaderStates = mutable.Map[Instance, LeaderState]()

  // TODO(mwhittaker): Add dependency graph.

  // Helpers ///////////////////////////////////////////////////////////////////
  private def leaderBallot(leaderState: LeaderState): Ballot = {
    leaderState match {
      case state: PreAccepting => state.ballot
      case state: Accepting    => state.ballot
      case state: Preparing    => state.ballot
    }
  }

  // stopTimers(instance) stops any timers that may be running in instance
  // `instance`. This is useful when we transition from one state to another
  // and need to make sure that all old timers have been stopped.
  private def stopTimers(instance: Instance): Unit = {
    leaderStates.get(instance) match {
      case None =>
      // No timers to stop.
      case Some(preAccepting: PreAccepting) =>
        preAccepting.resendPreAcceptsTimer.stop()
        preAccepting.defaultToSlowPathTimer.foreach(_.stop())
      case Some(accepting: Accepting) =>
        accepting.resendAcceptsTimer.stop()
      case Some(preparing: Preparing) =>
        preparing.resendPreparesTimer.stop()
    }
  }

  // Transition to the pre-accept phase.
  private def transitionToPreAcceptPhase(
      instance: Instance,
      ballot: Ballot,
      commandOrNoop: CommandOrNoop,
      avoidFastPath: Boolean
  ): Unit = {
    // Update our command log. This part of the algorithm is a little subtle.
    //
    // This replica acts as both a leader of this instance (e.g., sending out
    // PreAccepts and receiving PreAcceptOks) and as an acceptor of this
    // instance (e.g., receiving PreAccepts and sending PreAcceptOks).
    //
    // leaderStates is state used by a replica when acting as a leader, and
    // cmdLog is state used by a replica when acting as an acceptor. We're
    // currently acting as a leader but are about to modify cmdLog. Typically
    // when we modify cmdLog, we first have to check a lot of things (e.g., our
    // ballot is big enough, we haven't already voted, etc.).
    //
    // Here, we don't do those checks. Instead, we know that if we're at this
    // point in the algorithm, then our current ballot is larger than any we've
    // ever seen. If we ever receive a message for this instance in a higher
    // ballot (and hence change the command log entry of this instance), we
    // stop being a leader.
    //
    // This subtlety and complexity is arguably a drawback of EPaxos.
    cmdLog.get(instance) match {
      case Some(_: CommittedEntry) =>
        logger.fatal(
          s"A replica is transitioning to the pre-accept phase for instance " +
            s"$instance, but this instance has already been committed."
        )

      case None =>
      // No checks to do.

      case Some(noCommand: NoCommandEntry) =>
        logger.check_le(noCommand.ballot, ballot)(BallotHelpers.Ordering)

      case Some(accepted: AcceptedEntry) =>
        logger.check_le(accepted.ballot, ballot)(BallotHelpers.Ordering)
        logger.check_le(accepted.voteBallot, ballot)(BallotHelpers.Ordering)

      case Some(preAccepted: PreAcceptedEntry) =>
        logger.check_le(preAccepted.ballot, ballot)(BallotHelpers.Ordering)
        logger.check_le(preAccepted.voteBallot, ballot)(BallotHelpers.Ordering)
    }

    cmdLog(instance) = AcceptedEntry(
      ballot = ballot,
      voteBallot = ballot,
      triple = CommandTriple(
        commandOrNoop,
        sequenceNumber = ???, // TODO(mwhittaker): Implement.
        dependencies = ??? // TODO(mwhittaker): Implement.
      )
    )

    // Send PreAccept messages to all other replicas.
    //
    // TODO(mwhittaker): Maybe add thriftiness. Thriftiness is less important
    // for basic EPaxos since the fast quorum sizes are so big.
    val preAccept = PreAccept(
      instance = instance,
      ballot = ballot,
      commandOrNoop = commandOrNoop,
      sequenceNumber = ???, // TODO(mwhittaker): Implement.
      dependencies = ??? // TODO(mwhittaker): Implement.
    )
    otherReplicas.foreach(_.send(ReplicaInbound().withPreAccept(preAccept)))

    // Stop existing timers.
    stopTimers(instance)

    // Update our leader state.
    leaderStates(instance) = PreAccepting(
      ballot = ballot,
      commandOrNoop = commandOrNoop,
      responses = mutable.Map[ReplicaIndex, PreAcceptOk](
        index -> PreAcceptOk(
          instance = instance,
          ballot = ballot,
          replicaIndex = index,
          sequenceNumber = ???, // TODO(mwhittaker): Implement.
          dependencies = ??? // TODO(mwhittaker): Implement.
        )
      ),
      avoidFastPath = avoidFastPath,
      resendPreAcceptsTimer = makeResendPreAcceptsTimer(preAccept),
      defaultToSlowPathTimer = None
    )
  }

  // Transition to the accept phase.
  private def transitionToAcceptPhase(
      instance: Instance,
      ballot: Ballot,
      triple: CommandTriple
  ): Unit = {
    // Update our command log. This part of the algorithm is a little subtle.
    // See transitionToPreAcceptPhase for details.
    cmdLog.get(instance) match {
      case Some(_: CommittedEntry) =>
        logger.fatal(
          s"A replica is transitioning to the accept phase for instance " +
            s"$instance, but this instance has already been committed."
        )

      case None =>
      // No checks to do.

      case Some(noCommand: NoCommandEntry) =>
        logger.check_le(noCommand.ballot, ballot)(BallotHelpers.Ordering)

      case Some(accepted: AcceptedEntry) =>
        logger.check_le(accepted.ballot, ballot)(BallotHelpers.Ordering)
        logger.check_le(accepted.voteBallot, ballot)(BallotHelpers.Ordering)

      case Some(preAccepted: PreAcceptedEntry) =>
        logger.check_le(preAccepted.ballot, ballot)(BallotHelpers.Ordering)
        logger.check_le(preAccepted.voteBallot, ballot)(BallotHelpers.Ordering)
    }
    cmdLog(instance) =
      AcceptedEntry(ballot = ballot, voteBallot = ballot, triple)

    // Send out an accept message to other replicas.
    // TODO(mwhittaker): Implement thriftiness.
    val accept = Accept(
      instance = instance,
      ballot = ballot,
      commandOrNoop = triple.commandOrNoop,
      sequenceNumber = triple.sequenceNumber,
      dependencies = triple.dependencies.toSeq
    )
    otherReplicas.foreach(_.send(ReplicaInbound().withAccept(accept)))

    // Stop existing timers.
    stopTimers(instance)

    // Update leader state.
    leaderStates(instance) = Accepting(
      ballot = ballot,
      triple = triple,
      responses = mutable.Map[ReplicaIndex, AcceptOk](
        index -> AcceptOk(
          instance = instance,
          ballot = ballot,
          replicaIndex = index
        )
      ),
      resendAcceptsTimer = makeResendAcceptsTimer(accept)
    )
  }

  // Take the slow path during the pre-accept phase.
  private def preAcceptingSlowPath(
      instance: Instance,
      preAccepting: PreAccepting
  ): Unit = {
    // Compute the new dependencies and sequence numbers.
    logger.check(preAccepting.responses.size >= config.slowQuorumSize)
    val preAcceptOks: Set[PreAcceptOk] = preAccepting.responses.values.toSet
    val sequenceNumber: Int = preAcceptOks.map(_.sequenceNumber).max
    val dependencies: Set[Instance] =
      preAcceptOks.map(_.dependencies.to[Set]).flatten
    transitionToAcceptPhase(
      instance,
      preAccepting.ballot,
      CommandTriple(preAccepting.commandOrNoop, sequenceNumber, dependencies)
    )
  }

  private def commit(
      instance: Instance,
      triple: CommandTriple,
      informOthers: Boolean
  ): Unit = {
    // Stop any currently running timers.
    stopTimers(instance)

    // Update the command log.
    cmdLog(instance) = CommittedEntry(triple)

    // Update the leader state.
    leaderStates -= instance

    // Notify the other replicas.
    if (informOthers) {
      for (replica <- otherReplicas) {
        replica.send(
          ReplicaInbound().withCommit(
            Commit(
              instance = instance,
              commandOrNoop = triple.commandOrNoop,
              sequenceNumber = triple.sequenceNumber,
              dependencies = triple.dependencies.toSeq
            )
          )
        )
      }
    }

    // TODO(mwhittaker): Make the command eligible for execution.
  }

  private def transitionToPreparePhase(instance: Instance): Unit = {
    // Stop any currently running timers.
    stopTimers(instance)

    // Choose a ballot larger than any we've seen before.
    largestBallot = BallotHelpers.inc(largestBallot)
    val ballot = largestBallot

    // Note that we don't touch the command log when we transition to the
    // prepare phase. We may have a command log entry in `instance`; we may
    // not.

    // Send Prepares to all replicas, including ourselves.
    val prepare = Prepare(instance = instance, ballot = ballot)
    replicas.foreach(_.send(ReplicaInbound().withPrepare(prepare)))

    // Update our leader state.
    leaderStates(instance) = Preparing(
      ballot = ballot,
      responses = mutable.Map(),
      resendPreparesTimer = makeResendPreparesTimer(prepare)
    )
  }

  // Timers ////////////////////////////////////////////////////////////////////
  private def makeResendPreAcceptsTimer(
      preAccept: PreAccept
  ): Transport#Timer = {
    // TODO(mwhittaker): Pull this duration out into an option.
    lazy val t: Transport#Timer = timer(
      s"resendPreAccepts ${preAccept.instance} ${preAccept.ballot}",
      java.time.Duration.ofMillis(500),
      () => {
        otherReplicas.foreach(_.send(ReplicaInbound().withPreAccept(preAccept)))
        t.start()
      }
    )
    t.start()
    t
  }

  private def makeDefaultToSlowPathTimer(
      instance: Instance
  ): Transport#Timer = {
    // TODO(mwhittaker): Pull this duration out into an option.
    val t = timer(
      s"defaultToSlowPath ${instance}",
      java.time.Duration.ofMillis(500),
      () => {
        leaderStates.get(instance) match {
          case None | Some(_: Accepting) | Some(_: Preparing) =>
            logger.fatal(
              "defaultToSlowPath timer triggered but replica is not " +
                "preAccepting."
            )
          case Some(preAccepting: PreAccepting) =>
            preAcceptingSlowPath(instance, preAccepting)
        }
      }
    )
    t.start()
    t
  }

  private def makeResendAcceptsTimer(accept: Accept): Transport#Timer = {
    // TODO(mwhittaker): Pull this duration out into an option.
    lazy val t: Transport#Timer = timer(
      s"resendAccepts ${accept.instance} ${accept.ballot}",
      java.time.Duration.ofMillis(500),
      () => {
        otherReplicas.foreach(_.send(ReplicaInbound().withAccept(accept)))
        t.start()
      }
    )
    t.start()
    t
  }

  private def makeResendPreparesTimer(prepare: Prepare): Transport#Timer = {
    // TODO(mwhittaker): Pull this duration out into an option.
    lazy val t: Transport#Timer = timer(
      s"resendPrepares ${prepare.instance} ${prepare.ballot}",
      java.time.Duration.ofMillis(500),
      () => {
        replicas.foreach(_.send(ReplicaInbound().withPrepare(prepare)))
        t.start()
      }
    )
    t.start()
    t
  }

  // Handlers //////////////////////////////////////////////////////////////////
  override def receive(
      src: Transport#Address,
      inbound: ReplicaInbound
  ): Unit = {
    import ReplicaInbound.Request
    inbound.request match {
      case Request.ClientRequest(r) => handleClientRequest(src, r)
      case Request.PreAccept(r)     => handlePreAccept(src, r)
      case Request.PreAcceptOk(r)   => handlePreAcceptOk(src, r)
      case Request.Accept(r)        => handleAccept(src, r)
      case Request.AcceptOk(r)      => handleAcceptOk(src, r)
      case Request.Commit(r)        => handleCommit(src, r)
      case Request.Nack(r)          => handleNack(src, r)
      case Request.Prepare(r)       => handlePrepare(src, r)
      case Request.PrepareOk(r)     => handlePrepareOk(src, r)
      case Request.Empty => {
        logger.fatal("Empty ReplicaInbound encountered.")
      }
    }
  }

  private def handleClientRequest(
      src: Transport#Address,
      request: ClientRequest
  ): Unit = {
    // TODO(mwhittaker): Check the client table. If the command already exists
    // in the client table, then we can return it immediately.

    val instance: Instance = Instance(index, nextAvailableInstance)
    nextAvailableInstance += 1
    transitionToPreAcceptPhase(instance,
                               defaultBallot,
                               CommandOrNoop().withCommand(request.command),
                               avoidFastPath = false)
  }

  private def handlePreAccept(
      src: Transport#Address,
      preAccept: PreAccept
  ): Unit = {
    // Make sure we should be processing this message at all. For example,
    // sometimes we nack it, sometimes we ignore it, sometimes we re-send
    // replies that we previously sent because of it.
    val replica = chan[Replica[Transport]](src, Replica.serializer)
    val nack =
      ReplicaInbound().withNack(Nack(preAccept.instance, largestBallot))
    cmdLog.get(preAccept.instance) match {
      case None =>
      // We haven't seen anything for this instance yet, so we're good to
      // process the message.

      case Some(NoCommandEntry(ballot)) =>
        // Don't process messages from old ballots. Note that we want to have
        // `<` here instead of `<=` because we may have previously received a
        // Prepare in this ballot. Another way to think of it is that
        // pre-accepting is like phase 2 of Paxos, whereas preparing is like
        // phase 1. In phase 2, we don't reject a ballot if it is equal to our
        // ballot, only if it is smaller.
        if (preAccept.ballot < ballot) {
          replica.send(nack)
          return
        }

      case Some(PreAcceptedEntry(ballot, voteBallot, triple)) =>
        // Don't process messages from old ballots.
        if (preAccept.ballot < ballot) {
          replica.send(nack)
          return
        }

        // Ignore a PreAccept if we've already responded, but re-send our
        // response for liveness.
        if (preAccept.ballot == voteBallot) {
          replica.send(
            ReplicaInbound().withPreAcceptOk(
              PreAcceptOk(instance = preAccept.instance,
                          ballot = preAccept.ballot,
                          replicaIndex = index,
                          sequenceNumber = triple.sequenceNumber,
                          dependencies = triple.dependencies.toSeq)
            )
          )
          return
        }

      case Some(AcceptedEntry(ballot, voteBallot, _triple)) =>
        // Don't process messages from old ballots.
        if (preAccept.ballot < ballot) {
          replica.send(nack)
          return
        }

        // If we've already accepted in this ballot, we shouldn't be
        // PreAccepting it again.
        if (preAccept.ballot == voteBallot) {
          return
        }

      case Some(CommittedEntry(triple)) =>
        // The command has already been committed. No need to run the protocol.
        replica.send(
          ReplicaInbound().withCommit(
            Commit(instance = preAccept.instance,
                   commandOrNoop = triple.commandOrNoop,
                   sequenceNumber = triple.sequenceNumber,
                   dependencies = triple.dependencies.toSeq)
          )
        )
        return
    }

    // If we're currently leading this instance and the ballot we just received
    // is larger than the ballot we're using, then we should stop leading the
    // instance and yield to the replica with the higher ballot.
    if (leaderStates.contains(preAccept.instance) &&
        preAccept.ballot > leaderBallot(leaderStates(preAccept.instance))) {
      stopTimers(preAccept.instance)
      leaderStates -= preAccept.instance
    }

    // Update largestBallot.
    largestBallot = BallotHelpers.max(largestBallot, preAccept.ballot)

    // TODO(mwhittaker): Compute dependencies and sequence number, taking into
    // consideration the ones in the PreAccept.

    cmdLog.put(
      preAccept.instance,
      PreAcceptedEntry(
        ballot = preAccept.ballot,
        voteBallot = preAccept.ballot,
        triple = CommandTriple(
          commandOrNoop = preAccept.commandOrNoop,
          sequenceNumber = ???, // TODO(mwhittaker): Implement.
          dependencies = ??? // TODO(mwhittaker): Implement.
        )
      )
    )

    val leader = chan[Replica[Transport]](src, Replica.serializer)
    leader.send(
      ReplicaInbound().withPreAcceptOk(
        PreAcceptOk(
          instance = preAccept.instance,
          ballot = preAccept.ballot,
          replicaIndex = index,
          sequenceNumber = ???, // TODO(mwhittaker): Implement.
          dependencies = ??? // TODO(mwhittaker): Implement.
        )
      )
    )
  }

  private def handlePreAcceptOk(
      src: Transport#Address,
      preAcceptOk: PreAcceptOk
  ): Unit = {
    leaderStates.get(preAcceptOk.instance) match {
      case None =>
        logger.warn(
          s"Replica received a PreAcceptOk in instance " +
            s"${preAcceptOk.instance} but is not leading the instance."
        )
        return

      case Some(_: Accepting) =>
        logger.warn(
          s"Replica received a PreAcceptOk in instance " +
            s"${preAcceptOk.instance} but is accepting."
        )
        return

      case Some(_: Preparing) =>
        logger.warn(
          s"Replica received a PreAcceptOk in instance " +
            s"${preAcceptOk.instance} but is preparing."
        )
        return

      case Some(
          preAccepting @ PreAccepting(ballot,
                                      commandOrNoop,
                                      responses,
                                      avoidFastPath,
                                      resendPreAcceptsTimer,
                                      defaultToSlowPathTimer)
          ) =>
        if (preAcceptOk.ballot != ballot) {
          logger.warn(
            s"Replica received a preAcceptOk in ballot " +
              s"${preAcceptOk.instance} but is currently leading ballot " +
              s"$ballot."
          )
          // If preAcceptOk.ballot were larger, then we would have received a
          // Nack instead of a PreAcceptOk.
          logger.check_lt(preAcceptOk.ballot, ballot)(BallotHelpers.Ordering)
          return
        }

        // Record the response. Note that we may have already received a
        // PreAcceptOk from this replica before.
        val oldNumberOfResponses = responses.size
        responses(preAcceptOk.replicaIndex) = preAcceptOk
        val newNumberOfResponses = responses.size

        // We haven't received enough responses yet. We still have to wait to
        // hear back from at least a quorum.
        if (newNumberOfResponses < config.slowQuorumSize) {
          return
        }

        // If we've achieved a classic quorum for the first time (and we're not
        // avoiding the fast path), we still want to wait for a fast quorum,
        // but we need to set a timer to default to taking the slow path.
        if (!avoidFastPath &&
            oldNumberOfResponses < config.slowQuorumSize &&
            newNumberOfResponses >= config.slowQuorumSize) {
          logger.check(defaultToSlowPathTimer.isEmpty)
          leaderStates(preAcceptOk.instance) = preAccepting.copy(
            defaultToSlowPathTimer =
              Some(makeDefaultToSlowPathTimer(preAcceptOk.instance))
          )
          return
        }

        // If we _are_ avoiding the fast path, then we can take the slow path
        // right away. There's no need to wait after we've received a quorum of
        // responses.
        if (avoidFastPath && newNumberOfResponses >= config.slowQuorumSize) {
          preAcceptingSlowPath(preAcceptOk.instance, preAccepting)
          return
        }

        // If we've received a fast quorum of responses, we can try to take the
        // fast path!
        if (newNumberOfResponses >= config.fastQuorumSize) {
          logger.check(!avoidFastPath)

          // We extract all (seq, deps) pairs in the PreAcceptOks, excluding our
          // own. If any appears n-2 times or more, we're good to take the fast
          // path.
          val seqDeps: Seq[(Int, Set[Instance])] = responses
            .filterKeys(_ != index)
            .values
            .to[Seq]
            .map(p => (p.sequenceNumber, p.dependencies.toSet))
          val candidates = Util.popularItems(seqDeps, config.fastQuorumSize - 1)

          // If we have N-2 matching responses, then we can take the fast path
          // and transition directly into the commit phase. If we don't have
          // N-2 matching responses, then we take the slow path.
          if (candidates.size > 0) {
            logger.check_eq(candidates.size, 1)
            val (sequenceNumber, dependencies) = candidates.head
            commit(
              preAcceptOk.instance,
              CommandTriple(commandOrNoop, sequenceNumber, dependencies),
              informOthers = true
            )
          } else {
            // There were not enough matching (seq, deps) pairs. We have to
            // resort to the slow path.
            preAcceptingSlowPath(preAcceptOk.instance, preAccepting)
          }
          return
        }
    }
  }

  private def handleAccept(src: Transport#Address, accept: Accept): Unit = {
    // Make sure we should be processing this message at all.
    val replica = chan[Replica[Transport]](src, Replica.serializer)
    val nack = ReplicaInbound().withNack(Nack(accept.instance, largestBallot))
    cmdLog.get(accept.instance) match {
      case None =>
      // We haven't seen anything for this instance yet, so we're good to
      // process the message.

      case Some(NoCommandEntry(ballot)) =>
        // Don't process messages from old ballots.
        if (accept.ballot < ballot) {
          replica.send(nack)
          return
        }

      case Some(PreAcceptedEntry(ballot, voteBallot, triple)) =>
        // Don't process messages from old ballots.
        if (accept.ballot < ballot) {
          replica.send(nack)
          return
        }

      case Some(AcceptedEntry(ballot, voteBallot, _triple)) =>
        // Don't process messages from old ballots.
        if (accept.ballot < ballot) {
          replica.send(nack)
          return
        }

        // Ignore an Accept if we've already responded, but re-send our
        // response for liveness.
        if (accept.ballot == voteBallot) {
          replica.send(
            ReplicaInbound().withAcceptOk(
              AcceptOk(instance = accept.instance,
                       ballot = accept.ballot,
                       replicaIndex = index)
            )
          )
          return
        }

      case Some(CommittedEntry(triple)) =>
        // The command has already been committed. No need to run the protocol.
        replica.send(
          ReplicaInbound().withCommit(
            Commit(instance = accept.instance,
                   commandOrNoop = triple.commandOrNoop,
                   sequenceNumber = triple.sequenceNumber,
                   dependencies = triple.dependencies.toSeq)
          )
        )
        return
    }

    // If we're currently leading this instance and the ballot we just received
    // is larger than the ballot we're using, then we should stop leading the
    // instance and yield to the replica with the higher ballot.
    if (leaderStates.contains(accept.instance) &&
        accept.ballot > leaderBallot(leaderStates(accept.instance))) {
      stopTimers(accept.instance)
      leaderStates -= accept.instance
    }

    // Update largestBallot.
    largestBallot = BallotHelpers.max(largestBallot, accept.ballot)

    // Update our command log.
    cmdLog(accept.instance) = AcceptedEntry(
      ballot = accept.ballot,
      voteBallot = accept.ballot,
      triple = CommandTriple(commandOrNoop = accept.commandOrNoop,
                             sequenceNumber = accept.sequenceNumber,
                             dependencies = accept.dependencies.toSet)
    )

    replica.send(
      ReplicaInbound().withAcceptOk(
        AcceptOk(instance = accept.instance,
                 ballot = accept.ballot,
                 replicaIndex = index)
      )
    )
  }

  private def handleAcceptOk(
      src: Transport#Address,
      acceptOk: AcceptOk
  ): Unit = {
    leaderStates.get(acceptOk.instance) match {
      case None =>
        logger.warn(
          s"Replica received an AcceptOk in instance ${acceptOk.instance} " +
            s"but is not leading the instance."
        )

      case Some(_: PreAccepting) =>
        logger.warn(
          s"Replica received an AcceptOk in instance ${acceptOk.instance} " +
            s"but is pre-accepting."
        )

      case Some(_: Preparing) =>
        logger.warn(
          s"Replica received an AcceptOk in instance ${acceptOk.instance} " +
            s"but is preparing."
        )

      case Some(
          accepting @ Accepting(ballot, triple, responses, resendAcceptsTimer)
          ) =>
        if (acceptOk.ballot != ballot) {
          logger.warn(
            s"Replica received an AcceptOk in ballot ${acceptOk.instance} " +
              s"but is currently leading ballot $ballot."
          )
          // If acceptOk.ballot were larger, then we would have received a
          // Nack instead of an AcceptOk.
          logger.check_lt(acceptOk.ballot, ballot)(BallotHelpers.Ordering)
          return
        }

        responses(acceptOk.replicaIndex) = acceptOk

        // We don't have enough responses yet.
        if (responses.size < config.slowQuorumSize) {
          return
        }

        // We have a quorum of replies. Commit the triple.
        commit(acceptOk.instance, triple, informOthers = true)
    }
  }

  private def handleCommit(src: Transport#Address, c: Commit): Unit = {
    commit(
      c.instance,
      CommandTriple(c.commandOrNoop, c.sequenceNumber, c.dependencies.toSet),
      informOthers = false
    )
  }

  private def handleNack(src: Transport#Address, nack: Nack): Unit = {
    // TODO(mwhittaker): If we get a Nack, it's possible there's another
    // replica trying to recover this instance. To avoid dueling replicas, we
    // may want to do a random exponential backoff.
    largestBallot = BallotHelpers.max(largestBallot, nack.largestBallot)
    transitionToPreparePhase(nack.instance)
  }

  private def handlePrepare(
      src: Transport#Address,
      prepare: Prepare
  ): Unit = {
    // Update largestBallot.
    largestBallot = BallotHelpers.max(largestBallot, prepare.ballot)

    // If we're currently leading this instance and the ballot we just received
    // is larger than the ballot we're using, then we should stop leading the
    // instance and yield to the replica with the higher ballot.
    if (leaderStates.contains(prepare.instance) &&
        prepare.ballot > leaderBallot(leaderStates(prepare.instance))) {
      stopTimers(prepare.instance)
      leaderStates -= prepare.instance
    }

    val replica = chan[Replica[Transport]](src, Replica.serializer)
    val nack = ReplicaInbound().withNack(Nack(prepare.instance, largestBallot))
    cmdLog.get(prepare.instance) match {
      case None =>
        replica.send(
          ReplicaInbound().withPrepareOk(
            PrepareOk(
              ballot = prepare.ballot,
              instance = prepare.instance,
              replicaIndex = index,
              voteBallot = Replica.nullBallot,
              status = CommandStatus.NotSeen,
              commandOrNoop = None,
              sequenceNumber = None,
              dependencies = Seq()
            )
          )
        )
        cmdLog(prepare.instance) = NoCommandEntry(prepare.ballot)

      case Some(NoCommandEntry(ballot)) =>
        // Don't process messages from old ballots. Note that we use `<`
        // instead of `<=` so that a replica can re-send a reply back to the
        // leader. If we used `<=`, then leaders would have to time out and
        // increment their ballots.
        if (prepare.ballot < ballot) {
          replica.send(nack)
          return
        }

        replica.send(
          ReplicaInbound().withPrepareOk(
            PrepareOk(
              ballot = prepare.ballot,
              instance = prepare.instance,
              replicaIndex = index,
              voteBallot = Replica.nullBallot,
              status = CommandStatus.NotSeen,
              commandOrNoop = None,
              sequenceNumber = None,
              dependencies = Seq()
            )
          )
        )
        cmdLog(prepare.instance) = NoCommandEntry(prepare.ballot)

      case Some(entry @ PreAcceptedEntry(ballot, voteBallot, triple)) =>
        // Don't process messages from old ballots.
        if (prepare.ballot < ballot) {
          replica.send(nack)
          return
        }

        replica.send(
          ReplicaInbound().withPrepareOk(
            PrepareOk(
              ballot = prepare.ballot,
              instance = prepare.instance,
              replicaIndex = index,
              voteBallot = voteBallot,
              status = CommandStatus.PreAccepted,
              commandOrNoop = Some(triple.commandOrNoop),
              sequenceNumber = Some(triple.sequenceNumber),
              dependencies = triple.dependencies.toSeq
            )
          )
        )
        cmdLog(prepare.instance) = entry.copy(ballot = prepare.ballot)

      case Some(entry @ AcceptedEntry(ballot, voteBallot, triple)) =>
        // Don't process messages from old ballots.
        if (prepare.ballot < ballot) {
          replica.send(nack)
          return
        }

        replica.send(
          ReplicaInbound().withPrepareOk(
            PrepareOk(
              ballot = prepare.ballot,
              instance = prepare.instance,
              replicaIndex = index,
              voteBallot = voteBallot,
              status = CommandStatus.Accepted,
              commandOrNoop = Some(triple.commandOrNoop),
              sequenceNumber = Some(triple.sequenceNumber),
              dependencies = triple.dependencies.toSeq
            )
          )
        )
        cmdLog(prepare.instance) = entry.copy(ballot = prepare.ballot)

      case Some(CommittedEntry(triple)) =>
        // The command has already been committed. No need to run the protocol.
        replica.send(
          ReplicaInbound().withCommit(
            Commit(instance = prepare.instance,
                   commandOrNoop = triple.commandOrNoop,
                   sequenceNumber = triple.sequenceNumber,
                   dependencies = triple.dependencies.toSeq)
          )
        )
    }
  }

  private def handlePrepareOk(
      src: Transport#Address,
      prepareOk: PrepareOk
  ): Unit = {
    leaderStates.get(prepareOk.instance) match {
      case None =>
        logger.warn(
          s"Replica received a PrepareOk in instance ${prepareOk.instance} " +
            s"but is not leading the instance."
        )

      case Some(_: PreAccepting) =>
        logger.warn(
          s"Replica received a PrepareOk in instance ${prepareOk.instance} " +
            s"but is pre-accepting."
        )

      case Some(_: Accepting) =>
        logger.warn(
          s"Replica received a PrepareOk in instance ${prepareOk.instance} " +
            s"but is accepting."
        )

      case Some(preparing @ Preparing(ballot, responses, resendAcceptsTimer)) =>
        if (prepareOk.ballot != ballot) {
          logger.warn(
            s"Replica received a preAcceptOk in ballot ${prepareOk.instance} " +
              s"but is currently leading ballot $ballot."
          )
          // If prepareOk.ballot were larger, then we would have received a
          // Nack instead of a PrepareOk.
          logger.check_lt(prepareOk.ballot, ballot)(BallotHelpers.Ordering)
          return
        }

        responses(prepareOk.replicaIndex) = prepareOk

        // If we don't have a quorum of responses yet, we have to wait to get one.
        if (responses.size < config.slowQuorumSize) {
          return
        }

        // Look only at the ballots from the highest ballot, just like in
        // Paxos.
        val maxBallot =
          responses.values.map(_.voteBallot).max(BallotHelpers.Ordering)
        val prepareOks = responses.values.filter(_.voteBallot == maxBallot)

        // If some response was accepted, then we go with it. We are guaranteed
        // that there is only one such response. This is like getting a
        // response in a classic round during Fast Paxos.
        prepareOks.find(_.status == Some(CommandStatus.Accepted)) match {
          case Some(accepted) =>
            transitionToAcceptPhase(prepareOk.instance,
                                    ballot,
                                    CommandTriple(accepted.commandOrNoop.get,
                                                  accepted.sequenceNumber.get,
                                                  accepted.dependencies.toSet))
            return

          case None =>
        }

        // If we have f matching responses in the fast round, excluding the
        // leader, then we go with it. Clearly, there can only be one response
        // with f matches out of f+1 responses. This is like checking if any
        // response has a majority of the quorum in Fast Paxos.
        val preAcceptsInDefaultBallotNotFromLeader: Seq[CommandTriple] =
          prepareOks
            .filter(_.status == CommandStatus.PreAccepted)
            .filter(p => p.ballot == Ballot(0, p.instance.replicaIndex))
            .filter(_.replicaIndex != index)
            .to[Seq]
            .map(
              p =>
                CommandTriple(p.commandOrNoop.get,
                              p.sequenceNumber.get,
                              p.dependencies.toSet)
            )
        val candidates =
          Util.popularItems(preAcceptsInDefaultBallotNotFromLeader, config.f)
        if (candidates.size > 0) {
          logger.check_eq(candidates.size, 1)
          transitionToAcceptPhase(prepareOk.instance, ballot, candidates.head)
          return
        }

        // If no command had f matching responses, then we nothing was chosen
        // on the fast path. Now, we just start the protocol over with a
        // command (if there is one) or with a noop if there isn't.
        prepareOks.find(_.status == CommandStatus.PreAccepted) match {
          case Some(preAccepted) =>
            transitionToPreAcceptPhase(prepareOk.instance,
                                       ballot,
                                       preAccepted.commandOrNoop.get,
                                       avoidFastPath = true)
          case None =>
            transitionToPreAcceptPhase(prepareOk.instance,
                                       ballot,
                                       CommandOrNoop().withNoop(Noop()),
                                       avoidFastPath = true)
        }
    }
  }
}
