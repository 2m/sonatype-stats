scalaVersion := "2.12.6"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream" % "2.6.11",
  "com.typesafe.akka" %% "akka-http-core" % "10.2.2",
  "com.lightbend.akka" %% "akka-stream-alpakka-csv" % "2.0.2",
)

scalafmtOnCompile := true
