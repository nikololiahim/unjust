package unjust

import cats.*
import cats.data.NonEmptyList as Nel
import cats.effect.*
import cats.syntax.all.*
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import fs2.io.file.{Files, Path}
import fs2.text.*
import unjust.astparams.EOExprOnly
import unjust.parser.EoParser
import unjust.parser.eo.Parser
import unjust.utils.j2eo
import unjust.{AnalysisResult, Analyzer}

import java.nio.file.Path as JPath
import scala.util.Properties.lineSeparator

object Main
    extends CommandIOApp(
      name = "unjust",
      header = "Detects the unjustified assumption in subclass in EO programs."
    ) {

  override def main: Opts[IO[ExitCode]] = opts.map {
    _.traverse_ { path =>
      for {
        contents <- Files[IO]
          .readAll(path)
          .through(utf8.decode)
          .compile
          .string
        result <- analyzeSourceCode(Analyzer[IO])(contents)
        _ <- IO.println(renderResult(path)(result))
      } yield ()
    }
      .as(ExitCode.Success)
  }

  private val opts: Opts[Nel[Path]] = Functor[Opts]
    .compose[Nel]
    .map(Opts.arguments[JPath]())(Path.fromNioPath)

  private def renderResult(fileName: Path)(result: AnalysisResult): String = {
    val indent: String => String = "  " + _
    val indent2: String => String = indent andThen indent
    val message: Nel[String] = result match
      case AnalysisResult.Ok(_) => Nel.one("No errors found.")
      case AnalysisResult.DefectsDetected(_, messages) =>
        Nel
          .one("The following errors were found:")
          .concatNel(messages.map(indent))
      case AnalysisResult.Failure(_, reason) =>
        Nel.of(
          "Analyzer failed with the following error:",
          indent2(reason.getMessage)
        )

    s"""file "$fileName":
       |${message.map(indent).mkString_(lineSeparator)}
       |""".stripMargin
  }

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

  private def analyzeSourceCode[EORepr, F[_]](
      analyzer: Analyzer[F]
  )(eo: String)(implicit
      F: MonadThrow[F]
  ): F[AnalysisResult] = for {
    programAst <- F.fromEither(Parser.parse(eo).leftMap(new Exception(_)))
    astWithPredef = addPredef(programAst)
    mutualRecursionErrors <- analyzer.analyze(astWithPredef)
  } yield mutualRecursionErrors

}
