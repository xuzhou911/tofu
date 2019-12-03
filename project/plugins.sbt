logLevel := Level.Warn

addSbtPlugin("com.typesafe.sbt" % "sbt-git"      % "1.0.0")
addSbtPlugin("ch.epfl.scala"    % "sbt-scalafix" % "0.9.5")
addSbtPlugin("org.xerial.sbt"   % "sbt-sonatype" % "3.8.1")
addSbtPlugin("com.jsuereth"     % "sbt-pgp"      % "1.1.1")
addSbtPlugin("org.scalameta"    % "sbt-mdoc"     % "2.0.2")
addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.4.2")