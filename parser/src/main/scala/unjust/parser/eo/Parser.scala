package unjust.parser.eo

import cats.parse.{Parser => P}
import cats.parse.{Parser0 => P0}
import unjust.EOBnd
import unjust.EOProg
import unjust.astparams.EOExprOnly
import unjust.parser.eo.Tokens._

object Parser {

  def `object`(indent: Int, indentationStep: Int): P[EOBnd[EOExprOnly]] = {
    P.defer(
      Named.`object`(indent, indentationStep) |
        Anon.`object`(indent, indentationStep)
    )
  }

  def program(indent: Int, indentationStep: Int): P0[EOProg[EOExprOnly]] = (
    Metas.metas ~
      (emptyLinesOrComments *>
        `object`(indent, indentationStep)
          .repSep0(emptyLinesOrComments)) <*
      emptyLinesOrComments
  ).map { case (metas, objs) =>
    EOProg(metas, objs.toVector)
  }

  def parse(
    code: String,
    indentationStep: Int = 2
  ): Either[String, EOProg[EOExprOnly]] = {
    val pp = new Prettyprint(input = code)
    program(0, indentationStep).parseAll(code) match {
      case Left(value) => Left(pp.prettyprint(value))
      case Right(value) => Right(value)
    }
  }

}
