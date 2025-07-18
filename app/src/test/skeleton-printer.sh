#!/bin/bash

# SkeletonPrinter - A utility to analyze and print code skeletons
# Usage: ./skeleton-printer.sh [--skeleton-only] [--no-color] <path> <language>
#
# Options:
#   --skeleton-only    Only show skeleton output, not original content
#   --no-color         Disable colored output
#
# Path can be a directory or a specific file
# Supported languages: typescript, javascript, java, python

# Parse arguments
OPTIONS=""
while [[ $# -gt 2 ]]; do
    case $1 in
        --skeleton-only|--no-color)
            OPTIONS="$OPTIONS \"$1\""
            shift
            ;;
        *)
            echo "Unknown option: $1"
            echo "Usage: $0 [--skeleton-only] [--no-color] <directory> <language>"
            exit 1
            ;;
    esac
done

if [ $# -ne 2 ]; then
    echo "Usage: $0 [--skeleton-only] [--no-color] <path> <language>"
    echo "Options:"
    echo "  --skeleton-only    Only show skeleton output, not original content"
    echo "  --no-color         Disable colored output"
    echo "Path can be a directory or a specific file"
    echo "Supported languages: typescript, javascript, java, python"
    exit 1
fi

PATH_ARG="$1"
LANGUAGE="$2"

# Check if path exists
if [ ! -e "$PATH_ARG" ]; then
    echo "Error: Path '$PATH_ARG' does not exist"
    exit 1
fi

# Check if sbt is available
if ! command -v sbt >/dev/null 2>&1; then
    echo "Error: sbt is required but not installed"
    exit 1
fi

if [ -d "$PATH_ARG" ]; then
    echo "Analyzing $LANGUAGE files in directory: $PATH_ARG"
elif [ -f "$PATH_ARG" ]; then
    echo "Analyzing $LANGUAGE file: $PATH_ARG"
fi
echo "Building and running SkeletonPrinter..."
echo

# Run the SkeletonPrinter through sbt
if [ -n "$OPTIONS" ]; then
    eval "sbt \"Test/runMain io.github.jbellis.brokk.tools.SkeletonPrinter $OPTIONS \\\"$PATH_ARG\\\" \\\"$LANGUAGE\\\"\""
else
    sbt "Test/runMain io.github.jbellis.brokk.tools.SkeletonPrinter \"$PATH_ARG\" \"$LANGUAGE\""
fi
