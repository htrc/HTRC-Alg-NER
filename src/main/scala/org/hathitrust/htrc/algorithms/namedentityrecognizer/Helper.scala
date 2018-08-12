package org.hathitrust.htrc.algorithms.namedentityrecognizer

import java.io._
import java.util.Properties

import org.hathitrust.htrc.tools.scala.io.IOUtils.using
import org.slf4j.{Logger, LoggerFactory}

import scala.language.reflectiveCalls
import scala.util.{Failure, Try}

object Helper {
  @transient lazy val logger: Logger = LoggerFactory.getLogger(Main.appName)

  def loadPropertiesFromClasspath(path: String): Try[Properties] = {
    require(path != null && path.nonEmpty)

    Option(getClass.getResourceAsStream(path))
      .map(using(_) { is =>
        Try {
          val props = new Properties()
          props.load(is)
          props
        }
      })
      .getOrElse(Failure(new FileNotFoundException(s"$path not found")))
  }
}
