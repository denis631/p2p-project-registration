import Event.EnrollRegister
import RegistrationActor.RegistrationResponse
import akka.actor.{Actor, PoisonPill, Props}
import akka.pattern.{ask, pipe}

import scala.util.Random
import RegistrationStarter._

import scala.concurrent.Promise

object RegistrationActor {
  case class RegistrationResponse(msg: EnrollRegister, nonce: Long)

  def props: Props = Props(new RegistrationActor)
}

class RegistrationActor extends Actor {
  object ProbingActor {
    def props: Props = Props(new ProbingActor)
  }

  class ProbingActor extends Actor {
    override def receive: Receive = {
      case msg: EnrollRegister =>
        val caseToMatch = List("00", "00", "00", "00")

        Stream.iterate(0L) { _ => Random.nextLong() }
          .filter(msg.sha256(_).take(4) == caseToMatch)
          .foreach(sender ! RegistrationResponse(msg, _))
    }
  }

  override def receive: Receive = {
    case msg: EnrollRegister =>
      val p = Promise[RegistrationResponse]()
      val workers = (0 until 1024).map { _ => context.actorOf(ProbingActor.props) }

      workers
        .map{ worker => (worker ? msg).mapTo[RegistrationResponse] }
        .foreach { _ foreach p.trySuccess }

      val _ = p
        .future
        .pipeTo(sender)
        .foreach { _ =>
          workers.foreach(_ ! PoisonPill)
        }
  }
}
