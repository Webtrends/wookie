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
package com.oracle.infy.wookiee.service

import com.oracle.infy.wookiee.app.HarnessActorSystem.loader

import java.net.{URL, URLClassLoader}
import scala.util.{Failure, Success, Try}

/**
  * There should be one isolated instance of this class for each Component library
  * It is used to keep dependencies seperated between those libs and eventually for hawk(hot)-deployment
  * @param entityName The name of this Component or Service, should be
  * @param urls Paths to all the JARs that should be in this Class Loader
  */
case class HawkClassLoader(entityName: String, urls: Seq[URL]) extends URLClassLoader(urls.toArray) {

  // TODO invalidateCaches()
  /**
    * This method will perform the same functionality as ClassLoader.loadClass, except that it
    * will only locate and load the class in it's own class loader.
    */
  def loadClassLocally(name: String, resolve: Boolean): Option[Class[_]] = {
    // First see if the class is loaded
    (findLoadedClass(name) match {
      case null =>
        // Since the class was already searched for through the parents and local loader,
        // we will now just search for the class here
        Try[Class[_]](findClass(name))
      case c =>
        Success(c)
    }) match {
      case Success(clazz) =>
        val _ = findLoadedClass(name)
        if (resolve) resolveClass(clazz)
        Some(clazz)
      case _ =>
        None
    }
  }

  override def loadClass(name: String, resolve: Boolean): Class[_] = {
    println(s"HawkClassLoader : Trying to load class ${name}")
    // First, check if the class has already been loaded
    Try(super.loadClass(name, resolve)) match {
      case Success(v) => {
        println(s"Class already loaded ${name}")
        v
      }
      case Failure(_) => {
        println(s"Trying to load class from parent ${name}")
        loadClassFromParent(name, resolve)
      }
    }
  }

  private def loadClassFromParent(name: String, resolve: Boolean): Class[_] = {
    this.synchronized {
      // Get the loaded class
      loader.loadClass(name, resolve)
    }
  }

  def getLoadedClass(name: String): Option[Class[_]] = Option(findLoadedClass(name))

  /**
    * Appends the specified URL to the list of URLs to search for
    * classes and resources.
    * <p>
    * If the URL specified is <code>null</code> or is already in the
    * list of URLs, or if this loader is closed, then invoking this
    * method has no effect.
    *
    * @param url the URL to be added to the search path of URLs
    */
  def addServiceURL(url: URL): Unit = addURL(url)

}
