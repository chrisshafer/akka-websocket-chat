name := "akka-websocket-chat"

version := "1.0"

scalaVersion := "2.11.6"

libraryDependencies ++= {
  val akkaStreamsV = "1.0-RC4"
  val sprayV = "1.3.2"
  Seq(
    "com.typesafe.akka" %% "akka-stream-experimental" % akkaStreamsV,
    "com.typesafe.akka" %% "akka-http-core-experimental" % akkaStreamsV,
    "io.spray"          %%  "spray-json"    % sprayV
  )
}