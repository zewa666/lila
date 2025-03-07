package lila.report

import com.softwaremill.macwire.*

import lila.common.{ Bus, Heapsort }
import lila.db.dsl.{ *, given }
import lila.game.GameRepo
import lila.memo.CacheApi.*
import lila.user.{ Me, User, UserRepo, UserApi }
import lila.common.config.Max

final class ReportApi(
    val coll: Coll,
    userRepo: UserRepo,
    userApi: UserApi,
    gameRepo: GameRepo,
    autoAnalysis: AutoAnalysis,
    securityApi: lila.security.SecurityApi,
    userLoginsApi: lila.security.UserLoginsApi,
    playbanApi: lila.playban.PlaybanApi,
    ircApi: lila.irc.IrcApi,
    isOnline: lila.socket.IsOnline,
    cacheApi: lila.memo.CacheApi,
    snoozer: lila.memo.Snoozer[Report.SnoozeKey],
    thresholds: Thresholds,
    domain: lila.common.config.NetDomain
)(using Executor, Scheduler):

  import BSONHandlers.given
  import Report.Candidate

  private lazy val accuracyOf = accuracy.apply

  private lazy val scorer = wire[ReportScore]

  def create(data: ReportSetup, reporter: Reporter): Funit =
    Reason(data.reason) so { reason =>
      getSuspect(data.user.id).flatMapz: suspect =>
        create:
          Report.Candidate(
            reporter,
            suspect,
            reason,
            data.text take 1000
          )
    }

  def create(c: Candidate, score: Report.Score => Report.Score = identity): Funit =
    (!c.reporter.user.marks.reportban && !isAlreadySlain(c)) so {
      scorer(c) map (_ withScore score) flatMap { case scored @ Candidate.Scored(candidate, _) =>
        coll
          .one[Report](
            $doc(
              "user"   -> candidate.suspect.user.id,
              "reason" -> candidate.reason,
              "open"   -> true
            )
          )
          .flatMap { prev =>
            val report = Report.make(scored, prev)
            lila.mon.mod.report.create(report.reason.key, scored.score.value.toInt).increment()
            if report.isRecentComm &&
              report.score.value >= thresholds.discord() &&
              prev.exists(_.score.value < thresholds.discord())
            then ircApi.commReportBurst(c.suspect.user)
            coll.update.one($id(report.id), report, upsert = true).void >>
              autoAnalysis(candidate).andDo:
                if report.isCheat then
                  Bus.publish(lila.hub.actorApi.report.CheatReportCreated(report.user), "cheatReport")
          } andDo
          maxScoreCache.invalidateUnit()
      }
    }

  def commFlag(reporter: Reporter, suspect: Suspect, resource: String, text: String) =
    create(
      Candidate(
        reporter,
        suspect,
        Reason.Comm,
        s"${Reason.Comm.flagText} $resource ${text take 140}"
      )
    )

  def autoCommFlag(suspectId: SuspectId, resource: String, text: String, critical: Boolean = false) =
    getLichessReporter flatMap { reporter =>
      getSuspect(suspectId.value) flatMapz { suspect =>
        create(
          Candidate(
            reporter,
            suspect,
            Reason.Comm,
            s"${Reason.Comm.flagText} $resource ${text take 140}"
          ),
          score = (_: Report.Score).map(_ * (if critical then 2 else 1))
        )
      }
    }

  private def isAlreadySlain(candidate: Candidate) =
    (candidate.isCheat && candidate.suspect.user.marks.engine) ||
      (candidate.isAutomatic && candidate.isOther && candidate.suspect.user.marks.troll) ||
      (candidate.isComm && candidate.suspect.user.marks.troll)

  def getMyMod(using me: Me.Id): Fu[Option[Mod]]         = userRepo byId me dmap2 Mod.apply
  def getMod[U: UserIdOf](u: U): Fu[Option[Mod]]         = userRepo byId u dmap2 Mod.apply
  def getSuspect[U: UserIdOf](u: U): Fu[Option[Suspect]] = userRepo byId u dmap2 Suspect.apply

  def getLichessMod: Fu[Mod] = userRepo.lichess dmap2 Mod.apply orFail "User lichess is missing"
  def getLichessReporter: Fu[Reporter] =
    getLichessMod.map: l =>
      Reporter(l.user)

  def autoAltPrintReport(userId: UserId): Funit =
    coll.exists(
      $doc(
        "user"   -> userId,
        "reason" -> Reason.AltPrint.key
      )
    ) flatMap {
      if _ then funit // only report once
      else
        getSuspect(userId) zip getLichessReporter flatMap {
          case (Some(suspect), reporter) =>
            create(
              Candidate(
                reporter = reporter,
                suspect = suspect,
                reason = Reason.AltPrint,
                text = "Shares print with suspicious accounts"
              )
            )
          case _ => funit
        }
    }

  def autoCheatReport(userId: UserId, text: String): Funit =
    getSuspect(userId) zip
      getLichessReporter zip
      findRecent(1, selectRecent(SuspectId(userId), Reason.Cheat)).map(_.flatMap(_.atoms.toList)) flatMap {
        case ((Some(suspect), reporter), atoms) if atoms.forall(_.byHuman) =>
          lila.mon.cheat.autoReport.increment()
          create(
            Candidate(
              reporter = reporter,
              suspect = suspect,
              reason = Reason.Cheat,
              text = text
            )
          )
        case _ => funit
      }

  def autoCheatDetectedReport(userId: UserId, cheatedGames: Int): Funit =
    userRepo.byId(userId) zip getLichessReporter flatMap {
      case (Some(user), reporter) if !user.marks.engine =>
        lila.mon.cheat.autoReport.increment()
        create(
          Candidate(
            reporter = reporter,
            suspect = Suspect(user),
            reason = Reason.Cheat,
            text = s"$cheatedGames cheat detected in the last 6 months; last one is correspondence"
          )
        )
      case _ => funit
    }

  def autoBotReport(userId: UserId, referer: Option[String], name: String): Funit =
    getSuspect(userId) zip getLichessReporter flatMap {
      case (Some(suspect), reporter) =>
        create(
          Candidate(
            reporter = reporter,
            suspect = suspect,
            reason = Reason.Cheat,
            text = s"""$name bot detected on ${referer | "?"}"""
          )
        )
      case _ => funit
    }

  def maybeAutoPlaybanReport(userId: UserId, minutes: Int): Funit =
    (minutes > 60 * 24) so userLoginsApi.getUserIdsWithSameIpAndPrint(userId) flatMap { ids =>
      playbanApi
        .bans(userId :: ids.toList)
        .map:
          _ filter { (_, bans) => bans > 4 }
        .flatMap: bans =>
          val topSum = Heapsort.topNToList(bans.values, 10).sum
          (topSum >= 80) so {
            userRepo.byId(userId) zip
              getLichessReporter zip
              findRecent(1, selectRecent(SuspectId(userId), Reason.Playbans)) flatMap {
                case ((Some(abuser), reporter), past) if past.isEmpty =>
                  create(
                    Candidate(
                      reporter = reporter,
                      suspect = Suspect(abuser),
                      reason = Reason.Playbans,
                      text =
                        s"${bans.values.sum} playbans over ${bans.keys.size} accounts with IP+Print match."
                    )
                  )
                case _ => funit
              }
          }
    }

  def processAndGetBySuspect(suspect: Suspect): Fu[List[Report]] =
    for
      all <- recent(suspect, 10)
      open = all.filter(_.open)
      _ <- doProcessReport(
        $inIds(all.filter(_.open).map(_.id)),
        unsetInquiry = false
      )(using User.lichessIdAsMe)
    yield open

  def reopenReports(suspect: Suspect): Funit =
    for
      all <- recent(suspect, 10)
      closed = all
        .filter(_.done.map(_.by) has User.lichessId.into(ModId))
        .filterNot(_ isAlreadySlain suspect.user)
      _ <-
        coll.update
          .one(
            $inIds(closed.map(_.id)),
            $set("open" -> true) ++ $unset("done"),
            multi = true
          )
          .void
    yield ()

  // `seriousness` depends on the number of previous warnings, and number of games throwed away
  def autoBoostReport(winnerId: UserId, loserId: UserId, seriousness: Int): Funit =
    securityApi.shareAnIpOrFp(winnerId, loserId) zip
      userRepo.pair(winnerId, loserId) zip getLichessReporter flatMap {
        case ((isSame, Some((winner, loser))), reporter) if !winner.lame && !loser.lame =>
          val loginsText =
            if isSame then "Found matching IP/print"
            else "No IP/print match found"
          create(
            Candidate(
              reporter = reporter,
              suspect = Suspect(winner),
              reason = Reason.Boost,
              text = s"Boosting: farms rating points from @${loser.username} ($loginsText)"
            ),
            _ + Report.Score(seriousness)
          )
        case _ => funit
      }

  def autoSandbagReport(winnerIds: List[UserId], loserId: UserId, seriousness: Int): Funit =
    userRepo.byId(loserId) zip getLichessReporter flatMap {
      case (Some(loser), reporter) if !loser.lame =>
        create(
          Candidate(
            reporter = reporter,
            suspect = Suspect(loser),
            reason = Reason.Boost,
            text = s"Sandbagging: throws games to ${winnerIds.map("@" + _) mkString " "}"
          ),
          _ + Report.Score(seriousness)
        )
      case _ => funit
    }

  def byId(id: Report.Id) = coll.byId[Report](id)

  def process(report: Report)(using Me): Funit = for
    _ <- accuracy.invalidate($id(report.id))
    _ <- doProcessReport($id(report.id), unsetInquiry = true)
  yield
    maxScoreCache.invalidateUnit()
    lila.mon.mod.report.close.increment()

  def autoProcess(sus: Suspect, rooms: Set[Room])(using Me.Id): Funit =
    val selector = $doc(
      "user" -> sus.user.id,
      "room" $in rooms,
      "open" -> true
    )
    doProcessReport(selector, unsetInquiry = true).void andDo
      maxScoreCache.invalidateUnit() andDo
      lila.mon.mod.report.close.increment().unit

  private def doProcessReport(selector: Bdoc, unsetInquiry: Boolean)(using me: Me.Id): Funit =
    coll.update
      .one(
        selector,
        $set(
          "open" -> false,
          "done" -> Report.Done(me.modId, nowInstant)
        ) ++ (unsetInquiry so $unset("inquiry")),
        multi = true
      )
      .void

  def autoCommReport(userId: UserId, text: String, critical: Boolean): Funit =
    getSuspect(userId) zip getLichessReporter flatMap {
      case (Some(suspect), reporter) =>
        create(
          Candidate(
            reporter = reporter,
            suspect = suspect,
            reason = Reason.Comm,
            text = text
          ),
          score = (_: Report.Score).map(_ * (if critical then 2 else 1))
        )
      case _ => funit
    }

  def moveToXfiles(id: Report.Id): Funit =
    coll.update
      .one(
        $id(id),
        $set("room" -> Room.Xfiles.key) ++ $unset("inquiry")
      )
      .void

  private val closedSelect: Bdoc = $doc("open" -> false)
  private val sortLastAtomAt     = $doc("atoms.0.at" -> -1)

  private def roomSelect(room: Option[Room]): Bdoc =
    room.fold($doc("room" $in Room.allButXfiles)): r =>
      $doc("room" -> r)

  private def selectOpenInRoom(room: Option[Room], exceptIds: Iterable[Report.Id]) =
    $doc("open" -> true) ++ roomSelect(room) ++ {
      exceptIds.nonEmpty so $doc("_id" $nin exceptIds)
    }

  private def selectOpenAvailableInRoom(room: Option[Room], exceptIds: Iterable[Report.Id]) =
    selectOpenInRoom(room, exceptIds) ++ $doc("inquiry" $exists false)

  private val maxScoreCache = cacheApi.unit[Room.Scores]:
    _.refreshAfterWrite(5 minutes).buildAsyncFuture: _ =>
      Room.allButXfiles
        .map: room =>
          coll // hits the best_open partial index
            .primitiveOne[Float](
              selectOpenAvailableInRoom(room.some, Nil),
              $sort desc "score",
              "score"
            )
            .dmap(room -> _)
        .parallel
        .dmap: scores =>
          Room.Scores:
            scores
              .map: (room, s) =>
                room -> s.so(_.toInt)
              .toMap
        .addEffect: scores =>
          lila.mon.mod.report.highest.update(scores.highest).unit

  def maxScores = maxScoreCache.getUnit

  def recent(
      suspect: Suspect,
      nb: Int,
      readPref: ReadPref = _.sec
  ): Fu[List[Report]] =
    coll
      .find($doc("user" -> suspect.id.value))
      .sort(sortLastAtomAt)
      .cursor[Report](readPref)
      .list(nb)

  def moreLike(report: Report, nb: Int): Fu[List[Report]] =
    coll
      .find($doc("user" -> report.user, "_id" $ne report.id))
      .sort(sortLastAtomAt)
      .cursor[Report]()
      .list(nb)

  def byAndAbout(user: User, nb: Int)(using Me): Fu[Report.ByAndAbout] =
    for
      by <-
        coll
          .find($doc("atoms.by" -> user.id))
          .sort(sortLastAtomAt)
          .cursor[Report](ReadPref.priTemp)
          .list(nb)
      about <- recent(Suspect(user), nb, _.priTemp)
    yield Report.ByAndAbout(by, Room.filterGranted(about))

  def currentCheatScore(suspect: Suspect): Fu[Option[Report.Score]] =
    coll.primitiveOne[Report.Score](
      $doc(
        "user" -> suspect.user.id,
        "room" -> Room.Cheat.key,
        "open" -> true
      ),
      "score"
    )

  def currentCheatReport(suspect: Suspect): Fu[Option[Report]] =
    coll.one[Report]:
      $doc(
        "user" -> suspect.user.id,
        "room" -> Room.Cheat.key,
        "open" -> true
      )

  def recentReportersOf(sus: Suspect): Fu[List[ReporterId]] =
    coll
      .distinctEasy[ReporterId, List](
        "atoms.by",
        $doc(
          "user" -> sus.user.id,
          "atoms.0.at" $gt nowInstant.minusDays(3)
        ),
        _.sec
      )
      .dmap(_ filterNot ReporterId.lichess.==)

  def openAndRecentWithFilter(nb: Int, room: Option[Room])(using mod: Me): Fu[List[Report.WithSuspect]] =
    for
      opens <- findBest(nb, selectOpenInRoom(room, snoozedIds))
      nbClosed = nb - opens.size
      closed <-
        if room.has(Room.Xfiles) || nbClosed < 1 then fuccess(Nil)
        else findRecent(nbClosed, closedSelect ++ roomSelect(room))
      withNotes <- addSuspectsAndNotes(opens ++ closed)
    yield withNotes

  private def findNext(room: Room)(using Me): Fu[Option[Report]] =
    findBest(1, selectOpenAvailableInRoom(room.some, snoozedIds)).map(_.headOption)

  private def snoozedIds(using mod: Me) = snoozer snoozedKeysOf mod.userId map (_.reportId)

  private def addSuspectsAndNotes(reports: List[Report]): Fu[List[Report.WithSuspect]] =
    userApi
      .listWithPerfs(reports.map(_.user).distinct)
      .map: users =>
        reports
          .flatMap: r =>
            users.find(_.id == r.user).map { u => Report.WithSuspect(r, u, isOnline(u.id)) }
          .sortBy(-_.urgency)

  def snooze(reportId: Report.Id, duration: String)(using mod: Me): Fu[Option[Report]] =
    byId(reportId) flatMapz { report =>
      snoozer.set(Report.SnoozeKey(mod.userId, reportId), duration)
      inquiries.toggleNext(report.room)
    }

  object accuracy:

    private val cache =
      cacheApi[ReporterId, Option[Accuracy]](512, "report.accuracy"):
        _.expireAfterWrite(24 hours).buildAsyncFuture: reporterId =>
          coll
            .find:
              $doc(
                "atoms.by" -> reporterId,
                "room"     -> Room.Cheat.key,
                "open"     -> false
              )
            .sort(sortLastAtomAt)
            .cursor[Report](ReadPref.sec)
            .list(20)
            .flatMap: reports =>
              if reports.sizeIs < 4 then fuccess(none) // not enough data to know
              else
                val userIds = reports.map(_.user).distinct
                userRepo countEngines userIds map { nbEngines =>
                  Accuracy {
                    Math.round((nbEngines + 0.5f) / (userIds.length + 2f) * 100)
                  }.some
                }

    private def of(reporter: ReporterId): Fu[Option[Accuracy]] =
      cache get reporter

    def apply(candidate: Candidate): Fu[Option[Accuracy]] =
      candidate.isCheat so of(candidate.reporter.id)

    def invalidate(selector: Bdoc): Funit =
      coll
        .distinctEasy[ReporterId, List]("atoms.by", selector, _.sec)
        .map(_ foreach cache.invalidate)
        .void

  private def findRecent(nb: Int, selector: Bdoc): Fu[List[Report]] =
    (nb > 0) so coll.find(selector).sort(sortLastAtomAt).cursor[Report]().list(nb)

  private def findBest(nb: Int, selector: Bdoc): Fu[List[Report]] =
    (nb > 0) so coll.find(selector).sort($sort desc "score").cursor[Report]().list(nb)

  private def selectRecent(suspect: SuspectId, reason: Reason): Bdoc =
    $doc(
      "atoms.0.at" $gt nowInstant.minusDays(7),
      "user"   -> suspect.value,
      "reason" -> reason
    )

  object inquiries:

    private val workQueue = lila.hub.AsyncActorSequencer(
      maxSize = Max(32),
      timeout = 20 seconds,
      name = "report.inquiries"
    )

    def allBySuspect: Fu[Map[UserId, Report.Inquiry]] =
      coll.list[Report]($doc("inquiry.mod" $exists true)) map {
        _.view
          .flatMap: r =>
            r.inquiry.map: i =>
              r.user -> i
          .toMap
      }

    def ofModId[U: UserIdOf](modId: U): Fu[Option[Report]] = coll.one[Report]($doc("inquiry.mod" -> modId))

    def ofSuspectId(suspectId: UserId): Fu[Option[Report.Inquiry]] =
      coll.primitiveOne[Report.Inquiry]($doc("inquiry.mod" $exists true, "user" -> suspectId), "inquiry")

    def ongoingAppealOf(suspectId: UserId): Fu[Option[Report.Inquiry]] =
      coll.primitiveOne[Report.Inquiry](
        $doc(
          "inquiry.mod" $exists true,
          "user"         -> suspectId,
          "room"         -> Room.Other.key,
          "atoms.0.text" -> Report.appealText
        ),
        "inquiry"
      )

    /*
     * If the mod has no current inquiry, just start this one.
     * If they had another inquiry, cancel it and start this one instead.
     * If they already are on this inquiry, cancel it.
     * Returns the previous and next inquiries
     */
    def toggle(id: String | Either[Report.Id, UserId])(using Me): Fu[(Option[Report], Option[Report])] =
      workQueue:
        doToggle(id)

    private def doToggle(
        id: String | Either[Report.Id, UserId]
    )(using mod: Me): Fu[(Option[Report], Option[Report])] =
      def findByUser(userId: UserId) = coll.one[Report]($doc("user" -> userId, "inquiry.mod" $exists true))
      for
        report <- id match
          case Left(reportId) => coll.byId[Report](reportId)
          case Right(userId)  => findByUser(userId)
          case anyId: String  => coll.byId[Report](anyId) orElse findByUser(UserId(anyId))
        current <- ofModId(mod.userId)
        _       <- current so cancel
        _ <-
          report.so: r =>
            r.inquiry.isEmpty so coll
              .updateField(
                $id(r.id),
                "inquiry",
                Report.Inquiry(mod.userId, nowInstant)
              )
              .void
      yield (current, report.filter(_.inquiry.isEmpty))

    def toggleNext(room: Room)(using Me): Fu[Option[Report]] =
      workQueue:
        findNext(room) flatMapz { report =>
          doToggle(Left(report.id)).dmap(_._2)
        }

    private def cancel(report: Report)(using mod: Me): Funit =
      if report.isOther && mod.is(report.onlyAtom.map(_.by))
      then coll.delete.one($id(report.id)).void // cancel spontaneous inquiry
      else
        coll.update
          .one(
            $id(report.id),
            $unset("inquiry", "done") ++ $set("open" -> true)
          )
          .void

    def spontaneous(sus: Suspect)(using Me): Fu[Report] =
      openOther(sus, Report.spontaneousText)

    def appeal(sus: Suspect)(using Me): Fu[Report] =
      openOther(sus, Report.appealText)

    private def openOther(sus: Suspect, name: String)(using mod: Me): Fu[Report] =
      ofModId(mod.userId) flatMap { current =>
        current.so(cancel) >> {
          val report = Report
            .make(
              Candidate(
                Reporter(mod.value),
                sus,
                Reason.Other,
                name
              ) scored Report.Score(0),
              none
            )
            .copy(inquiry = Report.Inquiry(mod.userId, nowInstant).some)
          coll.insert.one(report) inject report
        }
      }

    private[report] def expire: Funit =
      workQueue:
        val selector = $doc(
          "inquiry.mod" $exists true,
          "inquiry.seenAt" $lt nowInstant.minusMinutes(20)
        )
        coll.delete.one(selector ++ $doc("text" -> Report.spontaneousText)) >>
          coll.update.one(selector, $unset("inquiry"), multi = true).void
