namespace ns {
    void free_func(int x) {
        // int overload
    }

    void free_func(double x) {
        // double overload
    }

    class C {
    public:
        void method(int x) {
            // int method
        }

        void method(double x) {
            // double method
        }
    };
}
