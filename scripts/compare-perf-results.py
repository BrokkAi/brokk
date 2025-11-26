#!/usr/bin/env python3
import argparse
import json
import os
import sys
import traceback
from pathlib import Path
from typing import Dict, List, Optional

METRICS_TO_TRACK = [
    {"key": "analysis_time_seconds", "name": "Analysis Time", "higherIsWorse": True, "unit": "s", "threshold": 10.0},
    {"key": "peak_memory_mb", "name": "Peak Memory", "higherIsWorse": True, "unit": "MB", "threshold": 10.0},
    {"key": "files_per_second", "name": "Files/Second", "higherIsWorse": False, "unit": " files/s", "threshold": 10.0},
]

def find_report_file(p: str) -> Optional[str]:
    path = Path(p)
    if not path.exists():
        print(f"Path does not exist: {p}", file=sys.stderr)
        return None

    if path.is_file() and path.suffix.lower() == ".json":
        return str(path)

    if not path.is_dir():
        print(f"Not a directory or JSON file: {p}", file=sys.stderr)
        return None

    files = []
    for child in path.iterdir():
        try:
            if child.is_file() and child.suffix.lower() == ".json":
                files.append(child)
        except Exception:
            # ignore files that cannot be stat'd
            pass

    if not files:
        print(f"Could not find any *.json report file in {p}", file=sys.stderr)
        return None

    # 1) Prefer baseline-*.json
    baseline = next((f for f in files if f.name.startswith("baseline-") and f.suffix.lower() == ".json"), None)
    if baseline:
        return str(baseline)

    # 2) Common names
    preferred_names = {"results.json", "report.json", "summary.json"}
    preferred = next((f for f in files if f.name in preferred_names), None)
    if preferred:
        return str(preferred)

    # 3) Most recent by mtime
    files.sort(key=lambda f: f.stat().st_mtime, reverse=True)
    return str(files[0])

def parse_report(file_path: Optional[str]) -> Optional[dict]:
    if not file_path:
        return None
    try:
        with open(file_path, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except Exception as e:
        print(f"Error parsing {file_path}: {e}", file=sys.stderr)
        return None

def extract_metrics(report: dict) -> Dict[str, float]:
    metrics: Dict[str, float] = {}
    if not report or not isinstance(report, dict):
        return metrics
    results = report.get("results")
    if not results or not isinstance(results, dict):
        return metrics

    repos = ["chromium"] if "chromium" in results else list(results.keys())
    for repo in repos:
        repo_results = results.get(repo)
        if not repo_results or not isinstance(repo_results, dict):
            continue

        for lang, lang_metrics in repo_results.items():
            if not isinstance(lang_metrics, dict):
                continue
            for metric in METRICS_TO_TRACK:
                metric_key = f"{repo}/{lang}.{metric['key']}"
                if metric["key"] in lang_metrics:
                    val = lang_metrics[metric["key"]]
                    if isinstance(val, (int, float)):
                        metrics[metric_key] = float(val)
    return metrics

def average_results(inputs: List[str]) -> Dict[str, float]:
    report_files = [f for f in (find_report_file(p) for p in inputs) if f]
    reports = [r for r in (parse_report(f) for f in report_files) if r]

    if not reports:
        raise RuntimeError(f"No valid reports found for directories: {', '.join(inputs)}")

    all_metrics = [extract_metrics(r) for r in reports]
    averaged: Dict[str, float] = {}
    counts: Dict[str, int] = {}

    for m in all_metrics:
        for k, v in m.items():
            averaged[k] = averaged.get(k, 0.0) + v
            counts[k] = counts.get(k, 0) + 1

    for k in list(averaged.keys()):
        if counts.get(k, 0) > 0:
            averaged[k] /= counts[k]

    return averaged

def format_value(value: Optional[float], metric_config: dict) -> str:
    if not isinstance(value, (int, float)):
        return "N/A"
    decimals = 1 if metric_config["key"] == "peak_memory_mb" else 2
    return f"{value:.{decimals}f}"

def build_status(base_value: Optional[float], head_value: Optional[float], metric_config: dict):
    if isinstance(base_value, (int, float)) and isinstance(head_value, (int, float)) and base_value != 0:
        change = ((head_value - base_value) / base_value) * 100.0
        change_str = f"{'+' if change > 0 else ''}{change:.1f}%"
        is_worse = change > 0 if metric_config["higherIsWorse"] else change < 0
        if abs(change) > metric_config["threshold"]:
            status = ":x: Regression" if is_worse else ":white_check_mark: Improvement"
        else:
            status = ":heavy_check_mark: OK"
        return change_str, status
    elif isinstance(head_value, (int, float)) and not isinstance(base_value, (int, float)):
        return "New", ":sparkles:"
    return "N/A", ":question:"

def generate_markdown_report(base_avg: Dict[str, float], head_avg: Dict[str, float]) -> str:
    head_sha = os.environ.get("HEAD_SHA_SHORT", "HEAD")
    base_sha = os.environ.get("BASE_SHA_SHORT", "BASE")

    lines = []
    lines.append(":rocket: **Daily Performance Report**")
    lines.append("")
    lines.append(f"Comparison between `master@{head_sha}` and `master@{base_sha}`.")
    lines.append("")
    lines.append("| Metric | Language | Baseline (" + base_sha + ") | Current (" + head_sha + ") | Change | Status |")
    lines.append("|---|---|---|---|---|---|")

    all_keys = sorted(set(base_avg.keys()) | set(head_avg.keys()))
    for key in all_keys:
        lang, metric_key = key.split(".", 1)
        metric_config = next((m for m in METRICS_TO_TRACK if m["key"] == metric_key), None)
        if not metric_config:
            continue
        base_value = base_avg.get(key)
        head_value = head_avg.get(key)
        change_str, status = build_status(base_value, head_value, metric_config)
        lines.append(
            f"| {metric_config['name']} | {lang} | "
            f"{format_value(base_value, metric_config)}{metric_config['unit']} | "
            f"{format_value(head_value, metric_config)}{metric_config['unit']} | "
            f"{change_str} | {status} |"
        )

    return "\n".join(lines)

def generate_blockkit_report(base_avg: Dict[str, float], head_avg: Dict[str, float]) -> str:
    head_sha = os.environ.get("HEAD_SHA_SHORT", "HEAD")
    base_sha = os.environ.get("BASE_SHA_SHORT", "BASE")

    blocks = []
    blocks.append({
        "type": "header",
        "text": {"type": "plain_text", "text": "Daily Performance Report"}
    })
    blocks.append({
        "type": "context",
        "elements": [
            {"type": "mrkdwn", "text": f"Comparison between `master@{head_sha}` and `master@{base_sha}`."}
        ]
    })
    blocks.append({"type": "divider"})

    all_keys = sorted(set(base_avg.keys()) | set(head_avg.keys()))
    for key in all_keys:
        lang, metric_key = key.split(".", 1)
        metric_config = next((m for m in METRICS_TO_TRACK if m["key"] == metric_key), None)
        if not metric_config:
            continue
        base_value = base_avg.get(key)
        head_value = head_avg.get(key)
        change_str, status = build_status(base_value, head_value, metric_config)

        fields = [
            {"type": "mrkdwn", "text": f"*Metric*\n{metric_config['name']}"},
            {"type": "mrkdwn", "text": f"*Scope*\n{lang}"},
            {"type": "mrkdwn", "text": f"*Baseline ({base_sha})*\n{format_value(base_value, metric_config)}{metric_config['unit']}"},
            {"type": "mrkdwn", "text": f"*Current ({head_sha})*\n{format_value(head_value, metric_config)}{metric_config['unit']}"},
            {"type": "mrkdwn", "text": f"*Change*\n{change_str}"},
            {"type": "mrkdwn", "text": f"*Status*\n{status}"}
        ]
        blocks.append({"type": "section", "fields": fields})

    return json.dumps(blocks, ensure_ascii=False)

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Compare TreeSitter performance reports and output markdown or Slack Block Kit."
    )
    parser.add_argument("--format", "-f", choices=["markdown", "blocks"], default="markdown")
    parser.add_argument("inputs", nargs="+", help="Paths to report directories or JSON files (both base and head).")
    args = parser.parse_args()

    base_inputs = [p for p in args.inputs if "report-base-" in p]
    head_inputs = [p for p in args.inputs if "report-head-" in p]

    if not base_inputs or not head_inputs:
        print("Usage: compare-perf-results.py [--format=markdown|blocks] <base-report-dirs...> <head-report-dirs...>", file=sys.stderr)
        sys.exit(1)

    try:
        base_avg = average_results(base_inputs)
        head_avg = average_results(head_inputs)

        if args.format == "blocks":
            blocks = generate_blockkit_report(base_avg, head_avg)
            print(blocks)
        else:
            report = generate_markdown_report(base_avg, head_avg)
            print(report)
    except Exception:
        head_sha = os.environ.get("HEAD_SHA_SHORT", "HEAD")
        base_sha = os.environ.get("BASE_SHA_SHORT", "BASE")
        err_text = traceback.format_exc()

        if args.format == "blocks":
            blocks = [
                {
                    "type": "section",
                    "text": {
                        "type": "mrkdwn",
                        "text": f":warning: *Failed to generate performance report*\nComparison between `master@{head_sha}` and `master@{base_sha}` failed."
                    }
                },
                {
                    "type": "section",
                    "text": {
                        "type": "mrkdwn",
                        "text": "```" + err_text + "```"
                    }
                }
            ]
            print(json.dumps(blocks, ensure_ascii=False))
            sys.exit(0)
        else:
            print(":warning: **Failed to generate performance report**")
            print("")
            print(f"Comparison between `master@{head_sha}` and `master@{base_sha}` failed.")
            print("")
            print("```")
            print(err_text)
            print("```")
            sys.exit(0)

if __name__ == "__main__":
    main()
