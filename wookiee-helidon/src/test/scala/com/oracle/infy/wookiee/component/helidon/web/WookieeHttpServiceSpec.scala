package com.oracle.infy.wookiee.component.helidon.web

import com.oracle.infy.wookiee.component.helidon.HelidonManager
import com.oracle.infy.wookiee.component.helidon.util.EndpointTestHelper
import com.oracle.infy.wookiee.component.helidon.web.client.WookieeWebClient.{getContent, oneOff}
import com.oracle.infy.wookiee.component.{ComponentInfoV2, ComponentManager}
import com.oracle.infy.wookiee.service.WookieeService
import com.oracle.infy.wookiee.test.BaseWookieeTest
import com.typesafe.config.Config
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext

class TestHttpService(config: Config) extends WookieeHttpService(config) {

  override def addCommands(implicit conf: Config, ec: ExecutionContext): Unit =
    WookieeHttpServiceSpec.calledAddCommands.set(true)
}

object WookieeHttpServiceSpec {
  val calledAddCommands: AtomicReference[Boolean] = new AtomicReference(false)
}

class WookieeHttpServiceSpec extends AnyWordSpec with Matchers with BaseWookieeTest with EndpointTestHelper {

  def simpleGet(path: String): String = {
    getContent(
      oneOff(
        s"http://localhost:$internalPort",
        path,
        "GET",
        "",
        Map()
      )
    )
  }

  "Wookiee HTTP Service trait" should {
    "kick off addCommands when wookiee-helidon is ready" in {
      WookieeHttpServiceSpec.calledAddCommands.get() mustEqual true
    }

    "host the metrics endpoint" in {
      val responseContent = simpleGet("/metrics")
      responseContent.contains("metrics") mustEqual true
    }

    "host the healthcheck endpoint" in {
      var response: String = ""
      response = simpleGet("/healthcheck")
      response.contains("NORMAL") mustEqual true
      response = simpleGet("/healthcheck/full")
      response.contains("NORMAL") mustEqual true
      response = simpleGet("/healthcheck/lb")
      response mustEqual "UP"
      response = simpleGet("/healthcheck/nagios")
      response mustEqual "NORMAL|Thunderbirds are GO"
    }

    "host all other default endpoints" in {
      var response = simpleGet("favicon.ico")
      response mustEqual ""
      response = simpleGet("ping")
      response.contains("pong") mustEqual true
    }
  }

  override protected def beforeAll(): Unit = {
    manager = ComponentManager
      .getComponentByName("wookiee-helidon")
      .get
      .asInstanceOf[ComponentInfoV2]
      .component
      .asInstanceOf[HelidonManager]
    registerEndpoints(manager)
  }

  override def servicesMap: Option[Map[String, Class[_ <: WookieeService]]] =
    Some(Map("TestHttpService" -> classOf[TestHttpService]))

  override def registerEndpoints(manager: HelidonManager): Unit = {}

  override def config: Config = conf
}
