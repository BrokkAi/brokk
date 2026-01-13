package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.Collection;
import org.junit.jupiter.api.Test;

public class RustAnalyzerTest {

    @Test
    void testModuleClassAndFunctionCodeUnits() throws Exception {
        String rustCode =
                """
            mod utils {
                pub fn helper() -> i32 {
                    42
                }
            }

            pub struct Point {
                pub x: i32,
                pub y: i32,
            }

            impl Point {
                pub fn new(x: i32, y: i32) -> Self {
                    Self { x, y }
                }
            }

            pub trait Drawable {
                fn draw(&self);
            }

            pub enum Color {
                Red,
                Green,
                Blue,
            }

            pub fn distance(a: &Point, b: &Point) -> f64 {
                0.0
            }
            """;

        String fileName = "lib.rs";

        try (IProject project =
                InlineTestProjectCreator.code(rustCode, fileName).build()) {
            RustAnalyzer analyzer = new RustAnalyzer(project);
            analyzer.update();

            // 1. Verify module exists
            assertCodeUnitType(analyzer, "utils", "Module", true, false, false);

            // 2. Verify struct/trait/enum exist as class code units
            assertCodeUnitType(analyzer, "Point", "Class", false, true, false);
            assertCodeUnitType(analyzer, "Drawable", "Class", false, true, false);
            assertCodeUnitType(analyzer, "Color", "Class", false, true, false);

            // 3. Verify free function exists
            assertCodeUnitType(analyzer, "distance", "Function", false, false, true);

            // 4. Verify method in impl block exists
            assertCodeUnitType(analyzer, "Point.new", "Function", false, false, true);

            // 5. Verify function inside the module exists
            assertCodeUnitType(analyzer, "utils.helper", "Function", false, false, true);
        }
    }

    private void assertCodeUnitType(
            RustAnalyzer analyzer, String fqName, String label, boolean isModule, boolean isClass, boolean isFunction) {
        Collection<CodeUnit> units = analyzer.getDefinitions(fqName);
        assertFalse(units.isEmpty(), "Should find code unit for: " + fqName);

        CodeUnit unit = units.iterator().next();
        assertEquals(isModule, unit.isModule(), fqName + " should " + (isModule ? "" : "not ") + "be a module");
        assertEquals(isClass, unit.isClass(), fqName + " should " + (isClass ? "" : "not ") + "be a class");
        assertEquals(isFunction, unit.isFunction(), fqName + " should " + (isFunction ? "" : "not ") + "be a function");
    }
}
