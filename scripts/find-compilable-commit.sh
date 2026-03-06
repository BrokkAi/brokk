#!/bin/bash

# Script to find the most recent commit that compiles successfully.
# Usage: ./scripts/find-compilable-commit.sh <START_SHA> [MAX_RETRIES] [--stay]

set -e

STAY=false
ARGS=()
while [[ $# -gt 0 ]]; do
    case $1 in
        --stay)
            STAY=true
            shift
            ;;
        *)
            ARGS+=("$1")
            shift
            ;;
    esac
done

START_SHA=${ARGS[0]:-HEAD}
MAX_RETRIES=${ARGS[1]:-5}

# Record the initial state (branch or SHA) to allow returning if necessary.
INITIAL_STATE=$(git rev-parse --abbrev-ref HEAD)
if [ "$INITIAL_STATE" == "HEAD" ]; then
    INITIAL_STATE=$(git rev-parse HEAD)
fi

# Function to restore initial state unless --stay is provided
cleanup() {
    if [ "$STAY" = false ]; then
        echo "Restoring initial state: $INITIAL_STATE" >&2
        git checkout -q "$INITIAL_STATE"
    fi
}
trap cleanup EXIT

CURRENT_SHA=$(git rev-parse "$START_SHA")
RETRIES_LEFT=$MAX_RETRIES

echo "Starting search from $CURRENT_SHA with $MAX_RETRIES retries..." >&2

while [ "$RETRIES_LEFT" -ge 0 ]; do
    echo "Checking out $CURRENT_SHA..." >&2
    git checkout -q "$CURRENT_SHA"

    echo "Running ./gradlew compileJava..." >&2
    # Use --no-daemon to ensure clean state in CI environments if needed, 
    # but standard ./gradlew is usually fine.
    if ./gradlew compileJava; then
        echo "Successfully compiled at $CURRENT_SHA" >&2
        # Print the successful SHA to stdout
        echo "$CURRENT_SHA"
        exit 0
    else
        echo "Compilation failed at $CURRENT_SHA" >&2
        
        if [ "$RETRIES_LEFT" -eq 0 ]; then
            break
        fi

        # Attempt to get the parent commit
        PARENT_SHA=$(git rev-parse "$CURRENT_SHA^" 2>/dev/null || true)
        
        if [ -z "$PARENT_SHA" ]; then
            echo "No parent commit found. Reached beginning of history." >&2
            break
        fi

        CURRENT_SHA=$PARENT_SHA
        RETRIES_LEFT=$((RETRIES_LEFT - 1))
        echo "Retries remaining: $RETRIES_LEFT" >&2
    fi
done

echo "Error: Could not find a compilable commit within the retry limit." >&2
exit 1
