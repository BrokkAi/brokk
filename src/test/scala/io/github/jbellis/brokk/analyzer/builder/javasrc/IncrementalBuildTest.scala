package io.github.jbellis.brokk.analyzer.builder.javasrc

import io.github.jbellis.brokk.analyzer.builder.languages.given
import io.github.jbellis.brokk.analyzer.builder.{CpgTestFixture, IncrementalBuildTestFixture}
import io.joern.javasrc2cpg.Config

class IncrementalBuildTest extends CpgTestFixture[Config] with IncrementalBuildTestFixture[Config] {

  override implicit def defaultConfig: Config = Config()

  "an incremental build from an empty project" in {
    withTestConfig { configA =>
      val projectA = emptyProject(configA)
      withTestConfig { configB =>
        val projectB = project(
          configB,
          """
            |public class Foo {
            | public static void main(String[] args) {
            |   System.out.println("Hello, world!");
            | }
            |}
            |""".stripMargin, "Foo.java")
        testIncremental(projectA, projectB)
      }
    }
  }

  "an incremental build from a single file change" in {
    withTestConfig { configA =>
      val projectA = project(configA,
        """
          |public class Foo {
          | public static void main(String[] args) {
          |   System.out.println("Hello, world!");
          | }
          |}
          |""".stripMargin, "Foo.java")
      withTestConfig { configB =>
        val projectB = project(configB,
          """
            |public class Foo {
            | public static void main(String[] args) {
            |   System.out.println("Hello, my incremental world!");
            | }
            |}
            |""".stripMargin, "Foo.java")

        testIncremental(projectA, projectB)
      }
    }
  }

  "an incremental build from a single file change with unchanged files present" in {
    withTestConfig { configA =>
      val projectA = project(configA,
        """
          |public class Foo {
          | public static void main(String[] args) {
          |   System.out.println("Hello, world!");
          | }
          |}
          |""".stripMargin, "Foo.java").moreCode(
        """
          |package test;
          |
          |public class Bar {
          | public int test(int a) {
          |   return 1 + a;
          | }
          |}
          |""".stripMargin, "test/Bar.java")
      withTestConfig { configB =>
        val projectB = project(configB,
          """
            |public class Foo {
            | public static void main(String[] args) {
            |   System.out.println("Hello, my incremental world!");
            | }
            |}
            |""".stripMargin, "Foo.java").moreCode(
          """
            |package test;
            |
            |public class Bar {
            | public int test(int a) {
            |   return 1 + a;
            | }
            |}
            |""".stripMargin, "test/Bar.java")

        testIncremental(projectA, projectB)
      }
    }
  }

}
