import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.ScalaReflection
import org.apache.spark.sql.simba.SimbaSession
import org.apache.spark.sql.types.StructType
import org.rogach.scallop.{ScallopConf, ScallopOption}
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object FlockFinder {
  private val log: Logger = LoggerFactory.getLogger("myLogger")
  private var nPointset: Long = 0

  case class ST_Point(x: Double, y: Double, t: Int, id: Int)
  case class Flock(start: Int, end: Int, ids: String, lon: Double = 0.0, lat: Double = 0.0)

  class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
    val epsilon:    ScallopOption[Double] = opt[Double] (default = Some(10.0))
    val mu:         ScallopOption[Int]    = opt[Int]    (default = Some(5))
    val entries:    ScallopOption[Int]    = opt[Int]    (default = Some(25))
    val partitions: ScallopOption[Int]    = opt[Int]    (default = Some(1024))
    val candidates: ScallopOption[Int]    = opt[Int]    (default = Some(256))
    val cores:      ScallopOption[Int]    = opt[Int]    (default = Some(28))
    val master:     ScallopOption[String] = opt[String] (default = Some("spark://169.235.27.134:7077")) /* spark://169.235.27.134:7077 */
    val path:       ScallopOption[String] = opt[String] (default = Some("Y3Q1/Datasets/"))
    val valpath:    ScallopOption[String] = opt[String] (default = Some("Y3Q1/Validation/"))
    val dataset:    ScallopOption[String] = opt[String] (default = Some("B20K"))
    val extension:  ScallopOption[String] = opt[String] (default = Some("csv"))
    val method:     ScallopOption[String] = opt[String] (default = Some("fpmax"))
    // FlockFinder parameters
    val delta:	    ScallopOption[Int]    = opt[Int]    (default = Some(3))    
    val tstart:     ScallopOption[Int]    = opt[Int]    (default = Some(0))
    val tend:       ScallopOption[Int]    = opt[Int]    (default = Some(5))
    val cartesian:  ScallopOption[Int]    = opt[Int]    (default = Some(2))
    val logs:	    ScallopOption[String] = opt[String] (default = Some("INFO"))    
    verify()
  }
  
  def run(conf: Conf): Unit = {
    // Tuning master and number of cores...
    var MASTER = conf.master()
    if (conf.cores() == 1) {
      MASTER = "local"
    }
    // Setting parameters...
    val epsilon: Double = conf.epsilon()
    val mu: Int = conf.mu()
    val CARTESIAN: Int = conf.cartesian()
    val POINT_SCHEMA = ScalaReflection.schemaFor[ST_Point].
      dataType.
      asInstanceOf[StructType]
    // Starting a session...
    log.info("Setting paramaters...")
    val simba = SimbaSession.builder().
      master(MASTER).
      appName("FlockFinder").
      config("simba.index.partitions", s"${conf.partitions()}").
      getOrCreate()
    simba.sparkContext.setLogLevel(conf.logs())
    // Calling implicits...
    import simba.implicits._
    import simba.simbaImplicits._
    val phd_home = scala.util.Properties.envOrElse("PHD_HOME", "/home/acald013/PhD/")
    val filename = s"$phd_home${conf.path()}${conf.dataset()}.${conf.extension()}"
    log.info("Reading %s ...".format(filename))
    val pointset = simba.read.
      option("header", "false").
      schema(POINT_SCHEMA).
      csv(filename).
      as[ST_Point].
      filter(datapoint => datapoint.t >= conf.tstart() && datapoint.t <= conf.tend()).
      cache()
    nPointset = pointset.count()
    log.info("Number of points in dataset: %d".format(nPointset))
    var timestamps = pointset.
      map(datapoint => datapoint.t).
      distinct.
      sort("value").
      collect.toList
    var FLOCKS_OUT = List.empty[String]
    // Running experiment with different values of epsilon and mu...
    log.info("epsilon=%.1f,mu=%d".format(epsilon, mu))
    // Running MaximalFinder...
    var timestamp = timestamps.head
    var currentPoints = pointset
      .filter(datapoint => datapoint.t == timestamp)
      .map{ datapoint => 
        "%d,%.2f,%.2f".format(datapoint.id, datapoint.x, datapoint.y)
      }.
      rdd
    log.info("nPointset=%d,timestamp=%d".format(currentPoints.count(), timestamp))
    // Maximal disks for time 0
    var F: RDD[Flock] = MaximalFinderExpansion.
      run(currentPoints, simba, conf).
      repartition(conf.cartesian()).
      map(f => Flock(timestamp, timestamp, f))
    log.info("Flock,Start,End,Flock")
    // Maximal disks for time 1 and onwards
    for(timestamp <- timestamps.slice(1,timestamps.length)){
	  // Reading points for current timestamp...
      currentPoints = pointset
        .filter(datapoint => datapoint.t == timestamp)
        .map{ datapoint => 
          "%d,%.2f,%.2f".format(datapoint.id, datapoint.x, datapoint.y)
        }.
        rdd
      log.info("nPointset=%d,timestamp=%d".format(currentPoints.count(), timestamp))
      
      // Finding maximal disks for current timestamp...
      val F_prime: RDD[Flock] = MaximalFinderExpansion.
        run(currentPoints, simba, conf).
        repartition(CARTESIAN).
        map(f => Flock(timestamp, timestamp, f))
      // Joining previous flocks and current ones...
      log.info("Running cartesian function for timestamps %d...".format(timestamp))
      var combinations = F.cartesian(F_prime)
      val ncombinations = combinations.count()
      log.info("Cartesian returns %d combinations...".format(ncombinations))
      // Checking if ids intersect...
      F = combinations.
        map{ tuple => 
          val ids_in_common = tuple._1.ids.intersect(tuple._2.ids).sorted
          Flock(tuple._1.start, tuple._2.end, ids_in_common)
        }.
        // Checking if they are greater than mu...
        filter(flock => flock.ids.length >= mu).
        // Removing duplicates...
        distinct
      val DELTA = conf.delta()
      // Reporting flocks with duration delta...
      F.foreach{ flock =>
		if(flock.end - flock.start == DELTA){
		  log.info("Flock,%d,%d,\"%s\"".format(flock.start, flock.end, flock.ids.mkString(";")))
        }
	  }
	  val n = F.filter(flock => flock.end - flock.start == DELTA).count()
      log.info("\n######\n#\n# Done!\n# %d flocks found in timestamp %d...\n#\n######".format(n, timestamp))
      // Appending new potential flocks from current timestamp...
      F = F.union(F_prime)
    }
    // Closing all...
    log.info("Closing app...")
    simba.close()
  }

  def main(args: Array[String]): Unit = {
    // Setting a custom logger...
    log.info("Starting app...")
    // Reading arguments from command line...
    val conf = new Conf(args)
    FlockFinder.run(conf)
  }
}
