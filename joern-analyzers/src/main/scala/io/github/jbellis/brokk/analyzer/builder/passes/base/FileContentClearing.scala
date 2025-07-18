package io.github.jbellis.brokk.analyzer.builder.passes.base

import io.shiftleft.semanticcpg.language.*
import io.shiftleft.codepropertygraph.generated.{Cpg, PropertyNames}
import io.shiftleft.passes.CpgPass

/** Clears file content to reduce memory footprint. In many frontends, the `offset` and `offsetEnd` nodes are only
  * populated when the file content feature is enabled in the configuration. However, nodes like [[TypeDecl]] do not
  * include `lineNumberEnd` which makes it difficult to get a precise code snippet of the related code. Thus, we enable
  * file content in the config to enable [[AstNodeExt.sourceCodeFromDisk]] to use offset information, but remove the
  * file content from the nodes.
  * @param cpg
  *   the CPG.
  */
class FileContentClearing(cpg: Cpg) extends CpgPass(cpg) {

  override def run(builder: DiffGraphBuilder): Unit =
    cpg.file.filterNot(_.content.isBlank).foreach(f => builder.setNodeProperty(f, PropertyNames.CONTENT, ""))

}
