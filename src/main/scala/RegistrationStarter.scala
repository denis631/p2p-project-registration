import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest

import Event.{EnrollInit, EnrollRegister}
import RegistrationActor.RegistrationResponse
import akka.actor.ActorSystem
import akka.io.Tcp.SO.KeepAlive
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Tcp}
import akka.util.{ByteString, Timeout}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

sealed trait Event {
  def size: Short
}

object Event {
  def parse(message: ByteString): Event = {
    def fieldToShort(idx: Int): Short = message.drop(2 * idx).take(2).asByteBuffer.getShort

    println(message)
    val size = fieldToShort(0)
    val status = fieldToShort(1)

    val event = status match {
      case 680 => EnrollInit(size, message.drop(4).asByteBuffer.getLong)
      case 682 => EnrollSuccess(size, fieldToShort(2), fieldToShort(3))
      case 683 => EnrollFailure(size, fieldToShort(2), fieldToShort(3), message.drop(8).utf8String)
      case _ => ???
    }

    println(event)
    event
  }

  final case class EnrollInit(size: Short, challenge: Long) extends Event
  final case class EnrollSuccess(size: Short, reserved: Short, teamNumber: Short) extends Event
  final case class EnrollFailure(size: Short, reserved: Short, errorNumber: Short, errorDescription: String) extends Event
  final case class EnrollRegister(challenge: Long, teamNumber: Short, projectChoice: Short, email: String, firstName: String, lastName: String) extends Event {
    override def size: Short = (24 + credentialsBytes.length).toShort

    val credentialsBytes: Array[Byte] = List(email, firstName, lastName).mkString("\r\n").getBytes("UTF-8")

    def sha256(nonce: Long): List[String] = {
      def longToBytes(x: Long): List[Byte] = ByteBuffer.allocate(8).putLong(x).array().toList
      def shortToBytes(x: Short): List[Byte] = ByteBuffer.allocate(2).putShort(x).array().toList

      val bytes = longToBytes(challenge) ++
        shortToBytes(teamNumber) ++
        shortToBytes(projectChoice) ++
        longToBytes(nonce) ++
        credentialsBytes.toList

      MessageDigest.getInstance("SHA-256")
        .digest(bytes.toArray)
        .map("%02x".format(_))
        .toList
    }

    def toByteString(nonce: Long): ByteString = {
      val byteBuffer = ByteBuffer
        .allocate(size)
        .putShort(size)
        .putShort(681)
        .putLong(challenge)
        .putShort(teamNumber)
        .putShort(projectChoice)
        .putLong(nonce)
        .put(credentialsBytes)

      ByteString(byteBuffer.array())
    }
  }
}

object RegistrationStarter extends App {
  implicit val system: ActorSystem = ActorSystem("ProjectRegistration")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = Timeout(24 hours)//Timeout(5 seconds)

  val host = "fulcrum.net.in.tum.de"
  val port = 34151
  val socketAddress = new InetSocketAddress(host, port)

  val registrationActor = system.actorOf(RegistrationActor.props)
  val clientFlow = Tcp().outgoingConnection(new InetSocketAddress(host, port), options = KeepAlive(true) :: Nil)

  val processingFlow = Flow[ByteString]
    .map(Event.parse)
    .map { case init: EnrollInit =>
      EnrollRegister(init.challenge, 0, 4963, "ga92xav@mytum.de", "Denis", "Grebennicov")
    }
    .ask[RegistrationResponse](registrationActor)
    .map { response => response.msg.toByteString(response.nonce) }

  clientFlow.join(processingFlow).run()
}