import akka.io.{IO, Tcp}
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import java.net.InetSocketAddress

import Tcp._
import UserClientSocketHandler.ClientEvent
import UserClientsManager.{BatchEvents, RegisterClientSocket, UnRegisterClientSocket}

case class UserClient(clientId: Long, followers: List[Long], socket: ActorRef, connection: ActorRef) {

  def addFollower(followerId: Long): UserClient = copy(followers = followers :+ followerId)

  def removeFollower(followerId: Long): UserClient = copy(followers = followers.filter(_ != followerId))

  def sendStatusUpdateToFollowers(event: StatusUpdate, clients: Map[Long, UserClient]): Unit = {
    followers.foreach { followerId =>
      clients.get(followerId).foreach(_.connection ! ClientEvent.fromEvent(event))
    }
  }

  def sendPrivateMessage(event: PrivateMessage): Unit = connection ! ClientEvent.fromEvent(event)

  def sendBroadCast(event: Broadcast): Unit = connection ! ClientEvent.fromEvent(event)
}

class UserClientsManager extends Actor with ActorLogging {
  import context.system

  private var clients: Map[Long, UserClient] = Map.empty


  IO(Tcp) ! Bind( self, new InetSocketAddress( "0.0.0.0", 9099 ) )

  def receive = {
    case b @ Bound(localAddress) =>
      log.info("Bound")

    case CommandFailed(_: Bind) =>
      log.info("Command failed.")
      context.stop(self)

    case c @ Connected(remote, local) =>
      log.info(s"Connection received from hostname: ${remote.getHostName} address: ${remote.getAddress.toString}")
      val connection = sender()
      val client = context.actorOf(UserClientSocketHandler.props(self, connection))
      connection ! Register(client)

    case BatchEvents(events) => {
      events.foreach {
        case e: Follow =>
          clients.get(e.toUserId).foreach { client =>
            clients.updated(client.clientId, client.addFollower(e.fromUserId))
          }
        case e: UnFollow =>
          clients.get(e.toUserId).foreach { client =>
            clients.updated(client.clientId, client.removeFollower(e.fromUserId))
          }
        case e: Broadcast =>
          clients.foreach { case (_, client) => client.sendBroadCast(e) }
        case e: PrivateMessage =>
          clients.get(e.toUserId).foreach(_.sendPrivateMessage(e))
        case e: StatusUpdate =>
          clients.get(e.fromUserId).foreach(_.sendStatusUpdateToFollowers(e, clients))
      }
    }

    case RegisterClientSocket(clientId, socket, connection) =>
      clients = clients + (clientId -> UserClient(clientId, List.empty, socket, connection))

    case UnRegisterClientSocket(clientId) =>
      clients = clients - clientId
      // Update followers list for each client removing disconnected client
      clients = clients.mapValues(client => client.removeFollower(clientId))
  }
}

object UserClientsManager {
  def props = Props(classOf[UserClientsManager])

  case class BatchEvents(events: List[Event])
  case class RegisterClientSocket(clientId: Long, socket: ActorRef, connection: ActorRef)
  case class UnRegisterClientSocket(clientId: Long)
}


class UserClientSocketHandler(userClientsManager: ActorRef, connection: ActorRef) extends Actor with ActorLogging {

  private var clientId: Long = -1

  val CRLF = sys.props("line.separator")
  log.info("UserClient Actor started")

  def receive = {
    case Received(data) =>
      clientId = data.decodeString("utf-8").trim.toLong
      userClientsManager ! RegisterClientSocket(clientId, self, connection)

    case PeerClosed =>
      userClientsManager ! UnRegisterClientSocket(clientId)
      log.info("Peer closed")
      context.stop(self)

    case ClientEvent(payload) =>
      println(s"Im $clientId, received $payload")
  }
}

object UserClientSocketHandler {
  def props(userClientsManager: ActorRef, connection: ActorRef) = Props(classOf[UserClientSocketHandler], userClientsManager)
  case class ClientEvent(payload: String)

  object ClientEvent {
    def fromEvent(event: Event): ClientEvent = ClientEvent(Event.toPayload(event))
  }
}

