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

package com.oracle.infy.wookiee.test.command

import com.oracle.infy.wookiee.command.WookieeCommand

import scala.concurrent.Future

case class TestPayload(name: String)

class TestCommand extends WookieeCommand[TestPayload, String] {
  override def commandName: String = TestCommand.CommandName

  override def execute(bean: TestPayload): Future[String] = {
    Future.successful(s"Test ${bean.name} OK")
  }
}

object TestCommand {
  def CommandName: String = "TestCommand"
}
