#ifndef DUPLICATES_H
#define DUPLICATES_H

// Test file for C++ duplicate CodeUnit handling
// This file intentionally contains various duplicate patterns that TreeSitter sees
// but a C++ compiler would handle via preprocessing

// ====================
// 1. Forward declaration + full definition
// ====================

// Forward declaration (first occurrence)
class ForwardDeclaredClass;

// Full definition (duplicate)
class ForwardDeclaredClass {
public:
    void method();
    int field;
};

// ====================
// 2. Preprocessor conditional duplicates
// ====================

#ifdef VERSION_1
class ConditionalClass {
public:
    void versionOneMethod();
};
#endif

#ifdef VERSION_2
// This is a duplicate in TreeSitter's view (it parses both branches)
class ConditionalClass {
public:
    void versionTwoMethod();
};
#endif

// ====================
// 3. Function overloads (not technically duplicates, but same name)
// ====================

// Function overload with int parameter
void overloadedFunction(int x) {
    // Implementation for int overload
}

// Function overload with double parameter
void overloadedFunction(double x) {
    // Implementation for double overload
}

// Function overload with two int parameters
void overloadedFunction(int x, int y) {
    // Implementation for two-int overload
}

// ====================
// 4. Template specializations
// ====================

// Primary template
template<typename T>
class TemplateClass {
    T value;
};

// Specialization (duplicate name in TreeSitter)
template<>
class TemplateClass<int> {
    int value;
    int extra;
};

// ====================
// 5. Multiple header inclusion simulation
// (In real code, this would be in separate files with header guards)
// ====================

struct Point {
    int x;
    int y;
};

// Simulating re-inclusion (TreeSitter sees this even with header guards)
#ifndef POINT_ALREADY_DEFINED
#define POINT_ALREADY_DEFINED
struct Point {
    int x;
    int y;
};
#endif

// ====================
// 6. Namespace duplicates
// ====================

namespace TestNamespace {
    class NamespacedClass {
        void method();
    };
}

// Reopening namespace (C++ allows this, TreeSitter might see as duplicate)
namespace TestNamespace {
    class NamespacedClass {
        void anotherMethod();
    };
}

#endif // DUPLICATES_H
