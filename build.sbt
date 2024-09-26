scalaVersion := "2.13.15"

libraryDependencies ++= Seq(
  "com.typesafe.akka"  %% "akka-stream"             % "2.6.20",
  "com.typesafe.akka"  %% "akka-http-core"          % "10.2.10",
  "com.lightbend.akka" %% "akka-stream-alpakka-csv" % "4.0.0"
)

scalafmtOnCompile := true

ThisBuild / scalafixDependencies ++= Seq(
  "com.nequissimus" %% "sort-imports" % "0.6.1"
)
