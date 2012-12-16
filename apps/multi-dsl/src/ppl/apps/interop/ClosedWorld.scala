package ppl.apps.interop

import ppl.dsl.optiql.OptiQL_
import ppl.dsl.optigraph.OptiGraph_
import ppl.dsl.optiml.OptiML_
import ppl.delite.framework.datastructures.DeliteArray
import ppl.delite.framework.EndScopes

import ppl.dsl.optigraph.NodeProperty

object CloseWorldCompose {
  
  def main(args: Array[String]) {
    println("scala 1")
    
    OptiQL_ {
      type Tweet = Record {
        val id: String
        val time: Date
        val fromId: Int
        val toId: Int
        val language: String
        val text: String
      }      
      def Tweet(_id: Rep[String], _time: Rep[Date], _fromId: Rep[Int], _toId: Rep[Int], _language: Rep[String], _text: Rep[String]): Rep[Tweet] = new Record {
        val id = _id;
        val time = _time;
        val fromId = _fromId;
        val toId = _toId;
        val language = _language;
        val text = _text;
      }      
      def emptyTweet(): Rep[Tweet] = Tweet("", Date(""), 0, 0, "", "")
            
      // type Tweet = Record{val fromId: Int; val toId: Int; val text: String}
      // val tweets: Rep[Table[Tweet]] = loadTweets() // elided
      val tweets = TableInputReader(args(0)+"/tweet.tbl", emptyTweet())      
      val result = tweets Where(t => t.time >= Date("2008-01-01") && t.language == "en") 
      // is there something in the desugaring scopes that ignores the block result?
      // without returnScopeResult, getting a block result of (), even the Scope result is of type R
      //returnScopeResult(result.toArray)
      println(result.toArray)
    }
    
    OptiGraph_ {      
      val da1 = DeliteArray[Int](12)
      val da2 = DeliteArray[Int](12)
      da1(0) = 0; da2(0) = 3
      da1(1) = 0; da2(1) = 1
      da1(2) = 0; da2(2) = 2
      da1(3) = 1; da2(3) = 4
      da1(4) = 1; da2(4) = 6
      da1(5) = 2; da2(5) = 0
      da1(6) = 2; da2(6) = 3
      da1(7) = 2; da2(7) = 4
      da1(8) = 3; da2(8) = 0
      da1(9) = 3; da2(9) = 5
      da1(10) = 3; da2(10) = 1
      da1(11) = 3; da2(11) = 2
      val da = da1.zip(da2)((i,j) => t2(i,j))
      val G = Graph.fromArray(da)
      // type Tweet = Record { val id: String; val time: Date; val fromId: Int; val toId: Int; val language: String; val text: String }
      // val in = lastScopeResult.asInstanceOf[Rep[DeliteArray[Tweet]]]
      // val G = Graph.fromArray(in.map(t => (t.fromId,t.toId)))                  
      
      // LCC
      // TODO: DeliteCodeGenRestage breaks when we have multiple Node Properties of different types
      val LCC: Rep[NodeProperty[Double]] = NodeProperty[Double](G, 0.0)
      val RT: Rep[NodeProperty[Double]] = NodeProperty[Double](G, 0.0)
      val threshold = 1
      
      Foreach(G.Nodes) { s =>
        var triangles = 0 
        var total = 0

        Foreach(G.InNbrs(s)) { t =>
          if (G.HasOutNbr(s,t)) {
            Foreach(G.InNbrs(s).filter(n => G.HasOutNbr(n,t))) { u =>
              if (G.HasOutNbr(s,u)) {
                if (G.HasOutNbr(u,t)) {triangles += 1}
                if (G.HasOutNbr(t,u)) {triangles += 1}
                total += 2
              }
            }
          }
        }
        if (total < threshold) {
          LCC(s) = 0.0
          println("Computed LCC = " + LCC(s))
          //println("Total (" + total.value + ") was less than threshold")
        } else {
          LCC(s) = (triangles) / (total)
          println("Computed LCC = " + LCC(s) + " = " + triangles + " / " + total)
        }
      }
      
      // retweet count
      //TODO: Better to accumulate the number of retweets this node 
      Foreach(G.Nodes) { t =>
        RT(t) = G.InNbrs(t).length
        println("inNbrs = " + G.InNbrs(t).length)
      }
            
      // returnScopeResult((LCC.toArray, RT.toArray))
    }
        
    OptiML_ {
      // unweighted linear regression
      // val in = lastScopeResult.AsInstanceOfInstanceOf[(DeliteArray[Double],DeliteArray[Double])]
      // val X = Matrix.fromArray(tuple2_get1(in), numFeatures = 1)/*.mutable*/
      // val y = Vector.fromArray(tuple2_get2(in)).t
      val X = readMatrix(args(0)+"/ml/linreg/q2x.dat")
      val y = readVector(args(0)+"/ml/linreg/q2y.dat").t
      X.insertCol(0, Vector.ones(X.numRows).t)
      val x2 = X.unsafeImmutable
      val theta = y+y
      // val theta = ((x2.t*x2).inv)*(x2.t*y)
      println("theta(0): " + theta(0))
      // theta.pprint
      // linreg.weighted(readMatrix(args(0),readVector(args(1)).t))
      // println("got input from previous stage: " + previous(0).AsInstanceOf[DeliteArray[Int]]) 
      // println("optiml 2")
    }    
    
    EndScopes() // marker to complete the scope file
    
    // unstaged blocks get executed immediately like normal
    println("scala 2")
  }
}