#!/usr/bin/env python3

# /// script
# requires-python = ">=3.10"
# dependencies = [
#   "PyQt6",
# ]
# ///

from __future__ import annotations

import argparse
import json
import os
import re
from dataclasses import dataclass
from collections import defaultdict
from urllib.error import HTTPError, URLError
from urllib.parse import quote_plus
from urllib.request import Request, urlopen
from datetime import date, datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple


DIR_RE = re.compile(
    r"^(?P<date>\d{4}-\d{2}-\d{2})-(?P<hour>\d{2})-(?P<minute>\d{2})-(?P<second>\d{2}) "
    r"(?P<optype>\S+) (?P<noise>.+)$"
)
FILE_RE = re.compile(
    r"^(?P<hour>\d{2})-(?P<minute>\d{2})\.(?P<second>\d{2}) (?P<index>\d+)-(?P<suffix>.+)$"
)


BROKK_PROXY_URL = "https://proxy.brokk.ai"
DEFAULT_HISTORY_PATH = str(Path.home() / "Projects" / "brokk" / ".brokk" / "llm-history")
@dataclass(frozen=True)
class PriceBand:
    min_tokens_inclusive: int
    max_tokens_inclusive: int
    input_cost_per_token: float
    cached_input_cost_per_token: float
    output_cost_per_token: float

    def contains(self, token_count: int) -> bool:
        return self.min_tokens_inclusive <= token_count <= self.max_tokens_inclusive


MAX_TIER_TOKENS = 10**18


@dataclass(frozen=True)
class ParsedEntry:
    ts: datetime
    index: str
    path: Path
    suffix: str


@dataclass
class Turn:
    directory_name: str
    optype: str
    row_label: str
    request_index: str
    request_ts: datetime
    response_ts: datetime
    model: Optional[str]
    log_path: Optional[Path]
    log_ts: Optional[datetime]
    service_tier: Optional[str]
    input_tokens: int
    cached_input_tokens: int
    output_tokens: int
    tools: List[str]


@dataclass
class GapAnnotation:
    from_request: str
    to_request: str
    directory_name: str
    optype: str
    start: datetime
    end: datetime
    turn_duration_ms: int
    tools: List[str]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Render llm history as a PyQt gantt chart")
    parser.add_argument(
        "start",
        nargs="?",
        help="Start time as HH:MM:SS or HH-MM-SS; defaults to first history subdirectory timestamp",
    )
    parser.add_argument(
        "end",
        nargs="?",
        help="End time as HH:MM:SS or HH-MM-SS; defaults to the last history directory timestamp",
    )
    parser.add_argument(
        "history_path",
        nargs="?",
        help="Legacy positional history path when start is omitted",
    )
    parser.add_argument(
        "--history",
        default=DEFAULT_HISTORY_PATH,
        help="History directory path (default: ~/Projects/brokk/.brokk/llm-history)",
    )
    parser.add_argument(
        "--day",
        default="today",
        help="Calendar day in YYYY-MM-DD (default: today)",
    )
    parser.add_argument(
        "--debug",
        action="store_true",
        help="Print turn and gap data as jsonl and do not launch gui",
    )
    parser.add_argument(
        "--show-costs",
        action="store_true",
        help="Fetch model pricing from proxy and report estimated costs",
    )
    return parser.parse_args()


def parse_day(raw: str) -> date:
    if raw.lower() == "today":
        return date.today()
    try:
        return datetime.strptime(raw, "%Y-%m-%d").date()
    except ValueError as exc:
        raise ValueError("--day must be today or YYYY-MM-DD") from exc


def parse_time(raw: str) -> datetime.time:
    for pattern in ("%H:%M:%S", "%H-%M-%S"):
        try:
            return datetime.strptime(raw, pattern).time()
        except ValueError:
            continue
    raise ValueError("start/end must be HH:MM:SS or HH-MM-SS")


def parse_time_or_none(raw: Optional[str]) -> Optional[datetime.time]:
    if raw is None:
        return None
    try:
        return parse_time(raw)
    except ValueError:
        return None


def decode_properties_value(value: str) -> str:
    return value.replace("\\:", ":").replace("\\=", "=").replace("\\\\", "\\")


def read_properties_file(path: Path) -> Dict[str, str]:
    properties: Dict[str, str] = {}
    if not path.is_file():
        return properties

    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except Exception:
        return properties

    for raw_line in lines:
        line = raw_line.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue

        delimiter = None
        for index, char in enumerate(line):
            if char not in ":=":
                continue
            if index > 0 and line[index - 1] == "\\":
                continue
            delimiter = index
            break
        if delimiter is None:
            continue

        key = line[:delimiter].strip()
        value = line[delimiter + 1 :].strip()
        if not key:
            continue
        properties[key] = decode_properties_value(value)

    return properties


def parse_brokk_config() -> Dict[str, str]:
    candidates = [
        Path.home() / "Library" / "Application Support" / "Brokk" / "brokk.properties",
        Path.home() / ".config" / "Brokk" / "brokk.properties",
        Path.home() / ".config" / "brokk" / "brokk.properties",
    ]

    properties: Dict[str, str] = {}
    for candidate in candidates:
        candidate = candidate.expanduser()
        if candidate.is_file():
            properties.update(read_properties_file(candidate))

    return properties


def parse_brokk_api_key(raw_key: str) -> Optional[str]:
    key = raw_key.strip()
    if not key:
        return None

    if key.startswith("Bearer "):
        key = key[len("Bearer ") :].strip()

    if key.startswith("sk-"):
        return key

    parts = key.split("+")
    if len(parts) != 3:
        return None
    if parts[0] != "brk" or not parts[2]:
        return None

    token = parts[2].strip()
    if not token:
        return None
    if token.startswith("sk-"):
        return token
    return f"sk-{token}"


def parse_brokk_user_id(api_key: str) -> Optional[str]:
    key = api_key.strip()
    if key.startswith("Bearer "):
        key = key[len("Bearer ") :].strip()
    parts = key.split("+")
    if len(parts) != 3:
        return None
    if parts[0] != "brk":
        return None
    if not parts[1]:
        return None
    return parts[1]


def proxy_brokk_api_key() -> Optional[str]:
    return parse_brokk_config().get("brokkApiKey") or os.getenv("BROKK_API_KEY")


def parse_float_value(raw: Any, default: float = 0.0) -> float:
    if isinstance(raw, bool):
        return 1.0 if raw else 0.0
    if isinstance(raw, int | float):
        return float(raw)
    if isinstance(raw, str):
        try:
            return float(raw.strip())
        except ValueError:
            return default
    return default


def parse_int_value(raw: Any) -> int:
    if isinstance(raw, bool):
        return 1 if raw else 0
    if isinstance(raw, int):
        return max(0, raw)
    if isinstance(raw, float):
        return max(0, int(raw))
    if isinstance(raw, str):
        try:
            return max(0, int(float(raw.strip())))
        except ValueError:
            return 0
    return 0


def get_cost_from_sources(
    primary: Dict[str, Any],
    fallback: Optional[Dict[str, Any]],
    key: str,
    default: float,
) -> float:
    if key in primary and primary.get(key) is not None:
        return parse_float_value(primary[key], default)
    if fallback is not None and key in fallback and fallback.get(key) is not None:
        return parse_float_value(fallback[key], default)
    return default


def pricing_bands_from_source(
    primary: Dict[str, Any],
    fallback: Optional[Dict[str, Any]] = None,
) -> List[PriceBand]:
    input_cost = get_cost_from_sources(
        primary,
        fallback,
        "input_cost_per_token",
        0.0,
    )
    cached_cost = get_cost_from_sources(
        primary,
        fallback,
        "cache_read_input_token_cost",
        0.0,
    )
    output_cost = get_cost_from_sources(
        primary,
        fallback,
        "output_cost_per_token",
        0.0,
    )

    has_above = (
        (primary.get("input_cost_per_token_above_200k_tokens") is not None)
        or (primary.get("cache_read_input_token_cost_above_200k_tokens") is not None)
        or (primary.get("output_cost_per_token_above_200k_tokens") is not None)
        or (fallback is not None and fallback.get("input_cost_per_token_above_200k_tokens") is not None)
        or (fallback is not None and fallback.get("cache_read_input_token_cost_above_200k_tokens") is not None)
        or (fallback is not None and fallback.get("output_cost_per_token_above_200k_tokens") is not None)
    )

    if not has_above:
        return [
            PriceBand(
                0,
                MAX_TIER_TOKENS,
                input_cost,
                cached_cost,
                output_cost,
            )
        ]

    above_input = get_cost_from_sources(
        primary,
        fallback,
        "input_cost_per_token_above_200k_tokens",
        input_cost,
    )
    above_cached = get_cost_from_sources(
        primary,
        fallback,
        "cache_read_input_token_cost_above_200k_tokens",
        cached_cost,
    )
    above_output = get_cost_from_sources(
        primary,
        fallback,
        "output_cost_per_token_above_200k_tokens",
        output_cost,
    )

    return [
        PriceBand(0, 199_999, input_cost, cached_cost, output_cost),
        PriceBand(200_000, MAX_TIER_TOKENS, above_input, above_cached, above_output),
    ]


def service_tier_normalized(raw: Optional[str]) -> str:
    if not raw:
        return "standard"
    normalized = raw.strip().lower()
    return {
        "default": "standard",
        "priority": "priority",
        "flex": "flex",
        "batch": "batch",
    }.get(normalized, "standard")


def pricing_bands_for_model(info: Dict[str, Any], service_tier: Optional[str] = None) -> List[PriceBand]:
    if not isinstance(info, dict):
        return []

    pricing_tiers = info.get("pricing_tiers")
    if isinstance(pricing_tiers, dict):
        normalized_tier = service_tier_normalized(service_tier)
        selected_tier = pricing_tiers.get(normalized_tier)
        if normalized_tier != "standard":
            # fallback to standard if the specific tier is not configured
            selected_tier = pricing_tiers.get(normalized_tier, pricing_tiers.get("standard"))
        if isinstance(selected_tier, dict):
            bands = pricing_bands_from_source(selected_tier, fallback=info)
            if bands:
                return bands

    return pricing_bands_from_source(info)


def cost_for_turn(
    turn: Turn,
    model_info_map: Dict[str, Dict[str, Any]],
    cached_bands: Dict[Tuple[str, str], List[PriceBand]],
) -> Optional[float]:
    model_name = turn.model
    if not model_name:
        return None

    cache_key = (model_name, turn.service_tier or "standard")
    if cache_key not in cached_bands:
        info = model_info_map.get(model_name)
        if not isinstance(info, dict):
            cached_bands[cache_key] = []
        else:
            cached_bands[cache_key] = pricing_bands_for_model(info, turn.service_tier)

    bands = cached_bands[cache_key]
    if not bands:
        return None

    prompt_tokens = turn.input_tokens + turn.cached_input_tokens
    band_for_prompt = next(
        (band for band in bands if band.contains(prompt_tokens)),
        bands[-1],
    )
    return (
        (turn.input_tokens * band_for_prompt.input_cost_per_token)
        + (turn.cached_input_tokens * band_for_prompt.cached_input_cost_per_token)
        + (turn.output_tokens * band_for_prompt.output_cost_per_token)
    )


def fetch_model_info(proxy_url: str, proxy_setting: str, api_key: Optional[str]) -> Dict[str, Any]:
    request_url = f"{proxy_url}/model/info"
    request_urls: List[str] = [request_url]
    headers: Dict[str, str] = {}

    if proxy_setting == "BROKK":
        bearer_token = parse_brokk_api_key(api_key) if api_key else None
        if bearer_token:
            headers["Authorization"] = f"Bearer {bearer_token}"

        user_id = parse_brokk_user_id(api_key) if api_key else None
        if user_id:
            request_urls = [f"{request_url}?user_id={quote_plus(user_id)}", request_url]
        elif not bearer_token:
            print("Cannot fetch model pricing: BROKK proxy selected but brokkApiKey is missing.")

    auth_attempts: List[Dict[str, str]] = [headers] if headers else [dict()]
    if headers and api_key and f"Bearer {api_key}" != headers.get("Authorization"):
        auth_attempts.append({"Authorization": f"Bearer {api_key}"})

    payload = None
    last_code = None
    for url in request_urls:
        for attempt_headers in auth_attempts:
            request = Request(url, headers=attempt_headers)
            try:
                with urlopen(request, timeout=20) as response:
                    raw_payload = response.read().decode("utf-8", errors="replace")
                    payload = json.loads(raw_payload)
            except HTTPError as exc:
                last_code = exc.code
                if exc.code == 401 and (
                    attempt_headers != auth_attempts[-1]
                    or url != request_urls[-1]
                ):
                    continue
                print(f"Failed to fetch model info from {url}: HTTP {exc.code}")
                return {}
            except URLError as exc:
                print(f"Failed to fetch model info from {url}: {exc}")
                return {}
            except json.JSONDecodeError:
                return {}

            if payload is not None and isinstance(payload, dict):
                break
        if payload is not None and isinstance(payload, dict):
            request_url = url
            break

    if payload is None:
        if last_code == 401:
            print(f"Failed to fetch model info from {request_urls[-1]}: HTTP 401")
        return {}

    data = payload.get("data") if isinstance(payload, dict) else None
    if not isinstance(data, list):
        print("/model/info response did not include a data array")
        return {}

    model_info_map: Dict[str, Dict[str, Any]] = {}
    for entry in data:
        if not isinstance(entry, dict):
            continue
        model_name = str(entry.get("model_name", "")).strip()
        if not model_name:
            continue
        raw_info = entry.get("model_info")
        if isinstance(raw_info, dict):
            model_info_map[model_name] = raw_info

    return model_info_map


def parse_metadata_from_log(log_path: Path) -> Tuple[Optional[str], int, int, int, Optional[str]]:
    try:
        lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()
    except Exception:
        return None, 0, 0, 0, None

    for index, line in enumerate(lines):
        if line.strip() == "## metadata":
            metadata_blob = "\n".join(lines[index + 1 :])
            start = metadata_blob.find("{")
            if start < 0:
                break
            end = metadata_blob.rfind("}")
            if end <= start:
                break
            try:
                metadata = json.loads(metadata_blob[start : end + 1])
            except (ValueError, json.JSONDecodeError):
                break
            if not isinstance(metadata, dict):
                break

            model_name = metadata.get("modelName")
            if not isinstance(model_name, str) or not model_name:
                model_name = None

            service_tier = metadata.get("serviceTier")
            if not isinstance(service_tier, str):
                service_tier = metadata.get("service_tier")

            return (
                model_name,
                parse_int_value(metadata.get("inputTokens")),
                parse_int_value(metadata.get("cachedInputTokens")),
                parse_int_value(metadata.get("outputTokens")),
                service_tier if isinstance(service_tier, str) else None,
            )

    return None, 0, 0, 0, None


def compute_model_usage(
    turns: Sequence[Turn],
    model_info_map: Optional[Dict[str, Dict[str, Any]]] = None,
    show_costs: bool = False,
) -> Tuple[Dict[str, Dict[str, int]], Dict[str, float], Dict[str, bool]]:
    by_model: Dict[str, Dict[str, int]] = defaultdict(lambda: {"input": 0, "cached": 0, "output": 0})
    by_model_cost: Dict[str, float] = defaultdict(float)
    cost_cache: Dict[Tuple[str, str], List[PriceBand]] = {}
    cost_missing: Dict[str, bool] = {}

    for turn in turns:
        model_name = turn.model or "<missing>"
        by_model[model_name]["input"] += turn.input_tokens
        by_model[model_name]["cached"] += turn.cached_input_tokens
        by_model[model_name]["output"] += turn.output_tokens

        if show_costs and model_info_map is not None:
            cost = cost_for_turn(turn, model_info_map, cost_cache)
            if cost is None and turn.model not in {None, ""}:
                cost_missing[model_name] = True
            elif cost is not None:
                by_model_cost[model_name] += cost

    if show_costs and model_info_map is not None:
        return by_model, by_model_cost, cost_missing
    return by_model, {}, {}


def print_model_usage_summary(
    turns: Sequence[Turn],
    model_info_map: Optional[Dict[str, Dict[str, Any]]] = None,
    show_costs: bool = False,
) -> None:
    by_model, by_model_cost, by_model_cost_missing = compute_model_usage(
        turns,
        model_info_map=model_info_map,
        show_costs=show_costs,
    )

    print("Model token usage:")
    if not by_model:
        print("  no model metadata found")
        return

    total_input = 0
    total_cached = 0
    total_output = 0
    total_cost = 0.0

    for model_name in sorted(by_model):
        usage = by_model[model_name]
        input_tokens = usage["input"]
        cached_tokens = usage["cached"]
        output_tokens = usage["output"]
        total_tokens = input_tokens + cached_tokens + output_tokens

        total_input += input_tokens
        total_cached += cached_tokens
        total_output += output_tokens

        line = f"  {model_name}: input={input_tokens} cached={cached_tokens} output={output_tokens} total={total_tokens}"
        if show_costs and model_info_map is not None:
            if by_model_cost_missing.get(model_name, False):
                line += " cost=unavailable"
            else:
                cost = by_model_cost.get(model_name, 0.0)
                line += f" cost=${cost:.6f}"
                total_cost += cost

        print(line)

    if show_costs and model_info_map is not None:
        print(
            f"  TOTAL: input={total_input} cached={total_cached} output={total_output} total={total_input + total_cached + total_output} cost=${total_cost:.6f}"
        )
    else:
        print(
            f"  TOTAL: input={total_input} cached={total_cached} output={total_output} total={total_input + total_cached + total_output}"
        )


def parse_file_entry(base_day: date, filename: str, path: Path) -> Optional[ParsedEntry]:
    match = FILE_RE.match(filename)
    if not match:
        return None
    ts = datetime.strptime(
        f"{match.group('hour')}:{match.group('minute')}:{match.group('second')}",
        "%H:%M:%S",
    ).time()
    return ParsedEntry(
        ts=datetime.combine(base_day, ts),
        index=match.group("index"),
        path=path,
        suffix=match.group("suffix"),
    )


def parse_directory_timestamp(directory_name: str) -> Optional[datetime]:
    match = DIR_RE.match(directory_name)
    if not match:
        return None
    return datetime.strptime(
        f"{match.group('date')} {match.group('hour')}:{match.group('minute')}:{match.group('second')}",
        "%Y-%m-%d %H:%M:%S",
    )


def add_tool_name(values: List[str], seen: set[str], value: Any) -> None:
    if not value:
        return
    if not isinstance(value, str):
        return
    value = value.strip()
    if not value or value in seen:
        return
    seen.add(value)
    values.append(value)


def parse_tool_names(payload_path: Path) -> List[str]:
    try:
        with payload_path.open("r", encoding="utf-8") as file:
            payload = json.load(file)
    except Exception:
        return []

    messages = payload.get("messages")
    if not isinstance(messages, list):
        return []

    tools: List[str] = []
    seen: set[str] = set()

    for message in messages:
        if not isinstance(message, dict):
            continue
        role = str(message.get("role", "")).lower()

        if role == "assistant":
            tool_calls = message.get("tool_calls")
            if isinstance(tool_calls, list):
                for tool_call in tool_calls:
                    if not isinstance(tool_call, dict):
                        continue
                    add_tool_name(
                        tools, seen, tool_call.get("name"),
                    )
                    function = tool_call.get("function")
                    if isinstance(function, dict):
                        add_tool_name(tools, seen, function.get("name"))

        if role == "tool":
            add_tool_name(tools, seen, message.get("name"))

        direct_name = message.get("name")
        if isinstance(direct_name, str):
            add_tool_name(tools, seen, direct_name)

    return tools


def row_label(optype: str, noise: str) -> str:
    words = noise.split()
    second_word = ""
    if len(words) >= 2:
        second_word = words[1]
    elif len(words) == 1:
        second_word = words[0]
    if second_word:
        return f"{optype} {second_word}"
    return optype


def load_turns(directory: Path, _day_start: datetime, _day_end: datetime) -> List[Turn]:
    match = DIR_RE.match(directory.name)
    if not match:
        return []

    optype = match.group("optype")
    row = row_label(optype, match.group("noise"))
    base_day = datetime.strptime(match.group("date"), "%Y-%m-%d").date()

    entries: List[ParsedEntry] = []
    for file in directory.iterdir():
        if not file.is_file():
            continue
        parsed = parse_file_entry(base_day, file.name, file)
        if parsed is not None:
            entries.append(parsed)

    if not entries:
        return []

    entries.sort(key=lambda item: item.ts)
    entries_by_index: Dict[str, List[ParsedEntry]] = defaultdict(list)
    request_entries: List[ParsedEntry] = []
    for entry in entries:
        entries_by_index[entry.index].append(entry)
        if entry.suffix == "request.json":
            request_entries.append(entry)

    turns: List[Turn] = []
    for request in sorted(request_entries, key=lambda item: item.ts):
        response_ts = request.ts
        log_ts = None
        for entry in entries_by_index.get(request.index, []):
            if entry.path == request.path:
                continue
            if entry.ts < request.ts:
                continue
            response_ts = entry.ts
            break

        log_path = None
        for entry in entries_by_index.get(request.index, []):
            if entry.suffix.endswith(".log") and entry.ts >= request.ts:
                log_path = entry.path
                log_ts = entry.ts
                break

        model = None
        service_tier = None
        input_tokens = 0
        cached_input_tokens = 0
        output_tokens = 0
        if log_path is not None:
            model, input_tokens, cached_input_tokens, output_tokens, service_tier = parse_metadata_from_log(log_path)

        turns.append(
            Turn(
                directory_name=directory.name,
                optype=optype,
                row_label=row,
                request_index=request.index,
                request_ts=request.ts,
                response_ts=response_ts,
                log_path=log_path,
                log_ts=log_ts,
                model=model,
                service_tier=service_tier,
                input_tokens=input_tokens,
                cached_input_tokens=cached_input_tokens,
                output_tokens=output_tokens,
                tools=parse_tool_names(request.path),
            )
        )

    return turns


def ms_between(start: datetime, end: datetime) -> int:
    return int((end - start).total_seconds() * 1000)


def seconds_from_ms(ms: int) -> int:
    return int(ms / 1000)


def seconds_between(start: datetime, end: datetime) -> int:
    return int((end - start).total_seconds())


def last_history_directory_timestamp(history_root: Path) -> Optional[datetime]:
    latest: Optional[datetime] = None
    for directory in history_root.iterdir():
        if not directory.is_dir():
            continue
        directory_timestamp = parse_directory_timestamp(directory.name)
        if directory_timestamp is None:
            continue
        if latest is None or directory_timestamp > latest:
            latest = directory_timestamp
    return latest


def first_history_directory_timestamp(history_root: Path) -> Optional[datetime]:
    earliest: Optional[datetime] = None
    for directory in history_root.iterdir():
        if not directory.is_dir():
            continue
        directory_timestamp = parse_directory_timestamp(directory.name)
        if directory_timestamp is None:
            continue
        if earliest is None or directory_timestamp < earliest:
            earliest = directory_timestamp
    return earliest


def calculate_inference_ms(turns: Sequence[Turn]) -> int:
    return sum(ms_between(turn.request_ts, turn.response_ts) for turn in turns)


def calculate_wall_ms(turns: Sequence[Turn]) -> Optional[int]:
    if not turns:
        return None
    first_json = min(turn.request_ts for turn in turns)
    log_timestamps = [turn.log_ts for turn in turns if turn.log_ts is not None]
    if not log_timestamps:
        return None
    return ms_between(first_json, max(log_timestamps))


def print_timing_summary(turns: Sequence[Turn]) -> None:
    inference_ms = calculate_inference_ms(turns)
    wall_ms = calculate_wall_ms(turns)
    print(f"Inference time: {inference_ms}ms ({seconds_from_ms(inference_ms)}s)")
    if wall_ms is None:
        print("Wall time: unavailable (no .log timestamps found)")
    else:
        print(f"Wall time: {wall_ms}ms ({seconds_from_ms(wall_ms)}s)")


def gather_gaps(turns: Sequence[Turn]) -> List[GapAnnotation]:
    turns_by_dir: Dict[str, List[Turn]] = defaultdict(list)
    for turn in turns:
        turns_by_dir[turn.directory_name].append(turn)

    gaps: List[GapAnnotation] = []
    for directory_name, directory_turns in turns_by_dir.items():
        directory_turns = sorted(directory_turns, key=lambda item: item.request_ts)
        for i in range(len(directory_turns) - 1):
            previous_turn = directory_turns[i]
            next_turn = directory_turns[i + 1]
            gaps.append(
                GapAnnotation(
                    from_request=previous_turn.request_index,
                    to_request=next_turn.request_index,
                    directory_name=directory_name,
                    optype=next_turn.optype,
                    start=previous_turn.response_ts,
                    end=next_turn.request_ts,
                    turn_duration_ms=ms_between(next_turn.request_ts, next_turn.response_ts),
                    tools=next_turn.tools,
                )
            )
    return gaps


def label_for_gap(gap: GapAnnotation) -> str:
    tools = "\n".join(gap.tools) if gap.tools else "no-tools"
    gap_ms = ms_between(gap.start, gap.end)
    return f"{seconds_between(gap.start, gap.end)}s\n{tools}"


def split_gap_lines(gap: GapAnnotation) -> List[str]:
    tools = gap.tools or ["no-tools"]
    return [f"{seconds_between(gap.start, gap.end)}s", *tools]


def clamp(value: int, lower: int, upper: int) -> int:
    if value < lower:
        return lower
    if value > upper:
        return upper
    return value


def load_data(
    history_root: Path,
    day_start: datetime,
    day_end: datetime,
) -> Tuple[List[Turn], List[GapAnnotation]]:
    all_turns: List[Turn] = []
    for directory in sorted(history_root.iterdir()):
        if not directory.is_dir():
            continue
        dir_start = parse_directory_timestamp(directory.name)
        if dir_start is None:
            continue
        if dir_start < day_start or dir_start > day_end:
            continue
        all_turns.extend(load_turns(directory, day_start, day_end))

    all_turns.sort(key=lambda item: item.request_ts)
    return all_turns, gather_gaps(all_turns)


def launch_gui(turns: Sequence[Turn], gaps: Sequence[GapAnnotation], day_start: datetime, day_end: datetime) -> int:
    try:
            from PyQt6.QtGui import QColor, QPainter
            from PyQt6.QtCore import QRect
            from PyQt6.QtWidgets import (
                QApplication,
                QDialog,
                QPlainTextEdit,
                QVBoxLayout,
                QMainWindow,
                QScrollArea,
                QWidget,
            )
    except Exception:
        raise RuntimeError(
            "PyQt6 is required. Install PyQt6 to run the chart view."
        )

    class GanttCanvas(QWidget):
        left_margin = 130
        right_margin = 30
        top_margin = 50
        bottom_margin = 40
        row_height = 44
        bar_height = 14
        tick_count = 6
        seconds_per_pixel_cap = 10.0

        def __init__(self, turns: Sequence[Turn], gaps: Sequence[GapAnnotation], day_start: datetime, day_end: datetime):
            super().__init__()
            self.turns = list(turns)
            self.gaps = list(gaps)
            self.day_start = day_start
            self.day_end = day_end
            self.total_seconds = max(1.0, (day_end - day_start).total_seconds())

            self.rows: Dict[str, List[Turn]] = defaultdict(list)
            for turn in self.turns:
                self.rows[turn.directory_name].append(turn)
            self.rows = {
                directory: sorted(turns, key=lambda item: item.request_ts)
                for directory, turns in self.rows.items()
                if turns
            }
            self.row_labels = sorted(
                self.rows.keys(),
                key=lambda row: (self.rows[row][0].request_ts, row),
            )
            self.row_display_labels: Dict[str, str] = {}
            for row in self.row_labels:
                if self.rows[row]:
                    self.row_display_labels[row] = self.rows[row][0].row_label
                else:
                    self.row_display_labels[row] = row

            self.row_to_color: Dict[str, QColor] = {}
            for row in self.row_labels:
                hue = (abs(hash(row)) % 360)
                self.row_to_color[row] = QColor.fromHsv(hue, 170, 220)

            self.group_state: set[str] = set()
            self.row_parent: Dict[str, Optional[str]] = {}
            self.row_depth: Dict[str, int] = {}
            self.row_root: Dict[str, str] = {}
            self.display_row_offsets: Dict[str, float] = {}
            self.display_rows: List[str] = []
            self.max_possible_plot_seconds = max(1.0, self.total_seconds)
            self.row_start_cache: Dict[str, datetime] = {}
            self.row_end_cache: Dict[str, datetime] = {}
            self.row_label_hitboxes: Dict[str, QRect] = {}
            self.gaps_by_row: Dict[str, List[GapAnnotation]] = defaultdict(list)
            self.gap_indices: Dict[int, int] = {}
            self.expanded_gaps: set[int] = set()
            self.gap_hitboxes: Dict[int, QRect] = {}
            self.turn_hitboxes: Dict[int, QRect] = {}
            self.turns_by_hitbox: Dict[int, Turn] = {}
            self.turn_log_viewers: Dict[int, QDialog] = {}
            for gap in self.gaps:
                self.gap_indices[id(gap)] = len(self.gap_indices)
                if gap.directory_name in self.rows:
                    self.gaps_by_row[gap.directory_name].append(gap)
            for values in self.gaps_by_row.values():
                values.sort(key=lambda item: item.start)
            for row_name, row_turns in self.rows.items():
                if row_turns:
                    self.row_start_cache[row_name] = row_turns[0].request_ts
                    self.row_end_cache[row_name] = row_turns[-1].response_ts
            self._recompute_row_groups()
            self.max_possible_plot_seconds = self._compute_max_plot_seconds(self.row_labels)

            self._recompute_layout()

        def _recompute_row_groups(self) -> None:
            self.row_parent = {}
            self.row_depth = {}

            for index, row_name in enumerate(self.row_labels):
                row_start = self.row_start_cache.get(row_name)
                row_end = self.row_end_cache.get(row_name)
                parent = None
                if row_start is not None and row_end is not None:
                    for candidate in reversed(self.row_labels[:index]):
                        candidate_start = self.row_start_cache.get(candidate)
                        candidate_end = self.row_end_cache.get(candidate)
                        if candidate_start is None or candidate_end is None:
                            continue
                        if candidate_start <= row_start and row_end <= candidate_end:
                            parent = candidate
                            break
                self.row_parent[row_name] = parent
                self.row_depth[row_name] = self._group_depth(row_name)

            self.row_root = {row_name: self._group_root(row_name) for row_name in self.row_labels}

        def _group_root(self, row_name: str) -> str:
            root = row_name
            visited: set[str] = set()
            while True:
                parent = self.row_parent.get(root)
                if not parent or parent in visited:
                    break
                visited.add(root)
                root = parent
            return root

        def _group_depth(self, row_name: str) -> int:
            parent = self.row_parent.get(row_name)
            if not parent:
                return 0
            depth = 1
            seen: set[str] = {row_name}
            while parent is not None and parent not in seen:
                seen.add(parent)
                grandparent = self.row_parent.get(parent)
                if not grandparent:
                    break
                depth += 1
                parent = grandparent
            return depth

        def _is_row_expanded(self, row_name: str) -> bool:
            return self._group_root(row_name) in self.group_state

        def _compute_max_plot_seconds(self, row_names: Sequence[str]) -> float:
            offsets = self._compute_display_row_offsets()
            max_row_end = 0.0
            for row_name in row_names:
                turns = self.rows.get(row_name, [])
                if not turns:
                    continue
                row_end = offsets.get(row_name, 0.0) + self._row_plot_end_seconds(row_name)
                if row_end > max_row_end:
                    max_row_end = row_end
            return max(1.0, max_row_end)

        def _elapsed_seconds(self, value: datetime, base: datetime) -> float:
            elapsed = (value - base).total_seconds()
            return float(elapsed) if elapsed > 0 else 0.0

        def _row_duration_seconds(self, row_name: str) -> float:
            turns = self.rows.get(row_name, [])
            if not turns:
                return 0.0
            return self._elapsed_seconds(turns[-1].response_ts, turns[0].request_ts)

        def _row_first_turn(self, row_name: str) -> Optional[Turn]:
            turns = self.rows.get(row_name, [])
            if not turns:
                return None
            return turns[0]

        def _group_start_time(self, row_name: str) -> datetime:
            root = self.row_root.get(row_name, row_name)
            return self.row_start_cache.get(
                root,
                self.row_start_cache.get(row_name, self.day_start),
            )

        def _row_plot_end_seconds(self, row_name: str) -> float:
            last_turn = self._row_last_turn(row_name)
            if last_turn is None:
                return 0.0
            return self._elapsed_seconds(last_turn.response_ts, self._group_start_time(row_name))

        def _row_last_turn(self, row_name: str) -> Optional[Turn]:
            turns = self.rows.get(row_name, [])
            if not turns:
                return None
            return turns[-1]

        def _compute_display_row_offsets(self) -> Dict[str, float]:
            offsets: Dict[str, float] = {}
            for row_name in self.row_labels:
                root = self.row_root.get(row_name, row_name)
                if root == row_name:
                    offsets[row_name] = 0.0
                else:
                    offsets[row_name] = offsets.get(root, 0.0)
            return offsets

        def _recompute_layout(self) -> None:
            self.display_rows = []
            self.display_row_offsets = self._compute_display_row_offsets()
            for row_name in self.row_labels:
                if self._is_row_expanded(row_name) and self.rows.get(row_name):
                    self.display_rows.append(row_name)

            self.max_possible_plot_seconds = self._compute_max_plot_seconds(self.row_labels)
            self.total_plot_seconds = self.max_possible_plot_seconds
            width = self.left_margin + self.right_margin + int(self.total_plot_seconds * self.seconds_per_pixel_cap)
            width = max(width, 1000)
            row_count = max(1, len(self.row_labels))
            height = self.top_margin + row_count * self.row_height + self.bottom_margin
            self.setMinimumSize(width, height)
            self.setMinimumWidth(width)
            self.setMinimumHeight(height)

        def _response_text_from_log(self, log_path: Path) -> str:
            try:
                lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines(keepends=True)
            except Exception as exc:
                return f"Failed to read log file: {log_path}\n{exc}"

            for index, line in enumerate(lines):
                if line.lstrip().startswith("# Response"):
                    return "".join(lines[index + 1 :]).strip()
            return "".join(lines).strip()

        def _open_turn_log(self, turn: Turn) -> None:
            content = "No .log file was found for this turn."
            if turn.log_path is not None:
                content = self._response_text_from_log(turn.log_path)

            viewer = QDialog(self)
            viewer.setWindowTitle(f"Turn {turn.request_index} response")
            layout = QVBoxLayout(viewer)
            text_view = QPlainTextEdit()
            text_view.setReadOnly(True)
            text_view.setPlainText(content)
            layout.addWidget(text_view)
            viewer.resize(900, 600)

            viewer_id = id(viewer)
            self.turn_log_viewers[viewer_id] = viewer
            viewer.destroyed.connect(lambda _obj: self.turn_log_viewers.pop(viewer_id, None))

            viewer.show()
            viewer.raise_()
            viewer.activateWindow()

        def _seconds_to_x(self, seconds: float) -> int:
            plot_width = max(1, self.width() - self.left_margin - self.right_margin)
            ratio = clamp(
                int((seconds / self.total_plot_seconds) * plot_width),
                0,
                plot_width,
            )
            return self.left_margin + ratio

        def _time_to_row_x(self, row_name: str, when: datetime) -> int:
            row_start = self._group_start_time(row_name)
            row_offset = self.display_row_offsets.get(row_name, 0.0)
            row_seconds = row_offset + self._elapsed_seconds(when, row_start)
            return self._seconds_to_x(row_seconds)

        def _format_seconds(self, seconds: float) -> str:
            total_seconds = max(0, int(seconds))
            h = total_seconds // 3600
            m = (total_seconds % 3600) // 60
            s = total_seconds % 60
            return f"{h:02}:{m:02}:{s:02}"

        def _draw_axis(self, painter: QPainter, plot_width: int, row_area_bottom: int) -> None:
            chart_top = self.top_margin
            chart_left = self.left_margin
            chart_right = chart_left + plot_width

            painter.setPen(QColor("black"))
            painter.drawLine(chart_left, chart_top, chart_right, chart_top)
            painter.drawLine(chart_left, row_area_bottom, chart_right, row_area_bottom)

            for i in range(self.tick_count):
                x = chart_left + int((plot_width * i) / (self.tick_count - 1))
                painter.drawLine(x, self.top_margin - 6, x, self.top_margin + 4)
                label_seconds = self.total_plot_seconds * i / (self.tick_count - 1)
                text = self._format_seconds(label_seconds)
                painter.drawText(x - 26, 22, text)

        def _draw_row(self, painter: QPainter, row_name: str, y: int) -> None:
            turns = self.rows.get(row_name, [])
            painter.setPen(QColor("black"))
            if not turns or not self._is_row_expanded(row_name):
                return None

            color = self.row_to_color[row_name]
            baseline = y + self.row_height // 2

            row_first_turn_x: Optional[int] = None
            row_last_turn_x: Optional[int] = None
            for turn in turns:
                x1 = self._time_to_row_x(row_name, turn.request_ts)
                x2 = self._time_to_row_x(row_name, turn.response_ts)
                if x2 < x1:
                    x1, x2 = x2, x1
                if x2 == x1:
                    x2 = x1 + 3
                if row_first_turn_x is None:
                    row_first_turn_x = x1
                row_last_turn_x = x2
                rect_left = clamp(x1, self.left_margin, self.width() - self.right_margin)
                rect_right = clamp(x2, self.left_margin, self.width() - self.right_margin)
                rect_left = min(rect_left, rect_right)

                y_bar = baseline - self.bar_height // 2
                rect = QRect(
                    rect_left,
                    y_bar,
                    max(3, rect_right - rect_left),
                    self.bar_height,
                )
                painter.fillRect(rect, color)
                painter.setPen(QColor("black"))
                painter.drawRect(rect)
                self.turn_hitboxes[id(turn)] = rect
                self.turns_by_hitbox[id(turn)] = turn
                font_metrics = painter.fontMetrics()
                text_y = y_bar + (self.bar_height + font_metrics.ascent() - font_metrics.descent()) // 2
                turn_s = seconds_from_ms(ms_between(turn.request_ts, turn.response_ts))
                painter.drawText(
                    rect_left + 3,
                    text_y,
                    f"{turn.request_index} ({turn_s}s)",
                )

            for gap in self.gaps_by_row.get(row_name, []):
                if gap.directory_name not in self.display_rows:
                    continue
                if gap.end < self.day_start or gap.start < self.day_start:
                    continue
                x1 = self._time_to_row_x(row_name, gap.start)
                x2 = self._time_to_row_x(row_name, gap.end)
                mid = int((x1 + x2) / 2)
                metrics = painter.fontMetrics()
                gap_id = self.gap_indices[id(gap)]
                lines = self._gap_box_lines(gap, gap_id in self.expanded_gaps)
                line_height = metrics.height()
                padding_x = 6
                padding_y = 4
                content_width = max(metrics.horizontalAdvance(line) for line in lines)
                box_w = min(
                    content_width + (padding_x * 2),
                    self.width() - self.left_margin - self.right_margin,
                )
                box_h = (len(lines) * line_height) + (padding_y * 2)
                box_left = clamp(
                    mid - (box_w // 2),
                    self.left_margin,
                    self.width() - self.right_margin - box_w,
                )
                box_top = y + self.row_height + 4
                box = QRect(box_left, box_top, box_w, box_h)
                self.gap_hitboxes[gap_id] = box
                painter.fillRect(box, QColor("white"))
                painter.setPen(QColor("black"))
                painter.drawRect(box)
                for index, line in enumerate(lines):
                    painter.drawText(
                        box_left + padding_x,
                        box_top + padding_y + (index * line_height) + metrics.ascent(),
                        line,
                    )
            return row_first_turn_x, row_last_turn_x

        def _gap_box_lines(self, gap: GapAnnotation, expanded: bool) -> List[str]:
            gap_s = seconds_between(gap.start, gap.end)
            header = f"{'[-]' if expanded else '[+]'} {gap_s}s"
            if not expanded:
                return [header]
            lines = [header]
            if gap.tools:
                lines.extend(gap.tools)
            else:
                lines.append("no-tools")
            return lines

        def _draw_transition_arrow(self, painter: QPainter, start_x: int, start_y: int, end_x: int, end_y: int, elapsed_seconds: int) -> None:
            painter.drawLine(start_x, start_y, end_x, end_y)
            arrow_left = end_x >= start_x
            if arrow_left:
                painter.drawLine(end_x, end_y, end_x - 6, end_y - 4)
                painter.drawLine(end_x, end_y, end_x - 6, end_y + 4)
            else:
                painter.drawLine(end_x, end_y, end_x + 6, end_y - 4)
                painter.drawLine(end_x, end_y, end_x + 6, end_y + 4)

            metrics = painter.fontMetrics()
            label = f"+{elapsed_seconds}s"
            text_w = metrics.horizontalAdvance(label)
            cx = (start_x + end_x) // 2
            cy = (start_y + end_y) // 2 - 6
            painter.drawText(clamp(cx - text_w // 2, self.left_margin, self.width() - text_w - 1), cy, label)

        def paintEvent(self, event: object) -> None:
            painter = QPainter(self)
            self.gap_hitboxes = {}
            self.turn_hitboxes = {}
            self.turns_by_hitbox = {}
            self.row_label_hitboxes = {}
            painter.fillRect(self.rect(), QColor("white"))

            if not self.row_labels:
                painter.setPen(QColor("black"))
                painter.drawText(20, 40, "No data in selected time window")
                return

            self._recompute_layout()
            row_area_bottom = self.top_margin + len(self.row_labels) * self.row_height
            plot_width = max(1, self.width() - self.left_margin - self.right_margin)
            self._draw_axis(painter, plot_width, row_area_bottom)

            row_turn_points: Dict[str, Tuple[int, int]] = {}
            row_first_times: Dict[str, datetime] = {}
            row_last_times: Dict[str, datetime] = {}

            for index, row_name in enumerate(self.row_labels):
                y = self.top_margin + index * self.row_height
                painter.setPen(QColor("black"))
                row_expanded = self._is_row_expanded(row_name)
                row_depth = self.row_depth.get(row_name, 0)
                base_label = self.row_display_labels.get(row_name, row_name)
                linked = " [linked]" if row_depth > 0 else ""
                indent = " " * (row_depth * 2)
                if row_expanded:
                    label_text = f"[-] {indent}{base_label}{linked}"
                else:
                    elapsed_seconds = int(self._row_duration_seconds(row_name))
                    if elapsed_seconds > 0:
                        label_text = f"[+] {indent}{base_label}{linked} ({self._format_seconds(elapsed_seconds)})"
                    else:
                        label_text = f"[+] {indent}{base_label}{linked}"
                painter.drawText(8, y + 16, label_text)
                self.row_label_hitboxes[row_name] = QRect(2, y + 2, self.left_margin - 4, self.row_height - 4)
                if row_name not in self.display_rows:
                    continue
                row_points = self._draw_row(painter, row_name, y)
                if not row_points:
                    continue
                first_turn_x, last_turn_x = row_points
                if first_turn_x is not None and last_turn_x is not None:
                    first_turn = self._row_first_turn(row_name)
                    last_turn = self._row_last_turn(row_name)
                    if first_turn is not None and last_turn is not None and first_turn_x is not None and last_turn_x is not None:
                        row_first_times[row_name] = first_turn.request_ts
                        row_last_times[row_name] = last_turn.response_ts
                        row_turn_points[row_name] = (first_turn_x, last_turn_x)

            visible_rows = [
                row for row in self.row_labels
                if row in self.display_rows and self.row_parent.get(row) is None
            ]
            for index, row_name in enumerate(visible_rows[:-1]):
                next_row_name = visible_rows[index + 1]
                previous_row_last = row_last_times.get(row_name)
                next_row_first = row_first_times.get(next_row_name)
                previous_points = row_turn_points.get(row_name)
                next_points = row_turn_points.get(next_row_name)
                if previous_row_last is None or next_row_first is None or previous_points is None or next_points is None:
                    continue
                elapsed_seconds = int((next_row_first - previous_row_last).total_seconds())
                if elapsed_seconds <= 0:
                    continue
                start_x = previous_points[1]
                end_x = next_points[0]
                start_y = (self.top_margin + self.row_labels.index(row_name) * self.row_height) + self.row_height // 2
                end_y = (self.top_margin + self.row_labels.index(next_row_name) * self.row_height) + self.row_height // 2
                self._draw_transition_arrow(painter, start_x, start_y, end_x, end_y, elapsed_seconds)

        def mousePressEvent(self, event: object) -> None:
            click_pos = event.pos()
            for row_name, hitbox in list(self.row_label_hitboxes.items()):
                if hitbox.contains(click_pos):
                    row_root = self._group_root(row_name)
                    if row_root in self.group_state:
                        self.group_state.remove(row_root)
                    else:
                        self.group_state.add(row_root)
                    self._recompute_layout()
                    self.update()
                    return
            for gap_id, hitbox in list(self.gap_hitboxes.items()):
                if hitbox.contains(click_pos):
                    if gap_id in self.expanded_gaps:
                        self.expanded_gaps.remove(gap_id)
                    else:
                        self.expanded_gaps.add(gap_id)
                    self.update()
                    return
            for hitbox_id, hitbox in list(self.turn_hitboxes.items()):
                if hitbox.contains(click_pos):
                    turn = self.turns_by_hitbox.get(hitbox_id)
                    if turn is not None:
                        self._open_turn_log(turn)
                        return
            super().mousePressEvent(event)

    class GanttWindow(QMainWindow):
        def __init__(self, turns: Sequence[Turn], gaps: Sequence[GapAnnotation], day_start: datetime, day_end: datetime):
            super().__init__()
            self.setWindowTitle("LLM History Gantt")
            self.resize(1400, 700)

            canvas = GanttCanvas(turns, gaps, day_start, day_end)
            scroll = QScrollArea()
            scroll.setWidget(canvas)
            scroll.setWidgetResizable(False)
            self.setCentralWidget(scroll)

    app = QApplication([])
    window = GanttWindow(turns, gaps, day_start, day_end)
    window.show()
    return app.exec()


def print_debug(
    turns: Sequence[Turn],
    gaps: Sequence[GapAnnotation],
    model_info_map: Optional[Dict[str, Dict[str, Any]]] = None,
    show_costs: bool = False,
) -> None:
    by_directory: Dict[str, List[Turn]] = defaultdict(list)
    by_optype: Dict[str, int] = defaultdict(int)
    for turn in turns:
        by_directory[turn.directory_name].append(turn)
        by_optype[turn.optype] += 1

    print(
        json.dumps(
            {
                "type": "debug_summary",
                "turn_count": len(turns),
                "gap_count": len(gaps),
                "directory_count": len(by_directory),
                "turns_by_optype": dict(sorted(by_optype.items())),
                "inference_ms": calculate_inference_ms(turns),
                "wall_ms": calculate_wall_ms(turns),
                "show_costs": show_costs,
            },
            sort_keys=True,
        )
    )

    cost_bands_cache: Dict[Tuple[str, str], List[PriceBand]] = {}
    model_totals, model_costs, model_cost_missing = compute_model_usage(
        turns,
        model_info_map=model_info_map,
        show_costs=show_costs,
    )
    for model_name in sorted(model_totals):
        model_usage = model_totals[model_name]
        payload = {
            "type": "model_usage",
            "model": model_name,
            "input_tokens": model_usage["input"],
            "cached_input_tokens": model_usage["cached"],
            "output_tokens": model_usage["output"],
        }
        if show_costs and model_info_map is not None:
            if model_cost_missing.get(model_name, False):
                payload["estimated_cost"] = None
            else:
                payload["estimated_cost"] = model_costs.get(model_name, 0.0)
        print(json.dumps(payload, sort_keys=True))

    for directory_name, directory_turns in sorted(by_directory.items(), key=lambda item: item[0]):
        directory_turns = sorted(directory_turns, key=lambda item: item.request_ts)
        by_optype = directory_turns[0].optype if directory_turns else ""
        row_label_value = directory_turns[0].row_label if directory_turns else None
        directory_gaps = [
            gap
            for gap in gaps
            if gap.directory_name == directory_name
        ]
        print(
            json.dumps(
                {
                    "type": "debug_directory",
                    "directory": directory_name,
                    "optype": by_optype,
                    "row_label": row_label_value,
                    "turn_count": len(directory_turns),
                    "gap_count": len(directory_gaps),
                    "first_request": directory_turns[0].request_ts.isoformat() if directory_turns else None,
                    "last_request": directory_turns[-1].request_ts.isoformat() if directory_turns else None,
                },
                sort_keys=True,
            )
        )

    for i, turn in enumerate(sorted(turns, key=lambda item: item.request_ts), start=1):
        payload = {
            "type": "turn",
            "turn_number": i,
            "directory": turn.directory_name,
            "optype": turn.optype,
            "row_label": turn.row_label,
            "request_index": turn.request_index,
            "request_ts": turn.request_ts.isoformat(),
            "response_ts": turn.response_ts.isoformat(),
            "model": turn.model,
            "service_tier": turn.service_tier,
            "input_tokens": turn.input_tokens,
            "cached_input_tokens": turn.cached_input_tokens,
            "output_tokens": turn.output_tokens,
            "tools": turn.tools,
        }
        if show_costs and model_info_map is not None:
            payload["estimated_cost"] = cost_for_turn(turn, model_info_map, cost_bands_cache)
        print(json.dumps(payload, sort_keys=True))
    for i, gap in enumerate(sorted(gaps, key=lambda item: (item.start, item.directory_name, item.from_request)), start=1):
        payload = {
            "type": "gap",
            "gap_number": i,
            "from_directory": gap.directory_name,
            "from_request": gap.from_request,
            "to_request": gap.to_request,
            "start": gap.start.isoformat(),
            "end": gap.end.isoformat(),
            "gap_ms": ms_between(gap.start, gap.end),
            "gap_s": seconds_between(gap.start, gap.end),
            "turn_ms": gap.turn_duration_ms,
            "turn_s": seconds_from_ms(gap.turn_duration_ms),
            "tools": gap.tools,
        }
        print(json.dumps(payload, sort_keys=True))


def main() -> int:
    args = parse_args()
    target_day = parse_day(args.day)
    explicit_history = Path(args.history_path) if args.history_path is not None else None
    start_arg = args.start

    if start_arg is not None and explicit_history is None:
        start_candidate_dir = Path(start_arg).expanduser()
        if start_candidate_dir.is_dir():
            explicit_history = start_candidate_dir
            start_arg = None

    if explicit_history is not None and args.history != DEFAULT_HISTORY_PATH:
        raise SystemExit("Use either positional history path or --history, not both.")

    history_root = explicit_history or Path(args.history).expanduser()
    if not history_root.exists():
        raise SystemExit(f"History path not found: {history_root}")

    start_time = parse_time_or_none(start_arg)
    if start_arg is not None and start_time is None:
        raise SystemExit("start must be HH:MM:SS or HH-MM-SS, or a history directory path")

    if start_time is None:
        day_start = first_history_directory_timestamp(history_root)
        if day_start is None:
            raise SystemExit("No parseable history directories found")
    else:
        day_start = datetime.combine(target_day, start_time)

    model_info_map: Optional[Dict[str, Dict[str, Any]]] = None
    if args.show_costs:
        model_info_map = fetch_model_info(BROKK_PROXY_URL, "BROKK", proxy_brokk_api_key())

    if args.end is None:
        day_end = last_history_directory_timestamp(history_root)
        if day_end is None:
            raise SystemExit("No parseable history directories found")
    else:
        end_time = parse_time_or_none(args.end)
        if end_time is None:
            raise SystemExit("end must be HH:MM:SS or HH-MM-SS")
        day_end = datetime.combine(
            day_start.date() if start_arg is None else target_day,
            end_time,
        )
        if day_end < day_start:
            raise SystemExit("--end must be after --start")

    turns, gaps = load_data(history_root, day_start, day_end)

    if args.debug:
        print_debug(turns, gaps, model_info_map=model_info_map, show_costs=args.show_costs)
        print_timing_summary(turns)
        print_model_usage_summary(
            turns,
            model_info_map=model_info_map,
            show_costs=args.show_costs,
        )
        return 0

    if not turns:
        print_timing_summary(turns)
        print_model_usage_summary(
            turns,
            model_info_map=model_info_map,
            show_costs=args.show_costs,
        )
        print("No matching turns in selected window.")
        return 0

    print_timing_summary(turns)
    print_model_usage_summary(
        turns,
        model_info_map=model_info_map,
        show_costs=args.show_costs,
    )
    return launch_gui(turns, gaps, day_start, day_end)


if __name__ == "__main__":
    raise SystemExit(main())
