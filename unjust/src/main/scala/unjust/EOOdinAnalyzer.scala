package unjust

import cats._
import cats.data.EitherNel
import cats.data.NonEmptyList
import cats.effect.Sync
import cats.syntax.all._
import unjust.EOOdinAnalyzer._
import unjust.utils.inlining.Inliner
import unjust.utils.j2eo
import unjust.EOBndExpr
import unjust.EOProg
import unjust.astparams.EOExprOnly
import unjust.parser.EoParser
import unjust.parser.eo.Parser

trait ASTAnalyzer[F[_]] {
  val name: String
  def analyze(ast: EOProg[EOExprOnly]): F[OdinAnalysisResult]
}

object EOOdinAnalyzer {

  private def addPredef(existing: EOProg[EOExprOnly]): EOProg[EOExprOnly] = {
    val PREDEF = (
      j2eo.Primitives.all ++ j2eo.Other.all
    )
      .flatMap(code => Parser.parse(code).toOption.get.bnds)
      .filterNot(bnd =>
        existing.bnds.collect { case e @ EOBndExpr(_, _) => e }.contains(bnd)
      )

    existing.copy(bnds = existing.bnds.prependedAll(PREDEF))
  }

  sealed trait OdinAnalysisResult {
    val ruleId: String
  }

  object OdinAnalysisResult {

    final case class Ok(override val ruleId: String) extends OdinAnalysisResult

    final case class DefectsDetected(
      override val ruleId: String,
      messages: NonEmptyList[String],
    ) extends OdinAnalysisResult

    final case class AnalyzerFailure(
      override val ruleId: String,
      reason: Throwable
    ) extends OdinAnalysisResult

    def fromErrors(
      analyzer: String
    )(errors: List[String]): OdinAnalysisResult =
      errors match {
        case e :: es => DefectsDetected(analyzer, NonEmptyList(e, es))
        case Nil => Ok(analyzer)
      }

    def fromThrow[F[_]: ApplicativeThrow](
      analyzer: String
    )(f: F[List[String]]): F[OdinAnalysisResult] =
      f.attempt.map {
        case Left(value) => AnalyzerFailure(analyzer, value)
        case Right(errors) => fromErrors(analyzer)(errors)
      }

  }

  private def toThrow[F[_], A](
    eitherNel: EitherNel[String, A]
  )(implicit mt: MonadThrow[F]): F[A] = {
    MonadThrow[F].fromEither(
      eitherNel
        .leftMap(_.mkString_(util.Properties.lineSeparator))
        .leftMap(new Exception(_))
    )
  }


  def unjustifiedAssumptionAnalyzer[F[_]: Sync]: ASTAnalyzer[F] =
    new ASTAnalyzer[F] {

      override val name: String = "Unjustified Assumption"

      override def analyze(
        ast: EOProg[EOExprOnly]
      ): F[OdinAnalysisResult] =
        OdinAnalysisResult.fromThrow[F](name) {
          for {
            tree <-
              toThrow(Inliner.zipMethodsWithTheirInlinedVersionsFromParent(ast))
            errors <- unjustifiedassumptions
              .Analyzer
              .analyzeObjectTree[F](tree)
              .value
              .flatMap(e => toThrow(e))
          } yield errors
        }

    }


  def analyzeSourceCode[EORepr, F[_]](
    analyzer: ASTAnalyzer[F]
  )(
    eoRepr: EORepr
  )(implicit
    m: Monad[F],
    parser: EoParser[EORepr, F, EOProg[EOExprOnly]],
  ): F[OdinAnalysisResult] = for {
    programAst <- parser.parse(eoRepr)
    astWithPredef = addPredef(programAst)
    mutualRecursionErrors <-
      analyzer
        .analyze(astWithPredef)
//        .handleErrorWith(_ => Stream.empty)
  } yield mutualRecursionErrors

}
