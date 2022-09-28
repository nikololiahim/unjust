package unjust.parser.eo

import cats.data.NonEmptyVector
import cats.parse.{Parser => P}
import unjust.EOBnd
import unjust.EOBndExpr
import unjust.astparams.EOExprOnly
import unjust.parser.eo.Tokens._

object Common {

  val nonEmptyErrorMsg: String =
    "Managed to parse zero arguments, where 1 or more were required. This is probably a bug."

  def deeper(
    indent: Int,
    indentationStep: Int
  ): P[Unit] = P.string(" " * (indent + indentationStep))

  def wspBetweenObjs(
    indent: Int,
    indentationStep: Int
  ): P[Unit] = {
    (eol *> emptyLinesOrComments).soft *>
      deeper(indent, indentationStep)
  }

  def boundAttributes(
    indent: Int,
    indentationStep: Int
  ): P[Vector[EOBndExpr[EOExprOnly]]] = P.defer(
    wspBetweenObjs(indent, indentationStep)
      *> Named
        .`object`(indent + indentationStep, indentationStep)
        .repSep(1, sep = wspBetweenObjs(indent, indentationStep))
        .map(_.toList.toVector)
  )

  def verticalApplicationArgs(
    indent: Int,
    indentationStep: Int
  ): P[NonEmptyVector[EOBnd[EOExprOnly]]] = P.defer(
    wspBetweenObjs(indent, indentationStep)
      *> Parser
        .`object`(indent + indentationStep, indentationStep)
        .repSep(1, wspBetweenObjs(indent, indentationStep))
        .mapFilter(objs => NonEmptyVector.fromVector(objs.toList.toVector))
  )

}
