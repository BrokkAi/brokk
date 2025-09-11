import argparse
import subprocess
import json
import os
import shlex
import sys
import tempfile
from pathlib import Path
import platform

def run_command(command, extra_env=None, cwd=None, check=True):
    """Runs a command and returns its standard output."""
    env = None
    if extra_env:
        env = os.environ.copy()
        env.update(extra_env)

    try:
        result = subprocess.run(
            command,
            check=check,
            capture_output=True,
            text=True,
            encoding='utf-8',
            env=env,
            cwd=cwd
        )
        if check and result.returncode != 0:
            raise subprocess.CalledProcessError(result.returncode, command, result.stdout, result.stderr)
        return result.stdout, result.stderr, result.returncode
    except FileNotFoundError:
        print(f"Error: Command '{command[0]}' not found. Is it installed and in your PATH?", file=sys.stderr)
        sys.exit(1)
    except subprocess.CalledProcessError as e:
        print(f"Error executing command: {' '.join(command)}", file=sys.stderr)
        print(f"Return code: {e.returncode}", file=sys.stderr)
        print(f"Stdout:\n{e.stdout}", file=sys.stderr)
        print(f"Stderr:\n{e.stderr}", file=sys.stderr)
        sys.exit(1)


def find_project_root():
    """Find the project root directory containing gradlew or build.gradle.kts."""
    current_dir = Path.cwd()
    while current_dir != current_dir.parent:
        if (current_dir / "gradlew").exists() or (current_dir / "build.gradle.kts").exists():
            return current_dir
        current_dir = current_dir.parent
    return None


def ensure_brokk_built(project_root):
    """Ensure that the Brokk CLI JAR is built."""
    print("Checking if Brokk CLI JAR needs to be built...")
    
    # Check if JAR exists
    libs_dir = project_root / "app" / "build" / "libs"
    jar_files = list(libs_dir.glob("brokk*.jar")) if libs_dir.exists() else []
    
    if not jar_files:
        print("Brokk JAR not found. Building...")
        build_brokk(project_root)
    else:
        print(f"Found Brokk JAR: {jar_files[0]}")
    
    return find_brokk_jar(project_root)


def build_brokk(project_root):
    """Build the Brokk CLI JAR using gradlew."""
    gradlew_cmd = "./gradlew" if platform.system() != "Windows" else "gradlew.bat"
    gradlew_path = project_root / gradlew_cmd
    
    if not gradlew_path.exists():
        print(f"Error: {gradlew_cmd} not found in {project_root}", file=sys.stderr)
        sys.exit(1)
    
    print("Building Brokk JAR with shadowJar task...")
    stdout, stderr, returncode = run_command([str(gradlew_path), "shadowJar"], cwd=project_root)
    
    if returncode != 0:
        print("Failed to build Brokk JAR", file=sys.stderr)
        sys.exit(1)
    
    print("Brokk JAR built successfully")


def find_brokk_jar(project_root):
    """Find the most recent Brokk JAR file."""
    libs_dir = project_root / "app" / "build" / "libs"
    if not libs_dir.exists():
        print(f"Error: Build libs directory not found: {libs_dir}", file=sys.stderr)
        sys.exit(1)
    
    jar_files = list(libs_dir.glob("brokk*.jar"))
    if not jar_files:
        print(f"Error: No brokk*.jar found in {libs_dir}", file=sys.stderr)
        sys.exit(1)
    
    # Return the most recently modified JAR
    return max(jar_files, key=lambda p: p.stat().st_mtime)


def execute_brokk_cli(jar_path, project_path, instructions, additional_args=None):
    """Execute the Brokk CLI with the given instructions."""
    command = [
        "java",
        "-ea",  # Enable assertions
        "-XX:+UseParallelGC",  # Use parallel GC for better CLI performance
        "-Xmx2G",  # Increase memory limit for complex tasks
        "-Djava.awt.headless=true",  # Ensure headless mode
        "-cp", str(jar_path),
        "io.github.jbellis.brokk.cli.BrokkCli",
        "--project", str(project_path),
        "--code", instructions
    ]
    
    if additional_args:
        command.extend(additional_args)
    
    print(f"Executing: {' '.join(command[:8])} ... [--project {project_path} --code ...]")
    stdout, stderr, returncode = run_command(command, check=False)
    
    return stdout, stderr, returncode

def main():
    """
    A wrapper for the Brokk CLI tool to automate applying a change and capturing the git diff.

    This script performs the following steps:
    1. Takes natural language instructions for a code change from the command line.
    2. Finds the Brokk project root and ensures the CLI JAR is built.
    3. Executes the Brokk CLI with proper parameters to apply the changes to the codebase.
    4. Captures all uncommitted changes in the current git repository using 'git diff HEAD'.
    5. Formats the resulting diff into one of two formats:
       - Raw git diff string.
       - A JSON object compatible with the SWE-bench format, which includes an 'instance_id'.
    6. Outputs the result to either standard output or a specified file.

    Usage:
        # To get a raw diff printed to the console
        python brokk_wrapper.py "Your natural language instructions for the change"

        # To get SWE-bench formatted output in a file
        python brokk_wrapper.py "Fix the bug in the login page" --instance_id "my-project-123" --output_file "preds.json"

        # To specify a different target project directory
        python brokk_wrapper.py "Add error handling" --target_project "/path/to/target/project"

    Important Note on Repeatability:
    This script captures *all* uncommitted git changes. If you run the script multiple times
    without committing or resetting the changes in between, the subsequent runs will include
    the changes from previous runs in their diff output. For isolated changes, ensure you
    reset your repository's state (e.g., 'git reset --hard HEAD') between runs.
    """
    parser = argparse.ArgumentParser(
        description="A wrapper for Brokk CLI to apply a change and get the git diff.",
        formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "instructions",
        help="The natural language instructions for the code change."
    )
    parser.add_argument(
        "--instance_id",
        help="Optional instance ID for SWE-bench format output."
    )
    parser.add_argument(
        "--output_file",
        help="Optional file path to save the output. Prints to stdout if not provided."
    )
    parser.add_argument(
        "--target_project",
        help="Path to the target project to modify. Defaults to current directory."
    )
    parser.add_argument(
        "--brokk_project",
        help="Path to the Brokk project root (where gradlew exists). Auto-detected if not provided."
    )
    parser.add_argument(
        "--model",
        help="Override the model to use for the code task."
    )
    parser.add_argument(
        "--dry_run",
        action="store_true",
        help="Show what would be executed without actually running Brokk."
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="Show verbose output including Brokk's stdout/stderr."
    )
    parser.add_argument(
        "--log_dir",
        help="Optional directory to write logs (command, stdout, stderr, git diff)."
    )

    args = parser.parse_args()

    # Determine project paths
    if args.brokk_project:
        brokk_root = Path(args.brokk_project).resolve()
    else:
        brokk_root = find_project_root()
        if not brokk_root:
            print("Error: Could not find Brokk project root (directory with gradlew). Use --brokk_project to specify.", file=sys.stderr)
            sys.exit(1)

    target_project = Path(args.target_project).resolve() if args.target_project else Path.cwd()
    
    # Validate paths
    if not brokk_root.exists():
        print(f"Error: Brokk project directory does not exist: {brokk_root}", file=sys.stderr)
        sys.exit(1)
    
    if not target_project.exists():
        print(f"Error: Target project directory does not exist: {target_project}", file=sys.stderr)
        sys.exit(1)
    
    # Check if target project has a git repository
    if not (target_project / ".git").exists():
        print(f"Error: Target project is not a git repository: {target_project}", file=sys.stderr)
        print("Brokk CLI requires the target project to be a Git repository.", file=sys.stderr)
        sys.exit(1)

    print(f"Brokk project root: {brokk_root}")
    print(f"Target project: {target_project}")

    # Ensure Brokk CLI is built
    try:
        jar_path = ensure_brokk_built(brokk_root)
        print(f"Using Brokk JAR: {jar_path}")
    except Exception as e:
        print(f"Error building Brokk CLI: {e}", file=sys.stderr)
        sys.exit(1)

    # Prepare additional arguments
    additional_args = []
    if args.model:
        additional_args.extend(["--model", args.model])

    if args.dry_run:
        print("DRY RUN - Would execute:")
        print(f"java -cp {jar_path} io.github.jbellis.brokk.cli.BrokkCli --project {target_project} --code \"{args.instructions}\"")
        if additional_args:
            print(f"Additional args: {' '.join(additional_args)}")
        return

    # If logging is enabled, ensure directory exists and record command later
    log_dir_path = None
    if args.log_dir:
        try:
            log_dir_path = Path(args.log_dir)
            log_dir_path.mkdir(parents=True, exist_ok=True)
        except Exception as e:
            print(f"Error creating log directory {args.log_dir}: {e}", file=sys.stderr)
            sys.exit(1)

    # Execute Brokk CLI
    print("Running Brokk to apply changes based on instructions...")
    try:
        stdout, stderr, returncode = execute_brokk_cli(jar_path, target_project, args.instructions, additional_args)
        
        if args.verbose:
            print("=== Brokk stdout ===")
            print(stdout)
            print("=== Brokk stderr ===") 
            print(stderr)
            print("=== End Brokk output ===")

        # Write logs if requested
        if log_dir_path:
            try:
                with open(log_dir_path / "brokk_stdout.txt", "w", encoding="utf-8") as f:
                    f.write(stdout or "")
                with open(log_dir_path / "brokk_stderr.txt", "w", encoding="utf-8") as f:
                    f.write(stderr or "")
                # Reconstruct and save the effective command (approximate)
                cmd_preview = f"java -cp {jar_path} io.github.jbellis.brokk.cli.BrokkCli --project {target_project} --code \"{args.instructions}\""
                if additional_args:
                    cmd_preview += " " + " ".join(additional_args)
                with open(log_dir_path / "command.txt", "w", encoding="utf-8") as f:
                    f.write(cmd_preview + "\n")
            except Exception as e:
                print(f"Warning: Failed writing Brokk logs: {e}", file=sys.stderr)
        
        if returncode != 0:
            print(f"Warning: Brokk CLI exited with non-zero status: {returncode}", file=sys.stderr)
            if not args.verbose:
                print("Brokk stderr:", file=sys.stderr)
                print(stderr, file=sys.stderr)
            # Continue to capture diff even if Brokk had issues
        else:
            print("Brokk finished successfully.")
            
    except Exception as e:
        print(f"Error executing Brokk CLI: {e}", file=sys.stderr)
        sys.exit(1)

    print("Capturing git diff...")

    # Capture the git diff of all uncommitted changes in the target project
    try:
        diff_stdout, diff_stderr, diff_returncode = run_command(
            ["git", "diff", "HEAD"], 
            cwd=target_project, 
            check=False
        )
        
        if diff_returncode != 0:
            print(f"Error capturing git diff: {diff_stderr}", file=sys.stderr)
            sys.exit(1)
            
        git_diff = diff_stdout
    except Exception as e:
        print(f"Error running git diff: {e}", file=sys.stderr)
        sys.exit(1)

    if not git_diff.strip():
        print("Warning: No changes detected by 'git diff HEAD'. Brokk might not have made any changes.", file=sys.stderr)
        git_diff = ""

    # Write git diff log if requested
    if log_dir_path:
        try:
            with open(log_dir_path / "git_diff.patch", "w", encoding="utf-8") as f:
                f.write(git_diff or "")
        except Exception as e:
            print(f"Warning: Failed writing git diff log: {e}", file=sys.stderr)

    # Format output
    if args.instance_id:
        # Format output for SWE-bench
        output_data = {
            "instance_id": args.instance_id,
            "model_name_or_path": "BrokkAgent",
            "model_patch": git_diff
        }
        output_str = json.dumps(output_data, indent=2)
    else:
        # Just output the raw diff
        output_str = git_diff

    # Save or print output
    if args.output_file:
        output_path = Path(args.output_file)
        try:
            with open(output_path, 'w', encoding='utf-8') as f:
                f.write(output_str)
            print(f"Output saved to {output_path}")
        except Exception as e:
            print(f"Error writing to output file {output_path}: {e}", file=sys.stderr)
            sys.exit(1)
    else:
        print(output_str)

if __name__ == "__main__":
    main()
