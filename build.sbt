scalaVersion := "2.12.6"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream" % "2.5.21",
  "com.typesafe.akka" %% "akka-http-core" % "10.1.1",
  "com.lightbend.akka" %% "akka-stream-alpakka-csv" % "0.19",
)

scalafmtOnCompile := true
