package io.github.jbellis.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.testutil.TestProject;
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
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
        Set<CodeUnit> declarations = analyzer.getDeclarationsInFile(helloTsFile);
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
        Optional<String> stringOrNumberSkeleton = analyzer.getSkeleton("_module_.StringOrNumber");
        assertTrue(stringOrNumberSkeleton.isPresent());
        assertEquals(
                normalize.apply("export type StringOrNumber = string | number"),
                normalize.apply(stringOrNumberSkeleton.get()));

        Optional<String> greetMethodSkeleton = analyzer.getSkeleton("Greeter.greet");
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
                normalize.apply(analyzer.getSkeleton("globalFunc").orElse("")));
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
        Optional<String> innerClassSkel = analyzer.getSkeleton("MyModule.InnerClass");
        assertTrue(innerClassSkel.isPresent());
        // When getting skeleton for a nested CU, it should be part of the parent's reconstruction.
        // The current `getSkeleton` will reconstruct from the top-level parent of that CU.
        // So `getSkeleton("MyModule$InnerClass")` should effectively return the skeleton of `MyModule` because
        // `InnerClass` is a child of `MyModule`.
        // This might be unintuitive if one expects only the InnerClass part.
        // Let's test this behavior:
        assertEquals(
                normalize.apply(expectedMyModuleSkeleton),
                normalize.apply(innerClassSkel.get()),
                "getSkeleton for nested class should return the reconstructed parent skeleton.");

        Optional<String> innerTypeAliasSkelViaParent = analyzer.getSkeleton("MyModule.InnerTypeAlias");
        assertTrue(
                innerTypeAliasSkelViaParent.isPresent(),
                "Skeleton for MyModule.InnerTypeAlias should be part of MyModule's skeleton");
        assertEquals(
                normalize.apply(expectedMyModuleSkeleton),
                normalize.apply(innerTypeAliasSkelViaParent.get()),
                "getSkeleton for nested type alias should return reconstructed parent skeleton.");

        Set<CodeUnit> declarations = analyzer.getDeclarationsInFile(moduleTsFile);
        // TODO: capture nested modules
        assertTrue(declarations.contains(CodeUnit.cls(moduleTsFile, "", "MyModule.NestedNamespace.DeeperClass")));
        assertTrue(declarations.contains(CodeUnit.field(moduleTsFile, "", "MyModule.InnerTypeAlias")));
        assertTrue(declarations.contains(CodeUnit.field(moduleTsFile, "", "MyModule.NestedNamespace.DeepType")));
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
        Set<CodeUnit> declarations = analyzer.getDeclarationsInFile(advancedTsFile);
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
        Optional<String> dollarSkeleton = analyzer.getSkeleton("_module_.$");
        assertTrue(dollarSkeleton.isPresent(), "Should be able to get skeleton for ambient var $");
        assertEquals(normalize.apply("declare var $: any"), normalize.apply(dollarSkeleton.get()));

        Optional<String> fetchSkeleton = analyzer.getSkeleton("fetch");
        assertTrue(fetchSkeleton.isPresent(), "Should be able to get skeleton for ambient function fetch");
        assertEquals(
                normalize.apply("declare function fetch(url:string): Promise<any>;"),
                normalize.apply(fetchSkeleton.get()));

        Optional<String> thirdPartySkeleton = analyzer.getSkeleton("ThirdPartyLib");
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
        Optional<String> greetSource = analyzer.getMethodSource("Greeter.greet", true);
        assertTrue(greetSource.isPresent());
        assertEquals(
                normalize.apply("greet(): string {\n    return \"Hello, \" + this.greeting;\n}"),
                normalize.apply(greetSource.get()));

        Optional<String> constructorSource = analyzer.getMethodSource("Greeter.constructor", true);
        assertTrue(constructorSource.isPresent());
        assertEquals(
                normalize.apply("constructor(message: string) {\n    this.greeting = message;\n}"),
                normalize.apply(constructorSource.get()));

        // From Vars.ts (arrow function)
        Optional<String> arrowSource = analyzer.getMethodSource("anArrowFunc", true);
        assertTrue(arrowSource.isPresent());
        assertEquals(
                normalize.apply("const anArrowFunc = (msg: string): void => {\n    console.log(msg);\n};"),
                normalize.apply(arrowSource.get()));

        // From Advanced.ts (async named function)
        Optional<String> asyncNamedSource = analyzer.getMethodSource("asyncNamedFunc", true);
        assertTrue(asyncNamedSource.isPresent());
        assertEquals(
                normalize.apply(
                        "export async function asyncNamedFunc(param: number): Promise<void> {\n    await Promise.resolve();\n    console.log(param);\n}"),
                normalize.apply(asyncNamedSource.get()));

        // Test getMethodSource for overloaded function (processInput from Advanced.ts)
        // It should return all signatures and the implementation concatenated.
        Optional<String> overloadedSource = analyzer.getMethodSource("processInput", true);
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
    void testAmbientDeclarations() {
        // Dedicated test for ambient declarations (declare statements)

        ProjectFile advancedTsFile = new ProjectFile(project.getRoot(), "Advanced.ts");
        Map<CodeUnit, String> skeletons = analyzer.getSkeletons(advancedTsFile);
        Set<CodeUnit> declarations = analyzer.getDeclarationsInFile(advancedTsFile);

        // Test 1: Ambient variable declaration
        CodeUnit dollarVar = CodeUnit.field(advancedTsFile, "", "_module_.$");
        assertTrue(skeletons.containsKey(dollarVar), "Ambient var $ should be captured");
        assertEquals(
                normalize.apply("declare var $: any"),
                normalize.apply(skeletons.get(dollarVar)),
                "Ambient var should have correct skeleton with 'declare var' keywords");

        // Test 2: Ambient function declaration with proper 'function' keyword
        CodeUnit fetchFunc = CodeUnit.fn(advancedTsFile, "", "fetch");
        assertTrue(skeletons.containsKey(fetchFunc), "Ambient function fetch should be captured");
        assertEquals(
                normalize.apply("declare function fetch(url:string): Promise<any>;"),
                normalize.apply(skeletons.get(fetchFunc)),
                "Ambient function should include 'function' keyword in skeleton");

        // Test 3: Ambient namespace with contents
        CodeUnit thirdPartyNamespace = CodeUnit.cls(advancedTsFile, "", "ThirdPartyLib");
        assertTrue(skeletons.containsKey(thirdPartyNamespace), "Ambient namespace should be captured");

        String expectedNamespace =
                """
            declare namespace ThirdPartyLib {
              doWork(): void;
              interface LibOptions {
              }
            }""";
        assertEquals(
                normalize.apply(expectedNamespace),
                normalize.apply(skeletons.get(thirdPartyNamespace)),
                "Ambient namespace should contain all its member declarations");

        // Test 4: Ambient namespace members are captured as separate CodeUnits
        CodeUnit doWorkFunc = CodeUnit.fn(advancedTsFile, "", "ThirdPartyLib.doWork");
        assertTrue(
                declarations.contains(doWorkFunc),
                "Function inside ambient namespace should be captured as separate CodeUnit");

        CodeUnit libOptionsInterface = CodeUnit.cls(advancedTsFile, "", "ThirdPartyLib.LibOptions");
        assertTrue(
                declarations.contains(libOptionsInterface),
                "Interface inside ambient namespace should be captured as separate CodeUnit");

        // Test 5: No duplicate captures
        long ambientVarCount = declarations.stream()
                .filter(cu -> cu.kind() == CodeUnitType.FIELD && cu.identifier().equals("$"))
                .count();
        assertEquals(1, ambientVarCount, "Ambient variable should only be captured once");

        long ambientFuncCount = declarations.stream()
                .filter(cu ->
                        cu.kind() == CodeUnitType.FUNCTION && cu.identifier().equals("fetch"))
                .count();
        assertEquals(1, ambientFuncCount, "Ambient function should only be captured once");

        long ambientNamespaceCount = declarations.stream()
                .filter(cu -> cu.kind() == CodeUnitType.CLASS && cu.identifier().equals("ThirdPartyLib"))
                .count();
        assertEquals(1, ambientNamespaceCount, "Ambient namespace should only be captured once");

        // Test 6: Different types of ambient declarations
        // Verify we can handle: declare var, declare function, declare namespace
        // Future: could add declare class, declare interface, declare enum, declare module
        assertTrue(skeletons.containsKey(dollarVar), "declare var is supported");
        assertTrue(skeletons.containsKey(fetchFunc), "declare function is supported");
        assertTrue(skeletons.containsKey(thirdPartyNamespace), "declare namespace is supported");
    }

    @Test
    @DisabledOnOs(
            value = OS.WINDOWS,
            disabledReason = "TreeSitter byte offset alignment issues on Windows due to line ending differences")
    void testGetClassSource() throws IOException {
        String greeterSource =
                normalize.apply(analyzer.getClassSource("Greeter", true).get());
        assertNotNull(greeterSource);
        assertTrue(greeterSource.startsWith("export class Greeter"));
        assertTrue(greeterSource.contains("greeting: string;"));
        assertTrue(greeterSource.contains("greet(): string {"));
        assertTrue(greeterSource.endsWith("}"));

        // Test with Point interface - could be from Hello.ts or Advanced.ts
        String pointSource =
                normalize.apply(analyzer.getClassSource("Point", true).get());
        assertNotNull(pointSource);
        assertTrue(
                pointSource.contains("x: number") && pointSource.contains("y: number"),
                "Point should have x and y properties");
        assertTrue(pointSource.endsWith("}"));

        // Handle both possible Point interfaces
        if (pointSource.contains("move(dx: number, dy: number): void")) {
            // This is the comprehensive Hello.ts Point interface
            assertTrue(pointSource.startsWith("export interface Point"));
            assertTrue(pointSource.contains("label?: string"));
            assertTrue(pointSource.contains("readonly originDistance?: number"));
        } else {
            // This is the minimal Advanced.ts Point interface
            assertTrue(pointSource.startsWith("interface Point"));
            assertFalse(pointSource.contains("export"));
        }
    }

    @Test
    @DisabledOnOs(
            value = OS.WINDOWS,
            disabledReason = "TreeSitter byte offset alignment issues on Windows due to line ending differences")
    void testCodeUnitEqualityFixed() throws IOException {
        // Test that verifies the CodeUnit equality fix prevents byte range corruption
        var project = TestProject.createTestProject("testcode-ts", Languages.TYPESCRIPT);
        var analyzer = new TypescriptAnalyzer(project);

        // Find both Point interfaces from different files
        List<CodeUnit> allPointInterfaces = analyzer.getTopLevelDeclarations().values().stream()
                .flatMap(List::stream)
                .filter(cu -> cu.fqName().equals("Point") && cu.isClass())
                .toList();

        // Should find Point interfaces from both Hello.ts and Advanced.ts
        assertEquals(2, allPointInterfaces.size(), "Should find Point interfaces in both Hello.ts and Advanced.ts");

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
        String pointSource = analyzer.getClassSource("Point", true).get();

        // The source should be a valid Point interface, not corrupted
        assertFalse(pointSource.contains("MyParameterDecorator"), "Should not contain decorator function text");
        assertTrue(
                pointSource.trim().startsWith("interface Point")
                        || pointSource.trim().startsWith("export interface Point"),
                "Should start with interface declaration");
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
            assertFalse(analyzer.getDeclarationsInFile(tsFile).isEmpty(), "TypeScript file should have declarations");
            assertFalse(analyzer.getDeclarationsInFile(tsxFile).isEmpty(), "TSX file should have declarations");

            // JavaScript files should have no declarations (empty because they're ignored)
            assertTrue(
                    analyzer.getDeclarationsInFile(jsFile).isEmpty(),
                    "JavaScript file should have no declarations in TypeScript analyzer");
            assertTrue(
                    analyzer.getDeclarationsInFile(jsxFile).isEmpty(),
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
        Set<CodeUnit> declarations = analyzer.getDeclarationsInFile(javaFile);

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
        Optional<String> userServiceSource = analyzer.getClassSource("UserService", true);
        assertTrue(userServiceSource.isPresent(), "UserService class should be found");

        String normalizedService = normalize.apply(userServiceSource.get());
        assertTrue(
                normalizedService.contains("Service class for user management")
                        || normalizedService.contains("@class UserService"),
                "Class source should include JSDoc class annotation");
        assertTrue(normalizedService.contains("class UserService"), "Class source should include class definition");

        // Test method with comprehensive JSDoc annotations
        Optional<String> getUserByIdSource = analyzer.getMethodSource("UserService.getUserById", true);
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
        Optional<String> getUserDeprecatedSource = analyzer.getMethodSource("UserService.getUser", true);
        assertTrue(getUserDeprecatedSource.isPresent(), "getUser deprecated method should be found");

        String normalizedDeprecated = normalize.apply(getUserDeprecatedSource.get());
        assertTrue(
                normalizedDeprecated.contains("@deprecated") || normalizedDeprecated.contains("deprecated method"),
                "Deprecated method source should include deprecation annotation");
        assertTrue(normalizedDeprecated.contains("async getUser"), "Method source should include method definition");

        // Test static method with annotations
        Optional<String> validateConfigSource = analyzer.getMethodSource("UserService.validateConfig", true);
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
        Optional<String> repositorySource = analyzer.getClassSource("Repository", true);
        assertTrue(repositorySource.isPresent(), "Repository generic class should be found");

        String normalizedRepo = normalize.apply(repositorySource.get());
        assertTrue(
                normalizedRepo.contains("@template") || normalizedRepo.contains("Generic repository pattern"),
                "Generic class source should include template annotations");
        assertTrue(normalizedRepo.contains("class Repository"), "Class source should include class definition");

        // Test method with experimental annotation
        Optional<String> batchProcessSource = analyzer.getMethodSource("Repository.batchProcess", true);
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
        Optional<String> processDataSource = analyzer.getMethodSource("processData", true);
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
        Optional<String> fetchWithRetrySource = analyzer.getMethodSource("fetchWithRetry", true);
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
        Optional<String> userConfigSource = analyzer.getClassSource("UserConfig", true);
        if (userConfigSource.isPresent()) {
            String normalizedConfig = normalize.apply(userConfigSource.get());
            assertTrue(
                    normalizedConfig.contains("User configuration")
                            || normalizedConfig.contains("@since")
                            || normalizedConfig.contains("@category"),
                    "Interface source should include JSDoc annotations");
        }

        // Test enum with annotations
        Optional<String> userRoleSource = analyzer.getClassSource("UserRole", true);
        if (userRoleSource.isPresent()) {
            String normalizedRole = normalize.apply(userRoleSource.get());
            assertTrue(
                    normalizedRole.contains("@enum")
                            || normalizedRole.contains("User role enumeration")
                            || normalizedRole.contains("Standard user access"),
                    "Enum source should include enum and member annotations");
        }

        // Test User interface with member annotations
        Optional<String> userSource = analyzer.getClassSource("User", true);
        if (userSource.isPresent()) {
            String normalizedUser = normalize.apply(userSource.get());
            assertTrue(
                    normalizedUser.contains("User entity")
                            || normalizedUser.contains("@interface")
                            || normalizedUser.contains("Unique user identifier"),
                    "User interface should include interface and member annotations");
        }
    }
}
