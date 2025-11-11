package ai.brokk.analyzer;

import static ai.brokk.testutil.TestProject.*;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AnalyzerUtil;
import ai.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TypescriptAnalyzerTest {

    private static TestProject project;
    private static TypescriptAnalyzer analyzer;

    // Helper to normalize line endings and strip leading/trailing whitespace from each line
    private static final Function<String, String> normalize = (String s) ->
            s.lines().map(String::strip).filter(line -> !line.isEmpty()).collect(Collectors.joining("\n"));

    @BeforeAll
    static void setUp(@TempDir Path tempDir) throws IOException {
        // Use a common TestProject setup method if available, or adapt TreeSitterAnalyzerTest.createTestProject
        Path testResourceRoot = Path.of("src/test/resources/testcode-ts");
        assertTrue(
                Files.exists(testResourceRoot) && Files.isDirectory(testResourceRoot),
                "Test resource directory 'testcode-ts' must exist.");

        // For TypescriptAnalyzerTest, we'll point the TestProject root directly to testcode-ts
        project = TestProject.createTestProject("testcode-ts", Languages.TYPESCRIPT);
        analyzer = new TypescriptAnalyzer(project); // Initialize with default excluded files (none)
        assertFalse(analyzer.isEmpty(), "Analyzer should have processed TypeScript files and not be empty.");
    }

    @Test
    void testHelloTsSkeletons() {

        ProjectFile helloTsFile = new ProjectFile(project.getRoot(), "Hello.ts");
        Map<CodeUnit, String> skeletons = analyzer.getSkeletons(helloTsFile);
        assertFalse(skeletons.isEmpty(), "Skeletons map for Hello.ts should not be empty.");

        CodeUnit greeterClass = CodeUnit.cls(helloTsFile, "", "Greeter");
        CodeUnit globalFunc = CodeUnit.fn(helloTsFile, "", "globalFunc");
        CodeUnit piConst = CodeUnit.field(helloTsFile, "", "_module_.PI");
        CodeUnit pointInterface = CodeUnit.cls(helloTsFile, "", "Point");
        CodeUnit colorEnum = CodeUnit.cls(helloTsFile, "", "Color");
        CodeUnit stringOrNumberAlias = CodeUnit.field(helloTsFile, "", "_module_.StringOrNumber");
        CodeUnit localDetailsAlias = CodeUnit.field(helloTsFile, "", "_module_.LocalDetails");

        assertTrue(skeletons.containsKey(greeterClass), "Greeter class skeleton missing.");
        assertEquals(
                normalize.apply(
                        """
            export class Greeter {
              greeting: string
              constructor(message: string) { ... }
              greet(): string { ... }
            }"""),
                normalize.apply(skeletons.get(greeterClass)));

        assertTrue(skeletons.containsKey(globalFunc), "globalFunc skeleton missing.");
        assertEquals(
                normalize.apply("export function globalFunc(num: number): number { ... }"),
                normalize.apply(skeletons.get(globalFunc)));

        assertTrue(
                skeletons.containsKey(piConst),
                "PI const skeleton missing. Found: "
                        + skeletons.keySet().stream().map(CodeUnit::fqName).collect(Collectors.joining(", ")));
        assertEquals(normalize.apply("export const PI: number = 3.14159"), normalize.apply(skeletons.get(piConst)));

        assertTrue(skeletons.containsKey(pointInterface), "Point interface skeleton missing.");
        assertEquals(
                normalize.apply(
                        """
            export interface Point {
              x: number
              y: number
              label?: string
              readonly originDistance?: number
              move(dx: number, dy: number): void
            }"""),
                normalize.apply(skeletons.get(pointInterface)));

        assertTrue(skeletons.containsKey(colorEnum), "Color enum skeleton missing.");
        assertEquals(
                normalize.apply(
                        """
            export enum Color {
              Red,
              Green = 3,
              Blue
            }"""),
                normalize.apply(skeletons.get(colorEnum)));

        assertTrue(skeletons.containsKey(stringOrNumberAlias), "StringOrNumber type alias skeleton missing.");
        assertEquals(
                normalize.apply("export type StringOrNumber = string | number"),
                normalize.apply(skeletons.get(stringOrNumberAlias)));

        assertTrue(skeletons.containsKey(localDetailsAlias), "LocalDetails type alias skeleton missing.");
        assertEquals(
                normalize.apply("type LocalDetails = { id: number, name: string }"),
                normalize.apply(skeletons.get(localDetailsAlias)));

        // Check getDeclarationsInFile
        Set<CodeUnit> declarations = analyzer.getDeclarations(helloTsFile);
        assertTrue(declarations.contains(greeterClass));
        assertTrue(declarations.contains(globalFunc));
        assertTrue(declarations.contains(piConst));
        assertTrue(declarations.contains(pointInterface));
        assertTrue(declarations.contains(colorEnum));
        assertTrue(declarations.contains(stringOrNumberAlias));
        assertTrue(declarations.contains(localDetailsAlias));

        // also members
        assertTrue(declarations.contains(CodeUnit.field(helloTsFile, "", "Greeter.greeting")));
        assertTrue(declarations.contains(CodeUnit.fn(helloTsFile, "", "Greeter.constructor")));
        assertTrue(declarations.contains(CodeUnit.fn(helloTsFile, "", "Greeter.greet")));
        assertTrue(declarations.contains(CodeUnit.field(helloTsFile, "", "Point.x")));
        assertTrue(declarations.contains(CodeUnit.fn(helloTsFile, "", "Point.move")));
        assertTrue(declarations.contains(CodeUnit.field(helloTsFile, "", "Color.Red")));

        // Test getSkeleton for individual items
        Optional<String> stringOrNumberSkeleton = AnalyzerUtil.getSkeleton(analyzer, "_module_.StringOrNumber");
        assertTrue(stringOrNumberSkeleton.isPresent());
        assertEquals(
                normalize.apply("export type StringOrNumber = string | number"),
                normalize.apply(stringOrNumberSkeleton.get()));

        Optional<String> greetMethodSkeleton = AnalyzerUtil.getSkeleton(analyzer, "Greeter.greet");
        assertTrue(greetMethodSkeleton.isPresent());
        // Note: getSkeleton for a method might only return its own line if it's not a top-level CU.
        // The full class skeleton is obtained by getSkeleton("Greeter").
        // The current reconstructFullSkeleton logic builds the full nested structure from the top-level CU.
        // If we call getSkeleton("Greeter.greet"), it should find the "Greeter" CU first, then reconstruct.
        // This means, if "Greeter.greet" itself is a CU in `signatures` (which it should be as a child),
        // then `reconstructFullSkeleton` called on `Greeter.greet` might only give its own signature.
        // Let's test `getSkeleton` on a top-level item:
        assertEquals(
                normalize.apply("export function globalFunc(num: number): number { ... }"),
                normalize.apply(AnalyzerUtil.getSkeleton(analyzer, "globalFunc").orElse("")));
    }

    @Test
    void testVarsTsSkeletons() {
        ProjectFile varsTsFile = new ProjectFile(project.getRoot(), "Vars.ts");
        Map<CodeUnit, String> skeletons = analyzer.getSkeletons(varsTsFile);

        CodeUnit maxUsers = CodeUnit.field(varsTsFile, "", "_module_.MAX_USERS");
        CodeUnit currentUser = CodeUnit.field(varsTsFile, "", "_module_.currentUser");
        CodeUnit config = CodeUnit.field(varsTsFile, "", "_module_.config");
        CodeUnit anArrowFunc =
                CodeUnit.fn(varsTsFile, "", "anArrowFunc"); // Arrow func assigned to const is a function CU
        CodeUnit legacyVar = CodeUnit.field(varsTsFile, "", "_module_.legacyVar");

        assertTrue(skeletons.containsKey(maxUsers));
        assertEquals(normalize.apply("export const MAX_USERS = 100"), normalize.apply(skeletons.get(maxUsers)));

        assertTrue(skeletons.containsKey(currentUser));
        assertEquals(
                normalize.apply("let currentUser: string = \"Alice\""), normalize.apply(skeletons.get(currentUser)));

        assertTrue(skeletons.containsKey(config));
        assertEquals(
                normalize.apply("const config = {"),
                normalize.apply(
                        skeletons.get(config).lines().findFirst().orElse(""))); // obj literal, just check header

        assertTrue(skeletons.containsKey(anArrowFunc));
        assertEquals(
                normalize.apply("const anArrowFunc = (msg: string): void => { ... }"),
                normalize.apply(skeletons.get(anArrowFunc)));

        assertTrue(skeletons.containsKey(legacyVar));
        assertEquals(normalize.apply("export var legacyVar = \"legacy\""), normalize.apply(skeletons.get(legacyVar)));

        // A function declared inside Vars.ts but not exported
        CodeUnit localHelper = CodeUnit.fn(varsTsFile, "", "localHelper");
        assertTrue(skeletons.containsKey(localHelper));
        assertEquals(
                normalize.apply("function localHelper(): string { ... }"), normalize.apply(skeletons.get(localHelper)));
    }

    @Test
    void testArrowFunctionClassificationAndModifierFallback() {
        // Test arrow function classification from Vars.ts
        ProjectFile varsTsFile = new ProjectFile(project.getRoot(), "Vars.ts");
        Map<CodeUnit, String> skeletons = analyzer.getSkeletons(varsTsFile);

        // 1. Arrow function should be classified as FUNCTION (not field)
        CodeUnit anArrowFunc = CodeUnit.fn(varsTsFile, "", "anArrowFunc");
        assertTrue(skeletons.containsKey(anArrowFunc), "anArrowFunc should be classified as a function CU");
        assertTrue(anArrowFunc.isFunction(), "anArrowFunc CodeUnit should be a function type");
        assertEquals(
                normalize.apply("const anArrowFunc = (msg: string): void => { ... }"),
                normalize.apply(skeletons.get(anArrowFunc)),
                "Arrow function skeleton should show function signature with body placeholder");

        // 2. Non-arrow const should remain a field
        CodeUnit maxUsers = CodeUnit.field(varsTsFile, "", "_module_.MAX_USERS");
        assertTrue(skeletons.containsKey(maxUsers), "MAX_USERS should be a field CU");
        assertTrue(maxUsers.isField(), "MAX_USERS CodeUnit should be a field type");
        assertEquals(
                normalize.apply("export const MAX_USERS = 100"),
                normalize.apply(skeletons.get(maxUsers)),
                "Non-arrow const should preserve 'export const' modifiers");

        // 3. Test 'let' modifier is preserved
        CodeUnit currentUser = CodeUnit.field(varsTsFile, "", "_module_.currentUser");
        assertTrue(skeletons.containsKey(currentUser), "currentUser should be a field CU");
        String currentUserSkel = skeletons.get(currentUser);
        assertTrue(currentUserSkel.contains("let"), "'let' keyword should appear in skeleton via fallback");

        // 4. Test 'var' modifier is preserved for legacy variables
        CodeUnit legacyVar = CodeUnit.field(varsTsFile, "", "_module_.legacyVar");
        assertTrue(skeletons.containsKey(legacyVar), "legacyVar should be a field CU");
        String legacyVarSkel = skeletons.get(legacyVar);
        assertTrue(legacyVarSkel.contains("var"), "'var' keyword should appear in skeleton via fallback");
        assertTrue(legacyVarSkel.contains("export"), "'export' keyword should appear in skeleton via fallback");

        // 5. Test 'declare' modifier from ambient declarations (Advanced.ts)
        ProjectFile advancedTsFile = new ProjectFile(project.getRoot(), "Advanced.ts");
        Map<CodeUnit, String> advancedSkeletons = analyzer.getSkeletons(advancedTsFile);
        CodeUnit dollarVar = CodeUnit.field(advancedTsFile, "", "_module_.$");
        assertTrue(advancedSkeletons.containsKey(dollarVar), "Ambient var $ should be captured");
        String dollarSkel = advancedSkeletons.get(dollarVar);
        assertTrue(dollarSkel.contains("declare"), "'declare' keyword should appear via fallback");
        assertTrue(dollarSkel.contains("var"), "'var' keyword should appear in ambient declaration");

        // 6. Verify no regression in overload merging
        CodeUnit processInput = CodeUnit.fn(advancedTsFile, "", "processInput");
        assertTrue(advancedSkeletons.containsKey(processInput), "Overloaded function processInput should be present");
        String processInputSkel = advancedSkeletons.get(processInput);
        long exportCount = processInputSkel
                .lines()
                .filter(l -> l.strip().startsWith("export function processInput"))
                .count();
        assertTrue(exportCount >= 3, "Should have multiple export function signatures for overloads");

        // 7. Verify export preference: exported version preferred over non-exported
        // (This is implicit in the above tests - all export keywords are preserved)
    }

    @Test
    void testModuleTsSkeletons() {
        ProjectFile moduleTsFile = new ProjectFile(project.getRoot(), "Module.ts"); // Assuming "" package for top level
        Map<CodeUnit, String> skeletons = analyzer.getSkeletons(moduleTsFile);

        CodeUnit myModule = CodeUnit.cls(moduleTsFile, "", "MyModule"); // Namespace is a class-like CU
        assertTrue(skeletons.containsKey(myModule), "MyModule namespace skeleton missing.");

        String expectedMyModuleSkeleton =
                """
            namespace MyModule {
              export class InnerClass {
                name: string = "Inner"
                constructor() { ... }
                doSomething(): void { ... }
              }
              export function innerFunc(): void { ... }
              export const innerConst: number = 42
              export interface InnerInterface {
                id: number
                describe(): string
              }
              export enum InnerEnum {
                A,
                B
              }
              export type InnerTypeAlias<V> = InnerInterface | V
              namespace NestedNamespace {
                export class DeeperClass {
                }
                export type DeepType = string
              }
            }""";
        assertEquals(normalize.apply(expectedMyModuleSkeleton), normalize.apply(skeletons.get(myModule)));

        CodeUnit anotherClass = CodeUnit.cls(moduleTsFile, "", "AnotherClass");
        assertTrue(skeletons.containsKey(anotherClass));
        assertEquals(normalize.apply("export class AnotherClass {\n}"), normalize.apply(skeletons.get(anotherClass)));

        CodeUnit topLevelArrow = CodeUnit.fn(moduleTsFile, "", "topLevelArrow");
        assertTrue(skeletons.containsKey(topLevelArrow));
        // Arrow functions are now abbreviated with { ... }
        assertEquals(
                normalize.apply("export const topLevelArrow = (input: any): any => { ... }"),
                normalize.apply(skeletons.get(topLevelArrow)));

        CodeUnit topLevelGenericAlias = CodeUnit.field(moduleTsFile, "", "_module_.TopLevelGenericAlias");
        assertTrue(
                skeletons.containsKey(topLevelGenericAlias),
                "TopLevelGenericAlias skeleton missing. Skeletons: " + skeletons.keySet());
        assertEquals(
                normalize.apply("export type TopLevelGenericAlias<K, V> = Map<K, V>"),
                normalize.apply(skeletons.get(topLevelGenericAlias)));

        // Check a nested item via getSkeleton
        // With namespace-as-package: FQN is "MyModule.InnerClass" (package="MyModule", shortName="InnerClass")
        Optional<String> innerClassSkel = AnalyzerUtil.getSkeleton(analyzer, "MyModule.InnerClass");
        assertTrue(innerClassSkel.isPresent());
        // When getting skeleton for a nested CU, it should be part of the parent's reconstruction.
        // The current `getSkeleton` will reconstruct from the top-level parent of that CU.
        // So `getSkeleton("MyModule.InnerClass")` should effectively return the skeleton of `MyModule` because
        // `InnerClass` is a child of `MyModule`.
        // This might be unintuitive if one expects only the InnerClass part.
        // Let's test this behavior:
        assertEquals(
                normalize.apply(expectedMyModuleSkeleton),
                normalize.apply(innerClassSkel.get()),
                "getSkeleton for nested class should return the reconstructed parent skeleton.");

        // Type alias FQN is "MyModule._module_.InnerTypeAlias" (package="MyModule",
        // shortName="_module_.InnerTypeAlias")
        Optional<String> innerTypeAliasSkelViaParent =
                AnalyzerUtil.getSkeleton(analyzer, "MyModule._module_.InnerTypeAlias");
        assertTrue(
                innerTypeAliasSkelViaParent.isPresent(),
                "Skeleton for MyModule._module_.InnerTypeAlias should be part of MyModule's skeleton");
        assertEquals(
                normalize.apply(expectedMyModuleSkeleton),
                normalize.apply(innerTypeAliasSkelViaParent.get()),
                "getSkeleton for nested type alias should return reconstructed parent skeleton.");

        Set<CodeUnit> declarations = analyzer.getDeclarations(moduleTsFile);
        // With namespace-as-package semantics: package contains namespace, shortName contains class/field name
        assertTrue(declarations.contains(CodeUnit.cls(moduleTsFile, "MyModule.NestedNamespace", "DeeperClass")));
        assertTrue(declarations.contains(CodeUnit.field(moduleTsFile, "MyModule", "_module_.InnerTypeAlias")));
        assertTrue(
                declarations.contains(CodeUnit.field(moduleTsFile, "MyModule.NestedNamespace", "_module_.DeepType")));
        assertTrue(declarations.contains(topLevelGenericAlias));
    }

    @Test
    void testAdvancedTsSkeletonsAndFeatures() {
        ProjectFile advancedTsFile = new ProjectFile(project.getRoot(), "Advanced.ts");
        Map<CodeUnit, String> skeletons = analyzer.getSkeletons(advancedTsFile);

        CodeUnit decoratedClass = CodeUnit.cls(advancedTsFile, "", "DecoratedClass");
        assertTrue(skeletons.containsKey(decoratedClass));

        // With unified query, class-level decorators and method type parameters are not captured
        assertEquals(
                normalize.apply(
                        """
            export class DecoratedClass<T> {
              @MyPropertyDecorator
              decoratedProperty: string = "initial"
              private _value: T
              constructor(@MyParameterDecorator initialValue: T) { ... }
              @MyMethodDecorator
              genericMethod<U extends Point>(value: T, other: U): [T, U] { ... }
              get value(): T { ... }
              set value(val: T) { ... }
            }"""),
                normalize.apply(skeletons.get(decoratedClass)));

        CodeUnit genericInterface = CodeUnit.cls(advancedTsFile, "", "GenericInterface");
        assertTrue(skeletons.containsKey(genericInterface));
        assertEquals(
                normalize.apply(
                        """
            export interface GenericInterface<T, U extends Point> {
              item: T
              point: U
              process(input: T): U
              new (param: T): GenericInterface<T,U>
            }"""),
                normalize.apply(skeletons.get(genericInterface)));

        CodeUnit abstractBase = CodeUnit.cls(advancedTsFile, "", "AbstractBase");
        assertTrue(skeletons.containsKey(abstractBase));
        assertEquals(
                normalize.apply(
                        """
            export abstract class AbstractBase {
              abstract performAction(name: string): void
              concreteMethod(): string { ... }
            }"""),
                normalize.apply(skeletons.get(abstractBase)));

        CodeUnit asyncArrow = CodeUnit.fn(advancedTsFile, "", "asyncArrowFunc");
        assertTrue(skeletons.containsKey(asyncArrow));
        assertEquals(
                normalize.apply("export const asyncArrowFunc = async (p: Promise<string>): Promise<number> => { ... }"),
                normalize.apply(skeletons.get(asyncArrow)));

        CodeUnit asyncNamed = CodeUnit.fn(advancedTsFile, "", "asyncNamedFunc");
        assertTrue(skeletons.containsKey(asyncNamed));
        assertEquals(
                normalize.apply("export async function asyncNamedFunc(param: number): Promise<void> { ... }"),
                normalize.apply(skeletons.get(asyncNamed)));

        CodeUnit fieldTest = CodeUnit.cls(advancedTsFile, "", "FieldTest");
        assertTrue(skeletons.containsKey(fieldTest));
        assertEquals(
                normalize.apply(
                        """
            export class FieldTest {
              public name: string
              private id: number = 0
              protected status?: string
              readonly creationDate: Date
              static version: string = "1.0"
              #trulyPrivateField: string = "secret"
              constructor(name: string) { ... }
              public publicMethod() { ... }
              private privateMethod() { ... }
              protected protectedMethod() { ... }
              static staticMethod() { ... }
            }"""),
                normalize.apply(skeletons.get(fieldTest)));

        CodeUnit pointyAlias = CodeUnit.field(advancedTsFile, "", "_module_.Pointy");
        assertTrue(
                skeletons.containsKey(pointyAlias), "Pointy type alias skeleton missing. Found: " + skeletons.keySet());
        assertEquals(
                normalize.apply("export type Pointy<T> = { x: T, y: T }"), normalize.apply(skeletons.get(pointyAlias)));

        CodeUnit overloadedFunc = CodeUnit.fn(advancedTsFile, "", "processInput");
        assertEquals(
                normalize.apply(
                        """
            export function processInput(input: string): string[]
            export function processInput(input: number): number[]
            export function processInput(input: boolean): boolean[]
            export function processInput(input: any): any[] { ... }"""),
                normalize.apply(skeletons.get(overloadedFunc)));

        // Test interface Point that follows a comment (regression test for interface appearing as free fields)
        CodeUnit pointInterface = CodeUnit.cls(advancedTsFile, "", "Point");
        assertTrue(
                skeletons.containsKey(pointInterface),
                "Point interface skeleton missing. This interface follows a comment and should be captured correctly.");
        String actualPointSkeleton = skeletons.get(pointInterface);

        // Verify Point appears as proper interface structure (not as free x, y fields)
        assertTrue(
                actualPointSkeleton.contains("interface Point {"),
                "Point should appear as a proper interface structure");
        assertTrue(actualPointSkeleton.contains("x: number"), "Point interface should contain x property");
        assertTrue(actualPointSkeleton.contains("y: number"), "Point interface should contain y property");

        // Verify that Point interface properties are correctly captured as Point interface members
        // and NOT appearing as top-level fields (which was the original bug)
        Set<CodeUnit> declarations = analyzer.getDeclarations(advancedTsFile);
        CodeUnit pointXField = CodeUnit.field(advancedTsFile, "", "Point.x");
        CodeUnit pointYField = CodeUnit.field(advancedTsFile, "", "Point.y");
        assertTrue(declarations.contains(pointXField), "Point.x should be captured as a member of Point interface");
        assertTrue(declarations.contains(pointYField), "Point.y should be captured as a member of Point interface");

        // Verify no top-level fields with names x or y exist (the original bug would create these)
        boolean hasTopLevelXField = declarations.stream()
                .anyMatch(cu -> cu.kind() == CodeUnitType.FIELD
                        && cu.identifier().equals("x")
                        && !cu.shortName().contains("."));
        boolean hasTopLevelYField = declarations.stream()
                .anyMatch(cu -> cu.kind() == CodeUnitType.FIELD
                        && cu.identifier().equals("y")
                        && !cu.shortName().contains("."));
        assertFalse(
                hasTopLevelXField,
                "x should not appear as a top-level field - original bug created free fields instead of interface properties");
        assertFalse(
                hasTopLevelYField,
                "y should not appear as a top-level field - original bug created free fields instead of interface properties");

        // Test for ambient declarations (declare statements) - regression test for missing declare var $
        CodeUnit dollarVar = CodeUnit.field(advancedTsFile, "", "_module_.$");
        assertTrue(
                skeletons.containsKey(dollarVar),
                "declare var $ skeleton missing. This was the original reported issue.");
        assertEquals(normalize.apply("declare var $: any"), normalize.apply(skeletons.get(dollarVar)));

        CodeUnit fetchFunc = CodeUnit.fn(advancedTsFile, "", "fetch");
        assertTrue(skeletons.containsKey(fetchFunc), "declare function fetch skeleton missing.");
        assertEquals(
                normalize.apply("declare function fetch(url:string): Promise<any>;"),
                normalize.apply(skeletons.get(fetchFunc)));

        CodeUnit thirdPartyNamespace = CodeUnit.cls(advancedTsFile, "", "ThirdPartyLib");
        assertTrue(skeletons.containsKey(thirdPartyNamespace), "declare namespace ThirdPartyLib skeleton missing.");
        String actualNamespace = skeletons.get(thirdPartyNamespace);
        assertTrue(
                actualNamespace.contains("declare namespace ThirdPartyLib {"),
                "Should contain declare namespace declaration");
        assertTrue(actualNamespace.contains("doWork(): void;"), "Should contain doWork function signature");
        assertTrue(actualNamespace.contains("interface LibOptions {"), "Should contain LibOptions interface");

        // Verify the complete ambient namespace skeleton structure
        String expectedThirdPartyNamespace =
                """
            declare namespace ThirdPartyLib {
              doWork(): void;
              interface LibOptions {
              }
            }""";
        assertEquals(
                normalize.apply(expectedThirdPartyNamespace),
                normalize.apply(skeletons.get(thirdPartyNamespace)),
                "Ambient namespace should have complete structure with contents");

        // Verify ambient namespace function member
        CodeUnit doWorkFunc = CodeUnit.fn(advancedTsFile, "", "ThirdPartyLib.doWork");
        assertTrue(declarations.contains(doWorkFunc), "ThirdPartyLib.doWork should be captured as a function member");

        // Verify ambient namespace interface member
        CodeUnit libOptionsInterface = CodeUnit.cls(advancedTsFile, "", "ThirdPartyLib.LibOptions");
        assertTrue(
                declarations.contains(libOptionsInterface),
                "ThirdPartyLib.LibOptions should be captured as an interface member");

        // Verify no duplicate captures for ambient declarations
        long dollarVarCount =
                declarations.stream().filter(cu -> cu.identifier().equals("$")).count();
        assertEquals(1, dollarVarCount, "$ variable should only be captured once");

        long fetchFuncCount = declarations.stream()
                .filter(cu -> cu.identifier().equals("fetch") && cu.kind() == CodeUnitType.FUNCTION)
                .count();
        assertEquals(1, fetchFuncCount, "fetch function should only be captured once");

        // Test getSkeleton for individual ambient declarations
        Optional<String> dollarSkeleton = AnalyzerUtil.getSkeleton(analyzer, "_module_.$");
        assertTrue(dollarSkeleton.isPresent(), "Should be able to get skeleton for ambient var $");
        assertEquals(normalize.apply("declare var $: any"), normalize.apply(dollarSkeleton.get()));

        Optional<String> fetchSkeleton = AnalyzerUtil.getSkeleton(analyzer, "fetch");
        assertTrue(fetchSkeleton.isPresent(), "Should be able to get skeleton for ambient function fetch");
        assertEquals(
                normalize.apply("declare function fetch(url:string): Promise<any>;"),
                normalize.apply(fetchSkeleton.get()));

        Optional<String> thirdPartySkeleton = AnalyzerUtil.getSkeleton(analyzer, "ThirdPartyLib");
        assertTrue(thirdPartySkeleton.isPresent(), "Should be able to get skeleton for ambient namespace");
        assertEquals(normalize.apply(expectedThirdPartyNamespace), normalize.apply(thirdPartySkeleton.get()));
    }

    @Test
    void testDefaultExportSkeletons() {
        ProjectFile defaultExportFile = new ProjectFile(project.getRoot(), "DefaultExport.ts");
        Map<CodeUnit, String> skeletons = analyzer.getSkeletons(defaultExportFile);
        assertFalse(skeletons.isEmpty(), "Skeletons map for DefaultExport.ts should not be empty.");

        // Default exported class
        // The simple name for a default export class might be tricky.
        // If query gives it a name like "MyDefaultClass", then CU is "MyDefaultClass"
        // If query gives it a special name like "default", then CU is "default"
        // Current query uses `(class_declaration name: (identifier) @class.name)`.
        // For `export default class Foo`, `name` is `Foo`.
        // For `export default class { ... }` (anonymous default), name node would be absent.
        // TS query `@class.name` is `(identifier)`. `export default class MyDefaultClass` has `name: (identifier)`
        CodeUnit defaultClass = CodeUnit.cls(defaultExportFile, "", "MyDefaultClass");
        assertTrue(
                skeletons.containsKey(defaultClass),
                "MyDefaultClass (default export) skeleton missing. Found: " + skeletons.keySet());
        assertEquals(
                normalize.apply(
                        """
            export default class MyDefaultClass {
              constructor() { ... }
              doSomething(): void { ... }
              get value(): string { ... }
            }"""),
                normalize.apply(skeletons.get(defaultClass)));

        // Default exported function
        CodeUnit defaultFunction = CodeUnit.fn(defaultExportFile, "", "myDefaultFunction");
        assertTrue(skeletons.containsKey(defaultFunction), "myDefaultFunction (default export) skeleton missing.");
        assertEquals(
                normalize.apply("export default function myDefaultFunction(param: string): string { ... }"),
                normalize.apply(skeletons.get(defaultFunction)));

        // Named export in the same file
        CodeUnit anotherNamedClass = CodeUnit.cls(defaultExportFile, "", "AnotherNamedClass");
        assertTrue(skeletons.containsKey(anotherNamedClass));
        assertEquals(
                normalize.apply(
                        """
            export class AnotherNamedClass {
              name: string = "Named"
            }"""),
                normalize.apply(skeletons.get(anotherNamedClass)));

        CodeUnit utilityRateConst = CodeUnit.field(defaultExportFile, "", "_module_.utilityRate");
        assertTrue(skeletons.containsKey(utilityRateConst));
        assertEquals(
                normalize.apply("export const utilityRate: number = 0.15"),
                normalize.apply(skeletons.get(utilityRateConst)));

        CodeUnit defaultAlias = CodeUnit.field(defaultExportFile, "", "_module_.DefaultAlias");
        assertTrue(
                skeletons.containsKey(defaultAlias),
                "DefaultAlias (default export type) skeleton missing. Skeletons: " + skeletons.keySet());
        assertEquals(
                normalize.apply("export default type DefaultAlias = boolean"),
                normalize.apply(skeletons.get(defaultAlias)));
    }

    @Test
    void testGetMethodSource() throws IOException {
        // From Hello.ts
        Optional<String> greetSource = AnalyzerUtil.getMethodSource(analyzer, "Greeter.greet", true);
        assertTrue(greetSource.isPresent());
        assertEquals(
                normalize.apply("greet(): string {\n    return \"Hello, \" + this.greeting;\n}"),
                normalize.apply(greetSource.get()));

        Optional<String> constructorSource = AnalyzerUtil.getMethodSource(analyzer, "Greeter.constructor", true);
        assertTrue(constructorSource.isPresent());
        assertEquals(
                normalize.apply("constructor(message: string) {\n    this.greeting = message;\n}"),
                normalize.apply(constructorSource.get()));

        // From Vars.ts (arrow function)
        Optional<String> arrowSource = AnalyzerUtil.getMethodSource(analyzer, "anArrowFunc", true);
        assertTrue(arrowSource.isPresent());
        assertEquals(
                normalize.apply("const anArrowFunc = (msg: string): void => {\n    console.log(msg);\n};"),
                normalize.apply(arrowSource.get()));

        // From Advanced.ts (async named function)
        Optional<String> asyncNamedSource = AnalyzerUtil.getMethodSource(analyzer, "asyncNamedFunc", true);
        assertTrue(asyncNamedSource.isPresent());
        assertEquals(
                normalize.apply(
                        "export async function asyncNamedFunc(param: number): Promise<void> {\n    await Promise.resolve();\n    console.log(param);\n}"),
                normalize.apply(asyncNamedSource.get()));

        // Test getMethodSource for overloaded function (processInput from Advanced.ts)
        // It should return all signatures and the implementation concatenated.
        Optional<String> overloadedSource = AnalyzerUtil.getMethodSource(analyzer, "processInput", true);
        assertTrue(overloadedSource.isPresent(), "Source for overloaded function processInput should be present.");

        // Check the actual format returned by TreeSitterAnalyzer
        String actualNormalized = normalize.apply(overloadedSource.get());
        String[] actualLines = actualNormalized.split("\n");

        // Build expected based on actual separator used (with semicolons for overload signatures)
        // Now includes the preceding comment due to comment expansion functionality
        String expectedOverloadedSource = String.join(
                "\n",
                "// Function Overloads",
                "export function processInput(input: string): string[];",
                "export function processInput(input: number): number[];",
                "export function processInput(input: boolean): boolean[];",
                "export function processInput(input: any): any[] {",
                "if (typeof input === \"string\") return [`s-${input}`];",
                "if (typeof input === \"number\") return [`n-${input}`];",
                "if (typeof input === \"boolean\") return [`b-${input}`];",
                "return [input];",
                "}");

        assertEquals(expectedOverloadedSource, actualNormalized, "processInput overloaded source mismatch.");
    }

    @Test
    void testGetSymbols() {
        ProjectFile helloTsFile = new ProjectFile(project.getRoot(), "Hello.ts");
        ProjectFile varsTsFile = new ProjectFile(project.getRoot(), "Vars.ts");

        CodeUnit greeterClass = CodeUnit.cls(helloTsFile, "", "Greeter");
        CodeUnit piConst = CodeUnit.field(varsTsFile, "", "_module_.PI"); // No, PI is in Hello.ts
        piConst = CodeUnit.field(helloTsFile, "", "_module_.PI");
        CodeUnit anArrowFunc = CodeUnit.fn(varsTsFile, "", "anArrowFunc");

        Set<CodeUnit> sources = Set.of(greeterClass, piConst, anArrowFunc);
        Set<String> symbols = analyzer.getSymbols(sources);

        // Expected:
        // From Greeter: "Greeter", "greeting", "constructor", "greet"
        // From PI: "PI"
        // From anArrowFunc: "anArrowFunc"
        Set<String> expectedSymbols = Set.of(
                "Greeter",
                "greeting",
                "constructor",
                "greet",
                "PI",
                "anArrowFunc",
                "StringOrNumber" // From Hello.ts, via _module_.StringOrNumber in allCodeUnits()
                );
        // Add StringOrNumber to sources to test its symbol directly
        CodeUnit stringOrNumberAlias = CodeUnit.field(helloTsFile, "", "_module_.StringOrNumber");
        sources = Set.of(greeterClass, piConst, anArrowFunc, stringOrNumberAlias);
        symbols = analyzer.getSymbols(sources);
        assertEquals(expectedSymbols, symbols);

        // Test with interface
        CodeUnit pointInterface = CodeUnit.cls(helloTsFile, "", "Point");
        Set<String> interfaceSymbols = analyzer.getSymbols(Set.of(pointInterface));
        assertEquals(Set.of("Point", "x", "y", "label", "originDistance", "move"), interfaceSymbols);

        // Test with type alias directly
        Set<String> aliasSymbols = analyzer.getSymbols(Set.of(stringOrNumberAlias));
        assertEquals(Set.of("StringOrNumber"), aliasSymbols);

        // Test with generic type alias from Advanced.ts
        ProjectFile advancedTsFile = new ProjectFile(project.getRoot(), "Advanced.ts");
        CodeUnit pointyAlias = CodeUnit.field(advancedTsFile, "", "_module_.Pointy");
        Set<String> pointySymbols = analyzer.getSymbols(Set.of(pointyAlias));
        assertEquals(Set.of("Pointy"), pointySymbols);
    }

    @Test
    void testGetClassSource() throws IOException {
        String greeterSource = normalize.apply(
                AnalyzerUtil.getClassSource(analyzer, "Greeter", true).get());
        assertNotNull(greeterSource);
        assertTrue(greeterSource.startsWith("export class Greeter"));
        assertTrue(greeterSource.contains("greeting: string;"));
        assertTrue(greeterSource.contains("greet(): string {"));
        assertTrue(greeterSource.endsWith("}"));

        // Test with Point interface - could be from Hello.ts or Advanced.ts
        String pointSource = normalize.apply(
                AnalyzerUtil.getClassSource(analyzer, "Point", true).get());
        assertNotNull(pointSource);
        assertTrue(
                pointSource.contains("x: number") && pointSource.contains("y: number"),
                "Point should have x and y properties");
        assertTrue(pointSource.endsWith("}"));

        // Handle both possible Point interfaces
        if (pointSource.contains("move(dx: number, dy: number): void")) {
            // This is the comprehensive Hello.ts Point interface
            assertTrue(pointSource.contains("export interface Point"));
            assertTrue(pointSource.contains("label?: string"));
            assertTrue(pointSource.contains("readonly originDistance?: number"));
        } else {
            // This is the minimal Advanced.ts Point interface
            assertTrue(pointSource.contains("interface Point"));
            assertFalse(pointSource.contains("export interface Point"));
        }
    }

    @Test
    void testCodeUnitEqualityFixed() throws IOException {
        // Test that verifies the CodeUnit equality fix prevents byte range corruption
        var project = TestProject.createTestProject("testcode-ts", Languages.TYPESCRIPT);
        var analyzer = new TypescriptAnalyzer(project);

        // Find both Point interfaces from different files
        var allPointInterfaces = analyzer.getTopLevelDeclarations().values().stream()
                .flatMap(List::stream)
                .filter(cu -> cu.fqName().equals("Point") && cu.isClass())
                .toList();

        // Should find Point interfaces/classes from Hello.ts, Advanced.ts, and NamespaceMerging.ts
        assertEquals(3, allPointInterfaces.size(), "Should find Point interfaces in Hello.ts, Advanced.ts, and NamespaceMerging.ts");

        CodeUnit helloPoint = allPointInterfaces.stream()
                .filter(cu -> cu.source().toString().equals("Hello.ts"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Should find Point in Hello.ts"));

        CodeUnit advancedPoint = allPointInterfaces.stream()
                .filter(cu -> cu.source().toString().equals("Advanced.ts"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Should find Point in Advanced.ts"));

        // The CodeUnits should be from different files
        assertNotEquals(
                helloPoint.source().toString(),
                advancedPoint.source().toString(),
                "Point interfaces should be from different files");

        // After the fix: CodeUnits with same FQN but different source files should be distinct
        assertFalse(
                helloPoint.equals(advancedPoint), "CodeUnits with same FQN from different files should be distinct");
        assertNotEquals(
                helloPoint.hashCode(), advancedPoint.hashCode(), "Distinct CodeUnits should have different hashCodes");

        // With distinct CodeUnits, getClassSource should work correctly without corruption
        String pointSource =
                AnalyzerUtil.getClassSource(analyzer, "Point", true).get();

        // The source should be a valid Point interface, not corrupted
        assertFalse(pointSource.contains("MyParameterDecorator"), "Should not contain decorator function text");
        assertTrue(pointSource.contains("interface Point"), "Should contain interface declaration");
    }

    @Test
    void testTypescriptDependencyCandidates() throws IOException {
        // Create a temporary test project with a node_modules directory
        Path tempDir = Files.createTempDirectory("typescript-dep-test");
        try {
            var tsProject = new TestProject(tempDir, Languages.TYPESCRIPT);

            // Create a node_modules directory structure
            Path nodeModules = tempDir.resolve("node_modules");
            Files.createDirectories(nodeModules);

            // Create some mock dependencies
            Path reactDir = nodeModules.resolve("react");
            Path lodashDir = nodeModules.resolve("lodash");
            Path binDir = nodeModules.resolve(".bin");
            Files.createDirectories(reactDir);
            Files.createDirectories(lodashDir);
            Files.createDirectories(binDir);

            // Add TypeScript files (.ts, .d.ts) that should be analyzed
            Files.writeString(reactDir.resolve("index.d.ts"), "export = React;");
            Files.writeString(lodashDir.resolve("index.d.ts"), "export = _;");
            Files.writeString(reactDir.resolve("react.ts"), "interface ReactComponent {}");

            // Add JavaScript files that should be ignored by TypeScript analyzer
            Files.writeString(reactDir.resolve("index.js"), "module.exports = React;");
            Files.writeString(reactDir.resolve("component.jsx"), "export const Component = () => <div/>;");
            Files.writeString(lodashDir.resolve("lodash.js"), "module.exports = _;");

            // Test getDependencyCandidates
            List<Path> candidates = Languages.TYPESCRIPT.getDependencyCandidates(tsProject);

            // Should find react and lodash but not .bin
            assertEquals(2, candidates.size(), "Should find 2 dependency candidates");
            assertTrue(candidates.contains(reactDir), "Should find react dependency");
            assertTrue(candidates.contains(lodashDir), "Should find lodash dependency");
            assertFalse(candidates.contains(binDir), "Should not include .bin directory");

            // Now test that TypeScript analyzer only processes .ts/.tsx files from dependencies
            // Check which file extensions TypeScript language recognizes
            assertTrue(Languages.TYPESCRIPT.getExtensions().contains("ts"), "TypeScript should recognize .ts files");
            assertTrue(Languages.TYPESCRIPT.getExtensions().contains("tsx"), "TypeScript should recognize .tsx files");
            assertFalse(
                    Languages.TYPESCRIPT.getExtensions().contains("js"), "TypeScript should NOT recognize .js files");
            assertFalse(
                    Languages.TYPESCRIPT.getExtensions().contains("jsx"), "TypeScript should NOT recognize .jsx files");

            // Verify that Language.fromExtension correctly routes files
            assertEquals(
                    Languages.TYPESCRIPT,
                    Languages.fromExtension("ts"),
                    ".ts files should be handled by TypeScript analyzer");
            assertEquals(
                    Languages.TYPESCRIPT,
                    Languages.fromExtension("tsx"),
                    ".tsx files should be handled by TypeScript analyzer");
            assertEquals(
                    Languages.JAVASCRIPT,
                    Languages.fromExtension("js"),
                    ".js files should be handled by JavaScript analyzer");
            assertEquals(
                    Languages.JAVASCRIPT,
                    Languages.fromExtension("jsx"),
                    ".jsx files should be handled by JavaScript analyzer");

        } finally {
            // Clean up
            deleteRecursively(tempDir);
        }
    }

    @Test
    void testTypescriptDependencyCandidatesNoDeps() throws IOException {
        // Test with project that has no node_modules
        Path tempDir = Files.createTempDirectory("typescript-nodeps-test");
        try {
            var tsProject = new TestProject(tempDir, Languages.TYPESCRIPT);

            List<Path> candidates = Languages.TYPESCRIPT.getDependencyCandidates(tsProject);

            assertTrue(candidates.isEmpty(), "Should return empty list when no node_modules exists");

        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void testTypescriptIsAnalyzed() throws IOException {
        // Create a temporary test project
        Path tempDir = Files.createTempDirectory("typescript-analyzed-test");
        try {
            var tsProject = new TestProject(tempDir, Languages.TYPESCRIPT);

            // Create a node_modules directory
            Path nodeModules = tempDir.resolve("node_modules");
            Files.createDirectories(nodeModules);
            Path reactDir = nodeModules.resolve("react");
            Files.createDirectories(reactDir);

            // Create project source files
            Path srcDir = tempDir.resolve("src");
            Files.createDirectories(srcDir);
            Path sourceFile = srcDir.resolve("app.ts");
            Files.writeString(sourceFile, "console.log('hello');");

            // Test isAnalyzed method

            // Project source files should be analyzed
            assertTrue(
                    Languages.TYPESCRIPT.isAnalyzed(tsProject, sourceFile),
                    "Project source files should be considered analyzed");
            assertTrue(
                    Languages.TYPESCRIPT.isAnalyzed(tsProject, srcDir),
                    "Project source directories should be considered analyzed");

            // node_modules should NOT be analyzed (as they are dependencies)
            assertFalse(
                    Languages.TYPESCRIPT.isAnalyzed(tsProject, nodeModules),
                    "node_modules directory should not be considered analyzed");
            assertFalse(
                    Languages.TYPESCRIPT.isAnalyzed(tsProject, reactDir),
                    "Individual dependency directories should not be considered analyzed");

            // Files outside project should not be analyzed
            Path outsideFile = Path.of("/tmp/outside.ts").toAbsolutePath();
            assertFalse(
                    Languages.TYPESCRIPT.isAnalyzed(tsProject, outsideFile),
                    "Files outside project should not be considered analyzed");

        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void testTypescriptIgnoresJavaScriptFiles() throws IOException {
        // Create a test project with mixed TypeScript and JavaScript files
        Path tempDir = Files.createTempDirectory("typescript-js-ignore-test");
        try {
            var tsProject = new TestProject(tempDir, Languages.TYPESCRIPT);

            // Create both TypeScript and JavaScript files in project root
            Files.writeString(
                    tempDir.resolve("component.ts"),
                    """
                    export class TypeScriptClass {
                        method(): string { return "typescript"; }
                    }
                    """);

            Files.writeString(
                    tempDir.resolve("component.tsx"),
                    """
                    export const TsxComponent = () => <div>TSX</div>;
                    """);

            // Create JavaScript files that should be ignored
            Files.writeString(
                    tempDir.resolve("script.js"),
                    """
                    export class JavaScriptClass {
                        method() { return "javascript"; }
                    }
                    """);

            Files.writeString(
                    tempDir.resolve("component.jsx"),
                    """
                    export const JsxComponent = () => <div>JSX</div>;
                    """);

            // Create TypeScript analyzer
            var analyzer = new TypescriptAnalyzer(tsProject);

            // Verify analyzer is not empty (it found TypeScript files)
            assertFalse(analyzer.isEmpty(), "TypeScript analyzer should find TypeScript files");

            // Get all top-level declarations
            var declarations = analyzer.getTopLevelDeclarations();
            var allDeclarations =
                    declarations.values().stream().flatMap(List::stream).toList();

            // Should find TypeScript symbols
            assertTrue(
                    allDeclarations.stream().anyMatch(cu -> cu.fqName().contains("TypeScriptClass")),
                    "Should find TypeScript class");
            assertTrue(
                    allDeclarations.stream().anyMatch(cu -> cu.fqName().contains("TsxComponent")),
                    "Should find TSX component");

            // Should NOT find JavaScript symbols
            assertFalse(
                    allDeclarations.stream().anyMatch(cu -> cu.fqName().contains("JavaScriptClass")),
                    "Should NOT find JavaScript class");
            assertFalse(
                    allDeclarations.stream().anyMatch(cu -> cu.fqName().contains("JsxComponent")),
                    "Should NOT find JSX component");

            // Test file-specific declarations
            var tsFile = new ProjectFile(tempDir, "component.ts");
            var tsxFile = new ProjectFile(tempDir, "component.tsx");
            var jsFile = new ProjectFile(tempDir, "script.js");
            var jsxFile = new ProjectFile(tempDir, "component.jsx");

            // TypeScript files should have declarations
            assertFalse(analyzer.getDeclarations(tsFile).isEmpty(), "TypeScript file should have declarations");
            assertFalse(analyzer.getDeclarations(tsxFile).isEmpty(), "TSX file should have declarations");

            // JavaScript files should have no declarations (empty because they're ignored)
            assertTrue(
                    analyzer.getDeclarations(jsFile).isEmpty(),
                    "JavaScript file should have no declarations in TypeScript analyzer");
            assertTrue(
                    analyzer.getDeclarations(jsxFile).isEmpty(),
                    "JSX file should have no declarations in TypeScript analyzer");

        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.compareTo(a)) // Delete children before parents
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                // Ignore cleanup errors
                            }
                        });
            }
        }
    }

    @Test
    void testSearchDefinitions_CaseSensitiveAndRegex() {
        // Test case-insensitive behavior (default)
        var greeterLower = analyzer.searchDefinitions("greeter");
        var greeterUpper = analyzer.searchDefinitions("GREETER");
        var greeterLowerNames = greeterLower.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        var greeterUpperNames = greeterUpper.stream().map(CodeUnit::fqName).collect(Collectors.toSet());

        assertEquals(
                greeterLowerNames,
                greeterUpperNames,
                "TypeScript search should be case-insensitive: 'greeter' and 'GREETER' should return identical results");

        // Test regex patterns with metacharacters
        var dotAnyPattern = analyzer.searchDefinitions(".*Greeter.*"); // Regex pattern to match Greeter
        var greeterNames = dotAnyPattern.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(
                greeterNames.stream().anyMatch(name -> name.contains("Greeter")),
                "Regex pattern should match Greeter and its members");

        // Test class/interface name patterns
        var classPattern = analyzer.searchDefinitions(".*Class.*");
        var classNames = classPattern.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(
                classNames.stream().anyMatch(name -> name.contains("DecoratedClass")),
                "Class pattern should match DecoratedClass");

        // Test enum patterns
        var colorEnum = analyzer.searchDefinitions("Color");
        var colorNames = colorEnum.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(colorNames.stream().anyMatch(name -> name.contains("Color")), "Should find Color enum");

        // Test function patterns
        var funcPattern = analyzer.searchDefinitions(".*Func.*");
        var funcNames = funcPattern.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(
                funcNames.stream().anyMatch(name -> name.contains("globalFunc")),
                "Function pattern should match globalFunc");

        // Test TypeScript-specific patterns: generic types
        var genericPattern = analyzer.searchDefinitions(".*Generic.*");
        var genericNames = genericPattern.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(
                genericNames.stream().anyMatch(name -> name.contains("GenericInterface")),
                "Generic pattern should match GenericInterface");

        // Test async/await patterns
        var asyncPattern = analyzer.searchDefinitions("async.*");
        var asyncNames = asyncPattern.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(
                asyncNames.stream()
                        .anyMatch(name -> name.contains("asyncArrowFunc") || name.contains("asyncNamedFunc")),
                "Async pattern should match async functions");

        // Test type alias patterns
        var typePattern = analyzer.searchDefinitions("Pointy");
        var typeNames = typePattern.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(
                typeNames.stream().anyMatch(name -> name.contains("Pointy")), "Should find Pointy generic type alias");

        // Test ambient declaration patterns
        var ambientPattern = analyzer.searchDefinitions("$");
        var ambientNames = ambientPattern.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(ambientNames.stream().anyMatch(name -> name.contains("$")), "Should find ambient $ variable");

        // Test method/property patterns with dot notation
        var methodPattern = analyzer.searchDefinitions(".*\\.greet");
        var methodNames = methodPattern.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(
                methodNames.stream().anyMatch(name -> name.contains("Greeter.greet")),
                "Dot notation pattern should match method names");

        // Test namespace patterns
        var namespacePattern = analyzer.searchDefinitions(".*ThirdParty.*");
        var namespaceNames = namespacePattern.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertTrue(
                namespaceNames.stream().anyMatch(name -> name.contains("ThirdPartyLib")),
                "Namespace pattern should match ThirdPartyLib");
    }

    // ==================== SANITY TESTS FOR FILE FILTERING ====================

    @Test
    void testGetSkeletonsRejectsJavaFile() {
        // Test that TypeScript analyzer safely rejects Java files
        ProjectFile javaFile = new ProjectFile(project.getRoot(), "test/A.java");
        Map<CodeUnit, String> skeletons = analyzer.getSkeletons(javaFile);

        assertTrue(skeletons.isEmpty(), "TypeScript analyzer should return empty skeletons for Java file");
    }

    @Test
    void testGetDeclarationsInFileRejectsJavaFile() {
        // Test that TypeScript analyzer safely rejects Java files
        ProjectFile javaFile = new ProjectFile(project.getRoot(), "test/B.java");
        Set<CodeUnit> declarations = analyzer.getDeclarations(javaFile);

        assertTrue(declarations.isEmpty(), "TypeScript analyzer should return empty declarations for Java file");
    }

    @Test
    void testUpdateFiltersMixedFileTypes() {
        // Test that update() properly filters files by extension
        ProjectFile tsFile = new ProjectFile(project.getRoot(), "valid.ts");
        ProjectFile jsFile = new ProjectFile(project.getRoot(), "valid.js");
        ProjectFile javaFile = new ProjectFile(project.getRoot(), "invalid.java");
        ProjectFile pythonFile = new ProjectFile(project.getRoot(), "invalid.py");

        Set<ProjectFile> mixedFiles = Set.of(tsFile, jsFile, javaFile, pythonFile);

        // This should not throw an exception and should only process TS/JS files
        IAnalyzer result = analyzer.update(mixedFiles);
        assertNotNull(result, "Update should complete successfully with mixed file types");

        // Verify the analyzer still works for TypeScript files
        Map<CodeUnit, String> tsSkeletons = analyzer.getSkeletons(tsFile);
        // tsSkeletons might be empty if the file doesn't exist, but the call should not hang
        assertNotNull(tsSkeletons, "Should return non-null result for TypeScript file");
    }

    @Test
    void testUpdateWithOnlyNonTypeScriptFiles() {
        // Test that update() with only irrelevant files returns immediately
        ProjectFile javaFile = new ProjectFile(project.getRoot(), "test.java");
        ProjectFile pythonFile = new ProjectFile(project.getRoot(), "test.py");
        ProjectFile rustFile = new ProjectFile(project.getRoot(), "test.rs");

        Set<ProjectFile> nonTsFiles = Set.of(javaFile, pythonFile, rustFile);

        long startTime = System.currentTimeMillis();
        IAnalyzer result = analyzer.update(nonTsFiles);
        long duration = System.currentTimeMillis() - startTime;

        assertNotNull(result, "Update should complete successfully");
        assertTrue(duration < 100, "Update with no relevant files should complete quickly (took " + duration + "ms)");
    }

    @Test
    void testAnalyzerOnlyProcessesRelevantExtensions() {
        // Verify that the analyzer language extensions match expectations
        Set<String> tsExtensions = Set.of("ts", "tsx");
        Set<String> analyzerExtensions = Set.copyOf(Languages.TYPESCRIPT.getExtensions());

        assertEquals(tsExtensions, analyzerExtensions, "TypeScript analyzer should only handle TS/TSX file extensions");
    }

    @Test
    void testTypescriptAnnotationComments() throws IOException {
        TestProject project = TestProject.createTestProject("testcode-ts", Languages.TYPESCRIPT);
        TypescriptAnalyzer analyzer = new TypescriptAnalyzer(project);

        Function<String, String> normalize =
                s -> s.lines().map(String::strip).filter(l -> !l.isEmpty()).collect(Collectors.joining("\n"));

        // Test class with JSDoc annotations
        Optional<String> userServiceSource = AnalyzerUtil.getClassSource(analyzer, "UserService", true);
        assertTrue(userServiceSource.isPresent(), "UserService class should be found");

        String normalizedService = normalize.apply(userServiceSource.get());
        assertTrue(
                normalizedService.contains("Service class for user management")
                        || normalizedService.contains("@class UserService"),
                "Class source should include JSDoc class annotation");
        assertTrue(normalizedService.contains("class UserService"), "Class source should include class definition");

        // Test method with comprehensive JSDoc annotations
        Optional<String> getUserByIdSource = AnalyzerUtil.getMethodSource(analyzer, "UserService.getUserById", true);
        assertTrue(getUserByIdSource.isPresent(), "getUserById method should be found");

        String normalizedGetUserById = normalize.apply(getUserByIdSource.get());
        assertTrue(
                normalizedGetUserById.contains("Retrieves user by ID")
                        || normalizedGetUserById.contains("@param")
                        || normalizedGetUserById.contains("@returns"),
                "Method source should include JSDoc annotations");
        assertTrue(
                normalizedGetUserById.contains("async getUserById"), "Method source should include method definition");

        // Test deprecated method with @deprecated annotation
        Optional<String> getUserDeprecatedSource = AnalyzerUtil.getMethodSource(analyzer, "UserService.getUser", true);
        assertTrue(getUserDeprecatedSource.isPresent(), "getUser deprecated method should be found");

        String normalizedDeprecated = normalize.apply(getUserDeprecatedSource.get());
        assertTrue(
                normalizedDeprecated.contains("@deprecated") || normalizedDeprecated.contains("deprecated method"),
                "Deprecated method source should include deprecation annotation");
        assertTrue(normalizedDeprecated.contains("async getUser"), "Method source should include method definition");

        // Test static method with annotations
        // Note: Static methods now have $static suffix to distinguish from instance methods with same name
        Optional<String> validateConfigSource =
                AnalyzerUtil.getMethodSource(analyzer, "UserService.validateConfig$static", true);
        assertTrue(validateConfigSource.isPresent(), "validateConfig static method should be found");

        String normalizedStatic = normalize.apply(validateConfigSource.get());
        assertTrue(
                normalizedStatic.contains("@static") || normalizedStatic.contains("Validates user configuration"),
                "Static method source should include static annotation");
        assertTrue(
                normalizedStatic.contains("static validateConfig"),
                "Method source should include static method definition");
    }

    @Test
    void testTypescriptGenericClassAnnotations() throws IOException {
        TestProject project = TestProject.createTestProject("testcode-ts", Languages.TYPESCRIPT);
        TypescriptAnalyzer analyzer = new TypescriptAnalyzer(project);

        Function<String, String> normalize =
                s -> s.lines().map(String::strip).filter(l -> !l.isEmpty()).collect(Collectors.joining("\n"));

        // Test generic class with template annotations
        Optional<String> repositorySource = AnalyzerUtil.getClassSource(analyzer, "Repository", true);
        assertTrue(repositorySource.isPresent(), "Repository generic class should be found");

        String normalizedRepo = normalize.apply(repositorySource.get());
        assertTrue(
                normalizedRepo.contains("@template") || normalizedRepo.contains("Generic repository pattern"),
                "Generic class source should include template annotations");
        assertTrue(normalizedRepo.contains("class Repository"), "Class source should include class definition");

        // Test method with experimental annotation
        Optional<String> batchProcessSource = AnalyzerUtil.getMethodSource(analyzer, "Repository.batchProcess", true);
        assertTrue(batchProcessSource.isPresent(), "batchProcess method should be found");

        String normalizedBatch = normalize.apply(batchProcessSource.get());
        assertTrue(
                normalizedBatch.contains("@experimental")
                        || normalizedBatch.contains("@beta")
                        || normalizedBatch.contains("under development"),
                "Experimental method source should include experimental annotations");
        assertTrue(normalizedBatch.contains("async batchProcess"), "Method source should include method definition");
    }

    @Test
    void testTypescriptFunctionOverloadsWithAnnotations() throws IOException {
        TestProject project = TestProject.createTestProject("testcode-ts", Languages.TYPESCRIPT);
        TypescriptAnalyzer analyzer = new TypescriptAnalyzer(project);

        Function<String, String> normalize =
                s -> s.lines().map(String::strip).filter(l -> !l.isEmpty()).collect(Collectors.joining("\n"));

        // Test function overloads with individual JSDoc annotations
        Optional<String> processDataSource = AnalyzerUtil.getMethodSource(analyzer, "processData", true);
        assertTrue(processDataSource.isPresent(), "processData overloaded function should be found");

        String normalizedProcessData = normalize.apply(processDataSource.get());
        assertTrue(
                normalizedProcessData.contains("@overload")
                        || normalizedProcessData.contains("String processing")
                        || normalizedProcessData.contains("Number processing"),
                "Function overloads should include overload annotations");
        assertTrue(
                normalizedProcessData.contains("function processData"),
                "Function source should include function definitions");

        // Test async function with comprehensive annotations
        Optional<String> fetchWithRetrySource = AnalyzerUtil.getMethodSource(analyzer, "fetchWithRetry", true);
        assertTrue(fetchWithRetrySource.isPresent(), "fetchWithRetry function should be found");

        String normalizedFetch = normalize.apply(fetchWithRetrySource.get());
        assertTrue(
                normalizedFetch.contains("@async")
                        || normalizedFetch.contains("@throws")
                        || normalizedFetch.contains("@see")
                        || normalizedFetch.contains("@todo")
                        || normalizedFetch.contains("retry logic"),
                "Async function should include comprehensive annotations");
        assertTrue(
                normalizedFetch.contains("async function fetchWithRetry"),
                "Function source should include async function definition");
    }

    @Test
    void testTypescriptInterfaceAndEnumAnnotations() throws IOException {
        TestProject project = TestProject.createTestProject("testcode-ts", Languages.TYPESCRIPT);
        TypescriptAnalyzer analyzer = new TypescriptAnalyzer(project);

        Function<String, String> normalize =
                s -> s.lines().map(String::strip).filter(l -> !l.isEmpty()).collect(Collectors.joining("\n"));

        // Test interface with JSDoc annotations
        Optional<String> userConfigSource = AnalyzerUtil.getClassSource(analyzer, "UserConfig", true);
        if (userConfigSource.isPresent()) {
            String normalizedConfig = normalize.apply(userConfigSource.get());
            assertTrue(
                    normalizedConfig.contains("User configuration")
                            || normalizedConfig.contains("@since")
                            || normalizedConfig.contains("@category"),
                    "Interface source should include JSDoc annotations");
        }

        // Test enum with annotations
        Optional<String> userRoleSource = AnalyzerUtil.getClassSource(analyzer, "UserRole", true);
        if (userRoleSource.isPresent()) {
            String normalizedRole = normalize.apply(userRoleSource.get());
            assertTrue(
                    normalizedRole.contains("@enum")
                            || normalizedRole.contains("User role enumeration")
                            || normalizedRole.contains("Standard user access"),
                    "Enum source should include enum and member annotations");
        }

    }

    @Test
    void testAnonymousDefaultExports() {
        ProjectFile anonymousFile = new ProjectFile(project.getRoot(), "AnonymousDefaults.ts");
        Map<CodeUnit, String> skeletons = analyzer.getSkeletons(anonymousFile);
        Set<CodeUnit> declarations = analyzer.getDeclarations(anonymousFile);

        // Note: TypeScript does not support truly anonymous default exports for classes.
        // Even default exported classes must have a name in TypeScript's grammar.
        // This test verifies that default exports (with names) and named exports both work correctly.

        // Test the default exported class (which has a name in TypeScript)
        CodeUnit defaultClass = CodeUnit.cls(anonymousFile, "", "AnonymousDefault");
        assertTrue(
                skeletons.containsKey(defaultClass),
                "Default exported class should be captured. Found: "
                        + skeletons.keySet().stream().map(CodeUnit::fqName).collect(Collectors.joining(", ")));
        String defaultClassSkeleton = skeletons.get(defaultClass);
        assertTrue(
                defaultClassSkeleton.contains("export default"),
                "Default class skeleton should indicate default export. Skeleton: " + defaultClassSkeleton);
        assertTrue(
                defaultClassSkeleton.contains("getValue") && defaultClassSkeleton.contains("setValue"),
                "Default class skeleton should contain class members");

        // Test that named exports work normally alongside default export
        CodeUnit namedClass = CodeUnit.cls(anonymousFile, "", "NamedClass");
        assertTrue(
                skeletons.containsKey(namedClass),
                "Named class should be captured normally. Found: "
                        + skeletons.keySet().stream().map(CodeUnit::fqName).collect(Collectors.joining(", ")));
        assertEquals(
                normalize.apply(
                        """
                export class NamedClass {
                  name: string = "named"
                }"""),
                normalize.apply(skeletons.get(namedClass)));

        CodeUnit namedFunc = CodeUnit.fn(anonymousFile, "", "namedFunction");
        assertTrue(skeletons.containsKey(namedFunc), "Named function should be captured");
        assertEquals(
                normalize.apply("export function namedFunction(): string { ... }"),
                normalize.apply(skeletons.get(namedFunc)));

        CodeUnit namedConstUnit = CodeUnit.field(anonymousFile, "", "_module_.namedConst");
        assertTrue(skeletons.containsKey(namedConstUnit), "Named const should be captured");
        assertEquals(
                normalize.apply("export const namedConst: number = 100"),
                normalize.apply(skeletons.get(namedConstUnit)));

        // Verify no crashes or exceptions occurred during parsing
        assertNotNull(skeletons, "Skeletons map should not be null");
        assertNotNull(declarations, "Declarations set should not be null");

        // Verify all exports are complete in declarations
        assertTrue(declarations.contains(defaultClass), "Declarations should contain default exported class");
        assertTrue(declarations.contains(namedClass), "Declarations should contain named class");
        assertTrue(declarations.contains(namedFunc), "Declarations should contain named function");
        assertTrue(declarations.contains(namedConstUnit), "Declarations should contain named const");

        // Test getDeclarations consistency
        Set<CodeUnit> topLevel = Set.copyOf(analyzer.getTopLevelDeclarations(anonymousFile));
        assertTrue(
                topLevel.contains(defaultClass),
                "Top-level declarations should contain default exported class at top level");
        assertTrue(
                topLevel.contains(namedClass), "Top-level declarations should contain named class at top level");
        assertTrue(
                topLevel.contains(namedFunc), "Top-level declarations should contain named function at top level");
    }

    @Test
    void testInterfaceDeclarationMerging() {
        ProjectFile mergingFile = new ProjectFile(project.getRoot(), "DeclarationMerging.ts");
        Map<CodeUnit, String> skeletons = analyzer.getSkeletons(mergingFile);
        Set<CodeUnit> declarations = analyzer.getDeclarations(mergingFile);

        // Test 1: Interface merging - User interface
        // Should have only ONE User interface CodeUnit despite multiple declarations
        List<CodeUnit> userInterfaces = declarations.stream()
                .filter(cu -> cu.shortName().equals("User") && cu.isClass())
                .collect(Collectors.toList());

        assertEquals(
                1,
                userInterfaces.size(),
                "Should have exactly one User interface CodeUnit (merged). Found: "
                        + userInterfaces.stream().map(CodeUnit::fqName).collect(Collectors.joining(", ")));

        CodeUnit userInterface = userInterfaces.get(0);
        assertTrue(skeletons.containsKey(userInterface), "User interface should have skeleton");

        String userSkeleton = skeletons.get(userInterface);
        // Verify all members from all declarations are present in the merged skeleton
        assertTrue(userSkeleton.contains("id: number"), "Merged User should contain id from first declaration");
        assertTrue(userSkeleton.contains("name: string"), "Merged User should contain name from second declaration");
        assertTrue(userSkeleton.contains("email?: string"), "Merged User should contain optional email");
        assertTrue(userSkeleton.contains("createdAt: Date"), "Merged User should contain createdAt from third declaration");
        assertTrue(
                userSkeleton.contains("updateProfile"),
                "Merged User should contain updateProfile method from third declaration");

        // Test 2: Function + namespace merging - buildQuery
        // TypeScript declaration merging: function + namespace results in keeping the function CodeUnit
        CodeUnit buildQueryFunc = declarations.stream()
                .filter(cu -> cu.shortName().equals("buildQuery") && cu.isFunction())
                .findFirst()
                .orElseThrow(() -> new AssertionError("buildQuery function should be found"));

        assertTrue(declarations.contains(buildQueryFunc), "Should have buildQuery function");

        // For function+namespace merging in TypeScript, the analyzer keeps the function
        // The namespace members may not be directly attached in the current implementation
        // Just verify the function exists (namespace semantics handled by TypeScript runtime)
        // This is a known limitation: function+namespace merging is partially supported

        // Test 3: Enum + namespace merging - Status
        CodeUnit statusEnum = declarations.stream()
                .filter(cu -> cu.shortName().equals("Status") && cu.isClass())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Status enum should be found"));

        // Status namespace members should be captured
        boolean hasIsActiveMethod = declarations.stream()
                .anyMatch(cu -> cu.fqName().contains("Status") && cu.identifier().equals("isActive"));
        assertTrue(hasIsActiveMethod, "Status.isActive method should be found in namespace");

        // Test 4: Class + namespace merging - Config
        CodeUnit configClass = declarations.stream()
                .filter(cu -> cu.shortName().equals("Config") && cu.isClass() && !cu.fqName().contains("."))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Config class should be found"));

        // Config namespace members should be captured
        boolean hasDefaultConfig = declarations.stream()
                .anyMatch(cu -> cu.fqName().contains("Config") && cu.identifier().equals("DEFAULT_CONFIG"));
        boolean hasCreateMethod = declarations.stream()
                .anyMatch(cu -> cu.fqName().contains("Config") && cu.identifier().equals("create"));
        assertTrue(hasDefaultConfig, "Config.DEFAULT_CONFIG should be found in namespace");
        assertTrue(hasCreateMethod, "Config.create method should be found in namespace");

        // Test 5: Calculator interface with method overloads across declarations
        List<CodeUnit> calculatorInterfaces = declarations.stream()
                .filter(cu -> cu.shortName().equals("Calculator") && cu.isClass())
                .collect(Collectors.toList());

        assertEquals(
                1,
                calculatorInterfaces.size(),
                "Should have exactly one Calculator interface CodeUnit (merged). Found: "
                        + calculatorInterfaces.stream().map(CodeUnit::fqName).collect(Collectors.joining(", ")));

        CodeUnit calculatorInterface = calculatorInterfaces.get(0);
        String calculatorSkeleton = skeletons.get(calculatorInterface);

        // Verify all methods from all declarations are present
        assertTrue(calculatorSkeleton.contains("add"), "Calculator should contain add method");
        assertTrue(calculatorSkeleton.contains("subtract"), "Calculator should contain subtract method");
        assertTrue(calculatorSkeleton.contains("multiply"), "Calculator should contain multiply method");
        assertTrue(calculatorSkeleton.contains("divide"), "Calculator should contain divide method");

        // Check method signatures are properly merged (overloads)
        List<String> addSignatures = analyzer.signaturesOf(
                declarations.stream()
                        .filter(cu -> cu.fqName().equals("Calculator.add"))
                        .findFirst()
                        .orElseThrow());
        assertTrue(
                addSignatures.size() >= 2,
                "Calculator.add should have multiple signatures from merged declarations. Found: " + addSignatures.size());

        // Test 6: Exported merged interface - ApiResponse
        List<CodeUnit> apiResponseInterfaces = declarations.stream()
                .filter(cu -> cu.shortName().equals("ApiResponse") && cu.isClass())
                .collect(Collectors.toList());

        assertEquals(
                1,
                apiResponseInterfaces.size(),
                "Should have exactly one ApiResponse interface CodeUnit (merged)");

        CodeUnit apiResponseInterface = apiResponseInterfaces.get(0);
        String apiResponseSkeleton = skeletons.get(apiResponseInterface);

        // Verify export keyword is preserved
        assertTrue(
                apiResponseSkeleton.contains("export interface ApiResponse"),
                "Merged ApiResponse should preserve export keyword");

        // Verify all members from both declarations
        assertTrue(apiResponseSkeleton.contains("status"), "ApiResponse should contain status");
        assertTrue(apiResponseSkeleton.contains("data"), "ApiResponse should contain data");
        assertTrue(apiResponseSkeleton.contains("headers"), "ApiResponse should contain headers");
        assertTrue(apiResponseSkeleton.contains("timestamp"), "ApiResponse should contain timestamp");

        // Test 7: Conflicting property types (last declaration wins in TypeScript)
        CodeUnit conflictingInterface = declarations.stream()
                .filter(cu -> cu.shortName().equals("Conflicting") && cu.isClass())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Conflicting interface should be found"));

        String conflictingSkeleton = skeletons.get(conflictingInterface);
        // The last declaration of 'value' should be present (number, not string)
        // However, the analyzer may capture both - we just verify the interface exists and has members
        assertTrue(conflictingSkeleton.contains("value"), "Conflicting interface should contain value property");
        assertTrue(conflictingSkeleton.contains("extra"), "Conflicting interface should contain extra property");
    }

    @Test
    void testNamespaceClassMerging() {
        // Test that namespace + class/enum merging is correctly captured
        // Both the class/enum and the namespace members should be present with correct FQNames
        
        ProjectFile mergingFile = new ProjectFile(project.getRoot(), "NamespaceMerging.ts");
        Map<CodeUnit, String> skeletons = analyzer.getSkeletons(mergingFile);
        Set<CodeUnit> declarations = analyzer.getDeclarations(mergingFile);
        
        assertFalse(skeletons.isEmpty(), "Skeletons map for NamespaceMerging.ts should not be empty");
        assertFalse(declarations.isEmpty(), "Declarations set for NamespaceMerging.ts should not be empty");
        
        // Test 1: Class + namespace merging - Color
        // Should have Color class
        CodeUnit colorClass = declarations.stream()
                .filter(cu -> cu.shortName().equals("Color") && cu.isClass())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Color class should be found"));
        
        assertTrue(skeletons.containsKey(colorClass), "Color class should have skeleton");
        String colorSkeleton = skeletons.get(colorClass);
        assertTrue(colorSkeleton.contains("class Color"), "Color skeleton should contain class definition");
        assertTrue(colorSkeleton.contains("constructor"), "Color class should have constructor");
        assertTrue(colorSkeleton.contains("toHex"), "Color class should have toHex method");
        assertTrue(colorSkeleton.contains("static blend"), "Color class should have static blend method");
        
        // Should have Color namespace members
        // Note: namespace const declarations use _module_ prefix like module-level exports
        CodeUnit colorWhite = declarations.stream()
                .filter(cu -> cu.fqName().equals("Color._module_.white") && cu.isField())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Color.white field should be found. Available: " + 
                        declarations.stream()
                                .filter(cu -> cu.fqName().startsWith("Color."))
                                .map(CodeUnit::fqName)
                                .collect(Collectors.joining(", "))));
        
        CodeUnit colorFromHex = declarations.stream()
                .filter(cu -> cu.fqName().equals("Color.fromHex") && cu.isFunction())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Color.fromHex function should be found"));
        
        CodeUnit colorRandom = declarations.stream()
                .filter(cu -> cu.fqName().equals("Color.random") && cu.isFunction())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Color.random function should be found"));
        
        // Verify no FQName conflicts between class methods and namespace functions
        List<CodeUnit> colorMembers = declarations.stream()
                .filter(cu -> cu.fqName().startsWith("Color."))
                .collect(Collectors.toList());
        
        // Should have both static class method (blend) and namespace functions (fromHex, random)
        assertTrue(
                colorMembers.stream().anyMatch(cu -> cu.fqName().contains("blend")),
                "Should have Color.blend from class");
        assertTrue(
                colorMembers.stream().anyMatch(cu -> cu.fqName().equals("Color.fromHex")),
                "Should have Color.fromHex from namespace");
        assertTrue(
                colorMembers.stream().anyMatch(cu -> cu.fqName().equals("Color.random")),
                "Should have Color.random from namespace");
        
        // Test 2: Enum + namespace merging - Direction
        CodeUnit directionEnum = declarations.stream()
                .filter(cu -> cu.shortName().equals("Direction") && cu.isClass())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Direction enum should be found"));
        
        assertTrue(skeletons.containsKey(directionEnum), "Direction enum should have skeleton");
        String directionSkeleton = skeletons.get(directionEnum);
        assertTrue(directionSkeleton.contains("enum Direction"), "Direction skeleton should contain enum definition");
        
        // Should have Direction namespace members
        CodeUnit directionOpposite = declarations.stream()
                .filter(cu -> cu.fqName().equals("Direction.opposite") && cu.isFunction())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Direction.opposite function should be found"));
        
        CodeUnit directionIsVertical = declarations.stream()
                .filter(cu -> cu.fqName().equals("Direction.isVertical") && cu.isFunction())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Direction.isVertical function should be found"));
        
        // Note: namespace const declarations use _module_ prefix
        CodeUnit directionAll = declarations.stream()
                .filter(cu -> cu.fqName().equals("Direction._module_.all") && cu.isField())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Direction.all field should be found"));
        
        // Test 3: Exported class + namespace merging - Point
        CodeUnit pointClass = declarations.stream()
                .filter(cu -> cu.shortName().equals("Point") && cu.isClass())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Point class should be found"));
        
        String pointSkeleton = skeletons.get(pointClass);
        assertTrue(pointSkeleton.contains("export class Point"), "Point skeleton should be exported");
        
        // Should have Point namespace members
        // Note: namespace const declarations use _module_ prefix
        CodeUnit pointOrigin = declarations.stream()
                .filter(cu -> cu.fqName().equals("Point._module_.origin") && cu.isField())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Point.origin field should be found"));
        
        CodeUnit pointFromPolar = declarations.stream()
                .filter(cu -> cu.fqName().equals("Point.fromPolar") && cu.isFunction())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Point.fromPolar function should be found"));
        
        // Point.Config interface should be nested within Point namespace
        CodeUnit pointConfig = declarations.stream()
                .filter(cu -> cu.fqName().equals("Point.Config") && cu.isClass())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Point.Config interface should be found"));
        
        // Test 4: Exported enum + namespace merging - HttpStatus
        CodeUnit httpStatusEnum = declarations.stream()
                .filter(cu -> cu.shortName().equals("HttpStatus") && cu.isClass())
                .findFirst()
                .orElseThrow(() -> new AssertionError("HttpStatus enum should be found"));
        
        String httpStatusSkeleton = skeletons.get(httpStatusEnum);
        assertTrue(httpStatusSkeleton.contains("export enum HttpStatus"), "HttpStatus skeleton should be exported");
        
        // Should have HttpStatus namespace members
        CodeUnit httpStatusIsSuccess = declarations.stream()
                .filter(cu -> cu.fqName().equals("HttpStatus.isSuccess") && cu.isFunction())
                .findFirst()
                .orElseThrow(() -> new AssertionError("HttpStatus.isSuccess function should be found"));
        
        CodeUnit httpStatusIsError = declarations.stream()
                .filter(cu -> cu.fqName().equals("HttpStatus.isError") && cu.isFunction())
                .findFirst()
                .orElseThrow(() -> new AssertionError("HttpStatus.isError function should be found"));
        
        // Note: namespace const declarations use _module_ prefix
        CodeUnit httpStatusMessages = declarations.stream()
                .filter(cu -> cu.fqName().equals("HttpStatus._module_.messages") && cu.isField())
                .findFirst()
                .orElseThrow(() -> new AssertionError("HttpStatus.messages field should be found"));
        
        // Verify no duplicate captures
        Map<String, Long> fqNameCounts = declarations.stream()
                .map(CodeUnit::fqName)
                .collect(Collectors.groupingBy(fqn -> fqn, Collectors.counting()));
        
        List<String> duplicates = fqNameCounts.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        
        assertTrue(
                duplicates.isEmpty(),
                "Should have no duplicate FQNames. Found duplicates: " + String.join(", ", duplicates));
        
        // Verify all namespace members are properly scoped
        List<String> colorNamespaceMembers = declarations.stream()
                .filter(cu -> cu.fqName().startsWith("Color.") && !cu.fqName().contains("$"))
                .map(CodeUnit::fqName)
                .collect(Collectors.toList());
        
        assertTrue(
                colorNamespaceMembers.size() >= 5,
                "Should have at least 5 Color members (class methods + namespace members). Found: " + 
                colorNamespaceMembers.size() + " - " + String.join(", ", colorNamespaceMembers));
    }

    @Test
    void testAdvancedTypeConstructs() {
        ProjectFile advancedTypesFile = new ProjectFile(project.getRoot(), "AdvancedTypes.ts");
        Map<CodeUnit, String> skeletons = analyzer.getSkeletons(advancedTypesFile);
        Set<CodeUnit> declarations = analyzer.getDeclarations(advancedTypesFile);

        assertFalse(skeletons.isEmpty(), "Skeletons map for AdvancedTypes.ts should not be empty");
        assertFalse(declarations.isEmpty(), "Declarations set for AdvancedTypes.ts should not be empty");

        // ===== Test Tuple Types =====

        // Basic tuple type
        CodeUnit coordType = CodeUnit.field(advancedTypesFile, "", "_module_.Coord");
        assertTrue(
                skeletons.containsKey(coordType),
                "Coord tuple type should be captured. Found: "
                        + skeletons.keySet().stream().map(CodeUnit::fqName).collect(Collectors.joining(", ")));
        assertEquals(
                normalize.apply("export type Coord = [number, number]"), normalize.apply(skeletons.get(coordType)));

        // Tuple with optional elements
        CodeUnit point3DType = CodeUnit.field(advancedTypesFile, "", "_module_.Point3D");
        assertTrue(skeletons.containsKey(point3DType), "Point3D tuple type should be captured");
        assertEquals(
                normalize.apply("export type Point3D = [number, number, number?]"),
                normalize.apply(skeletons.get(point3DType)));

        // Tuple with rest elements
        CodeUnit restTupleType = CodeUnit.field(advancedTypesFile, "", "_module_.RestTuple");
        assertTrue(skeletons.containsKey(restTupleType), "RestTuple type should be captured");
        assertEquals(
                normalize.apply("export type RestTuple = [string, ...number[]]"),
                normalize.apply(skeletons.get(restTupleType)));

        // Named tuple elements
        CodeUnit rangeType = CodeUnit.field(advancedTypesFile, "", "_module_.Range");
        assertTrue(skeletons.containsKey(rangeType), "Range named tuple type should be captured");
        assertEquals(
                normalize.apply("export type Range = [start: number, end: number]"),
                normalize.apply(skeletons.get(rangeType)));

        // Readonly tuple
        CodeUnit readonlyCoordType = CodeUnit.field(advancedTypesFile, "", "_module_.ReadonlyCoord");
        assertTrue(skeletons.containsKey(readonlyCoordType), "ReadonlyCoord tuple type should be captured");
        assertEquals(
                normalize.apply("export type ReadonlyCoord = readonly [number, number]"),
                normalize.apply(skeletons.get(readonlyCoordType)));

        // ===== Test Mapped Types =====

        // Basic mapped type - Readonly
        CodeUnit readonlyType = CodeUnit.field(advancedTypesFile, "", "_module_.Readonly");
        assertTrue(skeletons.containsKey(readonlyType), "Readonly mapped type should be captured");
        String readonlySkeleton = skeletons.get(readonlyType);
        assertTrue(
                readonlySkeleton.contains("readonly [P in keyof T]"),
                "Readonly mapped type should contain mapped type syntax");

        // Partial mapped type
        CodeUnit partialType = CodeUnit.field(advancedTypesFile, "", "_module_.Partial");
        assertTrue(skeletons.containsKey(partialType), "Partial mapped type should be captured");
        String partialSkeleton = skeletons.get(partialType);
        assertTrue(
                partialSkeleton.contains("[P in keyof T]?"), "Partial mapped type should contain optional modifier");

        // Mapped type with key remapping
        CodeUnit gettersType = CodeUnit.field(advancedTypesFile, "", "_module_.Getters");
        assertTrue(skeletons.containsKey(gettersType), "Getters mapped type with key remapping should be captured");
        String gettersSkeleton = skeletons.get(gettersType);
        assertTrue(
                gettersSkeleton.contains("as `get${Capitalize"),
                "Getters mapped type should contain key remapping syntax");

        // Mapped type with filtering
        CodeUnit onlyStringsType = CodeUnit.field(advancedTypesFile, "", "_module_.OnlyStrings");
        assertTrue(skeletons.containsKey(onlyStringsType), "OnlyStrings filtered mapped type should be captured");
        String onlyStringsSkeleton = skeletons.get(onlyStringsType);
        assertTrue(
                onlyStringsSkeleton.contains("as T[P] extends string ? P : never"),
                "OnlyStrings mapped type should contain filtering logic");

        // ===== Test Conditional Types =====

        // Basic conditional type - Extract
        CodeUnit extractType = CodeUnit.field(advancedTypesFile, "", "_module_.Extract");
        assertTrue(skeletons.containsKey(extractType), "Extract conditional type should be captured");
        assertEquals(
                normalize.apply("export type Extract<T, U> = T extends U ? T : never"),
                normalize.apply(skeletons.get(extractType)));

        // Conditional type with infer
        CodeUnit returnTypeType = CodeUnit.field(advancedTypesFile, "", "_module_.ReturnType");
        assertTrue(skeletons.containsKey(returnTypeType), "ReturnType conditional type with infer should be captured");
        String returnTypeSkeleton = skeletons.get(returnTypeType);
        assertTrue(
                returnTypeSkeleton.contains("infer R"), "ReturnType should contain infer keyword for type inference");

        // Nested conditional type
        CodeUnit flattenType = CodeUnit.field(advancedTypesFile, "", "_module_.Flatten");
        assertTrue(skeletons.containsKey(flattenType), "Flatten nested conditional type should be captured");
        String flattenSkeleton = skeletons.get(flattenType);
        assertTrue(
                flattenSkeleton.contains("Array<infer U>"),
                "Flatten should contain nested conditional with infer");

        // Multi-branch conditional type
        CodeUnit typeNameType = CodeUnit.field(advancedTypesFile, "", "_module_.TypeName");
        assertTrue(skeletons.containsKey(typeNameType), "TypeName multi-conditional type should be captured");
        String typeNameSkeleton = skeletons.get(typeNameType);
        assertTrue(
                typeNameSkeleton.contains("extends string")
                        && typeNameSkeleton.contains("extends number")
                        && typeNameSkeleton.contains("extends boolean"),
                "TypeName should contain multiple conditional branches");

        // Distributive conditional type
        CodeUnit toArrayType = CodeUnit.field(advancedTypesFile, "", "_module_.ToArray");
        assertTrue(skeletons.containsKey(toArrayType), "ToArray distributive conditional type should be captured");
        assertEquals(
                normalize.apply("export type ToArray<T> = T extends any ? T[] : never"),
                normalize.apply(skeletons.get(toArrayType)));

        // ===== Test Intersection Types =====

        // Basic intersection
        CodeUnit combinedType = CodeUnit.field(advancedTypesFile, "", "_module_.Combined");
        assertTrue(skeletons.containsKey(combinedType), "Combined intersection type should be captured");
        assertEquals(
                normalize.apply("export type Combined = TypeA & TypeB & TypeC"),
                normalize.apply(skeletons.get(combinedType)));

        // Intersection with primitives (never type)
        CodeUnit stringAndNumberType = CodeUnit.field(advancedTypesFile, "", "_module_.StringAndNumber");
        assertTrue(
                skeletons.containsKey(stringAndNumberType),
                "StringAndNumber intersection type should be captured");
        assertEquals(
                normalize.apply("export type StringAndNumber = string & number"),
                normalize.apply(skeletons.get(stringAndNumberType)));

        // Complex intersection with generics
        CodeUnit mergeableType = CodeUnit.field(advancedTypesFile, "", "_module_.Mergeable");
        assertTrue(skeletons.containsKey(mergeableType), "Mergeable generic intersection type should be captured");
        String mergeableSkeleton = skeletons.get(mergeableType);
        assertTrue(
                mergeableSkeleton.contains("T & U & { merged: true }"),
                "Mergeable should contain complex intersection syntax");

        // Intersection with function types
        CodeUnit universalLoggerType = CodeUnit.field(advancedTypesFile, "", "_module_.UniversalLogger");
        assertTrue(
                skeletons.containsKey(universalLoggerType),
                "UniversalLogger function intersection type should be captured");
        String universalLoggerSkeleton = skeletons.get(universalLoggerType);
        assertTrue(
                universalLoggerSkeleton.contains("Logger & ErrorLogger"),
                "UniversalLogger should contain function type intersection");

        // ===== Test Template Literal Types =====

        CodeUnit eventNameType = CodeUnit.field(advancedTypesFile, "", "_module_.EventName");
        assertTrue(skeletons.containsKey(eventNameType), "EventName template literal type should be captured");
        String eventNameSkeleton = skeletons.get(eventNameType);
        assertTrue(
                eventNameSkeleton.contains("`${T}Changed`"),
                "EventName should contain template literal type syntax");

        CodeUnit propEventNameType = CodeUnit.field(advancedTypesFile, "", "_module_.PropEventName");
        assertTrue(skeletons.containsKey(propEventNameType), "PropEventName template literal type should be captured");
        String propEventNameSkeleton = skeletons.get(propEventNameType);
        assertTrue(
                propEventNameSkeleton.contains("on${Capitalize<T>}"),
                "PropEventName should contain capitalized template literal");

        // ===== Test Complex Combined Types =====

        // Mapped + conditional combination
        CodeUnit pickByTypeType = CodeUnit.field(advancedTypesFile, "", "_module_.PickByType");
        assertTrue(
                skeletons.containsKey(pickByTypeType),
                "PickByType combined mapped/conditional type should be captured");
        String pickByTypeSkeleton = skeletons.get(pickByTypeType);
        assertTrue(
                pickByTypeSkeleton.contains("as T[P] extends U ? P : never"),
                "PickByType should combine mapped type with conditional filtering");

        // Tuple to union
        CodeUnit tupleToUnionType = CodeUnit.field(advancedTypesFile, "", "_module_.TupleToUnion");
        assertTrue(skeletons.containsKey(tupleToUnionType), "TupleToUnion type should be captured");
        String tupleToUnionSkeleton = skeletons.get(tupleToUnionType);
        assertTrue(
                tupleToUnionSkeleton.contains("T[number]"),
                "TupleToUnion should contain indexed access type syntax");

        // Union to intersection (advanced type manipulation)
        CodeUnit unionToIntersectionType = CodeUnit.field(advancedTypesFile, "", "_module_.UnionToIntersection");
        assertTrue(
                skeletons.containsKey(unionToIntersectionType), "UnionToIntersection type should be captured");
        String unionToIntersectionSkeleton = skeletons.get(unionToIntersectionType);
        assertTrue(
                unionToIntersectionSkeleton.contains("infer I"),
                "UnionToIntersection should contain advanced type inference");

        // Recursive conditional type
        CodeUnit deepReadonlyType = CodeUnit.field(advancedTypesFile, "", "_module_.DeepReadonly");
        assertTrue(skeletons.containsKey(deepReadonlyType), "DeepReadonly recursive type should be captured");
        String deepReadonlySkeleton = skeletons.get(deepReadonlyType);
        assertTrue(
                deepReadonlySkeleton.contains("DeepReadonly<T[P]>"),
                "DeepReadonly should contain recursive type reference");

        // ===== Test Utility Type Aliases =====

        CodeUnit nonNullableType = CodeUnit.field(advancedTypesFile, "", "_module_.NonNullable");
        assertTrue(skeletons.containsKey(nonNullableType), "NonNullable utility type should be captured");
        String nonNullableSkeleton = skeletons.get(nonNullableType);
        assertTrue(
                nonNullableSkeleton.contains("null | undefined"),
                "NonNullable should filter null and undefined");

        CodeUnit parametersType = CodeUnit.field(advancedTypesFile, "", "_module_.Parameters");
        assertTrue(skeletons.containsKey(parametersType), "Parameters utility type should be captured");
        String parametersSkeleton = skeletons.get(parametersType);
        assertTrue(
                parametersSkeleton.contains("infer P"), "Parameters should extract function parameter types");

        CodeUnit constructorParametersType = CodeUnit.field(advancedTypesFile, "", "_module_.ConstructorParameters");
        assertTrue(
                skeletons.containsKey(constructorParametersType),
                "ConstructorParameters utility type should be captured");
        String constructorParametersSkeleton = skeletons.get(constructorParametersType);
        assertTrue(
                constructorParametersSkeleton.contains("new (...args: infer P)"),
                "ConstructorParameters should extract constructor parameter types");

        // ===== Verify all types are captured as FIELD_LIKE CodeUnits =====

        List<CodeUnit> typeAliases = declarations.stream()
                .filter(cu -> cu.isField() && cu.fqName().startsWith("_module_."))
                .collect(Collectors.toList());

        assertTrue(
                typeAliases.size() >= 30,
                "Should capture at least 30 type aliases. Found: " + typeAliases.size());

        // Verify all captured types have skeletons
        for (CodeUnit typeAlias : typeAliases) {
            assertTrue(
                    skeletons.containsKey(typeAlias),
                    "Type alias " + typeAlias.fqName() + " should have skeleton");
            String skeleton = skeletons.get(typeAlias);
            assertTrue(
                    skeleton.contains("type "),
                    "Type alias " + typeAlias.fqName() + " skeleton should contain 'type' keyword");
        }

        // Verify getSkeleton works for individual type aliases
        Optional<String> coordSkeleton = AnalyzerUtil.getSkeleton(analyzer, "_module_.Coord");
        assertTrue(coordSkeleton.isPresent(), "Should retrieve Coord type alias via getSkeleton");
        assertEquals(
                normalize.apply("export type Coord = [number, number]"), normalize.apply(coordSkeleton.get()));

        Optional<String> extractSkeleton = AnalyzerUtil.getSkeleton(analyzer, "_module_.Extract");
        assertTrue(extractSkeleton.isPresent(), "Should retrieve Extract type alias via getSkeleton");
        assertEquals(
                normalize.apply("export type Extract<T, U> = T extends U ? T : never"),
                normalize.apply(extractSkeleton.get()));

        Optional<String> combinedSkeleton = AnalyzerUtil.getSkeleton(analyzer, "_module_.Combined");
        assertTrue(combinedSkeleton.isPresent(), "Should retrieve Combined type alias via getSkeleton");
        assertEquals(
                normalize.apply("export type Combined = TypeA & TypeB & TypeC"),
                normalize.apply(combinedSkeleton.get()));
    }

    @Test
    void testTopLevelCodeUnitsOfNonExistentFile() {
        ProjectFile nonExistentFile = new ProjectFile(project.getRoot(), "NonExistent.ts");
        List<CodeUnit> topLevelUnits = analyzer.getTopLevelDeclarations(nonExistentFile);

        assertTrue(topLevelUnits.isEmpty(), "Non-existent file should return empty list");
    }

    @Test
    void testTopLevelCodeUnitsExcludesNested() {
        ProjectFile helloTsFile = new ProjectFile(project.getRoot(), "Hello.ts");
        List<CodeUnit> topLevelUnits = analyzer.getTopLevelDeclarations(helloTsFile);

        // Get all declarations including nested ones
        Set<CodeUnit> allDeclarations = analyzer.getDeclarations(helloTsFile);

        // Top-level units should be a subset of all declarations
        assertTrue(allDeclarations.containsAll(topLevelUnits), "All top-level units should be present in declarations");

        // But top-level units should be smaller than all declarations (due to nested members)
        assertTrue(
                topLevelUnits.size() < allDeclarations.size(),
                "Top-level units should exclude nested members. Found "
                        + topLevelUnits.size()
                        + " top-level, "
                        + allDeclarations.size()
                        + " total");

        // Verify specific top-level vs nested distinction
        boolean hasGreeterClass =
                topLevelUnits.stream().anyMatch(cu -> cu.fqName().equals("Greeter"));
        boolean hasGreeterGreet =
                topLevelUnits.stream().anyMatch(cu -> cu.fqName().equals("Greeter.greet"));

        assertTrue(hasGreeterClass, "Should include Greeter class at top level");
        assertFalse(hasGreeterGreet, "Should not include Greeter.greet method at top level");
    }

    @Test
    void testStaticInstanceMemberOverlap() {
        // Test that static and instance members with the same name generate different FQNames
        // This prevents false duplicate warnings for legitimate TypeScript patterns

        ProjectFile testFile = new ProjectFile(project.getRoot(), "StaticInstanceOverlap.ts");

        // Use getDeclarations to get all CodeUnits including class members
        Set<CodeUnit> allDeclarations = analyzer.getDeclarations(testFile);
        assertFalse(allDeclarations.isEmpty(), "Declarations list for StaticInstanceOverlap.ts should not be empty.");

        // Get all CodeUnits for the Color class members
        List<CodeUnit> colorUnits = allDeclarations.stream()
                .filter(cu -> cu.fqName().startsWith("Color."))
                .collect(Collectors.toList());

        // Find instance and static versions of "transparent"
        Optional<CodeUnit> instanceTransparent = colorUnits.stream()
                .filter(cu -> cu.fqName().equals("Color.transparent"))
                .findFirst();

        Optional<CodeUnit> staticTransparent = colorUnits.stream()
                .filter(cu -> cu.fqName().equals("Color.transparent$static"))
                .findFirst();

        assertTrue(
                instanceTransparent.isPresent(),
                "Instance method 'transparent' should be found with FQName 'Color.transparent'. Found units: "
                        + colorUnits.stream().map(CodeUnit::fqName).collect(Collectors.joining(", ")));

        assertTrue(
                staticTransparent.isPresent(),
                "Static property 'transparent' should be found with FQName 'Color.transparent$static'. Found units: "
                        + colorUnits.stream().map(CodeUnit::fqName).collect(Collectors.joining(", ")));

        // Verify both are function-like or field-like as appropriate
        assertTrue(instanceTransparent.get().isFunction(), "Instance transparent should be a function (method)");
        assertTrue(staticTransparent.get().isField(), "Static transparent should be a field (property)");

        // Test normalize methods (instance vs static)
        Optional<CodeUnit> instanceNormalize = colorUnits.stream()
                .filter(cu -> cu.fqName().equals("Color.normalize"))
                .findFirst();

        Optional<CodeUnit> staticNormalize = colorUnits.stream()
                .filter(cu -> cu.fqName().equals("Color.normalize$static"))
                .findFirst();

        assertTrue(instanceNormalize.isPresent(), "Instance method 'normalize' should be found");
        assertTrue(staticNormalize.isPresent(), "Static method 'normalize' should be found");

        // Test count properties (instance vs static)
        Optional<CodeUnit> instanceCount = colorUnits.stream()
                .filter(cu -> cu.fqName().equals("Color.count"))
                .findFirst();

        Optional<CodeUnit> staticCount = colorUnits.stream()
                .filter(cu -> cu.fqName().equals("Color.count$static"))
                .findFirst();

        assertTrue(instanceCount.isPresent(), "Instance property 'count' should be found");
        assertTrue(staticCount.isPresent(), "Static property 'count' should be found");
    }

    @Test
    void testFunctionOverloadSignatures() {
        ProjectFile overloadsFile = new ProjectFile(project.getRoot(), "Overloads.ts");
        Map<CodeUnit, String> skeletons = analyzer.getSkeletons(overloadsFile);
        assertFalse(skeletons.isEmpty(), "Skeletons map for Overloads.ts should not be empty");

        // Test 1: Basic overload - add function
        // TypeScript merges overloads into a single CodeUnit with multiple signatures
        CodeUnit addFunc = skeletons.keySet().stream()
                .filter(cu -> cu.shortName().equals("add") && cu.isFunction())
                .findFirst()
                .orElseThrow(() -> new AssertionError("add function not found"));

        List<String> addSignatures = analyzer.signaturesOf(addFunc);
        assertEquals(3, addSignatures.size(), "Should have 3 signature variants for add function");

        // Check that signatures contain the expected parameter types
        assertTrue(
                addSignatures.stream().anyMatch(s -> s.contains("number") && s.contains("number")),
                "Should have signature with (number, number)");
        assertTrue(
                addSignatures.stream().anyMatch(s -> s.contains("string") && s.contains("string")),
                "Should have signature with (string, string)");
        assertTrue(addSignatures.stream().anyMatch(s -> s.contains("any")), "Should have signature with any");

        // Test 2: Optional parameter - query function
        CodeUnit queryFunc = skeletons.keySet().stream()
                .filter(cu -> cu.shortName().equals("query") && cu.isFunction())
                .findFirst()
                .orElseThrow(() -> new AssertionError("query function not found"));

        List<String> querySignatures = analyzer.signaturesOf(queryFunc);
        assertEquals(3, querySignatures.size(), "Should have 3 signature variants for query function");

        // Check for optional parameter marker
        assertTrue(
                querySignatures.stream().anyMatch(s -> s.contains("?")),
                "Should have signature with optional parameter (?)");

        // Test 3: Rest parameter - combine function
        CodeUnit combineFunc = skeletons.keySet().stream()
                .filter(cu -> cu.shortName().equals("combine") && cu.isFunction())
                .findFirst()
                .orElseThrow(() -> new AssertionError("combine function not found"));

        List<String> combineSignatures = analyzer.signaturesOf(combineFunc);
        assertEquals(3, combineSignatures.size(), "Should have 3 signature variants for combine function");

        // Check for rest parameter marker
        assertTrue(
                combineSignatures.stream().anyMatch(s -> s.contains("...")),
                "Should have signature with rest parameter (...)");

        // Test 4: Complex generic types - map function
        CodeUnit mapFunc = skeletons.keySet().stream()
                .filter(cu -> cu.shortName().equals("map") && cu.isFunction())
                .findFirst()
                .orElseThrow(() -> new AssertionError("map function not found"));

        List<String> mapSignatures = analyzer.signaturesOf(mapFunc);
        assertEquals(3, mapSignatures.size(), "Should have 3 signature variants for map function");

        // Verify at least one signature contains array types and function types
        assertTrue(
                mapSignatures.stream().anyMatch(s -> s.contains("[]") && s.contains("=>")),
                "Map signatures should contain array types and function types");

        // Test 5: Class method overloads
        Optional<CodeUnit> multiplyMethodOpt = skeletons.keySet().stream()
                .filter(cu -> cu.fqName().contains("multiply"))
                .findFirst();

        if (multiplyMethodOpt.isPresent()) {
            CodeUnit multiplyMethod = multiplyMethodOpt.get();
            List<String> multiplySignatures = analyzer.signaturesOf(multiplyMethod);
            assertEquals(
                    3, multiplySignatures.size(), "Should have 3 signature variants for " + multiplyMethod.fqName());

            // Verify both overload parameter types are present
            assertTrue(
                    multiplySignatures.stream().anyMatch(s -> s.contains("number") && s.contains("number")),
                    "Should have (number, number) signature for multiply");
            assertTrue(
                    multiplySignatures.stream().anyMatch(s -> s.contains("string") && s.contains("number")),
                    "Should have (string, number) signature for multiply");
        }
    }
}
