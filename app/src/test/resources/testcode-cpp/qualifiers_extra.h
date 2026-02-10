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
