package com.webtrends.harness.extension

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import com.webtrends.harness.app.{HarnessActorSystem, HarnessClassLoader}
import com.webtrends.harness.component.{ComponentManager, ComponentRequest, ComponentResponse, LoadComponent, Request}
import com.webtrends.harness.service.HawkClassLoader
import com.webtrends.harness.service.test.BaseWookieeTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.io.File
import java.net.URLClassLoader
import scala.concurrent.Await
import scala.concurrent.duration._

// Note: This test only works if you set your working directory to wookiee/wookiee-test
class ClassLoaderSpec extends BaseWookieeTest with AnyWordSpecLike with Matchers {
  implicit val timeout: Timeout = 25.seconds

  "A single class loader" should {
    "Take the first of the same package classes across different jars by default" in {
      val sys = ActorSystem("SingleLoader")

      try {
        val jarA = getClass.getResource("/basic-extension-a.jar")
        val jarB = getClass.getResource("/basic-extension-b.jar")
        val harnessClassLoader = new HarnessClassLoader(new URLClassLoader(Array(jarA, jarB)))
        val cm = sys.actorOf(Props[ComponentManager])

        println("Loading each component jar..")
        val extA = Await.result((cm ? LoadComponent("BasicExtensionA", "com.webtrends.infy.qa.BasicExtension",
          Some(harnessClassLoader))).mapTo[Option[ActorRef]], timeout.duration)
        val extB = Await.result((cm ? LoadComponent("BasicExtensionB", "com.webtrends.infy.qa.BasicExtension",
          Some(harnessClassLoader))).mapTo[Option[ActorRef]], timeout.duration)
        Thread.sleep(1000L)
        println("\nTrying to log from each..")
        val instA = extA.map(act => Await.result((act ? "log").mapTo[String], timeout.duration)).getOrElse("")
        val instB = extB.map(act => Await.result((act ? "log").mapTo[String], timeout.duration)).getOrElse("")

        instA shouldEqual instB
      } finally {
        sys.terminate()
      }
    }
  }

  "A class loader for each component" should {
    "Keep separate classes, even with the same path, on different loaders" in {
      val sys = ActorSystem("MultiLoader")

      try {
        val jarA = getClass.getResource("/basic-extension-a.jar")
        val jarB = getClass.getResource("/basic-extension-b.jar")
        val harnessClassLoaderA = new HarnessClassLoader(new URLClassLoader(Array(jarA)))
        val harnessClassLoaderB = new HarnessClassLoader(new URLClassLoader(Array(jarB)))

        val cm = sys.actorOf(Props[ComponentManager])

        println("Loading each component jar..")
        val extB = Await.result((cm ? LoadComponent("BasicExtensionB", "com.webtrends.infy.qa.BasicExtension",
          Some(harnessClassLoaderB))).mapTo[Option[ActorRef]], timeout.duration)
        val extA = Await.result((cm ? LoadComponent("BasicExtensionA", "com.webtrends.infy.qa.BasicExtension",
          Some(harnessClassLoaderA))).mapTo[Option[ActorRef]], timeout.duration)
        Thread.sleep(1000L)
        println("\nTrying to log from each..")
        val instA = extA.map(act => Await.result((act ? "log").mapTo[String], timeout.duration)).getOrElse("")
        val instB = extB.map(act => Await.result((act ? "log").mapTo[String], timeout.duration)).getOrElse("")

        instA should not be instB
      } finally {
        sys.terminate()
      }
    }
  }

  "The main harness class loader" should {
    "Load up children classes from their respective loaders" in {
      val sys = ActorSystem("MainMultiLoader")

      try {
        val jarA = getClass.getResource("/basic-extension-a.jar")
        val jarOther = getClass.getResource("/other-extension.jar")
        val harnessClassLoader = new HarnessClassLoader(new URLClassLoader(Array()))
        val clA = new HawkClassLoader(List(jarA))
        val clOther = new HawkClassLoader(List(jarOther))

        harnessClassLoader.addChildLoader(clA)
        harnessClassLoader.addChildLoader(clOther)

        val cm = sys.actorOf(Props[ComponentManager])

        println("Loading each component jar..")
        val extB = Await.result((cm ? LoadComponent("BasicExtension", "com.webtrends.infy.qa.BasicExtension",
          Some(harnessClassLoader))).mapTo[Option[ActorRef]], timeout.duration)
        val extA = Await.result((cm ? LoadComponent("OtherExtension", "com.webtrends.infy.qa.OtherExtension",
          Some(harnessClassLoader))).mapTo[Option[ActorRef]], timeout.duration)
        Thread.sleep(1000L)
        println("\nTrying to log from each..")
        val instA = extA.map(act => Await.result((act ? "log").mapTo[String], timeout.duration)).getOrElse("")
        val instB = extB.map(act => Await.result((act ? "log").mapTo[String], timeout.duration)).getOrElse("")

        instA should not be instB
      } finally {
        sys.terminate()
      }
    }

    "Should not load classes in when reading config" in {
      val config = HarnessActorSystem.getConfig(None)
      config.getString("other-extension.something.value") shouldEqual "example"
      HarnessActorSystem.loader.getChildLoaders
        .exists(_.getURLs.exists(_.getPath.contains("other-extension"))) shouldEqual true
    }

    "Should isolate classes to their component's jars/dirs" in {
      val otherStr = pollComponentReq[String]("other-extension", "log")
      val basicStr = pollComponentReq[String]("basic-extension", "log")

      otherStr shouldEqual "O"
      basicStr shouldEqual "A"
    }
  }

  override def config: Config = {
    val workingDir = new File(System.getProperty("user.dir")).listFiles().filter(_.isDirectory).map(_.getName)
    val compDir = if (workingDir.contains("wookiee")) "wookiee/wookiee-test/src/test/resources"
    else if (workingDir.contains("wookiee-test")) "wookiee-test/src/test/resources"
    else "src/test/resources"

    println(s"Component Directory: [$compDir]")
    ConfigFactory.parseString(
      s"""{
         | services.path = "src/"
         | components.path = "$compDir"
         |}""".stripMargin)
  }

  def waitForSome[T](isSome: => Option[T]): T = {
    val waitTill = System.currentTimeMillis() + timeout.duration.toMillis
    while (System.currentTimeMillis() < waitTill && isSome.isEmpty) Thread.sleep(500L)
    isSome.get
  }

  def pollComponentReq[U](componentName: String, request: U): String = {
    val cm = waitForSome({ testWookiee.componentManager })
    val waitTill = System.currentTimeMillis() + timeout.duration.toMillis
    while (System.currentTimeMillis() < waitTill) {
      try {
        val resp = Await.result((cm ? Request[U](componentName,
          ComponentRequest(request, Some(ComponentManager.ComponentRef)))).mapTo[ComponentResponse[String]], timeout.duration)
        return resp.resp
      } catch {
        case _: Throwable => // Ignore until we're out of time
      }
    }
    "<timeout>"
  }
}
