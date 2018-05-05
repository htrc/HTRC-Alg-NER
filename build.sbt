showCurrentGitBranch

git.useGitDescribe := true

lazy val commonSettings = Seq(
  organization := "org.hathitrust.htrc",
  organizationName := "HathiTrust Research Center",
  organizationHomepage := Some(url("https://www.hathitrust.org/htrc")),
  scalaVersion := "2.12.6",
  scalacOptions ++= Seq(
    "-feature",
    "-deprecation",
    "-language:postfixOps",
    "-language:implicitConversions"
  ),
  resolvers ++= Seq(
    "I3 Repository" at "http://nexus.htrc.illinois.edu/content/groups/public",
    Resolver.mavenLocal
  ),
  packageOptions in (Compile, packageBin) += Package.ManifestAttributes(
    ("Git-Sha", git.gitHeadCommit.value.getOrElse("N/A")),
    ("Git-Branch", git.gitCurrentBranch.value),
    ("Git-Version", git.gitDescribedVersion.value.getOrElse("N/A")),
    ("Git-Dirty", git.gitUncommittedChanges.value.toString),
    ("Build-Date", new java.util.Date().toString)
  )
)

lazy val `named-entity-recognizer` = (project in file(".")).
  enablePlugins(GitVersioning, GitBranchPrompt, JavaAppPackaging).
  settings(commonSettings).
  settings(
    name := "named-entity-recognizer",
    description := "Performs NER on a HathiTrust workset",
    licenses += "Apache2" -> url("http://www.apache.org/licenses/LICENSE-2.0"),
    libraryDependencies ++= Seq(
      "edu.stanford.nlp"              %  "stanford-corenlp"     % "3.9.1",
      "edu.stanford.nlp"              %  "stanford-corenlp"     % "3.9.1"
        classifier "models"
        classifier "models-chinese"
        classifier "models-english"
        classifier "models-german"
        classifier "models-spanish",
      "eu.fbk.dh"                     %  "tint-runner"          % "1.0-SNAPSHOT"
        excludeAll ExclusionRule(organization = "org.apache.logging.log4j"),
      "org.hathitrust.htrc"           %% "dataapi-client"       % "0.5",
      "org.hathitrust.htrc"           %% "data-model"           % "1.1",
      "org.hathitrust.htrc"           %% "scala-utils"          % "2.2.1",
      "com.nrinaudo"                  %% "kantan.csv"           % "0.4.0",
      "org.rogach"                    %% "scallop"              % "3.1.2",
      "com.gilt"                      %% "gfc-time"             % "0.0.7",
      "ch.qos.logback"                %  "logback-classic"      % "1.2.3",
      "org.scalacheck"                %% "scalacheck"           % "1.14.0"      % Test,
      "org.scalatest"                 %% "scalatest"            % "3.0.5"       % Test
    )
  )
