package org.hathitrust.htrc.algorithms.namedentityrecognizer

import java.io.File
import java.net.URL

import org.rogach.scallop.{Scallop, ScallopConf, ScallopHelpFormatter, ScallopOption, SimpleOption}

/**
  * Command line argument configuration
  *
  * @param arguments The cmd line args
  */
class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
  appendDefaultToDescription = true
  helpFormatter = new ScallopHelpFormatter {
    override def getOptionsHelp(s: Scallop): String = {
      super.getOptionsHelp(s.copy(opts = s.opts.map {
        case opt: SimpleOption if !opt.required =>
          opt.copy(descr = "(Optional) " + opt.descr)
        case other => other
      }))
    }
  }

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

  val numPartitions: ScallopOption[Int] = opt[Int]("num-partitions",
    descr = "The number of partitions to split the input set of HT IDs into, " +
      "for increased parallelism",
    argName = "N",
    validate = 0 <
  )

  val numCores: ScallopOption[Int] = opt[Int]("num-cores",
    descr = "The number of CPU cores to use (if not specified, uses all available cores)",
    argName = "N",
    short = 'c',
    validate = 0 <
  )

  val dataApiUrl: ScallopOption[URL] = opt[URL]("dataapi-url",
    descr = "The DataAPI endpoint URL (Note: DATAAPI_TOKEN environment variable must be set)",
    argName = "URL",
    default = Some(new URL("https://dataapi-algo.htrc.indiana.edu/data-api")),
    noshort = true
  )

  val pairtreeRootPath: ScallopOption[File] = opt[File]("pairtree",
    descr = "The path to the pairtree root hierarchy to process",
    argName = "DIR"
  )

  val outputPath: ScallopOption[File] = opt[File]("output",
    descr = "The folder where the output will be written to",
    argName = "DIR",
    required = true
  )

  val language: ScallopOption[String] = opt[String]("language",
    descr = s"""ISO 639-1 language code (supported languages: ${Main.supportedLanguages.mkString(", ")})""",
    argName = "LANG",
    required = true,
    validate = Main.supportedLanguages.contains
  )

  val htids: ScallopOption[File] = trailArg[File]("htids",
    descr = "The HT ids to process (if not provided, will read from stdin)"
  )

  validateFileExists(pairtreeRootPath)
  validateFileExists(htids)
  verify()
}