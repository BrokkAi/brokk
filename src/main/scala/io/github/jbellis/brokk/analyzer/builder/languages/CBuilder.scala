package io.github.jbellis.brokk.analyzer.builder.languages

import io.github.jbellis.brokk.analyzer.builder.IncrementalCpgBuilder
import io.joern.c2cpg.Config as CConfig
import io.shiftleft.codepropertygraph.generated.Cpg

object CBuilder {

  given cBuilder: IncrementalCpgBuilder[CConfig] with {

    override def build(cpg: Cpg, config: CConfig): Cpg = {
      // TODO: Handle
      cpg
    }

  }

}
