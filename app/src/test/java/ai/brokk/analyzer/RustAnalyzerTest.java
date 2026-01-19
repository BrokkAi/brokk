package ai.brokk.analyzer;

import static ai.brokk.testutil.AssertionHelperUtil.assertCodeUnitType;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
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
            assertCodeUnitType(analyzer, "utils", CodeUnitType.MODULE);

            // 2. Verify struct/trait/enum exist as class code units
            assertCodeUnitType(analyzer, "Point", CodeUnitType.CLASS);
            assertCodeUnitType(analyzer, "Drawable", CodeUnitType.CLASS);
            assertCodeUnitType(analyzer, "Color", CodeUnitType.CLASS);

            // 3. Verify free function exists
            assertCodeUnitType(analyzer, "distance", CodeUnitType.FUNCTION);

            // 4. Verify method in impl block exists
            assertCodeUnitType(analyzer, "Point.new", CodeUnitType.FUNCTION);

            // 5. Verify function inside the module exists
            assertCodeUnitType(analyzer, "utils.helper", CodeUnitType.FUNCTION);
        }
    }

    @Test
    void testNestedModulesWithTestFunction() throws Exception {
        String rustCode =
                """
            mod outer {
                mod inner {
                    #[test]
                    fn my_test() {
                        assert!(true);
                    }
                }

                pub fn outer_helper() -> i32 {
                    42
                }
            }

            pub fn top_level() {}
            """;

        String fileName = "nested_test.rs";

        try (IProject project =
                InlineTestProjectCreator.code(rustCode, fileName).build()) {
            RustAnalyzer analyzer = new RustAnalyzer(project);
            analyzer.update();

            // Verify modules
            assertCodeUnitType(analyzer, "outer", CodeUnitType.MODULE);
            assertCodeUnitType(analyzer, "outer.inner", CodeUnitType.MODULE);

            // Verify functions
            assertCodeUnitType(analyzer, "outer.inner.my_test", CodeUnitType.FUNCTION);
            assertCodeUnitType(analyzer, "outer.outer_helper", CodeUnitType.FUNCTION);
            assertCodeUnitType(analyzer, "top_level", CodeUnitType.FUNCTION);

            // Verify test detection works for nested test
            ProjectFile file = new ProjectFile(project.getRoot(), fileName);
            assertTrue(analyzer.containsTests(file), "File with nested #[test] should be detected as containing tests");
        }
    }
}
