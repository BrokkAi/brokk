#!/usr/bin/env bash
#
# Adds an alias for Brokk that allocates 1/4 of available RAM to the JVM.
# Safe to re-run: aborts if the alias already exists.

set -euo pipefail

# Choose the RC file based on the current shell
shell_name="$(basename "${SHELL:-bash}")"
rc_file="$HOME/.${shell_name}rc"

# Abort if alias already present
if grep -qE '^alias +brokk=' "$rc_file" 2>/dev/null; then
  echo "Alias 'brokk' already exists in $rc_file. Aborting."
  exit 1
fi

# Ensure curl exists
if ! command -v curl >/dev/null 2>&1; then
  echo "'curl' is required but was not found. Please install curl and re-run."
  exit 1
fi

# Ensure jbang exists (offer to install)
if ! command -v jbang >/dev/null 2>&1; then
  read -rp "JBang is not installed. Install it now? [y/N] " reply
  if [[ "$reply" =~ ^[Yy]$ ]]; then
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

alias_line="alias brokk=\"jbang run --java-options -Xmx${xmx} brokk@brokkai/brokk\""

echo "Adding alias to $rc_file:"
echo "  $alias_line"
echo "$alias_line" >> "$rc_file"

echo "Success! Re-open your terminal or run:  source \"$rc_file\""
