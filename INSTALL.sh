#!/usr/bin/env bash
#
# Installs the Brokk command using JBang with dynamic memory allocation (1/4 of available RAM).
# Safe to re-run: aborts if the command already exists.

set -euo pipefail

# Check if brokk command already exists via JBang
if command -v jbang >/dev/null 2>&1 && jbang list 2>/dev/null | grep -q '^brokk\s'; then
  echo "Command 'brokk' is already installed via JBang. Aborting."
  exit 1
fi

# Ensure jbang exists (offer to install)
if ! command -v jbang >/dev/null 2>&1; then
  read -rp "JBang is not installed. Install it now? [y/N] " reply
  if [[ "$reply" =~ ^[Yy]$ ]]; then
    # Check for curl only when we need to install JBang
    if ! command -v curl >/dev/null 2>&1; then
      echo "'curl' is required to install JBang but was not found. Please install curl and re-run."
      exit 1
    fi
    echo "Installing JBang …"
    curl -Ls https://sh.jbang.dev | bash -s - app setup
    # Make JBang available for this script run (might already be on PATH after installer)
    export PATH="$HOME/.jbang/bin:$PATH"
  else
    echo "Cannot continue without JBang. Aborting."
    exit 1
  fi
fi

# Determine total memory (bytes) and compute 1/4 of it
mem_bytes=0
if [[ -r /proc/meminfo ]]; then                                   # Linux
  mem_kb=$(grep MemTotal /proc/meminfo | awk '{print $2}')
  mem_bytes=$((mem_kb * 1024))
elif command -v sysctl >/dev/null 2>&1; then                      # macOS/BSD
  mem_bytes=$(sysctl -n hw.memsize)
fi

if [[ "$mem_bytes" -eq 0 ]]; then
  echo "Unable to detect total RAM. Defaulting to -Xmx4G."
  xmx="4G"
else
  quarter_mb=$((mem_bytes / 4 / 1024 / 1024))
  if (( quarter_mb >= 1024 )); then
    # Round to nearest GB
    quarter_gb=$(( (quarter_mb + 512) / 1024 ))
    xmx="${quarter_gb}G"
  else
    xmx="${quarter_mb}M"
  fi
fi

echo "Installing brokk command with -Xmx${xmx}..."
jbang app install --name brokk --java-options "-Xmx${xmx}" brokk@brokkai/brokk

echo "Success! The 'brokk' command is now available in your PATH."
echo "You can run it from any terminal with: brokk"
