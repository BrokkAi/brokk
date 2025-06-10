import sbt.*
import sbt.Keys.*
import sbtbuildinfo.BuildInfoPlugin
import sbtbuildinfo.BuildInfoPlugin.autoImport.*

scalaVersion := "3.6.4"
version := "0.9.14"
organization := "io.github.jbellis"
name := "brokk"

val javaVersion = "21"
javacOptions ++= Seq(
  "-source", javaVersion,
  "-target", javaVersion,
  // Reflection-specific flags
  "-parameters",           // Preserve method parameter names
  "-g:source,lines,vars"   // Generate full debugging information
)
scalacOptions ++= Seq(
  "-Xfatal-warnings",
  "-print-lines",
  "-encoding", "UTF-8", // two args, need to go together
  // Reflection-related compiler options
  "-language:reflectiveCalls",
  "-feature",
)

val jlamaVersion = "1.0.0-beta3" // Assuming this matches langchain4j-jlama
// Additional repositories
resolvers ++= Seq(
  "Gradle Libs" at "https://repo.gradle.org/gradle/libs-releases",
  "IntelliJ Releases" at "https://www.jetbrains.com/intellij-repository/releases"
)

libraryDependencies ++= Seq(
  // LangChain4j dependencies
  "dev.langchain4j" % "langchain4j" % "1.0.0-beta3",
  "dev.langchain4j" % "langchain4j-open-ai" % "1.0.0-beta3",
  "com.squareup.okhttp3" % "okhttp" % "4.12.0",

  "com.github.tjake" % "jlama-core" % "0.8.3",

  // Console and logging
  "org.slf4j" % "slf4j-api" % "2.0.16",
  "org.apache.logging.log4j" % "log4j-slf4j2-impl" % "2.20.0",

  // Joern dependencies
  "io.joern" %% "x2cpg" % "4.0.369",
  "io.joern" %% "c2cpg" % "4.0.369",
  "io.joern" %% "javasrc2cpg" % "4.0.369",
  "io.joern" %% "pysrc2cpg" % "4.0.369",
  "io.joern" %% "joern-cli" % "4.0.369",
  "io.joern" %% "semanticcpg" % "4.0.369",
  
  // Utilities
  "com.formdev" % "flatlaf" % "3.6",
  "com.fifesoft" % "rsyntaxtextarea" % "3.5.4",
  "com.fifesoft" % "autocomplete" % "3.3.2",
  "io.github.java-diff-utils" % "java-diff-utils" % "4.15",
  "org.yaml" % "snakeyaml" % "2.3",
  "org.eclipse.jgit" % "org.eclipse.jgit" % "7.2.1.202505142326-r",
  "org.eclipse.jgit" % "org.eclipse.jgit.ssh.apache" % "7.2.1.202505142326-r",
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.18.3",
  "com.vladsch.flexmark" % "flexmark" % "0.64.8",
  "com.vladsch.flexmark" % "flexmark-html2md-converter" % "0.64.8",
  "org.kohsuke" % "github-api" % "1.327",
  "org.jsoup" % "jsoup" % "1.19.1",
  "com.jgoodies" % "jgoodies-forms" % "1.9.0",
  "com.github.spullara.mustache.java" % "compiler" % "0.9.10",

  // TreeSitter Java parser
  "io.github.bonede" % "tree-sitter" % "0.25.3",
  "io.github.bonede" % "tree-sitter-c-sharp" % "0.23.1",
  "io.github.bonede" % "tree-sitter-go" % "0.23.3",
  "io.github.bonede" % "tree-sitter-javascript" % "0.23.1",
  "io.github.bonede" % "tree-sitter-php" % "0.23.11",
  "io.github.bonede" % "tree-sitter-python" % "0.23.4",
  "io.github.bonede" % "tree-sitter-rust" % "0.23.1",
  "io.github.bonede" % "tree-sitter-typescript" % "0.21.1",

  // Testing
  "org.junit.jupiter" % "junit-jupiter" % "5.10.2" % Test,
  "org.junit.jupiter" % "junit-jupiter-engine"  % "5.10.2" % Test,
  "com.github.sbt.junit" % "jupiter-interface"  % "0.13.3" % Test,

  // Java Decompiler
  "com.jetbrains.intellij.java" % "java-decompiler-engine" % "243.25659.59",
)

enablePlugins(BuildInfoPlugin)

buildInfoKeys := Seq[BuildInfoKey](version)
buildInfoPackage := "io.github.jbellis.brokk"
buildInfoObject := "BuildInfo"

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", xs @ _*) =>
    xs.last match {
      case x if x.endsWith(".SF") || x.endsWith(".DSA") || x.endsWith(".RSA") => MergeStrategy.discard
      case "MANIFEST.MF" => MergeStrategy.discard 
      case _ => MergeStrategy.first
    }
  case _ => MergeStrategy.first
}
assembly / mainClass := Some("io.github.jbellis.brokk.Brokk")
Compile / mainClass := Some("io.github.jbellis.brokk.Brokk")

Compile / run / fork := true
javaOptions ++= Seq(
  "-ea",
  "--add-modules=jdk.incubator.vector",
  "-Dbrokk.devmode=true",
  "-Dbrokk.prtab=true",
  "-Dbrokk.issuetab=true"
)

testFrameworks += new TestFramework("com.github.sbt.junit.JupiterFramework")

