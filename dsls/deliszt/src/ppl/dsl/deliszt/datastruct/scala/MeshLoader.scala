package ppl.dsl.deliszt.datastruct.scala

import java.io._
import net.liftweb.json.{JsonParser, JsonAST}
import net.liftweb.json.JsonDSL._
import MetaInteger._

/**
 * author: Michael Wu (mikemwu@stanford.edu)
 * last modified: 04/05/2011
 *
 * Pervasive Parallelism Laboratory (PPL)
 * Stanford University
 */

object MeshLoader {
  var loaded = false

  def init(cfgPath : String) {
    if(!loaded) {
      try {
        System.loadLibrary("MeshLoader");
      }
      catch {
        case e: java.lang.UnsatisfiedLinkError => if(e.getMessage.indexOf("already loaded") < 0) throw e
      }

      loaded = true
    }

    val cfgFile = new File(cfgPath)

    if(!cfgFile.exists) {
      throw new FileNotFoundException("Liszt cfg file " + cfgPath + " does not exist")
    }
    
    val cfg = new BufferedReader(new FileReader(cfgFile))
    val json = JsonParser.parse(cfg)

    implicit val formats = net.liftweb.json.DefaultFormats

    Mesh.loader = new MeshLoader()

    case class MeshFilename(`mesh-file`: String)

    val meshFilename = json.extract[MeshFilename].`mesh-file`

    println("Loading mesh file " + meshFilename)
    
    var file = new File(meshFilename)
    if(!file.exists()) {
      val resource = getClass.getResource(meshFilename)

      if(resource != null) {
        file = new File(resource.getPath)
      }
    }

    if(!file.exists()) {
      file = new File(cfgFile.getParent, meshFilename)
    }

    if(file.exists()) {
      println("File exists, found at " + file.getPath)
      Mesh.mesh = Mesh.loader.loadMesh(file.getPath)
      
      /*val v = LabelData.vertexData
      
      println("LABELS")
      for( (key, value) <- v.data ) {
        println(key)
      }
      
      val pos = v.data("position")
      
      for(i <- 0 until 3) {
        val vec = pos(i).asInstanceOf[Array[Double]]
        
        println("Array " + i)
        for(j <- 0 until 3) {
          println(vec(j))
        }
      }
      
      val f = Mesh.label[Vertex,Vec[_3,Float]]("position")
      
      for(i <- 0 until 3) {
        val vec = f(i)
        
        println("Vector " + i)
        for(j <- 0 until 3) {
          println(vec(j))
        }
      }
	  
      val vs = Mesh.meshSet[Vertex]
      println("size")
      println(vs.size)
      */
      
      println("ncells: " + Mesh.mesh.ncells)
      println("nedges: " + Mesh.mesh.nedges)
      println("nfaces: " + Mesh.mesh.nfaces)
      println("nvertices: " + Mesh.mesh.nvertices)
    }
    else {
      throw new FileNotFoundException("Mesh file " + meshFilename + " does not exist")
    }
  }
}

class MeshLoader {
  @native
  def loadMesh(file : String) : Mesh = null

  def loadBoundarySet[MO<:MeshObj:MeshObjConstruct](name : String) : BoundarySet[MO] = {  
    _loadBoundarySet(name)
  }

  @native
  def _loadBoundarySet[MO<:MeshObj:MeshObjConstruct](name : String) : BoundarySet[MO] = null
}