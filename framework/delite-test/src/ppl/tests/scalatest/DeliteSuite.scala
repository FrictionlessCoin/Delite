package ppl.tests.scalatest

import org.scalatest._
import ppl.delite.framework.DeliteApplication
import ppl.delite.framework.Config
import ppl.delite.framework.codegen.Target
import ppl.delite.runtime.graph._
import ppl.delite.runtime.graph.ops._
import ppl.delite.runtime.graph.targets.Targets
import scala.lms.ops._
import scala.collection.mutable.{ ArrayBuffer, SynchronizedBuffer }
import java.io.{ File, Console => _, _ }
import java.io.FileSystem

trait DeliteTestConfig {
  // something arbitrary that we should never see in any test's output
  val MAGICDELIMETER = "!~x02$758209"

  val propFile = new File("delite.properties")
  if (!propFile.exists) throw new TestFailedException("could not find delite.properties", 3)
  val props = new java.util.Properties()
  props.load(new FileReader(propFile))

  // test parameters
  val verbose = props.getProperty("tests.verbose", "false").toBoolean
  val verboseDefs = props.getProperty("tests.verboseDefs", "false").toBoolean
  val threads = props.getProperty("tests.threads", "1")
  val cacheSyms = props.getProperty("tests.cacheSyms", "true").toBoolean
  val javaHome = new File(props.getProperty("java.home", ""))
  val scalaHome = new File(props.getProperty("scala.vanilla.home", ""))
  val deliteHome = new File(props.getProperty("delite.home",""))
  val runtimeClasses = new File(props.getProperty("runtime.classes", ""))
  val runtimeExternalProc = false // scalaHome and runtimeClasses only required if runtimeExternalProc is true. should this be configurable? or should we just remove execTestExternal?
  val deliteTestTargets = props.getProperty("tests.targets", "scala").split(",")
  val useBlas = props.getProperty("tests.extern.blas", "false").toBoolean
}

trait DeliteSuite extends Suite with DeliteTestConfig {
  val javaBin = new File(javaHome, "bin/java")
  val scalaCompiler = new File(scalaHome, "lib/scala-compiler.jar")
  val scalaLibrary = new File(scalaHome, "lib/scala-library.jar")

  val CHECK_MULTILOOP = true

  def validateParameters() {
    if (!javaHome.exists) throw new TestFailedException("java.home must be a valid path in delite.properties", 3)
    else if (!deliteHome.exists) throw new TestFailedException("delite.home must be a valid path in delite.properties", 3)
    else if (!javaBin.exists) throw new TestFailedException("Could not find valid java installation in " + javaHome, 3)
    else if (runtimeExternalProc && !scalaHome.exists) throw new TestFailedException("scala.vanilla.home must be a valid path in delite.proeprties", 3)
    else if (runtimeExternalProc && (!scalaCompiler.exists || !scalaLibrary.exists)) throw new TestFailedException("Could not find valid scala installation in " + scalaHome, 3)
    else if (runtimeExternalProc && !runtimeClasses.exists) throw new TestFailedException("runtime.classes must be a valid path in delite.properties", 3)
  }

  def uniqueTestName(app: DeliteTestRunner): String = {
    app.getClass.getName.replaceAll("\\$", "")
  }

  def degName(app: DeliteTestRunner): String = {
    uniqueTestName(app) + "-test.deg"
  }

  def compileAndTest(app: DeliteTestRunner, checkMultiLoop: Boolean = false) {
    println("=================================================================================================")
    println("TEST: " + app.toString)
    println("=================================================================================================")

    validateParameters()
    val args = Array(degName(app))
    app.resultBuffer = new ArrayBuffer[Boolean] with SynchronizedBuffer[Boolean]

    // Enable specified target code generators 
    for(t <- deliteTestTargets) {
      t match {
        case "scala" => 
        case "cuda" => Config.generateCUDA = true
        case "cpp" => Config.generateCpp = true
        case "opencl" => Config.generateOpenCL = true
        case _ => println("Unknown test target: " + t)
      }
    }

    if(useBlas) Config.useBlas = true

    // check if all multiloops in the test app are generated for specified targets
    if(checkMultiLoop) {
      val generateCUDA = Config.generateCUDA
      Config.generateCUDA = true
      stageTest(app)
      val graph = ppl.delite.runtime.Delite.loadDeliteDEG(degName(app))
      val targets = List("scala","cuda") // Add other targets 
      for(op <- graph.totalOps if op.isInstanceOf[OP_MultiLoop]) {
        targets foreach { t =>  if(!op.supportsTarget(Targets.target(t))) assert(false) }
      }
      Config.generateCUDA = generateCUDA
    }
    else { // Just stage test
      stageTest(app)
    }

    // Set runtime parameters for targets and execute runtime
    for(t <- deliteTestTargets) { 
      t match {
        case "cuda" => ppl.delite.runtime.Config.numCuda = 1
        case "cpp" => ppl.delite.runtime.Config.numCpp = 1
        case "opencl" => ppl.delite.runtime.Config.numOpenCL = 1
        case _ => 
      }
      val outStr = execTest(app, args, t) // if (runtimeExternalProc..)?
      checkTest(app, outStr)
      t match {
        case "cuda" => ppl.delite.runtime.Config.numCuda = 0
        case "cpp" => ppl.delite.runtime.Config.numCpp = 0
        case "opencl" => ppl.delite.runtime.Config.numOpenCL = 0
        case _ => 
      }
    }
  }

  private def stageTest(app: DeliteTestRunner) = {
    println("STAGING...")
    val save = Config.degFilename
    val buildDir = Config.buildDir
    val saveCacheSyms = Config.cacheSyms
    val generatedDir = deliteHome + java.io.File.separator + "generated" + java.io.File.separator + uniqueTestName(app)
    try {
      Config.degFilename = degName(app)
      Config.buildDir = generatedDir
      Config.cacheSyms = cacheSyms
      val screenOrVoid = if (verbose) System.out else new PrintStream(new ByteArrayOutputStream())
      Console.withOut(screenOrVoid) {
        app.main(Array())
        if (verboseDefs) app.globalDefs.foreach { d => //TR print all defs
          println(d)
          val s = d match { case app.TP(sym,_) => sym; case app.TTP(syms,_,_) => syms(0); case _ => sys.error("unknown Stm type: " + d) }
          val info = s.sourceInfo.drop(3).takeWhile(_.getMethodName != "main")
          println(info.map(s => s.getFileName + ":" + s.getLineNumber).distinct.mkString(","))
        }
        //assert(!app.hadErrors) //TR should enable this check at some time ...
      }
    } finally { 
      // concurrent access check 
      assert(Config.buildDir == generatedDir)
      Config.degFilename = save
      Config.buildDir = buildDir
      Config.cacheSyms = saveCacheSyms
    }
  }

  private def execTest(app: DeliteTestRunner, args: Array[String], target: String) = {
    println("EXECUTING(" + target + ")...")
    val name = "test.tmp"
    // Setting up the env variables here does not apply
    //System.setProperty("delite.threads", threads.toString)
    //System.setProperty("delite.code.cache.home", "generatedCache" + java.io.File.separator + uniqueTestName)
    Console.withOut(new PrintStream(new FileOutputStream(name))) {
      println("test output for: " + app.toString)
      ppl.delite.runtime.Delite.embeddedMain(args, app.staticDataMap)
    }
    val buf = new Array[Byte](new File(name).length().toInt)
    val fis = new FileInputStream(name)
    fis.read(buf)
    fis.close()
    val r = new String(buf)
    if (verbose) System.out.println(r)
    r
  }

  private def execTestExternal(args: Array[String]) = {
    println("EXECUTING...")
    //Delite.main(args)
    // need to use a different compiler version to build and run Delite
    var p: Process = null
    val output = new File("test.tmp")
    try {
      val javaProc = javaBin.toString
      val javaArgs = "-server -d64 -XX:+UseCompressedOops -XX:+DoEscapeAnalysis -Xmx16g -Ddelite.threads=" + threads + " -cp " + runtimeClasses + ":" + scalaLibrary + ":" + scalaCompiler
      val cmd = Array(javaProc) ++ javaArgs.split(" ") ++ Array("ppl.delite.runtime.Delite") ++ args
      val pb = new ProcessBuilder(java.util.Arrays.asList(cmd: _*))
      p = pb.start()
    } catch {
      case e: Throwable => e.printStackTrace()
    }

    var exited = 3.14
    val errStream = p.getErrorStream
    val errStreamReader = new BufferedReader(new InputStreamReader(errStream))
    val outStream = p.getInputStream
    val outStreamReader = new BufferedReader(new InputStreamReader(outStream))
    val buf = new StringBuilder()

    // have to read streams and check process exit concurrently, or process can fill its buffers and hang
    while (exited == 3.14 || errStream.available() > 0 || outStream.available() > 0) {
      if (errStream.available() > 0) {
        System.err.println(errStreamReader.readLine())
      }

      if (outStream.available() > 0) {
        var line = outStreamReader.readLine()
        buf append line
        buf append System.getProperty("line.separator")
        if (verbose) System.out.println(line)
      }

      exited =
        try { p.exitValue() }
        catch { case e => 3.14 }
    }

    buf.toString
  }

  private def checkTest(app: DeliteTestRunner, outStr: String) {
    println("CHECKING...")
    val resultStr = outStr substring (outStr.indexOf(MAGICDELIMETER) + MAGICDELIMETER.length, outStr.lastIndexOf(MAGICDELIMETER))
    val results = resultStr split ","
    for (i <- 0 until results.length) {
      if (verbose) print("  condition " + i + ": ")
      val passed = results(i).toLowerCase() == "true"
      if (verbose)
        if (passed) println("PASSED") else println("FAILED")
      assert(passed)
    }
  }
}

// how do we add our code generators? right now we expect a single codegen package being supplied by the dsl.
// the workaround for now is that the dsl under test must include ArrayBuffer in its code gen
trait DeliteTestRunner extends DeliteTestModule with DeliteApplication
  with MiscOpsExp with SynchronizedArrayBufferOpsExp with StringOpsExp {

  var resultBuffer: ArrayBuffer[Boolean] = _

  def collector: Rep[ArrayBuffer[Boolean]] = staticData(resultBuffer)
}

// it is not ideal that the test module imports these things into the application under test
trait DeliteTestModule extends DeliteTestConfig
  with MiscOps with SynchronizedArrayBufferOps with StringOps {

  //var args: Rep[Array[String]]
  def main(): Unit

  //var collector: Rep[ArrayBuffer[Boolean]] = null.asInstanceOf[Rep[ArrayBuffer[Boolean]]]
  //  lazy val ctest = ArrayBuffer[Boolean]()
  //
  //  def collect(s: Rep[Boolean]) {
  //    // interpreted if/then/else!
  //    //if (collector == null) collector = ArrayBuffer[Boolean]()
  //    ctest append s
  //  }
  //
  //  def mkReport() {
  //    println(MAGICDELIMETER + (ctest mkString unit(",")) + MAGICDELIMETER)
  //  }

  def collector: Rep[ArrayBuffer[Boolean]]

  def collect(s: Rep[Boolean]) { collector += s }

  def mkReport(): Rep[Unit] = {
    println(unit(MAGICDELIMETER) + (collector mkString unit(",")) + unit(MAGICDELIMETER))
  }

  /*
  def collect(s: Rep[Boolean])(implicit c: Rep[ArrayBuffer[Boolean]]) { c += s }

  def mkReport(implicit c: Rep[ArrayBuffer[Boolean]]): Rep[Unit] = {
    println(MAGICDELIMETER + (c mkString unit(",")) + MAGICDELIMETER)
  }
  */

  /*
  def main() = {
    test()
    out
  }

  def test(): Rep[Unit]
  */
}
