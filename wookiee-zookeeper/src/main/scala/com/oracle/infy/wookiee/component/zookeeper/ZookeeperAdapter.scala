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

import com.oracle.infy.wookiee.component.zookeeper.ZookeeperService._
import com.oracle.infy.wookiee.logging.LoggingAdapter
import com.typesafe.config.Config
import org.apache.curator.framework.recipes.atomic.DistributedAtomicLong
import org.apache.zookeeper.CreateMode

import java.util.concurrent.TimeUnit
import scala.concurrent.Future
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.util.Try

trait ZookeeperAdapter extends LoggingAdapter {
  def config: Config

  private[zookeeper] lazy implicit val zkTimeout: FiniteDuration =
    Try(config.getDuration(s"${ZookeeperManager.ComponentName}.default-send-timeout", TimeUnit.MILLISECONDS).millis)
      .getOrElse(10.seconds)

  /**
    * Set data in Zookeeper for the given path and optionally namespace
    * @param path the path to set data in
    * @param data the data to set
    * @param create should the node be created if it does not exist
    * @param ephemeral should the created node be ephemeral
    * @param namespace the optional name space
    * @return the length of data that was written
    */
  def setData(
      path: String,
      data: Array[Byte],
      create: Boolean = false,
      ephemeral: Boolean = false,
      namespace: Option[String] = None
  ): Future[Int] = {
    (getMediator(config) ? SetPathData(path, data, create, ephemeral, namespace)).mapTo[Int]
  }

  /**
    * Set data in Zookeeper for the given path async, does not return anything
    * @param path the path to set data in
    * @param data the data to set
    * @param create should the node be created if it does not exist
    * @param ephemeral should the created node be ephemeral
    * @param namespace an optional name space
    */
  def setDataAsync(
      path: String,
      data: Array[Byte],
      create: Boolean = false,
      ephemeral: Boolean = false,
      namespace: Option[String] = None
  ): Unit =
    getMediator(config) ! SetPathData(path, data, create, ephemeral, namespace, async = true)

  /**
    * Get Zookeeper data for the given path
    * @param path the path to get data from
    * @param namespace the optional name space
    * @return An instance of Array[Byte] or an empty array
    */
  def getData(path: String, namespace: Option[String] = None): Future[Array[Byte]] =
    (getMediator(config) ? GetPathData(path, namespace)).mapTo[Array[Byte]]

  /**
    * Get the data in Zookeeper for the given path or set it if the path does not exist
    * @param path the path to set data in
    * @param data the data to set
    * @param ephemeral should the created node be ephemeral
    * @param namespace the optional name space
    * @return An instance of Array[Byte] or an empty array
    */
  def getOrSetData(
      path: String,
      data: Array[Byte],
      ephemeral: Boolean = false,
      namespace: Option[String] = None
  ): Future[Array[Byte]] =
    (getMediator(config) ? GetOrSetPathData(path, data, ephemeral, namespace)).mapTo[Array[Byte]]

  /**
    * Get the child nodes for the given path
    * @param path the path to get the children of
    * @param includeData should the children's data be returned. Defaults to false.
    * @param namespace the optional name space
    * @return A Seq[String] or Nil is an error occurs or if there no children
    */
  def getChildren(
      path: String,
      includeData: Boolean = false,
      namespace: Option[String] = None
  ): Future[Seq[(String, Option[Array[Byte]])]] =
    (getMediator(config) ? GetPathChildren(path, includeData, namespace))
      .mapTo[Seq[(String, Option[Array[Byte]])]]

  /**
    * Does the node exist
    * @param path the path to check
    * @param namespace the optional name space
    * @return true or false
    */
  def nodeExists(path: String, namespace: Option[String] = None): Future[Boolean] =
    (getMediator(config) ? GetNodeExists(path, namespace)).mapTo[Boolean]

  /**
    * Create a node at the given path
    * @param path the path to create the node at
    * @param ephemeral is this node ephemeral
    * @param data the data to set in the node
    * @param namespace the optional name space
    * @return the full path to the newly created node
    */
  def createNode(
      path: String,
      ephemeral: Boolean,
      data: Option[Array[Byte]],
      namespace: Option[String] = None
  ): Future[String] = {
    val mode = if (ephemeral) CreateMode.EPHEMERAL else CreateMode.PERSISTENT
    (getMediator(config) ? CreateNode(path, mode, data, namespace)).mapTo[String]
  }

  /**
    * Create a node at the given path
    * @param path the path to create the node at
    * @param createMode mode in which to create the node
    * @param data the data to set in the node
    * @return the full path to the newly created node
    */
  def createNode(path: String, createMode: CreateMode, data: Option[Array[Byte]]): Future[String] =
    (getMediator(config) ? CreateNode(path, createMode, data)).mapTo[String]

  /**
    * Create a node at the given path
    * @param path the path to create the node at
    * @param createMode mode in which to create the node
    * @param data the data to set in the node
    * @param namespace the optional name space
    * @return the full path to the newly created node
    */
  def createNodeWithNamespace(
      path: String,
      createMode: CreateMode,
      data: Option[Array[Byte]],
      namespace: Option[String] = None
  ): Future[String] =
    (getMediator(config) ? CreateNode(path, createMode, data, namespace)).mapTo[String]

  /**
    * Create a counter that can be incremented and decremented
    * @param path the path in ZK to store the counter
    * @return distributed ZK long that can be incremented/decremented
    */
  def createCounter(path: String): Future[DistributedAtomicLong] = {
    (getMediator(config) ? CreateCounter(path)).mapTo[DistributedAtomicLong]
  }

  /**
    * Delete a node at the given path
    * @param path the path to create the node at
    * @param namespace the optional name space
    * @return the full path to the newly created node
    */
  def deleteNode(path: String, namespace: Option[String] = None): Future[String] =
    (getMediator(config) ? DeleteNode(path, namespace)).mapTo[String]

  /**
    * Stops the zookeeper mediator (ZookeeperActor) associated with this system
    */
  def stopZookeeper(): Unit =
    ZookeeperService.unregisterMediator(config)
}
