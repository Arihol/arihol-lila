package lila.report

import lila.game.{ Game, GameRepo }

final private class ReportScore(
    getAccuracy: Report.Candidate => Fu[Option[Accuracy]],
    gameRepo: GameRepo,
    domain: lila.common.config.NetDomain
)(implicit ec: scala.concurrent.ExecutionContext) {

  def apply(candidate: Report.Candidate): Fu[Report.Candidate.Scored] =
    getAccuracy(candidate) map { accuracy =>
      impl.baseScore +
        impl.accuracyScore(accuracy) +
        impl.reporterScore(candidate.reporter) +
        impl.autoScore(candidate)
    } map impl.fixedAutoScore(candidate) map
      Report.Score map
      (_.withinBounds) map
      candidate.scored flatMap
      impl.dropScoreIfCheatReportHasNoAnalyzedGames map
      (_.withScore(_.withinBounds))

  private object impl {

    val baseScore = 20

    def accuracyScore(a: Option[Accuracy]): Double =
      a ?? { accuracy =>
        (accuracy.value - 50) * 0.8d
      }

    def reporterScore(r: Reporter) = r.user.lameOrTroll ?? -30d

    def autoScore(candidate: Report.Candidate) = candidate.isAutomatic ?? 25d

    // https://github.com/lichess-org/lila/issues/4587
    def fixedAutoScore(c: Report.Candidate)(score: Double): Double =
      if (c.isAutoBoost) baseScore * 1.5
      else if (c.isAutoComm) 42d
      else if (c.isIrwinCheat) 60d
      else if (c.isPrint || c.isCoachReview || c.isPlaybans) baseScore * 2
      else score

    private val gameRegex = ReportForm gameLinkRegex domain

    def dropScoreIfCheatReportHasNoAnalyzedGames(c: Report.Candidate.Scored): Fu[Report.Candidate.Scored] =
      if (c.candidate.isCheat) {
        val gameIds = gameRegex.findAllMatchIn(c.candidate.text).toList.take(20).map(_.group(1))
        def isUsable(gameId: Game.ID) = gameRepo analysed gameId map { _.exists(_.turns > 30) }
        lila.common.Future.exists(gameIds)(isUsable) map {
          case true  => c
          case false => c.withScore(_ / 3)
        }
      } else fuccess(c)
  }
}
