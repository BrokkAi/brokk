#!/usr/bin/env bash

# Renames PascalCase Bootstrap Streamline icons to snake_case format.
# This script should be run from within the app/src/main/resources/icons/ directory.

for file in Filetype-*--Streamline-Bootstrap.svg; do
    # Skip if no files match the pattern
    [ -e "$file" ] || continue

    # Transform: lowercase, replace '--' with '-', then all '-' with '_'
    new_name=$(echo "$file" | tr '[:upper:]' '[:lower:]' | sed 's/--/-/g' | tr '-' '_')

    # Rename using git mv if the target doesn't already exist
    if [ ! -e "$new_name" ]; then
        echo "Renaming $file to $new_name"
        git mv "$file" "$new_name"
    else
        echo "Skipping $file: $new_name already exists"
    fi
done
