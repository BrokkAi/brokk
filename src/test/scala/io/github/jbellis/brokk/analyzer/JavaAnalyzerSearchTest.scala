package io.github.jbellis.brokk.analyzer

import io.github.jbellis.brokk.testutil.TestProject
import org.junit.jupiter.api.Assertions.{assertEquals, assertFalse, assertTrue}
import org.junit.jupiter.api.{BeforeAll, Test}

import java.nio.file.{Files, Path}
import java.util.Collections
import scala.jdk.javaapi.CollectionConverters.asScala

// Companion object for @BeforeAll setup
object JavaAnalyzerSearchTest {
  private var javaAnalyzer: JavaAnalyzer = scala.compiletime.uninitialized

  @BeforeAll
  def setup(): Unit = {
    val javaTestProject: TestProject = createTestProject("testcode-java", Language.JAVA)
    val tempCpgFile: Path            = Path.of(System.getProperty("java.io.tmpdir"), "brokk-java-search-test.bin")
    javaAnalyzer = new JavaAnalyzer(javaTestProject.getRoot, Collections.emptySet(), tempCpgFile)
  }

  private def createTestProject(subDir: String, lang: Language): TestProject = {
    val testDir = Path.of("src/test/resources", subDir)
    assertTrue(Files.exists(testDir), s"Test resource dir missing: $testDir")
    assertTrue(Files.isDirectory(testDir), s"$testDir is not a directory")
    new TestProject(testDir.toAbsolutePath, lang)
  }
}

class JavaAnalyzerSearchTest {

  // Use the analyzer from the companion object
  private def javaAnalyzer: JavaAnalyzer = JavaAnalyzerSearchTest.javaAnalyzer

  @Test
  def testSearchDefinitions_BasicPatterns(): Unit = {
    val eSymbols = javaAnalyzer.searchDefinitions("e")
    assertFalse(eSymbols.isEmpty, "Should find symbols containing 'e'.")

    val eFqNames = asScala(eSymbols).filter(_.isClass).map(_.fqName).toSet
    assertTrue(eFqNames.contains("E"), "Should find class 'E'")
    assertTrue(eFqNames.contains("UseE"), "Should find class 'UseE'")
    assertTrue(eFqNames.contains("AnonymousUsage"), "Should find class 'AnonymousUsage'")
    assertTrue(eFqNames.contains("Interface"), "Should find class 'Interface'")

    val method1Symbols = javaAnalyzer.searchDefinitions("method1")
    assertFalse(method1Symbols.isEmpty, "Should find symbols containing 'method1'.")
    val method1FqNames = asScala(method1Symbols).map(_.fqName).toSet
    assertTrue(method1FqNames.contains("A.method1"), "Should find 'A.method1'")

    val methodD1Symbols = javaAnalyzer.searchDefinitions("method.*1")
    assertFalse(methodD1Symbols.isEmpty, "Should find symbols matching 'method.*1'.")
    val methodD1FqNames = asScala(methodD1Symbols).map(_.fqName).toSet
    assertTrue(methodD1FqNames.contains("A.method1"), "Should find 'A.method1'")
    assertTrue(methodD1FqNames.contains("D.methodD1"), "Should find 'D.methodD1'")
  }

  @Test
  def testSearchDefinitions_CaseInsensitive(): Unit = {
    // Test that search is case-insensitive by comparing uppercase and lowercase patterns
    val upperE = javaAnalyzer.searchDefinitions("E")
    val lowerE = javaAnalyzer.searchDefinitions("e")

    val upperENames = asScala(upperE).map(_.fqName).toSet
    val lowerENames = asScala(lowerE).map(_.fqName).toSet

    // Both patterns should return identical results demonstrating case-insensitive behavior
    assertEquals(upperENames, lowerENames, "Case-insensitive search: 'E' and 'e' should return identical results")
    assertTrue(upperENames.contains("E"), "Should find class 'E' regardless of pattern case")
    assertTrue(upperENames.contains("UseE"), "Should find class 'UseE' regardless of pattern case")
    assertTrue(upperENames.contains("Interface"), "Should find class 'Interface' regardless of pattern case")

    // Test mixed case patterns - "UsE" should find symbols containing the substring "UsE" case-insensitively
    val mixedCase      = javaAnalyzer.searchDefinitions("UsE")
    val mixedCaseNames = asScala(mixedCase).map(_.fqName).toSet
    assertFalse(mixedCaseNames.isEmpty, "Mixed case 'UsE' should find symbols containing 'UsE'")
    assertTrue(mixedCaseNames.contains("UseE"), "Should find 'UseE' with pattern 'UsE'")
    // Verify case-insensitive behavior: "use" should find same results as "UsE"
    val lowerUse      = javaAnalyzer.searchDefinitions("use")
    val lowerUseNames = asScala(lowerUse).map(_.fqName).toSet
    assertEquals(mixedCaseNames, lowerUseNames, "Case-insensitive: 'UsE' and 'use' should return identical results")
  }

  @Test
  def testSearchDefinitions_RegexPatterns(): Unit = {
    val fieldSymbols = javaAnalyzer.searchDefinitions(".*field.*")
    assertFalse(fieldSymbols.isEmpty, "Should find symbols containing 'field'.")

    val fieldFqNames = asScala(fieldSymbols).map(_.fqName).toSet
    assertTrue(fieldFqNames.contains("D.field1"), "Should find 'D.field1'")
    assertTrue(fieldFqNames.contains("D.field2"), "Should find 'D.field2'")
    assertTrue(fieldFqNames.contains("E.iField"), "Should find 'E.iField'")
    assertTrue(fieldFqNames.contains("E.sField"), "Should find 'E.sField'")

    val methodSymbols = javaAnalyzer.searchDefinitions("method.*")
    val methodFqNames = asScala(methodSymbols).map(_.fqName).toSet
    assertTrue(methodFqNames.contains("A.method1"), "Should find 'A.method1'")
    assertTrue(methodFqNames.contains("A.method2"), "Should find 'A.method2'")
    assertTrue(methodFqNames.contains("D.methodD1"), "Should find 'D.methodD1'")
    assertTrue(methodFqNames.contains("D.methodD2"), "Should find 'D.methodD2'")
  }

  @Test
  def testSearchDefinitions_SpecificClasses(): Unit = {
    val aSymbols    = javaAnalyzer.searchDefinitions("A")
    val aClassNames = asScala(aSymbols).filter(_.isClass).map(_.fqName).toSet
    assertTrue(aClassNames.contains("A"), s"Should find class 'A'. Found classes: $aClassNames")

    val baseClassSymbols = javaAnalyzer.searchDefinitions(".*Class")
    val baseClassNames   = asScala(baseClassSymbols).filter(_.isClass).map(_.fqName).toSet
    assertTrue(baseClassNames.contains("BaseClass"), s"Should find 'BaseClass'. Found: $baseClassNames")
    assertTrue(baseClassNames.contains("CamelClass"), s"Should find 'CamelClass'. Found: $baseClassNames")
  }

  @Test
  def testSearchDefinitions_EmptyAndNonExistent(): Unit = {
    // Test empty pattern - should return no results
    val emptyPatternSymbols = javaAnalyzer.searchDefinitions("")
    assertTrue(emptyPatternSymbols.isEmpty, "Empty pattern should return no results")

    // Test non-existent pattern
    val nonExistentSymbols = javaAnalyzer.searchDefinitions("NonExistentPatternXYZ123")
    assertTrue(nonExistentSymbols.isEmpty, "Non-existent pattern should return no results")
  }

  @Test
  def testSearchDefinitions_NestedClasses(): Unit = {
    val innerSymbols = javaAnalyzer.searchDefinitions("Inner")
    assertFalse(innerSymbols.isEmpty, "Should find nested classes containing 'Inner'")

    val innerFqNames = asScala(innerSymbols).map(_.fqName).toSet
    assertTrue(innerFqNames.contains("A$AInner"), "Should find nested class 'A$AInner'")
    assertTrue(innerFqNames.contains("A$AInner$AInnerInner"), "Should find deeply nested class 'A$AInner$AInnerInner'")
  }

  @Test
  def testSearchDefinitions_Constructors(): Unit = {
    val constructorSymbols = javaAnalyzer.searchDefinitions("init")
    val constructorFqNames = asScala(constructorSymbols).map(_.fqName).toSet

    println(s"Found constructor symbols: $constructorFqNames")

    if (!constructorSymbols.isEmpty) {
      assertTrue(
        asScala(constructorSymbols).exists(_.fqName.contains("init")),
        s"Should find symbols containing 'init'. Found: $constructorFqNames"
      )
    } else {
      println("No constructor symbols found - this will be compared with TreeSitter behavior")
    }
  }

  @Test
  def testSearchDefinitions_PatternWrapping(): Unit = {
    val methodSymbols1 = javaAnalyzer.searchDefinitions("method2")
    val methodSymbols2 = javaAnalyzer.searchDefinitions(".*method2.*")

    val method2Names1 = asScala(methodSymbols1).map(_.fqName).toSet
    val method2Names2 = asScala(methodSymbols2).map(_.fqName).toSet

    assertEquals(method2Names1, method2Names2, "Auto-wrapped pattern should match explicit pattern")
    assertTrue(method2Names1.contains("A.method2"), "Should find 'A.method2'")
  }

  @Test
  def testGetDefinition(): Unit = {
    val classDDef = javaAnalyzer.getDefinition("D")
    assertTrue(classDDef.isPresent, "Should find definition for class 'D'")
    assertEquals("D", classDDef.get().fqName)
    assertTrue(classDDef.get().isClass)

    val method1Def = javaAnalyzer.getDefinition("A.method1")
    assertTrue(method1Def.isPresent, "Should find definition for method 'A.method1'")
    assertEquals("A.method1", method1Def.get().fqName)
    assertTrue(method1Def.get().isFunction)

    val field1Def = javaAnalyzer.getDefinition("D.field1")
    assertTrue(field1Def.isPresent, "Should find definition for field 'D.field1'")
    assertEquals("D.field1", field1Def.get().fqName)
    assertFalse(field1Def.get().isClass)
    assertFalse(field1Def.get().isFunction)

    val nonExistentDef = javaAnalyzer.getDefinition("NonExistentSymbol")
    assertFalse(nonExistentDef.isPresent, "Should not find definition for non-existent symbol")
  }

}
