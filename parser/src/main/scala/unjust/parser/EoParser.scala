package unjust.parser

import cats.ApplicativeError
import cats.Functor
import cats.MonadError
import cats.syntax.either._
import cats.syntax.functor._
import unjust.EOBnd
import unjust.EOMetas
import unjust.EOProg
import unjust.astparams.EOExprOnly

trait EoParser[EORepr, F[_], R] {
  def parse(eoRepr: EORepr): F[R]
}

object EoParser {

  def apply[EORepr, F[_], R](implicit
      parser: EoParser[EORepr, F, R]
  ): EoParser[EORepr, F, R] = parser

  def parse[EORepr, F[_], R](eoRepr: EORepr)(implicit
      parser: EoParser[EORepr, F, R]
  ): F[R] = parser.parse(eoRepr)

  def sourceCodeEoParser[F[_]](
      indentationStep: Int = 2
  )(implicit
      ae: ApplicativeError[F, Throwable]
  ): EoParser[String, F, EOProg[EOExprOnly]] =
    new EoParser[String, F, EOProg[EOExprOnly]] {
      import eo.Parser

      override def parse(
          eoRepr: String
      ): F[EOProg[EOExprOnly]] = {
        ae.fromEither(
          Parser
            .parse(eoRepr, indentationStep)
            .leftMap(new IllegalArgumentException(_))
        )
      }

    }
}
