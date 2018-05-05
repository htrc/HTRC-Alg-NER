package org.hathitrust.htrc.algorithms.namedentityrecognizer.stanfordnlp

import java.util.Locale

import edu.stanford.nlp.ling.CoreAnnotations
import edu.stanford.nlp.pipeline.{Annotation, StanfordCoreNLP}
import org.hathitrust.htrc.algorithms.namedentityrecognizer.utils.Helper.loadPropertiesFromClasspath
import org.hathitrust.htrc.data.{HtrcPage, HtrcVolume}
import org.hathitrust.htrc.data.TextOptions._
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.util.{Failure, Success}

object EntityExtractor {
  private val logger = LoggerFactory.getLogger(getClass)

  def apply(locale: Locale): EntityExtractor = {
    val language = locale.getLanguage

    logger.info(s"Loading the ${locale.getDisplayLanguage} NER pipeline...")
    val props = loadPropertiesFromClasspath(s"/nlp/config/$language.properties") match {
      case Success(p) => p
      case Failure(_) => throw EntityExtractorException(s"No language models available for $language")
    }

    new EntityExtractor(new StanfordCoreNLP(props))
  }
}

class EntityExtractor(nlp: StanfordCoreNLP) {

  def extractTextEntities(text: String): List[Entity] = {
    val document = new Annotation(text)
    nlp.annotate(document)

    val entities =
      document.get(classOf[CoreAnnotations.SentencesAnnotation]).asScala.toList
        .flatMap { sentence =>
          sentence
            .get(classOf[CoreAnnotations.MentionsAnnotation]).asScala
            .map { mention =>
              val entity = mention.get(classOf[CoreAnnotations.TextAnnotation])
              val entityType = mention.get(classOf[CoreAnnotations.NamedEntityTagAnnotation])
              Entity(entity, entityType)
            }
        }

    entities
  }

  def extractPageEntities(page: HtrcPage, dehyphenateAtEol: Boolean = true): List[Entity] = {
    val textOptions = if (dehyphenateAtEol) Seq(TrimLines, RemoveEmptyLines, DehyphenateAtEol) else Seq.empty
    val pageText = page.text(textOptions: _*)
    extractTextEntities(pageText)
  }

  def extractVolumeEntities(volume: HtrcVolume,
                            dehyphenateAtEol: Boolean = true,
                            enableMulticore: Boolean = true): Seq[(HtrcPage, List[Entity])] = {
    val pages = if (enableMulticore) volume.pages.par else volume.pages
    pages.map(page => page -> extractPageEntities(page, dehyphenateAtEol)).seq
  }

}
