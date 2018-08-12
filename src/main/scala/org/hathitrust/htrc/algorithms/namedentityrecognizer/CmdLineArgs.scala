package org.hathitrust.htrc.algorithms.namedentityrecognizer

import java.io.File
import java.net.URL

import org.rogach.scallop.exceptions.{Help, ScallopException, ScallopResult, Version}
import org.rogach.scallop.{Scallop, ScallopConf, ScallopHelpFormatter, ScallopOption, SimpleOption, throwError}

abstract class InputArg(arguments: Seq[String]) extends ScallopConf(arguments) {
  val htids: ScallopOption[File] = trailArg[File]("htids",
    descr = "The HT ids to process (if not provided, will read from stdin)"
  )

  validateFileExists(htids)
}

/**
  * Command line argument configuration
  *
  * @param arguments The cmd line args
  */
class CmdLineArgs(arguments: Seq[String]) extends InputArg(arguments) {
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
    default = Some(Defaults.DATAAPI_URL),
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

  val keyStore: ScallopOption[File] = opt[File]("keystore",
    descr = "The keystore containing the client certificate to use for DataAPI",
    argName = "FILE"
  )

  val keyStorePwd: ScallopOption[String] = opt[String]("keystore-pwd",
    descr = "The keystore password",
    argName = "PASSWORD"
  )

  val language: ScallopOption[String] = opt[String]("language",
    descr = s"""ISO 639-1 language code (supported languages: ${Main.supportedLanguages.mkString(", ")})""",
    argName = "LANG",
    required = true,
    validate = Main.supportedLanguages.contains
  )

  validateFileExists(pairtreeRootPath)
  validateFileExists(keyStore)
  verify()
}

class ConfigFileArg(arguments: Seq[String]) extends InputArg(arguments) {
  version("not used")

  override protected def onError(e: Throwable): Unit = e match {
    case r: ScallopResult if !throwError.value => r match {
      case Help("") => new CmdLineArgs(Seq("--help"))
      case Version => new CmdLineArgs(Seq("--version"))
      case ScallopException(message) => errorMessageHandler(message)
      case _ =>
    }
    case err => throw err
  }

  val configFile: ScallopOption[File] = opt[File]("config",
    descr = "Configuration file that can be used instead of the command line arguments",
    noshort = true,
    required = true
  )

  validateFileExists(configFile)
  verify()
}