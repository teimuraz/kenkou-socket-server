import akka.io.{IO, Tcp}
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import java.net.InetSocketAddress

import Tcp._
import UserClientsManager.{BatchEvents, RegisterClientConnection, UnRegisterClientConnection}
import akka.util.ByteString

case class UserClient(connection: ActorRef) {
  def sendEvent(event: Event): Unit = connection ! Write(ByteString(Event.toPayload(event)))
}

class UserClientsManager extends Actor with ActorLogging {
  import context.system

  private var connectedClients: Map[Long, ActorRef] = Map.empty
  private var clientFollowers: Map[Long, List[Long]] = Map.empty


  IO(Tcp) ! Bind(self, new InetSocketAddress( "0.0.0.0", 9099))

  def receive = {
    case b @ Bound(localAddress) =>
      log.info("Bound")

    case CommandFailed(_: Bind) =>
      log.info("Command failed.")
      context.stop(self)

    case c @ Connected(remote, local) =>
      val connection = sender()
      val handler = context.actorOf(UserClientSocketHandler.props(self, connection))
      connection ! Register(handler)

    case BatchEvents(events) => {
      events.foreach {
        case e: Follow =>
          connectedClients.get(e.toUserId).foreach { connection =>
            sendEvent(connection, e)
          }

          clientFollowers = clientFollowers.get(e.toUserId) match {
            case Some(f) => clientFollowers.updated(e.toUserId, f :+ e.fromUserId)
            case None => clientFollowers + (e.toUserId -> List(e.fromUserId))
          }

        case e: UnFollow =>
          clientFollowers.get(e.toUserId).foreach { followers =>
            clientFollowers = clientFollowers.updated(e.toUserId, followers.filter(_ != e.fromUserId))
          }

        case e: Broadcast =>
          connectedClients.foreach { case (_, connection) => sendEvent(connection, e) }

        case e: PrivateMessage =>
          connectedClients.get(e.toUserId).foreach(connetion => sendEvent(connetion, e))

        case e: StatusUpdate =>
          clientFollowers.get(e.fromUserId).foreach { followerIds =>
            followerIds.foreach { followerId =>
              connectedClients.get(followerId).foreach(connection => sendEvent(connection, e))
            }
          }
      }
    }

    case RegisterClientConnection(clientId, connection) =>
      connectedClients = connectedClients + (clientId -> connection)

    case UnRegisterClientConnection(clientId) =>
      connectedClients = connectedClients - clientId
      // Update followers list for each client removing disconnected client
      clientFollowers = clientFollowers.mapValues(followers => followers.filter(_ != clientId))
  }

  def sendEvent(connection: ActorRef, event: Event): Unit = connection ! Write(ByteString(Event.toPayload(event)))
}

object UserClientsManager {
  def props = Props(classOf[UserClientsManager])

  case class BatchEvents(events: List[Event])
  case class RegisterClientConnection(clientId: Long, connection: ActorRef)
  case class UnRegisterClientConnection(clientId: Long)
}


class UserClientSocketHandler(userClientsManager: ActorRef, connection: ActorRef) extends Actor with ActorLogging {

  private var clientId: Option[Long] = None

  def receive = {
    case Received(data) =>
      clientId = Some(data.decodeString("utf-8").trim.toLong)
      userClientsManager ! RegisterClientConnection(clientId.get, connection)

    case PeerClosed =>
      clientId match {
        case Some(id) => userClientsManager ! UnRegisterClientConnection(id)
        case None => log.warning("PeerClosed event received, but no client id is bound to socket handler")
      }
      context.stop(self)
  }
}

object UserClientSocketHandler {
  def props(userClientsManager: ActorRef, connection: ActorRef) = Props(classOf[UserClientSocketHandler], userClientsManager, connection)
}

