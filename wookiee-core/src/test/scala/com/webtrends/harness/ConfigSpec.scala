/*
 * Copyright 2015 Webtrends (http://www.webtrends.com)
 *
 * See the LICENCE.txt file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.webtrends.harness

import java.io.{BufferedWriter, File, FileWriter}
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import com.webtrends.harness.app.HarnessActor.ConfigChange
import com.webtrends.harness.config.ConfigWatcherActor
import com.webtrends.harness.health.{ComponentState, HealthComponent}
import com.webtrends.harness.service.messages.CheckHealth
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.FiniteDuration
import scala.reflect.io.{Directory, Path}

class ConfigSpec extends WordSpecLike with Matchers with BeforeAndAfterAll {
  implicit val dur: FiniteDuration = FiniteDuration(2, TimeUnit.SECONDS)
  new File("services/test/conf").mkdirs()
  implicit val sys: ActorSystem = ActorSystem("system", ConfigFactory.parseString( """
    services { path = "services" }
    """).withFallback(ConfigFactory.load()))

  implicit val ec: ExecutionContextExecutor =  sys.dispatcher

  val probe: TestProbe = TestProbe()
  val parent: ActorRef = sys.actorOf(Props(new Actor {
    val child: ActorRef = context.actorOf(ConfigWatcherActor.props, "child")
    def receive: Receive = {
      case x if sender == child => probe.ref forward x
      case x => child forward x
    }
  }))

  "Config " should {
    "be in good health" in {
      probe.send(parent, CheckHealth)
      val msg = probe.expectMsgClass(classOf[HealthComponent])
      msg.state equals ComponentState.NORMAL
    }

    "detect changes in config" in {
      val file = new File("services/test/conf/test.conf")
      val bw = new BufferedWriter(new FileWriter(file))
      bw.write("test = \"value\"")
      bw.close()
      val msg = probe.expectMsgClass(classOf[ConfigChange])
      msg.isInstanceOf[ConfigChange]
    }
  }

  override protected def afterAll(): Unit = {
    sys.terminate().onComplete { _ =>
        Directory(Path(new File("services"))).deleteRecursively()
    }
  }
}

