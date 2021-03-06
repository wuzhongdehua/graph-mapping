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
package software.uncharted.graphing.layout



/**
  *  Quadtree Decomposition Algorithm
  *
  *  Adapted from the 'FFDLayouter' code in ApertureJS
  *
  */
class QuadTree(box: (Double, Double, Double, Double)) { // bounding box of entire quadtree (x,y of lower left corner, width, height)

  private val _root: QuadNode = new QuadNode(box)

  def getRoot: QuadNode = {
    _root
  }

  /**
    * Insert quad node into the tree.
    * @param x X position of the node
    * @param y Y position of the node
    * @param id Id of the node
    * @param size Size of the node
    */
  def insert(x: Double, y: Double, id: Long, size: Double): Unit = {
    insertIntoQuadNode(_root, new QuadNodeData(x, y, id, size))
  }

  //scalastyle:off method.length
  /**
    * Insert quad node into the tree
    * @param qn Base quad node to use for insertion
    * @param data Quad node data to insert
    */
  def insertIntoQuadNode(qn: QuadNode, data: QuadNodeData): Unit = {

    if (qn == null) { //scalastyle:ignore
      return //scalastyle:ignore
    }

    qn.incrementChildren()

    // case 1: leaf node, no data
    //   just add the values and get out
    if (qn.getNumChildren == 1) {
      qn.setData(data)
      qn.setCenterOfMass((data.getX, data.getY))
      qn.setSize(data.getSize)
      return //scalastyle:ignore
    }

    // case 2: check that two nodes aren't located in exact same location
    // (to prevent quadtree with infinite depth)
    if ((qn.getData != null) && (qn.getNumChildren == 2)) { //scalastyle:ignore
      val x1 = qn.getData.getX
      val y1 = qn.getData.getY
      if ((x1 == data.getX) && (y1 == data.getY)) {
        qn.setNumChildren(1) // force two nodes at same location to be considered as one node
        return //scalastyle:ignore
      }
    }

    // move the center of mass by scaling old value by (n-1)/n and adding the 1/n new contribution
    val scale = 1.0/qn.getNumChildren
    val newX = qn.getCenterOfMass._1 + scale*(data.getX - qn.getCenterOfMass._1)
    val newY = qn.getCenterOfMass._2 + scale*(data.getY - qn.getCenterOfMass._2)
    qn.setCenterOfMass((newX, newY))
    // and use same technique for updating the average size (ie node radius) of the current quad node
    val newSize = qn.getSize + scale*(data.getSize - qn.getSize)
    qn.setSize(newSize)

    // case 3: current leaf needs to become internal, and four new child quad nodes need to be created
    if (qn.getData != null) { //scalastyle:ignore
      val rect = qn.getBounds
      val halfwidth = rect._3 / 2
      val halfheight = rect._4 / 2
      qn.setNW( new QuadNode(rect._1, rect._2 + halfheight, halfwidth, halfheight))
      qn.setNE( new QuadNode(rect._1 + halfwidth, rect._2 + halfheight, halfwidth, halfheight))
      qn.setSW( new QuadNode(rect._1, rect._2, halfwidth, halfheight))
      qn.setSE( new QuadNode(rect._1 + halfwidth, rect._2, halfwidth, halfheight))
      val oldNodeData = qn.getData
      qn.setData(null) //scalastyle:ignore
      insertIntoContainingChildQuandrant(qn, oldNodeData)
    }

    // case 4: internal node, has more than one child but no nodedata
    //    just push into the proper subquadrant (which already exists)
    insertIntoContainingChildQuandrant(qn, data)
  }
  //scalastyle:on method.length

  private def insertIntoContainingChildQuandrant(qn: QuadNode, qnData: QuadNodeData) = {
    var childRecursionQuad: QuadNode = null //scalastyle:ignore

    if (qn.getNW.isPointInBounds(qnData.getX, qnData.getY)) {
      childRecursionQuad = qn.getNW
    } else if (qn.getNE.isPointInBounds(qnData.getX, qnData.getY)) {
      childRecursionQuad = qn.getNE
    } else if (qn.getSW.isPointInBounds(qnData.getX, qnData.getY)) {
      childRecursionQuad = qn.getSW
    } else if (qn.getSE.isPointInBounds(qnData.getX, qnData.getY)) {
      childRecursionQuad = qn.getSE
    }
    insertIntoQuadNode(childRecursionQuad, qnData)
  }
}
