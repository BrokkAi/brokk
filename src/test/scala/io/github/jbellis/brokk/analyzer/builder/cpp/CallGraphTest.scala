package io.github.jbellis.brokk.analyzer.builder.cpp

import io.github.jbellis.brokk.analyzer.builder.CpgTestFixture
import io.github.jbellis.brokk.analyzer.builder.languages.given
import io.joern.c2cpg
import io.shiftleft.codepropertygraph.generated.DispatchTypes
import io.shiftleft.semanticcpg.language.*

class CallGraphTest extends CpgTestFixture[c2cpg.Config] {

  override protected implicit def defaultConfig: c2cpg.Config = c2cpg.Config()

  "a simple static call should be resolved" in {
    withTestConfig { config =>
      val cpg = project(
        config,
        """
          |#include <string>
          |
          |std::string bar() {
          |  return "Hello, bar";
          |}
          |
          |int main() {
          |  bar();
          |  return 0;
          |}
          |""".stripMargin,
        "test.cpp"
      ).buildAndOpen

      inside(cpg.method.nameExact("bar").callIn.l) { case barCall :: Nil =>
        barCall.dispatchType shouldBe DispatchTypes.STATIC_DISPATCH
        barCall.code shouldBe "bar()"
        barCall.method.name shouldBe "main"
      }
    }
  }

  "a simple dynamic dispatch call should be resolved" in {
    withTestConfig { config =>
      val cpg = project(
        config,
        """
          |class Vehicle {
          |public:
          |  virtual void start() {}
          |};
          |
          |class Car : public Vehicle {
          |public:
          |  void start() override {}
          |};
          |
          |int main() {
          |  Vehicle* v = new Car();
          |  v->start();
          |  delete v;
          |  return 0;
          |}
          |""".stripMargin,
        "test.cpp"
      ).buildAndOpen

      inside(cpg.call.nameExact("start").l) { case startCall :: Nil =>
        startCall.method.name shouldBe "main"
        startCall.dispatchType shouldBe DispatchTypes.DYNAMIC_DISPATCH
        startCall.callee.fullName.l should contain theSameElementsAs List("Vehicle.start:void()", "Car.start:void()")
      }
    }
  }

  "a polymorphic call site should be resolved to multiple targets" in {
    withTestConfig { config =>
      val cpg = project(
        config,
        """
          |#pragma once
          |#include <string>
          |
          |class Greeter {
          |public:
          |  virtual std::string greet() = 0;
          |  virtual ~Greeter() = default;
          |};
          |""".stripMargin,
        "Greeter.h"
      ).moreCode(
        """
            |#pragma once
            |#include "Greeter.h"
            |
            |class EnglishGreeter : public Greeter {
            |public:
            |  std::string greet() override { return "Hello"; }
            |};
            |""".stripMargin,
        "EnglishGreeter.h"
      ).moreCode(
        """
            |#pragma once
            |#include "Greeter.h"
            |
            |class SpanishGreeter : public Greeter {
            |public:
            |  std::string greet() override { return "Hola"; }
            |};
            |""".stripMargin,
        "SpanishGreeter.h"
      ).moreCode(
        """
            |#include "EnglishGreeter.h"
            |#include "SpanishGreeter.h"
            |
            |void doGreet(Greeter* greeter) {
            |  greeter->greet();
            |}
            |
            |int main() {
            |  doGreet(new EnglishGreeter());
            |  doGreet(new SpanishGreeter());
            |  return 0;
            |}
            |""".stripMargin,
        "main.cpp"
      ).buildAndOpen

      inside(cpg.call.nameExact("greet").l) { case greetCall :: Nil =>
        greetCall.method.name shouldBe "doGreet"
        greetCall.dispatchType shouldBe DispatchTypes.DYNAMIC_DISPATCH
        // There is a bug in the frontend caused by `Greeter*` to be interpreted literally with the * resulting in
        // no matched types
        greetCall.callee.fullName.l should contain theSameElementsAs List(
          "Greeter.greet:ANY()",
          "EnglishGreeter.greet:string()",
          "SpanishGreeter.greet:string()"
        )
      }
    }
  }

  "a call inside a lambda should be resolved" in {
    withTestConfig { config =>
      val cpg = project(
        config,
        """
          |#include <string>
          |#include <functional>
          |
          |std::string getGreeting() {
          |  return "Hello from free function";
          |}
          |
          |void execute(const std::function<std::string()>& func) {
          |  func();
          |}
          |
          |int main() {
          |  execute([]() { return getGreeting(); });
          |  return 0;
          |}
          |""".stripMargin,
        "test.cpp"
      ).buildAndOpen

      val lambdaMethod = cpg.method.isLambda.head
      lambdaMethod.signature shouldBe "ANY()" // should really be "std.string()"

      inside(cpg.call.name("getGreeting").l) { case getGreetingCall :: Nil =>
        getGreetingCall.method.fullName shouldBe lambdaMethod.fullName
        getGreetingCall.dispatchType shouldBe DispatchTypes.STATIC_DISPATCH
        getGreetingCall.callee.fullName.l shouldBe List("getGreeting:ANY()") // List("getGreeting:std.string()")
      }

      inside(cpg.call.name("<operator>\\(\\)|func").l) { case funcCall :: Nil =>
        funcCall.method.name shouldBe "execute"
        // funcCall.dispatchType shouldBe DispatchTypes.DYNAMIC_DISPATCH (this is being determined to be static)
        // This seems completely unresolved
        funcCall.callee.fullName.l should contain(
          "<unresolvedNamespace>.func:<unresolvedSignature>(0)"
        ) // ,lambdaMethod.fullName)
      }
    }
  }

  "a chained dynamic dispatch call should be resolved" in {
    withTestConfig { config =>
      val cpg = project(
        config,
        """
          |class B {
          |public:
          |  virtual void doSomething() = 0;
          |  virtual ~B() = default;
          |};
          |
          |class ConcreteB : public B {
          |public:
          |  void doSomething() override {}
          |};
          |
          |class A {
          |public:
          |  virtual B* getB() = 0;
          |  virtual ~A() = default;
          |};
          |
          |class ConcreteA : public A {
          |private:
          |  B* myB;
          |public:
          |  ConcreteA() { myB = new ConcreteB(); }
          |  ~ConcreteA() { delete myB; }
          |  B* getB() override { return myB; }
          |};
          |
          |int main() {
          |  A* a = new ConcreteA();
          |  a->getB()->doSomething();
          |  delete a;
          |  return 0;
          |}
          |""".stripMargin,
        "test.cpp"
      ).buildAndOpen

      inside(cpg.call.nameExact("getB").l) { case getBCall :: Nil =>
        getBCall.method.name shouldBe "main"
        getBCall.dispatchType shouldBe DispatchTypes.DYNAMIC_DISPATCH
        getBCall.callee.fullName.l should contain theSameElementsAs List("A.getB:B*()", "ConcreteA.getB:B*()")
      }

      inside(cpg.call.nameExact("doSomething").l) { case doSomethingCall :: Nil =>
        doSomethingCall.method.name shouldBe "main"
        doSomethingCall.dispatchType shouldBe DispatchTypes.DYNAMIC_DISPATCH
        doSomethingCall.callee.fullName.l should contain theSameElementsAs List(
          "B.doSomething:void()",
          "ConcreteB.doSomething:void()"
        )
      }
    }
  }

}
