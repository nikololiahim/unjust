package unjust.utils.inlining

import cats.Monoid
import higherkindness.droste.data.Fix
import monocle.Iso
import unjust.utils.Abstract
import unjust.utils.Abstract.foldAst
import unjust.utils.Optics
import unjust.utils.Optics._
import unjust.utils.inlining
import unjust._
import unjust.astparams.EOExprOnly

import types._

object LocateCalls {

  def hasNoReferencesToPhi(binds: Vector[EOBnd[Fix[EOExpr]]]): Boolean = {
    implicit val andMonoid: Monoid[Boolean] = new Monoid[Boolean] {
      override def empty: Boolean = true
      override def combine(x: Boolean, y: Boolean): Boolean = x && y
    }

    foldAst[Boolean](binds, 0) { case (EOSimpleAppWithLocator("@", _), _) =>
      false
    }
  }

  def hasPhiAttribute(bnds: Vector[EOBnd[EOExprOnly]]): Boolean =
    bnds.exists {
      case EOBndExpr(EODecoration, _) => true
      case _ => false
    }

  def inlineThisObject(
    selfArgName: String
  )(body: EOObj[EOExprOnly]): EOObj[EOExprOnly] = {
    // TODO:
    // 1. find object where @ = this
    // 2. replace all occurences of this object with this,
    val one = BigInt(1)
    val thisObj = body.bndAttrs.collectFirst {
      case EOBndExpr(
             EOAnyNameBnd(LazyName(name)),
             EOObj(
               Vector(),
               None,
               Vector(
                 EOBndExpr(
                   EODecoration,
                   EOSimpleAppWithLocator(`selfArgName`, `one`)
                 )
               )
             )
           ) => name
    }

    thisObj match {
      case Some(thisObjectName) => Optics
          .prisms
          .fixToEOObj
          .getOption(Abstract.modifyExpr(_ => {
            case Fix(EOSimpleAppWithLocator(`thisObjectName`, locator)) =>
              Fix[EOExpr](EOSimpleAppWithLocator(selfArgName, locator))
            case other => other
          })(Fix(body)))
          .get

      case None => body
    }
  }

  def parseMethod(
    methodBnd: EOBndExpr[EOExprOnly],
    bndDepth: BigInt
  ): Option[MethodInfo] = {
    def findCalls(selfArg: String, body: EOObj[EOExprOnly]): Vector[Call] = {
      def findCallsRec(
        subExpr: Fix[EOExpr],
        pathToCallSite: PathToCallSite,
        pathToCall: PathToCall,
        depth: BigInt,
      ): Vector[Call] = {
        Fix.un(subExpr) match {
          // the call was found
          case EOCopy(
                 Fix(
                   EODot(Fix(EOSimpleAppWithLocator(srcName, locator)), name)
                 ),
                 args
               ) if locator == depth && srcName == selfArg =>
            val firstArgIsValid: Boolean = args.head match {
              case EOAnonExpr(EOSimpleAppWithLocator(firstArg, locator))
                   if locator == depth && firstArg == selfArg => true
              case _ => false
            }
            if (firstArgIsValid)
              Vector(
                inlining.Call(
                  depth = depth,
                  methodName = name,
                  callSite = pathToCallSite,
                  callLocation = pathToCall,
                  args = args
                )
              )
            else
              Vector()

          // the new callsite was found
          // pathToCall points to the new callsite
          // pathToCall is reset relative to this new callsite
          // depth is incremented
          case EOObj(_, _, bndAttrs) =>
            bndAttrs.flatMap { bnd =>
              findCallsRec(
                subExpr = bnd.expr,
                pathToCallSite =
                  pathToCallSite.andThen(pathToCall).andThen(prisms.fixToEOObj),
                pathToCall = optionals.focusBndAttrWithName(bnd.bndName),
                depth = depth + 1
              )
            }

          // looking for calls in copy trg and args
          case EOCopy(trg, args) => findCallsRec(
              subExpr = trg,
              pathToCallSite = pathToCallSite,
              pathToCall = pathToCall
                .andThen(prisms.fixToEOCopy)
                .andThen(lenses.focusCopyTrg),
              depth = depth
            ) ++ args.zipWithIndex.toVector.flatMap { case (arg, i) =>
              findCallsRec(
                subExpr = arg.expr,
                pathToCallSite = pathToCallSite,
                pathToCall = pathToCall
                  .andThen(prisms.fixToEOCopy)
                  .andThen(optionals.focusCopyArgAtIndex(i)),
                depth = depth
              )
            }

          // looking for calls in EODot src
          case EODot(src, _) => findCallsRec(
              subExpr = src,
              pathToCallSite = pathToCallSite,
              pathToCall = pathToCall
                .andThen(prisms.fixToEODot)
                .andThen(lenses.focusDotSrc),
              depth = depth
            )

          // looking for calls in EOArray elements
          case EOArray(elems) => elems.zipWithIndex.flatMap { case (elem, i) =>
              findCallsRec(
                subExpr = elem.expr,
                pathToCallSite = pathToCallSite,
                pathToCall = pathToCall
                  .andThen(prisms.fixToEOArray)
                  .andThen(optionals.focusArrayElemAtIndex(i)),
                depth = depth
              )
            }

          // any other node can not contain calls
          case _ => Vector()
        }
      }

      body.bndAttrs.flatMap { bnd =>
        findCallsRec(
          subExpr = bnd.expr,
          pathToCallSite = Iso.id[EOObj[EOExprOnly]],
          pathToCall = optionals.focusBndAttrWithName(bnd.bndName),
          depth = 0
        )
      }
    }

    Fix.un(methodBnd.expr) match {
      case obj @ EOObj(selfArg +: _, _, bndAttrs)
           if hasPhiAttribute(bndAttrs) &&
           hasNoReferencesToPhi(bndAttrs) &&
           (selfArg.name == "this" || selfArg.name == "self") &&
           // TODO: properly handle constructors
           methodBnd.bndName.name.name != "constructor" =>
        val objWithInlinedThis = inlineThisObject(selfArg.name)(obj)
        Some(
          MethodInfo(
            selfArgName = selfArg.name,
            calls = findCalls(selfArg.name, objWithInlinedThis),
            body = objWithInlinedThis,
            depth = bndDepth
          )
        )
      case _ => None
    }
  }

}
