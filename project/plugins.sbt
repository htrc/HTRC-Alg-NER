logLevel := Level.Warn

addSbtPlugin("com.typesafe.sbt"       % "sbt-git"             % "1.0.0")
addSbtPlugin("com.typesafe.sbt"       % "sbt-native-packager" % "1.3.15")  // later versions broken up to 1.3.18 (incl)
addSbtPlugin("com.eed3si9n"           % "sbt-assembly"        % "0.14.9")
addSbtPlugin("org.wartremover"        % "sbt-wartremover"     % "2.4.1")
addSbtPlugin("com.jsuereth"           % "sbt-pgp"             % "2.0.0-M2")
