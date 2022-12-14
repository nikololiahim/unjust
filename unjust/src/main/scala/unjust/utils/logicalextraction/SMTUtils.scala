package unjust.utils.logicalextraction

import cats.data.EitherNel
import cats.data.NonEmptyList
import unjust.EONamedBnd
import smtlib.common.Positioned
import smtlib.theories.Core.BoolSort
import smtlib.theories.Core.True
import smtlib.theories.Ints.IntSort
import smtlib.trees.Commands.FunDef
import smtlib.trees.Terms._

import scala.annotation.tailrec

object SMTUtils {

  final case class LogicInfo(
    forall: List[SortedVar],
    bindings: List[VarBinding],
    value: Term,
    properties: Term,
    exists: Vector[SSymbol] = Vector()
  )

  def simpleAppToInfo(names: List[String], depth: List[String]): LogicInfo = {
    LogicInfo(
      List.empty,
      List.empty,
      QualifiedIdentifier(SimpleIdentifier(nameToSSymbol(names, depth))),
      True()
    )
  }

  def nameToSSymbol(names: List[String], depth: List[String]): SSymbol = {
    val name = (depth.reverse ++ names).mkString("-")
    SSymbol(s"var-$name")
  }

  def mkValueFunSSymbol(name: String, depth: List[String]): SSymbol = {
    val qualifiedName = (depth.reverse ++ List(name)).mkString("-")
    SSymbol(s"value-of-$qualifiedName")
  }

  def mkValueFunIdent(
    name: String,
    depth: List[String]
  ): QualifiedIdentifier = {
    QualifiedIdentifier(
      SimpleIdentifier(SMTUtils.mkValueFunSSymbol(name, depth))
    )
  }

  def mkPropertiesFunSSymbol(name: String, depth: List[String]): SSymbol = {
    val qualifiedName = (depth.reverse ++ List(name)).mkString("-")
    SSymbol(s"properties-of-$qualifiedName")
  }

  def mkPropertiesFunIdent(
    name: String,
    depth: List[String]
  ): QualifiedIdentifier = {
    QualifiedIdentifier(
      SimpleIdentifier(SMTUtils.mkPropertiesFunSSymbol(name, depth))
    )
  }

  def mkIntVar(name: String, depth: List[String]): SortedVar = {
    SortedVar(SMTUtils.nameToSSymbol(List(name), depth), IntSort())
  }

  def mkFunDefs(
    tag: String,
    name: EONamedBnd,
    info: LogicInfo
  ): List[FunDef] = {
    val valueSort = info.value match {
      case QualifiedIdentifier(
             SimpleIdentifier(SSymbol("true" | "false")),
             _
           ) => BoolSort()
      case QualifiedIdentifier(_, Some(sort)) => sort
      case FunctionApplication(QualifiedIdentifier(_, Some(sort)), _) => sort
      case FunctionApplication(
             QualifiedIdentifier(SimpleIdentifier(SSymbol("and")), _),
             _
           ) => BoolSort()
      case _ => IntSort()
    }

    val value = info.value match {
      case QualifiedIdentifier(SimpleIdentifier(SSymbol("no-value")), _) =>
        SNumeral(8008)
      case other => other
    }

//    println(s"${tag}-${name.name.name} =>  ${info.exists}")
    // TODO: Int body cannot be Exists???? :(
    def existify(term: Term) = info.exists.toList match {
      case _ => term
//      case x :: xs =>
//        Exists(SortedVar(x, IntSort()), xs.map(SortedVar(_, IntSort())), term)
    }

    val valueDef =
      FunDef(
        SMTUtils.mkValueFunSSymbol(name.name.name, List(tag)),
        info.forall,
        valueSort,
        existify(value)
      )
    val propertiesDef =
      FunDef(
        SMTUtils.mkPropertiesFunSSymbol(name.name.name, List(tag)),
        info.forall,
        BoolSort(),
        existify(info.properties)
      )
    List(valueDef, propertiesDef)
  }

  def extractMethodDependencies[A](
    term: Positioned,
    availableDeps: Map[String, A],
  ): List[A] = {
    def recurse(t: Positioned): List[A] = {
      t match {
        case VarBinding(_, term) => recurse(term)
        case Let(x, xs, term) =>
          recurse(term) ++ xs.flatMap(recurse) ++ recurse(x)
        case Forall(_, _, term) => recurse(term)
        case Exists(_, _, term) => recurse(term)
        case FunctionApplication(fun, terms) =>
          availableDeps.get(fun.id.symbol.name) match {
            case Some(funDef) => List(funDef) ++ terms.flatMap(recurse)
            case None => terms.flatMap(recurse).toList
          }
        case QualifiedIdentifier(Identifier(SSymbol(name), _), _) =>
          availableDeps.get(name).toList
        case _ => List()
      }
    }

    recurse(term)
  }

  def runTsort[A](
    elements: List[A],
    toTerm: A => Term,
    toString: A => String,
    handleBads: (A, Set[A]) => Either[(A, Set[A]), A] =
      (a: A, s: Set[A]) => Left((a, s))
  ): (List[A], Map[A, Set[A]]) = {

    def getElDependencies(elements: List[A]): (List[A], List[(A, List[A])]) = {
      val elDepPairs = elements.map(el =>
        (
          el,
          extractMethodDependencies[A](
            toTerm(el),
            elements.map(toString).zip(elements).toMap
          )
        )
      )
      val independentEls = elDepPairs.collect { case (a, List()) =>
        a
      }
      val dependentEls = elDepPairs.collect { case pair @ (_, _ :: _) =>
        pair
      }
      (independentEls, dependentEls)
    }

    @tailrec
    def tsort(
      edges: Iterable[(A, A)],
      handleBads: (A, Set[A]) => Either[(A, Set[A]), A],
      independentTerms: List[A] = List()
    ): (List[A], Map[A, Set[A]]) = {

      @tailrec
      def tsortHelper(
        toPreds: Map[A, Set[A]],
        done: Iterable[A]
      ): (Iterable[A], Map[A, Set[A]]) = {
        val (noPreds, hasPreds) = toPreds.partition { _._2.isEmpty }
        if (noPreds.isEmpty) {
          (done, hasPreds)
        } else {
          val found = noPreds.keys
          tsortHelper(
            hasPreds.view.mapValues { _ -- found }.toMap,
            done ++ found
          )
        }
      }

      val toPred = edges.foldLeft(Map[A, Set[A]]()) { (acc, e) =>
        acc + (e._1 -> acc
          .getOrElse(e._1, Set())) + (e._2 -> (acc.getOrElse(
          e._2,
          Set()
        ) + e._1))
      }

      //    val toPred = edges
      //      .foldLeft(Map[A, Set[A]]()) { case (acc, (from, to)) =>
      //        val fromEdge = from -> acc.getOrElse(from, Set())
      //        val toEdge = to -> acc.getOrElse(to, Set() + from)
      //        acc + fromEdge + toEdge
      //      }

      val (goodEdges, badEdges) = tsortHelper(toPred, Seq())
      if (badEdges.isEmpty)
        (independentTerms ++ goodEdges.toList.reverse, badEdges)
      else {
        val (notHandledBads, handledBads) = badEdges
          .partitionMap { case (func, bads) =>
            handleBads(func, bads)
          }

        if (notHandledBads.nonEmpty)
          (
            independentTerms ++ handledBads.toList ++ goodEdges.toList.reverse,
            notHandledBads.toMap
          )
        else {
          val (indepTerms, depTerms) =
            getElDependencies((handledBads ++ goodEdges).toList)
          val graph = for {
            (from, tos) <- depTerms
            to <- tos
          } yield (from, to)

          tsort(graph, handleBads, indepTerms)
        }
      }
    }

    val (independentEls, dependentEls) = getElDependencies(elements)

    val graph = dependentEls.flatMap { case (binding, dependencies) =>
      dependencies.map(dependency => (binding, dependency))
    }.distinct
    val (sortedEls, badEls) = tsort(graph, handleBads)

    ((independentEls ++ sortedEls).distinct, badEls)
  }

  def orderLets(
    lets: List[VarBinding],
    realTerm: Term
  ): EitherNel[String, Term] = {
    def nestLets(binding: List[VarBinding]): Term = {
      binding match {
        case ::(current, next) => Let(current, List(), nestLets(next))
        case Nil => realTerm
      }
    }

    if (lets.isEmpty)
      Right(realTerm)
    else {
      val (sortedLets, badLets) =
        runTsort[VarBinding](lets, _.term, _.name.name)

      if (badLets.nonEmpty)
        Left(
          NonEmptyList.one(
            s"The following local bindings form a circular dependency: $sortedLets"
          )
        )
      else
        Right(nestLets(sortedLets))
    }
  }

  def removeProblematicCalls(fun: FunDef, badFuns: Set[SSymbol]): FunDef = {
    def recurse(t: Term): Term = {
      t match {

        case let @ Let(x, xs, term) => let.copy(
            binding = x.copy(term = recurse(x.term)),
            bindings = xs.map(x => x.copy(term = recurse(x.term))),
            term = recurse(term)
          )
        case forAll @ Forall(_, _, term) => forAll.copy(term = recurse(term))
        case exists @ Exists(_, _, term) => exists.copy(term = recurse(term))
        case call @ FunctionApplication(calledFun, terms) =>
          if (badFuns.contains(calledFun.id.symbol)) {
            fun.returnSort match {
              case BoolSort() => True()
              case _ => SNumeral(8008)
            }
          } else {
            call.copy(terms = terms.map(recurse))
          }
        case other => other
      }
    }

    fun.copy(body = recurse(fun.body))

    //    val res = fun.copy(body = recurse(fun.body) match {
//      case QualifiedIdentifier(Identifier(SSymbol("true"), _), _) =>
//        SNumeral(8008)
//      case good => good
//    })
//
//    res
  }

}
