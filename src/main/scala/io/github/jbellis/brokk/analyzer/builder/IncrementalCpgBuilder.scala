package io.github.jbellis.brokk.analyzer.builder

import io.joern.x2cpg.X2CpgConfig
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.semanticcpg.language.*
import io.joern.javasrc2cpg.{Config as JavaSrcConfig}
import io.joern.c2cpg.{Config as CConfig}

import java.nio.file.Path

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
  def update(cpg: Cpg, config: R): Unit

}

object IncrementalCpgBuilder {

  /**
   * Determines which files have been "changed" when compared to the given CPG. This is a reflection of the difference
   * in state between the current project and the last time the CPG was generated.
   *
   * @param cpg         the "old" cpg.
   * @param projectRoot the current project root.
   * @return a sequence of file changes.
   */
  def determineChangedFiles(cpg: Cpg, projectRoot: Path): Seq[FileChange] = {
    cpg.file.map { file =>

    }
    Nil
  }

  given javaBuilder: IncrementalCpgBuilder[JavaSrcConfig] with {

    override def update(cpg: Cpg, config: JavaSrcConfig): Unit = {
      // TODO: Handle
    }

  }

  given cBuilder: IncrementalCpgBuilder[CConfig] with {

    override def update(cpg: Cpg, config: CConfig): Unit = {
      // TODO: Handle
    }

  }

}

