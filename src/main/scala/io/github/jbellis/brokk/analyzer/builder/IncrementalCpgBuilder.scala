package io.github.jbellis.brokk.analyzer.builder

import io.github.jbellis.brokk.analyzer.JavaAnalyzer
import io.github.jbellis.brokk.analyzer.builder.passes.RemovedFilePass
import io.github.jbellis.brokk.analyzer.implicits.CpgExt.*
import io.github.jbellis.brokk.analyzer.implicits.PathExt.*
import io.joern.c2cpg.Config as CConfig
import io.joern.javasrc2cpg.Config as JavaSrcConfig
import io.joern.x2cpg.X2CpgConfig
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.semanticcpg.language.*

import java.nio.file.{FileVisitOption, Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * A trait to be implemented by a language-specific incremental CPG builder.
 *
 * @tparam R the language's configuration object.
 */
trait IncrementalCpgBuilder[R <: X2CpgConfig[R]] {

  /**
   * Given a CPG and a configuration object, incrementally update the existing CPG with the changed files at the path
   * determined by the configuration object.
   *
   * @param cpg    the CPG to be updated.
   * @param config the langugage-specific configuration object containing the input path of source files to re-build
   *               from.
   */
  def update(cpg: Cpg, config: R): Cpg

}

object IncrementalCpgBuilder {

  private[brokk] case class PathAndHash(path: String, contents: String)

  /**
   * Determines which files have been "changed" when compared to the given CPG. This is a reflection of the difference
   * in state between the current project and the last time the CPG was generated.
   *
   * @param cpg         the "old" cpg.
   * @param projectRoot the current project root.
   * @return a sequence of file changes.
   */
  def determineChangedFiles(cpg: Cpg, projectRoot: Path): Seq[FileChange] = {
    val rootPath = cpg.metaData.root.headOption.map(Paths.get(_)).getOrElse(Paths.get(".")) // default to relative
    val existingFiles = cpg.file.map { file =>
      val path = rootPath.resolve(file.name)
      PathAndHash(rootPath.resolve(file.name).toString, file.hash.getOrElse(""))
    }.toSeq
    // The below will include files unrelated to project source code, but will be filtered out by the language frontend
    val newFiles = Files.walk(projectRoot, FileVisitOption.FOLLOW_LINKS)
      .toList
      .asScala
      .flatMap {
        case dir if Files.isDirectory(dir) => None
        case path => Option(PathAndHash(path.toString, path.sha1))
      }.toSeq
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

    fileChanges.collect { case x: AddedFile => x }.foreach { case AddedFile(path) =>
      val newPath = Paths.get(path.toString.stripSuffix(projectRoot.toString))
      if (!Files.exists(newPath.getParent)) Files.createDirectories(newPath)
      Files.copy(path, newPath)
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
     * @return this CPG.
     */
    def buildAddedAsts(fileChanges: Seq[FileChange], astBuilder: (Path) => Unit): Cpg = {
      val buildDir = createNewIncrementalBuildDirectory(cpg.projectRoot, fileChanges)
      try {
        astBuilder(buildDir)
      } finally {
        buildDir.deleteRecursively
      }
      cpg
    }

  }

  given javaBuilder: IncrementalCpgBuilder[JavaSrcConfig] with {

    override def update(cpg: Cpg, config: JavaSrcConfig): Cpg = {
      val fileChanges = determineChangedFiles(cpg, Paths.get(config.inputPath))
      cpg.removeStaleFiles(fileChanges)
      cpg.buildAddedAsts(fileChanges, (buildDir) => build(config.withInputPath(buildDir.toString)))
    }

    private def build(config: JavaSrcConfig) = {
      JavaAnalyzer.createAst(config)
    }

  }

  given cBuilder: IncrementalCpgBuilder[CConfig] with {

    override def update(cpg: Cpg, config: CConfig): Cpg = {
      // TODO: Handle
      Cpg.empty
    }

  }

}

