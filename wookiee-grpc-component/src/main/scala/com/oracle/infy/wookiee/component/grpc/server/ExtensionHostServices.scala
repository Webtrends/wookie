package com.oracle.infy.wookiee.component.grpc.server

import cats.effect.{Blocker, ContextShift, IO}
import com.oracle.infy.wookiee.component.grpc.GrpcManager
import com.oracle.infy.wookiee.grpc.WookieeGrpcUtils
import com.oracle.infy.wookiee.logging.LoggingAdapter
import com.oracle.infy.wookiee.utils.{ConfigUtil, ThreadUtil}
import com.typesafe.config.Config
import org.apache.curator.framework.CuratorFramework

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Try

trait ExtensionHostServices extends LoggingAdapter {

  lazy val bossThreads: Int =
    ConfigUtil.getConfigAtEitherLevel(s"${GrpcManager.ComponentName}.grpc.boss-threads", hostConfig.getInt) // scalafix:ok
  lazy val workerThreads: Int =
    ConfigUtil.getConfigAtEitherLevel(s"${GrpcManager.ComponentName}.grpc.worker-threads", hostConfig.getInt) // scalafix:ok
  private lazy val mainEC: ExecutionContext = ThreadUtil.createEC(getClass.getSimpleName + "-main") // scalafix:ok
  private lazy val blockingEC: ExecutionContext = ThreadUtil.createEC(getClass.getSimpleName + "-blocking") // scalafix:ok

  private lazy val curatorConnectString: String =
    Try(ConfigUtil.getConfigAtEitherLevel("zookeeper-config.connect-string", hostConfig.getString)).getOrElse(
      ConfigUtil.getConfigAtEitherLevel("wookiee-zookeeper.quorum", hostConfig.getString)
    ) // scalafix:ok
  private lazy val curator: CuratorFramework =
    createCurator(curatorConnectString, mainEC, blockingEC) // scalafix:ok

  def hostConfig: Config

  def getCurator: CuratorFramework = curator

  protected def createCurator(
      connectionString: String,
      mainEc: ExecutionContext,
      blockingEc: ExecutionContext
  ): CuratorFramework = {

    implicit val cs: ContextShift[IO] = IO.contextShift(mainEc)
    implicit val blocker: Blocker = Blocker.liftExecutionContext(blockingEc)

    log.info("Creating and starting curator framework")

    val curatorIO = for {
      curator <- WookieeGrpcUtils
        .createCurator(
          connectionString,
          10.seconds,
          blocker.blockingContext
        )

      _ = curator.start()
      _ = log.info(s"Started curator framework to '$connectionString'")

    } yield curator

    curatorIO.unsafeRunSync()
  }
}
