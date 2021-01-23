package essent

import firrtl.ir.{Expression, Info, MultiInfo, Statement}

import collection.mutable.{ArrayBuffer, HashMap, ListBuffer}
import scala.collection.{GenTraversableOnce, IterableLike, immutable, mutable}
import scala.collection.generic.CanBuildFrom
import scala.reflect.ClassTag

object Util {
  // Given an array, returns a map of value to all indices that had that value (CAM-like)
  def groupIndicesByValue[T](a: ArrayBuffer[T]): Map[T, Seq[Int]] = {
    a.zipWithIndex.groupBy{ _._1 }.mapValues{ v => v.toSeq map { _._2 }}
  }

  // Given a list of pairs, returns a map of value of first element to all second values (CAM-like)
  def groupByFirst[T,Y](l: Seq[(T,Y)]): Map[T, Seq[Y]] = {
    l.groupBy{ _._1 }.mapValues{ v => v map { _._2 }}
  }

  def selectFromMap[K,V](selectors: Seq[K], targetMap: Map[K,V]): Map[K,V] = {
    (selectors flatMap {
      k => if (targetMap.contains(k)) Seq(k -> targetMap(k)) else Seq()
    }).toMap
  }

  def tidyString(str: String): String = {
    val charsToRemove = Set(' ', ',', '.', '(', ')')
    str filter { !charsToRemove.contains(_) }
  }

  def sortHashMapValues[K](hm: HashMap[K,Seq[Int]]) {
    hm.keys foreach { k => hm(k) = hm(k).sorted }
  }

  /**
   * Utilities to add to the [[TraversableOnce]]-derived classes, which is most of the list classes
   * @param iter
   * @tparam A
   * @tparam Repr
   */
  implicit class TraversableOnceUtils[+A](iter: TraversableOnce[A]) {
    /**
     * Find the first occurence of the given item as an [[Option]]
     * @param item
     * @tparam B
     * @return
     */
    def getOption(item: Any): Option[A] = iter.find(item.equals(_))

    /**
     * Find the items which are equal in this collection
     * @param item
     * @return
     */
    def findEqual(item: Any): TraversableOnce[A] = iter.filter(_.equals(item))

    /**
     * Convert a list of 2-tuples to a map of lists, with the left element being the key and the right the value
     * @tparam T key type
     * @tparam U value type
     */
    def toMapOfLists[T, U](implicit tagT: ClassTag[T], tagU: ClassTag[U], ev: A <:< (T, U)): mutable.Map[T, ListBuffer[U]] = {
      val b = mutable.Map[T, mutable.ListBuffer[U]]()
      for ((k:T, v:U) <- iter) {
        b.getOrElseUpdate(k, new mutable.ListBuffer[U]()).append(v)
      }

      b
    }
  }

  implicit class StatementUtils(stmt: Statement) {
    /**
     * Same as foreachInfo on [[Statement]] except that this one recursively finds the [[Info]]s contained in [[MultiInfo]]
     */
    def foreachInfoRecursive(f: Info => Unit): Unit = {
      def foreachInfoRecursiveHelper(info: Info): Unit = info match {
        case MultiInfo(infos) => {
          f(info) // handle the MultiInfo itself, if desired
          infos foreach foreachInfoRecursiveHelper // recurse to handle the real infos
        }
        case _ => f(info) // this is a normal info object, handle it
      }

      stmt foreachInfo foreachInfoRecursiveHelper
    }

    /**
     * Same as mapExpr on [[Statment]] except that this one recursively descends into the expressions contained in the tree
     */
    def mapExprRecursive(f: Expression => Expression): Statement = {
      def mapExprRecursiveHelper(expr: Expression): Expression = {
        f(expr).mapExpr(mapExprRecursiveHelper)
      }

      stmt mapExpr mapExprRecursiveHelper
    }
  }

  implicit class ExpressionUtils(expr: Expression) {
    /**
     * Similar to foreachExpr but also recurses through sub-expressions, calling their foreachExpr
     * @param f
     */
    def foreachExprRecursive(f: Expression => Unit): Unit = {
      def foreachExprRecursiveHelper(e: Expression): Unit = {
        f(e)
        e foreachExpr foreachExprRecursiveHelper
      }

      f(expr)
      expr foreachExpr foreachExprRecursiveHelper
    }
  }
}