package lila.pref

import play.api.data.*
import play.api.data.Forms.*

import lila.common.Form.{ numberIn, stringIn }

object PrefForm:

  private def containedIn(choices: Seq[(Int, String)]): Int => Boolean =
    choice => choices.exists(_._1 == choice)

  private def checkedNumber(choices: Seq[(Int, String)]) =
    number.verifying(containedIn(choices))

  private def bitPresent(anInt: Int, bit: Int): Boolean =
    (anInt & bit) == bit

  private def bitContainedIn(choices: Seq[(Int, String)]): Int => Boolean =
    choice => choice == 0 || choices.exists((bit, _) => bitPresent(choice, bit))

  private def bitCheckedNumber(choices: Seq[(Int, String)]) =
    number.verifying(bitContainedIn(choices))

  private lazy val booleanNumber =
    number.verifying(Pref.BooleanPref.verify)

  def pref(lichobile: Boolean) = Form(
    mapping(
      "display" -> mapping(
        "animation"     -> numberIn(Set(0, 1, 2, 3)),
        "captured"      -> booleanNumber,
        "highlight"     -> booleanNumber,
        "destination"   -> booleanNumber,
        "coords"        -> checkedNumber(Pref.Coords.choices),
        "replay"        -> checkedNumber(Pref.Replay.choices),
        "pieceNotation" -> optional(booleanNumber),
        "zen"           -> optional(booleanNumber),
        "resizeHandle"  -> optional(checkedNumber(Pref.ResizeHandle.choices)),
        "blindfold"     -> checkedNumber(Pref.Blindfold.choices)
      )(DisplayData.apply)(unapply),
      "behavior" -> mapping(
        "moveEvent"     -> optional(numberIn(Set(0, 1, 2))),
        "premove"       -> booleanNumber,
        "takeback"      -> checkedNumber(Pref.Takeback.choices),
        "autoQueen"     -> checkedNumber(Pref.AutoQueen.choices),
        "autoThreefold" -> checkedNumber(Pref.AutoThreefold.choices),
        "submitMove" -> optional:
          if lichobile then
            import Pref.SubmitMove.{ lichobile as compat }
            numberIn(compat.choices).transform(compat.appToServer, compat.serverToApp)
          else bitCheckedNumber(Pref.SubmitMove.choices)
        ,
        "confirmResign" -> checkedNumber(Pref.ConfirmResign.choices),
        "keyboardMove"  -> optional(booleanNumber),
        "voice"         -> optional(booleanNumber),
        "rookCastle"    -> optional(booleanNumber)
      )(BehaviorData.apply)(unapply),
      "clock" -> mapping(
        "tenths"   -> checkedNumber(Pref.ClockTenths.choices),
        "bar"      -> booleanNumber,
        "sound"    -> booleanNumber,
        "moretime" -> checkedNumber(Pref.Moretime.choices)
      )(ClockData.apply)(unapply),
      "follow"       -> booleanNumber,
      "challenge"    -> checkedNumber(Pref.Challenge.choices),
      "message"      -> checkedNumber(Pref.Message.choices),
      "studyInvite"  -> optional(checkedNumber(Pref.StudyInvite.choices)),
      "insightShare" -> numberIn(Set(0, 1, 2)),
      "ratings"      -> optional(booleanNumber)
    )(PrefData.apply)(unapply)
  )

  case class DisplayData(
      animation: Int,
      captured: Int,
      highlight: Int,
      destination: Int,
      coords: Int,
      replay: Int,
      pieceNotation: Option[Int],
      zen: Option[Int],
      resizeHandle: Option[Int],
      blindfold: Int
  )

  case class BehaviorData(
      moveEvent: Option[Int],
      premove: Int,
      takeback: Int,
      autoQueen: Int,
      autoThreefold: Int,
      submitMove: Option[Int],
      confirmResign: Int,
      keyboardMove: Option[Int],
      voice: Option[Int],
      rookCastle: Option[Int]
  )

  case class ClockData(
      tenths: Int,
      bar: Int,
      sound: Int,
      moretime: Int
  )

  case class PrefData(
      display: DisplayData,
      behavior: BehaviorData,
      clock: ClockData,
      follow: Int,
      challenge: Int,
      message: Int,
      studyInvite: Option[Int],
      insightShare: Int,
      ratings: Option[Int]
  ):

    def apply(pref: Pref) =
      pref.copy(
        autoQueen = behavior.autoQueen,
        autoThreefold = behavior.autoThreefold,
        takeback = behavior.takeback,
        moretime = clock.moretime,
        clockTenths = clock.tenths,
        clockBar = clock.bar == 1,
        clockSound = clock.sound == 1,
        follow = follow == 1,
        highlight = display.highlight == 1,
        destination = display.destination == 1,
        coords = display.coords,
        replay = display.replay,
        blindfold = display.blindfold,
        challenge = challenge,
        message = message,
        studyInvite = studyInvite | Pref.default.studyInvite,
        premove = behavior.premove == 1,
        animation = display.animation,
        submitMove = behavior.submitMove.getOrElse(0),
        insightShare = insightShare,
        confirmResign = behavior.confirmResign,
        captured = display.captured == 1,
        keyboardMove = behavior.keyboardMove | pref.keyboardMove,
        voice = if pref.voice.isEmpty && !behavior.voice.contains(1) then None else behavior.voice,
        zen = display.zen | pref.zen,
        ratings = ratings | pref.ratings,
        resizeHandle = display.resizeHandle | pref.resizeHandle,
        rookCastle = behavior.rookCastle | pref.rookCastle,
        pieceNotation = display.pieceNotation | pref.pieceNotation,
        moveEvent = behavior.moveEvent | pref.moveEvent
      )

  object PrefData:
    def apply(pref: Pref): PrefData =
      PrefData(
        display = DisplayData(
          highlight = if pref.highlight then 1 else 0,
          destination = if pref.destination then 1 else 0,
          animation = pref.animation,
          coords = pref.coords,
          replay = pref.replay,
          captured = if pref.captured then 1 else 0,
          blindfold = pref.blindfold,
          zen = pref.zen.some,
          resizeHandle = pref.resizeHandle.some,
          pieceNotation = pref.pieceNotation.some
        ),
        behavior = BehaviorData(
          moveEvent = pref.moveEvent.some,
          premove = if pref.premove then 1 else 0,
          takeback = pref.takeback,
          autoQueen = pref.autoQueen,
          autoThreefold = pref.autoThreefold,
          submitMove = pref.submitMove.some,
          confirmResign = pref.confirmResign,
          keyboardMove = pref.keyboardMove.some,
          voice = pref.voice.getOrElse(0).some,
          rookCastle = pref.rookCastle.some
        ),
        clock = ClockData(
          tenths = pref.clockTenths,
          bar = if pref.clockBar then 1 else 0,
          sound = if pref.clockSound then 1 else 0,
          moretime = pref.moretime
        ),
        follow = if pref.follow then 1 else 0,
        challenge = pref.challenge,
        message = pref.message,
        studyInvite = pref.studyInvite.some,
        insightShare = pref.insightShare,
        ratings = pref.ratings.some
      )

  def prefOf(p: Pref): Form[PrefData] = pref(lichobile = false).fill(PrefData(p))

  val theme = Form(
    single(
      "theme" -> text.verifying(Theme contains _)
    )
  )

  val pieceSet = Form(
    single(
      "set" -> text.verifying(PieceSet contains _)
    )
  )

  val theme3d = Form(
    single(
      "theme" -> text.verifying(Theme3d contains _)
    )
  )

  val pieceSet3d = Form(
    single(
      "set" -> text.verifying(PieceSet3d contains _)
    )
  )

  val soundSet = Form(
    single(
      "set" -> text.verifying(SoundSet contains _)
    )
  )

  val bg = Form(
    single(
      "bg" -> stringIn(Pref.Bg.fromString.keySet)
    )
  )

  // Allow blank image URL
  val bgImg = Form(
    single(
      "bgImg" -> text(maxLength = 400)
        .verifying { url => url.isBlank || url.startsWith("https://") || url.startsWith("//") }
    )
  )

  val is3d = Form(
    single(
      "is3d" -> text.verifying(List("true", "false") contains _)
    )
  )

  val zen = Form(
    single(
      "zen" -> text.verifying(Set("0", "1") contains _)
    )
  )

  val voice = Form(
    single(
      "voice" -> text.verifying(Set("0", "1") contains _)
    )
  )

  val keyboardMove = Form(
    single(
      "keyboardMove" -> text.verifying(Set("0", "1") contains _)
    )
  )
