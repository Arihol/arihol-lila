package lila.tv

import akka.pattern.{ ask => actorAsk }
import play.api.libs.json.Json
import scala.concurrent.duration._
import scala.concurrent.Promise

import lila.common.{ Bus, LightUser }
import lila.game.{ Game, Pov }
import lila.hub.SyncActor

final private[tv] class TvSyncActor(
    renderer: lila.hub.actors.Renderer,
    lightUserApi: lila.user.LightUserApi,
    recentTvGames: lila.round.RecentTvGames,
    gameProxyRepo: lila.round.GameProxyRepo,
    rematches: lila.game.Rematches
)(implicit ec: scala.concurrent.ExecutionContext)
    extends SyncActor {

  import TvSyncActor._

  Bus.subscribe(this, "startGame")

  private val channelTroupers: Map[Tv.Channel, ChannelSyncActor] = Tv.Channel.all.map { c =>
    c -> new ChannelSyncActor(
      c,
      onSelect = this.!,
      gameProxyRepo.game,
      rematches.getAcceptedId,
      lightUserApi.sync
    )
  }.toMap

  private var channelChampions = Map[Tv.Channel, Tv.Champion]()

  private def forward[A](channel: Tv.Channel, msg: Any) =
    channelTroupers get channel foreach { _ ! msg }

  protected val process: SyncActor.Receive = {

    case GetGameId(channel, promise) =>
      forward(channel, ChannelSyncActor.GetGameId(promise))

    case GetGameIdAndHistory(channel, promise) =>
      forward(channel, ChannelSyncActor.GetGameIdAndHistory(promise))

    case GetGameIds(channel, max, promise) =>
      forward(channel, ChannelSyncActor.GetGameIds(max, promise))

    case GetReplacementGameId(channel, oldId, exclude, promise) =>
      forward(channel, ChannelSyncActor.GetReplacementGameId(oldId, exclude, promise))

    case GetChampions(promise) => promise success Tv.Champions(channelChampions)

    case lila.game.actorApi.StartGame(g) =>
      if (g.hasClock) {
        val candidate = Tv.Candidate(g, g.userIds.exists(lightUserApi.isBotSync))
        channelTroupers collect {
          case (chan, trouper) if chan filter candidate => trouper
        } foreach (_ addCandidate g)
      }

    case s @ TvSyncActor.Select => channelTroupers.foreach(_._2 ! s)

    case Selected(channel, game) =>
      import lila.socket.Socket.makeMessage
      import cats.implicits._
      val player = game.players.sortBy { p =>
        ~p.rating + ~p.userId.flatMap(lightUserApi.sync).flatMap(_.title).flatMap(Tv.titleScores.get)
      }.lastOption | game.player(game.naturalOrientation)
      val user = player.userId flatMap lightUserApi.sync
      (user, player.rating) mapN { (u, r) =>
        channelChampions += (channel -> Tv.Champion(u, r, game.id))
      }
      recentTvGames.put(game)
      val data = Json.obj(
        "channel" -> channel.key,
        "id"      -> game.id,
        "color"   -> game.naturalOrientation.name,
        "player" -> user.map { u =>
          Json.obj(
            "name"   -> u.name,
            "title"  -> u.title,
            "rating" -> player.rating
          )
        }
      )
      Bus.publish(lila.hub.actorApi.tv.TvSelect(game.id, game.speed, data), "tvSelect")
      if (channel == Tv.Channel.Best) {
        implicit def timeout = makeTimeout(100 millis)
        actorAsk(renderer.actor, actorApi.RenderFeaturedJs(game)) foreach { case html: String =>
          val pov = Pov naturalOrientation game
          val event = lila.round.ChangeFeatured(
            pov,
            makeMessage(
              "featured",
              Json.obj(
                "html"  -> html,
                "color" -> pov.color.name,
                "id"    -> game.id
              )
            )
          )
          Bus.publish(event, "changeFeaturedGame")
        }
      }
  }
}

private[tv] object TvSyncActor {

  case class GetGameId(channel: Tv.Channel, promise: Promise[Option[Game.ID]])
  case class GetGameIds(channel: Tv.Channel, max: Int, promise: Promise[List[Game.ID]])
  case class GetReplacementGameId(
      channel: Tv.Channel,
      oldId: Game.ID,
      exclude: List[Game.ID],
      promise: Promise[Option[Game.ID]]
  )

  case class GetGameIdAndHistory(channel: Tv.Channel, promise: Promise[ChannelSyncActor.GameIdAndHistory])

  case object Select
  case class Selected(channel: Tv.Channel, game: Game)

  case class GetChampions(promise: Promise[Tv.Champions])
}
