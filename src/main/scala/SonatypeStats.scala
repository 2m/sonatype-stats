package sonatypestats

import java.nio.file.Paths
import java.time.YearMonth
import java.time.format.DateTimeFormatter

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, Uri}
import akka.stream.alpakka.csv.scaladsl.{CsvFormatting, CsvParsing}
import akka.stream.scaladsl.{FileIO, Flow, Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.Config

import scala.collection.JavaConverters._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

object SonatypeStats {

  final val YearMonthFormat = DateTimeFormatter.ofPattern("yyyyMM")

  def main(args: Array[String]): Unit = {

    implicit val sys = ActorSystem("SonatypeStats")
    implicit val mat = ActorMaterializer()
    import sys.dispatcher

    try {
      val config = sys.settings.config.getConfig("sonatype-stats")
      val complete = for {
        stats <- getStats(config)
        io <- printStats(stats, config.getStringList("scala-major-versions").size)
      } yield ()

      Await.result(complete, 10.seconds)
    } finally {
      sys.terminate()
    }
  }

  private def printStats(results: Seq[Stats], numberOfScalaVersions: Int)(implicit mat: Materializer) = {
    val header = Seq("Artifact", "Scala Version") ++ results.head.downloads.map(_._1.format(YearMonthFormat))
    val withScalaVersionTotals = results
      .grouped(numberOfScalaVersions)
      .flatMap { perVersion =>
        val allVersions = perVersion.drop(1).foldLeft(perVersion.head)(_ + _)
        perVersion ++ Seq(allVersions, Stats.empty)
      }
    val values: Seq[Seq[String]] =
      withScalaVersionTotals
        .map(s => Seq(s.artifact.name, s.scalaVersion) ++ s.downloads.map(_._2.toString))
        .toList

    Source
      .single(header)
      .concat(Source.apply(values))
      .via(CsvFormatting.format())
      .alsoTo(FileIO.toPath(Paths.get("./sonatype.csv")))
      .map(_.utf8String)
      .runForeach(print)
  }

  private def getStats(c: Config)(implicit sys: ActorSystem, mat: Materializer): Future[Seq[Stats]] = {

    import sys.dispatcher

    val fromMonth = YearMonth.parse(c.getString("from"), YearMonthFormat)
    val scalaVersions =
      c.getStringList("scala-major-versions").asScala.toIndexedSeq

    def reqFactory =
      (s: Stats) =>
        request(s.artifact.project,
                s.artifact.group,
                s.artifact.name + s.scalaPostfix,
                fromMonth,
                c.getInt("months"),
                c.getString("username"),
                c.getString("password"))

    val source = Source(getArtifacts(c))
      .via(artifactToStats(scalaVersions, fromMonth, reqFactory))

    source
      .runWith(Sink.seq)
  }

  private def artifactToStats(
      scalaVersions: Seq[String],
      from: YearMonth,
      reqFactory: Stats => HttpRequest
  )(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext): Flow[Artifact, Stats, NotUsed] =
    Flow[Artifact]
      .mapConcat(a => scalaVersions.map(Stats(a, _, Seq.empty)))
      .mapAsync(2)(s => getDownloads(reqFactory(s), from).map(downloads => s.copy(downloads = downloads)))

  private def getArtifacts(c: Config) = {
    val artifactConfig = c.getConfig("artifacts")
    for {
      projectId <- artifactConfig.shallowKeys()
      project = artifactConfig.getConfig(projectId)
      groupId <- project.keys()
      name <- project.getStringList(groupId).asScala
    } yield Artifact(projectId, groupId.replaceAll('"'.toString, ""), name)
  }

  private def request(project: String,
                      group: String,
                      name: String,
                      from: YearMonth,
                      months: Int,
                      username: String,
                      password: String) = {
    val requestQuery = Query("p" -> project,
                             "t" -> "raw",
                             "g" -> group,
                             "a" -> name,
                             "from" -> from.format(YearMonthFormat),
                             "nom" -> months.toString)
    val requestUri =
      Uri("https://oss.sonatype.org/service/local/stats/timeline_csv")
        .withQuery(requestQuery)
    val request = HttpRequest(HttpMethods.GET, requestUri)
      .addHeader(Authorization(BasicHttpCredentials(username, password)))
    request
  }

  private def getDownloads(request: HttpRequest,
                           from: YearMonth)(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext) =
    Http()
      .singleRequest(request)
      .flatMap(
        _.entity.dataBytes
          .via(CsvParsing.lineScanner())
          .log("line")
          .map(_.head.utf8String.toInt)
          .runWith(Sink.seq)
      )
      .map(_.zipWithIndex.map {
        case (hits, idx) => from.plusMonths(idx) -> hits
      })

  case class Artifact(project: String, group: String, name: String)

  case class Stats(artifact: Artifact, scalaVersion: String, downloads: Seq[(YearMonth, Int)]) {
    val scalaPostfix = s"_$scalaVersion"

    def +(other: Stats): Stats = {
      if (this.artifact != other.artifact)
        throw new RuntimeException(s"mismatching artifacts $artifact vs. ${other.artifact}")
      Stats(
        this.artifact,
        "",
        downloads = this.downloads.zip(other.downloads).map {
          case ((thisYearMonth, thisNum), (otherYearMonth, otherNum)) =>
            if (thisYearMonth == otherYearMonth) (thisYearMonth, thisNum + otherNum)
            else throw new RuntimeException(s"mismatching months for $artifact: $thisYearMonth $otherYearMonth")
        }
      )
    }
  }

  object Stats {
    val empty = Stats(Artifact("", "", ""), "", Seq.empty)
  }

  implicit class ConfigOps(c: Config) {
    def shallowKeys(): Seq[String] = c.root().entrySet().asScala.toIndexedSeq.map(_.getKey)

    def keys(): Seq[String] = c.entrySet().asScala.toIndexedSeq.map(_.getKey)
  }

}
