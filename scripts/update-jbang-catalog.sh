#!/usr/bin/env bash

set -euo pipefail

# Check for required commands
for cmd in jq curl; do
    if ! command -v "$cmd" &> /dev/null; then
        echo "Error: $cmd is required but not installed."
        exit 1
    fi
done

VERSION="$1"
CATALOG_FILE="${2:-jbang-catalog.json}"
MAX_VERSIONS="${3:-3}"

if [ -z "$VERSION" ]; then
    echo "Usage: $0 <version> [catalog-file] [max-versions]"
    echo "Example: $0 0.12.4-M1"
    exit 1
fi

if [ ! -f "$CATALOG_FILE" ]; then
    echo "Error: Catalog file '$CATALOG_FILE' not found"
    exit 1
fi

echo "Updating JBang catalog for version $VERSION..."

# Create the new JAR URL
JAR_URL="https://github.com/BrokkAi/brokk/releases/download/${VERSION}/brokk-${VERSION}.jar"

# Check if the JAR URL exists
echo "Checking if JAR exists at: $JAR_URL"
# Use --fail-with-body for better error handling across curl versions
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -L --max-time 30 "$JAR_URL" 2>/dev/null || echo "000")

if [ "$HTTP_STATUS" != "200" ]; then
    echo "Error: JAR not found at $JAR_URL (HTTP status: $HTTP_STATUS)"
    echo "Please ensure the release has been created and the JAR has been uploaded."
    exit 1
fi

echo "✓ JAR confirmed to exist at $JAR_URL"

# Create new entry for this version
NEW_ENTRY=$(jq -n --arg version "brokk-$VERSION" --arg url "$JAR_URL" '{
    ($version): {
        "script-ref": $url,
        "java": "21",
        "java-options": ["--add-modules=jdk.incubator.vector"]
    }
}')

# Process the catalog: update main alias, keep previous main + 2 other previous versions
jq --arg url "$JAR_URL" --arg new_version "brokk-$VERSION" --argjson max "$MAX_VERSIONS" '
    # Extract the current main version from its URL to create a versioned alias
    (.aliases.brokk."script-ref" | match(".*/download/([^/]+)/.*").captures[0].string) as $current_main_version |
    # Update main brokk alias to point to new version
    .aliases.brokk."script-ref" = $url |
    # Get existing versioned aliases (excluding main "brokk" and the new version)
    ([.aliases | to_entries | .[] |
      select(.key | startswith("brokk-")) |
      select(.key != "brokk") |
      select(.key != $new_version)] |
     sort_by(.key | gsub("brokk-"; "") | split(".") | map(tonumber? // 0)) |
     .[-($max-1):]) as $previous |
    # Create previous main version alias
    ("brokk-" + $current_main_version) as $previous_main_key |
    # Rebuild the aliases object with proper structure
    .aliases = (
        {"brokk": .aliases.brokk} +
        {($previous_main_key): {
            "script-ref": ("https://github.com/BrokkAi/brokk/releases/download/" + $current_main_version + "/brokk-" + $current_main_version + ".jar"),
            "java": "21",
            "java-options": ["--add-modules=jdk.incubator.vector"]
        }} +
        (($previous | reverse) | from_entries)
    )
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
