/*
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.oracle.infy.wookiee.component.zookeeper

import com.oracle.infy.wookiee.actor.WookieeActor
import com.oracle.infy.wookiee.actor.WookieeActor.PoisonPill
import com.oracle.infy.wookiee.component.ComponentV2
import com.oracle.infy.wookiee.component.zookeeper.mock.MockZookeeper
import com.oracle.infy.wookiee.utils.ThreadUtil
import com.oracle.infy.wookiee.zookeeper.ZookeeperSettings
import com.oracle.infy.wookiee.zookeeper.ZookeeperSettings._
import com.typesafe.config.ConfigFactory
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.RetryNTimes
import org.apache.curator.test.TestingServer

import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.util.Try

trait Zookeeper {
  this: ComponentV2 =>

  private val resources: AtomicReference[(Closeable, Option[String])] =
    new AtomicReference[(Closeable, Option[String])]()

  // Generally clusterEnabled is only used by wookiee-cluster, also mocking support built
  // into this method for when mock-enabled or mock-port are set in wookiee-zookeeper
  def startZookeeper(clusterEnabled: Boolean = isClusterEnabled): Unit = {
    // Load the zookeeper actor
    if (isMock(config)) {
      log.info("Zookeeper Mock Mode Enabled, Starting Local Test Server...")
      val quorum = getMockPort(config) match {
        case Some(port) =>
          val testCurator = CuratorFrameworkFactory.newClient(s"127.0.0.1:$port", 5000, 5000, new RetryNTimes(3, 100))
          try {
            testCurator.start()
            testCurator.blockUntilConnected(2, TimeUnit.SECONDS)
            assert(testCurator.getZookeeperClient.isConnected)
            resources.set((testCurator, None))
            s"127.0.0.1:$port"
          } catch {
            case _: Throwable =>
              log.info("^^^ Ignore above error if using multiple mock servers")
              val mockZk = new TestingServer(port)
              registerMediator(config, mockZk)
              resources.set((mockZk, Some(port.toString)))
              mockZk.getConnectString
          } finally {
            testCurator.close()
          }
        case None =>
          val mockZk = new TestingServer()
          registerMediator(mockZk.getPort.toString, mockZk)
          resources.set((mockZk, Some(mockZk.getPort.toString)))
          mockZk.getConnectString
      }

      // Start up ZK Actor with mock settings
      MockZookeeper(zookeeperSettings(Some(quorum)), clusterEnabled)(config)
    } else {
      // Start up ZK Actor as per normal, not mocking
      WookieeActor.actorOf(ZookeeperActor(zookeeperSettings(None), clusterEnabled)(config))
    }
    ()
  }

  def stopZookeeper(): Unit = {
    ZookeeperService.maybeGetMediator(config).foreach(_ ! PoisonPill)
    // Wait for the actor to stop before closing the server as it needs ZK for unregistering
    ThreadUtil.awaitEvent(ZookeeperService.maybeGetMediator(config).isEmpty, 5000L)
    Option(resources.get()) match {
      case Some((server, None)) =>
        log.info("Stopping Zookeeper Client...")
        server.close()
        resources.set(null)
      case Some((server, Some(_))) =>
        log.info("Stopping Zookeeper Mock Server...")
        server.close()
        ZookeeperSettings.unregisterMediator(config)
        resources.set(null)
      case None =>
        log.info("Zookeeper Mock Server Not Running...")
    }
  }

  protected def zookeeperSettings(quorum: Option[String]): ZookeeperSettings = {
    if (isMock(config)) {
      val conf = ConfigFactory
        .parseString(
          ZookeeperManager.ComponentName +
            s""".quorum="${quorum.getOrElse("")}""""
        )
        .withFallback(config)
      ZookeeperSettings(conf)
    } else {
      ZookeeperSettings(config)
    }
  }

  protected def isClusterEnabled: Boolean = {
    Try(config.getBoolean("wookiee-cluster.enabled")).getOrElse(false)
  }
}

object Zookeeper {
  val ZookeeperName = "zookeeper"
}
