package sonatypestats

import java.time.YearMonth
import java.time.format.DateTimeFormatter

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, Uri}
import akka.stream.alpakka.csv.scaladsl.{CsvFormatting, CsvParsing}
import akka.stream.scaladsl.{Flow, Sink, Source}
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
      val complete = for {
        stats <- getStats(sys.settings.config.getConfig("sonatype-stats"))
        io <- printStats(stats)
      } yield ()

      Await.result(complete, 10.seconds)
    } finally {
      sys.terminate()
    }
  }

  private def printStats(resutls: Seq[Stats])(implicit mat: Materializer) = {
    val header = Seq("Artifact") ++ resutls.head.downloads.keys.map(_.format(YearMonthFormat))
    val values =
      resutls.map(s => Seq(s.artifact.name + s.scalaPostfix) ++ s.downloads.values.map(_.toString)).sortBy(_.head)

    Source
      .single(header)
      .concat(Source.apply(values))
      .via(CsvFormatting.format())
      .map(_.utf8String)
      .runForeach(print)
  }

  private def getStats(c: Config)(implicit sys: ActorSystem, mat: Materializer): Future[Seq[Stats]] = {

    import sys.dispatcher

    val fromMonth = YearMonth.parse(c.getString("from"), YearMonthFormat)
    val scalaPostfixes =
      c.getStringList("scala-cross-postfix").asScala.toIndexedSeq

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
      .via(artifactToStats(scalaPostfixes, fromMonth, reqFactory))

    source.runWith(Sink.seq)
  }

  private def artifactToStats(
      scalaPostfixes: Seq[String],
      from: YearMonth,
      reqFactory: Stats => HttpRequest
  )(implicit sys: ActorSystem, mat: Materializer, ec: ExecutionContext): Flow[Artifact, Stats, NotUsed] =
    Flow[Artifact]
      .mapConcat(a => scalaPostfixes.map(Stats(a, _, Map.empty)))
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
      .map(_.toMap)

  case class Artifact(project: String, group: String, name: String)
  case class Stats(artifact: Artifact, scalaPostfix: String, downloads: Map[YearMonth, Int])

  implicit class ConfigOps(c: Config) {
    def shallowKeys(): Seq[String] = c.root().entrySet().asScala.toIndexedSeq.map(_.getKey)
    def keys(): Seq[String] = c.entrySet().asScala.toIndexedSeq.map(_.getKey)
  }
}
