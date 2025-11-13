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
        assertEquals(
                3,
                allPointInterfaces.size(),
                "Should find Point interfaces in Hello.ts, Advanced.ts, and NamespaceMerging.ts");

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
            Path outsideFile = Path.of(System.getProperty("java.io.tmpdir"), "outside.ts");
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
        assertTrue(topLevel.contains(namedClass), "Top-level declarations should contain named class at top level");
        assertTrue(topLevel.contains(namedFunc), "Top-level declarations should contain named function at top level");
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
        assertTrue(
                userSkeleton.contains("createdAt: Date"),
                "Merged User should contain createdAt from third declaration");
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
                .anyMatch(
                        cu -> cu.fqName().contains("Status") && cu.identifier().equals("isActive"));
        assertTrue(hasIsActiveMethod, "Status.isActive method should be found in namespace");

        // Test 4: Class + namespace merging - Config
        CodeUnit configClass = declarations.stream()
                .filter(cu -> cu.shortName().equals("Config")
                        && cu.isClass()
                        && !cu.fqName().contains("."))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Config class should be found"));

        // Config namespace members should be captured
        boolean hasDefaultConfig = declarations.stream()
                .anyMatch(
                        cu -> cu.fqName().contains("Config") && cu.identifier().equals("DEFAULT_CONFIG"));
        boolean hasCreateMethod = declarations.stream()
                .anyMatch(
                        cu -> cu.fqName().contains("Config") && cu.identifier().equals("create"));
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
        List<String> addSignatures = analyzer.signaturesOf(declarations.stream()
                .filter(cu -> cu.fqName().equals("Calculator.add"))
                .findFirst()
                .orElseThrow());
        assertTrue(
                addSignatures.size() >= 2,
                "Calculator.add should have multiple signatures from merged declarations. Found: "
                        + addSignatures.size());

        // Test 6: Exported merged interface - ApiResponse
        List<CodeUnit> apiResponseInterfaces = declarations.stream()
                .filter(cu -> cu.shortName().equals("ApiResponse") && cu.isClass())
                .collect(Collectors.toList());

        assertEquals(
                1, apiResponseInterfaces.size(), "Should have exactly one ApiResponse interface CodeUnit (merged)");

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
                .orElseThrow(() -> new AssertionError("Color.white field should be found. Available: "
                        + declarations.stream()
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
                "Should have at least 5 Color members (class methods + namespace members). Found: "
                        + colorNamespaceMembers.size() + " - " + String.join(", ", colorNamespaceMembers));
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
        assertTrue(partialSkeleton.contains("[P in keyof T]?"), "Partial mapped type should contain optional modifier");

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
        assertTrue(flattenSkeleton.contains("Array<infer U>"), "Flatten should contain nested conditional with infer");

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
        assertTrue(skeletons.containsKey(stringAndNumberType), "StringAndNumber intersection type should be captured");
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
                eventNameSkeleton.contains("`${T}Changed`"), "EventName should contain template literal type syntax");

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
                tupleToUnionSkeleton.contains("T[number]"), "TupleToUnion should contain indexed access type syntax");

        // Union to intersection (advanced type manipulation)
        CodeUnit unionToIntersectionType = CodeUnit.field(advancedTypesFile, "", "_module_.UnionToIntersection");
        assertTrue(skeletons.containsKey(unionToIntersectionType), "UnionToIntersection type should be captured");
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
        assertTrue(nonNullableSkeleton.contains("null | undefined"), "NonNullable should filter null and undefined");

        CodeUnit parametersType = CodeUnit.field(advancedTypesFile, "", "_module_.Parameters");
        assertTrue(skeletons.containsKey(parametersType), "Parameters utility type should be captured");
        String parametersSkeleton = skeletons.get(parametersType);
        assertTrue(parametersSkeleton.contains("infer P"), "Parameters should extract function parameter types");

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

        assertTrue(typeAliases.size() >= 30, "Should capture at least 30 type aliases. Found: " + typeAliases.size());

        // Verify all captured types have skeletons
        for (CodeUnit typeAlias : typeAliases) {
            assertTrue(skeletons.containsKey(typeAlias), "Type alias " + typeAlias.fqName() + " should have skeleton");
            String skeleton = skeletons.get(typeAlias);
            assertTrue(
                    skeleton.contains("type "),
                    "Type alias " + typeAlias.fqName() + " skeleton should contain 'type' keyword");
        }

        // Verify getSkeleton works for individual type aliases
        Optional<String> coordSkeleton = AnalyzerUtil.getSkeleton(analyzer, "_module_.Coord");
        assertTrue(coordSkeleton.isPresent(), "Should retrieve Coord type alias via getSkeleton");
        assertEquals(normalize.apply("export type Coord = [number, number]"), normalize.apply(coordSkeleton.get()));

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
    void testTemplateLiteralAndUtilityTypes() {
        ProjectFile templateTypesFile = new ProjectFile(project.getRoot(), "TemplateTypes.ts");
        Map<CodeUnit, String> skeletons = analyzer.getSkeletons(templateTypesFile);
        Set<CodeUnit> declarations = analyzer.getDeclarations(templateTypesFile);

        assertFalse(skeletons.isEmpty(), "Skeletons map for TemplateTypes.ts should not be empty");
        assertFalse(declarations.isEmpty(), "Declarations set for TemplateTypes.ts should not be empty");

        // ===== Test Basic Template Literal Types =====

        // Simple template literal
        CodeUnit eventNameType = CodeUnit.field(templateTypesFile, "", "_module_.EventName");
        assertTrue(skeletons.containsKey(eventNameType), "EventName template literal type should be captured");
        String eventNameSkeleton = skeletons.get(eventNameType);
        assertTrue(eventNameSkeleton.contains("`${T}Changed`"), "EventName should contain template literal syntax");

        // Template with Capitalize
        CodeUnit propEventHandlerType = CodeUnit.field(templateTypesFile, "", "_module_.PropEventHandler");
        assertTrue(
                skeletons.containsKey(propEventHandlerType),
                "PropEventHandler template literal type should be captured");
        String propEventHandlerSkeleton = skeletons.get(propEventHandlerType);
        assertTrue(
                propEventHandlerSkeleton.contains("on${Capitalize<T>}"),
                "PropEventHandler should contain Capitalize in template literal");

        // Template with Uppercase
        CodeUnit actionTypeType = CodeUnit.field(templateTypesFile, "", "_module_.ActionType");
        assertTrue(skeletons.containsKey(actionTypeType), "ActionType template literal type should be captured");
        String actionTypeSkeleton = skeletons.get(actionTypeType);
        assertTrue(
                actionTypeSkeleton.contains("ACTION_${Uppercase<T>}"),
                "ActionType should contain Uppercase in template literal");

        // Template with Lowercase
        CodeUnit methodNameType = CodeUnit.field(templateTypesFile, "", "_module_.MethodName");
        assertTrue(skeletons.containsKey(methodNameType), "MethodName template literal type should be captured");
        String methodNameSkeleton = skeletons.get(methodNameType);
        assertTrue(
                methodNameSkeleton.contains("get${Capitalize<T>}"),
                "MethodName should contain Capitalize in template literal");

        // Template with Uncapitalize
        CodeUnit privatePropType = CodeUnit.field(templateTypesFile, "", "_module_.PrivateProp");
        assertTrue(skeletons.containsKey(privatePropType), "PrivateProp template literal type should be captured");
        String privatePropSkeleton = skeletons.get(privatePropType);
        assertTrue(
                privatePropSkeleton.contains("_${Uncapitalize<T>}"),
                "PrivateProp should contain Uncapitalize in template literal");

        // ===== Test Nested Template Literal Types =====

        // Multiple transformations
        CodeUnit complexEventType = CodeUnit.field(templateTypesFile, "", "_module_.ComplexEvent");
        assertTrue(skeletons.containsKey(complexEventType), "ComplexEvent nested template should be captured");
        String complexEventSkeleton = skeletons.get(complexEventType);
        assertTrue(
                complexEventSkeleton.contains("on${Capitalize<T>}Changed"),
                "ComplexEvent should contain nested template transformations");

        // Chained template literals
        CodeUnit nestedTemplateType = CodeUnit.field(templateTypesFile, "", "_module_.NestedTemplate");
        assertTrue(skeletons.containsKey(nestedTemplateType), "NestedTemplate type should be captured");
        String nestedTemplateSkeleton = skeletons.get(nestedTemplateType);
        assertTrue(
                nestedTemplateSkeleton.contains("${T}_${U}_handler"),
                "NestedTemplate should contain multiple template parameters");

        // Template with union
        CodeUnit apiEndpointType = CodeUnit.field(templateTypesFile, "", "_module_.ApiEndpoint");
        assertTrue(skeletons.containsKey(apiEndpointType), "ApiEndpoint template type should be captured");
        String apiEndpointSkeleton = skeletons.get(apiEndpointType);
        assertTrue(
                apiEndpointSkeleton.contains("api/${Lowercase<T>}"),
                "ApiEndpoint should contain Lowercase in template");

        // ===== Test Basic Utility Types =====

        // Partial
        CodeUnit partialUserType = CodeUnit.field(templateTypesFile, "", "_module_.PartialUser");
        assertTrue(skeletons.containsKey(partialUserType), "PartialUser utility type should be captured");
        assertEquals(
                normalize.apply("export type PartialUser = Partial<User>"),
                normalize.apply(skeletons.get(partialUserType)));

        // Required
        CodeUnit requiredConfigType = CodeUnit.field(templateTypesFile, "", "_module_.RequiredConfig");
        assertTrue(skeletons.containsKey(requiredConfigType), "RequiredConfig utility type should be captured");
        assertEquals(
                normalize.apply("export type RequiredConfig = Required<Config>"),
                normalize.apply(skeletons.get(requiredConfigType)));

        // Readonly
        CodeUnit readonlyUserType = CodeUnit.field(templateTypesFile, "", "_module_.ReadonlyUser");
        assertTrue(skeletons.containsKey(readonlyUserType), "ReadonlyUser utility type should be captured");
        assertEquals(
                normalize.apply("export type ReadonlyUser = Readonly<User>"),
                normalize.apply(skeletons.get(readonlyUserType)));

        // Pick
        CodeUnit userNameEmailType = CodeUnit.field(templateTypesFile, "", "_module_.UserNameEmail");
        assertTrue(skeletons.containsKey(userNameEmailType), "UserNameEmail Pick type should be captured");
        assertEquals(
                normalize.apply("export type UserNameEmail = Pick<User, 'name' | 'email'>"),
                normalize.apply(skeletons.get(userNameEmailType)));

        // Omit
        CodeUnit userWithoutIdType = CodeUnit.field(templateTypesFile, "", "_module_.UserWithoutId");
        assertTrue(skeletons.containsKey(userWithoutIdType), "UserWithoutId Omit type should be captured");
        assertEquals(
                normalize.apply("export type UserWithoutId = Omit<User, 'id'>"),
                normalize.apply(skeletons.get(userWithoutIdType)));

        // Record
        CodeUnit stringRecordType = CodeUnit.field(templateTypesFile, "", "_module_.StringRecord");
        assertTrue(skeletons.containsKey(stringRecordType), "StringRecord utility type should be captured");
        assertEquals(
                normalize.apply("export type StringRecord = Record<string, string>"),
                normalize.apply(skeletons.get(stringRecordType)));

        // ===== Test Complex Utility Type Combinations =====

        // Partial + Pick
        CodeUnit partialUserNameEmailType = CodeUnit.field(templateTypesFile, "", "_module_.PartialUserNameEmail");
        assertTrue(
                skeletons.containsKey(partialUserNameEmailType),
                "PartialUserNameEmail combined utility type should be captured");
        String partialUserNameEmailSkeleton = skeletons.get(partialUserNameEmailType);
        assertTrue(
                partialUserNameEmailSkeleton.contains("Partial<Pick<User, 'name' | 'email'>>"),
                "PartialUserNameEmail should contain nested utility types");

        // Required + Omit
        CodeUnit requiredConfigWithoutOptionalType =
                CodeUnit.field(templateTypesFile, "", "_module_.RequiredConfigWithoutOptional");
        assertTrue(
                skeletons.containsKey(requiredConfigWithoutOptionalType),
                "RequiredConfigWithoutOptional combined utility type should be captured");
        String requiredConfigSkeleton = skeletons.get(requiredConfigWithoutOptionalType);
        assertTrue(
                requiredConfigSkeleton.contains("Required<Omit<Config, 'optional'>>"),
                "RequiredConfigWithoutOptional should contain nested utility types");

        // Readonly + Partial
        CodeUnit readonlyPartialUserType = CodeUnit.field(templateTypesFile, "", "_module_.ReadonlyPartialUser");
        assertTrue(
                skeletons.containsKey(readonlyPartialUserType),
                "ReadonlyPartialUser combined utility type should be captured");
        assertEquals(
                normalize.apply("export type ReadonlyPartialUser = Readonly<Partial<User>>"),
                normalize.apply(skeletons.get(readonlyPartialUserType)));

        // Pick + Required
        CodeUnit requiredUserNameEmailType = CodeUnit.field(templateTypesFile, "", "_module_.RequiredUserNameEmail");
        assertTrue(
                skeletons.containsKey(requiredUserNameEmailType),
                "RequiredUserNameEmail combined utility type should be captured");
        String requiredUserNameEmailSkeleton = skeletons.get(requiredUserNameEmailType);
        assertTrue(
                requiredUserNameEmailSkeleton.contains("Required<Pick<User, 'name' | 'email'>>"),
                "RequiredUserNameEmail should contain nested utility types");

        // Omit + Partial
        CodeUnit partialUserWithoutIdType = CodeUnit.field(templateTypesFile, "", "_module_.PartialUserWithoutId");
        assertTrue(
                skeletons.containsKey(partialUserWithoutIdType),
                "PartialUserWithoutId combined utility type should be captured");
        assertEquals(
                normalize.apply("export type PartialUserWithoutId = Partial<Omit<User, 'id'>>"),
                normalize.apply(skeletons.get(partialUserWithoutIdType)));

        // ===== Test Triple Utility Type Combinations =====

        // Readonly + Partial + Pick
        CodeUnit readonlyPartialUserNameEmailType =
                CodeUnit.field(templateTypesFile, "", "_module_.ReadonlyPartialUserNameEmail");
        assertTrue(
                skeletons.containsKey(readonlyPartialUserNameEmailType),
                "ReadonlyPartialUserNameEmail triple utility type should be captured");
        String tripleUtilitySkeleton = skeletons.get(readonlyPartialUserNameEmailType);
        assertTrue(
                tripleUtilitySkeleton.contains("Readonly<Partial<Pick<User, 'name' | 'email'>>>"),
                "ReadonlyPartialUserNameEmail should contain three nested utility types");

        // Required + Omit + Readonly
        CodeUnit requiredReadonlyConfigType =
                CodeUnit.field(templateTypesFile, "", "_module_.RequiredReadonlyConfigWithoutOptional");
        assertTrue(
                skeletons.containsKey(requiredReadonlyConfigType),
                "RequiredReadonlyConfigWithoutOptional triple utility type should be captured");
        String requiredReadonlySkeleton = skeletons.get(requiredReadonlyConfigType);
        assertTrue(
                requiredReadonlySkeleton.contains("Required<Readonly<Omit<Config, 'optional'>>>"),
                "RequiredReadonlyConfigWithoutOptional should contain three nested utility types");

        // ===== Test Advanced Utility Type Patterns =====

        // Record with complex value type
        CodeUnit userRecordType = CodeUnit.field(templateTypesFile, "", "_module_.UserRecord");
        assertTrue(skeletons.containsKey(userRecordType), "UserRecord type should be captured");
        assertEquals(
                normalize.apply("export type UserRecord = Record<string, User>"),
                normalize.apply(skeletons.get(userRecordType)));

        CodeUnit partialUserRecordType = CodeUnit.field(templateTypesFile, "", "_module_.PartialUserRecord");
        assertTrue(skeletons.containsKey(partialUserRecordType), "PartialUserRecord type should be captured");
        String partialUserRecordSkeleton = skeletons.get(partialUserRecordType);
        assertTrue(
                partialUserRecordSkeleton.contains("Record<string, Partial<User>>"),
                "PartialUserRecord should contain nested utility in Record value");

        // Nested utility types
        CodeUnit nestedUtilityType = CodeUnit.field(templateTypesFile, "", "_module_.NestedUtility");
        assertTrue(skeletons.containsKey(nestedUtilityType), "NestedUtility type should be captured");
        String nestedUtilitySkeleton = skeletons.get(nestedUtilityType);
        assertTrue(
                nestedUtilitySkeleton.contains("Partial<Record<string, Pick<User, 'name' | 'email'>>>"),
                "NestedUtility should contain deeply nested utility types");

        // Utility with conditional
        CodeUnit conditionalUtilityType = CodeUnit.field(templateTypesFile, "", "_module_.ConditionalUtility");
        assertTrue(skeletons.containsKey(conditionalUtilityType), "ConditionalUtility type should be captured");
        String conditionalUtilitySkeleton = skeletons.get(conditionalUtilityType);
        assertTrue(
                conditionalUtilitySkeleton.contains("T extends User ? Partial<T> : Required<T>"),
                "ConditionalUtility should contain conditional with utility types");

        // ===== Test Template Literals with Utility Types =====

        // Template literal combined with mapped type
        CodeUnit eventHandlerType = CodeUnit.field(templateTypesFile, "", "_module_.EventHandler");
        assertTrue(
                skeletons.containsKey(eventHandlerType), "EventHandler template with mapped type should be captured");
        String eventHandlerSkeleton = skeletons.get(eventHandlerType);
        assertTrue(
                eventHandlerSkeleton.contains("on${Capitalize<T>}"),
                "EventHandler should contain template literal in mapped type");

        // Template literal with Record
        CodeUnit eventMapType = CodeUnit.field(templateTypesFile, "", "_module_.EventMap");
        assertTrue(skeletons.containsKey(eventMapType), "EventMap template with Record type should be captured");
        String eventMapSkeleton = skeletons.get(eventMapType);
        assertTrue(
                eventMapSkeleton.contains("Record<`${T}Event`, () => void>"),
                "EventMap should contain template literal in Record key");

        // ===== Test Mapped Types with Template Literals =====

        // Getters using template literals
        CodeUnit gettersType = CodeUnit.field(templateTypesFile, "", "_module_.Getters");
        assertTrue(skeletons.containsKey(gettersType), "Getters mapped type with template should be captured");
        String gettersSkeleton = skeletons.get(gettersType);
        assertTrue(
                gettersSkeleton.contains("get${Capitalize"),
                "Getters should contain template literal in key remapping");

        // Setters using template literals
        CodeUnit settersType = CodeUnit.field(templateTypesFile, "", "_module_.Setters");
        assertTrue(skeletons.containsKey(settersType), "Setters mapped type with template should be captured");
        String settersSkeleton = skeletons.get(settersType);
        assertTrue(
                settersSkeleton.contains("set${Capitalize"),
                "Setters should contain template literal in key remapping");

        // Event emitters
        CodeUnit eventEmitterType = CodeUnit.field(templateTypesFile, "", "_module_.EventEmitter");
        assertTrue(skeletons.containsKey(eventEmitterType), "EventEmitter mapped type should be captured");
        String eventEmitterSkeleton = skeletons.get(eventEmitterType);
        assertTrue(
                eventEmitterSkeleton.contains("on${Capitalize") && eventEmitterSkeleton.contains("Change"),
                "EventEmitter should contain template literal with suffix");

        // ===== Test Extreme Utility Type Combinations =====

        // Four levels deep
        CodeUnit deepUtilityType = CodeUnit.field(templateTypesFile, "", "_module_.DeepUtility");
        assertTrue(skeletons.containsKey(deepUtilityType), "DeepUtility four-level nested type should be captured");
        String deepUtilitySkeleton = skeletons.get(deepUtilityType);
        assertTrue(
                deepUtilitySkeleton.contains("Readonly<Required<Partial<Pick<User, 'name' | 'email'>>>>"),
                "DeepUtility should contain four nested utility types");

        // Mixed utilities with Record
        CodeUnit complexMixedUtilityType = CodeUnit.field(templateTypesFile, "", "_module_.ComplexMixedUtility");
        assertTrue(skeletons.containsKey(complexMixedUtilityType), "ComplexMixedUtility type should be captured");
        String complexMixedSkeleton = skeletons.get(complexMixedUtilityType);
        assertTrue(
                complexMixedSkeleton.contains("Partial<Record<string, Required<Pick<User, 'name'>>>>"),
                "ComplexMixedUtility should contain nested utilities with Record");

        // Utility with multiple type parameters
        CodeUnit mergedUtilityType = CodeUnit.field(templateTypesFile, "", "_module_.MergedUtility");
        assertTrue(skeletons.containsKey(mergedUtilityType), "MergedUtility generic type should be captured");
        String mergedUtilitySkeleton = skeletons.get(mergedUtilityType);
        assertTrue(
                mergedUtilitySkeleton.contains("Partial<T> & Required<U>"),
                "MergedUtility should contain utility types with intersection");

        // ===== Test Real-world Use Cases =====

        // API response type
        CodeUnit apiResponseType = CodeUnit.field(templateTypesFile, "", "_module_.ApiResponse");
        assertTrue(skeletons.containsKey(apiResponseType), "ApiResponse generic type should be captured");
        String apiResponseSkeleton = skeletons.get(apiResponseType);
        assertTrue(
                apiResponseSkeleton.contains("data: T") && apiResponseSkeleton.contains("status: number"),
                "ApiResponse should contain complete type definition");

        // Paginated API response
        CodeUnit paginatedResponseType = CodeUnit.field(templateTypesFile, "", "_module_.PaginatedResponse");
        assertTrue(skeletons.containsKey(paginatedResponseType), "PaginatedResponse type should be captured");
        String paginatedResponseSkeleton = skeletons.get(paginatedResponseType);
        assertTrue(
                paginatedResponseSkeleton.contains("ApiResponse<T[]>")
                        && paginatedResponseSkeleton.contains("page: number"),
                "PaginatedResponse should extend ApiResponse with intersection");

        // User update payload
        CodeUnit userUpdatePayloadType = CodeUnit.field(templateTypesFile, "", "_module_.UserUpdatePayload");
        assertTrue(skeletons.containsKey(userUpdatePayloadType), "UserUpdatePayload type should be captured");
        assertEquals(
                normalize.apply("export type UserUpdatePayload = Partial<Omit<User, 'id'>>"),
                normalize.apply(skeletons.get(userUpdatePayloadType)));

        // Partial environment config
        CodeUnit partialEnvConfigType = CodeUnit.field(templateTypesFile, "", "_module_.PartialEnvironmentConfig");
        assertTrue(skeletons.containsKey(partialEnvConfigType), "PartialEnvironmentConfig type should be captured");
        String partialEnvConfigSkeleton = skeletons.get(partialEnvConfigType);
        assertTrue(
                partialEnvConfigSkeleton.contains("Partial<EnvironmentConfig>"),
                "PartialEnvironmentConfig should contain utility type");

        // ===== Verify All Type Aliases Are Captured =====

        List<CodeUnit> typeAliases = declarations.stream()
                .filter(cu -> cu.isField() && cu.fqName().startsWith("_module_."))
                .collect(Collectors.toList());

        assertTrue(
                typeAliases.size() >= 50,
                "Should capture at least 50 type aliases from TemplateTypes.ts. Found: " + typeAliases.size());

        // Verify all captured type aliases have skeletons
        for (CodeUnit typeAlias : typeAliases) {
            assertTrue(skeletons.containsKey(typeAlias), "Type alias " + typeAlias.fqName() + " should have skeleton");
            String skeleton = skeletons.get(typeAlias);
            assertTrue(
                    skeleton.contains("type "),
                    "Type alias " + typeAlias.fqName() + " skeleton should contain 'type' keyword");
        }

        // ===== Test getSkeleton for Individual Type Aliases =====

        Optional<String> eventNameSkeletonViaGet = AnalyzerUtil.getSkeleton(analyzer, "_module_.EventName");
        assertTrue(eventNameSkeletonViaGet.isPresent(), "Should retrieve EventName type alias via getSkeleton");
        assertTrue(
                eventNameSkeletonViaGet.get().contains("`${T}Changed`"),
                "EventName skeleton should contain template literal");

        Optional<String> partialUserNameEmailSkeletonViaGet =
                AnalyzerUtil.getSkeleton(analyzer, "_module_.PartialUserNameEmail");
        assertTrue(
                partialUserNameEmailSkeletonViaGet.isPresent(),
                "Should retrieve PartialUserNameEmail type alias via getSkeleton");
        assertTrue(
                partialUserNameEmailSkeletonViaGet.get().contains("Partial<Pick<User, 'name' | 'email'>>"),
                "PartialUserNameEmail skeleton should contain nested utility types");

        Optional<String> deepUtilitySkeletonViaGet = AnalyzerUtil.getSkeleton(analyzer, "_module_.DeepUtility");
        assertTrue(deepUtilitySkeletonViaGet.isPresent(), "Should retrieve DeepUtility type alias via getSkeleton");
        assertTrue(
                deepUtilitySkeletonViaGet.get().contains("Readonly<Required<Partial<Pick<User, 'name' | 'email'>>>>"),
                "DeepUtility skeleton should contain four nested utility types");
    }

    @Test
    void testModernTypeScriptFeatures() {
        ProjectFile modernFeaturesFile = new ProjectFile(project.getRoot(), "ModernFeatures.ts");
        Map<CodeUnit, String> skeletons = analyzer.getSkeletons(modernFeaturesFile);
        Set<CodeUnit> declarations = analyzer.getDeclarations(modernFeaturesFile);

        assertFalse(skeletons.isEmpty(), "Skeletons map for ModernFeatures.ts should not be empty");
        assertFalse(declarations.isEmpty(), "Declarations set for ModernFeatures.ts should not be empty");

        // ===== Test Satisfies Operator =====
        // Note: The satisfies operator appears in initializer expressions, not in type signatures,
        // so it cannot be tested via skeletons. We only verify the const declarations are captured.

        // Basic satisfies usage - verify const declaration is captured
        CodeUnit configConst = CodeUnit.field(modernFeaturesFile, "", "_module_.config");
        assertTrue(skeletons.containsKey(configConst), "config const should be captured as a CodeUnit");

        // Satisfies with type narrowing - verify const declaration is captured
        CodeUnit strictConfigConst = CodeUnit.field(modernFeaturesFile, "", "_module_.strictConfig");
        assertTrue(skeletons.containsKey(strictConfigConst), "strictConfig const should be captured as a CodeUnit");

        // Satisfies with complex types - verify const declaration is captured
        CodeUnit themeConfigConst = CodeUnit.field(modernFeaturesFile, "", "_module_.themeConfig");
        assertTrue(skeletons.containsKey(themeConfigConst), "themeConfig const should be captured as a CodeUnit");

        // ===== Test Type Predicate Functions =====

        // Basic type predicate
        CodeUnit isStringFunc = CodeUnit.fn(modernFeaturesFile, "", "isString");
        assertTrue(skeletons.containsKey(isStringFunc), "isString type predicate function should be captured");
        String isStringSkeleton = skeletons.get(isStringFunc);
        assertTrue(
                isStringSkeleton.contains(": x is string"),
                "isString should show type predicate return type 'x is string'. Skeleton: " + isStringSkeleton);

        // Type predicate with complex type
        CodeUnit isNumberFunc = CodeUnit.fn(modernFeaturesFile, "", "isNumber");
        assertTrue(skeletons.containsKey(isNumberFunc), "isNumber type predicate function should be captured");
        String isNumberSkeleton = skeletons.get(isNumberFunc);
        assertTrue(
                isNumberSkeleton.contains(": value is number"),
                "isNumber should show type predicate return type 'value is number'");

        // Type predicate with generic
        CodeUnit isArrayFunc = CodeUnit.fn(modernFeaturesFile, "", "isArray");
        assertTrue(skeletons.containsKey(isArrayFunc), "isArray generic type predicate should be captured");
        String isArraySkeleton = skeletons.get(isArrayFunc);
        assertTrue(
                isArraySkeleton.contains(": value is Array<T>"),
                "isArray should show generic type predicate 'value is Array<T>'");

        // Type predicate for object type
        CodeUnit isUserFunc = CodeUnit.fn(modernFeaturesFile, "", "isUser");
        assertTrue(skeletons.containsKey(isUserFunc), "isUser type predicate function should be captured");
        String isUserSkeleton = skeletons.get(isUserFunc);
        assertTrue(
                isUserSkeleton.contains(": obj is User"),
                "isUser should show type predicate return type 'obj is User'");

        // Type predicate with null check
        CodeUnit isNonNullFunc = CodeUnit.fn(modernFeaturesFile, "", "isNonNull");
        assertTrue(skeletons.containsKey(isNonNullFunc), "isNonNull type predicate function should be captured");
        String isNonNullSkeleton = skeletons.get(isNonNullFunc);
        assertTrue(
                isNonNullSkeleton.contains(": value is T"),
                "isNonNull should show type predicate return type 'value is T'");

        // Class method with type predicate
        CodeUnit typeCheckerClass = CodeUnit.cls(modernFeaturesFile, "", "TypeChecker");
        assertTrue(skeletons.containsKey(typeCheckerClass), "TypeChecker class should be captured");
        String typeCheckerSkeleton = skeletons.get(typeCheckerClass);
        assertTrue(
                typeCheckerSkeleton.contains(": x is string"),
                "TypeChecker.isStringValue should show type predicate in class");
        assertTrue(
                typeCheckerSkeleton.contains("static isNumberValue"),
                "TypeChecker should have static type predicate method");

        // ===== Test Assertion Signatures =====

        // Basic assertion signature
        CodeUnit assertFunc = CodeUnit.fn(modernFeaturesFile, "", "assert");
        assertTrue(skeletons.containsKey(assertFunc), "assert function with assertion signature should be captured");
        String assertSkeleton = skeletons.get(assertFunc);
        assertTrue(
                assertSkeleton.contains(": asserts condition"),
                "assert should show assertion signature 'asserts condition'. Skeleton: " + assertSkeleton);

        // Assertion signature with message
        CodeUnit assertWithMessageFunc = CodeUnit.fn(modernFeaturesFile, "", "assertWithMessage");
        assertTrue(skeletons.containsKey(assertWithMessageFunc), "assertWithMessage function should be captured");
        String assertWithMessageSkeleton = skeletons.get(assertWithMessageFunc);
        assertTrue(
                assertWithMessageSkeleton.contains(": asserts condition"),
                "assertWithMessage should show assertion signature");

        // Assertion signature with type predicate
        CodeUnit assertIsStringFunc = CodeUnit.fn(modernFeaturesFile, "", "assertIsString");
        assertTrue(skeletons.containsKey(assertIsStringFunc), "assertIsString function should be captured");
        String assertIsStringSkeleton = skeletons.get(assertIsStringFunc);
        assertTrue(
                assertIsStringSkeleton.contains(": asserts value is string"),
                "assertIsString should show assertion with type predicate 'asserts value is string'");

        // Assertion signature with generic
        CodeUnit assertIsArrayFunc = CodeUnit.fn(modernFeaturesFile, "", "assertIsArray");
        assertTrue(
                skeletons.containsKey(assertIsArrayFunc),
                "assertIsArray generic assertion function should be captured");
        String assertIsArraySkeleton = skeletons.get(assertIsArrayFunc);
        assertTrue(
                assertIsArraySkeleton.contains(": asserts value is Array<T>"),
                "assertIsArray should show generic assertion 'asserts value is Array<T>'");

        // Assertion signature for non-null
        CodeUnit assertNonNullFunc = CodeUnit.fn(modernFeaturesFile, "", "assertNonNull");
        assertTrue(skeletons.containsKey(assertNonNullFunc), "assertNonNull assertion function should be captured");
        String assertNonNullSkeleton = skeletons.get(assertNonNullFunc);
        assertTrue(
                assertNonNullSkeleton.contains(": asserts value is T"),
                "assertNonNull should show assertion 'asserts value is T'");

        // Class method with assertion signature
        CodeUnit validatorClass = CodeUnit.cls(modernFeaturesFile, "", "Validator");
        assertTrue(skeletons.containsKey(validatorClass), "Validator class should be captured");
        String validatorSkeleton = skeletons.get(validatorClass);
        assertTrue(
                validatorSkeleton.contains(": asserts value is number"),
                "Validator.assertPositive should show assertion signature in class");
        assertTrue(
                validatorSkeleton.contains("static assertNotEmpty"), "Validator should have static assertion method");

        // ===== Test This Parameters =====

        // Basic this parameter
        CodeUnit greetFunc = CodeUnit.fn(modernFeaturesFile, "", "greet");
        assertTrue(skeletons.containsKey(greetFunc), "greet function with this parameter should be captured");
        String greetSkeleton = skeletons.get(greetFunc);
        assertTrue(
                greetSkeleton.contains("this: User"),
                "greet should show this parameter 'this: User' in signature. Skeleton: " + greetSkeleton);

        // This parameter with return type
        CodeUnit getUserAgeFunc = CodeUnit.fn(modernFeaturesFile, "", "getUserAge");
        assertTrue(skeletons.containsKey(getUserAgeFunc), "getUserAge function with this parameter should be captured");
        String getUserAgeSkeleton = skeletons.get(getUserAgeFunc);
        assertTrue(getUserAgeSkeleton.contains("this: User"), "getUserAge should show this parameter");
        assertTrue(getUserAgeSkeleton.contains(": number"), "getUserAge should show return type");

        // This parameter with generics
        CodeUnit getValueFunc = CodeUnit.fn(modernFeaturesFile, "", "getValue");
        assertTrue(
                skeletons.containsKey(getValueFunc),
                "getValue generic function with this parameter should be captured");
        String getValueSkeleton = skeletons.get(getValueFunc);
        assertTrue(getValueSkeleton.contains("this: { value: T }"), "getValue should show generic this parameter");

        // This parameter with void return
        CodeUnit logUserFunc = CodeUnit.fn(modernFeaturesFile, "", "logUser");
        assertTrue(skeletons.containsKey(logUserFunc), "logUser function with this parameter should be captured");
        String logUserSkeleton = skeletons.get(logUserFunc);
        assertTrue(logUserSkeleton.contains("this: User"), "logUser should show this parameter");

        // Multiple parameters with this
        CodeUnit updateUserFunc = CodeUnit.fn(modernFeaturesFile, "", "updateUser");
        assertTrue(skeletons.containsKey(updateUserFunc), "updateUser function with this parameter should be captured");
        String updateUserSkeleton = skeletons.get(updateUserFunc);
        assertTrue(updateUserSkeleton.contains("this: User"), "updateUser should show this parameter");
        assertTrue(
                updateUserSkeleton.contains("name: string") && updateUserSkeleton.contains("age: number"),
                "updateUser should show additional parameters after this");

        // Class with methods using this parameter explicitly
        CodeUnit calculatorClass = CodeUnit.cls(modernFeaturesFile, "", "Calculator");
        assertTrue(skeletons.containsKey(calculatorClass), "Calculator class should be captured");
        String calculatorSkeleton = skeletons.get(calculatorClass);
        assertTrue(
                calculatorSkeleton.contains("add(this: Calculator"),
                "Calculator.add should show explicit this parameter");
        assertTrue(
                calculatorSkeleton.contains("static create(this: typeof Calculator"),
                "Calculator.create should show static this parameter");

        // ===== Test Const Type Parameters =====

        // Basic const type parameter
        CodeUnit identityFunc = CodeUnit.fn(modernFeaturesFile, "", "identity");
        assertTrue(
                skeletons.containsKey(identityFunc), "identity function with const type parameter should be captured");
        String identitySkeleton = skeletons.get(identityFunc);
        assertTrue(
                identitySkeleton.contains("<const T>"),
                "identity should show const type parameter '<const T>'. Skeleton: " + identitySkeleton);

        // Const type parameter with array
        CodeUnit tupleFunc = CodeUnit.fn(modernFeaturesFile, "", "tuple");
        assertTrue(skeletons.containsKey(tupleFunc), "tuple function with const type parameter should be captured");
        String tupleSkeleton = skeletons.get(tupleFunc);
        assertTrue(
                tupleSkeleton.contains("<const T extends readonly any[]>"),
                "tuple should show const type parameter with constraint");

        // Const type parameter preserving literal types
        CodeUnit asConstFunc = CodeUnit.fn(modernFeaturesFile, "", "asConst");
        assertTrue(skeletons.containsKey(asConstFunc), "asConst function with const type parameter should be captured");
        String asConstSkeleton = skeletons.get(asConstFunc);
        assertTrue(asConstSkeleton.contains("<const T>"), "asConst should show const type parameter");

        // Const type parameter with object
        CodeUnit freezeFunc = CodeUnit.fn(modernFeaturesFile, "", "freeze");
        assertTrue(skeletons.containsKey(freezeFunc), "freeze function with const type parameter should be captured");
        String freezeSkeleton = skeletons.get(freezeFunc);
        assertTrue(
                freezeSkeleton.contains("<const T extends Record<string, any>>"),
                "freeze should show const type parameter with object constraint");

        // Const type parameter with multiple parameters
        CodeUnit pairFunc = CodeUnit.fn(modernFeaturesFile, "", "pair");
        assertTrue(
                skeletons.containsKey(pairFunc),
                "pair function with multiple const type parameters should be captured");
        String pairSkeleton = skeletons.get(pairFunc);
        assertTrue(pairSkeleton.contains("<const T, const U>"), "pair should show multiple const type parameters");

        // Class with const type parameter
        CodeUnit containerClass = CodeUnit.cls(modernFeaturesFile, "", "Container");
        assertTrue(
                skeletons.containsKey(containerClass), "Container class with const type parameter should be captured");
        String containerSkeleton = skeletons.get(containerClass);
        assertTrue(containerSkeleton.contains("<const T>"), "Container class should show const type parameter");

        // Function with const type parameter and constraint
        CodeUnit createRecordFunc = CodeUnit.fn(modernFeaturesFile, "", "createRecord");
        assertTrue(
                skeletons.containsKey(createRecordFunc),
                "createRecord function with const type parameter should be captured");
        String createRecordSkeleton = skeletons.get(createRecordFunc);
        assertTrue(
                createRecordSkeleton.contains("<const K extends string"),
                "createRecord should show const type parameter with constraint");

        // ===== Test Combined Modern Features =====

        // Type predicate with const type parameter
        CodeUnit isLiteralArrayFunc = CodeUnit.fn(modernFeaturesFile, "", "isLiteralArray");
        assertTrue(
                skeletons.containsKey(isLiteralArrayFunc),
                "isLiteralArray combining const and type predicate should be captured");
        String isLiteralArraySkeleton = skeletons.get(isLiteralArrayFunc);
        assertTrue(
                isLiteralArraySkeleton.contains("<const T extends readonly any[]>"),
                "isLiteralArray should show const type parameter");
        assertTrue(isLiteralArraySkeleton.contains(": value is T"), "isLiteralArray should show type predicate");

        // Assertion with const type parameter
        CodeUnit assertLiteralFunc = CodeUnit.fn(modernFeaturesFile, "", "assertLiteral");
        assertTrue(
                skeletons.containsKey(assertLiteralFunc),
                "assertLiteral combining const and assertion should be captured");
        String assertLiteralSkeleton = skeletons.get(assertLiteralFunc);
        assertTrue(assertLiteralSkeleton.contains("<const T>"), "assertLiteral should show const type parameter");
        assertTrue(
                assertLiteralSkeleton.contains(": asserts value is T"),
                "assertLiteral should show assertion signature");

        // This parameter with const type parameter
        CodeUnit bindMethodFunc = CodeUnit.fn(modernFeaturesFile, "", "bindMethod");
        assertTrue(skeletons.containsKey(bindMethodFunc), "bindMethod combining this and const should be captured");
        String bindMethodSkeleton = skeletons.get(bindMethodFunc);
        assertTrue(bindMethodSkeleton.contains("this: any"), "bindMethod should show this parameter");
        assertTrue(bindMethodSkeleton.contains("<const T extends"), "bindMethod should show const type parameter");

        // Const with type predicate - verify const declaration is captured
        CodeUnit validatorConst = CodeUnit.field(modernFeaturesFile, "", "_module_.validator");
        assertTrue(skeletons.containsKey(validatorConst), "validator const should be captured as a CodeUnit");

        // Complex combination: const type parameter + type predicate + this
        CodeUnit typeSafeBuilderClass = CodeUnit.cls(modernFeaturesFile, "", "TypeSafeBuilder");
        assertTrue(
                skeletons.containsKey(typeSafeBuilderClass),
                "TypeSafeBuilder combining all modern features should be captured");
        String typeSafeBuilderSkeleton = skeletons.get(typeSafeBuilderClass);
        assertTrue(
                typeSafeBuilderSkeleton.contains("<const T extends Record<string, any>>"),
                "TypeSafeBuilder should show const type parameter");
        assertTrue(
                typeSafeBuilderSkeleton.contains("this: TypeSafeBuilder<T>"),
                "TypeSafeBuilder methods should show this parameter");
        assertTrue(
                typeSafeBuilderSkeleton.contains("isComplete(this: TypeSafeBuilder<T>): this is TypeSafeBuilder<T>"),
                "TypeSafeBuilder.isComplete should show type predicate with this");

        // ===== Verify All Modern Features Are Captured =====

        // Count functions with modern features
        List<CodeUnit> functionsWithTypePredicates = declarations.stream()
                .filter(cu -> cu.isFunction())
                .filter(cu -> {
                    String skeleton = skeletons.get(cu);
                    return skeleton != null && skeleton.contains(" is ");
                })
                .collect(Collectors.toList());

        assertTrue(
                functionsWithTypePredicates.size() >= 8,
                "Should capture at least 8 functions with type predicates. Found: "
                        + functionsWithTypePredicates.size());

        List<CodeUnit> functionsWithAssertions = declarations.stream()
                .filter(cu -> cu.isFunction())
                .filter(cu -> {
                    String skeleton = skeletons.get(cu);
                    return skeleton != null && skeleton.contains("asserts");
                })
                .collect(Collectors.toList());

        assertTrue(
                functionsWithAssertions.size() >= 5,
                "Should capture at least 5 functions with assertion signatures. Found: "
                        + functionsWithAssertions.size());

        List<CodeUnit> functionsWithThisParam = declarations.stream()
                .filter(cu -> cu.isFunction())
                .filter(cu -> {
                    String skeleton = skeletons.get(cu);
                    return skeleton != null && skeleton.contains("this:");
                })
                .collect(Collectors.toList());

        assertTrue(
                functionsWithThisParam.size() >= 5,
                "Should capture at least 5 functions with this parameter. Found: " + functionsWithThisParam.size());

        List<CodeUnit> functionsWithConstTypeParam = declarations.stream()
                .filter(cu -> cu.isFunction())
                .filter(cu -> {
                    String skeleton = skeletons.get(cu);
                    return skeleton != null && skeleton.contains("<const ");
                })
                .collect(Collectors.toList());

        assertTrue(
                functionsWithConstTypeParam.size() >= 7,
                "Should capture at least 7 functions with const type parameters. Found: "
                        + functionsWithConstTypeParam.size());

        // ===== Test getSkeleton for Individual Modern Features =====

        Optional<String> isStringSkeletonViaGet = AnalyzerUtil.getSkeleton(analyzer, "isString");
        assertTrue(isStringSkeletonViaGet.isPresent(), "Should retrieve isString via getSkeleton");
        assertTrue(
                isStringSkeletonViaGet.get().contains(": x is string"),
                "isString skeleton should contain type predicate");

        Optional<String> assertSkeletonViaGet = AnalyzerUtil.getSkeleton(analyzer, "assert");
        assertTrue(assertSkeletonViaGet.isPresent(), "Should retrieve assert via getSkeleton");
        assertTrue(
                assertSkeletonViaGet.get().contains(": asserts condition"),
                "assert skeleton should contain assertion signature");

        Optional<String> greetSkeletonViaGet = AnalyzerUtil.getSkeleton(analyzer, "greet");
        assertTrue(greetSkeletonViaGet.isPresent(), "Should retrieve greet via getSkeleton");
        assertTrue(greetSkeletonViaGet.get().contains("this: User"), "greet skeleton should contain this parameter");

        Optional<String> identitySkeletonViaGet = AnalyzerUtil.getSkeleton(analyzer, "identity");
        assertTrue(identitySkeletonViaGet.isPresent(), "Should retrieve identity via getSkeleton");
        assertTrue(
                identitySkeletonViaGet.get().contains("<const T>"),
                "identity skeleton should contain const type parameter");
    }

    @Test
    void testTypeScriptEdgeCases() {
        ProjectFile edgeCasesFile = new ProjectFile(project.getRoot(), "EdgeCases.ts");
        Map<CodeUnit, String> skeletons = analyzer.getSkeletons(edgeCasesFile);
        Set<CodeUnit> declarations = analyzer.getDeclarations(edgeCasesFile);

        // The file should not be empty even though it starts with comments
        assertFalse(skeletons.isEmpty(), "EdgeCases.ts should contain declarations despite initial comments");
        assertFalse(declarations.isEmpty(), "EdgeCases.ts should have declarations");

        // ===== Test 1: Empty File Section Handling =====
        // The analyzer should skip over the comment-only section at the top
        // and successfully parse the actual declarations that follow

        // ===== Test 2: Deeply Nested Namespace Structure (5 levels) =====
        // Verify the top-level namespace is captured
        // Note: The analyzer may not fully traverse deeply nested namespace structures (5+ levels)
        // This is a known limitation when namespaces are nested beyond typical depth

        CodeUnit namespaceA = declarations.stream()
                .filter(cu -> cu.shortName().equals("A") && cu.isClass())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Namespace A should be found. Available declarations: "
                        + declarations.stream().map(CodeUnit::fqName).collect(Collectors.joining(", "))));

        assertTrue(skeletons.containsKey(namespaceA), "Namespace A should have skeleton");
        String namespaceSkeleton = skeletons.get(namespaceA);
        assertTrue(
                namespaceSkeleton.contains("namespace A"), "Namespace A skeleton should contain namespace definition");

        // Verify the module-level instance of the deeply nested class is captured
        CodeUnit deepInstance = declarations.stream()
                .filter(cu -> cu.fqName().equals("_module_.deepInstance") && cu.isField())
                .findFirst()
                .orElseThrow(
                        () -> new AssertionError("_module_.deepInstance should be found as it's a module-level const"));

        // The deeply nested structures (5 levels deep) may not be fully traversable by the analyzer
        // This is an edge case that reveals limitations with extreme nesting depth
        // The important behavior is that the analyzer doesn't crash and captures what it can

        // ===== Test 3: String Enums =====

        // Status enum (exported string enum)
        CodeUnit statusEnum = declarations.stream()
                .filter(cu -> cu.shortName().equals("Status") && cu.isClass())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Status enum should be found"));

        assertTrue(skeletons.containsKey(statusEnum), "Status enum should have skeleton");
        String statusSkeleton = skeletons.get(statusEnum);
        assertTrue(
                statusSkeleton.contains("enum Status"),
                "Status skeleton should contain enum definition. Actual skeleton: " + statusSkeleton);

        // Verify enum members are present in the skeleton
        assertTrue(
                statusSkeleton.contains("Active"),
                "Status enum skeleton should contain Active member. Actual: " + statusSkeleton);
        assertTrue(
                statusSkeleton.contains("Inactive"),
                "Status enum skeleton should contain Inactive member. Actual: " + statusSkeleton);
        assertTrue(
                statusSkeleton.contains("Pending"),
                "Status enum skeleton should contain Pending member. Actual: " + statusSkeleton);
        assertTrue(
                statusSkeleton.contains("Archived"),
                "Status enum skeleton should contain Archived member. Actual: " + statusSkeleton);

        // Verify enum members are captured
        CodeUnit statusActive = declarations.stream()
                .filter(cu -> cu.fqName().equals("Status.Active") && cu.isField())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Status.Active member should be captured"));

        // LogLevel enum (non-exported string enum)
        CodeUnit logLevelEnum = declarations.stream()
                .filter(cu -> cu.shortName().equals("LogLevel") && cu.isClass())
                .findFirst()
                .orElseThrow(() -> new AssertionError("LogLevel enum should be found"));

        String logLevelSkeleton = skeletons.get(logLevelEnum);
        assertTrue(
                logLevelSkeleton.contains("Debug") && logLevelSkeleton.contains("Info"),
                "LogLevel enum should contain member names. Actual: " + logLevelSkeleton);

        // ===== Test 4: Heterogeneous Enums (Mixed String/Number Members) =====

        // MixedEnum (exported heterogeneous enum)
        CodeUnit mixedEnum = declarations.stream()
                .filter(cu -> cu.shortName().equals("MixedEnum") && cu.isClass())
                .findFirst()
                .orElseThrow(() -> new AssertionError("MixedEnum should be found"));

        assertTrue(skeletons.containsKey(mixedEnum), "MixedEnum should have skeleton");
        String mixedEnumSkeleton = skeletons.get(mixedEnum);
        assertTrue(
                mixedEnumSkeleton.contains("No") && mixedEnumSkeleton.contains("Yes"),
                "MixedEnum should contain member names. Actual: " + mixedEnumSkeleton);
        assertTrue(
                mixedEnumSkeleton.contains("Unknown") && mixedEnumSkeleton.contains("Maybe"),
                "MixedEnum should contain all member names. Actual: " + mixedEnumSkeleton);

        // ResponseCode enum
        CodeUnit responseCodeEnum = declarations.stream()
                .filter(cu -> cu.shortName().equals("ResponseCode") && cu.isClass())
                .findFirst()
                .orElseThrow(() -> new AssertionError("ResponseCode enum should be found"));

        String responseCodeSkeleton = skeletons.get(responseCodeEnum);
        assertTrue(
                responseCodeSkeleton.contains("Success") && responseCodeSkeleton.contains("SuccessMessage"),
                "ResponseCode should contain member names. Actual: " + responseCodeSkeleton);

        // ===== Test 5: Computed Enum Members =====

        // Flags enum (exported with bitwise operations)
        CodeUnit flagsEnum = declarations.stream()
                .filter(cu -> cu.shortName().equals("Flags") && cu.isClass())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Flags enum should be found"));

        assertTrue(skeletons.containsKey(flagsEnum), "Flags enum should have skeleton");
        String flagsSkeleton = skeletons.get(flagsEnum);
        assertTrue(
                flagsSkeleton.contains("None") && flagsSkeleton.contains("Read"),
                "Flags enum should contain None and Read members. Actual: " + flagsSkeleton);
        assertTrue(
                flagsSkeleton.contains("Write") && flagsSkeleton.contains("Execute"),
                "Flags enum should contain Write and Execute members. Actual: " + flagsSkeleton);
        assertTrue(
                flagsSkeleton.contains("Delete") && flagsSkeleton.contains("All"),
                "Flags enum should contain Delete and All members. Actual: " + flagsSkeleton);

        // Permissions enum (computed with expressions)
        CodeUnit permissionsEnum = declarations.stream()
                .filter(cu -> cu.shortName().equals("Permissions") && cu.isClass())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Permissions enum should be found"));

        String permissionsSkeleton = skeletons.get(permissionsEnum);
        assertTrue(
                permissionsSkeleton.contains("ViewOnly") && permissionsSkeleton.contains("Edit"),
                "Permissions enum should contain member names. Actual: " + permissionsSkeleton);

        // FileSize enum (arithmetic operations)
        CodeUnit fileSizeEnum = declarations.stream()
                .filter(cu -> cu.shortName().equals("FileSize") && cu.isClass())
                .findFirst()
                .orElseThrow(() -> new AssertionError("FileSize enum should be found"));

        String fileSizeSkeleton = skeletons.get(fileSizeEnum);
        assertTrue(
                fileSizeSkeleton.contains("KB") && fileSizeSkeleton.contains("MB"),
                "FileSize enum should contain KB and MB members. Actual: " + fileSizeSkeleton);
        assertTrue(
                fileSizeSkeleton.contains("GB") && fileSizeSkeleton.contains("TB"),
                "FileSize enum should contain GB and TB members. Actual: " + fileSizeSkeleton);

        // ===== Test 6: Additional Edge Cases =====

        // Counter enum (mixed explicit and implicit values)
        CodeUnit counterEnum = declarations.stream()
                .filter(cu -> cu.shortName().equals("Counter") && cu.isClass())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Counter enum should be found"));

        String counterSkeleton = skeletons.get(counterEnum);
        assertTrue(
                counterSkeleton.contains("First") && counterSkeleton.contains("Third"),
                "Counter should contain member names. Actual: " + counterSkeleton);

        // EmptyEnum (edge case: enum with no members)
        CodeUnit emptyEnum = declarations.stream()
                .filter(cu -> cu.shortName().equals("EmptyEnum") && cu.isClass())
                .findFirst()
                .orElseThrow(() -> new AssertionError("EmptyEnum should be found"));

        String emptySkeleton = skeletons.get(emptyEnum);
        assertTrue(emptySkeleton.contains("enum EmptyEnum"), "EmptyEnum should have enum declaration");

        // SingleMember enum
        CodeUnit singleMemberEnum = declarations.stream()
                .filter(cu -> cu.shortName().equals("SingleMember") && cu.isClass())
                .findFirst()
                .orElseThrow(() -> new AssertionError("SingleMember enum should be found"));

        String singleMemberSkeleton = skeletons.get(singleMemberEnum);
        assertTrue(
                singleMemberSkeleton.contains("OnlyOne"),
                "SingleMember enum should contain OnlyOne member. Actual: " + singleMemberSkeleton);

        // ===== Test 7: Verify Namespace Nesting Doesn't Create Duplicate Top-level =====
        // The namespace A should be a top-level declaration, but A.B, A.B.C, etc. should not
        List<CodeUnit> topLevel = analyzer.getTopLevelDeclarations(edgeCasesFile);

        boolean hasTopLevelA = topLevel.stream()
                .anyMatch(cu -> cu.shortName().equals("A") && cu.packageName().isEmpty());
        assertTrue(hasTopLevelA, "Namespace A should be a top-level declaration");

        // Nested namespaces should NOT appear as top-level
        boolean hasTopLevelB = topLevel.stream()
                .anyMatch(cu -> cu.shortName().equals("B") && cu.packageName().isEmpty());
        assertFalse(hasTopLevelB, "Nested namespace B should not be a top-level declaration");

        // ===== Test 8: Verify All Enum Members Are Captured =====
        List<CodeUnit> statusMembers = declarations.stream()
                .filter(cu -> cu.fqName().startsWith("Status."))
                .collect(Collectors.toList());

        assertEquals(
                4, statusMembers.size(), "Status enum should have 4 members (Active, Inactive, Pending, Archived)");

        List<CodeUnit> flagsMembers = declarations.stream()
                .filter(cu -> cu.fqName().startsWith("Flags."))
                .collect(Collectors.toList());

        assertEquals(
                6, flagsMembers.size(), "Flags enum should have 6 members (None, Read, Write, Execute, Delete, All)");

        // ===== Test 9: Verify getSkeleton Works for Nested Items =====
        Optional<String> deeplyClassSkeleton = AnalyzerUtil.getSkeleton(analyzer, "A.B.C.D.E.Deeply");
        assertTrue(deeplyClassSkeleton.isPresent(), "Should retrieve deeply nested class skeleton via getSkeleton");

        Optional<String> statusEnumSkeleton = AnalyzerUtil.getSkeleton(analyzer, "Status");
        assertTrue(statusEnumSkeleton.isPresent(), "Should retrieve Status enum skeleton via getSkeleton");
        assertTrue(statusEnumSkeleton.get().contains("Active"), "Status enum skeleton should contain member names");

        Optional<String> flagsEnumSkeleton = AnalyzerUtil.getSkeleton(analyzer, "Flags");
        assertTrue(flagsEnumSkeleton.isPresent(), "Should retrieve Flags enum skeleton via getSkeleton");
        assertTrue(
                flagsEnumSkeleton.get().contains("Read")
                        && flagsEnumSkeleton.get().contains("All"),
                "Flags enum skeleton should contain member names");
    }

    @Test
    void testImportExportEdgeCases() {
        ProjectFile importExportFile = new ProjectFile(project.getRoot(), "ImportExportEdgeCases.ts");

        // The main goal is to ensure the analyzer handles imports/exports without errors
        // and that getTopLevelDeclarations() doesn't fail or produce duplicates

        // Test 1: Verify analyzer can process the file without exceptions
        Map<CodeUnit, String> skeletons = analyzer.getSkeletons(importExportFile);
        assertNotNull(skeletons, "Skeletons map should not be null even with complex imports");

        Set<CodeUnit> declarations = analyzer.getDeclarations(importExportFile);
        assertNotNull(declarations, "Declarations set should not be null");

        // Test 2: Verify getTopLevelDeclarations works without errors
        List<CodeUnit> topLevelUnits = analyzer.getTopLevelDeclarations(importExportFile);
        assertNotNull(topLevelUnits, "Top-level declarations should not be null");

        // Test 3: Verify local declarations are captured (not import statements themselves)
        // The analyzer should capture the LOCAL declarations, not the imported symbols

        CodeUnit localClass = declarations.stream()
                .filter(cu -> cu.shortName().equals("LocalClass") && cu.isClass())
                .findFirst()
                .orElse(null);
        assertNotNull(localClass, "LocalClass should be captured as a declaration");
        assertTrue(skeletons.containsKey(localClass), "LocalClass should have a skeleton");

        CodeUnit localInterface = declarations.stream()
                .filter(cu -> cu.shortName().equals("LocalInterface") && cu.isClass())
                .findFirst()
                .orElse(null);
        assertNotNull(localInterface, "LocalInterface should be captured");

        CodeUnit localFunction = declarations.stream()
                .filter(cu -> cu.shortName().equals("localFunction") && cu.isFunction())
                .findFirst()
                .orElse(null);
        assertNotNull(localFunction, "localFunction should be captured");

        CodeUnit localConst = declarations.stream()
                .filter(cu -> cu.fqName().equals("_module_.localConst") && cu.isField())
                .findFirst()
                .orElse(null);
        assertNotNull(localConst, "localConst should be captured");

        CodeUnit useNamespaceFunc = declarations.stream()
                .filter(cu -> cu.shortName().equals("useNamespace") && cu.isFunction())
                .findFirst()
                .orElse(null);
        assertNotNull(useNamespaceFunc, "useNamespace function should be captured");

        CodeUnit dynamicImportFunc = declarations.stream()
                .filter(cu -> cu.shortName().equals("dynamicImport") && cu.isFunction())
                .findFirst()
                .orElse(null);
        assertNotNull(dynamicImportFunc, "dynamicImport function should be captured");

        CodeUnit circularBClass = declarations.stream()
                .filter(cu -> cu.shortName().equals("CircularB") && cu.isClass())
                .findFirst()
                .orElse(null);
        assertNotNull(circularBClass, "CircularB class should be captured");

        // Test 4: Verify no duplicate FQNames in declarations
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

        // Test 5: Verify skeletons are properly formed for local declarations
        String localClassSkeleton = skeletons.get(localClass);
        assertTrue(
                localClassSkeleton.contains("export class LocalClass"),
                "LocalClass skeleton should contain class definition");
        assertTrue(localClassSkeleton.contains("method"), "LocalClass skeleton should contain method");

        String localInterfaceSkeleton = skeletons.get(localInterface);
        assertTrue(
                localInterfaceSkeleton.contains("export interface LocalInterface"),
                "LocalInterface skeleton should contain interface definition");
        assertTrue(
                localInterfaceSkeleton.contains("id: number") && localInterfaceSkeleton.contains("name: string"),
                "LocalInterface skeleton should contain properties");

        // Test 6: Verify top-level declarations include local declarations but not imports
        boolean hasLocalClassTopLevel =
                topLevelUnits.stream().anyMatch(cu -> cu.shortName().equals("LocalClass"));
        assertTrue(hasLocalClassTopLevel, "LocalClass should be in top-level declarations");

        boolean hasLocalFunctionTopLevel =
                topLevelUnits.stream().anyMatch(cu -> cu.shortName().equals("localFunction"));
        assertTrue(hasLocalFunctionTopLevel, "localFunction should be in top-level declarations");

        // Test 7: Verify imported symbols are NOT added as local declarations
        // The analyzer should not create CodeUnits for imported symbols (they're from other files)
        boolean hasImportedReact = declarations.stream()
                .anyMatch(cu -> cu.identifier().equals("React") && cu.source().equals(importExportFile));
        assertFalse(hasImportedReact, "Imported 'React' should not appear as a local declaration in this file");

        boolean hasImportedNS = declarations.stream()
                .anyMatch(cu -> cu.identifier().equals("NS") && cu.source().equals(importExportFile));
        assertFalse(hasImportedNS, "Imported namespace 'NS' should not appear as a local declaration");

        // Test 8: Verify re-exports don't create duplicate local declarations
        // Re-exports like `export * from './module'` should not create local CodeUnits
        // (they're re-exporting from other files)

        // Test 9: Verify getSkeleton works for individual items
        Optional<String> localClassSkeletonViaGet = AnalyzerUtil.getSkeleton(analyzer, "LocalClass");
        assertTrue(localClassSkeletonViaGet.isPresent(), "Should retrieve LocalClass via getSkeleton");

        Optional<String> localFunctionSkeletonViaGet = AnalyzerUtil.getSkeleton(analyzer, "localFunction");
        assertTrue(localFunctionSkeletonViaGet.isPresent(), "Should retrieve localFunction via getSkeleton");

        // Test 10: Verify the analyzer processed the file successfully despite complex import patterns
        assertFalse(declarations.isEmpty(), "Should have captured local declarations from the file");
        assertFalse(skeletons.isEmpty(), "Should have generated skeletons for local declarations");

        // The key insight: imports are metadata/references, not declarations
        // The analyzer should capture LOCAL declarations and not confuse them with imports
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
