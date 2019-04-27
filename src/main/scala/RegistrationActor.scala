import java.net.InetSocketAddress

import Event.{EnrollInit, EnrollRegister}
import RegistrationActor.{Probe, RegistrationResponse, Try}
import RegistrationStarter._
import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import akka.io.Tcp.SO.KeepAlive
import akka.pattern.{ask, pipe}
import akka.stream.scaladsl.{Flow, Tcp}
import akka.util.{ByteString, Timeout}

import scala.concurrent.{ExecutionContext, Future, Promise, TimeoutException}
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success}

object RegistrationActor {
  object Try
  case class Probe(msg: EnrollRegister, sender: ActorRef)
  case class RegistrationResponse(msg: EnrollRegister, nonce: Long)

  def props(host: String, port: Int): Props = Props(new RegistrationActor(host, port))
}

class RegistrationActor(host: String, port: Int) extends Actor {
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = Timeout(100 seconds)

  object ProbingActor {
    def props: Props = Props(new ProbingActor)
  }

  class ProbingActor extends Actor {
    val caseToMatch = (0 until 4).map { i => List("00", "00", "00", s"0$i")}

    override def receive: Receive = {
      case msg: EnrollRegister => self ! Probe(msg, sender)
      case probe @ Probe(msg, originalSender) =>
        val possibleNonce = Random.nextLong()
        val shaPrefix = msg.sha256(possibleNonce).take(4)

        val leadingZeros = shaPrefix.takeWhile(_ == "00")
        if (leadingZeros.length > 2) {
          println(shaPrefix)
        }

        if (caseToMatch.contains(shaPrefix)) {
          originalSender ! RegistrationResponse(msg, possibleNonce)
        } else {
          self ! probe
        }
    }
  }

  override def receive: Receive = {
    case Try =>
      val clientFlow = Tcp().outgoingConnection(new InetSocketAddress(host, port), options = KeepAlive(true) :: Nil)
      val processingFlow = Flow[ByteString]
        .map(Event.parse)
        .map { case init: EnrollInit =>
          EnrollRegister(init.challenge, 0, 4963, "ga92xav@mytum.de", "Denis", "Grebennicov")
        }
        .ask[RegistrationResponse](self)
        .map { response => response.msg.toByteString(response.nonce) }

      val _ = clientFlow.join(processingFlow).run()

    case msg: EnrollRegister =>
      implicit val timeout: Timeout = Timeout(100 seconds)
      val p = Promise[RegistrationResponse]()
      val workers = (0 until 16).map { _ => context.actorOf(ProbingActor.props) }

      workers
        .map { worker => worker.ask(msg).mapTo[RegistrationResponse] }
        .foreach(_  foreach p.trySuccess)

      val futureTimeout = akka.pattern.after(100 seconds, context.system.scheduler)(Future.failed(new TimeoutException(s"timed out during...")))

      Future.firstCompletedOf(Seq(p.future, futureTimeout))
        .map { response =>
          println(response)
          response
        }
        .pipeTo(sender)
        .onComplete {
          case Success(_) =>
            workers.foreach(_ ! PoisonPill)
          case Failure(_) =>
            workers.foreach(_ ! PoisonPill)
            self ! Try
        }
  }
}
