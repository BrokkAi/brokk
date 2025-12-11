#!/usr/bin/env bash
#
# Bisect a performance regression between two commits using git worktrees.
# It builds and runs the TreeSitter baseline runner multiple times per commit,
# collects JSON results, and uses compare-perf-results.py to decide regression.
#
# Usage:
#   scripts/bisect-perf-regression.sh [options] <good_commit> <bad_commit>
#
# Options:
#   --iterations N        Number of iterations per commit (default: 3)
#   --retries M           Retries per iteration when JSON is not produced and decision retries for midpoint when inconclusive (default: 1)
#   --runner-args "..."   Arguments to scripts/run-treesitter-repos.sh (must include a command)
#                         Example: --runner-args "chromium-cpp --max-files 1000 --json"
#   --workdir DIR         Directory to store artifacts (default: <repo>/perf-bisect-<timestamp>/)
#   --keep-worktrees      Keep created worktrees for inspection (default: remove on exit)
#   -h, --help            Show this help
#
# Requirements:
#   - bash, git, python3
#   - scripts/run-treesitter-repos.sh
#   - scripts/compare-perf-results.py
#
set -euo pipefail

# --------------- Colors ---------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# --------------- Globals ---------------
ITERATIONS=3
RETRIES=1
RUNNER_ARGS="quick --json"
WORKDIR=""
KEEP_WORKTREES=false

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [[ -z "${REPO_ROOT}" ]] || [[ ! -d "${REPO_ROOT}" ]]; then
  echo -e "${RED}This script must be run inside a Git repository.${NC}" >&2
  exit 1
fi

RUNNER_SCRIPT_REL="scripts/run-treesitter-repos.sh"
RUNNER_SCRIPT="${REPO_ROOT}/${RUNNER_SCRIPT_REL}"
COMPARE_SCRIPT="${REPO_ROOT}/scripts/compare-perf-results.py"

if [[ ! -x "${RUNNER_SCRIPT}" ]]; then
  echo -e "${RED}Runner script not found or not executable: ${RUNNER_SCRIPT}${NC}" >&2
  exit 1
fi
if [[ ! -f "${COMPARE_SCRIPT}" ]]; then
  echo -e "${RED}Compare script not found: ${COMPARE_SCRIPT}${NC}" >&2
  exit 1
fi

# Track created worktrees for cleanup
declare -a CREATED_WORKTREES=()

# --------------- Helpers ---------------
usage() {
  cat <<EOF
Bisect a performance regression between two commits using git worktrees.

Usage:
  $(basename "$0") [options] <good_commit> <bad_commit>

Options:
  --iterations N        Number of iterations per commit (default: ${ITERATIONS})
  --retries M           Retries per iteration when JSON is not produced and decision retries for midpoint when inconclusive (default: ${RETRIES})
  --runner-args "..."   Arguments passed to run-treesitter-repos.sh. Must include a command.
                        Example: --runner-args "chromium-cpp --max-files 1000 --json"
  --workdir DIR         Directory to store artifacts (default: <repo>/perf-bisect-<timestamp>/)
  --keep-worktrees      Keep created worktrees for inspection
  -h, --help            Show this help

Notes:
- The runner should produce JSON results (--json). If not present in --runner-args,
  --json will be appended automatically.
- Artifacts will be stored under: <workdir>/{results,logs,worktrees}
EOF
}

timestamp() {
  date +"%Y%m%d-%H%M%S"
}

abs_path() {
  local p="$1"
  if [[ "$p" = /* ]]; then
    printf "%s" "$p"
  else
    printf "%s/%s" "$PWD" "$p"
  fi
}

cleanup_worktrees() {
  if [[ "${KEEP_WORKTREES}" == "true" ]]; then
    return
  fi
  if [[ "${#CREATED_WORKTREES[@]}" -eq 0 ]]; then
    return
  fi
  echo -e "${YELLOW}Cleaning up ${#CREATED_WORKTREES[@]} worktree(s)...${NC}"
  for wt in "${CREATED_WORKTREES[@]}"; do
    if [[ -d "$wt" ]]; then
      # Remove from repo root so git can find the worktree reference
      (cd "${REPO_ROOT}" && git worktree remove --force "$wt") || true
    fi
  done
  # Prune any stale references
  (cd "${REPO_ROOT}" && git worktree prune) || true
}

on_exit() {
  local code=$?
  if [[ $code -ne 0 ]]; then
    echo -e "${RED}Script failed with exit code $code. Triggering cleanup...${NC}" >&2
  fi
  cleanup_worktrees
}
trap on_exit EXIT INT TERM

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo -e "${RED}Required command not found: $1${NC}" >&2
    exit 1
  }
}

resolve_sha() {
  local ref="$1"
  git -C "${REPO_ROOT}" rev-parse "${ref}"
}

is_ancestor() {
  local anc="$1"
  local desc="$2"
  if git -C "${REPO_ROOT}" merge-base --is-ancestor "$anc" "$desc"; then
    return 0
  fi
  return 1
}

midpoint_on_path() {
  local good="$1"
  local bad="$2"
  # List commits on ancestry path from good (exclusive) to bad (inclusive)
  local -a commits
  mapfile -t commits < <(git -C "${REPO_ROOT}" rev-list --ancestry-path "${good}..${bad}")
  local n="${#commits[@]}"
  if [[ "$n" -eq 0 ]]; then
    echo ""
    return 0
  fi
  local mid_index=$(( n / 2 ))
  echo "${commits[$mid_index]}"
}

ensure_json_flag() {
  local args="$1"
  if [[ "$args" == *"--json"* ]]; then
    printf "%s" "$args"
  else
    printf "%s --json" "$args"
  fi
}

# Runs the baseline at a given commit, collecting ITERATIONS JSON files into:
#   <WORKDIR>/results/<sha>/
# Returns: prints the absolute path to the per-commit results dir on stdout.
run_commit() {
  local commit="$1"
  local sha
  sha="$(resolve_sha "$commit")"
  local sha_short
  sha_short="$(git -C "${REPO_ROOT}" rev-parse --short "$sha")"

  local wt_dir="${WORKDIR}/worktrees/wt-${sha_short}"
  local res_dir="${WORKDIR}/results/${sha}"
  local log_dir="${WORKDIR}/logs/${sha}"
  mkdir -p "${wt_dir}" "${res_dir}" "${log_dir}"
  local runner="${wt_dir}/${RUNNER_SCRIPT_REL}"

  echo -e "${BLUE}Creating worktree for ${sha_short} -> ${wt_dir}${NC}"
  # Add a detached worktree
  (cd "${REPO_ROOT}" && git worktree add --detach "${wt_dir}" "${sha}") >/dev/null
  CREATED_WORKTREES+=("${wt_dir}")

  # Run iterations
  pushd "${wt_dir}" >/dev/null

  # Ensure baseline-results dir exists
  mkdir -p baseline-results

  local i
  for (( i=1; i<=ITERATIONS; i++ )); do
    # Clean up any previous JSON outputs to make detection deterministic
    rm -f baseline-results/*.json || true

    local attempt=1
    local produced=""
    while (( attempt <= RETRIES )); do
      echo -e "${GREEN}[${sha_short}] Iteration ${i}/${ITERATIONS} (attempt ${attempt}/${RETRIES})${NC}"
      # Use a subshell with bash -lc to preserve any quoting inside RUNNER_ARGS
      local start_ts
      start_ts="$(timestamp)"
      if ! bash -lc "bash \"${runner}\" ${RUNNER_ARGS}"; then
        echo -e "${YELLOW}Runner failed for ${sha_short} (iteration ${i}, attempt ${attempt}). Retrying if available...${NC}" >&2
      fi

      # Try to find the newest JSON output
      produced="$(ls -t baseline-results/*.json 2>/dev/null | head -n1 || true)"
      if [[ -n "${produced}" ]] && [[ -s "${produced}" ]]; then
        local dst="${res_dir}/iter-$(printf "%02d" "${i}").json"
        cp -f "${produced}" "${dst}"
        echo -e "${GREEN}Captured ${dst}${NC}"
        break
      fi
      attempt=$((attempt + 1))
    done

    if [[ -z "${produced}" ]]; then
      echo -e "${RED}No JSON output produced for ${sha_short} at iteration ${i}. Aborting commit run.${NC}" >&2
      popd >/dev/null
      return 1
    fi
  done

  popd >/dev/null

  if [[ "${KEEP_WORKTREES}" != "true" ]]; then
    echo -e "${YELLOW}Removing worktree ${wt_dir}${NC}"
    (cd "${REPO_ROOT}" && git worktree remove --force "${wt_dir}") >/dev/null || true
  fi

  echo "${res_dir}"
}

# Compare two result directories using compare-perf-results.py in JSON mode.
# Prints "regression", "ok", or "unknown".
compare_dirs_status() {
  local base_dir="$1"
  local head_dir="$2"
  local base_sha_short="$3"
  local head_sha_short="$4"

  local out_json
  out_json="$(
    BASE_SHA_SHORT="${base_sha_short}" HEAD_SHA_SHORT="${head_sha_short}" \
      python3 "${COMPARE_SCRIPT}" --format json "${base_dir}" "${head_dir}" 2>/dev/null
  )"

  # Parse using Python to avoid dependency on jq
  python3 - "$out_json" <<'PY'
import json, sys
try:
    data = json.loads(sys.argv[1])
    metrics = data.get("metrics", {})
    statuses = [v.get("status", "").lower() for v in metrics.values()]
    if not statuses:
        print("unknown")
    elif any(s == "regression" for s in statuses):
        print("regression")
    elif all(s in ("ok", "improvement") for s in statuses if s):
        print("ok")
    else:
        print("unknown")
except Exception:
    print("unknown")
PY
}

# --------------- Argument parsing ---------------
parse_args() {
  if [[ $# -eq 0 ]]; then
    usage
    exit 1
  fi

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --iterations)
        shift
        ITERATIONS="${1:-}"
        if ! [[ "${ITERATIONS}" =~ ^[0-9]+$ ]] || [[ "${ITERATIONS}" -le 0 ]]; then
          echo -e "${RED}--iterations must be a positive integer${NC}" >&2
          exit 1
        fi
        ;;
      --retries)
        shift
        RETRIES="${1:-}"
        if ! [[ "${RETRIES}" =~ ^[0-9]+$ ]] || [[ "${RETRIES}" -le 0 ]]; then
          echo -e "${RED}--retries must be a positive integer${NC}" >&2
          exit 1
        fi
        ;;
      --runner-args)
        shift
        RUNNER_ARGS="${1:-}"
        ;;
      --workdir)
        shift
        WORKDIR="$(abs_path "${1:-}")"
        ;;
      --keep-worktrees)
        KEEP_WORKTREES=true
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      --) # end of options
        shift
        break
        ;;
      -*)
        echo -e "${RED}Unknown option: $1${NC}" >&2
        usage
        exit 1
        ;;
      *)
        # Positional args begin
        break
        ;;
    esac
    shift || true
  done

  if [[ $# -lt 2 ]]; then
    echo -e "${RED}Missing required arguments: <good_commit> <bad_commit>${NC}" >&2
    usage
    exit 1
  fi

  GOOD_COMMIT="$1"
  BAD_COMMIT="$2"

  # Ensure --json in runner args
  RUNNER_ARGS="$(ensure_json_flag "${RUNNER_ARGS}")"

  if [[ -z "${WORKDIR}" ]]; then
    WORKDIR="${REPO_ROOT}/perf-bisect-$(timestamp)"
  fi
  WORKDIR="$(abs_path "${WORKDIR}")"
  mkdir -p "${WORKDIR}/results" "${WORKDIR}/logs" "${WORKDIR}/worktrees"
}

# --------------- Main ---------------
main() {
  require_cmd git
  require_cmd python3
  parse_args "$@"

  local good_sha bad_sha
  good_sha="$(resolve_sha "${GOOD_COMMIT}")"
  bad_sha="$(resolve_sha "${BAD_COMMIT}")"

  if ! is_ancestor "${good_sha}" "${bad_sha}"; then
    echo -e "${RED}GOOD commit must be an ancestor of BAD commit.${NC}" >&2
    echo "good: ${good_sha}"
    echo "bad : ${bad_sha}"
    exit 1
  fi

  local good_sha_short bad_sha_short
  good_sha_short="$(git -C "${REPO_ROOT}" rev-parse --short "${good_sha}")"
  bad_sha_short="$(git -C "${REPO_ROOT}" rev-parse --short "${bad_sha}")"

  echo -e "${BLUE}Artifacts directory: ${WORKDIR}${NC}"
  echo -e "${BLUE}Runner args: ${RUNNER_ARGS}${NC}"
  echo -e "${BLUE}Iterations: ${ITERATIONS}, Retries: ${RETRIES}${NC}"
  echo -e "${BLUE}Good: ${good_sha} (${good_sha_short})  Bad: ${bad_sha} (${bad_sha_short})${NC}"

  # Pre-check: run endpoints
  echo -e "${GREEN}Running baseline on GOOD commit ${good_sha_short}${NC}"
  local good_dir
  if ! good_dir="$(run_commit "${good_sha}")"; then
    echo -e "${RED}Failed to run baseline on GOOD commit ${good_sha_short}.${NC}" >&2
    exit 1
  fi

  echo -e "${GREEN}Running baseline on BAD commit ${bad_sha_short}${NC}"
  local bad_dir
  if ! bad_dir="$(run_commit "${bad_sha}")"; then
    echo -e "${RED}Failed to run baseline on BAD commit ${bad_sha_short}.${NC}" >&2
    exit 1
  fi

  # Confirm that there is an actual regression between endpoints
  local pre_status
  pre_status="$(compare_dirs_status "${good_dir}" "${bad_dir}" "${good_sha_short}" "${bad_sha_short}")"
  if [[ "${pre_status}" != "regression" ]]; then
    echo -e "${YELLOW}No reproducible regression detected between endpoints (${good_sha_short} -> ${bad_sha_short}).${NC}"
    echo -e "${YELLOW}compare-perf-results status: ${pre_status}${NC}"
    echo "GOOD results: ${good_dir}"
    echo "BAD results : ${bad_dir}"
    echo "Artifacts kept at: ${WORKDIR}"
    exit 2
  fi

  # Bisect loop using linear ancestry path (--reverse) and midpoint retries on inconclusive results.
  # Build the ancestry path from GOOD..BAD, oldest->newest, and include GOOD at the start.
  local -a PATH_COMMITS
  mapfile -t PATH_COMMITS < <(git -C "${REPO_ROOT}" rev-list --ancestry-path "${good_sha}..${bad_sha}" --reverse)
  PATH_COMMITS=( "${good_sha}" "${PATH_COMMITS[@]}" )
  local n="${#PATH_COMMITS[@]}"
  if [[ "${n}" -lt 2 ]]; then
    echo -e "${RED}Unexpected ancestry path length: ${n}${NC}" >&2
    exit 1
  fi

  # Cache result directories and short SHAs for tested commits.
  declare -A DIRS
  declare -A SHORTS
  DIRS["${good_sha}"]="${good_dir}"
  DIRS["${bad_sha}"]="${bad_dir}"
  SHORTS["${good_sha}"]="${good_sha_short}"
  SHORTS["${bad_sha}"]="${bad_sha_short}"

  local lo=0
  local hi=$(( n - 1 ))

  while (( hi - lo > 1 )); do
    local mid_index=$(( (lo + hi) / 2 ))
    local mid_sha="${PATH_COMMITS[$mid_index]}"
    local lo_sha="${PATH_COMMITS[$lo]}"
    local hi_sha="${PATH_COMMITS[$hi]}"

    if [[ -z "${SHORTS[${mid_sha}]:-}" ]]; then
      SHORTS["${mid_sha}"]="$(git -C "${REPO_ROOT}" rev-parse --short "${mid_sha}")"
    fi
    local mid_short="${SHORTS[${mid_sha}]}"
    local lo_short="${SHORTS[${lo_sha}]}"
    local hi_short="${SHORTS[${hi_sha}]}"

    echo -e "${GREEN}Testing midpoint ${mid_sha} (${mid_short}) between ${lo_short}..${hi_short}${NC}"

    # Ensure results exist for midpoint
    local mid_dir="${DIRS[${mid_sha}]:-}"
    if [[ -z "${mid_dir}" ]] || [[ ! -d "${mid_dir}" ]]; then
      if ! mid_dir="$(run_commit "${mid_sha}")"; then
        echo -e "${YELLOW}Failed to run ${mid_short}; treating as inconclusive and retrying if allowed...${NC}" >&2
        mid_dir=""
      fi
      if [[ -n "${mid_dir}" ]]; then
        DIRS["${mid_sha}"]="${mid_dir}"
      fi
    fi

    # Compare GOOD (lo) vs MID
    local status="unknown"
    if [[ -n "${DIRS[${lo_sha}]:-}" ]] && [[ -n "${DIRS[${mid_sha}]:-}" ]]; then
      status="$(compare_dirs_status "${DIRS[${lo_sha}]}" "${DIRS[${mid_sha}]}" "${lo_short}" "${mid_short}")"
    fi

    # If not a regression, retry up to --retries times by re-running midpoint to replace JSON runs.
    if [[ "${status}" != "regression" ]]; then
      local attempt=1
      while (( attempt <= RETRIES )); do
        echo -e "${YELLOW}Inconclusive midpoint result (${status}) for ${mid_short}; retry ${attempt}/${RETRIES}...${NC}"
        if mid_dir="$(run_commit "${mid_sha}")"; then
          DIRS["${mid_sha}"]="${mid_dir}"
          status="$(compare_dirs_status "${DIRS[${lo_sha}]}" "${DIRS[${mid_sha}]}" "${lo_short}" "${mid_short}")"
          if [[ "${status}" == "regression" ]]; then
            break
          fi
        else
          echo -e "${YELLOW}Midpoint rerun failed for ${mid_short}.${NC}" >&2
        fi
        attempt=$((attempt + 1))
      done
    fi

    if [[ "${status}" == "regression" ]]; then
      # Midpoint behaves like BAD -> narrow upper bound
      hi="${mid_index}"
      echo -e "${RED}${mid_short} classified as BAD (regression).${NC}"
    else
      # Midpoint behaves like GOOD (or still inconclusive after retries) -> advance lower bound
      lo="${mid_index}"
      echo -e "${GREEN}${mid_short} classified as GOOD.${NC}"
    fi
  done

  local first_bad_sha="${PATH_COMMITS[$hi]}"
  local first_bad_short="$(git -C "${REPO_ROOT}" rev-parse --short "${first_bad_sha}")"
  echo -e "${GREEN}First bad commit identified: ${first_bad_sha} (${first_bad_short})${NC}"
  echo "${first_bad_sha}" > "${WORKDIR}/first-bad-commit.txt"

  # Produce a final markdown summary using current GOOD (lo) and BAD (hi)
  local final_good_sha="${PATH_COMMITS[$lo]}"
  local final_bad_sha="${PATH_COMMITS[$hi]}"
  local final_good_short="$(git -C "${REPO_ROOT}" rev-parse --short "${final_good_sha}")"
  local final_bad_short="$(git -C "${REPO_ROOT}" rev-parse --short "${final_bad_sha}")"
  local final_good_dir="${DIRS[${final_good_sha}]}"
  local final_bad_dir="${DIRS[${final_bad_sha}]}"

  local report_md="${WORKDIR}/final-report.md"
  BASE_SHA_SHORT="${final_good_short}" \
  HEAD_SHA_SHORT="${final_bad_short}" \
    python3 "${COMPARE_SCRIPT}" --format markdown "${final_good_dir}" "${final_bad_dir}" > "${report_md}" || true
  echo -e "${BLUE}Summary written to: ${report_md}${NC}"
  echo -e "${BLUE}Artifacts kept at: ${WORKDIR}${NC}"
}

main "$@"
