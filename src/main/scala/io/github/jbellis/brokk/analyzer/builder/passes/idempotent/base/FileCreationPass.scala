/*
 * Copyright 2025 The Joern Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Modifications copyright 2025 Brokk, Inc. and made available under the GPLv3.
 *
 * The original file can be found at https://github.com/joernio/joern/blob/3e923e15368e64648e6c5693ac014a2cac83990a/joern-cli/frontends/x2cpg/src/main/scala/io/joern/x2cpg/passes/base/FileCreationPass.scala
 */
package io.github.jbellis.brokk.analyzer.builder.passes.idempotent.base

import io.joern.x2cpg.utils.LinkingUtil
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes, NodeTypes, PropertyNames}
import io.shiftleft.passes.CpgPass
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.types.structure.FileTraversal

import scala.collection.mutable

/** Re-implements [[FileCreationPass]] with only feeding source nodes to the linker that don't already have a
  * `SOURCE_FILE` edge.
  */
class FileCreationPass(cpg: Cpg) extends CpgPass(cpg) with LinkingUtil {

  private val srcLabels = List(NodeTypes.NAMESPACE_BLOCK, NodeTypes.TYPE_DECL, NodeTypes.METHOD, NodeTypes.COMMENT)

  override def run(dstGraph: DiffGraphBuilder): Unit = {
    val originalFileNameToNode = mutable.Map.empty[String, StoredNode]
    val newFileNameToNode      = mutable.Map.empty[String, FileBase]

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
      srcNodes = cpg.graph.nodes(srcLabels*).cast[StoredNode].whereNot(_.out(EdgeTypes.SOURCE_FILE)).toList,
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
