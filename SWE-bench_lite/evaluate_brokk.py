#!/usr/bin/env python3
"""
SWE-bench-lite Evaluation Script for Brokk CLI

This script:
1. Loads SWE-bench-lite instances
2. Runs Brokk CLI on each repository with the problem statement
3. Captures git diffs as model patches
4. Generates preds.json in the required format
5. Provides detailed status updates and progress tracking
"""

import json
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Dict, List, Any, Optional
from datasets import load_from_disk, DatasetDict
import logging
import argparse
from datetime import datetime

# Setup logging with timestamps
logging.basicConfig(
    level=logging.INFO, 
    format='%(asctime)s - %(levelname)s - %(message)s',
    datefmt='%H:%M:%S'
)
logger = logging.getLogger(__name__)

# Colors for output
class Colors:
    BLUE = '\033[94m'
    GREEN = '\033[92m'
    YELLOW = '\033[93m'
    RED = '\033[91m'
    BOLD = '\033[1m'
    END = '\033[0m'

def log_status(message: str, color: str = Colors.BLUE):
    """Log with color and timestamp"""
    print(f"{color}[{datetime.now().strftime('%H:%M:%S')}] {message}{Colors.END}")

def log_success(message: str):
    log_status(f"V {message}", Colors.GREEN)

def log_warning(message: str):
    log_status(f"!  {message}", Colors.YELLOW)

def log_error(message: str):
    log_status(f"X {message}", Colors.RED)

def log_info(message: str):
    log_status(f"i)  {message}", Colors.BLUE)

def load_swe_bench_lite_dataset(dataset_path: str = "SWE-bench_lite") -> DatasetDict:
    """Load the SWE-bench-lite dataset."""
    try:
        dataset_path = Path(dataset_path)
        if not dataset_path.exists():
            raise FileNotFoundError(f"Dataset not found at {dataset_path}")
        
        log_info(f"Loading SWE-bench-lite dataset from {dataset_path}")
        dataset = load_from_disk(str(dataset_path))
        
        log_success(f"Loaded dataset with {len(dataset['dev'])} dev instances and {len(dataset['test'])} test instances")
        return dataset
        
    except Exception as e:
        log_error(f"Failed to load dataset: {e}")
        sys.exit(1)

def load_repo_mapping(mapping_file: str) -> Dict[str, str]:
    """Load the instance to repository mapping."""
    try:
        with open(mapping_file, 'r') as f:
            mapping = json.load(f)
        log_success(f"Loaded repository mapping with {len(mapping)} entries")
        return mapping
    except Exception as e:
        log_error(f"Failed to load repository mapping: {e}")
        sys.exit(1)

def setup_brokk_config(repo_path: Path, template_path: Path) -> bool:
    """
    Setup .brokk/project.properties from template.
    
    Returns:
        True if successful, False otherwise
    """
    try:
        brokk_dir = repo_path / ".brokk"
        brokk_dir.mkdir(exist_ok=True)
        
        config_file = brokk_dir / "project.properties"
        
        # Copy template
        with open(template_path, 'r') as f:
            template_content = f.read()
        
        with open(config_file, 'w') as f:
            f.write(template_content)
        
        log_success(f"Set up .brokk/project.properties from template")
        return True
        
    except Exception as e:
        log_error(f"Failed to setup Brokk config: {e}")
        return False

def run_brokk_cli(repo_path: str, problem_statement: str, instance_id: str, template_path: Path) -> Dict[str, Any]:
    """
    Run Brokk CLI on a repository with the given problem statement.
    
    Returns:
        Dictionary with success status, git diff, and any errors
    """
    log_info(f"Running Brokk CLI on {instance_id}")
    log_info(f"Repository: {repo_path}")
    log_info(f"Problem: {problem_statement[:100]}...")
    
    # Change to the repository directory
    original_cwd = Path.cwd()
    repo_path = Path(repo_path).resolve()
    
    if not repo_path.exists():
        return {
            "success": False,
            "error": f"Repository path does not exist: {repo_path}",
            "patch": ""
        }
    
    # Setup .brokk/project.properties before running Brokk
    if not setup_brokk_config(repo_path, template_path):
        return {
            "success": False,
            "error": "Failed to setup Brokk configuration",
            "patch": ""
        }
    
    try:
        # Get initial git status
        log_info("Checking initial git status...")
        initial_status = subprocess.run(
            ["git", "status", "--porcelain"],
            cwd=repo_path,
            capture_output=True,
            text=True,
            check=True
        ).stdout.strip()
        
        if initial_status:
            log_warning(f"Repository has uncommitted changes: {initial_status}")
        
        # Record initial commit
        initial_commit = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=repo_path,
            capture_output=True,
            text=True,
            check=True
        ).stdout.strip()
        
        log_info(f"Initial commit: {initial_commit}")
        
        # Run Brokk CLI
        log_info("Starting Brokk CLI execution...")
        log_status("🔄 Brokk CLI is analyzing the problem and generating code...", Colors.YELLOW)
        
        # Use the cli script directly
        brokk_cmd = [
            "../../cli",  # Relative path from repo to cli script
            "--project", ".",
            "--deepscan",  # Enable deep scan for better context
            "--code", problem_statement
        ]
        
        log_info(f"Command: {' '.join(brokk_cmd)}")
        
        # Run with streaming and inactivity timeout
        start_time = time.time()
        process = subprocess.Popen(
            brokk_cmd,
            cwd=repo_path,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1
        )

        stdout_lines: List[str] = []
        stderr_lines: List[str] = []
        last_output_time = time.time()
        inactivity_timeout_sec = int(os.environ.get("BROKK_INACTIVITY_TIMEOUT", "180"))

        def _drain_stream(stream, collector, label):
            for line in iter(stream.readline, ""):
                collector.append(line)
                last_nonlocal[0] = time.time()
                # Echo live output for visibility
                prefix = "BROKK STDOUT" if label == "stdout" else "BROKK STDERR"
                print(f"{Colors.YELLOW}[{datetime.now().strftime('%H:%M:%S')}] [{prefix}] {line.rstrip()}{Colors.END}")

        # We need a mutable box for last_output_time inside inner function
        last_nonlocal = [last_output_time]

        import threading
        t_out = threading.Thread(target=_drain_stream, args=(process.stdout, stdout_lines, "stdout"), daemon=True)
        t_err = threading.Thread(target=_drain_stream, args=(process.stderr, stderr_lines, "stderr"), daemon=True)
        t_out.start(); t_err.start()

        # Poll loop with inactivity watchdog
        while True:
            ret = process.poll()
            now = time.time()
            if ret is not None:
                break
            if now - last_nonlocal[0] > inactivity_timeout_sec:
                log_error(f"No output from Brokk for {inactivity_timeout_sec}s – killing process")
                process.kill()
                ret = process.wait(timeout=5)
                break
            time.sleep(0.5)

        t_out.join(timeout=2)
        t_err.join(timeout=2)

        end_time = time.time()
        execution_time = end_time - start_time
        combined_stdout = "".join(stdout_lines)
        combined_stderr = "".join(stderr_lines)
        log_info(f"Brokk CLI finished with code {process.returncode} in {execution_time:.1f}s")

        # Check if Brokk CLI succeeded
        if process.returncode != 0:
            log_error(f"Brokk CLI failed with return code {process.returncode}")
            if combined_stdout:
                log_error(f"STDOUT: {combined_stdout[-4000:]}")
            if combined_stderr:
                log_error(f"STDERR: {combined_stderr[-4000:]}")
            return {
                "success": False,
                "error": "Brokk CLI failed or was terminated (see stderr)",
                "patch": "",
                "stdout": combined_stdout,
                "stderr": combined_stderr,
                "execution_time": execution_time
            }

        log_success("Brokk CLI completed successfully")
        
        # Check for changes
        log_info("Checking for changes...")
        final_status = subprocess.run(
            ["git", "status", "--porcelain"],
            cwd=repo_path,
            capture_output=True,
            text=True,
            check=True
        ).stdout.strip()
        
        if not final_status:
            log_warning("No changes detected - Brokk may not have found a solution")
            return {
                "success": True,
                "error": None,
                "patch": "",
                "changes": False,
                "stdout": combined_stdout,
                "stderr": combined_stderr,
                "execution_time": execution_time
            }
        
        log_success(f"Changes detected: {final_status}")
        
        # Generate git diff (excluding .brokk/ directory)
        log_info("Generating git diff (excluding .brokk/)...")
        diff_result = subprocess.run(
            ["git", "diff", "--", ".", ":(exclude).brokk"],
            cwd=repo_path,
            capture_output=True,
            text=True,
            check=True
        )
        
        patch = diff_result.stdout
        log_success(f"Generated patch ({len(patch)} characters, .brokk/ excluded)")
        
        # Also check for new commits
        final_commit = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=repo_path,
            capture_output=True,
            text=True,
            check=True
        ).stdout.strip()
        
        if initial_commit != final_commit:
            log_success(f"New commit created: {final_commit}")
        
        return {
            "success": True,
            "error": None,
            "patch": patch,
            "changes": True,
            "stdout": combined_stdout,
            "stderr": combined_stderr,
            "initial_commit": initial_commit,
            "final_commit": final_commit,
            "execution_time": execution_time
        }
        
    except subprocess.TimeoutExpired:
        log_error("Brokk CLI execution timed out (5 minutes)")
        return {
            "success": False,
            "error": "Execution timeout",
            "patch": ""
        }
    except subprocess.CalledProcessError as e:
        log_error(f"Git command failed: {e}")
        return {
            "success": False,
            "error": f"Git error: {e}",
            "patch": ""
        }
    except Exception as e:
        log_error(f"Unexpected error: {e}")
        return {
            "success": False,
            "error": f"Unexpected error: {e}",
            "patch": ""
        }
    finally:
        # Return to original directory
        os.chdir(original_cwd)

def evaluate_instances(
    instances: List[Dict[str, Any]], 
    repo_mapping: Dict[str, str],
    template_path: Path,
    max_instances: Optional[int] = None,
    model_name: str = "brokk-cli"
) -> Dict[str, Dict[str, Any]]:
    """
    Evaluate Brokk CLI on multiple instances.
    
    Returns:
        Dictionary in preds.json format
    """
    if max_instances:
        instances = instances[:max_instances]
    
    log_info(f"Starting evaluation of {len(instances)} instances")
    log_info(f"Model name: {model_name}")
    log_info(f"Using template: {template_path}")
    
    results = {}
    successful_evaluations = 0
    failed_evaluations = 0
    
    for i, instance in enumerate(instances):
        instance_id = instance["instance_id"]
        
        log_status(f"\n{'='*60}", Colors.BOLD)
        log_status(f"Instance {i+1}/{len(instances)}: {instance_id}", Colors.BOLD)
        log_status(f"{'='*60}", Colors.BOLD)
        
        # Get repository path
        if instance_id not in repo_mapping:
            log_error(f"No repository mapping found for {instance_id}")
            failed_evaluations += 1
            results[instance_id] = {
                "model_name_or_path": model_name,
                "instance_id": instance_id,
                "model_patch": "",
                "error": "No repository mapping found"
            }
            continue
        
        repo_path = repo_mapping[instance_id]
        problem_statement = instance["problem_statement"]
        
        # Run evaluation
        evaluation_result = run_brokk_cli(repo_path, problem_statement, instance_id, template_path)
        
        # Store result
        results[instance_id] = {
            "model_name_or_path": model_name,
            "instance_id": instance_id,
            "model_patch": evaluation_result["patch"],
            "success": evaluation_result["success"],
            "changes": evaluation_result.get("changes", False),
            "error": evaluation_result.get("error"),
            "execution_time": evaluation_result.get("execution_time")
        }
        
        if evaluation_result["success"]:
            successful_evaluations += 1
            if evaluation_result.get("changes", False):
                log_success(f"✅ Instance {instance_id}: Changes made")
            else:
                log_warning(f"⚠️  Instance {instance_id}: No changes made")
        else:
            failed_evaluations += 1
            log_error(f"❌ Instance {instance_id}: Failed - {evaluation_result.get('error', 'Unknown error')}")
        
        # Progress update
        progress = (i + 1) / len(instances) * 100
        log_status(f"Progress: {i+1}/{len(instances)} ({progress:.1f}%) - Success: {successful_evaluations}, Failed: {failed_evaluations}", Colors.BLUE)
    
    # Final summary
    log_status(f"\n{'='*60}", Colors.BOLD)
    log_status(f"EVALUATION COMPLETE", Colors.BOLD)
    log_status(f"{'='*60}", Colors.BOLD)
    log_success(f"Total instances: {len(instances)}")
    log_success(f"Successful evaluations: {successful_evaluations}")
    log_error(f"Failed evaluations: {failed_evaluations}")
    log_info(f"Success rate: {successful_evaluations/len(instances)*100:.1f}%")
    
    return results

def save_predictions(results: Dict[str, Dict[str, Any]], output_file: str):
    """Save results in preds.json format."""
    # Convert to the required format (remove extra fields)
    preds_format = {}
    for instance_id, result in results.items():
        preds_format[instance_id] = {
            "model_name_or_path": result["model_name_or_path"],
            "instance_id": result["instance_id"],
            "model_patch": result["model_patch"]
        }
    
    with open(output_file, 'w') as f:
        json.dump(preds_format, f, indent=2)
    
    log_success(f"Predictions saved to {output_file}")

def main():
    parser = argparse.ArgumentParser(
        description="Evaluate Brokk CLI on SWE-bench-lite instances",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Evaluate first 5 test instances
  python evaluate_brokk.py --split test --max_instances 5 --repos_dir swe_bench_repos
  
  # Evaluate all dev instances
  python evaluate_brokk.py --split dev --repos_dir swe_bench_repos
  
  # Evaluate specific instance
  python evaluate_brokk.py --instance_id matplotlib__matplotlib-18869 --repos_dir swe_bench_repos
  
  # Custom model name
  python evaluate_brokk.py --split test --max_instances 3 --model_name "brokk-cli-v1.0"
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
        help="Dataset split to evaluate (default: test)"
    )
    
    parser.add_argument(
        "--repos_dir",
        default="swe_bench_repos",
        help="Directory containing cloned repositories (default: swe_bench_repos)"
    )
    
    parser.add_argument(
        "--max_instances",
        type=int,
        help="Maximum number of instances to evaluate"
    )
    
    parser.add_argument(
        "--instance_id",
        help="Specific instance ID to evaluate (e.g., matplotlib__matplotlib-18869)"
    )
    
    parser.add_argument(
        "--model_name",
        default="brokk-cli",
        help="Model name for preds.json (default: brokk-cli)"
    )
    
    parser.add_argument(
        "--output_file",
        default="preds.json",
        help="Output file for predictions (default: preds.json)"
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
        log_error(f"Split '{args.split}' not found in dataset")
        sys.exit(1)
    
    # Convert to list
    instances_dataset = dataset[args.split]
    instances = list(instances_dataset)
    
    # Load repository mapping
    mapping_file = Path(args.repos_dir) / "instance_to_repo_mapping.json"
    if not mapping_file.exists():
        log_error(f"Repository mapping file not found: {mapping_file}")
        log_error("Please run repo_setup.py first to set up repositories")
        sys.exit(1)
    
    repo_mapping = load_repo_mapping(mapping_file)
    
    # Load template path
    template_path = Path(__file__).parent / "template_project.properties"
    if not template_path.exists():
        log_error(f"Template file not found: {template_path}")
        log_error("Please ensure template_project.properties exists in SWE-bench_lite/")
        sys.exit(1)
    
    log_success(f"Found template: {template_path}")
    
    # Filter instances to only those with repositories
    available_instances = []
    for instance in instances:
        if instance["instance_id"] in repo_mapping:
            available_instances.append(instance)
        else:
            log_warning(f"No repository available for {instance['instance_id']}")
    
    log_info(f"Found {len(available_instances)} instances with available repositories")
    
    if not available_instances:
        log_error("No instances with available repositories found")
        sys.exit(1)
    
    # Filter to specific instance if requested
    if args.instance_id:
        specific_instance = None
        for instance in available_instances:
            if instance["instance_id"] == args.instance_id:
                specific_instance = instance
                break
        
        if specific_instance:
            available_instances = [specific_instance]
            log_info(f"Filtering to specific instance: {args.instance_id}")
        else:
            log_error(f"Instance {args.instance_id} not found in available instances")
            log_info(f"Available instances: {[i['instance_id'] for i in available_instances]}")
            sys.exit(1)
    
    # Run evaluation
    results = evaluate_instances(
        available_instances,
        repo_mapping,
        template_path,
        args.max_instances,
        args.model_name
    )
    
    # Save results
    save_predictions(results, args.output_file)
    
    log_success("Evaluation completed successfully!")

if __name__ == "__main__":
    import os
    main()
