package io.github.jbellis.brokk.analyzer.builder.languages

import io.github.jbellis.brokk.analyzer.builder.CpgBuilder
import io.github.jbellis.brokk.analyzer.builder.passes.idempotent
import io.joern.javasrc2cpg.Config as JavaSrcConfig
import io.joern.javasrc2cpg.passes.{AstCreationPass, OuterClassRefPass, TypeInferencePass}
import io.joern.x2cpg.passes.frontend.JavaConfigFileCreationPass
import io.shiftleft.codepropertygraph.generated.{Cpg, Languages}

import scala.jdk.CollectionConverters.*
import scala.util.Try

object JavaSrcBuilder {

  given javaBuilder: CpgBuilder[JavaSrcConfig] with {

    override protected val language: String = "Java"

    override def createAst(cpg: Cpg, config: JavaSrcConfig): Try[Cpg] = Try {
      createOrUpdateMetaData(cpg, Languages.JAVASRC, config.inputPath)
      val astCreationPass = new AstCreationPass(config, cpg)
      astCreationPass.createAndApply()
      astCreationPass.sourceParser.cleanupDelombokOutput()
      astCreationPass.clearJavaParserCaches()
      new OuterClassRefPass(cpg).createAndApply()
      JavaConfigFileCreationPass(cpg).createAndApply()
      if (!config.skipTypeInfPass) {
        idempotent.frontend.TypeNodePass.withRegisteredTypes(astCreationPass.global.usedTypes.keys().asScala.toList, cpg).createAndApply()
        new TypeInferencePass(cpg).createAndApply()
      }
      cpg
    }

  }

}
