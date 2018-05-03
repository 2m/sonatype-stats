package sonatypestats

import java.time.YearMonth
import java.time.format.DateTimeFormatter

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, Uri}
import akka.stream.alpakka.csv.scaladsl.CsvParsing
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

    try {
      val future = getAndPrintStats(sys.settings.config.getConfig("sonatype-stats"))
      val result = Await.result(future, 10.seconds)

      val months = result.head.downloads.keys.map(_.format(YearMonthFormat)).mkString("|", "|", "|")
      println(s"|$months")

      result.sortBy(s => s.artifact.group + ":" + s.artifact.name).foreach { s =>
        val downloads = s.downloads.values.mkString("|", "|", "|")
        println(s"|${s.artifact.name}${s.scalaPostfix}$downloads")
      }
    } finally {
      sys.terminate()
    }
  }

  private def getAndPrintStats(c: Config)(implicit sys: ActorSystem, mat: Materializer): Future[Seq[Stats]] = {

    import sys.dispatcher

    val fromMonth = YearMonth.parse(c.getString("from"), YearMonthFormat)
    val scalaPostfixes =
      c.getStringList("scala-cross-postfix").asScala.toIndexedSeq

    def reqFactory =
      (s: Stats) =>
        request(s.artifact.group,
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
    val artifactGroups = artifactConfig.entrySet().asScala.map(_.getKey)
    artifactGroups
      .flatMap(g => artifactConfig.getStringList(g).asScala.map(Artifact(g.replaceAll('"'.toString, ""), _)))
      .toIndexedSeq
  }

  private def request(group: String, name: String, from: YearMonth, months: Int, username: String, password: String) = {
    val requestQuery = Query("p" -> "67234479532118",
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

  case class Artifact(group: String, name: String)
  case class Stats(artifact: Artifact, scalaPostfix: String, downloads: Map[YearMonth, Int])
}
