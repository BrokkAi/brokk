#!/usr/bin/env python3
"""
Reset SWE-bench repositories to clean state.

This script resets all repositories to their original state by:
- Discarding uncommitted changes (git reset --hard)
- Removing untracked files (git clean -fd)
- Removing .brokk/ directories

Usage:
    python reset_repos.py --repos_dir swe_bench_repos
    python reset_repos.py --repos_dir swe_bench_repos --instance_id django__django-15213
"""

import argparse
import json
import subprocess
import sys
from pathlib import Path

def reset_repository(repo_path: Path) -> bool:
    """Reset a single repository to clean state."""
    try:
        print(f"Resetting: {repo_path.name}")
        
        # Reset tracked files
        subprocess.run(
            ["git", "reset", "--hard", "HEAD"],
            cwd=repo_path,
            capture_output=True,
            text=True,
            check=True
        )
        
        # Remove untracked files
        subprocess.run(
            ["git", "clean", "-fd"],
            cwd=repo_path,
            capture_output=True,
            text=True,
            check=True
        )
        
        print(f"  ✓ Reset complete")
        return True
        
    except subprocess.CalledProcessError as e:
        print(f"  ✗ Failed: {e}")
        return False
    except Exception as e:
        print(f"  ✗ Error: {e}")
        return False

def main():
    parser = argparse.ArgumentParser(
        description="Reset SWE-bench repositories to clean state"
    )
    
    parser.add_argument(
        "--repos_dir",
        default="swe_bench_repos",
        help="Directory containing repositories (default: swe_bench_repos)"
    )
    
    parser.add_argument(
        "--instance_id",
        help="Specific instance to reset (optional)"
    )
    
    args = parser.parse_args()
    
    repos_dir = Path(args.repos_dir)
    
    if not repos_dir.exists():
        print(f"Error: Directory not found: {repos_dir}")
        sys.exit(1)
    
    # Load mapping
    mapping_file = repos_dir / "instance_to_repo_mapping.json"
    if not mapping_file.exists():
        print(f"Error: Mapping file not found: {mapping_file}")
        sys.exit(1)
    
    with open(mapping_file, 'r') as f:
        repo_mapping = json.load(f)
    
    # Determine which repos to reset
    if args.instance_id:
        if args.instance_id not in repo_mapping:
            print(f"Error: Instance not found: {args.instance_id}")
            sys.exit(1)
        repos_to_reset = {args.instance_id: repo_mapping[args.instance_id]}
    else:
        repos_to_reset = repo_mapping
    
    print(f"Resetting {len(repos_to_reset)} repository(ies)...\n")
    
    success_count = 0
    fail_count = 0
    
    for instance_id, repo_path in repos_to_reset.items():
        if reset_repository(Path(repo_path)):
            success_count += 1
        else:
            fail_count += 1
    
    print(f"\n{'='*60}")
    print(f"Complete: {success_count} successful, {fail_count} failed")
    print(f"{'='*60}")

if __name__ == "__main__":
    main()

