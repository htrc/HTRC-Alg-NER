package org.hathitrust.htrc.algorithms.namedentityrecognizer.utils

import java.io.FileNotFoundException
import java.util.Properties

import org.hathitrust.htrc.tools.scala.io.IOUtils._

import scala.util.{Failure, Try}

object Helper {

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
