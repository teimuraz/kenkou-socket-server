import UserClientsManager.RegisterClientConnection
import akka.actor.ActorSystem
import akka.io.Tcp.{Received, Write}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.ByteString
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

class ServerSpec() extends TestKit(ActorSystem("EventServerTest")) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  private val userClientManager = system.actorOf(UserClientsManager.props)
  implicit private val userClientsManagerHolder: UserClientsManagerHolder = UserClientsManagerHolder(userClientManager)

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "Clients" must {
    "receive events in correct order regardless of sending order" in {

      val connections: Map[Long, TestProbe] = Map(
        111L -> connectClientToServer(111)
      )
      val eventSourceHandler = system.actorOf(EventSourceHandler.props(userClientsManagerHolder))

      eventSourceHandler ! Received(rawEventsIn(List("3|B", "1|B", "2|B")))
      connections(111).expectMsg(rawEventOut("1|B"))
      connections(111).expectMsg(rawEventOut("2|B"))
      connections(111).expectMsg(rawEventOut("3|B"))
    }

    "receive appropriate events" in {

      val connections: Map[Long, TestProbe] = Map(
        111L -> connectClientToServer(111),
        222L -> connectClientToServer(222),
        333L -> connectClientToServer(333)
      )
      val eventSourceHandler = system.actorOf(EventSourceHandler.props(userClientsManagerHolder))

      eventSourceHandler ! Received(rawEventsIn(List(
        "7|S|222", "3|S|222", "1|F|111|222", "4|P|222|333", "6|U|111|222", "5|B", "2|F|333|222"
      )))

      connections(222).expectMsg(rawEventOut("1|F|111|222"))
      connections(222).expectMsg(rawEventOut("2|F|333|222"))
      connections(111).expectMsg(rawEventOut("3|S|222"))
      connections(333).expectMsg(rawEventOut("3|S|222"))
      connections(333).expectMsg(rawEventOut("4|P|222|333"))
      connections(111).expectMsg(rawEventOut("5|B"))
      connections(222).expectMsg(rawEventOut("5|B"))
      connections(333).expectMsg(rawEventOut("5|B"))
      connections(333).expectMsg(rawEventOut("7|S|222"))
    }
  }

  def rawEventIn(event: String) = ByteString(s"$event${Utils.crlf}")

  def rawEventsIn(events: List[String]) = ByteString(events.map(e => s"$e${Utils.crlf}").mkString)

  def rawEventOut(event: String) = Write(ByteString(s"$event${Utils.crlf}"))

  def rawEventsOut(events: List[String]) = Write(ByteString(events.map(e => s"$e${Utils.crlf}").mkString))

  def connectClientToServer(userId: Long)(implicit userClientsManagerHolder: UserClientsManagerHolder): TestProbe = {
    val connectionProb = TestProbe()
    val connectionHolder = ClientConnectionHolder(connectionProb.ref)
    val userClientSocketHandler = system.actorOf(UserClientSocketHandler.props(userClientsManagerHolder, connectionHolder))
    userClientSocketHandler ! Received(rawEventIn(userId.toString))
    connectionProb
  }


}
