#!/usr/bin/env bash

set -euo pipefail

# Check for required commands
for cmd in jq curl; do
    if ! command -v "$cmd" &> /dev/null; then
        echo "Error: $cmd is required but not installed."
        exit 1
    fi
done

VERSION="${1:-}"
CATALOG_FILE="${2:-jbang-catalog.json}"
MAX_VERSIONS="${3:-3}"
REPO_SLUG_OVERRIDE="${4:-}"

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
            echo "Usage: $0 [version] [catalog-file] [max-versions] [repo-slug]"
            echo "Example: $0 0.12.4-M1"
            echo "Example: $0 0.12.4-M1 jbang-catalog.json 3 myuser/mybrokk"
            echo "If no version is provided, the latest version-like git tag will be used"
            echo "Version tags should start with a digit (e.g., 0.12.4) or 'v' + digit (e.g., v0.12.4)"
            exit 1
        fi
    else
        echo "Error: No version specified and not in a git repository"
        echo "Usage: $0 [version] [catalog-file] [max-versions] [repo-slug]"
        echo "Example: $0 0.12.4-M1"
        echo "Example: $0 0.12.4-M1 jbang-catalog.json 3 myuser/mybrokk"
        exit 1
    fi
fi

if [ ! -f "$CATALOG_FILE" ]; then
    echo "Error: Catalog file '$CATALOG_FILE' not found"
    exit 1
fi

echo "Updating JBang catalog for version $VERSION..."

REPO_SLUG="BrokkAi/brokk-releases"
echo "Using repository for releases: $REPO_SLUG"

# Create the new JAR URL
JAR_URL="https://github.com/${REPO_SLUG}/releases/download/${VERSION}/brokk-${VERSION}.jar"

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

# Create new entry for this version
NEW_ENTRY=$(jq -n --arg version "brokk-$VERSION" --arg url "$JAR_URL" '{
    ($version): {
        "script-ref": $url,
        "java": "21",
        "main": "ai.brokk.cli.BrokkCli",
        "java-options": ["--enable-native-access=ALL-UNNAMED"]
    }
}')

# Get all available versions and group by generation (major.minor)
ALL_VERSIONS=$(git tag -l | grep -E '^v?[0-9]' | sort -V)

# Get current version's major.minor.patch prefix
CURRENT_PREFIX=$(echo "$VERSION" | sed -E 's/^v?([0-9]+\.[0-9]+\.[0-9]+).*$/\1/')

# Get latest version from each minor series, excluding current series entirely
CANDIDATE_VERSIONS=$(echo "$ALL_VERSIONS" | \
    awk -F'.' -v current_prefix="$CURRENT_PREFIX" '
    {
        # Extract major.minor.patch prefix (pad with .0 if needed)
        if (NF >= 3) {
            prefix = $1 "." $2 "." $3
        } else if (NF == 2) {
            prefix = $1 "." $2 ".0"
        } else {
            prefix = $0 ".0.0"
        }
        # Skip versions from the current series
        if (prefix != current_prefix) {
            # Store the latest version for each prefix series
            versions[prefix] = $0
        }
    }
    END {
        # Output all series, sorted by version
        for (prefix in versions) {
            print versions[prefix]
        }
    }' | sort -rV)

# Filter to only versions whose JARs actually exist in brokk-releases
VERSIONS_TO_KEEP=""
KEPT=0
NEEDED=$((MAX_VERSIONS - 1))
for V in $CANDIDATE_VERSIONS; do
    if [ "$KEPT" -ge "$NEEDED" ]; then
        break
    fi
    V_URL="https://github.com/${REPO_SLUG}/releases/download/${V}/brokk-${V}.jar"
    V_STATUS=$(curl -s -I -L --max-time 10 -w "%{http_code}" -o /dev/null "$V_URL" 2>/dev/null || echo "000")
    if [ "$V_STATUS" = "200" ]; then
        VERSIONS_TO_KEEP="${VERSIONS_TO_KEEP}${V}"$'\n'
        KEPT=$((KEPT + 1))
        echo "  OK: $V JAR exists"
    else
        echo "  SKIP: $V JAR not found (HTTP $V_STATUS)"
    fi
done


# Process the catalog: update main alias, keep the most recent N-1 other versions
jq --arg url "$JAR_URL" --arg new_version "brokk-$VERSION" --arg repo_slug "$REPO_SLUG" --argjson versions_to_keep "$(echo "$VERSIONS_TO_KEEP" | jq -R -s 'split("\n") | map(select(length > 0))')" '
    # Update main brokk alias to point to new version
    .aliases.brokk."script-ref" = $url |
    .aliases.brokk."main" = "ai.brokk.cli.BrokkCli" |
    .aliases.brokk."java-options" = ["--enable-native-access=ALL-UNNAMED"] |
    del(.aliases.brokk.dependencies) |
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
    # Rebuild the aliases object
    .aliases = ({"brokk": .aliases.brokk} + $version_aliases)
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
