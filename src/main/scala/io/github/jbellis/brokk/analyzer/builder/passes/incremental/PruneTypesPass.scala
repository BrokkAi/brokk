package io.github.jbellis.brokk.analyzer.builder.passes.incremental

import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.passes.CpgPass
import io.shiftleft.semanticcpg.language.*

/**
 * There may be unused external types once the update is complete, so we prune these away for consistency.
 */
class PruneTypesPass(cpg: Cpg) extends CpgPass(cpg) {

  override def run(diffGraph: DiffGraphBuilder): Unit = {
    cpg.typ.whereNot(
        _.and(
          _.evalTypeIn,
          _.referencedTypeDecl.isExternal(true),
        )
      )
      .flatMap(t => t :: t.referencedTypeDecl.l)
      .foreach(diffGraph.removeNode)
  }

}
