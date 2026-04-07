class Outer {
public:
    class Inner {
    public:
        void show() {}
    };
};

int main() {
    Outer::Inner obj;
    obj.show(42); // Error: too many arguments
}