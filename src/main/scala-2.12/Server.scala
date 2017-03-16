import java.io._
import java.net._
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{Executors, TimeUnit}

import CommunicationProtocol._
import Server.{ClientEntry, UdpEntry}
import spray.json._

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Random, Success, Try}

class Server {
  implicit private[this] val executionContext = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
  private[this] val clients = TrieMap.empty[String, ClientEntry]
  private[this] val udpClients = TrieMap.empty[String, UdpEntry]
  private val loopExecutor = Executors.newScheduledThreadPool(3)
  private[this] var acceptingFlag: Boolean = false
  private[this] val udpAcceptingFlag: AtomicBoolean = new AtomicBoolean(false)

  private val serverSocket: ServerSocket = new ServerSocket(8080)
  private val udpServerSocket: DatagramSocket = new DatagramSocket(8080)

  private def acceptConnection(): Try[ClientEntry] = Try {
    val clientSocket = serverSocket.accept()
    val reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream))
    val jsonString = reader.readLine()
    println(jsonString)
    val parsedNick = jsonString.parseJson.convertTo[InitializationMessage]
    ClientEntry(clientSocket, parsedNick.nick, reading = false)
  }

  private def receiveMessage(id: String, socket: Socket): Future[String] = Future {
    val reader = new BufferedReader(new InputStreamReader(socket.getInputStream))
    reader.readLine()
  }

  private def sendMessage[T: JsonFormat](socket: Socket, message: String): Future[Unit] = Future {
    val writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream), true)
    writer.println(message)
  }

  private def chatLoop(): Unit = {
    clients.retain { case (_, ClientEntry(socket, _, _, failCount)) => !socket.isClosed && failCount < 5 }
    for ((id, e@ClientEntry(senderSocket, _, reading, _)) <- clients if !reading) {
      println(s"Listening for message from $id")
      clients.put(id, e.copy(reading = true))
      receiveMessage(id, senderSocket).andThen {
        case Success(message) =>
          for ((receiverId, ClientEntry(socket, _, _, _)) <- clients if receiverId != id) sendMessage(socket, s"[$id]: $message")
          println(s"Unlocking client: $id")
          clients.put(id, e.copy(reading = false))
        case Failure(reason) =>
          println(s"Failed to receive message from client: $id, due to ${reason.getMessage}")
          clients.put(id, e.copy(reading = false, failCount = e.failCount + 1))
      }
    }
  }


  private val buffer = new Array[Byte](1024)
  private def udpAcceptLoop(): Unit = {
    if (udpAcceptingFlag.compareAndSet(false, true)) {
      val packet = new DatagramPacket(buffer, buffer.length)
      udpServerSocket.receive(packet)
      val idString = new String(packet.getData)
      if (idString.startsWith(CommunicationProtocol.initializeUdp)) {
        udpClients.put(idString.split("::")(1), UdpEntry(packet.getAddress, packet.getPort))
      } else if (idString.startsWith(CommunicationProtocol.disableUdp)) {
        udpClients.remove(idString.split("::")(1))
      }
      udpAcceptingFlag.set(false)
    }
  }

  private def udpStreamLoop(): Unit = {
    val line = ("---" + Array.fill[Char](Random.nextInt(10))('|').mkString + "---").getBytes()
    Future {
      for ((_, UdpEntry(address, port)) <- udpClients) {
        val packet = new DatagramPacket(line, line.length, address, port)
        udpServerSocket.send(packet)
      }
    }
  }

  private def acceptNewClient(): Unit = {
    if (!acceptingFlag) {
      acceptingFlag = true
      println("Accepting")
      acceptConnection() match {
        case Success(e@ClientEntry(_, id, _, _)) =>
          println(s"Accepted cient with id: $id")
          clients += id -> e
          acceptingFlag = false
        case Failure(reason) =>
          reason.printStackTrace()
          acceptingFlag = false
      }
    }
  }

  def run(): Unit = {
    loopExecutor.scheduleAtFixedRate((() => acceptNewClient()): Runnable, 1, 1, TimeUnit.MILLISECONDS)
    loopExecutor.scheduleAtFixedRate((() => chatLoop()): Runnable, 1, 1, TimeUnit.MILLISECONDS)
    loopExecutor.scheduleAtFixedRate((() => udpAcceptLoop()): Runnable, 10, 1, TimeUnit.MILLISECONDS)
    loopExecutor.scheduleAtFixedRate((() => udpStreamLoop()): Runnable, 10, 40, TimeUnit.MILLISECONDS)
  }

}

object Server {

  case class UdpEntry(address: InetAddress, port: Int)
  case class ClientEntry(socket: Socket, id: String, reading: Boolean, failCount: Int = 0)

  def main(args: Array[String]): Unit = {
    val server = new Server
    server.run()
  }

}
