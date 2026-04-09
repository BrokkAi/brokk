#!/usr/bin/env bash

set -euo pipefail

# Check for required commands
for cmd in jq curl; do
    if ! command -v "$cmd" &> /dev/null; then
        echo "Error: $cmd is required but not installed."
        exit 1
    fi
done

VERSION=""
CATALOG_FILE="jbang-catalog.json"
MAX_VERSIONS=3
REPO_SLUG="BrokkAi/brokk-releases"

usage() {
    echo "Usage: $0 [options]"
    echo ""
    echo "Options:"
    echo "  -v, --version <ver>      Version to release (e.g. 0.20.4.7). Defaults to latest git tag."
    echo "  -c, --catalog <file>     Catalog file to update (default: jbang-catalog.json)"
    echo "  -m, --max <num>          Max number of historical versions to keep (default: 3)"
    echo "  -r, --repo <slug>        GitHub repository slug (default: BrokkAi/brokk-releases)"
    echo "  -h, --help               Show this help message"
    echo ""
    exit 1
}

while [[ $# -gt 0 ]]; do
    case $1 in
        -v|--version)
            VERSION="$2"
            shift 2
            ;;
        -c|--catalog)
            CATALOG_FILE="$2"
            shift 2
            ;;
        -m|--max)
            MAX_VERSIONS="$2"
            shift 2
            ;;
        -r|--repo)
            REPO_SLUG="$2"
            shift 2
            ;;
        -h|--help)
            usage
            ;;
        *)
            # Support positional version if no flag is used
            if [[ -z "$VERSION" && ! "$1" =~ ^- ]]; then
                VERSION="$1"
                shift
            else
                echo "Unknown option: $1"
                usage
            fi
            ;;
    esac
done

if [ -z "$VERSION" ]; then
    # Try to get the latest version-like git tag from remote (starts with digit or 'v' followed by digit)
    if command -v git &> /dev/null && git rev-parse --git-dir &> /dev/null; then
        echo "Fetching remote tags..."
        git fetch --tags origin 2>/dev/null || echo "Warning: Could not fetch remote tags, using local tags"
        VERSION=$(git tag -l | grep -E '^v?[0-9]' | sort -V | tail -n1)
        if [ -n "$VERSION" ]; then
            echo "No version specified, using latest version tag: $VERSION"
        else
            echo "Error: No version specified and no version-like git tags found"
            usage
        fi
    else
        echo "Error: No version specified and not in a git repository"
        usage
    fi
fi

if [ ! -f "$CATALOG_FILE" ]; then
    echo "Error: Catalog file '$CATALOG_FILE' not found"
    exit 1
fi

echo "Updating JBang catalog for version $VERSION..."
echo "Using repository for releases: $REPO_SLUG"

# Create the new JAR URLs
JAR_URL="https://github.com/${REPO_SLUG}/releases/download/${VERSION}/brokk-${VERSION}.jar"
CORE_JAR_URL="https://github.com/${REPO_SLUG}/releases/download/${VERSION}/brokk-core-${VERSION}.jar"

# Check if the JAR URL exists (HEAD request only - no download)
echo "Verifying JAR exists at: $JAR_URL"
HTTP_STATUS=$(curl -s -I -L --max-time 10 -w "%{http_code}" -o /dev/null "$JAR_URL" 2>/dev/null || echo "000")

if [ "$HTTP_STATUS" != "200" ]; then
    echo "Error: JAR not found at $JAR_URL (HTTP status: $HTTP_STATUS)"
    echo "Please ensure:"
    echo "  - The release has been created on GitHub"
    echo "  - The JAR has been uploaded as a release asset"
    echo "  - The release is public (not draft)"
    echo "You can check the release at: https://github.com/${REPO_SLUG}/releases/tag/${VERSION}"
    exit 1
fi

echo "OK: JAR confirmed to exist at $JAR_URL"

# Check if the brokk-core JAR URL exists
echo "Verifying brokk-core JAR exists at: $CORE_JAR_URL"
CORE_HTTP_STATUS=$(curl -s -I -L --max-time 10 -w "%{http_code}" -o /dev/null "$CORE_JAR_URL" 2>/dev/null || echo "000")

if [ "$CORE_HTTP_STATUS" != "200" ]; then
    echo "Warning: brokk-core JAR not found at $CORE_JAR_URL (HTTP status: $CORE_HTTP_STATUS)"
    echo "brokk-core alias will not be updated"
    CORE_JAR_URL=""
else
    echo "OK: brokk-core JAR confirmed to exist at $CORE_JAR_URL"
fi

# Create new entry for this version
NEW_ENTRY=$(jq -n --arg version "brokk-$VERSION" --arg url "$JAR_URL" '{
    ($version): {
        "script-ref": $url,
        "java": "21",
        "main": "ai.brokk.cli.BrokkCli",
        "java-options": ["--enable-native-access=ALL-UNNAMED"]
    }
}')

# Get all available versions in descending order
ALL_VERSIONS=$(git tag -l | grep -E '^v?[0-9]' | sort -rV)

# Filter to only versions whose JARs actually exist in brokk-releases
# We stop as soon as we find enough valid versions
VERSIONS_TO_KEEP=""
KEPT=0
NEEDED=$((MAX_VERSIONS - 1))

echo "Searching for $NEEDED additional valid versions..."

for V in $ALL_VERSIONS; do
    if [ "$KEPT" -ge "$NEEDED" ]; then
        break
    fi

    # Skip the version we just added (it will be the main 'brokk' alias)
    if [ "$V" = "$VERSION" ]; then
        continue
    fi

    V_URL="https://github.com/${REPO_SLUG}/releases/download/${V}/brokk-${V}.jar"
    V_STATUS=$(curl -s -I -L --max-time 5 -w "%{http_code}" -o /dev/null "$V_URL" 2>/dev/null || echo "000")
    
    if [ "$V_STATUS" = "200" ]; then
        VERSIONS_TO_KEEP="${VERSIONS_TO_KEEP}${V}"$'\n'
        KEPT=$((KEPT + 1))
        echo "  OK: $V found and added to catalog"
    else
        echo "  SKIP: $V (HTTP $V_STATUS)"
    fi
done


# Process the catalog: update main aliases, keep the most recent N-1 other versions
jq --arg url "$JAR_URL" --arg core_url "$CORE_JAR_URL" --arg new_version "brokk-$VERSION" --arg repo_slug "$REPO_SLUG" --argjson versions_to_keep "$(echo "$VERSIONS_TO_KEEP" | jq -R -s 'split("\n") | map(select(length > 0))')" '
    # Update main brokk alias to point to new version
    .aliases.brokk."script-ref" = $url |
    .aliases.brokk."main" = "ai.brokk.cli.BrokkCli" |
    .aliases.brokk."java-options" = ["--enable-native-access=ALL-UNNAMED"] |
    del(.aliases.brokk.dependencies) |
    # Create brokk-headless alias
    .aliases."brokk-headless"."script-ref" = $url |
    .aliases."brokk-headless"."java" = "21" |
    .aliases."brokk-headless"."main" = "ai.brokk.executor.HeadlessExecutorMain" |
    .aliases."brokk-headless"."java-options" = ["--enable-native-access=ALL-UNNAMED"] |
    # Create brokk-core alias (if core JAR URL is available)
    (if $core_url != "" then
        .aliases."brokk-core"."script-ref" = $core_url |
        .aliases."brokk-core"."java" = "21" |
        .aliases."brokk-core"."main" = "ai.brokk.mcpserver.BrokkCoreMcpServer" |
        .aliases."brokk-core"."java-options" = ["--enable-native-access=ALL-UNNAMED"]
    else . end) |
    # Create version aliases for the versions we want to keep
    ($versions_to_keep | map({
        key: ("brokk-" + .),
        value: {
            "script-ref": ("https://github.com/" + $repo_slug + "/releases/download/" + . + "/brokk-" + . + ".jar"),
            "java": "21",
            "main": "ai.brokk.cli.BrokkCli",
            "java-options": ["--enable-native-access=ALL-UNNAMED"]
        }
    }) | from_entries) as $version_aliases |
    # Rebuild the aliases object (preserve brokk-core if it exists)
    .aliases = ({"brokk": .aliases.brokk, "brokk-headless": .aliases."brokk-headless"} + (if .aliases."brokk-core" then {"brokk-core": .aliases."brokk-core"} else {} end) + $version_aliases)
' "$CATALOG_FILE" > "${CATALOG_FILE}.tmp"

# Move the updated file back
mv "${CATALOG_FILE}.tmp" "$CATALOG_FILE"

# Show what was done
VERSIONED_ALIASES=$(jq -r '[.aliases | to_entries | .[] | select(.key | startswith("brokk-")) | .key] | sort | join(", ")' "$CATALOG_FILE")
echo "Updated catalog:"
echo "- Main 'brokk' alias now points to: $JAR_URL"
echo "- Added versioned alias: brokk-$VERSION"
echo "- Kept latest $MAX_VERSIONS versioned aliases: $VERSIONED_ALIASES"

# Ensure the script ends with a newline
exit 0
