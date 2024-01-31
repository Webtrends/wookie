/*
 * Copyright 2015 Oracle (http://www.Oracle.com)
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
package com.oracle.infy.wookiee.component.metrics.messages

import com.codahale.metrics.MetricFilter
import com.oracle.infy.wookiee.component.metrics.metrictype.Metric

trait MetricMessage {
  def metric: Metric
}

case class RemoveMetric(metric: Metric) extends MetricMessage

case class RemoveMatchingMetric(filter: MetricFilter, metric: Metric) extends MetricMessage
