#ifndef NESTED_NS_CTOR_DTOR_H
#define NESTED_NS_CTOR_DTOR_H

// Test constructors and destructors in nested namespaces
// Validates correct fqName generation and signature handling for special member functions

namespace outer {
    namespace inner {

        class Widget {
        public:
            // Constructor overloads
            Widget();
            Widget(int size);
            Widget(int width, int height);

            // Destructor with noexcept qualifier
            ~Widget() noexcept;

        private:
            int data;
        };

        // Out-of-class definitions with full namespace qualification
        inline Widget::Widget() : data(0) {
            // Default constructor
        }

        inline Widget::Widget(int size) : data(size) {
            // Single-parameter constructor
        }

        inline Widget::Widget(int width, int height) : data(width * height) {
            // Two-parameter constructor
        }

        inline Widget::~Widget() noexcept {
            // Destructor
        }

    } // namespace inner
} // namespace outer

#endif // NESTED_NS_CTOR_DTOR_H
