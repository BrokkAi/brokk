package io.github.jbellis.brokk.analyzer.builder

import io.github.jbellis.brokk.analyzer.builder.languages.given
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

  }
}
