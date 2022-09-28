package unjust.parser.eo

import cats.parse.{Parser => P}
import higherkindness.droste.data.Fix
import unjust._
import unjust.astparams.EOExprOnly
import unjust.parser.Utils._
import unjust.parser.eo.Common._
import unjust.parser.eo.SingleLine._
import unjust.parser.eo.Tokens._

object Anon {

  def `object`(
    indent: Int,
    indentationStep: Int
  ): P[EOAnonExpr[EOExprOnly]] = {

    val regularApplication: P[EOAnonExpr[EOExprOnly]] = (
      singleLineApplication ~
        verticalApplicationArgs(indent, indentationStep).?
    ).map {
      case (trg, Some(args)) => EOAnonExpr(
          Fix[EOExpr](EOCopy(trg, args))
        )
      case (trg, None) => EOAnonExpr(trg)
    }

    val inverseDotApplication: P[EOAnonExpr[EOExprOnly]] = (
      (identifier.soft <* P.char('.')).soft ~
        verticalApplicationArgs(indent, indentationStep)
    ).map { case (id, args) =>
      EOAnonExpr(createInverseDot(id, args))
    }

    val application: P[EOAnonExpr[EOExprOnly]] =
      inverseDotApplication | regularApplication

    val verticalArray: P[EOAnonExpr[EOExprOnly]] = (
      P.char('*').soft *>
        verticalApplicationArgs(indent, indentationStep)
    ).map { args =>
      EOAnonExpr(createArrayFromNonEmpty(Some(args)))
    }

    val abstraction: P[EOAnonExpr[EOExprOnly]] = (
      params ~ boundAttributes(indent, indentationStep).?
    ).map { case ((params, vararg), attrs) =>
      EOAnonExpr(
        Fix[EOExpr](EOObj(params, vararg, attrs.getOrElse(Vector())))
      )
    }

    P.defer(
      abstraction.backtrack |
        application |
        verticalArray
    )
  }

}
