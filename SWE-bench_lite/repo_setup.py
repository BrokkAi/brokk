#!/usr/bin/env python3
"""
Repository Setup Script for SWE-bench-lite + Brokk CLI Integration

This script demonstrates how to:
1. Load SWE-bench-lite instances
2. Clone the actual GitHub repositories
3. Checkout the specific commits
4. Set up the repositories for Brokk CLI evaluation

The key insight: SWE-bench-lite contains PROBLEM DESCRIPTIONS, but you need to 
clone the actual repositories and checkout specific commits to work with real code.
"""

import json
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Any, Optional
from datasets import load_from_disk, DatasetDict
import logging
from concurrent.futures import ThreadPoolExecutor, as_completed
from threading import Lock

# Setup logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)


def load_swe_bench_lite_dataset(dataset_path: str = "SWE-bench_lite") -> DatasetDict:
    """Load the SWE-bench-lite dataset."""
    try:
        dataset_path = Path(dataset_path)
        if not dataset_path.exists():
            raise FileNotFoundError(f"Dataset not found at {dataset_path}")
        
        logger.info(f"Loading SWE-bench-lite dataset from {dataset_path}")
        dataset = load_from_disk(str(dataset_path))
        
        logger.info(f"Loaded dataset with {len(dataset['dev'])} dev instances and {len(dataset['test'])} test instances")
        return dataset
        
    except Exception as e:
        logger.error(f"Failed to load dataset: {e}")
        sys.exit(1)


def clone_and_setup_repo(instance: Dict[str, Any], repos_dir: Path) -> Optional[Path]:
    """
    Clone a repository and checkout the specific commit for an instance.
    
    Args:
        instance: SWE-bench instance with repo and base_commit info
        repos_dir: Directory to clone repositories into
        
    Returns:
        Path to the cloned repository, or None if failed
    """
    # Debug: Check instance type and structure
    logger.debug(f"Instance type: {type(instance)}")
    logger.debug(f"Instance content: {instance}")
    
    # Handle different instance formats
    if isinstance(instance, str):
        logger.error(f"Instance is a string, not a dict: {instance}")
        return None
    
    if not isinstance(instance, dict):
        logger.error(f"Instance is not a dict: {type(instance)}")
        return None
    
    # Extract fields with error handling
    try:
        repo_name = instance["repo"]  # e.g., "django/django"
        base_commit = instance["base_commit"]  # e.g., "abc123def456..."
        instance_id = instance["instance_id"]  # e.g., "django__django-12345"
    except KeyError as e:
        logger.error(f"Missing key in instance: {e}")
        logger.error(f"Available keys: {list(instance.keys()) if isinstance(instance, dict) else 'Not a dict'}")
        return None
    
    # Create a safe directory name from the repo
    safe_repo_name = repo_name.replace("/", "__")
    repo_dir = repos_dir / f"{safe_repo_name}_{instance_id}"
    
    logger.info(f"🔄 Setting up repository for {instance_id}")
    logger.info(f"   Repository: {repo_name}")
    logger.info(f"   Base commit: {base_commit}")
    logger.info(f"   Target directory: {repo_dir}")
    
    try:
        # Remove existing directory if it exists
        if repo_dir.exists():
            logger.info(f"   Removing existing directory: {repo_dir}")
            subprocess.run(["rm", "-rf", str(repo_dir)], check=True)
        
        # Clone the repository
        repo_url = f"https://github.com/{repo_name}.git"
        logger.info(f"   Cloning from: {repo_url}")
        
        clone_result = subprocess.run([
            "git", "clone", repo_url, str(repo_dir)
        ], capture_output=True, text=True, check=True)
        
        logger.info(f"   ✅ Repository cloned successfully")
        
        # Checkout the specific commit
        logger.info(f"   Checking out commit: {base_commit}")
        
        checkout_result = subprocess.run([
            "git", "checkout", base_commit
        ], cwd=repo_dir, capture_output=True, text=True, check=True)
        
        logger.info(f"   ✅ Commit checked out successfully")
        
        # Remove origin remote to prevent accidental pushes
        subprocess.run([
            "git", "remote", "remove", "origin"
        ], cwd=repo_dir, capture_output=True, text=True)
        
        logger.info(f"   ✅ Repository setup complete: {repo_dir}")
        return repo_dir
        
    except subprocess.CalledProcessError as e:
        logger.error(f"   ❌ Git command failed: {e}")
        logger.error(f"   stdout: {e.stdout}")
        logger.error(f"   stderr: {e.stderr}")
        return None
    except Exception as e:
        logger.error(f"   💥 Unexpected error: {e}")
        return None


def setup_repositories_for_instances(
    instances: List[Dict[str, Any]], 
    repos_dir: Path,
    max_repos: Optional[int] = None,
    max_workers: int = 5
) -> Dict[str, Path]:
    """
    Set up repositories for multiple instances in parallel.
    
    Args:
        instances: List of SWE-bench instances
        repos_dir: Directory to clone repositories into
        max_repos: Maximum number of repositories to set up
        max_workers: Number of parallel workers (default: 5)
        
    Returns:
        Dictionary mapping instance_id to repository path
    """
    repos_dir.mkdir(parents=True, exist_ok=True)
    
    if max_repos:
        instances = instances[:max_repos]
    
    logger.info(f"🚀 Setting up {len(instances)} repositories in {repos_dir}")
    logger.info(f"⚡ Using {max_workers} parallel workers for faster cloning")
    
    # Debug: Check instances structure
    logger.debug(f"Instances type: {type(instances)}")
    if instances:
        logger.debug(f"First instance type: {type(instances[0])}")
        logger.debug(f"First instance: {instances[0]}")
    
    successful_repos = {}
    progress_lock = Lock()
    completed_count = [0]  # Use list for mutability in closure
    
    def clone_with_progress(instance_data):
        """Clone a repository and update progress."""
        index, instance = instance_data
        
        # Debug each instance
        logger.debug(f"Processing instance {index}: {type(instance)} - {instance}")
        
        repo_path = clone_and_setup_repo(instance, repos_dir)
        
        # Update progress (thread-safe)
        with progress_lock:
            completed_count[0] += 1
            logger.info(f"📊 Progress: {completed_count[0]}/{len(instances)}")
        
        # Return result
        if repo_path:
            if isinstance(instance, dict) and "instance_id" in instance:
                return (instance["instance_id"], repo_path)
            else:
                logger.warning(f"⚠️  Cannot extract instance_id from: {instance}")
                return None
        else:
            logger.warning(f"⚠️  Failed to set up repository for instance {index}")
            return None
    
    # Use ThreadPoolExecutor for parallel cloning (I/O bound operation)
    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        # Submit all tasks
        futures = {
            executor.submit(clone_with_progress, (i, instance)): instance 
            for i, instance in enumerate(instances)
        }
        
        # Collect results as they complete
        for future in as_completed(futures):
            try:
                result = future.result()
                if result:
                    instance_id, repo_path = result
                    successful_repos[instance_id] = repo_path
            except Exception as e:
                logger.error(f"💥 Unexpected error in parallel task: {e}")
    
    logger.info(f"✅ Successfully set up {len(successful_repos)}/{len(instances)} repositories")
    return successful_repos


def main():
    parser = argparse.ArgumentParser(
        description="Set up repositories for SWE-bench-lite evaluation",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Set up repositories for first 5 test instances
  python repo_setup.py --split test --max_repos 5 --repos_dir swe_bench_repos
  
  # Set up repositories for all dev instances with 10 parallel workers
  python repo_setup.py --split dev --repos_dir dev_repos --max_workers 10
  
  # Fast cloning with 20 parallel workers (requires good network)
  python repo_setup.py --max_repos 50 --max_workers 20 --repos_dir swe_bench_repos
  
  # Conservative cloning with fewer workers (for slower connections)
  python repo_setup.py --max_repos 10 --max_workers 2 --repos_dir swe_bench_repos
        """
    )
    
    parser.add_argument(
        "--dataset_path",
        default="SWE-bench_lite",
        help="Path to SWE-bench-lite dataset directory (default: SWE-bench_lite)"
    )
    
    parser.add_argument(
        "--split",
        choices=["dev", "test"],
        default="test",
        help="Dataset split to process (default: test)"
    )
    
    parser.add_argument(
        "--repos_dir",
        default="swe_bench_repos",
        help="Directory to clone repositories into (default: swe_bench_repos)"
    )
    
    parser.add_argument(
        "--max_repos",
        type=int,
        help="Maximum number of repositories to set up"
    )
    
    parser.add_argument(
        "--max_workers",
        type=int,
        default=5,
        help="Number of parallel workers for cloning (default: 5, increase for faster cloning)"
    )
    
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="Enable verbose logging"
    )
    
    args = parser.parse_args()
    
    # Setup logging level
    if args.verbose:
        logging.getLogger().setLevel(logging.DEBUG)
    
    # Load dataset
    dataset = load_swe_bench_lite_dataset(args.dataset_path)
    
    if args.split not in dataset:
        logger.error(f"Split '{args.split}' not found in dataset")
        sys.exit(1)
    
    # Convert HuggingFace Dataset to list of dictionaries
    instances_dataset = dataset[args.split]
    instances = list(instances_dataset)
    
    # Debug: Check what we got
    logger.debug(f"Dataset type: {type(dataset)}")
    logger.debug(f"Instances dataset type: {type(instances_dataset)}")
    logger.debug(f"Instances list type: {type(instances)}")
    logger.debug(f"Number of instances: {len(instances)}")
    if len(instances) > 0:
        logger.debug(f"First instance type: {type(instances[0])}")
        logger.debug(f"First instance: {instances[0]}")
        if hasattr(instances[0], 'keys'):
            logger.debug(f"First instance keys: {list(instances[0].keys())}")
    
    # Set up repositories
    repos_dir = Path(args.repos_dir)
    successful_repos = setup_repositories_for_instances(
        instances, 
        repos_dir, 
        args.max_repos,
        args.max_workers
    )
    
    # Save mapping
    mapping_file = repos_dir / "instance_to_repo_mapping.json"
    with open(mapping_file, 'w') as f:
        json.dump({
            instance_id: str(repo_path) 
            for instance_id, repo_path in successful_repos.items()
        }, f, indent=2)
    
    logger.info(f"📁 Repository mapping saved to: {mapping_file}")
    
    # Print summary
    print(f"\n🎉 Repository setup completed!")
    print(f"📊 Successfully set up: {len(successful_repos)} repositories")
    print(f"📁 Repository directory: {repos_dir}")
    print(f"📋 Mapping file: {mapping_file}")
    
    print(f"\n🚀 Next steps:")
    print(f"1. Use the repository paths with swe_bench_lite_runner.py:")
    print(f"   python swe_bench_lite_runner.py \\")
    print(f"       --split {args.split} \\")
    print(f"       --target_project {repos_dir}/<repo_name> \\")
    print(f"       --max_instances 1")
    print(f"")
    print(f"2. Or run Brokk CLI directly on individual repositories:")
    for instance_id, repo_path in list(successful_repos.items())[:3]:  # Show first 3
        print(f"   cd {repo_path}")
        print(f"   python brokk_wrapper.py \"<problem_statement>\" \\")
        print(f"       --instance_id \"{instance_id}\" \\")
        print(f"       --target_project \".\" \\")
        print(f"       --output_file \"pred_{instance_id}.json\"")
        print(f"   cd -")


if __name__ == "__main__":
    import argparse
    main()
