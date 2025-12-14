package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment.SkeletonFragmentFormatter;
import ai.brokk.context.ContextFragment.SummaryType;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SkeletonFragmentFormatterTest {

    @TempDir
    Path tempDir;

    @Test
    void summaryWithAncestors_filtersAnonymousUnits() {
        ProjectFile file = new ProjectFile(tempDir, "src/pkg/Foo.java");

        CodeUnit primary = CodeUnit.cls(file, "pkg", "Foo");
        CodeUnit anonInner = CodeUnit.cls(file, "pkg", "Foo$anon$1");
        CodeUnit ancestorOk = CodeUnit.cls(file, "pkg", "Base");
        CodeUnit ancestorAnon = CodeUnit.cls(file, "pkg", "Anon$anon$Base");

        Map<CodeUnit, String> skeletons = new LinkedHashMap<>();
        skeletons.put(primary, "class Foo {}");
        skeletons.put(anonInner, "class Foo$anon$1 {}");
        skeletons.put(ancestorOk, "class Base {}");
        skeletons.put(ancestorAnon, "class Anon {}");

        List<CodeUnit> ancestors = List.of(ancestorOk, ancestorAnon);

        SkeletonFragmentFormatter formatter = new SkeletonFragmentFormatter();
        SkeletonFragmentFormatter.Request request =
                new SkeletonFragmentFormatter.Request(primary, ancestors, skeletons, SummaryType.CODEUNIT_SKELETON);

        String out = formatter.format(request);

        assertTrue(out.contains("package pkg;"));
        assertTrue(out.contains("class Foo {}"));
        assertTrue(out.contains("// Direct ancestors of Foo: Base"));

        assertFalse(out.contains("$anon$"));
        assertFalse(out.contains("Foo$anon$1"));
        assertFalse(out.contains("class Anon {}"));
        assertFalse(out.contains("Anon$anon$Base"));
    }

    @Test
    void formatByPackage_filtersAnonymousUnits() {
        ProjectFile file1 = new ProjectFile(tempDir, "src/pkg1/C1.java");
        ProjectFile file2 = new ProjectFile(tempDir, "src/pkg2/C3.java");

        CodeUnit c1 = CodeUnit.cls(file1, "pkg1", "C1");
        CodeUnit c1Anon = CodeUnit.cls(file1, "pkg1", "C1$anon$1");
        CodeUnit c3 = CodeUnit.cls(file2, "pkg2", "C3");

        Map<CodeUnit, String> skeletons = new LinkedHashMap<>();
        skeletons.put(c1, "class C1 {}");
        skeletons.put(c1Anon, "class C1$anon$1 {}");
        skeletons.put(c3, "class C3 {}");

        SkeletonFragmentFormatter formatter = new SkeletonFragmentFormatter();
        SkeletonFragmentFormatter.Request request =
                new SkeletonFragmentFormatter.Request(null, List.of(), skeletons, SummaryType.FILE_SKELETONS);

        String out = formatter.format(request);

        assertTrue(out.contains("package pkg1;"));
        assertTrue(out.contains("package pkg2;"));
        assertTrue(out.contains("class C1 {}"));
        assertTrue(out.contains("class C3 {}"));

        assertFalse(out.contains("$anon$"));
        assertFalse(out.contains("class C1$anon$1 {}"));
    }

    @Test
    void anonymousPrimary_suppressesAncestorHeader_butRendersAncestors() {
        ProjectFile file = new ProjectFile(tempDir, "src/pkg/Foo.java");

        CodeUnit primaryAnon = CodeUnit.cls(file, "pkg", "Foo$anon$1");
        CodeUnit ancestor1 = CodeUnit.cls(file, "pkg", "Base");
        CodeUnit ancestor2 = CodeUnit.cls(file, "pkg", "Util");

        Map<CodeUnit, String> skeletons = new LinkedHashMap<>();
        skeletons.put(primaryAnon, "class Foo$anon$1 {}");
        skeletons.put(ancestor1, "class Base {}");
        skeletons.put(ancestor2, "class Util {}");

        List<CodeUnit> ancestors = List.of(ancestor1, ancestor2);

        SkeletonFragmentFormatter formatter = new SkeletonFragmentFormatter();
        SkeletonFragmentFormatter.Request request =
                new SkeletonFragmentFormatter.Request(primaryAnon, ancestors, skeletons, SummaryType.CODEUNIT_SKELETON);

        String out = formatter.format(request);

        assertTrue(out.contains("package pkg;"));
        assertTrue(out.contains("class Base {}"));
        assertTrue(out.contains("class Util {}"));

        assertFalse(out.contains("// Direct ancestors of"));
        assertFalse(out.contains("$anon$"));
        assertFalse(out.contains("class Foo$anon$1 {}"));
    }
}
