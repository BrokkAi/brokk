package ai.brokk.context;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static ai.brokk.testutil.AssertionHelperUtil.assertCodeEquals;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment.SummaryType;
import ai.brokk.context.ContextFragments.SummaryFragment;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

public class SummaryFragmentTest {

    @Test
    public void codeunitSkeletonFetchesOnlyTarget() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
    public class Base {}
    class Child extends Base {}
    """, "Test.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);

            var fragment = new SummaryFragment(cm, "Child", SummaryType.CODEUNIT_SKELETON);
            String text = fragment.text().join();

            assertCodeEquals("""
    package (default package);

    class Child extends Base {
    }
    """, text);

            // sources() should include only Child
            var sources = fragment.sources().join();
            var fqns = sources.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
            assertEquals(Set.of("Child"), fqns, "sources() should include only Child");

            // files() should include Test.java
            ProjectFile expectedFile = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Test.java"))
                    .findFirst()
                    .orElseThrow();
            var files = fragment.referencedFiles().join();
            assertEquals(Set.of(expectedFile), files, "files() should include Test.java");
        }
    }

    @Test
    public void testSupportingFragmentsForClass() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
    public class Base {}
    class Child extends Base {}
    """, "Test.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);

            var fragment = new SummaryFragment(cm, "Child", SummaryType.CODEUNIT_SKELETON);
            var supporting = fragment.supportingFragments();

            var fqns = supporting.stream()
                    .filter(f -> f instanceof SummaryFragment)
                    .map(f -> ((SummaryFragment) f).getTargetIdentifier())
                    .collect(Collectors.toSet());

            assertEquals(Set.of("Base"), fqns, "supportingFragments() should return ancestor fragments");
        }
    }

    @Test
    public void fileSkeletonFetchesOnlyTLDs() throws IOException {
        var builder = InlineTestProjectCreator.code("""
    public class Base {}
    """, "Base.java");
        try (var testProject = builder.addFileContents(
                        """
    class Child1 extends Base {}
    class Child2 extends Base {}
    """, "Children.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);

            ProjectFile childrenFile = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Children.java"))
                    .findFirst()
                    .orElseThrow();

            var fragment = new SummaryFragment(cm, childrenFile.toString(), SummaryType.FILE_SKELETONS);
            String text = fragment.text().join();

            assertCodeEquals(
                    """
    package (default package);

    class Child1 extends Base {
    }

    class Child2 extends Base {
    }
    """,
                    text);

            // sources() should include Child1 and Child2
            var sources = fragment.sources().join();
            var fqns = sources.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
            assertEquals(Set.of("Child1", "Child2"), fqns, "sources() should include both children");

            // files() should include only Children.java
            ProjectFile children = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Children.java"))
                    .findFirst()
                    .orElseThrow();
            var files = fragment.referencedFiles().join();
            assertEquals(Set.of(children), files, "files() should include Children.java");
        }
    }

    @Test
    public void fileSkeletonWithMultipleTLDs() throws IOException {
        var builder = InlineTestProjectCreator.code(
                """
    public interface I1 {}
    public interface I2 {}
    public class Base {}
    """,
                "Base.java");
        try (var testProject = builder.addFileContents(
                        """
    class Child1 extends Base implements I1 {}
    class Child2 extends Base implements I2 {}
    class Standalone {}
    """,
                        "Multi.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);

            ProjectFile multiFile = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Multi.java"))
                    .findFirst()
                    .orElseThrow();

            var fragment = new SummaryFragment(cm, multiFile.toString(), SummaryType.FILE_SKELETONS);
            String text = fragment.text().join();

            assertCodeEquals(
                    """
                    package (default package);

                    class Child1 extends Base implements I1 {
                    }

                    class Child2 extends Base implements I2 {
                    }

                    class Standalone {
                    }
                    """,
                    text);

            // sources() should include the 3 TLDs
            var sources = fragment.sources().join();
            var fqns = sources.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
            assertEquals(Set.of("Child1", "Child2", "Standalone"), fqns, "sources() should include only TLDs");

            // files() should include only Multi.java
            ProjectFile multi = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Multi.java"))
                    .findFirst()
                    .orElseThrow();
            var files = fragment.referencedFiles().join();
            assertEquals(Set.of(multi), files, "files() should include only Multi.java");
        }
    }

    @Test
    public void outputFormattedByPackage() throws IOException {
        var builder = InlineTestProjectCreator.code("""
    package p1;
    public class Base {}
    """, "Base.java");
        try (var testProject = builder.addFileContents(
                        """
    package p2;
    import p1.Base;
    class Child extends Base {}
    """, "Child.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);

            var fragment = new SummaryFragment(cm, "p2.Child", SummaryType.CODEUNIT_SKELETON);
            String text = fragment.text().join();

            assertCodeEquals("""
    package p2;

    class Child extends Base {
    }
    """, text);

            // sources() should include p2.Child
            var sources = fragment.sources().join();
            var fqns = sources.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
            assertEquals(Set.of("p2.Child"), fqns, "sources() should include Child");

            // files() should include Child.java
            ProjectFile child = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Child.java"))
                    .findFirst()
                    .orElseThrow();
            var files = fragment.referencedFiles().join();
            assertEquals(Set.of(child), files, "files() should include Child.java");
        }
    }

    @Test
    public void nonClassCUSkeletonRendersWithoutAncestorSection() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
    public class MyClass {
    public void myMethod() {}
    }
    """, "Test.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);

            var fragment = new SummaryFragment(cm, "MyClass.myMethod", SummaryType.CODEUNIT_SKELETON);
            String text = fragment.text().join();

            assertCodeEquals("""
    package (default package);

    public void myMethod()
    """, text);

            // sources() should include only the method; no ancestors for non-class targets
            var sources = fragment.sources().join();
            var fqns = sources.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
            assertEquals(Set.of("MyClass.myMethod"), fqns, "sources() should include only the method");
            assertEquals(sources.size(), fqns.size(), "sources() should not contain duplicates");

            // files() should include only Test.java
            ProjectFile expectedFile = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Test.java"))
                    .findFirst()
                    .orElseThrow();
            var files = fragment.referencedFiles().join();
            assertEquals(Set.of(expectedFile), files, "files() should include only Test.java");
        }
    }

    @Test
    public void fileSkeletonDoesNotIncludeSharedAncestors() throws IOException {
        var builder = InlineTestProjectCreator.code("""
    public class SharedBase {}
    """, "Base.java");
        try (var testProject = builder.addFileContents(
                        """
    class ChildA extends SharedBase {}
    class ChildB extends SharedBase {}
    """,
                        "Children.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);

            ProjectFile childrenFile = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Children.java"))
                    .findFirst()
                    .orElseThrow();

            var fragment = new SummaryFragment(cm, childrenFile.toString(), SummaryType.FILE_SKELETONS);
            String text = fragment.text().join();

            // Verify the individual class skeletons are present
            assertTrue(text.contains("class ChildA extends SharedBase"), "Should contain ChildA skeleton");
            assertTrue(text.contains("class ChildB extends SharedBase"), "Should contain ChildB skeleton");
            assertFalse(text.contains("public class SharedBase"), "Should NOT contain SharedBase skeleton");

            // Verify sources()
            var sources = fragment.sources().join();
            var fqns = sources.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
            assertEquals(Set.of("ChildA", "ChildB"), fqns, "sources() should include both children");
        }
    }

    @Test
    public void supportingFragments_excludesInnerClassAncestors() throws IOException {
        try (var testProject = InlineTestProjectCreator.code("", "Outer.java").build()) {
            ProjectFile outerFile = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Outer.java"))
                    .findFirst()
                    .orElseThrow();
            ProjectFile outerBaseFile = new ProjectFile(testProject.getRoot(), "OuterBase.java");
            ProjectFile innerBaseFile = new ProjectFile(testProject.getRoot(), "InnerBase.java");

            CodeUnit outer = CodeUnit.cls(outerFile, "com.example", "Outer");
            CodeUnit inner = CodeUnit.cls(outerFile, "com.example", "Outer.Inner");
            CodeUnit outerBase = CodeUnit.cls(outerBaseFile, "com.example", "OuterBase");
            CodeUnit innerBase = CodeUnit.cls(innerBaseFile, "com.example", "InnerBase");

            TestAnalyzer analyzer = new TestAnalyzer() {
                @Override
                public List<CodeUnit> getDirectChildren(CodeUnit cu) {
                    if (Objects.equals(outer, cu)) {
                        return List.of(inner);
                    }
                    return super.getDirectChildren(cu);
                }
            };

            analyzer.addDeclaration(outer);
            analyzer.addDeclaration(inner);
            analyzer.setDirectAncestors(outer, List.of(outerBase));
            analyzer.setDirectAncestors(inner, List.of(innerBase));

            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);
            var fragment = new SummaryFragment(cm, "com.example.Outer", SummaryType.CODEUNIT_SKELETON);

            var supporting = fragment.supportingFragments();
            var targetIds = supporting.stream()
                    .filter(f -> f instanceof SummaryFragment)
                    .map(f -> ((SummaryFragment) f).getTargetIdentifier())
                    .collect(Collectors.toSet());

            assertTrue(targetIds.contains("com.example.OuterBase"), "Should include OuterBase");
            assertFalse(targetIds.contains("com.example.InnerBase"), "Should NOT include InnerBase");
        }
    }

    @Test
    public void supportingFragments_fileSkeletonsResolvesAncestors() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        "public class Base1 {}\npublic class Base2 {}", "Bases.java")
                .addFileContents(
                        """
                        class Child1 extends Base1 {}
                        class Child2 extends Base2 {}
                        """,
                        "Children.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);

            ProjectFile childrenFile = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("Children.java"))
                    .findFirst()
                    .orElseThrow();

            // 1. Create FILE_SKELETONS fragment
            var fileFragment = new SummaryFragment(cm, childrenFile.toString(), SummaryType.FILE_SKELETONS);

            // 2. Call supportingFragments()
            var supporting = fileFragment.supportingFragments();

            // 3. Verify returned set contains non-anonymous ancestors of TLDs
            var targetIds = supporting.stream()
                    .filter(f -> f instanceof SummaryFragment)
                    .map(f -> ((SummaryFragment) f).getTargetIdentifier())
                    .collect(Collectors.toSet());

            assertEquals(Set.of("Base1", "Base2"), targetIds, "FILE_SKELETONS should return ancestors for all TLDs");

            // 4. Compare behavior against CODEUNIT_SKELETON for consistency
            var cuFragment = new SummaryFragment(cm, "Child1", SummaryType.CODEUNIT_SKELETON);
            var cuSupporting = cuFragment.supportingFragments();
            var cuTargetIds = cuSupporting.stream()
                    .filter(f -> f instanceof SummaryFragment)
                    .map(f -> ((SummaryFragment) f).getTargetIdentifier())
                    .collect(Collectors.toSet());

            assertEquals(
                    Set.of("Base1"), cuTargetIds, "CODEUNIT_SKELETON should return ancestors for the specific unit");
            assertTrue(
                    targetIds.containsAll(cuTargetIds),
                    "FILE_SKELETONS ancestors should be a superset of individual units");
        }
    }

    @Test
    public void pythonFileSkeleton() throws IOException {
        String pythonCode =
                """
                from __future__ import annotations

                import math
                from dataclasses import dataclass
                from typing import Optional

                # Grid unit size in inches (1 grid unit = 0.25 inches)
                GRID_INCHES: float = 0.25


                def inches_to_grid(inches: float) -> int:
                    \"""Convert inches to grid units, rounding to nearest.\"""
                    return round(inches / GRID_INCHES)


                def inches_to_grid_ceil(inches: float) -> int:
                    \"""Convert inches to grid units, ceiling (for bounding boxes).\"""
                    return math.ceil(inches / GRID_INCHES)


                def grid_to_inches(grid_units: int) -> float:
                    \"""Convert grid units to inches.\"""
                    return grid_units * GRID_INCHES


                def grid_to_pixels(grid_units: int, dpi: int) -> int:
                    \"""Convert grid units to pixels at the given DPI.\"""
                    return round(grid_units * GRID_INCHES * dpi)


                def pixels_to_grid_ceil(pixels: int, dpi: int) -> int:
                    \"""Convert pixels to grid units, ceiling (for bounding boxes).\"""
                    inches = pixels / dpi
                    return math.ceil(inches / GRID_INCHES)


                @dataclass(frozen=True)
                class GridRect:
                    \"""Axis-aligned rectangle in grid units (integers), top-left origin.\"""

                    x: int
                    y: int
                    w: int
                    h: int

                    def __post_init__(self) -> None:
                        if self.w < 1 or self.h < 1:
                            raise ValueError(
                                f"GridRect dimensions must be positive, got {self.w}x{self.h}"
                            )

                    @property
                    def area(self) -> int:
                        return self.w * self.h

                    @property
                    def x2(self) -> int:
                        return self.x + self.w

                    @property
                    def y2(self) -> int:
                        return self.y + self.h

                    def intersection(self, other: "GridRect") -> Optional["GridRect"]:
                        \"""Return the intersection rectangle, or None if no overlap.\"""
                        x1 = max(self.x, other.x)
                        y1 = max(self.y, other.y)
                        x2 = min(self.x2, other.x2)
                        y2 = min(self.y2, other.y2)
                        if x2 <= x1 or y2 <= y1:
                            return None
                        return GridRect(x1, y1, x2 - x1, y2 - y1)

                    def intersection_area(self, other: "GridRect") -> int:
                        \"""Return the area of intersection, or 0 if no overlap.\"""
                        inter = self.intersection(other)
                        return inter.area if inter else 0

                    def intersects(self, other: "GridRect") -> bool:
                        \"""Return True if rectangles overlap (not just touch).\"""
                        return not (
                            self.x2 <= other.x
                            or other.x2 <= self.x
                            or self.y2 <= other.y
                            or other.y2 <= self.y
                        )

                    def expanded(self, pad: int) -> "GridRect":
                        \"""Return a new rectangle expanded by pad grid units on all sides.\"""
                        new_w = self.w + 2 * pad
                        new_h = self.h + 2 * pad
                        if new_w < 1 or new_h < 1:
                            raise ValueError(f"Expansion by {pad} results in non-positive dimensions")
                        return GridRect(self.x - pad, self.y - pad, new_w, new_h)

                    def contained_in(self, bounds: "GridRect") -> bool:
                        \"""Return True if this rectangle is fully inside bounds.\"""
                        return (
                            self.x >= bounds.x
                            and self.y >= bounds.y
                            and self.x2 <= bounds.x2
                            and self.y2 <= bounds.y2
                        )

                    def contains_point(self, x: int, y: int) -> bool:
                        \"""Return True if the point (x, y) is inside this rectangle.\"""
                        return self.x <= x < self.x2 and self.y <= y < self.y2


                @dataclass(frozen=True)
                class Rect:
                    \"""Axis-aligned rectangle, top-left origin coordinate system.\"""

                    x: float
                    y: float
                    w: float
                    h: float

                    def __post_init__(self) -> None:
                        if self.w < 0.001 or self.h < 0.001:
                            raise ValueError(f"Rect dimensions must be positive, got {self.w}x{self.h}")

                    @property
                    def area(self) -> float:
                        return self.w * self.h

                    @property
                    def x2(self) -> float:
                        return self.x + self.w

                    @property
                    def y2(self) -> float:
                        return self.y + self.h

                    def intersection(self, other: "Rect") -> Optional["Rect"]:
                        x1 = max(self.x, other.x)
                        y1 = max(self.y, other.y)
                        x2 = min(self.x2, other.x2)
                        y2 = min(self.y2, other.y2)
                        if x2 <= x1 or y2 <= y1:
                            return None
                        return Rect(x1, y1, x2 - x1, y2 - y1)

                    def intersection_area(self, other: "Rect") -> float:
                        inter = self.intersection(other)
                        return inter.area if inter else 0.0

                    def intersects(self, other: "Rect") -> bool:
                        return not (
                            self.x2 <= other.x
                            or other.x2 <= self.x
                            or self.y2 <= other.y
                            or other.y2 <= self.y
                        )

                    def expanded(self, pad: float) -> "Rect":
                        return Rect(self.x - pad, self.y - pad, self.w + 2 * pad, self.h + 2 * pad)

                    def contained_in(self, bounds: "Rect") -> bool:
                        return (
                            self.x >= bounds.x
                            and self.y >= bounds.y
                            and self.x2 <= bounds.x2
                            and self.y2 <= bounds.y2
                        )
                """;
        try (var testProject =
                InlineTestProjectCreator.code(pythonCode, "rect.py").build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);

            ProjectFile chosen = testProject.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("rect.py"))
                    .findFirst()
                    .orElseThrow();

            var fragment = new SummaryFragment(cm, chosen.getRelPath().toString(), SummaryType.FILE_SKELETONS);
            String text = fragment.text().join();

            assertNotNull(text);
            assertCodeEquals(
                    """
package rect;

GRID_INCHES: float = 0.25

def inches_to_grid(inches: float) -> int: ...

def inches_to_grid_ceil(inches: float) -> int: ...

def grid_to_inches(grid_units: int) -> float: ...

def grid_to_pixels(grid_units: int, dpi: int) -> int: ...

def pixels_to_grid_ceil(pixels: int, dpi: int) -> int: ...

  def __post_init__(self) -> None: ...
  @property
  def area(self) -> int: ...
  @property
  def x2(self) -> int: ...
  @property
  def y2(self) -> int: ...
  def intersection(self, other: "GridRect") -> Optional["GridRect"]: ...
  def intersection_area(self, other: "GridRect") -> int: ...
  def intersects(self, other: "GridRect") -> bool: ...
  def expanded(self, pad: int) -> "GridRect": ...
  def contained_in(self, bounds: "GridRect") -> bool: ...
  def contains_point(self, x: int, y: int) -> bool: ...

  def __post_init__(self) -> None: ...
  @property
  def area(self) -> float: ...
  @property
  def x2(self) -> float: ...
  @property
  def y2(self) -> float: ...
  def intersection(self, other: "Rect") -> Optional["Rect"]: ...
  def intersection_area(self, other: "Rect") -> float: ...
  def intersects(self, other: "Rect") -> bool: ...
  def expanded(self, pad: float) -> "Rect": ...
  def contained_in(self, bounds: "Rect") -> bool: ...
  """,
                    text);
        }
    }

    @Test
    public void testCombinedTextDeduplicatesCodeUnitsWithDifferentSourceFiles() throws IOException {
        try (var testProject = InlineTestProjectCreator.code("", "Test.java").build()) {
            Path root1 = Paths.get("/root1");
            Path root2 = Paths.get("/root2");
            ProjectFile file1 = new ProjectFile(root1, "src/Test.java");
            ProjectFile file2 = new ProjectFile(root2, "src/Test.java");

            // These CodeUnits are semantically identical but differ in their 'source' ProjectFile instance
            CodeUnit cu1 = CodeUnit.cls(file1, "com.example", "TestClass");
            CodeUnit cu2 = CodeUnit.cls(file2, "com.example", "TestClass");

            String skeleton = "class TestClass {}";

            TestAnalyzer analyzer = new TestAnalyzer() {
                @Override
                public Optional<String> getSkeleton(CodeUnit cu) {
                    if (cu.fqName().equals("com.example.TestClass")) {
                        return Optional.of(skeleton);
                    }
                    return Optional.empty();
                }

                @Override
                public SequencedSet<CodeUnit> getDefinitions(String fqName) {
                    if (fqName.equals("com.example.TestClass")) {
                        return new LinkedHashSet<>(List.of(cu1, cu2));
                    }
                    return new LinkedHashSet<>();
                }
            };

            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);

            // Create fragments for what are effectively the same unit
            var sf1 = new SummaryFragment(cm, "com.example.TestClass", SummaryType.CODEUNIT_SKELETON);
            var sf2 = new SummaryFragment(cm, "com.example.TestClass", SummaryType.CODEUNIT_SKELETON);

            String combined = SummaryFragment.combinedText(List.of(sf1, sf2));

            // Assert that the class definition appears EXACTLY ONCE
            int count = 0;
            int index = 0;
            while ((index = combined.indexOf("class TestClass {}", index)) != -1) {
                count++;
                index += "class TestClass {}".length();
            }
            assertEquals(1, count, "Identical CodeUnit from different file instances should be deduplicated");
        }
    }

    @Test
    public void combinedTextDeduplicatesSharedAncestors() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
    public class Base {}
    class ChildA extends Base {}
    class ChildB extends Base {}
    """,
                        "Test.java")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), analyzer);

            var sfA = new SummaryFragment(cm, "ChildA", SummaryType.CODEUNIT_SKELETON);
            var sfB = new SummaryFragment(cm, "ChildB", SummaryType.CODEUNIT_SKELETON);

            List<SummaryFragment> fragments = new ArrayList<>();
            fragments.add(sfA);
            fragments.addAll(sfA.supportingFragments().stream()
                    .map(f -> (SummaryFragment) f)
                    .toList());
            fragments.add(sfB);
            fragments.addAll(sfB.supportingFragments().stream()
                    .map(f -> (SummaryFragment) f)
                    .toList());

            String combined = SummaryFragment.combinedText(fragments);

            // Combined text uses "by package" formatting, so it shouldn't have redundant ancestor headers
            assertFalse(combined.contains("// Direct ancestors"), "Combined text should use flat package formatting");

            // Each child should be present
            assertTrue(combined.contains("class ChildA extends Base"), "Should contain ChildA");
            assertTrue(combined.contains("class ChildB extends Base"), "Should contain ChildB");

            // The superclass "Base" should appear EXACTLY once in the whole combined output
            int count = 0;
            int index = 0;
            while ((index = combined.indexOf("public class Base", index)) != -1) {
                count++;
                index += "public class Base".length();
            }
            assertEquals(1, count, "Shared ancestor 'Base' should only appear once in combined output");
        }
    }
}
