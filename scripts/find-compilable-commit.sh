#!/bin/bash

# Script to find the most recent commit that compiles successfully.
# Usage: ./scripts/find-compilable-commit.sh <START_SHA> [MAX_RETRIES] [--restore] [--] [BUILD_CMD...]
#
# Important: The script prints ONLY the successful SHA to stdout. All other output (including build output)
# goes to stderr so callers can safely use command substitution, e.g. RESOLVED_SHA=$(...).

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
# Maintain compatibility with MAX_RETRIES if MAX_ATTEMPTS is not set
MAX_ATTEMPTS=${MAX_ATTEMPTS:-${MAX_RETRIES:-${ARGS[1]:-5}}}

# Record the initial state (branch or SHA) to allow returning if necessary.
INITIAL_STATE=$(git rev-parse --abbrev-ref HEAD)
if [ "$INITIAL_STATE" == "HEAD" ]; then
    INITIAL_STATE=$(git rev-parse HEAD)
fi

cleanup() {
    if [ "$RESTORE" = true ]; then
        echo "Restoring initial state: $INITIAL_STATE" >&2
        git checkout -q "$INITIAL_STATE"
    fi
}
trap cleanup EXIT

CURRENT_SHA=$(git rev-parse "$START_SHA")
ATTEMPTS_LEFT=$MAX_ATTEMPTS

echo "Starting search from $CURRENT_SHA with up to $MAX_ATTEMPTS attempts..." >&2

while [ "$ATTEMPTS_LEFT" -gt 0 ]; do
    ATTEMPTS_LEFT=$((ATTEMPTS_LEFT - 1))
    echo "Checking out $CURRENT_SHA... ($ATTEMPTS_LEFT attempts remaining after this)" >&2
    git checkout -q "$CURRENT_SHA"

    echo "Running ${BUILD_CMD[*]}..." >&2
    if "${BUILD_CMD[@]}" 1>&2; then
        echo "Successfully compiled at $CURRENT_SHA" >&2
        echo "$CURRENT_SHA"
        exit 0
    else
        echo "Compilation failed at $CURRENT_SHA" >&2

        if [ "$ATTEMPTS_LEFT" -eq 0 ]; then
            break
        fi

        PARENT_SHA=$(git rev-parse "$CURRENT_SHA^" 2>/dev/null || true)

        if [ -z "$PARENT_SHA" ]; then
            echo "No parent commit found. Reached beginning of history." >&2
            break
        fi

        CURRENT_SHA=$PARENT_SHA
    fi
done

echo "Error: Could not find a compilable commit within the limit of $MAX_ATTEMPTS attempts." >&2
exit 1
