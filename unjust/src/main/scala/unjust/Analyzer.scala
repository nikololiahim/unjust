package unjust

import cats.MonadThrow
import cats.data.{EitherNel, EitherT, NonEmptyList as Nel}
import cats.effect.Sync
import cats.syntax.all.*
import unjust.*
import unjust.astparams.EOExprOnly
import unjust.utils.inlining.Inliner.AnalysisInfo
import unjust.utils.inlining.{MethodInfoForAnalysis, ObjectTree}
import unjust.utils.logicalextraction.ExtractLogic.{
  checkImplication,
  extractObjectLogic
}
import unjust.utils.logicalextraction.SMTUtils.LogicInfo
import unjust.utils.inlining.Inliner

trait Analyzer[F[_]] {
  val name: String
  def analyze(ast: EOProg[EOExprOnly]): F[AnalysisResult]
}

object Analyzer {
  def apply[F[_]: Sync]: Analyzer[F] =
    new Analyzer[F] {

      override val name: String = "Unjustified Assumption"

      override def analyze(
          ast: EOProg[EOExprOnly]
      ): F[AnalysisResult] =
        AnalysisResult.fromThrow[F](name) {
          for {
            tree <-
              toThrow(Inliner.zipMethodsWithTheirInlinedVersionsFromParent(ast))
            errors <- Analyzer
              .analyzeObjectTree[F](tree)
              .value
              .flatMap(e => toThrow(e))
          } yield errors
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

  def analyzeObjectTree[F[_]](
      objs: Map[EONamedBnd, ObjectTree[(AnalysisInfo, AnalysisInfo)]]
  )(implicit F: Sync[F]): EitherT[F, Nel[String], List[String]] = {
    objs.toList.flatTraverse { case (_, tree) =>
      val (before, after) = tree.info
      for {
        currentRes <- checkMethods(before, after)
        recurseRes <- Analyzer.analyzeObjectTree(tree.children)
      } yield (currentRes ++ recurseRes).distinct
    }
  }

  def checkMethods[F[_]](
      infoBefore: AnalysisInfo,
      infoAfter: AnalysisInfo
  )(implicit F: Sync[F]): EitherT[F, Nel[String], List[String]] = {
    val methodPairs = infoBefore.indirectMethods
      .alignWith(infoAfter.indirectMethods)(_.onlyBoth.get)

    methodPairs.toList.flatTraverse { case (name, (before, after)) =>
      val methodName = name.name.name
      // println("==================================================")
      // println(before.body.toEOPretty)
      // println("==================================================")
      // println(after.body.toEOPretty)
      // println("==================================================")
      for {
        methodsBefore <-
          EitherT
            .fromEither[F](getMethodsInfo("before", infoBefore.allMethods))
        methodsAfter <-
          EitherT.fromEither[F](getMethodsInfo("after", infoAfter.allMethods))

        res1 <-
          EitherT.fromEither[F](
            extractMethodLogic(
              before.selfArgName,
              "before",
              before,
              methodName,
              methodsBefore.keySet
            )
          )
        res2 <- EitherT.fromEither[F](
          extractMethodLogic(
            after.selfArgName,
            "after",
            after,
            methodName,
            methodsAfter.keySet
          )
        )
        res <-
          checkImplication[F](
            methodName,
            res1,
            methodsBefore,
            res2,
            methodsAfter,
            name =>
              s"Inlining calls in method $name is not safe: doing so may break the behaviour of subclasses!"
          )
      } yield res.toList
    }
  }

  def getMethodsInfo(
      tag: String,
      methods: Map[EONamedBnd, MethodInfoForAnalysis]
  ): EitherNel[String, Map[EONamedBnd, LogicInfo]] = {
    val methodNames = methods.keySet
    methods.toList
      .foldLeft[EitherNel[String, Map[EONamedBnd, LogicInfo]]](Right(Map())) {
        case (acc, (key, value)) =>
          for {
            acc <- acc
            newVal <- extractMethodLogic(
              value.selfArgName,
              tag,
              value,
              key.name.name,
              methodNames
            )
          } yield acc.updated(key, newVal)
      }
  }

  def extractMethodLogic(
      selfArgName: String,
      tag: String,
      method: MethodInfoForAnalysis,
      name: String,
      availableMethods: Set[EONamedBnd]
  ): EitherNel[String, LogicInfo] = {
    val body = method.body

    body.bndAttrs.collectFirst { case EOBndExpr(EODecoration, phiExpr) =>
      phiExpr
    } match {
      case Some(_) =>
        extractObjectLogic(selfArgName, body, availableMethods, List(tag))
      case None =>
        Left(Nel.one(s"Method $name does not have attached @ attribute"))
    }

  }

}
