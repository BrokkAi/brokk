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
from typing import Dict, List, Optional, Any
import subprocess


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


def run_brokk_on_instance(
    instance: Dict[str, Any], 
    brokk_wrapper_path: Path,
    target_project: Path,
    additional_args: Optional[List[str]] = None
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
    instance_id = instance.get("instance_id", "unknown")
    problem_statement = instance.get("problem_statement", "")
    
    if not problem_statement:
        print(f"Warning: No problem statement found for instance {instance_id}", file=sys.stderr)
        return {
            "instance_id": instance_id,
            "model_name_or_path": "BrokkAgent",
            "model_patch": ""
        }
    
    # Build command
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
    
    print(f"Processing instance {instance_id}...")
    print(f"Problem: {problem_statement[:100]}...")
    
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8')
        
        if result.returncode != 0:
            print(f"Error processing instance {instance_id}:", file=sys.stderr)
            print(f"Stdout: {result.stdout}", file=sys.stderr)
            print(f"Stderr: {result.stderr}", file=sys.stderr)
            return {
                "instance_id": instance_id,
                "model_name_or_path": "BrokkAgent",
                "model_patch": ""
            }
        
        # Load the result
        with open(tmp_path, 'r', encoding='utf-8') as f:
            prediction = json.load(f)
        
        # Clean up temp file
        Path(tmp_path).unlink(missing_ok=True)
        
        # Validate the result
        errors = validate_swe_bench_format(prediction)
        if errors:
            print(f"Warning: Invalid format for instance {instance_id}: {errors}", file=sys.stderr)
        
        return prediction
        
    except Exception as e:
        print(f"Error running brokk wrapper for instance {instance_id}: {e}", file=sys.stderr)
        # Clean up temp file
        Path(tmp_path).unlink(missing_ok=True)
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
        
        for i, instance in enumerate(instances, 1):
            print(f"\n--- Processing instance {i}/{len(instances)} ---")
            prediction = run_brokk_on_instance(
                instance, brokk_wrapper_path, target_project, additional_args
            )
            predictions.append(prediction)
        
        # Save predictions
        output_path = Path(args.output)
        create_prediction_file(predictions, output_path)
        print(f"\nCompleted processing {len(predictions)} instances")
    
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
