package ppl.dsl.deliszt

import java.io._
import scala.virtualization.lms.common._
import scala.virtualization.lms.internal.{GenericFatCodegen, GenericCodegen}
import ppl.delite.framework.{Config, DeliteApplication}
import ppl.delite.framework.codegen.Target
import ppl.delite.framework.codegen.scala.TargetScala
import ppl.delite.framework.codegen.cuda.TargetCuda
import ppl.delite.framework.codegen.c.TargetC
import ppl.delite.framework.codegen.delite.overrides.{DeliteCudaGenAllOverrides, DeliteCGenAllOverrides, DeliteScalaGenAllOverrides, DeliteAllOverridesExp}
import ppl.delite.framework.ops._
import ppl.dsl.deliszt.datastruct.CudaGenDataStruct
import ppl.dsl.deliszt.field._

/**
 * These separate DeLiszt applications from the Exp world.
 */

// ex. object GDARunner extends DeLisztApplicationRunner with GDA
trait DeLisztApplicationRunner extends DeLisztApplication with DeliteApplication with DeLisztExp

// ex. trait GDA extends DeLisztApplication
trait DeLisztApplication extends DeLiszt with DeLisztLift with DeLisztLibrary {
  var args: Rep[Array[String]]
  def main(): Unit
}

trait DeLisztLibrary {
  this: DeLisztApplication =>
}

/**
 * These are the portions of Scala imported into DeLiszt's scope.
 */
trait DeLisztLift extends LiftVariables with LiftEquals with LiftString with LiftBoolean with LiftNumeric {
  this: DeLiszt =>
}

trait DeLisztScalaOpsPkg extends Base
  with Equal with IfThenElse with Variables with While with Functions
  with ImplicitOps with OrderingOps with StringOps
  with BooleanOps with PrimitiveOps with MiscOps with TupleOps
  with MathOps with CastingOps with ObjectOps
  // only included because of args. TODO: investigate passing args as a vector
  with ArrayOps

trait DeLisztScalaOpsPkgExp extends DeLisztScalaOpsPkg with DSLOpsExp
  with EqualExp with IfThenElseExp with VariablesExp with WhileExp with FunctionsExp
  with ImplicitOpsExp with OrderingOpsExp with StringOpsExp with RangeOpsExp with IOOpsExp
  with ArrayOpsExp with BooleanOpsExp with PrimitiveOpsExp with MiscOpsExp with TupleOpsExp
  with ListOpsExp with SeqOpsExp with MathOpsExp with CastingOpsExp with SetOpsExp with ObjectOpsExp
  with ArrayBufferOpsExp

trait DeLisztScalaCodeGenPkg extends ScalaGenDSLOps
  with ScalaGenEqual with ScalaGenIfThenElse with ScalaGenVariables with ScalaGenWhile with ScalaGenFunctions
  with ScalaGenImplicitOps with ScalaGenOrderingOps with ScalaGenStringOps with ScalaGenRangeOps with ScalaGenIOOps
  with ScalaGenArrayOps with ScalaGenBooleanOps with ScalaGenPrimitiveOps with ScalaGenMiscOps with ScalaGenTupleOps
  with ScalaGenListOps with ScalaGenSeqOps with ScalaGenMathOps with ScalaGenCastingOps with ScalaGenSetOps with ScalaGenObjectOps
  with ScalaGenArrayBufferOps
  { val IR: DeLisztScalaOpsPkgExp  }

trait DeLisztCudaCodeGenPkg extends CudaGenDSLOps with CudaGenImplicitOps with CudaGenOrderingOps
  with CudaGenEqual with CudaGenIfThenElse with CudaGenVariables with CudaGenWhile with CudaGenFunctions
  with CudaGenStringOps with CudaGenRangeOps with CudaGenIOOps with CudaGenArrayOps with CudaGenBooleanOps
  with CudaGenPrimitiveOps with CudaGenMiscOps
  with CudaGenListOps with CudaGenSeqOps with CudaGenMathOps with CudaGenCastingOps with CudaGenSetOps
  with CudaGenArrayBufferOps
  { val IR: DeLisztScalaOpsPkgExp  }

trait DeLisztCCodeGenPkg extends CGenDSLOps with CGenImplicitOps with CGenOrderingOps
  with CGenStringOps with CGenRangeOps with CGenIOOps with CGenArrayOps with CGenBooleanOps
  with CGenPrimitiveOps with CGenMiscOps with CGenFunctions with CGenEqual with CGenIfThenElse
  with CGenVariables with CGenWhile with CGenListOps with CGenSeqOps
  with CGenArrayBufferOps
  { val IR: DeLisztScalaOpsPkgExp  }

/**
 * This the trait that every DeLiszt application must extend.
 */
trait DeLiszt extends DeLisztScalaOpsPkg with LanguageOps {
  this: DeLisztApplication =>
}

// these ops are only available to the compiler (they are restricted from application use)
trait DeLisztCompiler extends DeLiszt with FieldPrivateOps {
  this: DeLisztApplication with DeLisztExp =>
}


/**
 * These are the corresponding IR nodes for DeLiszt.
 */
trait DeLisztExp extends DeLisztCompiler with DeLisztScalaOpsPkgExp with LanguageOpsExp
  with DeliteOpsExp with VariantsOpsExp with DeliteAllOverridesExp {

  // this: DeLisztApplicationRunner => why doesn't this work?
  this: DeliteApplication with DeLisztApplication with DeLisztExp => // can't be DeLisztApplication right now because code generators depend on stuff inside DeliteApplication (via IR)

  def getCodeGenPkg(t: Target{val IR: DeLisztExp.this.type}) : GenericFatCodegen{val IR: DeLisztExp.this.type} = {
    t match {
      case _:TargetScala => new DeLisztCodeGenScala{val IR: DeLisztExp.this.type = DeLisztExp.this}
      case _:TargetCuda => new DeLisztCodeGenCuda{val IR: DeLisztExp.this.type = DeLisztExp.this}
      case _:TargetC => new DeLisztCodeGenC{val IR: DeLisztExp.this.type = DeLisztExp.this} 
      case _ => throw new RuntimeException("DeLiszt does not support this target")
    }
  }

}


/**
 * DeLiszt code generators
 */
trait DeLisztCodeGenBase extends GenericFatCodegen {

  val IR: DeliteApplication with DeLisztExp
  override def initialDefs = IR.deliteGenerator.availableDefs


  def dsmap(line: String) = line

  val specialize = Set[String]()
  val specialize2 = Set[String]()
  def genSpec(f: File, outPath: String) = {}
  def genSpec2(f: File, outPath: String) = {}

  override def emitDataStructures(path: String) {
    val s = File.separator
    val dsRoot = Config.homeDir + s+"dsls"+s+"DeLiszt"+s+"src"+s+"ppl"+s+"dsl"+s+"DeLiszt"+s+"datastruct"+s + this.toString

    val dsDir = new File(dsRoot)
    if (!dsDir.exists) return
    val outDir = new File(path)
    outDir.mkdirs()

    for (f <- dsDir.listFiles) {
      if (specialize contains (f.getName.substring(0, f.getName.indexOf(".")))) {
        genSpec(f, path)
      }
      if (specialize2 contains (f.getName.substring(0, f.getName.indexOf(".")))) {
        genSpec2(f, path)
      }
      val outFile = path + f.getName
      val out = new BufferedWriter(new FileWriter(outFile))
      for (line <- scala.io.Source.fromFile(f).getLines) {
        out.write(dsmap(line) + "\n")
      }
      out.close()
    }
  }
}

trait DeLisztCodeGenScala extends DeLisztCodeGenBase with DeLisztScalaCodeGenPkg with ScalaGenDeliteOps with ScalaGenLanguageOps
  with DeliteScalaGenAllOverrides { //with ScalaGenMLInputReaderOps {
  
  val IR: DeliteApplication with DeLisztExp

  override val specialize = Set()
  override val specialize2 = Set()

  override def genSpec(f: File, dsOut: String) {
    for (s <- List("Double","Int","Float","Long","Boolean")) {
      val outFile = dsOut + s + f.getName
      val out = new BufferedWriter(new FileWriter(outFile))
      for (line <- scala.io.Source.fromFile(f).getLines) {
        out.write(specmap(line, s) + "\n")
      }
      out.close()
    }
  }

  override def genSpec2(f: File, dsOut: String) {
    for (s1 <- List("Double","Int","Float","Long","Boolean")) {
   	  for (s2 <- List("Double","Int","Float","Long","Boolean")) {
        val outFile = dsOut + s1 + s2 + f.getName
        val out = new BufferedWriter(new FileWriter(outFile))
        for (line <- scala.io.Source.fromFile(f).getLines) {
          out.write(specmap2(line, s1, s2) + "\n")
        }
        out.close()
	  }
    }
  }

  def specmap(line: String, t: String) : String = {
    var res = line.replaceAll("object ", "object " + t)
    res = res.replaceAll("import ", "import " + t)
    res = res.replaceAll("@specialized T: ClassManifest", t)
    res = res.replaceAll("T:Manifest", t)
    res = res.replaceAll("\\bT\\b", t)
    parmap(res)
  }

  def specmap2(line: String, t1: String, t2: String) : String = {
    var res = line.replaceAll("object ", "object " + t1 + t2)
    res = res.replaceAll("import ", "import " + t1 + t2)
    res = res.replaceAll("@specialized T: ClassManifest", t1)
    res = res.replaceAll("@specialized L: ClassManifest", t2)
    res = res.replaceAll("T:Manifest", t1)
    res = res.replaceAll("L:Manifest", t2)
    res = res.replaceAll("\\bT\\b", t1)
    res = res.replaceAll("\\bL\\b", t2)
    parmap(res)
  }

  override def remap[A](m: Manifest[A]): String = {
    parmap(super.remap(m))
  }

  def parmap(line: String): String = {
    var res = line
    for(tpe1 <- List("Int","Long","Double","Float","Boolean")) {
      for (s <- specialize) {
        res = res.replaceAll(s+"\\["+tpe1+"\\]", tpe1+s)
      }
      for(tpe2 <- List("Int","Long","Double","Float","Boolean")) {
        for (s <- specialize2) {
          // should probably parse and trim whitespace, this is fragile
          res = res.replaceAll(s+"\\["+tpe1+","+tpe2+"\\]", tpe1+tpe2+s)
          res = res.replaceAll(s+"\\["+tpe1+", "+tpe2+"\\]", tpe1+tpe2+s)
        }
		  }
	  }
    dsmap(res)
  }

  override def dsmap(line: String) : String = {
    var res = line.replaceAll("ppl.dsl.DeLiszt.datastruct", "generated")
    res = res.replaceAll("ppl.delite.framework", "generated.scala")
    res
  }
}

trait DeLisztCodeGenCuda extends DeLisztCodeGenBase with DeLisztCudaCodeGenPkg /*with CudaGenLanguageOps*/ with CudaGenArithOps with CudaGenDeliteOps with CudaGenVectorOps with CudaGenMatrixOps with CudaGenDataStruct with CudaGenTrainingSetOps with CudaGenMatrixRowOps // with CudaGenVectorViewOps
  with CudaGenVariantsOps with DeliteCudaGenAllOverrides // with DeliteCodeGenOverrideCuda // with CudaGenMLInputReaderOps  //TODO:DeliteCodeGenOverrideScala needed?
{
  val IR: DeliteApplication with DeLisztExp
  import IR._


  // Maps the scala type to cuda type
  override def remap[A](m: Manifest[A]) : String = {
    m.toString match {
      case _ => super.remap(m)
    }
  }

  override def isObjectType[T](m: Manifest[T]) : Boolean = m.toString match {
    case _ => super.isObjectType(m)
  }

  override def copyInputHtoD(sym: Sym[Any]) : String = remap(sym.Type) match {
    case _ => super.copyInputHtoD(sym)
  }

  override def copyOutputDtoH(sym: Sym[Any]) : String = remap(sym.Type) match {
    case _ => super.copyOutputDtoH(sym)
  }

  override def copyMutableInputDtoH(sym: Sym[Any]) : String = remap(sym.Type) match {
    case _ => super.copyMutableInputDtoH(sym)
  }

  override def positionMultDimInputs(sym: Sym[Any]) : String = remap(sym.Type) match {
    //TODO: Add matrix reposition, and also do safety check for datastructures that do not have data field
    case "Vector<int>" | "Vector<long>" | "Vector<float>" | "Vector<double>" | "Vector<bool>" => vectorPositionMultDimInputs(sym)
    case _ => super.positionMultDimInputs(sym)
  }

  override def getDSLHeaders: String = {
    val out = new StringBuilder
    out.append("#include <float.h>\n")
    // out.append("#include \"VectorImpl.h\"\n")
    out.toString
  }

}

trait DeLisztCodeGenC extends DeLisztCodeGenBase with DeLisztCCodeGenPkg with CGenArithOps with CGenDeliteOps with CGenVectorOps with CGenMatrixOps with CGenMatrixRowOps
  with CGenVariantsOps with DeliteCGenAllOverrides
{
  val IR: DeliteApplication with DeLisztExp
  import IR._
}
