package io.github.jbellis.brokk.analyzer.builder.languages

import io.github.jbellis.brokk.analyzer.builder.CpgBuilder
import io.joern.javasrc2cpg.passes.{AstCreationPass, OuterClassRefPass, TypeInferencePass}
import io.joern.javasrc2cpg.{JavaSrc2Cpg, Config as JavaSrcConfig}
import io.joern.x2cpg.passes.frontend.{JavaConfigFileCreationPass, TypeNodePass}
import io.shiftleft.codepropertygraph.generated.{Cpg, Languages}

import java.nio.charset.{CodingErrorAction, StandardCharsets}
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

      // BINARY COMPATIBILITY FIX: Use direct run() calls instead of createAndApply()
      // because Joern's passes expect createAndApply(ForkJoinPool) but our local
      // CpgPassBase interface defines createAndApply() with implicit ForkJoinPool parameter

      val astCreationPass = try {
        new AstCreationPass(config, cpg)
      } catch {
        case ex: java.io.UncheckedIOException if ex.getCause.isInstanceOf[java.nio.charset.MalformedInputException] =>
          // Handle malformed input files gracefully by creating a config that skips problematic files
          val filteredConfig = filterMalformedFiles(config)
          new AstCreationPass(filteredConfig, cpg)
        case ex: java.nio.charset.MalformedInputException =>
          // Handle direct MalformedInputException
          val filteredConfig = filterMalformedFiles(config)
          new AstCreationPass(filteredConfig, cpg)
      }
      val diffBuilder = io.shiftleft.codepropertygraph.generated.Cpg.newDiffGraphBuilder

      // Manual execution of pass parts since runWithBuilder has binary compatibility issues
      astCreationPass.init()
      val parts = astCreationPass.generateParts()
      for (part <- parts) {
        astCreationPass.runOnPart(diffBuilder, part)
      }
      astCreationPass.finish()

      flatgraph.DiffGraphApplier.applyDiff(cpg.graph, diffBuilder)

      astCreationPass.sourceParser.cleanupDelombokOutput()
      astCreationPass.clearJavaParserCaches()

      val outerClassRefPass = new OuterClassRefPass(cpg)
      val outerClassDiffBuilder = io.shiftleft.codepropertygraph.generated.Cpg.newDiffGraphBuilder

      // Manual execution for binary compatibility
      outerClassRefPass.init()
      val outerClassParts = outerClassRefPass.generateParts()
      for (part <- outerClassParts) {
        outerClassRefPass.runOnPart(outerClassDiffBuilder, part)
      }
      outerClassRefPass.finish()

      flatgraph.DiffGraphApplier.applyDiff(cpg.graph, outerClassDiffBuilder)

      val javaConfigPass = JavaConfigFileCreationPass(cpg)
      val javaConfigDiffBuilder = io.shiftleft.codepropertygraph.generated.Cpg.newDiffGraphBuilder

      // Manual execution for binary compatibility
      javaConfigPass.init()
      val javaConfigParts = javaConfigPass.generateParts()
      for (part <- javaConfigParts) {
        javaConfigPass.runOnPart(javaConfigDiffBuilder, part)
      }
      javaConfigPass.finish()

      flatgraph.DiffGraphApplier.applyDiff(cpg.graph, javaConfigDiffBuilder)

      if (!config.skipTypeInfPass) {
        val typeNodePass = TypeNodePass
          .withRegisteredTypes(astCreationPass.global.usedTypes.keys().asScala.toList, cpg)
        val typeNodeDiffBuilder = io.shiftleft.codepropertygraph.generated.Cpg.newDiffGraphBuilder

        // Manual execution for binary compatibility
        typeNodePass.init()
        val typeNodeParts = typeNodePass.generateParts()
        for (part <- typeNodeParts) {
          typeNodePass.runOnPart(typeNodeDiffBuilder, part)
        }
        typeNodePass.finish()

        flatgraph.DiffGraphApplier.applyDiff(cpg.graph, typeNodeDiffBuilder)

        val typeInferencePass = new TypeInferencePass(cpg)
        val typeInferenceDiffBuilder = io.shiftleft.codepropertygraph.generated.Cpg.newDiffGraphBuilder

        // Manual execution for binary compatibility
        typeInferencePass.init()
        val typeInferenceParts = typeInferencePass.generateParts()
        for (part <- typeInferenceParts) {
          typeInferencePass.runOnPart(typeInferenceDiffBuilder, part)
        }
        typeInferencePass.finish()

        flatgraph.DiffGraphApplier.applyDiff(cpg.graph, typeInferenceDiffBuilder)
      }
      cpg
    }

  }

  /** Creates a filtered config that excludes files with malformed UTF-8 encoding
    * @param config original configuration
    * @return new configuration with problematic files filtered out
    */
  private def filterMalformedFiles(config: JavaSrcConfig): JavaSrcConfig = {
    val inputPath = Paths.get(config.inputPath)

    // Create a temporary directory to hold validated source files
    val tempDir = Files.createTempDirectory("brokk-filtered-java-")

    try {
      // Find all Java source files and validate their encoding
      val validFiles = Files.walk(inputPath)
        .filter(Files.isRegularFile(_))
        .filter(path => {
          val name = path.getFileName.toString.toLowerCase
          name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".scala")
        })
        .filter(isValidUtf8File)
        .toList.asScala.toList

      // Copy valid files to temp directory maintaining structure
      for (file <- validFiles) {
        val relativePath = inputPath.relativize(file)
        val targetPath = tempDir.resolve(relativePath)
        Files.createDirectories(targetPath.getParent)
        Files.copy(file, targetPath)
      }

      // Return config pointing to the filtered directory
      config.withInputPath(tempDir.toString)
    } catch {
      case ex: Exception =>
        // If filtering fails, log and return original config
        System.err.println(s"Warning: Failed to filter malformed files, proceeding with original config: ${ex.getMessage}")
        config
    }
  }

  /** Checks if a file can be read as valid UTF-8
    * @param path the file path to check
    * @return true if the file is valid UTF-8, false otherwise
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
      case _: java.io.UncheckedIOException => false
      case _: Exception => false // Other IO issues
    }
  }

}
