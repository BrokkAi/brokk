// Test file to verify JavaScript class usage patterns

class BaseClass {
    static staticMethod() {
        return "static";
    }

    instanceMethod() {
        return "instance";
    }
}

class ExtendedClass extends BaseClass {
    constructor() {
        // Constructor call via super
        super();

        // Constructor call with new
        this.instance = new BaseClass();

        // Static access
        const result = BaseClass.staticMethod();
    }

    useBaseClass(param) {
        // Parameter usage
        return param.instanceMethod();
    }
}

// Variable declaration
const myBase = new BaseClass();

// Export/import pattern
export { BaseClass };
