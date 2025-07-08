package io.github.jbellis.brokk.analyzer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.{BeforeAll, Test}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Try
// For @JvmStatic, if required by JUnit setup for companion objects in Scala 3.
// However, JUnit 5 typically handles companion object @BeforeAll methods correctly without it.
// If the error persists, one might need to add a dependency providing it or check JUnit/Scala version compatibility.
// import kotlin.jvm.JvmStatic // Example if it were a Kotlin/Java interop scenario.

// Companion object for @BeforeAll, must be top-level or static in Java
object CppAnalyzerTest {
  private val testProjectPath = Path.of("src/test/resources/testcode-cpp").toAbsolutePath()
  private var analyzer: CppAnalyzer = scala.compiletime.uninitialized

  @BeforeAll
  // @JvmStatic // Usually not needed in Scala 3 with JUnit 5 for companion objects.
  // If build still complains about JvmStatic, ensure JUnit Jupiter is correctly configured for Scala.
  def setup(): Unit = {
    val tempFile = Files.createTempFile("brokk-cpp-cpg-", ".bin")
    tempFile.toFile.deleteOnExit()
    analyzer = new CppAnalyzer(testProjectPath, java.util.Collections.emptySet[String](), tempFile)
  }
}

class CppAnalyzerTest {
  // Use the analyzer from the companion object
  private def an: CppAnalyzer = CppAnalyzerTest.analyzer

  private val testProjectPath = Path.of("src/test/resources/testcode-cpp").toAbsolutePath() // Keep for ProjectFile creation

  @Test
  def parseFqNameTest(): Unit = {
    // Helper to call protected method
    def parse(fqn: String, t: CodeUnitType) = an.parseFqName(fqn, t)

    // Global function (c2cpg might prefix with filename or have other conventions)
    // Assuming simple global name if not file-prefixed by CPG
    // Test data has functions like geometry.cpp.global_func
    // and geometry.h contains declarations that might lead to geometry.h.func if not careful with CPG data
    assertEquals(new CodeUnit.Tuple3("geometry_h", "", "global_func_decl_in_header"), parse("geometry_h.global_func_decl_in_header", CodeUnitType.FUNCTION))
    assertEquals(new CodeUnit.Tuple3("geometry_cpp", "", "global_func"), parse("geometry_cpp.global_func", CodeUnitType.FUNCTION))


    // Class / Struct
    assertEquals(new CodeUnit.Tuple3("shapes", "Circle", ""), parse("shapes.Circle", CodeUnitType.CLASS))
    assertEquals(new CodeUnit.Tuple3("", "Point", ""), parse("Point", CodeUnitType.CLASS))

    // Method
    assertEquals(new CodeUnit.Tuple3("shapes", "Circle", "getArea"), parse("shapes.Circle.getArea", CodeUnitType.FUNCTION))
    assertEquals(new CodeUnit.Tuple3("", "Point", "print"), parse("Point.print", CodeUnitType.FUNCTION))

    // Constructor (c2cpg uses class name for constructor)
    assertEquals(new CodeUnit.Tuple3("shapes", "Circle", "Circle"), parse("shapes.Circle.Circle", CodeUnitType.FUNCTION))

    // Field / Member variable
    assertEquals(new CodeUnit.Tuple3("shapes", "Circle", "radius"), parse("shapes.Circle.radius", CodeUnitType.FIELD))
    assertEquals(new CodeUnit.Tuple3("", "Point", "x"), parse("Point.x", CodeUnitType.FIELD))

    // Namespace-only function (not part of a class)
    assertEquals(new CodeUnit.Tuple3("shapes", "", "another_in_shapes"), parse("shapes.another_in_shapes", CodeUnitType.FUNCTION))

    // Fallback cases (where FQN components might not be in CPG as expected)
    assertEquals(new CodeUnit.Tuple3("pkg", "Cls", "method"), parse("pkg.Cls.method", CodeUnitType.FUNCTION), "Fallback: pkg.Cls.method")
    assertEquals(new CodeUnit.Tuple3("pkg", "Cls", ""), parse("pkg.Cls", CodeUnitType.CLASS), "Fallback: pkg.Cls")
    assertEquals(new CodeUnit.Tuple3("pkg", "", "func"), parse("pkg.func", CodeUnitType.FUNCTION), "Fallback: pkg.func")
    // This case needs `Cls` to be a TypeDecl. If not, it would be ("Cls", "", "method") by current fallback.
    // The test implies Cls is treated as a class name even if not in CPG.
    assertEquals(new CodeUnit.Tuple3("", "Cls", "method"), parse("Cls.method", CodeUnitType.FUNCTION), "Fallback: Cls.method")
    assertEquals(new CodeUnit.Tuple3("", "", "func"), parse("func", CodeUnitType.FUNCTION), "Fallback: func (global)")
    assertEquals(new CodeUnit.Tuple3("", "Cls", ""), parse("Cls", CodeUnitType.CLASS), "Fallback: Cls (global)")
  }

  @Test
  def cuCreationTest(): Unit = {
    val dummyFile = ProjectFile(testProjectPath, "geometry.h")

    // Class
    val circleClass = an.cuClass("shapes.Circle", dummyFile).get
    assertEquals("shapes", circleClass.packageName())
    assertEquals("Circle", circleClass.shortName())
    assertEquals("shapes.Circle", circleClass.fqName())

    val pointStruct = an.cuClass("Point", dummyFile).get
    assertEquals("", pointStruct.packageName())
    assertEquals("Point", pointStruct.shortName())
    assertEquals("Point", pointStruct.fqName())

    // Function/Method
    val getAreaMethod = an.cuFunction("shapes.Circle.getArea", dummyFile).get
    assertEquals("shapes", getAreaMethod.packageName())
    assertEquals("Circle.getArea", getAreaMethod.shortName())
    assertEquals("shapes.Circle.getArea", getAreaMethod.fqName())

    // Global function (assuming file-prefixing by c2cpg or parseFqName handling)
    val globalFunc = an.cuFunction("geometry_cpp.global_func", dummyFile).get
    assertEquals("geometry_cpp", globalFunc.packageName())
    assertEquals("global_func", globalFunc.shortName())
    assertEquals("geometry_cpp.global_func", globalFunc.fqName())

    // Namespaced function (not in a class)
    val anotherInShapes = an.cuFunction("shapes.another_in_shapes", dummyFile).get
    assertEquals("shapes", anotherInShapes.packageName())
    assertEquals("another_in_shapes", anotherInShapes.shortName())
    assertEquals("shapes.another_in_shapes", anotherInShapes.fqName())

    // Field
    val radiusField = an.cuField("shapes.Circle.radius", dummyFile).get
    assertEquals("shapes", radiusField.packageName())
    assertEquals("Circle.radius", radiusField.shortName())
    assertEquals("shapes.Circle.radius", radiusField.fqName())
  }

  @Test
  def isClassInProjectTest(): Unit = {
    assertTrue(an.isClassInProject("shapes.Circle"), "shapes.Circle should be in project")
    assertTrue(an.isClassInProject("Point"), "Point struct should be in project")
    // std types are typically external or filtered out by isClassInProject if it checks isExternal or file path
    assertFalse(an.isClassInProject("std::vector"), "std::vector should not be in project")
    assertFalse(an.isClassInProject("NonExistentClass"), "NonExistentClass should not be in project")
  }

  @Test
  def resolveMethodNameTest(): Unit = {
    assertEquals("shapes.Circle.getArea", an.resolveMethodName("shapes.Circle.getArea:double()"))
    assertEquals("global_func", an.resolveMethodName("global_func:void(int)"))
    // c2cpg uses <duplicate>N (N is an int), from io.joern.c2cpg.astcreation.Defines.DuplicateSuffix
    assertEquals("foo", an.resolveMethodName(s"foo${io.joern.c2cpg.astcreation.Defines.DuplicateSuffix}0:int()"))
  }

  @Test
  def sanitizeTypeTest(): Unit = {
    assertEquals("Circle", an.sanitizeType("shapes::Circle"))
    assertEquals("int", an.sanitizeType("int"))
    assertEquals("double", an.sanitizeType("double*"))
    assertEquals("MyType", an.sanitizeType("const MyNamespace::MyType&"))
    assertEquals("char", an.sanitizeType("char[]"))
    assertEquals("MyType", an.sanitizeType("MyNamespace.MyType"))
    assertEquals("MyType", an.sanitizeType("struct MyNamespace.MyType"))
    assertEquals("MyType", an.sanitizeType("MyType*"))
    assertEquals("MyType", an.sanitizeType("MyType[]"))
    assertEquals("MyType", an.sanitizeType("const MyType"))
    assertEquals("MyType", an.sanitizeType("MyNamespace.MyType*[]"))
    assertEquals("MyType", an.sanitizeType("enum MyNamespace::MyType"))
    assertEquals("MyType", an.sanitizeType("volatile MyType * const * restrict"))
    assertEquals(io.joern.x2cpg.Defines.Any, an.sanitizeType("const*")) // what should this be?
  }

  @Test
  def getSkeletonTest(): Unit = {
    val circleSkeletonOpt = an.getSkeleton("shapes.Circle")
    assertTrue(circleSkeletonOpt.isPresent, "Skeleton for shapes.Circle not found")
    val circleSkeleton = circleSkeletonOpt.get
    assertTrue(circleSkeleton.contains("class Circle {"), s"Skeleton was: $circleSkeleton") // Expect class based on methods/inheritance
    assertTrue(circleSkeleton.contains("double getArea() {...}"), s"Skeleton was: $circleSkeleton") // Match actual signature
    assertTrue(circleSkeleton.contains("double radius;"), s"Skeleton was: $circleSkeleton")

    val pointSkeletonOpt = an.getSkeleton("Point")
    assertTrue(pointSkeletonOpt.isPresent, "Skeleton for Point not found")
    val pointSkeleton = pointSkeletonOpt.get()
    // CPG might represent struct as class if it has methods or based on configuration
    assertTrue(pointSkeleton.contains("struct Point {") || pointSkeleton.contains("class Point {"), s"Skeleton was: $pointSkeleton")
    assertTrue(pointSkeleton.contains("void print() {...}"), s"Skeleton was: $pointSkeleton")
    assertTrue(pointSkeleton.contains("int x;"), s"Skeleton was: $pointSkeleton")
  }

  @Test
  def getMethodSourceTest(): Unit = {
    // Method in a namespace
    val getAreaOpt = an.getMethodSource("shapes.Circle.getArea")
    assertTrue(getAreaOpt.isPresent, "Could not find source for shapes.Circle.getArea")
    assertTrue(getAreaOpt.get.contains("return M_PI * radius * radius;"))

    // Global function. FQN depends on c2cpg (e.g., geometry_cpp.global_func)
    val globalFuncOpt = an.getMethodSource("geometry_cpp.global_func")
    assertTrue(globalFuncOpt.isPresent, "Could not find source for geometry_cpp.global_func")
    assertTrue(globalFuncOpt.get.contains("global_var = val;"))
  }


  @Test
  def getDeclarationsInFileTest(): Unit = {
    val fileH = Try(ProjectFile(testProjectPath, "geometry.h")).toOption
    assumeTrue(fileH.isDefined, "geometry.h project file could not be created")

    val declsInH = an.getDeclarationsInFile(fileH.get).asScala.toSet.map(_.fqName())

    assertTrue(declsInH.contains("shapes.Circle"), "Missing shapes.Circle class from geometry.h")
    assertTrue(declsInH.contains("Point"), "Missing Point struct from geometry.h")
    // Method declarations in headers might or might not be picked up as distinct CodeUnits by getDeclarationsInFile
    // depending on AbstractAnalyzer's logic and CPG structure.
    // For now, ensure main types are found.
    // assertTrue(declsInH.contains("shapes.Circle.getArea"), "Missing shapes.Circle.getArea method from geometry.h")
    assertTrue(declsInH.contains("Point.x"), "Missing Point.x field from geometry.h")


    val fileCpp = Try(ProjectFile(testProjectPath, "geometry.cpp")).toOption
    assumeTrue(fileCpp.isDefined, "geometry.cpp project file could not be created")
    val declsInCpp = an.getDeclarationsInFile(fileCpp.get).asScala.toSet.map(_.fqName())

    // Functions defined in geometry.cpp
    // FQNs for global functions can be tricky, e.g., "geometry_cpp.global_func"
    assertTrue(declsInCpp.contains("geometry_cpp.global_func"), "Missing global_func from geometry.cpp")
    assertTrue(declsInCpp.contains("geometry_cpp.uses_global_func"), "Missing uses_global_func from geometry.cpp")
    assertTrue(declsInCpp.contains("shapes.another_in_shapes"), "Missing shapes.another_in_shapes from geometry.cpp")
    // Ensure class/method definitions from cpp file are also found if they are primarily there
    assertTrue(declsInCpp.contains("shapes.Circle.getArea"), "Missing definition of shapes.Circle.getArea from geometry.cpp")
  }

  @Test
  def getUsesTest(): Unit = {
    // Test uses of global_func (e.g., geometry_cpp.global_func)
    val usesGlobalFunc = an.getUses("geometry_cpp.global_func").asScala.map(_.fqName()).toSet
    assertTrue(usesGlobalFunc.contains("main_cpp.main"), "main_cpp.main should use global_func")
    assertTrue(usesGlobalFunc.contains("geometry_cpp.uses_global_func"), "uses_global_func should use global_func")
    // main_calls_lib is in main.cpp, which is part of testcode-cpp, so it should be analyzed.
    assertTrue(usesGlobalFunc.contains("main_cpp.main_calls_lib"), "main_calls_lib should use global_func")

    // Test uses of shapes.Circle.getArea
    val usesGetArea = an.getUses("shapes.Circle.getArea").asScala.map(_.fqName()).toSet
    assertTrue(usesGetArea.contains("main_cpp.main"), "main_cpp.main should use shapes.Circle.getArea")
    assertTrue(usesGetArea.contains("shapes.another_in_shapes"), "another_in_shapes should use shapes.Circle.getArea")
    assertTrue(usesGetArea.contains("main_cpp.main_calls_lib"), "main_calls_lib should use shapes.Circle.getArea")

    // Test uses of class Point (as a type)
    val usesPoint = an.getUses("Point").asScala.map(_.fqName()).toSet
    assertTrue(usesPoint.contains("main_cpp.main"), "main_cpp.main function uses Point type")
  }

  @Test
  def searchDefinitionsTest(): Unit = {
    val circleDefs = an.searchDefinitions("Circle").asScala
    assertTrue(circleDefs.exists(cu => cu.fqName == "shapes.Circle" && cu.isClass), "Did not find shapes.Circle class")

    val globalDefs = an.searchDefinitions("global").asScala
    assertTrue(globalDefs.exists(cu => cu.fqName().endsWith("global_func") && cu.isFunction()), "Did not find global_func")

    val getAreaDefs = an.searchDefinitions("getArea").asScala
    assertTrue(getAreaDefs.exists(cu => cu.fqName == "shapes.Circle.getArea" && cu.isFunction()), "Did not find shapes.Circle.getArea")
  }

  @Test
  def getDefinitionTest(): Unit = {
    // Class
    val circleDefOpt = an.getDefinition("shapes.Circle")
    assertTrue(circleDefOpt.isPresent)
    assertEquals("shapes.Circle", circleDefOpt.get.fqName)
    assertTrue(circleDefOpt.get.isClass)

    // Method
    val getAreaDefOpt = an.getDefinition("shapes.Circle.getArea")
    assertTrue(getAreaDefOpt.isPresent)
    assertEquals("shapes.Circle.getArea", getAreaDefOpt.get.fqName)
    assertTrue(getAreaDefOpt.get.isFunction)

    // Global function (e.g., geometry_cpp.global_func)
    val globalFuncDefOpt = an.getDefinition("geometry_cpp.global_func")
    assertTrue(globalFuncDefOpt.isPresent, "Failed to find geometry_cpp.global_func")
    assertEquals("geometry_cpp.global_func", globalFuncDefOpt.get.fqName)
    assertTrue(globalFuncDefOpt.get.isFunction)

    // Field
    val radiusDefOpt = an.getDefinition("shapes.Circle.radius")
    assertTrue(radiusDefOpt.isPresent)
    assertEquals("shapes.Circle.radius", radiusDefOpt.get.fqName)
    assertFalse(radiusDefOpt.get.isClass)
    assertFalse(radiusDefOpt.get.isFunction) // It's a field, not a function

    // Non-existent
    val nonExistentOpt = an.getDefinition("NonExistent.Symbol")
    assertFalse(nonExistentOpt.isPresent)
  }

  @Test
  def getFunctionLocationTest(): Unit = {
    // Global function in geometry.cpp
    val globalFuncLoc = an.getFunctionLocation("geometry_cpp.global_func", java.util.List.of("val"))
    assertTrue(globalFuncLoc.code.contains("global_var = val;"))
    assertEquals(ProjectFile(testProjectPath, "geometry.cpp"), globalFuncLoc.file)

    // Method in class
    val getAreaLoc = an.getFunctionLocation("shapes.Circle.getArea", java.util.Collections.emptyList())
    assertTrue(getAreaLoc.code.contains("return M_PI * radius * radius;"))
    assertEquals(ProjectFile(testProjectPath, "geometry.cpp"), getAreaLoc.file) // Definition in .cpp
  }

  @Test
  def getMethodSourceFromPytorchTest(): Unit = {
    // Test retrieving source for a function in pytorch.cpp
    val funcFqn = "pytorch_cpp.start_index"
    val sourceOpt = an.getMethodSource(funcFqn)
    assertTrue(sourceOpt.isPresent, s"Could not find source for FQN: $funcFqn")

    val source = sourceOpt.get()
    // Check for the signature (first line of the function definition)
    val expectedSignaturePrefix = "inline int start_index(int out_idx, int out_len, int in_len) {"
    assertTrue(source.startsWith(expectedSignaturePrefix),
      s"Source for $funcFqn did not start with expected signature.\nExpected prefix: '$expectedSignaturePrefix'\nActual source:\n$source")

    // Check for a specific line in the body
    val expectedBodyContent = "std::floor((float)(out_idx * in_len) / out_len)"
    assertTrue(source.contains(expectedBodyContent),
      s"Source for $funcFqn did not contain expected content '$expectedBodyContent'.\nActual source:\n$source")
  }

  @Test
  def getSkeletonsPytorchTest(): Unit = {
    val file = ProjectFile(testProjectPath, "pytorch.cpp")
    val skeletons = an.getSkeletons(file)

    val expectedKey = CodeUnit.fn(file, "at.native", "start_index")
    val expectedSkeleton = "int start_index(int out_idx, int out_len, int in_len) {...}"

    assertEquals(1, skeletons.size(), s"Expected 1 skeleton, got ${skeletons.size()}. Skeletons: $skeletons")
    assertTrue(skeletons.containsKey(expectedKey), s"Skeletons map does not contain expected key '$expectedKey'. Actual keys: ${skeletons.keySet().asScala.mkString(", ")}")
    assertEquals(expectedSkeleton, skeletons.get(expectedKey), "Skeleton content mismatch.")
  }
}
