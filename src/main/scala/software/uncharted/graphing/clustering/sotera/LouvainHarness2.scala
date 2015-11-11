/**
 * Copyright © 2014-2015 Uncharted Software Inc. All rights reserved.
 *
 * Property of Uncharted™, formerly Oculus Info Inc.
 * http://uncharted.software/
 *
 * This software is the confidential and proprietary information of
 * Uncharted Software Inc. ("Confidential Information"). You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with Uncharted Software Inc.
 */

package software.uncharted.graphing.clustering.sotera

import org.apache.spark.SparkContext
import org.apache.spark.graphx._

import scala.collection.mutable
import scala.reflect.ClassTag

/**
 * Coordinates execution of the louvain distributed community detection process on a graph.
 *
 * The input Graph must have an edge type of Long.
 *
 * All lower level algorithm functions are in LouvainCore, this class acts to 
 * coordinate calls into LouvainCore and check for convergence criteria
 *
 * Two hooks are provided to allow custom behavior
 *    -saveLevel  override to save the graph (vertcies/edges) after each phase of the process 
 *    -finalSave  override to specify a final action / save when the algorithm has completed. (not nessicary if saving at each level)
 *
 * High Level algorithm description.
 *
 *  Set up - Each vertex in the graph is assigned its own community.
 *  1.  Each vertex attempts to increase graph modularity by changing to a neighboring community, or reamining in its current community.
 *  2.  Repeat step 1 until progress is no longer made 
 *         - progress is measured by looking at the decrease in the number of vertices that change their community on each pass.
 *           If the change in progress is < minProgress more than progressCounter times we exit this level.
 *  3. -saveLevel, each vertex is now labeled with a community.
 *  4. Compress the graph representing each community as a single node.
 *  5. repeat steps 1-4 on the compressed graph.
 *  6. repeat until modularity is no longer improved
 *
 *  For details see:  Fast unfolding of communities in large networks, Blondel 2008
 *
 *  Code adapted from Sotera's graphX implementation of the distributed Louvain modularity algorithm
 *  https://github.com/Sotera/spark-distributed-louvain-modularity
 */
class  LouvainHarness2(minProgressFactor:Double,progressCounter:Int) {


  def run[VD: ClassTag](sc:SparkContext,graph:Graph[VD,Long]) = {

    var louvainGraphTemp = LouvainCore2.createLouvainGraph(graph)
    var louvainGraph = (Graph(louvainGraphTemp.vertices, LouvainCore2.tempPartitionBy(louvainGraphTemp.edges, PartitionStrategy.EdgePartition2D)))
      .groupEdges(_+_)
    louvainGraph.cache


    var level = -1  // number of times the graph has been compressed
    var q = -1.0    // current modularity value
    var halt = false
    var startTime = System.currentTimeMillis()
    var startModularity = Double.NaN
    val stats = mutable.Buffer[IterationStats]()
    do {
      level += 1
      println(s"\nStarting Louvain level $level")

      val minProgress = (minProgressFactor * louvainGraph.numVertices).toInt max 10

      // label each vertex with its best community choice at this level of compression
      val (currentQ,currentGraph,passes) = LouvainCore2.louvain(sc, louvainGraph,minProgress,progressCounter)
      currentGraph.cache
      louvainGraph.unpersistVertices(blocking=false)

      saveLevel(sc,level,currentQ,currentGraph)

      // If modularity was increased by at least 0.001 compress the graph and repeat
      // halt immediately if the community labeling took less than 3 passes
      //println(s"if ($passes > 2 && $currentQ > $q + 0.001 )")
      if (passes > 2 && currentQ > q + 0.001 ){
        //q = currentQ
        //louvainGraph = LouvainCore.compressGraph(louvainGraph)
      }
      else {
        halt = true
      }
      q = currentQ
      louvainGraph = LouvainCore2.compressGraph(currentGraph)

      val endTime = System.currentTimeMillis()

      // Calculate stats
      val nodes = louvainGraph.vertices.count
      val links = louvainGraph.edges.count
      val weight = louvainGraph.edges.map(_.attr).reduce(_ + _).toDouble
      stats += IterationStats(level, startTime, endTime, nodes, links, weight, startModularity, currentQ)
      startTime = System.currentTimeMillis()
      startModularity = currentQ
    } while ( !halt )

    //level += 1
    //saveLevel(sc,level,q,louvainGraph)	// save final level after graph compression
    finalSave(sc,level,q,louvainGraph)



    // Print out our calculation stats
    stats.foreach(_.print)
    val totalDuration = stats.map(stat => stat.endTime - stat.startTime).fold(0L)(_+_)/1000.0
    println("Total duration: %.3f seconds".format(totalDuration))
  }

  /**
   * Save the graph at the given level of compression with community labels
   * level 0 = no compression
   *
   * override to specify save behavior
   */
  def saveLevel(sc:SparkContext,level:Int,q:Double,graph:Graph[VertexState,Long]) = {
  }

  /**
   * Complete any final save actions required
   *
   * override to specify save behavior
   */
  def finalSave(sc:SparkContext,level:Int,q:Double,graph:Graph[VertexState,Long]) = {
  }
}
