package org.hathitrust.htrc.algorithms.namedentityrecognizer

import java.io.{File, FileOutputStream, OutputStreamWriter}
import java.util.Locale
import java.util.concurrent.Executors

import com.gilt.gfc.time.Timer
import kantan.csv._
import kantan.csv.ops._
import org.hathitrust.htrc.algorithms.namedentityrecognizer.stanfordnlp.{Entity, EntityExtractor}
import org.hathitrust.htrc.tools.dataapi.DataApiClient
import org.hathitrust.htrc.tools.scala.io.IOUtils._
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}
import scala.io.{Codec, Source, StdIn}
import scala.util.Random

object Main {
  val appName: String = "named-entity-recognizer"
  val logger: Logger = LoggerFactory.getLogger(appName)
  val supportedLanguages: Set[String] = Set("de", "en", "es", "fr", "it", "nl", "se", "zh")

  def main(args: Array[String]): Unit = {
    val conf = new Conf(args)
    val numCores = conf.numCores()
    val language = conf.language()
    val outputPath = conf.outputPath()
    val dataApiUrl = conf.dataApiUrl()
    val htidsOpt = conf.htids.toOption

    val dataApiToken = Option(System.getenv("DATAAPI_TOKEN")) match {
      case Some(token) => token
      case None => throw new RuntimeException("DATAAPI_TOKEN environment variable is missing")
    }

    logger.info("Starting...")
    logger.debug(s"Using $numCores cores")

    // record start time
    val t0 = Timer.nanoClock()

    implicit val codec: Codec = Codec.UTF8
    implicit val ec: ExecutionContext =
      ExecutionContext.fromExecutor(Executors.newFixedThreadPool(numCores))

    outputPath.mkdirs()

    val htids = htidsOpt match {
      case Some(file) => Source.fromFile(file).getLines().toBuffer
      case None => Iterator.continually(StdIn.readLine()).takeWhile(_ != null).toBuffer
    }

    val locale = Locale.forLanguageTag(language)
    val entityExtractor = EntityExtractor(locale)

    // English model options:
    // DefaultPaths.DEFAULT_NER_THREECLASS_MODEL
    // DefaultPaths.DEFAULT_NER_MUC_MODEL
    // DefaultPaths.DEFAULT_NER_CONLL_MODEL

    logger.info(f"Downloading ${htids.size}%,d volumes from $dataApiUrl...")

    val dataApi = DataApiClient.Builder()
      .setApiUrl(dataApiUrl.toString)
      .setAuthToken(dataApiToken)
      .setUseTempStorage()
      .build()

    val entitiesFile = new File(outputPath, "entities.csv")

    using(Await.result(dataApi.retrieveVolumes(htids), Duration.Inf)) { volumeIterator =>
      logger.info(s"Performing entity extraction and saving entities to $entitiesFile...")
      val random = Random

      val volumesEntities =
        for (vol <- volumeIterator) yield {
          logger.debug(s"Processing volume ${vol.volumeId}")
          vol -> entityExtractor.extractVolumeEntities(vol)
        }

      val rows =
        for {
          (vol, pagesEntities) <- volumesEntities
          (page, entities) <- pagesEntities
          Entity(entity, entityType) <- random.shuffle(entities)
        } yield (vol.volumeId.uncleanId, page.seq, entity, entityType)

      val writer = new OutputStreamWriter(new FileOutputStream(entitiesFile), codec.charSet)
      val csvConfig = rfc.withHeader("volId", "pageSeq", "entity", "type")
      using(writer.asCsvWriter[(String, String, String, String)](csvConfig)) { out =>
        out.write(rows)
      }
    }

    // record elapsed time and report it
    val t1 = Timer.nanoClock()
    val elapsed = t1 - t0

    logger.info(f"All done in ${Timer.pretty(elapsed)}")

    System.exit(0)
  }
}