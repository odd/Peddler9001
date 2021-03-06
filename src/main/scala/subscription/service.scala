package paermar
package service

// Ofrenda2014

import akka.actor._
import akka.io.IO

import scala.concurrent.{Promise, ExecutionContext, Future}
import scala.util.control.NonFatal

import scala.slick.jdbc.JdbcBackend._
import scala.slick.driver.MySQLDriver

import org.joda.time.format.ISODateTimeFormat
import org.joda.time.DateTime

import spray.can.Http
import spray.json._
import spray.httpx.SprayJsonSupport._
import spray.httpx.unmarshalling.{MalformedContent, FromStringDeserializer}
import spray.routing._, Directives._

object Service {
  import paermar.application.ApplicationFeatures
  import paermar.model.Domain.PersistenceModule._
  import paermar.model.Domain.TransactionsModule._
  import paermar.model.Domain.DepositsModule._
  import paermar.model.Domain.CustomersModule._

  trait ServiceUniverse {
    val protocol: Protocol
    val application: ApplicationFeatures
    implicit def executionContext: ExecutionContext
  }

  trait RouteSource {
    def route: Route
  }

  class Protocol(val features: ApplicationFeatures) extends DefaultJsonProtocol {
    import application.Parcel._
    import TransactionType._

    implicit object _StringToDateTime extends FromStringDeserializer[DateTime] {
      def apply(source: String) =
        try Right(_DateTimeFormat.Format parseDateTime source)
        catch {
          case NonFatal(x) ⇒
            Left(MalformedContent(s"Un-parsable date `$source`", x))
        }
    }

    implicit object _DateTimeFormat extends JsonFormat[DateTime] {
      final val Format = ISODateTimeFormat.dateTimeNoMillis.withZoneUTC

      def read(json: JsValue): DateTime = json match {
        // todo: handle errors
        case JsString(source) ⇒ Format parseDateTime source
      }

      def write(value: DateTime): JsValue = JsString(Format print value)
    }

    case class SingletonMapExtractor[K, V](key: K) {
      def unapply(m: Map[K, V]) = m get key
    }

    implicit object _TransactionTypeFormat extends JsonFormat[TransactionType] {
      def read(source: JsValue): TransactionType = source match {
        case JsString("Debit")  ⇒ Debit
        case JsString("Credit") ⇒ Credit
      }

      def write(`type`: TransactionType) = `type` match {
        case Debit  ⇒ JsString("Debit")
        case Credit ⇒ JsString("Credit")
      }
    }

    implicit val _cashPaymenyFormat = jsonFormat3(CashPayment)
    implicit val _depositFormat     = jsonFormat9(Deposit)
    implicit val _customerFormat    = jsonFormat3(Customer)
    implicit val _transactionFormat = jsonFormat6(Transaction)

    implicit def _ParcelFormat[X: JsonFormat, A: JsonFormat] = new RootJsonFormat[X ⊕ A] {
      val Success = SingletonMapExtractor[String, JsValue]("success")
      val Failure = SingletonMapExtractor[String, JsValue]("failure")

      def read(json: JsValue): X ⊕ A = json match {
        case Success(v) ⇒ successful[X, A](v.convertTo[A])
        case Failure(v) ⇒ failed[X, A]    (v.convertTo[X])
      }

      def write(parcel: X ⊕ A): JsValue =
        parcel.fold(x ⇒ JsObject("failure" -> x.toJson),
                    s ⇒ JsObject("success" -> s.toJson))
    }
  }

  trait ProtectedRoute extends RouteSource { self: ServiceUniverse ⇒
    import authentication._
    // Does this need a sealRoute added?

    val authenticator: UserPassAuthenticator[application.AuthenticationContext] = {
      case Some(UserPass(user, pass)) ⇒
        Future(application.authenticate(user, pass).mapOrElse(Option.apply)(None))
      case _ ⇒ Promise successful None future
    }

    override abstract def route = authenticate(BasicAuth(authenticator, realm = "Inside")) { authenticationContext ⇒
      // how do I pass on `authenticationContext` ?
      super.route
    }
  }

  trait ServerInfoRoute extends RouteSource { self: ServiceUniverse ⇒
    override abstract def route = thisRoute ~ super.route

    private def thisRoute = path("server-info") {
      get {
        complete {
          "Pärmar/0.1"
        }
      }
    }
  }

  trait CustomerRoute extends RouteSource { self: ServiceUniverse ⇒
    import protocol._

    override abstract def route = thisRoute ~ super.route

    private def thisRoute = path("customers") {
      get {
        complete(application.customers)
      } ~ post {
        entity(as[String]) { name ⇒ ctx ⇒
          val customer = application addCustomer name

          ctx complete customer
        }
      }
    }
  }

  trait TransactionRoute extends RouteSource { self: ServiceUniverse ⇒
    import protocol._

    override abstract def route = thisRoute ~ super.route

    private def thisRoute = path("transactions") {
      get {
        parameters('from.as[DateTime], 'through.as[DateTime]) { (from, through) ⇒
          complete(application.transactionsSpanning(from, through))
        }
      } ~ post {
        entity(as[Transaction]) { transaction ⇒
          val created = application addTransaction transaction

          complete(created)
        }
      }
    }
  }

  trait DepositRoute extends RouteSource { self: ServiceUniverse ⇒
    import protocol._

    override abstract def route = thisRoute ~ super.route

    private def thisRoute = path("deposits") {
      get {
        parameters('from.as[DateTime], 'through.as[DateTime]) { (from, through) ⇒
          complete(application.depositsSpanning(from, through))
        }
      } ~ post {
        entity(as[CashPayment]) { payment ⇒
          complete(application addDeposit payment)
        }
      }
    }
  }

  trait ServicePlatform extends ServiceUniverse with RouteSource {
    def route: Route = reject
  }

  trait ServiceRouteConcatenation extends ServicePlatform
    with ServerInfoRoute
    with ProtectedRoute
    with CustomerRoute
    with TransactionRoute
    with DepositRoute

  case class Endpoint(host: String, port: Int)

  class Router() extends HttpServiceActor with ServiceRouteConcatenation {
    val database = Database forURL (     url = "jdbc:mysql://localhost:3306/subscriptions",
                                      driver = "com.mysql.jdbc.Driver",
                                        user = "root",
                                    password = "")

    val application =
      new ApplicationFeatures(new UnifiedPersistence(MySQLDriver), database)

    val protocol = new Protocol(application)

    implicit def executionContext: ExecutionContext = context.dispatcher
    def receive = runRoute(route)
  }

  def bind(e: Endpoint)(implicit system: ActorSystem) = IO(Http) ! Http.Bind(
    listener  = system actorOf Props[Router],
    interface = e.host,
    port      = e.port)
}

object ServiceRunner extends App {
  implicit val actorSystem = ActorSystem("services")

  Service bind Service.Endpoint("localhost", 8080)

  Console.in.read()
  actorSystem.shutdown()
}