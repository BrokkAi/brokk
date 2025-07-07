package io.github.jbellis.brokk.analyzer.builder.passes.base

import io.joern.x2cpg.passes.base.FileCreationPass
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes, NodeTypes, PropertyNames}
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.types.structure.FileTraversal

import scala.collection.mutable

/**
 * Re-implements [[FileCreationPass]] with only feeding source nodes to the linker that don't already have a
 * `SOURCE_FILE` edge.
 */
class IdempotentFileCreationPass(cpg: Cpg) extends FileCreationPass(cpg) {

  private val srcLabels = List(NodeTypes.NAMESPACE_BLOCK, NodeTypes.TYPE_DECL, NodeTypes.METHOD, NodeTypes.COMMENT)

  override def run(dstGraph: DiffGraphBuilder): Unit = {
    val originalFileNameToNode = mutable.Map.empty[String, StoredNode]
    val newFileNameToNode = mutable.Map.empty[String, FileBase]

    cpg.file.foreach { node =>
      originalFileNameToNode += node.name -> node
    }

    def createFileIfDoesNotExist(srcNode: StoredNode, destFullName: String): Unit = {
      if (destFullName != File.PropertyDefaults.Name) {
        val dstFullName = if (destFullName == "") {
          FileTraversal.UNKNOWN
        } else {
          destFullName
        }
        val newFile = newFileNameToNode.getOrElseUpdate(
          dstFullName, {
            val file = NewFile().name(dstFullName).order(0)
            dstGraph.addNode(file)
            file
          }
        )
        dstGraph.addEdge(srcNode, newFile, EdgeTypes.SOURCE_FILE)
      }
    }

    // Create SOURCE_FILE edges from nodes of various types to FILE
    linkToSingle(
      cpg,
      srcNodes = cpg.graph.nodes(srcLabels *).cast[StoredNode].whereNot(_.out(EdgeTypes.SOURCE_FILE)).toList,
      srcLabels = srcLabels,
      dstNodeLabel = NodeTypes.FILE,
      edgeType = EdgeTypes.SOURCE_FILE,
      dstNodeMap = { x =>
        originalFileNameToNode.get(x)
      },
      dstFullNameKey = PropertyNames.FILENAME,
      dstDefaultPropertyValue = File.PropertyDefaults.Name,
      dstGraph,
      Some(createFileIfDoesNotExist)
    )
  }

}
