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
package com.oracle.infy.wookiee.service.meta

import akka.actor.ActorRef
import com.oracle.infy.wookiee.service.ServiceV2
import com.oracle.infy.wookiee.utils.{Json, JsonSerializable}

trait WookieeServiceMeta {
  val name: String
}

case class ServiceMetaDataV2(
    name: String,
    service: ServiceV2
) extends WookieeServiceMeta

case class ServiceMetaData(
    name: String,
    actorRef: ActorRef
) extends WookieeServiceMeta
    with JsonSerializable {

  override def toJson: String = {
    val props = Map[String, Any](
      "name" -> name
    )
    Json.build(props).toString
  }
}
