package io.github.jbellis.brokk.analyzer.builder.javasrc

import io.github.jbellis.brokk.analyzer.builder.{CpgTestFixture, IncrementalBuildTestFixture}
import io.joern.javasrc2cpg.Config

class JavaSrcIncrementalBuildTest extends JavaSrcIncrementalTestFixture {

  "an incremental build from an empty project" in {
    val projectA = emptyProject
    val projectB = project(
      """
        |public class Foo {
        | public static void main(String[] args) {
        |   System.out.println("Hello, world!");
        | }
        |}
        |""".stripMargin, "Foo.java")
    withTestConfig { config =>
      testIncremental(projectA, projectB)
    }
  }

  "an incremental build from a single file change" in {
    val projectA = project(
      """
        |public class Foo {
        | public static void main(String[] args) {
        |   System.out.println("Hello, world!");
        | }
        |}
        |""".stripMargin, "Foo.java")
    val projectB = project(
      """
        |public class Foo {
        | public static void main(String[] args) {
        |   System.out.println("Hello, my incremental world!");
        | }
        |}
        |""".stripMargin, "Foo.java")
    withTestConfig { config =>
      testIncremental(projectA, projectB)
    }
  }

  "an incremental build from a single file change with unchanged files present" in {
    val projectA = project(
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
    val projectB = project(
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
    withTestConfig { config =>
      testIncremental(projectA, projectB)
    }
  }

}

// Simplifies the declaration above
trait JavaSrcIncrementalTestFixture extends CpgTestFixture[Config] with IncrementalBuildTestFixture[Config] {
  override implicit val defaultConfig: Config = Config()
}
