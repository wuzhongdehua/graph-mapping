/**
  * Copyright (c) 2014-2016 Uncharted Software Inc. All rights reserved.
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

package software.uncharted.graphing.export

class ClusteredEdge (val srcId: String,
                     val srcX: String,
                     val srcY: String,
                     val dstId: String,
                     val dstX: String,
                     val dstY: String,
                     val attr: String,
                     val interCommunityEdge: String,
                     val level: Int) extends Serializable {

  override def toString() : String = {
    return "edge\t" + levelId(srcId, level-1) + "\t" + srcX + "\t" + srcY + "\t" + levelId(dstId, level-1) + "\t" + dstX + "\t" + dstY + "\t" + attr + "\t" + interCommunityEdge + "\t" + level
  }

  def levelId(id : String, level : Int) : String = {
    // Edges are for level - 1.
    return if(level >= 0) id + "_c_" + level else id
  }
}
