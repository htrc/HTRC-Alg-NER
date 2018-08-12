package org.hathitrust.htrc.algorithms.namedentityrecognizer.stanfordnlp

import java.util.Locale

import edu.stanford.nlp.ling.CoreAnnotations
import edu.stanford.nlp.pipeline.{Annotation, StanfordCoreNLP}
import org.hathitrust.htrc.data.ops.TextOptions._
import org.hathitrust.htrc.data.{HtrcPage, HtrcVolume}

import scala.collection.JavaConverters._

object EntityExtractor {
  def apply(locale: Locale): EntityExtractor = NLPInstances.forLocale(locale) match {
    case Some(nlp) => new EntityExtractor(nlp)
    case None => throw EntityExtractorException(s"No language models available for $language")
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
