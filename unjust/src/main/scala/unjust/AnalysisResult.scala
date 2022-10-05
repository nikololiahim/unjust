package unjust

import cats.data.NonEmptyList as Nel
import cats.ApplicativeThrow
import cats.syntax.all.*

sealed trait AnalysisResult {
  val ruleId: String
}

object AnalysisResult {

  final case class Ok(override val ruleId: String) extends AnalysisResult

  final case class DefectsDetected(
      override val ruleId: String,
      messages: Nel[String]
  ) extends AnalysisResult

  final case class Failure(
      override val ruleId: String,
      reason: Throwable
  ) extends AnalysisResult

  def fromErrors(
      analyzer: String
  )(errors: List[String]): AnalysisResult =
    errors match {
      case e :: es => DefectsDetected(analyzer, Nel(e, es))
      case Nil     => Ok(analyzer)
    }

  def fromThrow[F[_]: ApplicativeThrow](
      analyzer: String
  )(f: F[List[String]]): F[AnalysisResult] =
    f.attempt.map {
      case Left(value)   => Failure(analyzer, value)
      case Right(errors) => fromErrors(analyzer)(errors)
    }

}
