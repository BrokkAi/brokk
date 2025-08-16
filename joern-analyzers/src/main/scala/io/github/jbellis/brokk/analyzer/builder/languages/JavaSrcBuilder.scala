package io.github.jbellis.brokk.analyzer.builder.languages

import io.github.jbellis.brokk.analyzer.builder.CpgBuilder
import io.github.jbellis.brokk.analyzer.implicits.CpgExt.*
import io.joern.javasrc2cpg.passes.{AstCreationPass, OuterClassRefPass, TypeInferencePass}
import io.joern.javasrc2cpg.{JavaSrc2Cpg, Config as JavaSrcConfig}
import io.joern.x2cpg.passes.frontend.{JavaConfigFileCreationPass, TypeNodePass}
import io.shiftleft.codepropertygraph.generated.{Cpg, Languages}
import org.slf4j.LoggerFactory

import java.util.concurrent.ForkJoinPool
import scala.jdk.CollectionConverters.*
import scala.util.Try

object JavaSrcBuilder {

  private val logger = LoggerFactory.getLogger(getClass)

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
    }.recover { ex =>
      ex.getCause match {
        case malformedException: java.nio.charset.MalformedInputException =>
          JavaSrcBuilder.logger.error(s"Failed to create Java CPG due to malformed input: ${malformedException.getMessage}")
          JavaSrcBuilder.logger.error("This is likely caused by files with invalid UTF-8 encoding. Please check for empty or corrupted Java files.")
          throw new RuntimeException(s"Java CPG creation failed due to malformed input: ${malformedException.getMessage}", ex)
        case _ =>
          throw ex
      }
    }

  }

}