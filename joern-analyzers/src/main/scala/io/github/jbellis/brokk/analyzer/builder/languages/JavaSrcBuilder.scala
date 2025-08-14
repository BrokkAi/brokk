package io.github.jbellis.brokk.analyzer.builder.languages

import io.github.jbellis.brokk.analyzer.builder.CpgBuilder
import io.joern.javasrc2cpg.passes.{AstCreationPass, OuterClassRefPass, TypeInferencePass}
import io.joern.javasrc2cpg.{JavaSrc2Cpg, Config as JavaSrcConfig}
import io.joern.x2cpg.passes.frontend.{JavaConfigFileCreationPass, TypeNodePass}
import io.shiftleft.codepropertygraph.generated.{Cpg, Languages}

import java.util.concurrent.ForkJoinPool
import scala.jdk.CollectionConverters.*
import scala.util.Try

object JavaSrcBuilder {

  given javaBuilder: CpgBuilder[JavaSrcConfig] with {

    override protected val language: String = "Java"

    override def sourceFileExtensions: Set[String] = JavaSrc2Cpg.sourceFileExtensions

    override def createAst(cpg: Cpg, config: JavaSrcConfig)(using pool: ForkJoinPool): Try[Cpg] = Try {
      createOrUpdateMetaData(cpg, Languages.JAVASRC, config.inputPath)

      // BINARY COMPATIBILITY FIX: Use direct run() calls instead of createAndApply()
      // because Joern's passes expect createAndApply(ForkJoinPool) but our local
      // CpgPassBase interface defines createAndApply() with implicit ForkJoinPool parameter

      val astCreationPass = new AstCreationPass(config, cpg)
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

}
