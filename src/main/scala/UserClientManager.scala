import akka.io.{IO, Tcp}
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import java.net.InetSocketAddress

import Tcp._
import UserClientsManager.{ProcessEvents, RegisterClientConnection, UnRegisterClientConnection}
import akka.util.ByteString

/**
 * Wrapper for "Connection" actor to make code more readable and less error prone.
 * Note: it cannot extend AnyVal, since recommended way of defining Props (like props = Props(classOf[...], args...)
 * does not support them.
 * @param actor
 */
case class ClientConnectionHolder(actor: ActorRef)

/**
 * Wrapper for "UserClientsManager" actor
 * @param actor
 */
case class UserClientsManagerHolder(actor: ActorRef)

/**
 * UserClientsManager is responsible for forwarding event source events to appropriate user clients.
 */
class UserClientsManager extends Actor with ActorLogging {
  import context.system

  /**
   * Map user id to client connection
   */
  var connectedClients: Map[Long, ClientConnectionHolder] = Map.empty

  /**
   * Map user id to id of his followers
   */
  var clientFollowers: Map[Long, List[Long]] = Map.empty


  IO(Tcp) ! Bind(self, new InetSocketAddress( "0.0.0.0", 9099))

  def receive = {
    case b @ Bound(localAddress) =>
      log.info("User clients manager bound")

    case CommandFailed(_: Bind) =>
      log.info("Command failed.")
      context.stop(self)

    case c @ Connected(remote, local) =>
      val connectionHolder = ClientConnectionHolder(sender())
      val handler = context.actorOf(UserClientSocketHandler.props(UserClientsManagerHolder(self), connectionHolder))
      connectionHolder.actor ! Register(handler)

    // Handle event source events
    case ProcessEvents(events) => {
      events.foreach {
        case e: Follow =>
          // Only the To User Id should be notified
          connectedClients.get(e.toUserId).foreach { connectionHolder =>
            sendEvent(connectionHolder, e)
          }

          // Update followers of toUserId
          clientFollowers = clientFollowers
            .updated(e.toUserId, clientFollowers.getOrElse(e.toUserId, List.empty) :+ e.fromUserId)

        case e: UnFollow =>
          // No clients should be notified
          clientFollowers.get(e.toUserId).foreach { followers =>
            clientFollowers = clientFollowers.updated(e.toUserId, followers.filter(_ != e.fromUserId))
          }

        case e: Broadcast =>
          // All connected user clients should be notified
          connectedClients.foreach { case (_, connectionHolder) => sendEvent(connectionHolder, e) }

        case e: PrivateMessage =>
          // Only the To User Id should be notified
          connectedClients.get(e.toUserId).foreach(connectionHolder => sendEvent(connectionHolder, e))

        case e: StatusUpdate =>
          // All current followers of the From User ID should be notified
          clientFollowers.get(e.fromUserId).foreach { followerIds =>
            followerIds.foreach { followerId =>
              connectedClients.get(followerId).foreach(connectionHolder => sendEvent(connectionHolder, e))
            }
          }
      }
    }

    // Once user client connects to the server and sends its id, UserClientManager registers it
    case RegisterClientConnection(clientId, connectionHolder) =>
      connectedClients = connectedClients + (clientId -> connectionHolder)
      log.info(s"Registered user client $clientId")

    case UnRegisterClientConnection(clientId) =>
      connectedClients = connectedClients - clientId
      // Update followers list for each client removing disconnected client
      clientFollowers = clientFollowers.mapValues(followers => followers.filter(_ != clientId))
      log.info(s"Unregistered user client $clientId")
  }

  def sendEvent(connectionHolder: ClientConnectionHolder, event: Event): Unit = {
    connectionHolder.actor ! Write(ByteString(Event.toPayload(event)))
  }
}

object UserClientsManager {
  def props = Props(classOf[UserClientsManager])

  case class ProcessEvents(orderedEvents: List[Event])
  case class RegisterClientConnection(userId: Long, connectionHolder: ClientConnectionHolder)
  case class UnRegisterClientConnection(userId: Long)
}


class UserClientSocketHandler(
    userClientsManagerHolder: UserClientsManagerHolder,
    connectionHolder: ClientConnectionHolder)
  extends Actor with ActorLogging {

  var clientId: Option[Long] = None

  def receive = {
    case Received(data) =>
      clientId = Some(data.decodeString("utf-8").trim.toLong)
      userClientsManagerHolder.actor ! RegisterClientConnection(clientId.get, connectionHolder)

    case PeerClosed =>
      clientId match {
        case Some(id) => userClientsManagerHolder.actor ! UnRegisterClientConnection(id)
        case None => log.warning("PeerClosed event received, but no client id is bound to socket handler")
      }
      context.stop(self)
  }
}

object UserClientSocketHandler {
  def props(userClientsManager: UserClientsManagerHolder, connectionHolder: ClientConnectionHolder) =
    Props(classOf[UserClientSocketHandler], userClientsManager, connectionHolder)
}