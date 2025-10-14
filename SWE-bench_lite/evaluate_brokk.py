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
import re
import signal
import subprocess
import sys
import time
import psutil
from pathlib import Path
from typing import Dict, List, Any, Optional, Set
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

def reset_repository(repo_path: Path) -> bool:
    """
    Reset repository to clean state for re-evaluation.
    
    - Discards all uncommitted changes
    - Removes untracked files
    - Removes .brokk/ directory
    
    Returns:
        True if successful, False otherwise
    """
    try:
        log_info(f"Resetting repository: {repo_path}")
        
        # Reset all tracked files to HEAD
        subprocess.run(
            ["git", "reset", "--hard", "HEAD"],
            cwd=repo_path,
            capture_output=True,
            text=True,
            check=True
        )
        
        # Remove all untracked files and directories (including .brokk/)
        subprocess.run(
            ["git", "clean", "-fd"],
            cwd=repo_path,
            capture_output=True,
            text=True,
            check=True
        )
        
        log_success("Repository reset to clean state")
        return True
        
    except subprocess.CalledProcessError as e:
        log_error(f"Failed to reset repository: {e}")
        return False
    except Exception as e:
        log_error(f"Unexpected error resetting repository: {e}")
        return False

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

def extract_requested_files(output_text: str, repo_path: Path) -> Set[str]:
    """
    Extract file paths that Brokk is requesting to be added to the workspace.
    
    Looks for patterns like:
    - "Please add these files to the chat"
    - Files in backticks: `path/to/file.py`
    - Numbered lists with file paths
    
    Returns:
        Set of file paths that exist in the repository
    """
    requested_files = set()
    
    # Pattern 1: Files in backticks (common in Brokk output)
    # Match `path/to/file.py` or `django/core/files/storage.py`
    backtick_pattern = r'`([a-zA-Z0-9_/\.-]+\.[a-zA-Z]+)`'
    for match in re.finditer(backtick_pattern, output_text):
        file_path = match.group(1)
        full_path = repo_path / file_path
        if full_path.exists() and full_path.is_file():
            requested_files.add(file_path)
    
    # Pattern 2: Look for "Please add" sections and extract nearby file paths
    # This catches patterns like:
    # "Please add these files to the chat so I can..."
    # "1. django/core/files/storage.py"
    add_request_pattern = r'(?:Please add|add these|Add the following).*?(?:files?|source files?).*?(?:to|:)'
    if re.search(add_request_pattern, output_text, re.IGNORECASE):
        # Look for numbered lists with file paths
        numbered_pattern = r'^\s*\d+\.\s*`?([a-zA-Z0-9_/\.-]+\.[a-zA-Z]+)`?'
        for match in re.finditer(numbered_pattern, output_text, re.MULTILINE):
            file_path = match.group(1)
            full_path = repo_path / file_path
            if full_path.exists() and full_path.is_file():
                requested_files.add(file_path)
    
    return requested_files

def should_retry_with_files(result: Dict[str, Any]) -> bool:
    """
    Determine if we should retry Brokk with additional files.
    
    Returns True if:
    - Brokk succeeded but made no changes
    - Output indicates it needs more files
    """
    if not result.get("success", False):
        return False
    
    if result.get("changes", False):
        return False
    
    # Check if output contains file request indicators
    stdout = result.get("stdout", "")
    stderr = result.get("stderr", "")
    combined = stdout + "\n" + stderr
    
    # Look for common patterns indicating file requests
    request_indicators = [
        r"Please add.*files?.*to",
        r"add these.*files?",
        r"need to see.*implementation",
        r"need.*full.*files?",
        r"need access to",
        r"No edits found or applied in response"
    ]
    
    for pattern in request_indicators:
        if re.search(pattern, combined, re.IGNORECASE):
            return True
    
    return False

def run_brokk_cli_internal(
    repo_path: Path, 
    problem_statement: str, 
    instance_id: str, 
    additional_files: Optional[List[str]] = None,
    retry_attempt: int = 0,
    agent: str = "code"
) -> Dict[str, Any]:
    """
    Internal function to run Brokk CLI on a repository.
    
    Args:
        repo_path: Path to the repository
        problem_statement: The problem description to solve
        instance_id: The instance identifier
        additional_files: Optional list of files to add with --edit
        retry_attempt: Current retry attempt number (for logging)
    
    Returns:
        Dictionary with success status, git diff, and any errors
    """
    retry_suffix = f" (retry {retry_attempt})" if retry_attempt > 0 else ""
    log_info(f"Running Brokk CLI on {instance_id}{retry_suffix}")
    log_info(f"Repository: {repo_path}")
    log_info(f"Problem: {problem_statement[:100]}...")
    if additional_files:
        log_info(f"Adding {len(additional_files)} files explicitly: {additional_files}")
    
    original_cwd = Path.cwd()
    
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
        log_status("i) Brokk CLI is analyzing the problem and generating code...", Colors.YELLOW)
        
        # Use the cli script directly
        brokk_cmd = [
            "../../cli",  # Relative path from repo to cli script
            "--project", ".",
            "--deepscan",
        ]
        
        # Add explicit files if provided
        if additional_files:
            for file_path in additional_files:
                brokk_cmd.extend(["--edit", file_path])
        
        # Add the problem statement with the appropriate agent flag
        if agent == "code":
            brokk_cmd.extend(["--code", problem_statement])
        elif agent == "architect":
            brokk_cmd.extend(["--architect", problem_statement])
        elif agent == "search-tasks":
            brokk_cmd.extend(["--search-tasks", problem_statement])
        else:
            # Default to code
            brokk_cmd.extend(["--code", problem_statement])
        
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
        
        log_success(f"Changes detected in git status: {final_status}")
        
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
        
        # Check if patch is actually empty (only .brokk/ changes)
        if not patch or patch.strip() == "":
            log_warning("No actual code changes detected (only .brokk/ was modified)")
            return {
                "success": True,
                "error": None,
                "patch": "",
                "changes": False,
                "stdout": combined_stdout,
                "stderr": combined_stderr,
                "execution_time": execution_time
            }
        
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

def run_brokk_cli(
    repo_path: str, 
    problem_statement: str, 
    instance_id: str, 
    template_path: Path,
    max_retries: int = 1,
    agent: str = "code",
    reset_repo: bool = True
) -> Dict[str, Any]:
    """
    Run Brokk CLI on a repository with automatic retry if files are requested.
    
    Args:
        repo_path: Path to the repository
        problem_statement: The problem description to solve
        instance_id: The instance identifier
        template_path: Path to the project.properties template
        max_retries: Maximum number of retries with additional files (default: 1)
        agent: Which Brokk agent to use (code, architect, or search-tasks)
        reset_repo: Whether to reset repository to clean state before running (default: True)
    
    Returns:
        Dictionary with success status, git diff, and any errors
    """
    repo_path_obj = Path(repo_path).resolve()
    
    if not repo_path_obj.exists():
        return {
            "success": False,
            "error": f"Repository path does not exist: {repo_path_obj}",
            "patch": ""
        }
    
    # Reset repository to clean state if requested
    if reset_repo:
        if not reset_repository(repo_path_obj):
            log_warning("Failed to reset repository, continuing anyway...")
    
    # Setup .brokk/project.properties before running Brokk
    if not setup_brokk_config(repo_path_obj, template_path):
        return {
            "success": False,
            "error": "Failed to setup Brokk configuration",
            "patch": ""
        }
    
    # First attempt: run without additional files
    result = run_brokk_cli_internal(repo_path_obj, problem_statement, instance_id, agent=agent)
    
    # Track retry attempts
    retry_count = 0
    used_files: Set[str] = set()
    
    # Retry logic: if Brokk requests files and we haven't exceeded max retries
    while retry_count < max_retries and should_retry_with_files(result):
        # Extract requested files from output
        stdout = result.get("stdout", "")
        stderr = result.get("stderr", "")
        combined_output = stdout + "\n" + stderr
        
        requested_files = extract_requested_files(combined_output, repo_path_obj)
        
        # Filter out files we've already tried
        new_files = requested_files - used_files
        
        if not new_files:
            log_warning(f"No new files to add for retry (already tried: {used_files})")
            break
        
        log_info(f"Brokk requested {len(new_files)} file(s) - retrying with explicit context")
        log_info(f"Files to add: {sorted(new_files)}")
        
        # Update tracking
        used_files.update(new_files)
        retry_count += 1
        
        # Retry with the requested files
        result = run_brokk_cli_internal(
            repo_path_obj, 
            problem_statement, 
            instance_id,
            additional_files=sorted(new_files),
            retry_attempt=retry_count,
            agent=agent
        )
    
    # Add retry metadata to result
    if retry_count > 0:
        result["retry_count"] = retry_count
        result["added_files"] = sorted(used_files)
        if result.get("changes", False):
            log_success(f"Retry successful! Changes made after {retry_count} retry attempt(s)")
    
    return result

def evaluate_instances(
    instances: List[Dict[str, Any]], 
    repo_mapping: Dict[str, str],
    template_path: Path,
    output_dir: Path,
    max_instances: Optional[int] = None,
    model_name: str = "brokk-cli",
    max_retries: int = 1,
    agent: str = "code",
    reset_repo: bool = True,
    checkpoint_data: Optional[Dict[str, Any]] = None,
    eval_start_time: Optional[float] = None
) -> Dict[str, Dict[str, Any]]:
    """
    Evaluate Brokk CLI on multiple instances with checkpoint support.
    
    Args:
        instances: List of SWE-bench instances to evaluate
        repo_mapping: Mapping of instance IDs to repository paths
        template_path: Path to project.properties template
        output_dir: Output directory for checkpoints
        max_instances: Maximum number of instances to evaluate
        model_name: Name of the model for results
        max_retries: Maximum retry attempts when Brokk requests files
        agent: Which Brokk agent to use (code, architect, or search-tasks)
        reset_repo: Whether to reset repositories to clean state before running
        checkpoint_data: Previous checkpoint data to resume from
        eval_start_time: Start time from checkpoint (for resume)
    
    Returns:
        Dictionary in preds.json format
    """
    if max_instances:
        instances = instances[:max_instances]
    
    # Resume from checkpoint if available
    if checkpoint_data:
        results = checkpoint_data['results']
        completed_instances = set(checkpoint_data['completed_instances'])
        start_time = checkpoint_data['eval_start_time']
        log_info(f"Resuming evaluation - {len(completed_instances)} instances already completed")
    else:
        results = {}
        completed_instances = set()
        start_time = eval_start_time if eval_start_time else time.time()
    
    log_info(f"Evaluating {len(instances)} total instances")
    log_info(f"Model name: {model_name}")
    log_info(f"Agent mode: {agent}")
    log_info(f"Using template: {template_path}")
    log_info(f"Max retries with requested files: {max_retries}")
    log_info(f"Reset repositories before evaluation: {reset_repo}")
    
    successful_evaluations = sum(1 for r in results.values() if r.get("success", False))
    failed_evaluations = sum(1 for r in results.values() if not r.get("success", False))
    
    # Set up signal handler for graceful shutdown
    interrupted = [False]  # Mutable list to allow modification in nested function
    
    def signal_handler(signum, frame):
        log_warning(f"\nReceived interrupt signal ({signum}). Saving checkpoint and exiting...")
        interrupted[0] = True
        # Save final checkpoint
        save_checkpoint(output_dir, results, list(completed_instances), start_time)
        log_success("Checkpoint saved. You can resume later with the same command.")
        sys.exit(0)
    
    # Register signal handlers
    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)
    
    for i, instance in enumerate(instances):
        instance_id = instance["instance_id"]
        
        # Skip if already completed (resume logic)
        if instance_id in completed_instances:
            log_info(f"Skipping {instance_id} (already completed)")
            continue
        
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
            completed_instances.add(instance_id)
            # Save checkpoint after each instance
            save_checkpoint(output_dir, results, list(completed_instances), start_time)
            continue
        
        repo_path = repo_mapping[instance_id]
        problem_statement = instance["problem_statement"]
        
        # Run evaluation
        evaluation_result = run_brokk_cli(
            repo_path, 
            problem_statement, 
            instance_id, 
            template_path,
            max_retries=max_retries,
            agent=agent,
            reset_repo=reset_repo
        )
        
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
        
        # Mark as completed
        completed_instances.add(instance_id)
        
        if evaluation_result["success"]:
            successful_evaluations += 1
            if evaluation_result.get("changes", False):
                log_success(f"V Instance {instance_id}: Changes made")
            else:
                log_warning(f"!  Instance {instance_id}: No changes made")
        else:
            failed_evaluations += 1
            log_error(f"X Instance {instance_id}: Failed - {evaluation_result.get('error', 'Unknown error')}")
        
        # Save checkpoint after each instance completion
        save_checkpoint(output_dir, results, list(completed_instances), start_time)
        
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

def generate_results_json(results: Dict[str, Dict[str, Any]]) -> Dict[str, Any]:
    """
    Generate results.json in SWE-bench format.
    
    Note: resolved/unresolved instances require running actual tests,
    so we mark all completed instances as unresolved until tests are run.
    """
    total_instances = len(results)
    
    # Categorize instances
    completed_ids = []
    incomplete_ids = []
    empty_patch_ids = []
    submitted_ids = []
    error_ids = []
    
    for instance_id, result in results.items():
        # All instances we attempted are submitted
        submitted_ids.append(instance_id)
        
        if result.get("success", False):
            # Completed successfully
            completed_ids.append(instance_id)
            
            if not result.get("model_patch") or result.get("model_patch").strip() == "":
                # Completed but no patch
                empty_patch_ids.append(instance_id)
        else:
            # Failed to complete
            if result.get("error"):
                error_ids.append(instance_id)
            incomplete_ids.append(instance_id)
    
    # Sort all ID lists for consistency
    completed_ids.sort()
    incomplete_ids.sort()
    empty_patch_ids.sort()
    submitted_ids.sort()
    error_ids.sort()
    
    # For resolved/unresolved, we need actual test results
    # For now, mark all completed as unresolved (tests haven't run yet)
    unresolved_ids = sorted(completed_ids)  # All completed are unresolved until tested
    resolved_ids = []  # Will be populated after running SWE-bench tests
    
    return {
        "total_instances": total_instances,
        "submitted_instances": len(submitted_ids),
        "completed_instances": len(completed_ids),
        "resolved_instances": len(resolved_ids),
        "unresolved_instances": len(unresolved_ids),
        "empty_patch_instances": len(empty_patch_ids),
        "error_instances": len(error_ids),
        "completed_ids": completed_ids,
        "incomplete_ids": incomplete_ids,
        "empty_patch_ids": empty_patch_ids,
        "submitted_ids": submitted_ids,
        "resolved_ids": resolved_ids,
        "unresolved_ids": unresolved_ids,
        "error_ids": error_ids,
        "schema_version": 2
    }

def save_results_json(results: Dict[str, Dict[str, Any]], output_file: str):
    """Save results.json in SWE-bench format."""
    results_data = generate_results_json(results)
    
    with open(output_file, 'w') as f:
        json.dump(results_data, f, indent=2)
    
    log_success(f"Results summary saved to {output_file}")

def generate_diagnostics(
    results: Dict[str, Dict[str, Any]], 
    start_time: float, 
    end_time: float,
    model_name: str
) -> Dict[str, Any]:
    """Generate diagnostic information about the evaluation run."""
    total_runtime = end_time - start_time
    
    # Extract execution times
    execution_times = []
    retry_counts = []
    
    for result in results.values():
        if "execution_time" in result and result["execution_time"]:
            execution_times.append(result["execution_time"])
        if "retry_count" in result:
            retry_counts.append(result["retry_count"])
    
    # Calculate statistics
    avg_runtime = sum(execution_times) / len(execution_times) if execution_times else 0
    min_runtime = min(execution_times) if execution_times else 0
    max_runtime = max(execution_times) if execution_times else 0
    
    # Get system info
    process = psutil.Process()
    memory_info = process.memory_info()
    
    # Count retries
    instances_with_retries = len([r for r in results.values() if r.get("retry_count", 0) > 0])
    total_retries = sum(retry_counts)
    
    # Count changes
    instances_with_changes = len([r for r in results.values() if r.get("changes", False)])
    instances_no_changes = len([r for r in results.values() 
                                 if r.get("success", False) and not r.get("changes", False)])
    
    return {
        "evaluation_metadata": {
            "model_name": model_name,
            "start_time": datetime.fromtimestamp(start_time).isoformat(),
            "end_time": datetime.fromtimestamp(end_time).isoformat(),
            "total_instances": len(results)
        },
        "timing_statistics": {
            "total_runtime_seconds": round(total_runtime, 2),
            "total_runtime_human": f"{int(total_runtime // 3600)}h {int((total_runtime % 3600) // 60)}m {int(total_runtime % 60)}s",
            "average_runtime_per_instance_seconds": round(avg_runtime, 2),
            "min_runtime_seconds": round(min_runtime, 2),
            "max_runtime_seconds": round(max_runtime, 2),
            "instances_evaluated": len(execution_times)
        },
        "retry_statistics": {
            "instances_with_retries": instances_with_retries,
            "total_retry_attempts": total_retries,
            "average_retries_per_instance": round(total_retries / len(results), 2) if results else 0
        },
        "outcome_statistics": {
            "instances_with_code_changes": instances_with_changes,
            "instances_no_changes": instances_no_changes,
            "instances_failed": len([r for r in results.values() if not r.get("success", False)]),
            "instances_empty_patch": len([r for r in results.values() 
                                         if r.get("success", False) and 
                                         (not r.get("model_patch") or r.get("model_patch").strip() == "")])
        },
        "system_info": {
            "memory_used_mb": round(memory_info.rss / 1024 / 1024, 2),
            "cpu_percent": psutil.cpu_percent(interval=1),
            "python_version": sys.version,
            "platform": sys.platform
        }
    }

def save_diagnostics(
    results: Dict[str, Dict[str, Any]], 
    start_time: float, 
    end_time: float,
    model_name: str,
    output_file: str
):
    """Save diagnostic information to file."""
    diagnostics = generate_diagnostics(results, start_time, end_time, model_name)
    
    with open(output_file, 'w') as f:
        json.dump(diagnostics, f, indent=2)
    
    log_success(f"Diagnostics saved to {output_file}")

def save_checkpoint(
    output_dir: Path,
    results: Dict[str, Dict[str, Any]],
    completed_instances: List[str],
    eval_start_time: float
):
    """
    Save evaluation checkpoint to disk.
    
    Args:
        output_dir: Output directory for checkpoint
        results: Current results dictionary
        completed_instances: List of completed instance IDs
        eval_start_time: Start time of evaluation
    """
    # Create checkpoints subdirectory
    checkpoints_dir = output_dir / "checkpoints"
    checkpoints_dir.mkdir(exist_ok=True)
    
    checkpoint_file = checkpoints_dir / "checkpoint.json"
    checkpoint_data = {
        "completed_instances": completed_instances,
        "results": results,
        "eval_start_time": eval_start_time,
        "last_checkpoint": datetime.now().isoformat(),
        "checkpoint_count": len(completed_instances)
    }
    
    # Write to temporary file first, then rename (atomic operation)
    temp_file = checkpoints_dir / "checkpoint.tmp.json"
    with open(temp_file, 'w') as f:
        json.dump(checkpoint_data, f, indent=2)
    temp_file.rename(checkpoint_file)
    
    log_info(f"Checkpoint saved ({len(completed_instances)} instances completed)")

def load_checkpoint(output_dir: Path) -> Optional[Dict[str, Any]]:
    """
    Load evaluation checkpoint from disk.
    
    Args:
        output_dir: Output directory containing checkpoint
    
    Returns:
        Checkpoint data if found, None otherwise
    """
    checkpoints_dir = output_dir / "checkpoints"
    checkpoint_file = checkpoints_dir / "checkpoint.json"
    if not checkpoint_file.exists():
        return None
    
    try:
        with open(checkpoint_file, 'r') as f:
            checkpoint_data = json.load(f)
        
        log_info(f"Found checkpoint with {len(checkpoint_data['completed_instances'])} completed instances")
        log_info(f"Last checkpoint: {checkpoint_data['last_checkpoint']}")
        return checkpoint_data
    except Exception as e:
        log_error(f"Failed to load checkpoint: {e}")
        return None

def prompt_resume_checkpoint(checkpoint_data: Dict[str, Any]) -> bool:
    """
    Ask user if they want to resume from checkpoint.
    
    Args:
        checkpoint_data: Loaded checkpoint data
    
    Returns:
        True if user wants to resume, False otherwise
    """
    completed_count = len(checkpoint_data['completed_instances'])
    last_checkpoint = checkpoint_data['last_checkpoint']
    
    log_status(f"\n{'='*60}", Colors.YELLOW)
    log_warning(f"CHECKPOINT FOUND")
    log_status(f"{'='*60}", Colors.YELLOW)
    log_info(f"Completed instances: {completed_count}")
    log_info(f"Last checkpoint: {last_checkpoint}")
    log_info(f"")
    
    while True:
        response = input("Do you want to resume from checkpoint? [y/n]: ").strip().lower()
        if response in ['y', 'yes']:
            log_success("Resuming from checkpoint...")
            return True
        elif response in ['n', 'no']:
            log_warning("Starting fresh evaluation (checkpoint will be overwritten)")
            return False
        else:
            print("Please enter 'y' or 'n'")

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
  
  # Enable automatic retries when Brokk requests files
  python evaluate_brokk.py --split test --max_instances 5 --max_retries 2
  
  # Use search-tasks agent (research + task execution mode)
  python evaluate_brokk.py --split test --max_instances 3 --agent search-tasks
  
  # Use architect agent (planning + coding mode)
  python evaluate_brokk.py --split test --max_instances 3 --agent architect
  
  # Don't reset repositories (preserve previous state)
  python evaluate_brokk.py --split test --max_instances 3 --no-reset
  
  # Custom output directory
  python evaluate_brokk.py --split test --max_instances 3 --output_dir my_results
  
  # Resume from checkpoint (interactive prompt)
  python evaluate_brokk.py --split test --output_dir swe_bench_tests/20250113_143022_brokk-cli
  
  # Resume from checkpoint (non-interactive)
  python evaluate_brokk.py --split test --output_dir swe_bench_tests/20250113_143022_brokk-cli --resume
  
  # Force fresh start (ignore checkpoint)
  python evaluate_brokk.py --split test --output_dir swe_bench_tests/20250113_143022_brokk-cli --force-fresh
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
        "--output_dir",
        default=None,
        help="Output directory for results (default: swe_bench_tests/{timestamp}_{model_name})"
    )
    
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="Enable verbose logging"
    )
    
    parser.add_argument(
        "--max_retries",
        type=int,
        default=1,
        help="Maximum retry attempts when Brokk requests additional files (default: 1)"
    )
    
    parser.add_argument(
        "--agent",
        choices=["code", "architect", "search-tasks"],
        default="code",
        help="Brokk agent to use: code (direct coding), architect (planning + coding), or search-tasks (research + task execution) (default: code)"
    )
    
    parser.add_argument(
        "--no-reset",
        action="store_true",
        help="Don't reset repositories to clean state before evaluation (default: repositories are reset)"
    )
    
    parser.add_argument(
        "--resume",
        action="store_true",
        help="Automatically resume from checkpoint if found (non-interactive)"
    )
    
    parser.add_argument(
        "--force-fresh",
        action="store_true",
        help="Ignore any existing checkpoint and start fresh"
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
    
    # Create output directory
    if args.output_dir:
        output_dir = Path(args.output_dir)
    else:
        # Default: swe_bench_tests/{timestamp}_{model_name}
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        safe_model_name = args.model_name.replace("/", "_").replace(" ", "_")
        output_dir = Path("swe_bench_tests") / f"{timestamp}_{safe_model_name}"
    
    output_dir.mkdir(parents=True, exist_ok=True)
    log_success(f"Created output directory: {output_dir}")
    
    # Check for existing checkpoint and prompt to resume
    checkpoint_data = None
    resume_from_checkpoint = False
    
    if args.force_fresh:
        # User wants fresh start, delete any checkpoint
        checkpoints_dir = output_dir / "checkpoints"
        checkpoint_file = checkpoints_dir / "checkpoint.json"
        if checkpoint_file.exists():
            checkpoint_file.unlink()
            log_info("Checkpoint file deleted (--force-fresh flag)")
    else:
        # Check for checkpoint
        checkpoint_data = load_checkpoint(output_dir)
        
        if checkpoint_data:
            if args.resume:
                # Non-interactive resume
                log_success("Resuming from checkpoint (--resume flag)")
                resume_from_checkpoint = True
            else:
                # Interactive prompt
                resume_from_checkpoint = prompt_resume_checkpoint(checkpoint_data)
                if not resume_from_checkpoint:
                    # User chose not to resume, delete checkpoint
                    checkpoints_dir = output_dir / "checkpoints"
                    checkpoint_file = checkpoints_dir / "checkpoint.json"
                    if checkpoint_file.exists():
                        checkpoint_file.unlink()
                    checkpoint_data = None
    
    # Run evaluation
    eval_start_time = time.time()
    results = evaluate_instances(
        available_instances,
        repo_mapping,
        template_path,
        output_dir,
        args.max_instances,
        args.model_name,
        args.max_retries,
        args.agent,
        reset_repo=not args.no_reset,  # Invert the flag
        checkpoint_data=checkpoint_data,
        eval_start_time=eval_start_time
    )
    eval_end_time = time.time()
    
    # Save all output files
    preds_file = output_dir / "preds.json"
    results_file = output_dir / "results.json"
    diagnostics_file = output_dir / "diagnostics.json"
    
    save_predictions(results, str(preds_file))
    save_results_json(results, str(results_file))
    save_diagnostics(results, eval_start_time, eval_end_time, args.model_name, str(diagnostics_file))
    
    # Clean up checkpoint directory after successful completion
    checkpoints_dir = output_dir / "checkpoints"
    checkpoint_file = checkpoints_dir / "checkpoint.json"
    if checkpoint_file.exists():
        checkpoint_file.unlink()
        log_info("Checkpoint file removed (evaluation completed successfully)")
        # Try to remove checkpoints directory if empty
        try:
            checkpoints_dir.rmdir()
            log_info("Checkpoints directory removed")
        except:
            pass  # Directory not empty or other issue, that's okay
    
    # Print summary
    log_status(f"\n{'='*60}", Colors.BOLD)
    log_status("OUTPUT FILES", Colors.BOLD)
    log_status(f"{'='*60}", Colors.BOLD)
    log_success(f"Output directory: {output_dir}")
    log_success(f"  - preds.json: Model predictions for SWE-bench evaluation")
    log_success(f"  - results.json: Summary statistics (needs test results for resolve/unresolved)")
    log_success(f"  - diagnostics.json: Performance metrics and system info")
    
    log_success("\nEvaluation completed successfully!")

if __name__ == "__main__":
    import os
    main()
