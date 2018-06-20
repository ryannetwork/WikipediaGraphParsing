package tavonatti.scalco.wikipedia_parsing

import java.text.SimpleDateFormat
import java.util
import java.util.{Date, GregorianCalendar}

import org.apache.spark.SparkConf
import org.apache.spark.graphx.{Edge, Graph, VertexId}
import org.apache.spark.ml.feature.{BucketedRandomProjectionLSH, MinHashLSH}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{SparkSession, functions}
import org.apache.spark.sql.functions.{col, udf}
import org.apache.spark.storage.StorageLevel
import org.neo4j.spark.Neo4j.{NameProp, Pattern}
import org.neo4j.spark._

import scala.collection.mutable
import scala.util.matching.Regex

object Main2 extends App {
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
    .load("samples/Wikipedia-20180620152418.xml")
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



  /*search first revision date in the dataset*/


  /* Finds the minumum for every page */
 /* val timestampRdd:RDD[Date] =df.select("revision.timestamp").rdd.flatMap(row=>{
    val it=row.get(0).asInstanceOf[mutable.WrappedArray[String]].iterator

    /*if the array is empty return the iterator*/
    if(!it.hasNext){
      it
    }

    /* This represents the time that is used to check all the other dates */
    var min: Date = new Date(System.currentTimeMillis()+1000)

    /* Iterates through all the dates and checks if a new minimum is found at every iteration*/
    while (it.hasNext){
      if(it!=null && !it.equals("") && it.nonEmpty) {
        val temp: Date = format.parse(it.next());
        if (temp.getTime < min.getTime) {
          /* New minimum found */
          min = temp
        }
      }
    }

    mutable.Seq[Date] {min}.iterator
  })*/


  /* Finds the global minimum through a reduce */
 /* val first=timestampRdd.reduce((d1,d2)=>{
    if(d1.getTime<d2.getTime){
      d1
    }
    else {
      d2
    }
  })

  println(first)*/

  /* Building RDDs for all the nodes found. The nodes are then cached in memory since they will be used several times */
  val nodes: RDD[(VertexId,String)]=df.select("id","title").rdd.map(n=>{

    /* Creating a node with the id of the page and the title */
    (n.get(0).asInstanceOf[Long],n.get(1).toString)
  })
  nodes.cache()

  /* Get all the revisions from the data frame */
  val revisions=df.select(df.col("id"),df.col("title"),functions.explode(functions.col("revision")).as("revision"))

  /* df is no longer needed, so remove it from cache */
  df.unpersist()

  /* Tokenize and clean the dataset */
  val dfTokenized=Utils.tokenizeAndClean(revisions.withColumn("textLowerAndUpper",$"revision.text._VALUE").select(col("id"),col("title"),col("revision.timestamp"), lowerRemoveAllSpecialCharsUDF(col("textLowerAndUpper")).as("text_clean"),col("textLowerAndUpper").as("text"),col("revision.id").as("revision_id")))

  /* Convert the timestamp from a date string to a long value */
  val dfClean=dfTokenized.withColumn("timestampLong",stringToTimestampUDF(col("timestamp"))).repartition(6)
  //dfClean.cache()
  dfClean.printSchema()
  //dfClean.show()

  /* calculate the ids table*/
  val idsDF=nodes.toDF("id","name")
  idsDF.cache()
  idsDF.printSchema()
  idsDF.show()

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

        list.add(temp)
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
      .withColumn("revision_date",timestampToDateUDF(col("timestampLong")))
      .drop("timestamp")
      .withColumn("revision_month",functions.month($"revision_date"))
      .withColumn("revision_year",functions.year($"revision_date"))
      .sort(col("timestampLong").desc)

  dfClean2.printSchema()
  //dfClean2.show(true)

  /*take the most update revision per month*/
  val dfClean3=dfClean2.groupBy("id","title","revision_month","revision_year")
      .agg(functions.first("text_clean").as("text_clean"),functions.first("text")
        .as("text"),functions.first("revision_id").as("revision_id"),
        functions.first("tokens").as("tokens"),
        functions.first("tokenClean").as("tokenClean"),
        functions.first("frequencyVector").as("frequencyVector")
        ,functions.first("timestampLong").as("timestampLong"),
        functions.first("connected_pages").as("connected_pages")
        ,functions.first("revision_date").as("revision_date"))


  println("dfClean3: ")
  dfClean3.printSchema()
  //dfClean3.show(true)

  val dfClean3Exploded=dfClean3.withColumn("linked_page",functions.explode(col("connected_pages"))).drop("connected_pages")

  //dfClean3Exploded.cache()
  println("dfClean3Exploded: ")
  dfClean3Exploded.printSchema()
  //dfClean3Exploded.show(true)

  val suffix="_LINKED"
  val renamedColumns=dfClean3Exploded.columns.map(c=> dfClean3Exploded(c).as(s"$c$suffix"))
  val dfClean3ExplodedRenamed = dfClean3Exploded.select(renamedColumns: _*).drop(s"linked_page$suffix")



  println(s"dfClean3ExplodedRenamed:")
  dfClean3ExplodedRenamed.printSchema()

  println("dfClean3Exploded sample:")
 // dfClean3Exploded.select("id","title","linked_page").show()

  val dfMerged=dfClean3Exploded.join(dfClean3ExplodedRenamed,$"linked_page"===$"title$suffix","left")
  println("dfMerged:")
  dfMerged.printSchema()



 // dfMerged.select("id",s"id$suffix","title",s"title$suffix").show()

  //System.exit(0)

  val neo = Neo4j(sc)

  val savedNodes=nodes.map(n=>{
    neo.cypher("MERGE (p:Page{title:\""+n._2+"\", pageId:+"+n._1+"})").loadRowRdd.count()
    n
  })

  println(""+savedNodes.count()+" saved")

  println("create index on :Page(pageId)...")
  neo.cypher("CREATE INDEX ON :Page(pageId)").loadRowRdd.count()


  val edges: RDD[Long] =dfMerged.coalesce(2).rdd.map(row=>{
    val idSource=row.getAs[Long]("id")
    val idDest=row.getAs[Long](s"id$suffix")
    val linkName=row.getAs[Int]("revision_year")+
      "-"+row.getAs[Int]("revision_month")

    val query="MATCH (p:Page{pageId:"+idSource+"}),(p2:Page{pageId:"+idDest+"})"+
      "\nCREATE (p)-[:LOLOL"+linkName.replace("-","gattino")+"{page_src:\""+42+"\"}]->(p2)"
    neo.cypher(query).loadRowRdd.count()
    //Edge(idSource,idDest,(linkName,42.0))
    //Edge(idSource,idDest,linkName)
  })

  //val pageGraph:Graph[String,(String,Double)] = Graph(nodes, edges)
 // val pageGraph:Graph[String,String] = Graph(nodes, edges)

  //print(neo.saveGraph(pageGraph,"page_name",Pattern(new NameProp("Page","id"),Seq(new NameProp("to-","years")),new NameProp("Page","id")),merge = true))
  //print(Neo4jGraph.saveGraph(sc,pageGraph,"wiki_page",("boh","page"),Some("Page","id"),Some("Page","id"),merge = true))
 println(""+edges.reduce((a,b)=>a+b)+" edges saved")


}
//https://github.com/neo4j-contrib/neo4j-spark-connector