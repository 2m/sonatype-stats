scalaVersion := "2.13.7"

libraryDependencies ++= Seq(
  "com.typesafe.akka"  %% "akka-stream"             % "2.6.18",
  "com.typesafe.akka"  %% "akka-http-core"          % "10.2.7",
  "com.lightbend.akka" %% "akka-stream-alpakka-csv" % "3.0.4"
)

scalafmtOnCompile := true

ThisBuild / scalafixDependencies ++= Seq(
  "com.nequissimus" %% "sort-imports" % "0.5.5"
)
