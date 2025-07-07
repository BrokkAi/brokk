package io.github.jbellis.brokk.analyzer.builder.passes.base

import io.joern.x2cpg.passes.base.NamespaceCreator
import io.shiftleft.codepropertygraph.generated.nodes.NewNamespace
import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes}
import io.shiftleft.semanticcpg.language.*

/**
 * Re-implements [[NamespaceCreator]] and checks if a namespace exists before creating one, and similarly for the
 * `REF` edges.
 */
class IdempotentNamespaceCreator(cpg: Cpg) extends NamespaceCreator(cpg) {

  override def run(dstGraph: DiffGraphBuilder): Unit = {
    cpg.namespaceBlock
      .groupBy(_.name)
      .foreach { case (name: String, blocks) =>
        cpg.namespace.nameExact(name).headOption match {
          case Some(namespace) =>
            blocks
              .whereNot(_.namespace.name(name))
              .foreach(block => dstGraph.addEdge(block, namespace, EdgeTypes.REF))
          case None =>
            val namespace = NewNamespace().name(name)
            dstGraph.addNode(namespace)
            blocks.foreach(block => dstGraph.addEdge(block, namespace, EdgeTypes.REF))
        }

      }
  }

}
