#!/bin/bash
#
# Brokk Context Hook for Claude Code
#
# Runs the Brokk analyzer (pure local, no LLM, no API key) to produce
# a project symbol index and injects it as additionalContext so Claude
# has structured code intelligence before it starts thinking.
#
# The analyzer reuses cached state from .brokk/ when available,
# so subsequent invocations are fast.

set -euo pipefail

# The plugin jar lives in the persistent data directory
HOOK_JAR="${CLAUDE_PLUGIN_DATA}/brokk-hook.jar"

if [ ! -f "$HOOK_JAR" ]; then
    # Jar not installed yet — silently skip
    exit 0
fi

# Pass stdin (hook input JSON) through to the Java process
exec java \
    --enable-native-access=ALL-UNNAMED \
    -Djava.awt.headless=true \
    -cp "$HOOK_JAR" \
    ai.brokk.claudehook.ContextHook
