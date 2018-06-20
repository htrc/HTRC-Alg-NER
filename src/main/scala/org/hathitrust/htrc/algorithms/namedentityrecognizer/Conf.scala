package org.hathitrust.htrc.algorithms.namedentityrecognizer

import java.io.File
import java.net.URL

import org.rogach.scallop.{ScallopConf, ScallopOption}

/**
  * Command line argument configuration
  *
  * @param arguments The cmd line args
  */
class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
  val (appTitle, appVersion, appVendor) = {
    val p = getClass.getPackage
    val nameOpt = Option(p).flatMap(p => Option(p.getImplementationTitle))
    val versionOpt = Option(p).flatMap(p => Option(p.getImplementationVersion))
    val vendorOpt = Option(p).flatMap(p => Option(p.getImplementationVendor))
    (nameOpt, versionOpt, vendorOpt)
  }

  version(appTitle.flatMap(
    name => appVersion.flatMap(
      version => appVendor.map(
        vendor => s"$name $version\n$vendor"))).getOrElse(Main.appName))

  val numCores: ScallopOption[Int] = opt[Int]("num-cores",
    descr = "The number of CPU cores to use (if not specified, uses all available cores)",
    short = 'c',
    argName = "N",
    validate = 0 <,
    default = Some(Runtime.getRuntime.availableProcessors())
  )

  val pairtreeRootPath: ScallopOption[File] = opt[File]("pairtree",
    descr = "The path to the pairtree root hierarchy to process",
    argName = "DIR"
  )

  val language: ScallopOption[String] = opt[String]("language",
    descr = s"""ISO 639-1 language code (supported languages: ${Main.supportedLanguages.mkString(", ")})""",
    required = true,
    argName = "LANG",
    validate = Main.supportedLanguages.contains
  )

  val outputPath: ScallopOption[File] = opt[File]("output",
    descr = "Write the output to DIR",
    required = true,
    argName = "DIR"
  )

  val dataApiUrl: ScallopOption[URL] = opt[URL]("dataapi-url",
    descr = "The DataAPI endpoint URL",
    default = Some(new URL("https://dataapi-algo.htrc.indiana.edu/data-api")),
    argName = "URL",
    noshort = true
  )

  val htids: ScallopOption[File] = trailArg[File]("htids",
    descr = "The HT ids to process (if not provided, will read from stdin)",
    required = false
  )

  validateFileExists(pairtreeRootPath)
  validateFileExists(htids)
  verify()
}