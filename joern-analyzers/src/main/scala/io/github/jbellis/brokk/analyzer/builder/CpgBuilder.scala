package io.github.jbellis.brokk.analyzer.builder

import io.github.jbellis.brokk.analyzer.ProjectFile
import io.github.jbellis.brokk.analyzer.builder.IncrementalUtils.*
import io.github.jbellis.brokk.analyzer.builder.passes.base.FileContentClearing
import io.github.jbellis.brokk.analyzer.builder.passes.incremental.{HashFilesPass, PruneTypesPass}
import io.github.jbellis.brokk.analyzer.implicits.CpgExt.*
import io.joern.x2cpg.X2CpgConfig
import io.joern.x2cpg.passes.base.*
import io.joern.x2cpg.passes.callgraph.*
import io.joern.x2cpg.passes.frontend.MetaDataPass
import io.joern.x2cpg.passes.typerelations.*
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.passes.CpgPassBase
import io.shiftleft.semanticcpg.language.*
import org.slf4j.{Logger, LoggerFactory}

import java.io.IOException
import java.nio.file.Paths
import java.util
import scala.util.{Try, Using}

/** A trait to be implemented by a language-specific incremental CPG builder.
  *
  * @tparam R
  *   the language's configuration object.
  */
trait CpgBuilder[R <: X2CpgConfig[R]] {

  protected val logger: Logger = LoggerFactory.getLogger(getClass)

  /** The user-friendly string for the target language.
    */
  protected val language: String

  /** Given an initialised CPG and a configuration object, incrementally build the existing CPG with the changed files
    * at the path determined by the configuration object.
    *
    * @param cpg
    *   the CPG to be built or updated.
    * @param config
    *   the language-specific configuration object containing the input path of source files to re-build from.
    * @param maybeFileChanges
    *   an optional list of specific files to use for the incremental build.
    * @return
    *   the same CPG reference as given.
    */
  def build(cpg: Cpg, config: R, maybeFileChanges: Option[util.Set[ProjectFile]] = None): Cpg = {
    if (cpg.metaData.nonEmpty) {
      if cpg.projectRoot != Paths.get(config.inputPath) then
        logger.warn(
          s"Project root in the CPG (${cpg.projectRoot}) does not match given path in config (${config.inputPath})!"
        )
      val fileChanges = IncrementalUtils.determineChangedFiles(cpg, config, sourceFileExtensions, maybeFileChanges)
      debugChanges(fileChanges)

      cpg
        .removeStaleFiles(fileChanges)
        .buildAddedAsts(fileChanges, buildDir => runPasses(cpg, config.withInputPath(buildDir.toString)))
    } else {
      runPasses(cpg, config)
    }
  }

  /** @return
    *   the source file extensions supported by the language frontend
    */
  def sourceFileExtensions: Set[String]

  private def debugChanges(fileChanges: Seq[FileChange]): Unit = {
    val extensionGroups = fileChanges
      .map { p =>
        val maybeExt = p.name.split('.').lastOption
        maybeExt.getOrElse("<N/A>") -> p
      }
      .groupBy(_._1)
      .map { case (ext, groups) => ext -> groups.map(_._2) }

    val topGroups = extensionGroups
      .sortBy(_._2.size)
      .takeRight(10)
      .toMap
    val otherGroups = extensionGroups.removedAll(topGroups.keySet)

    def changesString(ext: String, changes: Seq[FileChange]): String = {
      val additions     = changes.count(_.isInstanceOf[AddedFile])
      val removals      = changes.count(_.isInstanceOf[RemovedFile])
      val modifications = changes.count(_.isInstanceOf[ModifiedFile])
      s" - .$ext: $additions additions, $removals removals, $modifications modifications"
    }

    val mainChanges = extensionGroups
      .sortBy(_._2.size)
      .takeRight(10)
      .map(changesString)
      .mkString("\n")

    val finalLine = if otherGroups.nonEmpty then {
      val otherChanges = changesString("Other", otherGroups.flatMap(_._2).toSeq)
      s"$mainChanges\n$otherChanges"
    } else {
      mainChanges
    }

    logger.debug(s"All file changes\n$finalLine")
  }

  protected def runPasses(cpg: Cpg, config: R): Cpg = {
    Using.resource(createAst(cpg, config).getOrElse {
      throw new IOException(s"Failed to create $language CPG")
    }) { cpg =>
      applyPasses(cpg).getOrElse {
        throw new IOException(s"Failed to apply post-processing on $language CPG")
      }
    }
  }

  /** Given a CPG will create the meta-data node if none exists. If one exists, we assume that the given input path
    * points to a temporary directory of files that require rebuilding for some incremental update.
    *
    * @param cpg
    *   the CPG.
    * @param language
    *   the target language.
    * @param inputPath
    *   the input path.
    * @return
    *   the given CPG.
    */
  protected def createOrUpdateMetaData(cpg: Cpg, language: String, inputPath: String): Cpg = {
    if cpg.metaData.isEmpty then new MetaDataPass(cpg, language, inputPath).createAndApply()
    cpg
  }

  /** Runs the AST creator for the specific frontend, and any other frontend-specific passes before base overlays are
    * applied.
    *
    * @param cpg
    *   the CPG.
    * @param config
    *   the frontend config.
    * @return
    *   this CPG if successful, an exception if otherwise.
    */
  protected def createAst(cpg: Cpg, config: R): Try[Cpg]

  /** Runs the necessary "base" passes over an existing or new CPG generated by Joern. Think of this as fine-tuned "base
    * passes" that turn an AST to a CPG.
    *
    * @param cpg
    *   some updated or new CPG to apply passes to. This CPG will be mutated.
    * @return
    *   this CPG if successful, an exception if otherwise.
    */
  protected def applyPasses(cpg: Cpg): Try[Cpg] = Try {
    // These are separated as we may want to insert our own custom, framework-specific passes
    // in between these at some point in the future. For now, these resemble the default Joern
    // pass ordering and strategy minus CFG.
    (basePasses(cpg) ++ typeRelationsPasses(cpg) ++ callGraphPasses(cpg) ++ postProcessingPasses(cpg))
      .foreach(_.createAndApply())
    cpg
  }

  protected def basePasses(cpg: Cpg): Iterator[CpgPassBase] = {
    Iterator(
      new FileContentClearing(cpg),
      // Stub creators are moved up as these create nodes that interact with [File|Namespace]CreationPass
      new MethodStubCreator(cpg),
      new TypeDeclStubCreator(cpg),
      new FileCreationPass(cpg),
      new NamespaceCreator(cpg),
      new ParameterIndexCompatPass(cpg),
      new MethodDecoratorPass(cpg),
      new AstLinkerPass(cpg),
      new ContainsEdgePass(cpg),
      new TypeRefPass(cpg),
      new TypeEvalPass(cpg)
    )
  }

  protected def typeRelationsPasses(cpg: Cpg): Iterator[CpgPassBase] = {
    Iterator(new TypeHierarchyPass(cpg), new AliasLinkerPass(cpg), new FieldAccessLinkerPass(cpg))
  }

  protected def callGraphPasses(cpg: Cpg): Iterator[CpgPassBase] = {
    Iterator(new MethodRefLinker(cpg), new StaticCallLinker(cpg), new DynamicCallLinker(cpg))
  }

  protected def postProcessingPasses(cpg: Cpg): Iterator[CpgPassBase] = {
    Iterator(new PruneTypesPass(cpg), new HashFilesPass(cpg))
  }

}
