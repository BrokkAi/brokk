package io.github.jbellis.brokk.analyzer.builder.javasrc

import io.github.jbellis.brokk.analyzer.builder.CpgTestFixture
import io.github.jbellis.brokk.analyzer.builder.languages.given
import io.joern.javasrc2cpg.Config
import io.joern.x2cpg.Defines
import io.shiftleft.codepropertygraph.generated.nodes.{Method, NamespaceBlock, TypeDecl}
import io.shiftleft.semanticcpg.language.*

class SimpleBuildTest extends CpgTestFixture[Config] {

  override protected implicit def defaultConfig: Config = Config()

  "a basic Java program" should {
    withTestConfig { config =>
      val cpg = project(
        config,
        """package com.world.hello;
          |
          |public class Foo {
          | public static void main(String[] args) {
          |   System.out.println("Hello, world!");
          | }
          |}
          |""".stripMargin,
        "com/world/hello/Foo.java"
      ).buildAndOpen

      "create a file node linked to a namespace, type decl, and method node, via SOURCE_FILE" in {
        val file = cpg.file.name(".*Foo.java").head
        file.hash.isDefined shouldBe true
        inside(file._sourceFileIn.toList) {
          case (comWorldHello: NamespaceBlock) :: (foo: TypeDecl) :: (m1: Method) :: (m2: Method) :: Nil =>
            comWorldHello.name shouldBe "com.world.hello"
            foo.name shouldBe "Foo"
            m1.name shouldBe "main"
            m2.name shouldBe Defines.ConstructorMethodName
        }
      }

    }

  }

}
