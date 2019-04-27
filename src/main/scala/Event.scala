import java.nio.ByteBuffer
import java.security.MessageDigest

import akka.util.ByteString

sealed trait Event

object Event {
  def parse(message: ByteString): Event = {
    def fieldToShort(idx: Int): Short = message.drop(2 * idx).take(2).asByteBuffer.getShort

    println(message)
    val status = fieldToShort(1)

    val event = status match {
      case 680 => EnrollInit(message.drop(4).asByteBuffer.getLong)
      case 682 => EnrollSuccess(fieldToShort(2), fieldToShort(3))
      case 683 => EnrollFailure(fieldToShort(2), fieldToShort(3), message.drop(8).utf8String)
      case _ => ???
    }

    println(event)
    event
  }

  final case class EnrollInit(challenge: Long) extends Event
  final case class EnrollSuccess(reserved: Short, teamNumber: Short) extends Event
  final case class EnrollFailure(reserved: Short, errorNumber: Short, errorDescription: String) extends Event
  final case class EnrollRegister(challenge: Long, teamNumber: Short, projectChoice: Short, email: String, firstName: String, lastName: String) extends Event {
    def size: Short = (24 + credentialsBytes.length).toShort

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