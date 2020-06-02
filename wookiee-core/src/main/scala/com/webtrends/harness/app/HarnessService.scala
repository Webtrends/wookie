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
package com.webtrends.harness.app

object HarnessService extends App {
  execute()

  def execute(): Unit = {
    // Main body which gets run at startup
    try {
      Harness.externalLogger.info("Starting Harness...")

      val meta = Harness.startActorSystem()
      Harness.addShutdownHook()(meta.actorSystem)
      var keepRunning = true
      while (keepRunning) {
        try Thread.sleep(200)
        catch {
          case _: InterruptedException =>
            keepRunning = false
            Harness.externalLogger.info("Caught interrupt, exiting.")
          case ex: Throwable =>
            Harness.externalLogger.info("Caught exception '" + ex.getMessage + "'keeping main thread alive, keeping on.")
        }
      }
    } catch {
      case t: Throwable =>
        Harness.externalLogger.error(s"An error occurred while running the application: ${t.getMessage}", t)
        sys.exit(0)
    }
  }
}
