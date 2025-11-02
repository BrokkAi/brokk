#ifndef QUALIFIERS_EXTRA_H
#define QUALIFIERS_EXTRA_H

// Test fixture for extended C++ qualifiers: volatile, const volatile, &&, and noexcept conditions

class QualifiersExtra {
public:
    // Volatile qualifier
    void f() volatile;

    // Const volatile qualifier
    void f() const volatile;

    // Rvalue reference qualifier
    void f() &&;

    // Two noexcept overloads differing only by noexcept condition
    void h() noexcept(true);
    void h() noexcept(false);

    // Reference/pointer type variations to distinguish overloads
    void refPtrTest(int val);
    void refPtrTest(int& ref);
    void refPtrTest(int* ptr);
    void refPtrTest(const int& constRef);
    void refPtrTest(int&& rvalueRef);

    // Edge-case noexcept expressions with nested parentheses
    void noexceptEdge1() noexcept(noexcept(int()));
    void noexceptEdge2() noexcept(sizeof(int) < 8);
    void noexceptEdge3() noexcept  (  true  );  // Multiple spaces

    // Volatile with multiple parameters
    void multiParam(int a, double b) volatile;
    void multiParam(int a, double b) const volatile;
    void multiParam(int a, double b, char* c) const volatile &&;

private:
    int value;
};

// Out-of-class definitions to ensure analyzer sees both declaration and definition forms
inline void QualifiersExtra::f() volatile {
    // Volatile member function definition
}

inline void QualifiersExtra::h() noexcept(true) {
    // Noexcept(true) member function definition
}

#endif // QUALIFIERS_EXTRA_H
