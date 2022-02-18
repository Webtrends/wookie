package com.oracle.infy.wookiee.grpc.loadbalancers

import com.google.common.base.Objects
import com.oracle.infy.wookiee.grpc.loadbalancers.Pickers._
import com.oracle.infy.wookiee.grpc.utils.implicits.MultiversalEquality
import io.grpc.ConnectivityState._
import io.grpc.LoadBalancer.{CreateSubchannelArgs, Subchannel}
import io.grpc.util.ForwardingSubchannel
import io.grpc.{Attributes, ConnectivityState, ConnectivityStateInfo, EquivalentAddressGroup, LoadBalancer, Status}

import java.util
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.jdk.CollectionConverters._
import scala.util.Random

final class WookieeLoadBalancer(helper: LoadBalancer.Helper, readyPickerFactory: List[Subchannel] => ReadyPicker)
    extends LoadBalancer {

  private[wookiee] val STATE_INFO: Attributes.Key[AtomicReference[ConnectivityStateInfo]] =
    Attributes.Key.create("state-info")

  private val subChannels: util.HashMap[EquivalentAddressGroup, Subchannel] =
    new util.HashMap[EquivalentAddressGroup, Subchannel]()

  // The only state that a usable subchannel can be in for this picker is READY. If it isn't ready,
  // that isn't necessarily an error, but no subchannels should be picked, hence the need for this status.
  private val EMPTY_OK = Status.OK.withDescription("no subchannels ready")

  // Either a subchannel is ready, or it is not. If it is ready, then use a ready picker, which
  // will pick a subchannel based on its current load. Otherwise, use an empty picker.
  private val eitherPicker: AtomicReference[Either[ReadyPicker, EmptyPicker]] =
    new AtomicReference[Either[ReadyPicker, EmptyPicker]](Right(EmptyPicker(EMPTY_OK)))

  val random: Random = new Random()
  val currentState: AtomicReference[ConnectivityState] = new AtomicReference[ConnectivityState]()

  override def handleResolvedAddresses(resolvedAddresses: LoadBalancer.ResolvedAddresses): Unit = {
    // Map of equivalent address groups: EAG stripped of Attributes -> Original EAG with Attributes
    val latestAddrs: List[(EquivalentAddressGroup, EquivalentAddressGroup)] = resolvedAddresses
      .getAddresses
      .asScala
      .flatMap(eag => Map[EquivalentAddressGroup, EquivalentAddressGroup]((stripAttrs(eag), eag)))
      .toList
    // Set of keys of addresses after channel has made connection
    val currentAddrs: Set[EquivalentAddressGroup] = subChannels.keySet.asScala.toSet
    // Addresses that are no longer in use and need to be shutdown
    val removedAddrs: Set[EquivalentAddressGroup] = currentAddrs.diff(latestAddrs.toMap.keySet)
    latestAddrs
      .foreach(latestEntry => {
        val strippedAddressGroup: EquivalentAddressGroup = latestEntry._1
        val originalAddressGroup: EquivalentAddressGroup = latestEntry._2
        val existingSubchannel: Option[Subchannel] = Option(subChannels.get(strippedAddressGroup))
        val attributes: Attributes = originalAddressGroup.getAttributes
        existingSubchannel match {
          case Some(subchannel: ForwardingSubchannel) =>
            subchannel.updateAddresses(List(originalAddressGroup).asJava)
          case _ =>
            val subchannelAttrs = Attributes
              .newBuilder()
              .setAll(attributes)
              .set(
                STATE_INFO,
                new AtomicReference[ConnectivityStateInfo](ConnectivityStateInfo.forNonError(IDLE))
              )
              .build()
            val subchannel: Option[Subchannel] = Option(
              helper.createSubchannel(
                CreateSubchannelArgs
                  .newBuilder()
                  .setAddresses(originalAddressGroup)
                  .setAttributes(subchannelAttrs)
                  .build()
              )
            )
            subchannel match {
              case Some(someSubchannel) =>
                someSubchannel.start((state: ConnectivityStateInfo) => {
                  processSubchannelState(someSubchannel, state)
                })
                subChannels.put(strippedAddressGroup, someSubchannel)
                someSubchannel.requestConnection()
              case None => throw new NullPointerException("subchannel") //scalafix:ok
            }
        }
      })
    val removedSubchannels: util.ArrayList[Subchannel] = new util.ArrayList[Subchannel]()
    removedAddrs.foreach(addressGroup => {
      removedSubchannels.add(subChannels.remove(addressGroup))
      ()
    })
    updateBalancingState()
    removedSubchannels.forEach((removedSubchannel: Subchannel) => shutdownSubchannel(removedSubchannel))
  }

  override def handleNameResolutionError(error: Status): Unit =
    eitherPicker.get() match {
      case Left(readyPicker) => helper.updateBalancingState(TRANSIENT_FAILURE, readyPicker)
      case Right(_)          => helper.updateBalancingState(TRANSIENT_FAILURE, EmptyPicker(error))
    }

  private def processSubchannelState(subchannel: Subchannel, connectivityStateInfo: ConnectivityStateInfo): Unit = {
    if (subChannels.get(stripAttrs(subchannel.getAddresses)) === subchannel) {
      if (connectivityStateInfo.getState === IDLE) {
        subchannel.requestConnection()
      }
      val subchannelStateRef: AtomicReference[ConnectivityStateInfo] = getSubchannelStateInfoRef(
        subchannel
      )
      if (!(subchannelStateRef.get.getState === TRANSIENT_FAILURE && (connectivityStateInfo.getState
            === CONNECTING || connectivityStateInfo.getState === IDLE))) {
        subchannelStateRef
          .getAndUpdate((t: ConnectivityStateInfo) => {
            val _ = t
            connectivityStateInfo
          })
        updateBalancingState()
      }
    }
    ()
  }

  private def shutdownSubchannel(subchannel: Subchannel): Unit = {
    subchannel.shutdown()
    getSubchannelStateInfoRef(subchannel)
      .getAndUpdate((t: ConnectivityStateInfo) => {
        val _ = t
        ConnectivityStateInfo.forNonError(SHUTDOWN)
      })
    ()
  }

  override def shutdown(): Unit =
    getSubchannels.forEach(subchannel => shutdownSubchannel(subchannel))

  private def updateBalancingState(): Unit = {
    val activeList: List[LoadBalancer.Subchannel] = filterNonFailingSubchannels(getSubchannels).asScala.toList
    // No READY subchannels, determine aggregate state and error status
    if (activeList.isEmpty || activeList.size < subChannels.size()) {
      val isConnecting: AtomicBoolean = new AtomicBoolean(false)
      val aggStatus: AtomicReference[Status] = new AtomicReference[Status](EMPTY_OK)
      subChannels
        .values
        .forEach(subchannel => {
          val stateInfo: ConnectivityStateInfo = getSubchannelStateInfoRef(subchannel).get()
          // This subchannel IDLE is not because of channel IDLE_TIMEOUT,
          // in which case LB is already shutdown.
          // RRLB will request connection immediately on subchannel IDLE.
          if ((stateInfo.getState === CONNECTING) || (stateInfo.getState === IDLE)) isConnecting.getAndSet(true)
          if ((aggStatus.get() === EMPTY_OK) || !aggStatus.get.isOk) aggStatus.updateAndGet((t: Status) => t)
          ()
        })
      // Set the connectivity state based on whether subchannel is connecting or not.
      val state: ConnectivityState = if (isConnecting.get) CONNECTING else TRANSIENT_FAILURE
      updateBalancingState(
        state,
        // If all subchannels are TRANSIENT_FAILURE, return the Status associated with
        // an arbitrary subchannel, otherwise return OK.
        Right(EmptyPicker(aggStatus.get()))
      )
    } else { // Pick subchannel based on rules of ready picker.
      updateBalancingState(READY, Left(readyPickerFactory(activeList)))
    }
  }

  private def updateBalancingState(state: ConnectivityState, picker: Either[ReadyPicker, EmptyPicker]): Unit = {
    val isNotCurrentState: Boolean = state /== currentState.get()
    val isPickerEquivalent: Boolean = WookieeLoadBalancer.isEquivalentTo(eitherPicker.get, picker)
    if (isNotCurrentState || !isPickerEquivalent) {
      currentState.getAndUpdate((t: ConnectivityState) => t)
      picker match {
        case Left(readyPicker) =>
          helper.updateBalancingState(state, readyPicker)
          eitherPicker.getAndUpdate((t: Either[ReadyPicker, EmptyPicker]) => {
            val _ = t
            Left(readyPicker)
          })
        case Right(emptyPicker) =>
          helper.updateBalancingState(state, emptyPicker)
          eitherPicker.getAndUpdate((t: Either[ReadyPicker, EmptyPicker]) => {
            val _ = t
            Right(emptyPicker)
          })
      }
    }
    ()
  }

  /**
    * Filters out non-ready subchannels.
    */
  private def filterNonFailingSubchannels(subchannels: util.Collection[LoadBalancer.Subchannel]) = {
    val readySubchannels = new util.ArrayList[LoadBalancer.Subchannel](subchannels.size)
    subchannels.forEach(subchannel => {
      if (isReady(subchannel)) {
        readySubchannels.add(subchannel)
      }
      ()
    })
    readySubchannels
  }

  private def stripAttrs(eag: EquivalentAddressGroup): EquivalentAddressGroup =
    new EquivalentAddressGroup(eag.getAddresses)

  private[wookiee] def getSubchannels: util.Collection[Subchannel] = subChannels.values

  // Gets subchannel's state info reference, if reference is null a null pointer exception is thrown.
  // Otherwise, the reference is returned to the calling method.
  private def getSubchannelStateInfoRef(subchannel: LoadBalancer.Subchannel): AtomicReference[ConnectivityStateInfo] = {
    val maybeStateVal: Option[AtomicReference[ConnectivityStateInfo]] = Option(subchannel.getAttributes.get(STATE_INFO))
    maybeStateVal match {
      case Some(stateVal) => stateVal
      case _ =>
        throw new NullPointerException("STATE_INFO") //scalafix:ok
    }
  }

  // package-private to avoid synthetic access
  private[wookiee] def isReady(subchannel: Subchannel) = {
    val stateVal: AtomicReference[ConnectivityStateInfo] = getSubchannelStateInfoRef(subchannel)
    stateVal.get.getState === READY
  }

}

object WookieeLoadBalancer {
  sealed trait ConnectionState
  case object IDLE extends ConnectionState
  case object CONNECTING extends ConnectionState
  case object READY extends ConnectionState
  case object TRANSIENT_FAILURE extends ConnectionState
  val maybeList: AtomicReference[Option[List[Subchannel]]] = new AtomicReference[Option[List[Subchannel]]]()

  def isEquivalentTo(
      currentPicker: Either[ReadyPicker, EmptyPicker],
      picker: Either[ReadyPicker, EmptyPicker]
  ): Boolean =
    currentPicker match {
      case Left(currentReadyPicker) =>
        picker match {
          case Left(readyPicker) =>
            (currentReadyPicker === readyPicker) || (currentReadyPicker
              .list
              .size === readyPicker.list.size && new util.HashSet[Subchannel](currentReadyPicker.list.asJava)
              .containsAll(readyPicker.list.asJava))
          case Right(_) => false
        }
      case Right(currentEmptyPicker) =>
        picker match {
          case Left(_) => false
          case Right(emptyPicker) =>
            Objects.equal(currentEmptyPicker.status, emptyPicker.status) || (currentEmptyPicker
              .status
              .isOk && emptyPicker
              .status
              .isOk)
        }
    }

}
