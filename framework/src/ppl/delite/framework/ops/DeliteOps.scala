package ppl.delite.framework.ops

import java.io.{FileWriter, File, PrintWriter, StringWriter}

import scala.reflect.SourceContext
import scala.virtualization.lms.common._
import scala.virtualization.lms.internal.{GenericCodegen, GenericFatCodegen, GenerationFailedException, CCodegen}
import ppl.delite.framework.{Config, Util, DeliteApplication}
import ppl.delite.framework.datastructures._
import ppl.delite.framework.extern.lib._
import ppl.delite.framework.analysis.{StencilExp,NestedLoopMappingExp}
import scala.collection.mutable.{HashSet,HashMap}

trait DeliteOpsExp extends DeliteOpsExpIR with DeliteInternalOpsExp with DeliteCollectionOpsExp with DeliteArrayFatExp with DeliteMapOpsExp {

  /**
   * A sequential task - will execute block in a single thread and respect any free variable dependencies inside it.
   *
   * @param  block   the task to execute; must be reified if it contains effectful operations!
   */

  class DeliteOpSingleTask[R:Manifest](block0: => Block[R], requireInputs: Boolean = false) extends DeliteOpAbstractSingleTask[R](block0, requireInputs) {
    type OpType <: DeliteOpSingleTask[R]
    val mR = manifest[R]
  }

  class DeliteOpSingleWithManifest[A:Manifest,R:Manifest](block0: => Block[R], requireInputs: Boolean = false) extends DeliteOpSingleTask[R](block0,requireInputs) {
    val mA = manifest[A]
  }

  class DeliteOpSingleWithManifest2[A:Manifest,B:Manifest,R:Manifest](block0: => Block[R], requireInputs: Boolean = false) extends DeliteOpSingleWithManifest[A,R](block0,requireInputs) {
    val mB = manifest[B]
  }

  /**
   * A method call to an external library.
   */
  abstract class DeliteOpExternal[A:Manifest] extends DeliteOpAbstractExternal[A] {
    type OpType <: DeliteOpExternal[A]
    def alloc: Exp[A]
    def inputs: List[Exp[Any]] = Nil
    val funcName: String
  }

  /**
   * A Conditional task - will emit a Conditional DEG node as well as kernels for the then and else clauses
   *
   * @param  cond    the condition of the Conditional
   * @param  thenp   the Then block to execute if condition is true
   * @param  elsep   the Else block to execute if condition is false
   */
  trait DeliteOpCondition[A] extends DeliteOp[A] {
    type OpType <: DeliteOpCondition[A]
    val cond: Exp[Boolean]
    val thenp: Block[A]
    val elsep: Block[A]
  }

  /**
   * An while loop - will emit an while loop DEG node as well as a kernel for the body
   *
   * @param  cond  condition expression, will be emitted as a kernel
   * @param  body   the body of the loop
   */
  trait DeliteOpWhileLoop extends DeliteOp[Unit] {
    type OpType <: DeliteOpWhileLoop
    val cond: Block[Boolean]
    val body: Block[Unit]
  }

  /**
   *  Delite parallel ops - represents common parallel execution patterns, most of which
   *  are represented by an underlying 'loop' abstraction. All Delite parallel ops must
   *  not mutate their inputs or any global state, and produce a single output, with the
   *  exception of DeliteOpForeach. DeliteOpForeach can only mutate shared state protected
   *  by the 'sync' method; all other side-effects and writes must be disjoint (i.e., not
   *  have inter-iteration dependencies). In all cases, there is no ordering guarantee.
   *
   *  Note that size is supplied explicitly to allow domain-specific pattern rewrites.
   *
   *  One design question moving forward: fusing can handle much of the composition here
   *  automatically (e.g. MapReduce / ZipWithReduce), but we currently don't have a way
   *  to represent this fused thing as a single parallel op (we have to unroll it).
   *  It would be nice to be general, so that we could have e.g. a ZipZipReduce op that is
   *  automatically fused and still a single IR node. OpComposite?
   */

  /**
   * DeliteOpFlatMapLike is the base type for all Delite ops with collect elem bodies,
   * representing loops that create an output collection with elements of type O.
   *
   * It now supports allocating an intermediate result of type I (which will be modified during
   * construction) and returning a result of type CO by invoking the 'finalizer' method.
   */
  abstract class DeliteOpFlatMapLike[O:Manifest, I<:DeliteCollection[O]:Manifest, CO<:DeliteCollection[O]:Manifest](implicit ctx: SourceContext)
      extends DeliteOpLoop[CO] {
    type OpType <: DeliteOpFlatMapLike[O,I,CO]

    // Behavior supplied by subclasses
    
    // the flatmap function, can use bound index variable this.v
    def flatMapLikeFunc(): Exp[DeliteCollection[O]]
    // Allocates the intermediate result collection. The size passed is 0 if
    // the OutputStrategy is OutputBuffer, and it is op.size (the input size of
    // this loop) if it is OutputFlat.
    def alloc(size: Exp[Int]): Exp[I] = throw new IllegalArgumentException("alloc in DeliteOpFlatMapLike should have been overridden")
    // the finalizer to transform the intermediate result collection of type I
    // to the final output collection of type CO
    def finalizer(x: Exp[I]): Exp[CO]
    // true in the general flatMap case, false for a fixed-size map where the
    // output size is known before runtime
    val unknownOutputSize = true

    // FlatMap loop bound vars
    final lazy val iFunc: Exp[DeliteCollection[O]] = copyTransformedOrElse(_.iFunc)(flatMapLikeFunc())
    final lazy val iF: Sym[Int] = copyTransformedOrElse(_.iF)(fresh[Int]).asInstanceOf[Sym[Int]]
    final lazy val eF: Sym[DeliteCollection[O]] = copyTransformedOrElse(_.eF)(fresh[DeliteCollection[O]](iFunc.tp)).asInstanceOf[Sym[DeliteCollection[O]]]
    // Buffer bound vars
    final lazy val eV: Sym[O] = copyTransformedOrElse(_.eV)(fresh[O]).asInstanceOf[Sym[O]]
    final lazy val sV: Sym[Int] = copyTransformedOrElse(_.sV)(fresh[Int]).asInstanceOf[Sym[Int]]
    final lazy val allocVal: Sym[I] = copyTransformedOrElse(_.allocVal)(reflectMutableSym(fresh[I])).asInstanceOf[Sym[I]]
    final lazy val iV: Sym[Int] = copyTransformedOrElse(_.iV)(fresh[Int]).asInstanceOf[Sym[Int]]
    final lazy val iV2: Sym[Int] = copyTransformedOrElse(_.iV2)(fresh[Int]).asInstanceOf[Sym[Int]]
    final lazy val aV2: Sym[I] = copyTransformedOrElse(_.aV2)(fresh[I]).asInstanceOf[Sym[I]]

    // buffer elem
    lazy val buf = DeliteBufferElem(
      eV = this.eV,
      sV = this.sV,
      iV = this.iV,
      iV2 = this.iV2,
      allocVal = this.allocVal,
      aV2 = this.aV2,
      alloc = reifyEffects(this.alloc(sV)),
      apply = unusedBlock, //reifyEffects(dc_apply(allocVal,v)),
      // update used if strategy == OutputFlat
      update = reifyEffects(dc_update(allocVal,v,eV)),
      // append etc. used if strategy == OutputBuffer (linear buffer)
      append = reifyEffects(dc_append(allocVal,v,eV)),
      appendable = reifyEffects(dc_appendable(allocVal,v,eV)),
      setSize = reifyEffects(dc_set_logical_size(allocVal,sV)),
      allocRaw = reifyEffects(dc_alloc[O,I](allocVal,sV)),
      copyRaw = reifyEffects(dc_copy(aV2,iV,allocVal,iV2,sV)),
      // finalizer used to transform from DC[I] to DC[CO]
      finalizer = reifyEffects(this.finalizer(allocVal))
    )

    // loop elem
    lazy val body: Def[CO] = copyBodyOrElse(DeliteCollectElem[O,I,CO](
      iFunc = reifyEffects(this.iFunc),
      unknownOutputSize = this.unknownOutputSize,
      // true if the output collection allocated by alloc is linear and supports
      // append etc. for OutputBuffer strategy
      linearOutputCollection = dc_linear_buffer(this.allocVal),
      // strategy is defined as:
      // if (unknownOutputSize && linearOutputCollection) OutputBuffer else OutputFlat
      buf = this.buf,
      numDynamicChunks = this.numDynamicChunks,
      eF = this.eF,
      iF = this.iF,
      sF = reifyEffects(dc_size(eF)),
      aF = reifyEffects(dc_apply(eF,iF))
    ))

    val dmO = manifest[O]
    val dmI = manifest[I]
    val dmCO = manifest[CO]
  }

  override def getCollectElemType(elem: DeliteCollectElem[_,_,_]): DeliteCollectElemType = {
    val iFuncRes = elem.iFunc match {
      case Block(Def(Reify(x, _, _))) => x
      case Block(x) => x
    }
    iFuncRes match {
      case Def(EatReflect(DeliteArraySingletonInLoop(siElem, _))) if (!elem.unknownOutputSize) =>
        CollectMap(siElem)
      case Def(EatReflect(DeliteArraySingletonInLoop(siElem, _))) if (elem.unknownOutputSize) =>
        CollectDynamicMap(siElem)
      case Def(EatReflect(IfThenElse(cond, Block(Def(EatReflect(DeliteArraySingletonInLoop(thenElem, _)))), Block(Def(DeliteArrayEmptyInLoop(_,_)))))) => 
        CollectFilter(cond, thenElem)
      case _ => CollectFlatMap()
    }
  }

  /**
   * DeliteOpMapLike is the special case of FlatMap where the each loop iteration generates one
   * output element. The output size is therefore the same as the input/loop size. Code
   * generation can be specialized to avoid the intermediate allocation of a collection of size
   * 1 at each iteration. See DeliteCollectElemType.
   *
   * Depending on the number of input collections the following subclasses can be used:
   * - 0 input collections: DeliteOpMapIndices
   * - 1 input collection (of type A): DeliteOpMap
   * - 2 input collections (of type A and B): DeliteOpZipWith
   */
   abstract class DeliteOpMapLike[O:Manifest, I <: DeliteCollection[O]:Manifest, CO <: DeliteCollection[O]:Manifest](implicit ctx: SourceContext)
      extends DeliteOpFlatMapLike[O,I,CO] {
    type OpType <: DeliteOpMapLike[O,I,CO]

    // supplied by subclass, produces the element for each iteration index
    def mapFunc(): Exp[O]

    override def flatMapLikeFunc() = DeliteArray.singletonInLoop(mapFunc(), v)
    override val unknownOutputSize = false
   }

  /**
   * Parallel map over the indices from 0 to (size - 1).
   *
   * @param  size  the size of the loop, which is the same as the output size
   * @param  func  the mapping function Exp[Int] => Exp[O]
   * @param  alloc the function returning the output collection
   */
  abstract class DeliteOpMapIndices[O:Manifest, CO <: DeliteCollection[O]:Manifest](implicit ctx: SourceContext)
      extends DeliteOpMapIndicesI[O,CO,CO] {
    type OpType <: DeliteOpMapIndices[O,CO]

    def finalizer(x: Exp[CO]) = x
  }

  /** DeliteOpMapIndices with intermediate collection type. */
  abstract class DeliteOpMapIndicesI[O:Manifest, I <: DeliteCollection[O]:Manifest, CO <: DeliteCollection[O]:Manifest](implicit ctx: SourceContext)
      extends DeliteOpMapLike[O,CO,CO] {
    type OpType <: DeliteOpMapIndicesI[O,I,CO]

    // supplied by subclass, produces the element for each iteration index
    def func: Exp[Int] => Exp[O]
    
    override def mapFunc() = func(v)
  }

  /**
   * Parallel map from DeliteCollection[A] => DeliteCollection[O]. Input functions can depend on free
   * variables, but they cannot depend on other elements of the input or output collection (disjoint access).
   *
   * @param  in    the input collection
   * @param  size  the size of the input collection
   * @param  func  the mapping function Exp[A] => Exp[O]
   * @param  alloc function returning the output collection. If it is the same as the input collection,
   *               the operation is mutable; (=> DeliteCollection[O]).
   */
  abstract class DeliteOpMap[A:Manifest, O:Manifest, CO <: DeliteCollection[O]:Manifest](implicit ctx: SourceContext)
      extends DeliteOpMapI[A,O,CO,CO] {
    type OpType <: DeliteOpMap[A,O,CO]

    def finalizer(x: Exp[CO]) = x
  }

  /** DeliteOpMap with intermediate collection type. */
  abstract class DeliteOpMapI[A:Manifest, O:Manifest, I <: DeliteCollection[O]:Manifest,CO <: DeliteCollection[O]:Manifest](implicit ctx: SourceContext)
      extends DeliteOpMapLike[O,I,CO] {
    type OpType <: DeliteOpMapI[A,O,I,CO]

    // supplied by subclass
    val in: Exp[DeliteCollection[A]]
    def func: Exp[A] => Exp[O]

    // bound var for map function, may be required by transformers
    lazy val fin: Exp[A] = copyTransformedOrElse(_.fin)(dc_apply(in,v))
    override def mapFunc() = func(fin)

    val dmA = manifest[A]
  }


  /**
   * Parallel 2 element zipWith-(map) from (DeliteCollection[A],DeliteCollection[B]) => DeliteCollection[O].
   * Input functions can depend on free variables, but they cannot depend on other elements of the input or
   * output collection (disjoint access).
   *
   * @param  inA   the first input collection
   * @param  inB   the second input collection
   * @param  size  the size of the collections (should be the same)
   * @param  func  the zipWith function; ([Exp[A],Exp[B]) => Exp[O]
   * @param  alloc function returning the output collection. If it is the same as the input collection,
   *               the operation is mutable; (=> DeliteCollection[B]).
   */
  abstract class DeliteOpZipWith[A:Manifest, B:Manifest, O:Manifest, CO <: DeliteCollection[O]:Manifest](implicit ctx: SourceContext)
      extends DeliteOpZipWithI[A,B,O,CO,CO] {
    type OpType <: DeliteOpZipWith[A,B,O,CO]

    def finalizer(x: Exp[CO]) = x
  }

  /** DeliteOpZipWith with intermediate collection type. */
  abstract class DeliteOpZipWithI[A:Manifest, B:Manifest, O:Manifest, I <: DeliteCollection[O]:Manifest, CO <: DeliteCollection[O]:Manifest](implicit ctx: SourceContext)
      extends DeliteOpMapLike[O,I,CO] {
    type OpType <: DeliteOpZipWithI[A,B,O,I,CO]

    // supplied by subclass
    val inA: Exp[DeliteCollection[A]]
    val inB: Exp[DeliteCollection[B]]
    def func: (Exp[A], Exp[B]) => Exp[O]

    // bound var for map function, may be required by transformers
    lazy val fin: (Exp[A],Exp[B]) = (copyTransformedOrElse(_.fin._1)(dc_apply(inA,v)),copyTransformedOrElse(_.fin._2)(dc_apply(inB,v)))
    override def mapFunc() = func(fin._1,fin._2)

    val dmA = manifest[A]
    val dmB = manifest[B]
  }


  /**
   * Parallel flatMap from DeliteCollection[A] => DeliteCollection[O]. Input functions can depend on free
   * variables, but they cannot depend on other elements of the input or output collection (disjoint access).
   *
   * @param  in    the input collection
   * @param  size  the size of the input collection
   * @param  func  the mapping function Exp[A] => Exp[DeliteCollection[O]]
   * @param  alloc function returning the output collection. If it is the same as the input collection,
   *               the operation is mutable; (=> DeliteCollection[O]).
   */
  abstract class DeliteOpFlatMap[A:Manifest, O:Manifest, CO<:DeliteCollection[O]:Manifest](implicit ctx: SourceContext)
      extends DeliteOpFlatMapI[A,O,CO,CO] {
    type OpType <: DeliteOpFlatMap[A,O,CO]

    def finalizer(x: Exp[CO]) = x
  }

  /** DeliteOpFlatMap with intermediate collection type. */
  abstract class DeliteOpFlatMapI[A:Manifest, O:Manifest, I<:DeliteCollection[O]:Manifest, CO<:DeliteCollection[O]:Manifest](implicit ctx: SourceContext)
      extends DeliteOpFlatMapLike[O,I,CO] {
    type OpType <: DeliteOpFlatMapI[A,O,I,CO]

    // supplied by subclass
    val in: Exp[DeliteCollection[A]]
    def func: Exp[A] => Exp[DeliteCollection[O]]

    // bound var for map function, may be required by transformers
    lazy val fin: Exp[A] = copyTransformedOrElse(_.fin)(dc_apply(in,v))
    override def flatMapLikeFunc() = func(fin)

    val dmA = manifest[A]
  }


  /**
   * Parallel (map)-filter from DeliteCollection[A] => DeliteCollection[O]. Input functions can depend on free
   * variables, but they cannot depend on other elements of the input or output collection (disjoint access).
   * Currently appends values that pass the condition to buffers, which are concatenated in the combine stage.
   *
   * @param  in    the input collection
   * @param  size  the size of the input collection
   * @param  func  the mapping function Exp[A] => Exp[O]
   * @param  cond  the filter condition function Exp[A] => Exp[Boolean]
   * @param  alloc function returning the output collection. If it is the same as the input collection,
   *               the operation is mutable; (=> DeliteCollection[O]).
   */
  abstract class DeliteOpFilter[A:Manifest, O:Manifest, CO <: DeliteCollection[O]:Manifest](implicit ctx: SourceContext)
      extends DeliteOpFilterI[A,O,CO,CO] {
    type OpType <: DeliteOpFilter[A,O,CO]

    def finalizer(x: Exp[CO]) = x
  }

  /** DeliteOpFilter with intermediate collection type. */
  abstract class DeliteOpFilterI[A:Manifest, O:Manifest, I <: DeliteCollection[O]:Manifest, CO <: DeliteCollection[O]:Manifest](implicit ctx: SourceContext)
      extends DeliteOpFlatMapLike[O,I,CO] {
    type OpType <: DeliteOpFilterI[A,O,I,CO]

    // supplied by subclass
    val in: Exp[DeliteCollection[A]]
    def func: Exp[A] => Exp[O]
    def cond: Exp[A] => Exp[Boolean]

    // bound var for map function, may be required by transformers
    lazy val fin: Exp[A] = copyTransformedOrElse(_.fin)(dc_apply(in,v))
    override def flatMapLikeFunc() = IfThenElse(cond(fin), reifyEffects(DeliteArray.singletonInLoop(func(fin), v)), reifyEffects(DeliteArray.emptyInLoop[O](v)))

    val dmA = manifest[A]
  }


  /**
   * DeliteOpReduceLike is the base type for all Delite ops with reduce elem bodies,
   * representing loops that create a single output element of type A.
   *
   * NOTE ABOUT ZERO:
   *    the supplied zero parameter is required to have value equality *in the generated code*
   *    it will not be used unless the collection is empty or in a conditional reduce where the
   *    first (or more) conditions fail. In both cases, we never try to actually reduce a zero
   *    element - we only return it or use it as an initialization check.
   *
   *    if stripFirst is set to false, i.e. for a mutable reduction, then the accInit value is used
   *    to allocate the accumulator, and it IS used in the initial reduction.
   */
  abstract class DeliteOpReduceLike[A:Manifest](implicit ctx: SourceContext) extends DeliteOpLoop[A] {
    type OpType <: DeliteOpReduceLike[A]
    final lazy val rV: (Sym[A],Sym[A]) = copyOrElse(_.rV)((if (mutable) reflectMutableSym(fresh[A]) else fresh[A], fresh[A])) // TODO: transform vars??
    val mutable: Boolean = false
    val stripFirst = !isPrimitiveType(manifest[A]) && !this.mutable

    def accInit: Exp[A] = fatal(unit("DeliteOpReduce accInit called without any implementation on " + manifest[A].toString))
  }

  /**
   * Parallel reduction of a DeliteCollection[A]. Reducing function must be associative.
   *
   * @param  in    the input collection
   * @param  size  the size of the input collection
   * @param  zero  the "empty" value - must have value equality
   * @param  func  the reduction function; ([Exp[A],Exp[A]) => Exp[A]. Must be associative.
   */
  abstract class DeliteOpReduce[A:Manifest](implicit ctx: SourceContext) extends DeliteOpReduceLike[A] {
    type OpType <: DeliteOpReduce[A]

    // supplied by subclass
    val in: Exp[DeliteCollection[A]]
    def zero: Exp[A]
    def func: (Exp[A], Exp[A]) => Exp[A]

    // loop
    lazy val body: Def[A] = copyBodyOrElse(DeliteReduceElem[A](
      func = reifyEffects(dc_apply(in,v)),
      zero = reifyEffects(this.zero),
      accInit = if (isPrimitiveType(manifest[A])) reifyEffects(zero) else reifyEffects(this.accInit),
      rV = this.rV,
      rFunc = reifyEffects(this.func(rV._1, rV._2)),
      stripFirst = this.stripFirst,
      numDynamicChunks = this.numDynamicChunks
    ))

    val dmA = manifest[A]
  }


  /**
   * Parallel map-reduction from a DeliteCollection[A] => R. The map-reduce is composed, so no temporary collection
   * is instantiated to hold the result of the map.
   *
   * @param  in      the input collection
   * @param  size    the size of the input collection
   * @param  zero    the "empty" value - must have value equality
   * @param  map     the mapping function; Exp[A] => Exp[R]
   * @param  reduce  the reduction function; ([Exp[R],Exp[R]) => Exp[R]. Must be associative.
   */
  abstract class DeliteOpMapReduce[A:Manifest,R:Manifest](implicit ctx: SourceContext)
    extends DeliteOpReduceLike[R] {
    type OpType <: DeliteOpMapReduce[A,R]

    // supplied by subclass
    val in: Exp[DeliteCollection[A]]
    def zero: Exp[R]
    def map: Exp[A] => Exp[R]
    def reduce: (Exp[R], Exp[R]) => Exp[R]

    // loop
    lazy val body: Def[R] = copyBodyOrElse(DeliteReduceElem[R](
      func = reifyEffects(map(dc_apply(in,v))),
      zero = reifyEffects(this.zero),
      accInit = if (isPrimitiveType(manifest[R])) reifyEffects(zero) else reifyEffects(this.accInit),
      rV = this.rV,
      rFunc = reifyEffects(reduce(rV._1, rV._2)),
      stripFirst = this.stripFirst,
      numDynamicChunks = this.numDynamicChunks
    ))
  }

  // should this be folded into DeliteOpMapReduce (or into DeliteOpFilter)?
  abstract class DeliteOpFilterReduce[A:Manifest,R:Manifest](implicit ctx: SourceContext)
    extends DeliteOpReduceLike[R] {
    type OpType <: DeliteOpFilterReduce[A,R]

    // supplied by subclass
    val in: Exp[DeliteCollection[A]]
    def zero: Exp[R]
    def func: Exp[A] => Exp[R]
    def reduce: (Exp[R], Exp[R]) => Exp[R]
    def cond: Exp[A] => Exp[Boolean] // does this need to be more general (i.e. a List?)

    // loop
    lazy val body: Def[R] = copyBodyOrElse(DeliteReduceElem[R](
      func = reifyEffects(this.func(dc_apply(in,v))),
      cond = reifyEffects(this.cond(dc_apply(in,v)))::Nil,
      zero = reifyEffects(this.zero),
      accInit = if (isPrimitiveType(manifest[R])) reifyEffects(zero) else reifyEffects(this.accInit),
      rV = this.rV,
      rFunc = reifyEffects(reduce(rV._1, rV._2)),
      stripFirst = this.stripFirst,
      numDynamicChunks = this.numDynamicChunks
    ))
  }


  // reduce tuple in parallel, return first component
  abstract class DeliteOpFilterReduceFold[R:Manifest](implicit ctx: SourceContext)
    extends DeliteOpLoop[R] {
    type OpType <: DeliteOpFilterReduceFold[R]

    // supplied by subclass
    val in: Exp[DeliteCollection[Int]]
    val zero: (Block[R], Block[Int])
    def func: Exp[Int] => (Block[R],Block[Int])
    def reducePar: ((Exp[R],Exp[Int]), (Exp[R],Exp[Int])) => (Block[R],Block[Int])
    def reduceSeq: ((Exp[R],Exp[Int]), (Exp[R],Exp[Int])) => (Block[R],Block[Int]) // = reduce

    val mutable: Boolean = false
    final lazy protected val rVPar: ((Sym[R],Sym[Int]),(Sym[R],Sym[Int])) = copyOrElse(_.rVPar)(((reflectMutableSym(fresh[R]),reflectMutableSym(fresh[Int])), (fresh[R],fresh[Int])))
    final lazy protected val rVSeq: ((Sym[R],Sym[Int]),(Sym[R],Sym[Int])) = copyOrElse(_.rVSeq)(((reflectMutableSym(fresh[R]),reflectMutableSym(fresh[Int])), (fresh[R],fresh[Int])))

    // loop
    lazy val body: Def[R] = copyBodyOrElse(DeliteReduceTupleElem[R,Int](
      func = /*reifyEffects*/(func(dc_apply(in,v))), //FIXME: tupled reify
      zero = this.zero,
      rVPar = this.rVPar,
      rVSeq = this.rVSeq,
      rFuncPar = /*reifyEffects*/(reducePar(rVPar._1, rVPar._2)),  //FIXME: tupled reify
      rFuncSeq = /*reifyEffects*/(reduceSeq(rVSeq._1, rVSeq._2)),  //FIXME: tupled reify
      stripFirst = false, //(!isPrimitiveType(manifest[R]) || !isPrimitiveType(manifest[R])) && !this.mutable
      numDynamicChunks = this.numDynamicChunks
    ))
  }


  /**
   * Parallel zipWith-reduction from a (DeliteCollection[A],DeliteCollection[A]) => R. The map-reduce is composed,
   * so no temporary collection is instantiated to hold the result of the map.
   *
   * @param  inA     the first input collection
   * @param  inB     the second input collection
   * @param  size    the size of the input collections (should be the same)
   * @param  zero    the "empty" value - must have value equality
   * @param  zip     the zipWith function; reified version of (Exp[A],Exp[B]) => Exp[R]
   * @param  reduce  the reduction function; reified version of ([Exp[R],Exp[R]) => Exp[R]. Must be associative.
   */
  abstract class DeliteOpZipWithReduce[A:Manifest,B:Manifest,R:Manifest](implicit ctx: SourceContext)
    extends DeliteOpReduceLike[R] {
    type OpType <: DeliteOpZipWithReduce[A,B,R]

    // supplied by subclass
    val inA: Exp[DeliteCollection[A]]
    val inB: Exp[DeliteCollection[B]]
    def zero: Exp[R]
    def zip: (Exp[A], Exp[B]) => Exp[R]
    def reduce: (Exp[R], Exp[R]) => Exp[R]

    // loop
    lazy val body: Def[R] = copyBodyOrElse(DeliteReduceElem[R](
      func = reifyEffects(zip(dc_apply(inA,v), dc_apply(inB,v))),
      zero = reifyEffects(this.zero),
      accInit = if (isPrimitiveType(manifest[R])) reifyEffects(zero) else reifyEffects(this.accInit),
      rV = this.rV,
      rFunc = reifyEffects(reduce(rV._1, rV._2)),
      stripFirst = this.stripFirst,
      numDynamicChunks = this.numDynamicChunks
    ))
  }

  // reduce tuple in parallel, return first component
  abstract class DeliteOpZipWithReduceTuple[A:Manifest,B:Manifest,R:Manifest,Q:Manifest](implicit ctx: SourceContext)
    extends DeliteOpLoop[R] {
    type OpType <: DeliteOpZipWithReduceTuple[A,B,R,Q]

    // supplied by subclass
    val inA: Exp[DeliteCollection[A]]
    val inB: Exp[DeliteCollection[B]]
    val zero: (Block[R], Block[Q])
    def zip: (Exp[A], Exp[B]) => (Block[R],Block[Q])
    def reduce: ((Exp[R],Exp[Q]), (Exp[R],Exp[Q])) => (Block[R],Block[Q])

    val mutable: Boolean = false
    final lazy protected val rV: ((Sym[R],Sym[Q]),(Sym[R],Sym[Q])) = copyOrElse(_.rV)(((reflectMutableSym(fresh[R]),reflectMutableSym(fresh[Q])), (fresh[R],fresh[Q]))) // TODO: transform vars??
    // loop
    lazy val body: Def[R] = copyBodyOrElse(DeliteReduceTupleElem[R,Q](
      func = /*reifyEffects*/(zip(dc_apply(inA,v), dc_apply(inB,v))), //FIXME: tupled reify
      zero = this.zero,
      rVPar = this.rV,
      rVSeq = this.rV,
      rFuncPar = /*reifyEffects*/(reduce(rV._1, rV._2)),  //FIXME: tupled reify
      rFuncSeq = /*reifyEffects*/(reduce(rV._1, rV._2)),  //FIXME: tupled reify
      stripFirst = (!isPrimitiveType(manifest[R]) || !isPrimitiveType(manifest[R])) && !this.mutable,
      numDynamicChunks = this.numDynamicChunks
    ))
  }

  /**
   * Parallel foreach from DeliteCollection[A] => Unit. Input functions must specify any free variables that it
   * requires are protected (e.g. locked before chunk execution) using the sync list.
   *
   * @param  in     the input collection
   * @param  size   the size of the input collection
   * @param  func   the foreach function Exp[A] => Exp[Unit]
   * @param  sync   a function from an index to a list of objects that should be locked, in a total ordering,
   *                prior to chunk execution, and unlocked after; (Exp[Int] => Exp[List[Any]])
   */
  abstract class DeliteOpForeach[A:Manifest](implicit ctx: SourceContext) extends DeliteOpLoop[Unit] { //DeliteOp[Unit] {
    type OpType <: DeliteOpForeach[A]
    val in: Exp[DeliteCollection[A]]
    val size: Exp[Int]
    def func: Exp[A] => Exp[Unit]
    def sync: Exp[Int] => Exp[List[Any]] // TODO: need to extend runtime to do something with sync in multiloop

    final lazy val i: Sym[Int] = copyOrElse(_.i)(fresh[Int])
    lazy val body: Def[Unit] = copyBodyOrElse(DeliteForeachElem(
      func = reifyEffects(this.func(dc_apply(in,v))),
      numDynamicChunks = this.numDynamicChunks
      //sync = reifyEffects(this.sync(i))
    ))
  }

  /**
   * Parallel foreach with possible global reductions (instances of DeliteReduction) in the body.
   * DeliteReductions are factored out into separate ReduceElem bodies, and the result of the reduction
   * is written to their input vars after the foreach completes.
   *
   * @param  in     the input collection
   * @param  size   the size of the input collection
   * @param  func   the foreach function Exp[A] => Exp[Unit]
   */
  abstract class DeliteOpForeachReduce[A:Manifest](implicit ctx: SourceContext) extends DeliteOpAbstractForeachReduce[A]  { //DeliteOpLoop[Unit] {
    type OpType <: DeliteOpForeachReduce[A]
    val in: Exp[DeliteCollection[A]]
    val size: Exp[Int]
    def funcBody = funcBodyVal

    /* expand func body for proper effect tracking */
    lazy val funcBodyVal: Block[Unit] = copyTransformedBlockOrElse(_.funcBody)(reifyEffects(this.func(dc_apply(in,v))))

    /**
     * DeliteOpForeachReduce instances eventually get transformed into a composite of foreach and reduce elements.
     * See ForeachReduceTransformer.scala. Here we grab the reductions to be used later.
     */
    lazy val (xFunc, deliteReductions) = funcBody match {
      case Block(Def(Reify(x,u,es))) =>
        val reductions = es.collect(e => e match { case Def(Reflect(r:DeliteReduction[_,_],_,_)) => r })
        if (reductions.length == 0) {
          (funcBody, Nil)
        }
        else {
          (Block(Reify(Const(),u,es)), reductions)
        }
     }
  }

  abstract class DeliteOpIndexedLoop(implicit ctx: SourceContext) extends DeliteOpLoop[Unit] {
    type OpType <: DeliteOpIndexedLoop
    val size: Exp[Int]
    def func: Exp[Int] => Exp[Unit]

    lazy val body: Def[Unit] = copyBodyOrElse(DeliteForeachElem(
      func = reifyEffects(this.func(v)),
      numDynamicChunks = this.numDynamicChunks
      //sync = reifyEffects(unit(List()))
    ))
  }

  abstract class DeliteOpHashCollectLike[K:Manifest, V:Manifest, I:Manifest, CV:Manifest, CI:Manifest, CCV: Manifest](implicit ctx: SourceContext) extends DeliteOpLoop[CCV] {
    type OpType <: DeliteOpHashCollectLike[K,V,I,CV,CI,CCV]
    final lazy val allocVal: Sym[CI] = copyTransformedOrElse(_.allocVal)(reflectMutableSym(fresh[CI])).asInstanceOf[Sym[CI]]
    final lazy val aV2: Sym[CI] = copyTransformedOrElse(_.aV2)(fresh[CI]).asInstanceOf[Sym[CI]]
    final lazy val iV: Sym[Int] = copyTransformedOrElse(_.iV)(fresh[Int]).asInstanceOf[Sym[Int]]
    final lazy val iV2: Sym[Int] = copyTransformedOrElse(_.iV2)(fresh[Int]).asInstanceOf[Sym[Int]]
    final lazy val iiV: Sym[Int] = copyTransformedOrElse(_.iiV)(fresh[Int]).asInstanceOf[Sym[Int]]
    final lazy val iiV2: Sym[Int] = copyTransformedOrElse(_.iiV2)(fresh[Int]).asInstanceOf[Sym[Int]]
    final lazy val sV: Sym[Int] = copyTransformedOrElse(_.sV)(fresh[Int]).asInstanceOf[Sym[Int]]
    final lazy val eV: Sym[V] = copyTransformedOrElse(_.eV)(fresh[V]).asInstanceOf[Sym[V]]

    def alloc(i: Exp[Int]): Exp[CI]
    def allocI(i: Exp[Int]): Exp[I]
    def finalizer(x: Exp[CI]): Exp[CCV]
    def finalizerI(x: Exp[I]): Exp[CV]
  }

  abstract class DeliteOpBuildIndex[A:Manifest, K:Manifest, CV:Manifest](implicit ctx: SourceContext) extends DeliteOpLoop[CV] {
    type OpType <: DeliteOpBuildIndex[A,K,CV]

    val in: Exp[DeliteCollection[A]]
    def keyFunc: Exp[A] => Exp[K]
    def cond: Exp[A] => Exp[Boolean]

    lazy val body: Def[CV] = copyBodyOrElse(DeliteHashIndexElem[K,CV](
      keyFunc = reifyEffects(this.keyFunc(dc_apply(in,v))),
      cond = if (this.cond == null) Nil else reifyEffects(this.cond(dc_apply(in,v)))::Nil,
      numDynamicChunks = this.numDynamicChunks
    ))

    val dmA = manifest[A]
    val dmK = manifest[K]
    val dmCV = manifest[CV]
  }

  abstract class DeliteOpGroupBy[K:Manifest, V: Manifest, CV<:DeliteCollection[V]:Manifest, CCV<:DeliteCollection[CV]:Manifest](implicit ctx: SourceContext) extends DeliteOpMappedGroupBy[V,K,V,CV,CCV] {
    type OpType <: DeliteOpGroupBy[K,V,CV,CCV]
    val in: Exp[DeliteCollection[V]]
    def valFunc: Exp[V] => Exp[V] = v => v
  }

  abstract class DeliteOpMappedGroupBy[A:Manifest, K:Manifest, V:Manifest, CV<:DeliteCollection[V]:Manifest, CCV<:DeliteCollection[CV]:Manifest](implicit ctx: SourceContext) extends DeliteOpFilteredGroupBy[A,K,V,CV,CCV] {
    type OpType <: DeliteOpMappedGroupBy[A,K,V,CV,CCV]
    val in: Exp[DeliteCollection[A]]
    def cond: Exp[A] => Exp[Boolean] = null
  }

  abstract class DeliteOpFilteredGroupBy[A:Manifest, K:Manifest, V:Manifest, CV<:DeliteCollection[V]:Manifest, CCV<:DeliteCollection[CV]:Manifest](implicit ctx: SourceContext) extends DeliteOpFilteredGroupByI[A,K,V,CV,CV,CCV,CCV] {
    type OpType <: DeliteOpFilteredGroupBy[A,K,V,CV,CCV]
    def finalizer(x: Exp[CCV]) = x
    def finalizerI(x: Exp[CV]) = x
  }

  abstract class DeliteOpFilteredGroupByI[A:Manifest, K:Manifest, V:Manifest, I<:DeliteCollection[V]:Manifest, CV<:DeliteCollection[V]:Manifest, CI<:DeliteCollection[I]:Manifest, CCV<:DeliteCollection[CV]:Manifest](implicit ctx: SourceContext) extends DeliteOpHashCollectLike[K,V,I,CV,CI,CCV] {
    type OpType <: DeliteOpFilteredGroupByI[A,K,V,I,CV,CI,CCV]
    val in: Exp[DeliteCollection[A]]

    def keyFunc: Exp[A] => Exp[K]
    def valFunc: Exp[A] => Exp[V]
    def cond: Exp[A] => Exp[Boolean]

    final lazy val ibufVal: Exp[I] =
      if (Config.soaEnabled) {
        copyTransformedOrElse(_.ibufVal)(dc_apply(allocVal,iV)) // this is necessary to make nested collections with SoA work properly (rewrites can't kick in on a bound sym)
      }
      else {
        copyTransformedOrElse(_.ibufVal)(reflectMutableSym(fresh[I]).asInstanceOf[Sym[I]])
      }
    final lazy val ibufVal2: Exp[I] =
      if (Config.soaEnabled) {
        copyTransformedOrElse(_.ibufVal2)(dc_apply(aV2,iV2))
      }
      else {
        copyTransformedOrElse(_.ibufVal2)(reflectMutableSym(fresh[I]).asInstanceOf[Sym[I]])
      }

    lazy val body: Def[CCV] = copyBodyOrElse(DeliteHashCollectElem[K,V,I,CV,CI,CCV](
      keyFunc = reifyEffects(this.keyFunc(dc_apply(in,v))),
      valFunc = reifyEffects(this.valFunc(dc_apply(in,v))),
      cond = if (this.cond == null) Nil else reifyEffects(this.cond(dc_apply(in,v)))::Nil,
      iBufSize = reifyEffects(dc_size(ibufVal2)),
      iBuf = DeliteBufferElem(
        eV = this.eV,
        sV = this.sV,
        iV = this.iiV,
        iV2 = this.iiV2,
        allocVal = if (Config.soaEnabled) unusedSym else this.ibufVal.asInstanceOf[Sym[I]],
        aV2 = if (Config.soaEnabled) unusedSym else this.ibufVal2.asInstanceOf[Sym[I]],
        alloc = reifyEffects(this.allocI(sV)),
        apply = unusedBlock,
        update = unusedBlock,
        appendable = unusedBlock,
        append = reifyEffects(dc_append(ibufVal,v,eV)), // with SoA enabled, this short-circuits directly to contents of allocVal
        setSize = reifyEffects(dc_set_logical_size(ibufVal,sV)),
        allocRaw = if (Config.soaEnabled) unusedBlock else reifyEffects(dc_apply(this.aV2,this.iV2)), // co-opting this field to tunnel the bound apply through
        copyRaw = reifyEffects(dc_copy(ibufVal2,iiV2,ibufVal,iiV,sV)),
        finalizer = reifyEffects(this.finalizerI(ibufVal))
      ),
      buf = DeliteBufferElem(
        eV = unusedSym,
        sV = this.sV,
        iV = this.iV,
        iV2 = this.iV2,
        allocVal = this.allocVal,
        aV2 = this.aV2,
        alloc = reifyEffects(this.alloc(sV)),
        apply = if (Config.soaEnabled) unusedBlock else reifyEffects(dc_apply(allocVal,iV)),
        // update = reifyEffects(dc_update(this.allocVal,iV,dc_alloc[V,I](ibufVal,sV))), // why does this use dc_alloc on iBufVal, while append below uses allocI? this one is apparently used only in postProcess, while append is used in process.
        update = if (Config.soaEnabled) reifyEffects(dc_update(this.allocVal,iV,dc_alloc[V,I](ibufVal,sV))) else reifyEffects(dc_update(this.allocVal,iV,delite_unsafe_immutable(ibufVal))),
        appendable = unusedBlock,
        // append = reifyEffects(dc_append(this.allocVal,v,this.allocI(sV))), // without SoA, we lose the mutable allocI here. why is the allocI (iBuf allocation) hidden down here instead of using iBuf.alloc above? must have something to do with the rewrites on iBufVal..
        append = if (Config.soaEnabled) reifyEffects(dc_append(this.allocVal,v,this.allocI(sV))) else reifyEffects(dc_append(this.allocVal,v,delite_unsafe_immutable(ibufVal))),
        setSize = reifyEffects(dc_set_logical_size(this.allocVal,sV)),
        allocRaw = reifyEffects(dc_alloc[I,CI](this.allocVal,sV)),
        copyRaw = unusedBlock,
        finalizer = reifyEffects(this.finalizer(this.allocVal))
      ),
      numDynamicChunks = this.numDynamicChunks
    ))

    val dmA = manifest[A]
    val dmK = manifest[K]
    val dmV = manifest[V]
    val dmI = manifest[I]
    val dmCV = manifest[CV]
    val dmCI = manifest[CI]
    val dmCCV = manifest[CCV]
  }

  abstract class DeliteOpHashReduceLike[K:Manifest, V:Manifest, I<:DeliteCollection[V]:Manifest, CV<:DeliteCollection[V]:Manifest](implicit ctx: SourceContext) extends DeliteOpLoop[CV] {
    type OpType <: DeliteOpHashReduceLike[K,V,I,CV]
    final lazy val rV: (Sym[V],Sym[V]) = copyOrElse(_.rV)((fresh[V], fresh[V]))
    final lazy val allocVal: Sym[I] = copyTransformedOrElse(_.allocVal)(reflectMutableSym(fresh[I])).asInstanceOf[Sym[I]]
    final lazy val iV: Sym[Int] = copyTransformedOrElse(_.iV)(fresh[Int]).asInstanceOf[Sym[Int]]
    final lazy val sV: Sym[Int] = copyTransformedOrElse(_.sV)(fresh[Int]).asInstanceOf[Sym[Int]]
    final lazy val eV: Sym[V] = copyTransformedOrElse(_.eV)(fresh[V]).asInstanceOf[Sym[V]]
    def alloc(i: Exp[Int]): Exp[I]
    def finalizer(x: Exp[I]): Exp[CV]
  }

  abstract class DeliteOpGroupByReduce[K:Manifest, V:Manifest, CV <: DeliteCollection[V]:Manifest](implicit ctx: SourceContext) extends DeliteOpMappedGroupByReduce[V,K,V,CV] {
    type OpType <: DeliteOpGroupByReduce[K,V,CV]
    def valFunc: Exp[V] => Exp[V] = v => v
  }

  abstract class DeliteOpMappedGroupByReduce[A:Manifest, K:Manifest, V:Manifest, CV <: DeliteCollection[V]:Manifest](implicit ctx: SourceContext) extends DeliteOpFilteredGroupByReduce[A,K,V,CV] {
    type OpType <: DeliteOpMappedGroupByReduce[A,K,V,CV]
    def cond: Exp[A] => Exp[Boolean] = null
  }

  abstract class DeliteOpFilteredGroupByReduce[A:Manifest, K:Manifest, V:Manifest, CV<:DeliteCollection[V]:Manifest](implicit ctx: SourceContext) extends DeliteOpFilteredGroupByReduceI[A,K,V,CV,CV] {
    type OpType <: DeliteOpFilteredGroupByReduce[A,K,V,CV]
    def finalizer(x: Exp[CV]) = x
  }

  abstract class DeliteOpFilteredGroupByReduceI[A:Manifest, K:Manifest, V:Manifest, I<:DeliteCollection[V]:Manifest, CV<:DeliteCollection[V]:Manifest](implicit ctx: SourceContext) extends DeliteOpHashReduceLike[K,V,I,CV] {
    type OpType <: DeliteOpFilteredGroupByReduceI[A,K,V,I,CV]
    val in: Exp[DeliteCollection[A]]

    def keyFunc: Exp[A] => Exp[K]
    def valFunc: Exp[A] => Exp[V]
    def reduceFunc: (Exp[V], Exp[V]) => Exp[V]
    def zero: Exp[V]
    def cond: Exp[A] => Exp[Boolean]

    lazy val body: Def[CV] = copyBodyOrElse(DeliteHashReduceElem[K,V,I,CV](
      keyFunc = reifyEffects(this.keyFunc(dc_apply(in,v))),
      valFunc = reifyEffects(this.valFunc(dc_apply(in,v))),
      cond = if (this.cond == null) Nil else reifyEffects(this.cond(dc_apply(in,v)))::Nil,
      zero = reifyEffects(this.zero),
      rV = this.rV,
      rFunc = reifyEffects(reduceFunc(rV._1, rV._2)),
      buf = DeliteBufferElem(
        eV = this.eV,
        sV = this.sV,
        iV = this.iV,
        iV2 = unusedSym,
        allocVal = this.allocVal,
        aV2 = unusedSym,
        alloc = reifyEffects(this.alloc(sV)),
        apply = reifyEffects(dc_apply(allocVal,iV)),
        update = reifyEffects(dc_update(allocVal,iV,eV)),
        appendable = unusedBlock, //reifyEffects(dc_appendable(allocVal,v,eV)),
        append = reifyEffects(dc_append(allocVal,v,eV)),
        setSize = reifyEffects(dc_set_logical_size(allocVal,sV)),
        allocRaw = unusedBlock, //reifyEffects(dc_alloc[V,I](allocVal,sV)),
        copyRaw = unusedBlock, //reifyEffects(dc_copy(aV2,iV,allocVal,iV2,sV)),
        finalizer = reifyEffects(this.finalizer(allocVal))
      ),
      numDynamicChunks = this.numDynamicChunks
    ))

    val dmA = manifest[A]
    val dmK = manifest[K]
    val dmV = manifest[V]
    val dmI = manifest[I]
    val dmCV = manifest[CV]
  }

  ///////////////////////////
  // helpers

  def unusedBlock() = reifyEffectsHere(fatal(unit("emitted unused block in Multiloop")))
  def unusedSym() = Sym(-10)
  val encounteredZipWith = new scala.collection.mutable.HashMap[Exp[Any], DeliteOpZipWith[_,_,_,_]]()

}
