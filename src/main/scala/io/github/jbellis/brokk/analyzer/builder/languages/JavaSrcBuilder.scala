package io.github.jbellis.brokk.analyzer.builder.languages

import io.github.jbellis.brokk.analyzer.JavaAnalyzer
import io.github.jbellis.brokk.analyzer.builder.IncrementalCpgBuilder
import io.github.jbellis.brokk.analyzer.builder.IncrementalCpgBuilder.*
import io.joern.javasrc2cpg.Config as JavaSrcConfig
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.semanticcpg.language.*

import java.io.IOException
import java.nio.file.Paths
import scala.util.Using

object JavaSrcBuilder {

  given javaBuilder: IncrementalCpgBuilder[JavaSrcConfig] with {

    override def build(cpg: Cpg, config: JavaSrcConfig): Cpg = {
      if (cpg.metaData.nonEmpty) {
        val fileChanges = IncrementalCpgBuilder.determineChangedFiles(cpg, Paths.get(config.inputPath))
        cpg.removeStaleFiles(fileChanges)
        cpg.buildAddedAsts(fileChanges, buildDir => buildCpgFromConfig(cpg, config.withInputPath(buildDir.toString)))
      } else {
        buildCpgFromConfig(cpg, config)
      }
    }

    private def buildCpgFromConfig(cpg: Cpg, config: JavaSrcConfig): Cpg = {
      Using.resource(JavaAnalyzer.createAst(cpg, config).getOrElse {
        throw new IOException("Failed to create Java CPG")
      }) { cpg =>
        JavaAnalyzer.applyPasses(cpg).getOrElse {
          throw new IOException("Failed to apply post-processing on Java CPG")
        }
      }
    }

  }

}
