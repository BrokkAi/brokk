#!/bin/bash
#
# Run the Brokk ACP Server
#
# Usage: ./scripts/run-acp-server.sh [args...]
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

JAR_PATH="$PROJECT_ROOT/acp-server/build/libs/brokk-acp-server.jar"

if [ ! -f "$JAR_PATH" ]; then
    echo "ACP server JAR not found at $JAR_PATH"
    echo "Run './gradlew buildAcpServer' first to build it."
    exit 1
fi

exec java -jar "$JAR_PATH" "$@"
