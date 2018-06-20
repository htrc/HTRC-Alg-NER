package org.hathitrust.htrc.algorithms.namedentityrecognizer

import java.io.{File, FileOutputStream, OutputStreamWriter}
import java.util.Locale
import java.util.concurrent.Executors

import com.gilt.gfc.time.Timer
import kantan.csv._
import kantan.csv.ops._
import org.apache.hadoop.fs.Path
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.hathitrust.htrc.algorithms.namedentityrecognizer.Helper.logger
import org.hathitrust.htrc.algorithms.namedentityrecognizer.stanfordnlp.{Entity, EntityExtractor}
import org.hathitrust.htrc.data.TextOptions.{DehyphenateAtEol, RemoveEmptyLines, TrimLines}
import org.hathitrust.htrc.data.{HtrcVolume, HtrcVolumeId}
import org.hathitrust.htrc.tools.dataapi.DataApiClient
import org.hathitrust.htrc.tools.scala.io.IOUtils._
import org.hathitrust.htrc.tools.spark.errorhandling.ErrorAccumulator
import org.hathitrust.htrc.tools.spark.errorhandling.RddExtensions._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}
import scala.io.{Codec, Source, StdIn}
import scala.util.Random

object Main {
  val appName: String = "named-entity-recognizer"
  val supportedLanguages: Set[String] = Set("ar", "zh", "en", "fr", "de", "es", "it", "nl", "se")

  def main(args: Array[String]): Unit = {
    val conf = new Conf(args)
    val numPartitions = conf.numPartitions.toOption
    val numCores = conf.numCores.map(_.toString).getOrElse("*")
    val dataApiUrl = conf.dataApiUrl()
    val pairtreeRootPath = conf.pairtreeRootPath.toOption.map(_.toString)
    val outputPath = conf.outputPath()
    val language = conf.language()
    val htids = conf.htids.toOption match {
      case Some(file) => Source.fromFile(file).getLines().toSeq
      case None => Iterator.continually(StdIn.readLine()).takeWhile(_ != null).toSeq
    }

    val sparkConf = new SparkConf()
    sparkConf.setAppName(appName)
    sparkConf.setIfMissing("spark.master", s"local[$numCores]")

    val spark = SparkSession.builder()
      .config(sparkConf)
      .getOrCreate()

    val sc = spark.sparkContext

    logger.info("Starting...")
    logger.debug(s"Using $numCores cores")

    val t0 = Timer.nanoClock()
    val idErrAcc = new ErrorAccumulator[String, String](identity)(sc)

    outputPath.mkdirs()

    val idsRDD = numPartitions match {
      case Some(n) => sc.parallelize(htids, n) // split input into n partitions
      case None => sc.parallelize(htids) // use default number of partitions
    }

    val volumesRDD = pairtreeRootPath match {
      case Some(path) =>
        logger.info("Processing volumes from {}", path)
        idsRDD.tryMap { id =>
          val pairtreeVolume =
            HtrcVolumeId
              .parseUnclean(id)
              .map(_.toPairtreeDoc(path))
              .get

          HtrcVolume.from(pairtreeVolume)(Codec.UTF8).get
        }(idErrAcc)

      case None =>
        val dataApiToken = Option(System.getenv("DATAAPI_TOKEN")) match {
          case Some(token) => token
          case None => throw new RuntimeException("DATAAPI_TOKEN environment variable is missing")
        }

        logger.info("Processing volumes from {}", dataApiUrl)

        idsRDD.mapPartitions { ids =>
          val dataApi = DataApiClient.Builder()
            .setApiUrl(dataApiUrl.toString)
            .setAuthToken(dataApiToken)
            .setUseTempStorage()
            .build()

          val ec = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())
          val volumes = using(Await.result(dataApi.retrieveVolumes(ids)(Codec.UTF8, ec), Duration.Inf))(_.toList)
          volumes.iterator
        }
    }

    // English model options:
    // DefaultPaths.DEFAULT_NER_THREECLASS_MODEL
    // DefaultPaths.DEFAULT_NER_MUC_MODEL
    // DefaultPaths.DEFAULT_NER_CONLL_MODEL

    val pageEntities =
      volumesRDD
        .flatMap(vol => vol.structuredPages.map(vol.volumeId.uncleanId -> _))
        .mapValues { page =>
          val pageText = page.body(TrimLines, RemoveEmptyLines, DehyphenateAtEol)
          val locale = Locale.forLanguageTag(language)
          val entityExtractor = EntityExtractor(locale)
          page.seq -> entityExtractor.extractTextEntities(pageText)
        }
        .collect()

    if (idErrAcc.nonEmpty) {
      logger.info("Writing error report...")
      idErrAcc.saveErrors(new Path(outputPath.toString, "id_errors.txt"), _.toString)
    }

    val rows = {
      val random = Random

      for {
        (volId, (seq, entities)) <- pageEntities
        Entity(entity, entityType) <- random.shuffle(entities)
      } yield (volId, seq, entity, entityType)
    }

    logger.info("Saving named entities...")
    val entitiesFile = new File(outputPath, "entities.csv")
    val writer = new OutputStreamWriter(new FileOutputStream(entitiesFile), Codec.UTF8.charSet)
    val csvConfig = rfc.withHeader("volId", "pageSeq", "entity", "type")
    using(writer.asCsvWriter[(String, String, String, String)](csvConfig)) { out =>
      out.write(rows)
    }

    // record elapsed time and report it
    val t1 = Timer.nanoClock()
    val elapsed = t1 - t0

    logger.info(f"All done in ${Timer.pretty(elapsed)}")

    System.exit(0)
  }
}