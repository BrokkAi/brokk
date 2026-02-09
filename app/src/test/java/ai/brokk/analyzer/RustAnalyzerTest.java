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
    void testImplForReferenceType() throws Exception {
        String rustCode =
                """
            pub struct MyStruct;

            pub trait MyTrait {
                fn do_something(&self);
            }

            impl MyTrait for MyStruct {
                fn do_something(&self) {}
            }

            impl<T> MyTrait for &T {
                fn do_something(&self) {}
            }
            """;

        try (IProject project =
                InlineTestProjectCreator.code(rustCode, "lib.rs").build()) {
            RustAnalyzer analyzer = new RustAnalyzer(project);
            analyzer.update();

            // The impl block for MyStruct should work normally
            assertCodeUnitType(analyzer, "MyStruct", CodeUnitType.CLASS);
            assertCodeUnitType(analyzer, "MyStruct.do_something", CodeUnitType.FUNCTION);

            // The impl block for &T extracts "T" as the type name - verify the method exists
            // Note: T is a generic parameter, so it creates a CodeUnit for the impl's methods
            assertCodeUnitType(analyzer, "T.do_something", CodeUnitType.FUNCTION);
        }
    }

    @Test
    void testImplForScopedGenericType() throws Exception {
        String rustCode =
                """
            mod ast {
                pub struct StringLike<'a> {
                    value: &'a str,
                }
            }

            pub trait StringLikeExtensions {
                fn is_empty(&self) -> bool;
            }

            impl<'a> StringLikeExtensions for ast::StringLike<'a> {
                fn is_empty(&self) -> bool {
                    false
                }
            }
            """;

        try (IProject project =
                InlineTestProjectCreator.code(rustCode, "lib.rs").build()) {
            RustAnalyzer analyzer = new RustAnalyzer(project);
            analyzer.update();

            // The impl block for ast::StringLike<'a> should extract "StringLike" as the name
            assertCodeUnitType(analyzer, "StringLike", CodeUnitType.CLASS);
            assertCodeUnitType(analyzer, "StringLike.is_empty", CodeUnitType.FUNCTION);
        }
    }

    @Test
    void testImplForDeeplyNestedType() throws Exception {
        String rustCode =
                """
            pub struct Inner;
            pub trait MyTrait { fn method(&self); }
            impl<T> MyTrait for &Vec<Box<T>> {
                fn method(&self) {}
            }
            """;

        try (IProject project =
                InlineTestProjectCreator.code(rustCode, "lib.rs").build()) {
            RustAnalyzer analyzer = new RustAnalyzer(project);
            analyzer.update();

            // The impl block for &Vec<Box<T>> extracts "Vec" as the type name
            // &Vec<Box<T>> -> Vec<Box<T>> (GENERIC_TYPE) -> Vec (TYPE_IDENTIFIER)
            // We extract the outermost named type, not the innermost generic parameter
            assertCodeUnitType(analyzer, "Vec.method", CodeUnitType.FUNCTION);
        }
    }

    @Test
    void testImplForRawPointerType() throws Exception {
        String rustCode =
                """
            pub trait Deref {
                fn deref(&self);
            }

            impl<T> Deref for *const T {
                fn deref(&self) {}
            }

            impl<T> Deref for *mut T {
                fn deref(&self) {}
            }
            """;

        try (IProject project =
                InlineTestProjectCreator.code(rustCode, "lib.rs").build()) {
            RustAnalyzer analyzer = new RustAnalyzer(project);
            analyzer.update();

            // Raw pointer types *const T and *mut T should extract "T"
            assertCodeUnitType(analyzer, "T.deref", CodeUnitType.FUNCTION);
        }
    }

    @Test
    void testImplForSliceType() throws Exception {
        String rustCode =
                """
            pub trait SliceTrait {
                fn len(&self) -> usize;
            }

            impl<T> SliceTrait for [T] {
                fn len(&self) -> usize { 0 }
            }
            """;

        try (IProject project =
                InlineTestProjectCreator.code(rustCode, "lib.rs").build()) {
            RustAnalyzer analyzer = new RustAnalyzer(project);
            analyzer.update();

            // Slice type [T] should extract "T"
            assertCodeUnitType(analyzer, "T.len", CodeUnitType.FUNCTION);
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
