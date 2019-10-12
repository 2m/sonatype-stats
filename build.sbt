scalaVersion := "2.12.6"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream" % "2.5.25",
  "com.typesafe.akka" %% "akka-http-core" % "10.1.10",
  "com.lightbend.akka" %% "akka-stream-alpakka-csv" % "1.1.2",
)

scalafmtOnCompile := true
