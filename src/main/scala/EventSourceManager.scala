import akka.io.{IO, Tcp}
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import java.net.InetSocketAddress

import Tcp._
import UserClientsManager.BatchEvents

class EventSourceSocket(userClientsManager: ActorRef) extends Actor with ActorLogging {

  log.info("Event source Actor started")

  def receive = {
    case Received(data) =>
      val batchStr = data.decodeString("utf-8")

      val CRLF = sys.props("line.separator")
      val eventsOrdered: List[Event] = batchStr
        .split(CRLF)
        .map(Event.fromPayload)
        .sortBy(_.sequenceNumber)
        .toList

      userClientsManager ! BatchEvents(eventsOrdered)

    case PeerClosed =>
      log.info("Event source Peer closed")
      context.stop( self )
  }
}

object EventSourceSocket {
  def props(userClientsManager: ActorRef): Props = Props(classOf[EventSourceSocket], userClientsManager)
}

class EventSourceManager(userClientsManager: ActorRef) extends Actor with ActorLogging {

  import Tcp._
  import context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress("0.0.0.0", 9090))

  def receive = {
    case b @ Bound( localAddress ) =>
      log.info("Bound")

    case CommandFailed(_: Bind) =>
      log.info("Command failed.")
      context.stop( self )

    case c @ Connected(remote, local) =>
      log.info(s"Connection received from hostname: ${remote.getHostName} address: ${remote.getAddress.toString}")
      val handler = context.actorOf(EventSourceSocket.props(userClientsManager))
      val connection = sender()
      connection ! Register(handler)
  }
}

object EventSourceManager {
  def props(userClientsManager: ActorRef) = Props(classOf[EventSourceManager], userClientsManager)
}
