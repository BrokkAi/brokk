#!/bin/bash
#
# Run the Brokk ACP Server
#
# Usage: ./scripts/run-acp-server.sh [args...]
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

exec "$PROJECT_ROOT/gradlew" -p "$PROJECT_ROOT" runAcpServer "$@"
