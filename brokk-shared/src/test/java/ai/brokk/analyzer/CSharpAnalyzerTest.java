package ai.brokk.analyzer;

import static ai.brokk.testutil.AssertionHelperUtil.assertCodeEquals;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AnalyzerUtil;
import ai.brokk.IContextManager;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestCodeProject;
import ai.brokk.testutil.TestContextManager;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CSharpAnalyzerTest {

    private static final Logger logger = LoggerFactory.getLogger(CSharpAnalyzerTest.class);

    @Test
    void testCSharpInitializationAndSkeletons() {
        try (var project = TestCodeProject.fromResourceDir("testcode-cs", Languages.C_SHARP)) {
            IAnalyzer ana = new CSharpAnalyzer(project);
            assertInstanceOf(CSharpAnalyzer.class, ana);

            CSharpAnalyzer analyzer = (CSharpAnalyzer) ana;
            assertFalse(analyzer.isEmpty(), "Analyzer should have processed C# files");

            ProjectFile fileA = new ProjectFile(project.getRoot(), "A.cs");
            var classA_CU = CodeUnit.cls(fileA, "TestNamespace", "A");

            assertTrue(analyzer.getDeclarations(fileA).contains(classA_CU), "File A.cs should contain class A.");

            var skelA = analyzer.getSkeletons(fileA);
            assertFalse(skelA.isEmpty(), "Skeletons map for file A.cs should not be empty.");
            assertTrue(
                    skelA.containsKey(classA_CU),
                    "Skeleton map should contain top-level class A. Skeletons found: " + skelA.keySet());

            String classASkeleton = skelA.get(classA_CU);
            assertNotNull(classASkeleton, "Skeleton for class A should not be null.");
            assertTrue(
                    classASkeleton.trim().startsWith("public class A {")
                            || classASkeleton.trim().startsWith("public class A\n{"), // Allow for newline before brace
                    "Class A skeleton should start with 'public class A {'. Actual: '" + classASkeleton.trim() + "'");

            String expectedClassASkeleton =
                    """
                    public class A {
                      public int MyField;
                      public string MyProperty { get; set; }
                      public void MethodA() { … }
                      public void MethodA(int param) { … }
                      public A() { … }
                    }
                    """;
            assertCodeEquals(expectedClassASkeleton, classASkeleton, "Class A skeleton mismatch.");

            // Check that attribute_list capture does not result in top-level CodeUnits or signatures
            boolean hasAnnotationSignature = analyzer.withCodeUnitProperties(Map::keySet).stream()
                    .filter(cu -> cu.source().equals(fileA))
                    .anyMatch(cu -> "annotation".equals(cu.shortName())
                            || (cu.packageName() != null && cu.packageName().equals("annotation"))
                            || cu.identifier().startsWith("annotation"));
            assertFalse(hasAnnotationSignature, "No signatures from 'annotation' captures expected.");

            Set<CodeUnit> declarationsInA_Cs = analyzer.getDeclarations(fileA);
            assertTrue(
                    declarationsInA_Cs.contains(classA_CU),
                    "getDeclarationsInFile mismatch for file A.cs. Expected to contain " + classA_CU + ". Found: "
                            + declarationsInA_Cs);
            // Potentially add checks for members of classA_CU like MyField, MyProperty, MethodA if they are expected in
            // getDeclarationsInFile
            var classASkeletonOpt = AnalyzerUtil.getSkeleton(analyzer, classA_CU.fqName());
            assertTrue(
                    classASkeletonOpt.isPresent(),
                    "Skeleton for classA fqName '" + classA_CU.fqName() + "' should be found.");
            assertCodeEquals(classASkeleton, classASkeletonOpt.get(), "getSkeleton for classA fqName mismatch.");
        }
    }

    @Test
    void testCSharpMixedScopesAndNestedNamespaces() {
        try (var project = TestCodeProject.fromResourceDir("testcode-cs", Languages.C_SHARP)) {
            CSharpAnalyzer analyzer = new CSharpAnalyzer(project);

            ProjectFile mixedScopeFile = new ProjectFile(project.getRoot(), "MixedScope.cs");
            var skelMixed = analyzer.getSkeletons(mixedScopeFile); // Triggers parsing and populates internal maps
            assertFalse(skelMixed.isEmpty(), "Skeletons map for MixedScope.cs should not be empty.");

            CodeUnit topLevelClass = CodeUnit.cls(mixedScopeFile, "", "TopLevelClass");
            CodeUnit myTestAttributeClass = CodeUnit.cls(mixedScopeFile, "", "MyTestAttribute");
            CodeUnit namespacedClass = CodeUnit.cls(mixedScopeFile, "NS1", "NamespacedClass");
            CodeUnit nsInterface =
                    CodeUnit.cls(mixedScopeFile, "NS1", "INamespacedInterface"); // Interfaces are classes for CodeUnit
            CodeUnit topLevelStruct = CodeUnit.cls(mixedScopeFile, "", "TopLevelStruct"); // Structs are classes

            // Check if these CUs are present by querying for their skeletons or inclusion in file classes
            assertTrue(skelMixed.containsKey(topLevelClass), "Skeletons should contain TopLevelClass.");
            assertTrue(skelMixed.containsKey(myTestAttributeClass), "Skeletons should contain MyTestAttribute class.");
            assertTrue(skelMixed.containsKey(namespacedClass), "Skeletons should contain NS1.NamespacedClass.");
            assertTrue(skelMixed.containsKey(nsInterface), "Skeletons should contain NS1.INamespacedInterface.");
            assertTrue(skelMixed.containsKey(topLevelStruct), "Skeletons should contain TopLevelStruct.");

            Set<CodeUnit> actualDeclarationsMixed = analyzer.getDeclarations(mixedScopeFile);
            assertTrue(
                    actualDeclarationsMixed.contains(topLevelClass),
                    "MixedScope.cs declarations missing TopLevelClass. Found: " + actualDeclarationsMixed);
            assertTrue(
                    actualDeclarationsMixed.contains(myTestAttributeClass),
                    "MixedScope.cs declarations missing MyTestAttribute. Found: " + actualDeclarationsMixed);
            assertTrue(
                    actualDeclarationsMixed.contains(namespacedClass),
                    "MixedScope.cs declarations missing NamespacedClass. Found: " + actualDeclarationsMixed);
            assertTrue(
                    actualDeclarationsMixed.contains(nsInterface),
                    "MixedScope.cs declarations missing INamespacedInterface. Found: " + actualDeclarationsMixed);
            assertTrue(
                    actualDeclarationsMixed.contains(topLevelStruct),
                    "MixedScope.cs declarations missing TopLevelStruct. Found: " + actualDeclarationsMixed);
            // Consider adding checks for methods/fields if they are top-level or part of the expected set.

            ProjectFile nestedNamespacesFile = new ProjectFile(project.getRoot(), "NestedNamespaces.cs");
            var skelNested = analyzer.getSkeletons(nestedNamespacesFile);
            assertFalse(skelNested.isEmpty(), "Skeletons map for NestedNamespaces.cs should not be empty.");

            CodeUnit myNestedClass = CodeUnit.cls(nestedNamespacesFile, "Outer.Inner", "MyNestedClass");
            CodeUnit myNestedInterface = CodeUnit.cls(nestedNamespacesFile, "Outer.Inner", "IMyNestedInterface");
            CodeUnit outerClass = CodeUnit.cls(nestedNamespacesFile, "Outer", "OuterClass");
            CodeUnit anotherClass = CodeUnit.cls(nestedNamespacesFile, "AnotherTopLevelNs", "AnotherClass");

            assertTrue(skelNested.containsKey(myNestedClass), "Skeletons should contain Outer.Inner.MyNestedClass.");
            assertTrue(
                    skelNested.containsKey(myNestedInterface),
                    "Skeletons should contain Outer.Inner.IMyNestedInterface.");
            assertTrue(skelNested.containsKey(outerClass), "Skeletons should contain Outer.OuterClass.");
            assertTrue(
                    skelNested.containsKey(anotherClass), "Skeletons should contain AnotherTopLevelNs.AnotherClass.");

            Set<CodeUnit> actualDeclarationsNested = analyzer.getDeclarations(nestedNamespacesFile);
            assertTrue(
                    actualDeclarationsNested.contains(myNestedClass),
                    "NestedNamespaces.cs declarations missing MyNestedClass. Found: " + actualDeclarationsNested);
            assertTrue(
                    actualDeclarationsNested.contains(myNestedInterface),
                    "NestedNamespaces.cs declarations missing IMyNestedInterface. Found: " + actualDeclarationsNested);
            assertTrue(
                    actualDeclarationsNested.contains(outerClass),
                    "NestedNamespaces.cs declarations missing OuterClass. Found: " + actualDeclarationsNested);
            assertTrue(
                    actualDeclarationsNested.contains(anotherClass),
                    "NestedNamespaces.cs declarations missing AnotherClass. Found: " + actualDeclarationsNested);
            // Consider adding checks for methods/fields.
        }
    }

    @Test
    void testCSharpGetMethodSource() {
        try (var project = TestCodeProject.fromResourceDir("testcode-cs", Languages.C_SHARP)) {
            CSharpAnalyzer analyzer = new CSharpAnalyzer(project);
            assertFalse(analyzer.isEmpty());

            // Case 1: Single method (Constructor in this case, as it's simple and unique)
            // FQName for constructor of class A in TestNamespace is "TestNamespace.A.A"
            Optional<String> constructorSourceOpt = AnalyzerUtil.getSource(analyzer, "TestNamespace.A.A", true);
            assertTrue(constructorSourceOpt.isPresent(), "Source for constructor A.A should be present.");
            String expectedConstructorSource =
                    """
                        // Constructor
                                public A()\s
                                {
                                    MyField = 0;
                                    MyProperty = "default";
                                }""";
            assertCodeEquals(expectedConstructorSource, constructorSourceOpt.get(), "Constructor A.A source mismatch.");

            // Case 2: Multiple overloads for TestNamespace.A.MethodA
            Optional<String> methodASourcesOpt = AnalyzerUtil.getSource(analyzer, "TestNamespace.A.MethodA", true);
            assertTrue(
                    methodASourcesOpt.isPresent(), "Sources for TestNamespace.A.MethodA overloads should be present.");

            String expectedMethodAOverload1Source =
                    """
                        // Method
                                public void MethodA()\s
                                {
                                    // Method body
                                }""";
            String expectedMethodAOverload2Source =
                    """
                                // Overloaded Method
                                public void MethodA(int param)
                                {
                                    // Overloaded method body
                                    int x = param + 1;
                                }
                        """;
            String expectedCombinedMethodASource =
                    expectedMethodAOverload1Source + "\n\n" + expectedMethodAOverload2Source;

            assertCodeEquals(
                    expectedCombinedMethodASource,
                    methodASourcesOpt.get(),
                    "Combined sources for TestNamespace.A.MethodA mismatch.");

            // Case 3: Non-existent method
            Optional<String> nonExistentSourceOpt =
                    AnalyzerUtil.getSource(analyzer, "TestNamespace.A.NonExistentMethod", true);
            assertFalse(nonExistentSourceOpt.isPresent(), "Source for non-existent method should be empty.");

            // Case 4: Method in a nested namespace class
            Optional<String> nestedMethodSourceOpt =
                    AnalyzerUtil.getSource(analyzer, "Outer.Inner.MyNestedClass.NestedMethod", true);
            assertTrue(
                    nestedMethodSourceOpt.isPresent(),
                    "Source for Outer.Inner.MyNestedClass.NestedMethod should be present.");
            String expectedNestedMethodSource =
                    """
                public void NestedMethod() {}"""; // This is the exact content from
            // NestedNamespaces.cs
            assertCodeEquals(expectedNestedMethodSource, nestedMethodSourceOpt.get(), "NestedMethod source mismatch.");
        }
    }

    @Test
    void testCSharpInterfaceSkeleton() {
        try (var project = TestCodeProject.fromResourceDir("testcode-cs", Languages.C_SHARP)) {
            CSharpAnalyzer analyzer = new CSharpAnalyzer(project);
            IContextManager contextManager = new TestContextManager(project, analyzer);
            ProjectFile file = new ProjectFile(project.getRoot(), "AssetRegistrySA.cs");

            // Define expected CodeUnits
            var ifaceCU = CodeUnit.cls(file, "ConsumerCentricityPermission.Core.ISA", "IAssetRegistrySA");

            var validateCU = CodeUnit.fn(
                    file, "ConsumerCentricityPermission.Core.ISA", "IAssetRegistrySA.ValidateExistenceAsync");
            var canConnectCU =
                    CodeUnit.fn(file, "ConsumerCentricityPermission.Core.ISA", "IAssetRegistrySA.CanConnectAsync");
            var getDescCU = CodeUnit.fn(
                    file, "ConsumerCentricityPermission.Core.ISA", "IAssetRegistrySA.GetDeliveryPointDescriptionAsync");
            var messageCU = CodeUnit.cls(file, "ConsumerCentricityPermission.Core.ISA", "Message");

            // Basic assertions: declarations must be found
            Set<CodeUnit> declarationsInFile = analyzer.getDeclarations(file);
            assertTrue(declarationsInFile.contains(ifaceCU), "Interface CU missing. Found: " + declarationsInFile);
            assertTrue(
                    declarationsInFile.contains(validateCU),
                    "ValidateExistenceAsync CU missing. Found: " + declarationsInFile);
            assertTrue(
                    declarationsInFile.contains(canConnectCU),
                    "CanConnectAsync CU missing. Found: " + declarationsInFile);
            assertTrue(
                    declarationsInFile.contains(getDescCU),
                    "GetDeliveryPointDescriptionAsync CU missing. Found: " + declarationsInFile);

            // Skeleton reconstruction
            var skels = analyzer.getSkeletons(file);
            assertTrue(skels.containsKey(ifaceCU), "Interface skeleton missing. Keys: " + skels.keySet());
            // Methods inside interface are not top-level for getSkeletons, their skeletons are part of parent.
            // So we check their presence via getDeclarationsInFile and their textual form via the parent skeleton.

            // Verify the text of the interface skeleton
            String expectedIfaceSkeleton =
                    """
                public interface IAssetRegistrySA {
                  public Task<Message> ValidateExistenceAsync(Guid assetId) { … }
                  public Task<bool> CanConnectAsync() { … }
                  public Task<string> GetDeliveryPointDescriptionAsync(Guid deliveryPointId) { … }
                }
                """;
            assertCodeEquals(expectedIfaceSkeleton, skels.get(ifaceCU), "Interface skeleton mismatch.");

            // Verify getSkeleton for the interface FQ name
            Optional<String> ifaceSkeletonOpt = AnalyzerUtil.getSkeleton(analyzer, ifaceCU.fqName());
            assertTrue(ifaceSkeletonOpt.isPresent(), "Skeleton for IAssetRegistrySA FQ name should be found.");
            assertCodeEquals(
                    expectedIfaceSkeleton,
                    ifaceSkeletonOpt.get(),
                    "getSkeleton for IAssetRegistrySA FQ name mismatch.");

            // Create SkeletonFragment and assert its properties
            // and we want to avoid complex mocking for this CSharpAnalyzer test.
            var skeletonFragment = new ContextFragments.SummaryFragment(
                    contextManager, ifaceCU.fqName(), ContextFragment.SummaryType.CODEUNIT_SKELETON);

            // Assert that the skels map (directly from analyzer) contains the interface
            assertTrue(
                    skels.containsKey(ifaceCU),
                    "The skeletons map from analyzer should contain the interface CodeUnit as a key.");

            // Assert the description of the SkeletonFragment
            // The format() method is not asserted here as it would require a non-null IContextManager
            // and would re-fetch skeletons, which is beyond the scope of this analyzer unit test.
            assertEquals(
                    "Summary of " + ifaceCU.fqName(),
                    skeletonFragment.description().join(),
                    "SkeletonFragment.description() mismatch.");

            // Method source extraction for interface methods
            Optional<String> validateSourceOpt = AnalyzerUtil.getSource(analyzer, validateCU.fqName(), true);
            assertTrue(validateSourceOpt.isPresent(), "Source for ValidateExistenceAsync should be present.");
            assertCodeEquals(
                    "public Task<Message> ValidateExistenceAsync(Guid assetId);",
                    validateSourceOpt.get(),
                    "ValidateExistenceAsync source mismatch.");

            Optional<String> canConnectSourceOpt = AnalyzerUtil.getSource(analyzer, canConnectCU.fqName(), true);
            assertTrue(canConnectSourceOpt.isPresent(), "Source for CanConnectAsync should be present.");
            assertCodeEquals(
                    "public Task<bool> CanConnectAsync();",
                    canConnectSourceOpt.get(),
                    "CanConnectAsync source mismatch.");

            Optional<String> getDescSourceOpt = AnalyzerUtil.getSource(analyzer, getDescCU.fqName(), true);
            assertTrue(getDescSourceOpt.isPresent(), "Source for GetDeliveryPointDescriptionAsync should be present.");
            assertCodeEquals(
                    "public Task<string> GetDeliveryPointDescriptionAsync(Guid deliveryPointId);",
                    getDescSourceOpt.get(),
                    "GetDeliveryPointDescriptionAsync source mismatch.");
        }
    }

    @Test
    void testUtf8ByteOffsetHandling() {
        // This test verifies proper handling of files with UTF-8 BOM or other multi-byte characters
        // The issue: TreeSitter returns byte offsets, but String.substring requires char offsets
        // Without proper handling, names in non-ASCII files get truncated

        try (var project = TestCodeProject.fromResourceDir("testcode-cs", Languages.C_SHARP)) {
            CSharpAnalyzer analyzer = new CSharpAnalyzer(project);

            // GetTerminationRecordByIdHandler.cs contains a UTF-8 BOM
            ProjectFile bomFile = new ProjectFile(project.getRoot(), "GetTerminationRecordByIdHandler.cs");

            // Define expected CodeUnits with correct namespaces and class names
            CodeUnit handlerClass = CodeUnit.cls(
                    bomFile,
                    "ConsumerCentricityPermission.Core.Business.Handlers.TerminationRecordHandlers.Queries",
                    "GetTerminationRecordByIdHandler");

            CodeUnit requestClass = CodeUnit.cls(
                    bomFile,
                    "ConsumerCentricityPermission.Core.Business.Handlers.TerminationRecordHandlers.Queries",
                    "GetTerminationRecordByIdRequest");

            // Main assertions - these would fail if byte/char conversion is wrong
            // since we'd get "onsumerCentricity..." (missing first 'C') and
            // "etTerminationRecordByIdHandler" (missing first 'G')
            Set<CodeUnit> declarations = analyzer.getDeclarations(bomFile);

            assertTrue(
                    declarations.contains(handlerClass),
                    "File should contain class GetTerminationRecordByIdHandler with correct namespace");

            assertTrue(
                    declarations.contains(requestClass),
                    "File should contain class GetTerminationRecordByIdRequest with correct namespace");

            // Check that the namespace and class name in the full definition are correct
            Optional<CodeUnit> handlerDefinition =
                    analyzer.getDefinitions(handlerClass.fqName()).stream().findFirst();
            assertTrue(handlerDefinition.isPresent(), "Handler definition should be found");

            // Without proper byte/char handling, we'd get "onsumerCentricity..." and "etTermination..."
            assertEquals(
                    "ConsumerCentricityPermission.Core.Business.Handlers.TerminationRecordHandlers.Queries.GetTerminationRecordByIdHandler",
                    handlerDefinition.get().fqName(),
                    "Full qualified name should be correct without truncation");

            // Verify skeleton reconstruction works with proper names
            Optional<String> handlerSkeleton = AnalyzerUtil.getSkeleton(analyzer, handlerClass.fqName());
            assertTrue(handlerSkeleton.isPresent(), "Handler skeleton should be present");
            assertTrue(
                    handlerSkeleton.get().contains("public class GetTerminationRecordByIdHandler"),
                    "Handler skeleton should contain correct class name");
        }
    }

    @Test
    void testMultiAssignmentFieldSignatures() throws IOException {
        String code =
                """
                public class MultiField {
                    public int x = 1, y = 2;
                }
                """;
        try (var testProject =
                InlineTestProjectCreator.code(code, "MultiField.cs").build()) {
            CSharpAnalyzer analyzer = new CSharpAnalyzer(testProject);

            var xDefs = analyzer.getDefinitions("MultiField.x");
            assertEquals(1, xDefs.size());
            var xCu = xDefs.iterator().next();
            var xSkeleton = analyzer.getSkeleton(xCu);
            assertTrue(xSkeleton.isPresent());
            assertCodeEquals("public int x = 1;", xSkeleton.get());

            var yDefs = analyzer.getDefinitions("MultiField.y");
            assertEquals(1, yDefs.size());
            var yCu = yDefs.iterator().next();
            var ySkeleton = analyzer.getSkeleton(yCu);
            assertTrue(ySkeleton.isPresent());
            assertCodeEquals("public int y = 2;", ySkeleton.get());
        }
    }

    @Test
    void testMultiAssignmentWithAttributes() throws IOException {
        String code =
                """
                public class C {
                  [NonSerialized] public int x = 1, y = 2;
                }
                """;
        try (var testProject = InlineTestProjectCreator.code(code, "C.cs").build()) {
            CSharpAnalyzer analyzer = new CSharpAnalyzer(testProject);

            var xDefs = analyzer.getDefinitions("C.x");
            assertEquals(1, xDefs.size());
            var xCu = xDefs.iterator().next();
            var xSkeleton = analyzer.getSkeleton(xCu);
            assertTrue(xSkeleton.isPresent());
            // Should contain 'public' and NOT contain the attribute
            assertCodeEquals("public int x = 1;", xSkeleton.get());

            var yDefs = analyzer.getDefinitions("C.y");
            assertEquals(1, yDefs.size());
            var yCu = yDefs.iterator().next();
            var ySkeleton = analyzer.getSkeleton(yCu);
            assertTrue(ySkeleton.isPresent());
            assertCodeEquals("public int y = 2;", ySkeleton.get());
        }
    }

    @Test
    void testComplexFieldInitializerIsOmitted() throws IOException {
        String code =
                """
                public class ComplexField {
                    public object obj = new object();
                }
                """;
        try (var testProject =
                InlineTestProjectCreator.code(code, "ComplexField.cs").build()) {
            CSharpAnalyzer analyzer = new CSharpAnalyzer(testProject);

            var defs = analyzer.getDefinitions("ComplexField.obj");
            assertEquals(1, defs.size());
            var cu = defs.iterator().next();
            var skeleton = analyzer.getSkeleton(cu);
            assertTrue(skeleton.isPresent());
            // The object creation should be omitted, leaving only the declaration and semicolon
            assertCodeEquals("public object obj;", skeleton.get());
        }
    }

    @Test
    void testComplexFieldInitializersAreOmitted() {
        String code =
                """
                public class ComplexFields {
                    public object o = new object();
                    public int literal = 42;
                    public string s = "hello";
                    public int calculated = 1 + 1;
                }
                """;

        try (var testProject =
                InlineTestProjectCreator.code(code, "ComplexFields.cs").build()) {
            CSharpAnalyzer analyzer = new CSharpAnalyzer(testProject);

            var oDefs = analyzer.getDefinitions("ComplexFields.o");
            assertCodeEquals(
                    "public object o;",
                    analyzer.getSkeleton(oDefs.iterator().next()).get());

            var literalDefs = analyzer.getDefinitions("ComplexFields.literal");
            assertCodeEquals(
                    "public int literal = 42;",
                    analyzer.getSkeleton(literalDefs.iterator().next()).get());

            var sDefs = analyzer.getDefinitions("ComplexFields.s");
            assertCodeEquals(
                    "public string s = \"hello\";",
                    analyzer.getSkeleton(sDefs.iterator().next()).get());

            var calcDefs = analyzer.getDefinitions("ComplexFields.calculated");
            assertCodeEquals(
                    "public int calculated;",
                    analyzer.getSkeleton(calcDefs.iterator().next()).get());
        }
    }

    @Test
    void testExpressionFieldInitializerIsTruncated() throws IOException {
        String code =
                """
                public class ExprField {
                    public int x = 1 + 1;
                    public string s = "a" + "b";
                }
                """;
        try (var testProject =
                InlineTestProjectCreator.code(code, "ExprField.cs").build()) {
            CSharpAnalyzer analyzer = new CSharpAnalyzer(testProject);

            var xDefs = analyzer.getDefinitions("ExprField.x");
            assertCodeEquals(
                    "public int x;",
                    analyzer.getSkeleton(xDefs.iterator().next()).get());

            var sDefs = analyzer.getDefinitions("ExprField.s");
            assertCodeEquals(
                    "public string s;",
                    analyzer.getSkeleton(sDefs.iterator().next()).get());
        }
    }
}
