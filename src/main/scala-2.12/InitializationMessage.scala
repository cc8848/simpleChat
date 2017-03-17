import spray.json.{DefaultJsonProtocol, RootJsonFormat}

case class InitializationMessage(nick: String)
case class ASCIIArtDatagram(nick: String, line: String)


object CommunicationProtocol extends DefaultJsonProtocol {
  implicit val initializationMessageFormat: RootJsonFormat[InitializationMessage] = jsonFormat1(InitializationMessage)
  implicit val ASCIIArtDatagramFormat: RootJsonFormat[ASCIIArtDatagram] = jsonFormat2(ASCIIArtDatagram)

  val initializeUdp = "INIT"
  val disableUdp = "DISABLE"
}
