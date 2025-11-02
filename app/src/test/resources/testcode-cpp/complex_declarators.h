// Test cases for complex function declarators
// These test the analyzer's ability to handle function pointers in signatures

// Function returning a function pointer
int (*getHandler())(double);

// Function taking a function pointer as parameter
void process(int (*callback)(int));

// Function taking multiple function pointers
void multiCallback(int (*onSuccess)(int), void (*onError)(const char*));

// Nested: function taking function pointer and returning function pointer
void (*signal(int signum, void (*handler)(int)))(int);

// Function pointer with multiple parameters
void execute(int (*operation)(int, double, char*));

// Mix of regular and function pointer parameters
void hybrid(int value, double (*transform)(double), const char* name);

// Template-like complexity (not actually a template, just complex types)
void complexMix(int* ptr, int (*func)(int*), int** ptrPtr);
