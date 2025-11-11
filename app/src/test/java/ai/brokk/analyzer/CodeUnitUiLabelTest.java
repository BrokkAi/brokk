package ai.brokk.analyzer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CodeUnit.uiLabel() to ensure chips show concise symbol names.
 */
public class CodeUnitUiLabelTest {

    @Test
    public void classUiLabelReturnsShortName() {
        // Passing null for ProjectFile is fine for this focused test as uiLabel() doesn't use the source
        CodeUnit cu = CodeUnit.cls(null, "com.example", "Chrome");
        assertEquals("Chrome", cu.uiLabel());
    }

    @Test
    public void functionUiLabelReturnsIdentifier() {
        CodeUnit cu = CodeUnit.fn(null, "com.example", "MyClass.myMethod");
        assertEquals("myMethod", cu.uiLabel());
    }

    @Test
    public void fieldUiLabelReturnsIdentifier() {
        CodeUnit cu = CodeUnit.field(null, "com.example", "Container.fieldName");
        assertEquals("fieldName", cu.uiLabel());
    }
}
