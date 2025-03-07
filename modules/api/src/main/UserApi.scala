package lila.api

import play.api.libs.json.*

import lila.common.config.*
import lila.user.{ Trophy, Me, User }
import lila.rating.{ PerfType, UserRankMap }
import play.api.i18n.Lang
import lila.common.Json.given
import lila.security.Granter

final class UserApi(
    jsonView: lila.user.JsonView,
    relationApi: lila.relation.RelationApi,
    bookmarkApi: lila.bookmark.BookmarkApi,
    crosstableApi: lila.game.CrosstableApi,
    gameCache: lila.game.Cached,
    userApi: lila.user.UserApi,
    userCache: lila.user.Cached,
    prefApi: lila.pref.PrefApi,
    liveStreamApi: lila.streamer.LiveStreamApi,
    gameProxyRepo: lila.round.GameProxyRepo,
    trophyApi: lila.user.TrophyApi,
    shieldApi: lila.tournament.TournamentShieldApi,
    revolutionApi: lila.tournament.RevolutionApi,
    net: NetConfig
)(using Executor):

  def one(u: User.WithPerfs, joinedAt: Option[Instant] = None): JsObject = {
    addStreaming(jsonView.full(u.user, u.perfs.some, withProfile = true), u.id) ++
      Json.obj("url" -> makeUrl(s"@/${u.username}")) // for app BC
  }.add("joinedTeamAt", joinedAt)

  def extended(
      username: UserStr,
      withFollows: Boolean,
      withTrophies: Boolean
  )(using Option[Me], Lang): Fu[Option[JsObject]] =
    userApi withPerfs username flatMapz {
      extended(_, withFollows, withTrophies) dmap some
    }

  def extended(
      u: User | User.WithPerfs,
      withFollows: Boolean,
      withTrophies: Boolean
  )(using as: Option[Me], lang: Lang): Fu[JsObject] =
    u.match
      case u: User           => userApi.withPerfs(u)
      case u: User.WithPerfs => fuccess(u)
    .flatMap: u =>
        if u.enabled.no
        then fuccess(jsonView disabled u.light)
        else
          gameProxyRepo.urgentGames(u).dmap(_.headOption) zip
            as.filter(u !=).so(me => crosstableApi.nbGames(me.userId, u.id)) zip
            withFollows.soFu(relationApi.countFollowing(u.id)) zip
            as.isDefined.so(prefApi followable u.id) zip
            as.map(_.userId).so(relationApi.fetchRelation(_, u.id)) zip
            as.map(_.userId).so(relationApi.fetchFollows(u.id, _)) zip
            bookmarkApi.countByUser(u.user) zip
            gameCache.nbPlaying(u.id) zip
            gameCache.nbImportedBy(u.id) zip
            withTrophies.soFu(getTrophiesAndAwards(u.user)) map {
            // format: off
              case (((((((((gameOption,nbGamesWithMe),following),followable),
                relation),isFollowed),nbBookmarks),nbPlaying),nbImported),trophiesAndAwards)=>
              // format: on
                jsonView.full(u.user, u.perfs.some, withProfile = true) ++ {
                  Json
                    .obj(
                      "url"     -> makeUrl(s"@/${u.username}"), // for app BC
                      "playing" -> gameOption.map(g => makeUrl(s"${g.gameId}/${g.color.name}")),
                      "count" -> Json.obj(
                        "all"      -> u.count.game,
                        "rated"    -> u.count.rated,
                        "ai"       -> u.count.ai,
                        "draw"     -> u.count.draw,
                        "drawH"    -> u.count.drawH,
                        "loss"     -> u.count.loss,
                        "lossH"    -> u.count.lossH,
                        "win"      -> u.count.win,
                        "winH"     -> u.count.winH,
                        "bookmark" -> nbBookmarks,
                        "playing"  -> nbPlaying,
                        "import"   -> nbImported,
                        "me"       -> nbGamesWithMe
                      )
                    )
                    .add("streaming", liveStreamApi.isStreaming(u.id))
                    .add("nbFollowing", following)
                    .add("nbFollowers", withFollows.option(0))
                    .add("trophies", trophiesAndAwards ifFalse u.lame map trophiesJson) ++
                    as.isDefined.so:
                      Json.obj(
                        "followable" -> followable,
                        "following"  -> relation.has(true),
                        "blocking"   -> relation.has(false),
                        "followsYou" -> isFollowed
                      )

                }.noNull
            }

  def getTrophiesAndAwards(u: User) =
    trophyApi.findByUser(u) zip shieldApi.active(u) zip revolutionApi.active(u) map {
      case ((trophies, shields), revols) =>
        val roleTrophies = trophyApi.roleBasedTrophies(
          u,
          Granter.of(_.PublicMod)(u),
          Granter.of(_.Developer)(u),
          Granter.of(_.Verified)(u),
          Granter.of(_.ContentTeam)(u)
        )
        UserApi.TrophiesAndAwards(userCache.rankingsOf(u.id), trophies ::: roleTrophies, shields, revols)
    }

  private def trophiesJson(all: UserApi.TrophiesAndAwards)(using Lang): JsArray =
    JsArray {
      all.ranks.toList.sortBy(_._2).collect {
        case (perf, rank) if rank == 1   => perfTopTrophy(perf, 1, "Champion")
        case (perf, rank) if rank <= 10  => perfTopTrophy(perf, 10, "Top 10")
        case (perf, rank) if rank <= 50  => perfTopTrophy(perf, 50, "Top 50")
        case (perf, rank) if rank <= 100 => perfTopTrophy(perf, 10, "Top 100")
      } ::: all.trophies.map { t =>
        Json
          .obj(
            "type" -> t.kind._id,
            "name" -> t.kind.name,
            "date" -> t.date
          )
          .add("icon" -> t.kind.icon)
          .add("url" -> t.anyUrl)
      }
    }

  private def perfTopTrophy(perf: PerfType, top: Int, name: String)(using Lang) = Json.obj(
    "type" -> "perfTop",
    "perf" -> perf.key,
    "top"  -> top,
    "name" -> s"${perf.trans} $name"
  )

  private def addStreaming(js: JsObject, id: UserId) =
    js.add("streaming", liveStreamApi.isStreaming(id))

  private def makeUrl(path: String): String = s"${net.baseUrl}/$path"

object UserApi:
  case class TrophiesAndAwards(
      ranks: UserRankMap,
      trophies: List[Trophy],
      shields: List[lila.tournament.TournamentShield.Award],
      revolutions: List[lila.tournament.Revolution.Award]
  ):
    def countTrophiesAndPerfCups = trophies.size + ranks.count(_._2 <= 100)
