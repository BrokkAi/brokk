#!/usr/bin/env python3
"""
Test script for the Brokk wrapper integration.
"""

import json
import tempfile
import subprocess
import sys
from pathlib import Path


def test_dry_run():
    """Test the dry run functionality."""
    print("Testing dry run...")
    
    result = subprocess.run([
        sys.executable, "brokk_wrapper.py",
        "Add a comment to the main function",
        "--dry_run"
    ], capture_output=True, text=True)
    
    if result.returncode == 0:
        print("PASS: Dry run test passed")
        print(f"Output: {result.stdout[:200]}...")
        return True
    else:
        print("FAIL: Dry run test failed")
        print(f"Error: {result.stderr}")
        return False


def test_swe_bench_format():
    """Test SWE-bench format validation."""
    print("Testing SWE-bench format validation...")
    
    # Create a test prediction file
    test_prediction = {
        "instance_id": "test-001",
        "model_name_or_path": "BrokkAgent",
        "model_patch": "diff --git a/test.py b/test.py\nindex abc123..def456 100644\n--- a/test.py\n+++ b/test.py\n@@ -1,3 +1,4 @@\n def main():\n+    # Added comment\n     pass"
    }
    
    with tempfile.NamedTemporaryFile(mode='w', suffix='.json', delete=False) as f:
        json.dump(test_prediction, f, indent=2)
        temp_file = f.name
    
    try:
        result = subprocess.run([
            sys.executable, "swe_bench_helper.py",
            "validate", temp_file
        ], capture_output=True, text=True)
        
        if result.returncode == 0:
            print("PASS: SWE-bench format validation test passed")
            return True
        else:
            print("FAIL: SWE-bench format validation test failed")
            print(f"Error: {result.stderr}")
            return False
    finally:
        Path(temp_file).unlink(missing_ok=True)


def test_help_output():
    """Test that help output works."""
    print("Testing help output...")
    
    # Test wrapper help
    result = subprocess.run([
        sys.executable, "brokk_wrapper.py", "--help"
    ], capture_output=True, text=True)
    
    if result.returncode == 0 and "wrapper for Brokk CLI" in result.stdout:
        print("PASS: Wrapper help test passed")
    else:
        print("FAIL: Wrapper help test failed")
        return False
    
    # Test helper help
    result = subprocess.run([
        sys.executable, "swe_bench_helper.py", "--help"
    ], capture_output=True, text=True)
    
    if result.returncode == 0 and "SWE-bench-Live helper" in result.stdout:
        print("PASS: Helper help test passed")
        return True
    else:
        print("FAIL: Helper help test failed")
        return False


def test_invalid_swe_bench_format():
    """Test validation of invalid SWE-bench format."""
    print("Testing invalid SWE-bench format validation...")
    
    # Create an invalid prediction file (missing required fields)
    invalid_prediction = {
        "instance_id": "test-002",
        # Missing model_name_or_path and model_patch
    }
    
    with tempfile.NamedTemporaryFile(mode='w', suffix='.json', delete=False) as f:
        json.dump(invalid_prediction, f, indent=2)
        temp_file = f.name
    
    try:
        result = subprocess.run([
            sys.executable, "swe_bench_helper.py",
            "validate", temp_file
        ], capture_output=True, text=True)
        
        # Should fail validation
        if result.returncode != 0:
            print("PASS: Invalid format correctly detected")
            return True
        else:
            print("FAIL: Invalid format not detected (should have failed)")
            return False
    finally:
        Path(temp_file).unlink(missing_ok=True)


def main():
    """Run all tests."""
    print("Running Brokk wrapper tests...\n")
    
    tests = [
        test_help_output,
        test_dry_run,
        test_swe_bench_format,
        test_invalid_swe_bench_format,
    ]
    
    passed = 0
    total = len(tests)
    
    for test in tests:
        try:
            if test():
                passed += 1
        except Exception as e:
            print(f"FAIL: Test {test.__name__} failed with exception: {e}")
        print()
    
    print(f"Tests completed: {passed}/{total} passed")
    
    if passed == total:
        print("SUCCESS: All tests passed!")
        return 0
    else:
        print("ERROR: Some tests failed")
        return 1


if __name__ == "__main__":
    sys.exit(main())
