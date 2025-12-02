#!/usr/bin/env python3
import argparse
import json
import os
import sys
import traceback
from pathlib import Path
from typing import Dict, List, Optional, Tuple

METRICS_TO_TRACK = [
    {"key": "analysis_time_seconds", "name": "Analysis Time", "higherIsWorse": True, "unit": "s", "threshold": 10.0},
    {"key": "files_per_second", "name": "Files/Second", "higherIsWorse": False, "unit": " files/s", "threshold": 10.0},
]

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

    # If chromium exists, only use chromium to normalize scope
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

def _list_direct_json_files(dir_path: str) -> List[str]:
    d = Path(dir_path).expanduser().resolve()
    if not d.exists() or not d.is_dir():
        return []
    files: List[Path] = []
    for child in d.iterdir():
        try:
            if child.is_file() and child.suffix.lower() == ".json":
                files.append(child)
        except Exception:
            pass
    # Sort for deterministic ordering; averaging is unaffected, but helps debugging
    files.sort(key=lambda p: p.name)
    return [str(p) for p in files]

def average_results_dir(dir_path: str) -> Dict[str, float]:
    """
    Read all JSON files directly under dir_path (non-recursive), extract metrics, and average them.
    """
    json_files = _list_direct_json_files(dir_path)
    if not json_files:
        return {}

    reports = [r for r in (parse_report(f) for f in json_files) if r]
    if not reports:
        return {}

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
    return f"{value:.2f}"

def build_change_and_status(base_value: Optional[float], head_value: Optional[float], metric_config: dict) -> Tuple[Optional[float], str, str]:
    # Returns (change_pct numeric or None, status_text, status_emoji)
    if isinstance(base_value, (int, float)) and isinstance(head_value, (int, float)):
        if base_value == 0:
            return None, "N/A", ":question:"
        change = ((head_value - base_value) / base_value) * 100.0
        is_worse = change > 0 if metric_config["higherIsWorse"] else change < 0
        if abs(change) > metric_config["threshold"]:
            status_text = "Regression" if is_worse else "Improvement"
            status_emoji = ":x:" if is_worse else ":white_check_mark:"
        else:
            status_text = "OK"
            status_emoji = ":heavy_check_mark:"
        return change, status_text, status_emoji
    elif isinstance(head_value, (int, float)) and not isinstance(base_value, (int, float)):
        return None, "New", ":sparkles:"
    return None, "N/A", ":question:"

def generate_markdown_report(base_avg: Dict[str, float], head_avg: Dict[str, float]) -> str:
    head_sha = os.environ.get("HEAD_SHA_SHORT", "HEAD")
    base_sha = os.environ.get("BASE_SHA_SHORT", "BASE")

    lines: List[str] = []
    lines.append(":rocket: **Daily Performance Report**")
    lines.append("")
    lines.append(f"Comparison between `master@{head_sha}` and `master@{base_sha}`.")
    lines.append("")
    lines.append("| Metric | Scope | Baseline (" + base_sha + ") | Current (" + head_sha + ") | Change | Status |")
    lines.append("|---|---|---|---|---|---|")

    all_keys = sorted(set(base_avg.keys()) | set(head_avg.keys()))
    for key in all_keys:
        scope, metric_key = key.split(".", 1)
        metric_config = next((m for m in METRICS_TO_TRACK if m["key"] == metric_key), None)
        if not metric_config:
            continue
        base_value = base_avg.get(key)
        head_value = head_avg.get(key)
        change_pct, status_text, status_emoji = build_change_and_status(base_value, head_value, metric_config)
        change_str = "N/A" if change_pct is None else f"{'+' if change_pct > 0 else ''}{change_pct:.1f}%"
        lines.append(
            f"| {metric_config['name']} | {scope} | "
            f"{format_value(base_value, metric_config)}{metric_config['unit']} | "
            f"{format_value(head_value, metric_config)}{metric_config['unit']} | "
            f"{change_str} | {status_emoji} {status_text} |"
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
        scope, metric_key = key.split(".", 1)
        metric_config = next((m for m in METRICS_TO_TRACK if m["key"] == metric_key), None)
        if not metric_config:
            continue
        base_value = base_avg.get(key)
        head_value = head_avg.get(key)
        change_pct, status_text, status_emoji = build_change_and_status(base_value, head_value, metric_config)
        change_str = "N/A" if change_pct is None else f"{'+' if change_pct > 0 else ''}{change_pct:.1f}%"

        fields = [
            {"type": "mrkdwn", "text": f"*Metric*\n{metric_config['name']}"},
            {"type": "mrkdwn", "text": f"*Scope*\n{scope}"},
            {"type": "mrkdwn", "text": f"*Baseline ({base_sha})*\n{format_value(base_value, metric_config)}{metric_config['unit']}"},
            {"type": "mrkdwn", "text": f"*Current ({head_sha})*\n{format_value(head_value, metric_config)}{metric_config['unit']}"},
            {"type": "mrkdwn", "text": f"*Change*\n{change_str}"},
            {"type": "mrkdwn", "text": f"*Status*\n{status_emoji} {status_text}"}
        ]
        blocks.append({"type": "section", "fields": fields})

    return json.dumps(blocks, ensure_ascii=False)

def generate_json_summary(base_avg: Dict[str, float], head_avg: Dict[str, float]) -> dict:
    # Aggregate across all scopes for each metric key
    def aggregate_for(metric_key: str) -> Tuple[Optional[float], Optional[float], int, int]:
        base_vals: List[float] = []
        head_vals: List[float] = []
        for k, v in base_avg.items():
            if k.endswith("." + metric_key):
                base_vals.append(v)
        for k, v in head_avg.items():
            if k.endswith("." + metric_key):
                head_vals.append(v)
        base_mean = sum(base_vals) / len(base_vals) if base_vals else None
        head_mean = sum(head_vals) / len(head_vals) if head_vals else None
        return base_mean, head_mean, len(base_vals), len(head_vals)

    head_sha = os.environ.get("HEAD_SHA_SHORT", "HEAD")
    base_sha = os.environ.get("BASE_SHA_SHORT", "BASE")

    metrics_json: Dict[str, dict] = {}
    for cfg in METRICS_TO_TRACK:
        base_mean, head_mean, base_n, head_n = aggregate_for(cfg["key"])
        change_pct, status_text, status_emoji = build_change_and_status(base_mean, head_mean, cfg)

        # Pre-formatted string values for Slack (no conditionals there)
        base_str = f"{format_value(base_mean, cfg)}{cfg['unit']}" if base_mean is not None else ""
        head_str = f"{format_value(head_mean, cfg)}{cfg['unit']}" if head_mean is not None else ""
        if change_pct is None:
            change_str = "N/A"
        else:
            sign = "+" if change_pct > 0 else ""
            change_str = f"{sign}{change_pct:.1f}%"

        metrics_json[cfg["key"]] = {
            "name": cfg["name"],
            "unit": cfg["unit"],
            "higherIsWorse": cfg["higherIsWorse"],
            "threshold": cfg["threshold"],
            "base_avg": base_mean,
            "base_count": base_n,
            "base_str": base_str,
            "head_avg": head_mean,
            "head_count": head_n,
            "head_str": head_str,
            "change_pct": change_pct,
            "change_str": change_str,
            "status": status_text,
            "status_emoji": status_emoji,
        }

    ok = any(v.get("base_avg") is not None for v in metrics_json.values()) and any(
        v.get("head_avg") is not None for v in metrics_json.values()
    )

    return {
        "ok": bool(ok),
        "head_sha_short": head_sha,
        "base_sha_short": base_sha,
        "metrics": metrics_json,
    }

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Compare TreeSitter performance reports by averaging all JSON files directly under two directories."
    )
    parser.add_argument("--format", "-f", choices=["markdown", "blocks", "json"], default="markdown")
    parser.add_argument("base_dir", help="Directory containing BASE iteration JSON files (direct children only).")
    parser.add_argument("head_dir", help="Directory containing HEAD iteration JSON files (direct children only).")
    args = parser.parse_args()

    base_dir = Path(args.base_dir).expanduser().resolve()
    head_dir = Path(args.head_dir).expanduser().resolve()

    if not base_dir.exists() or not base_dir.is_dir() or not head_dir.exists() or not head_dir.is_dir():
        error_msg = f"Both inputs must be directories. base_dir={base_dir} head_dir={head_dir}"
        if args.format == "json":
            print(json.dumps({"ok": False, "error": error_msg}))
            sys.exit(0)
        else:
            print(error_msg, file=sys.stderr)
            sys.exit(1)

    try:
        base_avg = average_results_dir(str(base_dir))
        head_avg = average_results_dir(str(head_dir))

        if args.format == "blocks":
            blocks = generate_blockkit_report(base_avg, head_avg)
            print(blocks)
        elif args.format == "json":
            summary = generate_json_summary(base_avg, head_avg)
            if not summary.get("ok"):
                summary["error"] = "No valid reports found for one or both inputs."
                summary["base_inputs"] = _list_direct_json_files(str(base_dir))
                summary["head_inputs"] = _list_direct_json_files(str(head_dir))
            print(json.dumps(summary, ensure_ascii=False))
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
        elif args.format == "json":
            print(json.dumps({
                "ok": False,
                "error": f"Exception while generating performance report for master@{head_sha} vs master@{base_sha}",
                "trace": err_text,
            }, ensure_ascii=False))
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
