package software.uncharted.graphing.salt



import scala.collection.mutable.{Buffer => MutableBuffer}

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Column, DataFrame, Row}

import software.uncharted.salt.core.analytic.Aggregator
import software.uncharted.salt.core.generation.output.SeriesData
import software.uncharted.salt.core.generation.request.TileRequest
import software.uncharted.salt.core.projection.numeric.{NumericProjection}
import software.uncharted.salt.core.spreading.SpreadingFunction
import software.uncharted.sparkpipe.Pipe
import software.uncharted.sparkpipe.ops.core.dataframe._



object CartesianSegmentOp {
  def apply[T, U, V, W, X]
  (projection:
   NumericProjection[
     (Double, Double, Double, Double),
     (Int, Int, Int, Int, Int),
     (Int, Int, Int, Int)],
   x1Col: String,
   y1Col: String,
   x2Col: String,
   y2Col: String,
   valueColumns: Option[Seq[String]],
   xyBounds: (Double, Double, Double, Double),
   zBounds: (Int, Int),
   valueExtractor: Row => Option[T],
   binAggregator: Aggregator[T, U, V],
   tileAggregator: Option[Aggregator[V, W, X]],
   tileSize: Int
  )(request: TileRequest[(Int, Int, Int)])(input: DataFrame): RDD[SeriesData[(Int, Int, Int), V, X]] = {
    // We need both coordinate and value columns
    val coordCols = Seq(x1Col, y2Col, x2Col, y2Col)
    val selectCols = valueColumns.map(coordCols union _).getOrElse(coordCols).distinct.map(new Column(_))

    val data = Pipe(input)
      .to(castColumns(Map(x1Col -> "double", y1Col -> "double", x2Col -> "double", y2Col -> "double")))
      .to(_.select(selectCols:_*))
      .run

    val coordinateExtractor = (r: Row) =>
      if (!r.isNullAt(0) && !r.isNullAt(1) && !r.isNullAt(2) && !r.isNullAt(3)) {
        Some((r.getDouble(0), r.getDouble(1), r.getDouble(2), r.getDouble(3)))
      } else {
        None
      }

//    val series = new Series((
//      // Maximum bin indices
//      (tileSize - 1, tileSize - 1),
//      coordinateExtractor,
//      projection,
//      valueExtractor,
//      binAggregator,
//      tileAggregator,
//      None)

//    val foo: CartesianProjection
    null
  }
}

trait SegmentProjection {
  /**
    * Change from a (tile, bin) coordinate to a (universal bin) coordinate
    *
    * Generally, the upper left corner is taken as (0, 0).  If TMS is specified, then the tile Y coordinate is
    * flipped (i.e., lower left is (0, 0)), but the bin coordinates (both tile-bin and universal-bin) are not.
    *
    * @param tile the tile coordinate
    * @param bin the bin coordinate
    * @param maxBin the maximum bin index within each tile
    * @param tms if true, the Y axis for tile coordinates only is flipped
    * @return The universal bin coordinate of the target cell, with (0, 0) being the upper left corner of the whole
    *         space
    */
  protected def tileBinIndexToUniversalBinIndex(tile: (Int, Int, Int),
                                                bin: (Int, Int),
                                                maxBin: (Int, Int),
                                                tms: Boolean): (Int, Int) = {
    val pow2 = 1 << tile._1

    val tileLeft = tile._2 * (maxBin._1+1)

    val tileTop = tms match {
      case true => (pow2 - tile._3 - 1)*(maxBin._2+1)
      case false => tile._3*(maxBin._2+1)
    }

    (tileLeft + bin._1, tileTop + bin._2)
  }

  /**
    * Change from a (universal bin) coordinate to a (tile, bin) coordinate.
    *
    * Generally, the upper left corner is taken as (0, 0).  If TMS is specified, then the tile Y coordinate is
    * flipped (i.e., lower left is (0, 0)), but the bin coordinates (both tile-bin and universal-bin) are not.
    *
    * @param z The zoom level of the point in question
    * @param universalBin The universal bin coordinate of the input point
    * @param maxBin the maximum bin index within each tile
    * @param tms if true, the Y axis for tile coordinates only is flipped
    * @return The tile and bin at the given level of the given universal bin
    */
  protected def universalBinIndexToTileIndex(z: Int,
                                             universalBin: (Int, Int),
                                             maxBin: (Int, Int),
                                             tms: Boolean) = {
    val pow2 = 1 << z

    val xBins = (maxBin._1+1)
    val yBins = (maxBin._2+1)

    val tileX = universalBin._1/xBins
    val binX = universalBin._1 - tileX * xBins;

    val tileY = tms match {
      case true => pow2 - (universalBin._2/yBins) - 1;
      case false => universalBin._2/yBins
    }

    val binY = tms match {
      case true => universalBin._2 - ((pow2 - tileY - 1) * yBins)
      case false => universalBin._2 - (tileY) * yBins
    }

    ((z, tileX, tileY), (binX, binY))
  }
}

trait StraightSegmentCalculation {
  /**
    * Calc length of a line from two endpoints
    */
  def calcLen(start: (Int, Int), end: (Int, Int)): Int = {
    //calc integer length between to bin indices
    var (x0, y0, x1, y1) = (start._1, start._2, end._1, end._2)
    val dx = x1-x0
    val dy = y1-y0
    Math.sqrt(dx*dx + dy*dy).toInt
  }

  private def getPoints (start: (Int, Int), end: (Int, Int)): (Boolean, Int, Int, Int, Int) = {
    val xs = start._1
    val xe = end._1
    val ys = start._2
    val ye = end._2
    val steep = (math.abs(ye - ys) > math.abs(xe - xs))

    if (steep) {
      if (ys > ye) {
        (steep, ye, xe, ys, xs)
      } else {
        (steep, ys, xs, ye, xe)
      }
    } else {
      if (xs > xe) {
        (steep, xe, ye, xs, ys)
      } else {
        (steep, xs, ys, xe, ye)
      }
    }
  }

  def endpointsToBins (start: (Int, Int), end: (Int, Int), leaderLength: Option[Int], minLength: Option[Int], maxLength: Option[Int]): Seq[(Int, Int)] = {
    val len = calcLen(start, end)
    var leaderInfo = leaderLength.map(n => (n, n*n, false))

    if (maxLength.map(len > _).getOrElse(false)) {
      Seq()
    } else if (minLength.map(len < _).getOrElse(false)) {
      Seq()
    } else {
      val (steep, x0, y0, x1, y1) = getPoints(start, end)
      var x0_mid = 0
      var x1_mid = 0
      var x0_slope = 0.0
      var x1_slope = 0.0

      val deltax = x1 - x0
      val deltay = math.abs(y1 - y0)
      var error = deltax >> 1
      var y = y0
      val ystep = if (y0 < y1) 1 else -1
      var x = x0
      var pastLeader = leaderLength.map(len => false)

      val points = MutableBuffer[(Int, Int)]()
      while (x <= x1) {
        var curY = y
        error = error - deltay
        if (error < 0) {
          y = y + ystep
          error = error + deltax
        }

        val storeValue = leaderInfo.map { case (leader, leaderSquared, pastLeader) =>
          if (pastLeader) {
            val distanceToEnd = (x - x1) * (x - x1) + (curY - y1) * (curY - y1)
            distanceToEnd <= leaderSquared
          } else {
            val distanceToStart = (x - x0) * (x - x0) + (curY - y0) * (curY - y0)
            if (distanceToStart > leaderSquared) {
              leaderInfo = Some((leader, leaderSquared, true))
              val distanceToEnd = (x - x1) * (x - x1) + (curY - y1) * (curY - y1)

              // Jump to within sight of end
              // Figure out roughly how many x to jump.  Jump a little (1) less than ideal to make sure to catch
              // the boundary
              val jumpLength = ((x1 - x0) * (len - math.sqrt(distanceToStart) - leader) / len.toDouble - 1).ceil.toInt
              if (jumpLength > 1) {
                val bigError = error - deltay.toLong * jumpLength
                val xSteps = ((-bigError.toDouble) / deltax).ceil.toInt

                error = (bigError + deltax.toLong * xSteps).toInt
                curY = curY + ystep * xSteps
                y = y + ystep * xSteps
                x = x + jumpLength
              }
              false
            } else {
              true
            }
          }
        }.getOrElse(true)

        if (storeValue) {
          if (steep) points += ((curY, x))
          else points += ((x, curY))
        }

        x = x + 1
      }

      points
    }
  }
}

/**
  * A cartesian projection of lines
  *
  * Input coordinates are the two endpoints of the line in the form (x1, y1, x2, y2)
  *
  * Tile coordinates are as normal
  *
  * Bin coordinates are the endpoints of the line, in <em>universal</em> bin coordinates
  *
  * @param zoomLevels
  * @param min the minimum value of a data-space coordinate
  * @param max the maximum value of a data-space coordinate
  * @param leaderLineLength The number of bins on each side of a line to keep
  */
class CartesianLeaderLineProjection(zoomLevels: Seq[Int],
                                    min: (Double, Double),
                                    max: (Double, Double),
                                    leaderLineLength: Int
                                   )
  extends NumericProjection[(Double, Double, Double, Double), (Int, Int, Int), (Int, Int, Int, Int)]((min._1, min._2, min._1, min._2), (max._1, max._2, max._1, max._2)) {

  /**
    * Project a data-space coordinate into the corresponding tile coordinate and bin coordinate
    *
    * @param coordinates     the data-space coordinate
    * @param maxBin The maximum possible bin index (i.e. if your tile is 256x256, this would be (255,255))
    * @return Option[Seq[(TC, Int)]] representing a series of tile coordinate/bin index pairs if the given source
    *         row is within the bounds of the viz. None otherwise.
    */
  override def project(coordinates: Option[(Double, Double, Double, Double)],
                       maxBin: (Int, Int, Int, Int)): Option[Seq[((Int, Int, Int), (Int, Int, Int, Int))]] = {

    None
  }

  /**
    * Project a bin index BC into 1 dimension for easy storage of bin values in an array
    *
    * @param bin    A bin index
    * @param maxBin The maximum possible bin index (i.e. if your tile is 256x256, this would be (255,255))
    * @return the bin index converted into its one-dimensional representation
    */
  override def binTo1D(bin: (Int, Int, Int, Int), maxBin: (Int, Int, Int, Int)): Int = {
    bin._1 + bin._2*(maxBin._1 + 1)
  }

}

class TrailerHeaderLineSpreadingFunction[T] extends SpreadingFunction[(Int, Int, Int), (Int, Int, Int, Int), T] {
  /**
    * Spread a single value over multiple visualization-space coordinates
    *
    * @param coords the visualization-space coordinates
    * @param value  the value to spread
    * @return Seq[(TC, BC, Option[T])] A sequence of tile coordinates, with the spread values
    */
  override def spread(coords: Seq[((Int, Int, Int), (Int, Int, Int, Int))], value: Option[T]): Seq[((Int, Int, Int), (Int, Int, Int, Int), Option[T])] = {
    null
  }
}