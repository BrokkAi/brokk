package io.github.jbellis.brokk.analyzer.builder.cpp

import io.github.jbellis.brokk.analyzer.builder.languages.given
import io.github.jbellis.brokk.analyzer.builder.{CpgTestFixture, IncrementalBuildTestFixture}
import io.joern.c2cpg.Config

class IncrementalBuildTest extends CpgTestFixture[Config] with IncrementalBuildTestFixture[Config] {

  override implicit def defaultConfig: Config = Config()

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
          |""".stripMargin, "Foo.cpp")
      testIncremental(projectA, projectB)
    }
  }

  "an incremental build from a single file change" in {
    withIncrementalTestConfig { (configA, configB) =>
      val projectA = project(configA,
        """
          |#include <iostream>
          |
          |int main() {
          |  std::cout << "Hello, world!" << std::endl;
          |  return 0;
          |}
          |""".stripMargin, "Foo.cpp")
      val projectB = project(configB,
        """
          |#include <iostream>
          |
          |int main() {
          |  std::cout << "Hello, my incremental world!" << std::endl;
          |  return 0;
          |}
          |""".stripMargin, "Foo.cpp")

      testIncremental(projectA, projectB)
    }
  }

  "an incremental build from a single file change with unchanged files present" in {
    withIncrementalTestConfig { (configA, configB) =>
      val projectA = project(configA,
        """
          |#include <iostream>
          |
          |int main() {
          |  std::cout << "Hello, world!" << std::endl;
          |  return 0;
          |}
          |""".stripMargin, "Foo.cpp").moreCode(
        """
          |namespace test {
          |  class Bar {
          |  public:
          |    int test(int a) {
          |      return 1 + a;
          |    }
          |  };
          |}
          |""".stripMargin, "test/Bar.cpp")
      val projectB = project(configB,
        """
          |#include <iostream>
          |
          |int main() {
          |  std::cout << "Hello, my incremental world!" << std::endl;
          |  return 0;
          |}
          |""".stripMargin, "Foo.cpp").moreCode(
        """
          |namespace test {
          |  class Bar {
          |  public:
          |    int test(int a) {
          |      return 1 + a;
          |    }
          |  };
          |}
          |""".stripMargin, "test/Bar.cpp")

      testIncremental(projectA, projectB)
    }
  }

  "an incremental build with file inheritance and calls across files" in {
    withIncrementalTestConfig { (configA, configB) =>
      val projectA = project(configA,
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
          |""".stripMargin, "Base.h").moreCode(
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
          |""".stripMargin, "SuperA.h").moreCode(
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
          |""".stripMargin, "SuperB.h").moreCode(
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
          |""".stripMargin, "Driver.h")
      val projectB = project(configB,
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
          |""".stripMargin, "Base.h").moreCode(
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
          |""".stripMargin, "SuperA.h").moreCode(
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
          |""".stripMargin, "SuperB.h").moreCode(
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
          |""".stripMargin, "Driver.h")

      testIncremental(projectA, projectB)
    }
  }


}
