import sbt._
import Keys._

object DeliteBuild extends Build {
  val lms = "ch.epfl" %% "lms" % "0.4-SNAPSHOT"
  
  // -DshowSuppressedErrors=false
  System.setProperty("showSuppressedErrors", "false")

  val mavenLocal = "Maven Local" at "file://"+Path.userHome+"/.m2/repository" // for custom-built scala version

  val scalaTestCompile = "org.scalatest" % "scalatest_2.10" % "2.0.M5b"
  val scalaTest = scalaTestCompile % "test"

  val virtScala = Option(System.getenv("SCALA_VIRTUALIZED_VERSION")).getOrElse("2.10.1")
  val virtBuildSettingsBase = Defaults.defaultSettings ++ Seq(
    //resolvers := Seq(mavenLocal, prereleaseScalaTest, Resolver.sonatypeRepo("snapshots"), Resolver.sonatypeRepo("releases")),
    organization := "stanford-ppl",
    scalaOrganization := "org.scala-lang.virtualized",
    //scalaHome := Some(file(Path.userHome + "/scala/build/pack")),
    scalaVersion := virtScala,
    scalaBinaryVersion := "2.10",
    publishArtifact in (Compile, packageDoc) := false,
    // needed for scala.tools, which is apparently not included in sbt's built in version
    libraryDependencies += lms,
    libraryDependencies += "org.scala-lang.virtualized" % "scala-library" % virtScala,
    libraryDependencies += "org.scala-lang.virtualized" % "scala-compiler" % virtScala,
    libraryDependencies += "org.scala-lang" % "scala-actors" % virtScala, // for ScalaTest
    libraryDependencies += scalaTest,
    libraryDependencies += "org.apache.commons" % "commons-math" % "2.2",
    libraryDependencies += "com.google.protobuf" % "protobuf-java" % "2.4.1",
    libraryDependencies += "org.apache.mesos" % "mesos" % "0.9.0-incubating",    

    // used in delitec to access jars
    retrieveManaged := true,
    scalacOptions += "-Yno-generic-signatures",
    scalacOptions += "-Yvirtualize"
  )

  val virtBuildSettings = virtBuildSettingsBase ++ Seq(
    scalaSource in Compile <<= baseDirectory(_ / "src"),
    scalaSource in Test <<= baseDirectory(_ / "tests"),
    parallelExecution in Test := false
  )


  /*
  val vanillaScala = "2.9.1"
  val vanillaBuildSettings = Defaults.defaultSettings ++ Seq(
    //scalaSource in Compile <<= baseDirectory(_ / "src"),
    //scalaVersion := vanillaScala,
    // needed for scala.tools, which is apparently not included in sbt's built in version
    libraryDependencies += "org.scala-lang" % "scala-library" % vanillaScala,
    libraryDependencies += "org.scala-lang" % "scala-compiler" % vanillaScala
  )
  */

  /*
  lazy val getJars = TaskKey[Unit]("get-jars")
  lazy val getJarsTask = getJars <<= (target, fullClasspath in Runtime) map { (target, cp) =>
    println("Target path is: "+target)
    println("Full classpath is: "+cp.map(_.data).mkString(":"))
  }
  */

  // build targets

  // _ forces sbt to choose it as default
  // useless base directory is to avoid compiling leftover .scala files in the project root directory
  lazy val _delite = Project("delite", file("project/boot"),
    settings = virtBuildSettings) aggregate(framework, dsls, runtime, apps, tests)

  lazy val framework = Project("framework", file("framework"), settings = virtBuildSettings) dependsOn(runtime) // dependency to runtime because of Scopes

  lazy val deliteTest = Project("delite-test", file("framework/delite-test"), settings = virtBuildSettings ++ Seq(
    libraryDependencies += scalaTestCompile 
  )) dependsOn(framework, runtime)

  lazy val dsls = Project("dsls", file("dsls"), settings = virtBuildSettings) aggregate(optila, optiml, optiql, optimesh, optigraph, opticvx) 
  lazy val optila = Project("optila", file("dsls/optila"), settings = virtBuildSettings) dependsOn(framework, deliteTest)
  lazy val optiml = Project("optiml", file("dsls/optiml"), settings = virtBuildSettings) dependsOn(optila, deliteTest)
  lazy val optiql = Project("optiql", file("dsls/optiql"), settings = virtBuildSettings) dependsOn(framework, deliteTest)
  lazy val optimesh = Project("optimesh", file("dsls/deliszt"), settings = virtBuildSettings) dependsOn(framework, deliteTest)
  lazy val optigraph = Project("optigraph", file("dsls/optigraph"), settings = virtBuildSettings) dependsOn(framework, deliteTest)
  lazy val opticvx = Project("opticvx", file("dsls/opticvx"), settings = virtBuildSettings) dependsOn(framework, deliteTest)

  lazy val apps = Project("apps", file("apps"), settings = virtBuildSettings) aggregate(optimlApps, optiqlApps, optimeshApps, optigraphApps, opticvxApps, interopApps)
  lazy val optimlApps = Project("optiml-apps", file("apps/optiml"), settings = virtBuildSettings) dependsOn(optiml)
  lazy val optiqlApps = Project("optiql-apps", file("apps/optiql"), settings = virtBuildSettings) dependsOn(optiql)
  lazy val optimeshApps = Project("optimesh-apps", file("apps/deliszt"), settings = virtBuildSettings) dependsOn(optimesh)
  lazy val optigraphApps = Project("optigraph-apps", file("apps/optigraph"), settings = virtBuildSettings) dependsOn(optigraph)
  lazy val opticvxApps = Project("opticvx-apps", file("apps/opticvx"), settings = virtBuildSettings) dependsOn(opticvx)
  lazy val interopApps = Project("interop-apps", file("apps/multi-dsl"), settings = virtBuildSettings) dependsOn(optiml, optiql, optigraph) // dependsOn(dsls) not working

  lazy val runtime = Project("runtime", file("runtime"), settings = virtBuildSettings)

  lazy val tests = Project("tests", file("tests"), settings = virtBuildSettingsBase ++ Seq(
    scalaSource in Test <<= baseDirectory(_ / "src"),
    parallelExecution in Test := false
    // don't appear to be able to depend on a different scala version simultaneously, so just using scala-virtualized for everything
  )) dependsOn(framework, runtime, optiml, optimlApps, deliteTest)
  //dependsOn(framework % "test->compile;compile->compile", optiml % "test->compile;compile->compile", optiql % "test", optimlApps % "test->compile;compile->compile", runtime % "test->compile;compile->compile")
}
