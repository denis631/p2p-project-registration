import RegistrationActor.Try
import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives
import akka.stream.ActorMaterializer

object RegistrationStarter extends App with Directives {
  implicit val system: ActorSystem = ActorSystem("ProjectRegistration")
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val host = "fulcrum.net.in.tum.de"
  val port = 34151

  val registrationActor = system.actorOf(RegistrationActor.props(host, port))
  registrationActor ! Try
}