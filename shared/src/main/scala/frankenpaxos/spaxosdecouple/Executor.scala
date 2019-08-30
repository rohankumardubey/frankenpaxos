package frankenpaxos.spaxosdecouple

import collection.mutable
import com.google.protobuf.ByteString
import frankenpaxos.Actor
import frankenpaxos.Chan
import frankenpaxos.Logger
import frankenpaxos.ProtoSerializer
import frankenpaxos.monitoring.Collectors
import frankenpaxos.monitoring.Counter
import frankenpaxos.monitoring.Gauge
import frankenpaxos.monitoring.PrometheusCollectors
import frankenpaxos.roundsystem.RoundSystem
import frankenpaxos.spaxosdecouple.Leader.{ECommand, ENoop, Entry}
import frankenpaxos.statemachine.StateMachine

import scala.scalajs.js.annotation._

@JSExportAll
object ExecutorInboundSerializer extends ProtoSerializer[ExecutorInbound] {
  type A = ExecutorInbound
  override def toBytes(x: A): Array[Byte] = super.toBytes(x)
  override def fromBytes(bytes: Array[Byte]): A = super.fromBytes(bytes)
  override def toPrettyString(x: A): String = super.toPrettyString(x)
}

@JSExportAll
object Executor {
  val serializer = ExecutorInboundSerializer
}

@JSExportAll
class ExecutorMetrics(collectors: Collectors) {
  val requestsTotal: Counter = collectors.counter
    .build()
    .name("fast_multipaxos_acceptor_requests_total")
    .labelNames("type")
    .help("Total number of processed requests.")
    .register()

  val batchesTotal: Counter = collectors.counter
    .build()
    .name("fast_multipaxos_acceptor_batches_total")
    .help("Total number of ProposeRequest batches processed.")
    .register()

  val proposeRequestsInBatchesTotal: Counter = collectors.counter
    .build()
    .name("fast_multipaxos_acceptor_propose_requests_in_batches_total")
    .help("Total number of ProposeRequests processed in a batch.")
    .register()
}

@JSExportAll
case class ExecutorOptions(
    // With Fast MultiPaxos, it's possible that two clients concurrently
    // propose two conflicting commands and that those commands arrive at
    // acceptors in different orders preventing either from being chosen. This
    // is called a conflict, and the performance of Fast MultiPaxos degrades
    // as the number of conflicts increases.
    //
    // As a heuristic to avoid conflicts, we have acceptors buffer messages and
    // process them in batches in a deterministic order. Every `waitPeriod`
    // seconds, an acceptor forms a batch of all propose requests that are
    // older than `waitStagger`, sorts them deterministically, and process
    // them.
    //
    // TODO(mwhittaker): I don't think waitStagger is actually useful. Verify
    // that it's pointless and remove it.
    // TODO(mwhittaker): Is there a smarter way to reduce the number of
    // conflicts?
    waitPeriod: java.time.Duration,
    waitStagger: java.time.Duration
)

@JSExportAll
object ExecutorOptions {
  val default = ExecutorOptions(
    waitPeriod = java.time.Duration.ofMillis(25),
    waitStagger = java.time.Duration.ofMillis(25)
  )
}

@JSExportAll
class Executor[Transport <: frankenpaxos.Transport[Transport]](
    address: Transport#Address,
    transport: Transport,
    logger: Logger,
    config: Config[Transport],
    val stateMachine: StateMachine,
    options: ExecutorOptions = ExecutorOptions.default,
    metrics: ExecutorMetrics = new ExecutorMetrics(PrometheusCollectors)
) extends Actor(address, transport, logger) {
  override type InboundMessage = ExecutorInbound
  override val serializer = Executor.serializer

  // Fields ////////////////////////////////////////////////////////////////////
  // Sanity check the Paxos configuration and compute the acceptor's id.
  logger.check(config.executorAddresses.contains(address))
  private val executorId = config.executorAddresses.indexOf(address)

  // Channels to the disseminators.
  private val disseminators: Map[Int, Chan[Disseminator[Transport]]] = {
    for ((disseminatorAddress, i) <- config.disseminatorAddresses.zipWithIndex)
      yield i -> chan[Disseminator[Transport]](disseminatorAddress, Disseminator.serializer)
  }.toMap

  type Slot = Int
  type ClientPseudonym = Int
  type ClientId = Int

  @JSExport
  protected var idToRequest: mutable.Map[UniqueId, ClientRequest] =
    mutable.Map[UniqueId, ClientRequest]()

  val log: mutable.SortedMap[Slot, Entry] = mutable.SortedMap()

  @JSExport
  protected var chosenWatermark: Slot = 0

  @JSExport
  protected var clientTable =
    mutable.Map[(Transport#Address, ClientPseudonym), (ClientId, Array[Byte])]()

// Handlers //////////////////////////////////////////////////////////////////
  override def receive(src: Transport#Address, inbound: InboundMessage) = {
    import ExecutorInbound.Request
    inbound.request match {
      case Request.IdToRequest(r) => handleIdToRequest(src, r)
      case Request.ValueChosen(r) => handleValueChosen(src, r)
      case Request.SendRequest(r) => handleSendRequest(src, r)
      case Request.ValueChosenBuffer(r) => handleValueChosenBuffer(src, r)
      case Request.Empty => {
        logger.fatal("Empty AcceptorInbound encountered.")
      }
    }
  }

  def handleIdToRequest(src: Transport#Address, request: IdToRequest): Unit = {
    idToRequest.put(request.clientRequest.uniqueId, request.clientRequest)
  }

  def handleValueChosen(src: Transport#Address,
                        valueChosen: ValueChosen): Unit = {
    if (idToRequest.get(valueChosen.getUniqueId).nonEmpty) {
      val request: ClientRequest = idToRequest.get(valueChosen.getUniqueId).get

      val clientAddress = transport.addressSerializer.fromBytes(
        valueChosen.getUniqueId.clientAddress.toByteArray()
      )
      val client = chan[Client[Transport]](clientAddress, Client.serializer)
      client.send(
        ClientInbound().withClientReply(
          ClientReply(
            clientPseudonym = request.uniqueId.clientPseudonym,
            clientId = request.uniqueId.clientId,
            result = request.command
          )))
    } else {
      for ((_, disseminator) <- disseminators) {
        disseminator.send(DisseminatorInbound().withGetRequest(GetRequest(valueChosen.getUniqueId)))
      }
    }
  }

  private def handleValueChosenBuffer(
                                       src: Transport#Address,
                                       valueChosenBuffer: ValueChosenBuffer
                                     ): Unit = {
    metrics.requestsTotal.labels("ValueChosenBuffer").inc()
    for (valueChosen <- valueChosenBuffer.valueChosen) {
      val entry = valueChosen.value match {
        case ValueChosen.Value.UniqueId(command) => ECommand(command)
        case ValueChosen.Value.Noop(_)          => ENoop
        case ValueChosen.Value.Empty => null
      }

      log.get(valueChosen.slot) match {
        case Some(existingEntry) => null
        case None =>
          log(valueChosen.slot) = entry
      }
    }
    executeLog()
  }

  def executeLog(): Unit = {
    while (log.contains(chosenWatermark)) {
      log(chosenWatermark) match {
        case ECommand(
        UniqueId(clientAddressBytes, clientPseudonym, clientId)
        ) =>
          val clientAddress = transport.addressSerializer.fromBytes(
            clientAddressBytes.toByteArray()
          )

          // True if this command has already been executed.
          val executed =
            clientTable.get((clientAddress, clientPseudonym)) match {
              case Some((highestClientId, _)) => clientId <= highestClientId
              case None                       => false
            }

          if (!executed) {
            val command = idToRequest.getOrElse(UniqueId(clientAddressBytes, clientPseudonym, clientId), null).command
            val output = stateMachine.run(command.toByteArray())
            clientTable((clientAddress, clientPseudonym)) = (clientId, output)

            // Note that only the leader replies to the client since
            // ProposeReplies include the round of the leader, and only the
            // leader knows this.
              val client =
                chan[Client[Transport]](clientAddress, Client.serializer)
              client.send(
                ClientInbound().withClientReply(
                  ClientReply(
                    clientPseudonym = clientPseudonym,
                    clientId = clientId,
                    result = ByteString.copyFrom(output))
                )
              )
            }
        case ENoop =>
          // Do nothing.

      }
      chosenWatermark += 1
    }
  }


  def handleSendRequest(src: Transport#Address, sendRequest: SendRequest): Unit = {
    val clientAddress = transport.addressSerializer.fromBytes(
      sendRequest.uniqueId.clientAddress.toByteArray()
    )
    val client = chan[Client[Transport]](clientAddress, Client.serializer)
    client.send(
      ClientInbound().withClientReply(
        ClientReply(
          clientPseudonym = sendRequest.uniqueId.clientPseudonym,
          clientId = sendRequest.uniqueId.clientId,
          result = sendRequest.command
        )))
  }
}

