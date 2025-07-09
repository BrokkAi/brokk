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
 * The original file can be found at https://github.com/joernio/joern/blob/3e923e15368e64648e6c5693ac014a2cac83990a/joern-cli/frontends/x2cpg/src/main/scala/io/joern/x2cpg/passes/base/NamespaceCreator.scala
 */
package io.github.jbellis.brokk.analyzer.builder.passes.idempotent.base

import io.shiftleft.codepropertygraph.generated.nodes.NewNamespace
import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes}
import io.shiftleft.passes.CpgPass
import io.shiftleft.semanticcpg.language.*

/** Re-implements [[NamespaceCreator]] and checks if a namespace exists before creating one, and similarly for the `REF`
 * edges.
 */
class NamespaceCreator(cpg: Cpg) extends CpgPass(cpg) {

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
