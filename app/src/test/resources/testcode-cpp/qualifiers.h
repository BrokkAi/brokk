struct S {
    void f() const;
    void f() &;
    void f() noexcept;
};

void S::f() const {}
void S::f() & {}
void S::f() noexcept {}
