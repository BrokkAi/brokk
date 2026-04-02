#ifndef OPERATORS_H
#define OPERATORS_H

// Test fixture for operator overloads (member and non-member)
struct S {
    // member call operator, const-qualified
    void operator()() const;
};

// out-of-class definition for member operator()
inline void S::operator()() const {
    // body
}

// Non-member operator== declaration and definition
bool operator==(int a, int b);

inline bool operator==(int a, int b) {
    return a == b;
}

#endif // OPERATORS_H
