#ifndef FUNCTION_TEMPLATES_H
#define FUNCTION_TEMPLATES_H

// Template function with variadic template params
template <class... Args>
void process(const Args&... args) {}

// Non-template overload
void process(int x) {}

// Template function with single type param
template <typename T>
void process(const T& value, int count) {}

#endif
