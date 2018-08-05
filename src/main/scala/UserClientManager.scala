import akka.io.{IO, Tcp}
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import java.net.InetSocketAddress

import Tcp._
import UserClientsManager.{BatchEvents, RegisterClientConnection, UnRegisterClientConnection}
import akka.util.ByteString

case class UserClient(clientId: Long, followers: List[Long], connection: ActorRef) {

  def addFollower(followerId: Long): UserClient = copy(followers = followers :+ followerId)

  def removeFollower(followerId: Long): UserClient = copy(followers = followers.filter(_ != followerId))

  def sendEvent(event: Event) =  {
    connection ! Write(ByteString(Event.toPayload(event)))
  }

}

class UserClientsManager extends Actor with ActorLogging {
  import context.system

  private var clients: Map[Long, UserClient] = Map.empty


  IO(Tcp) ! Bind(self, new InetSocketAddress( "0.0.0.0", 9099))


  def receive = {
    case b @ Bound(localAddress) =>
      log.info("Bound")

    case CommandFailed(_: Bind) =>
      log.info("Command failed.")
      context.stop(self)

    case c @ Connected(remote, local) =>
      log.info(s"Connection received from hostname: ${remote.getHostName} address: ${remote.getAddress.toString}")
      val connection = sender()
      sender() ! Write(ByteString("OK!"))
      val client = context.actorOf(UserClientSocketHandler.props(self, connection))
      connection ! Register(client)

    case BatchEvents(events) => {
      events.foreach {
        case e: Follow =>
          clients.get(e.toUserId).foreach { client =>
            clients = clients.updated(client.clientId, client.addFollower(e.fromUserId))
            client.sendEvent(e)
          }
          clients.foreach { case (_, client) => client.sendEvent(e)}
        case e: UnFollow =>
          clients.get(e.toUserId).foreach { client =>
            clients = clients.updated(client.clientId, client.removeFollower(e.fromUserId))
          }
          clients.foreach { case (_, client) => client.sendEvent(e)}
        case e: Broadcast =>
          clients.foreach { case (_, client) => client.sendEvent(e) }
          clients.foreach { case (_, client) => client.sendEvent(e)}

        case e: PrivateMessage =>
          clients.get(e.toUserId).foreach(_.sendEvent(e))
          clients.foreach { case (_, client) => client.sendEvent(e)}

        case e: StatusUpdate =>
          clients.get(e.fromUserId).foreach { client =>
            client.followers.foreach { followerId =>
              clients.get(followerId).foreach(_.sendEvent(e))
            }
          }
          clients.foreach { case (_, client) => client.sendEvent(e)}
      }
    }

    case RegisterClientConnection(clientId, connection) =>
      clients = clients + (clientId -> UserClient(clientId, List.empty, connection))

    case UnRegisterClientConnection(clientId) =>
      clients = clients - clientId
      // Update followers list for each client removing disconnected client
      clients = clients.mapValues(client => client.removeFollower(clientId))
  }
}

object UserClientsManager {
  def props = Props(classOf[UserClientsManager])

  case class BatchEvents(events: List[Event])
  case class RegisterClientConnection(clientId: Long, connection: ActorRef)
  case class UnRegisterClientConnection(clientId: Long)
}


class UserClientSocketHandler(userClientsManager: ActorRef, connection: ActorRef) extends Actor with ActorLogging {

  private var clientId: Long = -1

  val CRLF = sys.props("line.separator")
  log.info("UserClient Actor started")

  def receive = {
    case Received(data) =>
      clientId = data.decodeString("utf-8").trim.toLong
      userClientsManager ! RegisterClientConnection(clientId, connection)
      log.info(s"Received $data")
      sender() ! data

    case PeerClosed =>
      userClientsManager ! UnRegisterClientConnection(clientId)
      log.info("Peer closed")
      context.stop(self)

    case _ =>
      log.info(s"Any")

  }
}

object UserClientSocketHandler {
  def props(userClientsManager: ActorRef, connection: ActorRef) = Props(classOf[UserClientSocketHandler], userClientsManager, connection)
}

