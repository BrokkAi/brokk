package io.github.jbellis.brokk.analyzer.implicits

import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.passes.{CpgPass, CpgPassBase, ForkJoinParallelCpgPass}
import io.shiftleft.semanticcpg.language.*
import org.slf4j.{Logger, LoggerFactory}

import java.nio.file.{Path, Paths}
import java.util.concurrent.ForkJoinPool
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

/** Provides an entrypoint to incrementally building CPG via implicit methods. The benefit of this type-argument
  * approach is that supported languages are statically verified by the compiler. If an end-user would like to bring
  * incremental builds in for a language not yet supported, the compiler will flag this case.
  */
object CpgExt {

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  extension (cpg: Cpg) {

    /** @return
      *   the root directory of the project where this CPG was generated from. Defaults to the current directory.
      */
    def projectRoot: Path = cpg.metaData.root.headOption.map(Paths.get(_)).getOrElse(Paths.get("."))

    /** A custom version of CpgPass that executes processing using the given ForkJoinPool.
      * @param pass
      *   the pass to execute.
      * @param pool
      *   the pool to execute with.
      * @return
      *   the given CPG.
      */
    def createAndApply(pass: CpgPassBase)(using pool: ForkJoinPool): Cpg = {
      implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
      pass match {
        case simplePass: CpgPass =>
          // CpgPass extends ForkJoinParallelCpgPass but implements it serially
          cpg.applySerialPass(pass)
        case parallelPass: ForkJoinParallelCpgPass[_] =>
          // Manual execution for ForkJoinParallelCpgPass to avoid binary compatibility issues
          cpg.applyParallelPass(parallelPass)
        case pass => cpg.applySerialPass(pass)
      }
      cpg
    }

    private def applySerialPass(pass: CpgPassBase)(using ec: ExecutionContext): Unit = {
      // For non-parallel passes, use createAndApply() with graceful error handling
      try {
        Await.result(
          Future {
            pass.createAndApply()
          },
          Duration.Inf
        )
      } catch {
        case ex: NoSuchMethodError =>
          logger.warn(s"Failed to execute pass ${pass.getClass.getName} due to binary compatibility: ${ex.getMessage}")
      }
    }

    private def applyParallelPass[T <: AnyRef](
      parallelPass: ForkJoinParallelCpgPass[T]
    )(using ec: ExecutionContext): Unit = {
      parallelPass.init()
      val parts = parallelPass.generateParts().toIndexedSeq
      val futurePartBuilders: Seq[Future[flatgraph.DiffGraphBuilder]] = parts.map { part =>
        Future {
          // Each Future gets its own builder
          val localBuilder = Cpg.newDiffGraphBuilder
          parallelPass.runOnPart(localBuilder, part.asInstanceOf[T])
          localBuilder
        }
      }

      val mainBuilder =
        try {
          Await
            .result(Future.sequence(futurePartBuilders), Duration.Inf)
            .foldLeft(Cpg.newDiffGraphBuilder)((a, b) => {
              // Sequentially merge the results from all builders into one
              a.absorb(b)
              a
            })
        } finally {
          parallelPass.finish()
        }

      flatgraph.DiffGraphApplier.applyDiff(cpg.graph, mainBuilder)
    }

  }

}
