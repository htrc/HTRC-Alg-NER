package org.hathitrust.htrc.algorithms.namedentityrecognizer.stanfordnlp

case class EntityExtractorException(message: String, throwable: Throwable = null)
  extends Exception(message, throwable)
