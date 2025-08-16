package io.github.jbellis.brokk.analyzer.builder

import io.github.jbellis.brokk.analyzer.builder.languages.given
import io.github.jbellis.brokk.analyzer.implicits.X2CpgConfigExt.*
import io.joern.javasrc2cpg.Config as JavaSrcConfig
import io.shiftleft.semanticcpg.language.*
import org.scalatest.matchers.should.Matchers

/** End-to-end integration test that simulates the shadowJar scenario.
  *
  * This test validates that the full Java CPG creation pipeline works without NoSuchMethodError exceptions that were
  * occurring in shadowJar deployments.
  *
  * The test simulates real-world usage scenarios that would fail on the old code but should succeed with our binary
  * compatibility fixes.
  */
class JavaCpgIntegrationTest extends CpgTestFixture[JavaSrcConfig] with Matchers {

  override protected implicit def defaultConfig: JavaSrcConfig = JavaSrcConfig()

  "Full Java CPG creation pipeline" should {

    "complete successfully without NoSuchMethodError for simple Java project" in {
      withTestConfig { config =>
        val javaCode =
          """
            |public class TestClass {
            |  private String field;
            |
            |  public TestClass() {
            |    this.field = "test";
            |  }
            |
            |  public void method() {
            |    System.out.println(field);
            |  }
            |
            |  class InnerClass {
            |    void innerMethod() {
            |      TestClass.this.method();
            |    }
            |  }
            |}
            |""".stripMargin

        // This full pipeline would fail with NoSuchMethodError on old code
        // The failure would occur in JavaSrcBuilder.createAst when it tries to call
        // createAndApply() on Joern library passes
        val cpg = project(config, javaCode, "TestClass.java").buildAndOpen

        // Verify CPG was created successfully
        cpg.file.name(".*TestClass.java").nonEmpty.shouldBe(true)
        cpg.typeDecl.name("TestClass").nonEmpty.shouldBe(true)
        cpg.method.name("method").nonEmpty.shouldBe(true)

        // Verify inner class was processed (tests OuterClassRefPass)
        // Inner classes are represented as "OuterClass$InnerClass" in the CPG
        cpg.typeDecl.name.toList.exists(_.contains("InnerClass")).shouldBe(true)
        cpg.method.name("innerMethod").nonEmpty.shouldBe(true)

        cpg.close()
      }
    }

    "handle malformed UTF-8 files gracefully" in {
      withTestConfig { config =>
        // Create a project with a normal Java file
        val normalJavaCode =
          """
            |public class NormalClass {
            |  public void normalMethod() {
            |    System.out.println("Hello World");
            |  }
            |}
            |""".stripMargin

        // Create the project with normal files and write them
        val tempProject = project(config, normalJavaCode, "NormalClass.java")
        tempProject.writeFiles

        // Create a file with malformed UTF-8 content directly in the input directory
        val inputPath = java.nio.file.Paths.get(config.inputPath)
        val malformedFile = inputPath.resolve("MalformedClass.java")
        // Create a file with invalid UTF-8 sequence
        java.nio.file.Files.write(malformedFile, Array[Byte](0xFF.toByte, 0xFE.toByte, 0x00.toByte, 0x00.toByte))

        // This should handle the malformed file gracefully - either succeed by ignoring it
        // or fail with a proper error message (not a raw MalformedInputException)
        try {
          config.buildAndThrow()
          val cpg = config.open
          // If it succeeds, that's fine - it means the malformed file was handled gracefully
          cpg.close()
          succeed
        } catch {
          case ex: RuntimeException if ex.getMessage != null && ex.getMessage.contains("malformed input") =>
            // This is the expected graceful error handling
            succeed
          case ex: java.nio.charset.MalformedInputException =>
            // This would be a failure of our error handling - we shouldn't get raw MalformedInputException
            fail(s"Raw MalformedInputException should be caught and wrapped: ${ex.getMessage}")
          case ex: Exception =>
            // Any other exception should contain our error message
            if (ex.getMessage != null && ex.getMessage.contains("malformed input")) {
              succeed
            } else {
              fail(s"Unexpected exception without proper error handling: ${ex.getClass.getSimpleName}: ${ex.getMessage}")
            }
        }
      }
    }

  }
}
