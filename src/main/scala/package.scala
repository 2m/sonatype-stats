import scala.collection.immutable

package object sonatypestats {
  type Seq[A] = immutable.Seq[A]
  val Seq = immutable.Seq
}
