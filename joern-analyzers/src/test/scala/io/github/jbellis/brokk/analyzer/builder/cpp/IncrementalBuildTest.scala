package io.github.jbellis.brokk.analyzer.builder.cpp

import io.github.jbellis.brokk.analyzer.ProjectFile
import io.github.jbellis.brokk.analyzer.builder.languages.given
import io.github.jbellis.brokk.analyzer.builder.{CpgTestFixture, IncrementalBuildTestFixture}
import io.joern.c2cpg.Config
import io.shiftleft.codepropertygraph.generated.{Cpg, Operators}
import io.shiftleft.codepropertygraph.generated.nodes.Literal
import io.shiftleft.semanticcpg.language.*

import java.nio.file.Path

class IncrementalBuildTest extends CpgTestFixture[Config] with IncrementalBuildTestFixture[Config] {

  override implicit def defaultConfig: Config = Config()

  // Automatically detected changed files tests

  "an incremental build from an empty project" in {
    withIncrementalTestConfig { (configA, configB) =>
      val projectA = emptyProject(configA)
      val projectB = project(
        configB,
        """
          |#include <iostream>
          |
          |int main() {
          |  std::cout << "Hello, world!" << std::endl;
          |  return 0;
          |}
          |""".stripMargin,
        "Foo.cpp"
      )
      testIncremental(projectA, projectB)
    }
  }

  "an incremental build from a single file change" in {
    withIncrementalTestConfig { (configA, configB) =>
      val projectA = project(
        configA,
        """
          |#include <iostream>
          |
          |int main() {
          |  std::cout << "Hello, world!" << std::endl;
          |  return 0;
          |}
          |""".stripMargin,
        "Foo.cpp"
      )
      val projectB = project(
        configB,
        """
          |#include <iostream>
          |
          |int main() {
          |  std::cout << "Hello, my incremental world!" << std::endl;
          |  return 0;
          |}
          |""".stripMargin,
        "Foo.cpp"
      )

      testIncremental(projectA, projectB)
    }
  }

  "an incremental build from a single file change with unchanged files present" in {
    withIncrementalTestConfig { (configA, configB) =>
      val projectA = project(
        configA,
        """
          |#include <iostream>
          |
          |int main() {
          |  std::cout << "Hello, world!" << std::endl;
          |  return 0;
          |}
          |""".stripMargin,
        "Foo.cpp"
      ).moreCode(
        """
          |namespace test {
          |  class Bar {
          |  public:
          |    int test(int a) {
          |      return 1 + a;
          |    }
          |  };
          |}
          |""".stripMargin,
        "test/Bar.cpp"
      )
      val projectB = project(
        configB,
        """
          |#include <iostream>
          |
          |int main() {
          |  std::cout << "Hello, my incremental world!" << std::endl;
          |  return 0;
          |}
          |""".stripMargin,
        "Foo.cpp"
      ).moreCode(
        """
          |namespace test {
          |  class Bar {
          |  public:
          |    int test(int a) {
          |      return 1 + a;
          |    }
          |  };
          |}
          |""".stripMargin,
        "test/Bar.cpp"
      )

      testIncremental(projectA, projectB)
    }
  }

  "an incremental build with file inheritance and calls across files" in {
    withIncrementalTestConfig { (configA, configB) =>
      val projectA = project(
        configA,
        """
          |#include <iostream>
          |
          |class Base {
          |public:
          |  virtual void foo() {
          |    std::cout << "Hello, base!" << std::endl;
          |  }
          |  virtual ~Base() = default;
          |};
          |""".stripMargin,
        "Base.h"
      ).moreCode(
        """
          |#include <iostream>
          |#include "Base.h"
          |
          |class SuperA : public Base {
          |public:
          |  void foo() override {
          |    std::cout << "Hello, super A!" << std::endl;
          |  }
          |};
          |""".stripMargin,
        "SuperA.h"
      ).moreCode(
        """
          |#include <iostream>
          |#include "Base.h"
          |
          |class SuperB : public Base {
          |public:
          |  void foo() override {
          |    std::cout << "Hello, super B!" << std::endl;
          |  }
          |};
          |""".stripMargin,
        "SuperB.h"
      ).moreCode(
        """
          |#include "Base.h"
          |
          |class Driver {
          |public:
          |  void drive(Base* b) {
          |    if (b) {
          |      b->foo();
          |    }
          |  }
          |};
          |""".stripMargin,
        "Driver.h"
      )
      val projectB = project(
        configB,
        """
          |#include <iostream>
          |
          |class Base {
          |public:
          |  virtual void foo() {
          |    std::cout << "Hello, base (but different)!" << std::endl;
          |  }
          |  virtual ~Base() = default;
          |};
          |""".stripMargin,
        "Base.h"
      ).moreCode(
        """
          |#include <iostream>
          |#include "Base.h"
          |
          |class SuperA : public Base {
          |public:
          |  void foo() override {
          |    std::cout << "Hello, super A!" << std::endl;
          |  }
          |};
          |""".stripMargin,
        "SuperA.h"
      ).moreCode(
        """
          |#include <iostream>
          |#include "Base.h"
          |
          |class SuperB : public Base {
          |public:
          |  void foo() override {
          |    std::cout << "Hello, super B (but different)!" << std::endl;
          |  }
          |};
          |""".stripMargin,
        "SuperB.h"
      ).moreCode(
        """
          |#include "Base.h"
          |
          |class Driver {
          |public:
          |  void drive(Base* b) {
          |    if (b) {
          |      b->foo();
          |    }
          |  }
          |};
          |""".stripMargin,
        "Driver.h"
      )

      testIncremental(projectA, projectB)
    }
  }

  // Specified change tests

  def printlnLiteral(cpg: Cpg, fileName: String = "Foo.cpp"): Literal =
    cpg.method
      .where(_.file.nameExact(fileName))
      .nameExact("main")
      .call
      .nameExact(Operators.shiftLeft)
      .argument
      .isLiteral
      .head

  "an incremental build that specifies no changes, albeit changes have occurred, should not change the CPG" in {
    withIncrementalTestConfig { (configA, configB) =>
      val projectA = project(
        configA,
        """
          |#include <iostream>
          |
          |int main() {
          |  std::cout << "Hello, world!" << std::endl;
          |  return 0;
          |}
          |""".stripMargin,
        "Foo.cpp"
      )
      val projectB = project(
        configB,
        """
          |#include <iostream>
          |
          |int main() {
          |  std::cout << "Hello, my incremental world!" << std::endl;
          |  return 0;
          |}
          |""".stripMargin,
        "Foo.cpp"
      )

      testSpecifiedChanges(
        projectA,
        projectB,
        Set.empty,
        { (original, updated) =>
          val originalLiteral = printlnLiteral(original)
          val updatedLiteral  = printlnLiteral(updated)

          originalLiteral.code shouldBe updatedLiteral.code
        }
      )
    }
  }

  "an incremental build that specifies the changed file should update it" in {
    withIncrementalTestConfig { (configA, configB) =>
      val projectA = project(
        configA,
        """
          |#include <iostream>
          |
          |int main() {
          |  std::cout << "Hello, world!" << std::endl;
          |  return 0;
          |}
          |""".stripMargin,
        "Foo.cpp"
      )
      val projectB = project(
        configB,
        """
          |#include <iostream>
          |
          |int main() {
          |  std::cout << "Hello, my incremental world!" << std::endl;
          |  return 0;
          |}
          |""".stripMargin,
        "Foo.cpp"
      )

      testSpecifiedChanges(
        projectA,
        projectB,
        Set(ProjectFile(Path.of(configA.inputPath), "Foo.cpp")),
        { (original, updated) =>
          val originalLiteral = printlnLiteral(original)
          val updatedLiteral  = printlnLiteral(updated)

          originalLiteral.code shouldBe "\"Hello, world!\""
          updatedLiteral.code shouldBe "\"Hello, my incremental world!\""
        }
      )
    }
  }

  "an incremental build that specifies the changed file should update it, but not other files" in {
    withIncrementalTestConfig { (configA, configB) =>
      val projectA = project(
        configA,
        """
          |#include <iostream>
          |
          |int main() {
          |  std::cout << "Hello, world!" << std::endl;
          |  return 0;
          |}
          |""".stripMargin,
        "Foo.cpp"
      ).moreCode(
        """#include <iostream>
          |
          |namespace test {
          | int main() {
          |   std::cout << "Hello, from Bar!" << std::endl;
          |   return 0;
          | }
          |}
          |""".stripMargin,
        "test/Bar.cpp"
      )
      val projectB = project(
        configB,
        """
          |#include <iostream>
          |
          |int main() {
          |  std::cout << "Hello, my incremental world!" << std::endl;
          |  return 0;
          |}
          |""".stripMargin,
        "Foo.cpp"
      ).moreCode(
        """#include <iostream>
          |
          |namespace test {
          | int main() {
          |   std::cout << "Hello, from incremental Bar!" << std::endl;
          |   return 0;
          | }
          |}
          |""".stripMargin,
        "test/Bar.cpp"
      )

      testSpecifiedChanges(
        projectA,
        projectB,
        Set(ProjectFile(Path.of(configA.inputPath), "Foo.cpp")),
        { (original, updated) =>
          printlnLiteral(original).code shouldBe "\"Hello, world!\""
          printlnLiteral(updated).code shouldBe "\"Hello, my incremental world!\""

          printlnLiteral(original, "test/Bar.cpp").code shouldBe "\"Hello, from Bar!\""
          printlnLiteral(updated, "test/Bar.cpp").code shouldBe "\"Hello, from Bar!\""
        }
      )
    }
  }
}
