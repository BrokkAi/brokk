#!/usr/bin/env bash
#
# Brokk installer: bifrost (auth proxy) + brokk-acp (Rust ACP server).
#
# Quick start:
#   curl -sSL https://install.brokk.ai | bash
#
# Or with options:
#   curl -sSL https://install.brokk.ai | bash -s -- --install-dir ~/bin
#
# Supported platforms (intersection of bifrost and brokk-acp builds):
#   - Linux x86_64
#   - macOS aarch64 (Apple Silicon)
#
# For Windows, use install.ps1.

set -euo pipefail

INSTALLER_VERSION="0.1.0"
BIFROST_REPO="BrokkAi/bifrost"
BROKK_REPO="BrokkAi/brokk"
ACP_TAG_PREFIX="brokk-acp-rust-"

DEFAULT_INSTALL_DIR="${HOME}/.local/bin"

# ---- helpers -------------------------------------------------------------

log()  { printf '%s\n' "$*" >&2; }
warn() { printf 'warning: %s\n' "$*" >&2; }
err()  { printf 'error: %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<EOF
Brokk installer ${INSTALLER_VERSION}

Installs bifrost (auth proxy) and brokk-acp (Rust ACP server) to a local
directory and ensures it is on PATH.

Usage:
  install.sh [options]

Options:
  --install-dir DIR        Install location (default: \$HOME/.local/bin)
  --bifrost-version VER    Pin bifrost version (e.g. v0.2.0). Default: latest.
  --acp-version VER        Pin brokk-acp version (e.g. 0.1.0). Default: latest.
  --skip-path              Do not modify shell rc files.
  --no-verify              Skip running --version on installed binaries.
  -h, --help               Show this help.
  -V, --version            Print installer version.

Environment variables (override defaults):
  BROKK_INSTALL_DIR        Same as --install-dir.
  BIFROST_VERSION          Same as --bifrost-version.
  BROKK_ACP_VERSION        Same as --acp-version.
EOF
}

# ---- platform detection --------------------------------------------------

detect_platform() {
    local os arch
    os="$(uname -s)"
    arch="$(uname -m)"

    case "$os" in
        Linux)  os="linux" ;;
        Darwin) os="macos" ;;
        *) err "Unsupported OS: $os (use install.ps1 on Windows)" ;;
    esac

    case "$arch" in
        x86_64|amd64)  arch="x86_64" ;;
        arm64|aarch64) arch="aarch64" ;;
        *) err "Unsupported architecture: $arch" ;;
    esac

    case "${os}-${arch}" in
        linux-x86_64|macos-aarch64) ;;
        macos-x86_64)
            err "Intel Macs are not supported: brokk-acp ships only Apple Silicon (aarch64) builds for macOS." ;;
        linux-aarch64)
            err "Linux ARM64 is not supported: brokk-acp does not yet publish aarch64 Linux builds." ;;
        *) err "Unsupported platform combination: ${os}-${arch}" ;;
    esac

    printf '%s %s\n' "$os" "$arch"
}

bifrost_target() {
    case "$1-$2" in
        linux-x86_64)  echo "x86_64-unknown-linux-gnu" ;;
        macos-aarch64) echo "aarch64-apple-darwin" ;;
        *) err "internal: no bifrost target for $1-$2" ;;
    esac
}

acp_asset_name() {
    case "$1-$2" in
        linux-x86_64)  echo "brokk-acp-linux-x86_64" ;;
        macos-aarch64) echo "brokk-acp-macos-aarch64" ;;
        *) err "internal: no brokk-acp asset for $1-$2" ;;
    esac
}

# ---- HTTP -----------------------------------------------------------------

# download URL DEST [--allow-fail]
# Forces TLS 1.2+ and HTTPS-only. Returns non-zero if download fails.
download() {
    local url="$1" dest="$2"
    if command -v curl >/dev/null 2>&1; then
        curl --proto '=https' --tlsv1.2 -fsSL "$url" -o "$dest"
    elif command -v wget >/dev/null 2>&1; then
        wget --https-only -qO "$dest" "$url"
    else
        err "Neither curl nor wget is available."
    fi
}

# ---- release lookups -----------------------------------------------------

# Latest release on a repo (works when latest tag is unambiguous, e.g. bifrost).
get_latest_tag() {
    local repo="$1" tmp tag
    tmp="$(mktemp)"
    if ! download "https://api.github.com/repos/${repo}/releases/latest" "$tmp"; then
        rm -f "$tmp"
        err "Could not fetch latest release for ${repo} (network or rate-limited?)."
    fi
    tag="$(sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$tmp" | head -n1)"
    rm -f "$tmp"
    [ -n "$tag" ] || err "Could not parse tag_name from ${repo} latest release."
    printf '%s\n' "$tag"
}

# Newest release whose tag starts with PREFIX (used for brokk-acp-rust-* tags
# in the brokk monorepo, where /releases/latest may point at a different
# component's release).
get_latest_prefixed_tag() {
    local repo="$1" prefix="$2" tmp tag
    tmp="$(mktemp)"
    if ! download "https://api.github.com/repos/${repo}/releases?per_page=100" "$tmp"; then
        rm -f "$tmp"
        err "Could not fetch releases for ${repo}."
    fi
    tag="$(sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$tmp" \
           | grep "^${prefix}" | head -n1 || true)"
    rm -f "$tmp"
    [ -n "$tag" ] || err "No release found for ${repo} with tag prefix ${prefix}. Pin a version with --acp-version or BROKK_ACP_VERSION."
    printf '%s\n' "$tag"
}

# ---- install helpers -----------------------------------------------------

verify_sha256() {
    local file="$1" expected="$2" got
    if command -v sha256sum >/dev/null 2>&1; then
        got="$(sha256sum "$file" | awk '{print $1}')"
    elif command -v shasum >/dev/null 2>&1; then
        got="$(shasum -a 256 "$file" | awk '{print $1}')"
    else
        warn "No sha256sum/shasum available; skipping checksum verification."
        return 0
    fi
    if [ "$got" != "$expected" ]; then
        err "Checksum mismatch for ${file##*/} (expected ${expected}, got ${got})"
    fi
}

install_file() {
    local src="$1" dst="$2"
    # Use install(1) when available for atomic move + chmod; fall back to cp.
    if command -v install >/dev/null 2>&1; then
        install -m 0755 "$src" "$dst"
    else
        cp "$src" "$dst"
        chmod 0755 "$dst"
    fi
}

# ---- bifrost install ----------------------------------------------------

install_bifrost() {
    local os="$1" arch="$2" install_dir="$3" version="$4"
    local target tarball url tmp expected_sha bin_path

    target="$(bifrost_target "$os" "$arch")"
    tarball="bifrost-${version}-${target}.tar.gz"
    url="https://github.com/${BIFROST_REPO}/releases/download/${version}/${tarball}"

    log "==> Downloading bifrost ${version} (${target})"
    tmp="$(mktemp -d)"
    if ! download "$url" "${tmp}/${tarball}"; then
        rm -rf "$tmp"
        err "Failed to download ${url}"
    fi

    # Verify checksum if upstream provides .sha256 sidecar (bifrost does).
    if download "${url}.sha256" "${tmp}/${tarball}.sha256" 2>/dev/null; then
        expected_sha="$(awk '{print $1}' "${tmp}/${tarball}.sha256")"
        verify_sha256 "${tmp}/${tarball}" "$expected_sha"
    else
        warn "No sha256 sidecar for bifrost ${version}; skipping checksum verification."
    fi

    ( cd "$tmp" && tar -xzf "$tarball" )
    # Tarball layout varies (binary at root vs. inside a dir): search for it.
    bin_path="$(find "$tmp" -maxdepth 4 -type f -name 'bifrost' | head -n1)"
    [ -n "$bin_path" ] || err "Could not find 'bifrost' inside ${tarball}."

    install_file "$bin_path" "${install_dir}/bifrost"
    rm -rf "$tmp"
    log "    installed: ${install_dir}/bifrost"
}

# ---- brokk-acp install --------------------------------------------------

install_brokk_acp() {
    local os="$1" arch="$2" install_dir="$3" version="$4"
    local asset url tmp

    asset="$(acp_asset_name "$os" "$arch")"
    url="https://github.com/${BROKK_REPO}/releases/download/${ACP_TAG_PREFIX}${version}/${asset}"

    log "==> Downloading brokk-acp ${version} (${os}-${arch})"
    tmp="$(mktemp -d)"
    if ! download "$url" "${tmp}/${asset}"; then
        rm -rf "$tmp"
        err "Failed to download ${url}"
    fi

    install_file "${tmp}/${asset}" "${install_dir}/brokk-acp"
    rm -rf "$tmp"
    log "    installed: ${install_dir}/brokk-acp"
}

# ---- PATH helper --------------------------------------------------------

ensure_on_path() {
    local install_dir="$1"
    case ":${PATH}:" in
        *":${install_dir}:"*)
            log "PATH already contains ${install_dir}"
            return 0
            ;;
    esac

    local shell_name rc line
    shell_name="$(basename "${SHELL:-/bin/sh}")"
    case "$shell_name" in
        bash) rc="${HOME}/.bashrc" ;;
        zsh)  rc="${ZDOTDIR:-$HOME}/.zshrc" ;;
        fish) rc="${HOME}/.config/fish/config.fish" ;;
        *)    rc="${HOME}/.profile" ;;
    esac

    if [ "$shell_name" = "fish" ]; then
        line="set -gx PATH \"${install_dir}\" \$PATH"
    else
        line="export PATH=\"${install_dir}:\$PATH\""
    fi

    # Idempotency: only append if the directory isn't already referenced in the rc.
    if [ -f "$rc" ] && grep -Fq "$install_dir" "$rc"; then
        log "PATH entry already present in ${rc}"
        return 0
    fi

    mkdir -p "$(dirname "$rc")"
    {
        printf '\n# Added by brokk installer\n'
        printf '%s\n' "$line"
    } >> "$rc"
    log "Appended PATH entry to ${rc}"
    log "Restart your shell or run:  source ${rc}"
}

# ---- main ---------------------------------------------------------------

main() {
    local install_dir="${BROKK_INSTALL_DIR:-$DEFAULT_INSTALL_DIR}"
    local bifrost_version="${BIFROST_VERSION:-}"
    local acp_version="${BROKK_ACP_VERSION:-}"
    local skip_path="false"
    local skip_verify="false"

    while [ $# -gt 0 ]; do
        case "$1" in
            --install-dir)        install_dir="${2:?--install-dir requires a value}"; shift 2 ;;
            --install-dir=*)      install_dir="${1#*=}"; shift ;;
            --bifrost-version)    bifrost_version="${2:?--bifrost-version requires a value}"; shift 2 ;;
            --bifrost-version=*)  bifrost_version="${1#*=}"; shift ;;
            --acp-version)        acp_version="${2:?--acp-version requires a value}"; shift 2 ;;
            --acp-version=*)      acp_version="${1#*=}"; shift ;;
            --skip-path)          skip_path="true"; shift ;;
            --no-verify)          skip_verify="true"; shift ;;
            -h|--help)            usage; exit 0 ;;
            -V|--version)         printf '%s\n' "${INSTALLER_VERSION}"; exit 0 ;;
            *) err "Unknown argument: $1 (use --help)" ;;
        esac
    done

    local platform os arch
    platform="$(detect_platform)"
    os="${platform% *}"
    arch="${platform#* }"
    log "Detected platform:  ${os} ${arch}"

    [ -n "$bifrost_version" ] || bifrost_version="$(get_latest_tag "$BIFROST_REPO")"

    if [ -z "$acp_version" ]; then
        local acp_tag
        acp_tag="$(get_latest_prefixed_tag "$BROKK_REPO" "$ACP_TAG_PREFIX")"
        acp_version="${acp_tag#"${ACP_TAG_PREFIX}"}"
    fi

    log "bifrost version:    ${bifrost_version}"
    log "brokk-acp version:  ${acp_version}"
    log "Install directory:  ${install_dir}"
    log

    mkdir -p "$install_dir"

    install_bifrost   "$os" "$arch" "$install_dir" "$bifrost_version"
    install_brokk_acp "$os" "$arch" "$install_dir" "$acp_version"

    if [ "$skip_path" = "false" ]; then
        ensure_on_path "$install_dir"
    fi

    if [ "$skip_verify" = "false" ]; then
        log
        log "==> Verifying installs"
        if ! "${install_dir}/bifrost" --version; then
            warn "bifrost --version failed"
        fi
        if ! "${install_dir}/brokk-acp" --version; then
            warn "brokk-acp --version failed"
        fi
    fi

    log
    log "Done. Binaries installed to: ${install_dir}"
}

main "$@"
