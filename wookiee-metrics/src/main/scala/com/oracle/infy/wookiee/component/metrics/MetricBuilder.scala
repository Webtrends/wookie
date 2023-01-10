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
package com.oracle.infy.wookiee.component.metrics

import com.codahale.metrics._
import com.codahale.metrics.jvm._
import com.oracle.infy.wookiee.component.metrics.messages.{
  CounterObservation,
  GaugeObservation,
  HistogramObservation,
  MeterObservation,
  TimerObservation
}
import com.oracle.infy.wookiee.component.metrics.metrictype.WookieeFileRatioGauge

import scala.language.existentials
import java.lang.management.ManagementFactory
import scala.jdk.CollectionConverters._

object MetricBuilder {

  val registry = new MetricRegistry()
  val jvmRegistry = new MetricRegistry()

  def apply(): MetricRegistry = registry

  /**
    * Fetch or register a Counter metric
    */
  def apply(o: CounterObservation): Counter = registry.counter(o.metric.name)

  /**
    * Fetch or register a Gauge metric
    */
  def apply(o: GaugeObservation): UpdatableGauge[Float] = {
    // See if this is already registered
    registry
      .getGauges(new MetricFilter {
        def matches(regName: String, metric: Metric): Boolean =
          regName.equals(o.metric.name)
      })
      .values
      .asScala
      .headOption match {

      case Some(m) =>
        m.asInstanceOf[UpdatableGauge[Float]]
      case None =>
        // Not registered so do so now
        registry.register(o.metric.name, new UpdatableGauge[Float]())
    }
  }

  /**
    * Fetch or register a Histogram metric
    */
  def apply(o: HistogramObservation): Histogram = {
    registry
      .getHistograms(new MetricFilter {
        def matches(regName: String, metric: Metric): Boolean =
          regName.equals(o.metric.name)
      })
      .values
      .asScala
      .headOption match {

      case Some(m: Histogram) =>
        m
      case None =>
        // Not registered so do so now
        registry.register(o.metric.name, if (o.metric.biased) {
          new Histogram(new ExponentiallyDecayingReservoir)
        } else {
          new Histogram(new UniformReservoir)
        })
    }
  }

  /**
    * Fetch or register a Meter metric
    */
  def apply(o: MeterObservation): Meter = registry.meter(o.metric.name)

  /**
    * Fetch or register a Timer metric
    */
  def apply(o: TimerObservation): Timer = registry.timer(o.metric.name)

  /**
    * Remove the given metric
    * @param metric the metric to remove
    * @return was the metric removed
    */
  def remove(metric: com.oracle.infy.wookiee.component.metrics.metrictype.Metric): Boolean =
    registry.remove(metric.name)

  def registerJvmMetrics: ThreadStatesGaugeSet = {

    val gcset = new GarbageCollectorMetricSet()
    jvmRegistry.register("gc", gcset)

    //Rename gc.time metrics to avoid issues with InfluxDB using time as a reserved word
    val gaugeIter = jvmRegistry.getGauges.asScala.iterator
    while (gaugeIter.hasNext) {
      val (oldName, gauge) = gaugeIter.next()
      val timeIndex = oldName.lastIndexOf(".time")
      if (timeIndex > 0) {
        val newName = s"${oldName.substring(0, timeIndex)}.gctime"
        jvmRegistry.remove(oldName)
        jvmRegistry.register(newName, gauge)
      }
    }

    val srv = ManagementFactory.getPlatformMBeanServer
    val poolset = new BufferPoolMetricSet(srv)
    jvmRegistry.register("buffer-pool", poolset)
    val musage = new MemoryUsageGaugeSet()
    jvmRegistry.register("memory", musage)
    val fd = WookieeFileRatioGauge()
    jvmRegistry.register("files.used.ratio", fd)
    val ts = new ThreadStatesGaugeSet()
    jvmRegistry.register("thread", ts)
  }

}
