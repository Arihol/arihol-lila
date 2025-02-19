package lila.opening

import chess.Speed
import play.api.data._
import play.api.data.Forms._
import play.api.mvc.RequestHeader

import lila.common.Form.numberIn
import lila.common.Iso
import lila.common.LilaCookie

case class OpeningConfig(ratings: Set[Int], speeds: Set[Speed]) {

  override def toString = s"Speed: $showSpeeds; Rating: $showRatings"

  def showRatings: String =
    showContiguous(ratings.toList.sorted.map(_.toString), OpeningConfig.contiguousRatings)
  def showSpeeds: String =
    showContiguous(speeds.toList.sorted.map(_.name), OpeningConfig.contiguousSpeeds)

  // shows contiguous rating ranges, or distinct ratings
  // 1600 to 2200
  // or 1600, 2000, 2200
  private def showContiguous(list: List[String], reference: String): String = list match {
    case Nil          => "All"
    case List(single) => single
    case first :: rest =>
      val many = first :: rest
      val hash = many.mkString(",")
      if (reference == hash) "All"
      else if (reference contains hash) s"$first to ${rest.lastOption | first}"
      else many mkString ", "
  }
}

final class OpeningConfigStore(baker: LilaCookie) {
  import OpeningConfig._

  def read(implicit req: RequestHeader): OpeningConfig =
    req.cookies.get(cookie.name).map(_.value).flatMap(cookie.read) | OpeningConfig.default

  def write(config: OpeningConfig)(implicit req: RequestHeader) = baker.cookie(
    cookie.name,
    cookie.write(config),
    maxAge = cookie.maxAge.some,
    httpOnly = false.some
  )
}

object OpeningConfig {

  val allRatings        = List[Int](1600, 1800, 2000, 2200, 2500)
  val contiguousRatings = allRatings.mkString(",")

  val allSpeeds =
    List[Speed](
      Speed.UltraBullet,
      Speed.Bullet,
      Speed.Blitz,
      Speed.Rapid,
      Speed.Classical,
      Speed.Correspondence
    )
  val contiguousSpeeds = allSpeeds.map(_.name).mkString(",")

  val default = OpeningConfig(allRatings.drop(1).toSet, allSpeeds.drop(1).toSet)

  private[opening] object cookie {
    val name     = "opening"
    val maxAge   = 31536000 // one year
    val valueSep = '/'
    val fieldSep = '!'

    def read(str: String): Option[OpeningConfig] = str split fieldSep match {
      case Array(r, s) =>
        OpeningConfig(
          ratings = r.split(valueSep).flatMap(_.toIntOption).toSet,
          speeds = s.split(valueSep).flatMap(_.toIntOption).flatMap(Speed.byId.get).toSet
        ).some
      case _ => none
    }

    def write(cfg: OpeningConfig): String = List(
      cfg.ratings.mkString(valueSep.toString),
      cfg.speeds.map(_.id).mkString(valueSep.toString)
    ) mkString fieldSep.toString
  }

  val form = Form(
    mapping(
      "ratings" -> set(numberIn(allRatings)),
      "speeds"  -> set(numberIn(allSpeeds.map(_.id)).transform[Speed](s => Speed(s).get, _.id))
    )(OpeningConfig.apply)(OpeningConfig.unapply)
  )

  val ratingChoices = allRatings zip allRatings.map(_.toString)
  val speedChoices  = allSpeeds.map(_.id) zip allSpeeds.map(_.name)
}
