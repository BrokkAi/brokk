package io.github.jbellis.brokk.analyzer.implicits

import io.github.jbellis.brokk.analyzer.implicits.CpgExt.projectRoot
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.AstNode
import io.shiftleft.semanticcpg.language.*

import scala.io.Source
import scala.util.Using

object AstNodeExt {

  extension (node: AstNode) {

    def sourceCodeFromDisk: Option[String] = {
      node.file.name.headOption.flatMap { filename =>
        val filePath = Cpg(node.graph).projectRoot.resolve(filename).toAbsolutePath.toString
        (node.offset, node.offsetEnd) match {
          case (Some(offset), Some(offsetEnd)) =>
            Using(Source.fromFile(filePath))(
              _.mkString.replace(System.lineSeparator, "\n").slice(offset, offsetEnd)
            ).toOption
          case _ => None
        }
      }
    }

  }

  extension (nodes: IterableOnce[AstNode]) {

    def sourceCodeFromDisk: Iterator[String] = nodes.flatMap(_.sourceCodeFromDisk)

  }

}
