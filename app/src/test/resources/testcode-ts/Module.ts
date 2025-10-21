namespace MyModule {
    export class InnerClass {
        name: string = "Inner";
        constructor() {}
        doSomething(): void {}
    }
    export function innerFunc(): void {
        const nestedArrow = () => console.log("nested");
        nestedArrow();
    }
    export const innerConst: number = 42;

    export interface InnerInterface {
        id: number;
        describe(): string;
    }
    export enum InnerEnum { A, B }
    
    export type InnerTypeAlias<V> = InnerInterface | V;

    namespace NestedNamespace {
        export class DeeperClass {}
        export type DeepType = string;
    }
}

// top-level item in same file
export class AnotherClass {}

export const topLevelArrow = (input: any): any => input;

export type TopLevelGenericAlias<K, V> = Map<K, V>;
