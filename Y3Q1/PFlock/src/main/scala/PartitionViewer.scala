import Misc.GeoGSON
import SPMF.AlgoFPMax
import scala.collection.JavaConverters._
import org.apache.spark.rdd.DoubleRDDFunctions
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions._
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.ScalaReflection
import org.apache.spark.sql.simba.SimbaSession
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.simba.index.RTreeType
import org.apache.spark.sql.simba.SimbaSession
import org.apache.spark.sql.simba.index._
import org.slf4j.{Logger, LoggerFactory}

/**
  * Created by and on 3/20/17.
  */

object PartitionViewer {
	private val logger: Logger = LoggerFactory.getLogger("myLogger")
	private val EPSG: String = "3068"
	
	case class SP_Point(id: Int, x: Double, y: Double)
    case class APoint(id: Int, x: Double, y: Double)
	case class ACenter(id: Long, x: Double, y: Double)
	case class BBox(minx: Double, miny: Double, maxx: Double, maxy: Double)

	def main(args: Array[String]): Unit = {
		val dataset = args(0)
		val entries = args(1)
		val partitions = args(2)
		val epsilon = args(3).toDouble
		val mu = args(4).toInt
		val master = args(5)
		val cores = args(6)
		val POINT_SCHEMA = ScalaReflection.schemaFor[SP_Point].dataType.asInstanceOf[StructType]
		val DELTA = 0.01
		// Setting session...
		logger.info("Setting session...")
        val simba = SimbaSession.builder().
			master(master).
			appName("Optimizer").
			config("simba.rtree.maxEntriesPerNode", entries).
			config("simba.index.partitions", partitions).
			config("spark.cores.max", cores).
			getOrCreate()
		import simba.implicits._
		import simba.simbaImplicits._
        // Reading...
		val phd_home = scala.util.Properties.envOrElse("PHD_HOME", "/home/acald013/PhD/")
		val path = "Y3Q1/Datasets/"
		val extension = "csv"
		val filename = "%s%s%s.%s".format(phd_home, path, dataset, extension)
		logger.info("Reading %s...".format(filename))
		val points = simba.read.option("header", "false").schema(POINT_SCHEMA).csv(filename).as[SP_Point]
		val n = points.count()
        // Indexing...
		logger.info("Point indexing...")
		val p1 = points.toDF("id1", "x1", "y1")
		p1.index(RTreeType, "p1RT", Array("x1", "y1"))
		val p2 = points.toDF("id2", "x2", "y2")
		p2.index(RTreeType, "p2RT", Array("x2", "y2"))
		// Self-join...
		logger.info("Self-join...")
		val pairsRDD = p1.distanceJoin(p2, Array("x1", "y1"), Array("x2", "y2"), epsilon).rdd
		// Computing disks...
		logger.info("Computing disks...")
		val centers = findDisks(pairsRDD, epsilon)
			.distinct()
			.toDS()
			.index(RTreeType, "centersRT", Array("x", "y"))
			.withColumn("id", monotonically_increasing_id())
			.as[ACenter]
		// Mapping disks and points...
		logger.info("Mapping disks and points...")
		val candidates = centers
			.distanceJoin(p1, Array("x", "y"), Array("x1", "y1"), (epsilon / 2) + DELTA)
			.groupBy("id", "x", "y")
			.agg(collect_list("id1").alias("IDs"))
		val ncandidates = candidates.count()
		// Filtering less-than-mu disks...
		logger.info("Filtering less-than-mu disks...")
		val filteredCandidates = candidates.
			filter(row => row.getList(3).size() >= mu).
			map(d => (d.getLong(0), d.getDouble(1), d.getDouble(2), d.getList[Integer](3).toString))
		val nFilteredCandidates = filteredCandidates.count()
		// Candidate indexing...
		logger.info("Candidate indexing...")
		filteredCandidates.index(RTreeType, "candidatesRT", Array("_2", "_3"))
		// Getting maximal disks inside partitions...
		logger.info("Getting maximal disks inside partitions...")
		var time1 = System.currentTimeMillis()
		val maximalInside = filteredCandidates
			.rdd
			.mapPartitions { partition =>
				val transactions = partition
				.map { disk =>
					disk._4
					.replace("[","")
					.replace("]","")
					.split(",")
					.map{ id =>
						new Integer(id.trim)
					}
					.sorted
					.toList
					.asJava
				}.toList.asJava
				val fpMax = new AlgoFPMax
				val itemsets = fpMax.runAlgorithm(transactions, 1)
				itemsets.getItemsets(mu).asScala.toIterator
			}
		val nMaximalInside = maximalInside.count()
		var time2 = System.currentTimeMillis()
		val timeI = (time2 - time1) / 1000.0
		// Getting maximal disks on frame partitions...
		logger.info("Getting maximal disks on frame partitions...")
		time1 = System.currentTimeMillis()
		val maximalFrame = filteredCandidates
			.rdd
			.mapPartitions { partition =>
				val pList = partition.toList
				val bbox = getBoundingBox(pList)
				val transactions = pList
				.map(disk => (disk._1, disk._2, disk._3, disk._4, !isInside(disk._2, disk._3, bbox, epsilon)))
				.filter(_._5)
				.map { disk =>
					disk._4
					.replace("[","")
					.replace("]","")
					.split(",")
					.map{ id =>
						new Integer(id.trim)
					}
					.sorted
					.toList
					.asJava
				}.asJava
			val fpMax = new AlgoFPMax
			val itemsets = fpMax.runAlgorithm(transactions, 1)
			itemsets.getItemsets(mu).asScala.toIterator
		}
		val nMaximalFrame = maximalFrame.count()
		time2 = System.currentTimeMillis()
		val timeF = (time2 - time1) / 1000.0
		// Collecting stats...
		logger.info("Collecting stats...")
		val partition_sizes = filteredCandidates.rdd.
			mapPartitions{ it =>
				List(it.size.toDouble).iterator
			}
		val new_partitions = filteredCandidates.rdd.getNumPartitions
		val sizes = new DoubleRDDFunctions(partition_sizes)
		val avg = sizes.mean()
		val sd = sizes.stdev()
		val variance = sizes.variance()
		val max = partition_sizes.max().toInt
		val min = partition_sizes.min().toInt
		// Printing summary...
		logger.info("Printing summary...")
		logger.info("%5s,%8s,%6s,%6s,%4s,%4s,%4s,%6s,%3s,%9s,%9s,%9s,%6s,%6s,%5s,%5s".
			format("Data", "# Cands", "# In", "# Out", 
				"Par1", "Par2", "Ent", "Eps", "Mu", 
				"TimeIn", "TimeOut", "Avg", "SD", "Var", "Min", "Max"
			)
		)
		logger.info("%5s,%8d,%6d,%6d,%4s,%4d,%4s,%6.1f,%3d,%9.2f,%9.2f,%9.2f,%6.2f,%6.2f,%5d,%5d".
			format(dataset, nFilteredCandidates, nMaximalInside, nMaximalFrame,
				partitions, new_partitions, entries, epsilon, mu, 
				timeI, timeF, avg, sd, variance, min, max
			)
		)
		// Writing Geojsons...
		logger.info("Writing Geojsons...")
		val mbrs = filteredCandidates.rdd.mapPartitionsWithIndex{ (index, iterator) =>
			var min_x: Double = Double.MaxValue
			var min_y: Double = Double.MaxValue
			var max_x: Double = Double.MinValue
			var max_y: Double = Double.MinValue
			var size: Int = 0
			iterator.toList.foreach{ row =>
				val x = row._2
				val y = row._3
				if(x < min_x){ min_x = x }
				if(y < min_y){ min_y = y }
				if(x > max_x){ max_x = x }
				if(y > max_y){ max_y = y }
				size += 1
			}
			List((min_x, min_y, max_x, max_y, index, size)).iterator
		}
		var gson1 = new GeoGSON(EPSG)
		mbrs.collect().foreach{ row =>
			gson1.makeMBRs(row._1, row._2, row._3, row._4, row._5, row._6)
		}
		var geojson: String = "%s%sViz/RTree_%s_E%.1f_M%d_P%s.geojson".format(phd_home, path, dataset, epsilon, mu, partitions)
		gson1.saveGeoJSON(geojson)
		logger.info("%s has been written...".format(geojson))
		var gson2 = new GeoGSON(EPSG)
		filteredCandidates.collect().foreach{ row =>
			gson2.makePoints(row._2, row._3)
		}
		geojson = "%s%sViz/Points_%s_E%.1f_M%d_P%s.geojson".format(phd_home, path, dataset, epsilon, mu, partitions)
		gson2.saveGeoJSON(geojson)
		logger.info("%s has been written...".format(geojson))

		// Dropping indices...
		logger.info("Dropping indices...")
		centers.dropIndexByName("centersRT")
		filteredCandidates.dropIndexByName("candidatesRT")			
		p1.dropIndexByName("p1RT")
		p2.dropIndexByName("p2RT")
		// Closing app...
		logger.info("Closing app...")
		simba.stop()
	}
	
	def findDisks(pairsRDD: RDD[Row], epsilon: Double): RDD[ACenter] = {
		val r2: Double = math.pow(epsilon / 2, 2)
		val centers = pairsRDD
			.filter((row: Row) => row.getInt(0) != row.getInt(3))
			.map { (row: Row) =>
				val p1 = APoint(row.getInt(0), row.getDouble(1), row.getDouble(2))
				val p2 = APoint(row.getInt(3), row.getDouble(4), row.getDouble(5))
				calculateDiskCenterCoordinates(p1, p2, r2)
			}
		centers
	}

	def calculateDiskCenterCoordinates(p1: APoint, p2: APoint, r2: Double): ACenter = {
		val X: Double = p1.x - p2.x
		val Y: Double = p1.y - p2.y
		var D2: Double = math.pow(X, 2) + math.pow(Y, 2)
		if (D2 == 0)
			D2 = 0.01
		val root: Double = math.sqrt(math.abs(4.0 * (r2 / D2) - 1.0))
		val h1: Double = ((X + Y * root) / 2) + p2.x
		val k1: Double = ((Y - X * root) / 2) + p2.y

		ACenter(0, h1, k1)
	}

	def isInside(x: Double, y: Double, bbox: BBox, epsilon: Double): Boolean ={
		x < (bbox.maxx - epsilon) &&
		x > (bbox.minx + epsilon) &&
		y < (bbox.maxy - epsilon) &&
		y > (bbox.miny + epsilon)
	}

	def getBoundingBox(p: List[(Long, Double, Double, Any)]): BBox = {
		var minx: Double = Double.MaxValue
		var miny: Double = Double.MaxValue
		var maxx: Double = Double.MinValue
		var maxy: Double = Double.MinValue
		p.foreach{r =>
			if(r._2 < minx){ minx = r._2 }
			if(r._2 > maxx){ maxx = r._2 }
			if(r._3 < miny){ miny = r._3 }
			if(r._3 > maxy){ maxy = r._3 }
		}
		BBox(minx, miny, maxx, maxy)
	}	
}
