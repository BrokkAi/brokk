import sbt.*
import sbt.Keys.*
import sbtbuildinfo.BuildInfoPlugin
import sbtbuildinfo.BuildInfoPlugin.autoImport.*
import scala.sys.process._

scalaVersion := "3.6.4"
version := "0.11.1"
organization := "io.github.jbellis"
name := "brokk"

// Add local Error Prone JAR and its dependencies to the compile classpath for javac -Xplugin:ErrorProne
Compile / unmanagedJars ++= Seq(
  baseDirectory.value / "errorprone" / "error_prone_core-brokk_build-with-dependencies.jar",
  baseDirectory.value / "errorprone" / "dataflow-errorprone-3.49.3-eisop1.jar",
  baseDirectory.value / "errorprone" / "nullaway-0.12.7.jar",
  baseDirectory.value / "errorprone" / "dataflow-nullaway-3.49.3.jar",
  baseDirectory.value / "errorprone" / "checker-qual-3.49.3.jar",
)

// also add to javac’s annotation-processor classpath
Compile / javacOptions ++= {
  val pluginJars = Seq(
    baseDirectory.value / "errorprone" / "error_prone_core-brokk_build-with-dependencies.jar",
    baseDirectory.value / "errorprone" / "dataflow-errorprone-3.49.3-eisop1.jar",
    baseDirectory.value / "errorprone" / "nullaway-0.12.7.jar",
    baseDirectory.value / "errorprone" / "dataflow-nullaway-3.49.3.jar",
    baseDirectory.value / "errorprone" / "checker-qual-3.49.3.jar",
  )

  val procPath = pluginJars.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)
  Seq("-processorpath", procPath)
}

val javaVersion = "21"
javacOptions := {
  Seq(
    "--release", javaVersion,
    // Reflection-specific flags
    "-parameters",           // Preserve method parameter names
    "-g:source,lines,vars",  // Generate full debugging information
    // Error Prone configuration
    "-Xmaxerrs", "500",
    "-Xplugin:ErrorProne " +
      "-Xep:FutureReturnValueIgnored:OFF " +
      "-Xep:MissingSummary:OFF " +
      "-Xep:EmptyBlockTag:OFF " +
      "-Xep:NonCanonicalType:OFF " +
      "-Xep:RedundantNullCheck " +
      "-Xep:NullAway:ERROR " +
      "-XepOpt:NullAway:AnnotatedPackages=io.github.jbellis.brokk " +
      "-XepOpt:NullAway:ExcludedFieldAnnotations=org.junit.jupiter.api.BeforeEach,org.junit.jupiter.api.BeforeAll,org.junit.jupiter.api.Test " +
      "-XepOpt:NullAway:ExcludedClassAnnotations=org.junit.jupiter.api.extension.ExtendWith,org.junit.jupiter.api.TestInstance " +
      "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true " +
      "-XepOpt:NullAway:JarInferStrictMode=true " +
      "-XepOpt:NullAway:CheckOptionalEmptiness=true " +
      "-XepOpt:NullAway:KnownInitializers=org.junit.jupiter.api.BeforeEach,org.junit.jupiter.api.BeforeAll " +
      "-XepOpt:NullAway:HandleTestAssertionLibraries=true ",
    "-Werror",
    "-Xlint:deprecation",
    "-Xlint:unchecked",

    // JVM arguments for the forked javac process to run Error Prone on JDK 16+
    // The -J prefix is needed because Compile / javaHome is set, which forks javac.
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
    "-J--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
    "-J--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
    "-J--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
    // Error Prone flags for compilation policy
    "-XDcompilePolicy=simple",
    "--should-stop=ifError=FLOW",
  )
}

// Set javaHome to force forking javac, ensuring -J flags are passed
Compile / javaHome := Some(file(System.getProperty("java.home")))

scalacOptions ++= Seq(
  "-Xfatal-warnings",
  "-print-lines",
  "-encoding", "UTF-8", // two args, need to go together
  // Reflection-related compiler options
  "-language:reflectiveCalls",
  "-feature",
)

val jlamaVersion = "1.0.0-beta3"
// Additional repositories
resolvers ++= Seq(
  "Gradle Libs" at "https://repo.gradle.org/gradle/libs-releases",
  "IntelliJ Releases" at "https://www.jetbrains.com/intellij-repository/releases"
)

// JavaFX - dependencies are determined dynamically based on OS
val javafxVersion = "22"
def osClassifier: String = {
  val archSuffix = System.getProperty("os.arch") match {
    case "aarch64" | "arm64" => "-aarch64"
    case _ => "" // default: x86_64/amd64
  }

  System.getProperty("os.name").toLowerCase match {
    case n if n.startsWith("mac") => s"mac$archSuffix"
    case n if n.startsWith("win") => "win"
    case n if n.startsWith("linux") => s"linux$archSuffix"
    case other => sys.error(s"Unsupported OS: $other")
  }
}

libraryDependencies ++= Seq(
  // NullAway - version must match local jar version
  "com.uber.nullaway" % "nullaway" % "0.12.7",
  
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
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.18.3",
  "org.jspecify" % "jspecify" % "1.0.0",
  "com.vladsch.flexmark" % "flexmark" % "0.64.8",
  "com.vladsch.flexmark" % "flexmark-html2md-converter" % "0.64.8",
  "org.kohsuke" % "github-api" % "1.327",
  "org.jsoup" % "jsoup" % "1.19.1",
  "com.jgoodies" % "jgoodies-forms" % "1.9.0",
  "com.github.spullara.mustache.java" % "compiler" % "0.9.10",
  "org.checkerframework" % "checker-util" % "3.49.3",

  // JGit and SSH
  "org.eclipse.jgit" % "org.eclipse.jgit" % "7.2.1.202505142326-r",
  "org.eclipse.jgit" % "org.eclipse.jgit.ssh.apache" % "7.2.1.202505142326-r",
  "org.bouncycastle" % "bcprov-jdk18on" % "1.80",

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

  // JavaFX
  "org.openjfx" % "javafx-controls" % javafxVersion classifier osClassifier,
  "org.openjfx" % "javafx-web"      % javafxVersion classifier osClassifier,
  "org.openjfx" % "javafx-swing"    % javafxVersion classifier osClassifier,
  "org.openjfx" % "javafx-base"     % javafxVersion classifier osClassifier,
  "org.openjfx" % "javafx-graphics" % javafxVersion classifier osClassifier,
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
  "-Dbrokk.upgradeagenttab=true"
) ++ sys.env.get("UI_SCALE").map { scale =>
  Seq(
    s"-Dsun.java2d.uiScale=$scale",
    "-Dsun.java2d.dpiaware=true"
  )
}.getOrElse(Seq.empty)

testFrameworks += new TestFramework("com.github.sbt.junit.JupiterFramework")
Test / javacOptions := (Compile / javacOptions).value.filterNot(_.contains("-Xplugin:ErrorProne"))
Test / fork := true

// Task to bundle JavaScript with Rollup only when sources change
lazy val bundleJs = taskKey[File]("Bundle UI code with Rollup only when stale")

bundleJs := {
  val log = streams.value.log
  val root = baseDirectory.value
  val frontendDir = root / "frontend-mop"
  val outputDir = root / "src" / "main" / "resources" / "mop-web"
  val bundleFile = outputDir / "bundle.js"
  IO.createDirectory(outputDir)

  // Inputs that trigger rebuild
  val inputs: Seq[File] = 
    (frontendDir / "src" ** "*").get ++
    Seq(
      frontendDir / "package.json",
      frontendDir / "package-lock.json",
      frontendDir / "rollup.config.mjs"
    ).filter(_.exists)

  // Cache directory to store input hashes
  val cacheDir = streams.value.cacheDirectory / "rollup"

  // Cached function to avoid rebuild if inputs haven't changed
  val cachedBuild = FileFunction.cached(cacheDir, inStyle = FilesInfo.hash) { _ =>
    log.info("JavaScript sources changed - rebuilding bundle")
    
    // Ensure dependencies are installed (fast if already present)
    Process("npm ci", frontendDir) ! log
    
    // Run the build
    if ((Process("npm run build", frontendDir) ! log) != 0) {
      sys.error("Rollup build failed")
    }
    
    Set(bundleFile)
  }

  // Execute the cached function (skipped if inputs unchanged)
  cachedBuild(inputs.toSet)
  
  // Safety check
  if (!bundleFile.exists) {
    log.warn("Bundle file not found after build attempt - frontend directory may not be set up")
  }
  
  bundleFile
}

// Add the bundle to resources so it is included in the JAR
Compile / resourceGenerators += Def.task {
  Seq(bundleJs.value)
}.taskValue
