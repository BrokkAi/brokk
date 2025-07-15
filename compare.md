  Current Branch vs origin/typescript TypescriptAnalyzerTest.java

  Major Differences:

  1. Dynamic Query File Selection (Current Branch Only)
    - Lines 44, 142, 179, 255, 345, 396, 446, 498: Current branch calls TypescriptAnalyzer.setQueryFile("treesitter/typescript-unified.scm") in
   every test
    - Origin: Uses default query file only (no dynamic switching)
  2. Test Expectations - Skeleton Content

  2. Arrow Functions:
    - Current: "const anArrowFunc = (msg: string): void => {\n    console.log(msg);\n};" (full body)
    - Origin: "const anArrowFunc = (msg: string): void => { ... }" (abbreviated)

  TopLevel Arrow:
    - Current: "export const topLevelArrow = (input: any): any => input;" (full body)
    - Origin: "export const topLevelArrow = (input: any): any => { ... }" (abbreviated)

  Enum Values:
    - Current: "Red,\nBlue" (simple enum)
    - Origin: "Red,\nGreen = 3,\nBlue" (enum with explicit values)
  3. Test Expectations - Semicolons
    - Current: All const declarations include semicolons consistently
    - Origin: Mixed - some with semicolons, some without (inconsistent)
  4. Test Expectations - Advanced Features

  4. Class Decorators:
    - Current: Missing class-level decorators, simplified content
    - Origin: Full decorator support (@MyClassDecorator, method type parameters <U extends Point>)

  Interface Constructors:
    - Current: Missing constructor signatures in interfaces
    - Origin: Includes new (param: T): GenericInterface<T,U>;

  Abstract Classes:
    - Current: Simplified abstract class handling
    - Origin: Full abstract method support (abstract performAction(name: string): void;)

  Access Modifiers:
    - Current: Missing method access modifiers
    - Origin: Full access modifier support (public publicMethod(), private privateMethod())
  5. Test Structure Differences

  5. Namespace Declarations:
    - Current: Uses CodeUnit.cls(moduleTsFile, "", "MyModule$NestedNamespace$DeeperClass")
    - Origin: Uses CodeUnit.cls(moduleTsFile, "MyModule", "NestedNamespace$DeeperClass")

  Constants Missing Semicolons:
    - Current: "export const PI: number = 3.14159;" and "export const utilityRate: number = 0.15;"
    - Origin: "export const PI: number = 3.14159" and "export const utilityRate: number = 0.15"
  6. Method Source Testing
    - Current: Simplified overloaded function formatting
    - Origin: More complex expected format with explicit "\n\n" separators

  Summary of Changes:

  The current branch represents a significantly simplified version of the TypeScript analyzer tests compared to origin/typescript. The current
  branch:

  1. Loses advanced TypeScript features like decorators, abstract methods, interface constructors, and access modifiers
  2. Uses dynamic query switching to work around limitations in the unified query
  3. Shows full arrow function bodies instead of abbreviated skeletons
  4. Has inconsistent semicolon handling that required manual test fixes
  5. Simplifies complex namespace handling

  The origin/typescript branch has much more comprehensive TypeScript language support with proper handling of advanced features that the
  current branch's unified query approach cannot properly capture.

  This suggests that the current implementation may have regressed from the original TypeScript support in favor of a unified query approach
  that doesn't handle TypeScript's complexity as well.
