package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.testutil.TestProject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.github.jbellis.brokk.testutil.TestProject.createTestProject;
import static org.junit.jupiter.api.Assertions.*;

public class TreeSitterAnalyzerRustTest {

    private static TestProject rsTestProject;
    private static RustAnalyzer rsAnalyzer;
    private static ProjectFile pointRsFile;

    // Helper to normalize multiline strings for comparison (strips leading/trailing whitespace from each line and joins)
    private static final java.util.function.Function<String, String> normalizeSource =
            (String s) -> s.lines().map(String::strip).filter(l -> !l.isEmpty()).collect(Collectors.joining("\n"));


    @BeforeAll
    static void setup() {
        rsTestProject = createTestProject("testcode-rs", Language.RUST);
        rsAnalyzer = new RustAnalyzer(rsTestProject);
        assertFalse(rsAnalyzer.isEmpty(), "RustAnalyzer should have processed Rust files");

        pointRsFile = new ProjectFile(rsTestProject.getRoot(), "Point.rs");
        assertTrue(pointRsFile.absPath().toFile().exists(), "Test file Point.rs not found at " + pointRsFile.absPath());

        // Verify basic parsing by checking if some expected CodeUnits exist
        // Package name is "" because Point.rs is directly under testcode-rs (project root for this test)
        Optional<CodeUnit> pointStructCU = rsAnalyzer.getDefinition("Point");
        assertTrue(pointStructCU.isPresent(), "CodeUnit for 'Point' struct should exist.");
        assertEquals(CodeUnit.cls(pointRsFile, "", "Point"), pointStructCU.get());

        Optional<CodeUnit> originConstCU = rsAnalyzer.getDefinition("_module_.ORIGIN");
        assertTrue(originConstCU.isPresent(), "CodeUnit for '_module_.ORIGIN' const should exist.");
        assertEquals(CodeUnit.field(pointRsFile, "", "_module_.ORIGIN"), originConstCU.get());

        // Test for newly added enum and trait
        Optional<CodeUnit> colorEnumCU = rsAnalyzer.getDefinition("Color");
        assertTrue(colorEnumCU.isPresent(), "CodeUnit for 'Color' enum should exist.");
        assertEquals(CodeUnit.cls(pointRsFile, "", "Color"), colorEnumCU.get());

        Optional<CodeUnit> shapeTraitCU = rsAnalyzer.getDefinition("Shape");
        assertTrue(shapeTraitCU.isPresent(), "CodeUnit for 'Shape' trait should exist.");
        assertEquals(CodeUnit.cls(pointRsFile, "", "Shape"), shapeTraitCU.get());
    }

    @Test
    void testRustInitializationAndSkeletons() {
        // Skeletons are reconstructed from signatures map.
        var skeletonsInPointRs = rsAnalyzer.getSkeletons(pointRsFile);
        assertFalse(skeletonsInPointRs.isEmpty(), "Skeletons map for Point.rs should not be empty.");

        CodeUnit pointCU = CodeUnit.cls(pointRsFile, "", "Point"); // From `pub struct Point`
        CodeUnit pointImplCU = CodeUnit.cls(pointRsFile, "", "Point"); // From `impl Point`
        // Note: `impl Point` and `struct Point` will both map to CU "Point". Signatures will be aggregated.
        // The SCM for impl_item captures the type being implemented as @class.name.
        // So for `impl Point`, simpleName is "Point". For `impl Drawable for Point`, simpleName is "Drawable".
        // The createCodeUnit logic will make `CodeUnit.cls(..., "Point")` for `impl Point`.
        // If the SCM for `impl_item` has `@class.name` point to `Point` in `impl Drawable for Point`, then `simpleName` for that block would be `Point`.
        // The provided SCM for `impl_item` uses `@impl.type.target` for `impl Trait for ActualType` -> `ActualType`.
        // Let's adjust the test expectation slightly if `impl Drawable for Point` creates a CU for `Point` rather than `Drawable`.
        // The current SCM has `type: [ ... (type_identifier) @impl.type.target ... ] @class.name`. This means @class.name will be `Point` for `impl Drawable for Point`.

        CodeUnit drawableTraitCU = CodeUnit.cls(pointRsFile, "", "Drawable"); // From `pub trait Drawable`

        assertTrue(skeletonsInPointRs.containsKey(pointCU), "Skeletons map should contain Point struct/impl. Found: " + skeletonsInPointRs.keySet());
        assertTrue(skeletonsInPointRs.containsKey(drawableTraitCU), "Skeletons map should contain Drawable trait. Found: " + skeletonsInPointRs.keySet());

        String pointSkeleton = skeletonsInPointRs.get(pointCU);
        assertNotNull(pointSkeleton);

        // Expected skeleton for Point (combining struct and its impl block, and impl Drawable for Point)
        // Fields in Point.rs do not have semicolons.
        // The TreeSitterAnalyzer's reconstructSkeletonRecursive method will list all signatures for "Point"
        // (struct, impl Point, impl Drawable for Point) first, then all children (fields and methods),
        // then a single closer.
        String expectedPointSkeleton = """
                                       pub struct Point {
                                       impl Point {
                                       impl Drawable for Point {
                                       impl Shape for Point {
                                       impl DefaultPosition for Point {
                                         pub x: i32
                                         pub y: i32
                                         pub fn new(x: i32, y: i32) -> Self { ... }
                                         pub fn translate(&mut self, dx: i32, dy: i32) { ... }
                                         fn draw(&self) { ... }
                                         const ID: u32 = 1;
                                         fn area(&self) -> f64 { ... }
                                       }
                                       """.stripIndent();
        assertEquals(normalizeSource.apply(expectedPointSkeleton), normalizeSource.apply(pointSkeleton), "Point struct/impl skeleton mismatch.");

        String drawableSkeleton = skeletonsInPointRs.get(drawableTraitCU);
        String expectedDrawableSkeleton = """
                                          pub trait Drawable {
                                            fn draw(&self);
                                          }
                                          """.stripIndent();
        assertEquals(normalizeSource.apply(expectedDrawableSkeleton), normalizeSource.apply(drawableSkeleton), "Drawable trait skeleton mismatch.");

        // Test top-level const
        CodeUnit originCU = CodeUnit.field(pointRsFile, "", "_module_.ORIGIN");
        assertTrue(skeletonsInPointRs.containsKey(originCU), "Skeletons map should contain _module_.ORIGIN.");
        String expectedOriginSkeleton = "pub const ORIGIN: Point = Point { x: 0, y: 0 };"; // SCM captures whole const_item
        assertEquals(normalizeSource.apply(expectedOriginSkeleton), normalizeSource.apply(skeletonsInPointRs.get(originCU)), "_module_.ORIGIN skeleton mismatch.");

        // Test top-level function
        CodeUnit distanceCU = CodeUnit.fn(pointRsFile, "", "distance");
        assertTrue(skeletonsInPointRs.containsKey(distanceCU), "Skeletons map should contain distance function.");
        String expectedDistanceSkeleton = """
                                          pub fn distance(p: &Point, q: &Point) -> f64 { ... }
                                          """.stripIndent();
        assertEquals(normalizeSource.apply(expectedDistanceSkeleton), normalizeSource.apply(skeletonsInPointRs.get(distanceCU)), "distance function skeleton mismatch.");

        // Test Enum Skeleton with Variants
        CodeUnit colorCU = CodeUnit.cls(pointRsFile, "", "Color");
        assertTrue(skeletonsInPointRs.containsKey(colorCU), "Skeletons map should contain Color enum.");
        String colorSkeleton = skeletonsInPointRs.get(colorCU);
        String expectedColorSkeleton = """
                                       pub enum Color {
                                         Red
                                         Green
                                         Blue
                                         Rgb(u8, u8, u8)
                                         Named { name: String }
                                       }
                                       """.stripIndent();
        assertEquals(normalizeSource.apply(expectedColorSkeleton), normalizeSource.apply(colorSkeleton), "Color enum skeleton mismatch.");

        // Test Trait Skeleton with Associated Constant and Method
        CodeUnit shapeTraitCU = CodeUnit.cls(pointRsFile, "", "Shape");
        assertTrue(skeletonsInPointRs.containsKey(shapeTraitCU), "Skeletons map should contain Shape trait.");
        String shapeTraitSkeleton = skeletonsInPointRs.get(shapeTraitCU);
        String expectedShapeTraitSkeleton = """
                                            pub trait Shape {
                                              const ID: u32;
                                              fn area(&self) -> f64;
                                            }
                                            """.stripIndent();
        assertEquals(normalizeSource.apply(expectedShapeTraitSkeleton), normalizeSource.apply(shapeTraitSkeleton), "Shape trait skeleton mismatch.");
    }

    @Test
    void testGetSkeletonHeader_Rust() {
        Optional<String> pointHeader = rsAnalyzer.getSkeletonHeader("Point");
        assertTrue(pointHeader.isPresent(), "Skeleton header for Point should be found.");
        // The header will be the first line of the combined skeleton, which starts with the struct definition
        assertEquals("pub struct Point {", pointHeader.get().trim());

        Optional<String> drawableHeader = rsAnalyzer.getSkeletonHeader("Drawable");
        assertTrue(drawableHeader.isPresent(), "Skeleton header for Drawable should be found.");
        assertEquals("pub trait Drawable {", drawableHeader.get().trim());

        Optional<String> originHeader = rsAnalyzer.getSkeletonHeader("_module_.ORIGIN");
        assertTrue(originHeader.isPresent(), "Skeleton header for _module_.ORIGIN should be found.");
        assertEquals("pub const ORIGIN: Point = Point { x: 0, y: 0 };", originHeader.get().trim());

        Optional<String> colorHeader = rsAnalyzer.getSkeletonHeader("Color");
        assertTrue(colorHeader.isPresent(), "Skeleton header for Color enum should be found.");
        assertEquals("pub enum Color {", colorHeader.get().trim());

        Optional<String> shapeHeader = rsAnalyzer.getSkeletonHeader("Shape");
        assertTrue(shapeHeader.isPresent(), "Skeleton header for Shape trait should be found.");
        assertEquals("pub trait Shape {", shapeHeader.get().trim());

        Optional<String> nonExistentHeader = rsAnalyzer.getSkeletonHeader("NonExistent");
        assertFalse(nonExistentHeader.isPresent(), "Skeleton header for NonExistent should be empty.");
    }

    @Test
    void testGetMembersInClass_Rust() {
        // Members of struct Point (fields, methods from impl Point, impl Drawable for Point, impl Shape for Point, impl DefaultPosition for Point)
        List<CodeUnit> pointMembers = rsAnalyzer.getMembersInClass("Point");
        Set<String> expectedPointMemberFqNames = Set.of(
                "Point.x", "Point.y",                      // fields from struct Point
                "Point.new", "Point.translate",            // methods from impl Point
                "Point.draw",                               // method from impl Drawable for Point
                "Point.ID",                                 // associated const from impl Shape for Point
                "Point.area",                               // method from impl Shape for Point
                "Point.DEFAULT_X", "Point.DEFAULT_Y"       // associated consts from DefaultPosition (via impl DefaultPosition for Point)
                // default_pos is also a member via DefaultPosition trait
        );
        Set<String> actualPointMemberFqNames = pointMembers.stream().map(CodeUnit::fqName).collect(Collectors.toSet());

        // Expected members: Point.x, Point.y, Point.new, Point.translate, Point.draw, Point.ID (from impl Shape), Point.area, Point.DEFAULT_X, Point.DEFAULT_Y, Point.default_pos
        // Total: 10
        // Note: The 'default_pos' method from DefaultPosition should also be a member of Point through its impl.
        // We need to ensure methods from traits (even with default implementations) are captured if the trait is implemented.
        // The current SCM for `impl_item` captures the type `Point` as the class for `impl DefaultPosition for Point`.
        // Children of `impl DefaultPosition for Point {}` (if any were explicitly defined there) would be members of `Point`.
        // To get members from the trait `DefaultPosition` itself (like default_pos), we need to handle trait inheritance/composition,
        // which is more complex. For now, getMembersInClass will primarily show direct declarations under the impl.
        // Let's adjust expectation: only `Point.ID` from `impl Shape` is directly in an impl for Point.
        // `DEFAULT_X` and `DEFAULT_Y` would appear if `impl DefaultPosition for Point` explicitly defined them or if
        // we had logic to pull in trait defaults. For now, `getMembersInClass("Point")` will find fields from struct Point,
        // methods from `impl Point`, `impl Drawable for Point`, `impl Shape for Point`.
        // It will NOT automatically find `Point.DEFAULT_X` unless `impl DefaultPosition for Point` overrode it or re-declared it.
        // The current `RustAnalyzer` and query don't explicitly resolve trait items into implementing types' member lists
        // unless they are re-declared in the `impl` block.

        // Re-evaluating expected members for "Point":
        // Fields: Point.x, Point.y
        // Methods in `impl Point`: Point.new, Point.translate
        // Methods in `impl Drawable for Point`: Point.draw
        // Const in `impl Shape for Point`: Point.ID
        // Method in `impl Shape for Point`: Point.area
        // Total 7 directly defined/associated members under 'Point' through its struct and impls.
        Set<String> expectedPointMembersStrict = Set.of(
                "Point.x", "Point.y",
                "Point.new", "Point.translate",
                "Point.draw",
                "Point.ID", // from `impl Shape for Point`
                "Point.area"  // from `impl Shape for Point`
        );
        assertEquals(expectedPointMembersStrict.size(), actualPointMemberFqNames.size(),
                     "Point member count mismatch. Expected: " + expectedPointMembersStrict + ", Got: " + actualPointMemberFqNames);
        assertTrue(actualPointMemberFqNames.containsAll(expectedPointMembersStrict),
                   "Point members mismatch. Expected: " + expectedPointMembersStrict + ", Got: " + actualPointMemberFqNames);


        // Members of trait Drawable (methods)
        List<CodeUnit> drawableMembers = rsAnalyzer.getMembersInClass("Drawable");
        CodeUnit drawMethodInTrait = CodeUnit.fn(pointRsFile, "", "Drawable.draw");
        assertTrue(drawableMembers.contains(drawMethodInTrait), "Drawable members should include draw method signature.");
        assertEquals(1, drawableMembers.size(), "Drawable trait should have 1 member (draw signature).");

        // Members of enum Color (variants)
        List<CodeUnit> colorMembers = rsAnalyzer.getMembersInClass("Color");
        Set<String> expectedColorMemberFqNames = Set.of(
                "Color.Red", "Color.Green", "Color.Blue", "Color.Rgb", "Color.Named"
        );
        Set<String> actualColorMemberFqNames = colorMembers.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertEquals(expectedColorMemberFqNames.size(), actualColorMemberFqNames.size(), "Color enum member (variant) count mismatch.");
        assertTrue(actualColorMemberFqNames.containsAll(expectedColorMemberFqNames), "Color enum members (variants) mismatch.");

        // Members of trait Shape (associated const and method)
        List<CodeUnit> shapeMembers = rsAnalyzer.getMembersInClass("Shape");
        Set<String> expectedShapeMemberFqNames = Set.of("Shape.ID", "Shape.area");
        Set<String> actualShapeMemberFqNames = shapeMembers.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertEquals(expectedShapeMemberFqNames.size(), actualShapeMemberFqNames.size(), "Shape trait member count mismatch.");
        assertTrue(actualShapeMemberFqNames.containsAll(expectedShapeMemberFqNames), "Shape trait members mismatch.");

        List<CodeUnit> nonClassMembers = rsAnalyzer.getMembersInClass("distance"); // 'distance' is a function
        assertTrue(nonClassMembers.isEmpty(), "A function (distance) should not have members.");

        List<CodeUnit> nonExistentMembers = rsAnalyzer.getMembersInClass("NonExistent");
        assertTrue(nonExistentMembers.isEmpty(), "NonExistent symbol should have no members.");
    }

    @Test
    void testGetFileFor_Rust() {
        Optional<ProjectFile> pointFile = rsAnalyzer.getFileFor("Point"); // Struct Point
        assertTrue(pointFile.isPresent());
        assertEquals(pointRsFile, pointFile.get());

        Optional<ProjectFile> translateMethodFile = rsAnalyzer.getFileFor("Point.translate"); // Method
        assertTrue(translateMethodFile.isPresent());
        assertEquals(pointRsFile, translateMethodFile.get());

        Optional<ProjectFile> originConstFile = rsAnalyzer.getFileFor("_module_.ORIGIN"); // Const
        assertTrue(originConstFile.isPresent());
        assertEquals(pointRsFile, originConstFile.get());

        Optional<ProjectFile> distanceFuncFile = rsAnalyzer.getFileFor("distance"); // Free function
        assertTrue(distanceFuncFile.isPresent());
        assertEquals(pointRsFile, distanceFuncFile.get());

        // For enum variant
        Optional<ProjectFile> redVariantFile = rsAnalyzer.getFileFor("Color.Red");
        assertTrue(redVariantFile.isPresent());
        assertEquals(pointRsFile, redVariantFile.get());

        // For associated const in trait
        Optional<ProjectFile> shapeIdFile = rsAnalyzer.getFileFor("Shape.ID");
        assertTrue(shapeIdFile.isPresent());
        assertEquals(pointRsFile, shapeIdFile.get());

        // For associated const in impl
        Optional<ProjectFile> pointIdFile = rsAnalyzer.getFileFor("Point.ID");
        assertTrue(pointIdFile.isPresent());
        assertEquals(pointRsFile, pointIdFile.get());

        Optional<ProjectFile> nonExistentFile = rsAnalyzer.getFileFor("NonExistent");
        assertFalse(nonExistentFile.isPresent());
    }

    @Test
    void testGetDefinition_Rust() {
        Optional<CodeUnit> pointDef = rsAnalyzer.getDefinition("Point");
        assertTrue(pointDef.isPresent());
        assertEquals(CodeUnit.cls(pointRsFile, "", "Point"), pointDef.get());

        Optional<CodeUnit> newMethodDef = rsAnalyzer.getDefinition("Point.new");
        assertTrue(newMethodDef.isPresent());
        assertEquals(CodeUnit.fn(pointRsFile, "", "Point.new"), newMethodDef.get());

        Optional<CodeUnit> originDef = rsAnalyzer.getDefinition("_module_.ORIGIN");
        assertTrue(originDef.isPresent());
        assertEquals(CodeUnit.field(pointRsFile, "", "_module_.ORIGIN"), originDef.get());

        // Enum variant
        Optional<CodeUnit> redVariantDef = rsAnalyzer.getDefinition("Color.Red");
        assertTrue(redVariantDef.isPresent());
        assertEquals(CodeUnit.field(pointRsFile, "", "Color.Red"), redVariantDef.get());

        // Associated const in trait
        Optional<CodeUnit> shapeIdDef = rsAnalyzer.getDefinition("Shape.ID");
        assertTrue(shapeIdDef.isPresent());
        assertEquals(CodeUnit.field(pointRsFile, "", "Shape.ID"), shapeIdDef.get());

        // Associated const in impl
        Optional<CodeUnit> pointIdDef = rsAnalyzer.getDefinition("Point.ID");
        assertTrue(pointIdDef.isPresent());
        assertEquals(CodeUnit.field(pointRsFile, "", "Point.ID"), pointIdDef.get());

        Optional<CodeUnit> nonExistentDef = rsAnalyzer.getDefinition("NonExistent");
        assertFalse(nonExistentDef.isPresent());
    }

    @Test
    void testSearchDefinitions_Rust() {
        List<CodeUnit> pointResults = rsAnalyzer.searchDefinitions("Point");
        Set<String> pointFqNames = pointResults.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        // Expected: Point (struct), Point.x, Point.y (fields), Point.new, Point.translate, Point.draw (methods)
        // Point.ID, Point.area (from impl Shape for Point)
        // _module_.ORIGIN (type is Point), distance (param type is Point)
        // The search is on FQ name.
        Set<String> expectedPointRelatedFqNs = Set.of(
                "Point", "Point.x", "Point.y", "Point.new", "Point.translate", "Point.draw",
                "Point.ID", "Point.area"
                // Point.DEFAULT_X, Point.DEFAULT_Y, Point.default_pos are not directly in `impl Point`
        );
        assertTrue(pointFqNames.containsAll(expectedPointRelatedFqNs),
                   "Search for 'Point' missing expected FQNs. Expected to contain: " + expectedPointRelatedFqNs + ", Got: " + pointFqNames);
        // Other FQNs containing "Point" (e.g. parameters like in `distance`) are not expected by default with `fqName().contains()`
        // if the type information is not part of the FQN for parameters.

        List<CodeUnit> drawResults = rsAnalyzer.searchDefinitions("draw");
        Set<String> drawFqNames = drawResults.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(drawFqNames.contains("Drawable.draw")); // From trait definition
        assertTrue(drawFqNames.contains("Point.draw"));    // From impl Drawable for Point
        assertEquals(2, drawFqNames.size());

        List<CodeUnit> originResults = rsAnalyzer.searchDefinitions("ORIGIN");
        assertTrue(originResults.stream().anyMatch(cu -> "_module_.ORIGIN".equals(cu.fqName())));
        assertEquals(1, originResults.size());

        // Search for enum variant
        List<CodeUnit> redResults = rsAnalyzer.searchDefinitions("Red");
        assertTrue(redResults.stream().anyMatch(cu -> "Color.Red".equals(cu.fqName())));

        // Search for associated const
        List<CodeUnit> idResults = rsAnalyzer.searchDefinitions("ID"); // Will find Shape.ID, Point.ID, Circle.ID
        Set<String> idFqNames = idResults.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(idFqNames.contains("Shape.ID"));
        assertTrue(idFqNames.contains("Point.ID"));
        assertTrue(idFqNames.contains("Circle.ID"));
        assertEquals(3, idFqNames.size());
    }

    @Test
    void testGetClassSource_Rust() {
        // Source for struct Point
        String pointSource = rsAnalyzer.getClassSource("Point");
        String expectedPointSource = """
                                     pub struct Point {
                                         pub x: i32,
                                         pub y: i32,
                                     }
                                     """; // This is the struct_item node's text
        assertEquals(normalizeSource.apply(expectedPointSource), normalizeSource.apply(pointSource));

        // Source for trait Drawable
        String drawableSource = rsAnalyzer.getClassSource("Drawable");
        String expectedDrawableSource = """
                                        pub trait Drawable {
                                            fn draw(&self);
                                        }
                                        """; // This is the trait_item node's text
        assertEquals(normalizeSource.apply(expectedDrawableSource), normalizeSource.apply(drawableSource));

        // Source for enum Color
        String colorSource = rsAnalyzer.getClassSource("Color");
        String expectedColorSource = """
                                     pub enum Color {
                                         Red,
                                         Green,
                                         Blue,
                                         Rgb(u8, u8, u8),
                                         Named { name: String },
                                     }
                                     """;
        assertEquals(normalizeSource.apply(expectedColorSource), normalizeSource.apply(colorSource));

        // Source for trait Shape
        String shapeSource = rsAnalyzer.getClassSource("Shape");
        String expectedShapeSource = """
                                     pub trait Shape {
                                         const ID: u32; // Associated constant in trait
                                         fn area(&self) -> f64;
                                     }
                                     """;
        assertEquals(normalizeSource.apply(expectedShapeSource), normalizeSource.apply(shapeSource));


        // Note: For `impl Point` or `impl Drawable for Point`, getClassSource might be tricky.
        // The CodeUnit "Point" combines struct and impls in its skeleton.
        // getClassSource typically returns the primary definition (struct for "Point").
        // If `impl Item` also created a distinct CodeUnit.cls (e.g. `ImplPoint`), that could be queried.
        // Current RustAnalyzer creates `CodeUnit.cls(file, pkg, "Point")` for `impl Point`.
        // This means `getDefinition("Point")` would return the struct definition's CodeUnit if sorted first,
        // or the impl's CU if that's how `uniqueCodeUnitList` resolves it.
        // The base `getClassSource` uses `getDefinition`, so it depends on which CU is returned for "Point".
        // Let's assume the `struct_item` is primary for `getClassSource("Point")`.

        assertThrows(SymbolNotFoundException.class, () -> rsAnalyzer.getClassSource("distance")); // function, not class
        assertThrows(SymbolNotFoundException.class, () -> rsAnalyzer.getClassSource("_module_.ORIGIN")); // field, not class
        assertThrows(SymbolNotFoundException.class, () -> rsAnalyzer.getClassSource("Color.Red")); // enum variant, not class
        assertThrows(SymbolNotFoundException.class, () -> rsAnalyzer.getClassSource("Shape.ID")); // associated const, not class
        assertThrows(SymbolNotFoundException.class, () -> rsAnalyzer.getClassSource("NonExistent"));
    }

    @Test
    void testGetMethodSource_Rust() throws IOException {
        Optional<String> translateSourceOpt = rsAnalyzer.getMethodSource("Point.translate");
        assertTrue(translateSourceOpt.isPresent(), "Source for Point.translate should be found.");
        String expectedTranslateSource = """
                                         pub fn translate(&mut self, dx: i32, dy: i32) {
                                                 self.x += dx;
                                                 self.y += dy;
                                             }
                                         """;
        assertEquals(normalizeSource.apply(expectedTranslateSource), normalizeSource.apply(translateSourceOpt.get()));

        Optional<String> drawInTraitSourceOpt = rsAnalyzer.getMethodSource("Drawable.draw");
        assertTrue(drawInTraitSourceOpt.isPresent(), "Source for Drawable.draw (trait method) should be found.");
        String expectedDrawInTraitSource = "fn draw(&self);"; // From trait definition
        assertEquals(normalizeSource.apply(expectedDrawInTraitSource), normalizeSource.apply(drawInTraitSourceOpt.get()));

        Optional<String> drawInImplSourceOpt = rsAnalyzer.getMethodSource("Point.draw");
        assertTrue(drawInImplSourceOpt.isPresent(), "Source for Point.draw (impl method) should be found.");
        String expectedDrawInImplSource = """
                                          fn draw(&self) {
                                                  // Simulate drawing
                                                  println!("Drawing point at ({}, {})", self.x, self.y);
                                              }
                                          """;
        assertEquals(normalizeSource.apply(expectedDrawInImplSource), normalizeSource.apply(drawInImplSourceOpt.get()));


        Optional<String> distanceSourceOpt = rsAnalyzer.getMethodSource("distance"); // Free function
        assertTrue(distanceSourceOpt.isPresent(), "Source for distance function should be found.");
        String expectedDistanceSource = """
                                        pub fn distance(p: &Point, q: &Point) -> f64 {
                                            let dx = (p.x - q.x) as f64;
                                            let dy = (p.y - q.y) as f64;
                                            (dx * dx + dy * dy).sqrt()
                                        }
                                        """;
        assertEquals(normalizeSource.apply(expectedDistanceSource), normalizeSource.apply(distanceSourceOpt.get()));

        // Test getMethodSource for associated const (should be empty as it's not a method)
        Optional<String> shapeIdSourceOpt = rsAnalyzer.getMethodSource("Shape.ID");
        assertFalse(shapeIdSourceOpt.isPresent(), "Associated constant Shape.ID should not return source via getMethodSource.");

        Optional<String> nonExistentSourceOpt = rsAnalyzer.getMethodSource("NonExistent.method");
        assertFalse(nonExistentSourceOpt.isPresent());

        Optional<String> classAsMethodSourceOpt = rsAnalyzer.getMethodSource("Point"); // Class, not method
        assertFalse(classAsMethodSourceOpt.isPresent());
    }

    @Test
    void testGetSymbols_Rust() {
        // Using Point.rs file
        CodeUnit pointCU = rsAnalyzer.getDefinition("Point").orElseThrow();
        CodeUnit drawableCU = rsAnalyzer.getDefinition("Drawable").orElseThrow();
        CodeUnit originCU = rsAnalyzer.getDefinition("_module_.ORIGIN").orElseThrow();
        CodeUnit distanceCU = rsAnalyzer.getDefinition("distance").orElseThrow();
        CodeUnit colorCU = rsAnalyzer.getDefinition("Color").orElseThrow();
        CodeUnit shapeCU = rsAnalyzer.getDefinition("Shape").orElseThrow();
        CodeUnit circleCU = rsAnalyzer.getDefinition("Circle").orElseThrow();


        // Test with Point struct (includes its fields and methods from impls)
        Set<String> pointSymbols = rsAnalyzer.getSymbols(Set.of(pointCU));
        // Expected: Point, x, y, new, translate, draw (from Drawable), ID (from Shape), area (from Shape)
        Set<String> expectedPointSymbols = Set.of("Point", "x", "y", "new", "translate", "draw", "ID", "area");
        assertEquals(expectedPointSymbols, pointSymbols, "Symbols for Point CU mismatch.");

        // Test with Drawable trait (includes its method signatures)
        Set<String> drawableSymbols = rsAnalyzer.getSymbols(Set.of(drawableCU));
        Set<String> expectedDrawableSymbols = Set.of("Drawable", "draw");
        assertEquals(expectedDrawableSymbols, drawableSymbols);

        // Test with _module_.ORIGIN const
        Set<String> originSymbols = rsAnalyzer.getSymbols(Set.of(originCU));
        assertEquals(Set.of("ORIGIN"), originSymbols);

        // Test with distance function
        Set<String> distanceSymbols = rsAnalyzer.getSymbols(Set.of(distanceCU));
        assertEquals(Set.of("distance"), distanceSymbols);

        // Test with Color enum (includes its variants)
        Set<String> colorSymbols = rsAnalyzer.getSymbols(Set.of(colorCU));
        Set<String> expectedColorSymbols = Set.of("Color", "Red", "Green", "Blue", "Rgb", "Named");
        assertEquals(expectedColorSymbols, colorSymbols);

        // Test with Shape trait (includes its associated const and method)
        Set<String> shapeSymbols = rsAnalyzer.getSymbols(Set.of(shapeCU));
        Set<String> expectedShapeSymbols = Set.of("Shape", "ID", "area");
        assertEquals(expectedShapeSymbols, shapeSymbols);

        // Test with Circle struct (includes its fields and methods/consts from impl Shape for Circle)
        Set<String> circleSymbols = rsAnalyzer.getSymbols(Set.of(circleCU));
        Set<String> expectedCircleSymbols = Set.of("Circle", "center", "radius", "ID", "area"); // ID and area from impl Shape for Circle
        assertEquals(expectedCircleSymbols, circleSymbols, "Symbols for Circle CU mismatch");


        // Test with multiple sources
        Set<CodeUnit> combinedSources = Set.of(pointCU, drawableCU, originCU, distanceCU, colorCU, shapeCU, circleCU);
        Set<String> combinedSymbols = rsAnalyzer.getSymbols(combinedSources);

        // Initialize as a mutable set
        Set<String> expectedCombined = new java.util.HashSet<>();

        // The expectedCombined should be the union of all individual symbol sets.
        expectedCombined.addAll(expectedPointSymbols);
        expectedCombined.addAll(expectedDrawableSymbols);
        expectedCombined.addAll(originSymbols);
        expectedCombined.addAll(distanceSymbols);
        expectedCombined.addAll(expectedColorSymbols);
        expectedCombined.addAll(expectedShapeSymbols);
        expectedCombined.addAll(expectedCircleSymbols);

        assertEquals(expectedCombined, combinedSymbols, "Combined symbols mismatch.");
    }

    @Test
    void testDeterminePackageName_RustSpecificLayouts() throws IOException {
        // Helper to call determinePackageName, as its other params are not used by RustAnalyzer's impl
        java.util.function.Function<ProjectFile, String> getPkgName =
                (pf) -> rsAnalyzer.determinePackageName(pf, null, null, "");

        // 1. File at project root (like Point.rs)
        ProjectFile pointFile = new ProjectFile(rsTestProject.getRoot(), "Point.rs");
        assertEquals("", getPkgName.apply(pointFile), "Package name for Point.rs at root should be empty.");

        // 2. src/lib.rs
        Path libRsPath = rsTestProject.getRoot().resolve("src").resolve("lib.rs");
        libRsPath.getParent().toFile().mkdirs(); // Ensure src directory exists
        libRsPath.toFile().createNewFile(); // Create dummy file
        ProjectFile libRsFile = new ProjectFile(rsTestProject.getRoot(), "src/lib.rs");
        assertEquals("", getPkgName.apply(libRsFile), "Package name for src/lib.rs should be empty.");

        // 3. src/main.rs
        Path mainRsPath = rsTestProject.getRoot().resolve("src").resolve("main.rs");
        mainRsPath.toFile().createNewFile(); // Create dummy file
        ProjectFile mainRsFile = new ProjectFile(rsTestProject.getRoot(), "src/main.rs");
        assertEquals("", getPkgName.apply(mainRsFile), "Package name for src/main.rs should be empty.");

        // 4. src/foo.rs (module file)
        Path fooRsPath = rsTestProject.getRoot().resolve("src").resolve("foo.rs");
        fooRsPath.toFile().createNewFile(); // Create dummy file
        ProjectFile fooRsFile = new ProjectFile(rsTestProject.getRoot(), "src/foo.rs");
        assertEquals("foo", getPkgName.apply(fooRsFile), "Package name for src/foo.rs should be 'foo'.");

        // 5. src/bar/mod.rs (directory module)
        Path barModRsPath = rsTestProject.getRoot().resolve("src").resolve("bar").resolve("mod.rs");
        barModRsPath.getParent().toFile().mkdirs(); // Ensure src/bar directory exists
        barModRsPath.toFile().createNewFile(); // Create dummy file
        ProjectFile barModRsFile = new ProjectFile(rsTestProject.getRoot(), "src/bar/mod.rs");
        assertEquals("bar", getPkgName.apply(barModRsFile), "Package name for src/bar/mod.rs should be 'bar'.");

        // 6. src/bar/baz.rs (file in directory module)
        Path barBazRsPath = rsTestProject.getRoot().resolve("src").resolve("bar").resolve("baz.rs");
        barBazRsPath.toFile().createNewFile(); // Create dummy file
        ProjectFile barBazRsFile = new ProjectFile(rsTestProject.getRoot(), "src/bar/baz.rs");
        assertEquals("bar.baz", getPkgName.apply(barBazRsFile), "Package name for src/bar/baz.rs should be 'bar.baz'.");

        // 7. other_crate_file.rs (file at project root, sibling to src/)
        Path otherCratePath = rsTestProject.getRoot().resolve("other_crate_file.rs");
        otherCratePath.toFile().createNewFile(); // Create dummy file
        ProjectFile otherCrateFile = new ProjectFile(rsTestProject.getRoot(), "other_crate_file.rs");
        assertEquals("", getPkgName.apply(otherCrateFile), "Package name for other_crate_file.rs at root should be empty.");

        // 8. examples/example1.rs (file in a common directory like examples)
        Path example1Path = rsTestProject.getRoot().resolve("examples").resolve("example1.rs");
        example1Path.getParent().toFile().mkdirs(); // Ensure examples directory exists
        example1Path.toFile().createNewFile(); // Create dummy file
        ProjectFile example1File = new ProjectFile(rsTestProject.getRoot(), "examples/example1.rs");
        assertEquals("examples.example1", getPkgName.apply(example1File), "Package name for examples/example1.rs should be 'examples.example1'.");
        
        // Cleanup dummy files - not strictly necessary for this test but good practice if state matters across tests
        libRsPath.toFile().delete();
        mainRsPath.toFile().delete();
        fooRsPath.toFile().delete();
        barBazRsPath.toFile().delete();
        barModRsPath.toFile().delete();
        barModRsPath.getParent().toFile().delete(); // remove src/bar
        // libRsPath.getParent().toFile().delete(); // careful with src if other tests depend on its structure or Point.rs's implicit "" pkg
        otherCratePath.toFile().delete();
        example1Path.toFile().delete();
        example1Path.getParent().toFile().delete(); // remove examples
    }
}
