#ifndef ADVANCED_FEATURES_H
#define ADVANCED_FEATURES_H

// Namespace with enums and unions
namespace graphics {

    // Enum declaration
    enum Color {
        RED,
        GREEN,
        BLUE,
        ALPHA
    };

    // Scoped enum (C++11)
    enum class BlendMode {
        NORMAL,
        MULTIPLY,
        SCREEN
    };

    // Union declaration
    union Pixel {
        struct {
            unsigned char r, g, b, a;
        } rgba;
        unsigned int value;
    };

    // Type alias using typedef
    typedef int PixelIndex;

    // Type alias using 'using' (C++11)
    using ColorValue = float;
    using PixelBuffer = Pixel*;

    class Renderer {
    public:
        void setColor(Color c);
        void setBlendMode(BlendMode mode);
        ColorValue normalize(int value);
    private:
        Color currentColor;
        BlendMode currentMode;
        PixelBuffer buffer;
    };
}

// Global enum outside namespace
enum Status {
    SUCCESS = 0,
    ERROR = -1,
    PENDING = 1
};

// Global union
union DataValue {
    int intVal;
    float floatVal;
    double doubleVal;
    char* stringVal;
};

// Global type aliases
typedef unsigned int uint32_t;
using String = char*;

// Nested namespace (C++17 style)
namespace ui::widgets {
    enum WidgetType {
        BUTTON,
        LABEL,
        TEXTBOX
    };

    class Widget {
    public:
        WidgetType getType();
    private:
        WidgetType type;
    };
}

#endif // ADVANCED_FEATURES_H
