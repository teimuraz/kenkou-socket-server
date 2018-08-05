import akka.io.{IO, Tcp}
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import java.net.InetSocketAddress

import Tcp._
import UserClientsManager.{BatchEvents, RegisterClientConnection, UnRegisterClientConnection}
import akka.util.ByteString

case class UserClient(clientId: Long, followers: List[Long], connection: ActorRef) {

  def addFollower(followerId: Long): UserClient = copy(followers = followers :+ followerId)

  def removeFollower(followerId: Long): UserClient = copy(followers = followers.filter(_ != followerId))

  def sendEvent(event: Event): Unit = connection ! Write(ByteString(Event.toPayload(event)))

}

class UserClientsManager extends Actor with ActorLogging {
  import context.system

  private var clients: Map[Long, UserClient] = Map.empty
  private var clientFollowers: Map[Long, List[Long]] = Map.empty


  IO(Tcp) ! Bind(self, new InetSocketAddress( "0.0.0.0", 9099))

  private var lastSeqNumber: Long = 0

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
          clients.get(e.toUserId).foreach { client =>
            client.sendEvent(e)
          }

          clientFollowers = clientFollowers.get(e.toUserId) match {
            case Some(f) => clientFollowers.updated(e.toUserId, f :+ e.fromUserId)
            case None => clientFollowers + (e.toUserId -> List(e.fromUserId))
          }

        case e: UnFollow =>
//          clients.get(e.toUserId).foreach { client =>
//            clients = clients.updated(client.clientId, client.removeFollower(e.fromUserId))
//          }


          clientFollowers.get(e.toUserId).foreach { followers =>
            clientFollowers = clientFollowers.updated(e.toUserId, followers.filter(_ != e.fromUserId))
          }

        case e: Broadcast =>
          clients.foreach { case (_, client) => client.sendEvent(e) }

        case e: PrivateMessage =>
          clients.get(e.toUserId).foreach(_.sendEvent(e))

        case e: StatusUpdate =>
//          clients.get(e.fromUserId).foreach { client =>
//            client.followers.foreach { followerId =>
//              clients.get(followerId).foreach(_.sendEvent(e))
//            }
//          }
          clientFollowers.get(e.fromUserId).foreach { followerIds =>
            followerIds.foreach { followerId =>
              clients.get(followerId).foreach(_.sendEvent(e))
            }
          }
//          clients.get(e.fromUserId).foreach { client =>
//            client.followers.foreach { followerId =>
//              clients.get(followerId).foreach(_.sendEvent(e))
//            }
//          }
      }
    }

    case RegisterClientConnection(clientId, connection) =>
      clients = clients + (clientId -> UserClient(clientId, List.empty, connection))

    case UnRegisterClientConnection(clientId) =>
      clients = clients - clientId
      // Update followers list for each client removing disconnected client
      clients = clients.mapValues(client => client.removeFollower(clientId))
      clientFollowers = clientFollowers.mapValues(followers => followers.filter(_ != clientId))
//      clientFollowers.get(e.toUserId).foreach { followers =>
//        clientFollowers = clientFollowers.updated(e.toUserId, followers.filter(_ != e.fromUserId))
//      }
  }
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

