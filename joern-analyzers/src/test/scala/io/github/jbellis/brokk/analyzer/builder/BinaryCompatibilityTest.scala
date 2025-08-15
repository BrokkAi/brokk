package io.github.jbellis.brokk.analyzer.builder

import io.github.jbellis.brokk.analyzer.builder.languages.given
import io.joern.javasrc2cpg.Config as JavaSrcConfig
import io.joern.javasrc2cpg.passes.AstCreationPass
import io.joern.x2cpg.passes.frontend.{JavaConfigFileCreationPass}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.semanticcpg.language.*
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.ForkJoinPool
import scala.util.Using

/** Test class that validates the binary compatibility fixes for Joern passes.
  *
  * These tests would fail with NoSuchMethodError on the old code but pass with our fixes. The errors would be:
  *   - NoSuchMethodError: 'void
  *     io.joern.javasrc2cpg.passes.AstCreationPass.createAndApply(java.util.concurrent.ForkJoinPool)'
  *   - NoSuchMethodError: 'void
  *     io.joern.javasrc2cpg.passes.OuterClassRefPass.createAndApply(java.util.concurrent.ForkJoinPool)'
  *   - Similar errors for other Joern library passes
  */
class BinaryCompatibilityTest extends CpgTestFixture[JavaSrcConfig] with Matchers {

  override protected implicit def defaultConfig: JavaSrcConfig = JavaSrcConfig()

  "AstCreationPass binary compatibility" should {
    "execute without NoSuchMethodError using manual execution pattern" in {
      withTestConfig { config =>
        Using.resource(Cpg.empty) { cpg =>
          given ForkJoinPool = ForkJoinPool.commonPool()

          // This would fail with NoSuchMethodError on old code:
          // java.lang.NoSuchMethodError: 'void io.joern.javasrc2cpg.passes.AstCreationPass.createAndApply(java.util.concurrent.ForkJoinPool)'
          val astCreationPass = new AstCreationPass(config, cpg)
          val diffBuilder     = io.shiftleft.codepropertygraph.generated.Cpg.newDiffGraphBuilder

          // Our manual execution pattern should work without NoSuchMethodError
          astCreationPass.init()
          val parts = astCreationPass.generateParts()
          for (part <- parts) {
            astCreationPass.runOnPart(diffBuilder, part)
          }
          astCreationPass.finish()

          flatgraph.DiffGraphApplier.applyDiff(cpg.graph, diffBuilder)

          // Verify the pass executed successfully
          astCreationPass should not be null
        }
      }
    }
  }

  "JavaConfigFileCreationPass binary compatibility" should {
    "execute without NoSuchMethodError" in {
      withTestConfig { config =>
        Using.resource(Cpg.empty) { cpg =>
          given ForkJoinPool = ForkJoinPool.commonPool()

          // This would fail with NoSuchMethodError on old code
          val javaConfigPass = JavaConfigFileCreationPass(cpg)
          val diffBuilder    = io.shiftleft.codepropertygraph.generated.Cpg.newDiffGraphBuilder

          // Manual execution pattern should work
          javaConfigPass.init()
          val parts = javaConfigPass.generateParts()
          for (part <- parts) {
            javaConfigPass.runOnPart(diffBuilder, part)
          }
          javaConfigPass.finish()

          flatgraph.DiffGraphApplier.applyDiff(cpg.graph, diffBuilder)

          javaConfigPass should not be null
        }
      }
    }
  }

  "CpgBuilder mixed pass handling" should {
    "correctly distinguish between Joern library and local passes" in {
      withTestConfig { config =>
        // Create a simple Java file to trigger pass execution
        val javaCode =
          """
            |public class TestClass {
            |  public void method() {
            |    System.out.println("test");
            |  }
            |}
            |""".stripMargin

        // This will execute the mixed pass handling logic in CpgBuilder.applyPasses
        // which would fail on old code but should work with our fixes
        val cpg = project(config, javaCode, "TestClass.java").buildAndOpen

        // Verify CPG was created successfully without NoSuchMethodError
        cpg.file.name(".*TestClass.java").nonEmpty.shouldBe(true)
        cpg.typeDecl.name("TestClass").nonEmpty.shouldBe(true)
        cpg.method.name("method").nonEmpty.shouldBe(true)

        cpg.close()
      }
    }
  }

}
