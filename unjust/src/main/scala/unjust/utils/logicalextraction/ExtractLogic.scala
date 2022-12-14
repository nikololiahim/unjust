package unjust.utils.logicalextraction

import ap.SimpleAPI
import cats.data.EitherNel
import cats.data.EitherT
import cats.data.NonEmptyVector
import cats.data.{NonEmptyList => Nel}
import cats.effect.Sync
import cats.syntax.monadError._
import cats.syntax.traverse._
import higherkindness.droste.data.Fix
import unjust.utils.logicalextraction.SMTUtils.LogicInfo
import unjust.utils.logicalextraction.SMTUtils.mkPropertiesFunIdent
import unjust.utils.logicalextraction.SMTUtils.mkValueFunIdent
import unjust.utils.logicalextraction.SMTUtils.nameToSSymbol
import unjust.utils.logicalextraction.SMTUtils.orderLets
import unjust._
import unjust.astparams.EOExprOnly
import smtlib.printer.RecursivePrinter
import smtlib.theories.Core._
import smtlib.theories.Ints._
import smtlib.trees.Commands._
import smtlib.trees.Terms._

import java.io.StringReader

object ExtractLogic {

  @annotation.tailrec
  def dotToSimpleAppsWithLocator(
    src: EOExprOnly,
    lastNames: List[String]
  ): EitherNel[String, (BigInt, List[String])] = {
    Fix.un(src) match {
      case EOObj(_, _, _) => Left(Nel.one("Cannot analyze [someObject].attr"))
      case app: EOApp[_] => app match {
          case EOSimpleApp(name) =>
            Left(Nel.one(s"Encountered unqualified attribute $name"))
          case EOSimpleAppWithLocator(name, locator) =>
            Right((locator, lastNames.prepended(name)))
          case EODot(src, name) =>
            dotToSimpleAppsWithLocator(src, lastNames.prepended(name))
          case EOCopy(_, _) =>
            Left(Nel.one("Cannot analyze dot of app:  (t1 t2).a"))
        }
      case _: EOData[_] =>
        Left(Nel.one("Cannot analyze arbitrary attributes of data"))
    }
  }

  def mkEqualsBndAttr(
    name: EONamedBnd,
    depth: List[String],
    value: Term
  ): Term = {
    value match {
      case QualifiedIdentifier(SimpleIdentifier(SSymbol("no-value")), _) =>
        True()
      case _ =>
        Equals(
          QualifiedIdentifier(
            SimpleIdentifier(
              SMTUtils.nameToSSymbol(List(name.name.name), depth)
            )
          ),
          value
        )
    }
  }

  def extractObjectLogic(
    selfArgName: String,
    body: EOObj[EOExprOnly],
    availableMethods: Set[EONamedBnd],
    depth: List[String],
    stubPhi: Boolean = false
  ): EitherNel[String, LogicInfo] = {
    // TODO: check that depth is correct????
    val exists = body
      .bndAttrs
      .collect {
        case EOBndExpr(
               EOAnyNameBnd(LazyName(name)),
               EOCopy(
                 EODot(EOSimpleAppWithLocator(valName, _), "constructor_1"),
                 _
               )
             ) if valName.startsWith("prim__") =>
          nameToSSymbol(List(name), depth)
      }

    body
      .bndAttrs
      .filter {
        case EOBndExpr(
               _,
               EOCopy(
                 EODot(EOSimpleAppWithLocator(name, _), "constructor_1"),
                 _
               )
             ) if name.startsWith("prim__") => false
        case _ => true
      }
      .traverse { case EOBndExpr(bndName, expr) =>
        extractLogic(
          selfArgName,
          bndName.name.name :: depth,
          expr,
          availableMethods
        )
          .map(info => (bndName, info))
      } flatMap (infos => {
      val phi = infos.toMap.get(EODecoration)

      val localInfos = infos.filter {
        case (EODecoration, _) => false
        case _ => true
      }

      val localLets =
        phi.map(_.bindings).getOrElse(List()) ++ localInfos.toList.flatMap {
          case (EOAnyNameBnd(LazyName(letName)), letTerm) =>
            VarBinding(
              SMTUtils.nameToSSymbol(List(letName), depth),
              letTerm.value match {
                case QualifiedIdentifier(
                       SimpleIdentifier(SSymbol("no-value")),
                       _
                     )
                     // TODO: Somehow check what type should be here
                     => SNumeral(8008) // True()
                case value => value
              }
            ) :: letTerm.bindings
          case (EODecoration, term) => term.bindings
          case _ => List()
        }

      val localProperties = localInfos.toList match {
        case _ :: _ :: _ => And(localInfos.map { case (name, info) =>
            And(
              info.properties,
              mkEqualsBndAttr(name, depth, info.value)
            )
          })
        case (name, info) :: Nil =>
          And(
            info.properties,
            mkEqualsBndAttr(name, depth, info.value)
          )
        case Nil => True()
      }

      if (stubPhi && phi.isEmpty) {
        orderLets(
          localLets,
          SNumeral(8008)
        )
          .flatMap(resultValue =>
            Right(
              localLets match {
                case x :: xs => LogicInfo(
                    List.empty,
                    // TODO: IS THIS HOW IT SHOULD BE?????
                    x :: xs, // List.empty,
                    resultValue,
                    Let(
                      x,
                      xs,
                      localProperties
                    ),
                    exists
                  )
                case Nil => LogicInfo(
                    List.empty,
                    List.empty,
                    resultValue,
                    localProperties,
                    exists
                  )
              }
            )
          )
      } else {
        phi match {
          case None =>
            Left(Nel.one("Some method has no phi attribute attached!"))
          case Some(resultInfo) =>
            // FIXME: we are assuming first argument is self (need to check)

            val params = {
              if (body.freeAttrs.nonEmpty)
                body
                  .freeAttrs
                  .tail
                  .toList
                  .map(name => SMTUtils.mkIntVar(name.name, depth))
              else List()
            }

            for {
              value <- orderLets(localLets, resultInfo.value)
              properties <- orderLets(
                localLets,
                And(resultInfo.properties, localProperties)
              )
            } yield LogicInfo(
              params,
              localLets,
              value,
              properties,
              exists
            )
        }
      }
    })
  }

  def extractLogic(
    selfArgName: String,
    depth: List[String],
    expr: EOExprOnly,
    availableMethods: Set[EONamedBnd]
  ): EitherNel[String, LogicInfo] = {
    Fix.un(expr) match {
      case body @ EOObj(Vector(), None, _) =>
        extractObjectLogic(
          selfArgName,
          body,
          availableMethods,
          depth,
          stubPhi = true
        )

      case EOObj(_, _, _) =>
        Left(Nel.one("object with void attributes are not supported yet!")) /*
         * FIXME */
      case app: EOApp[_] => app match {
          case EOSimpleApp(name) =>
            Left(Nel.one(s"Encountered unqualified attribute $name"))
          case EOSimpleAppWithLocator("memory" | "cage", locator)
               if locator == depth.length =>
            Right(
              LogicInfo(
                List.empty,
                List.empty,
                QualifiedIdentifier(
                  SimpleIdentifier(SSymbol("no-value"))
                ),
                True()
              )
            )
          case EOSimpleAppWithLocator(name, locator) =>
            Right(
              SMTUtils
                .simpleAppToInfo(List(name), depth.drop(locator.toInt + 1))
            )
          case EODot(src, name) =>
            dotToSimpleAppsWithLocator(src, List(name)).map {
              case (locator, names) =>
                SMTUtils.simpleAppToInfo(names, depth.drop(locator.toInt + 1))
            }
          case EOCopy(
                 Fix(
                   EODot(
                     Fix(EOSimpleAppWithLocator(srcName, locator)),
                     methodName
                   )
                 ),
                 args
               ) if (srcName == selfArgName) =>
            args.map(x => Fix.un(x.expr)) match {
              case NonEmptyVector(
                     EOSimpleAppWithLocator(firstArgName, locator2),
                     moreArgs
                   ) if locator == locator2 && firstArgName == selfArgName =>
                moreArgs
                  .traverse(expr =>
                    extractLogic(
                      selfArgName,
                      depth,
                      Fix(expr),
                      availableMethods
                    )
                  )
                  .flatMap(arguments =>
                    if (
                      availableMethods
                        .contains(EOAnyNameBnd(LazyName(methodName)))
                    ) {
                      arguments match {
                        case Vector() =>
                          Right(
                            LogicInfo(
                              List.empty,
                              List.empty,
                              mkValueFunIdent(
                                methodName,
                                depth.drop(locator.toInt + 1)
                              ),
                              mkPropertiesFunIdent(
                                methodName,
                                depth.drop(locator.toInt + 1)
                              )
                            )
                          )
                        case _ =>
                          Right(
                            LogicInfo(
                              List.empty,
                              List.empty,
                              FunctionApplication(
                                SMTUtils.mkValueFunIdent(
                                  methodName,
                                  depth.drop(locator.toInt + 1)
                                ),
                                arguments.map(arg => arg.value)
                              ),
                              FunctionApplication(
                                SMTUtils.mkPropertiesFunIdent(
                                  methodName,
                                  depth.drop(locator.toInt + 1)
                                ),
                                arguments.map(arg => arg.value)
                              )
                            )
                          )
                      }

                    } else
                      Left(Nel.one(s"Unknown method $methodName"))
                  )
              case _ => Left(Nel.one(s"Unsupported EOCopy with self: $app"))
            }
          // J2EO  TODO: DEPTH CHECK
          case EOCopy(
                 Fix(
                   EODot(
                     EOSimpleAppWithLocator(name, _),
                     "constructor_2" | "constructor_3"
                   )
                 ),
                 args
               ) if name.startsWith("prim__") =>
            extractLogic(selfArgName, depth, args.last.expr, availableMethods)

          case EOCopy(Fix(EOSimpleAppWithLocator(name, _)), args) => for { /*
               * FIXME: check locators */
              infoArgs <- args.traverse(arg =>
                extractLogic(selfArgName, depth, arg.expr, availableMethods)
              )
              result <- (name, infoArgs) match {
                case ("seq", NonEmptyVector(arg, Vector())) =>
                  Right(
                    LogicInfo(
                      List.empty,
                      arg.bindings,
                      arg.value,
                      arg.properties
                    )
                  )
                case ("seq", args) =>
                  Right(
                    LogicInfo(
                      List.empty,
                      args.toVector.flatMap(_.bindings).toList,
                      args.last.value,
                      And(args.toVector.map(x => x.properties))
                    )
                  )
                // Todo if possible check that assert content is a boolean
                // assert (3.div 2) causes problems with type correspondance
                case ("assert", NonEmptyVector(arg, Vector())) =>
                  Right(
                    LogicInfo(
                      List.empty,
                      List.empty,
                      SNumeral(8008),
                      And(arg.properties, arg.value)
                    )
                  )
                case _ =>
                  Left(
                    Nel.one(
                      s"Unsupported ${infoArgs.length}-ary primitive $name"
                    )
                  )
              }
            } yield result

          // J2EO version of `assert`
          case EOCopy(
                 Fix(EODot(src, "if")),
                 NonEmptyVector(
                   _,
                   Vector(
                     EOAnonExpr(
                       EOObj(
                         Vector(),
                         _,
                         Vector(
                           EOBndExpr(
                             EOAnyNameBnd(LazyName("msg")),
                             EOStrData("AssertionError")
                           )
                         )
                       )
                     )
                   )
                 )
               ) =>
            extractLogic(selfArgName, depth, src, availableMethods).flatMap(
              res =>
                Right(
                  LogicInfo(
                    List.empty,
                    List.empty,
                    SNumeral(8008),
                    And(res.properties, res.value)
                  )
                )
            )

          case EOCopy(
                 Fix(EODot(Fix(EOSimpleAppWithLocator(name, srcLoc)), "write")),
                 NonEmptyVector(
                   dest,
                   Vector()
                 )
               ) =>
            val srcIdnt = QualifiedIdentifier(
              SimpleIdentifier(
                // TODO: fix +1 locator thing
                nameToSSymbol(List(name), depth.drop(srcLoc.toInt + 1))
              )
            )
            dest match {
              case EOAnonExpr(
                     EOSimpleAppWithLocator(valName, valLoc)
                   ) =>
                val resVal = QualifiedIdentifier(
                  SimpleIdentifier(
                    // TODO: fix +1 locator thing
                    nameToSSymbol(List(valName), depth.drop(valLoc.toInt + 1))
                  )
                )

                Right(
                  LogicInfo(
                    List.empty,
                    List(VarBinding(srcIdnt.id.symbol, resVal)),
                    resVal,
                    Equals(
                      srcIdnt,
                      resVal
                    )
                  )
                )

              case EOAnonExpr(expr) =>
                extractLogic(selfArgName, depth, expr, availableMethods)
                  .map(resVal =>
                    Right(
                      LogicInfo(
                        List.empty,
                        List(VarBinding(srcIdnt.id.symbol, resVal.value)),
                        resVal.value,
                        resVal.properties
                      )
                    )
                  )
                  .getOrElse(
                    Right(
                      LogicInfo(
                        List.empty,
                        List.empty,
                        QualifiedIdentifier(
                          SimpleIdentifier(SSymbol("no-value"))
                        ),
                        True()
                      )
                    )
                  )
              case _ =>
                Right(
                  LogicInfo(
                    List.empty,
                    List.empty,
                    QualifiedIdentifier(
                      SimpleIdentifier(SSymbol("no-value"))
                    ),
                    True()
                  )
                )
            }

          case EOCopy(Fix(EODot(src, attr)), args) => for {
              infoSrc <- extractLogic(selfArgName, depth, src, availableMethods)
              infoArgs <- args.traverse(arg =>
                extractLogic(selfArgName, depth, arg.expr, availableMethods)
              )
              result <- (attr, infoArgs.toVector.toList) match {
                case ("add", infoArg :: Nil) =>
                  Right(
                    LogicInfo(
                      List.empty,
                      List.empty,
                      Add(infoSrc.value, infoArg.value),
                      And(infoSrc.properties, infoArg.properties)
                    )
                  )
                case ("div", infoArg :: Nil) =>
                  Right(
                    LogicInfo(
                      List.empty,
                      List.empty,
                      Div(infoSrc.value, infoArg.value),
                      And(
                        infoSrc.properties,
                        infoArg.properties,
                        Not(Equals(infoArg.value, SNumeral(0)))
                      )
                    )
                  )
                case ("mul", infoArg :: Nil) =>
                  Right(
                    LogicInfo(
                      List.empty,
                      List.empty,
                      Mul(infoSrc.value, infoArg.value),
                      And(infoSrc.properties, infoArg.properties)
                    )
                  )
                case ("sub", infoArg :: Nil) =>
                  Right(
                    LogicInfo(
                      List.empty,
                      List.empty,
                      Sub(infoSrc.value, infoArg.value),
                      And(infoSrc.properties, infoArg.properties)
                    )
                  )
                case ("less", infoArg :: Nil) =>
                  Right(
                    LogicInfo(
                      List.empty,
                      List.empty,
                      LessThan(infoSrc.value, infoArg.value),
                      And(infoSrc.properties, infoArg.properties)
                    )
                  )
                case ("greater", infoArg :: Nil) =>
                  Right(
                    LogicInfo(
                      List.empty,
                      List.empty,
                      GreaterThan(infoSrc.value, infoArg.value),
                      And(infoSrc.properties, infoArg.properties)
                    )
                  )
                case ("if", ifTrue :: ifFalse :: Nil) =>
                  Right(
                    LogicInfo(
                      List.empty,
                      List.empty,
                      ITE(infoSrc.value, ifTrue.value, ifFalse.value),
                      And(
                        infoSrc.properties,
                        Or(
                          And(infoSrc.value, ifTrue.properties),
                          And(Not(infoSrc.value), ifFalse.properties)
                        )
                      )
                    )
                  )
                case ("mod", infoArg :: Nil) =>
                  Right(
                    LogicInfo(
                      List.empty,
                      List.empty,
                      Mod(infoSrc.value, infoArg.value),
                      And(infoSrc.properties, infoArg.properties)
                    )
                  )
                case _ =>
                  Right(
                    LogicInfo(
                      List.empty,
                      List.empty,
                      QualifiedIdentifier(
                        SimpleIdentifier(SSymbol("no-value"))
                      ),
                      True()
                    )
                  )
//                  Left(
//                    Nel.one(
//                      s"Unsupported ${infoArgs.length}-ary primitive .$attr"
//                    )
//                  )
              }
            } yield result
          case _ => Left(Nel.one(s"Some EOCopy is not supported yet: $app"))
        }
      case EOIntData(n) =>
        Right(LogicInfo(List.empty, List.empty, SNumeral(n), True()))
      case EOBoolData(v) =>
        Right(
          LogicInfo(List.empty, List.empty, if (v) True() else False(), True())
        )
      case EOFloatData(f) =>
        Right(
          LogicInfo(
            List.empty,
            List.empty,
            SDecimal(BigDecimal(f.toDouble)),
            True()
          )
        )
      case _ =>
        Right(
          LogicInfo(
            List.empty,
            List.empty,
            QualifiedIdentifier(SimpleIdentifier(SSymbol("no-value"))),
            True()
          )
        )
//        Left(Nel.one(s"Some case is not checked: $expr")) // FIXME
    }
  }

  // TODO: check that behaviour is intended even when before and after different
  def addVarCorrespondenceToTerm(
    before: List[SortedVar],
    after: List[SortedVar],
    term: Term
  ): Term = {
    val innerTerm = before.zip(after) match {
      case Nil => term
      case terms => And(
          And(True() :: terms.map { case (x, y) =>
            Equals(
              QualifiedIdentifier(SimpleIdentifier(SSymbol(x.name.name))),
              QualifiedIdentifier(SimpleIdentifier(SSymbol(y.name.name)))
            )
          }),
          term
        )
    }

    val afterOuter = after match {
      case x :: xs => Exists(
          x,
          xs,
          innerTerm
        )
      case Nil => innerTerm
    }

    before match {
      case x :: xs => Forall(
          x,
          xs,
          afterOuter
        )
      case Nil => afterOuter
    }

  }

  def checkImplication[F[_]](
    methodName: String,
    before: LogicInfo,
    methodsBefore: Map[EONamedBnd, LogicInfo],
    after: LogicInfo,
    methodsAfter: Map[EONamedBnd, LogicInfo],
    resultMsgGenerator: String => String,
    beforeTag: String = "before",
    afterTag: String = "after"
  )(implicit F: Sync[F]): EitherT[F, Nel[String], Option[String]] = {
    val impl = addVarCorrespondenceToTerm(
      before.forall,
      after.forall,
      Implies(before.properties, after.properties)
    )

    val defsBefore = methodsBefore.toList.flatMap { case (name, info) =>
      SMTUtils.mkFunDefs(beforeTag, name, info)
    }
    val defsAfter = methodsAfter.toList.flatMap { case (name, info) =>
      SMTUtils.mkFunDefs(afterTag, name, info)
    }

// TODO: Check why the commented code does not cause type inconsistencies

//    val allDefs = defsBefore ++ defsAfter
//    val callGraph = allDefs
//      .map(func =>
//        (
//          func,
//          SMTUtils
//            .extractMethodDependencies(
//              func.body,
//              allDefs.map(_.name.name).zip(allDefs).toMap
//            )
//        )
//      )
//      .flatMap { case (caller, vals) =>
//        vals.map(callee => (caller, callee))
//      }
//      .distinct
//
//    val (orderedDefs, badDefs) = SMTUtils.tsort[FunDef](
//      callGraph,
//      { case (func, bads) =>
//        Right(SMTUtils.removeProblematicCalls(func, bads.map(_.name)))
//      }
//    )

    val (orderedDefs, badDefs) = SMTUtils.runTsort[FunDef](
      defsBefore ++ defsAfter,
      _.body,
      _.name.name,
      { case (func, bads) =>
        Right(SMTUtils.removeProblematicCalls(func, bads.map(_.name)))
      }
    )

    if (badDefs.nonEmpty)
      EitherT(
        F.delay(
          Left(
            Nel.one(
              s"Could not process; The following methods are in a circular dependency : $badDefs"
            )
          )
        )
      )
    else {
      val prog = orderedDefs.map(DefineFun) ++ List(Assert(impl))

      val formula = prog.map(RecursivePrinter.toString).mkString
//      println(formula)
      EitherT(
        F.delay(
          SimpleAPI.withProver(p => {
            p.execSMTLIB(new StringReader(formula))
            p.checkSat(true)
            p.getStatus(true) match {
              case ap.SimpleAPI.ProverStatus.Sat => Right(None)
              case ap.SimpleAPI.ProverStatus.Unsat => Right(
                  Some(resultMsgGenerator(methodName))
                )
              case err => Left(Nel.one(s"SMT solver failed with error: $err"))
            }
          })
        ).adaptError(t =>
          new Exception(
            s"SMT failed to parse the generated program with error:${t.getMessage}",
            t
          )
        )
      )
    }
  }

}
