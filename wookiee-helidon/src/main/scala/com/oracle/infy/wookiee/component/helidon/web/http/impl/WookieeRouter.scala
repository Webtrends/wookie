package com.oracle.infy.wookiee.component.helidon.web.http.impl

import com.oracle.infy.wookiee.component.helidon.web.http.HttpObjects._
import com.oracle.infy.wookiee.component.helidon.web.http.WookieeHttpHandler
import com.oracle.infy.wookiee.logging.LoggingAdapter
import io.helidon.webserver._

import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._

/**
  * This support object for WookieeRouter contains the handler creation logic that Helidon needs
  * for HTTP endpoint registration. It also has helpful functions for error handling, path segmentation,
  * and other routing related tasks.
  */
object WookieeRouter extends LoggingAdapter {
  // Represents the entire HTTP service and routing layers
  case class ServiceHolder(server: WebServer, routingService: WookieeRouter)

  // Primary class of our Path Trie
  case class PathNode(children: ConcurrentHashMap[String, PathNode], handler: Option[Handler] = None)

  // Takes the endpoints path template (e.g. /api/$foo/$bar) and the actual segments
  // that were sent (e.g. /api/v1/v2) and returns the Map of segment values (e.g. Map(foo -> v1, bar -> v2))
  def getPathSegments(pathWithVariables: String, actualPath: String): Map[String, String] = {
    val pathSegments = pathWithVariables.split("/")
    val actualSegments = actualPath.split("/")
    pathSegments
      .zip(actualSegments)
      .collect {
        case (segment, actual) if segment.startsWith("$") => (segment.drop(1), actual)
      }
      .toMap
  }

  // This method is meant to wrap the error handling logic for an individual endpoint
  // in yet another try/catch block. This is because the error handling logic itself
  // can throw an error, and we want to make sure that we catch that and return a 500.
  def handleErrorAndRespond(
      errorHandler: Throwable => WookieeResponse,
      res: ServerResponse,
      e: Throwable
  ): Unit =
    try {
      // Go into error handling
      val response = errorHandler(e)
      res.status(response.statusCode.code)
      response.headers.mappings.foreach(x => res.headers().add(x._1, x._2.asJava))
      res.send(response.content.value)
      ()
    } catch {
      case ex: Throwable =>
        log.error("RH400: Unexpected error in error handling logic while handling error", ex)
        log.error("RH401: Original error was:", e)
        res.status(500)
        res.send("There was an internal server error.")
        ()
    }

  // This is the conversion method that takes a WookieeHttpHandler command and converts it into
  // a Helidon Handler. This is the method that HelidonManager calls when it needs to register an endpoint.
  def handlerFromCommand(command: WookieeHttpHandler)(implicit ec: ExecutionContext): Handler = { (req, res) =>
    // There are three catch and recovery points in this method as there are three different
    // scopes in which errors can occur
    try {
      val actualPath = req.path().toString.split("\\?").headOption.getOrElse("")
      val pathSegments = WookieeRouter.getPathSegments(command.path, actualPath)

      // Get the query parameters on the request
      val queryParams = req.queryParams().toMap.asScala.toMap.map(x => x._1 -> x._2.asScala.toList)
      req.content().as(classOf[Array[Byte]]).thenAccept { bytes =>
        try {
          val wookieeRequest = WookieeRequest(
            Content(bytes),
            pathSegments,
            queryParams,
            Headers(req.headers().toMap.asScala.toMap.map(x => x._1 -> x._2.asScala.toList))
          )

          command
            .execute(wookieeRequest) // main business logic
            .map { response =>
              val respHeaders = command.endpointOptions.defaultHeaders.mappings ++ response.headers.mappings
              respHeaders.foreach(x => res.headers().add(x._1, x._2.asJava))
              res.status(response.statusCode.code)
              res.send(response.content.value)
            }
            .recover {
              case e: Throwable =>
                WookieeRouter.handleErrorAndRespond(command.errorHandler, res, e)
            }
          ()
        } catch {
          case e: Throwable =>
            WookieeRouter.handleErrorAndRespond(command.errorHandler, res, e)
        }
      }

      ()
    } catch {
      case e: Throwable =>
        WookieeRouter.handleErrorAndRespond(command.errorHandler, res, e)
    }
  }
}

/**
  * This class is the main router for the Wookiee Helidon HTTP service. It is responsible for
  * registering endpoints and routing/handling requests. The routing schema utilizes a Trie
  * data structure to allow for efficient routing of requests.
  */
class WookieeRouter extends Service {
  import WookieeRouter._

  val root = new ConcurrentHashMap[String, PathNode]()

  /**
    * This is the method that HelidonManager calls when it needs to register an endpoint.
    * It takes the path, method, and handler and adds it to the Trie of available endpoints.
    *
    * Any '$' prefixed values on the path are considered variables and will be treated
    * as wildcards when routing requests. For example, if you register an endpoint with the
    * path "/api/$foo/$bar" and a request comes in with the path "/api/v1/v2", the request
    * will be routed to that endpoint. Precedence will be given to exact matches, so a request
    * "/api/v1" will hit the endpoint registered with the path "/api/v1" instead of "/api/$baz".
    *
    * You can get the Helidon Handler object via WookieeRouter.handlerFromCommand
    */
  def addRoute(path: String, method: String, handler: Handler): Unit = {
    // Path must be non-empty
    if (path.isEmpty) throw new IllegalArgumentException("Path cannot be empty")

    val segments = path.split("/").filter(_.nonEmpty)
    val upperMethod = method.toUpperCase
    if (!root.containsKey(upperMethod)) {
      root.put(upperMethod, PathNode(new ConcurrentHashMap[String, PathNode]()))
    }

    var currentNode = root.get(upperMethod)
    // Navigate to the correct node in the Trie, creating nodes along the way
    for (segment <- segments.init) {
      // Any variable ('$' prefixed) segments are treated as wildcard '*' strings
      val segOrWild = if (segment.startsWith("$")) "*" else segment
      if (!currentNode.children.containsKey(segOrWild)) {
        currentNode.children.put(segOrWild, PathNode(new ConcurrentHashMap[String, PathNode]()))
      }
      currentNode = currentNode.children.get(segOrWild)
    }

    val finalSegment = if (segments.last.startsWith("$")) "*" else segments.last
    // Update this final node with the handler object provided, allowing it to be found
    if (!currentNode.children.containsKey(finalSegment)) {
      currentNode.children.put(finalSegment, PathNode(new ConcurrentHashMap[String, PathNode](), Some(handler)))
    } else {
      val node = currentNode.children.get(finalSegment)
      currentNode.children.put(finalSegment, node.copy(handler = Some(handler)))
    }
    ()
  }

//  def addWebsocketEndpoint(endpointConfig: ServerEndpointConfig): Unit = {
//    // TODO
//  }

  // This is the search method for the underlying Trie. It is complicated by the
  // fact that we need to support path variables. This means that we need to
  // search down multiple paths in the Trie since some paths represent wildcards.
  def findHandler(path: String, method: String): Option[Handler] = {
    // Strip off any trialing query params
    val withoutQueryParams = path.split("\\?").head
    val segments = withoutQueryParams.split("/").filter(_.nonEmpty)
    val upperMethod = method.toUpperCase
    if (!root.containsKey(upperMethod)) {
      return None
    }

    // Will be used to store wildcard nodes that we encounter
    // in case we need to backtrack
    val wildcardNodes = new mutable.Stack[(PathNode, Int)]()
    var currentNode = Option(root.get(upperMethod))
    var i = 0

    // Goes down the trie until we find a handler or we run out of trie
    while (i < segments.length && currentNode.isDefined) {
      currentNode match {
        case Some(PathNode(children, _)) if children.containsKey(segments(i)) =>
          currentNode = Some(children.get(segments(i)))
          if (children.containsKey("*"))
            wildcardNodes.push((children.get("*"), i))
          i += 1
        case Some(PathNode(children, _)) if children.containsKey("*") =>
          currentNode = Some(children.get("*"))
          i += 1
        case _ =>
          if (wildcardNodes.nonEmpty) {
            val (node, index) = wildcardNodes.pop()
            currentNode = Some(node)
            i = index + 1
          } else {
            currentNode = None
          }
      }
    }

    // If we found a handler, return it. Otherwise, return None
    currentNode.flatMap {
      case PathNode(_, Some(handler)) => Some(handler)
      case _                          => None
    }
  }

  // This is a Helidon Service method that is called by that library
  // whenever a request comes into the WebServer. Calls findHandler mainly
  override def update(rules: Routing.Rules): Unit = {
    rules.any((req, res) => {
      val path = req.path().toString

      findHandler(path, req.method().name()) match {
        case Some(handler) => handler.accept(req, res)
        case None          => res.status(404).send("Endpoint not found.")
      }
      ()
    })
    ()
  }
}
