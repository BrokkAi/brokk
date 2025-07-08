package io.github.jbellis.brokk.analyzer.builder

import io.github.jbellis.brokk.analyzer.builder.passes.incremental.RemovedFilePass
import io.github.jbellis.brokk.analyzer.implicits.CpgExt.*
import io.github.jbellis.brokk.analyzer.implicits.PathExt.*
import io.joern.x2cpg.{SourceFiles, X2CpgConfig}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.semanticcpg.language.*
import org.slf4j.LoggerFactory

import java.io.File
import java.nio.file.*
import scala.util.Try

object IncrementalUtils {

  private val logger = LoggerFactory.getLogger(getClass)

  private[brokk] case class PathAndHash(path: String, contents: String)

  /**
   * Determines which files have been "changed" when compared to the given CPG. This is a reflection of the difference
   * in state between the current project and the last time the CPG was generated.
   *
   * @param cpg    the "old" cpg.
   * @param config the current configuration.
   * @return a sequence of file changes.
   */
  def determineChangedFiles[R <: X2CpgConfig[R]](cpg: Cpg, config: R, sourceFileExtensions: Set[String]): Seq[FileChange] = {
    val rootPath = cpg.projectRoot
    val existingFiles = cpg.file.map { file =>
      val path = rootPath.resolve(file.name)
      PathAndHash(path.toString, file.hash.getOrElse(""))
    }.toSeq
    // The below will include files unrelated to project source code, but will be filtered out by the language frontend
    val newFiles = SourceFiles.determine(
        config.inputPath,
        sourceFileExtensions,
        ignoredDefaultRegex = Option(config.defaultIgnoredFilesRegex),
        ignoredFilesRegex = Option(config.ignoredFilesRegex),
        ignoredFilesPath = Option(config.ignoredFiles)
      )
      .map(Paths.get(_))
      .flatMap {
        case dir if Files.isDirectory(dir) => None
        case path => Option(PathAndHash(path.toString, path.sha1))
      }
    determineChangedFiles(existingFiles, newFiles)
  }

  /**
   * Determines the file changes between an existing and a new set of files.
   *
   * @param existingFiles The sequence of files considered as the baseline.
   * @param newFiles      The sequence of files to compare against the baseline.
   * @return A sequence of FileChange objects representing added, removed, or modified files.
   */
  private def determineChangedFiles(existingFiles: Seq[PathAndHash], newFiles: Seq[PathAndHash]): Seq[FileChange] = {
    val existingFilesMap = existingFiles.map(f => f.path -> f.contents).toMap
    val newFilesMap = newFiles.map(f => f.path -> f.contents).toMap

    val allPaths = existingFilesMap.keySet ++ newFilesMap.keySet

    // Iterate over all unique paths and determine the change status for each.
    // flatMap is used to transform and filter in a single pass.
    // It discards `None` values, which represent unchanged files.
    allPaths.flatMap { pathStr =>
      val existingContentOpt = existingFilesMap.get(pathStr)
      val newContentOpt = newFilesMap.get(pathStr)

      val filePath = Path.of(pathStr)

      (existingContentOpt, newContentOpt) match {
        // Modified: Path exists in both, but contents differ.
        case (Some(existingHash), Some(newHash)) if existingHash != newHash => Some(ModifiedFile(filePath))
        // Added: Path exists only in the new files map.
        case (None, Some(_)) => Some(AddedFile(filePath))
        // Removed: Path exists only in the existing files map.
        case (Some(_), None) => Some(RemovedFile(filePath))
        // Unchanged: Path exists in both with identical content, or other invalid states.
        // These are mapped to None and filtered out by flatMap.
        case _ => None
      }
    }.toSeq
  }

  /**
   * Builds a temporary directory of all the files that were newly added.
   *
   * @param projectRoot the project root used to relativize file paths.
   * @param fileChanges all file changes.
   * @return a temporary directory of all the newly added files.
   */
  private def createNewIncrementalBuildDirectory(projectRoot: Path, fileChanges: Seq[FileChange]): Path = {
    val tempDir = Files.createTempDirectory("brokk-incremental-build-")
    val filesToMove = fileChanges.collect {
      case x: AddedFile => x.path
      case x: ModifiedFile => x.path
    }

    logger.info(s"Moving ${filesToMove.size} files to an incremental build directory at '$tempDir'")

    filesToMove.foreach { path =>
      val relativePath = Paths.get(path.toString.stripPrefix(projectRoot.toString).stripPrefix(File.separator))
      val newPath = tempDir.resolve(relativePath)
      val newParentDir = newPath.getParent
      if (!Files.exists(newParentDir)) Files.createDirectories(newParentDir)
      Try(Files.copy(path, newPath)).failed.foreach {
        case _: NoSuchFileException =>
          // this is almost certainly an ephemeral file
          logger.info(s"$relativePath no longer exists at time of CPG update")
        case e => logger.warn(s"Exception encountered while copying $relativePath to incremental build directory at $tempDir", e)
      }
    }

    tempDir
  }

  extension (cpg: Cpg) {

    /**
     * Applies [[RemovedFilePass]] which concurrently deletes all nodes related to removed or modified files.
     *
     * @param fileChanges all file changes.
     * @return this CPG.
     */
    def removeStaleFiles(fileChanges: Seq[FileChange]): Cpg = {
      RemovedFilePass(cpg, fileChanges).createAndApply()
      cpg
    }

    /**
     * Builds the ASTs for new files from a temporary directory.
     *
     * @param fileChanges all file changes.
     * @param astBuilder  builds on top of the CPG defined at the CPG project root.
     * @return the updated CPG.
     */
    def buildAddedAsts(fileChanges: Seq[FileChange], astBuilder: Path => Unit): Cpg = {
      val buildDir = createNewIncrementalBuildDirectory(cpg.projectRoot, fileChanges)
      // We need to ensure this CPG is serialized
      assert(cpg.graph.storagePathMaybe.isDefined, "CPG seems to be in-memory. Expected CPG to have serializable path.")
      try {
        astBuilder(buildDir)
        cpg
      } finally {
        buildDir.deleteRecursively()
      }
    }
  }

}
