import java.io._
import java.net._
import java.util.concurrent.atomic.AtomicBoolean

import Client.{Failed, Ok, SendResult}
import CommunicationProtocol._
import spray.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class Client(nick: String, serverAddress: String, port: Int) {

  private val trySocket: Try[Socket] = init(serverAddress, port)
  private val tryUdpSocket: Try[DatagramSocket] = Try(new DatagramSocket())
  private[this] val isStreaming = new AtomicBoolean(false)
  implicit private[this] val executionContext = ExecutionContext.global

  private def init(address: String, port: Int): Try[Socket] = Try {
    val socket = new Socket(address, port)
    val writer = new PrintWriter(socket.getOutputStream, true)
    val initMessage = InitializationMessage(nick)
    writer.println(initMessage.toJson.compactPrint)
    socket
  }

  private def sendMessage(socket: Socket, message: String): SendResult = try {
    val writer = new PrintWriter(socket.getOutputStream, true)
    writer.println(message)
    Ok
  } catch {
    case t: Throwable =>
      println(s"Failed to send message!")
      Failed
  }

  private def sendAvatar(datagramSocket: DatagramSocket): Unit = {
    import Client.avatar
    val avatarLines = avatar.split("\n")
    val address = InetAddress.getByName(serverAddress)
    for (line <- avatarLines) {
      val data = line.getBytes
      val packet = new DatagramPacket(data, data.length, address, port)
      datagramSocket.send(packet)
    }
  }

  private def receiveMessage(socket: Socket): Unit = try {
    val reader = new BufferedReader(new InputStreamReader(socket.getInputStream))
    println(reader.readLine())
  } catch {
    case t: Throwable =>
      println(s"Failed to receive message!")
  }

  private def initUdp(socket: DatagramSocket): Unit = {
    Try {
      val address = InetAddress.getByName(serverAddress)
      val data = s"INIT::$nick".getBytes
      val initPacket = new DatagramPacket(data, data.length, address, port)
      socket.send(initPacket)
      isStreaming.set(true)
      receiveUdpFuture(socket)
    }
  }

  private def disableUdp(socket: DatagramSocket): Unit = {
    Try {
      val address = InetAddress.getByName(serverAddress)
      val data = s"DISABLE::$nick".getBytes
      val initPacket = new DatagramPacket(data, data.length, address, port)
      socket.send(initPacket)
      isStreaming.set(false)
    }
  }

  def sendMessage(socket: Socket, datagramSocket: DatagramSocket): Future[Any] = Future {
    val userInput = io.StdIn.readLine()
    if (userInput == "exit" || userInput == "EXIT") exit(socket)
    else if (userInput == "M") {
      initUdp(datagramSocket)
    } else if (userInput == "X") {
      disableUdp(datagramSocket)
    } else if (userInput == "N") {
      sendAvatar(datagramSocket)
    }
    else {
      sendMessage(socket, userInput)
    }
  }.andThen {
    case _ => sendMessage(socket, datagramSocket)
  }

  def receiveTcpFuture(socket: Socket): Future[Any] = Future {
    receiveMessage(socket)
  }.andThen {
    case _ => receiveTcpFuture(socket)
  }

  def receiveUdpFuture(datagramSocket: DatagramSocket): Future[Any] = Future {
    val buffer = new Array[Byte](200)
    val address = InetAddress.getByName(serverAddress)
    val receivePacket = new DatagramPacket(buffer, buffer.length, address, port)
    datagramSocket.receive(receivePacket)
    println(new String(receivePacket.getData))
  }.filter(_ => isStreaming.get()).andThen {
    case Success(_) => receiveUdpFuture(datagramSocket)
  }

  def mainLoop(socket: Socket, datagramSocket: DatagramSocket): Unit = {
    sendMessage(socket, datagramSocket)
    receiveTcpFuture(socket)
  }

  private def exit(socket: Socket): Unit = {
    try {
      val stream = socket.getInputStream
      while (stream.available() > 0) {
        stream.read()
      }
    } finally {
      println("Good by!")
      sys.exit()
    }
  }

  def start(): Unit = (for (s <- trySocket; u <- tryUdpSocket) yield s -> u) match {
    case Success((socket, datagramSocket)) =>
      mainLoop(socket, datagramSocket)
      while (true) {
      }
    case Failure(_) => Future {
      println("Critical app error")
    }
  }
}

object Client {

  val avatar: String =
    """
      |--```---```---
      |--------------
      |----\___/-----
      | ---&&&&&-----
    """.stripMargin

  sealed trait SendResult

  case object Ok extends SendResult

  case object Failed extends SendResult

  case object Locked extends SendResult

  def main(args: Array[String]): Unit = {
    println(avatar)
    val nick = io.StdIn.readLine("Type your nick\n")
    val client = new Client(nick, "localhost", 8080)
    client.start()
  }
}
