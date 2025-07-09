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
 * The original file can be found at https://github.com/joernio/joern/blob/3e923e15368e64648e6c5693ac014a2cac83990a/joern-cli/frontends/x2cpg/src/main/scala/io/joern/x2cpg/passes/callgraph/StaticCallLinker.scala
 */
package io.github.jbellis.brokk.analyzer.builder.passes.idempotent.callgraph

import io.joern.x2cpg.utils.LinkingUtil
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.generated.{Cpg, DispatchTypes, EdgeTypes}
import io.shiftleft.passes.ForkJoinParallelCpgPass
import io.shiftleft.semanticcpg.language.*
import org.slf4j.{Logger, LoggerFactory}

class StaticCallLinker(cpg: Cpg) extends ForkJoinParallelCpgPass[Seq[Call]](cpg) with LinkingUtil {

  private val logger: Logger = LoggerFactory.getLogger(classOf[StaticCallLinker])

  override def generateParts(): Array[Seq[Call]] = {
    cpg.call.toList.grouped(MAX_BATCH_SIZE).toArray
  }

  override def runOnPart(builder: DiffGraphBuilder, calls: Seq[Call]): Unit = {
    calls.foreach { call =>
      try {
        call.dispatchType match {
          case DispatchTypes.STATIC_DISPATCH | DispatchTypes.INLINED =>
            val resolvedMethods =
              cpg.method.fullNameExact(call.methodFullName).filterNot(_.in(EdgeTypes.CALL).contains(call)).l
            resolvedMethods.foreach(dst => builder.addEdge(call, dst, EdgeTypes.CALL))
            val size = resolvedMethods.size
            // Add the debug logs with number of METHOD nodes found for given method full name
            if size > 1 then logger.debug(s"Total $size METHOD nodes found for -> ${call.methodFullName}")
          case DispatchTypes.DYNAMIC_DISPATCH =>
          // Do nothing
          case _ => logger.warn(s"Unknown dispatch type on dynamic CALL ${call.code}")
        }
      } catch {
        case exception: Exception =>
          logger.error(s"Exception in StaticCallLinker: ", exception)
      }
    }
  }
}
