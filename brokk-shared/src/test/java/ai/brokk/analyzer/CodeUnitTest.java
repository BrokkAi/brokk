package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeUnitTest {
    @TempDir
    Path tempDir;

    @Test
    void equalCodeUnitsHaveEqualHashCodes() {
        var source = new ProjectFile(tempDir, "src/Foo.java");
        var first = new CodeUnit(source, CodeUnitType.FUNCTION, "com.example", "Foo.bar", "(int)", false);
        var second = new CodeUnit(source, CodeUnitType.FUNCTION, "com.example", "Foo.bar", "(int)", false);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void signatureIsPartOfIdentity() {
        var source = new ProjectFile(tempDir, "src/Foo.java");
        var first = new CodeUnit(source, CodeUnitType.FUNCTION, "com.example", "Foo.bar", "(int)", false);
        var second = new CodeUnit(source, CodeUnitType.FUNCTION, "com.example", "Foo.bar", "(String)", false);

        assertNotEquals(first, second);
    }

    @Test
    void nullSignatureIsDistinctFromPresentSignature() {
        var source = new ProjectFile(tempDir, "src/Foo.java");
        var withoutSignature = new CodeUnit(source, CodeUnitType.FUNCTION, "com.example", "Foo.bar", null, false);
        var withSignature = new CodeUnit(source, CodeUnitType.FUNCTION, "com.example", "Foo.bar", "(int)", false);

        assertNotEquals(withoutSignature, withSignature);
    }

    @Test
    void syntheticFlagIsPartOfIdentity() {
        var source = new ProjectFile(tempDir, "src/Foo.java");
        var real = new CodeUnit(source, CodeUnitType.FUNCTION, "com.example", "Foo.bar", "(int)", false);
        var synthetic = new CodeUnit(source, CodeUnitType.FUNCTION, "com.example", "Foo.bar", "(int)", true);

        assertNotEquals(real, synthetic);
    }

    @Test
    void sourceIsPartOfIdentity() {
        var firstSource = new ProjectFile(tempDir, "src/Foo.java");
        var secondSource = new ProjectFile(tempDir, "src/OtherFoo.java");
        var first = new CodeUnit(firstSource, CodeUnitType.CLASS, "com.example", "Foo", null, false);
        var second = new CodeUnit(secondSource, CodeUnitType.CLASS, "com.example", "Foo", null, false);

        assertNotEquals(first, second);
    }

    @Test
    void hashCodeAllowsNullSource() {
        var codeUnit = new CodeUnit(null, CodeUnitType.CLASS, "java.util", "List", null, false);

        assertDoesNotThrow(codeUnit::hashCode);
    }
}
