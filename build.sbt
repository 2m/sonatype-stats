scalaVersion := "2.13.6"

libraryDependencies ++= Seq(
  "com.typesafe.akka"  %% "akka-stream"             % "2.6.15",
  "com.typesafe.akka"  %% "akka-http-core"          % "10.2.5",
  "com.lightbend.akka" %% "akka-stream-alpakka-csv" % "3.0.2"
)

scalafmtOnCompile := true

ThisBuild / scalafixDependencies ++= Seq(
  "com.nequissimus" %% "sort-imports" % "0.5.5"
)
