scalaVersion := "2.12.6"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream" % "2.6.4",
  "com.typesafe.akka" %% "akka-http-core" % "10.1.11",
  "com.lightbend.akka" %% "akka-stream-alpakka-csv" % "2.0.0",
)

scalafmtOnCompile := true
