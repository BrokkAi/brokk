package io.github.jbellis.brokk.analyzer.builder.languages

import io.github.jbellis.brokk.analyzer.builder.CpgBuilder
import io.github.jbellis.brokk.analyzer.implicits.CpgExt.*
import io.joern.javasrc2cpg.passes.{AstCreationPass, OuterClassRefPass, TypeInferencePass}
import io.joern.javasrc2cpg.{JavaSrc2Cpg, Config as JavaSrcConfig}
import io.joern.x2cpg.passes.frontend.{JavaConfigFileCreationPass, TypeNodePass}
import io.shiftleft.codepropertygraph.generated.{Cpg, Languages}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.ForkJoinPool
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

object JavaSrcBuilder {

  given javaBuilder: CpgBuilder[JavaSrcConfig] with {

    override protected val language: String = "Java"

    override def sourceFileExtensions: Set[String] = JavaSrc2Cpg.sourceFileExtensions

    override def createAst(cpg: Cpg, config: JavaSrcConfig)(using pool: ForkJoinPool): Try[Cpg] = Try {
      createOrUpdateMetaData(cpg, Languages.JAVASRC, config.inputPath)

      // Binary Compatibility fix: Use manual execution for Java AstCreationPass
      val astCreationPass = new AstCreationPass(config, cpg)
      cpg.createAndApply(astCreationPass)
      astCreationPass.sourceParser.cleanupDelombokOutput()
      astCreationPass.clearJavaParserCaches()

      List(new OuterClassRefPass(cpg), JavaConfigFileCreationPass(cpg)).foreach(cpg.createAndApply)
      if !config.skipTypeInfPass then
        List(
          TypeNodePass.withRegisteredTypes(astCreationPass.global.usedTypes.keys().asScala.toList, cpg),
          new TypeInferencePass(cpg)
        ).foreach(cpg.createAndApply)

      cpg
    }

  }

  /** Creates a filtered config that excludes files with malformed UTF-8 encoding
    * @param config
    *   original configuration
    * @return
    *   new configuration with problematic files filtered out
    */
  private def filterMalformedFiles(config: JavaSrcConfig): JavaSrcConfig = {
    val inputPath = Paths.get(config.inputPath)

    // Create a temporary directory to hold validated source files
    val tempDir = Files.createTempDirectory("brokk-filtered-java-")

    try {
      // Find all Java source files and validate their encoding
      val validFiles = Files
        .walk(inputPath)
        .filter(Files.isRegularFile(_))
        .filter(path => {
          val name = path.getFileName.toString.toLowerCase
          name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".scala")
        })
        .filter(isValidUtf8File)
        .toList
        .asScala
        .toList

      // Copy valid files to temp directory maintaining structure
      for (file <- validFiles) {
        val relativePath = inputPath.relativize(file)
        val targetPath   = tempDir.resolve(relativePath)
        Files.createDirectories(targetPath.getParent)
        Files.copy(file, targetPath)
      }

      // Return config pointing to the filtered directory
      config.withInputPath(tempDir.toString)
    } catch {
      case ex: Exception =>
        // If filtering fails, log and return original config
        System.err.println(
          s"Warning: Failed to filter malformed files, proceeding with original config: ${ex.getMessage}"
        )
        config
    }
  }

  /** Checks if a file can be read as valid UTF-8
    * @param path
    *   the file path to check
    * @return
    *   true if the file is valid UTF-8, false otherwise
    */
  private def isValidUtf8File(path: Path): Boolean = {
    try {
      Using.resource(Files.newBufferedReader(path, StandardCharsets.UTF_8)) { reader =>
        var line = reader.readLine()
        while (line != null) {
          line = reader.readLine()
        }
        true
      }
    } catch {
      case _: java.nio.charset.MalformedInputException => false
      case _: java.io.UncheckedIOException             => false
      case _: Exception                                => false // Other IO issues
    }
  }

}
