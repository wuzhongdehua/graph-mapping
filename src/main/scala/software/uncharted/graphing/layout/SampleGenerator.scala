package software.uncharted.graphing.layout

import java.io._

import scala.util.{Failure, Success, Try}

/**
  * Make a sample dataset that is easy to debug
  */
object SampleGenerator {
  def writeNode(out: Writer, node: Node): Unit = {
    out.write(node.toString + "\n")
  }

  def writeEdge(out: Writer, edge: Edge): Unit = {
    out.write(edge.toString + "\n")
  }

  private var nextAvailableNodeId: Long = 0L
  def getNextId: Long = {
    val result = nextAvailableNodeId
    nextAvailableNodeId += 1L
    result
  }

  def main (args: Array[String]): Unit = {
    val levels: Int = Try{
      args(0).toInt
    } match {
      case Success(n) => n
      case Failure(e) =>
        println("Required argument: number of levels")
        System.exit(0)
        throw e
    }

    val (nodes, edges) = createNodes(levels)

    for (level <- 0 to levels) {
      val directory = new File(s"level_$level")
      if (!directory.exists()) directory.mkdir()

      val nodeFile = new File(directory, "nodes")
      if (nodeFile.exists()) nodeFile.delete()
      val nodeFileWriter = new OutputStreamWriter(new FileOutputStream(nodeFile))
      nodes(level).foreach(node => writeNode(nodeFileWriter, node))
      nodeFileWriter.flush()
      nodeFileWriter.close()

      val edgeFile = new File(directory, "edges")
      if (edgeFile.exists()) edgeFile.delete()
      val edgeFileWriter = new OutputStreamWriter(new FileOutputStream(edgeFile))
      edges(level).foreach(edge => writeEdge(edgeFileWriter, edge))
      edgeFileWriter.flush()
      edgeFileWriter.close()
    }
  }

  def createNodes (maxLevel: Int): (Array[Seq[Node]], Array[Seq[Edge]]) = {
    val levels = new Array[Seq[Node]](maxLevel + 1)
    val edges  = new Array[Seq[Edge]](maxLevel + 1)
    levels(0)  = Seq(Node(getNextId, 128.0, 128.0, 128.0, None, 1L, 0, "0"))
    edges(0)   = Seq(Edge(levels(0)(0), levels(0)(0), false, 1L))

    for (level <- 1 to maxLevel) {
      val (lvlSeq, edgeSeq) = createNodesAndEdgesForLevel(level, maxLevel, levels(level - 1))
      levels(level) = lvlSeq
      edges(level) = edgeSeq
    }

    (levels, edges)
  }

  def createNodesAndEdgesForLevel (level: Int, maxLevel: Int, upperLevel: Seq[Node]): (Seq[Node], Seq[Edge]) = {
    val isMaxLevel = (level == maxLevel)

    def mkEdge (a: Node, b: Node, weight: Long) =
      Edge(a, b, isMaxLevel, weight)


    val (nodes, internalEdges) = upperLevel.map { p /* parent */ =>
      val offset = 256.0 /  (1 to level).map(n => 4.0).reduce(_ * _)

      val node0 = Node(p.id,      p.x,          p.y,          offset/2, Some(p), 1L, 0, p.metaData+":0")
      val node1 = Node(getNextId, p.x - offset, p.y + offset, offset/2, Some(p), 1L, 0, p.metaData+":1")
      val node2 = Node(getNextId, p.x         , p.y + offset, offset/2, Some(p), 1L, 0, p.metaData+":2")
      val node3 = Node(getNextId, p.x + offset, p.y + offset, offset/2, Some(p), 1L, 0, p.metaData+":3")
      val node4 = Node(getNextId, p.x + offset, p.y         , offset/2, Some(p), 1L, 0, p.metaData+":4")
      val node5 = Node(getNextId, p.x + offset, p.y - offset, offset/2, Some(p), 1L, 0, p.metaData+":5")
      val node6 = Node(getNextId, p.x         , p.y - offset, offset/2, Some(p), 1L, 0, p.metaData+":6")
      val node7 = Node(getNextId, p.x - offset, p.y - offset, offset/2, Some(p), 1L, 0, p.metaData+":7")
      val node8 = Node(getNextId, p.x - offset, p.y         , offset/2, Some(p), 1L, 0, p.metaData+":8")

      p.setChildren(node1, node2, node3, node4, node5, node6, node7, node8)
      val nodes = Seq(node0, node1, node2, node3, node4, node5, node6, node7, node8)

      val internalEdges = Seq(
        mkEdge(node0, node1, 1L),
        mkEdge(node0, node2, 1L),
        mkEdge(node0, node3, 1L),
        mkEdge(node0, node4, 1L),
        mkEdge(node0, node5, 1L),
        mkEdge(node0, node6, 1L),
        mkEdge(node0, node7, 1L),
        mkEdge(node0, node8, 1L),
        mkEdge(node1, node4, 1L),
        mkEdge(node4, node7, 1L),
        mkEdge(node7, node2, 1L),
        mkEdge(node2, node5, 1L),
        mkEdge(node5, node8, 1L),
        mkEdge(node8, node3, 1L),
        mkEdge(node3, node6, 1L),
        mkEdge(node6, node1, 1L)
      )
      (nodes, internalEdges)
    }.reduce((a, b) => (a._1 ++ b._1, a._2 ++ b._2))

    val maxNumExternalEdges = (1 to level).map(n => 4).reduce(_ * _)
    val r = scala.util.Random
    val externalEdges = (1 to maxNumExternalEdges).flatMap{n =>
      val n1 = nodes(r.nextInt(nodes.length))
      val n2 = nodes(r.nextInt(nodes.length))

      if (n1 != n2 && n1.parent != n2.parent) {
        Some(mkEdge(n1, n2, 1L))
      } else {
        None
      }
    }

    (nodes, (internalEdges ++ externalEdges))
  }

  case class Node(id: Long,
                  x: Double,
                  y: Double,
                  radius: Double,
                  parent: Option[Node],
                  var numInternalNodes: Long,
                  var degree: Int,
                  metaData: String) {
    var children: Option[Seq[Node]] = None

    def setChildren (children: Node*): Unit = {
      addInternalNode(children.length)
      this.children = Some(children)
    }

    def addDegree (n: Int): Unit = {
      degree += n
      parent.map(_.addDegree(n))
    }

    def addInternalNode (n: Long): Unit = {
      numInternalNodes += n
      parent.map(_.addInternalNode(n))
    }

    override def toString =
      if (parent.isEmpty) {
        "node\t" + id + "\t" + x + "\t" + y + "\t" + radius + "\t" + id + "\t" + x + "\t" + y + "\t" + radius + "\t" + numInternalNodes + "\t" + degree + "\t" + metaData
      } else {
        val p = parent.get
        "node\t" + id + "\t" + x + "\t" + y + "\t" + radius + "\t" + p.id + "\t" + p.x + "\t" + p.y + "\t" + p.radius + "\t" + numInternalNodes + "\t" + degree + "\t" + metaData
      }
  }

  case class Edge(src: Node,
                  dst: Node,
                  maxLevel: Boolean,
                  weight: Long) {
    src.addDegree(1)
    dst.addDegree(1)

    val interCommunityEdge =if ((src.parent != dst.parent) || maxLevel) 1 else 0
    override def toString = "edge\t" + src.id + "\t" + src.x + "\t" + src.y + "\t" + dst.id + "\t" + dst.x + "\t" + dst.y + "\t" + weight + "\t" + interCommunityEdge
  }
}