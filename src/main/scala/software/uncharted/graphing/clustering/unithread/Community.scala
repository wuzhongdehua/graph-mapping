/**
  * Copyright (c) 2014-2017 Uncharted Software Inc. All rights reserved.
  *
  * Property of Uncharted(tm), formerly Oculus Info Inc.
  * http://uncharted.software/
  *
  * This software is the confidential and proprietary information of
  * Uncharted Software Inc. ("Confidential Information"). You shall not
  * disclose such Confidential Information and shall use it only in
  * accordance with the terms of the license agreement you entered into
  * with Uncharted Software Inc.
  */
package software.uncharted.graphing.clustering.unithread

import java.io.{BufferedReader, File, FileInputStream, FileOutputStream, InputStreamReader, PrintStream}

import grizzled.slf4j.Logging
import com.typesafe.config.{Config, ConfigFactory}
import software.uncharted.graphing.analytics.CustomGraphAnalytic
import software.uncharted.graphing.utilities.{ArgumentParser, ConfigLoader, ConfigReader}

import scala.collection.mutable.{Buffer => MutableBuffer, Map => MutableMap}
import scala.collection.Seq
import scala.io.Source
import scala.util.{Failure, Random, Success}

//scalastyle:off file.size.limit method.length cyclomatic.complexity multiple.string.literals regex

/**
  * Trait to specify what modification to apply to the baseline algorithm, along with the parameters needed
  * to modify it as specified
  */
trait AlgorithmModification

/** Use the algorithm as is */
class BaselineAlgorithm extends AlgorithmModification

/** Only allow nodes to change community to that of a node with sufficiently low degree, where "sufficiently" is
  * defined in a level-dependent manner */
class NodeDegreeAlgorithm (val degreeLimit: Long) extends AlgorithmModification

/** Same as NodeDegreeAlgorithm, but with limits set independently by level */
class UnlinkedNodeDegreeAlgorithm (val degreeLimits: Long*) extends AlgorithmModification

/** Only allow nodes to change community to communities still below a given size. */
class CommunitySizeAlgorithm (val maximumSize: Int) extends AlgorithmModification

/**
  *
  * @param g The graph to cluster
  * @param nb_pass number of pass for one level computation
  *                if -1, compute as many pass as needed to increas modularity
  * @param min_modularity a new pass is computed if the last one has generated an increase greater than min_modularity
  *                       if 0, even a minor increase is enough to go for one more pass
  * @param algorithmMod The modification to apply to the baseline clustering algorithm.
  */
class Community (val g: Graph,
                 nb_pass: Int,
                 min_modularity: Double,
                 algorithmMod: AlgorithmModification = new BaselineAlgorithm) {

  /**
    *
    * @param filename The base file from which to read edges
    * @param filename_w The file from which to read edge weights
    * @param filename_m The file from which to read node metadata
    * @param nbp nb_pass in the base constructor
    * @param minm min_modularity in the base constructor
    * @param customAnalytics A sequence of custom analytics to apply to the data. Included is lists of other relevant
    *                        columns to use.
    * @param algorithmMod algorithmMod in the base constructor
    * @return
    */
  def this (filename: String,  filename_w: Option[String], filename_m: Option[String],
            nbp: Int, minm: Double, customAnalytics: Array[CustomGraphAnalytic[_]],
            algorithmMod: AlgorithmModification) =
    this(Graph(filename, filename_w, filename_m, customAnalytics), nbp, minm, algorithmMod)

  val size = g.nb_nodes
  val n2c = new Array[Int](size)
  val community_size = new Array[Int](size)
  for (i <- 0 until size) {
    n2c(i) = i
    community_size(i) = 1
  }

  val tot: Array[Double] = n2c.map(n => g.weightedDegree(n))
  val in: Array[Double] = n2c.map(n => g.nbSelfLoops(n))
  val neigh_weight = n2c.map(n => -1.0)
  val neigh_pos = n2c.map(n => 0)
  var neigh_last: Int = 0
  var comm_nodes: Seq[Seq[Int]] = Seq()


  /**
    * Remove a node from a community.
    * @param node Node to remove from the community.
    * @param comm Community from which the node is removed.
    * @param dnodecomm Node community weight.
    */
  def remove(node: Int, comm: Int, dnodecomm: Double): Unit = {
    tot(comm) -= g.weightedDegree(node)
    in(comm) -= (2 * dnodecomm + g.nbSelfLoops(node))
    community_size(comm) -= 1
    n2c(node) = -1
  }

  /**
    * Insert a node in a community.
    * @param node The node being added.
    * @param comm The community to which the node is being added.
    * @param dnodecomm Node community weight.
    */
  def insert(node: Int, comm: Int, dnodecomm: Double): Unit = {
    tot(comm) += g.weightedDegree(node)
    in(comm) += (2 * dnodecomm + g.nbSelfLoops(node))
    community_size(comm) += 1
    n2c(node) = comm
  }

  /**
    * Calculate the modularity gain from a change in communities.
    * @param comm Community on which to calculate the modularity gain.
    * @param dnodecomm Node community weight.
    * @param w_degree Weighted degree.
    * @return The modularity gain.
    */
  def modularityGain(comm: Int, dnodecomm: Double, w_degree: Double): Double = {
    val totc = tot(comm)
    val degc = w_degree
    val m2 = g.total_weight
    val dnc = dnodecomm

    dnc - totc * degc / m2
  }

  /**
    * Calculate the modularity of the current set of communities.
    * @return The calculated modularity.
    */
  def modularity: Double = {
    var q = 0.0
    val m2 = g.total_weight

    for (i <- 0 until size) {
      if (tot(i) > 0) {
        val tm = tot(i) / m2
        q += in(i) / m2 - tm * tm
      }
    }

    q
  }

  /**
    * Calculate information used to determine which communities a node should join.
    * @param node Node being processed.
    */
  private def neighComm(node: Int): Unit = {
    for (i <- 0 until neigh_last) {
      neigh_weight(neigh_pos(i)) = -1
    }
    val degree = g.nbNeighbors(node)
    neigh_pos(0) = n2c(node)
    neigh_weight(neigh_pos(0)) = 0
    neigh_last = 1

    val allowed: Int => Boolean = algorithmMod match {
      case nd: NodeDegreeAlgorithm =>
        // Only allow joining with this neighbor if its degree is less than our limit.
        nn => g.nbNeighbors(nn) < nd.degreeLimit
      case cs: CommunitySizeAlgorithm =>
        // Only allow joining with this neighbor if its internal size is less than our limit
        nn => {
          val ccs = community_size(n2c(nn))
          ccs < cs.maximumSize
        }
      case _ =>
        nn => true
    }

    if (allowed(node)) {
      val neighbors = g.neighbors(node)
      for (i <- 0 until degree) {
        val (neighbor, neighbor_weight) = neighbors.next
        val neighbor_community = n2c(neighbor)
        if (allowed(neighbor)) {
          if (neighbor != node) {
            if (neigh_weight(neighbor_community) == -1.0) {
              neigh_weight(neighbor_community) = 0.0
              neigh_pos(neigh_last) = neighbor_community
              neigh_last += 1
            }
            neigh_weight(neighbor_community) += neighbor_weight
          }
        }
      }
    }
  }

  /**
    * Perform the clustering for one level. Clustering keeps going until the changes in modularity fall below the threshold.
    * @param randomize Randomize the order of processing (defaults to true).
    * @return True if some changes were made to the communities.
    */
  private[unithread] def oneLevel (randomize: Boolean = true): Boolean = {
    var improvement = false
    var nb_moves: Int = 0
    var nb_pass_done: Int = 0
    var new_mod = modularity
    var cur_mod = new_mod

    // Randomize the order of vertex inspection
    val random_order = new Array[Int](size)
    for (i <- 0 until size) random_order(i) = i
    val randomizer = new Random()
    if (randomize) {
      for (i <- 0 until (size - 1)) {
        val rand_pos = randomizer.nextInt(size - i) + i
        val tmp = random_order(i)
        random_order(i) = random_order(rand_pos)
        random_order(rand_pos) = tmp
      }
    }

    // repeat while
    //   there is an improvement of modularity
    //   or there is an improvement of modularity greater than a given minimumModularityGain
    //   or a predefined number of pass have been done
    do {
      cur_mod = new_mod
      nb_moves = 0
      nb_pass_done += 1

      // for each node: remove the node from its community and insert it in the best community
      for (node_tmp <- 0 until size) {
        val node = random_order(node_tmp)
        val node_comm = n2c(node)
        val w_degree = g.weightedDegree(node)

        // computation of all neighboring communities of current node
        neighComm(node)
        // remove node from its current community

        remove(node, node_comm, neigh_weight(node_comm))

        // compute the nearest community for node
        // default choice for future insertion is the former community
        var best_comm = node_comm
        var best_nblinks = 0.0
        var best_increase = 0.0
        for (i <- 0 until neigh_last) {
          val increase = modularityGain(neigh_pos(i), neigh_weight(neigh_pos(i)), w_degree)
          if (increase > best_increase) {
            best_comm = neigh_pos(i)
            best_nblinks = neigh_weight(neigh_pos(i))
            best_increase = increase
          }
        }

        // insert node in the nearest community
        insert(node, best_comm, best_nblinks)

        if (best_comm != node_comm) {
          nb_moves += 1
        }
      }

      var total_tot = 0.0
      var total_in = 0.0
      for (i <- tot.indices) {
        total_tot += tot(i)
        total_in += in(i)
      }

      new_mod = modularity
      if (nb_moves > 0) improvement = true
    } while (nb_moves > 0 && new_mod - cur_mod > min_modularity)

    val (renumber, _) = getRenumbering

    // Compute communities
    var comm_nodes_tmp = MutableBuffer[MutableBuffer[Int]]()
    for (node <- 0 until size) {
      val n = renumber(n2c(node))
      while (comm_nodes_tmp.size < n + 1) comm_nodes_tmp += MutableBuffer[Int]()
      comm_nodes_tmp(n) += node
    }
    comm_nodes = comm_nodes_tmp

    val comm_deg = comm_nodes.size
    for (comm <- 0 until comm_deg) {

      //Get the community representative node. Every node in the community has the same one.
      //Select the node with the highest degree as representative of the community.
      val (commNodeInfo, maxWeight, commNodeNum) = comm_nodes(comm).map(node => (g.nodeInfo(node), g.weightedDegree(node), node)).reduce{(a, b) =>
        if (a._2 > b._2) (a._1 + b._1, a._2, a._3) else (b._1 + a._1, b._2, b._3)
      }

      //Update all nodes in the community to store the new community id.
      for (node <- comm_nodes(comm)){
        g.nodeInfo(node).communityNode = Some(commNodeInfo)
      }
    }


    improvement
  }

  private def getRenumbering: (Array[Int], Int) = {
    val renumber = new Array[Int](size)
    for (i <- 0 until size) renumber(i) = -1

    for (node <- 0 until size)
      renumber(n2c(node)) += 1

    var last = 0
    for (i <- 0 until size) {
      if (renumber(i) != -1) {
        renumber(i) = last
        last += 1
      }
    }

    (renumber, last)
  }

  /**
    * Initialize a community with data read from a partition file.
    * @param filename Partition file to be read.
    */
  def initPartition (filename: String): Unit = {
    val finput = new BufferedReader(new InputStreamReader(new FileInputStream(filename)))

    var line = finput.readLine()
    while (null != line) { //scalastyle:ignore
      val fields = line.split("[ \t] + ")
      val node = fields(0).toInt
      val comm = fields(1).toInt

      val old_comm = n2c(node)
      neighComm(node)
      remove(node, old_comm, neigh_weight(old_comm))

      var i = 0
      var done = false
      while (i < neigh_last && !done) {
        val best_comm = neigh_pos(i)
        val best_nblinks = neigh_weight(best_comm)
        if (best_comm == comm) {
          insert(node, best_comm, best_nblinks)
          done = true
        } else {
          i = i + 1
        }
      }

      if (!done) {
        insert(node, comm, 0)
      }

      line = finput.readLine()
    }
    finput.close()
  }

  /**
    * Output the clustered graph content.
    * @param level Level being output, only used in stats output.
    * @param out Output stream for graph content.
    * @param stats Output stream for stats content.
    */
  def displayPartition (level: Int, out: PrintStream, stats: Option[PrintStream]) : Unit = {
    val (renumber, last) = getRenumbering

    // write nodes to output file
    for (i <- 0 until size) {
      def escapeString (input: String): String =
        input
          .replaceAllLiterally("\\", "\\\\")
          .replaceAllLiterally("\t", "\\t")
          .replaceAllLiterally("\n", "\\n")
          .replaceAllLiterally("\"", "\\\"")

      val id = g.id(i)
      val newCommunityId = g.nodeInfo(i).communityNode.get.id
      val size = g.internalSize(i)
      val weight = g.weightedDegree(i).round.toInt
      val metadata = g.metaData(i)
      val analyticData = g.nodeInfo(i).finishedAnalyticValues
      val analytics =
        if (analyticData.length > 0) analyticData.map(escapeString).mkString("\t", "\t", "") else ""
      out.println("node\t" + id + "\t" + newCommunityId + "\t" + size + "\t" + weight + "\t" + metadata + analytics)
    }
    // write links to output file
    g.displayLinks(out)

    // write stats to stats file
    stats.foreach{statsStream =>

      var nodes = 0.0
      var minCS = Int.MaxValue
      var maxCS = Int.MinValue
      var allL0 = 0.0
      var allL1 = 0.0
      var allL2 = 0.0
      var bigL0 = 0.0
      var bigL1 = 0.0
      var bigL2 = 0.0

      val N = 2153454.0
      val L = 6.0
      val C = 165000.0
      val idealCSize = math.pow(N / C, 1 / L)
      var diffFromIdeal = 0.0

      community_size.foreach{n =>
        nodes += 1.0
        if (n > 0) {
          val d = n.toDouble
          allL0 += 1
          allL1 += d
          allL2 += d * d
          minCS = minCS min n
          maxCS = maxCS max n

          val communityDiffFromIdeal = n - idealCSize
          diffFromIdeal += communityDiffFromIdeal * communityDiffFromIdeal
        }
        if (n > 1) {
          val d = n.toDouble
          bigL0 += 1
          bigL1 += d
          bigL2 += d*d
        }
      }
      val meanAll = allL1 / allL0
      val stdDevAll = allL2 / allL0 - meanAll * meanAll

      val meanBig = bigL1 / bigL0
      val stdDevBig = bigL2 / bigL0 - meanBig * meanBig

      statsStream.println()
      statsStream.println("Level " + level + " stats:")
      statsStream.println("\tTotal nodes: " + nodes)
      statsStream.println("\tMinimum(community size): " + minCS)
      statsStream.println("\tMaximum(community size): " + maxCS)
      statsStream.println("\tAll communities:")
      statsStream.println("\t\tN: " + allL0)
      statsStream.println("\t\tSum(community size): " + allL1)
      statsStream.println("\t\tSum(community size ^ 2): " + allL2)
      statsStream.println("\t\tMean(community size): " + meanAll)
      statsStream.println("\t\tSigma(community size): " + stdDevAll)
      statsStream.println("\tCommunities larger than 1 node:")
      statsStream.println("\t\tN: " + bigL0)
      statsStream.println("\t\tSum(community size): " + bigL1)
      statsStream.println("\t\tSum(community size ^ 2): " + bigL2)
      statsStream.println("\t\tMean(community size): " + meanBig)
      statsStream.println("\t\tsigma(communities size): " + stdDevBig)
      statsStream.println()
      statsStream.println()
      statsStream.println()
      statsStream.println("Ideal calculations (N=" + N + ", C=" + C + ", L=" + L + ")")
      statsStream.println("Unweighted diff from ideal: " + diffFromIdeal)
      statsStream.println("Weighted diff from ideal: " + (diffFromIdeal * math.pow(C / N, level / L)))
    }
  }

  /**
    * Create a new graph based on the current communities.
    * @return The new graph.
    */
  def partition2GraphBinary(): Graph = {
    val (renumber, _) = getRenumbering

    // Compute weighted graph
    val nb_nodes = comm_nodes.size
    val degrees = new Array[Int](nb_nodes)
    val links = MutableBuffer[Int]()
    val nodeInfos = new Array[NodeInfo](nb_nodes)
    val weights = MutableBuffer[Float]()

    val comm_deg = comm_nodes.size
    for (comm <- 0 until comm_deg) {
      val m = MutableMap[Int, Float]()

      val comm_size = comm_nodes(comm).size
      for (node <- 0 until comm_size) {
        val neighbors = g.neighbors(comm_nodes(comm)(node))
        val deg = g.nbNeighbors(comm_nodes(comm)(node))
        for (i <- 0 until deg) {
          val (neigh, neigh_weight) = neighbors.next
          val neigh_comm = renumber(n2c(neigh))
          m(neigh_comm) = m.getOrElse(neigh_comm, 0.0f) + neigh_weight
        }
      }
      degrees(comm) = if (0 == comm) m.size else degrees(comm-1) + m.size
      m.map{case (neighbor, weight) =>
          links += neighbor
          weights += weight
      }

      //Every node in the community has a reference to the same head community node.
      nodeInfos(comm) = g.nodeInfo(comm_nodes(comm)(0)).communityNode.get
    }

    new Graph(degrees, links.toArray, nodeInfos, Some(weights.toArray))
  }
}


/**
  * Object to run the community clustering algorithm.
  */
object Community extends ConfigReader {
  var nb_pass = 0
  var algorithm: AlgorithmModification = new BaselineAlgorithm

  /**
    * Parse CLI parameters into a new configuration.
    * @param config Base configuration to use.
    * @param argParser Argument parser to use to parse CLI parameters.
    * @return The configuration containing the base values & the parsed CLI parameters.
    */
  def parseArguments(config: Config, argParser: ArgumentParser): Config = {
    val loader = new ConfigLoader(config)
    loader.putValue(argParser.getStringOption("o", "Output directory", Some(".")),
      s"${CommunityConfigParser.SectionKey}.${CommunityConfigParser.Output}")
    loader.putValue(argParser.getStringOption("i", "File containing the graph to decompose in communities", None),
      s"${CommunityConfigParser.SectionKey}.${CommunityConfigParser.InputFilename}")
    loader.putValue(argParser.getStringOption("w", "Read the graph as a weighted one (weights are set to 1 otherwise).", None),
      s"${CommunityConfigParser.SectionKey}.${CommunityConfigParser.WeightFilename}")
    loader.putValue(argParser.getStringOption("m", "Read metadata for nodes (set to none otherwise).", None),
      s"${CommunityConfigParser.SectionKey}.${CommunityConfigParser.MetadataFilename}")
    loader.putValue(argParser.getStringOption("p", "Start the computation with a given partition instead of the trivial partition.", None),
      s"${CommunityConfigParser.SectionKey}.${CommunityConfigParser.PartitionFilename}")
    loader.putDoubleValue(argParser.getDoubleOption("q", "A given pass stops when the modularity is increased by less than minimumModularityGain.", Some(0.000001)),
      s"${CommunityConfigParser.SectionKey}.${CommunityConfigParser.MinimumModularityGain}")
    loader.putIntValue(argParser.getIntOption("l", "Displays the graph of level k rather than the hierachical structure. " +
      " if k=-1 then displays the hierarchical structure rather than the graph at a given level.", Some(-2)),
      s"${CommunityConfigParser.SectionKey}.${CommunityConfigParser.LevelDisplay}")
    loader.putBooleanValue(argParser.getBooleanOption("n", "Don't randomize the node order when converting, for repeatability in testing.", Some(false)),
      s"${CommunityConfigParser.SectionKey}.${CommunityConfigParser.KeepOrder}")
    loader.putValue(argParser.getStringOption("a",
      "The fully qualified name of a class describing a custom analytic to run on the node data.  Multiple instances allowed, and performed in order.", None),
      s"${CommunityConfigParser.SectionKey}.${CommunityConfigParser.Analytics}")
    loader.putIntValue(argParser.getIntOption("nd",
      "Use the node degree algorithm modification with the specified base.  This will only join nodes with other nodes of at most degree <base> on the " +
        "first clustering level, base^2 on the second, base^3 on the third, etc.  Exclusive with -cs.", None),
      s"${CommunityConfigParser.SectionKey}.${CommunityConfigParser.NodeDegree}")
    loader.putIntValue(argParser.getIntOption("cs",
      "Use the cluster size algorithm modification with the specified size.  Nodes will only be merged into a community if that community is smaller than " +
        "the specified size. Exclusive with -nd.", None),
      s"${CommunityConfigParser.SectionKey}.${CommunityConfigParser.CommunitySize}")

    loader.config
  }

  def main (args: Array[String]): Unit = {
    val argParser = new ArgumentParser(args)

    // Parse config files first.
    val configFile = argParser.getStringOption("config", "File containing configuration information.", None)
    val configComplete = readConfigArguments(configFile, c => parseArguments(c, argParser))
    val clusterConfig = CommunityConfigParser.parse(configComplete) match {
      case Success(s) => s
      case Failure(f) =>
        println(s"Failed to load cluster configuration properly. ${f}")
        f.printStackTrace()
        argParser.usage
        sys.exit(-1)
    }

    def algorithmByLevel (level: Int) = {
      clusterConfig.algorithm match {
        case und: UnlinkedNodeDegreeAlgorithm =>
          if ((level + 1) > und.degreeLimits.length) {
            new BaselineAlgorithm
          }
          else {
            val maxDegree = und.degreeLimits.take(level + 1).product
            new NodeDegreeAlgorithm(maxDegree)
          }
        case nd: NodeDegreeAlgorithm => new NodeDegreeAlgorithm(math.pow(nd.degreeLimit, level + 1).toLong)
        case cs: CommunitySizeAlgorithm => algorithm
        case _ => algorithm
      }
    }

    val curDir: Option[File] = if (-1 == clusterConfig.levelDisplay) Some(new File(clusterConfig.output)) else None

    // Perform our clustering
    new CommunityClusterer(
      {
        val c = new Community(Graph(clusterConfig.inputFilename, clusterConfig.weightFilename,
          clusterConfig.metadataFilename, clusterConfig.analytics), -1, clusterConfig.minimumModularityGain, algorithmByLevel(0))
        clusterConfig.partitionFilename.foreach(part => c.initPartition(part))
        c
      },
      clusterConfig.randomize,
      clusterConfig.partitionFilename.isDefined,
      clusterConfig.minimumModularityGain,
      algorithmByLevel
    ).doClustering[Int](
      withLevel(clusterConfig.levelDisplay, curDir)
    )
  }

  // Write out input and output communities as needed
  private def withLevel (display_level: Int, curDir: Option[File])
                        (level: Int,
                         initialGraph: Option[Graph],
                         initialModularity: Double,
                         community: Community): Int = {
    initialGraph.foreach(g =>
      if (level == display_level) {
        g.displayNodes(Console.out)
        g.displayLinks(Console.out)
      }
    )

    curDir.foreach { pwd =>
      val levelDir = new File(pwd, "level_" + (level-1))
      levelDir.mkdir()
      val out = new PrintStream(new FileOutputStream(new File(levelDir, "part_00000")))
      val stats = new PrintStream(new FileOutputStream(new File(levelDir, "stats")))
      community.displayPartition(level, out, Some(stats))
      out.flush()
      out.close()
      stats.flush()
      stats.close()
    }
    level
  }
}

/**
  * We separate the actual clustering procedure out into its own class for the sole reason that this allows the
  * initial input community to be garbage-collected during processing - having it as a member of the Community class
  * would of course keep a pointer to the community, and we can't make functions with var arguments in the Community
  * object.
  *
  * Needless to say, this class is one-use-only.
  *
  * @param c A community built off of the graph on which to perform clustering
  * @param randomize Whether or not the order in which nodes are considered when checking for clusters should be
  *                  randomized
  * @param forceSecondIteration Whether or not the clustering should necessarily run for at least two iterations - even
  *                             if the first iteration did nothing useful
  * @param min_modularity The threshold determining whether another level will be generated.  A new level is computed
  *                       if the last one has generated an increase greater than min_modularity.  If 0, even a minor
  *                       increase is enough to go for one more pass
  * @param algorithmByLevel A function that returns what modifications should be made to the clustering algorithm at
  *                         a given level
  */
class CommunityClusterer (var c: Community,
                          randomize: Boolean,
                          forceSecondIteration: Boolean,
                          min_modularity: Double,
                          algorithmByLevel: Int => AlgorithmModification)  extends Logging {
  private var used = false

  /**
    * Actually perform our clustering task, generating a hierarchy of communities that represent a sensible, geometry-
    * based clustering of the initial graph
    *
    * @param withLevel A function to run on each level of the community hierarchy, to pull out what information is
    *                  needed.  Parameters passed to this function are:
    *                  <ol>
    *                    <li>level - the level of clustering the current call describes</li>
    *                    <li>initialGraph - the graph passed into this level of clustering - i.e., the start point
    *                      from which the current level of clustering was performed.</li>
    *                    <li>initialModularity - the modularity before this level of clustering</li>
    *                    <li>community - the results of the current level of clustering.  This contains the modularity
    *                      achieved as a result of this round of clustering</li>
    *                  </ol>
    * @tparam T The type of information retained and returned about the community hierarchy generated
    */
  def doClustering[T] (withLevel: (Int, Option[Graph], Double, Community) => T): Seq[T] = {
    if (used) {
      throw new IllegalStateException("Community clusterer may not be called more than once")
    }
    used = true

    val time_begin = System.currentTimeMillis()
    info("Begining hierarchical clustering")

    // Track our results
    val results = MutableBuffer[T]()

    var g: Option[Graph] = None
    var improvement: Boolean = true
    var mod: Double = c.modularity
    var level: Int = 0

    do {
      info(s"Starting computation on level $level.  Network size: ${c.g.nb_nodes} nodes, ${c.g.nb_links} links, ${c.g.total_weight} weight.")

      improvement = c.oneLevel(randomize)
      val new_mod = c.modularity

      level = level + 1
      results += withLevel(level, g, mod, c)

      g = Some(c.partition2GraphBinary())

      val levelAlgorithm = algorithmByLevel(level)
      c = new Community(g.get, -1, min_modularity, levelAlgorithm)

      info(s"  modularity increased from ${mod} to ${new_mod}")

      mod = new_mod
      info(s"  Finished computation on level ${level - 1}")

      // do at least one more computation if partition is provided
      if (forceSecondIteration && 1 == level) improvement = true
    } while (improvement)


    val time_end = System.currentTimeMillis()
    info("Finished hierarchical clustering.  Total duration: %.3f sec.".format((time_end - time_begin) / 1000.0))
    info(s"Final modularity: ${mod}")

    results
  }
}
//scalastyle:on file.size.limit method.length cyclomatic.complexity multiple.string.literals regex
