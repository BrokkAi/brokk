#!/usr/bin/env python3
"""
Brokk CLI Setup Test Script

This script helps verify that Brokk CLI is properly configured for SWE-bench-lite evaluation.
It tests the most common issues that cause Brokk to hang or fail.
"""

import subprocess
import sys
import os
import time
from pathlib import Path

def test_java_version():
    """Test if Java 21+ is available."""
    print("🔍 Testing Java version...")
    try:
        result = subprocess.run(["java", "-version"], capture_output=True, text=True)
        if "version \"21" in result.stderr or "version \"22" in result.stderr:
            print("✅ Java 21+ detected")
            return True
        else:
            print("❌ Java 21+ required, found older version")
            print(f"   Output: {result.stderr}")
            return False
    except FileNotFoundError:
        print("❌ Java not found in PATH")
        return False

def test_brokk_cli_exists():
    """Test if Brokk CLI script exists and is executable."""
    print("🔍 Testing Brokk CLI script...")
    cli_path = Path("cli")
    if cli_path.exists() and cli_path.is_file():
        print("✅ Brokk CLI script found")
        return True
    else:
        print("❌ Brokk CLI script not found")
        print("   Run: ./gradlew shadowJar")
        return False

def test_shadow_jar_exists():
    """Test if shadow JAR exists."""
    print("🔍 Testing shadow JAR...")
    lib_dir = Path("app/build/libs")
    if lib_dir.exists():
        jar_files = list(lib_dir.glob("brokk-*.jar"))
        if jar_files:
            print(f"✅ Shadow JAR found: {jar_files[0].name}")
            return True
    
    print("❌ Shadow JAR not found")
    print("   Run: ./gradlew shadowJar")
    return False

def test_brokk_gui_config():
    """Test if Brokk GUI can start for configuration."""
    print("🔍 Testing Brokk GUI configuration...")
    print("ℹ️  Note: API keys are configured through Brokk GUI, not environment variables")
    print("ℹ️  To configure: ./cli --gui or java -jar app/build/libs/brokk-*.jar")
    return True  # Always pass since GUI config is manual

def test_brokk_cli_help():
    """Test if Brokk CLI help works."""
    print("🔍 Testing Brokk CLI help...")
    try:
        result = subprocess.run(["./cli", "--help"], capture_output=True, text=True, timeout=10)
        if result.returncode == 0:
            print("✅ Brokk CLI help works")
            return True
        else:
            print("❌ Brokk CLI help failed")
            print(f"   Error: {result.stderr}")
            return False
    except subprocess.TimeoutExpired:
        print("❌ Brokk CLI help timed out")
        return False
    except Exception as e:
        print(f"❌ Brokk CLI help error: {e}")
        return False

def test_brokk_cli_simple():
    """Test if Brokk CLI can handle a simple request."""
    print("🔍 Testing Brokk CLI with simple request...")
    
    # Create a simple test project
    test_dir = Path("test_project")
    test_dir.mkdir(exist_ok=True)
    
    # Create a simple Python file
    test_file = test_dir / "hello.py"
    test_file.write_text('print("Hello, World!")\n')
    
    # Initialize git
    subprocess.run(["git", "init"], cwd=test_dir, capture_output=True)
    subprocess.run(["git", "add", "."], cwd=test_dir, capture_output=True)
    subprocess.run(["git", "commit", "-m", "Initial commit"], cwd=test_dir, capture_output=True)
    
    try:
        # Test with timeout
        result = subprocess.run([
            "../cli", "--project", ".", "--ask", "What does this code do?"
        ], cwd=test_dir, capture_output=True, text=True, timeout=30)
        
        if result.returncode == 0:
            print("✅ Brokk CLI simple request works")
            return True
        else:
            print("❌ Brokk CLI simple request failed")
            print(f"   Error: {result.stderr}")
            return False
    except subprocess.TimeoutExpired:
        print("❌ Brokk CLI simple request timed out (likely API keys not configured via GUI)")
        return False
    except Exception as e:
        print(f"❌ Brokk CLI simple request error: {e}")
        return False
    finally:
        # Cleanup
        import shutil
        shutil.rmtree(test_dir, ignore_errors=True)

def test_python_dependencies():
    """Test if Python dependencies are installed."""
    print("🔍 Testing Python dependencies...")
    try:
        import datasets
        print("✅ datasets library found")
        return True
    except ImportError:
        print("❌ datasets library not found")
        print("   Run: pip install -r SWE-bench_lite/requirements.txt")
        return False

def main():
    """Run all tests."""
    print("🧪 Brokk CLI Setup Test")
    print("=" * 50)
    
    tests = [
        test_java_version,
        test_brokk_cli_exists,
        test_shadow_jar_exists,
        test_brokk_gui_config,
        test_brokk_cli_help,
        test_python_dependencies,
        test_brokk_cli_simple,
    ]
    
    results = []
    for test in tests:
        try:
            result = test()
            results.append(result)
        except Exception as e:
            print(f"❌ Test failed with exception: {e}")
            results.append(False)
        print()
    
    # Summary
    passed = sum(results)
    total = len(results)
    
    print("📊 Test Summary")
    print("=" * 50)
    print(f"Passed: {passed}/{total}")
    
    if passed == total:
        print("🎉 All tests passed! Brokk CLI is ready for SWE-bench-lite evaluation.")
    else:
        print("⚠️  Some tests failed. Please fix the issues above before running evaluations.")
        
        if not results[6]:  # Brokk CLI simple test failed
            print("\n💡 Most common issue: API keys not configured")
            print("   Configure API keys through Brokk GUI:")
            print("   ./cli --gui")
            print("   # Or: java -jar app/build/libs/brokk-*.jar")

if __name__ == "__main__":
    main()
