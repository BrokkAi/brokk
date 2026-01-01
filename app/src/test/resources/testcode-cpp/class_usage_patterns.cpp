// Test file to verify C++ class usage patterns

#include <vector>
#include "class_usage_patterns.h"

class BaseClass {
public:
    static void staticMethod() {
        // static implementation
    }

    void instanceMethod() {
        // instance implementation
    }
};

class ExtendedClass : public BaseClass {
private:
    BaseClass* field;

public:
    ExtendedClass(BaseClass* param) {
        // Constructor call
        field = new BaseClass();

        // Static access
        BaseClass::staticMethod();
    }

    // Method with type annotations
    BaseClass* processBase(BaseClass* input) {
        return input;
    }

    // Template usage
    std::vector<BaseClass*> getList() {
        return std::vector<BaseClass*>();
    }
};

void usageExample() {
    // Variable declarations
    BaseClass obj;
    BaseClass* ptr = new BaseClass();
    BaseClass& ref = obj;
}
