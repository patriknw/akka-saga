package com.example

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.util.Timeout
import com.example.BankAccountSaga.StartBankAccountSaga

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Test friendly abstract application.
  * @param system ActorSystem
  */
abstract class BaseApp(implicit val system: ActorSystem) {

  import BankAccountCommands._

  // Get config entry if available (for testing), will be set to 0 for runtime.
  var httpServerPort: Int = 0
  val configuredHttpPort = system.settings.config.getInt("akka-saga.http-port")
  if (configuredHttpPort == 0)
    httpServerPort = 8080
  else
    httpServerPort = configuredHttpPort

  // Set up bank account cluster sharding
  val bankAccountEntityIdExtractor: ShardRegion.ExtractEntityId = {
    case cmd: BankAccountCommand => (cmd.accountNumber, cmd)
  }
  val bankAccountShardCount: Int = system.settings.config.getInt("akka-saga.bank-account.shard-count")
  val bankAccountShardIdExtractor: ShardRegion.ExtractShardId = {
    case cmd: BankAccountCommand => (cmd.accountNumber.hashCode % bankAccountShardCount).toString
    case ShardRegion.StartEntity(id) ⇒
      // StartEntity is used by remembering entities feature
      (id.hashCode % bankAccountShardCount).toString
  }
  val bankAccountRegion: ActorRef = ClusterSharding(system).start(
    typeName = "bank-account",
    entityProps = BankAccount.props(),
    settings = ClusterShardingSettings(system),
    extractEntityId = bankAccountEntityIdExtractor,
    extractShardId = bankAccountShardIdExtractor
  )

  // Set up bank account saga cluster sharding
  val bankAccountSagaEntityIdExtractor: ShardRegion.ExtractEntityId = {
    case cmd: StartBankAccountSaga => (cmd.transactionId, cmd)
  }
  val bankAccountSagaShardCount: Int = system.settings.config.getInt("akka-saga.bank-account-saga.shard-count")
  val bankAccountSagaShardIdExtractor: ShardRegion.ExtractShardId = {
    case cmd: StartBankAccountSaga => (cmd.transactionId.hashCode % bankAccountSagaShardCount).toString
    case ShardRegion.StartEntity(id) ⇒
      (id.hashCode % bankAccountSagaShardCount).toString
  }
  val bankAccountSagaRegion: ActorRef = ClusterSharding(system).start(
    typeName = "bank-account-saga",
    entityProps = BankAccountSaga.props(bankAccountRegion),
    settings = ClusterShardingSettings(system),
    extractEntityId = bankAccountSagaEntityIdExtractor,
    extractShardId = bankAccountShardIdExtractor
  )

  val httpServerHost: String = "0.0.0.0"
  implicit val timeout: Timeout = Timeout(5.seconds)
  val clusterListener = system.actorOf(Props(new SimpleClusterListener))
  val httpServer: BankAccountHttpServer = createHttpServer()

  /**
    * Main function for running the app.
    */
  protected def run(): Unit = {
    createHttpServer()
    Await.ready(system.whenTerminated, Duration.Inf)
  }

  /**
    * Create Akka Http Server
    *
    * @return BankAccountHttpServer
    */
  private def createHttpServer(): BankAccountHttpServer =
    new BankAccountHttpServer(
      httpServerHost,
      httpServerPort,
      bankAccountRegion,
      bankAccountSagaRegion,
      clusterListener
    )(system, timeout)
}
