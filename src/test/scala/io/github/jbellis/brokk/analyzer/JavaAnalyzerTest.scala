package io.github.jbellis.brokk.analyzer

import io.shiftleft.codepropertygraph.generated.language.*
import io.shiftleft.semanticcpg.language.*
import org.junit.jupiter.api.Assertions.{assertEquals, assertFalse, assertThrows, assertTrue}
import org.junit.jupiter.api.Test

import java.nio.file.{Files, Path}
import java.util.Optional
import scala.jdk.OptionConverters.RichOptional
import scala.jdk.javaapi.*
import scala.jdk.javaapi.CollectionConverters.asScala

class JavaAnalyzerTest {
  implicit val callResolver: ICallResolver = NoResolve
  private val n                            = System.lineSeparator

  @Test
  def callerTest(): Unit = {
    val analyzer = getAnalyzer
    val callOut  = analyzer.cpg.method.call.l
    assert(callOut.nonEmpty)
    val callIn = analyzer.cpg.method.caller.l
    assert(callIn.nonEmpty)
  }

  @Test
  def isClassInProjectTest(): Unit = {
    val analyzer = getAnalyzer
    assert(analyzer.isClassInProject("A"))

    assert(!analyzer.isClassInProject("java.nio.filename.Path"))
    assert(!analyzer.isClassInProject("org.foo.Bar"))
  }

  @Test
  def extractMethodSource(): Unit = {
    val analyzer                    = getAnalyzer
    val sourceOpt: Optional[String] = analyzer.getMethodSource("A.method2")
    assertTrue(sourceOpt.isPresent)
    val source = sourceOpt.get().replace(n, "\n").stripIndent()

    val expected =
      """    public String method2(String input) {
        |        return "prefix_" + input;
        |    }
        |
        |    public String method2(String input, int otherInput) {
        |        // overload of method2
        |        return "prefix_" + input + " " + otherInput;
        |    }""".stripMargin.stripIndent

    assertEquals(expected, source)
  }

  @Test
  def extractMethodSourceNested(): Unit = {
    val analyzer                    = getAnalyzer
    val sourceOpt: Optional[String] = analyzer.getMethodSource("A$AInner$AInnerInner.method7")
    assertTrue(sourceOpt.isPresent)
    val source = sourceOpt.get().replace(n, "\n").stripIndent()

    val expected =
      """            public void method7() {
        |                System.out.println("hello");
        |            }""".stripMargin.stripIndent

    assertEquals(expected, source)
  }

  @Test
  def extractMethodSourceConstructor(): Unit = {
    val analyzer                    = getAnalyzer
    val sourceOpt: Optional[String] = analyzer.getMethodSource("B.<init>")
    assertTrue(sourceOpt.isPresent)
    val source = sourceOpt.get().replace(n, "\n").stripIndent()

    val expected =
      """    public B() {
        |        System.out.println("B constructor");
        |    }""".stripMargin.stripIndent

    assertEquals(expected, source)
  }

  @Test
  def getClassSourceTest(): Unit = {
    val analyzer = getAnalyzer
    val source   = analyzer.getClassSource("A")

    // Verify the source contains class definition and methods
    assertTrue(source.contains("class A {"))
    assertTrue(source.contains("public void method1()"))
    assertTrue(source.contains("public String method2(String input)"))
  }

  @Test
  def getClassSourceNestedTest(): Unit = {
    val analyzer = getAnalyzer
    val source   = analyzer.getClassSource("A$AInner").replace(n, "\n").stripIndent()
    // Verify the source contains inner class definition
    val expected =
      """
        |   public class AInner {
        |        public class AInnerInner {
        |            public void method7() {
        |                System.out.println("hello");
        |            }
        |        }
        |    }
        |""".stripMargin.stripIndent.strip
    assertEquals(expected, source)
  }

  @Test
  def getClassSourceTwiceNestedTest(): Unit = {
    val analyzer = getAnalyzer
    val source   = analyzer.getClassSource("A$AInner$AInnerInner").replace(n, "\n").stripIndent()
    // Verify the source contains inner class definition
    val expected =
      """
        |        public class AInnerInner {
        |            public void method7() {
        |                System.out.println("hello");
        |            }
        |        }
        |""".stripMargin.stripIndent.strip
    assertEquals(expected, source)
  }

  @Test
  def getClassSourceFallbackTest(): Unit = {
    val analyzer = getAnalyzer
    val source   = analyzer.getClassSource("A$NonExistent").replace(n, "\n").stripIndent()
    // Verify that the class fallback works if subclasses (or anonymous classes) aren't resolved
    assertTrue(source.contains("class A {"))
    assertTrue(source.contains("public void method1()"))
    assertTrue(source.contains("public String method2(String input)"))
  }

  @Test
  def getClassSourceNonexistentTest(): Unit = {
    val analyzer = getAnalyzer
    val source   = analyzer.getClassSource("NonExistentClass")
    assertEquals(null, source)
  }

  @Test
  def sanitizeTypeTest(): Unit = {
    val analyzer = getAnalyzer

    // Simple types
    assertEquals("String", analyzer.sanitizeType("java.lang.String"))
    assertEquals("String[]", analyzer.sanitizeType("java.lang.String[]"))

    // Generic types
    assertEquals(
      "Function<Integer, Integer>",
      analyzer.sanitizeType("java.util.function.Function<java.lang.Integer, java.lang.Integer>")
    )

    // Nested generic types
    assertEquals(
      "Map<String, List<Integer>>",
      analyzer.sanitizeType("java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>")
    )

    // Method return type with generics
    assertEquals(
      "Function<Integer, Integer>",
      analyzer.sanitizeType("java.util.function.Function<java.lang.Integer, java.lang.Integer>")
    )
  }

  @Test
  def getSkeletonTestA(): Unit = {
    val skeletonOpt = getAnalyzer.getSkeleton("A")
    assertTrue(skeletonOpt.isPresent)
    val skeleton = skeletonOpt.get().replace(n, "\n").stripIndent()
    // https://github.com/joernio/joern/issues/5297
    //    |  public Function<Integer, Integer> method3() {...}
    val expected =
      """class A {
        |  public void method1() {...}
        |  public String method2(String input) {...}
        |  public String method2(String input, int otherInput) {...}
        |  public Function method3() {...}
        |  public static int method4(double foo, Integer bar) {...}
        |  public void method5() {...}
        |  public void method6() {...}
        |  public void <init>() {...}
        |  class A$AInner {
        |    public void <init>(A outerClass) {...}
        |    class A$AInner$AInnerInner {
        |      public void method7() {...}
        |      public void <init>(A$AInner outerClass) {...}
        |    }
        |  }
        |  class A$AInnerStatic {
        |    public void <init>() {...}
        |  }
        |}""".stripMargin.stripIndent
    assertEquals(expected, skeleton)
  }

  @Test
  def getSkeletonTestD(): Unit = {
    val skeletonOpt = getAnalyzer.getSkeleton("D")
    assertTrue(skeletonOpt.isPresent)
    val skeleton = skeletonOpt.get().replace(n, "\n").stripIndent()
    val expected =
      """class D {
        |  public void methodD1() {...}
        |  public void methodD2() {...}
        |  public void <init>() {...}
        |  int field1;
        |  String field2;
        |  class D$DSubStatic {
        |    public void <init>() {...}
        |  }
        |  class D$DSub {
        |    public void <init>(D outerClass) {...}
        |  }
        |}""".stripMargin.stripIndent
    assertEquals(expected, skeleton)
  }

  @Test
  def getGetSkeletonHeaderTest(): Unit = {
    val skeletonOpt = getAnalyzer.getSkeletonHeader("D")
    assertTrue(skeletonOpt.isPresent)
    val skeleton = skeletonOpt.get().replace(n, "\n").stripIndent()
    val expected =
      """class D {
        |  public int field1;
        |  private String field2;
        |  [... methods not shown ...]
        |}""".stripMargin.stripIndent
    assertEquals(expected, skeleton)
  }

  @Test
  def getAllClassesTest(): Unit = {
    val analyzer = getAnalyzer
    val classes  = analyzer.getAllDeclarations
  }

  @Test
  def getCallgraphToTest(): Unit = {
    val analyzer  = getAnalyzer
    val callgraph = analyzer.getCallgraphTo("A.method1", 5)

    // Convert to a more convenient form for testing
    val callsites = asScala(callgraph).toMap

    // Expect A.method1 -> [B.callsIntoA, D.methodD1]
    assertTrue(callsites.contains("A.method1"), "Should contain A.method1 as a key")

    val callers =
      callsites.get("A.method1").map(sites => asScala(sites).map(_.target().fqName).toSet).getOrElse(Set.empty)
    assertEquals(Set("B.callsIntoA", "D.methodD1"), callers)
  }

  @Test
  def getCallgraphFromTest(): Unit = {
    val analyzer  = getAnalyzer
    val callgraph = analyzer.getCallgraphFrom("B.callsIntoA", 5)

    // Convert to a more convenient form for testing
    val callsites = asScala(callgraph).toMap

    // Expect B.callsIntoA -> [A.method1, A.method2]
    assertTrue(callsites.contains("B.callsIntoA"), "Should contain B.callsIntoA as a key")

    val callees =
      callsites.get("B.callsIntoA").map(sites => asScala(sites).map(_.target().fqName).toSet).getOrElse(Set.empty)
    assertTrue(callees.contains("A.method1"), "Should call A.method1")
    assertTrue(callees.contains("A.method2"), "Should call A.method2")
  }

  @Test
  def getPagerankTest(): Unit = {
    val analyzer = getAnalyzer
    import scala.jdk.javaapi.*

    val seeds  = CollectionConverters.asJava(Map("D" -> (1.0: java.lang.Double)))
    val ranked = analyzer.getPagerank(seeds, 3, false)

    // D calls A and B
    assert(ranked.size() == 2, ranked)
    val classes = asScala(ranked).map(_._1.fqName()).toSet
    assertEquals(Set("A", "B"), classes)
  }

  @Test
  def getPagerankEmptyClassTest(): Unit = {
    val analyzer = getAnalyzer
    import scala.jdk.javaapi.*

    // Seed with CamelClass, which has no connections
    val seeds  = CollectionConverters.asJava(Map("CamelClass" -> (1.0: java.lang.Double)))
    val ranked = analyzer.getPagerank(seeds, 5, false)

    // Expect an empty list because CamelClass has few connections,
    // and after filtering itself and zero-scores, nothing should remain.
    assertTrue(ranked.isEmpty, s"Expected empty pagerank results, but got: $ranked")
  }

  @Test
  def getDeclarationsInFileTest(): Unit = {
    val analyzer     = getAnalyzer
    val file         = analyzer.toFile("D.java").get
    val declarations = analyzer.getDeclarationsInFile(file)
    val expected = Set(
      // Classes
      CodeUnit.cls(file, "", "D"),
      CodeUnit.cls(file, "", "D$DSub"),
      CodeUnit.cls(file, "", "D$DSubStatic"),
      // Methods
      CodeUnit.fn(file, "", "D.methodD1"),
      CodeUnit.fn(file, "", "D.methodD2"),
      CodeUnit.fn(file, "", "D.<init>"),
      CodeUnit.fn(file, "", "D$DSubStatic.<init>"),
      CodeUnit.fn(file, "", "D$DSub.<init>"),
      // Fields
      CodeUnit.field(file, "", "D.field1"),
      CodeUnit.field(file, "", "D.field2")
    )
    assertEquals(expected, asScala(declarations).toSet)
  }

  @Test
  def declarationsInPackagedFileTest(): Unit = {
    val analyzer     = getAnalyzer
    val file         = analyzer.toFile("Packaged.java").get
    val declarations = analyzer.getDeclarationsInFile(file)
    val expected = Set(
      // Class
      CodeUnit.cls(file, "io.github.jbellis.brokk", "Foo"),
      // Method
      CodeUnit.fn(file, "io.github.jbellis.brokk", "Foo.bar"),
      // Constructor
      CodeUnit.fn(file, "io.github.jbellis.brokk", "Foo.<init>")
      // No fields in Packaged.java
    )
    assertEquals(expected, asScala(declarations).toSet)
  }

  @Test
  def getUsesMethodExistingTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol   = "A.method2"
    val usages   = analyzer.getUses(symbol)

    // Expect references in B.callsIntoA() because it calls a.method2("test")
    val actualMethodRefs = asScala(usages).filter(_.isFunction).map(_.fqName).toSet
    val actualRefs       = asScala(usages).map(_.fqName).toSet
    assertEquals(Set("B.callsIntoA", "AnonymousUsage$1.run"), actualRefs)
  }

  @Test
  def getUsesMethodNonexistentTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol   = "A.noSuchMethod:java.lang.String()"
    val ex       = assertThrows(classOf[IllegalArgumentException], () => analyzer.getUses(symbol))
    assertTrue(ex.getMessage.contains("not found as a method, field, or class"))
  }

  @Test
  def getUsesFieldExistingTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol   = "D.field1" // fully qualified field name
    val usages   = analyzer.getUses(symbol)

    val file       = analyzer.toFile("D.java").get
    val actualRefs = asScala(usages).map(_.fqName).toSet
    assertEquals(Set("D.methodD2", "E.dMethod"), actualRefs)
  }

  @Test
  def getUsesFieldNonexistentTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol   = "D.notAField"
    val ex       = assertThrows(classOf[IllegalArgumentException], () => analyzer.getUses(symbol))
    assertTrue(ex.getMessage.contains("not found"))
  }

  @Test
  def getUsesClassBasicTest(): Unit = {
    val analyzer = getAnalyzer
    // “A” has no static members, but it is used as a local type in B.callsIntoA and D.methodD1
    val symbol = "A"
    val usages = analyzer.getUses(symbol)

    // References to A include both function references and local variable references
    val foundRefs = asScala(usages).map(_.fqName).toSet

    // Get the usages of each type
    val functionRefs = asScala(usages).filter(_.isFunction).map(_.fqName).toSet
    val fieldRefs    = asScala(usages).filter(cu => !cu.isFunction && !cu.isClass).map(_.fqName).toSet
    val classRefs    = asScala(usages).filter(_.isClass).map(_.fqName).toSet

    // There should be function usages in these methods
    assertEquals(Set("B.callsIntoA", "D.methodD1", "AnonymousUsage$1.run"), functionRefs)

    // Ensure we have the correct usage types with our refactored implementation
    assertEquals(foundRefs, functionRefs ++ fieldRefs ++ classRefs)
  }

  @Test
  def getUsesClassNonexistentTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol   = "NoSuchClass"
    val ex       = assertThrows(classOf[IllegalArgumentException], () => analyzer.getUses(symbol))
    assertTrue(
      ex.getMessage.contains("Symbol 'NoSuchClass' (resolved: 'NoSuchClass') not found as a method, field, or class")
    )
  }

  @Test
  def getDefinitionsTest(): Unit = {
    val analyzer = getAnalyzer

    // bare regexp testing
    assertTrue("E".matches("(?i)e"))
    assertTrue("E".matches("(?i).*e"))
    assertTrue("e".matches("(?i)E"))

    // Find classes matching "*E"
    val classMatches = analyzer.searchDefinitions(".*e")
    val classRefs    = asScala(classMatches).filter(_.isClass).map(_.fqName).toSet
    assertEquals(Set("E", "UseE", "AnonymousUsage", "Interface"), classRefs)

    // Find methods matching "method*"
    val methodMatches = analyzer.searchDefinitions("method.*1")
    val methodRefs    = asScala(methodMatches).map(_.fqName).toSet
    assertEquals(Set("A.method1", "D.methodD1"), methodRefs)

    // Find fields matching "field.*"
    val fieldMatches = analyzer.searchDefinitions(".*field.*")
    val fieldRefs    = asScala(fieldMatches).map(_.fqName).toSet
    assertEquals(Set("D.field1", "D.field2", "E.iField", "E.sField", "F.HELLO_FIELD"), fieldRefs)
  }

  @Test
  def getDefinitionTest(): Unit = {
    val analyzer = getAnalyzer

    // Test finding a class
    val classDefOpt = analyzer.getDefinition("D")
    assertTrue(classDefOpt.isPresent, "Should find definition for class D")
    assertEquals("D", classDefOpt.get().fqName())
    assertTrue(classDefOpt.get().isClass)

    // Test finding a method (unique non-overloaded)
    val methodDefOpt = analyzer.getDefinition("A.method1")
    assertTrue(methodDefOpt.isPresent, "Should find definition for method A.method1")
    assertEquals("A.method1", methodDefOpt.get().fqName())
    assertTrue(methodDefOpt.get().isFunction)

    // Test finding a method
    val overloadedMethodOpt = analyzer.getDefinition("A.method2")
    assertTrue(overloadedMethodOpt.isPresent, "Should find a definition for overloaded method A.method2")
    assertEquals("A.method2", overloadedMethodOpt.get().fqName())
    assertTrue(overloadedMethodOpt.get().isFunction)

    // Test finding a field
    val fieldDefOpt = analyzer.getDefinition("D.field1")
    assertTrue(fieldDefOpt.isPresent, "Should find definition for field D.field1")
    assertEquals("D.field1", fieldDefOpt.get().fqName())
    assertFalse(fieldDefOpt.get().isClass)
    assertFalse(fieldDefOpt.get().isFunction)

    // Test non-existent symbol
    val nonExistentOpt = analyzer.getDefinition("NonExistentSymbol")
    assertFalse(nonExistentOpt.isPresent, "Should not find definition for NonExistentSymbol")
  }

  @Test
  def getUsesClassWithStaticMembersTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol   = "E"
    val usages   = analyzer.getUses(symbol)

    val refs = asScala(usages).map(_.fqName).toSet
    // Now includes field reference UseE.e as a FIELD type
    assertEquals(Set("UseE.some", "UseE.<init>", "UseE.moreM", "UseE.moreF", "UseE.e"), refs)
  }

  @Test
  def getUsesClassInheritanceTest(): Unit = {
    val analyzer = getAnalyzer
    val symbol   = "BaseClass"
    val usages   = analyzer.getUses(symbol)

    val refs = asScala(usages).map(_.fqName).toSet

    // Get references by type
    val classRefs = asScala(usages).filter(_.isClass).map(_.fqName).toSet

    // Create an error message capturing actual usages
    val errorMsg = s"Expected XExtendsY to be a usage of BaseClass. Actual usages: ${refs.mkString(", ")}"

    // XExtendsY should show up in the results because it extends BaseClass
    assertTrue(refs.exists(name => name.contains("XExtendsY")), errorMsg)

    // Verify that XExtendsY is specifically a CLASS type reference
    val classErrorMsg = s"Expected XExtendsY to be a CLASS type usage. Class references: ${classRefs.mkString(", ")}"
    assertTrue(classRefs.exists(name => name.contains("XExtendsY")), classErrorMsg)

    // New test: Methods returning BaseClass should be included (e.g. MethodReturner.getBase)
    assertTrue(
      refs.exists(name => name.contains("MethodReturner.getBase")),
      "Expected MethodReturner.getBase to be included in BaseClass usages"
    )
  }

  @Test
  def resolveMethodNameTest(): Unit = {
    val analyzer = getAnalyzer

    // Regular Methods
    assertEquals("java.util.List.isEmpty", analyzer.resolveMethodName("java.util.List.isEmpty"))
    assertEquals("java.lang.Math.max", analyzer.resolveMethodName("java.lang.Math.max"))
    assertEquals("java.util.UUID.toString", analyzer.resolveMethodName("java.util.UUID.toString"))

    // Static Methods
    assertEquals("java.lang.Integer.valueOf", analyzer.resolveMethodName("java.lang.Integer.valueOf"))
    assertEquals(
      "java.nio.file.Files.createDirectories",
      analyzer.resolveMethodName("java.nio.file.Files.createDirectories")
    )
    assertEquals(
      "java.util.Collections.unmodifiableList",
      analyzer.resolveMethodName("java.util.Collections.unmodifiableList")
    )
    assertEquals(
      "org.apache.cassandra.utils.FBUtilities.waitOnFuture",
      analyzer.resolveMethodName("org.apache.cassandra.utils.FBUtilities.waitOnFuture")
    )

    // Inner Class Methods
    assertEquals(
      "org.apache.cassandra.db.ClusteringPrefix$Kind.ordinal",
      analyzer.resolveMethodName("org.apache.cassandra.db.ClusteringPrefix$Kind.ordinal")
    )
    assertEquals(
      "org.apache.cassandra.io.sstable.format.big.BigTableWriter$IndexWriter.prepareToCommit",
      analyzer.resolveMethodName(
        "org.apache.cassandra.io.sstable.format.big.BigTableWriter$IndexWriter.prepareToCommit"
      )
    )
    assertEquals(
      "org.apache.cassandra.index.sai.disk.v1.kdtree.BKDReader$IteratorState.getMinLeafBlockFP",
      analyzer.resolveMethodName(
        "org.apache.cassandra.index.sai.disk.v1.kdtree.BKDReader$IteratorState.getMinLeafBlockFP"
      )
    )
    assertEquals(
      "org.apache.cassandra.repair.consistent.ConsistentSession$State.transitions",
      analyzer.resolveMethodName("org.apache.cassandra.repair.consistent.ConsistentSession$State.transitions")
    )

    // Anonymous Inner Classes used in a method
    assertEquals(
      "org.apache.cassandra.repair.RepairJob.run.FutureCallback$0.set",
      analyzer.resolveMethodName("org.apache.cassandra.repair.RepairJob.run.FutureCallback$0.set")
    )
    assertEquals(
      "org.apache.cassandra.db.lifecycle.View.updateCompacting.Function$0.all",
      analyzer.resolveMethodName("org.apache.cassandra.db.lifecycle.View.updateCompacting.Function$0.all")
    )
    assertEquals(
      "org.apache.cassandra.index.sai.plan.ReplicaPlans.writeNormal.Selector$1.any",
      analyzer.resolveMethodName("org.apache.cassandra.index.sai.plan.ReplicaPlans.writeNormal.Selector$1.any")
    )

    // Anonymous inner classes used in a field
    assertEquals(
      "org.apache.cassandra.cql3.functions.TimeFcts.minTimeuuidFct.NativeScalarFunction$0.<init>",
      analyzer.resolveMethodName(
        "org.apache.cassandra.cql3.functions.TimeFcts.minTimeuuidFct.NativeScalarFunction$0.<init>"
      )
    )
    assertEquals(
      "org.apache.cassandra.db.Clustering.$1.<init>",
      analyzer.resolveMethodName("org.apache.cassandra.db.Clustering.$1.<init>")
    )
    assertEquals(
      "FileSelectionTree.loadTreeInBackground.SwingWorker$0.addTreeWillExpandListener",
      analyzer.resolveMethodName(
        "FileSelectionTree.loadTreeInBackground.SwingWorker$0.addTreeWillExpandListener:void(javax.swing.event.TreeWillExpandListener"
      )
    )

    // Lambdas at some call site
    assertEquals(
      "io.github.jbellis.brokk.gui.GitCommitTab.rollbackChangesWithUndo",
      analyzer.resolveMethodName("io.github.jbellis.brokk.gui.GitCommitTab.lambda$rollbackChangesWithUndo$19")
    )
    assertEquals(
      "io.github.jbellis.brokk.gui.GitCommitTab.rollbackChangesWithUndo",
      analyzer.resolveMethodName("io.github.jbellis.brokk.gui.GitCommitTab.lambda$lambda$rollbackChangesWithUndo$19$20")
    )

    // Constructors
    assertEquals("java.util.HashMap.<init>", analyzer.resolveMethodName("java.util.HashMap.<init>"))
    assertEquals(
      "org.apache.cassandra.db.marshal.UserType.<init>",
      analyzer.resolveMethodName("org.apache.cassandra.db.marshal.UserType.<init>")
    )

    // Enum-related Methods
    assertEquals(
      "org.apache.cassandra.db.ConsistencyLevel.valueOf",
      analyzer.resolveMethodName("org.apache.cassandra.db.ConsistencyLevel.valueOf")
    )
    assertEquals(
      "org.apache.cassandra.repair.consistent.ConsistentSession$State.ordinal",
      analyzer.resolveMethodName("org.apache.cassandra.repair.consistent.ConsistentSession$State.ordinal")
    )

    // Interface Methods
    assertEquals(
      "org.apache.cassandra.io.IVersionedSerializer.deserialize",
      analyzer.resolveMethodName("org.apache.cassandra.io.IVersionedSerializer.deserialize")
    )
    assertEquals(
      "io.github.jbellis.jvector.graph.GraphIndex.ramBytesUsed",
      analyzer.resolveMethodName("io.github.jbellis.jvector.graph.GraphIndex.ramBytesUsed")
    )
    assertEquals("java.util.Comparator.comparing", analyzer.resolveMethodName("java.util.Comparator.comparing"))
    assertEquals(
      "org.apache.cassandra.dht.RingPosition.compareTo",
      analyzer.resolveMethodName("org.apache.cassandra.dht.RingPosition.compareTo")
    )
    assertEquals(
      "com.google.common.collect.SortedSetMultimap.values",
      analyzer.resolveMethodName("com.google.common.collect.SortedSetMultimap.values")
    )

    // Operator-related Methods
    assertEquals("<operator>.assignmentDivision", analyzer.resolveMethodName("<operator>.assignmentDivision"))
    assertEquals("<operator>.not", analyzer.resolveMethodName("<operator>.not"))
    assertEquals("<operator>.plus", analyzer.resolveMethodName("<operator>.plus"))
    assertEquals("<operators>.assignmentModulo", analyzer.resolveMethodName("<operators>.assignmentModulo"))
    assertEquals(
      "<operators>.assignmentLogicalShiftRight",
      analyzer.resolveMethodName("<operators>.assignmentLogicalShiftRight")
    )
  }

  @Test
  def getParentMethodNameTest(): Unit = {
    val analyzer = getAnalyzer
    val cpg      = analyzer.cpg

    // Basic dynamic dispatched call
    val methodD1Call = cpg.method.fullName("D\\.methodD2.*").call.nameExact("methodD1").head
    assertEquals("D.methodD2", analyzer.parentMethodName(methodD1Call))

    // Call within a lambda "Runnable"
    val method2Call = cpg.method.fullName("AnonymousUsage\\$1\\.run.*").call.nameExact("method2").head
    assertEquals("AnonymousUsage$1.run", analyzer.parentMethodName(method2Call)) // or do we want `AnonymousUsage.foo`?

    // Anonymous class assigned to a field
    val fieldsLambda = cpg.method.fullNameExact("F$1.<init>:void()").l
    assertEquals("F$1.<init>", analyzer.parentMethodName(fieldsLambda.head))

    // Anonymous class within a nested class method
    val nestedClassLambda = cpg.method.fullName("AnonymousUsage\\$NestedClass.*").isLambda.l
    assertEquals("AnonymousUsage$NestedClass.getSomething", analyzer.parentMethodName(nestedClassLambda.head))

    // Call within a method in a nested class
    val printlnInMethod7Call = cpg.method.fullName(".*AInnerInner\\.method7.*").call.nameExact("println").head
    assertEquals("A$AInner$AInnerInner.method7", analyzer.parentMethodName(printlnInMethod7Call))

    // A lambda assigned to a field within an interface
    val interfaceField = cpg.method.where(_.file.name("Interface.java")).isLambda.head
    // this is the last method we can show, further up is the type name, which is not a 'parentMethodName'
    assertEquals("Interface.<lambda>0", analyzer.parentMethodName(interfaceField))
  }

  @Test
  def getFunctionLocationSingleMatchTest(): Unit = {
    val analyzer = getAnalyzer
    // This method has exactly one parameter (String input) and so exactly one matching overload
    val location = analyzer.getFunctionLocation("A.method2", java.util.List.of("input"))
    assertTrue(location.startLine > 0, "Start line should be positive")
    assertTrue(location.endLine >= location.startLine, "End line should not precede start line")
    assertTrue(
      location.code.contains("public String method2(String input)"),
      s"Method code should contain signature for 'method2(String)'; got:\n${location.code}"
    )
  }

  @Test
  def getFunctionLocationMissingParamTest(): Unit = {
    val analyzer = getAnalyzer
    // "A.method2" has two overloads, but neither takes zero parameters
    assertThrows(
      classOf[SymbolNotFoundException],
      () => {
        analyzer.getFunctionLocation("A.method2", java.util.Collections.emptyList())
      }
    )
  }

  @Test
  def getFunctionLocationMissingPackageTest(): Unit = {
    val analyzer = getAnalyzer
    analyzer.getFunctionLocation("Foo.bar", java.util.Collections.emptyList())
  }

  @Test
  def getFunctionLocationParamMismatchTest(): Unit = {
    val analyzer = getAnalyzer
    // "A.method2" has overloads, but none with param name "bogusParam"
    assertThrows(
      classOf[SymbolNotFoundException],
      () => {
        analyzer.getFunctionLocation("A.method2", java.util.List.of("bogusParam"))
      }
    )
  }

  @Test
  def getFunctionLocationNoSuchMethodTest(): Unit = {
    val analyzer = getAnalyzer
    // "A.noSuchMethod" does not exist at all
    assertThrows(
      classOf[SymbolNotFoundException],
      () => {
        analyzer.getFunctionLocation("A.noSuchMethod", java.util.Collections.emptyList())
      }
    )
  }

  @Test
  def getFunctionLocationConstructorTest(): Unit = {
    val analyzer = getAnalyzer
    // "B.<init>" is a constructor with no params
    val location = analyzer.getFunctionLocation("B.<init>", java.util.Collections.emptyList())
    assertTrue(location.startLine > 0 && location.endLine > 0)
    assertTrue(location.code.contains("public B()"), s"Constructor code:\n${location.code}")
  }

  @Test
  def codeUnitShortNameTest(): Unit = {
    val analyzer = getAnalyzer
    val file     = analyzer.toFile("A.java").get

    // Class
    val classA = CodeUnit.cls(file, "", "A")
    assertEquals("A", classA.shortName())
    assertEquals("A", classA.fqName()) // No package
    assertEquals("A", classA.identifier())
    assertTrue(classA.classUnit().toScala.contains(classA))

    // Nested Class
    val innerInner = CodeUnit.cls(file, "", "A$AInner$AInnerInner")
    assertEquals("A$AInner$AInnerInner", innerInner.shortName())
    assertEquals("A$AInner$AInnerInner", innerInner.fqName()) // No package
    assertEquals("A$AInner$AInnerInner", innerInner.identifier())
    assertTrue(innerInner.classUnit().toScala.contains(innerInner))

    // Method in top-level class
    val method1 = CodeUnit.fn(file, "", "A.method1")
    assertEquals("A.method1", method1.shortName())
    assertEquals("A.method1", method1.fqName())
    assertEquals("method1", method1.identifier())
    val method1Class = CodeUnit.cls(file, "", "A")
    assertTrue(method1.classUnit().toScala.contains(method1Class))

    // Method in deeply nested class
    val method7 = CodeUnit.fn(file, "", "A$AInner$AInnerInner.method7")
    assertEquals("A$AInner$AInnerInner.method7", method7.shortName())
    assertEquals("A$AInner$AInnerInner.method7", method7.fqName())
    assertEquals("method7", method7.identifier())
    val method7Class = CodeUnit.cls(file, "", "A$AInner$AInnerInner")
    assertTrue(method7.classUnit().toScala.contains(method7Class))

    // Field in class with package
    val filePackaged = analyzer.toFile("Packaged.java").get
    val fieldF       = CodeUnit.field(filePackaged, "io.github.jbellis.brokk", "Foo.f")
    assertEquals("Foo.f", fieldF.shortName())
    assertEquals("io.github.jbellis.brokk.Foo.f", fieldF.fqName())
    assertEquals("f", fieldF.identifier())
    val fieldFClass = CodeUnit.cls(filePackaged, "io.github.jbellis.brokk", "Foo")
    assertTrue(fieldF.classUnit().toScala.contains(fieldFClass))

    // Field in class without package
    val fileD  = analyzer.toFile("D.java").get
    val field1 = CodeUnit.field(fileD, "", "D.field1")
    assertEquals("D.field1", field1.shortName())
    assertEquals("D.field1", field1.fqName())
    assertEquals("field1", field1.identifier())
    val field1Class = CodeUnit.cls(fileD, "", "D")
    assertTrue(field1.classUnit().toScala.contains(field1Class))
  }

  /** Helper to get a prebuilt analyzer */
  private def getAnalyzer = {
    val tempFile = Files.createTempFile("brokk-java-cpg-", ".bin")
    tempFile.toFile.deleteOnExit()
    JavaAnalyzer(Path.of("src/test/resources/testcode-java"), java.util.Collections.emptySet[String](), tempFile)
  }

  @Test
  def parseFqNameDirectTest(): Unit = {
    val analyzer = getAnalyzer

    def check(
      fqn: String,
      expectedType: CodeUnitType,
      expectedPkg: String,
      expectedCls: String,
      expectedMem: String
    ): Unit = {
      val result     = analyzer.parseFqName(fqn, expectedType) // Call protected method directly
      val typeString = if (expectedType != null) expectedType.toString else "null"
      assertEquals(expectedPkg, result._1(), s"Package name mismatch for FQN [$fqn] with type [$typeString]")
      assertEquals(expectedCls, result._2(), s"Class name mismatch for FQN [$fqn] with type [$typeString]")
      assertEquals(expectedMem, result._3(), s"Member name mismatch for FQN [$fqn] with type [$typeString]")
    }

    // === CPG-Resolved Tests ===
    // Class in CPG, default package
    check("A", CodeUnitType.CLASS, "", "A", "")
    // Method in CPG, default package class
    check("A.method1", CodeUnitType.FUNCTION, "", "A", "method1")
    // Field in CPG, default package class (D.field1)
    check("D.field1", CodeUnitType.FIELD, "", "D", "field1")
    // Static field in CPG, default package class (E.sField)
    check("E.sField", CodeUnitType.FIELD, "", "E", "sField")
    // Static method in CPG, default package class (E.sMethod)
    check("E.sMethod", CodeUnitType.FUNCTION, "", "E", "sMethod")

    // Class in CPG, with package
    check("io.github.jbellis.brokk.Foo", CodeUnitType.CLASS, "io.github.jbellis.brokk", "Foo", "")
    // Method in CPG, with package
    check("io.github.jbellis.brokk.Foo.bar", CodeUnitType.FUNCTION, "io.github.jbellis.brokk", "Foo", "bar")

    // Nested class in CPG (A$AInner)
    check("A$AInner", CodeUnitType.CLASS, "", "A$AInner", "")
    // Method in nested class in CPG (A$AInner$AInnerInner.method7)
    check("A$AInner$AInnerInner.method7", CodeUnitType.FUNCTION, "", "A$AInner$AInnerInner", "method7")

    // Constructor in CPG
    check("A.<init>", CodeUnitType.FUNCTION, "", "A", "<init>")
    // Assuming Foo has a constructor, and it's in CPG
    check("io.github.jbellis.brokk.Foo.<init>", CodeUnitType.FUNCTION, "io.github.jbellis.brokk", "Foo", "<init>")

    // === Fallback Heuristic Tests (for FQNs not in CPG or unresolvable parts) ===
    // Synthetic member (e.g. enum's $values - typically a method)
    check(
      "org.fife.ui.autocomplete.AutoCompletionEvent$Type.$values",
      CodeUnitType.FUNCTION,
      "org.fife.ui.autocomplete",
      "AutoCompletionEvent$Type",
      "$values"
    )

    // Simple class, default package
    check("NonCpgClass", CodeUnitType.CLASS, "", "NonCpgClass", "")
    // Class with package
    check("noncpg.package.SomeClass", CodeUnitType.CLASS, "noncpg.package", "SomeClass", "")
    // Method in class with package
    check("noncpg.package.SomeClass.someMethod", CodeUnitType.FUNCTION, "noncpg.package", "SomeClass", "someMethod")
    // Field in class with package
    check("noncpg.package.SomeClass.someField", CodeUnitType.FIELD, "noncpg.package", "SomeClass", "someField")
    // Method in default package class
    check("NonCpgClass.itsMethod", CodeUnitType.FUNCTION, "", "NonCpgClass", "itsMethod")
    // Constructor (fallback)
    check("noncpg.package.SomeClass.<init>", CodeUnitType.FUNCTION, "noncpg.package", "SomeClass", "<init>")
    // Method with '$' in class name (fallback)
    check(
      "noncpg.package.My$ProdClass.factory$Method",
      CodeUnitType.FUNCTION,
      "noncpg.package",
      "My$ProdClass",
      "factory$Method"
    )
    // Method with '$' in method name (fallback)
    check(
      "noncpg.package.MyClass.method$WithDollar",
      CodeUnitType.FUNCTION,
      "noncpg.package",
      "MyClass",
      "method$WithDollar"
    )

    // Unconventional FQN: all lowercase package and class, expecting method
    check(
      "lower.case.package.lowerclass.methodName",
      CodeUnitType.FUNCTION,
      "lower.case.package",
      "lowerclass",
      "methodName"
    )
    // Unconventional FQN: class only, all lowercase, expecting method
    check("lowerclass.methodName", CodeUnitType.FUNCTION, "", "lowerclass", "methodName")
    // Unconventional FQN: class only, all lowercase, expecting class
    check("lowerclass", CodeUnitType.CLASS, "", "lowerclass", "")

    // === Edge Cases ===
    // Empty string (pass CodeUnitType.CLASS as an arbitrary non-null type for this test)
    check("", CodeUnitType.CLASS, "", "", "")
    // Null (pass CodeUnitType.CLASS as an arbitrary non-null type for this test)
    check(null, CodeUnitType.CLASS, "", "", "")

    // FQN that is only a method name (expecting function)
    check("orphanMethod", CodeUnitType.FUNCTION, "", "", "orphanMethod")
    // FQN that is only a class name (expecting class)
    check("OrphanClass", CodeUnitType.CLASS, "", "OrphanClass", "")
    // FQN that is package-like but ends there (expecting class, heuristic treats last part as class)
    check("some.package.name", CodeUnitType.CLASS, "some.package", "name", "")
  }
}
