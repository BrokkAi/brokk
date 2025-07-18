// Simulating Point for U extends Point constraint
interface Point { x: number; y: number; }

// Decorators
function MyClassDecorator(target: Function) { /* ... */ }
function MyMethodDecorator(target: any, propertyKey: string, descriptor: PropertyDescriptor) { /* ... */ }
function MyPropertyDecorator(target: any, propertyKey: string | symbol) { /* ... */ }
function MyParameterDecorator(target: Object, propertyKey: string | symbol, parameterIndex: number) { /* ... */ }


@MyClassDecorator
export class DecoratedClass<T> {
    @MyPropertyDecorator
    decoratedProperty: string = "initial";

    private _value: T;

    constructor(@MyParameterDecorator initialValue: T) {
        this._value = initialValue;
    }

    @MyMethodDecorator
    genericMethod<U extends Point>(value: T, other: U): [T, U] {
        return [value, other];
    }

    get value(): T { return this._value; }
    set value(val: T) { this._value = val; }
}

export interface GenericInterface<T, U extends Point> {
    item: T;
    point: U;
    process(input: T): U;
    new (param: T): GenericInterface<T,U>; // Constructor signature
}

export abstract class AbstractBase {
    abstract performAction(name: string): void;
    concreteMethod(): string { return "done"; }
}

export const asyncArrowFunc = async (p: Promise<string>): Promise<number> => {
    const s = await p;
    return s.length;
};

export async function asyncNamedFunc(param: number): Promise<void> {
    await Promise.resolve();
    console.log(param);
}

// Class with various modifiers for fields
export class FieldTest {
    public name: string;
    private id: number = 0; // with initializer
    protected status?: string; // optional
    readonly creationDate: Date;
    static version: string = "1.0";
    #trulyPrivateField: string = "secret"; // ECMAScript private field

    constructor(name: string) {
        this.name = name;
        // this.id = id; // id now has initializer
        this.creationDate = new Date();
    }

    public publicMethod() {}
    private privateMethod() {}
    protected protectedMethod() {}
    static staticMethod() {}
}

// Function Overloads
export function processInput(input: string): string[];
export function processInput(input: number): number[];
export function processInput(input: boolean): boolean[];
export function processInput(input: any): any[] {
    if (typeof input === "string") return [`s-${input}`];
    if (typeof input === "number") return [`n-${input}`];
    if (typeof input === "boolean") return [`b-${input}`];
    return [input];
}

// Generic Type Alias
export type Pointy<T> = { x: T, y: T };

// export default class DefaultExportedClass {
//    message: string = "default";
// }

// Ambient declarations
declare var $: any;
declare function fetch(url:string): Promise<any>;
declare namespace ThirdPartyLib {
    function doWork(): void;
    interface LibOptions {}
}
