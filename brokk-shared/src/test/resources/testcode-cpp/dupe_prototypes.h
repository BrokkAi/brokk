#ifndef DUPE_PROTOTYPES_H
#define DUPE_PROTOTYPES_H

// Two identical prototypes for the same function
void duplicated_function(int x);
void duplicated_function(int x);

// At least one definition to ensure the function is recognized
inline void duplicated_function(int x) {
    // Function body
}

#endif // DUPE_PROTOTYPES_H
