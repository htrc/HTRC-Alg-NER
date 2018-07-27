package org.hathitrust.htrc.algorithms.namedentityrecognizer

import java.io.FileInputStream
import java.security.KeyStore
import java.util.Locale
import java.util.concurrent.Executors

import com.gilt.gfc.time.Timer
import org.apache.hadoop.fs.Path
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.SparkSession
import org.hathitrust.htrc.algorithms.namedentityrecognizer.Helper.logger
import org.hathitrust.htrc.algorithms.namedentityrecognizer.stanfordnlp.{Entity, EntityExtractor}
import org.hathitrust.htrc.data.ops.TextOptions._
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

  def stopSparkAndExit(sc: SparkContext, exitCode: Int = 0): Unit = {
    try {
      sc.stop()
    } finally {
      System.exit(exitCode)
    }
  }

  def main(args: Array[String]): Unit = {
    val conf = new Conf(args)
    val numPartitions = conf.numPartitions.toOption
    val numCores = conf.numCores.map(_.toString).getOrElse("*")
    val dataApiUrl = conf.dataApiUrl()
    val pairtreeRootPath = conf.pairtreeRootPath.toOption.map(_.toString)
    val outputPath = conf.outputPath()
    val keyStoreFile = conf.keyStore()
    val keyStorePwd = conf.keyStorePwd()
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

    import spark.implicits._

    val sc = spark.sparkContext

    try {
      logger.info("Starting...")
      logger.debug(s"Using $numCores cores")

      val t0 = Timer.nanoClock()
      outputPath.mkdirs()

      val idsRDD = numPartitions match {
        case Some(n) => sc.parallelize(htids, n) // split input into n partitions
        case None => sc.parallelize(htids) // use default number of partitions
      }

      val volumeErrAcc = new ErrorAccumulator[String, String](identity)(sc)

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
          }(volumeErrAcc)

        case None =>
          val dataApiToken = Option(System.getenv("DATAAPI_TOKEN")) match {
            case Some(token) => token
            case None => throw new RuntimeException("DATAAPI_TOKEN environment variable is missing")
          }

          logger.info("Processing volumes from {}", dataApiUrl)

          idsRDD.mapPartitions { ids =>
            val keyStore = KeyStore.getInstance("PKCS12")
            using(new FileInputStream(keyStoreFile)) { ksf =>
              keyStore.load(ksf, keyStorePwd.toCharArray)
            }

            val dataApi = DataApiClient.Builder()
              .setApiUrl(dataApiUrl.toString)
              .setAuthToken(dataApiToken)
              .setUseTempStorage(failOnError = false)
              .useClientCertKeyStore(keyStore, keyStorePwd)
              .build()

            val ec = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())
            val volumeIterator = Await.result(dataApi.retrieveVolumes(ids)(Codec.UTF8, ec), Duration.Inf)
            val volumes = using(volumeIterator)(_
              .withFilter {
                case Left(_) => true
                case Right(error) =>
                  logger.error("DataAPI: {}", error.message)
                  false
              }
              .collect { case Left(volume) => volume }
              .toList
            )
            volumes.iterator
          }
      }

      // English model options:
      // DefaultPaths.DEFAULT_NER_THREECLASS_MODEL
      // DefaultPaths.DEFAULT_NER_MUC_MODEL
      // DefaultPaths.DEFAULT_NER_CONLL_MODEL

      val entitiesDF =
        volumesRDD
          .flatMap(vol => vol.structuredPages.map(vol.volumeId.uncleanId -> _))
          .flatMap { case (volId, page) =>
            val pageText = page.body(TrimLines, RemoveEmptyLines, DehyphenateAtEol)
            val locale = Locale.forLanguageTag(language)
            val entityExtractor = EntityExtractor(locale)
            val entities = entityExtractor.extractTextEntities(pageText)
            val random = new Random
            for (Entity(entity, entityType) <- random.shuffle(entities))
              yield (volId, page.seq, entity, entityType)
          }
          .toDF("volId", "pageSeq", "entity", "type")

      entitiesDF.write
        .option("header", "false")
        .csv(outputPath + "/csv")

      if (volumeErrAcc.nonEmpty) {
        logger.info("Writing error report...")
        volumeErrAcc.saveErrors(new Path(outputPath.toString, "volumerm _errors.txt"), _.toString)
      }

      // record elapsed time and report it
      val t1 = Timer.nanoClock()
      val elapsed = t1 - t0

      logger.info(f"All done in ${Timer.pretty(elapsed)}")
    }
    catch {
      case e: Throwable =>
        logger.error(s"Uncaught exception", e)
        stopSparkAndExit(sc, exitCode = 500)
    }

    stopSparkAndExit(sc)
  }
}