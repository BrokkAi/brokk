#!/usr/bin/env python3
"""
SWE-bench-Live helper script for Brokk integration.

This script provides utilities for working with SWE-bench-Live datasets and 
formatting Brokk outputs in the correct format.
"""

import argparse
import json
import sys
from pathlib import Path
from typing import Dict, List, Optional, Any, Tuple
import subprocess
import shutil
import time
from datetime import datetime, timedelta
def _now_ts() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def log_info(message: str) -> None:
    print(f"[{_now_ts()}] {message}")


def run_command_stream(
    cmd: List[str],
    cwd: Optional[Path] = None,
    log_file: Optional[Path] = None,
    label: str = "",
    heartbeat_interval: int = 30,
) -> Tuple[int, str, str]:
    """Run a command streaming stdout/stderr and emitting heartbeats.

    Returns: (returncode, collected_stdout, collected_stderr)
    """
    start = time.time()
    last_heartbeat = start
    collected_out: List[str] = []
    collected_err: List[str] = []

    # Open log file if provided
    log_handle = None
    if log_file is not None:
        try:
            log_handle = open(log_file, "a", encoding="utf-8")
        except Exception:
            log_handle = None

    def _write_log(line: str) -> None:
        if log_handle is not None:
            try:
                log_handle.write(line)
                if not line.endswith("\n"):
                    log_handle.write("\n")
            except Exception:
                pass

    log_info(f"Starting: {' '.join(cmd)}" + (f" (label={label})" if label else ""))
    log_info(f"DEBUG: About to create subprocess Popen")
    try:
        proc = subprocess.Popen(
            cmd,
            cwd=(str(cwd) if cwd else None),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            bufsize=1,
        )
        log_info(f"DEBUG: Subprocess created successfully, PID: {proc.pid}")
    except Exception as e:
        log_info(f"DEBUG: Failed to create subprocess: {e}")
        if log_handle is not None:
            log_handle.close()
        raise e

    # Stream loop
    log_info(f"DEBUG: Entering stream loop")
    loop_count = 0
    max_wait_time = 600  # 10 minutes timeout
    try:
        while True:
            loop_count += 1
            if loop_count % 100 == 0:  # Every 100 iterations (~10 seconds)
                log_info(f"DEBUG: Stream loop iteration {loop_count}")
            
            out_line = None
            err_line = None
            if proc.stdout is not None:
                out_line = proc.stdout.readline()
            if proc.stderr is not None:
                err_line = proc.stderr.readline()

            progressed = False
            if out_line:
                collected_out.append(out_line)
                _write_log(out_line.rstrip("\n"))
                progressed = True
                log_info(f"DEBUG: Got stdout line: {out_line.rstrip()[:50]}...")
            if err_line:
                collected_err.append(err_line)
                _write_log(err_line.rstrip("\n"))
                progressed = True
                log_info(f"DEBUG: Got stderr line: {err_line.rstrip()[:50]}...")

            poll_result = proc.poll()
            if poll_result is not None:
                log_info(f"DEBUG: Process finished with returncode: {poll_result}")
                # Drain remainder
                if proc.stdout is not None:
                    remaining_out = proc.stdout.readlines()
                    log_info(f"DEBUG: Draining {len(remaining_out)} remaining stdout lines")
                    for line in remaining_out:
                        collected_out.append(line)
                        _write_log(line.rstrip("\n"))
                if proc.stderr is not None:
                    remaining_err = proc.stderr.readlines()
                    log_info(f"DEBUG: Draining {len(remaining_err)} remaining stderr lines")
                    for line in remaining_err:
                        collected_err.append(line)
                        _write_log(line.rstrip("\n"))
                break

            now = time.time()
            elapsed = int(now - start)
            
            # Timeout check
            if elapsed > max_wait_time:
                log_info(f"ERROR: Process timed out after {max_wait_time}s, terminating")
                try:
                    proc.terminate()
                    time.sleep(5)
                    if proc.poll() is None:
                        proc.kill()
                except Exception:
                    pass
                break
            
            if now - last_heartbeat >= heartbeat_interval:
                log_info(f"HEARTBEAT: Still running{f' {label}' if label else ''}... elapsed {elapsed}s (loop {loop_count})")
                last_heartbeat = now

            if not progressed:
                time.sleep(0.1)
    finally:
        if log_handle is not None:
            try:
                log_handle.flush()
                log_handle.close()
            except Exception:
                pass

    rc = proc.returncode if proc.returncode is not None else -1
    duration = int(time.time() - start)
    log_info(f"Finished (rc={rc}){f' {label}' if label else ''} in {duration}s")
    return rc, "".join(collected_out), "".join(collected_err)


def validate_swe_bench_format(data: Dict[str, Any]) -> List[str]:
    """
    Validate that the output data conforms to SWE-bench format.
    
    Returns:
        List of validation error messages (empty if valid)
    """
    errors = []
    
    required_fields = ["instance_id", "model_name_or_path", "model_patch"]
    for field in required_fields:
        if field not in data:
            errors.append(f"Missing required field: {field}")
    
    if "instance_id" in data and not isinstance(data["instance_id"], str):
        errors.append("instance_id must be a string")
    
    if "model_name_or_path" in data and not isinstance(data["model_name_or_path"], str):
        errors.append("model_name_or_path must be a string")
    
    if "model_patch" in data and not isinstance(data["model_patch"], str):
        errors.append("model_patch must be a string")
    
    # Check for valid git diff format if patch is provided
    if "model_patch" in data and data["model_patch"].strip():
        patch = data["model_patch"]
        if not (patch.startswith("diff --git") or "@@" in patch or patch.startswith("--- ")):
            errors.append("model_patch doesn't appear to be a valid git diff format")
    
    return errors


def create_prediction_file(predictions: List[Dict[str, Any]], output_path: Path) -> None:
    """
    Create a predictions file with multiple instances for SWE-bench evaluation.
    
    Args:
        predictions: List of prediction dictionaries
        output_path: Path to write the predictions file
    """
    try:
        with open(output_path, 'w', encoding='utf-8') as f:
            json.dump(predictions, f, indent=2)
        print(f"Predictions file created: {output_path}")
    except Exception as e:
        print(f"Error writing predictions file: {e}", file=sys.stderr)
        sys.exit(1)


def load_swe_bench_dataset(dataset_path: Path) -> List[Dict[str, Any]]:
    """
    Load a SWE-bench dataset file.
    
    Args:
        dataset_path: Path to the dataset JSON file
        
    Returns:
        List of dataset instances
    """
    try:
        with open(dataset_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
        
        # Handle both single instances and lists
        if isinstance(data, dict):
            return [data]
        elif isinstance(data, list):
            return data
        else:
            print(f"Error: Invalid dataset format in {dataset_path}", file=sys.stderr)
            sys.exit(1)
    except Exception as e:
        print(f"Error loading dataset {dataset_path}: {e}", file=sys.stderr)
        sys.exit(1)


def load_hf_arrow_dataset(dataset_dir: Path) -> List[Dict[str, Any]]:
    """
    Load a HuggingFace Arrow dataset exported locally (directory with train/test/validation folders or dataset_dict.json).

    This function uses the datasets library if available; otherwise, it reads minimal JSON lines if present.
    """
    try:
        import datasets  # type: ignore
    except Exception as e:
        print("Error: The 'datasets' package is required to load HF Arrow datasets. Install with: pip install datasets", file=sys.stderr)
        sys.exit(1)

    if not dataset_dir.exists():
        print(f"Error: Dataset directory not found: {dataset_dir}", file=sys.stderr)
        sys.exit(1)

    try:
        dset = datasets.load_from_disk(str(dataset_dir))
    except Exception as e:
        print(f"Error loading HF dataset from {dataset_dir}: {e}", file=sys.stderr)
        sys.exit(1)

    # Support DatasetDict or Dataset
    instances: List[Dict[str, Any]] = []
    if isinstance(dset, dict) or hasattr(dset, "keys"):
        # Prefer 'test' then 'validation' then 'train'
        split = None
        for name in ["test", "validation", "val", "dev", "train"]:
            if name in dset:
                split = dset[name]
                print(f"Using split: {name}")
                break
        if split is None:
            print("Error: No recognized split found in dataset (expected one of: test, validation, val, dev, train)", file=sys.stderr)
            sys.exit(1)
        items = split
    else:
        items = dset

    # Convert to list of dicts
    try:
        for rec in items:
            # rec is a dict-like row
            instances.append(dict(rec))
    except Exception as e:
        print(f"Error iterating dataset rows: {e}", file=sys.stderr)
        sys.exit(1)

    log_info(f"Loaded {len(instances)} instances from HF Arrow dataset")
    return instances


def extract_problem_statement(instance: Dict[str, Any]) -> Tuple[str, str]:
    """
    Extract instance_id and problem statement with flexible field names.
    Returns (instance_id, problem_statement).
    """
    # Try common field names for instance_id
    instance_id = (
        instance.get("instance_id")
        or instance.get("id")
        or instance.get("issue_id")
        or instance.get("_id")
        or "unknown"
    )

    # Try common field names for problem statement / instruction
    for key in [
        "problem_statement",
        "instruction",
        "prompt",
        "task",
        "description",
    ]:
        val = instance.get(key)
        if isinstance(val, str) and val.strip():
            return str(instance_id), val

    # Fallback: try to synthesize from title + body
    title = instance.get("title")
    body = instance.get("body") or instance.get("text")
    if isinstance(title, str) or isinstance(body, str):
        parts = []
        if isinstance(title, str):
            parts.append(title)
        if isinstance(body, str):
            parts.append(body)
        return str(instance_id), "\n\n".join(parts)

    return str(instance_id), ""


def prepare_repo_for_instance(instance: Dict[str, Any], workspace_root: Path, logs_dir: Optional[Path] = None) -> Path:
    """
    Clone/fetch and checkout the instance repo at base_commit under workspace_root.
    Returns the checked-out repository path.
    """
    repo_slug = instance.get("repo")
    base_commit = instance.get("base_commit")
    if not repo_slug or not base_commit:
        raise RuntimeError("Instance missing 'repo' or 'base_commit' fields")

    # Derive target dir: <workspace_root>/<owner>/<repo>/<shortsha>
    try:
        owner, name = repo_slug.split("/", 1)
    except ValueError:
        owner, name = "unknown", repo_slug.replace("/", "_")
    short = str(base_commit)[:12]
    repo_dir = workspace_root / owner / name / short
    repo_parent = repo_dir.parent
    repo_parent.mkdir(parents=True, exist_ok=True)

    # If already prepared, return
    if (repo_dir / ".git").exists():
        return repo_dir

    # Clone and checkout commit
    origin = f"https://github.com/{repo_slug}.git"
    tmp_dir = repo_parent / f".{name}-{short}.tmp"
    if tmp_dir.exists():
        shutil.rmtree(tmp_dir, ignore_errors=True)

    # Log helper
    def _log_write(fname: str, content: str) -> None:
        if logs_dir is None:
            return
        try:
            with open(logs_dir / fname, "a", encoding="utf-8") as f:
                f.write(content)
                if not content.endswith("\n"):
                    f.write("\n")
        except Exception:
            pass

    _log_write("checkout.txt", f"Cloning {origin} -> {tmp_dir}")
    rc, out, err = run_command_stream(
        ["git", "clone", "--filter=blob:none", "--no-checkout", origin, str(tmp_dir)],
        log_file=(logs_dir / "checkout.txt" if logs_dir else None),
        label=f"git clone {repo_slug}"
    )
    if rc != 0:
        raise RuntimeError(f"git clone failed for {origin}: {err}")

    _log_write("checkout.txt", f"Fetching commit {base_commit}")
    rc, out, err = run_command_stream(
        ["git", "-C", str(tmp_dir), "fetch", "origin", str(base_commit), "--depth", "1"],
        log_file=(logs_dir / "checkout.txt" if logs_dir else None),
        label=f"git fetch {short}"
    )
    if rc != 0:
        raise RuntimeError(f"git fetch failed for {origin} {base_commit}: {err}")

    _log_write("checkout.txt", f"Checking out {base_commit} (detached)")
    rc, out, err = run_command_stream(
        ["git", "-C", str(tmp_dir), "checkout", "--detach", str(base_commit)],
        log_file=(logs_dir / "checkout.txt" if logs_dir else None),
        label=f"git checkout {short}"
    )
    if rc != 0:
        raise RuntimeError(f"git checkout failed for {base_commit}: {err}")

    # Move prepared repo into final location
    tmp_dir.rename(repo_dir)
    return repo_dir


def run_brokk_on_instance(
    instance: Dict[str, Any], 
    brokk_wrapper_path: Path,
    target_project: Path,
    additional_args: Optional[List[str]] = None,
    log_root: Optional[Path] = None
) -> Dict[str, Any]:
    """
    Run Brokk wrapper on a single SWE-bench instance.
    
    Args:
        instance: SWE-bench instance dictionary
        brokk_wrapper_path: Path to the brokk_wrapper.py script
        target_project: Path to the target project
        additional_args: Additional arguments to pass to brokk_wrapper.py
        
    Returns:
        Prediction dictionary for SWE-bench
    """
    instance_id, problem_statement = extract_problem_statement(instance)
    
    if not problem_statement:
        print(f"Warning: No problem statement found for instance {instance_id}", file=sys.stderr)
        return {
            "instance_id": instance_id,
            "model_name_or_path": "BrokkAgent",
            "model_patch": ""
        }
    
    # Build command - use temp file for long problem statements to avoid command line issues
    import tempfile
    problem_file = None
    if len(problem_statement) > 500 or '\n' in problem_statement:
        # Write to temp file and use @file syntax
        with tempfile.NamedTemporaryFile(mode='w', suffix='.txt', delete=False, encoding='utf-8') as f:
            f.write(problem_statement)
            problem_file = f.name
        cmd = [
            sys.executable, str(brokk_wrapper_path),
            f"@{problem_file}",
            "--instance_id", instance_id,
            "--target_project", str(target_project)
        ]
    else:
        cmd = [
            sys.executable, str(brokk_wrapper_path),
            problem_statement,
            "--instance_id", instance_id,
            "--target_project", str(target_project)
        ]
    
    if additional_args:
        cmd.extend(additional_args)
    
    # Add output to a temporary file to capture the result
    import tempfile
    with tempfile.NamedTemporaryFile(mode='w+', suffix='.json', delete=False) as tmp_file:
        tmp_path = tmp_file.name
    
    cmd.extend(["--output_file", tmp_path])

    # Configure per-instance logging directory if requested
    if log_root is not None:
        inst_dir = log_root / str(instance_id)
        try:
            inst_dir.mkdir(parents=True, exist_ok=True)
            cmd.extend(["--log_dir", str(inst_dir)])
        except Exception as e:
            print(f"Warning: Could not create log directory {inst_dir}: {e}", file=sys.stderr)
    
    log_info(f"Processing instance {instance_id}...")
    log_info(f"Problem (first 100): {problem_statement[:100]}...")
    
    try:
        rc, out, err = run_command_stream(
            cmd,
            log_file=(inst_dir / "wrapper_stream.txt" if log_root else None),
            label=f"brokk_wrapper {instance_id}"
        )
        
        if rc != 0:
            print(f"Error processing instance {instance_id}:", file=sys.stderr)
            print(f"Stdout: {out}", file=sys.stderr)
            print(f"Stderr: {err}", file=sys.stderr)
            return {
                "instance_id": instance_id,
                "model_name_or_path": "BrokkAgent",
                "model_patch": ""
            }
        
        # Load the result
        with open(tmp_path, 'r', encoding='utf-8') as f:
            prediction = json.load(f)
        
        # Clean up temp files
        Path(tmp_path).unlink(missing_ok=True)
        if problem_file:
            Path(problem_file).unlink(missing_ok=True)
        
        # Validate the result
        errors = validate_swe_bench_format(prediction)
        if errors:
            print(f"Warning: Invalid format for instance {instance_id}: {errors}", file=sys.stderr)
        
        return prediction
        
    except Exception as e:
        print(f"Error running brokk wrapper for instance {instance_id}: {e}", file=sys.stderr)
        # Clean up temp files
        Path(tmp_path).unlink(missing_ok=True)
        if problem_file:
            Path(problem_file).unlink(missing_ok=True)
        return {
            "instance_id": instance_id,
            "model_name_or_path": "BrokkAgent",
            "model_patch": ""
        }


def main():
    """Main entry point for the SWE-bench helper."""
    parser = argparse.ArgumentParser(
        description="SWE-bench-Live helper for Brokk integration"
    )
    
    subparsers = parser.add_subparsers(dest='command', help='Available commands')
    
    # Validate command
    validate_parser = subparsers.add_parser('validate', help='Validate a predictions file')
    validate_parser.add_argument('predictions_file', help='Path to predictions JSON file')
    
    # Process dataset command
    process_parser = subparsers.add_parser('process', help='Process a SWE-bench dataset')
    process_parser.add_argument('dataset_file', help='Path to SWE-bench dataset JSON file')
    process_parser.add_argument('target_project', help='Path to target project directory')
    process_parser.add_argument('--output', '-o', default='predictions.json', help='Output predictions file')
    process_parser.add_argument('--brokk_wrapper', default='brokk_wrapper.py', help='Path to brokk_wrapper.py')
    process_parser.add_argument('--model', help='Model to use for Brokk')
    process_parser.add_argument('--max_instances', type=int, help='Maximum number of instances to process')
    process_parser.add_argument('--log_dir', help='Directory to write per-instance logs')

    # Process HF Arrow dataset command
    process_hf = subparsers.add_parser('process_hf', help='Process a HuggingFace Arrow dataset (load_from_disk)')
    process_hf.add_argument('dataset_dir', help='Path to HF dataset directory (e.g., SWE-bench_lite)')
    process_hf.add_argument('--target_project', help='Path to target project directory (single-repo datasets)')
    process_hf.add_argument('--output', '-o', default='predictions.json', help='Output predictions file')
    process_hf.add_argument('--brokk_wrapper', default='brokk_wrapper.py', help='Path to brokk_wrapper.py')
    process_hf.add_argument('--model', help='Model to use for Brokk')
    process_hf.add_argument('--max_instances', type=int, help='Maximum number of instances to process')
    process_hf.add_argument('--log_dir', help='Directory to write per-instance logs')
    process_hf.add_argument('--auto_checkout', action='store_true', help='Automatically clone and checkout each instance repo at base_commit into --workspace_root')
    process_hf.add_argument('--workspace_root', default='workspace', help='Root directory for auto checkout clones')
    
    # Single instance command
    single_parser = subparsers.add_parser('single', help='Process a single instance')
    single_parser.add_argument('instance_id', help='Instance ID')
    single_parser.add_argument('problem_statement', help='Problem statement')
    single_parser.add_argument('target_project', help='Path to target project directory')
    single_parser.add_argument('--output', '-o', default='prediction.json', help='Output prediction file')
    single_parser.add_argument('--brokk_wrapper', default='brokk_wrapper.py', help='Path to brokk_wrapper.py')
    single_parser.add_argument('--model', help='Model to use for Brokk')
    
    args = parser.parse_args()
    
    if not args.command:
        parser.print_help()
        return
    
    if args.command == 'validate':
        predictions_path = Path(args.predictions_file)
        if not predictions_path.exists():
            print(f"Error: Predictions file not found: {predictions_path}", file=sys.stderr)
            sys.exit(1)
        
        try:
            with open(predictions_path, 'r', encoding='utf-8') as f:
                data = json.load(f)
            
            if isinstance(data, dict):
                predictions = [data]
            elif isinstance(data, list):
                predictions = data
            else:
                print("Error: Invalid predictions file format", file=sys.stderr)
                sys.exit(1)
            
            total_errors = 0
            for i, pred in enumerate(predictions):
                errors = validate_swe_bench_format(pred)
                if errors:
                    print(f"Errors in prediction {i}: {errors}")
                    total_errors += len(errors)
            
            if total_errors == 0:
                print(f"All {len(predictions)} predictions are valid!")
            else:
                print(f"Found {total_errors} validation errors")
                sys.exit(1)
                
        except Exception as e:
            print(f"Error validating predictions file: {e}", file=sys.stderr)
            sys.exit(1)
    
    elif args.command == 'process':
        dataset_path = Path(args.dataset_file)
        target_project = Path(args.target_project)
        brokk_wrapper_path = Path(args.brokk_wrapper)
        
        if not dataset_path.exists():
            print(f"Error: Dataset file not found: {dataset_path}", file=sys.stderr)
            sys.exit(1)
        
        if not target_project.exists():
            print(f"Error: Target project not found: {target_project}", file=sys.stderr)
            sys.exit(1)
        
        if not brokk_wrapper_path.exists():
            print(f"Error: Brokk wrapper not found: {brokk_wrapper_path}", file=sys.stderr)
            sys.exit(1)
        
        # Load dataset
        instances = load_swe_bench_dataset(dataset_path)
        print(f"Loaded {len(instances)} instances from dataset")
        
        if args.max_instances:
            instances = instances[:args.max_instances]
            print(f"Processing first {len(instances)} instances")
        
        # Process each instance
        predictions = []
        additional_args = []
        if args.model:
            additional_args.extend(['--model', args.model])
        log_root = Path(args.log_dir) if getattr(args, 'log_dir', None) else None
        
        for i, instance in enumerate(instances, 1):
            print(f"\n--- Processing instance {i}/{len(instances)} ---")
            prediction = run_brokk_on_instance(
                instance, brokk_wrapper_path, target_project, additional_args, log_root
            )
            predictions.append(prediction)
    elif args.command == 'process_hf':
        dataset_dir = Path(args.dataset_dir)
        brokk_wrapper_path = Path(args.brokk_wrapper)

        if not dataset_dir.exists():
            print(f"Error: Dataset directory not found: {dataset_dir}", file=sys.stderr)
            sys.exit(1)
        if not brokk_wrapper_path.exists():
            print(f"Error: Brokk wrapper not found: {brokk_wrapper_path}", file=sys.stderr)
            sys.exit(1)

        start_time = time.time()
        log_info("Loading HF Arrow dataset...")
        instances = load_hf_arrow_dataset(dataset_dir)
        if args.max_instances:
            instances = instances[:args.max_instances]
            log_info(f"Processing first {len(instances)} instances (max_instances)")

        predictions: List[Dict[str, Any]] = []
        additional_args: List[str] = []
        if args.model:
            additional_args.extend(['--model', args.model])
        log_root = Path(args.log_dir) if getattr(args, 'log_dir', None) else None

        # Workspace logic
        single_target: Optional[Path] = None
        if getattr(args, 'target_project', None):
            single_target = Path(args.target_project)
            if not single_target.exists():
                print(f"Error: Target project not found: {single_target}", file=sys.stderr)
                sys.exit(1)
        elif args.auto_checkout is False:
            print("Error: Either --target_project must be provided (single-repo) or use --auto_checkout for multi-repo datasets.", file=sys.stderr)
            sys.exit(1)

        workspace_root = Path(args.workspace_root).resolve() if args.auto_checkout else None
        if workspace_root and not workspace_root.exists():
            workspace_root.mkdir(parents=True, exist_ok=True)

        durations: List[float] = []
        for i, instance in enumerate(instances, 1):
            log_info(f"--- Processing instance {i}/{len(instances)} ---")
            target_project: Path
            if single_target is not None:
                target_project = single_target
                inst_log_dir = log_root / str(instance.get("instance_id", i)) if log_root else None
            else:
                inst_log_dir = log_root / str(instance.get("instance_id", i)) if log_root else None
                try:
                    step_start = time.time()
                    target_project = prepare_repo_for_instance(instance, workspace_root, inst_log_dir)
                    log_info(f"Repo ready at {target_project} (prep {int(time.time()-step_start)}s)")
                except Exception as e:
                    print(f"Error preparing repo for instance {instance.get('instance_id')}: {e}", file=sys.stderr)
                    predictions.append({
                        "instance_id": instance.get("instance_id", "unknown"),
                        "model_name_or_path": "BrokkAgent",
                        "model_patch": ""
                    })
                    continue

            inst_start = time.time()
            prediction = run_brokk_on_instance(
                instance, brokk_wrapper_path, target_project, additional_args, log_root
            )
            predictions.append(prediction)
            inst_dur = time.time() - inst_start
            durations.append(inst_dur)
            # Progress + ETA
            elapsed = time.time() - start_time
            avg = sum(durations) / len(durations)
            remaining = (len(instances) - i) * avg
            eta = datetime.now() + timedelta(seconds=int(remaining))
            log_info(
                f"Instance {i}/{len(instances)} done in {int(inst_dur)}s | Elapsed {int(elapsed)}s | ETA {eta.strftime('%H:%M:%S')}"
            )

        output_path = Path(args.output)
        create_prediction_file(predictions, output_path)
        total = int(time.time() - start_time)
        log_info(f"Completed processing {len(predictions)} instances in {total}s. Predictions: {output_path}")
    
    elif args.command == 'single':
        target_project = Path(args.target_project)
        brokk_wrapper_path = Path(args.brokk_wrapper)
        
        if not target_project.exists():
            print(f"Error: Target project not found: {target_project}", file=sys.stderr)
            sys.exit(1)
        
        if not brokk_wrapper_path.exists():
            print(f"Error: Brokk wrapper not found: {brokk_wrapper_path}", file=sys.stderr)
            sys.exit(1)
        
        # Create instance
        instance = {
            "instance_id": args.instance_id,
            "problem_statement": args.problem_statement
        }
        
        additional_args = []
        if args.model:
            additional_args.extend(['--model', args.model])
        
        prediction = run_brokk_on_instance(
            instance, brokk_wrapper_path, target_project, additional_args
        )
        
        # Save prediction
        output_path = Path(args.output)
        with open(output_path, 'w', encoding='utf-8') as f:
            json.dump(prediction, f, indent=2)
        
        print(f"Prediction saved to {output_path}")


if __name__ == "__main__":
    main()
