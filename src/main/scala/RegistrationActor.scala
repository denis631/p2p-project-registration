import Event.EnrollRegister
import akka.actor.{Actor, Props}

import scala.util.Random

object RegistrationActor {
  def props: Props = Props(new RegistrationActor)
}

class RegistrationActor extends Actor {
  override def receive: Receive = {
    case msg: EnrollRegister =>
      Stream.iterate(0L) { _ => Random.nextLong() }
        .filter(msg.sha256(_).take(15) == List("00" * 15))
        .foreach(sender ! _)
  }
}
