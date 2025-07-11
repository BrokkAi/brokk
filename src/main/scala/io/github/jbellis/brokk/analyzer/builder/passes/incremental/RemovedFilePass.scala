package io.github.jbellis.brokk.analyzer.builder.passes.incremental

import io.github.jbellis.brokk.analyzer.builder.*
import io.github.jbellis.brokk.analyzer.implicits.CpgExt.*
import io.joern.x2cpg.Defines
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.passes.ForkJoinParallelCpgPass
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.types.structure.{FileTraversal, NamespaceTraversal}
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

/** Removes AST nodes associated with deleted or modified files.
  */
private[builder] class RemovedFilePass(cpg: Cpg, changedFiles: Seq[FileChange])
    extends ForkJoinParallelCpgPass[FileChange](cpg) {

  private val logger        = LoggerFactory.getLogger(getClass)
  private val pathToFileMap = mutable.Map.empty[String, File]

  override def init(): Unit = {
    val projectRoot = cpg.projectRoot
    cpg.file.foreach { file =>
      Try(projectRoot.resolve(file.name)).map(_.toString) match {
        case Failure(_) =>
          // Shouldn't be resolved, but should be considered nonetheless
          pathToFileMap.put(s"$projectRoot${java.io.File.separator}${file.name}", file)
        case Success(resolvedPath) => pathToFileMap.put(resolvedPath, file)
      }
    }
  }

  override def generateParts(): Array[FileChange] = {
    val filesToRemove = changedFiles
      .collect {
        case x: RemovedFile  => x
        case x: ModifiedFile => x
      }
      .filterNot(f => isSpecialNodeName(f.name)) // avoid special nodes
      .toArray
    logger.info(s"Removing ${filesToRemove.length} files from the CPG")
    filesToRemove
  }

  override def runOnPart(builder: DiffGraphBuilder, part: FileChange): Unit = {
    logger.debug(s"Removing nodes associated with '${part.fullName}'")
    pathToFileMap.get(part.fullName) match {
      case Some(fileNode) => obtainNodesToDelete(fileNode).foreach(builder.removeNode)
      case None           => logger.warn(s"Unable to match ${part.fullName} in the CPG, this is unexpected.")
    }
  }

  private def isSpecialNodeName(name: String): Boolean = {
    name == NamespaceTraversal.globalNamespaceName ||
    name == Defines.Any ||
    name == File.PropertyDefaults.Name ||
    name == FileTraversal.UNKNOWN
  }

  private def obtainNodesToDelete(fileNode: File): Seq[StoredNode] = {
    // io.joern.x2cpg.passes.base.FileCreationPass tells us what we need to know about how File nodes interact
    // with other entities. TLDR: (NAMESPACE_BLOCK | TYPE_DECL | METHOD | COMMENT) -[SOURCE_FILE]-> (FILE)
    val fileChildren = fileNode._sourceFileIn
      .map {
        // avoid special nodes
        case x: NamespaceBlock if !isSpecialNodeName(x.fullName) => x
        case x: Namespace if !isSpecialNodeName(x.name)          => x
        case x: TypeDecl if !isSpecialNodeName(x.fullName)       => x
        case x: Type if !isSpecialNodeName(x.fullName)           => x
        case other                                               => other
      }
      .cast[AstNode] // All nodes from here inherit the AstNode abstract type
      .flatMap(_.ast)
      .dedup
      .toSeq
    fileNode +: fileChildren
  }
}
