package ai.brokk.analyzer;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests verifying CodeUnit behavior when the provided short name is a fully-qualified token versus when package and
 * short name are provided separately.
 */
public class CodeUnitQualifiedNameHandlingTest {

    @Test
    public void qualifiedShortNameIsPreservedInCodeUnit() {
        ProjectFile source = new ProjectFile(Paths.get(".").toAbsolutePath().normalize(), "src/main/java/com/example/MyClass.java");
        CodeUnit cu = CodeUnit.cls(source, "", "com.example.MyClass");

        // The supplied shortName is itself a qualified token; CodeUnit should preserve it as the short name
        assertEquals("com.example.MyClass", cu.shortName());
        // fqName should match the preserved qualified token as provided
        assertEquals("com.example.MyClass", cu.fqName());
    }

    @Test
    public void fqNameConstructedFromPackageAndShortName() {
        ProjectFile source = new ProjectFile(Paths.get(".").toAbsolutePath().normalize(), "src/main/java/com/example/MyClass.java");
        CodeUnit cu = CodeUnit.cls(source, "com.example", "MyClass");

        // When package and shortName are provided separately, shortName remains the simple name
        assertEquals("MyClass", cu.shortName());
        // fqName should be composed of package + '.' + shortName
        assertEquals("com.example.MyClass", cu.fqName());
    }

    @Test
    public void nestedLikeShortNameIsPreservedAndPrefixedByPackage() {
        ProjectFile source = new ProjectFile(Paths.get(".").toAbsolutePath().normalize(), "src/main/java/com/example/Outer.java");
        // Simulate a shortName that already looks like an inner-class token "Outer$Inner"
        CodeUnit cu = CodeUnit.cls(source, "com.example", "Outer$Inner");

        // The supplied shortName contains a nested/inner-class marker and should be preserved as-is
        assertEquals("Outer$Inner", cu.shortName());
        // fqName should be composed of package + '.' + shortName, preserving the '$' marker
        assertEquals("com.example.Outer$Inner", cu.fqName());

        // This test locks in behavior for nested-like tokens provided as short names: they are not split
        // into package vs. simple name by CodeUnit construction and are preserved verbatim.
    }
}
