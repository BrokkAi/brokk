package io.github.jbellis.brokk.analyzer.builder.passes

import io.github.jbellis.brokk.analyzer.builder.*
import io.github.jbellis.brokk.analyzer.implicits.CpgExt.*
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.passes.ForkJoinParallelCpgPass
import io.shiftleft.semanticcpg.language.*
import org.slf4j.LoggerFactory

import scala.collection.mutable

/**
 * Removes AST nodes associated with deleted or modified files.
 */
private[builder] class RemovedFilePass(cpg: Cpg, changedFiles: Seq[FileChange])
  extends ForkJoinParallelCpgPass[FileChange](cpg) {

  private val logger = LoggerFactory.getLogger(getClass)
  private var pathToFileMap = mutable.Map.empty[String, File]

  override def init(): Unit = {
    val projectRoot = cpg.projectRoot
    cpg.file.foreach { file =>
      val resolvedPath = projectRoot.resolve(file.name).toString
      pathToFileMap.put(resolvedPath, file)
    }
  }

  override def generateParts(): Array[FileChange] = changedFiles.collect {
    case x: RemovedFile => x
    case x: ModifiedFile => x
  }.toArray

  override def runOnPart(builder: DiffGraphBuilder, part: FileChange): Unit = {
    pathToFileMap.get(part.path.toString) match {
      case Some(fileNode) => obtainNodesToDelete(fileNode).foreach(builder.removeNode)
      case None => logger.warn(s"Unable to match ${part.path} in the CPG, this is unexpected.")
    }
  }

  private def obtainNodesToDelete(fileNode: File): Seq[StoredNode] = {
    // io.joern.x2cpg.passes.base.FileCreationPass tells us what we need to know about how File nodes interact
    // with other entities. TLDR: (NAMESPACE_BLOCK | TYPE_DECL | METHOD | COMMENT) -[SOURCE_FILE]-> (FILE)
    val fileChildren = fileNode._sourceFileIn
      .collect { case x: AstNode => x } // All nodes from here inherit the AstNode abstract type
      .flatMap(_.ast)
      .dedup
      .toSeq
    fileNode +: fileChildren
  }
}
