struct C;

void g(void (*cb)(int, int));
void g(void (*cb)(int, int)) {}

struct C {
    void method();
};

void h(int C::* ptr);
void h(int C::* ptr) {}
