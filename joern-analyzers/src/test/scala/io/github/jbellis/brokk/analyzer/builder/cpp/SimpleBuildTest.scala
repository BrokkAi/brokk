package io.github.jbellis.brokk.analyzer.builder.cpp

import io.github.jbellis.brokk.analyzer.builder.CpgTestFixture
import io.github.jbellis.brokk.analyzer.builder.languages.given
import io.joern.c2cpg.Config
import io.shiftleft.codepropertygraph.generated.nodes.{Identifier, Method, NamespaceBlock, TypeDecl}
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.types.structure.NamespaceTraversal

class SimpleBuildTest extends CpgTestFixture[Config] {

  override protected implicit def defaultConfig: Config = Config()

  "a basic C program" should {
    withTestConfig { config =>
      val cpg = project(
        config,
        """#include <stdio.h>
          |
          |int main() {
          |    printf("Hello, World!\n");
          |    return 0;
          |}
          |""".stripMargin,
        "Foo.c"
      ).buildAndOpen

      "create a file node linked to a namespace, type decl, and method node, via SOURCE_FILE" in {
        val file = cpg.file.nameExact("Foo.c").head
        file.hash.isDefined shouldBe true
        inside(file._sourceFileIn.toList) {
          case (global: NamespaceBlock) :: (main: TypeDecl) :: (globalType: TypeDecl) :: (m1: Method) :: (globalMethod: Method) :: Nil =>
            global.name shouldBe NamespaceTraversal.globalNamespaceName
            main.name shouldBe "main"
            globalType.name shouldBe NamespaceTraversal.globalNamespaceName
            m1.name shouldBe "main"
            globalMethod.name shouldBe NamespaceTraversal.globalNamespaceName
        }
      }
    }
  }

  "a C++ pointer parameter" should {
    withTestConfig { config =>
      val cpg = project(
        config,
        """
            |#pragma once
            |#include <stdio.h>
            |#include <string>
            |
            |class Greeter {
            |public:
            |  virtual std::string greet() = 0;
            |  virtual ~Greeter() = default;
            |};
            |
            |int foo(Greeter* greeter) {
            |    std::cout << greeter->greet();
            |    return 0;
            |}
            |""".stripMargin,
        "Foo.cpp"
      ).buildAndOpen

      "evaluate to its underlying type" in {
        val fooMethod = cpg.method("foo").head
        inside(fooMethod.parameter.l) { case greeter :: Nil =>
          greeter.typ.fullName shouldBe "Greeter"
          greeter.typ.referencedTypeDecl.isExternal.head shouldBe false
        }

        inside(fooMethod.call.nameExact("greet").receiver.l) { case (greeterRcv: Identifier) :: Nil =>
          greeterRcv.typ.fullName.head shouldBe "Greeter"
          greeterRcv.typ.referencedTypeDecl.isExternal.head shouldBe false
        }
      }
    }
  }

}
