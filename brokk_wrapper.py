import argparse
import subprocess
import json
import os
import shlex
import sys
import tempfile
from pathlib import Path
import platform
import time
from datetime import datetime

def _now_ts() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def _log(line: str):
    print(f"[{_now_ts()}] {line}")


def run_command(command, extra_env=None, cwd=None, check=True):
    """Runs a command and returns its standard output."""
    env = None
    if extra_env:
        env = os.environ.copy()
        env.update(extra_env)

    try:
        start = time.time()
        _log(f"Running command: {' '.join(command)}")
        result = subprocess.run(
            command,
            check=check,
            capture_output=True,
            text=True,
            encoding='utf-8',
            env=env,
            cwd=cwd
        )
        duration = int(time.time() - start)
        _log(f"Command finished (rc={result.returncode}) in {duration}s")
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
    _log("Checking if Brokk CLI JAR needs to be built...")
    
    # Check if JAR exists
    libs_dir = project_root / "app" / "build" / "libs"
    jar_files = list(libs_dir.glob("brokk*.jar")) if libs_dir.exists() else []
    
    if not jar_files:
        _log("Brokk JAR not found. Building...")
        build_brokk(project_root)
    else:
        _log(f"Found Brokk JAR: {jar_files[0]}")
    
    return find_brokk_jar(project_root)


def build_brokk(project_root):
    """Build the Brokk CLI JAR using gradlew."""
    gradlew_cmd = "./gradlew" if platform.system() != "Windows" else "gradlew.bat"
    gradlew_path = project_root / gradlew_cmd
    
    if not gradlew_path.exists():
        print(f"Error: {gradlew_cmd} not found in {project_root}", file=sys.stderr)
        sys.exit(1)
    
    _log("Building Brokk JAR with shadowJar task...")
    stdout, stderr, returncode = run_command([str(gradlew_path), "shadowJar"], cwd=project_root)
    
    if returncode != 0:
        print("Failed to build Brokk JAR", file=sys.stderr)
        sys.exit(1)
    
    _log("Brokk JAR built successfully")


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
        "-ea",
        "-XX:+UseParallelGC",
        "-Xmx2G",
        "-Djava.awt.headless=true",
        "-cp", str(jar_path),
        "io.github.jbellis.brokk.cli.BrokkCli",
        "--project", str(project_path)
    ]
    
    if additional_args:
        command.extend(additional_args)
    
    _log(f"Executing Brokk CLI: {' '.join(command[:8])} ... [--project {project_path} ...]")
    _log(f"DEBUG: Full command: {' '.join(command)}")
    _log(f"DEBUG: Prompt text length: {len(instructions)} characters")
    _log(f"DEBUG: Prompt preview: {instructions[:200]}...")
    
    # Use streaming execution with heartbeats for long-running Brokk CLI
    start = time.time()
    last_heartbeat = start
    heartbeat_interval = 30  # seconds
    max_runtime = 600  # 10 minutes timeout
    
    _log(f"DEBUG: About to create Brokk CLI subprocess")
    try:
        proc = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding='utf-8',
            bufsize=1
        )
        _log(f"DEBUG: Brokk CLI subprocess created, PID: {proc.pid}")

        # Use communicate with timeout to avoid hanging
        _log(f"DEBUG: Using communicate with {max_runtime}s timeout")
        try:
            stdout, stderr = proc.communicate(timeout=max_runtime)
            returncode = proc.returncode
            _log(f"DEBUG: Brokk CLI finished with returncode: {returncode}")
            _log(f"DEBUG: stdout length: {len(stdout)}, stderr length: {len(stderr)}")
        except subprocess.TimeoutExpired:
            _log(f"ERROR: Brokk CLI timed out after {max_runtime}s, terminating process")
            proc.kill()
            stdout, stderr = proc.communicate()
            returncode = proc.returncode
            _log(f"DEBUG: Process killed, final returncode: {returncode}")
        
        duration = int(time.time() - start)
        _log(f"Brokk CLI finished (rc={returncode}) in {duration}s")
        
        return stdout, stderr, returncode
        
    except Exception as e:
        _log(f"Error executing Brokk CLI: {e}")
        return "", str(e), 1

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
        "--mode",
        choices=["architect", "code", "ask", "search"],
        default="architect",
        help="Which Brokk action to run (default: architect)."
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
        "--deepscan",
        action="store_true",
        help="Enable Brokk Deep Scan to auto-suggest relevant context."
    )
    parser.add_argument(
        "--no_deepscan",
        action="store_true",
        help="Disable Deep Scan even if enabled elsewhere."
    )
    parser.add_argument(
        "--prime_context",
        action="store_true",
        help="Prime workspace with language-aware summaries and important config files."
    )
    parser.add_argument(
        "--summary_globs",
        nargs="*",
        help="Optional globs to summarize into workspace (e.g., '**/*.py')."
    )
    parser.add_argument(
        "--extra_read",
        nargs="*",
        help="Optional additional filenames to add as read-only context (resolved fuzzily)."
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

    _log("DEBUG: About to parse arguments")
    args = parser.parse_args()
    _log(f"DEBUG: Arguments parsed successfully: {vars(args)}")

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

    _log(f"Brokk project root: {brokk_root}")
    _log(f"Target project: {target_project}")

    # Ensure Brokk CLI is built
    try:
        jar_path = ensure_brokk_built(brokk_root)
        _log(f"Using Brokk JAR: {jar_path}")
    except Exception as e:
        print(f"Error building Brokk CLI: {e}", file=sys.stderr)
        sys.exit(1)

    # Prepare Brokk CLI arguments
    cli_args = []
    if args.model:
        cli_args.extend(["--model", args.model])

    # Prepare prompt: if instructions starts with '@', read file content to avoid picocli @-expansion
    prompt_text = args.instructions
    try:
        if isinstance(prompt_text, str) and len(prompt_text) > 1 and prompt_text[0] == '@':
            at_path = Path(prompt_text[1:])
            if at_path.exists():
                _log(f"DEBUG: Reading prompt from file: {at_path}")
                prompt_text = at_path.read_text(encoding="utf-8")
                _log(f"DEBUG: Read {len(prompt_text)} characters from file")
            else:
                _log(f"DEBUG: @file path does not exist: {at_path}")
        else:
            _log(f"DEBUG: Using prompt text directly (length: {len(prompt_text)})")
    except Exception as e:
        _log(f"DEBUG: Error reading @file, using original: {e}")
        # Fallback to original instructions if read fails
        prompt_text = args.instructions

    # Select action
    action_flag: list[str]
    if args.mode == "architect":
        action_flag = ["--architect", prompt_text]
    elif args.mode == "code":
        action_flag = ["--code", prompt_text]
    elif args.mode == "ask":
        action_flag = ["--ask", prompt_text]
    else:
        action_flag = ["--search", prompt_text]

    # Deep Scan toggle: default on for architect/search, off otherwise unless explicitly set
    enable_deepscan = False
    if args.no_deepscan:
        enable_deepscan = False
    elif args.deepscan:
        enable_deepscan = True
    elif args.mode in ("architect", "search"):
        enable_deepscan = True
    # Do not add --deepscan here to avoid duplicates; it will be appended once in final_args

    # Context priming: add file summaries and key config files
    def _detect_langs(root: Path) -> set[str]:
        exts = set()
        try:
            for dirpath, _, filenames in os.walk(root):
                # Skip hidden and .git dirs quickly
                base = os.path.basename(dirpath)
                if base.startswith('.') or base in {"node_modules", "build", "dist", "target", "out", "venv", ".venv", "__pycache__"}:
                    continue
                for fn in filenames:
                    ext = os.path.splitext(fn)[1].lower()
                    if ext:
                        exts.add(ext)
        except Exception:
            pass
        langs = set()
        if ".py" in exts:
            langs.add("python")
        if ".java" in exts:
            langs.add("java")
        if any(e in exts for e in {".ts", ".tsx", ".js", ".jsx"}):
            langs.add("tsjs")
        if ".go" in exts:
            langs.add("go")
        if ".cs" in exts:
            langs.add("csharp")
        if any(e in exts for e in {".c", ".cpp", ".cc", ".h", ".hpp"}):
            langs.add("c_cpp")
        if ".rs" in exts:
            langs.add("rust")
        return langs

    if args.prime_context:
        # Summaries globs
        summary_globs = list(args.summary_globs or [])
        if not summary_globs:
            langs = _detect_langs(target_project)
            if "python" in langs:
                summary_globs.append("**/*.py")
            if "java" in langs:
                summary_globs.append("**/*.java")
            if "tsjs" in langs:
                summary_globs.append("**/*.{ts,tsx,js,jsx}")
            if "go" in langs:
                summary_globs.append("**/*.go")
            if "csharp" in langs:
                summary_globs.append("**/*.cs")
            if "c_cpp" in langs:
                summary_globs.append("**/*.{c,cpp,cc,h,hpp}")
            if "rust" in langs:
                summary_globs.append("**/*.rs")

        for g in summary_globs:
            cli_args.extend(["--add-summary-file", g])

        # Key config/docs as read-only context (fuzzy resolution by Brokk)
        default_read = [
            "README.md", "CONTRIBUTING.md", "pyproject.toml", "requirements.txt", "setup.py", "setup.cfg",
            "tox.ini", "pytest.ini", "package.json", "tsconfig.json", "Makefile", "CMakeLists.txt",
        ]
        if args.extra_read:
            default_read.extend(args.extra_read)
        for name in default_read:
            cli_args.extend(["--read", name])

    if args.dry_run:
        _log("DRY RUN - Would execute:")
        _log(
            f"java -cp {jar_path} io.github.jbellis.brokk.cli.BrokkCli --project {target_project} "
            + ("--deepscan " if enable_deepscan else "")
            + (" "+" ".join(shlex.quote(a) for a in cli_args) if cli_args else "")
            + (" "+" ".join(shlex.quote(a) for a in action_flag) if action_flag else "")
        )
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
    _log("Running Brokk to apply changes based on instructions...")
    try:
        # Compose final CLI command pieces for execution
        final_args = []
        if enable_deepscan:
            final_args.append("--deepscan")
        final_args.extend(cli_args)
        final_args.extend(action_flag)

        stdout, stderr, returncode = execute_brokk_cli(jar_path, target_project, "", final_args)
        
        if args.verbose:
            _log("=== Brokk stdout ===")
            print(stdout)
            _log("=== Brokk stderr ===") 
            print(stderr)
            _log("=== End Brokk output ===")

        # Write logs if requested
        if log_dir_path:
            try:
                with open(log_dir_path / "brokk_stdout.txt", "w", encoding="utf-8") as f:
                    f.write(stdout or "")
                with open(log_dir_path / "brokk_stderr.txt", "w", encoding="utf-8") as f:
                    f.write(stderr or "")
                # Reconstruct and save the effective command (approximate)
                cmd_preview = (
                    f"java -cp {jar_path} io.github.jbellis.brokk.cli.BrokkCli --project {target_project} "
                    + ("--deepscan " if enable_deepscan else "")
                    + (" "+" ".join(final_args) if final_args else "")
                )
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
            _log("Brokk finished successfully.")
            
    except Exception as e:
        print(f"Error executing Brokk CLI: {e}", file=sys.stderr)
        sys.exit(1)

    _log("Capturing git diff...")

    # Include untracked files in diff by marking them as intent-to-add
    try:
        untracked_out, _, _ = run_command(["git", "ls-files", "--others", "--exclude-standard"], cwd=target_project, check=False)
        untracked = [line.strip() for line in untracked_out.splitlines() if line.strip()]
        if untracked:
            _log(f"Staging {len(untracked)} untracked files as intent-to-add for diff visibility")
            for path in untracked:
                run_command(["git", "add", "-N", path], cwd=target_project, check=False)
    except Exception as e:
        _log(f"DEBUG: Skipping untracked staging due to error: {e}")

    # Capture the git diff of all uncommitted changes in the target project
    try:
        diff_stdout, diff_stderr, diff_returncode = run_command(
            ["git", "diff", "-M", "HEAD"], 
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
            _log(f"Output saved to {output_path}")
        except Exception as e:
            print(f"Error writing to output file {output_path}: {e}", file=sys.stderr)
            sys.exit(1)
    else:
        print(output_str)

if __name__ == "__main__":
    print(f"[{_now_ts()}] DEBUG: brokk_wrapper.py starting up...")
    print(f"[{_now_ts()}] DEBUG: sys.argv = {sys.argv}")
    sys.stdout.flush()
    main()
