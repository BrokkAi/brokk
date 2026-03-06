#!/bin/bash

# Script to find the most recent commit that compiles successfully.
# Usage: ./scripts/find-compilable-commit.sh <START_SHA> [MAX_RETRIES] [--restore]

set -e

RESTORE=false
BUILD_CMD=(./gradlew compileJava)
ARGS=()

while [[ $# -gt 0 ]]; do
    case $1 in
        --restore|--no-stay)
            RESTORE=true
            shift
            ;;
        --stay)
            RESTORE=false
            shift
            ;;
        --)
            shift
            if [[ $# -gt 0 ]]; then
                BUILD_CMD=("$@")
            fi
            break
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

# Function to restore initial state if --restore is provided
cleanup() {
    if [ "$RESTORE" = true ]; then
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

    echo "Running ${BUILD_CMD[*]}..." >&2
    if "${BUILD_CMD[@]}"; then
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
