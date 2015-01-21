package ppl.delite.framework.datastructures

import java.io.PrintWriter
import scala.virtualization.lms.common._
import scala.reflect.{SourceContext, RefinedManifest}
import scala.virtualization.lms.internal.{GenerationFailedException, GenericFatCodegen}
import ppl.delite.framework.ops._
import ppl.delite.framework.Util._
import ppl.delite.framework.Config

// Abstract, n-dimensional, multi-purpose array
// Intended for use at frontend w/ transformer to concrete data layouts
trait DeliteMultiArray[T]
trait DeliteArray1D[T] extends DeliteMultiArray[T]
trait DeliteArray2D[T] extends DeliteMultiArray[T]
trait DeliteArray3D[T] extends DeliteMultiArray[T]
trait DeliteArray4D[T] extends DeliteMultiArray[T]
trait DeliteArray5D[T] extends DeliteMultiArray[T]

// TODO: Really not sure about this abstraction yet, except for type signature [T,R]
// Seq[Seq[Int]] intended to represent various dimension layering and order... potentially could work

trait DeliteLayouts {
  abstract class Layout[T:Manifest,R:Manifest] {
    val dims: Seq[Seq[Int]]
    val rank: Int
  }

  // Assumed in order for now..
  case class FlatLayout[T:Manifest](n: Int) extends Layout[T,T] { 
    override val dims = Seq(Seq.tabulate(n){i=>i+1}) 
    override val rank = n
  }
  case class SinglyNested[T:Manifest](n: Int, inner: Int) extends Layout[T,DeliteArray[T]] { 
    override val dims = Seq(Seq.tabulate(inner-1){i=>i+1},Seq.tabulate(r-inner+1){i=>i+inner}) 
    override val rank = r
  }
}

trait DeliteMultiArrayOps extends Base with DeliteLayouts {
  
  object DeliteMultiArray {
    def apply[T:Manifest](dims: Rep[Int]*)(implicit ctx: SourceContext) = dmultia_new(dims)
    def imm[T:Manifest](dims: Rep[Int]*)(implicit ctx: SourceContext) = dmultia_new_immutable(dims)
    def fromFunction[T:Manifest](dims: Rep[Int]*)(func: Rep[Seq[Rep[Int]]] => Rep[T])(implicit ctx: SourceContext) = dmultia_fromfunction(dims, func)

    def sortIndices(length: Rep[Int])(comparator: (Rep[Int], Rep[Int]) => Rep[Int])(implicit ctx: SourceContext) = dmultia_sortIndices(length, comparator)
  }

  implicit def repMultiAtoMultiAOps[T:Manifest](ma: Rep[DeliteMultiArray[T]])(implicit ctx: SourceContext) = new DeliteMultiArrayOpsCls(ma)
  implicit def repArray1DtoArray1DOps[T:Manifest](ma: Rep[DeliteArray1D[T]])(implicit ctx: SourceContext) = new DeliteArray1DOpsCls(ma)

  class DeliteMultiArrayOpsCls[T:Manifest](ma: Rep[DeliteMultiArray[T]])(implicit ctx: SourceContext) {
    // --- rank casts
    def as1D: Rep[DeliteArray1D[T]] = dmultia_as_1D(ma)
    def as2D: Rep[DeliteArray2D[T]] = dmultia_as_2D(ma)
    def as3D: Rep[DeliteArray3D[T]] = dmultia_as_3D(ma)
    def as4D: Rep[DeliteArray4D[T]] = dmultia_as_4D(ma)
    def as5D: Rep[DeliteArray5D[T]] = dmultia_as_5D(ma)

    // --- properties
    def rank: Rep[Int] = dmultia_rank(ma)
    def shape: Rep[Seq[Rep[Int]]] = dmultia_shape(ma)
    def size: Rep[Int] = dmultia_size(ma)
    
    // --- single element
    def apply(i: Rep[Int]*): Rep[T] = dmultia_apply(ma,i)
    def update(i: Seq[Rep[Int]], x: Rep[T]): Rep[Unit] = dmultia_update(ma,i,x)
    
    // --- copies/reshaping
    def mutable = dmultia_mutable(ma)
    
    // --- parallel ops
    def map[B:Manifest](f: Rep[T] => Rep[B]): Rep[DeliteMultiArray[B]] = dmultia_map(ma,f)
    def zip[B:Manifest,R:Manifest](y: Rep[DeliteMultiArray[B]])(f: (Rep[T],Rep[B]) => Rep[R]): Rep[DeliteMultiArray[R]] = dmultia_zipwith(ma,y,f)
    def reduce(f: (Rep[T],Rep[T]) => Rep[T], zero: Rep[T]): Rep[T] = dmultia_reduce(ma,f,zero)
    def foreach(f: Rep[T] => Rep[Unit]): Rep[Unit] = dmultia_foreach(ma,f)
    def filter(f: Rep[T] => Rep[Boolean]): Rep[DeliteMultiArray[B]] = dmultia_filter(ma,f)
    def flatMap[B:Manifest](f: Rep[T] => Rep[DeliteMultiArray[B]])(implicit ctx: SourceContext) = dmultia_flatmap(ma,f)
    def groupByReduce[K:Manifest, V:Manifest](key: Rep[T] => Rep[K], value: Rep[T] => Rep[V], reduce: (Rep[V],Rep[V]) => Rep[V]) = dmultia_groupByReduce(ma,key,value,reduce)
    
    def mmap(f: Rep[T] => Rep[T]): Rep[Unit] = dmultia_mmap(ma,f)
    def mzip(y: Rep[DeliteMultiArray[T]])(f: (Rep[T],Rep[T]) => Rep[T]): Rep[Unit] = dmultia_mzipwith(ma,y,f)
  }

  class DeliteArray1DOpsCls[T:Manifest](ma: Rep[DeliteArray1D[T]])(implicit ctx: SourceContext) {
    // --- properties
    def length: Rep[Int] = dmultia_size(ma)

    // --- single element
    def apply(i: Rep[Int]): Rep[T] = dmultia_apply(ma,Seq(i))
    def update(i: Rep[Int], x: Rep[T]): Rep[Unit] = dmultia_update(ma,Seq(i),x)
  }

  // --- Rank type casts
  def dmultia_as_1D[T:Manifest](ma: Rep[DeliteMultiArray[T]]): Rep[DeliteArray1D[T]]
  def dmultia_as_2D[T:Manifest](ma: Rep[DeliteMultiArray[T]]): Rep[DeliteArray2D[T]]
  def dmultia_as_3D[T:Manifest](ma: Rep[DeliteMultiArray[T]]): Rep[DeliteArray3D[T]]
  def dmultia_as_4D[T:Manifest](ma: Rep[DeliteMultiArray[T]]): Rep[DeliteArray4D[T]]
  def dmultia_as_5D[T:Manifest](ma: Rep[DeliteMultiArray[T]]): Rep[DeliteArray5D[T]]

  // --- Compiler internal
  //def dmultia_element[A:Manifest](ma: Rep[DeliteMultiArray[A]]): Rep[A]

  // --- Array constructors
  def dmultia_new[A:Manifest](dims: Seq[Rep[Int]])(implicit ctx: SourceContext): Rep[DeliteMultiArray[A]]
  def dmultia_new_immutable[A:Manifest](dims: Seq[Rep[Int]])(implicit ctx: SourceContext): Rep[DeliteMultiArray[A]]
  def dmultia_fromfunction[A:Manifest](dims: Seq[Rep[Int]], f: Rep[Seq[Rep[Int]]] => Rep[A])(implicit ctx: SourceContext): Rep[DeliteMultiArray[A]]

  def dmultia_view[A:Manifest](ma: Rep[DeliteMultiArray[A]], start: Seq[Rep[Int]], stride: Seq[Rep[Int]], dims: Seq[Rep[Int]])(implicit ctx: SourceContext): Rep[DeliteMultiArray[A]]

  // --- Array properties
  def dmultia_rank[A:Manifest](ma: Rep[DeliteMultiArray[A]])(implicit ctx: SourceContext): Rep[Int]
  def dmultia_shape[A:Manifest](ma: Rep[DeliteMultiArray[A]])(implicit ctx: SourceContext): Rep[Seq[Rep[Int]]]
  def dmultia_size[A:Manifest](ma: Rep[DeliteMultiArray[A]])(implicit ctx: SourceContext): Rep[Int]

  // --- Array single element
  def dmultia_apply[A:Manifest](ma: Rep[DeliteMultiArray[A]], i: Seq[Rep[Int]])(implicit ctx: SourceContext): Rep[A]
  def dmultia_update[A:Manifest](ma: Rep[DeliteMultiArray[A]], i: Seq[Rep[Int]], x: Rep[A])(implicit ctx: SourceContext): Rep[Unit]

  // --- Array copies / reshaping
  def dmultia_mutable[A:Manifest](ma: Rep[DeliteMultiArray[A]])(implicit ctx: SourceContext): Rep[DeliteMultiArray[A]]
  def dmultia_immutable[A:Manifest](ma: Rep[DeliteMultiArray[A]])(implicit ctx: SourceContext): Rep[DeliteMultiArray[A]]
  def dmultia_permute[A:Manifest](ma: Rep[DeliteMultiArray[A]], config: Seq[Int])(implicit ctx: SourceContext): Rep[DeliteMultiArray[A]]
  def dmultia_reshape[A:Manifest](ma: Rep[DeliteMultiArray[A]], shape: Seq[Rep[Int]])(implicit ctx: SourceContext): Rep[DeliteMultiArray[A]]

  // --- Array parallel ops
  def dmultia_map[A:Manifest,B:Manifest](ma: Rep[DeliteMultiArray[A]], f: Rep[A] => Rep[B])(implicit ctx: SourceContext): Rep[DeliteMultiArray[B]]
  def dmultia_zipwith[A:Manifest,B:Manifest,R:Manifest](ma: Rep[DeliteMultiArray[A]], mb: Rep[DeliteMultiArray[B]], f: (Rep[A],Rep[B]) => Rep[R])(implicit ctx: SourceContext): Rep[DeliteMultiArray[R]]
  def dmultia_reduce[A:Manifest](ma: Rep[DeliteMultiArray[A]], f: (Rep[A],Rep[A]) => Rep[A], zero: Rep[A])(implicit ctx: SourceContext): Rep[A]
  def dmultia_forindices[A:Manifest](ma: Rep[DeliteMultiArray[A]], f: Rep[Seq[Rep[Int]]] => Rep[Unit])(implicit ctx: SourceContext): Rep[Unit]
  def dmultia_foreach[A:Manifest](ma: Rep[DeliteMultiArray[A]], f: Rep[A] => Rep[Unit])(implicit ctx: SourceContext): Rep[Unit]
  
  def dmultia_mmap[A:Manifest](ma: Rep[DeliteMultiArray[A]], f: Rep[A] => Rep[A])(implicit ctx: SourceContext): Rep[Unit]
  def dmultia_mzipwith[A:Manifest,B:Manifest](ma: Rep[DeliteMultiArray[A]], mb: Rep[DeliteMultiArray[B]], f: (Rep[A],Rep[B]) => Rep[A])(implicit ctx: SourceContext): Rep[Unit] 

  def dmultia_NDmap[A:Manifest,B:Manifest](ma: Rep[DeliteMultiArray[A]], mdims: Seq[Int], func: Rep[DeliteMultiArray[A]] => Rep[DeliteMultiArray[B]])(implicit ctx: SourceContext): Rep[DeliteMultiArray[B]]

  // --- Buffer ops
  def dmultia_NDinsert[A:Manifest](ma: Rep[DeliteMultiArray[A]], rhs: Rep[DeliteMultiArray[A]], axis: Int, index: Rep[Int])(implicit ctx: SourceContext): Rep[Unit]
  def dmultia_NDappend[A:Manifest](ma: Rep[DeliteMultiArray[A]], rhs: Rep[DeliteMultiArray[A]], axis: Int)(implicit ctx: SourceContext): Rep[Unit]

  def dmultia_insertAll[A:Manifest](ma: Rep[DeliteMultiArray[A]], rhs: Rep[DeliteMultiArray[A]], axis: Int, index: Rep[Int])(implicit ctx: SourceContext): Rep[Unit]
  def dmultia_appendAll[A:Manifest](ma: Rep[DeliteMultiArray[A]], rhs: Rep[DeliteMultiArray[A]], axis: Int)(implicit ctx: SourceContext): Rep[Unit]

  // --- Misc.
  def dmultia_mkstring[A:Manifest](ma: Rep[DeliteMultiArray[A]], dels: Seq[Rep[String]])(implicit ctx: SourceContext): Rep[String]
  def dmultia_string_split(str: Rep[String],pat: Rep[String],ofs: Rep[Int] = unit(0))(implicit __imp0: SourceContext): Rep[DeliteArray1D[String]]

  // --- Misc. 1D Operations
  def dmultia_sortIndices(len: Rep[Int], comp: (Rep[Int],Rep[Int]) => Rep[Int])(implicit ctx: SourceContext): Rep[DeliteArray1D[A]]
 
  def dmultia_flatmap[A:Manifest,B:Manifest](ma: Rep[DeliteArray1D[A]], f: Rep[A] => Rep[DeliteMultiArray[B]])(implicit ctx: SourceContext): Rep[DeliteArray1D[B]]
  def dmultia_filter[A:Manifest](ma: Rep[DeliteArray1D[A]], f: Rep[A] => Rep[Boolean])(implicit ctx: SourceContext): Rep[DeliteArray1D[A]]
  def dmultia_groupBy[A:Manifest,K:Manifest](ma: Rep[DeliteArray1D[A]], key: Rep[A] => Rep[K])(implicit ctx: SourceContext): Rep[DeliteMultiMap[A,DeliteArray1D[K]]]
  def dmultia_groupByReduce[A:Manifest,K:Manifest,V:Manifest](ma: Rep[DeliteArray1D[A]], key: Rep[A] => Rep[K], value: Rep[A] => Rep[V], reduce: (Rep[V],Rep[V]) => Rep[V])(implicit ctx: SourceContext): Rep[DeliteMultiMap[K,V]]

  def dmultia_insert[A:Manifest](ma: Rep[DeliteArray1D[A]], x: Rep[A], index: Rep[Int])(implicit ctx: SourceContext): Rep[Unit]
  def dmultia_append[A:Manifest](ma: Rep[DeliteArray1D[A]], x: Rep[A])(implicit ctx: SourceContext): Rep[Unit]

  // --- 2D Operations
  def dmultia_matmult[A:Manifest:Numeric](lhs: Rep[DeliteArray2D[A]], rhs: DeliteArray2D[A])(implicit ctx: SourceContext): Rep[DeliteArray2D[A]]
  def dmultia_matvecmult[A:Manifest:Numeric](mat: Rep[DeliteArray2D[A]], vec: DeliteArray1D[A])(implicit ctx: SourceContext): Rep[DeliteArray1D[A]]

  // --- Pinning
  def dmultia_pin[T:Manifest,R:Manifest](ma: Rep[DeliteMultiArray[T]], layout: Layout[T,R])(implicit ctx: SourceContext): Rep[DeliteArray[R]]
  def dmultia_unpin[T:Manifest,R:Manifest](in: Rep[DeliteArray[R]], layout: Layout[T,R], shape: Seq[Rep[Int]])(implicit ctx: SourceContext): Rep[DeliteMultiArray[T]]
}

trait DeliteMultiArrayOpsExp extends BaseExp with DeliteMultiArrayOps {
  this: DeliteOpsExp with DeliteMultiMapOpsExp with DeliteArrayOpsExp =>

  abstract class DefWithManifest2[A:Manifest,B:Manifest,R:Manifest] extends DefWithManifest[A,R] {
    val mB = manifest[B]
  }
  abstract class DefWithManifest3[A:Manifest,B:Manifest,T:Manifest,R:Manifest] extends DefWithManifest2[A,B,R] {
    val mT = manifest[T]
  }

  // Different than DeliteOps version - never adds any effects right now
  private def reflectPure[A:Manifest](d: Def[A])(implicit ctx: SourceContext): Exp[A] = toAtom(d)

  /////////////////////
  // Abstract IR Nodes

  // --- Compiler internal nodes
  // TODO: do we need this? would fresh[T] be good enough for reifyEffects on effectful loop bodies?
  //case class DeliteMultiArrayElement[T:Manifest](ma: Exp[DeliteMultiArray[T]]) extends DefWithManifest[T,T]

  // --- Array constructors
  case class DeliteMultiArrayNew[T](dims: Seq[Exp[Int]]) extends DefWithManifest[T,DeliteMultiArray[T]]
  case class DeliteMultiArrayFromFunction[T](dims: Seq[Exp[Int]], func: Exp[Seq[Exp[Int]]] => Exp[T]) extends DefWithManifest[T,DeliteMultiArray[T]]
  case class DeliteMultiArrayView[T:Manifest](ma: Exp[DeliteMultiArray[T]], start: Seq[Exp[Int]], stride: Seq[Exp[Int]], dims: Seq[Exp[Int]])(implicit ctx: SourceContext) extends DefWithManifest[T,DeliteMultiArray[T]]

  // --- Array properties
  case class DeliteMultiArrayRank[T:Manifest](ma: Exp[DeliteMultiArray[T]]) extends DefWithManifest[T,Int]
  case class DeliteMultiArrayShape[T:Manifest](ma: Exp[DeliteMultiArray[T]]) extends DefWithManifest[T,Seq[Exp[Int]]]
  case class DeliteMultiArraySize[T:Manifest](ma: Exp[DeliteMultiArray[T]]) extends DefWithManifest[T,Int]

  case class DeliteMultiArrayViewTarget[T:Manifest](v: Exp[DeliteMultiArray[T]]) extends DefWithManifest[T,DeliteMultiArray[T]]
  case class DeliteMultiArrayViewStart[T:Manifest](v: Exp[DeliteMultiArray[T]]) extends DefWithManifest[T,Seq[Exp[Int]]]
  case class DeliteMultiArrayViewStride[T:Manifest](v: Exp[DeliteMultiArray[T]]) extends DefWithManifest[T,Seq[Exp[Int]]]

  // --- Array single element ops
  case class DeliteMultiArrayApply[T:Manifest](ma: Exp[DeliteMultiArray[T]], i: Seq[Exp[Int]]) extends DefWithManifest[T,T]
  case class DeliteMultiArrayUpdate[T:Manifest](ma: Exp[DeliteMultiArray[T]], i: Seq[Exp[Int]], x: Exp[T]) extends DefWithManifest[T,Unit]

  // --- Array permute / reshaping
  case class DeliteMultiArrayPermute[T:Manifest](ma: Exp[DeliteMultiArray[T]], config: Seq[Int])(implicit ctx: SourceContext) extends DefWithManifest[T,DeliteMultiArray[T]]
  case class DeliteMultiArrayReshape[T:Manifest](ma: Exp[DeliteMultiArray[T]], dims: Seq[Exp[Int]])(implicit ctx: SourceContext) extends DefWithManifest[T,DeliteMultiArray[T]]

  // --- Array parallel ops
  case class DeliteMultiArrayMap[A:Manifest,B:Manifest](in: Exp[DeliteMultiArray[A]], func: Exp[A] => Exp[B])(implicit ctx: SourceContext) extends DefWithManifest2[A,B,DeliteMultiArray[B]]
  case class DeliteMultiArrayZipWith[A:Manifest,B:Manifest,R:Manifest](inA: Exp[DeliteMultiArray[A]], inB: Exp[DeliteMultiArray[B]], func: (Exp[A],Exp[B]) => Exp[R]) extends DefWithManifest3[A,B,R,DeliteMultiArray[R]]
  case class DeliteMultiArrayReduce[A:Manifest](in: Exp[DeliteMultiArray[A]], func: (Exp[A],Exp[A]) => Exp[A], zero: Exp[A])(implicit ctx: SourceContext) extends DefWithManifest[A,DeliteMultiArray[A]]
  case class DeliteMultiArrayForeach[A:Manifest](in: Exp[DeliteMultiArray[A]], func: Exp[A] => Exp[Unit])(implicit ctx: SourceContext) extends DefWithManifest[A,Unit]
  case class DeliteMultiArrayForIndices[A:Manifest,R:Manifest](in: Exp[DeliteMultiArray[A]], func: Exp[Seq[Exp[Int]]] => Exp[R])(implicit ctx: SourceContext) extends DefWithManifest2[A,R,Unit]
  case class DeliteMultiArrayMutableMap[A:Manifest](in: Exp[DeliteMultiArray[A]], func: Exp[A] => Exp[A])(implicit ctx: SourceContext) extends DefWithManifest[A,Unit]
  case class DeliteMultiArrayMutableZipWith[A:Manifest,B:Manifest](inA: Exp[DeliteMultiArray[A]], inB: Exp[DeliteMultiArray[B]], func: (Exp[A],Exp[A]) => Exp[A])(implicit ctx: SourceContext) extends DefWithManifest2[A,B,Unit]

  case class DeliteMultiArrayNDMap[A:Manifest,B:Manifest](in: Exp[DeliteMultiArray[A]], mdims: Seq[Int], func: Exp[DeliteMultiArray[A]] => Exp[DeliteMultiArray[B]])(implicit ctx: SourceContext) extends DefWithManifest2[A,B,DeliteMultiArray[B]]

  // --- Buffer operations
  case class DeliteMultiArrayNDInsert[T:Manifest](ma: Exp[DeliteMultiArray[T]], rhs: Exp[DeliteMultiArray[T]], axis: Int, index: Exp[Int])(implicit ctx: SourceContext) extends DefWithManifest[T,Unit]
  case class DeliteMultiArrayNDAppend[T:Manifest](ma: Exp[DeliteMultiArray[T]], rhs: Exp[DeliteMultiArray[T]], axis: Int)(implicit ctx: SourceContext) extends DefWithManifest[T,Unit]

  case class DeliteMultiArrayInsertAll[T:Manifest](ma: Exp[DeliteMultiArray[T]], rhs: Exp[DeliteMultiArray[T]], axis: Int, index: Exp[Int])(implicit ctx: SourceContext) extends DefWithManifest[T,Unit]
  case class DeliteMultiArrayAppendAll[T:Manifest](ma: Exp[DeliteMultiArray[T]], rhs: Exp[DeliteMultiArray[T]], axis: Int)(implicit ctx: SourceContext) extends DefWithManifest[T,Unit]

  // --- Misc. Operations
  case class DeliteMultiArrayMkString[T:Manifest](ma: Exp[DeliteMultiArray[T]], dels: Seq[Exp[String]]) extends DefWithManifest[T,String]
  case class DeliteStringSplit(str: Exp[String], split: Exp[String], lim: Exp[Int]) extends Def[DeliteArray1D[String]]

  // --- 1D Operations
  case class DeliteMultiArraySortIndices(length: Exp[Int], comparator: (Exp[Int],Exp[Int]) => Exp[Int]) extends Def[DeliteArray1D[Int]]
  case class DeliteMultiArrayMapFilter[A:Manifest,B:Manifest](in: Exp[DeliteArray1D[A]], func: Exp[A] => Exp[B], cond: Exp[A] => Exp[Boolean])(implicit ctx: SourceContext) extends DefWithManifest2[A,B,DeliteArray1D[B]]
  case class DeliteMultiArrayFlatMap[A:Manifest,B:Manifest](in: Exp[DeliteArray1D[A]], func: Exp[A] => Exp[DeliteArray1D[B]])(implicit ctx: SourceContext) extends DefWithManifest2[A,B,DeliteArray1D[B]]
  case class DeliteMultiArrayGroupBy[A:Manifest,K:Manifest](in: Exp[DeliteArray1D[A]], key: Exp[A] => Exp[K])(implicit ctx: SourceContext) extends DefWithManifest2[A,K,DeliteMultiMap[K,DeliteArray1D[A]]]
  case class DeliteMultiArrayGroupByReduce[A:Manifest,K:Manifest,V:Manifest](in: Exp[DeliteArray1D[A]], key: Exp[A] => Exp[K], value: Exp[A] => Exp[V], reduce: (Exp[V],Exp[V]) => Exp[V])(implicit ctx: SourceContext) extends DefWithManifest3[A,K,V,DeliteMultiMap[K,V]]

  case class DeliteMultiArrayInsert[T:Manifest](ma: Exp[DeliteArray1D[A]], x: Exp[T], index: Exp[Int]) extends DefWithManifest[T,Unit]
  case class DeliteMultiArrayAppend[T:Manifest](ma: Exp[DeliteArray1D[A]], x: Exp[T]) extends DefWithManifest[T,Unit]

  // --- 2D Operations
  case class DeliteMatrixMultiply[T:Manifest:Numeric](lhs: Exp[DeliteArray2D[T]], rhs: Exp[DeliteArray2D[T]]) extends DefWithManifest[T,DeliteArray2D[T]]
  case class DeliteMatrixVectorMultiply[T:Manifest:Numeric](mat: Exp[DeliteArray2D[T]], vec: Exp[DeliteArray1D[T]]) extends DefWithManifest[T,DeliteArray1D[T]]

  // --- Data type pinning
  // TODO: figure out layout type for these pinning nodes - should match up with what is used in analysis
  case class DeliteMultiArrayPin[T:Manifest,R:Manifest](ma: Exp[DeliteMultiArray[T]], layout: Layout[T,R]) extends DefWithManifest2[T,R,DeliteArray[R]]
  case class DeliteMultiArrayUnpin[T:Manifest,R:Manifest](in: Exp[DeliteArray[R]], layout: Layout[T,R], shape: Seq[Exp[Int]]) extends DefWithManifest[T,R,DeliteMultiArray[T]]

  //////////////////////
  // Array IR Functions

  // --- Rank type casts
  def dmultia_as_1D[T:Manifest](ma: Exp[DeliteMultiArray[T]]) = ma.asInstanceOf[Exp[DeliteArray1D[T]]]
  def dmultia_as_2D[T:Manifest](ma: Exp[DeliteMultiArray[T]]) = ma.asInstanceOf[Exp[DeliteArray2D[T]]]
  def dmultia_as_3D[T:Manifest](ma: Exp[DeliteMultiArray[T]]) = ma.asInstanceOf[Exp[DeliteArray3D[T]]]
  def dmultia_as_4D[T:Manifest](ma: Exp[DeliteMultiArray[T]]) = ma.asInstanceOf[Exp[DeliteArray4D[T]]]
  def dmultia_as_5D[T:Manifest](ma: Exp[DeliteMultiArray[T]]) = ma.asInstanceOf[Exp[DeliteArray5D[T]]]

  // --- Compiler internal
  //def dmultia_element[A:Manifest](ma: Exp[DeliteMultiArray[A]]): Exp[A] = reflectPure(DeliteMultiArrayElement(ma))

  // --- Array constructors
  def dmultia_new[A:Manifest](dims: Seq[Exp[Int]])(implicit ctx: SourceContext) = reflectMutable(DeliteMultiArrayNew(dims,manifest[A]))
  def dmultia_new_immutable[A:Manifest](dims: Seq[Exp[Int]])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArrayNew(dims,manifest[A]))
  def dmultia_fromfunction[A:Manifest](dims: Seq[Exp[Int]], f: Exp[Seq[Exp[Int]]] => Exp[A])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArrayFromFunction(dims,f))

  def dmultia_view[A:Manifest](ma: Exp[DeliteMultiArray[A]], start: Seq[Exp[Int]], stride: Seq[Exp[Int]], dims: Seq[Exp[Int]])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArrayView(ma,start,stride,dims))

  // --- Array properties
  def dmultia_rank[A:Manifest](ma: Exp[DeliteMultiArray[A]])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArrayRank(ma))
  def dmultia_shape[A:Manifest](ma: Exp[DeliteMultiArray[A]])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArrayShape(ma))
  def dmultia_size[A:Manifest](ma: Exp[DeliteMultiArray[A]])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArraySize(ma))

  def dmultia_view_target[A:Manifest](ma: Exp[DeliteMultiArray[A]]) = reflectPure(DeliteMultiArrayViewTarget(ma))
  def dmultia_view_start[A:Manifest](ma: Exp[DeliteMultiArray[A]]) = reflectPure(DeliteMultiArrayViewStart(ma))
  def dmultia_view_stride[A:Manifest](ma: Exp[DeliteMultiArray[A]]) = reflectPure(DeliteMultiArrayViewStride(ma))

  // --- Array single element ops
  def dmultia_apply[A:Manifest](ma: Exp[DeliteMultiArray[A]], i: Seq[Exp[Int]])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArrayApply(ma,i))
  def dmultia_update[A:Manifest](ma: Exp[DeliteMultiArray[A]], i: Seq[Exp[Int]], x: Exp[A])(implicit ctx: SourceContext) = reflectWrite(ma)(DeliteMultiArrayUpdate(ma,i,x))

  // --- Array permute / reshaping
  def dmultia_permute[A:Manifest](ma: Exp[DeliteMultiArray[A]], config: Seq[Int])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArrayPermute(ma,config))
  def dmultia_reshape[A:Manifest](ma: Exp[DeliteMultiArray[A]], dims: Seq[Exp[Int]])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArrayReshape(ma,dims))
  
  // --- Array copying
  // Doubles as View's toDense operation
  // TODO: do we need a symbol hint for this?
  def dmultia_clone[A:Manifest](ma: Exp[DeliteMultiArray[A]])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArrayMap(ma,{(e: Exp[A])=>e)})
  def dmultia_mutable[A:Manifest](ma: Exp[DeliteMultiArray[A]])(implicit ctx: SourceContext) = reflectMutable(DeliteMultiArrayMap(ma,{(e:Exp[A])=>e)})
  def dmultia_immutable[A:Manifest](ma: Exp[DeliteMultiArray[A]])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArrayMap(ma,{(e:Exp[A])=>e)})
  
  // --- Array parallel ops
  def dmultia_map[A:Manifest,B:Manifest](ma: Exp[DeliteMultiArray[A]], f: Exp[A] => Exp[B])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArrayMap(ma,f))
  def dmultia_zipwith[A:Manifest,B:Manifest,R:Manifest](ma: Exp[DeliteMultiArray[A]], mb: Exp[DeliteMultiArray[B]], f: (Exp[A],Exp[B]) => Exp[R])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArrayZipWith(ma,mb,f))
  def dmultia_reduce[A:Manifest](ma: Exp[DeliteMultiArray[A]], f: (Exp[A],Exp[A]) => Exp[A], zero: Exp[A])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArrayReduce(ma,f))
  def dmultia_forindices[A:Manifest](ma: Exp[DeliteMultiArray[A]], f: Exp[Seq[Exp[Int]]] => Exp[Unit])(implicit ctx: SourceContext) = {
    val df = DeliteMultiArrayForIndices(ma,f)
    val v = fresh[Seq[Exp[Int]]]
    reflectEffect(df, summarizeEffects(reifyEffects(f(v))).star andAlso Simple())
  }
  def dmultia_foreach[A:Manifest](ma: Exp[DeliteMultiArray[A]], f: Exp[A] => Exp[Unit])(implicit ctx: SourceContext) = {
    val df = DeliteMultiArrayForeach(ma,f)
    val v = fresh[A]
    reflectEffect(df, summarizeEffects(reifyEffects(f(v))).star andAlso Simple())
  }
  def dmultia_mmap[A:Manifest](ma: Exp[DeliteMultiArray[A]], f: Exp[A] => Exp[A])(implicit ctx: SourceContext) = reflectWrite(ma)(DeliteMultiArrayMutableMap(ma,f))
  def dmultia_mzipwith[A:Manifest](ma: Exp[DeliteMultiArray[A]], mb: Exp[DeliteMultiArray[A]], f: (Exp[A],Exp[A]) => Exp[A])(implicit ctx: SourceContext) = reflectWrite(ma)(DeliteMultiArrayMutableZipWith(ma,mb,f))

  def dmultia_NDmap[A:Manifest,B:Manifest](ma: Exp[DeliteMultiArray[A]], mdims: Seq[Int], func: Exp[DeliteMultiArray[A]] => Exp[DeliteMultiArray[B]])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArrayNDMap(ma,mdims,func))

  // --- Buffer ops
  def dmultia_NDinsert[A:Manifest](ma: Exp[DeliteMultiArray[A]], rhs: Exp[DeliteMultiArray[A]], axis: Int, index: Exp[Int])(implicit ctx: SourceContext) = reflectWrite(ma)(DeliteMultiArrayNDInsert(ma,rhs,axis,index))
  def dmultia_NDappend[A:Manifest](ma: Exp[DeliteMultiArray[A]], rhs: Exp[DeliteMultiArray[A]], axis: Int)(implicit ctx: SourceContext) = reflectWrite(ma)(DeliteMultiArrayNDAppend(ma,rhs,axis))

  def dmultia_insertAll[A:Manifest](ma: Exp[DeliteMultiArray[A]], rhs: Exp[DeliteMultiArray[A]], axis: Int, index: Exp[Int])(implicit ctx: SourceContext) = reflectWrite(ma)(DeliteMultiArrayInsertAll(ma,rhs,axis,index))
  def dmultia_appendAll[A:Manifest](ma: Exp[DeliteMultiArray[A]], rhs: Exp[DeliteMultiArray[A]], axis: Int)(implicit ctx: SourceContext) = reflectWrite(ma)(DeliteMultiArrayAppendAll(ma,rhs,axis))

  // --- Misc.
  def dmultia_mkstring[A:Manifest](ma: Exp[DeliteMultiArray[A]], dels: Seq[Exp[String]])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArrayMkString(ma,dels))
  def dmultia_string_split(str: Exp[String], pat: Exp[String], ofs: Exp[Int] = unit(0))(implicit ctx: SourceContext) = reflectPure(DeliteStringSplit(str,pat,ofs))

  // --- 1D Operations
  def dmultia_sortIndices(len: Exp[Int], comp: (Exp[Int],Exp[Int]) => Exp[Int])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArraySortIndices(len, comp))
  
  def dmultia_flatmap[A:Manifest,B:Manifest](ma: Exp[DeliteArray1D[A]], f: Exp[A] => Exp[DeliteArray1D[B]])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArrayFlatMap(ma,f))
  def dmultia_filter[A:Manifest](ma: Exp[DeliteArray1D[A]], f: Exp[A] => Exp[Boolean])(implicit ctx: SourceContext) = dmultia_mapfilter(x, {(e:Exp[A]) => e}, f)
  def dmultia_groupBy[A:Manifest,K:Manifest](ma: Exp[DeliteArray1D[A]], key: Exp[A] => Exp[K])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArrayGroupBy(ma,key))
  def dmultia_groupByReduce[A:Manifest,K:Manifest,V:Manifest](ma: Exp[DeliteArray1D[A]], key: Exp[A] => Exp[K], value: Exp[A] => Exp[V], reduce: (Exp[V],Exp[V]) => Exp[V])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArrayGroupByReduce(ma,key,value,reduce))

  def dmultia_mapfilter[A:Manifest,B:Manifest](ma: Exp[DeliteArray1D[A]], map: Exp[A] => Exp[B], cond: Exp[A] => Exp[Boolean])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArrayMapFilter(ma,map,cond))

  // Possible alternative implementations without need for respective 1D specific nodes
  // May not be able to do this in all cases? e.g. Long arrays are not necessarily flat?
  /*
  def dmultia_sortIndices(len: Exp[Int], comp: (Exp[Int],Exp[Int]) => Exp[Int])(implicit ctx: SourceContext) = {
    val arr = darray_sortIndices(len, comp)
    dmultia_unpin(arr, FlatLayout[Int](1), Seq(len)).as1D
  }
  
  def dmultia_flatmap[A:Manifest,B:Manifest](ma: Exp[DeliteArray1D[A]], f: Exp[A] => Exp[DeliteArray1D[B]])(implicit ctx: SourceContext) = {
    val arr = dmultia_pin(ma, FlatLayout[A](1))
    def flatpin(x: Exp[DeliteArray1D[A]]) = dmultia_pin(x, FlatLayout[A](1))

    val result = darray_flatmap(arr, f _ andThen flatpin _)
    dmultia_unpin(result, FlatLayout[A](1), Seq(darray_length(result))).as1D
  }

  def dmultia_mapfilter[A:Manifest,B:Manifest](ma: Exp[DeliteArray1D[A]], map: Exp[A] => Exp[B], cond: Exp[A] => Exp[Boolean])(implicit ctx: SourceContext) = {
    val arr = dmultia_pin(ma, FlatLayout[A](1))
    val result = darray_mapfilter(arr, map, cond)
    dmultia_unpin(result, FlatLayout[B](1), Seq(darray_length(result))).as1D
  }
  */

  def dmultia_insert[A:Manifest](ma: Exp[DeliteArray1D[A]], x: Exp[A], index: Exp[Int])(implicit ctx: SourceContext) = reflectWrite(ma)(DeliteMultiArrayInsert(ma,x,index))
  def dmultia_append[A:Manifest](ma: Exp[DeliteArray1D[A]], x: Exp[A])(implicit ctx: SourceContext) = reflectWrite(ma)(DeliteMultiArrayAppend(ma,x))

  // --- 2D Operations
  def dmultia_matmult[A:Manifest:Numeric](lhs: Exp[DeliteArray2D[A]], rhs: Exp[DeliteArray2D[A]])(implicit ctx: SourceContext) = reflectPure(DeliteMatrixMultiply(lhs,rhs))
  def dmultia_macvecmult[A:Manifest:Numeric](mat: Exp[DeliteArray2D[A]], vec: Exp[DeliteArray1D[A]])(implicit ctx: SourceContext) = reflectPure(DeliteMatrixVectorMultiply(lhs,rhs))

  // --- Pinning
  def dmultia_pin[T:Manifest,R:Manifest](ma: Exp[DeliteMultiArray[T]], layout: Layout[T,R])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArrayPin[T,R](ma,layout))
  def dmultia_unpin[T:Manifest,R:Manifest](in: Exp[DeliteArray[R]], layout: Layout[T,R], shape: Seq[Exp[Int]])(implicit ctx: SourceContext) = reflectPure(DeliteMultiArrayUnpin[T,R](ma,layout,shape))

  ///////////////////////
  // aliases and sharing

  // probably don't need anything special here, keeping in case
  // something needs to be added later

  // return x if e may be equal to x
  override def aliasSyms(e: Any): List[Sym[Any]] = e match {
    case _ => super.aliasSyms(e)
  }

  // return x if later apply to e may return x
  override def containSyms(e: Any): List[Sym[Any]] = e match {
    case _ => super.containSyms(e)
  }

  // return x if dereferencing x may return e?
  override def extractSyms(e: Any): List[Sym[Any]] = e match {
    case _ => super.extractSyms(e)
  }

  // return x if (part?) of x (may?) have been copied into e?
  override def copySyms(e: Any): List[Sym[Any]] = e match {
    case _ => super.copySyms(e)
  }    

  // symbols which are bound in a definition
  override def boundSyms(e: Any): List[Sym[Any]] = e match {
    case _ => super.boundSyms(e)
  }

  /////////////
  // mirroring

  def dmultia_unsafe_update[A:Manifest](ma: Exp[DeliteMultiArray[A]], i: Seq[Exp[Int]], x: Exp[A])(implicit ctx: SourceContext) = 

  // All functions in IR nodes are currently function pointers, not Blocks
  // so transformer doesn't need to operate on these

  override def mirror[A:Manifest](e: Def[A], f: Transformer)(implicit ctx: SourceContext): Exp[A] = (e match {
    case e@DeliteMultiArrayNew(d) => dmultia_new_immutable(f(d))(e.mA,ctx)
    case e@DeliteMultiArrayFromFunction(d,g) => dmultia_fromfunction(f(d),g)(e.mA,ctx)
    case e@DeliteMultiArrayView(m,o,s,d) => dmultia_view(f(m),f(o),f(s),f(d))(e.mA,ctx)
    case e@DeliteMultiArrayRank(m) => dmultia_rank(f(m))(e.mA,ctx)
    case e@DeliteMultiArrayShape(m) => dmultia_shape(f(m))(e.mA,ctx)
    case e@DeliteMultiArraySize(m) => dmultia_size(f(m))(e.mA,ctx)
    case e@DeliteMultiArrayViewTarget(m) => dmultia_view_target(f(m))(e.mA,ctx)
    case e@DeliteMultiArrayViewStart(m) => dmultia_view_start(f(m))(e.mA,ctx)
    case e@DeliteMultiArrayViewStride(m) => dmultia_view_stride(f(m))(e.mA,ctx)
    case e@DeliteMultiArrayApply(m,i) => dmultia_apply(f(m),f(i))(e.mA,ctx)
    
    case e@DeliteMultiArrayPermute(m,c) => dmultia_permute(f(m),c)(e.mA,ctx)
    case e@DeliteMultiArrayReshape(m,d) => dmultia_reshape(f(m),d)(e.mA,ctx)

    case e@DeliteMultiArrayMap(m,g) => reflectPure(DeliteMultiArrayMap(f(m),g)(e.mA,e.mB,ctx))(mtype(manifest[A]),ctx)
    case e@DeliteMultiArrayZipWith(ma,mb,g) => reflectPure(DeliteMultiArrayZipWith(f(ma),f(mb),g)(e.mA,e.mB,e.mT,ctx))(mtype(manifest[A]),ctx)
    case e@DeliteMultiArrayReduce(m,g,z) => reflectPure(DeliteMultiArrayReduce(f(m),g,f(z))(e.mA,ctx))(mtype(manifest[A]),ctx)
    case e@DeliteMultiArrayForeach(m,g) => reflectPure(DeliteMultiArrayForeach(f(m),g)(e.mA,ctx))(mtype(manifest[A]),ctx)
    case e@DeliteMultiArrayForIndices(m,g) => reflectPure(DeliteMultiArrayForIndices(f(m),g)(e.mA,ctx))(mtype(manifest[A]),ctx)
    case e@DeliteMultiArrayNDMap(m,d,g) => reflectPure(DeliteMultiArrayNDMap(f(m),d,g)(e.mA,e.mB,ctx))(mtype(manifest[A]),ctx)

    // ----
    // Mutations without reflects - should these ever happen? what to do if they do? 
    case e@DeliteMultiArrayUpdate(m,i,x) => toAtom(DeliteMultiArrayUpdate(f(m),f(i),f(x))(e.mA,ctx))(mtype(manifest[A]), ctx)
    case e@DeliteMultiArrayMutableMap(m,g) => toAtom(DeliteMultiArrayMutableMap(f(m),g)(e.mA,ctx))(mtype(manifest[A]), ctx)
    case e@DeliteMultiArrayMutableZipWith(ma,mb,g) => toAtom(DeliteMultiArrayMutableZipWith(f(ma),f(mb),g)(e.mA,e.mB,ctx))(mtype(manifest[A]), ctx)
    case e@DeliteMultiArrayNDInsert(m,r,a,i) => toAtom(DeliteMultiArrayNDInsert(f(m),f(r),a,f(i))(e.mA,ctx))(mtype(manifest[A]), ctx)
    case e@DeliteMultiArrayNDAppend(m,r,a) => toAtom(DeliteMultiArrayNDAppend(f(m),f(r),a)(e.mA,ctx))(mtype(manifest[A]), ctx)
    case e@DeliteMultiArrayInsertAll(m,r,a,i) => toAtom(DeliteMultiArrayInsertAll(f(m),f(r),a,f(i))(e.mA,ctx))(mtype(manifest[A]), ctx)
    case e@DeliteMultiArrayAppendAll(m,r,a) => toAtom(DeliteMultiArrayAppendAll(f(m),f(r),a)(e.mA,ctx))(mtype(manifest[A]), ctx)
    case e@DeliteMultiArrayInsert(m,x,i) => toAtom(DeliteMultiArrayInsert(f(m),f(x),f(i))(e.mA,ctx))(mtype(manifest[A]), ctx)
    case e@DeliteMultiArrayAppend(m,x) => toAtom(DeliteMultiArrayAppend(f(m),f(x))(e.mA,ctx))(mtype(manifest[A]), ctx)
    // ----

    case e@DeliteMultiArrayMkString(m,d) => dmultia_mkstring(f(m),f(d))(e.mA,ctx)
    case DeliteStringSplit(s,p,l) => dmultia_string_split(f(s),f(p),f(l))(ctx)

    case e@DeliteMultiArraySortIndices(l,c) => reflectPure(DeliteMultiArraySortIndices(f(l),c))(mtype(manifest[A]),implicitly[SourceContext])
    case e@DeliteMultiArrayMapFilter(m,g,c) => reflectPure(DeliteMultiArrayMapFilter(f(m),g,c)(e.mA,e.mB,ctx))(mtype(manifest[A]), ctx)
    case e@DeliteMultiArrayFlatMap(m,g) => reflectPure(DeliteMultiArrayFlatMap(f(m),g)(e.mA,e.mB,ctx))(mtype(manifest[A]), ctx)
    case e@DeliteMultiArrayGroupBy(m,k) => reflectPure(DeliteMultiArrayGroupBy(f(m),k)(e.mA,e.mB,ctx))(mtype(manifest[A]), ctx)
    case e@DeliteMultiArrayGroupByReduce(m,k,v,r) => reflectPure(DeliteMultiArrayGroupByReduce(f(m),k,v,r)(e.mA,e.mB,e.mT,ctx))(mtype(manifest[A]), ctx)

    case e@DeliteMatrixMultiply(l,r) => dmultia_matmult(f(l),f(r))(e.mA)
    case e@DeliteMatrixVectorMultiply(m,v) => dmultia_matmult(f(m),f(v))(e.mA)

    case e@DeliteMultiArrayPin(m,l) => reflectPure(DeliteMultiArrayPin(f(m),l)(e.mA,e.mB))(mtype(manifest[A]), ctx)
    case e@DeliteMultiArrayUnpin(in,l,s) => reflectPure(DeliteMultiArrayUnpin(f(in),l,f(s))(e.mA,e.mB))(mtype(manifest[A]),ctx)
   
    case Reflect(e@DeliteMultiArrayNew(d), u, es) => reflectMirrored(Reflect(DeliteMultiArrayNew(f(d))(e.mA), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMultiArrayFromFunction(d,g), u, es) => reflectMirrored(Reflect(DeliteMultiArrayFromFunction(f(d),g)(e.mA), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    // Probably shouldn't happen?
    case Reflect(e@DeliteMultiArrayView(m,o,s,d), u, es) => reflectMirrored(Reflect(DeliteMultiArrayView(f(m),f(o),f(s),f(d))(e.mA,ctx), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)

    case Reflect(e@DeliteMultiArrayRank(m), u, es) => reflectMirrored(Reflect(DeliteMultiArrayRank(f(m))(e.mA), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMultiArrayShape(m), u, es) => reflectMirrored(Reflect(DeliteMultiArrayShape(f(m))(e.mA), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMultiArraySize(m), u, es) => reflectMirrored(Reflect(DeliteMultiArraySize(f(m))(e.mA), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMultiArrayViewTarget(m), u, es) => reflectMirrored(Reflect(DeliteMultiArrayViewTarget(f(m))(e.mA), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMultiArrayViewStart(m), u, es) => reflectMirrored(Reflect(DeliteMultiArrayViewStart(f(m))(e.mA), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMultiArrayViewStride(m), u, es) => reflectMirrored(Reflect(DeliteMultiArrayViewStride(f(m))(e.mA), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)

    case Reflect(e@DeliteMultiArrayApply(m,i), u, es) => reflectMirrored(Reflect(DeliteMultiArrayApply(f(m),f(i))(e.mA), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMultiArrayUpdate(m,i,x), u, es) => reflectMirrored(Reflect(DeliteMultiArrayUpdate(f(m),f(i),f(x))(e.mA), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)

    case Reflect(e@DeliteMultiArrayPermute(m,c), u, es) => reflectMirrored(Reflect(DeliteMultiArrayPermute(f(m),c)(e.mA,ctx), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMultiArrayReshape(m,d), u, es) => reflectMirrored(Reflect(DeliteMultiArrayReshape(f(m),f(d))(e.mA,ctx), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)

    case Reflect(e@DeliteMultiArrayMap(m,g), u, es) => reflectMirrored(Reflect(DeliteMultiArrayMap(f(m),g)(e.mA,e.mB,ctx), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMultiArrayZipWith(ma,mb,g), u, es) => reflectMirrored(Reflect(DeliteMultiArrayZipWith(f(ma),f(mb),g)(e.mA,e.mB,e.mT,ctx), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMultiArrayReduce(m,g,z), u, es) => reflectMirrored(Reflect(DeliteMultiArrayReduce(f(m),g,f(z))(e.mA,ctx)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMultiArrayForeach(m,g), u, es) => reflectMirrored(Reflect(DeliteMultiArrayForeach(f(m),g)(e.mA,ctx), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMultiArrayForIndices(m,g), u, es) => reflectMirrored(Reflect(DeliteMultiArrayForIndices(f(m),g)(e.mA,ctx), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMultiArrayMutableMap(m,g), u, es) => reflectMirrored(Reflect(DeliteMultiArrayMutableMap(f(m),g)(e.mA,ctx), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMultiArrayMutableZipWith(ma,mb,g), u, es) => reflectMirrored(Reflect(DeliteMultiArrayMutableZipWith(f(ma),f(mb),g)(e.mA,e.mB,ctx), mapOver(f,u), f(es)))(mtype(manifest[A]),ctx)
    case Reflect(e@DeliteMultiArrayNDMap(m,d,g), u, es) => reflectMirrored(Reflect(DeliteMultiArrayNDMap(f(m),d,g)(e.mA,e.mB,ctx), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)

    case Reflect(e@DeliteMultiArrayNDInsert(m,r,a,i), u, es) => reflectMirrored(Reflect(DeliteMultiArrayNDInsert(f(m),f(r),a,f(i))(e.mA, ctx), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMultiArrayNDAppend(m,r,a), u, es) => reflectMirrored(Reflect(DeliteMultiArrayNDAppend(f(m),f(r),a)(e.mA,ctx), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMultiArrayInsertAll(m,r,a,i), u, es) => reflectMirrored(Reflect(DeliteMultiArrayInsertAll(f(m),f(r),a,f(i))(e.mA,ctx), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMultiArrayAppendAll(m,r,a), u, es) => reflectMirrored(Reflect(DeliteMultiArrayAppendAll(f(m),f(r),a)(e.mA,ctx), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)

    case Reflect(e@DeliteMultiArrayMkString(m,d), u, es) => reflectMirrored(Reflect(DeliteMultiArrayMkString(f(m),f(d))(e.mA), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(DeliteStringSplit(s,p,l), u, es) => reflectMirrored(Reflect(DeliteStringSplit(s,p,l), mapOver(f,u), f(es)))(mtype(manifest[A], ctx))

    case Reflect(e@DeliteMultiArraySortIndices(l,c), u, es) => reflectMirrored(Reflect(DeliteMultiArraySortIndices(f(l),c), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMultiArrayMapFilter(m,g,c), u, es) => reflectMirrored(Reflect(DeliteMultiArrayMapFilter(f(m),g,c)(e.mA,e.mB,ctx), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMultiArrayFlatMap(m,g), u, es) => reflectMirrored(Reflect(DeliteMultiArrayFlatMap(f(m),g)(e.mA,e.mB,ctx), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMultiArrayGroupBy(m,k), u, es) => reflectMirrored(Reflect(DeliteMultiArrayGroupBy(f(m),k)(e.mA,e.mB,ctx), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)        
    case Reflect(e@DeliteMultiArrayGroupByReduce(m,k,v,r), u, es) => reflectMirrored(Reflect(DeliteMultiArray(f(m),k,v,r)(e.mA,e.mB,e.mT,ctx), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)

    case Reflect(e@DeliteMultiArrayInsert(m,x,i), u, es) => reflectMirrored(Reflect(DeliteMultiArrayInsert(f(m),f(x),f(i))(e.mA), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMultiArrayAppend(m,x), u, es) => reflectMirrored(Reflect(DeliteMultiArrayAppend(f(m),f(x))(e.mA), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)

    case Reflect(e@DeliteMatrixMultiply(l,r), u, es) => reflectMirrored(Reflect(DeliteMatrixMultiply(f(l),f(r))(e.mA), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMatrixVectorMultiply(m,v), u, es) => reflectMirrored(Reflect(DeliteMatrixVectorMultiply(f(m),f(v))(e.mA), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)

    case Reflect(e@DeliteMultiArrayPin(m,l), u, es) => reflectMirrored(Reflect(DeliteMultiArrayPin(f(m),l)(e.mA,e.mB), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)
    case Reflect(e@DeliteMultiArrayUnpin(a,l,s), u, es) => reflectMirrored(Reflect(DeliteMultiArrayPin(f(a),l,f(s))(e.mA,e.mB), mapOver(f,u), f(es)))(mtype(manifest[A]), ctx)

    case _ => super.mirror(e,f)
  }).asInstanceOf[Exp[A]]
}