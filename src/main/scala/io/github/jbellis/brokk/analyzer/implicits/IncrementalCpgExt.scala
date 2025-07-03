package io.github.jbellis.brokk.analyzer.implicits

import io.github.jbellis.brokk.analyzer.builder.IncrementalCpgBuilder
import io.joern.x2cpg.X2CpgConfig
import io.shiftleft.codepropertygraph.generated.Cpg


/**
 * Provides an entrypoint to incrementally building CPG via implicit methods. The benefit of this type-argument approach
 * is that supported languages are statically verified by the compiler. If an end-user would like to bring incremental
 * builds in for a language not yet supported, the compiler will flag this case.
 */
object IncrementalCpgExt {

  given cpg: Cpg with {

    def updateWith[R <: X2CpgConfig[R]](config: R)(using builder: IncrementalCpgBuilder[R]): Unit = 
      builder.update(cpg, config)

  }

}
