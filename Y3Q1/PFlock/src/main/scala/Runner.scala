import org.apache.spark.rdd.RDD
import wvlet.log._
import org.apache.spark.sql.catalyst.ScalaReflection
import org.apache.spark.sql.simba.SimbaSession
import org.apache.spark.sql.types.StructType
import org.rogach.scallop.{ScallopConf, ScallopOption}

object Runner extends LogSupport{
  private val LAYER_0 = 117
  private val LAYERS_N = 2

  case class ST_Point(x: Double, y: Double, t: Int, id: Int)

  def main(args: Array[String]): Unit = {
    Logger.setDefaultFormatter(LogFormatter.SourceCodeLogFormatter)
    info(s"""{"content":"Starting app...","start":"${org.joda.time.DateTime.now.toLocalDateTime}"},\n""")
    // Reading arguments from command line...
    val conf = new Conf(args)
    // Tuning master and number of cores...
    var MASTER = conf.master()
    if (conf.cores() == 1) {
      MASTER = "local[1]"
    }
    // Setting parameters...
    val POINT_SCHEMA = ScalaReflection.schemaFor[ST_Point].dataType.asInstanceOf[StructType]
    // Starting a session...
    info(s"""{"content":"Setting paramaters...","start":"${org.joda.time.DateTime.now.toLocalDateTime}"},\n""")
    val simba = SimbaSession
      .builder()
      .master(MASTER)
      .appName("Reader")
      .config("simba.index.partitions", s"${conf.partitions()}")
      .config("spark.cores.max", s"${conf.cores()}")
      .getOrCreate()
    simba.sparkContext.setLogLevel(conf.logs())
    // Calling implicits...
    import simba.implicits._
    import simba.simbaImplicits._
    val phd_home = scala.util.Properties.envOrElse("PHD_HOME", "/home/and/Documents/PhD/Code/")
    val filename = s"$phd_home${conf.path()}${conf.dataset()}.${conf.extension()}"
    info("Reading %s ...".format(filename))
    val dataset = simba.read
      .option("header", "false")
      .schema(POINT_SCHEMA)
      .csv(filename)
      .as[ST_Point]
      .filter(datapoint => datapoint.t < LAYER_0 + LAYERS_N)
    dataset.cache()
    info("Number of points in dataset: %d".format(dataset.count()))
    val timestamps = dataset.map(datapoint => datapoint.t).distinct.sort("value").collect.toList
    // Setting PFlock...
    PFlock.EPSILON = conf.epsilon()
    PFlock.MU = conf.mu()
    PFlock.DATASET = conf.dataset()
    PFlock.CORES = conf.cores()
    PFlock.PARTITIONS = conf.partitions()
    // Running PFlock...
    var timestamp = timestamps.head
    var currentPoints = dataset
      .filter(datapoint => datapoint.t == timestamp)
      .map(datapoint => PFlock.SP_Point(datapoint.id, datapoint.x, datapoint.y))
    println("%d points in time %d".format(currentPoints.count(), timestamp))
    var f0: RDD[List[Int]] = PFlock.run(currentPoints, timestamp, simba)

    timestamp = timestamps(1)
    currentPoints = dataset
      .filter(datapoint => datapoint.t == timestamp)
      .map(datapoint => PFlock.SP_Point(datapoint.id, datapoint.x, datapoint.y))
    println("%d points in time %d".format(currentPoints.count(), timestamp))
    var f1: RDD[List[Int]] = PFlock.run(currentPoints, timestamp, simba)
    println("f0 has %d maximal disks and f1 has %d".format(f0.count(), f1.count()))
    // f0.toDF("f0").crossJoin(f1.toDF("f1"))

    // Saving results...
    PFlock.saveOutput()
    // Closing all...
    info(s"""{"content":"Closing app...","start":"${org.joda.time.DateTime.now.toLocalDateTime}"},\n""")
    simba.close()
  }

  class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
    val epsilon: ScallopOption[Double] = opt[Double](default = Some(10.0))
    val mu: ScallopOption[Int] = opt[Int](default = Some(3))
    val partitions: ScallopOption[Int] = opt[Int](default = Some(64))
    val cores: ScallopOption[Int] = opt[Int](default = Some(4))
    val master: ScallopOption[String] = opt[String](default = Some("local[*]"))
    val logs: ScallopOption[String] = opt[String](default = Some("ERROR"))
    val phd_home: ScallopOption[String] = opt[String](default = sys.env.get("PHD_HOME"))
    val path: ScallopOption[String] = opt[String](default = Some("Y3Q1/Datasets/"))
    val dataset: ScallopOption[String] = opt[String](default = Some("Berlin_N277K_A18K_T15"))
    val extension: ScallopOption[String] = opt[String](default = Some("csv"))

    verify()
  }

}