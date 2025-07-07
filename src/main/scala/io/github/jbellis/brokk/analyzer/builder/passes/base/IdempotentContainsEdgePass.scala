package io.github.jbellis.brokk.analyzer.builder.passes.base

import io.joern.x2cpg.passes.base.ContainsEdgePass
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.AstNode

/**
 * Wraps [[ContainsEdgePass]] with a check around the next "part" to see if contains edges are already present.
 */
class IdempotentContainsEdgePass(cpg: Cpg) extends ContainsEdgePass(cpg) {

  override def runOnPart(dstGraph: DiffGraphBuilder, source: AstNode): Unit =
    if (source._containsIn.isEmpty && source._containsOut.isEmpty) super.runOnPart(dstGraph, source)

}
