import spray.json.{DefaultJsonProtocol, RootJsonFormat}

case class InitializationMessage(nick: String)


object CommunicationProtocol extends DefaultJsonProtocol {
  implicit val initializationMessageFormat: RootJsonFormat[InitializationMessage] = jsonFormat1(InitializationMessage)

  val initializeUdp = "INIT"
  val disableUdp = "DISABLE"
}
