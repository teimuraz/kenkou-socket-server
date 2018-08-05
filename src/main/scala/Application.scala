import akka.actor.{ActorSystem, Props}

object Application extends App {

  val system = ActorSystem("EventServer")
  val userClientsManager = system.actorOf(UserClientsManager.props)
  val eventSourceManager = system.actorOf(EventSourceManager.props(userClientsManager))

}
