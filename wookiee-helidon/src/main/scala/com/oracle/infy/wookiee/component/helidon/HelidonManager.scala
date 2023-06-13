package com.oracle.infy.wookiee.component.helidon

import com.oracle.infy.wookiee.Mediator
import com.oracle.infy.wookiee.command.WookieeCommandExecutive
import com.oracle.infy.wookiee.component.helidon.web.http.HttpCommand
import com.oracle.infy.wookiee.component.helidon.web.http.HttpObjects.EndpointType.{
  BOTH,
  EXTERNAL,
  EndpointType,
  INTERNAL
}
import com.oracle.infy.wookiee.component.helidon.web.http.HttpObjects._
import com.oracle.infy.wookiee.component.helidon.web.http.impl.WookieeRouter
import com.oracle.infy.wookiee.component.helidon.web.http.impl.WookieeRouter._
import com.oracle.infy.wookiee.component.helidon.web.ws.WookieeWebsocket
import com.oracle.infy.wookiee.component.messages.StatusRequest
import com.oracle.infy.wookiee.component.{ComponentManager, ComponentRequest, ComponentV2}
import com.oracle.infy.wookiee.health.HealthCheckActor
import com.oracle.infy.wookiee.utils.ThreadUtil
import com.typesafe.config.Config
import io.helidon.webserver._
import org.joda.time.{DateTime, DateTimeZone}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

object HelidonManager extends Mediator[HelidonManager] {
  val ComponentName = "wookiee-helidon"

  /**
    * Primary 'object-oriented' entry point for registering an HTTP endpoint using Wookiee Helidon.
    * Will pull various functions and properties off of the WookieeHttpHandler and use them to
    * construct a handler registered at the specified path
    */
  def registerEndpoint(command: HttpCommand)(implicit config: Config, ec: ExecutionContext): Unit = {
    val mediator = getMediator(config)
    WookieeCommandExecutive.getMediator(config).registerCommand(command)
    val handler = handlerFromCommand(command)

    mediator.registerEndpoint(command.path, command.endpointType, command.method, handler)
  }

  /**
    * Primary 'functional' entry point for registering an HTTP endpoint using Wookiee Helidon.
    * Will compose the input functions in order of their passing into a WookieeHttpHandler object.
    * This object will then be automatically hosted on the specified web server
    */
  def registerEndpoint[Input <: Product: ClassTag: TypeTag, Output: ClassTag: TypeTag](
      name: String, // Unique name that will also expose this command via the WookieeCommandExecutive
      path: String, // HTTP path to host this endpoint, segments starting with '$' will be treated as wildcards
      method: String, // e.g. GET, POST, PATCH, OPTIONS, etc.
      endpointType: EndpointType.EndpointType, // Host this endpoint on internal or external port?
      requestHandler: WookieeRequest => Future[Input], // Marshall the WookieeRequest into any generic Input type
      businessLogic: Input => Future[Output], // Main business logic, Output is any generic type
      responseHandler: Output => WookieeResponse, // Marshall the generic Output type into a WookieeResponse
      errorHandler: Throwable => WookieeResponse, // If any errors happen at any point, how shall we respond
      endpointOptions: EndpointOptions = EndpointOptions.default // Set of options including CORS allowed headers
  )(
      implicit config: Config, // This just need to have 'instance-id' set to any string
      ec: ExecutionContext
  ): Unit = {

    val cmdName = name
    val cmdMethod = method.toUpperCase
    val cmdPath = path
    val cmdType = endpointType
    val cmdErrors = errorHandler
    val cmdOptions = endpointOptions
    val command = new HttpCommand {
      override def commandName: String = cmdName
      override def method: String = cmdMethod
      override def path: String = cmdPath
      override def endpointType: EndpointType = cmdType
      override def errorHandler(ex: Throwable): WookieeResponse = cmdErrors(ex)
      override def endpointOptions: EndpointOptions = cmdOptions

      override def execute(input: WookieeRequest): Future[WookieeResponse] = {
        requestHandler(input)
          .flatMap(businessLogic)
          .map(responseHandler)
      }
    }

    registerEndpoint(command)
  }

//  // TODO Fill in the handling of the message to be similar to akka-http
//  def registerWebsocket[I: ClassTag, O <: Product: ClassTag, A <: Product: ClassTag](
//      path: String,
//      endpointType: EndpointType,
//      authHandler: WookieeRequest => Future[A],
////                        textToInput: (A, String) => Future[I],
//      handleInMessage: (I, WebsocketInterface[I, O, A]) => Unit,
////                        outputToText: O => String,
//      onClose: (A, Option[I]) => Unit = { (_: A, _: Option[I]) =>
//        ()
//      },
//      authErrorHandler: WookieeRequest => PartialFunction[Throwable, WookieeResponse],
//      wsErrorHandler: PartialFunction[Throwable, WookieeResponse],
//      endpointOptions: EndpointOptions = EndpointOptions.default
//  )(implicit config: Config, formats: Formats): Unit = {
//    val wsPath = path
//    val wsEndpointType = endpointType
//    val wsEndpointOptions = endpointOptions
//
//    val websocket = new WookieeWebsocket {
//      override def path: String = wsPath
//      override def endpointType: EndpointType = wsEndpointType
//      override def endpointOptions: EndpointOptions = wsEndpointOptions
//
//      override def handleText(text: String, request: WookieeRequest)(implicit session: Session): Unit = ???
//    }
//  }

  def registerWebsocket(helidonWebsocket: WookieeWebsocket)(implicit config: Config): Unit = {
    val mediator = getMediator(config)
    mediator.registerEndpoint(
      helidonWebsocket.path,
      helidonWebsocket.endpointType,
      "WS",
      WebsocketHandler(helidonWebsocket)
    )
  }
}

// Main Helidon Component, manager of all things Helidon but for now mainly HTTP and Websockets
class HelidonManager(name: String, config: Config) extends ComponentV2(name, config) {
  HelidonManager.registerMediator(config, this)

  protected[oracle] var internalServer: ServiceHolder = _
  protected[oracle] var externalServer: ServiceHolder = _

  def internalPort: Int = config.getInt(s"${HelidonManager.ComponentName}.web.internal-port")
  def externalPort: Int = config.getInt(s"${HelidonManager.ComponentName}.web.external-port")

  // Kick off both internal and external web services on startup
  def startService(): Unit = {
    def startServer(routingService: WookieeRouter, port: Int): ServiceHolder = {
      val routing = Routing
        .builder()
        .register("/", routingService)
        .build() // Add Tyrus support for WebSocket

      val server = WebServer
        .builder()
        .routing(routing)
        .port(port)
        .build()

      server.start()
      ServiceHolder(server, routingService)
    }

    // Check config for the settings of allowed Origin headers
    def getAllowedOrigins(portType: String): CorsWhiteList = {
      val origins = config.getStringList(s"${HelidonManager.ComponentName}.web.cors.$portType").asScala.toList
      if (origins.isEmpty || origins.contains("*")) CorsWhiteList()
      else CorsWhiteList(origins)
    }

    val internalOrigins = getAllowedOrigins("internal-allowed-origins")
    val externalOrigins = getAllowedOrigins("external-allowed-origins")

    internalServer = startServer(new WookieeRouter(internalOrigins), internalPort)
    externalServer = startServer(new WookieeRouter(externalOrigins), externalPort)
    registerInternalEndpoints()
  }

  // Call on shutdown
  def stopService(): Unit = {
    internalServer.server.shutdown()
    externalServer.server.shutdown()
    ()
  }

  // Main internal entry point for registration of HTTP endpoints
  def registerEndpoint(path: String, endpointType: EndpointType, method: String, handler: WookieeHandler): Unit = {
    val actualMethod = handler match {
      case _: WebsocketHandler => "WS"
      case _                   => method
    }

    val slashPath = if (path.startsWith("/")) path else s"/$path"
    log.debug(s"HM200 Registering Endpoint: [path=$slashPath], [method=$actualMethod], [type=$endpointType]")

    endpointType match {
      case EXTERNAL =>
        externalServer.routingService.addRoute(slashPath, actualMethod, handler)
      case INTERNAL =>
        internalServer.routingService.addRoute(slashPath, actualMethod, handler)
      case BOTH =>
        externalServer.routingService.addRoute(slashPath, actualMethod, handler)
        internalServer.routingService.addRoute(slashPath, actualMethod, handler)
    }

    log.info(s"HM201 Endpoint Registered: Path=[$slashPath], Method=[$actualMethod], Type=[$endpointType]")
  }

  override def start(): Unit = {
    startService()
    log.info(s"Helidon Servers started on ports: [internal=$internalPort], [external=$externalPort]")
  }

  override def prepareForShutdown(): Unit = {
    stopService()
    log.info("Helidon Server shutdown complete")
  }

  private def registerInternalEndpoints(): Unit = {
    implicit val ec: ExecutionContext = ThreadUtil.createEC("internal-endpoint-pool")
    implicit val conf: Config = config

    internalRegister("healthcheck", { _ =>
      HealthCheckActor.getHealthFull.map { health =>
        WookieeResponse(Content(health.toJson))
      }
    })
    internalRegister("healthcheck/full", { _ =>
      HealthCheckActor.getHealthFull.map { health =>
        WookieeResponse(Content(health.toJson))
      }
    })
    internalRegister("healthcheck/lb", { _ =>
      HealthCheckActor.getHealthLB.map { health =>
        WookieeResponse(Content(health))
      }
    })
    internalRegister("healthcheck/nagios", { _ =>
      HealthCheckActor.getHealthNagios.map { health =>
        WookieeResponse(Content(health))
      }
    })
    internalRegister("ping", { _ =>
      Future.successful {
        WookieeResponse(Content(s"pong: ${new DateTime(System.currentTimeMillis(), DateTimeZone.UTC)}"))
      }
    })
    internalRegister("favicon.ico", { _ =>
      Future.successful {
        WookieeResponse(Content(""), StatusCode(204))
      }
    })
    internalRegister(
      "metrics", { _ =>
        ComponentManager.requestToComponent("wookiee-metrics", ComponentRequest(StatusRequest("string"))).map { resp =>
          WookieeResponse(Content(resp.resp.toString))
        }
      }
    )
  }

  private def internalRegister(cmdPath: String, execution: WookieeRequest => Future[WookieeResponse])(
      implicit ec: ExecutionContext,
      conf: Config
  ): Unit = {
    HelidonManager.registerEndpoint(new HttpCommand {
      override def method: String = "GET"
      override def path: String = cmdPath
      override def endpointType: EndpointType = EndpointType.BOTH
      override def execute(input: WookieeRequest): Future[WookieeResponse] = execution(input)
    })
  }
}
