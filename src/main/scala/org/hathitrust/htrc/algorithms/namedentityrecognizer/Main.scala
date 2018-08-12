package org.hathitrust.htrc.algorithms.namedentityrecognizer

import java.io.FileInputStream
import java.security.KeyStore
import java.util.Locale
import java.util.concurrent.Executors

import com.gilt.gfc.time.Timer
import com.typesafe.config.ConfigFactory
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
    val (conf, input) =
      if (args.contains("--config")) {
        val configArgs = new ConfigFileArg(args)
        val input = configArgs.htids.toOption
        Conf.fromConfig(ConfigFactory.parseFile(configArgs.configFile()).getConfig(appName)) -> input
      } else {
        val cmdLineArgs = new CmdLineArgs(args)
        val input = cmdLineArgs.htids.toOption
        Conf.fromCmdLine(cmdLineArgs) -> input
      }

    val htids = input match {
      case Some(file) => Source.fromFile(file).getLines().toSeq
      case None => Iterator.continually(StdIn.readLine()).takeWhile(_ != null).toSeq
    }

    val sparkConf = new SparkConf()
    sparkConf.setAppName(appName)
    sparkConf.setIfMissing("spark.master", s"local[${conf.numCores}]")

    val spark = SparkSession.builder()
      .config(sparkConf)
      .getOrCreate()

    import spark.implicits._

    val sc = spark.sparkContext

    try {
      logger.info("Starting...")
      logger.debug(s"Using ${conf.numCores} cores")

      val t0 = Timer.nanoClock()
      conf.outputPath.mkdirs()

      val idsRDD = conf.numPartitions match {
        case Some(n) => sc.parallelize(htids, n) // split input into n partitions
        case None => sc.parallelize(htids) // use default number of partitions
      }

      val volumeErrAcc = new ErrorAccumulator[String, String](identity)(sc)

      val volumesRDD = conf.pairtreeRootPath match {
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
          val dataApiUrl = conf.dataApiUrl.get
          val dataApiToken = conf.dataApiToken.get
          val keyStoreFile = conf.keyStoreFile.get
          val keyStorePwd = conf.keyStorePwd.get

          logger.info("Processing volumes from {}", dataApiUrl)

          idsRDD.mapPartitions {
            case ids if ids.nonEmpty =>
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

            case _ => Iterator.empty
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
            val locale = Locale.forLanguageTag(conf.language)
            val entityExtractor = EntityExtractor(locale)
            val entities = entityExtractor.extractTextEntities(pageText)
            val random = new Random
            for (Entity(entity, entityType) <- random.shuffle(entities))
              yield (volId, page.seq, entity, entityType)
          }
          .toDF("volId", "pageSeq", "entity", "type")

      entitiesDF.write
        .option("header", "false")
        .csv(conf.outputPath + "/csv")

      if (volumeErrAcc.nonEmpty) {
        logger.info("Writing error report...")
        volumeErrAcc.saveErrors(new Path(conf.outputPath.toString, "volumerm _errors.txt"), _.toString)
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