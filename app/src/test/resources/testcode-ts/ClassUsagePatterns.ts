// Test file to verify TypeScript class usage patterns

export class BaseClass {
    static staticMethod(): string {
        return "static";
    }

    instanceMethod(): string {
        return "instance";
    }
}

interface IBase {
    processBase(input: BaseClass): BaseClass;
}

class ExtendedClass extends BaseClass implements IBase {
    // Field with type annotation
    private field: BaseClass;

    constructor(param: BaseClass) {
        super();

        // Constructor call
        this.field = new BaseClass();

        // Static access
        const result = BaseClass.staticMethod();
    }

    // Method with type annotations
    processBase(input: BaseClass): BaseClass {
        return input;
    }

    // Generics
    getList(): Array<BaseClass> {
        return [new BaseClass()];
    }

    // Arrow function with return type
    getter = (): BaseClass => {
        return this.field;
    }
}

// Variable with type annotation
const myBase: BaseClass = new BaseClass();

// Generic array
const list: Array<BaseClass> = [];

// Import/export
export { ExtendedClass };
