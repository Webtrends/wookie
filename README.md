# wookiee
* [wookiee-grpc](#wookiee-grpc)

# wookiee-grpc
## Install
wookiee-grpc is available for Scala 2.12 and 2.13. There are no plans to support scala 2.11 or lower.
```sbt
libraryDependencies += "com.oracle.infy" %% "wookiee-grpc" % "2.2.8"
```

## Setup ScalaPB
We use [ScalaPB](https://github.com/scalapb/ScalaPB) to generate source code from a `.proto` file. You can use
other plugins/code generators if you wish. wookiee-grpc will work as long as you have `io.grpc.ServerServiceDefinition`
for the server and something that accept `io.grpc.ManagedChannel` for the client.

Declare your gRPC service using proto3 syntax and save it in `src/main/protobuf/myService.proto`
```proto
syntax = "proto3";

package com.oracle.infy.wookiee;

message HelloRequest {
  string name = 1;
}

message HelloResponse {
  string resp = 1;
}

service MyService {
  rpc greet(HelloRequest) returns (HelloResponse) {}
}

```

Add ScalaPB plugin to `plugin.sbt` file
```sbt
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.6")
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.8"

```

Configure the project in `build.sbt` so that ScalaPB can generate code
```sbt
    Compile / PB.targets := Seq(
      scalapb.gen() -> (Compile / sourceManaged).value / "scalapb"
    ),
    libraryDependencies ++= Seq(
      "io.grpc" % "grpc-netty" % scalapb.compiler.Version.grpcJavaVersion,
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion
    )
  )

```

In the sbt shell, type `protocGenerate` to generate scala code based on the `.proto` file. ScalaPB will generate
code and put it under `target/scala-2.13/src_managed/main`.

## Using wookiee-grpc
After the code has been generated by ScalaPB, you can use wookiee-grpc for service discoverability and load balancing.

wookiee-grpc is written using functional concepts. One key concept is side-effect management/referential transparency.
We use cats-effect (https://typelevel.org/cats-effect/) internally.
If you want to use cats-effect, you can use the methods that return `IO[_]`. Otherwise, use the methods prefixed with `unsafe`.
When using `unsafe` methods, you are expected to handle any exceptions

### Imports
Add the following imports:
```sbt
import com.oracle.infy.wookiee.grpc.model.{Host, HostMetadata}
import com.oracle.infy.wookiee.grpc.settings._
import com.oracle.infy.wookiee.grpc._
import com.oracle.infy.wookiee.grpc.model.LoadBalancers._
import io.grpc._

```

### Creating a Server
```sbt
    val serverSettingsF: ServerSettings = ServerSettings(
      discoveryPath = zookeeperDiscoveryPath,
      serverServiceDefinition = ssd,
      // This is an optional arg. wookiee-grpc will try to resolve the address automatically.
      // If you are running this locally, its better to explicitly set the hostname
      host = Host(0, "localhost", 9091, HostMetadata(0, quarantined = false)),
      authSettings = None,
      sslServerSettings = None,
      bossExecutionContext = mainEC,
      workerExecutionContext = mainEC,
      applicationExecutionContext = mainEC,
      bossThreads = bossThreads,
      workerThreads = mainECParallelism,
      curatorFramework = curator
    )

    val serverF: Future[WookieeGrpcServer] = WookieeGrpcServer.start(serverSettingsF).unsafeToFuture()

```

### Creating a Client Channel
```sbt
    val wookieeGrpcChannel: WookieeGrpcChannel = WookieeGrpcChannel
      .of(
        ChannelSettings(
          serviceDiscoveryPath = zookeeperDiscoveryPath,
          eventLoopGroupExecutionContext = blockingEC,
          channelExecutionContext = mainEC,
          offloadExecutionContext = blockingEC,
          eventLoopGroupExecutionContextThreads = bossThreads,
//           Load Balancing Policy
//             One of:
//               RoundRobinPolicy
//               RoundRobinWeightedPolicy
//               RoundRobinHashedPolicy
          lbPolicy = RoundRobinPolicy,
          curatorFramework = curator,
          sslClientSettings = None,
          clientAuthSettings = None
        )
      )
      .unsafeRunSync()

    val stub: MyServiceGrpc.MyServiceStub = MyServiceGrpc.stub(wookieeGrpcChannel.managedChannel)

```

### Executing a gRPC Call
```sbt
    val gRPCResponseF: Future[HelloResponse] = for {
      server <- serverF
      resp <- stub
        .withInterceptors(new ClientInterceptor {
          override def interceptCall[ReqT, RespT](
              method: MethodDescriptor[ReqT, RespT],
              callOptions: CallOptions,
              next: Channel
          ): ClientCall[ReqT, RespT] = {
            next.newCall(
              method,
              // Set the WookieeGrpcChannel.hashKeyCallOption when using RoundRobinHashedPolicy
              callOptions.withOption(WookieeGrpcChannel.hashKeyCallOption, "Some hash")
            )
          }
        })
        .greet(HelloRequest("world!"))
      _ <- wookieeGrpcChannel.shutdown().unsafeToFuture()
      _ <- server.shutdown().unsafeToFuture()
    } yield resp

    println(Await.result(gRPCResponseF, Duration.Inf))
    curator.close()
    zkFake.close()
    ()

```

###Setting up load balancing methods in channel settings

There are three load balancing policies that ship with wookiee-grpc.
The load balancing policies are set up within the gRPC Channel Settings.

* **Round Robin**
  
  A simple round robin policy that alternates between hosts as calls are executed. It's fairly simplistic.


* **Round Robin Weighted**

  This load balancer takes server load into consideration and distributes calls to the server with the lowest
  current usage. If all loads are equivalent, it defaults to simple Round Robin behavior.


* **Round Robin Hashed**
  
  Provides "stickiness" for the gRPC host. If you want a particular host to serve the request for all the calls with a
  particular key, you can use this policy. For example, if you want a single server to service all requests that use
  the key "foo", you can set the `hashKeyCallOption` on every call. This will ensure that all gRPC calls using the same
  hash will be executed on the same server.

```sbt
    val gRPCResponseF: Future[HelloResponse] = for {
      server <- serverF
      resp <- stub
        .withInterceptors(new ClientInterceptor {
          override def interceptCall[ReqT, RespT](
              method: MethodDescriptor[ReqT, RespT],
              callOptions: CallOptions,
              next: Channel
          ): ClientCall[ReqT, RespT] = {
            next.newCall(
              method,
              // Set the WookieeGrpcChannel.hashKeyCallOption when using RoundRobinHashedPolicy
              callOptions.withOption(WookieeGrpcChannel.hashKeyCallOption, "Some hash")
            )
          }
        })
        .greet(HelloRequest("world!"))
      _ <- wookieeGrpcChannel.shutdown().unsafeToFuture()
      _ <- server.shutdown().unsafeToFuture()
    } yield resp

    println(Await.result(gRPCResponseF, Duration.Inf))
    curator.close()
    zkFake.close()
    ()

```

  
