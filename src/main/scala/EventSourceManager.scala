import akka.io.{IO, Tcp}
import akka.actor.{Actor, ActorLogging, Props}
import java.net.InetSocketAddress
import Tcp._
import UserClientsManager.ProcessEvents


class EventSourceManager(userClientsManagerHolder: UserClientsManagerHolder) extends Actor with ActorLogging {

  import Tcp._
  import context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress("0.0.0.0", 9090))

  def receive = {
    case b @ Bound(localAddress) =>
      log.info("Event source manager bound")

    case CommandFailed(_: Bind) =>
      log.info("Command failed.")
      context.stop(self)

    case c @ Connected(remote, local) =>
      log.info(s"Connection received from hostname: ${remote.getHostName} address: ${remote.getAddress.toString}")
      val connection = sender()
      val handler = context.actorOf(EventSourceHandler.props(userClientsManagerHolder))
      connection ! Register(handler)
  }
}

object EventSourceManager {
  def props(userClientsManagerHolder: UserClientsManagerHolder) =
    Props(classOf[EventSourceManager], userClientsManagerHolder)
}

class EventSourceHandler(userClientsManagerHolder: UserClientsManagerHolder) extends Actor with ActorLogging {

  log.info("Event source Actor started")

  def receive = {
    case Received(data) =>
      val batchStr = data.decodeString("utf-8")

      val orderedEvents: List[Event] = batchStr
        .split(Utils.crlf)
        .map(Event.fromPayload)
        .sortBy(_.sequenceNumber)
        .toList

      userClientsManagerHolder.actor ! ProcessEvents(orderedEvents)

    case PeerClosed =>
      log.info("Event source Peer closed")
      context.stop( self )
  }
}

object EventSourceHandler {
  def props(userClientsManagerHolder: UserClientsManagerHolder): Props =
    Props(classOf[EventSourceHandler], userClientsManagerHolder)
}

