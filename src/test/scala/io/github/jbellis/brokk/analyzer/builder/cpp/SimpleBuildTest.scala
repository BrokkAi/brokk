package io.github.jbellis.brokk.analyzer.builder.cpp

import io.github.jbellis.brokk.analyzer.builder.CpgTestFixture
import io.github.jbellis.brokk.analyzer.builder.languages.given
import io.joern.c2cpg.Config
import io.shiftleft.codepropertygraph.generated.nodes.{Method, NamespaceBlock, TypeDecl}
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.types.structure.NamespaceTraversal

class SimpleBuildTest extends CpgTestFixture[Config] {

  override protected implicit def defaultConfig: Config = Config()

  "a basic C program" should {
    withTestConfig { config =>
      val cpg = project(config,
        """#include <stdio.h>
          |
          |int main() {
          |    printf("Hello, World!\n");
          |    return 0;
          |}
          |""".stripMargin, "Foo.c")
        .buildAndOpen

      "create a file node linked to a namespace, type decl, and method node, via SOURCE_FILE" in {
        val file = cpg.file.nameExact("Foo.c").head
        inside(file._sourceFileIn.toList) { case (global: NamespaceBlock) :: (main: TypeDecl) :: (globalType: TypeDecl) :: (m1: Method) :: (globalMethod: Method) :: Nil =>
          global.name shouldBe NamespaceTraversal.globalNamespaceName
          main.name shouldBe "main"
          globalType.name shouldBe NamespaceTraversal.globalNamespaceName
          m1.name shouldBe "main"
          globalMethod.name shouldBe NamespaceTraversal.globalNamespaceName
        }
      }

    }

  }

}
