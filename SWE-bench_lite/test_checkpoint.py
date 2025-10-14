#!/usr/bin/env python3
"""
Test script to verify checkpoint/resume functionality.

This script simulates a simple evaluation run and tests:
1. Checkpoint creation after each "instance"
2. Resume from checkpoint
3. Completion and cleanup

Run with: python3 SWE-bench_lite/test_checkpoint.py
"""

import json
import time
import sys
from pathlib import Path
from datetime import datetime

# Colors for output
class Colors:
    GREEN = '\033[92m'
    YELLOW = '\033[93m'
    RED = '\033[91m'
    BLUE = '\033[94m'
    BOLD = '\033[1m'
    END = '\033[0m'

def log_info(msg):
    print(f"{Colors.BLUE}[INFO]{Colors.END} {msg}")

def log_success(msg):
    print(f"{Colors.GREEN}[SUCCESS]{Colors.END} {msg}")

def log_error(msg):
    print(f"{Colors.RED}[ERROR]{Colors.END} {msg}")

def log_warning(msg):
    print(f"{Colors.YELLOW}[WARNING]{Colors.END} {msg}")

def save_checkpoint(output_dir: Path, completed: list, results: dict, start_time: float):
    """Save a checkpoint file."""
    checkpoints_dir = output_dir / "checkpoints"
    checkpoints_dir.mkdir(exist_ok=True)
    
    checkpoint_file = checkpoints_dir / "checkpoint.json"
    checkpoint_data = {
        "completed_instances": completed,
        "results": results,
        "eval_start_time": start_time,
        "last_checkpoint": datetime.now().isoformat(),
        "checkpoint_count": len(completed)
    }
    
    temp_file = checkpoints_dir / "checkpoint.tmp.json"
    with open(temp_file, 'w') as f:
        json.dump(checkpoint_data, f, indent=2)
    temp_file.rename(checkpoint_file)
    
    log_info(f"Checkpoint saved ({len(completed)} instances)")

def load_checkpoint(output_dir: Path):
    """Load a checkpoint file."""
    checkpoints_dir = output_dir / "checkpoints"
    checkpoint_file = checkpoints_dir / "checkpoint.json"
    if not checkpoint_file.exists():
        return None
    
    with open(checkpoint_file, 'r') as f:
        return json.load(f)

def test_checkpoint_functionality():
    """Test checkpoint save/load cycle."""
    log_info("Starting checkpoint functionality test...")
    
    # Create test output directory
    output_dir = Path("swe_bench_tests/checkpoint_test")
    output_dir.mkdir(parents=True, exist_ok=True)
    log_success(f"Created test directory: {output_dir}")
    
    # Test 1: Create initial checkpoint
    log_info("\n=== Test 1: Creating initial checkpoint ===")
    start_time = time.time()
    completed = ["instance_1", "instance_2"]
    results = {
        "instance_1": {"success": True, "patch": "diff --git..."},
        "instance_2": {"success": True, "patch": "diff --git..."}
    }
    save_checkpoint(output_dir, completed, results, start_time)
    
    checkpoints_dir = output_dir / "checkpoints"
    checkpoint_file = checkpoints_dir / "checkpoint.json"
    if checkpoint_file.exists():
        log_success("Checkpoint file created successfully")
    else:
        log_error("Checkpoint file not created!")
        return False
    
    # Test 2: Load checkpoint
    log_info("\n=== Test 2: Loading checkpoint ===")
    loaded_data = load_checkpoint(output_dir)
    if loaded_data:
        log_success("Checkpoint loaded successfully")
        log_info(f"  Completed instances: {len(loaded_data['completed_instances'])}")
        log_info(f"  Last checkpoint: {loaded_data['last_checkpoint']}")
        
        # Verify data integrity
        if loaded_data['completed_instances'] == completed:
            log_success("Data integrity verified")
        else:
            log_error("Data mismatch!")
            return False
    else:
        log_error("Failed to load checkpoint!")
        return False
    
    # Test 3: Resume and add more instances
    log_info("\n=== Test 3: Simulating resume with additional instances ===")
    completed.extend(["instance_3", "instance_4"])
    results["instance_3"] = {"success": True, "patch": "diff --git..."}
    results["instance_4"] = {"success": False, "error": "Test error"}
    save_checkpoint(output_dir, completed, results, start_time)
    log_success("Checkpoint updated with new instances")
    
    # Verify update
    loaded_data = load_checkpoint(output_dir)
    if len(loaded_data['completed_instances']) == 4:
        log_success("Resume simulation successful")
    else:
        log_error("Resume failed!")
        return False
    
    # Test 4: Atomic write (check for .tmp file cleanup)
    log_info("\n=== Test 4: Verifying atomic write ===")
    temp_file = checkpoints_dir / "checkpoint.tmp.json"
    if not temp_file.exists():
        log_success("Temporary file cleaned up (atomic write working)")
    else:
        log_warning("Temporary file still exists")
    
    # Test 5: Checkpoint file structure
    log_info("\n=== Test 5: Verifying checkpoint structure ===")
    required_keys = ["completed_instances", "results", "eval_start_time", "last_checkpoint", "checkpoint_count"]
    if all(key in loaded_data for key in required_keys):
        log_success("Checkpoint structure is valid")
    else:
        log_error("Missing required keys in checkpoint!")
        return False
    
    # Test 6: Cleanup
    log_info("\n=== Test 6: Cleanup ===")
    checkpoint_file.unlink()
    if not checkpoint_file.exists():
        log_success("Checkpoint file deleted successfully")
    else:
        log_error("Failed to delete checkpoint file!")
        return False
    
    # Clean up checkpoints directory
    try:
        checkpoints_dir.rmdir()
        log_success("Checkpoints directory cleaned up")
    except Exception as e:
        log_warning(f"Failed to remove checkpoints directory: {e}")
    
    # Clean up test directory
    try:
        output_dir.rmdir()
        log_success("Test directory cleaned up")
    except:
        log_warning("Test directory not empty (may contain other files)")
    
    return True

def main():
    print(f"\n{Colors.BOLD}{'='*60}{Colors.END}")
    print(f"{Colors.BOLD}Checkpoint Functionality Test Suite{Colors.END}")
    print(f"{Colors.BOLD}{'='*60}{Colors.END}\n")
    
    success = test_checkpoint_functionality()
    
    print(f"\n{Colors.BOLD}{'='*60}{Colors.END}")
    if success:
        log_success("All tests passed!")
        print(f"{Colors.BOLD}{'='*60}{Colors.END}\n")
        return 0
    else:
        log_error("Some tests failed!")
        print(f"{Colors.BOLD}{'='*60}{Colors.END}\n")
        return 1

if __name__ == "__main__":
    sys.exit(main())

