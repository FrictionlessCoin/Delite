package ppl.dsl.deliszt.mat

import ppl.dsl.delizst.datastruct.CudaGenDataStruct
import java.io.{PrintWriter}

import ppl.delite.framework.{DeliteApplication, DSLType}
import scala.virtualization.lms.common.DSLOpsExp
import scala.virtualization.lms.common.{VariablesExp, Variables}
import scala.virtualization.lms.common.{CudaGenBase, ScalaGenBase, CGenBase}
import ppl.delite.framework.ops.DeliteOpsExp
import scala.virtualization.lms.internal.{GenerationFailedException}
import ppl.delite.framework.Config
import ppl.dsl.delizst.{DeLisztExp, DeLiszt}
import ppl.dsl.delizst.datastruct.scala._
import ppl.dsl.deliszt.datastruct.scala.Mat
import ppl.dsl.deliszt.DeLisztExp

trait MatOps extends DSLType with Variables {
  this: DeLiszt =>

  object Mat {
  }

  implicit def repMatToMatOps[A:Manifest](x: Rep[Mat[A]]) = new matOpsCls(x)
  implicit def varToMatOps[A:Manifest](x: Var[Mat[A]]) = new matOpsCls(readVar(x))

  // could convert to infix, but apply doesn't work with it anyways yet
  class matOpsCls[R <: IntM, C <: IntM, VT : Manifest](x: Rep[Mat[R,C,VT]]) {
    type Self = Mat[R,C,VT]

    // accessors
    def apply[RR <: IntM, CC <: IntM](r: RR, c: CC) = mat_apply(x,r,c)
    def update[RR <: IntM, CC <: IntM, VT](r: RR, c: CC, v: VT) = mat_update(x,r,c,v)

    def apply(r: Int, c: Int) = mat_apply(x,r,c)
    def update(r: Int, c: Int, v: VT) = mat_update(x,r,c,v)

    // arithmetic operations
    def +(y: Rep[Mat[A]])(implicit a: Arith[A]) = mat_plus(x,y)
    def -(y: Rep[Mat[A]])(implicit a: Arith[A]) = mat_minus(x,y)
    def unary_-(implicit a: Arith[A]) = mat_unary_minus(x)

    def *(y: Rep[Mat[A]])(implicit a: Arith[A]) = mat_multiply(x,y)
    def *(y: Rep[Vector[A]])(implicit a: Arith[A], o: Overloaded1) = mat_times_vector(x,y)
    def *(y: Rep[A])(implicit a: Arith[A], o: Overloaded2) = mat_times_scalar(x,y)

    def /(y: Rep[A])(implicit a: Arith[A], o: Overloaded1) = mat_divide_scalar(x,y)
  }

  // class defs
  def mat_apply[A:Manifest](x: Rep[Mat[A]], i: Rep[Int], j: Rep[Int]): Rep[A]
  def mat_update[A:Manifest](x: Rep[Mat[A]], i: Rep[Int], j: Rep[Int], y: Rep[A]): Rep[Unit]

  def mat_plus[A:Manifest:Arith](x: Rep[Mat[A]], y: Rep[Mat[A]]): Rep[Mat[A]]
  def mat_plus_scalar[A:Manifest:Arith](x: Rep[Mat[A]], y: Rep[A]): Rep[Mat[A]]
  def mat_minus[A:Manifest:Arith](x: Rep[Mat[A]], y: Rep[Mat[A]]): Rep[Mat[A]]
  def mat_times[A:Manifest:Arith](x: Rep[Mat[A]], y: Rep[Mat[A]]): Rep[Mat[A]]
  def mat_multiply[A:Manifest:Arith](x: Rep[Mat[A]], y: Rep[Mat[A]]): Rep[Mat[A]]
  def mat_times_vector[A:Manifest:Arith](x: Rep[Mat[A]], y: Rep[Vector[A]]): Rep[Vector[A]]
  def mat_times_scalar[A:Manifest:Arith](x: Rep[Mat[A]], y: Rep[A]): Rep[Mat[A]]
  def mat_divide_scalar[A:Manifest:Arith](x: Rep[Mat[A]], y: Rep[A]): Rep[Mat[A]]
  def mat_unary_minus[A:Manifest:Arith](x: Rep[Mat[A]]): Rep[Mat[A]]
}


trait MatOpsExp extends MatOps with VariablesExp {
  this: MatImplOps with DeLisztExp  =>

  //////////////////////////////////////////////////
  // implemented via method on real data structure

  case class MatObjectNew[A:Manifest](numRows: Exp[Int], numCols: Exp[Int]) extends Def[Mat[A]] {
     val mM = manifest[MatImpl[A]]
  }
  //case class MatApply[A:Manifest](x: Exp[Mat[A]], i: Exp[Int], j: Exp[Int]) extends Def[A]
  case class MatDCApply[A:Manifest](x: Exp[Mat[A]], i: Exp[Int]) extends Def[A]
  case class MatUpdate[A:Manifest](x: Exp[Mat[A]], i: Exp[Int], j: Exp[Int], y: Exp[A]) extends Def[Unit]

  /////////////////////////////////////
  // implemented via kernel embedding

  case class MatApply[A:Manifest](x: Exp[Mat[A]], i: Exp[Int], j: Exp[Int])
    extends DeliteOpSingleTask(reifyEffectsHere(mat_apply_impl(x, i, j)))

  ///////////////////////////////////////////////////////////////////
  // BLAS enabled routines (currently these must all be singletasks)

  // TODO: generalize this so that we can generate fused, delite parallel op, or BLAS variants

  case class MatTimesVector[A:Manifest:Arith](x: Exp[Mat[A]], y: Exp[Vector[A]])
    extends DeliteOpSingleTask(reifyEffectsHere(mat_times_vector_impl(x,y)), true) {

    val mV = manifest[VectorImpl[A]]
    def mev = manifest[A]
    def aev = implicitly[Arith[A]]
  }

  case class MatMultiply[A:Manifest:Arith](x: Exp[Mat[A]], y: Exp[Mat[A]])
    extends DeliteOpSingleTask(reifyEffectsHere(mat_multiply_impl(x,y)), true) {

    val mM = manifest[MatImpl[A]]

  }

  ////////////////////////////////
  // implemented via delite ops

  case class MatPlus[A:Manifest:Arith](inA: Exp[Mat[A]], inB: Exp[Mat[A]])
    extends DeliteOpZipWith[A,A,A,Mat] {

    val alloc = reifyEffects(Mat[A](inA.numRows, inA.numCols))
    val v = (fresh[A],fresh[A])
    val func = v._1 + v._2
  }

  case class MatMinus[A:Manifest:Arith](inA: Exp[Mat[A]], inB: Exp[Mat[A]])
    extends DeliteOpZipWith[A,A,A,Mat] {

    val alloc = reifyEffects(Mat[A](inA.numRows, inA.numCols))
    val v = (fresh[A],fresh[A])
    val func = v._1 - v._2
  }

  case class MatTimesScalar[A:Manifest:Arith](in: Exp[Mat[A]], y: Exp[A])
    extends DeliteOpMap[A,A,Mat] {

    val alloc = reifyEffects(Mat[A](in.numRows, in.numCols))
    val v = fresh[A]
    val func = v * y
  }

  case class MatDivide[A:Manifest:Arith](inA: Exp[Mat[A]], inB: Exp[Mat[A]])
    extends DeliteOpZipWith[A,A,A,Mat] {

    val alloc = reifyEffects(Mat[A](inA.numRows, inA.numCols))
    val v = (fresh[A],fresh[A])
    val func = v._1 / v._2
  }

  ////////////////////
  // object interface

  ///////////////////
  // class interface

  def mat_apply[A:Manifest](x: Exp[Mat[A]], i: Exp[Int], j: Exp[Int]) = reflectPure(MatApply[A](x,i,j))
  def mat_update[A:Manifest](x: Exp[Mat[A]], i: Exp[Int], j: Exp[Int], y: Exp[A]) = reflectWrite(x)(MatUpdate[A](x,i,j,y))

  def mat_plus[A:Manifest:Arith](x: Exp[Mat[A]], y: Exp[Mat[A]]) = reflectPure(MatPlus(x, y))
  def mat_minus[A:Manifest:Arith](x: Exp[Mat[A]], y: Exp[Mat[A]]) = reflectPure(MatMinus(x,y))
  def mat_unary_minus[A:Manifest:Arith](x: Exp[Mat[A]]) = MatUnaryMinus(x)

  def mat_multiply[A:Manifest:Arith](x: Exp[Mat[A]], y: Exp[Mat[A]]) = reflectPure(MatMultiply(x,y))
  def mat_times_vector[A:Manifest:Arith](x: Exp[Mat[A]], y: Exp[Vector[A]]) = reflectPure(MatTimesVector(x,y))
  def mat_times_scalar[A:Manifest:Arith](x: Exp[Mat[A]], y: Exp[A]) = reflectPure(MatTimesScalar(x,y))

  def mat_divide[A:Manifest:Arith](x: Exp[Mat[A]], y: Exp[Mat[A]]) = reflectPure(MatDivide(x,y))

  //////////////////
  // internal

  def mat_dcapply[A:Manifest](x: Exp[Mat[A]], i: Exp[Int]) = reflectPure(MatDCApply(x,i))
}

/**
 *  Optimizations for composite MatOps operations.
 */

trait MatOpsExpOpt extends MatOpsExp {
  this: MatImplOps with DeLisztExp =>
}


trait ScalaGenMatOps extends ScalaGenBase {
  val IR: MatOpsExp
  import IR._

  override def emitNode(sym: Sym[Any], rhs: Def[Any])(implicit stream: PrintWriter) = rhs match {
    // these are the ops that call through to the underlying real data structure
    case m@MatObjectNew(numRows, numCols) => emitValDef(sym, "new " + remap(m.mM) + "(" + quote(numRows) + "," + quote(numCols) + ")")
    //case MatApply(x,i,j) => emitValDef(sym, quote(x) + "(" + quote(i) + ", " + quote(j) + ")")
    case MatDCApply(x,i) => emitValDef(sym, quote(x) + ".dcApply(" + quote(i) + ")")\
    case MatUpdate(x,i,j,y)  => emitValDef(sym, quote(x) + "(" + quote(i) + ", " + quote(j) + ") = " + quote(y))

    // BLAS calls
    // all corresponding nodes should have their DeliteOpSingleTask second argument set to "true" (require inputs)
    case m@MatMultiply(x,y) if (Config.useBlas) =>
      emitValDef(sym, "new " + remap(m.mM) + "(" + quote(x) + ".numRows," + quote(y) + ".numCols)")
      stream.println("scalaBLAS.matMult(%s.data,%s.data,%s.data,%s.numRows,%s.numCols,%s.numCols)".format(quote(x),quote(y),quote(sym),quote(x),quote(x),quote(y)))
    case m@MatTimesVector(x,y) if (Config.useBlas) =>
      emitValDef(sym, "new " + remap(m.mV) + "(" + quote(x) + ".numRows, false)")
      stream.println("scalaBLAS.matVMult(%s.data,%s.data,%s.data,%s.numRows,%s.numCols,0,1)".format(quote(x),quote(y),quote(sym),quote(x),quote(x)))
    case m@MatSigmoid(x) if (Config.useBlas) =>
      emitValDef(sym, "new " + remap(m.mM) + "(" + quote(x) + ".numRows," + quote(x) + ".numCols)")
      stream.println("scalaBLAS.sigmoid(%s.data,%s.data,0,%s.numRows*%s.numCols)".format(quote(x),quote(sym),quote(x),quote(x)))
    case m@MatSigmoidF(x) if (Config.useBlas) =>
      emitValDef(sym, "new " + remap(m.mM) + "(" + quote(x) + ".numRows," + quote(x) + ".numCols)")
      stream.println("scalaBLAS.sigmoid(%s.data,%s.data,0,%s.numRows*%s.numCols)".format(quote(x),quote(sym),quote(x),quote(x)))

    case _ => super.emitNode(sym, rhs)
  }
}

trait CudaGenMatOps extends CudaGenBase with CudaGenDataStruct {
  val IR: MatOpsExp
  import IR._

  override def emitNode(sym: Sym[Any], rhs: Def[Any])(implicit stream: PrintWriter) = rhs match {
    /* CUBLAS calls */
    case MatMultiply(x,y) =>
      val callStream = "cublasSetKernelStream(stream);"
      val callKernel = if(remap(x.Type.typeArguments(0)) == "double")
        "cublasDgemm('n','n',%s.numCols,%s.numRows,%s.numRows,1.0,%s.data,%s.numCols,%s.data,%s.numCols,0.0,%s.data,%s.numCols);".format(quote(y),quote(x),quote(y),quote(y),quote(y),quote(x),quote(x),quote(sym),quote(sym))
      else if(remap(x.Type.typeArguments(0)) == "float")
        "cublasSgemm('n','n',%s.numCols,%s.numRows,%s.numRows,1.0,%s.data,%s.numCols,%s.data,%s.numCols,0.0,%s.data,%s.numCols);".format(quote(y),quote(x),quote(y),quote(y),quote(y),quote(x),quote(x),quote(sym),quote(sym))
      else
        throw new RuntimeException("CudaGen: Not GPUable (Type %s is not supported for MatMulitply CUBLAS library)".format(remap(x.Type.typeArguments(0))))
      emitMatAlloc(sym,"%s->numRows".format(quote(x)),"%s->numCols".format(quote(y)),false)
      emitLibCall(sym,List(callStream,callKernel))
    
    case MatTimesVector(x,y) =>
      val callStream = "cublasSetKernelStream(stream);"
      val callKernel = if(remap(x.Type.typeArguments(0)) == "double")
        "cublasDgemv('t', %s.numCols, %s.numRows, 1.0, %s.data, %s.numCols, %s.data, 1, 0.0, %s.data, 1);".format(quote(x),quote(x),quote(x),quote(x),quote(y),quote(sym))
      else if(remap(x.Type.typeArguments(0)) == "float")
        "cublasSgemv('t', %s.numCols, %s.numRows, 1.0, %s.data, %s.numCols, %s.data, 1, 0.0, %s.data, 1);".format(quote(x),quote(x),quote(x),quote(x),quote(y),quote(sym))
      else
        throw new RuntimeException("CudaGen: Not GPUable (Type %s is not supported for Mat*Vector CUBLAS library)".format(remap(x.Type.typeArguments(0))))
      emitVectorAlloc(sym,"%s->numRows".format(quote(x)),"false",false)
      emitLibCall(sym,List(callStream,callKernel))

    /* The ops that call through to the underlying data structure */
    case MatDCApply(x,i) =>
      emitValDef(sym, "%s.dcApply(%s)".format(quote(x),quote(i)))
    case MatApply(x,i,j) =>
      emitValDef(sym, "%s.apply(%s,%s)".format(quote(x),quote(i),quote(j)))
    case MatUpdate(x,i,j,y)  =>
      stream.println(addTab() + "%s.update(%s,%s,%s);".format(quote(x),quote(i),quote(j),quote(y)))
    case MatNumRows(x)  =>
      emitValDef(sym, quote(x) + ".numRows")
    case MatNumCols(x)  =>
      emitValDef(sym, quote(x) + ".numCols")

    case _ => super.emitNode(sym, rhs)
  }
}

trait CGenMatOps extends CGenBase {
  val IR: MatOpsExp
  import IR._

  override def emitNode(sym: Sym[Any], rhs: Def[Any])(implicit stream: PrintWriter) = rhs match {

    case MatObjectNew(numRows,numCols) =>
      stream.println("%s *%s_data = malloc(sizeof(%s)*%s*%s);".format(remap(sym.Type.typeArguments(0)),quote(sym),remap(sym.Type.typeArguments(0)),quote(numRows),quote(numCols)))
      stream.println("%s %s;".format(remap(sym.Type),quote(sym)))
      stream.println("%s.numRows = %s;".format(quote(sym),quote(numRows)))
      stream.println("%s.numCols = %s;".format(quote(sym),quote(numCols)))
      stream.println("%s.data = %s_data;".format(quote(sym),quote(sym)))
    case MatDCApply(x,i) =>
      emitValDef(sym, "%s.apply(%s)".format(quote(x),quote(i)))
    //case MatApply(x,i,j) =>
    //  emitValDef(sym, "%s.apply(%s,%s)".format(quote(x),quote(i),quote(j)))
    case MatUpdate(x,i,j,y)  =>
      stream.println("%s.update(%s,%s,%s);".format(quote(x),quote(i),quote(j),quote(y)))
    case MatNumRows(x)  =>
      emitValDef(sym, quote(x) + ".numRows")
    case MatNumCols(x)  =>
      emitValDef(sym, quote(x) + ".numCols")
    case _ => super.emitNode(sym, rhs)
  }
}
