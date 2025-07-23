package io.github.jbellis.brokk.analyzer.builder.passes.incremental

import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.passes.CpgPass
import io.shiftleft.semanticcpg.language.*
import org.slf4j.LoggerFactory

/** There may be unused external types once the update is complete, so we prune these away for consistency.
  */
class PruneTypesPass(cpg: Cpg) extends CpgPass(cpg) {

  private val logger = LoggerFactory.getLogger(getClass)

  override def run(diffGraph: DiffGraphBuilder): Unit = {
    val typesToPrune = cpg.typ
      .whereNot(_.evalTypeIn) // no usages
      .where(_.referencedTypeDecl.isExternal(true)) // is not a user-defined type, i.e., is external
      .l
    logger.info(s"Found ${typesToPrune.size} types no longer referenced")
    typesToPrune
      .flatMap { t =>
        logger.debug(s"Pruning type ${t.fullName}")
        t :: t.referencedTypeDecl.l
      }
      .foreach(diffGraph.removeNode)
  }

}
