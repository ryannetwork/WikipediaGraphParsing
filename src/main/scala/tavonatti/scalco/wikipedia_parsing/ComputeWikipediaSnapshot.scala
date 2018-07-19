package tavonatti.scalco.wikipedia_parsing

import java.text.SimpleDateFormat
import java.util
import java.util.{Date, GregorianCalendar}

import org.apache.spark.SparkConf
import org.apache.spark.graphx.{Edge, Graph, VertexId}
import org.apache.spark.ml.feature.{BucketedRandomProjectionLSH, MinHashLSH}
import org.apache.spark.ml.linalg.SparseVector
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.{Row, SparkSession, functions}
import org.apache.spark.sql.functions.{col, udf}
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import org.apache.spark.storage.StorageLevel
import org.neo4j.spark.Neo4j.{NameProp, Pattern}
import org.neo4j.spark._

import scala.collection.mutable
import scala.util.matching.Regex

object ComputeWikipediaSnapshot extends App {
  /*
       ==============================================
       SPARK CONFIGURATION AND DATA RETRIEVING
       ==============================================
    */


  val startTime:Long=System.currentTimeMillis()

  val format = Utils.format

  println("history: Wikipedia-20180116134419.xml")
  println("current: Wikipedia-20180116144701.xml")

  //https://en.wikipedia.org/wiki/Wikipedia:Tutorial/Formatting

  /* Configuration parameters for Spark */
  val conf = new SparkConf()
  conf.set("spark.neo4j.bolt.url","bolt://127.0.0.1:7687")
  conf.set("spark.neo4j.bolt.user","neo4j")
  conf.set("spark.neo4j.bolt.password","password")
  conf.set("spark.driver.maxResultSize","2048")
 // conf.set("driver-memory","8g")

  /* Configures SparkSession and gets the spark context */
  val spark = SparkSession
    .builder()
    .appName("Wikipedia graph parsing")
    .master("local[*]")
    .config(conf)
    .getOrCreate()

  val sc=spark.sparkContext


  /* Definition and registration of custom user defined functions */
  val lowerRemoveAllSpecialCharsUDF = udf[String, String](Utils.lowerRemoveAllSpecialChars)
  val stringToTimestampUDF= udf[Long,String](Utils.stringToTimestamp)
  spark.udf.register("lowerRemoveAllSpecialCharsUDF",lowerRemoveAllSpecialCharsUDF)
  spark.udf.register("stringToTimestampUDF",stringToTimestampUDF)


  /* Reads the dataset */
  val df = spark.read
    .format("com.databricks.spark.xml")
    .option("rowTag", "page")
    //.load("samples/pages.xml")
    //.load("samples/Wikipedia-20180220091437.xml")//1000 revisions
    //.load("samples/Wikipedia-20180710084606.xml")
    //.load("samples/Wikipedia-20180710151718.xml")
    .load("samples/italy.xml")
    //.load("samples/total.xml")
    //.load("samples/spaceX.xml")
    //.load("samples/Wikipedia-20180620152418.xml")
    //.load("samples/Wikipedia-20180116144701.xml")

  import spark.implicits._
  import scala.collection.JavaConverters._

  df.persist(StorageLevel.MEMORY_AND_DISK)



  /*
      ==============================================
      EXTRACTING MIN DATES
      BUILDING THE NODES RDDS
      CLEANING THE DATASET
      ==============================================
   */





  /* Building RDDs for all the nodes found. The nodes are then cached in memory since they will be used several times */
  val nodes: RDD[(VertexId,String)]=df.select("id","title").rdd.map(n=>{

    /* Creating a node with the id of the page and the title */
    (n.get(0).asInstanceOf[Long],n.get(1).toString.toLowerCase)
  })
  nodes.cache()

  /* Get all the revisions from the data frame */
  val revisions=df.select(df.col("id"),df.col("title"),functions.explode(functions.col("revision")).as("revision"))

  /* df is no longer needed, so remove it from cache */
  df.unpersist()

  /* Tokenize and clean the dataset */
  val dfTokenized=Utils.tokenizeAndClean(revisions.withColumn("textLowerAndUpper",$"revision.text._VALUE").select(col("id"),col("title"),col("revision.timestamp"), lowerRemoveAllSpecialCharsUDF(col("textLowerAndUpper")).as("text_clean"),col("textLowerAndUpper").as("text"),col("revision.id").as("revision_id")))

  /* Convert the timestamp from a date string to a long value */
  val dfClean=dfTokenized.withColumn("revision_date",functions.to_date(col("timestamp"),"yyyy-MM-dd'T'HH:mm:ss'Z'")).repartition(6)
  //dfClean.cache()
  println("dfClean:")
  dfClean.printSchema()

  /* calculate the ids table*/
  val idsDF=nodes.toDF("id","name")
  idsDF.cache()
  idsDF.printSchema()
  idsDF.show()

  idsDF.groupBy(col("name")).count().filter(col("count").gt(1)).coalesce(1).write.csv("outputs/dup")

  /*check for duplicate names*/
  println("check for duplicated page title...")
  val duplicatedRowNumber= idsDF.groupBy(col("name")).count().filter(col("count").gt(1)).count()
  assert(duplicatedRowNumber==0)

  println("no duplicated page title found")

  /**
    * extract connection between the pages
    * @param s
    * @return
    */
  def extractIDS(s:String):Array[String] ={
    if(!(s==null || s.equals("") || s.isEmpty)){
      val list = new util.TreeSet[String]();
      val pattern = new Regex("\\[\\[[\\w|\\s]+\\]\\]")

      val iterator=pattern.findAllIn(s)

      while(iterator.hasNext){
        var temp=iterator.next()
        temp=temp.replaceAll("\\[\\[","").replaceAll("\\]\\]","")
        var splitted=temp.split("\\|")

        val it2=splitted.iterator
        while(it2.hasNext){
          val split=it2.next()
          list.add(split)
        }

      }

      val a=Array[String]()

     return list.toArray(a) //((source_id,revision_id),[list of links])
    }
    return (new util.TreeSet[String]).toArray(Array[String]())
  }

  val extractIdsUDF= udf[Array[String],String](extractIDS)
  spark.udf.register("extractIdsUDF",extractIdsUDF)

  def timestampToDate(ts:Long):java.sql.Date={
    if(ts!=null){
      return new java.sql.Date(ts)
    }
    return null
  }

  val timestampToDateUDF=udf[java.sql.Date,Long](timestampToDate)
  spark.udf.register("timestampToDateUDF",timestampToDateUDF)


  /*extract ids and revision month and year*/
  val dfClean2=dfClean.withColumn("connected_pages",extractIdsUDF(col("text")))
      //.withColumn("revision_date",timestampToDateUDF(col("timestampLong")))
      .drop("timestamp")
      //.withColumn("revision_month",functions.month($"revision_date"))
      .withColumn("revision_year",functions.year($"revision_date"))
    .drop("text","text_clean")
      .sort(col("revision_date").desc)

  println("dfClean2:")
  dfClean2.printSchema()
  //dfClean2.show(true)
  dfClean2.cache()

 /* val dfClean2Iterator= dfClean2.select("revision_year","revision_month").distinct()
    .sort(col("revision_year").asc,col("revision_month").asc)
    .filter(col("revision_year").geq(2000).and(col("revision_year").leq(2018))).collect().iterator
*/
   val dfClean2Iterator= dfClean2.select("revision_year").distinct()
     .sort(col("revision_year").asc)
     .filter(col("revision_year").geq(2000).and(col("revision_year").leq(2018))).collect().iterator

  //var rowsRDD:RDD[Row]=sc.emptyRDD

 // var snapshotDf=spark.emptyDataset(RowEncoder(dfClean2.schema))

  while (dfClean2Iterator.hasNext){
    val row=dfClean2Iterator.next()

    println("revison_"
      +row.getAs[String]("revision_year"))/*+"_"+
      row.getAs[String]("revision_month"))*/

    //.and(
    //col("revision_month").leq(row.getAs[Int]("revision_month"))
    val tempTable=dfClean2.filter(col("revision_year").leq(row.getAs[Int]("revision_year")))
      .groupBy("id","title")
      .agg(functions.first("revision_id").as("revision_id"),
        functions.first("tokens").as("tokens"),
        functions.first("tokenClean").as("tokenClean"),
        functions.first("frequencyVector").as("frequencyVector"),
        //functions.first("timestampLong").as("timestampLong"),
        functions.first("connected_pages").as("connected_pages")
        ,functions.first("revision_date").as("revision_date"))
      .withColumn("revision_year",functions.lit(row.getAs[Int]("revision_year")))
      //.withColumn("revision_month",functions.lit(row.getAs[Int]("revision_month")))

    //rowsRDD=rowsRDD.union(tempTable.rdd).repartition(8)
    tempTable.write.parquet("in/snappshot/"+"revison_"
      +row.getAs[String]("revision_year"))

  }

  //spark.createDataFrame(rowsRDD,dfClean2.schema).show()
  //System.exit(0)

  /*take the most update revision per month*/
  /*val dfClean3=dfClean2.groupBy("id","title","revision_month","revision_year")
      .agg(functions.first("text_clean").as("text_clean"),functions.first("text")
        .as("text"),functions.first("revision_id").as("revision_id"),
        functions.first("tokens").as("tokens"),
        functions.first("tokenClean").as("tokenClean"),
        functions.first("frequencyVector").as("frequencyVector")
        ,functions.first("timestampLong").as("timestampLong"),
        functions.first("connected_pages").as("connected_pages")
        ,functions.first("revision_date").as("revision_date"))*/

/*  val dfClean3=spark.read.parquet("in/partial")
    //spark.createDataFrame(rowsRDD,dfClean2.schema)

  println("dfClean3: ")
  dfClean3.printSchema()

  //.option("compression","bzip2")
  dfClean3.coalesce(1).write.parquet("in/snappshot")//format("json").save("in/snappshot")*/


}
//https://github.com/neo4j-contrib/neo4j-spark-connector