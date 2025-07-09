package io.github.jbellis.brokk.analyzer.implicits

import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.semanticcpg.language.*

import java.nio.file.{Path, Paths}

/** Provides an entrypoint to incrementally building CPG via implicit methods. The benefit of this type-argument
 * approach is that supported languages are statically verified by the compiler. If an end-user would like to bring
 * incremental builds in for a language not yet supported, the compiler will flag this case.
 */
object CpgExt {

  extension (cpg: Cpg) {

    /** @return
     * the root directory of the project where this CPG was generated from. Defaults to the current directory.
     */
    def projectRoot: Path = cpg.metaData.root.headOption.map(Paths.get(_)).getOrElse(Paths.get("."))

  }

}
