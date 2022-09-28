package unjust.parser.eo

import cats.parse.{Parser => P}
import higherkindness.droste.data.Fix
import unjust._
import unjust.astparams.EOExprOnly
import unjust.parser.Utils._
import unjust.parser.eo.Common._
import unjust.parser.eo.SingleLine._
import unjust.parser.eo.Tokens._

object Named {

  def `object`(
    indent: Int,
    indentationStep: Int
  ): P[EOBndExpr[EOExprOnly]] = {

    val abstraction =
      (
        params.soft ~ SingleLine.bndName ~
          boundAttributes(indent, indentationStep).?
      ).map { case (((params, vararg), name), attrs) =>
        EOBndExpr(
          name,
          Fix[EOExpr](EOObj(params, vararg, attrs.getOrElse(Vector())))
        )
      }

    val inverseDotApplication = (
      (identifier.soft <* P.char('.')).soft ~ SingleLine.bndName ~
        verticalApplicationArgs(indent, indentationStep)
    ).map { case ((attr, name), args) =>
      EOBndExpr(name, createInverseDot(attr, args))
    }

    val verticalArray = (
      (P.char('*').soft *> SingleLine.bndName).soft ~
        verticalApplicationArgs(indent, indentationStep)
    ).map { case (name, args) =>
      EOBndExpr(name, createArrayFromNonEmpty(Some(args)))
    }

    val regularApplication = (
      singleLineApplication.soft ~ SingleLine.bndName ~
        verticalApplicationArgs(indent, indentationStep).?
    ).map {
      case ((trg, name), Some(args)) =>
        EOBndExpr(name, Fix[EOExpr](EOCopy(trg, args)))
      case ((trg, name), None) => EOBndExpr(name, trg)
    }

    val application = inverseDotApplication | regularApplication

    P.defer(
      abstraction |
        verticalArray |
        application
    )
  }

}
