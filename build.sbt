scalaVersion := "2.12.6"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream" % "2.5.22",
  "com.typesafe.akka" %% "akka-http-core" % "10.1.8",
  "com.lightbend.akka" %% "akka-stream-alpakka-csv" % "0.20",
)

scalafmtOnCompile := true
