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
 * The original file can be found at https://github.com/joernio/joern/blob/3e923e15368e64648e6c5693ac014a2cac83990a/joern-cli/frontends/x2cpg/src/main/scala/io/joern/x2cpg/passes/base/ContainsEdgePass.scala
 */
package io.joern.x2cpg.passes.base

import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes, NodeTypes}
import io.shiftleft.passes.ForkJoinParallelCpgPass
import io.shiftleft.semanticcpg.language.*

import scala.collection.mutable

/** Wraps [[ContainsEdgePass]] with a check around the next "part" to see if contains edges are already present.
  */
class ContainsEdgePass(cpg: Cpg) extends ForkJoinParallelCpgPass[AstNode](cpg) {

  import ContainsEdgePass.*

  override def generateParts(): Array[AstNode] =
    cpg.graph.nodes(sourceTypes*).cast[AstNode].toArray

  override def runOnPart(dstGraph: DiffGraphBuilder, source: AstNode): Unit =
    if (source._containsIn.isEmpty && source._containsOut.isEmpty) {
      // AST is assumed to be a tree. If it contains cycles, then this will give a nice endless loop with OOM
      val queue = mutable.ArrayDeque[StoredNode](source)
      while (queue.nonEmpty) {
        val parent = queue.removeHead()
        for (nextNode <- parent._astOut) {
          if (isDestinationType(nextNode)) dstGraph.addEdge(source, nextNode, EdgeTypes.CONTAINS)
          if (!isSourceType(nextNode)) queue.append(nextNode)
        }
      }
    }

}

object ContainsEdgePass {

  private def isSourceType(node: StoredNode): Boolean = node match {
    case _: Method | _: TypeDecl | _: File => true
    case _                                 => false
  }

  private def isDestinationType(node: StoredNode): Boolean = node match {
    case _: Block | _: Identifier | _: FieldIdentifier | _: Return | _: Method | _: TypeDecl | _: Call | _: Literal |
        _: MethodRef | _: TypeRef | _: ControlStructure | _: JumpTarget | _: Unknown | _: TemplateDom =>
      true
    case _ => false
  }

  private val sourceTypes = List(NodeTypes.METHOD, NodeTypes.TYPE_DECL, NodeTypes.FILE)

}
