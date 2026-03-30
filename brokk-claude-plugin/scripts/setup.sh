#!/bin/bash
#
# SessionStart hook: ensures the Brokk hook jar is installed.
# Copies the jar from the plugin bundle to the persistent data directory
# if it's missing or the bundled version has changed.

set -euo pipefail

BUNDLED_JAR="${CLAUDE_PLUGIN_ROOT}/lib/brokk-hook.jar"
INSTALLED_JAR="${CLAUDE_PLUGIN_DATA}/brokk-hook.jar"

# If no bundled jar, nothing to do (plugin was installed without building first)
if [ ! -f "$BUNDLED_JAR" ]; then
    exit 0
fi

# Install or update if changed
if ! diff -q "$BUNDLED_JAR" "$INSTALLED_JAR" >/dev/null 2>&1; then
    mkdir -p "$CLAUDE_PLUGIN_DATA"
    cp "$BUNDLED_JAR" "$INSTALLED_JAR"
fi

exit 0
