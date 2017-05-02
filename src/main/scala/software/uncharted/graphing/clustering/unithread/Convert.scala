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



import java.io._ //scalastyle:ignore

import com.typesafe.config.{Config, ConfigFactory}

import scala.io.Source
import scala.util.{Failure, Success}
import scala.collection.mutable.{Buffer => MutableBuffer}
import software.uncharted.graphing.analytics.CustomGraphAnalytic
import software.uncharted.graphing.utilities.{ArgumentParser, ConfigLoader, ConfigReader}

/**
  * Code is an adaptation of https://sites.google.com/site/findcommunities, with the original done by
  * (c) 2008 V. Blondel, J.-L. Guillaume, R. Lambiotte, E. Lefebvre.
  */

//scalastyle:off multiple.string.literals
object Convert extends ConfigReader {

  /**
    * Parse CLI parameters into a new configuration.
    * @param config Base configuration to use.
    * @param argParser Argument parser to use to parse CLI parameters.
    * @return The configuration containing the base values & the parsed CLI parameters.
    */
  def parseArguments(config: Config, argParser: ArgumentParser): Config = {
    val loader = new ConfigLoader(config)
    loader.putValue(argParser.getStringOption("ie", "Edge input file", None), s"${ConvertConfigParser.SECTION_KEY}.${ConvertConfigParser.EDGE_INPUT}")
    loader.putValue(argParser.getStringOption("fe", "Edge filter", None), s"${ConvertConfigParser.SECTION_KEY}.${ConvertConfigParser.EDGE_FILTER}")
    loader.putValue(argParser.getStringOption("ce", "Edge separator", Some("[ \t]+")), s"${ConvertConfigParser.SECTION_KEY}.${ConvertConfigParser.EDGE_SEPARATOR}")
    loader.putValue(argParser.getStringOption("ae", "Edge analytics", None), s"${ConvertConfigParser.SECTION_KEY}.${ConvertConfigParser.EDGE_ANALYTIC}")
    loader.putIntValue(argParser.getIntOption("s", "Edge source column", None), s"${ConvertConfigParser.SECTION_KEY}.${ConvertConfigParser.SRC_NODE_COLUMN}")
    loader.putIntValue(argParser.getIntOption("d", "Edge destination column", None), s"${ConvertConfigParser.SECTION_KEY}.${ConvertConfigParser.DST_NODE_COLUMN}")
    loader.putIntValue(argParser.getIntOption("w", "Edge weight column", None), s"${ConvertConfigParser.SECTION_KEY}.${ConvertConfigParser.WEIGHT_COLUMN}")
    loader.putValue(argParser.getStringOption("in", "Node input file", None), s"${ConvertConfigParser.SECTION_KEY}.${ConvertConfigParser.NODE_INPUT}")
    loader.putValue(argParser.getStringOption("fn", "Node filter", None), s"${ConvertConfigParser.SECTION_KEY}.${ConvertConfigParser.NODE_FILTER}")
    loader.putValue(argParser.getStringOption("cn", "Node separator", Some("[ \t]+")), s"${ConvertConfigParser.SECTION_KEY}.${ConvertConfigParser.NODE_SEPARATOR}")
    loader.putValue(argParser.getStringOption("an", "Node analytics", None), s"${ConvertConfigParser.SECTION_KEY}.${ConvertConfigParser.NODE_ANALYTIC}")
    loader.putValue(argParser.getStringOption("anc", "Node analytics parameter", None), s"${ConvertConfigParser.SECTION_KEY}.${ConvertConfigParser.NODE_ANALYTIC}")
    loader.putIntValue(argParser.getIntOption("n", "Node id column", None), s"${ConvertConfigParser.SECTION_KEY}.${ConvertConfigParser.NODE_COLUMN}")
    loader.putIntValue(argParser.getIntOption("m", "Node metadata column", None), s"${ConvertConfigParser.SECTION_KEY}.${ConvertConfigParser.META_COLUMN}")
    loader.putValue(argParser.getStringOption("oe", "Edge output file", None), s"${ConvertConfigParser.SECTION_KEY}.${ConvertConfigParser.EDGE_OUTPUT}")
    loader.putValue(argParser.getStringOption("ow", "Weight output file", None), s"${ConvertConfigParser.SECTION_KEY}.${ConvertConfigParser.WEIGHT_OUTPUT}")
    loader.putValue(argParser.getStringOption("om", "Metadata output file", None), s"${ConvertConfigParser.SECTION_KEY}.${ConvertConfigParser.META_OUTPUT}")
    loader.putBooleanValue(argParser.getBooleanOption("r", "Renumber nodes to be zero based", None), s"${ConvertConfigParser.SECTION_KEY}.${ConvertConfigParser.RENUMBER}")

    loader.config
  }

  def main(args: Array[String]): Unit = {
    val argParser = new ArgumentParser(args)

    // Parse config files first.
    val configFile = argParser.getStringOption("config", "File containing configuration information.", None)
    val configComplete = readConfigArguments(configFile, c => parseArguments(c, argParser))
    val convertConfig = ConvertConfigParser.parse(configComplete) match {
      case Success(s) => s
      case Failure(f) =>
        println(s"Failed to load convert configuration properly. ${f}")
        sys.exit(-1)
    }

    // Use the config to pass all the necessary parameters.
    var g = GraphEdges(convertConfig.edgeInputFilename,
      convertConfig.edgeLineFilter,
      convertConfig.edgeSeparator,
      convertConfig.srcNodeColumn,
      convertConfig.dstNodeColumn,
      convertConfig.weightColumn,
      convertConfig.edgeAnalytics)


    convertConfig.nodeInputFilename.foreach { nodeFile =>
      g.readMetadata(nodeFile, convertConfig.nodeLineFilter, convertConfig.nodeSeparator, convertConfig.nodeColumn,
        convertConfig.metaColumn, convertConfig.nodeAnalytics)
    }

    if (convertConfig.renumber) {
      g = g.renumber()
    }

    val edgeStream = new DataOutputStream(new FileOutputStream(convertConfig.edgeOutputFilename))
    val weightStream = convertConfig.weightOutputFilename.map(filename => new DataOutputStream(new FileOutputStream(filename)))
    val metadataStream = convertConfig.metaOutputFilename.map(filename => new DataOutputStream(new FileOutputStream(filename)))
    g.displayBinary(edgeStream, weightStream, metadataStream)
    edgeStream.flush(); edgeStream.close()
    weightStream.foreach{s => s.flush(); s.close()}
    metadataStream.foreach{s => s.flush(); s.close()}
  }
}
//scalastyle:on multiple.string.literals
