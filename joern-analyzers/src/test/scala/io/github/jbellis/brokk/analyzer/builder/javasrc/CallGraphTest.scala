package io.github.jbellis.brokk.analyzer.builder.javasrc

import io.github.jbellis.brokk.analyzer.builder.CpgTestFixture
import io.github.jbellis.brokk.analyzer.builder.languages.given
import io.joern.javasrc2cpg
import io.shiftleft.codepropertygraph.generated.DispatchTypes
import io.shiftleft.semanticcpg.language.*

class CallGraphTest extends CpgTestFixture[javasrc2cpg.Config] {

  override protected implicit def defaultConfig: javasrc2cpg.Config = javasrc2cpg.Config()

  "a simple static call should be resolved" in {
    withTestConfig { config =>
      val cpg = project(
        config,
        """
          |public class Foo {
          | public static String bar() {
          |   return "Hello, bar";
          | }
          |}
          |""".stripMargin,
        "Foo.java"
      ).moreCode(
        """
            |public class Driver {
            | public static void main(String[] args) {
            |   Foo.bar();
            | }
            |}
            |""".stripMargin,
        "Driver.java"
      ).buildAndOpen

      inside(cpg.method.nameExact("bar").callIn.l) { case fooBar :: Nil =>
        fooBar.dispatchType shouldBe DispatchTypes.STATIC_DISPATCH
        fooBar.code shouldBe "Foo.bar()"
        fooBar.method.fullName shouldBe "Driver.main:void(java.lang.String[])"
      }
    }
  }

  "a simple dynamic dispatch call should be resolved" in {
    withTestConfig { config =>
      val cpg = project(
        config,
        """
          |public class Vehicle {
          |  public void start() {
          |    System.out.println("Vehicle starting");
          |  }
          |}
          |""".stripMargin,
        "Vehicle.java"
      ).moreCode(
        """
            |public class Car extends Vehicle {
            |  @Override
            |  public void start() {
            |    System.out.println("Car starting");
            |  }
            |
            |  public static void main(String[] args) {
            |    Vehicle v = new Car();
            |    v.start();
            |  }
            |}
            |""".stripMargin,
        "Car.java"
      ).buildAndOpen

      inside(cpg.call.nameExact("start").l) { case startCall :: Nil =>
        startCall.method.fullName shouldBe "Car.main:void(java.lang.String[])"
        startCall.dispatchType shouldBe DispatchTypes.DYNAMIC_DISPATCH
        startCall.typeFullName shouldBe "void"
        startCall.callee.fullName.l should contain theSameElementsAs List("Vehicle.start:void()", "Car.start:void()")
      }
    }
  }

  "a polymorphic call site should be resolved to multiple targets" in {
    withTestConfig { config =>
      val cpg = project(
        config,
        """
          |public interface Greeter {
          |  String greet();
          |}
          |""".stripMargin,
        "Greeter.java"
      ).moreCode(
        """
            |public class EnglishGreeter implements Greeter {
            |  @Override
            |  public String greet() { return "Hello"; }
            |}
            |""".stripMargin,
        "EnglishGreeter.java"
      ).moreCode(
        """
            |public class SpanishGreeter implements Greeter {
            |  @Override
            |  public String greet() { return "Hola"; }
            |}
            |""".stripMargin,
        "SpanishGreeter.java"
      ).moreCode(
        """
            |public class Driver {
            |  public static void doGreet(Greeter greeter) {
            |    greeter.greet();
            |  }
            |
            |  public static void main(String[] args) {
            |    doGreet(new EnglishGreeter());
            |    doGreet(new SpanishGreeter());
            |  }
            |}
            |""".stripMargin,
        "Driver.java"
      ).buildAndOpen

      inside(cpg.call.nameExact("greet").l) { case greetCall :: Nil =>
        greetCall.method.fullName shouldBe "Driver.doGreet:void(Greeter)"
        greetCall.dispatchType shouldBe DispatchTypes.DYNAMIC_DISPATCH
        greetCall.callee.fullName.l should contain theSameElementsAs List(
          "Greeter.greet:java.lang.String()", // we should decide if we include interfaces or only concrete methods
          "EnglishGreeter.greet:java.lang.String()",
          "SpanishGreeter.greet:java.lang.String()"
        )
      }
    }
  }

  "a call inside a lambda should be resolved" in {
    withTestConfig { config =>
      val cpg = project(
        config,
        """
          |import java.util.function.Supplier;
          |
          |public class Greeter {
          |  public static String getGreeting() {
          |    return "Hello from static method";
          |  }
          |}
          |""".stripMargin,
        "Greeter.java"
      ).moreCode(
        """
            |import java.util.function.Supplier;
            |
            |public class LambdaDriver {
            |  public static void execute(Supplier<String> supplier) {
            |    supplier.get();
            |  }
            |
            |  public static void main(String[] args) {
            |    execute(() -> Greeter.getGreeting());
            |  }
            |}
            |""".stripMargin,
        "LambdaDriver.java"
      ).buildAndOpen

      val lambdaMethod = cpg.method.isLambda.head
      lambdaMethod.fullName should fullyMatch regex "LambdaDriver\\.main\\.<lambda>\\d:java.lang.String\\(\\)"

      inside(cpg.call.name("getGreeting").l) { case getGreetingCall :: Nil =>
        getGreetingCall.method.fullName shouldBe lambdaMethod.fullName
        getGreetingCall.dispatchType shouldBe DispatchTypes.STATIC_DISPATCH
        getGreetingCall.callee.fullName.l shouldBe List("Greeter.getGreeting:java.lang.String()")
      }

      inside(cpg.call.name("get").l) { case getCall :: Nil =>
        getCall.method.fullName shouldBe "LambdaDriver.execute:void(java.util.function.Supplier)"
        getCall.dispatchType shouldBe DispatchTypes.DYNAMIC_DISPATCH
        getCall.callee.fullName.l should not contain lambdaMethod.fullName // do we want this to include our lambda instance?
        getCall.callee.fullName.l should contain("java.util.function.Supplier.get:java.lang.Object()")
      }
    }
  }

  "a chained dynamic dispatch call should be resolved" in {
    withTestConfig { config =>
      val cpg = project(
        config,
        """
          |public interface B {
          |  void doSomething();
          |}
          |""".stripMargin,
        "B.java"
      ).moreCode(
        """
            |public class ConcreteB implements B {
            |  @Override
            |  public void doSomething() {}
            |}
            |""".stripMargin,
        "ConcreteB.java"
      ).moreCode(
        """
            |public interface A {
            |  B getB();
            |}
            |""".stripMargin,
        "A.java"
      ).moreCode(
        """
            |public class ConcreteA implements A {
            |  private final B myB = new ConcreteB();
            |  @Override
            |  public B getB() { return this.myB; }
            |}
            |""".stripMargin,
        "ConcreteA.java"
      ).moreCode(
        """
            |public class Driver {
            |  public static void main(String[] args) {
            |    A a = new ConcreteA();
            |    a.getB().doSomething();
            |  }
            |}
            |""".stripMargin,
        "Driver.java"
      ).buildAndOpen

      inside(cpg.call.name("getB").l) { case getBCall :: Nil =>
        getBCall.method.fullName shouldBe "Driver.main:void(java.lang.String[])"
        getBCall.dispatchType shouldBe DispatchTypes.DYNAMIC_DISPATCH
        getBCall.callee.fullName.l should contain theSameElementsAs List("A.getB:B()", "ConcreteA.getB:B()")
      }

      inside(cpg.call.name("doSomething").l) { case doSomethingCall :: Nil =>
        doSomethingCall.method.fullName shouldBe "Driver.main:void(java.lang.String[])"
        doSomethingCall.dispatchType shouldBe DispatchTypes.DYNAMIC_DISPATCH
        doSomethingCall.callee.fullName.l should contain theSameElementsAs List(
          "B.doSomething:void()",
          "ConcreteB.doSomething:void()"
        )
      }
    }
  }
}
