
import java.net.{SocketOptions, Inet4Address, InetAddress, Socket}
import java.util.concurrent.TimeoutException

import akka.actor.{Actor, ActorRef, Props, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws._
import akka.io.Inet
import akka.routing._
import akka.stream.ActorMaterializer
import akka.stream.actor.ActorPublisher
import akka.stream.scaladsl._
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random


object Upgradeable {
  def unapply(req: HttpRequest) : Option[HttpRequest] = {
    if (req.header[UpgradeToWebsocket].isDefined) {
      req.header[UpgradeToWebsocket] match {
        case Some(upgrade) => Some(req)
        case None => None
      }
    } else None
  }
}

case class ChatEvent(message: String, user: String, code: Int = 1)
case object ChatEvent extends DefaultJsonProtocol {
  implicit val protocol = jsonFormat3(ChatEvent.apply)
}
object ChatServer extends App {

  implicit val system = ActorSystem("chatHandler")
  implicit val fm = ActorMaterializer()
  import system.dispatcher

  val router = system.actorOf(Props[RouterActor], "router")

  val binding = Http().bindAndHandleSync({

    case Upgradeable(req@HttpRequest(GET, Uri.Path("/chat"), _, _, _)) => upgrade(req, chatGraphFlow(router))
    case _ : HttpRequest => HttpResponse(400, entity = "Invalid websocket request")

  }, interface = "localhost", port = 9001)

  def chatGraphFlow(router: ActorRef): Flow[Message, Message, Unit] = {
    Flow() { implicit b =>
      import FlowGraph.Implicits._

      val source = Source.actorPublisher[String](Props(classOf[RouterPublisher],router))
      val merge = b.add(Merge[String](2))
      var user = ""

      val validOrInvalid = b.add(Flow[ChatEvent].map{
        case broadcast: ChatEvent if user != "" =>
          router ! ChatEvent(broadcast.message,user)
          ChatEvent("Message Sent","SERVER",200).toJson.toString()
        case register: ChatEvent if user == "" && register.code == 3 =>
          user = register.message
          ChatEvent("Hello "+user,"SERVER",200).toJson.toString()
        case _ =>
          ChatEvent("Invalid Message","SERVER",500).toJson.toString()
      })
      
      val mapMsgToIncomingMessage = b.add(Flow[Message].map[ChatEvent] {
        case TextMessage.Strict(txt) => JsonParser(txt).convertTo[ChatEvent]
        case _ => ChatEvent("","",-1)
      })

      val mapStringToMsg = b.add(Flow[String].map[Message]( x => TextMessage.Strict(x)))

      val broadcasted = b.add(source)

      mapMsgToIncomingMessage ~> validOrInvalid ~> merge
                           broadcasted ~> merge ~> mapStringToMsg

      (mapMsgToIncomingMessage.inlet, mapStringToMsg.outlet)
    }
  }

  def upgrade(req: HttpRequest, flow: Flow[Message, Message, Unit]) = {
    req.header[UpgradeToWebsocket].get.handleMessages(flow)
  }

  system.scheduler.schedule(50 milliseconds, 10 second){
    router ! SendStats
  }

  try {
    Await.result(binding, 1 second)
    println("Server online at http://localhost:9001")
  } catch {
    case exc: TimeoutException =>
      println("Server took to long to startup, shutting down")
      system.shutdown()
  }

}



