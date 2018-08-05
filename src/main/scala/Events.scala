sealed trait Event {
  def sequenceNumber: Long
}

object Event {
  def fromPayload(payload: String): Event = {
    val elements = payload.split("\\|")
    val sequenceNumber = elements(0).toLong
    val eventType = elements(1)

    eventType match {
      case "F" => Follow(sequenceNumber, elements(2).toLong, elements(3).toLong)
      case "U" => UnFollow(sequenceNumber, elements(2).toLong, elements(3).toLong)
      case "B" => Broadcast(sequenceNumber)
      case "P" => PrivateMessage(sequenceNumber, elements(2).toLong, elements(3).toLong)
      case "S" => StatusUpdate(sequenceNumber, elements(2).toLong)
    }
  }

  def toPayload(event: Event): String = event match {
    case e: Follow => s"${e.sequenceNumber}|F|${e.fromUserId}|${e.toUserId}"
    case e: UnFollow => s"${e.sequenceNumber}|U|${e.fromUserId}|${e.toUserId}"
    case e: Broadcast => s"${e.sequenceNumber}|B"
    case e: PrivateMessage => s"${e.sequenceNumber}|P|${e.fromUserId}|${e.toUserId}"
    case e: StatusUpdate => s"${e.sequenceNumber}|S|${e.fromUserId}"
  }
}

final case class Follow(sequenceNumber: Long, fromUserId: Long, toUserId: Long) extends Event
final case class UnFollow(sequenceNumber: Long, fromUserId: Long, toUserId: Long) extends Event
final case class Broadcast(sequenceNumber: Long) extends Event
final case class PrivateMessage(sequenceNumber: Long, fromUserId: Long, toUserId: Long) extends Event
final case class StatusUpdate(sequenceNumber: Long, fromUserId: Long) extends Event

